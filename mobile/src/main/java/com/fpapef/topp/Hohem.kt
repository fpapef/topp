package com.fpapef.topp

/**
 * Protocole Bluetooth de la nacelle Hohem iSteady V3, décodé à partir des journaux de Hohem Joy
 * (bibliothèque liblink, « blink V1.2.2.007 »).
 *
 * Trame : AA 55 | LEN | 00 | SRC | CMD | DONNÉES… | CHK | FF
 *  - LEN = nombre d'octets après AA 55 (FF compris) = taille(DONNÉES) + 6
 *  - CHK = somme(LEN … dernier octet de données) & 0xFF
 */
object Hohem {
    const val CMD_STATUS = 0x49
    const val CMD_TRACK = 0x60
    const val CMD_MODE = 0x7C
    const val CMD_INFO = 0x57
    const val CMD_ATTITUDE = 0x84
    const val CMD_SHUTTER = 0xA5
    const val CMD_REMOTE_JOYSTICK = 0xB0
    const val CMD_RECENTER_EVENT = 0xB1
    const val CMD_RECENTERED = 0x98

    /** Ordre dans lequel le bouton Mode de la nacelle fait défiler les modes (observé). */
    val MODE_CYCLE = listOf(1, 3, 2, 0)

    fun frame(cmd: Int, data: ByteArray = ByteArray(0), src: Int = 0): ByteArray {
        val body = ByteArray(4 + data.size)
        body[0] = (data.size + 6).toByte()
        body[1] = 0
        body[2] = src.toByte()
        body[3] = cmd.toByte()
        data.copyInto(body, 4)
        val chk = body.sumOf { it.toInt() and 0xFF } and 0xFF
        return byteArrayOf(0xAA.toByte(), 0x55) + body + byteArrayOf(chk.toByte(), 0xFF.toByte())
    }

    fun query(cmd: Int) = frame(cmd)

    fun setMode(mode: Int) = frame(CMD_MODE, byteArrayOf(0, 0, mode.toByte(), 1))

    /**
     * Commande de suivi (0x60) : position du « cadre » suivi dans l'image.
     * Mot de 40 bits big-endian : pan(10, signé) | tilt(10, signé) | 0000 | h(8) | v(8), puis 4 octets à zéro.
     * [officialQuirk] reproduit un défaut de l'encodeur officiel (tilt masqué sur 12 bits),
     * uniquement pour vérifier les trames capturées dans les tests.
     */
    fun track(pan: Int, tilt: Int, h: Int, v: Int, officialQuirk: Boolean = false): ByteArray {
        val tiltMask = if (officialQuirk) 0xFFFL else 0x3FFL
        val word = ((pan.toLong() and 0x3FF) shl 30) or
            ((tilt.toLong() and tiltMask) shl 20) or
            ((h.toLong() and 0xFF) shl 8) or
            (v.toLong() and 0xFF)
        val data = ByteArray(12)
        data[0] = 1; data[1] = 2; data[2] = 3
        for (i in 0 until 5) data[3 + i] = ((word shr (8 * (4 - i))) and 0xFF).toByte()
        return frame(CMD_TRACK, data)
    }

    /**
     * Cadre virtuel décalé du centre de l'image : x > 0 = sujet à droite, y > 0 = sujet en bas.
     * Mêmes échelles que Hohem Joy : pan = -x·510, tilt = y·510, taille ·255.
     */
    fun trackOffset(x: Float, y: Float, w: Float = 0.17f, h: Float = 0.137f): ByteArray {
        val pan = (-x * 510f).toInt().coerceIn(-511, 511)
        val tilt = (y * 510f).toInt().coerceIn(-511, 511)
        val hh = (w * 255f).toInt().coerceIn(1, 255)
        val vv = (h * 255f).toInt().coerceIn(1, 255)
        return track(pan, tilt, hh, vv)
    }

    /** Fin du suivi (état 0) : tout à zéro. */
    fun trackClear() = frame(CMD_TRACK, byteArrayOf(1, 2, 3) + ByteArray(9))

    /** Séquence envoyée par Hohem Joy juste après la connexion. */
    val initSequence: List<ByteArray> = listOf(
        query(CMD_STATUS),
        frame(0x34, byteArrayOf(0)),
        query(0x35),
        query(CMD_ATTITUDE),
        query(CMD_INFO),
        query(0x53),
        query(0x54),
        query(0x56),
        frame(0x9A, byteArrayOf(0x01) + ByteArray(11)),
        frame(0x9A, byteArrayOf(0x09) + ByteArray(11)),
        frame(0x9A, byteArrayOf(0x0A, 5, 2, 2, 2) + ByteArray(7)),
        frame(0xC1, byteArrayOf(3) + ByteArray(7)),
    )

    class Frame(val src: Int, val cmd: Int, val data: ByteArray) {
        override fun toString() = "src=%02X cmd=%02X data=%s".format(src, cmd, data.toHex())
    }

    /** Reconstitue les trames à partir des notifications (qui peuvent être coupées ou collées). */
    class Parser {
        private var buf = ByteArray(0)

        fun feed(bytes: ByteArray): List<Frame> {
            buf += bytes
            val out = ArrayList<Frame>()
            var i = 0
            while (true) {
                while (i + 1 < buf.size && !(buf[i] == 0xAA.toByte() && buf[i + 1] == 0x55.toByte())) i++
                if (i + 2 >= buf.size) break
                val len = buf[i + 2].toInt() and 0xFF
                if (len < 6) { i++; continue }
                val total = len + 2
                if (i + total > buf.size) break
                val f = buf.copyOfRange(i, i + total)
                val chk = f.copyOfRange(2, total - 2).sumOf { it.toInt() and 0xFF } and 0xFF
                if (f[total - 1] == 0xFF.toByte() && (f[total - 2].toInt() and 0xFF) == chk) {
                    out += Frame(f[4].toInt() and 0xFF, f[5].toInt() and 0xFF, f.copyOfRange(6, total - 2))
                    i += total
                } else {
                    i++
                }
            }
            buf = if (i >= buf.size) ByteArray(0) else buf.copyOfRange(i, buf.size)
            if (buf.size > 4096) buf = ByteArray(0)
            return out
        }
    }

    /** Angles en degrés (0,1° de résolution). Codeurs = angle des moteurs par rapport à la poignée. */
    data class Attitude(
        val gyroTilt: Float, val gyroRoll: Float, val gyroPan: Float,
        val encTilt: Float, val encRoll: Float, val encPan: Float,
    )

    fun decodeAttitude(d: ByteArray): Attitude? {
        if (d.size < 12) return null
        fun s16(i: Int) = ((d[i].toInt() shl 8) or (d[i + 1].toInt() and 0xFF)).toShort() / 10f
        return Attitude(s16(0), s16(2), s16(4), s16(6), s16(8), s16(10))
    }

    /** Mode de travail tel que rapporté par la trame 0x57. */
    fun decodeMode(d: ByteArray): Int? = if (d.size >= 15) d[14].toInt() and 0xFF else null

    fun nextMode(current: Int?): Int {
        val idx = MODE_CYCLE.indexOf(current ?: -1)
        return MODE_CYCLE[(idx + 1).mod(MODE_CYCLE.size)]
    }

    /** Numéro affiché (1 à 4) dans l'ordre du bouton Mode de la nacelle. */
    fun modeLabel(mode: Int?): String {
        val idx = MODE_CYCLE.indexOf(mode ?: -1)
        return if (idx < 0) "?" else "${idx + 1}"
    }

    fun wrap180(deg: Float): Float {
        var a = deg % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        return a
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
