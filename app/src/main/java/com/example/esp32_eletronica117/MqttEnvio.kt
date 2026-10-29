package com.example.esp32_eletronica117

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Envia os dados do acelerómetro por MQTT (JSON).
 *
 * Tópicos:
 *   esp32-udemy/telemovel/dados   -> {"nome":"D. Maria","device_id":"telemovel-3fa9c1","x":..,"y":..,"z":..,"inclinacao":..,"rotacao":..,"horizontal":true/false,"ts":..}
 *   esp32-udemy/telemovel/queda   -> {"nome":"D. Maria","device_id":"telemovel-3fa9c1","queda":true,"ts":..}
 *   esp32-udemy/telemovel/estado  -> "online" / "offline" (last will)
 *   esp32-udemy/telemovel/botao   -> {"nome":"D. Maria","device_id":"telemovel-3fa9c1","botao":"7C:C6:..","evento":"carregar","bateria_botao":100,"rssi":-62,"ts":..}
 *   fastapi/map/positions         -> {"device_id":"telemovel-3fa9c1","label":"D. Maria","lat":..,"lon":..,"precisao":..,"fonte":"GPS"}
 *                                    (página "Mapa" do FastAPI: aparece como marcador próprio, separado do track1)
 *   esp32/acelerometro            -> {"lx":..,"ly":..,"lz":..,"roll":..,"pitch":..,"yaw":..,"yaw_ok":1,"sample_utc":".."}
 *                                    (mesmo formato do BNO085 -> página "Gráficos" do FastAPI)
 *   esp32/acelerometro/config/set <- "100"  (pedido da página: intervalo em ms, 10..1000)
 *   esp32/acelerometro/config     -> {"period_ms":100,"sensor_on":true}  (confirmação)
 *   esp32/cmd                     <- "ZERO"  (botão "Definir referência" da página Mapa)
 *
 * Todos os ouvintes são chamados FORA da thread do ecrã!
 */
