package com.fpapef.topp

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Logique pure du suivi : rien ici ne dépend d'Android, tout est testé en local.

/** Point de l'image, coordonnées normalisées 0..1, y vers le bas. */
data class Pt(val x: Float, val y: Float)

data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val cx: Float get() = (l + r) / 2f
    val cy: Float get() = (t + b) / 2f
    val w: Float get() = r - l
    val h: Float get() = b - t
    fun shift(dx: Float, dy: Float) = Box(l + dx, t + dy, r + dx, b + dy)
    fun contains(x: Float, y: Float, pad: Float = 0f) = x >= l - pad && x <= r + pad && y >= t - pad && y <= b + pad
    fun iou(o: Box): Float {
        val iw = min(r, o.r) - max(l, o.l)
        val ih = min(b, o.b) - max(t, o.t)
        if (iw <= 0f || ih <= 0f) return 0f
        val i = iw * ih
        return i / (w * h + o.w * o.h - i)
    }
}

/** Repère du squelette : position normalisée et visibilité 0..1. */
data class Lm(val x: Float, val y: Float, val v: Float)

/** Une personne détectée dans une image (repère de l'écran, image non retournée). */
class Detection(
    val box: Box,
    /** Point à garder au centre de l'image : entre le menton et les épaules. */
    val aim: Pt,
    val wristL: Pt? = null,
    val wristR: Pt? = null,
    /** Largeur d'épaules dans l'image, pour comparer les vitesses d'une personne à l'autre. */
    val scale: Float = 0.1f,
    /** Couleurs du buste (vêtements), pour reconnaître la personne si on la perd de vue. */
    val sig: FloatArray? = null,
    /** Hauteur / largeur de l'image, pour mesurer les vitesses dans un repère carré. */
    val aspect: Float = 1f,
)

/**
 * La même détection vue avec un zoom multiplié par [k] (grossissement autour du centre de l'image).
 * Sert à ramener chaque image dans un repère « 1x », quel que soit l'objectif.
 */
fun Detection.scaled(k: Float): Detection {
    fun c(v: Float) = 0.5f + (v - 0.5f) * k
    fun p(v: Pt?) = v?.let { Pt(c(it.x), c(it.y)) }
    return Detection(Box(c(box.l), c(box.t), c(box.r), c(box.b)), p(aim)!!, p(wristL), p(wristR), scale * k, sig, aspect)
}

