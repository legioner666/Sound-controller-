package com.soniccontrol.drivers.nothing

import com.soniccontrol.core.devices.BaseDriverSession
import com.soniccontrol.core.devices.BatteryLevel
import com.soniccontrol.core.devices.BatteryStatus
import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CommandExecutor
import com.soniccontrol.core.devices.CommandGuard
import com.soniccontrol.core.devices.CommandKind
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.DeviceCommand
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.DriverMatch
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.FeatureSupport
import com.soniccontrol.core.devices.FramedTransport
import com.soniccontrol.core.devices.HeadphoneDriver
import com.soniccontrol.core.devices.HeadphoneState
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.NoiseStatus
import com.soniccontrol.core.devices.StandardUuids
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.Crc
import com.soniccontrol.core.protocol.bytesOf
import com.soniccontrol.core.protocol.u16le
import com.soniccontrol.core.protocol.u8
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Nothing "NT Link" over RFCOMM, written from ncr/omarchy-headphones PROTOCOL.md (MIT):
 * 55 60 01 | cmd u16 LE (low = command, high = direction) | len u16 LE | op | payload | CRC-16/MODBUS LE.
 */
object NtLink {
    val UUID_NT: UUID = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")
    const val DIR_GET = 0xC0
    const val DIR_SET = 0xF0
    const val DIR_ANSWER = 0x40
    const val DIR_ACK = 0x70
    const val DIR_UNSOLICITED = 0xE0
    const val CMD_DEVICE_INFO = 0x06
    const val CMD_BATTERY = 0x07
    const val CMD_NOISE = 0x1E
    const val CMD_NOISE_SET = 0x0F
    const val UNSOLICITED_BATTERY = 0x01
    const val UNSOLICITED_NOISE = 0x03

    data class Frame(val command: Int, val direction: Int, val op: Int, val payload: ByteArray)

    fun frame(command: Int, direction: Int, payload: ByteArray = ByteArray(0), op: Int = 0x01): ByteArray {
        val head = bytesOf(0x55, 0x60, 0x01, command, direction, payload.size and 0xFF, payload.size shr 8, op) + payload
        val crc = Crc.crc16Modbus(head)
        return head + bytesOf(crc and 0xFF, crc shr 8)
    }

    fun parse(b: ByteArray): Frame? {
        if (b.size < 10 || b.u8(0) != 0x55) return null
        val len = b.u16le(5)
        if (b.size != 10 + len) return null
        if (Crc.crc16Modbus(b, 0, b.size - 2) != b.u16le(b.size - 2)) return null
        return Frame(b.u8(3), b.u8(4), b.u8(7), b.copyOfRange(8, 8 + len))
    }

