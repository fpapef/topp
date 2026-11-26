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
    /** Repères verticaux du corps pour le cadrage (y de l'image), null si invisibles. */
    val body: Body? = null,
)

/**
 * Hauteurs utiles au cadrage : haut du crâne, épaules, hanches, pieds, et une unité de longueur
 * (nez → épaules) qui permet d'estimer ce qui sort de l'image.
 */
data class Body(val headTop: Float, val shoulderY: Float, val unit: Float, val hipY: Float?, val footY: Float?) {
    fun scaled(k: Float): Body {
        fun c(v: Float) = 0.5f + (v - 0.5f) * k
        return Body(c(headTop), c(shoulderY), unit * k, hipY?.let(::c), footY?.let(::c))
    }
}

/**
 * La même détection vue avec un zoom multiplié par [k] (grossissement autour du centre de l'image).
 * Sert à ramener chaque image dans un repère « 1x », quel que soit l'objectif.
 */
fun Detection.scaled(k: Float): Detection {
    fun c(v: Float) = 0.5f + (v - 0.5f) * k
    fun p(v: Pt?) = v?.let { Pt(c(it.x), c(it.y)) }
    return Detection(
        Box(c(box.l), c(box.t), c(box.r), c(box.b)), p(aim)!!, p(wristL), p(wristR), scale * k, sig, aspect, body?.scaled(k),
    )
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
    val body = if (sh != null && ok(0)) {
        // Unité : nez → ligne des épaules, au moins un tiers de la largeur d'épaules (tête penchée)
        val unit = maxOf(sh.y - lm[0].y, abs(lm[11].x - lm[12].x) / aspect * 0.35f)
        val hip = if (ok(23) && ok(24)) (lm[23].y + lm[24].y) / 2f else null
        val feet = (27..32).filter { ok(it) }.map { lm[it].y }.maxOrNull()
        if (unit > 0.004f) Body(lm[0].y - 0.85f * unit, sh.y, unit, hip, feet) else null
    } else null
    return Detection(box, aim, wl, wr, scale.coerceAtLeast(0.02f), sig, aspect, body)
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

/** Cadrage choisi : ce qui doit tenir dans la hauteur de l'image. */
enum class Framing(
    /** Part de la hauteur de l'image occupée par le cadrage. */
    val fill: Float,
    /** Bas du cadre estimé depuis le haut du crâne, en unités nez → épaules (si le repère est hors champ). */
    val unitsBelowHead: Float,
) {
    FULL(0.85f, 9.3f), // corps entier
    HALF(0.80f, 4.4f), // tête et buste, jusqu'au ventre
    CLOSE(0.72f, 2.7f), // visage et épaules
}

/**
 * Cadrage automatique : le zoom glisse en continu pour garder le cadrage choisi, quelle que soit
 * la distance. Entre deux objectifs, c'est du zoom numérique ; à 3x et 5x, le téléphone passe
 * sur le téléobjectif. Pour éviter que l'objectif change sans arrêt autour de 3x ou 5x, le zoom
 * reste juste en dessous ou juste au-dessus tant que l'écart est petit.
 */
class Framer {
    private val spans = ArrayDeque<Pair<Long, Float>>()
    private val mids = ArrayDeque<Pair<Long, Float>>()
    private var target = Float.NaN // log du zoom visé, lissé
    private var lastT = 0L
    private var gliding = false
    /** Zoom commandé. */
    var zoom = 1f
        private set

    fun reset(current: Float) {
        spans.clear(); mids.clear(); target = Float.NaN; lastT = 0L; gliding = false; zoom = current.coerceAtLeast(1f)
    }

    /** Le zoom a été changé à la main ou par la caméra : on repart de là. */
    fun sync(current: Float) { zoom = current.coerceAtLeast(1f); target = Float.NaN; gliding = false }

    /**
     * Hauteur du cadrage dans le repère 1x (fraction de l'image) et position de son milieu, d'après le
     * squelette. Le bas du cadre vient du repère s'il est bien dans l'image, sinon des proportions.
     */
    fun measure(b: Body, framing: Framing, frameZoom: Float): Pair<Float, Float> {
        fun inFrame(y: Float) = 0.5f + (y - 0.5f) * frameZoom < 0.96f
        val est = b.headTop + framing.unitsBelowHead * b.unit
        val bottom = when (framing) {
            Framing.FULL -> b.footY?.takeIf(::inFrame)?.let { it + 0.3f * b.unit } ?: est
            Framing.HALF -> b.hipY?.takeIf(::inFrame) ?: est
            Framing.CLOSE -> b.shoulderY + 0.9f * b.unit
        }
        val top = b.headTop - 0.15f * b.unit // un peu d'air au-dessus de la tête
        return (bottom - top).coerceAtLeast(0.01f) to (top + bottom) / 2f
    }

    private fun median(q: ArrayDeque<Pair<Long, Float>>) = q.map { it.second }.sorted().let { it[it.size / 2] }

    /** Milieu du cadrage (repère 1x), lissé : c'est le point que la nacelle garde au centre. */
    fun mid(): Float? = if (mids.isEmpty()) null else median(mids)

    /**
     * Une mesure par image : renvoie le zoom à appliquer maintenant.
     * [wx], [wy] écart du sujet au centre à l'écran, [speed] sa vitesse à l'écran (fraction par seconde).
     */
    fun update(
        t: Long, span: Float, mid: Float, framing: Framing, wx: Float, wy: Float, speed: Float,
        maxZoom: Float, lensSteps: List<Float>,
    ): Float {
        spans.addLast(t to span); mids.addLast(t to mid)
        while (t - spans.first().first > 1_200) spans.removeFirst()
        while (t - mids.first().first > 600) mids.removeFirst()
        var want = framing.fill / median(spans)
        // Marge de sécurité : sujet décentré ou rapide → on desserre un peu pour le garder dans le cadre
        val off = maxOf(abs(wx) - 0.18f, abs(wy) - 0.2f, 0f)
        want *= (1f - 1.6f * off - 0.7f * speed).coerceIn(0.5f, 1f)
        want = want.coerceIn(1f, maxZoom)
        val lw = kotlin.math.ln(want)
        val dt = if (lastT == 0L) 0.033f else ((t - lastT) / 1000f).coerceIn(0.001f, 0.2f)
        lastT = t
        target = if (target.isNaN()) lw else target + (lw - target) * (dt / (0.7f + dt))
        return glide(dt, kotlin.math.exp(target), lensSteps, fast = off > 0.12f)
    }

    /** Sujet perdu : on élargit doucement pour le retrouver. */
    fun widen(t: Long, lensSteps: List<Float>): Float {
        val dt = if (lastT == 0L) 0.033f else ((t - lastT) / 1000f).coerceIn(0.001f, 0.2f)
        lastT = t
        target = 0f
        return glide(dt, 1f, lensSteps, fast = true)
    }

    private fun glide(dt: Float, wantZ: Float, steps: List<Float>, fast: Boolean): Float {
        var goal = wantZ
        // Pas de va-et-vient d'objectif autour de 3x / 5x
        for (b in steps.filter { it > 1.01f }) {
            if (zoom < b && goal >= b && goal < b * 1.08f) goal = b * 0.985f
            if (zoom >= b && goal < b && goal > b / 1.08f) goal = b
        }
        val err = kotlin.math.ln(goal) - kotlin.math.ln(zoom)
        // Zone morte avec hystérésis : immobile tant que l'écart reste sous 5 %, et une fois parti,
        // on va jusqu'au bout (à 1,5 % près) pour ne pas s'arrêter à mi-chemin
        if (!gliding && abs(err) < 0.05f && !fast) return zoom
        gliding = abs(err) > 0.015f
        if (!gliding) return zoom
        val maxRate = if (fast || err < 0) 0.9f else 0.45f // dézoomer peut aller plus vite que zoomer
        val step = (err * 2f * dt).coerceIn(-maxRate * dt, maxRate * dt)
        zoom = kotlin.math.exp(kotlin.math.ln(zoom) + step).coerceAtLeast(1f)
        return zoom
    }
}

/** Objectif physique : identifiant et focale équivalente 24×36 (mm). */
data class LensInfo(val id: String, val eq: Float) {
    val ultraWide: Boolean get() = eq in 1f..18f
    val main: Boolean get() = eq > 18f && eq < 35f
}

/** Une caméra arrière proposée par le téléphone, avec les objectifs qui la composent. */
data class CamOption(val cameraId: String, val lenses: List<LensInfo>, val logical: Boolean)

/** Texte pour le journal : « 0 = 0,6x + 1x + 3x + 5x ; 2 = 0,6x ». */
fun describeCameras(opts: List<CamOption>): String {
    val main = opts.flatMap { it.lenses }.filter { it.main }.minByOrNull { abs(it.eq - 24f) }?.eq ?: 24f
    return opts.joinToString(" ; ") { o ->
        o.cameraId + " = " + o.lenses.sortedBy { it.eq }.joinToString(" + ") { l ->
            val r = l.eq / main
            lensLabel(if (r > 1.5f) kotlin.math.round(r) else r)
        }
    }
}

/** Caméra retenue, et éventuellement le seul objectif physique à utiliser. */
data class CamPlan(val cameraId: String?, val physicalId: String?, val note: String)

/**
 * Choix de la caméra arrière quand le 0,6x est bloqué :
 * 1) une caméra sans grand-angle (de préférence avec téléobjectifs) ;
 * 2) sinon, seulement l'objectif principal de la caméra combinée ;
 * 3) sinon, la caméra par défaut (le 0,6x n'est jamais choisi par le zoom, mais reste branché).
 */
fun chooseCamera(opts: List<CamOption>, defaultId: String?, blockUltraWide: Boolean): CamPlan {
    val def = opts.firstOrNull { it.cameraId == defaultId } ?: opts.firstOrNull()
        ?: return CamPlan(null, null, "")
    if (!blockUltraWide || def.lenses.none { it.ultraWide }) return CamPlan(def.cameraId, null, "")
    val clean = opts.filter { o -> o.lenses.isNotEmpty() && o.lenses.none { it.ultraWide } && o.lenses.any { it.main } }
        .maxByOrNull { it.lenses.size }
    if (clean != null) return CamPlan(clean.cameraId, null, "caméra ${clean.cameraId} sans grand-angle")
    val main = def.lenses.filter { it.main }.minByOrNull { abs(it.eq - 24f) }
    if (def.logical && main != null) return CamPlan(def.cameraId, main.id, "objectif principal seul")
    return CamPlan(def.cameraId, null, "impossible de séparer le 0,6x")
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
