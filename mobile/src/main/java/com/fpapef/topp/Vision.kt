package com.fpapef.topp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.max
import kotlin.math.min

/**
 * Détecte les personnes dans l'image de la caméra (MediaPipe Pose, sur le téléphone).
 * Source normale : le flux d'analyse de CameraX. Secours : une copie de l'aperçu affiché,
 * si la caméra ne fournit pas ce flux.
 * Ne travaille que lorsque le suivi caméra est actif.
 */
class Vision(
    private val ctx: Context,
    private val follow: Follow,
    private val lenses: Lenses,
    private val log: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var front = false
    /** Dernière image reçue du flux d'analyse (0 = jamais). */
    @Volatile var analysisAt = 0L
        private set
    @Volatile var busy = false
        private set

    private var landmarker: PoseLandmarker? = null
    private var failedAt = 0L
    private var lastTs = 0L
    private var errors = 0
    private var pixels = IntArray(0)
    private var loggedSource = ""

    private val wanted: Boolean get() = follow.on && follow.mode != FollowMode.GPS

    private fun ensure(): PoseLandmarker? {
        landmarker?.let { return it }
        if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < 10_000) return null
        return runCatching {
            PoseLandmarker.createFromOptions(
                ctx,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(4)
                    .setMinPoseDetectionConfidence(0.4f)
                    .setMinPosePresenceConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .build(),
            )
        }.onSuccess {
            landmarker = it
            failedAt = 0L
            follow.visionError = null
        }.onFailure {
            failedAt = SystemClock.elapsedRealtime()
            follow.visionError = describe(it)
            log("Détection des personnes indisponible : ${describe(it)}")
        }.getOrNull()
    }

    override fun analyze(image: ImageProxy) {
        try {
            analysisAt = SystemClock.elapsedRealtime()
            if (!wanted) return
            note("flux d'analyse ${image.width}×${image.height}, rotation ${image.imageInfo.rotationDegrees}°")
            run(upright(image), lenses.zoomForFrame(image.imageInfo.timestamp))
        } catch (e: Throwable) {
            fail(e)
        } finally {
            image.close()
        }
    }

    /** Secours : analyse une copie de l'aperçu (déjà à l'endroit, en miroir pour la caméra avant). */
    fun processPreview(shot: Bitmap) {
        busy = true
        try {
            if (!wanted) return
            note("copie de l'aperçu ${shot.width}×${shot.height}")
            val s = min(1f, 640f / max(shot.width, shot.height))
            val m = Matrix().apply {
                postScale(s, s)
                if (front) postScale(-1f, 1f) // on remet l'image à l'endroit, comme le flux d'analyse
            }
            run(Bitmap.createBitmap(shot, 0, 0, shot.width, shot.height, m, true), lenses.lastResultZoom)
        } catch (e: Throwable) {
            fail(e)
        } finally {
            busy = false
        }
    }

    private fun run(bmp: Bitmap, zoom: Float) {
        val lm = ensure() ?: return
        val t = SystemClock.elapsedRealtime()
        val ts = max(t, lastTs + 1)
        lastTs = ts
        val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
        val w = bmp.width
        val h = bmp.height
        val poses = res.landmarks()
        val dets = if (poses.isEmpty()) emptyList() else {
            if (pixels.size != w * h) pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            poses.mapNotNull { pose ->
                detectionOf(
                    pose.map {
                        // Visibilité absente : on se rabat sur la présence, puis sur « visible »
                        Lm(it.x(), it.y(), it.visibility().orElse(it.presence().orElse(1f)))
                    },
                    pixels, w, h,
                )
            }
        }
        follow.onFrame(t, dets, front, w.toFloat() / h, zoom)
        if (errors > 0 || follow.visionError != null) { errors = 0; follow.visionError = null }
    }

    private fun fail(e: Throwable) {
        val d = describe(e)
        follow.visionError = d
        if (errors++ == 0) log("Détection : $d")
    }

    private fun note(source: String) {
        val key = source.substringBefore(' ')
        if (key != loggedSource) { loggedSource = key; log("Suivi : $source") }
    }

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        return "${root.javaClass.simpleName}: ${root.message ?: "sans message"}".take(160)
    }

    /** Image redressée dans le sens de l'écran, réduite à 640 px sur le grand côté. */
    private fun upright(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val c = image.cropRect
        val crop = Rect(
            c.left.coerceIn(0, src.width - 1), c.top.coerceIn(0, src.height - 1),
            c.right.coerceIn(1, src.width), c.bottom.coerceIn(1, src.height),
        )
        val s = min(1f, 640f / max(crop.width(), crop.height()))
        val m = Matrix().apply {
            postScale(s, s)
            postRotate(image.imageInfo.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height(), m, true)
    }

    fun close() {
        runCatching { landmarker?.close() }
        landmarker = null
    }

    companion object {
        const val MODEL = "pose_landmarker_lite.task"
    }
}
