package com.example.esp32_eletronica117

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Html
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Ecrã da app. O trabalho (MQTT, GPS e acelerómetro) é feito pelo [MonitorService],
 * que continua a enviar com a app escondida ou o ecrã desligado.
 * Aqui só se mostram os valores e se dão ordens ao serviço.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TOPICO_RAPIDO = "esp32/acelerometro"
        private const val CHAVE_PEDIU_BATERIA = "pediu_bateria"
    }

    private lateinit var editNome: EditText
    private lateinit var buttonGuardarNome: Button
    private lateinit var editIntervalo: EditText
    private lateinit var buttonIntervalo: Button
    private lateinit var editTelefone: EditText
    private lateinit var buttonGuardarTelefone: Button
    private lateinit var buttonSms: Button

    private lateinit var button: Button
    private lateinit var buttonReferencia: Button
    private lateinit var textView2: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var textAcel: TextView
    private lateinit var textXyz: TextView
    private lateinit var textMqtt: TextView
    private lateinit var textGps: TextView
    private lateinit var textBateria: TextView
    private lateinit var textBotao: TextView
    private lateinit var buttonAssociar: Button
    private lateinit var buttonEsquecer: Button

    private var estadoMqtt = "MQTT: -"

    // Última mensagem enviada por tópico (ordem de chegada); o tópico rápido fica no fim
    private val ultimasMensagens = LinkedHashMap<String, String>()

    @Volatile
    private var linhaIntervalo = ""   // aparece depois de "Aplicar intervalo"

    private val handler = Handler(Looper.getMainLooper())
    private var servico: MonitorService? = null
    private var visivel = false

    // ---- Morada (Geocoder do Android, a partir das coordenadas GPS) ----
    private var linhaGps = ""
    private var latAtual = Double.NaN   // última posição GPS (para o SMS)
    private var lonAtual = Double.NaN
    private var morada = ""
    private var latMorada = Double.NaN
    private var lonMorada = Double.NaN
    private var tempoTentativaMorada = 0L
    private var aPedirMorada = false
    private val executorMorada = Executors.newSingleThreadExecutor()

    // Pede localização (+ notificações no Android 13+); depois volta a chamar o serviço,
    // que passa a usar o GPS se a localização tiver sido autorizada
    private val pedirPermissoes =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            MonitorService.iniciar(this)
            mostrarAvisoGps()
            pedirSemLimiteBateria()
        }

    // Pede autorização para enviar SMS (só na 1.ª vez que se carrega em "ALERTA!")
    private val pedirPermissaoSms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val autorizado = SmsEnvio.temPermissao(this)
            if (autorizado) clicarSms()
            else Toast.makeText(this, "Sem autorização para enviar SMS", Toast.LENGTH_LONG).show()
        }

    // Ao guardar o número: pede já a autorização de SMS, para o SMS de queda funcionar
    private val pedirPermissaoSmsQueda =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (!SmsEnvio.temPermissao(this)) Toast.makeText(
                this, "Sem autorização de SMS: as quedas não vão ser avisadas por SMS",
                Toast.LENGTH_LONG
            ).show()
        }

    // Autorização "Dispositivos próximos" (Bluetooth), pedida ao carregar em "Associar botão"
    private val pedirPermissaoBotao =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (ShellyBlu.temPermissao(this)) associarBotao()
            else Toast.makeText(
                this, "Sem autorização \"Dispositivos próximos\": o botão Shelly não funciona",
                Toast.LENGTH_LONG
            ).show()
        }

    private fun hora(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Erros.instalar(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            insets
        }

        button = findViewById(R.id.BotonLayout)
        buttonReferencia = findViewById(R.id.ReferenciaLayout)
        textView2 = findViewById(R.id.Texto2Layout)
        seekBar = findViewById(R.id.BarLayout)
        textAcel = findViewById(R.id.AcelLayout)
        textXyz = findViewById(R.id.XyzLayout)
        textMqtt = findViewById(R.id.MqttLayout)
        textGps = findViewById(R.id.GpsLayout)
        textBateria = findViewById(R.id.BateriaLayout)
        editNome = findViewById(R.id.NomeLayout)
        buttonGuardarNome = findViewById(R.id.GuardarNomeLayout)
        editIntervalo = findViewById(R.id.IntervaloLayout)
        buttonIntervalo = findViewById(R.id.AplicarIntervaloLayout)
        editTelefone = findViewById(R.id.TelefoneLayout)
        buttonGuardarTelefone = findViewById(R.id.GuardarTelefoneLayout)
        buttonSms = findViewById(R.id.SmsLayout)

        // ---- Nome da pessoa ----
        val nome = MonitorService.lerNome(this)
        editNome.setText(if (nome == MonitorService.NOME_POR_OMISSAO) "" else nome)
        buttonGuardarNome.setOnClickListener { guardarNome() }
        // Também guarda ao sair do campo (sem ser preciso carregar em Guardar)
        editNome.setOnFocusChangeListener { _, temFoco -> if (!temFoco) guardarNome(aviso = false) }
        editNome.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE) { guardarNome(); true } else false
        }

        // ---- Intervalo de envio dos dados (ms) ----
        editIntervalo.setText(MonitorService.lerIntervalo(this).toString())
        buttonIntervalo.setOnClickListener { aplicarIntervalo() }
        editIntervalo.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE) { aplicarIntervalo(); true } else false
        }

        // ---- Número para SMS + botão "ALERTA!" ----
        editTelefone.setText(SmsEnvio.lerNumero(this))
        buttonGuardarTelefone.setOnClickListener { guardarTelefone() }
        editTelefone.setOnFocusChangeListener { _, temFoco -> if (!temFoco) guardarTelefone(aviso = false) }
        editTelefone.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE) { guardarTelefone(); true } else false
        }
        buttonSms.setOnClickListener { clicarSms() }

        // ---- Botão Shelly BLU Button1 ----
        textBotao = findViewById(R.id.BotaoShellyLayout)
        buttonAssociar = findViewById(R.id.AssociarBotaoLayout)
        buttonEsquecer = findViewById(R.id.EsquecerBotaoLayout)
        buttonAssociar.setOnClickListener { associarBotao() }
        buttonEsquecer.setOnClickListener {
            if (ShellyBlu.lerMacs(this).isEmpty()) {
                Toast.makeText(this, "Não há botões associados", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Esquecer botões Shelly?")
                .setMessage("Os botões associados deixam de enviar alertas até serem associados de novo.")
                .setPositiveButton("Esquecer") { _, _ -> MonitorService.atual?.esquecerBotoes() }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        val sensores = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sensores.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            textXyz.text = "Sem acelerómetro"
        }

        // Barra de inclinação: 0° (deitado, face para cima) .. 180° (face para baixo).
        // Segue o acelerómetro; o dedo não a mexe.
        seekBar.max = 180
        seekBar.setOnTouchListener { _, _ -> true }

        // Botão "Definir referência": a posição atual passa a ser a posição de repouso
        buttonReferencia.setOnClickListener {
            val s = MonitorService.atual
            if (s == null) {
                Toast.makeText(this, "Serviço ainda a arrancar, tente de novo", Toast.LENGTH_SHORT).show()
            } else {
                s.definirReferencia()
            }
        }

        // Botão Ligar / Desligar MQTT
        atualizarBotaoMqtt("MQTT desligado")
        button.setOnClickListener {
            val s = MonitorService.atual ?: return@setOnClickListener
            if (s.mqtt.estaLigado()) s.desligarMqtt() else s.ligarMqtt()
        }

        if (savedInstanceState == null) pedirPermissoesEmFalta()

        // Se a app crashou da última vez, mostra o erro (para o enviar ao programador)
        Erros.lerEApagar(this)?.let { erro ->
            val texto = TextView(this).apply {
                text = erro
                setTextIsSelectable(true)
                textSize = 11f
                setPadding(40, 20, 40, 20)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("A app fechou por causa deste erro")
                .setView(android.widget.ScrollView(this).apply { addView(texto) })
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onStart() {
        super.onStart()
        visivel = true
        MonitorService.iniciar(this)   // arranca o serviço (se já estiver a correr, não faz mal)
        mostrarAvisoGps()
        ligarUi()
    }

    override fun onStop() {
        super.onStop()
        visivel = false
        guardarNome(aviso = false)   // não perder um nome escrito mas não guardado
        guardarTelefone(aviso = false)
        desligarUi()
        // NÃO pára nada: o serviço continua a enviar com a app escondida
    }

    // ---- Ligação ao serviço ----

    /** Liga os ouvintes do serviço ao ecrã (espera que o serviço arranque). */
    private fun ligarUi() {
        if (!visivel) return
        val s = MonitorService.atual
        if (s == null) {
            handler.postDelayed({ ligarUi() }, 200)
            return
        }
        servico = s

        s.uiEstado = { estado -> runOnUiThread { mostrarEstado(estado) } }
        s.uiEnvio = { topico, texto, total -> runOnUiThread { mostrarEnvio(topico, texto, total) } }
        s.uiAcel = { x, y, z, inclinacao, yaw -> mostrarAcel(x, y, z, inclinacao, yaw) }
        s.uiGps = { lat, lon, precisao, fonte ->
            latAtual = lat
            lonAtual = lon
            linhaGps = "GPS: %.6f, %.6f<br>±%.0f m (%s) às %s"
                .format(Locale.US, lat, lon, precisao, fonte, hora())
            mostrarGps()
            pedirMorada(lat, lon)
        }
        s.uiQueda = { Toast.makeText(this, "Queda detetada!", Toast.LENGTH_LONG).show() }
        s.uiZero = { Toast.makeText(this, "Posição de repouso definida", Toast.LENGTH_SHORT).show() }
        s.uiIntervalo = { ms ->
            linhaIntervalo = "<br><font color='#008800'>Intervalo de leitura: %d ms (%.2f Hz)</font>"
                .format(Locale.getDefault(), ms, 1000.0 / ms)
            runOnUiThread {
                // Mudado na página "Gráficos": a app passa a mostrar o mesmo valor
                if (!editIntervalo.hasFocus()) editIntervalo.setText(ms.toString())
                Toast.makeText(this, "Intervalo alterado para $ms ms", Toast.LENGTH_SHORT).show()
            }
        }

        s.uiBateria = { percentagem, aCarregar -> mostrarBateria(percentagem, aCarregar) }
        if (s.bateria >= 0) mostrarBateria(s.bateria, s.aCarregar)

        s.uiBotao = { estado -> mostrarBotao(estado) }
        mostrarBotao(s.estadoBotao)

        mostrarEstado(s.estadoMqtt)
        if (!editIntervalo.hasFocus()) editIntervalo.setText(MonitorService.lerIntervalo(this).toString())
    }

    private fun desligarUi() {
        handler.removeCallbacksAndMessages(null)
        servico?.apply {
            uiEstado = null
            uiEnvio = null
            uiAcel = null
            uiGps = null
            uiQueda = null
            uiZero = null
            uiIntervalo = null
            uiBateria = null
            uiBotao = null
        }
        servico = null
    }

    override fun onDestroy() {
        super.onDestroy()
        executorMorada.shutdownNow()
    }

    // ---- GPS + morada ----

    /** Dados de GPS e, a seguir, a morada a verde carregado. */
    private fun mostrarGps() {
        val texto = if (morada.isEmpty()) linhaGps
        else "$linhaGps<br><font color='#006400'>${Html.escapeHtml(morada)}</font>"
        textGps.text = Html.fromHtml(texto, Html.FROM_HTML_MODE_LEGACY)
    }

    /**
     * Pede a morada ao Geocoder numa thread à parte (precisa de Internet).
     * Só volta a pedir se o telemóvel andou mais de 25 m ou passou 1 minuto,
     * e nunca mais do que 1 pedido a cada 10 s.
     */
    private fun pedirMorada(lat: Double, lon: Double) {
        if (!Geocoder.isPresent() || aPedirMorada) return
        val agora = System.currentTimeMillis()
        if (agora - tempoTentativaMorada < 10_000) return
        if (!latMorada.isNaN()) {
            val distancia = FloatArray(1)
            Location.distanceBetween(latMorada, lonMorada, lat, lon, distancia)
            if (distancia[0] < 25f && agora - tempoTentativaMorada < 60_000) return
        }
        aPedirMorada = true
        tempoTentativaMorada = agora
        executorMorada.execute {
            val texto = try {
                @Suppress("DEPRECATION")
                Geocoder(this, Locale.forLanguageTag("pt-PT"))
                    .getFromLocation(lat, lon, 1)
                    ?.firstOrNull()
                    ?.let { formatarMorada(it) }
            } catch (e: Exception) {
                null   // sem Internet ou serviço indisponível: tenta mais tarde
            }
            runOnUiThread {
                aPedirMorada = false
                if (!texto.isNullOrBlank()) {
                    morada = texto
                    latMorada = lat
                    lonMorada = lon
                }
                if (visivel && linhaGps.isNotEmpty()) mostrarGps()
            }
        }
    }

    private fun formatarMorada(a: Address): String =
        a.getAddressLine(0)
            ?: listOfNotNull(a.thoroughfare, a.subThoroughfare, a.postalCode, a.locality)
                .joinToString(", ")

    // ---- Mostrar no ecrã ----

    private fun mostrarEstado(estado: String) {
        title = "Monitorização  ·  $estado"
        estadoMqtt = estado
        textMqtt.text = estadoMqtt
        atualizarBotaoMqtt(estado)
        if (estado.startsWith("MQTT erro")) {
            Toast.makeText(this, estado, Toast.LENGTH_LONG).show()
        }
    }

    private fun mostrarEnvio(topico: String, texto: String, total: Int) {
        val h = hora()
        // Guarda a última mensagem de CADA tópico, para não serem apagadas
        // pelo esp32/acelerometro (que é enviado várias vezes por segundo)
        ultimasMensagens[topico] = "[$h] Tópico: $topico\n$texto"

        textMqtt.text = buildString {
            append(estadoMqtt)
            append("\nEnviadas: $total   (última às $h)")
            for ((t, msg) in ultimasMensagens) {
                if (t != TOPICO_RAPIDO) append("\n\n").append(msg)
            }
            ultimasMensagens[TOPICO_RAPIDO]?.let { append("\n\n").append(it) }   // sempre em último
        }
    }

    private fun mostrarAcel(x: Float, y: Float, z: Float, inclinacao: Float, yaw: Float) {
        val horizontal = inclinacao < 15 || inclinacao > 165
        val posicao = if (horizontal)
            "<font color='#FF0000'><b>Telemóvel na horizontal</b></font>"
        else
            "<font color='#008800'>Telemóvel inclinado</font>"
        val linhaRotacao = if (!yaw.isNaN()) "Rotação eixo Z: %.0f°<br>".format(yaw) else ""

        // X, Y, Z numa só linha, por baixo do botão Ligar MQTT
        textXyz.text = "X: %.1f   Y: %.1f   Z: %.1f".format(x, y, z)

        // Inclinação em relação à posição de referência (é esta que dispara o SMS)
        val incRef = MonitorService.atual?.inclinacaoReferencia ?: Float.NaN
        val linhaRef = when {
            incRef.isNaN() -> ""   // ainda sem referência: não mostra nada
            incRef > 40f -> "<font color='#FF0000'><b>Desde a referência: %.0f° (SMS)</b></font><br>".format(incRef)
            else -> "<font color='#008800'>Desde a referência: %.0f°</font><br>".format(incRef)
        }

        // (a inclinação já aparece por cima da barra, não se repete aqui)
        val texto = linhaRef + linhaRotacao + posicao + linhaIntervalo
        textAcel.text = Html.fromHtml(texto, Html.FROM_HTML_MODE_LEGACY)
        seekBar.progress = inclinacao.roundToInt()
        textView2.text = "Inclinação: %.0f°".format(inclinacao)
    }

    /** "🔋 Bateria: 78% (a carregar)" — a vermelho com 20% ou menos. */
    private fun mostrarBateria(percentagem: Int, aCarregar: Boolean) {
        textBateria.text = "🔋 Bateria: $percentagem%" + if (aCarregar) " (a carregar)" else ""
        textBateria.setTextColor(
            if (percentagem <= 20 && !aCarregar) 0xFFD32F2F.toInt() else 0xFF2E7D32.toInt()
        )
    }

    /** "🔘 Botão Shelly: ..." — a vermelho quando há um problema (Bluetooth, autorização, encriptação). */
    private fun mostrarBotao(estado: String) {
        // "-44 dBm" passa a "▂▄▆█ -44 dBm": barras de sinal coloridas conforme a força
        val html = Html.escapeHtml("🔘 $estado").replace(Regex("(-?\\d+) dBm")) { m ->
            barrasSinal(m.groupValues[1].toInt()) + "&nbsp;" + m.value
        }
        textBotao.text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        val problema = listOf("ligue", "falta", "erro", "encriptação", "não tem").any { estado.contains(it) }
        textBotao.setTextColor(if (problema) 0xFFD32F2F.toInt() else 0xFF1565C0.toInt())
    }

    /**
     * Força do sinal Bluetooth em 4 barras (as que faltam ficam a cinzento):
     *  ≥ -60 dBm: 4 (verde) · ≥ -70: 3 (verde) · ≥ -80: 2 (amarelo) · ≥ -90: 1 (vermelho) · pior: 0
     */
    private fun barrasSinal(rssi: Int): String {
        val n = when {
            rssi >= -60 -> 4
            rssi >= -70 -> 3
            rssi >= -80 -> 2
            rssi >= -90 -> 1
            else -> 0
        }
        val cor = when (n) { 4, 3 -> "#2E7D32"; 2 -> "#F9A825"; else -> "#D32F2F" }
        return listOf("▂", "▄", "▆", "█").mapIndexed { i, barra ->
            "<font color='${if (i < n) cor else "#BDBDBD"}'>$barra</font>"
        }.joinToString("")
    }

    /** "Associar botão": pede a autorização (se faltar) e põe o serviço 30 s à espera de um carregão. */
    private fun associarBotao() {
        if (!ShellyBlu.temPermissao(this)) {
            pedirPermissaoBotao.launch(ShellyBlu.PERMISSOES)
            return
        }
        val s = MonitorService.atual
        if (s == null) {
            Toast.makeText(this, "Serviço ainda a arrancar, tente de novo", Toast.LENGTH_SHORT).show()
            return
        }
        s.associarBotao()
        Toast.makeText(this, "Carregue agora no botão Shelly (30 s)", Toast.LENGTH_LONG).show()
    }

    /** Ligado: "Desligar MQTT" a vermelho. Desligado/erro: "Ligar MQTT" a verde. */
    private fun atualizarBotaoMqtt(estado: String) {
        val aLigar = estado.startsWith("MQTT a ligar")
        val ligado = !aLigar && (estado.startsWith("MQTT ligado") || estado.startsWith("MQTT religado"))
        val cor = when {
            aLigar -> { button.text = "A ligar MQTT…"; 0xFF9E9E9E.toInt() }   // cinzento
            ligado -> { button.text = "Desligar MQTT"; 0xFFD32F2F.toInt() }   // vermelho
            else -> { button.text = "Ligar MQTT"; 0xFF2E7D32.toInt() }        // verde
        }
        button.isEnabled = !aLigar
        button.setTextColor(0xFFFFFFFF.toInt())
        button.backgroundTintList = ColorStateList.valueOf(cor)
    }

    // ---- Nome ----

    /** Guarda o nome escrito no campo e passa a usá-lo no MQTT (label no mapa). */
    private fun guardarNome(aviso: Boolean = true) {
        val nome = editNome.text.toString().trim().ifEmpty { MonitorService.NOME_POR_OMISSAO }
        if (!aviso && nome == MonitorService.lerNome(this)) return   // nada mudou
        MonitorService.guardarNome(this, nome)
        MonitorService.atual?.mudarNome(nome)

        // Esconde o teclado e tira o cursor do campo
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editNome.windowToken, 0)
        if (aviso) {
            editNome.clearFocus()
            Toast.makeText(this, "Nome guardado: $nome", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- SMS ("ALERTA!") ----

    /** Guarda o número escrito. Devolve o número limpo, ou null se for inválido / vazio. */
    private fun guardarTelefone(aviso: Boolean = true): String? {
        val texto = editTelefone.text.toString().trim()
        val numero = SmsEnvio.limparNumero(texto)
        if (numero == null) {
            if (aviso) Toast.makeText(this, "Número inválido", Toast.LENGTH_SHORT).show()
            return null
        }
        if (numero != SmsEnvio.lerNumero(this)) SmsEnvio.guardarNumero(this, numero)
        if (aviso) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(editTelefone.windowToken, 0)
            editTelefone.clearFocus()
            Toast.makeText(this, "Número guardado: $numero", Toast.LENGTH_SHORT).show()
            if (!SmsEnvio.temPermissao(this)) pedirPermissaoSmsQueda.launch(SmsEnvio.PERMISSOES)
        }
        return numero
    }

    private fun clicarSms() {
        try {
            clicarSmsSemProtecao()
        } catch (e: Throwable) {
            buttonSms.isEnabled = true
            Toast.makeText(this, "Erro ao enviar SMS: ${Erros.curto(e)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clicarSmsSemProtecao() {
        if (guardarTelefone(aviso = false) == null) {
            Toast.makeText(this, "Escreva primeiro o número do telemóvel", Toast.LENGTH_LONG).show()
            editTelefone.requestFocus()
            return
        }
        if (SmsEnvio.temPermissao(this)) enviarSms()
        else pedirPermissaoSms.launch(SmsEnvio.PERMISSOES)
    }

    /** Envia o SMS com o nome, a hora, o link do mapa e a morada. */
    private fun enviarSms() {
        val numero = guardarTelefone(aviso = false) ?: return
        var lat = latAtual
        var lon = lonAtual
        if (lat.isNaN()) ultimaPosicaoConhecida()?.let { lat = it.latitude; lon = it.longitude }

        // A morada do ecrã só serve se for de perto desta posição (< 50 m); senão procura-a de novo
        var moradaAtual = ""
        if (morada.isNotBlank() && !lat.isNaN() && !latMorada.isNaN()) {
            val distancia = FloatArray(1)
            Location.distanceBetween(latMorada, lonMorada, lat, lon, distancia)
            if (distancia[0] < 50f) moradaAtual = morada
        }
        buttonSms.isEnabled = false
        buttonSms.postDelayed({ buttonSms.isEnabled = true }, 20_000)   // se o Android não responder
        Toast.makeText(this, "A enviar SMS para $numero…", Toast.LENGTH_SHORT).show()

        SmsEnvio.enviarAlerta(
            this, numero, MonitorService.lerNome(this), lat, lon,
            moradaConhecida = moradaAtual, hora = hora(), titulo = "ALERTA"
        ) { resultado ->
            buttonSms.isEnabled = true
            Toast.makeText(applicationContext, resultado, Toast.LENGTH_LONG).show()
        }
    }

    /** Última posição que o Android conhece (quando o GPS ainda não deu nenhuma nesta sessão). */
    @SuppressLint("MissingPermission")
    private fun ultimaPosicaoConhecida(): Location? {
        if (!temLocalizacao()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.time }
    }

    // ---- Intervalo de envio ----

    /** Lê o intervalo escrito (ms), valida, guarda e passa logo a usá-lo. */
    private fun aplicarIntervalo() {
        val ms = editIntervalo.text.toString().trim().toLongOrNull()
        if (ms == null || ms !in MonitorService.INTERVALO_MIN_MS..MonitorService.INTERVALO_MAX_MS) {
            Toast.makeText(
                this,
                "Intervalo entre ${MonitorService.INTERVALO_MIN_MS} e ${MonitorService.INTERVALO_MAX_MS} ms",
                Toast.LENGTH_LONG
            ).show()
            editIntervalo.setText(MonitorService.lerIntervalo(this).toString())
            return
        }
        MonitorService.guardarIntervalo(this, ms)
        MonitorService.atual?.mudarIntervalo(ms)

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editIntervalo.windowToken, 0)
        editIntervalo.clearFocus()
        Toast.makeText(this, "Intervalo de leitura: $ms ms (%.2f Hz)".format(1000.0 / ms), Toast.LENGTH_SHORT).show()
    }

    // ---- Autorizações ----

    private fun temLocalizacao(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun pedirPermissoesEmFalta() {
        val pedidas = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) pedidas += Manifest.permission.POST_NOTIFICATIONS
        // Botão Shelly: só pede "Dispositivos próximos" se já houver algum botão associado
        // (senão é pedida ao carregar em "Associar botão")
        if (Build.VERSION.SDK_INT >= 31 && ShellyBlu.lerMacs(this).isNotEmpty())
            pedidas += Manifest.permission.BLUETOOTH_SCAN
        val faltam = pedidas.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltam.isEmpty()) pedirSemLimiteBateria()
        else pedirPermissoes.launch(faltam.toTypedArray())
    }

    private fun mostrarAvisoGps() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val ligado = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        when {
            !temLocalizacao() -> textGps.text = "GPS: sem autorização de localização"
            !ligado -> textGps.text = "GPS: ligue a Localização nas definições do telemóvel"
        }
    }

    /** Pede (uma vez) para o Android não limitar a app por causa da bateria. */
    private fun pedirSemLimiteBateria() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(CHAVE_PEDIU_BATERIA, false)) return
        prefs.edit().putBoolean(CHAVE_PEDIU_BATERIA, true).apply()
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) { }
    }
}