/** Construit une détection à partir des 33 repères MediaPipe Pose. */
fun detectionOf(lm: List<Lm>, pixels: IntArray? = null, w: Int = 0, h: Int = 0): Detection? {
    if (lm.size < 33) return null
    val vis = lm.filter { it.v >= 0.3f }
    if (vis.size < 6) return null
    fun ok(i: Int) = lm[i].v >= 0.5f
    val l = vis.minOf { it.x }
    val r = vis.maxOf { it.x }
    var t = vis.minOf { it.y }
    val b = vis.maxOf { it.y }
    t -= (b - t) * 0.08f // le haut du crâne dépasse des repères du visage
    val box = Box(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    if (box.w < 0.01f || box.h < 0.02f) return null
    val aspect = if (w > 0 && h > 0) h.toFloat() / w else 1f
    val sh = if (ok(11) && ok(12)) Pt((lm[11].x + lm[12].x) / 2f, (lm[11].y + lm[12].y) / 2f) else null
    val scale = if (sh != null) {
        hypot(lm[11].x - lm[12].x, (lm[11].y - lm[12].y) * aspect).coerceAtLeast(box.h * aspect * 0.12f)
    } else box.h * aspect * 0.25f
    val aim = when {
        sh != null && ok(0) -> Pt((sh.x + lm[0].x) / 2f, (sh.y + lm[0].y) / 2f)
        sh != null -> Pt(sh.x, sh.y - scale * 0.4f / aspect)
        else -> Pt(box.cx, box.t + box.h * 0.3f)
    }
    val wl = if (ok(15)) Pt(lm[15].x, lm[15].y) else null
    val wr = if (ok(16)) Pt(lm[16].x, lm[16].y) else null
    val sig = if (pixels != null && sh != null && w > 0 && h > 0) torsoSig(lm, pixels, w, h) else null
    return Detection(box, aim, wl, wr, scale.coerceAtLeast(0.02f), sig, aspect)
}

/** Couleur moyenne du buste en 2 × 3 cases : chromaticité rouge, verte et luminosité atténuée. */
fun torsoSig(lm: List<Lm>, px: IntArray, w: Int, h: Int): FloatArray? {
    val xs = mutableListOf(lm[11].x, lm[12].x)
    val ys = mutableListOf(lm[11].y, lm[12].y)
    if (lm[23].v >= 0.5f && lm[24].v >= 0.5f) {
        xs += lm[23].x; xs += lm[24].x; ys += lm[23].y; ys += lm[24].y
    } else {
        val sw = abs(lm[11].x - lm[12].x) * w / h
        ys += ys.max() + sw * 1.2f
    }
    var l = xs.min(); var r = xs.max(); var t = ys.min(); var b = ys.max()
    val mx = (r - l) * 0.15f
    val my = (b - t) * 0.12f
    l += mx; r -= mx; t += my; b -= my
    if (r - l < 0.01f || b - t < 0.01f) return null
    val out = FloatArray(18)
    for (row in 0 until 3) for (col in 0 until 2) {
        var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
        for (iy in 0 until 5) for (ix in 0 until 5) {
            val x = l + (r - l) * (col + (ix + 0.5f) / 5f) / 2f
            val y = t + (b - t) * (row + (iy + 0.5f) / 5f) / 3f
            val qx = (x * w).toInt()
            val qy = (y * h).toInt()
            if (qx !in 0 until w || qy !in 0 until h) continue
            val c = px[qy * w + qx]
            sr += (c shr 16 and 0xFF); sg += (c shr 8 and 0xFF); sb += (c and 0xFF); n++
        }
        if (n == 0) return null
        val s = (sr + sg + sb).coerceAtLeast(1f)
        val k = (row * 2 + col) * 3
        out[k] = sr / s
        out[k + 1] = sg / s
        out[k + 2] = s / n / 765f * 0.6f
    }
    return out
}

/** Ressemblance des vêtements, 0 (rien à voir) à 1 (identiques). null si on ne peut pas comparer. */
fun sigSimilarity(a: FloatArray?, b: FloatArray?): Float? {
    if (a == null || b == null || a.size != b.size) return null
    var s = 0f
    for (i in a.indices) s += (a[i] - b[i]).pow(2)
    val d = sqrt(s / a.size)
    return (1f - d / 0.08f).coerceIn(0f, 1f)
}

/** Série temporelle courte (horodatage en ms, valeur), découpée en tranches régulières à la demande. */
class Series(private val keepMs: Long = 8_000) {
    private val ts = ArrayDeque<Long>()
    private val vs = ArrayDeque<Float>()
    val size: Int get() = ts.size

    fun add(t: Long, v: Float) {
        if (v.isNaN() || v.isInfinite()) return
        ts.addLast(t); vs.addLast(v)
        while (ts.isNotEmpty() && t - ts.first() > keepMs) { ts.removeFirst(); vs.removeFirst() }
    }

    /** Moyenne par tranche de [step] ms à partir de [from] ; NaN pour une tranche vide. */
    fun bins(from: Long, step: Long, n: Int): FloatArray {
        val sum = FloatArray(n)
        val cnt = IntArray(n)
        for (i in ts.indices) {
            val dt = ts[i] - from
            if (dt < 0) continue
            val k = (dt / step).toInt()
            if (k >= n) continue
            sum[k] += vs[i]; cnt[k]++
        }
        return FloatArray(n) { if (cnt[it] > 0) sum[it] / cnt[it] else Float.NaN }
    }
}

/** Une personne suivie d'image en image. */
class Track internal constructor(val id: Int, d: Detection, t: Long) {
    var det: Detection = d
        private set
    val bornAt = t
    var seenAt = t
        private set
    var vx = 0f
        private set
    var vy = 0f
        private set
    /** Vitesse des poignets par rapport au buste, en largeurs d'épaules par seconde. */
    val wristL = Series()
    val wristR = Series()
    var sig: FloatArray? = d.sig?.copyOf()
        private set
    /** Ressemblance avec les mouvements de la montre (corrélation), null = pas assez d'indices. */
    var idScore: Float? = null

    private var sAim = d.aim
    private var sL: Pt? = d.wristL
    private var sR: Pt? = d.wristR

    val aim: Pt get() = sAim

    internal fun update(d: Detection, t: Long) {
        val dt = (t - seenAt) / 1000f
        val aim = Pt(sAim.x + 0.6f * (d.aim.x - sAim.x), sAim.y + 0.6f * (d.aim.y - sAim.y))
        if (dt in 0.005f..0.5f) {
            vx = 0.7f * vx + 0.3f * (aim.x - sAim.x) / dt
            vy = 0.7f * vy + 0.3f * (aim.y - sAim.y) / dt
            val sc = max(d.scale, 0.02f)
            val ax = aim.x - sAim.x
            val ay = aim.y - sAim.y
            fun step(prev: Pt?, cur: Pt?, out: Series): Pt? {
                if (cur == null) return null
                if (prev == null) return cur
                val s = Pt(prev.x + 0.5f * (cur.x - prev.x), prev.y + 0.5f * (cur.y - prev.y))
                val rx = (s.x - prev.x) - ax
                val ry = ((s.y - prev.y) - ay) * d.aspect
                out.add(t, hypot(rx, ry) / dt / sc)
                return s
            }
            sL = step(sL, d.wristL, wristL)
            sR = step(sR, d.wristR, wristR)
        } else {
            sL = d.wristL; sR = d.wristR
        }
        sAim = aim
        d.sig?.let { n ->
            val o = sig
            sig = if (o != null && o.size == n.size) FloatArray(o.size) { o[it] * 0.9f + n[it] * 0.1f } else n.copyOf()
        }
        det = d
        seenAt = t
    }

    /** Position du point visé, prolongée par la vitesse (au plus 0,4 s). */
    fun predicted(t: Long): Pt {
        val dt = ((t - seenAt) / 1000f).coerceIn(0f, 0.4f)
        return Pt(sAim.x + vx * dt, sAim.y + vy * dt)
    }
}

/** Associe les détections d'une image aux personnes déjà suivies. */
class PeopleTracker(private val forgetMs: Long = 1_200) {
    private var nextId = 1
    private val list = mutableListOf<Track>()
    val tracks: List<Track> get() = list

    private class Cand(val score: Float, val tr: Track, val d: Detection)

    fun update(t: Long, dets: List<Detection>): List<Track> {
        val cands = ArrayList<Cand>()
        for (tr in list) {
            val p = tr.predicted(t)
            val pb = tr.det.box.shift(p.x - tr.aim.x, p.y - tr.aim.y)
            val sc = max(tr.det.scale, 0.04f)
            for (d in dets) {
                val iou = pb.iou(d.box)
                val dist = hypot(p.x - d.aim.x, (p.y - d.aim.y) * d.aspect) / sc
                if (iou < 0.1f && dist > 1.5f) continue
                cands += Cand(iou - 0.2f * dist, tr, d)
            }
        }
        cands.sortByDescending { it.score }
        val usedT = HashSet<Int>()
        val usedD = HashSet<Detection>()
        for (c in cands) {
            if (c.tr.id in usedT || c.d in usedD) continue
            c.tr.update(c.d, t)
            usedT += c.tr.id
            usedD += c.d
        }
        for (d in dets) if (d !in usedD) list += Track(nextId++, d, t)
        list.removeAll { t - it.seenAt > forgetMs }
        return list
    }

    fun clear() = list.clear()
}

/**
 * Zoom automatique du suivi : passe à l'objectif suivant quand le sujet est petit et bien centré,
 * revient en arrière quand il grandit ou s'approche du bord. Les arrêts possibles sont les objectifs
 * du téléphone (1x, 3x, 5x). Le choix tient compte du temps, pour ne pas changer sans arrêt.
 */
class AutoZoom {
    private var smallSince = 0L
    private var bigSince = 0L
    var lastChange = -100_000L

    fun reset() { smallSince = 0L; bigSince = 0L; lastChange = -100_000L }

    /**
     * [h] hauteur du sujet (fraction de l'image), [wx], [wy] écart au centre, [speed] vitesse du
     * sujet dans l'image (fraction par seconde). Renvoie le zoom voulu, ou null pour ne rien changer.
     */
    fun update(t: Long, stops: List<Float>, zoom: Float, h: Float, wx: Float, wy: Float, speed: Float): Float? {
        if (stops.size < 2) return null
        val i = stops.indices.minBy { abs(stops[it] - zoom) }
        val edge = abs(wx) > 0.33f || abs(wy) > 0.36f
        if (i > 0 && (h > 0.8f || edge)) {
            smallSince = 0L
            if (bigSince == 0L) bigSince = t
            if (t - bigSince >= (if (edge) 250 else 600) && t - lastChange > 800) {
                bigSince = 0L
                // Au bord : on prend le plus large ; trop gros : l'objectif qui le fait tenir
                return if (edge) stops[0]
                else stops.take(i).lastOrNull { h * it / zoom <= 0.65f } ?: stops[0]
            }
            return null
        }
        bigSince = 0L
        val next = stops.getOrNull(i + 1)
        if (next != null && h < 0.3f && h * next / zoom <= 0.65f && abs(wx) < 0.15f && abs(wy) < 0.25f && speed < 0.15f) {
            if (smallSince == 0L) smallSince = t
            if (t - smallSince >= 1_500 && t - lastChange > 3_000) { smallSince = 0L; return next }
        } else {
            smallSince = 0L
        }
        return null
    }
}

/** Libellé d'un objectif : 1x, 3x, 0,6x. */
fun lensLabel(z: Float): String {
    val r = kotlin.math.round(z * 10f) / 10f
    val s = if (r == kotlin.math.floor(r)) r.toInt().toString() else r.toString().replace('.', ',')
    return s + "x"
}

/** Corrélation entre l'agitation de la montre (gyroscope) et celle d'un poignet à l'image. */
object Correl {
    const val STEP = 100L
    const val WINDOW = 4_000L
    private val N = (WINDOW / STEP).toInt()

    fun pearson(a: FloatArray, b: FloatArray, minN: Int = 12): Float? {
        var n = 0
        var sa = 0.0
        var sb = 0.0
        for (i in a.indices) if (!a[i].isNaN() && !b[i].isNaN()) { n++; sa += a[i]; sb += b[i] }
        if (n < minN) return null
        val ma = sa / n
        val mb = sb / n
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in a.indices) if (!a[i].isNaN() && !b[i].isNaN()) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db; va += da * da; vb += db * db
        }
        if (va < 1e-9 || vb < 1e-9) return null
        return (cov / sqrt(va * vb)).toFloat()
    }

    fun std(a: FloatArray): Float {
        var n = 0
        var s = 0.0
        for (v in a) if (!v.isNaN()) { n++; s += v }
        if (n < 2) return 0f
        val m = s / n
        var q = 0.0
        for (v in a) if (!v.isNaN()) q += (v - m) * (v - m)
        return sqrt(q / (n - 1)).toFloat()
    }

    /** La montre bouge-t-elle assez pour servir à reconnaître quelqu'un ? */
    fun active(watch: Series, now: Long, minStd: Float = 0.3f) = std(watch.bins(now - WINDOW, STEP, N)) >= minStd

    /**
     * Meilleure corrélation sur les 4 dernières secondes, en essayant de petits décalages
     * (±300 ms) pour absorber le retard du Bluetooth. null si l'un des deux ne bouge pas assez.
     */
    fun score(watch: Series, wrist: Series, now: Long, minWatchStd: Float = 0.3f, minWristStd: Float = 0.15f): Float? {
        val from = now - WINDOW
        val b = wrist.bins(from, STEP, N)
        if (std(b) < minWristStd) return null
        var best: Float? = null
        for (lag in -3..3) {
            val a = watch.bins(from + lag * STEP, STEP, N)
            if (std(a) < minWatchStd) continue
            val r = pearson(a, b) ?: continue
            if (best == null || r > best) best = r
        }
        return best
    }
}

