package com.fpapef.topp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class TrackTest {

    // ---------- Géographie ----------

    @Test
    fun distanceEtCap() {
        val lat = 14.7167
        val lon = -17.4677
        val (nLat, nLon) = Geo.offset(lat, lon, 20.0, 0.0)
        assertEquals(20.0, Geo.distance(lat, lon, nLat, nLon), 0.05)
        assertEquals(0.0, Geo.bearing(lat, lon, nLat, nLon), 0.1)
        val (eLat, eLon) = Geo.offset(lat, lon, 0.0, 15.0)
        assertEquals(15.0, Geo.distance(lat, lon, eLat, eLon), 0.05)
        assertEquals(90.0, Geo.bearing(lat, lon, eLat, eLon), 0.1)
        val (swLat, swLon) = Geo.offset(lat, lon, -10.0, -10.0)
        assertEquals(225.0, Geo.bearing(lat, lon, swLat, swLon), 0.2)
    }

    @Test
    fun moyenneDAnglesAutourDuNord() {
        val a = AngleAvg(0.1f)
        a.add(350f)
        a.add(10f)
        val v = a.value!!
        assertTrue("moyenne $v", v < 1f || v > 359f)
        a.reset()
        repeat(50) { a.add(if (it % 2 == 0) 355f else 15f) }
        assertEquals(5f, Hohem.wrap180(a.value!!), 3f)
    }

    @Test
    fun positionMoyenneDuTelephone() {
        val f = FixAvg()
        f.add(14.7, -17.4, 5f, 0)
        f.add(14.70001, -17.40001, 5f, 1)
        assertEquals(2, f.n)
        // Saut de 200 m : le téléphone a bougé, on repart de zéro
        val (lat, lon) = Geo.offset(14.7, -17.4, 200.0, 0.0)
        f.add(lat, lon, 5f, 2)
        assertEquals(1, f.n)
        assertEquals(lat, f.lat, 1e-9)
    }

    // ---------- Orientation ----------

    @Test
    fun directionDeLaCamera() {
        // Téléphone debout, dos vers le nord : X = est, Y = haut, Z = vers moi (sud)
        val north = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val (az, el) = cameraDirection(north, front = false)
        assertEquals(0f, Hohem.wrap180(az), 0.01f)
        assertEquals(0f, el, 0.01f)
        assertEquals(180f, cameraDirection(north, front = true).first, 0.01f)
        // Dos vers l'est
        val east = floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
        assertEquals(90f, cameraDirection(east, front = false).first, 0.01f)
        // À plat sur une table, dos vers le sol
        val flat = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(-90f, cameraDirection(flat, front = false).second, 0.01f)
    }

    @Test
    fun ecranVersMonde() {
        assertEquals(0.2f to 0.1f, toWorld(0.2f, 0.1f, 0))
        // Téléphone tourné d'un quart vers la gauche, écran resté en portrait
        assertEquals(0.1f to -0.2f, toWorld(0.2f, 0.1f, 90))
        assertEquals(-0.2f to -0.1f, toWorld(0.2f, 0.1f, 180))
        assertEquals(-0.1f to 0.2f, toWorld(0.2f, 0.1f, 270))
        assertEquals(-0.1f to 0.2f, toWorld(0.2f, 0.1f, -90))
    }

    // ---------- Apprentissage du sens ----------

    @Test
    fun sensAppris() {
        // +x fait tourner à droite, +y fait baisser la caméra : sens direct
        assertEquals(Mapping(0, 1, 1), Mapping.fromProbe(6f, 0.3f, 0.2f, -4f))
        // Tout inversé
        assertEquals(Mapping(0, -1, -1), Mapping.fromProbe(-6f, 0.3f, 0.2f, 4f))
        // Axes croisés : x agit sur le tilt, y sur le pan
        assertEquals(Mapping(1, 1, 1), Mapping.fromProbe(0.2f, -4f, 5f, 0.1f))
        // Le tilt n'a pas bougé (en butée) : on garde le pan appris
        assertEquals(Mapping(0, -1, 1), Mapping.fromProbe(-3f, 0f, 0f, 0.1f))
        // Rien n'a bougé
        assertNull(Mapping.fromProbe(0.1f, 0.1f, 0.2f, 0.1f))
    }

    @Test
    fun commandeSelonLeSens() {
        assertEquals(0.2f to 0.1f, Mapping().command(0.2f, 0.1f))
        assertEquals(-0.2f to 0.1f, Mapping(0, -1, 1).command(0.2f, 0.1f))
        assertEquals(0.1f to 0.2f, Mapping(1, 1, 1).command(0.2f, 0.1f))
        assertEquals(Mapping(1, -1, 1), Mapping.decode(Mapping(1, -1, 1).encode()))
        assertNull(Mapping.decode("2,1,1"))
        assertNull(Mapping.decode(null))
    }

    // ---------- Détection ----------

    private fun person(cx: Float, top: Float, height: Float, wristLY: Float? = null): List<Lm> {
        val lm = MutableList(33) { Lm(cx, top + height * 0.5f, 0.1f) }
        val sw = height * 0.25f
        lm[0] = Lm(cx, top + height * 0.08f, 0.99f) // nez
        lm[11] = Lm(cx + sw / 2, top + height * 0.2f, 0.99f)
        lm[12] = Lm(cx - sw / 2, top + height * 0.2f, 0.99f)
        lm[23] = Lm(cx + sw / 3, top + height * 0.55f, 0.99f)
        lm[24] = Lm(cx - sw / 3, top + height * 0.55f, 0.99f)
        lm[27] = Lm(cx + sw / 3, top + height, 0.95f)
        lm[28] = Lm(cx - sw / 3, top + height, 0.95f)
        lm[15] = Lm(cx + sw, wristLY ?: (top + height * 0.5f), 0.9f)
        lm[16] = Lm(cx - sw, top + height * 0.5f, 0.9f)
        return lm
    }

    @Test
    fun detectionDepuisLeSquelette() {
        val d = detectionOf(person(0.6f, 0.2f, 0.6f))!!
        assertEquals(0.6f, d.box.cx, 0.02f)
        assertTrue(d.box.t < 0.25f && d.box.b >= 0.79f)
        // Le point visé est entre le nez et les épaules
        assertEquals(0.6f, d.aim.x, 0.001f)
        assertEquals(0.2f + 0.6f * 0.14f, d.aim.y, 0.01f)
        assertNotNull(d.wristL)
        // Trop peu de repères visibles : rien
        assertNull(detectionOf(List(33) { Lm(0.5f, 0.5f, 0.1f) }))
    }

    @Test
    fun couleursDuBuste() {
        val w = 64
        val h = 48
        val red = IntArray(w * h) { 0xFFC03020.toInt() }
        val blue = IntArray(w * h) { 0xFF2040C0.toInt() }
        val lm = person(0.5f, 0.1f, 0.8f)
        val a = torsoSig(lm, red, w, h)!!
        val b = torsoSig(lm, blue, w, h)!!
        assertEquals(1f, sigSimilarity(a, a)!!, 1e-4f)
        assertTrue(sigSimilarity(a, b)!! < 0.2f)
        assertNull(sigSimilarity(a, null))
    }

    // ---------- Suivi image par image ----------

    @Test
    fun deuxPersonnesGardentLeurNumero() {
        val pt = PeopleTracker()
        var t = 0L
        var ids: Pair<Int, Int>? = null
        for (i in 0 until 30) {
            t += 50
            val a = detectionOf(person(0.3f + i * 0.005f, 0.2f, 0.6f))!!
            val b = detectionOf(person(0.75f - i * 0.004f, 0.25f, 0.5f))!!
            val tracks = pt.update(t, listOf(b, a))
            assertEquals(2, tracks.size)
            val left = tracks.minBy { it.aim.x }.id
            val right = tracks.maxBy { it.aim.x }.id
            if (ids == null) ids = left to right else assertEquals(ids, left to right)
        }
        // Une personne disparaît : on l'oublie après un moment
        for (i in 0 until 30) {
            t += 50
            pt.update(t, listOf(detectionOf(person(0.45f, 0.2f, 0.6f))!!))
        }
        assertEquals(1, pt.tracks.size)
        assertEquals(ids!!.first, pt.tracks[0].id)
    }

    @Test
    fun predictionAvecLaVitesse() {
        val pt = PeopleTracker()
        var t = 0L
        for (i in 0 until 20) {
            t += 50
            pt.update(t, listOf(detectionOf(person(0.2f + i * 0.01f, 0.2f, 0.6f))!!))
        }
        val tr = pt.tracks[0]
        assertTrue("vitesse ${tr.vx}", tr.vx > 0.12f)
        assertTrue(tr.predicted(t + 200).x > tr.aim.x)
    }

    // ---------- Reconnaissance par la montre ----------

    /** Agitation de la montre et vitesse du poignet du porteur, avec un retard de transmission. */
    private fun waving(now: Long, lagMs: Long, rnd: Random): Triple<Series, Series, Series> {
        val watch = Series(10_000)
        val you = Series()
        val other = Series()
        fun motion(t: Long) = (abs(sin(t / 400.0)) * 3.0 + (if ((t / 1300) % 2 == 0L) 2.0 else 0.0)).toFloat()
        var t = now - 6_000
        while (t <= now) {
            if (t % 100 == 0L) watch.add(t + lagMs, motion(t) + rnd.nextFloat() * 0.3f)
            if (t % 50 == 0L) {
                you.add(t, motion(t) * 0.8f + rnd.nextFloat() * 0.6f)
                other.add(t, (abs(sin(t / 170.0 + 1.0)) * 2.5).toFloat() + rnd.nextFloat() * 0.6f)
            }
            t += 10
        }
        return Triple(watch, you, other)
    }

    @Test
    fun laMontreReconnaitSonPorteur() {
        val now = 100_000L
        val (watch, you, other) = waving(now, 200, Random(4))
        val sYou = Correl.score(watch, you, now)!!
        val sOther = Correl.score(watch, other, now)!!
        assertTrue("porteur $sYou", sYou > 0.7f)
        assertTrue("autre $sOther", sOther < 0.4f)
        assertEquals(7, identify(mapOf(3 to sOther, 7 to sYou)))
        // Deux scores trop proches : on ne tranche pas
        assertNull(identify(mapOf(1 to 0.6f, 2 to 0.55f)))
        assertNull(identify(mapOf(1 to 0.3f)))
    }

    @Test
    fun montreImmobileNeDitRien() {
        val now = 50_000L
        val watch = Series(10_000)
        val wrist = Series()
        var t = now - 5_000
        while (t <= now) { watch.add(t, 0.05f); wrist.add(t, (t % 700) / 100f); t += 50 }
        assertNull(Correl.score(watch, wrist, now))
        assertTrue(!Correl.active(watch, now))
    }

    @Test
    fun choixInitial() {
        val pt = PeopleTracker()
        pt.update(0, listOf(
            detectionOf(person(0.15f, 0.3f, 0.4f))!!,
            detectionOf(person(0.55f, 0.15f, 0.75f))!!,
        ))
        assertEquals(0.55f, pickInitial(pt.tracks)!!.aim.x, 0.01f)
    }
}
