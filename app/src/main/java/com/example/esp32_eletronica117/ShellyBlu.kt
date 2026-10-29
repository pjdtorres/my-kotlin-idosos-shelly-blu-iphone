package com.example.esp32_eletronica117

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.idosos.shared.BTHomeDecoder

/**
 * Botão Bluetooth Shelly BLU Button1 (nome Bluetooth "SBBT-002C").
 *
 * O botão NÃO precisa de ser emparelhado: quando é carregado envia, várias vezes
 * seguidas, um anúncio Bluetooth (BLE advertising) no formato BTHome v2:
 *
 *   Service Data, UUID 0xFCD2:
 *     [0]  info do dispositivo: bit 0 = encriptado, bit 2 = enviado por evento, bits 5-7 = versão (2)
 *     depois, objetos "id + valor":
 *       0x00  n.º do pacote (1 byte)  -> o mesmo carregão repete o número: serve para ignorar repetições
 *       0x01  bateria % (1 byte)
 *       0x3A  botão (1 byte): 1 carregar · 2 duplo · 3 triplo · 4 longo · 0x80/0xFE manter carregado
 *
 * Exemplo (1 carregão, 100% de bateria):  44 00 2B 01 64 3A 01
 *
 * Esta classe só escuta (scan). Para não reagir aos botões dos vizinhos, a app só aceita
 * os botões "associados": carregue em "Associar" na app e, nos 30 s seguintes, no botão Shelly.
 * Podem ser associados vários botões (a embalagem traz 2).
 *
 * O [ouvinte] é chamado na thread do ecrã.
 */
