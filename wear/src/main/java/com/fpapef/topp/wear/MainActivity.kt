package com.fpapef.topp.wear

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import android.widget.Toast
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.nio.ByteBuffer

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private val messages by lazy { Wearable.getMessageClient(this) }
    private val nodeClient by lazy { Wearable.getNodeClient(this) }
    @Volatile private var nodes: List<String> = emptyList()

    private val phone = mutableStateOf(PhoneState())
    private val lastStateAt = mutableLongStateOf(0L)
    private val now = mutableLongStateOf(SystemClock.elapsedRealtime())
    private var recBase = 0L
    private var recBaseAt = 0L

    @Volatile private var joyX = 0f
    @Volatile private var joyY = 0f
    @Volatile private var touching = false

    private val tracker by lazy { GestureTracker(this) }
    private val gestureOn = mutableStateOf(false)
    private val gestureVec = mutableStateOf(Offset.Zero)

    private val commands = Channel<String>(Channel.UNLIMITED)
    private val joyOut = Channel<ByteArray>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val phoneOk = lastStateAt.longValue != 0L && now.longValue - lastStateAt.longValue < 6_000
            val recSeconds = if (phone.value.recording) recBase + (now.longValue - recBaseAt) / 1000 else 0L
            WatchScreen(
                state = phone.value,
                phoneOk = phoneOk,
                recSeconds = recSeconds,
                gestureOn = gestureOn.value,
                gestureVec = gestureVec.value,
                onJoy = { x, y, down -> joyX = x; joyY = y; touching = down },
                onCommand = { commands.trySend(it) },
                onGesture = ::setGesture,
            )
        }

        // Liste des appareils connectés (le téléphone) + signe de vie
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                nodes = runCatching { nodeClient.connectedNodes.await().map { it.id } }.getOrDefault(emptyList())
                commands.trySend("hello")
                delay(4_000)
            }
        }
        // Horloge pour détecter un téléphone muet
        lifecycleScope.launch {
            while (isActive) { delay(500); now.longValue = SystemClock.elapsedRealtime() }
        }
        // Joystick : 14 messages/s pendant l'appui ou pendant les gestes, puis deux zéros à l'arrêt.
        // Le doigt sur le cadran passe toujours avant les gestes.
        lifecycleScope.launch {
            var zeros = 0
            while (isActive) {
                delay(70)
                if (touching) {
                    joyOut.trySend(floats(joyX, joyY))
                    zeros = 2
                } else if (gestureOn.value) {
                    val st = phone.value
                    val (x, y) = Gesture.vector(tracker.dYaw, tracker.dElev, st.gestureSens, st.gestureInvX, st.gestureInvY)
                    gestureVec.value = Offset(x, y)
                    joyOut.trySend(floats(x, y))
                    zeros = 2
                } else if (zeros > 0) {
                    joyOut.trySend(floats(0f, 0f))
                    zeros--
                }
            }
        }
        // Envoi séquentiel : les commandes passent avant le joystick
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val (path, data) = commands.tryReceive().getOrNull()?.let { PATH_CMD to it.toByteArray() }
                    ?: select {
                        commands.onReceive { PATH_CMD to it.toByteArray() }
                        joyOut.onReceive { PATH_JOY to it }
                    }
                for (n in nodes) runCatching { messages.sendMessage(n, path, data).await() }
            }
        }
    }

    /** Active ou coupe le pilotage par les mouvements du poignet. */
    private fun setGesture(on: Boolean) {
        if (on == gestureOn.value) return
        if (on) {
            if (!tracker.available) {
                Toast.makeText(this, "Capteur de mouvement indisponible", Toast.LENGTH_SHORT).show()
                return
            }
            tracker.start()
        } else {
            tracker.stop()
            gestureVec.value = Offset.Zero
        }
        gestureOn.value = on
        commands.trySend(if (on) "g1" else "g0")
    }

    private fun floats(x: Float, y: Float): ByteArray = ByteBuffer.allocate(8).putFloat(x).putFloat(y).array()

    override fun onResume() {
        super.onResume()
        messages.addListener(this)
        commands.trySend("hello")
    }

    override fun onPause() {
        super.onPause()
        messages.removeListener(this)
        setGesture(false)
        touching = false
        joyOut.trySend(floats(0f, 0f))
    }

    override fun onMessageReceived(e: MessageEvent) {
        if (e.path != PATH_STATE) return
        runCatching {
            val j = JSONObject(String(e.data, Charsets.UTF_8))
            phone.value = PhoneState(
                gimbal = j.optBoolean("g"),
                text = j.optString("t"),
                mode = j.optString("m", "?"),
                recording = j.optBoolean("r"),
                recentering = j.optBoolean("c"),
                pan = if (j.has("p")) j.optInt("p") else null,
                tilt = if (j.has("ti")) j.optInt("ti") else null,
                gestureHold = j.optString("gm") == "hold",
                gestureSens = j.optDouble("gs", 1.0).toFloat(),
                gestureInvX = j.optBoolean("gix"),
                gestureInvY = j.optBoolean("giy"),
            )
            if (j.has("rs")) {
                recBase = j.optLong("rs")
                recBaseAt = SystemClock.elapsedRealtime()
            }
            lastStateAt.longValue = SystemClock.elapsedRealtime()
            now.longValue = lastStateAt.longValue
        }
    }

    companion object {
        const val PATH_JOY = "/topp/joy"
        const val PATH_CMD = "/topp/cmd"
        const val PATH_STATE = "/topp/state"
    }
}
