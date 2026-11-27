package com.soniccontrol.core.diagnostics

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Reader for Android's Bluetooth HCI snoop log (btsnoop format, TASK 014 Lab import).
 *
 * Only GATT values are taken out: ATT Write Request/Command (phone -> headset) and Handle Value Notification/Indication
 * (headset -> phone) on the LE/ATT channel (L2CAP CID 0x0004). Device addresses, HCI events, audio and RFCOMM are skipped and
 * never kept. The file is the owner's own, shared on purpose; the app only reads it when the owner picks it.
 */
object HciSnoop {
    enum class Direction { PHONE_TO_HEADSET, HEADSET_TO_PHONE }

    /** One ATT value. [millis] is relative to the first record of the file. */
    data class AttValue(val millis: Long, val direction: Direction, val opcode: Int, val handle: Int, val value: ByteArray) {
        override fun equals(other: Any?) = other is AttValue && millis == other.millis && direction == other.direction &&
            opcode == other.opcode && handle == other.handle && value.contentEquals(other.value)
        override fun hashCode() = value.contentHashCode() * 31 + handle
    }

    data class Result(val records: Int, val values: List<AttValue>, val truncated: Boolean, val error: String? = null)

    private const val DATALINK_H1 = 1001
    private const val DATALINK_H4 = 1002
    private const val ATT_CID = 0x0004
    private val ATT_OPCODES = setOf(0x12, 0x52, 0x1B, 0x1D)
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_VALUES = 20_000

    fun parse(input: InputStream, maxBytes: Long = MAX_BYTES): Result {
        val data = DataInputStream(input.buffered())
        val magic = ByteArray(8)
        try { data.readFully(magic) } catch (_: EOFException) { return Result(0, emptyList(), false, "not a btsnoop file") }
        if (!magic.contentEquals("btsnoop\u0000".toByteArray(Charsets.US_ASCII))) return Result(0, emptyList(), false, "not a btsnoop file")
        val version = data.readInt()
        val datalink = data.readInt()
        if (version != 1 || (datalink != DATALINK_H1 && datalink != DATALINK_H4)) return Result(0, emptyList(), false, "unsupported btsnoop variant ($version/$datalink)")
        var read = 16L
        var records = 0
        var first: Long? = null
        val out = ArrayList<AttValue>()
        val reassembly = HashMap<Int, Pair<Int, java.io.ByteArrayOutputStream>>()   // handle -> (expected L2CAP length, bytes)
        var truncated = false
        while (true) {
            val origLen: Int; val inclLen: Int; val flags: Int; val ts: Long
            try {
                origLen = data.readInt(); inclLen = data.readInt(); flags = data.readInt(); data.readInt(); ts = data.readLong()
            } catch (_: EOFException) { break }
            if (inclLen < 0 || inclLen > 65_535 + 16) return Result(records, out, true, "corrupt record length")
            read += 24 + inclLen
            if (read > maxBytes) { truncated = true; break }
            val packet = ByteArray(inclLen)
            try { data.readFully(packet) } catch (_: EOFException) { truncated = true; break }
            records++
            if (inclLen < origLen) continue                                // cut by the logger: not trusted
            val sent = flags and 0x01 == 0
            val isData = flags and 0x02 == 0
            val acl: ByteArray = when (datalink) {
                DATALINK_H4 -> if (packet.isNotEmpty() && packet[0].toInt() == 0x02) packet.copyOfRange(1, packet.size) else continue
                else -> if (isData) packet else continue
            }
            if (acl.size < 4) continue
            val handle = (acl[0].toInt() and 0xFF) or ((acl[1].toInt() and 0x0F) shl 8)
            val pb = (acl[1].toInt() shr 4) and 0x03
            val aclLen = (acl[2].toInt() and 0xFF) or ((acl[3].toInt() and 0xFF) shl 8)
            if (acl.size < 4 + aclLen) continue
            val payload = acl.copyOfRange(4, 4 + aclLen)
            val sdu: ByteArray? = if (pb == 0x01) {
                val (expected, buf) = reassembly[handle] ?: continue
                buf.write(payload)
                if (buf.size() >= expected + 4) { reassembly.remove(handle); buf.toByteArray() } else null
            } else {
                if (payload.size < 4) continue
                val l2Len = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                if (payload.size >= l2Len + 4) payload else { reassembly[handle] = l2Len to java.io.ByteArrayOutputStream().apply { write(payload) }; null }
            }
            if (sdu == null || sdu.size < 4) continue
            val cid = (sdu[2].toInt() and 0xFF) or ((sdu[3].toInt() and 0xFF) shl 8)
            if (cid != ATT_CID || sdu.size < 7) continue
            val opcode = sdu[4].toInt() and 0xFF
            if (opcode !in ATT_OPCODES) continue
            val attHandle = (sdu[5].toInt() and 0xFF) or ((sdu[6].toInt() and 0xFF) shl 8)
            val l2Len = (sdu[0].toInt() and 0xFF) or ((sdu[1].toInt() and 0xFF) shl 8)
            val end = minOf(sdu.size, 4 + l2Len)
            if (end < 7) continue
            val f = first ?: ts.also { first = it }
            out += AttValue((ts - f) / 1000, if (sent) Direction.PHONE_TO_HEADSET else Direction.HEADSET_TO_PHONE, opcode, attHandle, sdu.copyOfRange(7, end))
            if (out.size >= MAX_VALUES) { truncated = true; break }
        }
        return Result(records, out, truncated)
    }
}
