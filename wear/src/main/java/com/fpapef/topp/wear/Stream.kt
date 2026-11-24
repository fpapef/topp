package com.fpapef.topp.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer
import kotlin.math.sqrt

/**
 * Ce que la montre envoie au téléphone pendant le suivi :
 * l'agitation du poignet (gyroscope, toutes les 100 ms) pour te reconnaître à l'image,
 * et la position GPS (chaque seconde) pour viser quand tu sors du champ.
 */
class Stream(private val ctx: Context, private val scope: CoroutineScope) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private val messages = Wearable.getMessageClient(ctx)
    private val nodeClient = Wearable.getNodeClient(ctx)
    @Volatile private var nodes: List<String> = emptyList()

    private var sum = 0f
    private var cnt = 0
    private var job: Job? = null
    var motion = false
        private set
    var gps = false
        private set

    val hasLocation: Boolean
        get() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private val locListener = LocationListener { loc -> sendFix(loc) }

    @SuppressLint("MissingPermission")
    fun update(wantMotion: Boolean, wantGps: Boolean) {
        val g = wantGps && hasLocation && lm != null
        if (wantMotion != motion) {
            if (wantMotion && gyro != null) sm?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
            else sm?.unregisterListener(this)
            motion = wantMotion && gyro != null
        }
        if (g != gps) {
            if (g) {
                gps = runCatching {
                    lm!!.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, locListener, Looper.getMainLooper())
                }.isSuccess
            } else {
                runCatching { lm?.removeUpdates(locListener) }
                gps = false
            }
        }
        if ((motion || gps) && job == null) job = scope.launch { loop() }
        if (!motion && !gps) { job?.cancel(); job = null }
    }

    fun stop() = update(wantMotion = false, wantGps = false)

    private suspend fun loop() {
        var lastNodes = 0L
        val buf = FloatArray(2)
        var n = 0
        while (scope.isActive) {
            delay(Correl_STEP)
            val now = SystemClock.elapsedRealtime()
            if (now - lastNodes > 5_000 || nodes.isEmpty()) {
                nodes = runCatching { nodeClient.connectedNodes.await().map { it.id } }.getOrDefault(nodes)
                lastNodes = now
            }
            if (!motion) continue
            val v = synchronized(this) {
                val r = if (cnt > 0) sum / cnt else 0f
                sum = 0f; cnt = 0
                r
            }
            buf[n++] = v
            if (n == buf.size) {
                n = 0
                // Heure du centre de la dernière tranche, sur l'horloge de la montre
                val b = ByteBuffer.allocate(12 + 4 * buf.size).putLong(now - Correl_STEP / 2).putInt(buf.size)
                buf.forEach { b.putFloat(it) }
                send(PATH_MOTION, b.array())
            }
        }
    }

    private fun sendFix(loc: Location) {
        val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        val b = ByteBuffer.allocate(36)
            .putDouble(loc.latitude)
            .putDouble(loc.longitude)
            .putFloat(if (loc.hasAccuracy()) loc.accuracy else 20f)
            .putFloat(if (loc.hasSpeed()) loc.speed else -1f)
            .putFloat(if (loc.hasBearing()) loc.bearing else -1f)
            .putLong(age.coerceAtLeast(0))
        send(PATH_GPS, b.array())
    }

    private fun send(path: String, data: ByteArray) {
        val targets = nodes
        scope.launch(Dispatchers.IO) {
            for (id in targets) runCatching { messages.sendMessage(id, path, data).await() }
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        synchronized(this) { sum += m; cnt++ }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val PATH_MOTION = "/topp/mot"
        const val PATH_GPS = "/topp/gps"
        private const val Correl_STEP = 100L
    }
}