class MqttEnvio(
    /** Avisa a Activity do estado da ligação. */
    private val ouvinteEstado: (estado: String) -> Unit
) {

    companion object {
        // ---- Configuração do broker (EMQX no Hetzner) ----
        private const val SERVIDOR = "tcp://89.167.29.191:1883"
        private const val UTILIZADOR = ""   // preencher se o EMQX pedir (separador Security no Node-RED)
        private const val PASSWORD = ""

        private const val TOPICO_BASE = "esp32-udemy/telemovel"
        private const val TOPICO_DADOS = "$TOPICO_BASE/dados"
        private const val TOPICO_QUEDA = "$TOPICO_BASE/queda"
        private const val TOPICO_ESTADO = "$TOPICO_BASE/estado"
        private const val TOPICO_BOTAO = "$TOPICO_BASE/botao"   // botão Shelly BLU Button1


        // Página "Mapa" do FastAPI (http://89.167.29.191:8000/app)
        private const val TOPICO_MAPA = "fastapi/map/positions"
        private const val INTERVALO_GPS_MS = 2000L   // no máximo 1 posição a cada 2 s

        // Página "Gráficos" do FastAPI (mesmo tópico do BNO085 da T-SIM7000G)
        private const val TOPICO_GRAFICO = "esp32/acelerometro"
        private const val TOPICO_CONFIG = "$TOPICO_GRAFICO/config"
        private const val TOPICO_CONFIG_SET = "$TOPICO_CONFIG/set"
        private const val TOPICO_CMD = "esp32/cmd"

        /** Escapa aspas e barras para o texto poder ir dentro de JSON. */
        private fun escapar(texto: String): String =
            texto.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    /** Nome da pessoa (escrito na app): aparece como label no mapa e vai nos dados/quedas. */
    /** Carga da bateria (%) e se está a carregar; -1 = ainda não se sabe. Vai no JSON de "dados". */
    @Volatile var bateria: Int = -1
    @Volatile var aCarregar: Boolean = false

    @Volatile
    var nome: String = "Telemóvel"

    /** Identificador único deste telemóvel (um marcador por telemóvel no mapa). */
    @Volatile
    var deviceId: String = "telemovel"

    /** Avisa a Activity de cada mensagem enviada. */
    var ouvinteEnvio: ((topico: String, texto: String, total: Int) -> Unit)? = null

    /** Avisa quando a página pede ZERO. */
    var ouvinteZero: (() -> Unit)? = null

    /** A próxima leitura do acelerómetro é enviada logo (sem esperar pelo intervalo). */
    fun enviarLeituraJa() {
        ultimoEnvioGrafico = 0
    }

    /** Avisa quando a página muda o intervalo. */
    var ouvinteIntervalo: ((ms: Int) -> Unit)? = null

    /** Intervalo de envio para a página "Gráficos" (alterável pela página, 10..1000 ms). */
    @Volatile
    var intervaloGraficoMs = 100L
        private set

    /** Intervalo de envio dos dados (esp32-udemy/telemovel/dados), alterável na app. */
    @Volatile
    var intervaloDadosMs = 1000L

    private var totalEnviados = 0
    private var cliente: MqttAsyncClient? = null
    private var ultimoEnvio = 0L
    private var ultimoEnvioGrafico = 0L
    private var ultimoEnvioGps = 0L
    private var ultimoHorizontal: Boolean? = null

    // Última posição GPS recebida: é reenviada quando o MQTT liga, quando o
    // nome muda e de tempos a tempos. Sem isto, a posição que o GPS dá ao
    // arrancar perdia-se (o MQTT ainda estava a ligar) e, com o telemóvel
    // parado, o Android pode demorar muito a dar outra.
    private class PosicaoGps(val lat: Double, val lon: Double, val precisao: Float, val fonte: String)
    @Volatile
    private var ultimaPosicao: PosicaoGps? = null

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            publicar(TOPICO_ESTADO, "online", 1, true)
            try {
                cliente?.subscribe(TOPICO_CONFIG_SET, 1)
                cliente?.subscribe(TOPICO_CMD, 1)
            } catch (_: Exception) { }
            publicarConfig()
            reenviarGps()   // o mapa mostra logo o telemóvel, sem esperar por um GPS novo
            ouvinteEstado(if (reconnect) "MQTT religado" else "MQTT ligado")
        }

        override fun connectionLost(cause: Throwable?) {
            ouvinteEstado("MQTT desligado")
        }

        override fun messageArrived(topic: String?, message: MqttMessage?) {
            val texto = message?.payload?.toString(Charsets.UTF_8)?.trim() ?: return
            when (topic) {
                TOPICO_CMD -> {
                    if (texto.equals("ZERO", ignoreCase = true)) {
                        ouvinteZero?.invoke()
                        ultimoEnvioGrafico = 0   // envia já a leitura com os zeros
                    }
                }
                TOPICO_CONFIG_SET -> {
                    val ms = texto.toIntOrNull() ?: return
                    if (ms !in 10..1000) return
                    intervaloGraficoMs = ms.toLong()
                    publicarConfig()
                    ouvinteEstado("MQTT ligado · intervalo %d ms (%.1f Hz)".format(Locale.US, ms, 1000.0 / ms))
                    ouvinteIntervalo?.invoke(ms)
                }
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) { }
    }

    fun ligar() {
        try {
            val c = cliente ?: MqttAsyncClient(
                SERVIDOR,
                "android-" + UUID.randomUUID().toString().substring(0, 8),
                MemoryPersistence()
            ).also {
                it.setCallback(callback)
                cliente = it
            }
            if (c.isConnected) return

            val opcoes = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                keepAliveInterval = 60
                connectionTimeout = 10
                if (UTILIZADOR.isNotEmpty()) {
                    userName = UTILIZADOR
                    password = PASSWORD.toCharArray()
                }
                // Se a app desaparecer sem desligar, o broker publica "offline"
                setWill(TOPICO_ESTADO, "offline".toByteArray(), 1, true)
            }

            ouvinteEstado("MQTT a ligar...")
            c.connect(opcoes, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) { }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    ouvinteEstado("MQTT erro: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            ouvinteEstado("MQTT erro: ${e.message}")
        }
    }

    fun desligar() {
        try {
            cliente?.takeIf { it.isConnected }?.let {
                publicar(TOPICO_ESTADO, "offline", 1, true)
                it.disconnect()
            }
        } catch (_: Exception) { }
        // disconnect() pedido pela app não chama connectionLost: avisa aqui
        ouvinteEstado("MQTT desligado")
    }

    /** true se estiver ligado ao broker. */
    fun estaLigado(): Boolean = cliente?.isConnected == true

    /** Dados do acelerómetro: de [intervaloDadosMs] em [intervaloDadosMs], ou logo se a posição mudar. */
    fun enviarDados(x: Float, y: Float, z: Float, inclinacao: Float, rotacao: Float, horizontal: Boolean) {
        val agora = System.currentTimeMillis()
        val mudouPosicao = ultimoHorizontal != horizontal
        if (!mudouPosicao && agora - ultimoEnvio < intervaloDadosMs) return
        ultimoEnvio = agora
        ultimoHorizontal = horizontal

        val json = ("{\"nome\":\"%s\",\"device_id\":\"%s\",\"x\":%.2f,\"y\":%.2f,\"z\":%.2f,\"inclinacao\":%.1f,\"rotacao\":%.1f," +
                "\"horizontal\":%b,\"bateria\":%s,\"a_carregar\":%b,\"ts\":%d}")
            .format(Locale.US, escapar(nome), deviceId, x, y, z, inclinacao, rotacao, horizontal,
                if (bateria < 0) "null" else bateria.toString(), aCarregar, agora)
        publicar(TOPICO_DADOS, json, 0, false)
    }

    /** Para a página "Gráficos": lx, ly, lz (m/s², sem gravidade), roll e pitch (graus). */
    fun enviarGrafico(lx: Float, ly: Float, lz: Float, roll: Float, pitch: Float, yaw: Float) {
        val agora = System.currentTimeMillis()
        if (agora - ultimoEnvioGrafico < intervaloGraficoMs) return
        ultimoEnvioGrafico = agora

        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val semYaw = yaw.isNaN()
        val json = ("{\"lx\":%.3f,\"ly\":%.3f,\"lz\":%.3f,\"roll\":%.2f,\"pitch\":%.2f," +
                "\"yaw\":%.1f,\"yaw_ok\":%d,\"sample_utc\":\"%s\"}")
            .format(Locale.US, lx, ly, lz, roll, pitch,
                if (semYaw) 0f else yaw, if (semYaw) 0 else 1,
                iso.format(Date(agora)))
        publicar(TOPICO_GRAFICO, json, 0, false)
    }

    /**
     * Muda o intervalo da página "Gráficos" a partir da app (10..1000 ms)
     * e avisa logo a página (tópico esp32/acelerometro/config).
     */
    fun definirIntervaloGrafico(ms: Long) {
        intervaloGraficoMs = ms.coerceIn(10L, 1000L)
        publicarConfig()
    }

    /** Confirma à página o intervalo em uso (como faz a placa). */
    private fun publicarConfig() {
        publicar(TOPICO_CONFIG, "{\"period_ms\":$intervaloGraficoMs,\"sensor_on\":true}", 1, false)
    }

    /** Posição GPS para o mapa do FastAPI (no máximo 1 a cada 2 s). */
    fun enviarGps(lat: Double, lon: Double, precisao: Float, fonte: String) {
        ultimaPosicao = PosicaoGps(lat, lon, precisao, fonte)
        val agora = System.currentTimeMillis()
        if (agora - ultimoEnvioGps < INTERVALO_GPS_MS) return
        ultimoEnvioGps = agora

        val json = ("{\"device_id\":\"%s\",\"label\":\"%s\",\"lat\":%.6f,\"lon\":%.6f," +
                "\"precisao\":%.0f,\"fonte\":\"%s\",\"bateria\":%s,\"a_carregar\":%b,\"ts\":%d}")
            .format(Locale.US, deviceId, escapar(nome), lat, lon, precisao, fonte,
                if (bateria < 0) "null" else bateria.toString(), aCarregar, agora)
        publicar(TOPICO_MAPA, json, 0, false)
    }

    /** Reenvia já a última posição conhecida (se houver), ignorando o limite de 2 s. */
    fun reenviarGps() {
        val p = ultimaPosicao ?: return
        ultimoEnvioGps = 0
        enviarGps(p.lat, p.lon, p.precisao, p.fonte)
    }

    /** Queda: enviada logo, com QoS 1 (garantia de entrega). */
    fun enviarQueda() {
        val json = "{\"nome\":\"${escapar(nome)}\",\"device_id\":\"$deviceId\"," +
                "\"queda\":true,\"ts\":${System.currentTimeMillis()}}"
        publicar(TOPICO_QUEDA, json, 1, false)
    }

    /**
     * Botão Shelly BLU carregado: enviado logo, com QoS 1.
     * {"nome":"D. Maria","device_id":"telemovel-3fa9c1","botao":"7C:C6:B6:61:A2:3F",
     *  "evento":"carregar","bateria_botao":100,"rssi":-62,"ts":..}
     */
    fun enviarBotao(mac: String, evento: String, bateriaBotao: Int, rssi: Int) {
        val json = ("{\"nome\":\"%s\",\"device_id\":\"%s\",\"botao\":\"%s\",\"evento\":\"%s\"," +
                "\"bateria_botao\":%s,\"rssi\":%d,\"ts\":%d}")
            .format(Locale.US, escapar(nome), deviceId, mac, evento,
                if (bateriaBotao < 0) "null" else bateriaBotao.toString(), rssi,
                System.currentTimeMillis())
        publicar(TOPICO_BOTAO, json, 1, false)
    }

    private fun publicar(topico: String, texto: String, qos: Int, retido: Boolean) {
        try {
            val c = cliente ?: return
            if (c.isConnected) {
                c.publish(topico, texto.toByteArray(Charsets.UTF_8), qos, retido)
                totalEnviados++
                ouvinteEnvio?.invoke(topico, texto, totalEnviados)
            }
        } catch (_: Exception) { }
    }
}
