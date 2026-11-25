package com.soniccontrol.core.devices

/**
 * Google Fast Pair Message Stream (RFCOMM): group u8 | code u8 | length u16 BE | payload.
 * The headset pushes these on connect and when values change. Sonic Control only listens.
 */
object FastPair {
    const val GROUP_DEVICE_INFO = 0x03
    const val CODE_MODEL_ID = 0x01
    const val CODE_BLE_ADDRESS = 0x02
    const val CODE_BATTERY = 0x03
    const val CODE_BATTERY_TIME = 0x04
    const val CODE_ACTIVE_REQUEST = 0x05
    const val CODE_ACTIVE = 0x06
    const val CODE_FIRMWARE = 0x09

    /** Largest payload accepted from a Message Stream; anything bigger is treated as corruption and skipped. */
    const val MAX_PAYLOAD = 256

    /**
     * The only frame Sonic Control ever writes to the stream: "active components request" (group 3, code 5, empty payload).
     * It reads state and changes nothing on the headset. Written only when experimental features are enabled.
     */
    val ACTIVE_COMPONENTS_REQUEST: ByteArray = byteArrayOf(0x03, 0x05, 0x00, 0x00)

    fun isAllowedWrite(frame: ByteArray): Boolean = frame.contentEquals(ACTIVE_COMPONENTS_REQUEST)

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
            if (len > MAX_PAYLOAD) { i += 1; continue } // corrupt header: resynchronise byte by byte instead of waiting for 64 KiB
            if (i + 4 + len > bytes.size) break
            out += bytes.copyOfRange(i, i + 4 + len)
            i += 4 + len
        }
        return out to i
    }

    fun parse(frame: ByteArray): Message? {
        if (frame.size < 4) return null
        val len = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        if (len > MAX_PAYLOAD || frame.size != 4 + len) return null
        return Message(frame[0].toInt() and 0xFF, frame[1].toInt() and 0xFF, frame.copyOfRange(4, frame.size))
    }

    /** One byte per component: left, right, case (or one byte for a single battery). Bit 7 = charging; 0x7F = unknown. */
    fun parseBattery(m: Message, now: Long): BatteryStatus? {
        if (m.group != GROUP_DEVICE_INFO || m.code != CODE_BATTERY || (m.payload.size != 1 && m.payload.size != 3)) return null
        fun level(i: Int): BatteryLevel? {
            if (i >= m.payload.size) return null
            val raw = m.payload[i].toInt() and 0xFF
            val v = raw and 0x7F
            val charging = (raw and 0x80) != 0
            return if (v == 0x7F || v > 100) BatteryLevel(null, charging = if (v == 0x7F && charging) true else null) else BatteryLevel(v, charging = charging)
        }
        return if (m.payload.size == 1) {
            BatteryStatus(single = level(0), source = DataSource.FAST_PAIR_STREAM, timestampMillis = now)
        } else {
            BatteryStatus(left = level(0), right = level(1), case = level(2), source = DataSource.FAST_PAIR_STREAM, timestampMillis = now)
        }
    }

    fun parseFirmware(m: Message): String? =
        if (m.group == GROUP_DEVICE_INFO && m.code == CODE_FIRMWARE) String(m.payload, Charsets.UTF_8).trim('\u0000', ' ') else null

    fun parseModelId(m: Message): String? =
        if (m.group == GROUP_DEVICE_INFO && m.code == CODE_MODEL_ID && m.payload.size == 3) hex(m.payload) else null

    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }

    /** 0x0302: the headset's current BLE address, six bytes, most significant first. */
    fun parseBleAddress(m: Message): String? =
        if (m.group == GROUP_DEVICE_INFO && m.code == CODE_BLE_ADDRESS && m.payload.size == 6)
            m.payload.joinToString(":") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() } else null

    /** 0x0304: remaining battery time in minutes, as one byte or a big-endian uint16. */
    fun parseRemainingMinutes(m: Message): Int? {
        if (m.group != GROUP_DEVICE_INFO || m.code != CODE_BATTERY_TIME) return null
        return when (m.payload.size) {
            1 -> m.payload[0].toInt() and 0xFF
            2 -> ((m.payload[0].toInt() and 0xFF) shl 8) or (m.payload[1].toInt() and 0xFF)
            else -> null
        }
    }

    /** 0x0306: bit 0 = right bud active, bit 1 = left bud active. Other bits are not defined, so such a frame is rejected. */
    fun parseActive(m: Message): ActiveComponents? {
        if (m.group != GROUP_DEVICE_INFO || m.code != CODE_ACTIVE || m.payload.size != 1) return null
        val v = m.payload[0].toInt() and 0xFF
        if (v > 3) return null
        return ActiveComponents(right = (v and 1) != 0, left = (v and 2) != 0)
    }

    /**
     * Fast Pair "L2CAP PSM" GATT characteristic (FE2C1239): exactly 3 bytes, `state | PSM uint16`.
     * State 0 = unknown (re-read a limited number of times), 1 = ready, 2 = unavailable. A ready PSM must lie in 0x0080..0x00FF.
     * The byte order of the uint16 is not stated for the observed value 01 00 A1, so it is read big-endian
     * (0x00A1 = 161, matching the real headset); a little-endian reading (0xA100) is outside the allowed range and is rejected.
     */
    sealed interface PsmState {
        data class Ready(val psm: Int) : PsmState
        data object Unknown : PsmState
        data object Unavailable : PsmState
        data object Invalid : PsmState
    }

    fun parsePsmState(b: ByteArray): PsmState {
        if (b.size != 3) return PsmState.Invalid
        val psm = ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF)
        return when (b[0].toInt() and 0xFF) {
            0 -> if (psm in 0..0xFFFF) PsmState.Unknown else PsmState.Invalid
            1 -> if (psm in 0x0080..0x00FF) PsmState.Ready(psm) else PsmState.Invalid
            2 -> PsmState.Unavailable
            else -> PsmState.Invalid
        }
    }

    fun parsePsm(b: ByteArray): Int? = (parsePsmState(b) as? PsmState.Ready)?.psm
}

