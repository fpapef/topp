package com.fpapef.topp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
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
 * Détecte les personnes dans l'image de la caméra (MediaPipe Pose, sur le téléphone, hors ligne).
 * Ne travaille que lorsque le suivi caméra est actif.
 */
class Vision(
    private val ctx: Context,
    private val follow: Follow,
    private val log: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var front = false

    private var landmarker: PoseLandmarker? = null
    private var failed = false
    private var lastTs = 0L
    private var errors = 0
    private var pixels = IntArray(0)

    private fun ensure(): PoseLandmarker? {
        landmarker?.let { return it }
        if (failed) return null
        return runCatching {
            PoseLandmarker.createFromOptions(
                ctx,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(4)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .build(),
            )
        }.onSuccess { landmarker = it }
            .onFailure { failed = true; log("Détection des personnes indisponible : ${it.message}") }
            .getOrNull()
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!follow.on || follow.mode == FollowMode.GPS) return
            val lm = ensure() ?: return
            val t = SystemClock.elapsedRealtime()
            val bmp = upright(image)
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
                    detectionOf(pose.map { Lm(it.x(), it.y(), it.visibility().orElse(0f)) }, pixels, w, h)
                }
            }
            follow.onFrame(t, dets, front, w.toFloat() / h)
            errors = 0
        } catch (e: Throwable) {
            if (errors++ == 0) log("Détection : ${e.message}")
        } finally {
            image.close()
        }
    }

    /** Image redressée dans le sens de l'écran, réduite à 640 px sur le grand côté. */
    private fun upright(image: ImageProxy): Bitmap {
        val src = image.toBitmap()
        val c = image.cropRect
        val crop = android.graphics.Rect(
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
