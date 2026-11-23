package com.fpapef.topp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HohemTest {
    private fun hex(s: String) = s.replace(" ", "")
    private fun ByteArray.compact() = toHex().replace(" ", "")
    private fun resource(name: String) =
        javaClass.classLoader!!.getResource(name)!!.readText().lines().filter { it.isNotBlank() }

    @Test
    fun queriesMatchCapture() {
        assertEquals(hex("AA 55 06 00 00 49 4F FF"), Hohem.query(0x49).compact())
        assertEquals(hex("AA 55 07 00 00 34 00 3B FF"), Hohem.frame(0x34, byteArrayOf(0)).compact())
        assertEquals(hex("AA 55 06 00 00 84 8A FF"), Hohem.query(0x84).compact())
        assertEquals(
            hex("AA 55 12 00 00 9A 0A 05 02 02 02 00 00 00 00 00 00 00 C1 FF"),
            Hohem.initSequence[10].compact()
        )
        assertEquals(hex("AA 55 0E 00 00 C1 03 00 00 00 00 00 00 00 D2 FF"), Hohem.initSequence[11].compact())
    }

    @Test
    fun modeCommandsMatchCapture() {
        assertEquals(hex("AA 55 0A 00 00 7C 00 00 01 01 88 FF"), Hohem.setMode(1).compact())
        assertEquals(hex("AA 55 0A 00 00 7C 00 00 03 01 8A FF"), Hohem.setMode(3).compact())
        assertEquals(hex("AA 55 0A 00 00 7C 00 00 00 01 87 FF"), Hohem.setMode(0).compact())
    }

    @Test
    fun allCapturedTrackFramesReproduced() {
        val rows = resource("track_vectors.csv")
        assertTrue(rows.size > 200)
        for (row in rows) {
            val p = row.split(",")
            val frame = Hohem.track(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), officialQuirk = true)
            assertEquals(row, p[4], frame.compact())
        }
    }

    @Test
    fun cleanEncodingWhenTiltPositive() {
        assertEquals(
            hex("AA 55 12 00 00 60 01 02 03 D4 C1 10 24 12 00 00 00 00 53 FF"),
            Hohem.track(-173, 17, 36, 18).compact()
        )
        assertEquals(hex("AA 55 12 00 00 60 01 02 03 00 00 00 00 00 00 00 00 00 78 FF"), Hohem.trackClear().compact())
        // Même cadre que Hohem Joy : x=0,33981478 y=0,034374952 w=0,14351854 h=0,071875006
        assertEquals(
            hex("AA 55 12 00 00 60 01 02 03 D4 C1 10 24 12 00 00 00 00 53 FF"),
            Hohem.trackOffset(0.33981478f, 0.034374952f, 0.14351854f, 0.071875006f).compact()
        )
    }

    @Test
    fun negativeTiltDecodesBack() {
        val f = Hohem.track(-171, -12, 33, 16)
        val word = f.copyOfRange(9, 14).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        fun s10(v: Long) = (if (v >= 512) v - 1024 else v).toInt()
        assertEquals(-171, s10((word shr 30) and 0x3FF))
        assertEquals(-12, s10((word shr 20) and 0x3FF))
    }

    @Test
    fun parserReadsAllCapturedFrames() {
        val rows = resource("rx_frames.txt")
        val stream = rows.map { r -> r.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
            .fold(ByteArray(0)) { a, b -> a + byteArrayOf(0x12, 0x34) + b }
        val parser = Hohem.Parser()
        val frames = stream.toList().chunked(7).flatMap { parser.feed(it.toByteArray()) }
        assertEquals(rows.size, frames.size)
    }

    @Test
    fun attitudeAndModeDecode() {
        // Trame réelle reçue à 15:03:08 (1re capture)
        val bytes = "AA55220000" + "84FF010000FAC903C90CED060B0EFC0800011F27030300008000000000" + "1EFF"
        val raw = bytes.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val f = Hohem.Parser().feed(raw).single()
        val a = Hohem.decodeAttitude(f.data)
        assertNotNull(a)
        assertEquals(96.9f, a!!.encTilt, 0.01f)
        assertEquals(330.9f, a.encRoll, 0.01f)
        assertEquals(154.7f, a.encPan, 0.01f)

        val m = Hohem.Parser().feed(
            "AA55160000570005000300020100040302010000030186FF".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        ).single()
        assertEquals(3, Hohem.decodeMode(m.data))
    }

    @Test
    fun modeCycleAndWrap() {
        assertEquals(3, Hohem.nextMode(1))
        assertEquals(1, Hohem.nextMode(0))
        assertEquals(1, Hohem.nextMode(null))
        assertEquals(-0.2f, Hohem.wrap180(179.8f - 180f), 0.001f)
        assertEquals(0.1f, Hohem.wrap180(-179.9f - 180f), 0.001f)
    }
}