data class ActiveComponents(val left: Boolean, val right: Boolean)

/** What a headset has told us over the Message Stream; drives which fields the UI may show. */
data class FastPairCapabilities(val codes: Set<Int> = emptySet()) {
    val battery get() = FastPair.CODE_BATTERY in codes
    val remainingTime get() = FastPair.CODE_BATTERY_TIME in codes
    val activeComponents get() = FastPair.CODE_ACTIVE in codes
    val bleAddress get() = FastPair.CODE_BLE_ADDRESS in codes
}

sealed interface FastPairEvent {
    data class ModelId(val hex: String) : FastPairEvent
    data class BleAddress(val address: String) : FastPairEvent
    data class Battery(val status: BatteryStatus) : FastPairEvent
    data class RemainingMinutes(val minutes: Int) : FastPairEvent
    data class Active(val components: ActiveComponents) : FastPairEvent
    data class Firmware(val version: String) : FastPairEvent
    data class Unknown(val group: Int, val code: Int) : FastPairEvent
}

/** Decodes frames of one Message Stream and tracks which capabilities the headset has shown. Pure and JVM-testable. */
class FastPairSession(private val clock: () -> Long) {
    var capabilities = FastPairCapabilities()
        private set

    /** Null for a corrupt or invalid frame; such frames never change any state. */
    fun onFrame(frame: ByteArray): FastPairEvent? {
        val m = FastPair.parse(frame) ?: return null
        val event: FastPairEvent? = when {
            m.group != FastPair.GROUP_DEVICE_INFO -> FastPairEvent.Unknown(m.group, m.code)
            else -> when (m.code) {
                FastPair.CODE_MODEL_ID -> FastPair.parseModelId(m)?.let { FastPairEvent.ModelId(it) }
                FastPair.CODE_BLE_ADDRESS -> FastPair.parseBleAddress(m)?.let { FastPairEvent.BleAddress(it) }
                FastPair.CODE_BATTERY -> FastPair.parseBattery(m, clock())?.let { FastPairEvent.Battery(it) }
                FastPair.CODE_BATTERY_TIME -> FastPair.parseRemainingMinutes(m)?.let { FastPairEvent.RemainingMinutes(it) }
                FastPair.CODE_ACTIVE -> FastPair.parseActive(m)?.let { FastPairEvent.Active(it) }
                FastPair.CODE_FIRMWARE -> FastPair.parseFirmware(m)?.takeIf { it.isNotBlank() && it.none { c -> c.isISOControl() || c == '\uFFFD' } }?.let { FastPairEvent.Firmware(it) }
                else -> FastPairEvent.Unknown(m.group, m.code)
            }
        }
        if (event != null && event !is FastPairEvent.Unknown) capabilities = FastPairCapabilities(capabilities.codes + m.code)
        return event
    }
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
            val charging = (raw and 0x80) != 0
            return if (v == 0x7F || v > 100) BatteryLevel(null, charging = if (v == 0x7F && charging) true else null) else BatteryLevel(v, charging = charging)
        }
        return if (count == 1) BatteryStatus(single = level(0), source = DataSource.FAST_PAIR_ADVERT, timestampMillis = now)
        else BatteryStatus(left = level(0), right = level(1), case = if (count == 3) level(2) else null, source = DataSource.FAST_PAIR_ADVERT, timestampMillis = now)
    }
}

