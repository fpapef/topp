package com.fpapef.topp.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sign

/**
 * Calcul pur (testable) : écart de la main par rapport à sa position de départ → joystick.
 * La « direction de la main » est l'axe de la montre qui pointe vers les doigts (montre au poignet gauche).
 */
object Gesture {
    /** En dessous de cet écart (degrés), rien ne bouge : évite les tremblements. */
    const val DEAD_ZONE = 6f
    /** Écart (au-delà de la zone morte) qui donne la vitesse maximale, à sensibilité 1. */
    const val FULL_RANGE = 30f

    fun axis(deg: Float, sensitivity: Float): Float {
        val a = abs(deg) - DEAD_ZONE
        if (a <= 0f) return 0f
        return (deg.sign * a / (FULL_RANGE / sensitivity.coerceIn(0.3f, 3f))).coerceIn(-1f, 1f)
    }

    /**
     * [dYaw] : la main tourne vers la gauche si positif (sens trigonométrique vu du dessus).
     * [dElev] : la main monte si positif.
     * Résultat en coordonnées d'écran, comme le joystick : x > 0 = droite, y > 0 = bas.
     */
    fun vector(dYaw: Float, dElev: Float, sensitivity: Float, invertX: Boolean, invertY: Boolean): Pair<Float, Float> {
        var x = axis(-dYaw, sensitivity)
        var y = axis(-dElev, sensitivity)
        if (invertX) x = -x
        if (invertY) y = -y
        return x to y
    }

    fun wrap180(deg: Float): Float {
        var a = deg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
}

/** Lit l'orientation de la montre (gyroscope + accéléromètre, sans boussole). */
class GestureTracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private var yaw0 = Float.NaN
    private var elev0 = 0f

    @Volatile var dYaw = 0f
        private set
    @Volatile var dElev = 0f
        private set

    val available get() = sensor != null

    fun start() {
        yaw0 = Float.NaN
        dYaw = 0f
        dElev = 0f
        sensor?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm?.unregisterListener(this)
        dYaw = 0f
        dElev = 0f
    }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Axe X de la montre (vers les doigts) exprimé dans le repère du monde = 1re colonne
        val x = rot[0]
        val y = rot[3]
        val z = rot[6]
        val yaw = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
        val elev = Math.toDegrees(asin(z.coerceIn(-1f, 1f)).toDouble()).toFloat()
        if (yaw0.isNaN()) {
            yaw0 = yaw
            elev0 = elev
        }
        dYaw = Gesture.wrap180(yaw - yaw0)
        dElev = elev - elev0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
