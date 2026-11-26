package com.fpapef.topp

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Objectifs arrière : principal (1x) et téléobjectifs (3x, 5x), sans l'ultra grand-angle.
 * On passe par le zoom de la caméra arrière « combinée » : le téléphone bascule lui-même
 * sur le bon objectif (3x et 5x sur le S25 Ultra), même pendant l'enregistrement.
 */
class Lenses(private val ctx: Context, private val follow: Follow, private val log: (String) -> Unit) {
    val stops = MutableStateFlow<List<Float>>(emptyList())
    val zoom = MutableStateFlow(1f)

    private var camera: Camera? = null
    private var front = false
    private var physicalLabels: Map<String, String> = emptyMap()
    @Volatile private var activePhysical: String? = null
    private var rangeLogged = false

    /** Caméra tout juste liée. */
    fun attach(cam: Camera, isFront: Boolean) {
        camera = cam
        front = isFront
        zoom.value = 1f
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
            log("Caméra arrière : zoom de ${fmt(min)} à ${fmt(max)}" +
                if (list.size > 1) ", objectifs proposés ${list.joinToString(" ") { lensLabel(it) }}" else "")
        }
    }

    /** Change d'objectif. [byUser] : choisi à l'écran (met le zoom automatique en pause). */
    fun set(z: Float, byUser: Boolean, executor: Executor) {
        val cam = camera ?: return
        val old = zoom.value
        if (abs(old - z) < 0.01f) { if (!byUser) follow.onZoomFailed(); return }
        follow.onZoomStarting(byUser)
        val f = cam.cameraControl.setZoomRatio(z)
        f.addListener({
            if (runCatching { f.get() }.isSuccess) {
                zoom.value = z
                follow.onZoomApplied(old, z)
            } else {
                follow.onZoomFailed()
            }
        }, executor)
    }

    /** Objectif réellement utilisé par le téléphone, d'après chaque image (pour le journal). */
    fun onActivePhysical(id: String?) {
        if (id == null || id == activePhysical) return
        activePhysical = id
        val label = physicalLabels[id] ?: return
        log("Objectif utilisé : $label")
    }

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

    private fun fmt(v: Float) = lensLabel(v)
}
