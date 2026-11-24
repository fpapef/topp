package com.fpapef.topp

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = ctx.getSharedPreferences("topp", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("fmap_b_p", "0,1,1").commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        ble = GimbalBle(ctx, scope) { logs += it }
        ble.state.value = GimbalBle.State(status = GimbalBle.Status.READY)
        val controller = Controller(ble, scope, prefs) { logs += it }
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
}