    fun split(bytes: ByteArray): Pair<List<ByteArray>, Int> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 10 <= bytes.size) {
            if (bytes.u8(i) != 0x55) { i++; continue }
            val len = bytes.u16le(i + 5)
            if (i + 10 + len > bytes.size) break
            out += bytes.copyOfRange(i, i + 10 + len)
            i += 10 + len
        }
        return out to i
    }

    fun deviceInfoGet() = frame(CMD_DEVICE_INFO, DIR_GET)
    fun batteryGet() = frame(CMD_BATTERY, DIR_GET)
    fun noiseGet() = frame(CMD_NOISE, DIR_GET)

    fun noiseSet(mode: NoiseMode, ancLevel: Int = 0x01): ByteArray {
        val m = when (mode) {
            NoiseMode.ANC -> ancLevel.coerceIn(0x01, 0x04)
            NoiseMode.OFF -> 0x05
            NoiseMode.AMBIENT -> 0x07
            NoiseMode.TALK_THRU -> throw IllegalArgumentException("Nothing has no TalkThru mode")
        }
        return frame(CMD_NOISE_SET, DIR_SET, bytesOf(0x01, m, 0x00))
    }

    /** Noise payload `01 <mode> 00`: 01..04 ANC (high, mid, low, adaptive), 05 off, 07 transparency. */
    fun parseNoise(f: Frame): Pair<NoiseMode, Int>? {
        val isNoise = (f.direction == DIR_ANSWER && f.command == CMD_NOISE) || (f.direction == DIR_UNSOLICITED && f.command == UNSOLICITED_NOISE)
        if (!isNoise || f.payload.size < 2) return null
        val m = f.payload.u8(1)
        return when (m) {
            in 0x01..0x04 -> NoiseMode.ANC to m
            0x05 -> NoiseMode.OFF to m
            0x07 -> NoiseMode.AMBIENT to m
            else -> null
        }
    }

    /** Battery payload: count, then (component, level) pairs; 02 left, 03 right, 04 case, 06 single; bit 7 charging. */
    fun parseBattery(f: Frame, now: Long): BatteryStatus? {
        val isBattery = (f.direction == DIR_ANSWER && f.command == CMD_BATTERY) || (f.direction == DIR_UNSOLICITED && f.command == UNSOLICITED_BATTERY)
        if (!isBattery || f.payload.isEmpty()) return null
        val count = f.payload.u8(0)
        var left: BatteryLevel? = null; var right: BatteryLevel? = null; var case: BatteryLevel? = null; var single: BatteryLevel? = null
        for (k in 0 until count) {
            val i = 1 + k * 2
            if (i + 1 >= f.payload.size) break
            val raw = f.payload.u8(i + 1)
            val lvl = (raw and 0x7F).takeIf { it <= 100 }?.let { BatteryLevel(it, raw and 0x80 != 0) } ?: BatteryLevel(null)
            when (f.payload.u8(i)) { 0x02 -> left = lvl; 0x03 -> right = lvl; 0x04 -> case = lvl; 0x06 -> single = lvl }
        }
        return BatteryStatus(left, right, case, single, DataSource.VENDOR_PROTOCOL, now)
    }

    fun isAllowed(bytes: ByteArray): Boolean {
        val f = parse(bytes) ?: return false
        return when (f.direction) {
            DIR_GET -> f.command in setOf(CMD_DEVICE_INFO, CMD_BATTERY, CMD_NOISE) && f.payload.isEmpty()
            DIR_SET -> f.command == CMD_NOISE_SET && f.payload.size == 3 && f.payload.u8(0) == 0x01 &&
                f.payload.u8(1) in setOf(1, 2, 3, 4, 5, 7) && f.payload.u8(2) == 0
            else -> false
        }
    }
}

class NothingDriver : HeadphoneDriver {
    override val id = "nothing"
    override val brand = Brand.NOTHING
    override val displayName = "Nothing"

    private val supported = listOf("Ear (a)", "Ear (2)", "Ear", "Ear (stick)", "Headphone (1)")
    private val experimental = listOf("Ear (open)", "Ear (3)", "CMF Headphone Pro", "CMF Buds 2", "CMF Buds Pro 2")

    override val catalog: List<CatalogEntry> = (supported.map { it to SupportStatus.SUPPORTED } + experimental.map { it to SupportStatus.EXPERIMENTAL }).map { (name, s) ->
        CatalogEntry(
            Brand.NOTHING, name, id, "Nothing NT Link (RFCOMM)",
            mapOf(
                Feature.CONNECTION_STATUS to SupportStatus.BASIC, Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
                Feature.MEDIA_CONTROL to SupportStatus.BASIC, Feature.BATTERY to s, Feature.BATTERY_PER_COMPONENT to s,
                Feature.NOISE_CONTROL to s,
            ),
            aliases = listOf("Nothing $name"),
        )
    }

    init { StandardUuids.register(NtLink.UUID_NT, "Nothing NT Link") }

