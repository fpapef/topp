package com.fpapef.topp

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

enum class FollowMode { CAMERA_WATCH, CAMERA, GPS }

enum class FollowStatus { OFF, LOOKING, IDENTIFY, LEARNING, CAMERA, SEARCHING, GPS, GPS_WAIT, TOO_CLOSE, LOST, NO_GIMBAL }

data class PersonUi(val id: Int, val box: Box, val target: Boolean)

data class FollowUi(
    val on: Boolean = false,
    val mode: FollowMode = FollowMode.CAMERA_WATCH,
    val status: FollowStatus = FollowStatus.OFF,
    /** Personnes vues, dans le repère de l'image analysée (non retournée). */
    val people: List<PersonUi> = emptyList(),
    /** Caméra avant : l'aperçu est un miroir, les cadres aussi. */
    val mirror: Boolean = false,
    /** Largeur / hauteur de l'image analysée, dans le sens de l'écran. */
    val aspect: Float = 16f / 9f,
    /** La cible est reconnue comme le porteur de la montre. */
    val you: Boolean = false,
    val distance: Int? = null,
) {
    val needsMotion: Boolean get() = on && mode == FollowMode.CAMERA_WATCH
    val needsGps: Boolean get() = on && mode != FollowMode.CAMERA

    /** Code court pour la montre : couleur et vibrations. */
    val kind: String
        get() = when (status) {
            FollowStatus.CAMERA, FollowStatus.GPS -> "ok"
            FollowStatus.IDENTIFY -> "id"
            FollowStatus.LEARNING -> "cal"
            FollowStatus.LOOKING, FollowStatus.SEARCHING, FollowStatus.GPS_WAIT, FollowStatus.TOO_CLOSE -> "search"
            else -> "lost"
        }

    fun text(short: Boolean): String {
        val d = distance
        return when (status) {
            FollowStatus.OFF -> "Suivi coupé"
            FollowStatus.LOOKING -> if (mode == FollowMode.GPS) "GPS en attente" else "Je cherche quelqu'un"
            FollowStatus.IDENTIFY ->
                if (short) "Fais coucou" else "Plusieurs personnes : fais coucou avec la main de la montre, ou touche-toi à l'écran"
            FollowStatus.LEARNING -> if (short) "Calibrage" else "Calibrage du suivi : la nacelle bouge un peu"
            FollowStatus.CAMERA -> when {
                short -> "Suivi : caméra"
                you -> "Suivi caméra, la montre t'a reconnu"
                else -> "Suivi caméra"
            }
            FollowStatus.SEARCHING -> "Je te cherche"
            FollowStatus.GPS ->
                if (short) "Suivi : GPS" + (d?.let { " $it m" } ?: "")
                else "Suivi par le GPS de la montre" + (d?.let { ", $it m" } ?: "")
            FollowStatus.GPS_WAIT -> if (short) "GPS en attente" else "En attente du GPS de la montre et du téléphone"
            FollowStatus.TOO_CLOSE ->
                if (short) "GPS : trop près" else "GPS : trop près pour viser juste" + (d?.let { " ($it m)" } ?: "")
            FollowStatus.LOST -> if (short) "Perdu de vue" else "Perdu de vue : reviens dans l'image"
            FollowStatus.NO_GIMBAL -> "Nacelle non connectée"
        }
    }
}

fun modeLabel(m: FollowMode) = when (m) {
    FollowMode.CAMERA_WATCH -> "caméra + montre"
    FollowMode.CAMERA -> "caméra seule"
    FollowMode.GPS -> "GPS seul"
}

/**
 * Suivi du sujet. La caméra repère les personnes, la montre dit laquelle est son porteur
 * (ses mouvements de poignet), et son GPS reprend la main quand le sujet sort de l'image.
 * La nacelle est pilotée avec sa propre commande de suivi (0x60) : on lui donne la position
 * du sujet dans l'image et elle tourne vers lui.
 */
