package com.fpapef.topp

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parcours complet du suivi caméra, sans téléphone : images simulées → choix de la cible → commande. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FollowFlowTest {
    private lateinit var follow: Follow
    private lateinit var ble: GimbalBle
    private lateinit var controller: Controller
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("topp", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("fmap_b_p", "0,1,1").commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ble = GimbalBle(ctx, scope) { logs += it }
        ble.state.value = GimbalBle.State(status = GimbalBle.Status.READY)
        controller = Controller(ble, scope, prefs) { logs += it }
        follow = Follow(ctx, ble, controller, scope, prefs) { logs += it }
    }

    private fun person(cx: Float): Detection {
        val lm = MutableList(33) { Lm(cx, 0.5f, 0.1f) }
        lm[0] = Lm(cx, 0.25f, 0.99f)
        lm[11] = Lm(cx + 0.05f, 0.35f, 0.99f); lm[12] = Lm(cx - 0.05f, 0.35f, 0.99f)
        lm[23] = Lm(cx + 0.04f, 0.6f, 0.99f); lm[24] = Lm(cx - 0.04f, 0.6f, 0.99f)
        lm[27] = Lm(cx + 0.04f, 0.9f, 0.9f); lm[28] = Lm(cx - 0.04f, 0.9f, 0.9f)
        lm[15] = Lm(cx + 0.1f, 0.55f, 0.9f); lm[16] = Lm(cx - 0.1f, 0.55f, 0.9f)
        return detectionOf(lm)!!
    }

    @Test
    fun unePersonneEstSuivie() {
        assertTrue(follow.start())
        val t0 = SystemClock.elapsedRealtime()
        for (i in 0 until 10) follow.onFrame(t0 + i * 50L, listOf(person(0.7f)), false, 16f / 9f)
        val cmd = follow.step(t0 + 470, false)
        assertNotNull("aucune commande envoyée", cmd)
        assertEquals(FollowStatus.CAMERA, followStatus())
        // Sujet à droite (x = +0,2) : champ pan = trunc(−x·510), donc négatif
        assertTrue("pan ${panOf(cmd!!)}", panOf(cmd) < -50)
    }

    @Test
    fun personneNePasseRienEtLeDit() {
        assertTrue(follow.start())
        val t0 = SystemClock.elapsedRealtime()
        for (i in 0 until 5) follow.onFrame(t0 + i * 50L, emptyList(), false, 16f / 9f)
        follow.step(t0 + 260, false)
        assertEquals(FollowStatus.LOOKING, followStatus())
    }

    @Test
    fun detectionEnPanneSAffiche() {
        assertTrue(follow.start())
        follow.visionError = "UnsatisfiedLinkError: test"
        follow.step(SystemClock.elapsedRealtime(), false)
        follow.onFrame(SystemClock.elapsedRealtime(), emptyList(), false, 16f / 9f)
        assertEquals("error", follow.ui.value.vision)
        assertTrue(follow.ui.value.text(false).contains("en panne"))
    }

    /** Champ pan (10 bits signés) d'une trame de suivi 0x60. */
    private fun panOf(frame: ByteArray): Int {
        var word = 0L
        for (i in 9 until 14) word = (word shl 8) or (frame[i].toLong() and 0xFF)
        val p = ((word shr 30) and 0x3FF).toInt()
        return if (p >= 512) p - 1024 else p
    }

    private fun followStatus(): FollowStatus {
        follow.onFrame(SystemClock.elapsedRealtime() + 1, emptyList(), false, 16f / 9f) // publie l'état
        return follow.ui.value.status
    }

    @Test
    fun cameraFrontaleInverseLeHautEtLeBas() {
        val (x0, y0) = controller.offsetFor(0.5f, 0.5f)
        controller.frontCamera = true
        val (x1, y1) = controller.offsetFor(0.5f, 0.5f)
        assertEquals(x0, x1, 1e-6f)
        assertEquals(-y0, y1, 1e-6f)
        // Correction coupée dans les réglages : même sens qu'à l'arrière
        controller.autoCorrectFront = false
        assertEquals(y0, controller.offsetFor(0.5f, 0.5f).second, 1e-6f)
        // Le réglage « Inverser haut et bas » reste appliqué par-dessus
        controller.autoCorrectFront = true
        controller.invertY = true
        assertEquals(y0, controller.offsetFor(0.5f, 0.5f).second, 1e-6f)
    }

    @Test
    fun zoomAutoPendantLeSuivi() {
        val zooms = mutableListOf<Float>()
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch { follow.zoomRequest.collect { zooms += it } }
        follow.setLensStops(listOf(1f, 3f, 5f), 1f)
        assertTrue(follow.start())
        val t0 = SystemClock.elapsedRealtime()
        // Une personne petite et centrée pendant 2 s : demande du 3x
        var t = t0
        for (i in 0 until 40) {
            t = t0 + i * 50L
            follow.onFrame(t, listOf(small(0.5f, 1f)), false, 16f / 9f)
            follow.step(t + 10, false)
        }
        assertEquals(listOf(3f), zooms)
        assertEquals(null, follow.step(t + 20, false)) // nacelle à l'arrêt pendant le changement
        follow.onZoomStarting(byUser = false)
        follow.onZoomApplied(1f, 3f)
        // Après le changement, la même personne, trois fois plus grande, est toujours la cible
        val t1 = SystemClock.elapsedRealtime() + 300
        for (i in 0 until 6) follow.onFrame(t1 + i * 50L, listOf(small(0.5f, 3f)), false, 16f / 9f)
        assertNotNull(follow.step(t1 + 320, false))
        assertEquals(FollowStatus.CAMERA, followStatus())
        assertTrue(follow.ui.value.autoZoom)
        job.cancel()
    }

    /** Personne debout au centre, haute de 15 % de l'image à 1x, grossie de [k]. */
    private fun small(cx: Float, k: Float): Detection {
        fun y(v: Float) = 0.42f + (v - 0.42f) * k
        fun x(v: Float) = cx + (v - cx) * k
        val lm = MutableList(33) { Lm(cx, y(0.47f), 0.1f) }
        lm[0] = Lm(cx, y(0.39f), 0.99f)
        lm[11] = Lm(x(cx + 0.012f), y(0.42f), 0.99f); lm[12] = Lm(x(cx - 0.012f), y(0.42f), 0.99f)
        lm[23] = Lm(x(cx + 0.01f), y(0.47f), 0.99f); lm[24] = Lm(x(cx - 0.01f), y(0.47f), 0.99f)
        lm[27] = Lm(x(cx + 0.01f), y(0.535f), 0.9f); lm[28] = Lm(x(cx - 0.01f), y(0.535f), 0.9f)
        lm[15] = Lm(x(cx + 0.025f), y(0.46f), 0.9f); lm[16] = Lm(x(cx - 0.025f), y(0.46f), 0.9f)
        return detectionOf(lm)!!
    }
}
