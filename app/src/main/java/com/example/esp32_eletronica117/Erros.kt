package com.example.esp32_eletronica117

import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Se a app crashar, guarda o erro (para o mostrar da próxima vez que a app abrir),
 * sem ser preciso o Logcat do Android Studio.
 */
object Erros {
    private const val CHAVE = "ultimo_erro"
    @Volatile private var instalado = false

    fun instalar(context: Context) {
        if (instalado) return
        instalado = true
        val app = context.applicationContext
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            try {
                val sw = StringWriter()
                erro.printStackTrace(PrintWriter(sw))
                val hora = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
                app.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(CHAVE, "[$hora] thread ${thread.name}\n$sw").commit()
            } catch (_: Throwable) { }
            anterior?.uncaughtException(thread, erro)
        }
    }

    /** Devolve (e apaga) o último erro guardado, ou null. */
    fun lerEApagar(context: Context): String? {
        val prefs = context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
        val e = prefs.getString(CHAVE, null) ?: return null
        prefs.edit().remove(CHAVE).apply()
        return e
    }

    /** Texto curto de uma exceção, para mostrar num aviso. */
    fun curto(e: Throwable): String = "${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
}