/** Celui qui porte la montre : nettement plus corrélé que les autres. */
fun identify(scores: Map<Int, Float>, min: Float = 0.45f, margin: Float = 0.15f): Int? {
    val sorted = scores.entries.sortedByDescending { it.value }
    val best = sorted.firstOrNull() ?: return null
    if (best.value < min) return null
    val second = sorted.getOrNull(1)?.value ?: -1f
    return if (best.value - second >= margin) best.key else null
}

/** Sans autre indice : la personne la plus grande et la plus centrée. */
fun pickInitial(tracks: List<Track>): Track? =
    tracks.maxByOrNull { it.det.box.w * it.det.box.h * (1.2f - abs(it.aim.x - 0.5f) * 2f) }

// ---------- Géographie et orientation ----------

fun norm360(d: Float) = ((d % 360f) + 360f) % 360f

object Geo {
    private const val R = 6_371_000.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Cap de 1 vers 2, en degrés depuis le nord dans le sens des aiguilles. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point décalé de [north] m vers le nord et [east] m vers l'est. */
    fun offset(lat: Double, lon: Double, north: Double, east: Double): Pair<Double, Double> {
        val dLat = Math.toDegrees(north / R)
        val dLon = Math.toDegrees(east / (R * cos(Math.toRadians(lat))))
        return (lat + dLat) to (lon + dLon)
    }
}

