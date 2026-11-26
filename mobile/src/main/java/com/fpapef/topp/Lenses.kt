package com.fpapef.topp

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Objectifs arrière : principal (1x) et téléobjectifs (3x, 5x), sans l'ultra grand-angle.
 * On passe par le zoom de la caméra arrière « combinée » : le téléphone bascule lui-même
 * sur le bon objectif au bon moment. Le zoom glisse en douceur d'un objectif à l'autre,
 * comme dans l'appareil photo, au lieu de sauter.
 */
class Lenses(
    private val ctx: Context,
    private val follow: Follow,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {
    val stops = MutableStateFlow<List<Float>>(emptyList())
    /** Objectif choisi (celui qui s'allume à l'écran). */
    val zoom = MutableStateFlow(1f)

    private var camera: Camera? = null
    private var front = false
    private var physicalLabels: Map<String, String> = emptyMap()
    @Volatile private var activePhysical: String? = null
    private var rangeLogged = false
    private var anim: Job? = null
    /** Dernier zoom demandé à la caméra (il bouge pendant la transition). */
    @Volatile var applied = 1f
        private set

    // Zoom réel de chaque image, d'après les résultats de capture (horodatage capteur → zoom)
    private val stamps = LongArray(96)
    private val ratios = FloatArray(96)
    private var head = 0
    @Volatile var lastResultZoom = 1f
        private set

    /** Caméra tout juste liée. */
    fun attach(cam: Camera, isFront: Boolean) {
        anim?.cancel()
        camera = cam
        front = isFront
        zoom.value = 1f
        applied = 1f
        lastResultZoom = 1f
        synchronized(stamps) { stamps.fill(0L); head = 0 }
        activePhysical = null
        rangeLogged = false
        stops.value = emptyList()
        follow.setLensStops(emptyList(), 1f)
        // Le grand-angle 0,6x est parfois choisi par défaut : on demande explicitement le principal
        cam.cameraControl.setZoomRatio(1f)
        if (!isFront) physicalLabels = describePhysical(cam)
    }

    /** Plage de zoom connue (elle arrive juste après l'ouverture de la caméra). */
    fun onZoomRange(min: Float, max: Float) {
        if (camera == null) return
        val list = if (front) emptyList() else listOf(1f, 3f, 5f).filter { it <= max + 0.01f }.takeIf { it.size > 1 }
            ?: emptyList()
        if (list != stops.value) {
            stops.value = list
            follow.setLensStops(list, zoom.value)
        }
        if (!rangeLogged && !front) {
            rangeLogged = true
            log("Caméra arrière : zoom de ${lensLabel(min)} à ${lensLabel(max)}" +
                if (list.size > 1) ", objectifs proposés ${list.joinToString(" ") { lensLabel(it) }}" else "")
        }
    }

    /**
     * Change d'objectif en glissant vers [z] (0,3 à 0,7 s selon l'écart, accéléré puis freiné).
     * [byUser] : choisi à l'écran (met le zoom automatique du suivi en pause).
     */
    fun set(z: Float, byUser: Boolean) {
        val cam = camera ?: return
        val from = applied
        if (abs(from - z) < 0.01f && anim?.isActive != true) {
            zoom.value = z
            follow.onZoomDone(z)
            return
        }
        anim?.cancel()
        zoom.value = z
        follow.onZoomStarting(byUser, z)
        anim = scope.launch {
            val span = abs(ln(z / from))
            val duration = (180f + 300f * span).coerceIn(250f, 750f)
            val t0 = System.nanoTime()
            while (true) {
                val u = ((System.nanoTime() - t0) / 1e6f / duration).coerceIn(0f, 1f)
                val e = u * u * (3f - 2f * u) // départ et arrivée en douceur
                val r = if (u >= 1f) z else from * exp(ln(z / from) * e) // progression régulière à l'œil
                applied = r
                cam.cameraControl.setZoomRatio(r)
                if (u >= 1f) break
                delay(16)
            }
            follow.onZoomDone(z)
        }
    }

    /** Résultat de capture : zoom réel de l'image prise à [timestampNs], et objectif utilisé. */
    fun onCaptureResult(timestampNs: Long?, ratio: Float?, physicalId: String?) {
        if (timestampNs != null && ratio != null) {
            synchronized(stamps) {
                stamps[head] = timestampNs
                ratios[head] = ratio
                head = (head + 1) % stamps.size
            }
            lastResultZoom = ratio
        }
        if (physicalId != null && physicalId != activePhysical) {
            activePhysical = physicalId
            physicalLabels[physicalId]?.let { log("Objectif utilisé : $it") }
        }
    }

    /** Zoom de l'image analysée prise à [timestampNs] ; à défaut, le dernier connu. */
    fun zoomForFrame(timestampNs: Long): Float = synchronized(stamps) {
        var best = -1
        var bestDt = Long.MAX_VALUE
        for (i in stamps.indices) {
            val s = stamps[i]
            if (s == 0L) continue
            val dt = abs(s - timestampNs)
            if (dt < bestDt) { bestDt = dt; best = i }
        }
        if (best >= 0 && bestDt < 5_000_000L) ratios[best] else lastResultZoomOr()
    }

    private fun lastResultZoomOr() = if (stamps.all { it == 0L }) applied else lastResultZoom

    /** Liste les objectifs derrière la caméra combinée, avec leur grossissement par rapport au principal. */
    @SuppressLint("UnsafeOptInUsageError")
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun describePhysical(cam: Camera): Map<String, String> = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val logical = Camera2CameraInfo.from(cam.cameraInfo).cameraId
        val ids = cm.getCameraCharacteristics(logical).physicalCameraIds
        if (ids.isEmpty()) {
            log("Le téléphone ne détaille pas ses objectifs arrière : il choisit lui-même l'objectif selon le zoom")
            return@runCatching emptyMap()
        }
        val eq = ids.associateWith { id ->
            val c = cm.getCameraCharacteristics(id)
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val diag = size?.let { sqrt(it.width * it.width + it.height * it.height) } ?: 0f
            if (diag > 0f) f * 43.27f / diag else 0f
        }.filterValues { it > 0f }
        val main = eq.values.minByOrNull { abs(it - 24f) } ?: return@runCatching emptyMap()
        val labels = eq.mapValues { (_, v) ->
            val r = v / main
            lensLabel(if (r > 1.5f) kotlin.math.round(r) else r) +
                when { r < 0.8f -> " (grand-angle)"; r > 1.5f -> " (téléobjectif)"; else -> " (principal)" }
        }
        log("Objectifs arrière : " + labels.values.sortedBy { it.substringBefore('x').replace(',', '.').toFloatOrNull() ?: 0f }
            .joinToString(", "))
        labels
    }.getOrElse { emptyMap() }
}
