package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.BatteryLevel
import com.soniccontrol.core.devices.BatteryStatus
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.protocol.bytesOf
import com.soniccontrol.core.protocol.u16le
import com.soniccontrol.core.protocol.u8
import java.util.UUID

/**
 * Pure codecs for the three control protocols JBL earbuds are known to use. No Bluetooth here.
 * Byte layouts were written from the descriptions in docs/PROTOCOL.md; no third-party code was copied.
 */
object JblProtocol {

    /** "excelpoint.com" GATT service of JBL's Harman protocols (H-AA and Protocol 4). */
    val HARMAN_SERVICE: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0000")
    val HARMAN_NOTIFY: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0001")
    val HARMAN_WRITE: UUID = UUID.fromString("65786365-6c70-6f69-6e74-2e636f6d0002")
    /** Airoha vendor GATT service prefix ("PRIM...ohaBLE"), seen on JBL Tour Pro 3. */
    const val AIROHA_GATT_PREFIX = "5052494d-2dab-0341"
    /** Airoha RACE channel over RFCOMM (JBL Tour Pro 3). */
    val AIROHA_RACE_RFCOMM: UUID = UUID.fromString("00000000-0000-0000-0099-aabbccddeeff")

    /** Cuts both H-AA and Protocol 4 frames out of the Harman notification stream. */
    fun splitHarman(bytes: ByteArray): Pair<List<ByteArray>, Int> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            val rest = bytes.copyOfRange(i, bytes.size)
            val need = when {
                rest.u8(0) == P3.HEADER -> P3.expectedLength(rest)
                rest.size >= 2 && rest.u8(1) == 0xDD && rest.u8(0) <= 0x01 -> if (rest.size >= 8) 8 + rest.u16le(6) else null
                else -> { i++; continue } // not a frame start: resynchronise
            } ?: break
            if (rest.size < need) break
            out += rest.copyOfRange(0, need)
            i += need
        }
        return out to i
    }

    // ------------------------------------------------------------------
    // H-AA ("Protocol 3"): AA <cmd> <len> <payload>, no sequence, no checksum.
    // ------------------------------------------------------------------
    object P3 {
        const val HEADER = 0xAA
        const val CMD_VERSION = 0x21
        const val CMD_VERSION_REPLY = 0x22
        const val CMD_BATTERY = 0x25
        const val CMD_LISTENING_MODE = 0x91
        const val CMD_FACTORY_RESET = 0x95
        const val CMD_NOTIFY_CONTROL = 0x9B
        /** Observed reply `AA EE 01 <command>` to commands this model does not answer normally. Meaning not established. */
        const val CMD_ERROR_REPLY = 0xEE
        /**
         * Listening-mode report slot 4 (seen as `04 07`). TASK 014: a second, independent Tune Beam 2 capture shows it is the
         * ANC level (1..7, see [JblHarman.ancLevelInModeReport]). It never changes how the mode itself is decoded.
         */
        const val SLOT_UNKNOWN = 4
        const val OP_SET = 0x10
        const val OP_GET = 0x11
        const val OP_REPORT = 0x12

        /** Commands that carry a two-byte little-endian length. */
        private val LONG_LENGTH = setOf(0x06, 0x51, 0xA2, 0xA9)

        data class Frame(val command: Int, val payload: ByteArray) {
            override fun equals(other: Any?) = other is Frame && command == other.command && payload.contentEquals(other.payload)
            override fun hashCode() = 31 * command + payload.contentHashCode()
        }

        fun frame(command: Int, payload: ByteArray = ByteArray(0)): ByteArray {
            require(command in 0..0xFF) { "command out of range" }
            require(command !in LONG_LENGTH) { "long-length commands are not sent" }
            require(payload.size <= 0xFF) { "payload too long" }
            return byteArrayOf(HEADER.toByte(), command.toByte(), payload.size.toByte()) + payload
        }

        fun expectedLength(buf: ByteArray): Int? {
            if (buf.size < 3) return null
            return if (buf.u8(1) in LONG_LENGTH) {
                // Long frames are never above 255 bytes over this GATT link; a non-zero high byte means a short frame of the same
                // command (e.g. a query `AA A2 02 01 FF` echoed back), which would otherwise stall the stream waiting for 258 bytes.
                if (buf.size < 4) null else if (buf.u8(3) != 0) 3 + buf.u8(2) else 4 + buf.u16le(2)
            } else {
                3 + buf.u8(2)
            }
        }

        fun parse(bytes: ByteArray): Frame? {
            if (bytes.size < 3 || bytes.u8(0) != HEADER) return null
            val expected = expectedLength(bytes) ?: return null
            if (bytes.size != expected) return null
            val start = if (bytes.u8(1) in LONG_LENGTH && bytes.u8(3) == 0) 4 else 3
            return Frame(bytes.u8(1), bytes.copyOfRange(start, bytes.size))
        }

        /** Native device notification `AA 06 <len16 LE> <subtype16 LE> <body>`. Only the envelope is decoded; the body is never interpreted. */
        const val CMD_NATIVE = 0x06
        data class Native(val subtype: Int, val bodyLength: Int)

        fun parseNative(f: Frame): Native? =
            if (f.command == CMD_NATIVE && f.payload.size >= 2) Native(f.payload.u16le(0), f.payload.size - 2) else null

        /** Observed component ids inside native notifications (JBL Tune Beam 2, B484FB, fw 0.2.8.0): meaning inferred from one capture. */
        const val NATIVE_ID_LEFT = 0x1A
        const val NATIVE_ID_RIGHT = 0x1B
        const val NATIVE_ID_CASE = 0x1C

        data class NativeBattery(val left: Int?, val right: Int?, val case: Int?)

        /**
         * EXPERIMENTAL. Layout observed in a v0.6.7 capture: `AA 06 <len16 LE>` then payload
         * `02 | 00 1A 02 00 64 | 00 1B 02 00 5D | 00 1C 02 00 5B` = one leading byte (0x02, meaning unknown) and records of
         * `id u16 BE | length u8 = 2 | value u16 BE`; 0x1A/0x1B/0x1C = 100/93/91 %, the right and case values matching Fast Pair.
         * Strict: exact structure, known ids only, no duplicates, 0..100. Anything else (including record id 0x06 seen in
         * `AA 06 05 00 02 00 06 01 05`) returns null and is never interpreted.
         */
        fun parseNativeBattery(f: Frame): NativeBattery? {
            if (f.command != CMD_NATIVE || f.payload.size < 6 || f.payload.u8(0) != 0x02 || (f.payload.size - 1) % 5 != 0) return null
            val values = HashMap<Int, Int>()
            var i = 1
            while (i < f.payload.size) {
                val id = f.payload.u8(i) shl 8 or f.payload.u8(i + 1)
                val len = f.payload.u8(i + 2)
                val v = f.payload.u8(i + 3) shl 8 or f.payload.u8(i + 4)
                if (len != 2 || v !in 0..100 || id !in setOf(NATIVE_ID_LEFT, NATIVE_ID_RIGHT, NATIVE_ID_CASE) || values.put(id, v) != null) return null
                i += 5
            }
            return NativeBattery(values[NATIVE_ID_LEFT], values[NATIVE_ID_RIGHT], values[NATIVE_ID_CASE])
        }

        fun versionGet() = frame(CMD_VERSION, bytesOf(0x30))
        fun batteryGet() = frame(CMD_BATTERY, bytesOf(0x00))
        fun listeningModeGet() = frame(CMD_LISTENING_MODE, bytesOf(OP_GET))
        fun enableNotifications() = frame(CMD_NOTIFY_CONTROL, bytesOf(0x01, 0x01))

        /** Slot 1 = ANC, slot 2 = Ambient Aware, slot 3 = TalkThru. */
        fun listeningModeSet(mode: NoiseMode): ByteArray {
            val (anc, ambient, talk) = when (mode) {
                NoiseMode.OFF -> Triple(0, 0, 0)
                NoiseMode.ANC -> Triple(1, 0, 0)
                NoiseMode.AMBIENT -> Triple(0, 1, 0)
                NoiseMode.TALK_THRU -> Triple(0, 0, 1)
            }
            return frame(CMD_LISTENING_MODE, bytesOf(OP_SET, 1, anc, 2, ambient, 3, talk))
        }

        /**
         * Full validation of a received frame: exact framing and length, and a message whose layout we know.
         * Only validated frames may identify the protocol or confirm the control channel.
         */
        fun validate(bytes: ByteArray): Frame? {
            val f = parse(bytes) ?: return null
            val ok = when (f.command) {
                CMD_VERSION_REPLY -> f.payload.isNotEmpty()
                CMD_BATTERY -> isBatteryReport(f) && f.payload.size >= 7
                CMD_LISTENING_MODE -> (isListeningModeReport(f) && f.payload.size >= 7 && f.payload.size % 2 == 1 && listeningPairsValid(f)) ||
                    JblHarman.parseLevelReport(bytes) != null
                CMD_NOTIFY_CONTROL -> isNotifyAck(f)
                CMD_NATIVE -> f.payload.size >= 2          // envelope only; a body is interpreted solely by the strict decoders
                // TASK 014 (Tune Beam 2 family): structure-checked reports only.
                JblHarman.CMD_GESTURE -> JblHarman.parseGestureReport(bytes) != null || JblHarman.isGestureEcho(bytes)
                0x80 -> bytes.size == 12 && f.payload.u8(0) == 0x02
                0xA2 -> f.payload.size == 0x74 && f.payload.u8(0) == 0x04
                else -> false
            }
            return if (ok) f else null
        }

        /**
         * Pairs after the 0x12 marker: slots 1..3 (ANC, Ambient, TalkThru) carry 0/1; each of 1..3 must appear exactly once.
         * Slot 4 is accepted with any value and ignored. Any other slot id makes the frame invalid.
         */
        private fun listeningPairsValid(f: Frame): Boolean {
            val seen = HashSet<Int>()
            var i = 1
            while (i + 1 < f.payload.size) {
                val id = f.payload.u8(i); val v = f.payload.u8(i + 1)
                when (id) {
                    1, 2, 3 -> { if (v > 1 || !seen.add(id)) return false }
                    SLOT_UNKNOWN -> Unit
                    else -> return false
                }
                i += 2
            }
            return seen == setOf(1, 2, 3)
        }

        /** Slot 4 value of a valid report, kept for diagnostics only. */
        fun unknownSlotValue(f: Frame): Int? {
            var i = 1
            while (i + 1 < f.payload.size) { if (f.payload.u8(i) == SLOT_UNKNOWN) return f.payload.u8(i + 1); i += 2 }
            return null
        }

        /**
         * `AA EE 01 <cmd>`: returns the echoed command byte. Exact framing only. This is not an acknowledgement:
         * it neither confirms a command nor the control channel.
         */
        fun errorReplyFor(bytes: ByteArray): Int? =
            if (bytes.size == 4 && bytes.u8(0) == HEADER && bytes.u8(1) == CMD_ERROR_REPLY && bytes.u8(2) == 1) bytes.u8(3) else null

        fun isVersionReply(f: Frame) = f.command == CMD_VERSION_REPLY
        fun isBatteryReport(f: Frame) = f.command == CMD_BATTERY && f.payload.isNotEmpty() && f.payload.u8(0) == 0x01
        fun isListeningModeReport(f: Frame) = f.command == CMD_LISTENING_MODE && f.payload.isNotEmpty() && f.payload.u8(0) == OP_REPORT
        fun isNotifyAck(f: Frame) = f.command == CMD_NOTIFY_CONTROL && f.payload.isNotEmpty() && f.payload.u8(0) == 0x02

        /**
         * Battery report: payload[3] left, payload[4] right, payload[6] case (frame bytes 6, 7, 9).
         * 0x7F = no reading. The charging bit is not verified for this frame and is not reported.
         */
        fun parseBattery(f: Frame, now: Long): BatteryStatus? {
            if (!isBatteryReport(f) || f.payload.size < 7) return null
            fun level(i: Int): BatteryLevel {
                val v = f.payload.u8(i) and 0x7F
                return BatteryLevel(if (v == 0x7F || v > 100) null else v)
            }
            return BatteryStatus(left = level(3), right = level(4), case = level(6), source = DataSource.VENDOR_PROTOCOL, timestampMillis = now)
        }

        fun parseListeningMode(f: Frame): NoiseMode? {
            if (!isListeningModeReport(f)) return null
            val slots = HashMap<Int, Int>()
            var i = 1
            while (i + 1 < f.payload.size) {
                val id = f.payload.u8(i)
                if (id in 1..3 && slots.put(id, f.payload.u8(i + 1)) != null) return null // duplicate slot: contradictory
                i += 2
            }
            if (!slots.keys.containsAll(listOf(1, 2, 3))) return null // incomplete report: do not guess
            val on = listOf(slots[1] == 1, slots[2] == 1, slots[3] == 1)
            return when {
                on.count { it } > 1 -> null // contradictory: do not guess
                on[0] -> NoiseMode.ANC
                on[1] -> NoiseMode.AMBIENT
                on[2] -> NoiseMode.TALK_THRU
                else -> NoiseMode.OFF
            }
        }
    }

    // ------------------------------------------------------------------
    // Protocol 4 (2025+ models): 00 DD | cmd u16 LE | count | index | len u16 LE | payload (TLV)
    // Only GET_DEVICE_INFO (read) is ever built. SET is not implemented.
    // ------------------------------------------------------------------
    object P4 {
        const val IDENTIFIER = 0xDD00
        const val CMD_GET_DEVICE_INFO = 0x0001

        const val FID_PRODUCT_ID = 4
        const val FID_FIRMWARE = 12
        const val FID_BATTERY_LEFT = 13
        const val FID_BATTERY_RIGHT = 14
        const val FID_BATTERY_CASE = 7939

        /** Feature ids the app may ever request. All are documented as read-only status values. */
        val READABLE_IDS = setOf(FID_PRODUCT_ID, FID_FIRMWARE, FID_BATTERY_LEFT, FID_BATTERY_RIGHT, FID_BATTERY_CASE)

        data class Packet(val identifier: Int, val command: Int, val count: Int, val index: Int, val payload: ByteArray)

        fun looksLikeP4(bytes: ByteArray) = bytes.size >= 8 && bytes.u8(1) == 0xDD && bytes.u8(0) <= 0x01

        fun parse(bytes: ByteArray): Packet? {
            if (!looksLikeP4(bytes)) return null
            val len = bytes.u16le(6)
            if (bytes.size != 8 + len) return null
            return Packet(bytes.u16le(0), bytes.u16le(2), bytes.u8(4), bytes.u8(5), bytes.copyOfRange(8, bytes.size))
        }

        /** Header sanity: known identifier, 1..n packets, index inside count, exact length. */
        fun validate(bytes: ByteArray): Packet? {
            val p = parse(bytes) ?: return null
            if (p.identifier != IDENTIFIER && p.identifier != 0xDD01) return null
            if (p.count < 1 || p.index >= p.count) return null
            // A single-packet message must be a complete TLV list.
            if (p.count == 1 && parseTlvStrict(p.payload) == null) return null
            return p
        }

        /** TLV list that must consume the payload exactly; null otherwise. */
        fun parseTlvStrict(payload: ByteArray): Map<Int, ByteArray>? {
            var i = 0
            val out = LinkedHashMap<Int, ByteArray>()
            while (i + 4 <= payload.size) {
                val len = payload.u16le(i + 2)
                if (i + 4 + len > payload.size) return null
                out[payload.u16le(i)] = payload.copyOfRange(i + 4, i + 4 + len)
                i += 4 + len
            }
            return if (i == payload.size) out else null
        }

        fun getDeviceInfo(ids: List<Int>): ByteArray {
            require(ids.isNotEmpty() && ids.all { it in READABLE_IDS }) { "feature id not on the read allowlist" }
            val payload = ByteArray(ids.size * 2)
            ids.forEachIndexed { n, id -> payload[n * 2] = (id and 0xFF).toByte(); payload[n * 2 + 1] = (id shr 8).toByte() }
            val len = payload.size
            return bytesOf(
                IDENTIFIER and 0xFF, IDENTIFIER shr 8, CMD_GET_DEVICE_INFO and 0xFF, CMD_GET_DEVICE_INFO shr 8,
                1, 0, len and 0xFF, len shr 8,
            ) + payload
        }

        /** TLV: id u16 LE | len u16 LE | value. */
        fun parseTlv(payload: ByteArray): Map<Int, ByteArray> {
            val out = LinkedHashMap<Int, ByteArray>()
            var i = 0
            while (i + 4 <= payload.size) {
                val id = payload.u16le(i)
                val len = payload.u16le(i + 2)
                if (i + 4 + len > payload.size) break
                out[id] = payload.copyOfRange(i + 4, i + 4 + len)
                i += 4 + len
            }
            return out
        }

        /** Joins multi-packet messages; returns the full payload when the last packet arrives. */
        class Assembler {
            private var command = -1
            private var parts = ArrayList<ByteArray>()

            fun feed(p: Packet): ByteArray? {
                if (p.index == 0 || p.command != command) { command = p.command; parts = ArrayList() }
                parts += p.payload
                return if (p.index + 1 >= p.count) parts.fold(ByteArray(0)) { a, b -> a + b }.also { parts = ArrayList(); command = -1 } else null
            }
        }

        /** StatusLevelSize1 values: low 7 bits = level. The top bit's meaning is unverified, so it is ignored. */
        fun parseBattery(tlv: Map<Int, ByteArray>, now: Long): BatteryStatus? {
            fun level(id: Int): BatteryLevel? = tlv[id]?.takeIf { it.isNotEmpty() }?.let {
                val v = it.u8(0) and 0x7F
                BatteryLevel(if (v > 100) null else v)
            }
            val l = level(FID_BATTERY_LEFT); val r = level(FID_BATTERY_RIGHT); val c = level(FID_BATTERY_CASE)
            if (l == null && r == null && c == null) return null
            return BatteryStatus(left = l, right = r, case = c, source = DataSource.VENDOR_PROTOCOL, timestampMillis = now)
        }

        fun parseProductId(tlv: Map<Int, ByteArray>): Int? = tlv[FID_PRODUCT_ID]?.takeIf { it.size >= 2 }?.u16le(0)
        fun parseFirmware(tlv: Map<Int, ByteArray>): String? = tlv[FID_FIRMWARE]?.let { String(it, Charsets.US_ASCII).trim('\u0000', ' ') }
    }

    // ------------------------------------------------------------------
    // Airoha RACE (RFCOMM): 05 <type> <len u16 LE> <cmd u16 LE> <payload>, len = 2 + payload.
    // ------------------------------------------------------------------
    object Race {
        const val HEADER = 0x05
        const val TYPE_COMMAND = 0x5A
        const val TYPE_REPLY = 0x5B
        const val TYPE_EVENT = 0x5D
        const val CMD_MODE_GET = 0x0901
        const val CMD_MODE_SET = 0x0E06

        data class Frame(val type: Int, val command: Int, val payload: ByteArray) {
            override fun equals(other: Any?) = other is Frame && type == other.type && command == other.command && payload.contentEquals(other.payload)
            override fun hashCode() = (type * 31 + command) * 31 + payload.contentHashCode()
        }

        fun frame(type: Int, command: Int, payload: ByteArray): ByteArray {
            val len = 2 + payload.size
            return bytesOf(HEADER, type, len and 0xFF, len shr 8, command and 0xFF, command shr 8) + payload
        }

        fun parse(bytes: ByteArray): Frame? {
            if (bytes.size < 6 || bytes.u8(0) != HEADER) return null
            val len = bytes.u16le(2)
            if (len < 2 || bytes.size != 4 + len) return null
            return Frame(bytes.u8(1), bytes.u16le(4), bytes.copyOfRange(6, bytes.size))
        }

        fun split(bytes: ByteArray): Pair<List<ByteArray>, Int> {
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i + 4 <= bytes.size) {
                if (bytes.u8(i) != HEADER) { i++; continue }
                val len = bytes.u16le(i + 2)
                if (i + 4 + len > bytes.size) break
                out += bytes.copyOfRange(i, i + 4 + len)
                i += 4 + len
            }
            return out to i
        }

        /** A reply, notification or event (never an echo of our command type) with exact length. */
        fun validate(bytes: ByteArray): Frame? = parse(bytes)?.takeIf { it.type == TYPE_REPLY || it.type == 0x5C || it.type == TYPE_EVENT }

        fun modeGet() = frame(TYPE_COMMAND, CMD_MODE_GET, bytesOf(0x05, 0x00))

        fun modeSet(mode: NoiseMode): ByteArray = when (mode) {
            NoiseMode.OFF -> frame(TYPE_COMMAND, CMD_MODE_SET, bytesOf(0x00, 0x0B))
            NoiseMode.ANC -> frame(TYPE_COMMAND, CMD_MODE_SET, bytesOf(0x00, 0x0A, 0x01, 0x00))
            NoiseMode.AMBIENT -> frame(TYPE_COMMAND, CMD_MODE_SET, bytesOf(0x00, 0x0A, 0x0A, 0x04))
            NoiseMode.TALK_THRU -> frame(TYPE_COMMAND, CMD_MODE_SET, bytesOf(0x00, 0x0A, 0x09, 0x04))
        }

        fun isModeReport(f: Frame) =
            (f.type == TYPE_REPLY || f.type == TYPE_EVENT) && f.command == CMD_MODE_GET && f.payload.size >= 4 &&
                f.payload.u8(0) == 0x05 && f.payload.u8(1) == 0x00

        fun parseMode(f: Frame): NoiseMode? {
            if (!isModeReport(f)) return null
            return when (f.payload.u8(3)) {
                0x00 -> NoiseMode.OFF
                0x01 -> NoiseMode.ANC
                0x0A -> NoiseMode.AMBIENT
                0x09 -> NoiseMode.TALK_THRU
                else -> null
            }
        }
    }
}
