package com.soniccontrol.drivers.sony

import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.protocol.bytesOf
import com.soniccontrol.core.protocol.u8
import java.util.UUID

/**
 * Sony "MDR" protocol over RFCOMM, written from the frame description in ncr/omarchy-headphones
 * PROTOCOL.md (MIT). Frame: 3E | type | seq | len u32 BE | payload | checksum | 3C, with 3C/3D/3E escaped.
 */
object SonyMdr {
    val V2_UUID: UUID = UUID.fromString("956c7b26-d49a-4ba8-b03f-b17d393cb6e2")
    val V1_UUID: UUID = UUID.fromString("96cc203e-5068-46ad-b32d-e316f5e069ba")

    const val START = 0x3E
    const val END = 0x3C
    const val ESC = 0x3D
    const val TYPE_ACK = 0x01
    const val TYPE_COMMAND = 0x0C
    const val TYPE_COMMAND_2 = 0x0E

    const val NC_GET = 0x66
    const val NC_RET = 0x67
    const val NC_SET = 0x68
    const val NC_NTFY = 0x69
    const val BLOCK_V2 = 0x17
    const val BLOCK_V1 = 0x02

    data class Frame(val type: Int, val seq: Int, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Frame && type == other.type && seq == other.seq && payload.contentEquals(other.payload)
        override fun hashCode() = (type * 31 + seq) * 31 + payload.contentHashCode()
    }

    fun checksum(type: Int, seq: Int, payload: ByteArray): Int {
        var s = type + seq
        val n = payload.size
        s += (n ushr 24) and 0xFF; s += (n ushr 16) and 0xFF; s += (n ushr 8) and 0xFF; s += n and 0xFF
        payload.forEach { s += it.toInt() and 0xFF }
        return s and 0xFF
    }

    fun encode(type: Int, seq: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val body = bytesOf(type, seq, (n ushr 24) and 0xFF, (n ushr 16) and 0xFF, (n ushr 8) and 0xFF, n and 0xFF) +
            payload + bytesOf(checksum(type, seq, payload))
        val out = ArrayList<Byte>(body.size + 4)
        out += START.toByte()
        for (b in body) {
            val v = b.toInt() and 0xFF
            if (v == START || v == END || v == ESC) { out += ESC.toByte(); out += (v and 0xEF).toByte() } else out += b
        }
        out += END.toByte()
        return out.toByteArray()
    }

    /** Decodes one complete frame (including 3E ... 3C). Null if malformed or the checksum is wrong. */
    fun decode(raw: ByteArray): Frame? {
        if (raw.size < 9 || raw.u8(0) != START || raw.u8(raw.size - 1) != END) return null
        val body = ArrayList<Int>(raw.size)
        var i = 1
        while (i < raw.size - 1) {
            val v = raw.u8(i)
            if (v == ESC) {
                if (i + 1 >= raw.size - 1) return null
                body += raw.u8(i + 1) or 0x10
                i += 2
            } else { body += v; i++ }
        }
        if (body.size < 7) return null
        val len = (body[2] shl 24) or (body[3] shl 16) or (body[4] shl 8) or body[5]
        if (len < 0 || body.size != 7 + len) return null
        val payload = ByteArray(len) { body[6 + it].toByte() }
        if (checksum(body[0], body[1], payload) != body[6 + len]) return null
        return Frame(body[0], body[1], payload)
    }

