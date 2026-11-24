package com.fpapef.topp.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Service au premier plan : continue d'envoyer les mouvements du poignet et le GPS
 * même quand l'écran de la montre s'éteint. S'arrête tout seul quand le suivi est coupé
 * sur le téléphone ou que le téléphone ne répond plus.
 */
class FollowService : Service(), MessageClient.OnMessageReceivedListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var stream: Stream
    private val messages by lazy { Wearable.getMessageClient(this) }
    @Volatile private var lastState = SystemClock.elapsedRealtime()
    @Volatile private var offSince = 0L

    override fun onCreate() {
        super.onCreate()
        stream = Stream(this, scope)
        messages.addListener(this)
        scope.launch {
            while (isActive) {
                delay(2_000)
                val now = SystemClock.elapsedRealtime()
                if (now - lastState > 30_000 || (offSince != 0L && now - offSince > 3_000)) {
                    stopSelf()
                    break
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val motion = intent?.getBooleanExtra(EXTRA_MOTION, false) ?: false
        val gps = (intent?.getBooleanExtra(EXTRA_GPS, false) ?: false) && stream.hasLocation
        if (!motion && !gps) { stopSelf(); return START_NOT_STICKY }
        var types = 0
        if (gps) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (motion && Build.VERSION.SDK_INT >= 34) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        val started = runCatching { ServiceCompat.startForeground(this, NOTIF_ID, notification(), types) }.isSuccess
        if (!started) { stopSelf(); return START_NOT_STICKY }
        running = true
        lastState = SystemClock.elapsedRealtime()
        offSince = 0L
        stream.update(motion, gps)
        return START_NOT_STICKY
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != PATH_STATE) return
        lastState = SystemClock.elapsedRealtime()
        val on = runCatching { JSONObject(String(e.data, Charsets.UTF_8)).optBoolean("tr") }.getOrDefault(false)
        offSince = if (on) 0L else (if (offSince == 0L) lastState else offSince)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Suivi", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_follow)
            .setContentTitle("Topp suit tes mouvements")
            .setContentText("La montre aide le téléphone à te garder dans l'image.")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        running = false
        messages.removeListener(this)
        stream.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "suivi"
        private const val NOTIF_ID = 7
        private const val EXTRA_MOTION = "m"
        private const val EXTRA_GPS = "g"
        private const val PATH_STATE = "/topp/state"
        @Volatile var running = false
            private set

        /** Démarre, met à jour ou arrête le service selon ce dont le téléphone a besoin. */
        fun update(ctx: Context, motion: Boolean, gps: Boolean) {
            val i = Intent(ctx, FollowService::class.java)
            if (!motion && !gps) {
                ctx.stopService(i)
                return
            }
            runCatching {
                ContextCompat.startForegroundService(ctx, i.putExtra(EXTRA_MOTION, motion).putExtra(EXTRA_GPS, gps))
            }
        }
    }
}