object BatteryMerge {

    private fun BatteryStatus.perComponent() = left != null || right != null || case != null

    /** Chooses which reading to show when several sources report. Never invents values. */
    fun prefer(current: BatteryStatus?, incoming: BatteryStatus): BatteryStatus {
        if (current == null || !current.hasAnyValue) return stamp(incoming)
        if (!incoming.hasAnyValue && !incoming.hasChargingInfo) return current
        if (incoming.perComponent() && current.perComponent()) return mergeComponents(current, incoming)
        if (incoming.perComponent() || !current.perComponent()) return stamp(incoming)
        // current is per-component, incoming is a single value: a single BLE Battery Service value never replaces component readings.
        return current
    }

    private fun stamp(b: BatteryStatus): BatteryStatus {
        fun BatteryLevel?.s() = this?.let {
            it.copy(
                updatedMillis = it.updatedMillis ?: b.timestampMillis.takeIf { _ -> it.percent != null },
                chargingUpdatedMillis = it.chargingUpdatedMillis ?: b.timestampMillis.takeIf { _ -> it.charging != null },
                source = it.source ?: b.source,
            )
        }
        return b.copy(left = b.left.s(), right = b.right.s(), case = b.case.s(), single = b.single.s())
    }

    /**
     * Component by component. Percent, charging and source each keep their own time: a report without a percentage keeps the
     * last known percent (marked, with its old time) while a reported charging flag gets the new time, and the other way round.
     */
    private fun mergeComponents(cur: BatteryStatus, inc: BatteryStatus): BatteryStatus {
        fun one(c: BatteryLevel?, i: BatteryLevel?): BatteryLevel? {
            if (i == null) return c?.let { if (it.percent != null) it.copy(lastKnown = true) else it }
            val now = i.updatedMillis ?: inc.timestampMillis
            val charging = i.charging ?: c?.charging
            val chargingAt = if (i.charging != null) (i.chargingUpdatedMillis ?: inc.timestampMillis) else c?.chargingUpdatedMillis
            return if (i.percent != null) BatteryLevel(i.percent, charging, false, now, chargingAt, i.source ?: inc.source)
            else {
                val keep = c?.percent
                if (keep != null) BatteryLevel(keep, charging, true, c.updatedMillis, chargingAt, c.source)
                else BatteryLevel(null, charging, false, null, chargingAt, i.source ?: inc.source)
            }
        }
        return BatteryStatus(
            left = one(cur.left, inc.left), right = one(cur.right, inc.right), case = one(cur.case, inc.case),
            single = inc.single ?: cur.single, source = inc.source, timestampMillis = inc.timestampMillis,
        )
    }
}