    /** Cuts raw 3E…3C frames from a byte stream. */
    fun split(bytes: ByteArray): Pair<List<ByteArray>, Int> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var consumed = 0
        while (i < bytes.size) {
            if (bytes.u8(i) != START) { i++; consumed = i; continue }
            val end = (i + 1 until bytes.size).firstOrNull { bytes.u8(it) == END } ?: break
            out += bytes.copyOfRange(i, end + 1)
            i = end + 1
            consumed = i
        }
        return out to consumed
    }

    fun ack(receivedSeq: Int) = encode(TYPE_ACK, 1 - receivedSeq, ByteArray(0))

    // ---- payloads (unframed) ----
    val HANDSHAKE: ByteArray = bytesOf(0x00, 0x00)
    fun ncGetV2() = bytesOf(NC_GET, BLOCK_V2)
    fun ncGetV1() = bytesOf(NC_GET, BLOCK_V1)

    /** v2 type 0x17: cmd | 17 | changed(01) | effect | mode(00 NC, 01 ambient) | ambientMode(00 normal) | level. */
    fun ncSetV2(mode: NoiseMode, ambientLevel: Int): ByteArray {
        val level = ambientLevel.coerceIn(0, 20)
        return when (mode) {
            NoiseMode.OFF -> bytesOf(NC_SET, BLOCK_V2, 0x01, 0x00, 0x00, 0x00, level)
            NoiseMode.ANC -> bytesOf(NC_SET, BLOCK_V2, 0x01, 0x01, 0x00, 0x00, level)
            NoiseMode.AMBIENT -> bytesOf(NC_SET, BLOCK_V2, 0x01, 0x01, 0x01, 0x00, level)
            NoiseMode.TALK_THRU -> throw IllegalArgumentException("Sony has no TalkThru mode")
        }
    }

    /** v1 (WH-1000XM4), the three frames observed on hardware. */
    fun ncSetV1(mode: NoiseMode): ByteArray = when (mode) {
        NoiseMode.OFF -> bytesOf(NC_SET, BLOCK_V1, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
        NoiseMode.ANC -> bytesOf(NC_SET, BLOCK_V1, 0x11, 0x01, 0x02, 0x01, 0x00, 0x00)
        NoiseMode.AMBIENT -> bytesOf(NC_SET, BLOCK_V1, 0x11, 0x01, 0x00, 0x01, 0x00, 0x14)
        NoiseMode.TALK_THRU -> throw IllegalArgumentException("Sony has no TalkThru mode")
    }

    data class NcState(val mode: NoiseMode, val ambientLevel: Int?)

    /** Parses RET/NTFY of block 0x17 (v2) or 0x02 (v1). */
    fun parseNc(payload: ByteArray): NcState? {
        if (payload.size < 2) return null
        val cmd = payload.u8(0)
        if (cmd != NC_RET && cmd != NC_NTFY) return null
        return when (payload.u8(1)) {
            BLOCK_V2 -> {
                if (payload.size < 7) return null
                val effect = payload.u8(3); val m = payload.u8(4); val level = payload.u8(6)
                val mode = when { effect == 0 -> NoiseMode.OFF; m == 0 -> NoiseMode.ANC; else -> NoiseMode.AMBIENT }
                NcState(mode, level)
            }
            BLOCK_V1 -> {
                if (payload.size < 8) return null
                val effect = payload.u8(2); val ncValue = payload.u8(4)
                val mode = when {
                    effect == 0 -> NoiseMode.OFF
                    ncValue == 0x02 -> NoiseMode.ANC
                    ncValue == 0x00 -> NoiseMode.AMBIENT
                    else -> return null // 0x01 = wind reduction, not mapped
                }
                NcState(mode, payload.u8(7))
            }
            else -> null
        }
    }

    /** Payloads the driver may send. Anything else is refused. */
    fun isAllowedPayload(p: ByteArray): Boolean {
        if (p.contentEquals(HANDSHAKE) || p.contentEquals(ncGetV2()) || p.contentEquals(ncGetV1())) return true
        if (NoiseMode.entries.filter { it != NoiseMode.TALK_THRU }.any { ncSetV1(it).contentEquals(p) }) return true
        if (p.size == 7 && p.u8(0) == NC_SET && p.u8(1) == BLOCK_V2 && p.u8(2) == 0x01 && p.u8(3) <= 1 && p.u8(4) <= 1 &&
            p.u8(5) == 0 && p.u8(6) <= 20
        ) return true
        return false
    }
}
