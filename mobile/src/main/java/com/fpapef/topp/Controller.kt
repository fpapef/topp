package com.fpapef.topp

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Pilote la nacelle avec un « cadre virtuel » : la nacelle croit suivre un sujet décalé du centre
 * de l'image et tourne vers lui. Joystick vers la droite = sujet à droite = la nacelle tourne à droite.
 */
class Controller(
    private val ble: GimbalBle,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val log: (String) -> Unit,
) {
    @Volatile private var jx = 0f
    @Volatile private var jy = 0f
    @Volatile private var joyAt = 0L
    @Volatile var recentering = false
        private set

    val moving = MutableStateFlow(false)

    var speed: Float
        get() = prefs.getFloat("speed", 0.35f)
        set(v) = prefs.edit().putFloat("speed", v).apply()
    var invertX: Boolean
        get() = prefs.getBoolean("invX", false)
        set(v) = prefs.edit().putBoolean("invX", v).apply()
    var invertY: Boolean
        get() = prefs.getBoolean("invY", false)
        set(v) = prefs.edit().putBoolean("invY", v).apply()

    /** Gestes de la montre : true = actifs tant qu'on maintient le bouton, false = un appui marche/arrêt. */
    var gestureHold: Boolean
        get() = prefs.getBoolean("gHold", false)
        set(v) = prefs.edit().putBoolean("gHold", v).apply()
    var gestureSens: Float
        get() = prefs.getFloat("gSens", 1f)
        set(v) = prefs.edit().putFloat("gSens", v).apply()
    var gestureInvX: Boolean
        get() = prefs.getBoolean("gInvX", false)
        set(v) = prefs.edit().putBoolean("gInvX", v).apply()
    var gestureInvY: Boolean
        get() = prefs.getBoolean("gInvY", false)
        set(v) = prefs.edit().putBoolean("gInvY", v).apply()

    /**
     * Caméra frontale : le moteur du tilt penche tout le téléphone, donc quand l'objectif arrière
     * monte, l'objectif frontal descend. Avec la correction automatique, on inverse le haut et le bas
     * du joystick et des gestes pour que « vers le haut » reste vers le haut à l'image.
     */
    var autoCorrectFront: Boolean
        get() = prefs.getBoolean("autoFront", true)
        set(v) = prefs.edit().putBoolean("autoFront", v).apply()
    @Volatile var frontCamera = false

    val homePan: Float get() = prefs.getFloat("homePan", 180f)
    val homeTilt: Float get() = prefs.getFloat("homeTilt", 91.3f)

    /** x, y entre -1 et 1, en coordonnées d'écran (y vers le bas). */
    fun joystick(x: Float, y: Float) {
        jx = x.coerceIn(-1f, 1f)
        jy = y.coerceIn(-1f, 1f)
        joyAt = SystemClock.elapsedRealtime()
    }

    private fun curve(v: Float) = 0.3f * v + 0.7f * v * abs(v)

    /** Décalage envoyé à la nacelle pour une position du joystick (ou des gestes). */
    internal fun offsetFor(x: Float, y: Float): Pair<Float, Float> {
        val sx = if (invertX) -1f else 1f
        val sy = (if (invertY) -1f else 1f) * (if (frontCamera && autoCorrectFront) -1f else 1f)
        return sx * curve(x) * speed to sy * curve(y) * speed
    }

    fun start() = scope.launch {
        var was = false
        var clears = 0
        while (isActive) {
            delay(33)
            if (recentering) { was = false; clears = 0; continue }
            val fresh = SystemClock.elapsedRealtime() - joyAt < 500
            val x = if (fresh) jx else 0f
            val y = if (fresh) jy else 0f
            val on = abs(x) > 0.05f || abs(y) > 0.05f
            if (on) {
                val (ox, oy) = offsetFor(x, y)
                ble.sendTrack(Hohem.trackOffset(ox, oy))
                clears = 3
            } else if (clears > 0) {
                ble.sendTrack(Hohem.trackClear())
                clears--
            }
            if (on != was) moving.value = on
            was = on
        }
    }

    fun resetLearning() {
        prefs.edit().remove("signPan").remove("signTilt").apply()
        log("Sens du recentrage oublié : il sera réappris au prochain recentrage")
    }

    fun setHomeHere() = scope.launch {
        val a = ble.readAttitude() ?: run { log("Angles illisibles"); return@launch }
        prefs.edit().putFloat("homePan", a.encPan).putFloat("homeTilt", a.encTilt).apply()
        log("Nouveau centre : pan %.1f°, tilt %.1f°".format(a.encPan, a.encTilt))
    }

    fun recenter() {
        if (recentering || !ble.isReady) return
        scope.launch { doRecenter() }
    }

    /**
     * Recentrage calculé : on lit les angles des moteurs, on apprend une fois le sens de rotation
     * (petite poussée d'essai), puis on ramène la caméra vers la position de repos.
     */
    private suspend fun doRecenter() {
        recentering = true
        try {
            ble.sendTrack(Hohem.trackClear())
            delay(80)
            var a = ble.readAttitude() ?: run { log("Recentrage : angles illisibles"); return }
            fun ePan(t: Hohem.Attitude) = Hohem.wrap180(t.encPan - homePan)
            fun eTilt(t: Hohem.Attitude) = t.encTilt - homeTilt
            var sp = prefs.getInt("signPan", 0)
            var st = prefs.getInt("signTilt", 0)
            log("Recentrage : écart pan %.1f°, tilt %.1f°".format(ePan(a), eTilt(a)))

            val probeX = if (sp == 0 && abs(ePan(a)) > 2f) 0.2f else 0f
            val probeY = if (st == 0 && abs(eTilt(a)) > 2f) 0.2f else 0f
            if (probeX != 0f || probeY != 0f) {
                repeat(12) { ble.sendTrack(Hohem.trackOffset(probeX, probeY)); delay(33) }
                ble.sendTrack(Hohem.trackClear())
                delay(200)
                val b = ble.readAttitude() ?: return
                val dPan = Hohem.wrap180(b.encPan - a.encPan)
                val dTilt = b.encTilt - a.encTilt
                if (probeX != 0f && abs(dPan) > 0.7f) { sp = dPan.sign.toInt(); prefs.edit().putInt("signPan", sp).apply() }
                if (probeY != 0f && abs(dTilt) > 0.7f) { st = dTilt.sign.toInt(); prefs.edit().putInt("signTilt", st).apply() }
                log("Sens appris : pan $sp, tilt $st")
                a = b
            }

            val deadline = SystemClock.elapsedRealtime() + 8_000
            var i = 0
            while (SystemClock.elapsedRealtime() < deadline) {
                val ep = ePan(a)
                val et = eTilt(a)
                val doneP = sp == 0 || abs(ep) < 1.5f
                val doneT = st == 0 || abs(et) < 1.5f
                if (doneP && doneT) break
                val x = if (doneP) 0f else clampMag(-sp * ep * 0.012f)
                val y = if (doneT) 0f else clampMag(-st * et * 0.012f)
                ble.sendTrack(Hohem.trackOffset(x, y))
                delay(33)
                if (++i % 3 == 0) a = ble.readAttitude(300) ?: a
            }
            log("Recentrage terminé (pan %.1f°, tilt %.1f°)".format(ePan(a), eTilt(a)))
        } finally {
            ble.sendTrack(Hohem.trackClear())
            recentering = false
        }
    }

    private fun clampMag(v: Float): Float {
        val m = abs(v).coerceIn(0.04f, 0.4f)
        return if (v < 0) -m else m
    }
}