class ShellyBlu(
    private val context: Context,
    private val ouvinte: (evento: Evento) -> Unit
) {

    /** Um carregão num botão associado. */
    class Evento(
        val mac: String,
        val tipo: Int,          // BOTAO_*
        val descricao: String,  // "carregar", "duplo", ...
        val bateria: Int,       // % (-1 = não veio)
        val rssi: Int,          // força do sinal (dBm): -50 perto, -90 longe
        val pacote: Int
    )

    companion object {
        /** UUID de 16 bits do BTHome (0xFCD2) no formato completo. */
        val UUID_BTHOME: ParcelUuid = ParcelUuid.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")

        const val BOTAO_NADA = 0x00
        const val BOTAO_CARREGAR = 0x01
        const val BOTAO_DUPLO = 0x02
        const val BOTAO_TRIPLO = 0x03
        const val BOTAO_LONGO = 0x04
        const val BOTAO_MANTER = 0x80          // firmware 1.0.20 ou mais recente
        const val BOTAO_MANTER_ANTIGO = 0xFE   // firmware antigo

        private const val CHAVE_MACS = "blu_macs"
        private const val TEMPO_ASSOCIAR_MS = 30_000L
        private const val TEMPO_REPETICAO_MS = 10_000L   // o mesmo n.º de pacote dentro de 10 s = repetição

        fun descrever(tipo: Int): String = when (tipo) {
            BOTAO_CARREGAR -> "carregar"
            BOTAO_DUPLO -> "duplo"
            BOTAO_TRIPLO -> "triplo"
            BOTAO_LONGO -> "longo"
            BOTAO_MANTER, BOTAO_MANTER_ANTIGO -> "manter"
            else -> "evento $tipo"
        }

        /** Autorizações para o scan Bluetooth (no Android ≤ 11 basta a de localização). */
        val PERMISSOES: Array<String> =
            if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun temPermissao(context: Context): Boolean =
            PERMISSOES.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        /** MACs dos botões associados (ex.: "7C:C6:B6:61:A2:3F"). */
        fun lerMacs(context: Context): Set<String> =
            context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
                .getString(CHAVE_MACS, "")!!
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        private fun guardarMacs(context: Context, macs: Set<String>) {
            context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
                .edit().putString(CHAVE_MACS, macs.joinToString(",")).apply()
        }

        /** Resultado da leitura de um anúncio BTHome. */
        class Leitura(val encriptado: Boolean, val pacote: Int, val bateria: Int, val botao: Int)

        /**
         * Lê o Service Data BTHome v2. Devolve null se não for BTHome v2.
         * Público para se poder testar sem Bluetooth (ver comentário no topo).
         */
        fun interpretar(dados: ByteArray): Leitura? {
            val r = BTHomeDecoder.parse(dados) ?: return null
            return Leitura(r.encrypted, r.packet, r.battery, r.button)
        }
    }

    /** Frase com o estado (Bluetooth desligado, a associar, último carregão...). Thread do ecrã. */
    var ouvinteEstado: ((texto: String) -> Unit)? = null

    @Volatile var estado = "Botão Shelly: a iniciar…"
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val adaptador: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var aProcurar = false
    private var ligado = false
    private var associarAte = 0L
    private var sinaisVistos = 0          // diagnóstico: anúncios BTHome recebidos durante a associação

    // Última repetição vista, por botão: n.º do pacote e quando chegou
    private val ultimoPacote = HashMap<String, Pair<Int, Long>>()

    private val recetorBluetooth = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> comecarScan()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    pararScan()
                    mudarEstado("Botão Shelly: ligue o Bluetooth do telemóvel")
                }
            }
        }
    }

    private val fimAssociar = Runnable {
        if (associarAte != 0L) {
            associarAte = 0L
            mudarEstado(textoEspera("Nenhum botão novo encontrado"))
        }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = tratar(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { tratar(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return
            aProcurar = false
            mudarEstado("Botão Shelly: erro no scan Bluetooth ($errorCode)")
            // Tenta de novo daqui a 30 s (o Android limita a 5 arranques de scan em 30 s)
            handler.postDelayed({ if (ligado) comecarScan() }, 30_000)
        }
    }

    /** true se este telemóvel tiver Bluetooth LE. */
    fun existe(): Boolean =
        adaptador != null &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /**
     * Começa a escutar. Devolve false se faltar a autorização ou o Bluetooth LE.
     * Se o Bluetooth estiver desligado, começa sozinho quando for ligado.
     */
    fun iniciar(): Boolean {
        if (ligado) return true
        if (!existe()) {
            mudarEstado("Botão Shelly: este telemóvel não tem Bluetooth LE")
            return false
        }
        if (!temPermissao(context)) {
            mudarEstado(
                if (lerMacs(context).isEmpty()) textoEspera(null)   // ainda não se usa o botão
                else "Botão Shelly: falta a autorização \"Dispositivos próximos\""
            )
            return false
        }
        ligado = true
        ContextCompat.registerReceiver(
            context, recetorBluetooth, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        comecarScan()
        return true
    }

    fun parar() {
        if (!ligado) return
        ligado = false
        handler.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(recetorBluetooth) } catch (_: Exception) { }
        pararScan()
    }

    /** Durante 30 s, o próximo botão Shelly que for carregado fica associado. */
    fun associar() {
        associarAte = SystemClock.elapsedRealtime() + TEMPO_ASSOCIAR_MS
        sinaisVistos = 0
        handler.removeCallbacks(fimAssociar)
        handler.postDelayed(fimAssociar, TEMPO_ASSOCIAR_MS)
        mudarEstado(
            if (adaptador?.isEnabled == true) "Botão Shelly: carregue agora no botão (30 s)…"
            else "Botão Shelly: ligue o Bluetooth e carregue no botão (30 s)…"
        )
    }

    /** Esquece todos os botões associados. */
    fun esquecer() {
        guardarMacs(context, emptySet())
        ultimoPacote.clear()
        mudarEstado(textoEspera(null))
    }

    // ---- Interno ----

    @SuppressLint("MissingPermission")   // verificada em iniciar()
    private fun comecarScan() {
        if (!ligado || aProcurar) return
        val a = adaptador ?: return
        if (!a.isEnabled) {
            mudarEstado("Botão Shelly: ligue o Bluetooth do telemóvel")
            return
        }
        val s = a.bluetoothLeScanner ?: return
        // Com filtro, o Android deixa o scan continuar com o ecrã desligado
        val filtros = listOf(ScanFilter.Builder().setServiceData(UUID_BTHOME, ByteArray(0)).build())
        val definicoes = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)   // os anúncios do botão duram pouco
            .setReportDelay(0)
            .build()
        try {
            s.startScan(filtros, definicoes, callback)
            scanner = s
            aProcurar = true
            if (associarAte == 0L) mudarEstado(textoEspera(null))
        } catch (e: Exception) {
            mudarEstado("Botão Shelly: ${Erros.curto(e)}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun pararScan() {
        if (!aProcurar) return
        aProcurar = false
        try {
            if (adaptador?.isEnabled == true) scanner?.stopScan(callback)
        } catch (_: Exception) { }
        scanner = null
    }

    private fun tratar(r: ScanResult) {
        val dados = r.scanRecord?.getServiceData(UUID_BTHOME) ?: return
        val leitura = interpretar(dados) ?: return
        val mac = r.device.address
        val agora = SystemClock.elapsedRealtime()
        val associados = lerMacs(context)
        val aAssociar = associarAte != 0L && agora < associarAte

        if (aAssociar) {
            sinaisVistos++
            mudarEstado("Botão Shelly: a associar… sinais recebidos: $sinaisVistos " +
                    "(último ${leitura.botao.coerceAtLeast(0)} de $mac)")
        }
        if (mac !in associados && !aAssociar) return   // botão de outra pessoa

        if (leitura.encriptado) {
            mudarEstado("Botão Shelly $mac tem a encriptação ligada: desligue-a na app Shelly")
            return
        }
        // Só interessam carregões (os anúncios de "beacon" vêm com o botão a 0 ou sem botão)
        if (leitura.botao <= BOTAO_NADA) return

        // O mesmo carregão chega várias vezes com o mesmo n.º de pacote: só conta a 1.ª
        val anterior = ultimoPacote[mac]
        if (anterior != null && anterior.first == leitura.pacote &&
            agora - anterior.second < TEMPO_REPETICAO_MS) return
        ultimoPacote[mac] = leitura.pacote to agora

        if (mac !in associados) {
            guardarMacs(context, associados + mac)
            associarAte = 0L
            handler.removeCallbacks(fimAssociar)
            mudarEstado("Botão Shelly associado: $mac")
            return   // o carregão de associação não dispara alerta
        }

        val evento = Evento(
            mac, leitura.botao, descrever(leitura.botao), leitura.bateria, r.rssi, leitura.pacote
        )
        val bateria = if (evento.bateria >= 0) " · 🔋 ${evento.bateria}%" else ""
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        // 1 clique: só a hora; 2/3 cliques, longo ou manter: diz qual foi
        val tipo = if (evento.tipo == BOTAO_CARREGAR) "" else " · ${evento.descricao}"
        mudarEstado("Shelly: $hora$tipo$bateria · ${evento.rssi} dBm")
        handler.post { ouvinte(evento) }
    }

    private fun textoEspera(prefixo: String?): String {
        val n = lerMacs(context).size
        val base = when (n) {
            0 -> "Botão Shelly: Carregue para associar!"
            1 -> "Botão Shelly: 1 associado, à escuta"
            else -> "Botão Shelly: $n associados, à escuta"
        }
        return if (prefixo == null) base else "$prefixo. $base"
    }

    private fun mudarEstado(texto: String) {
        estado = texto
        handler.post { ouvinteEstado?.invoke(texto) }
    }
}
