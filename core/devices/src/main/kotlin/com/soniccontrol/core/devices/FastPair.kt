package com.soniccontrol.core.devices

/**
 * Google Fast Pair Message Stream (RFCOMM): group u8 | code u8 | length u16 BE | payload.
 * The headset pushes these on connect and when values change. Sonic Control only listens.
 */
object FastPair {
    const val GROUP_DEVICE_INFO = 0x03
    const val CODE_MODEL_ID = 0x01
    const val CODE_BATTERY = 0x03
    const val CODE_FIRMWARE = 0x09

    data class Message(val group: Int, val code: Int, val payload: ByteArray) {
        override fun equals(other: Any?) =
            other is Message && group == other.group && code == other.code && payload.contentEquals(other.payload)
        override fun hashCode() = (group * 31 + code) * 31 + payload.contentHashCode()
    }

    /** Splits a byte stream into complete messages; returns them as raw frames and the bytes consumed. */
    fun split(bytes: ByteArray): Pair<List<ByteArray>, Int> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 4 <= bytes.size) {
            val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (i + 4 + len > bytes.size) break
            out += bytes.copyOfRange(i, i + 4 + len)
            i += 4 + len
        }
        return out to i
    }

    fun parse(frame: ByteArray): Message? {
        if (frame.size < 4) return null
        val len = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        if (frame.size != 4 + len) return null
        return Message(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF, frame.copyOfRange(4, frame.size))
    }

    /** One byte per component: left, right, case (or one byte for a single battery). Bit 7 = charging; 0x7F = unknown. */
    fun parseBattery(m: Message, now: Long): BatteryStatus? {
        if (m.group != GROUP_DEVICE_INFO || m.code != CODE_BATTERY || m.payload.isEmpty()) return null
        fun level(i: Int): BatteryLevel? {
            if (i >= m.payload.size) return null
            val raw = m.payload[i].toInt() and 0xFF
            val v = raw and 0x7F
            return if (v == 0x7F || v > 100) BatteryLevel(null) else BatteryLevel(v, charging = (raw and 0x80) != 0)
        }
        return if (m.payload.size == 1) {
            BatteryStatus(single = level(0), source = DataSource.FAST_PAIR_STREAM, timestampMillis = now)
        } else {
            BatteryStatus(left = level(0), right = level(1), case = level(2), source = DataSource.FAST_PAIR_STREAM, timestampMillis = now)
        }
    }

    fun parseFirmware(m: Message): String? =
        if (m.group == GROUP_DEVICE_INFO && m.code == CODE_FIRMWARE) String(m.payload, Charsets.US_ASCII).trim('\u0000', ' ') else null

    fun parseModelId(m: Message): String? =
        if (m.group == GROUP_DEVICE_INFO && m.code == CODE_MODEL_ID) m.payload.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } else null
}

/**
 * Passive Fast Pair "not discoverable" advertisement (service data of 0xFE2C) with the optional battery extension:
 * header | account-key filter (len<<4 | 0) + bytes | salt (len<<4 | 1) + bytes | battery (count<<4 | 3 show UI, 4 hide UI) + values.
 * Parsing is strict: anything malformed returns null, so a wrong frame can never show a made-up battery level.
 */
object FastPairAdvert {
    fun parseBattery(data: ByteArray, now: Long): BatteryStatus? {
        if (data.size < 4 || data.size > 40) return null
        var i = 1 // byte 0: version / flags
        fun field(): Pair<Int, Int>? {
            if (i >= data.size) return null
            val b = data[i].toInt() and 0xFF
            i++
            return (b shr 4) to (b and 0x0F)
        }
        val (fLen, fType) = field() ?: return null
        if (fType != 0 || fLen == 0 || i + fLen > data.size) return null
        i += fLen
        val (sLen, sType) = field() ?: return null
        if (sType != 1 || sLen == 0 || i + sLen > data.size) return null
        i += sLen
        val (count, type) = field() ?: return null
        if (type != 3 && type != 4) return null
        if (count !in 1..3 || i + count != data.size) return null
        fun level(k: Int): BatteryLevel {
            val raw = data[i + k].toInt() and 0xFF
            val v = raw and 0x7F
            return if (v == 0x7F || v > 100) BatteryLevel(null) else BatteryLevel(v, charging = (raw and 0x80) != 0)
        }
        return if (count == 1) BatteryStatus(single = level(0), source = DataSource.FAST_PAIR_ADVERT, timestampMillis = now)
        else BatteryStatus(left = level(0), right = level(1), case = if (count == 3) level(2) else null, source = DataSource.FAST_PAIR_ADVERT, timestampMillis = now)
    }
}

object BatteryMerge {
    private const val PER_COMPONENT_GRACE_MILLIS = 5 * 60_000L

    private fun BatteryStatus.perComponent() = left != null || right != null || case != null

    /** Chooses which reading to show when several sources report. Never invents values. */
    fun prefer(current: BatteryStatus?, incoming: BatteryStatus): BatteryStatus {
        if (current == null || !current.hasAnyValue) return incoming
        if (!incoming.hasAnyValue) return current
        if (incoming.perComponent() || !current.perComponent()) return incoming
        // current is per-component, incoming is a single value: keep the richer reading while it is fresh.
        return if (incoming.timestampMillis - current.timestampMillis > PER_COMPONENT_GRACE_MILLIS) incoming else current
    }
}