class Follow(
    ctx: Context,
    private val ble: GimbalBle,
    private val controller: Controller,
    private val scope: CoroutineScope,
    private val prefs: SharedPreferences,
    private val log: (String) -> Unit,
) {
    val sensors = PhoneSensors(ctx)
    val ui = MutableStateFlow(FollowUi(mode = readMode()))
    /** L'écran doit demander l'autorisation de position. */
    val needLocationPermission = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val lock = Any()

    private var modeCache = readMode()
    private fun readMode() =
        runCatching { FollowMode.valueOf(prefs.getString("fMode", null) ?: "") }.getOrDefault(FollowMode.CAMERA_WATCH)

    var mode: FollowMode
        get() = modeCache
        set(v) {
            modeCache = v
            prefs.edit().putString("fMode", v.name).apply()
            synchronized(lock) { resetTarget(); status = FollowStatus.LOOKING; startedAt = now() }
            if (on) applySensors()
            publish()
        }

    /** Réactivité : multiplie l'écart envoyé à la nacelle. */
    var gain: Float
        get() = prefs.getFloat("fGain", 1f)
        set(v) = prefs.edit().putFloat("fGain", v).apply()

    @Volatile var on = false
        private set
    /** Rotations en degrés (0, 90, 180, 270) au sens de Surface.ROTATION_*. */
    @Volatile var displayRotation = 0
    @Volatile var physicalRotation = 0

    private var front = false
    private var aspect = 16f / 9f
    private val people = PeopleTracker()
    private var targetId: Int? = null
    private var you = false
    private var manual = false
    private var startedAt = 0L
    private var lastSeenTarget = 0L
    private var lastAimWorldX = 0f
    private var lastCmd = 0f to 0f
    private var targetSig: FloatArray? = null
    private val notMe = HashSet<Int>()
    private var badId = 0
    private var lastIdEval = 0L
    private var status = FollowStatus.OFF
    private var distance: Int? = null
    private var lastFrameAt = 0L

    private val watchMotion = Series(10_000)
    private val clockOffsets = ArrayDeque<Long>()
    private var watchMotionAt = 0L
    private var watchFix: WatchFix? = null

    private val calib = AngleAvg(0.1f)
    private var lastCalibFix = 0L

    private var probe: Probe? = null
    private var probeFails = 0

    private class Probe(val t0: Long, val az0: Float, val el0: Float) {
        var az1 = Float.NaN
        var el1 = Float.NaN
    }

    private data class WatchFix(
        val lat: Double, val lon: Double, val acc: Float, val speed: Float, val course: Float, val at: Long,
    )

    private fun now() = SystemClock.elapsedRealtime()
    private val delta: Int get() = ((physicalRotation - displayRotation) % 360 + 360) % 360
    private val landscape: Boolean get() = physicalRotation == 90 || physicalRotation == 270
    /** Champ horizontal approximatif de la caméra, en degrés. */
    private val hfov: Float get() = if (landscape) 68f else 42f

    private fun mapKey() = "fmap_${if (front) "f" else "b"}_${if (landscape) "l" else "p"}"
    private fun mapping(): Mapping? = Mapping.decode(prefs.getString(mapKey(), null))
    private fun saveMapping(m: Mapping) = prefs.edit().putString(mapKey(), m.encode()).apply()

    // ---------- Commandes ----------

    fun start(): Boolean {
        if (on) return true
        if (!ble.isReady) { log("Suivi : connecte d'abord la nacelle"); return false }
        synchronized(lock) {
            resetTarget()
            people.clear()
            startedAt = now()
            status = FollowStatus.LOOKING
            probe = null
            probeFails = 0
            calib.reset()
            lastCalibFix = 0L
            distance = null
            on = true
        }
        applySensors()
        log("Suivi activé : ${modeLabel(mode)}")
        publish()
        return true
    }

    fun stop() {
        if (!on) return
        on = false
        synchronized(lock) {
            resetTarget()
            people.clear()
            status = FollowStatus.OFF
            probe = null
            distance = null
        }
        sensors.stop()
        scope.launch { repeat(3) { ble.sendTrack(Hohem.trackClear()); delay(33) } }
        log("Suivi coupé")
        publish()
    }

    fun toggle() { if (on) stop() else start() }

    private fun applySensors() {
        sensors.start()
        if (mode != FollowMode.CAMERA) {
            if (!sensors.startLocation()) needLocationPermission.tryEmit(Unit)
        } else {
            sensors.stopLocation()
        }
    }

    /** À appeler quand l'autorisation de position vient d'être accordée. */
    fun onLocationPermission() {
        if (on && mode != FollowMode.CAMERA) sensors.startLocation()
    }

    fun relearn() {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("fmap_") }.forEach { e.remove(it) }
        e.apply()
        synchronized(lock) { probe = null; probeFails = 0 }
        log("Sens du suivi oublié : il sera réappris au prochain suivi")
    }

    /** Toucher une personne à l'écran : c'est elle qu'on suit. (x, y) dans l'image non retournée. */
    fun pick(x: Float, y: Float) {
        val ok = synchronized(lock) {
            val t = now()
            val hit = people.tracks.filter { t - it.seenAt < 400 && it.det.box.contains(x, y, 0.03f) }
                .minByOrNull { it.det.box.w * it.det.box.h } ?: return@synchronized false
            if (hit.id == targetId) return@synchronized false
            targetId = hit.id
            manual = true
            you = (hit.idScore ?: 0f) >= 0.45f
            targetSig = hit.sig?.copyOf()
            notMe.remove(hit.id)
            lastSeenTarget = hit.seenAt
            badId = 0
            true
        }
        if (ok) { log("Personne choisie à l'écran"); publish() }
    }

    /** Recalage manuel du GPS : l'utilisateur est au centre de l'image, loin de la caméra. */
    fun recalibrate() {
        val msg = synchronized(lock) {
            val t = now()
            val w = watchFix?.takeIf { t - it.at < 5_000 }
                ?: return@synchronized "Recalage impossible : pas de position de la montre (suivi activé ?)"
            val ph = sensors.phoneFix() ?: return@synchronized "Recalage impossible : pas encore de position du téléphone"
            val o = sensors.orientation(front) ?: return@synchronized "Recalage impossible : active d'abord le suivi"
            val d = Geo.distance(ph.lat, ph.lon, w.lat, w.lon)
            if (d < 8.0) return@synchronized "Recalage : éloigne-toi à plus de 8 m (tu es à ${d.roundToInt()} m)"
            calib.set(Geo.bearing(ph.lat, ph.lon, w.lat, w.lon).toFloat() - o.first, 5)
            "GPS recalé sur ta position (${d.roundToInt()} m)"
        }
        log(msg)
    }

    private fun resetTarget() {
        targetId = null
        you = false
        manual = false
        lastSeenTarget = 0L
        targetSig = null
        notMe.clear()
        badId = 0
    }

    // ---------- Entrées ----------

    /** Caméra active (avant ou arrière) : on repart de zéro quand elle change. */
    fun setLens(isFront: Boolean) {
        synchronized(lock) {
            if (isFront == front) return
            front = isFront
            people.clear()
            resetTarget()
            probe = null
        }
        publish()
    }

    /** Une image analysée : personnes détectées, dans le repère de l'écran. */
    fun onFrame(t: Long, dets: List<Detection>, isFront: Boolean, imageAspect: Float) {
        if (!on) return
        if (isFront != front) setLens(isFront)
        synchronized(lock) {
            aspect = imageAspect
            lastFrameAt = t
            people.update(t, dets)
            if (t - lastIdEval >= 500) { lastIdEval = t; evalIdentity(t) }
            choose(t)
            target()?.takeIf { it.seenAt == t }?.let { tr ->
                lastSeenTarget = t
                lastAimWorldX = toWorld(tr.aim.x - 0.5f, tr.aim.y - AIM_Y, delta).first
                if (you || !manual || targetSig == null) tr.sig?.let { targetSig = it.copyOf() }
            }
        }
        publish()
    }

    fun onWatchMotion(arrival: Long, tLast: Long, values: FloatArray) {
        synchronized(lock) {
            val off = arrival - tLast
            if (clockOffsets.isNotEmpty() && off - clockOffsets.min() > 5_000) clockOffsets.clear() // montre redémarrée
            clockOffsets.addLast(off)
            while (clockOffsets.size > 100) clockOffsets.removeFirst()
            val o = clockOffsets.min()
            for (i in values.indices) watchMotion.add(tLast - (values.size - 1 - i) * Correl.STEP + o, values[i])
            watchMotionAt = arrival
        }
    }

    fun onWatchGps(arrival: Long, lat: Double, lon: Double, acc: Float, speed: Float, course: Float, ageMs: Long) {
        synchronized(lock) { watchFix = WatchFix(lat, lon, acc, speed, course, arrival - ageMs.coerceIn(0L, 10_000L)) }
    }

    // ---------- Qui suivre ----------

    private fun target(): Track? = targetId?.let { id -> people.tracks.firstOrNull { it.id == id } }

    private fun watchStreaming(t: Long) = mode == FollowMode.CAMERA_WATCH && t - watchMotionAt < 2_000

    private fun evalIdentity(t: Long) {
        val watchOk = watchStreaming(t)
        for (tr in people.tracks) {
            tr.idScore = if (!watchOk || t - tr.seenAt > 300 || t - tr.bornAt < 1_500) null
            else listOfNotNull(Correl.score(watchMotion, tr.wristL, t), Correl.score(watchMotion, tr.wristR, t)).maxOrNull()
        }
        if (!watchOk || manual) return
        val cur = target()?.takeIf { t - it.seenAt < 400 } ?: return
        val s = cur.idScore ?: return
        if (s < 0.05f) badId++ else if (s > 0.2f) badId = 0
        if (badId >= (if (you) 10 else 6)) {
            notMe += cur.id
            log("D'après la montre, ce n'est pas toi : je te cherche")
            targetId = null
            you = false
            targetSig = null
            badId = 0
        }
    }

    private fun choose(t: Long) {
        val live = people.tracks.filter { t - it.seenAt < 250 && it.id !in notMe }
        val cur = target()?.takeIf { t - it.seenAt < 400 }
        val useWatch = watchStreaming(t)

        if (useWatch) {
            val idd = identify(live.mapNotNull { tr -> tr.idScore?.let { tr.id to it } }.toMap())
            if (idd != null) {
                if (idd == targetId) {
                    you = true
                } else if (!manual || cur == null) {
                    targetId = idd
                    you = true
                    manual = false
                    badId = 0
                    targetSig = null
                    log("La montre t'a reconnu")
                    return
                }
            }
        }
        if (cur != null || live.isEmpty()) return

        val sig = targetSig
        fun sim(tr: Track) = sigSimilarity(sig, tr.sig)
        fun wx(tr: Track) = toWorld(tr.aim.x - 0.5f, tr.aim.y - AIM_Y, delta).first
        val cands = live.filter { (sim(it) ?: 1f) >= 0.4f }
        if (cands.isEmpty()) return

        val pick: Track? = if (lastSeenTarget == 0L) {
            when {
                cands.size == 1 -> cands[0]
                useWatch && t - startedAt < 6_000 -> null // on laisse à la montre le temps de te reconnaître
                else -> pickInitial(cands)
            }
        } else {
            val lostFor = t - lastSeenTarget
            when {
                status == FollowStatus.GPS -> cands.filter { abs(wx(it)) < 0.3f }.minByOrNull { abs(wx(it)) }
                lostFor < 3_000 ->
                    cands.filter { abs(wx(it) - lastAimWorldX) < 0.3f }.minByOrNull { abs(wx(it) - lastAimWorldX) }
                        ?: cands.singleOrNull()?.takeIf { (sim(it) ?: 0.6f) >= 0.55f }
                cands.size == 1 && (sim(cands[0]) ?: 0.6f) >= 0.55f -> cands[0]
                !useWatch -> cands.filter { (sim(it) ?: 0f) >= 0.65f }.maxByOrNull { sim(it) ?: 0f }
                else -> null
            }
        }
        if (pick != null) {
            if (lastSeenTarget != 0L) log("Cible retrouvée")
            targetId = pick.id
            you = useWatch && (pick.idScore ?: 0f) >= 0.45f
        }
    }

    // ---------- Pilotage ----------

    fun startLoop() = scope.launch {
        var clears = 0
        var lastPub = 0L
        while (isActive) {
            delay(33)
            if (!on) { clears = 0; continue }
            val t = now()
            val manualNow = controller.moving.value || controller.recentering
            val cmd = synchronized(lock) { step(t, manualNow) }
            if (!manualNow && ble.isReady) {
                if (cmd != null) {
                    ble.sendTrack(cmd)
                    clears = 3
                } else if (clears > 0) {
                    ble.sendTrack(Hohem.trackClear())
                    clears--
                }
            }
            if (t - lastPub > 250) { lastPub = t; publish() }
        }
    }

    private fun step(t: Long, manualNow: Boolean): ByteArray? {
        if (!ble.isReady) { status = FollowStatus.NO_GIMBAL; probe = null; return null }
        val m = mapping() ?: run {
            status = FollowStatus.LEARNING
            if (manualNow) { probe = null; return null }
            if (!sensors.hasRotation) {
                saveMapping(Mapping())
                log("Pas de gyroscope : sens du suivi par défaut")
                return null
            }
            return probeStep(t)
        }
        return if (mode == FollowMode.GPS) stepGps(t, m) else stepCamera(t, m)
    }

    /**
     * Apprentissage du sens : petite poussée +x puis +y, mesurée avec le gyroscope du téléphone
     * (qui tourne avec la caméra). Une fois par caméra et par orientation.
     */
    private fun probeStep(t: Long): ByteArray? {
        val o = sensors.orientation(front) ?: return null
        val p = probe ?: Probe(t, o.first, o.second).also { probe = it }
        val dt = t - p.t0
        return when {
            dt < 500 -> Hohem.trackOffset(PROBE, 0f)
            dt < 800 -> null
            dt < 1_300 -> {
                if (p.az1.isNaN()) { p.az1 = o.first; p.el1 = o.second }
                Hohem.trackOffset(0f, PROBE)
            }
            dt < 1_600 -> null
            else -> {
                probe = null
                val dAzX = Hohem.wrap180(p.az1 - p.az0)
                val dElX = p.el1 - p.el0
                val dAzY = Hohem.wrap180(o.first - p.az1)
                val dElY = o.second - p.el1
                log("Calibrage : x → %.1f° / %.1f°, y → %.1f° / %.1f°".format(dAzX, dElX, dAzY, dElY))
                val m = Mapping.fromProbe(dAzX, dElX, dAzY, dElY)
                when {
                    m != null -> { saveMapping(m); log("Sens du suivi appris") }
                    ++probeFails >= 2 -> {
                        saveMapping(Mapping())
                        log("La nacelle n'a pas bougé pendant le calibrage : sens par défaut")
                    }
                    else -> log("Calibrage du suivi : nouvel essai")
                }
                null
            }
        }
    }

    private fun stepCamera(t: Long, m: Mapping): ByteArray? {
        val tr = target()?.takeIf { t - it.seenAt < 500 }
        if (tr != null) {
            status = FollowStatus.CAMERA
            distance = null
            val p = tr.predicted(t + 80)
            val (wx, wy) = toWorld(p.x - 0.5f, p.y - AIM_Y, delta)
            calibrate(t, wx)
            val g = gain
            val cx = if (abs(wx) < 0.025f) 0f else (wx * g).coerceIn(-0.5f, 0.5f)
            val cy = if (abs(wy) < 0.04f) 0f else (wy * g).coerceIn(-0.5f, 0.5f)
            val (x, y) = m.command(cx, cy)
            lastCmd = x to y
            val world = if (delta % 180 == 90) tr.det.box.h to tr.det.box.w else tr.det.box.w to tr.det.box.h
            val (bw, bh) = if (m.panAxis == 0) world else world.second to world.first
            return Hohem.trackOffset(x, y, bw.coerceIn(0.05f, 1f), bh.coerceIn(0.05f, 1f))
        }

        val live = people.tracks.count { t - it.seenAt < 250 && it.id !in notMe }
        val useWatch = watchStreaming(t)
        if (lastSeenTarget == 0L) {
            status = if (live >= 2 && useWatch) FollowStatus.IDENTIFY else FollowStatus.LOOKING
            return null
        }
        val lostFor = t - lastSeenTarget
        // Sorti par un bord : on continue un instant dans la même direction
        if (lostFor < 700 && abs(lastAimWorldX) > 0.28f) {
            status = FollowStatus.SEARCHING
            return Hohem.trackOffset(lastCmd.first * 0.8f, lastCmd.second * 0.3f)
        }
        // Hors champ depuis un moment : le GPS de la montre prend le relais (seulement si la cible, c'est toi)
        if (mode == FollowMode.CAMERA_WATCH && lostFor > 1_500 && (!manual || you)) {
            val c = stepGps(t, m)
            if (status == FollowStatus.GPS || status == FollowStatus.TOO_CLOSE) return c
        }
        status = when {
            live >= 2 && useWatch && !manual -> FollowStatus.IDENTIFY
            lostFor < 1_500 -> FollowStatus.SEARCHING
            else -> FollowStatus.LOST
        }
        return null
    }

    private fun stepGps(t: Long, m: Mapping): ByteArray? {
        val w = watchFix?.takeIf { t - it.at < 5_000 }
        val ph = sensors.phoneFix()
        val o = sensors.orientation(front)
        if (w == null || ph == null || o == null) { status = FollowStatus.GPS_WAIT; return null }
        val (lat, lon) = predict(w, t)
        val d = Geo.distance(ph.lat, ph.lon, lat, lon)
        distance = d.roundToInt()
        val unc = Math.toDegrees(atan2((w.acc + ph.acc).toDouble(), d)).toFloat()
        if (d < 4.0 || unc > 40f) { status = FollowStatus.TOO_CLOSE; return null }
        val off = offset() ?: run { status = FollowStatus.GPS_WAIT; return null }
        val e = Hohem.wrap180(Geo.bearing(ph.lat, ph.lon, lat, lon).toFloat() - (o.first + off))
        status = FollowStatus.GPS
        if (abs(e) < max(3f, unc * 0.4f)) return null
        val x = (e / hfov * gain).coerceIn(-0.45f, 0.45f)
        val (cx, cy) = m.command(x, 0f)
        lastCmd = cx to cy
        return Hohem.trackOffset(cx, cy)
    }

    /** Cap du repère gyroscope : recalé par la caméra si possible, sinon la boussole. */
    private fun offset(): Float? = calib.value?.takeIf { calib.count >= 3 } ?: sensors.compassOffset()

    /** Position de la montre prolongée par sa vitesse (la position GPS arrive avec du retard). */
    private fun predict(w: WatchFix, t: Long): Pair<Double, Double> {
        if (w.speed < 0.7f || w.course < 0f) return w.lat to w.lon
        val s = (w.speed * ((t - w.at) / 1000f + 0.3f)).coerceAtMost(15f).toDouble()
        val c = Math.toRadians(w.course.toDouble())
        return Geo.offset(w.lat, w.lon, s * cos(c), s * sin(c))
    }

    /**
     * Pendant que la caméra te suit, on mesure l'écart entre le cap GPS (téléphone → montre)
     * et la direction de la caméra : c'est ce qui permet au GPS de viser juste ensuite.
     */
    private fun calibrate(t: Long, wx: Float) {
        if (mode != FollowMode.CAMERA_WATCH || abs(wx) > 0.25f) return
        val alone = people.tracks.count { t - it.seenAt < 300 } == 1
        if (!(you || (alone && !manual))) return
        val w = watchFix ?: return
        if (w.at == lastCalibFix || t - w.at > 3_000 || w.acc > 15f) return
        val ph = sensors.phoneFix() ?: return
        if (ph.acc > 15f) return
        val o = sensors.orientation(front) ?: return
        val (lat, lon) = predict(w, t)
        val d = Geo.distance(ph.lat, ph.lon, lat, lon)
        if (d < 8.0) return
        lastCalibFix = w.at
        calib.add(Geo.bearing(ph.lat, ph.lon, lat, lon).toFloat() - (o.first + wx * hfov))
        if (calib.count == 3) log("GPS calé sur la caméra (${d.roundToInt()} m)")
    }

    private fun publish() {
        ui.value = synchronized(lock) {
            val t = now()
            FollowUi(
                on = on,
                mode = modeCache,
                status = if (on) status else FollowStatus.OFF,
                people = if (on && modeCache != FollowMode.GPS) {
                    people.tracks.filter { t - it.seenAt < 300 }.map { PersonUi(it.id, it.det.box, it.id == targetId) }
                } else emptyList(),
                mirror = front,
                aspect = aspect,
                you = you,
                distance = distance,
            )
        }
    }

    companion object {
        /** Hauteur où placer le haut du buste (un peu au-dessus du centre). */
        const val AIM_Y = 0.42f
        const val PROBE = 0.3f
    }
}
