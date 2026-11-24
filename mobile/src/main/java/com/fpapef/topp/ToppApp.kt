package com.fpapef.topp

import android.app.Application
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class ToppApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val logs = MutableStateFlow<List<String>>(emptyList())
    val recording = MutableStateFlow(false)
    /** Début de l'enregistrement en cours (horloge elapsedRealtime). */
    val recStartedAt = MutableStateFlow(0L)
    /** La montre pilote la nacelle avec les mouvements du poignet. */
    val gesturesActive = MutableStateFlow(false)
    /** Commandes à exécuter par l'écran (caméra) : "rec". */
    val uiCommands = MutableSharedFlow<String>(extraBufferCapacity = 16)

    lateinit var ble: GimbalBle
        private set
    lateinit var controller: Controller
        private set
    lateinit var wear: WearBridge
        private set
    lateinit var follow: Follow
        private set

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        logs.update { (it + line).takeLast(200) }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("topp", MODE_PRIVATE)
        ble = GimbalBle(this, scope, ::log)
        controller = Controller(ble, scope, prefs, ::log)
        follow = Follow(this, ble, controller, scope, prefs, ::log)
        wear = WearBridge(
            this, scope,
            onJoystick = controller::joystick,
            onCommand = ::onWatchCommand,
            onMotion = follow::onWatchMotion,
            onGps = follow::onWatchGps,
        )
        controller.start()
        follow.startLoop()
        wear.start()

        scope.launch {
            ble.frames.collect { f ->
                when (f.cmd) {
                    Hohem.CMD_SHUTTER -> uiCommands.tryEmit("rec")
                    Hohem.CMD_RECENTER_EVENT -> log("Recentrage demandé sur la nacelle")
                }
            }
        }
        scope.launch {
            val followSummary = follow.ui.map { Triple(it.on, it.text(true), it.kind) }.distinctUntilChanged()
            combine(ble.state, recording, controller.moving, followSummary) { s, r, m, _ -> watchState(s, r, m) }
                .distinctUntilChanged()
                .conflate()
                .collect {
                    wear.sendState(it)
                    delay(250)
                }
        }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (gesturesActive.value && SystemClock.elapsedRealtime() - wear.lastWatchMessage.value > 3_000) {
                    gesturesActive.value = false
                }
                wear.sendState(watchState(ble.state.value, recording.value, controller.moving.value))
            }
        }
    }

    private var lastWatchHello = 0L

    private fun onWatchCommand(cmd: String) {
        when (cmd) {
            "rec" -> uiCommands.tryEmit("rec")
            "flip" -> uiCommands.tryEmit("flip")
            "g1" -> { gesturesActive.value = true; log("Gestes de la montre activés") }
            "g0" -> { gesturesActive.value = false; log("Gestes de la montre coupés") }
            "mode" -> cycleMode()
            "center" -> controller.recenter()
            "t1" -> follow.start()
            "t0" -> follow.stop()
            "gps-refus" -> log("Position de la montre refusée : autorise-la sur la montre pour le GPS")
            "hello" -> {
                val now = SystemClock.elapsedRealtime()
                if (now - lastWatchHello > 10_000) log("Montre connectée ✓")
                lastWatchHello = now
                wear.sendState(watchState(ble.state.value, recording.value, controller.moving.value))
            }
        }
    }

    fun cycleMode() {
        if (!ble.isReady) { log("Nacelle non connectée"); return }
        val next = Hohem.nextMode(ble.state.value.mode)
        ble.send(Hohem.setMode(next))
        log("Mode ${Hohem.modeLabel(next)}")
    }

    private fun watchState(s: GimbalBle.State, rec: Boolean, moving: Boolean): String {
        val j = JSONObject()
            .put("g", s.status == GimbalBle.Status.READY)
            .put("t", statusLabel(s.status, controller.recentering))
            .put("m", Hohem.modeLabel(s.mode))
            .put("r", rec)
            .put("c", controller.recentering)
            .put("v", moving)
            .put("gm", if (controller.gestureHold) "hold" else "toggle")
            .put("gs", controller.gestureSens.toDouble())
            .put("gix", controller.gestureInvX)
            .put("giy", controller.gestureInvY)
        s.attitude?.let { a ->
            j.put("p", Hohem.wrap180(a.encPan - controller.homePan).roundToInt())
            j.put("ti", (a.encTilt - controller.homeTilt).roundToInt())
        }
        if (rec && recStartedAt.value > 0) j.put("rs", (SystemClock.elapsedRealtime() - recStartedAt.value) / 1000)
        val f = follow.ui.value
        j.put("tr", f.on)
        if (f.on) {
            j.put("ts", f.text(true))
            j.put("tk", f.kind)
            j.put("wm", f.needsMotion)
            j.put("wg", f.needsGps)
        }
        return j.toString()
    }
}
