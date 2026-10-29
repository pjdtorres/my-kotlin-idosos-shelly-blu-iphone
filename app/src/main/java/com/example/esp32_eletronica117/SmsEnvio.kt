package com.example.esp32_eletronica117

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Envio de SMS com o SmsManager do Android: o SMS sai logo, sem abrir a app de mensagens.
 * Precisa da autorização SEND_SMS (pedida no MainActivity quando se carrega no botão).
 *
 * Teste no emulador: com dois emuladores abertos, o número do outro é a porta
 * (ex.: "5556" para o emulator-5556) e o SMS aparece nas Mensagens desse emulador.
 */
object SmsEnvio {

    private const val CHAVE_NUMERO = "numero_sms"
    private const val ACAO_ENVIADO = "com.example.esp32_eletronica117.SMS_ENVIADO"
    private const val ESPERA_MORADA_S = 8L   // tempo máximo à espera da morada

    private val executor = Executors.newCachedThreadPool()
    private val principal = Handler(Looper.getMainLooper())

    /** Número guardado ("" se ainda não houver). */
    fun lerNumero(context: Context): String =
        context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            .getString(CHAVE_NUMERO, "") ?: ""

    fun guardarNumero(context: Context, numero: String) {
        context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(CHAVE_NUMERO, numero).apply()
    }

    /**
     * Tira espaços, traços e parênteses. Aceita "+351 912 345 678", "912345678"
     * ou "5556" (outro emulador). Devolve null se não for um número válido.
     */
    fun limparNumero(texto: String): String? {
        val n = texto.filter { it.isDigit() || it == '+' }
        return if (n.count { it.isDigit() } >= 3 && n.lastIndexOf('+') <= 0) n else null
    }

    /** Autorizações precisas para enviar SMS (a de "Telefone" é exigida por alguns Samsung). */
    val PERMISSOES = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)

    /** True se a app já tiver as autorizações para enviar SMS. */
    fun temPermissao(context: Context): Boolean =
        PERMISSOES.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Texto do SMS: título, nome, hora e MORADA.
     * Só se não houver morada (sem Internet, p. ex.) é que vai o link do mapa.
     */
    fun montarMensagem(
        nome: String, lat: Double, lon: Double, morada: String, hora: String,
        titulo: String = "ALERTA"
    ): String =
        buildString {
            append("$titulo: $nome (às $hora)")
            when {
                morada.isNotBlank() -> append("\nMorada: ").append(morada)
                lat.isNaN() || lon.isNaN() -> append("\nLocalização: sem GPS")
                else -> append("\nLocalização: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lon))
            }
        }

    /** Alerta de queda/inclinação: "ALERTA - caiu! nome" + morada (ou link do mapa se não houver). */
    fun montarAlertaCaiu(nome: String, lat: Double, lon: Double, morada: String): String =
        buildString {
            append("ALERTA - caiu! ").append(nome)
            when {
                morada.isNotBlank() -> append("\nMorada: ").append(morada)
                lat.isNaN() || lon.isNaN() -> append("\nLocalização: sem GPS")
                else -> append("\nLocalização: https://maps.google.com/?q=%.6f,%.6f".format(Locale.US, lat, lon))
            }
        }

    /**
     * Envia o alerta com a morada. Se [moradaConhecida] estiver vazia, procura a morada
     * a partir de [lat]/[lon] (Geocoder, precisa de Internet; espera no máx. 8 s) e só depois envia.
     */
    fun enviarAlerta(
        context: Context, numero: String, nome: String, lat: Double, lon: Double,
        moradaConhecida: String, hora: String, titulo: String, caiu: Boolean = false,
        resultado: (String) -> Unit
    ) {
        val app = context.applicationContext
        executor.execute {
            val texto = try {
                val morada = moradaConhecida.ifBlank {
                    if (lat.isNaN() || lon.isNaN()) "" else procurarMorada(app, lat, lon) ?: ""
                }
                if (caiu) montarAlertaCaiu(nome, lat, lon, morada)
                else montarMensagem(nome, lat, lon, morada, hora, titulo)
            } catch (e: Throwable) {
                if (caiu) montarAlertaCaiu(nome, lat, lon, "")      // sem morada, mas envia
                else montarMensagem(nome, lat, lon, "", hora, titulo)
            }
            principal.post {
                try {
                    enviar(app, numero, texto, resultado)
                } catch (e: Throwable) {
                    resultado("SMS não enviado: ${Erros.curto(e)}")
                }
            }
        }
    }

    /** Morada (ex.: "Av. Pedro Álvares Cabral 12, 6000-084 Castelo Branco") ou null. */
    private fun procurarMorada(context: Context, lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) return null
        val pedido = executor.submit<String?> {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.forLanguageTag("pt-PT"))
                .getFromLocation(lat, lon, 1)
                ?.firstOrNull()
                ?.let { a ->
                    a.getAddressLine(0)
                        ?: listOfNotNull(a.thoroughfare, a.subThoroughfare, a.postalCode, a.locality)
                            .joinToString(", ")
                }
                ?.takeIf { it.isNotBlank() }
        }
        return try {
            pedido.get(ESPERA_MORADA_S, TimeUnit.SECONDS)
        } catch (e: Exception) {
            pedido.cancel(true)
            null   // sem Internet / demorou demasiado: o SMS leva o link do mapa
        }
    }

    /**
     * Envia [texto] para [numero]. [resultado] é chamado na thread do ecrã
     * com uma frase a dizer se o SMS saiu ou porque falhou.
     */
    fun enviar(context: Context, numero: String, texto: String, resultado: (String) -> Unit) {
        val app = context.applicationContext
        val sms: SmsManager? = try {
            if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        } catch (e: Exception) {
            null
        }
        if (sms == null) {
            resultado("SMS não enviado: este aparelho não envia SMS")
            return
        }

        var recetorRegistado: BroadcastReceiver? = null
        try {
            val partes = sms.divideMessage(texto)   // SMS longos são enviados em várias partes
            var porConfirmar = partes.size
            var erro: String? = null

            // O Android avisa (uma vez por parte) se o SMS saiu ou não
            val recetor = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK && erro == null) erro = descreverErro(resultCode)
                    porConfirmar--
                    if (porConfirmar <= 0) {
                        try { app.unregisterReceiver(this) } catch (_: Exception) { }
                        resultado(erro?.let { "SMS não enviado: $it" } ?: "SMS enviado para $numero")
                    }
                }
            }
            ContextCompat.registerReceiver(
                app, recetor, IntentFilter(ACAO_ENVIADO), ContextCompat.RECEIVER_NOT_EXPORTED
            )
            recetorRegistado = recetor

            val avisos = ArrayList<PendingIntent>()
            for (k in partes.indices) {
                avisos += PendingIntent.getBroadcast(
                    app, k,
                    Intent(ACAO_ENVIADO).setPackage(app.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            }
            sms.sendMultipartTextMessage(numero, null, partes, avisos, null)
        } catch (e: Throwable) {
            recetorRegistado?.let { try { app.unregisterReceiver(it) } catch (_: Exception) { } }
            resultado("SMS não enviado: ${Erros.curto(e)}")
        }
    }

    private fun descreverErro(codigo: Int): String = when (codigo) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "sem rede móvel"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "modo de voo / rede desligada"
        SmsManager.RESULT_ERROR_NULL_PDU -> "mensagem inválida"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "falha geral (sem SIM ou sem saldo?)"
        else -> "erro $codigo"
    }
}