    override fun match(identity: DeviceIdentity): DriverMatch? {
        val n = identity.name ?: return null
        catalog.firstOrNull { it.model.equals(n, true) || it.aliases.any { a -> a.equals(n, true) } }?.let { return DriverMatch(90, it, "catalog name match") }
        if (NtLink.UUID_NT in identity.sdpUuids) return DriverMatch(80, null, "NT Link SDP record")
        if (n.startsWith("Nothing", true) || n.startsWith("CMF", true)) return DriverMatch(45, null, "brand name prefix")
        return null
    }

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession {
        val entry = match(identity)?.entry
        val status = entry?.features?.get(Feature.NOISE_CONTROL) ?: SupportStatus.EXPERIMENTAL
        return object : BaseDriverSession(link, identity, env, id, HeadphoneState(displayName = entry?.fullName ?: identity.name ?: identity.address, catalogEntry = entry, protocol = "Nothing NT Link")) {
            private var exec: CommandExecutor? = null
            private var ancLevel = 0x01
            private val guard = CommandGuard { if (NtLink.isAllowed(it.bytes)) null else "frame not on the Nothing allowlist" }

            private fun cmd(name: String, kind: CommandKind, bytes: ByteArray, cmd: Int, dir: Int) =
                DeviceCommand("nothing.$name", kind, status, bytes, 2000, { r -> NtLink.parse(r)?.let { it.command == cmd && it.direction == dir } ?: false })

            override suspend fun start() {
                startUniversalSources()
                val raw = link.openRfcomm(NtLink.UUID_NT, writable = true)
                    ?: return update { it.copy(notes = it.notes + "NT Link channel did not open. Close the Nothing X app and reconnect.") }
                val t = track(FramedTransport(raw, env.scope, env.clock, NtLink::split))
                exec = CommandExecutor(t, guard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
                launch {
                    t.incoming.collect { b ->
                        log(EventCategory.RX, "nt", b)
                        val f = NtLink.parse(b) ?: return@collect
                        NtLink.parseBattery(f, env.clock())?.let { acceptBattery(it) }
                        NtLink.parseNoise(f)?.let { (mode, raw) ->
                            if (mode == NoiseMode.ANC) ancLevel = raw
                            update { it.copy(noise = NoiseStatus(mode, DataSource.VENDOR_PROTOCOL, env.clock())) }
                        }
                    }
                }
                update {
                    it.copy(
                        capabilities = it.capabilities.with(Feature.NOISE_CONTROL, FeatureSupport(status, "NT Link 0x0F")),
                        noiseModes = listOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.AMBIENT),
                    )
                }
                if (status != SupportStatus.EXPERIMENTAL || env.experimentalEnabled.value) {
                    // Activation: device info first, then wait before real queries.
                    exec?.execute(cmd("info", CommandKind.READ, NtLink.deviceInfoGet(), NtLink.CMD_DEVICE_INFO, NtLink.DIR_ANSWER))
                    delay(500)
                    refresh()
                }
            }

            override suspend fun refresh(): CommandOutcome {
                val e = exec ?: return CommandOutcome.NotConnected
                val b = e.execute(cmd("battery", CommandKind.READ, NtLink.batteryGet(), NtLink.CMD_BATTERY, NtLink.DIR_ANSWER))
                val n = e.execute(cmd("noise.get", CommandKind.READ, NtLink.noiseGet(), NtLink.CMD_NOISE, NtLink.DIR_ANSWER))
                return if (n is CommandOutcome.Confirmed) n else b
            }

            override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome {
                val e = exec ?: return CommandOutcome.NotConnected
                if (mode == NoiseMode.TALK_THRU) return CommandOutcome.Unsupported("no TalkThru on Nothing")
                val ack = e.execute(cmd("noise.set.${mode.name.lowercase()}", CommandKind.WRITE, NtLink.noiseSet(mode, ancLevel), NtLink.CMD_NOISE_SET, NtLink.DIR_ACK))
                if (ack !is CommandOutcome.Confirmed) return ack
                delay(500)
                val read = e.execute(cmd("noise.get", CommandKind.READ, NtLink.noiseGet(), NtLink.CMD_NOISE, NtLink.DIR_ANSWER))
                if (read !is CommandOutcome.Confirmed) return read
                val now = NtLink.parse(read.response)?.let(NtLink::parseNoise)?.first
                return if (now == mode) read else CommandOutcome.Failed("headset reports $now after the change")
            }

            override suspend fun probe() = refresh()
        }
    }
}
