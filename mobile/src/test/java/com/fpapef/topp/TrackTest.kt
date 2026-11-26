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
        // Repères du cadrage : haut du crâne au-dessus du nez, hanches et pieds vus
        val b = d.body!!
        assertTrue(b.headTop < 0.2f + 0.6f * 0.08f)
        assertEquals(0.2f + 0.6f * 0.55f, b.hipY!!, 0.001f)
        assertEquals(0.8f, b.footY!!, 0.001f)
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

    // ---------- Objectifs ----------

    @Test
    fun libellesDesObjectifs() {
        assertEquals("1x", lensLabel(1f))
        assertEquals("3x", lensLabel(3f))
        assertEquals("0,6x", lensLabel(0.6f))
        assertEquals("100x", lensLabel(100f))
    }

    // ---------- Cadrage automatique ----------

    private val stops = listOf(1f, 3f, 5f)
    /** Corps vu à 1x : tête en haut à 0,30, épaules 0,38, hanches 0,55, pieds 0,85. */
    private val body = Body(headTop = 0.30f, shoulderY = 0.38f, unit = 0.05f, hipY = 0.55f, footY = 0.85f)

    /** Fait tourner le cadrage [ms] millisecondes à 30 Hz ; renvoie tous les zooms commandés. */
    private fun run(f: Framer, t0: Long, ms: Long, b: Body, framing: Framing, wx: Float = 0f, speed: Float = 0f): List<Float> {
        val out = ArrayList<Float>()
        var t = t0
        while (t <= t0 + ms) {
            val (span, mid) = f.measure(b, framing, f.zoom)
            out += f.update(t, span, mid, framing, wx, 0f, speed, 10f, stops)
            t += 33
        }
        return out
    }

    @Test
    fun cadrageMesureLeBonMorceauDuCorps() {
        val f = Framer()
        val (half, _) = f.measure(body, Framing.HALF, 1f)
        val (full, _) = f.measure(body, Framing.FULL, 1f)
        val (close, _) = f.measure(body, Framing.CLOSE, 1f)
        assertTrue(close < half && half < full)
        assertEquals(0.55f - (0.30f - 0.0075f), half, 0.001f)
        // Zoomé à 2x, les hanches (0,55 → 0,60 à l'écran) restent visibles ; à 4x elles sortent (0,70… non)
        // Pieds hors champ à 2x (0,85 → 1,2) : on estime depuis la tête
        val (fullZoomed, _) = f.measure(body, Framing.FULL, 2f)
        assertEquals(0.3f + 9.3f * 0.05f - (0.3f - 0.0075f), fullZoomed, 0.001f)
    }

    @Test
    fun cadrageZoomeEnDouceur() {
        val f = Framer()
        // Tête et buste : 0,8 / 0,2575 ≈ 3,1x voulu → reste juste sous 3x (pas de bascule d'objectif inutile)
        val zs = run(f, 10_000, 12_000, body, Framing.HALF)
        for (i in 1 until zs.size) {
            val ratio = zs[i] / zs[i - 1]
            assertTrue("saut de ${(ratio - 1) * 100} %", ratio in 0.98f..1.02f)
        }
        assertTrue("zoom final ${zs.last()}", zs.last() in 2.85f..2.999f)
        // Visage et épaules : plus serré, au-delà de 3x
        val f2 = Framer()
        assertTrue(run(f2, 0, 15_000, body, Framing.CLOSE).last() > 3.5f)
    }

    @Test
    fun cadrageNeFaitPasDeVaEtVientAutourDe3x() {
        val f = Framer()
        run(f, 0, 12_000, body, Framing.HALF) // juste sous 3x
        // Le sujet bouge un peu d'avant en arrière : le zoom voulu oscille autour de 3x
        var crossed = 0
        var below = f.zoom < 3f
        for (k in 0 until 20) {
            val b = if (k % 2 == 0) body.copy(hipY = 0.555f) else body.copy(hipY = 0.545f)
            for (z in run(f, 20_000L + k * 1_000L, 900, b, Framing.HALF)) {
                if ((z < 3f) != below) { crossed++; below = z < 3f }
            }
        }
        assertEquals(0, crossed)
    }

    @Test
    fun cadrageDesserreSiTuSorsDuCentre() {
        val centered = run(Framer(), 0, 10_000, body, Framing.HALF).last()
        val off = run(Framer(), 0, 10_000, body, Framing.HALF, wx = 0.35f).last()
        assertTrue("centré $centered, décentré $off", off < centered * 0.85f)
        // Perdu : on élargit vers 1x
        val f = Framer()
        run(f, 0, 10_000, body, Framing.HALF)
        var z = f.zoom
        var t = 20_000L
        repeat(150) { t += 33; z = f.widen(t, stops) }
        assertTrue("élargi à $z", z < 1.2f)
    }

    // ---------- Grand-angle bloqué ----------

    private val uw = LensInfo("20", 13f)
    private val wide = LensInfo("21", 24f)
    private val t3 = LensInfo("22", 69f)
    private val t5 = LensInfo("23", 111f)

    @Test
    fun choixDeCameraSansGrandAngle() {
        val combined = CamOption("0", listOf(uw, wide, t3, t5), logical = true)
        val uwAlone = CamOption("2", listOf(LensInfo("2", 13f)), logical = false)
        // Une caméra sans 0,6x mais avec téléobjectifs : on la prend
        val clean = CamOption("4", listOf(wide, t3, t5), logical = true)
        assertEquals(CamPlan("4", null, "caméra 4 sans grand-angle"), chooseCamera(listOf(combined, uwAlone, clean), "0", true))
        // Seulement la caméra combinée : objectif principal seul
        assertEquals("21", chooseCamera(listOf(combined, uwAlone), "0", true).physicalId)
        // Blocage coupé : caméra par défaut, telle quelle
        assertEquals(CamPlan("0", null, ""), chooseCamera(listOf(combined, uwAlone), "0", false))
        // Caméra par défaut déjà sans 0,6x : rien à faire
        assertEquals(CamPlan("0", null, ""), chooseCamera(listOf(CamOption("0", listOf(wide), false)), "0", true))
        assertEquals("0 = 0,5x + 1x + 3x + 5x ; 2 = 0,5x", describeCameras(listOf(combined, uwAlone)))
    }

    @Test
    fun changementDObjectifGardeLaPersonne() {
        val pt = PeopleTracker()
        var t = 0L
        for (i in 0 until 10) { t += 50; pt.update(t, listOf(detectionOf(person(0.55f, 0.4f, 0.2f))!!)) }
        val id = pt.tracks[0].id
        // Passage à 3x : la personne grossit trois fois autour du centre ; ramenée en repère 1x,
        // c'est toujours la même
        val zoomed = detectionOf(person(0.5f + 0.05f * 3, 0.5f + (0.4f - 0.5f) * 3, 0.2f * 3))!!
        t += 50
        val tracks = pt.update(t, listOf(zoomed.scaled(1f / 3f)))
        assertEquals(1, tracks.size)
        assertEquals(id, tracks[0].id)
    }
}
