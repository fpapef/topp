package com.fpapef.topp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Capteurs du téléphone utiles au suivi : orientation de la caméra (gyroscope, sans dérive
 * magnétique), boussole pour un premier cap, et position GPS du téléphone.
 */
class PhoneSensors(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val game = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rot = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val lm = ctx.getSystemService(LocationManager::class.java)

    private val gameR = FloatArray(9)
    private val magR = FloatArray(9)
    @Volatile private var gameAt = 0L
    @Volatile private var hasMag = false
    private val compass = AngleAvg(0.05f)
    private val fix = FixAvg()
    @Volatile private var declination = 0f
    private var running = false
    private var locating = false

    val hasRotation: Boolean get() = game != null

    val locationGranted: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val locListener = LocationListener { loc ->
        synchronized(fix) {
            if (fix.n == 0) {
                declination = GeomagneticField(
                    loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis(),
                ).declination
            }
            fix.add(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 20f, SystemClock.elapsedRealtime())
        }
    }

    private val users = HashSet<String>()

    /** Les capteurs d'orientation tournent tant qu'au moins un utilisateur les demande (suivi, écran). */
    @Synchronized
    fun acquire(who: String) {
        users += who
        if (running || sm == null) return
        running = true
        game?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        rot?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    @Synchronized
    fun release(who: String) {
        users -= who
        if (users.isNotEmpty() || !running) return
        sm?.unregisterListener(this)
        running = false
        gameAt = 0L
        hasMag = false
        compass.reset()
    }

    @SuppressLint("MissingPermission")
    fun startLocation(): Boolean {
        if (locating) return true
        if (!locationGranted || lm == null) return false
        return runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locListener, Looper.getMainLooper())
            locating = true
        }.isSuccess
    }

    fun stopLocation() {
        if (locating) runCatching { lm?.removeUpdates(locListener) }
        locating = false
        synchronized(fix) { fix.reset() }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                synchronized(gameR) { SensorManager.getRotationMatrixFromVector(gameR, e.values) }
                gameAt = SystemClock.elapsedRealtime()
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                synchronized(magR) { SensorManager.getRotationMatrixFromVector(magR, e.values) }
                hasMag = true
                if (SystemClock.elapsedRealtime() - gameAt < 500) {
                    val g = synchronized(gameR) { cameraDirection(gameR, false) }
                    val m = synchronized(magR) { cameraDirection(magR, false) }
                    // Le cap n'a de sens que si la caméra ne vise pas le ciel ou le sol
                    if (kotlin.math.abs(g.second) < 60f) compass.add(m.first + declination - g.first)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** (azimut, élévation) de la caméra active, dans le repère du gyroscope. */
    fun orientation(front: Boolean): Pair<Float, Float>? {
        if (SystemClock.elapsedRealtime() - gameAt > 500) return null
        return synchronized(gameR) { cameraDirection(gameR, front) }
    }

    /** Écart entre le repère du gyroscope et le nord vrai, d'après la boussole (approximatif). */
    fun compassOffset(): Float? = compass.value?.takeIf { compass.count >= 10 }

    data class Fix(val lat: Double, val lon: Double, val acc: Float)

    fun phoneFix(): Fix? = synchronized(fix) {
        if (fix.n == 0 || SystemClock.elapsedRealtime() - fix.lastAt > 10_000) null else Fix(fix.lat, fix.lon, fix.acc)
    }
}