/** Moyenne d'angles en degrés : moyenne simple au début, puis lissage exponentiel. */
class AngleAvg(private val alpha: Float) {
    var value: Float? = null
        private set
    var count = 0
        private set

    fun add(deg: Float): Float {
        val v = value
        val a = max(alpha, 1f / (count + 1))
        val nv = if (v == null) norm360(deg) else norm360(v + a * Hohem.wrap180(deg - v))
        value = nv
        count++
        return nv
    }

    fun set(deg: Float, weight: Int) { value = norm360(deg); count = weight }
    fun reset() { value = null; count = 0 }
}

/** Position moyenne du téléphone (posé sur la nacelle, il bouge peu). */
class FixAvg {
    var lat = 0.0; private set
    var lon = 0.0; private set
    var acc = 0f; private set
    var n = 0; private set
    var lastAt = 0L; private set
    private var sw = 0.0
    private var sLat = 0.0
    private var sLon = 0.0

    fun add(lat: Double, lon: Double, acc: Float, at: Long) {
        if (n > 0 && Geo.distance(this.lat, this.lon, lat, lon) > 15.0 + acc + this.acc) reset()
        val w = 1.0 / max(acc, 1f).toDouble().pow(2)
        sw += w; sLat += lat * w; sLon += lon * w; n++
        this.lat = sLat / sw
        this.lon = sLon / sw
        this.acc = max(sqrt(1.0 / sw).toFloat(), 2f)
        lastAt = at
    }

