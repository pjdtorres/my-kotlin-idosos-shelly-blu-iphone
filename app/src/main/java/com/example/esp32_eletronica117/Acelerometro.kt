package com.example.esp32_eletronica117

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.example.idosos.shared.FallDetector
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Lê o acelerómetro do telemóvel e entrega:
 *  - X, Y, Z SEM gravidade (m/s²): parado ≈ 0, 0, 0
 *  - inclinação (graus): 0° = deitado de face para cima, 90° = em pé, 180° = face para baixo
 *  - aviso de QUEDA: queda livre (aceleração total ≈ 0) seguida de impacto forte
 *  - aviso de POSIÇÃO: quando o telemóvel fica / deixa de ficar na horizontal
 *  - rotação (°/s): velocidade de rotação total, lida do giroscópio ([rotacao])
 *  - yaw (graus 0..360): rotação em torno do eixo Z, do sensor "rotation vetor" ([yaw])
 *
 * Uso:
 *   val acel = Acelerómetro(this) {x, y, z, inclinação -> ...
 *   acel.ouvinteQueda = {...
 *   acel.ouvintePosicao = {horizontal -> ...
 *   acel.iniciar() // no onResume()
 *   acel.parar() // no onPause()
 */
class Acelerometro(
    context: Context,
    /** Recebe os valores sem gravidade e a inclinação. */
    private val ouvinte: (x: Float, y: Float, z: Float, inclinacao: Float) -> Unit
) : SensorEventListener {

    companion object {
        // ---- Parâmetros da posição horizontal (ajustáveis) ----
        private const val TOLERANCIA_HORIZONTAL = 15f    // graus em relação a 0° ou 180°

        // Filtro para separar a gravidade (passa-baixo)
        private const val ALFA = 0.8f

        private fun graus(rad: Double): Float = Math.toDegrees(rad).toFloat()

        private fun entre180(valor: Float): Float {
            var g = valor % 360f
            if (g > 180f) g -= 360f
            if (g < -180f) g += 360f
            return g
        }
    }

    /** Recebe o aviso de queda. */
    var ouvinteQueda: (() -> Unit)? = null

    /** Recebe true quando o telemóvel fica na horizontal, false quando sai. */
    var ouvintePosicao: ((horizontal: Boolean) -> Unit)? = null

    // Gravidade na posição de referência (fica guardada: sobrevive a reiniciar a app)
    private val prefs = context.getSharedPreferences(MonitorService.PREFS, Context.MODE_PRIVATE)
    private val refGravidade = floatArrayOf(
        prefs.getFloat("ref_gx", 0f), prefs.getFloat("ref_gy", 0f), prefs.getFloat("ref_gz", 0f)
    )
    @Volatile private var temRefGravidade = prefs.getBoolean("ref_g_ok", false)

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val giroscopio: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Preferir o rotation vetor com bússola (yaw relativamente ao norte);
    // sem magnetómetro usa o "game" (yaw relativo ao arranque)
    private val vetorRotacao: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    /** Rotação (°/s): velocidade de rotação total à volta dos 3 eixos (giroscópio). */
    @Volatile
    var rotacao = 0f
        private set

    @Volatile
    private var yawBruto = Float.NaN          // graus 0..360; NaN = ainda sem leitura
    private val matrizRotacao = FloatArray(9)
    private val orientacao = FloatArray(3)

    // Referência (botão "Definir referência" da página Mapa -> ZERO em esp32/cmd)
    @Volatile private var temReferencia = false
    @Volatile private var refRoll = 0f
    @Volatile private var refPitch = 0f
    @Volatile private var refYaw = 0f

    // Estado da posição
    private var horizontal: Boolean? = null   // null = ainda não se sabe

    private val gravidade = FloatArray(3)
    private var primeiraLeitura = true

    private val fallDetector = FallDetector()

    /**
     * Yaw (graus): rotação em torno do eixo Z. NaN se ainda não houver leitura.
     * Sem referência: 0..360. Depois de definirReferencia(): −180..180 em relação à referência.
     */
    val yaw: Float
        get() {
            val y = yawBruto
            return if (y.isNaN() || !temReferencia) y else entre180(y - refYaw)
        }

    /** Roll (graus): rotação em torno do eixo X, relativamente à referência (se houver). */
    val roll: Float
        get() {
            val r = rollAbsoluto()
            return if (temReferencia) entre180(r - refRoll) else r
        }

    /** Pitch (graus): rotação em torno do eixo Y, relativamente à referência (se houver). */
    val pitch: Float
        get() {
            val p = pitchAbsoluto()
            return if (temReferencia) p - refPitch else p
        }

    /**
     * Inclinação (graus, 0..180) EM RELAÇÃO À POSIÇÃO DE REFERÊNCIA: ângulo entre a gravidade
     * agora e a gravidade quando se carregou em "Definir referência". NaN se não houver referência.
     */
    val inclinacaoReferencia: Float
        get() {
            if (!temRefGravidade || primeiraLeitura) return Float.NaN
            val g = gravidade
            val r = refGravidade
            val ng = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            val nr = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            if (ng < 0.1f || nr < 0.1f) return Float.NaN
            val c = ((g[0] * r[0] + g[1] * r[1] + g[2] * r[2]) / (ng * nr)).coerceIn(-1f, 1f)
            return graus(acos(c.toDouble()))
        }

    /** A posição atual passa a ser roll 0, pitch 0, yaw 0 (como o ZERO do BNO085). */
    fun definirReferencia() {
        if (!primeiraLeitura) {
            refGravidade[0] = gravidade[0]; refGravidade[1] = gravidade[1]; refGravidade[2] = gravidade[2]
            temRefGravidade = true
            prefs.edit()
                .putFloat("ref_gx", refGravidade[0]).putFloat("ref_gy", refGravidade[1])
                .putFloat("ref_gz", refGravidade[2]).putBoolean("ref_g_ok", true)
                .apply()
        }
        refRoll = rollAbsoluto()
        refPitch = pitchAbsoluto()
        refYaw = if (yawBruto.isNaN()) 0f else yawBruto
        temReferencia = true
    }

    /** True se o telemóvel tiver giroscópio. */
    fun existeGiroscopio(): Boolean = giroscopio != null

    /** True se o telemóvel tiver acelerómetro. */
    fun existe(): Boolean = sensor != null

    private fun rollAbsoluto(): Float =
        graus(atan2(gravidade[1].toDouble(), gravidade[2].toDouble()))

    private fun pitchAbsoluto(): Float =
        graus(atan2(-gravidade[0].toDouble(),
            sqrt((gravidade[1] * gravidade[1] + gravidade[2] * gravidade[2]).toDouble())))

    fun iniciar() {
        sensor?.let {
            primeiraLeitura = true
            horizontal = null
            // GAME = ~50 leituras/s, rápido o suficiente para apanhar a queda
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        giroscopio?.let {
            rotacao = 0f
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        vetorRotacao?.let {
            yawBruto = Float.NaN
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun parar() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            // Giroscópio: só guarda a rotação (rad/s -> °/s); o ecrã atualiza com o acelerómetro
            Sensor.TYPE_GYROSCOPE -> {
                val (gx, gy, gz) = event.values
                rotacao = graus(sqrt((gx * gx + gy * gy + gz * gz).toDouble()))
                return
            }
            // Rotation vetor: só guarda o yaw (azimute) em graus 0..360
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(matrizRotacao, event.values)
                SensorManager.getOrientation(matrizRotacao, orientacao)
                val g = graus(orientacao[0].toDouble())   // -180..180
                yawBruto = (g + 360f) % 360f
                return
            }
        }

        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val agora = event.timestamp / 1_000_000   // ns -> ms

        // 1) Separar a gravidade (componente lenta) do movimento (componente rápida)
        if (primeiraLeitura) {
            gravidade[0] = ax; gravidade[1] = ay; gravidade[2] = az
            primeiraLeitura = false
        } else {
            gravidade[0] = ALFA * gravidade[0] + (1 - ALFA) * ax
            gravidade[1] = ALFA * gravidade[1] + (1 - ALFA) * ay
            gravidade[2] = ALFA * gravidade[2] + (1 - ALFA) * az
        }
        val x = ax - gravidade[0]
        val y = ay - gravidade[1]
        val z = az - gravidade[2]

        // 2) Inclinação: ângulo entre o telemóvel e a horizontal (a partir da gravidade)
        val g = sqrt(gravidade[0] * gravidade[0] + gravidade[1] * gravidade[1] + gravidade[2] * gravidade[2])
        var inclinacao = 0f
        if (g > 0.1f) {
            val c = (gravidade[2] / g).coerceIn(-1f, 1f)
            inclinacao = graus(acos(c.toDouble()))
        }

        ouvinte(x, y, z, inclinacao)

        // 3) Deteção de queda (usa a aceleração total, COM gravidade)
        val total = sqrt(ax * ax + ay * ay + az * az)
        detetarQueda(total, agora)

        // 4) Posição horizontal
        detetarPosicao(inclinacao)
    }

    private fun detetarPosicao(inclinacao: Float) {
        // Horizontal = menos de 15° (face para cima) ou mais de 165° (face para baixo)
        val novo = inclinacao < TOLERANCIA_HORIZONTAL || inclinacao > 180f - TOLERANCIA_HORIZONTAL
        if (horizontal != novo) {   // só avisa quando muda (ou na primeira leitura)
            horizontal = novo
            ouvintePosicao?.invoke(novo)
        }
    }

    private fun detetarQueda(total: Float, agora: Long) {
        if (fallDetector.update(total, agora)) ouvinteQueda?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // não é necessário
    }
}
