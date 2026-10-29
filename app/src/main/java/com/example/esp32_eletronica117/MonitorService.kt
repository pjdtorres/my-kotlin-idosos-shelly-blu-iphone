package com.example.esp32_eletronica117

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serviço em primeiro plano (Foreground Service): faz o trabalho todo
 * (MQTT, GPS e acelerómetro) e CONTINUA a correr com a app escondida
 * ou com o ecrã desligado. Mostra uma notificação fixa enquanto corre.
 *
 * A Activity só mostra os valores: liga os ouvintes ui* no onStart()
 * e desliga-os no onStop().
 */
class MonitorService : Service() {

    companion object {
        const val ACAO_PARAR = "com.example.esp32_eletronica117.PARAR"
        const val PREFS = "config"
        const val NOME_POR_OMISSAO = "Telemóvel"
        private const val CHAVE_NOME = "nome"
        private const val CHAVE_MQTT_DESLIGADO = "mqtt_desligado"
        private const val CHAVE_INTERVALO = "intervalo_grafico_ms"

        /** Limites do intervalo de leitura da página "Gráficos" (ms), iguais aos da página. */
        const val INTERVALO_MIN_MS = 10L
        const val INTERVALO_MAX_MS = 1000L
        const val INTERVALO_POR_OMISSAO_MS = 100L

        private const val CANAL = "monitorizacao"
        private const val ID_NOTIFICACAO = 1

        // Com o telemóvel parado o GPS quase não dá posições novas:
        // reenvia a última de 10 em 10 s para o marcador não desaparecer do mapa.
        private const val REENVIO_GPS_MS = 10_000L

        // ---- SMS automático por inclinação (ajustável) ----
        // Inclinação medida EM RELAÇÃO À POSIÇÃO DE REFERÊNCIA (botão "Definir referência")
        private const val INCLINACAO_ALERTA = 40f          // > 40° -> SMS de alerta; < 40° -> "Está tudo bem!"
        private const val TEMPO_CONFIRMAR_MS = 1_000L      // tem de ficar 1 s acima/abaixo (ignora abanões)
        // ---- SMS de bateria fraca (ajustável) ----
        private const val BATERIA_FRACA = 20          // envia SMS quando a carga fica em 20% ou menos
        private const val BATERIA_REARMAR = 30        // volta a ficar pronto depois de carregar acima disto
        private const val CHAVE_BATERIA_AVISADA = "bateria_avisada"

        private const val ESPERA_REF_AUTO_MS = 3_000L     // sem referência: define-a sozinho ao fim de 3 s

        private const val JANELA_QUEDA_MS = 10_000L        // queda nos últimos 10 s -> "QUEDA DETETADA"

        // ---- Botão Shelly BLU Button1 (ajustável) ----
        //  1 carregão / carregão longo / manter -> SMS "PEDIDO DE AJUDA" (com morada)
        //  2 carregões                          -> SMS "Está tudo bem!" (cancela o pedido)
        //  3 carregões                          -> só MQTT
        // Todos os carregões vão para o MQTT (esp32-udemy/telemovel/botao).
        private const val INTERVALO_SMS_BOTAO_MS = 20_000L  // no máx. 1 SMS de ajuda a cada 20 s

        /** Serviço em execução (null se estiver parado). */
        @Volatile
        var atual: MonitorService? = null
            private set

        /** Arranca o serviço (ou, se já estiver a correr, volta a verificar o GPS). */
        fun iniciar(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
        }

        /** Nome guardado; se ainda não houver, "Telemóvel". */
        fun lerNome(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(CHAVE_NOME, null)
                ?.takeIf { it.isNotBlank() }
                ?: NOME_POR_OMISSAO

        /** Intervalo de leitura guardado (ms), o mesmo da página "Gráficos". */
        fun lerIntervalo(context: Context): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(CHAVE_INTERVALO, INTERVALO_POR_OMISSAO_MS)
                .coerceIn(INTERVALO_MIN_MS, INTERVALO_MAX_MS)

        fun guardarIntervalo(context: Context, ms: Long) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(CHAVE_INTERVALO, ms).apply()
        }

        fun guardarNome(context: Context, nome: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CHAVE_NOME, nome).apply()
        }
    }

    // ---- Ouvintes do ecrã (null quando a app está escondida) ----
    var uiEstado: ((estado: String) -> Unit)? = null                               // thread do MQTT
    var uiEnvio: ((topico: String, texto: String, total: Int) -> Unit)? = null     // thread do MQTT
    var uiAcel: ((x: Float, y: Float, z: Float, inclinacao: Float, yaw: Float) -> Unit)? = null  // thread do ecrã
    var uiGps: ((lat: Double, lon: Double, precisao: Float, fonte: String) -> Unit)? = null      // thread do ecrã
    var uiQueda: (() -> Unit)? = null                                              // thread do ecrã
    var uiZero: (() -> Unit)? = null                                               // thread do ecrã
    var uiIntervalo: ((ms: Int) -> Unit)? = null
    var uiBateria: ((percentagem: Int, aCarregar: Boolean) -> Unit)? = null        // thread do ecrã
    var uiBotao: ((estado: String) -> Unit)? = null                                // thread do ecrã

    @Volatile
    var estadoMqtt = "MQTT desligado"
        private set

    lateinit var mqtt: MqttEnvio
        private set
    private lateinit var acelerometro: Acelerometro
    private lateinit var gps: Gps
    private lateinit var botao: ShellyBlu

    private var iniciado = false
    private var gpsLigado = false
    private var botaoLigado = false
    private var ultimoSmsBotao = 0L

    /** Estado do botão Shelly (para o ecrã mostrar ao abrir). */
    val estadoBotao: String
        get() = botao.estado
    @Volatile private var emPrimeiroPlano = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val handler = Handler(Looper.getMainLooper())

    // Última posição GPS (vai no SMS) e estado do alerta de inclinação
    private var ultimaLat = Double.NaN
    private var ultimaLon = Double.NaN
    private var mudouDesde = 0L              // desde quando está do "outro lado" dos 40° (0 = não mudou)
    private var emAlerta = false             // true depois do SMS de alerta, até enviar "Está tudo bem!"
    private var ultimaQueda = 0L
    private var inicioLeituras = 0L          // para a referência automática

    // ---- Bateria: o Android avisa sempre que a carga muda ----
    @Volatile var bateria = -1            // % (-1 = ainda não se sabe)
        private set
    @Volatile var aCarregar = false
        private set
    private var bateriaLigada = false
    private val recetorBateria = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = verificarBateria(i)
    }
    private val reenvioGps = object : Runnable {
        override fun run() {
            mqtt.reenviarGps()
            handler.postDelayed(this, REENVIO_GPS_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Erros.instalar(this)
        atual = this
        criarCanal()

        // ---- MQTT ----
        mqtt = MqttEnvio { estado ->
            estadoMqtt = estado
            atualizarNotificacao()
            uiEstado?.invoke(estado)
        }
        mqtt.ouvinteEnvio = { topico, texto, total -> uiEnvio?.invoke(topico, texto, total) }
        mqtt.ouvinteZero = { handler.post { definirReferencia() } }
        // A página mudou o intervalo: guarda-o para a app mostrar o mesmo valor
        mqtt.ouvinteIntervalo = { ms ->
            guardarIntervalo(this, ms.toLong())
            uiIntervalo?.invoke(ms)
        }
        mqtt.deviceId = idTelemovel()
        mqtt.nome = lerNome(this)
        mqtt.definirIntervaloGrafico(lerIntervalo(this))

        // ---- GPS ----
        gps = Gps(this) { lat, lon, precisao, fonte ->
            ultimaLat = lat
            ultimaLon = lon
            mqtt.enviarGps(lat, lon, precisao, fonte)
            uiGps?.invoke(lat, lon, precisao, fonte)
        }

        // ---- Acelerómetro ----
        acelerometro = Acelerometro(this) { x, y, z, inclinacao ->
            val horizontal = inclinacao < 15 || inclinacao > 165
            val yaw = acelerometro.yaw
            verificarInclinacao(acelerometro.inclinacaoReferencia)
            mqtt.enviarDados(x, y, z, inclinacao, acelerometro.rotacao, horizontal)
            mqtt.enviarGrafico(x, y, z, acelerometro.roll, acelerometro.pitch, yaw)
            uiAcel?.invoke(x, y, z, inclinacao, yaw)
        }
        acelerometro.ouvinteQueda = {
            mqtt.enviarQueda()
            ultimaQueda = SystemClock.elapsedRealtime()   // o SMS de inclinação diz "QUEDA DETETADA"
            uiQueda?.invoke()
        }

        // ---- Botão Shelly BLU Button1 (Bluetooth) ----
        botao = ShellyBlu(this) { evento -> tratarBotao(evento) }
        botao.ouvinteEstado = { texto -> uiBotao?.invoke(texto) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACAO_PARAR) {
            pararTudo()
            return START_NOT_STICKY
        }

        if (!passarParaPrimeiroPlano()) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!iniciado) {
            iniciado = true
            // Mantém o processador acordado com o ecrã desligado (senão os sensores param)
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "idosos:monitorizacao")
                .apply { setReferenceCounted(false); acquire() }

            if (!mqttDesligadoPeloUtilizador()) mqtt.ligar()
            acelerometro.iniciar()
            ContextCompat.registerReceiver(
                this, recetorBateria, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            bateriaLigada = true
            handler.postDelayed(reenvioGps, REENVIO_GPS_MS)
        }

        // Pode ser chamado várias vezes: liga o GPS assim que houver autorização / Localização ligada
        if (!gpsLigado && gps.temPermissao() && gps.ligadoNoTelemovel()) {
            gpsLigado = gps.iniciar()
        }
        // Idem para o botão Shelly: começa a escutar assim que houver autorização de Bluetooth
        if (!botaoLigado) botaoLigado = botao.iniciar()

        return START_STICKY   // se o sistema matar o serviço, volta a arrancá-lo
    }

    /**
     * A posição atual do telemóvel passa a ser a posição de repouso (roll 0, pitch 0, yaw 0).
     * Chamado pelo botão "Definir referência" da app ou pelo ZERO da página /app.
     */
    fun definirReferencia() {
        acelerometro.definirReferencia()
        mqtt.enviarLeituraJa()   // a página recebe já a leitura com os zeros
        uiZero?.invoke()
    }

    override fun onDestroy() {
        libertar()
        atual = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Inclinação em relação à posição de referência (NaN se ainda não houver referência). */
    val inclinacaoReferencia: Float
        get() = acelerometro.inclinacaoReferencia

    // ---- Ordens vindas do ecrã ----

    fun ligarMqtt() {
        guardarMqttDesligado(false)
        mqtt.ligar()
    }

    fun desligarMqtt() {
        guardarMqttDesligado(true)
        mqtt.desligar()
    }

    fun mudarNome(nome: String) {
        mqtt.nome = nome
        mqtt.reenviarGps()   // o mapa passa logo a mostrar o novo nome
        atualizarNotificacao()
    }

    /** Botão "Associar" da app: o próximo botão Shelly carregado (30 s) fica associado. */
    fun associarBotao() {
        if (!botaoLigado) botaoLigado = botao.iniciar()
        botao.associar()
    }

    /** Esquece todos os botões Shelly associados. */
    fun esquecerBotoes() = botao.esquecer()

    /** A app mudou o intervalo: aplica-o e avisa a página "Gráficos". */
    fun mudarIntervalo(ms: Long) {
        mqtt.definirIntervaloGrafico(ms)
    }

    // ---- SMS automático (inclinação > 40°) ----

    /**
     * Chamado a cada leitura do acelerómetro com a inclinação EM RELAÇÃO À REFERÊNCIA.
     *  - passa dos 40° (durante 1 s)        -> SMS de ALERTA
     *  - volta para baixo dos 40° (durante 1 s) -> SMS "ALERTA: Está tudo bem!"
     * E assim sucessivamente, sempre que muda de lado.
     */
    private fun verificarInclinacao(inclinacao: Float) {
        val agora = SystemClock.elapsedRealtime()
        if (inclinacao.isNaN()) {
            // Ainda sem referência (app recém-instalada): passados 3 s, a posição atual
            // passa a ser a referência. Pode ser mudada a qualquer momento em "Definir referência".
            if (inicioLeituras == 0L) inicioLeituras = agora
            else if (agora - inicioLeituras >= ESPERA_REF_AUTO_MS) {
                inicioLeituras = 0L
                handler.post { definirReferencia() }
            }
            return
        }
        inicioLeituras = 0L
        val acima = inclinacao > INCLINACAO_ALERTA

        if (acima == emAlerta) {          // continua do mesmo lado: nada a fazer
            mudouDesde = 0L
            return
        }
        if (mudouDesde == 0L) mudouDesde = agora
        if (agora - mudouDesde < TEMPO_CONFIRMAR_MS) return

        // Mudou de lado há mais de 1 s
        mudouDesde = 0L
        emAlerta = acima
        try {
            if (acima) enviarSmsAutomatico(inclinacao) else enviarSmsTudoBem(inclinacao)
        } catch (e: Throwable) {
            avisar("Erro ao enviar SMS: ${Erros.curto(e)}")
        }
    }

    // ---- Bateria ----

    /**
     * Chamado sempre que a carga muda: atualiza o ecrã e o MQTT e, com 20% ou menos
     * (sem estar a carregar), envia 1 SMS "BATERIA FRACA". Só volta a avisar depois de
     * o telemóvel carregar acima de 30%. O aviso fica guardado, para não repetir o SMS
     * se a app ou o telemóvel reiniciarem.
     */
    private fun verificarBateria(i: Intent) {
        val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val escala = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (nivel < 0 || escala <= 0) return
        val percentagem = nivel * 100 / escala
        val estado = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val carregar = estado == BatteryManager.BATTERY_STATUS_CHARGING ||
                estado == BatteryManager.BATTERY_STATUS_FULL

        bateria = percentagem
        aCarregar = carregar
        mqtt.bateria = percentagem
        mqtt.aCarregar = carregar
        uiBateria?.invoke(percentagem, carregar)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(CHAVE_BATERIA_AVISADA, false)) {
            if (percentagem > BATERIA_REARMAR) prefs.edit().putBoolean(CHAVE_BATERIA_AVISADA, false).apply()
            return
        }
        if (carregar || percentagem > BATERIA_FRACA) return

        val numero = SmsEnvio.lerNumero(this)
        if (numero.isEmpty() || !SmsEnvio.temPermissao(this)) return
        prefs.edit().putBoolean(CHAVE_BATERIA_AVISADA, true).apply()

        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val texto = "BATERIA FRACA: $percentagem%\n${mqtt.nome} (às $hora)\n" +
                "Lembre-se de carregar o telemóvel."
        avisar("Bateria $percentagem%: a enviar SMS para $numero…")
        SmsEnvio.enviar(this, numero, texto) { resultado -> avisar(resultado) }
    }

    /** SMS "ALERTA: Está tudo bem!" quando a inclinação volta para baixo dos 40°. */
    private fun enviarSmsTudoBem(inclinacao: Float) {
        val numero = SmsEnvio.lerNumero(this)
        if (numero.isEmpty() || !SmsEnvio.temPermissao(this)) return
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val texto = "ALERTA: Está tudo bem!\n${mqtt.nome} (às $hora)"
        avisar("Inclinação %.0f°: a enviar \"Está tudo bem!\" para %s…".format(inclinacao, numero))
        SmsEnvio.enviar(this, numero, texto) { resultado -> avisar(resultado) }
    }

    /**
     * Envia o SMS para o número guardado na app (campo "Telemóvel p/ SMS").
     * Funciona com a app escondida / ecrã desligado.
     */
    private fun enviarSmsAutomatico(inclinacao: Float) {
        val numero = SmsEnvio.lerNumero(this)
        if (numero.isEmpty()) {
            avisar("Inclinação %.0f°: SMS não enviado (falta o número)".format(inclinacao))
            return
        }
        if (!SmsEnvio.temPermissao(this)) {
            avisar("Inclinação %.0f°: SMS não enviado (falta autorização de SMS)".format(inclinacao))
            return
        }
        val agora = SystemClock.elapsedRealtime()

        var lat = ultimaLat
        var lon = ultimaLon
        if (lat.isNaN()) ultimaPosicaoConhecida()?.let { lat = it.first; lon = it.second }

        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        avisar("Inclinação %.0f°: a enviar SMS para %s…".format(inclinacao, numero))
        // Procura a morada (no máx. 8 s) e envia; sem morada, vai o link do mapa
        SmsEnvio.enviarAlerta(
            this, numero, mqtt.nome, lat, lon, moradaConhecida = "",
            hora = hora, titulo = "ALERTA", caiu = true
        ) { resultado -> avisar(resultado) }
    }

    // ---- Botão Shelly BLU Button1 ----

    /** Chamado (thread do ecrã) por cada carregão num botão associado. */
    private fun tratarBotao(evento: ShellyBlu.Evento) {
        mqtt.enviarBotao(evento.mac, evento.descricao, evento.bateria, evento.rssi)
        try {
            when (evento.tipo) {
                ShellyBlu.BOTAO_CARREGAR, ShellyBlu.BOTAO_LONGO,
                ShellyBlu.BOTAO_MANTER, ShellyBlu.BOTAO_MANTER_ANTIGO -> enviarSmsPedidoAjuda()
                ShellyBlu.BOTAO_DUPLO -> enviarSmsBotaoTudoBem()
                else -> avisar("Botão Shelly: ${evento.descricao} (enviado por MQTT)")
            }
        } catch (e: Throwable) {
            avisar("Erro ao enviar SMS: ${Erros.curto(e)}")
        }
    }

    /** SMS "PEDIDO DE AJUDA: nome (às hh:mm:ss)" + morada (ou link do mapa). */
    private fun enviarSmsPedidoAjuda() {
        val agora = SystemClock.elapsedRealtime()
        if (ultimoSmsBotao != 0L && agora - ultimoSmsBotao < INTERVALO_SMS_BOTAO_MS) {
            avisar("Botão Shelly: pedido de ajuda já enviado há pouco")
            return
        }
        val numero = SmsEnvio.lerNumero(this)
        if (numero.isEmpty()) {
            avisar("Botão Shelly: SMS não enviado (falta o número)")
            return
        }
        if (!SmsEnvio.temPermissao(this)) {
            avisar("Botão Shelly: SMS não enviado (falta autorização de SMS)")
            return
        }
        ultimoSmsBotao = agora

        var lat = ultimaLat
        var lon = ultimaLon
        if (lat.isNaN()) ultimaPosicaoConhecida()?.let { lat = it.first; lon = it.second }
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        avisar("Botão Shelly: a enviar pedido de ajuda para $numero…")
        SmsEnvio.enviarAlerta(
            this, numero, mqtt.nome, lat, lon, moradaConhecida = "",
            hora = hora, titulo = "PEDIDO DE AJUDA"
        ) { resultado -> avisar(resultado) }
    }

    /** 2 carregões: SMS "Está tudo bem!" (cancela o pedido de ajuda). */
    private fun enviarSmsBotaoTudoBem() {
        val numero = SmsEnvio.lerNumero(this)
        if (numero.isEmpty() || !SmsEnvio.temPermissao(this)) {
            avisar("Botão Shelly: SMS não enviado (falta o número ou a autorização)")
            return
        }
        ultimoSmsBotao = 0L   // um novo carregão volta a pedir ajuda logo
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val texto = "Está tudo bem! (cancelado no botão)\n${mqtt.nome} (às $hora)"
        avisar("Botão Shelly: a enviar \"Está tudo bem!\" para $numero…")
        SmsEnvio.enviar(this, numero, texto) { resultado -> avisar(resultado) }
    }

    /** Última posição que o Android conhece (se o GPS ainda não deu nenhuma). */
    @SuppressLint("MissingPermission")
    private fun ultimaPosicaoConhecida(): Pair<Double, Double>? {
        if (!gps.temPermissao()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    }

    private fun avisar(texto: String) {
        handler.post { Toast.makeText(applicationContext, texto, Toast.LENGTH_LONG).show() }
    }

    // ---- Interno ----

    private fun passarParaPrimeiroPlano(): Boolean {
        val notificacao = criarNotificacao()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                var tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (gps.temPermissao()) tipo = tipo or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                try {
                    startForeground(ID_NOTIFICACAO, notificacao, tipo)
                } catch (e: SecurityException) {
                    // Sem autorização de localização neste momento: continua sem o tipo "location"
                    startForeground(ID_NOTIFICACAO, notificacao, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                }
            } else {
                startForeground(ID_NOTIFICACAO, notificacao)
            }
            emPrimeiroPlano = true
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun pararTudo() {
        emPrimeiroPlano = false
        libertar()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun libertar() {
        if (!iniciado) return
        iniciado = false
        handler.removeCallbacks(reenvioGps)
        acelerometro.parar()
        if (bateriaLigada) {
            try { unregisterReceiver(recetorBateria) } catch (_: Exception) { }
            bateriaLigada = false
        }
        gps.parar()
        gpsLigado = false
        botao.parar()
        botaoLigado = false
        mqtt.desligar()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(CANAL, "Monitorização", NotificationManager.IMPORTANCE_LOW)
            canal.description = "Mantém o envio de dados ligado"
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
        }
    }

    private fun criarNotificacao(): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val parar = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACAO_PARAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Monitorização ativa · ${mqtt.nome}")
            .setContentText(estadoMqtt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .addAction(0, "Parar", parar)
            .build()
    }

    private fun atualizarNotificacao() {
        if (!emPrimeiroPlano) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(ID_NOTIFICACAO, criarNotificacao())
    }

    private fun mqttDesligadoPeloUtilizador(): Boolean =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(CHAVE_MQTT_DESLIGADO, false)

    private fun guardarMqttDesligado(desligado: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(CHAVE_MQTT_DESLIGADO, desligado).apply()
    }

    /**
     * Identificador único deste telemóvel, ex.: "telemovel-3fa9c1".
     * Não muda quando se muda o nome, por isso o marcador no mapa é sempre o mesmo.
     */
    @SuppressLint("HardwareIds")
    private fun idTelemovel(): String {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        return "telemovel-" + androidId.takeLast(6).ifEmpty { "0" }
    }
}