    fun reset() { sw = 0.0; sLat = 0.0; sLon = 0.0; n = 0 }
}

/**
 * Azimut et élévation (degrés) de l'axe de la caméra, à partir d'une matrice de rotation Android
 * (appareil vers monde). Caméra arrière : -Z de l'appareil ; caméra avant : +Z.
 */
fun cameraDirection(r: FloatArray, front: Boolean): Pair<Float, Float> {
    val s = if (front) 1f else -1f
    val x = s * r[2]
    val y = s * r[5]
    val z = s * r[8]
    val az = norm360(Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat())
    val el = Math.toDegrees(asin(z.coerceIn(-1f, 1f).toDouble())).toFloat()
    return az to el
}

/**
 * Passe un décalage du repère de l'écran au repère du monde, quand l'écran ne tourne pas
 * avec le téléphone (rotation auto coupée). [delta] = rotation physique − rotation de l'écran.
 */
fun toWorld(dx: Float, dy: Float, delta: Int): Pair<Float, Float> = when (((delta % 360) + 360) % 360) {
    90 -> dy to -dx
    180 -> -dx to -dy
    270 -> -dy to dx
    else -> dx to dy
}

/**
 * Comment la nacelle répond à la commande de suivi : quel axe de la commande fait pivoter (pan)
 * et dans quel sens. Appris une fois par caméra et par orientation.
 */
data class Mapping(val panAxis: Int = 0, val panSign: Int = 1, val tiltSign: Int = 1) {
    /** (wx, wy) : sujet à droite / en bas dans le repère du monde → commande (x, y). */
    fun command(wx: Float, wy: Float): Pair<Float, Float> {
        val p = panSign * wx
        val t = tiltSign * wy
        return if (panAxis == 0) p to t else t to p
    }

    fun encode() = "$panAxis,$panSign,$tiltSign"

    companion object {
        fun decode(s: String?): Mapping? {
            val v = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
            if (v.size != 3 || v[0] !in 0..1 || abs(v[1]) != 1 || abs(v[2]) != 1) return null
            return Mapping(v[0], v[1], v[2])
        }

        /**
         * Déduit le sens à partir d'un essai : poussée +x puis poussée +y, mesurées avec le gyroscope
         * du téléphone (variation d'azimut et d'élévation de la caméra). Pour viser un sujet à droite,
         * l'azimut doit augmenter ; pour un sujet en bas, l'élévation doit baisser.
         */
        fun fromProbe(dAzX: Float, dElX: Float, dAzY: Float, dElY: Float, min: Float = 0.8f): Mapping? {
            val xIsPan = abs(dAzX) + abs(dElY) >= abs(dElX) + abs(dAzY)
            return if (xIsPan) {
                if (abs(dAzX) < min) null
                else Mapping(0, if (dAzX > 0) 1 else -1, if (abs(dElY) < min || dElY < 0) 1 else -1)
            } else {
                if (abs(dAzY) < min) null
                else Mapping(1, if (dAzY > 0) 1 else -1, if (abs(dElX) < min || dElX < 0) 1 else -1)
            }
        }
    }
}
