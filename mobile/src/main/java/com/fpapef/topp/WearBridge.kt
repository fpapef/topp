package com.fpapef.topp

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

/** Échanges avec l'appli de la montre (Wear OS Data Layer). */
class WearBridge(
    ctx: Context,
    private val scope: CoroutineScope,
    private val onJoystick: (Float, Float) -> Unit,
    private val onCommand: (String) -> Unit,
    /** (réception, heure montre du dernier échantillon, agitation du poignet en rad/s toutes les 100 ms) */
    private val onMotion: (Long, Long, FloatArray) -> Unit = { _, _, _ -> },
    /** (réception, lat, lon, précision m, vitesse m/s ou −1, cap ° ou −1, âge de la position en ms) */
    private val onGps: (Long, Double, Double, Float, Float, Float, Long) -> Unit = { _, _, _, _, _, _, _ -> },
) : MessageClient.OnMessageReceivedListener {

    private val messages = Wearable.getMessageClient(ctx)
    private val nodes = Wearable.getNodeClient(ctx)

    /** Horodatage du dernier message reçu de la montre. */
    val lastWatchMessage = MutableStateFlow(0L)

    fun start() {
        messages.addListener(this)
    }

    override fun onMessageReceived(e: MessageEvent) {
        val now = SystemClock.elapsedRealtime()
        lastWatchMessage.value = now
        runCatching {
            when (e.path) {
                PATH_JOY -> if (e.data.size >= 8) {
                    val b = ByteBuffer.wrap(e.data)
                    onJoystick(b.float, b.float)
                }
                PATH_CMD -> onCommand(String(e.data, Charsets.UTF_8))
                PATH_MOTION -> if (e.data.size >= 12) {
                    val b = ByteBuffer.wrap(e.data)
                    val tLast = b.long
                    val n = b.int.coerceIn(0, (e.data.size - 12) / 4)
                    onMotion(now, tLast, FloatArray(n) { b.float })
                }
                PATH_GPS -> if (e.data.size >= 36) {
                    val b = ByteBuffer.wrap(e.data)
                    onGps(now, b.double, b.double, b.float, b.float, b.float, b.long)
                }
            }
        }
    }

    fun sendState(payload: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                for (n in nodes.connectedNodes.await()) {
                    messages.sendMessage(n.id, PATH_STATE, payload.toByteArray(Charsets.UTF_8)).await()
                }
            }
        }
    }

    companion object {
        const val PATH_JOY = "/topp/joy"
        const val PATH_CMD = "/topp/cmd"
        const val PATH_STATE = "/topp/state"
        const val PATH_MOTION = "/topp/mot"
        const val PATH_GPS = "/topp/gps"
    }
}
