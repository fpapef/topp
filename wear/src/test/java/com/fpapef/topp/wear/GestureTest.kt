package com.fpapef.topp.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTest {
    @Test
    fun deadZoneKeepsStill() {
        val (x, y) = Gesture.vector(5f, -5.9f, 1f, false, false)
        assertEquals(0f, x, 0f)
        assertEquals(0f, y, 0f)
    }

    @Test
    fun handRightAndUpMovesRightAndUp() {
        // Main tournée vers la droite (dYaw négatif vu du dessus) et levée (dElev positif)
        val (x, y) = Gesture.vector(-20f, 20f, 1f, false, false)
        assertTrue(x > 0f)
        assertTrue(y < 0f) // y < 0 = vers le haut, comme le joystick
        assertEquals(14f / 30f, x, 0.001f)
    }

    @Test
    fun saturatesAndSensitivity() {
        assertEquals(1f, Gesture.vector(-80f, 0f, 1f, false, false).first, 0f)
        // Sensibilité ×2 : même geste, deux fois plus de vitesse
        assertEquals(2 * 14f / 30f, Gesture.vector(-20f, 0f, 2f, false, false).first, 0.001f)
    }

    @Test
    fun inversion() {
        val (x, y) = Gesture.vector(-20f, 20f, 1f, true, true)
        assertTrue(x < 0f && y > 0f)
    }

    @Test
    fun wrap() {
        assertEquals(-20f, Gesture.wrap180(340f), 0.001f)
        assertEquals(10f, Gesture.wrap180(-350f), 0.001f)
    }
}
