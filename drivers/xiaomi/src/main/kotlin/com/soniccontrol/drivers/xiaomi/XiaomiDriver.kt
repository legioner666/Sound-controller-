package com.soniccontrol.drivers.xiaomi

import com.soniccontrol.core.devices.BaseDriverSession
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
import com.soniccontrol.core.protocol.bytesOf
import com.soniccontrol.core.protocol.u16be
import com.soniccontrol.core.protocol.u8

/**
 * Xiaomi "compact GAIA" over SPP, from ncr/omarchy-headphones PROTOCOL.md (MIT):
 * FF | version | flags | payload length | vendor u16 BE | command u16 BE | payload.
 */
object XiaomiGaia {
    const val VENDOR_HANDSHAKE = 0x000A
    const val VENDOR_DEVICE = 0x001D
    const val CMD_HANDSHAKE = 0x0300
    const val CMD_MODE_GET = 0x1003
    const val CMD_MODE_SET = 0x1004
    const val CMD_MODE_GET_REPLY = 0x1103
    const val CMD_MODE_SET_ACK = 0x1104

    data class Frame(val vendor: Int, val command: Int, val payload: ByteArray)

    fun frame(vendor: Int, command: Int, payload: ByteArray = ByteArray(0)) =
        bytesOf(0xFF, 0x03, 0x00, payload.size, vendor shr 8, vendor and 0xFF, command shr 8, command and 0xFF) + payload

    fun parse(b: ByteArray): Frame? {
        if (b.size < 8 || b.u8(0) != 0xFF) return null
        if (b.size != 8 + b.u8(3)) return null
        return Frame(b.u16be(4), b.u16be(6), b.copyOfRange(8, b.size))
    }

    fun split(bytes: ByteArray): Pair<List<ByteArray>, Int> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 8 <= bytes.size) {
            if (bytes.u8(i) != 0xFF) { i++; continue }
            val n = 8 + bytes.u8(i + 3)
            if (i + n > bytes.size) break
            out += bytes.copyOfRange(i, i + n)
            i += n
        }
        return out to i
    }

    fun handshake() = frame(VENDOR_HANDSHAKE, CMD_HANDSHAKE)
    fun modeGet() = frame(VENDOR_DEVICE, CMD_MODE_GET)

    /** Values: 00 off, 01 ANC, 02 ambient. 0x04 reboots the buds and can never be produced here. */
    fun modeSet(mode: NoiseMode): ByteArray = frame(VENDOR_DEVICE, CMD_MODE_SET, bytesOf(
        when (mode) {
            NoiseMode.OFF -> 0x00
            NoiseMode.ANC -> 0x01
            NoiseMode.AMBIENT -> 0x02
            NoiseMode.TALK_THRU -> throw IllegalArgumentException("no TalkThru")
        },
    ))

    fun parseMode(f: Frame): NoiseMode? {
        if (f.vendor != VENDOR_DEVICE || f.command != CMD_MODE_GET_REPLY || f.payload.isEmpty()) return null
        return when (f.payload.u8(0)) { 0 -> NoiseMode.OFF; 1 -> NoiseMode.ANC; 2 -> NoiseMode.AMBIENT; else -> null }
    }

    private val allowed: List<ByteArray> by lazy {
        listOf(handshake(), modeGet()) + listOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.AMBIENT).map(::modeSet)
    }

    fun isAllowed(bytes: ByteArray) = allowed.any { it.contentEquals(bytes) }
}

class XiaomiDriver : HeadphoneDriver {
    override val id = "xiaomi"
    override val brand = Brand.XIAOMI
    override val displayName = "Xiaomi"

    private val basicFeatures = mapOf(
        Feature.CONNECTION_STATUS to SupportStatus.BASIC, Feature.SYSTEM_VOLUME to SupportStatus.BASIC,
        Feature.MEDIA_CONTROL to SupportStatus.BASIC, Feature.BATTERY to SupportStatus.SUPPORTED,
    )

    override val catalog: List<CatalogEntry> = listOf(
        CatalogEntry(Brand.XIAOMI, "Xiaomi Buds 5 Pro", id, "Xiaomi compact GAIA (SPP)", basicFeatures + (Feature.NOISE_CONTROL to SupportStatus.SUPPORTED),
            notes = "Noise control confirmed on hardware in public research."),
        CatalogEntry(Brand.XIAOMI, "Xiaomi Buds 5", id, "Xiaomi compact GAIA (SPP)", basicFeatures + (Feature.NOISE_CONTROL to SupportStatus.EXPERIMENTAL)),
        CatalogEntry(Brand.XIAOMI, "Redmi Buds 5", id, "Redmi RCSP (planned)", basicFeatures, notes = "RCSP driver planned for v0.2."),
        CatalogEntry(Brand.XIAOMI, "Redmi Buds 5 Pro", id, "Redmi RCSP (planned)", basicFeatures, notes = "RCSP driver planned for v0.2."),
        CatalogEntry(Brand.XIAOMI, "Redmi Buds 3 Pro", id, "Redmi RCSP with authentication (planned)", basicFeatures),
    )

    override fun match(identity: DeviceIdentity): DriverMatch? {
        val n = identity.name ?: return null
        catalog.firstOrNull { it.model.equals(n, true) }?.let { return DriverMatch(90, it, "catalog name match") }
        if (n.startsWith("Xiaomi", true) || n.startsWith("Redmi", true)) return DriverMatch(45, null, "brand name prefix")
        return null
    }

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession {
        val entry = match(identity)?.entry
        val status = entry?.features?.get(Feature.NOISE_CONTROL) ?: SupportStatus.UNSUPPORTED
        return object : BaseDriverSession(link, identity, env, id, HeadphoneState(displayName = entry?.fullName ?: identity.name ?: identity.address, catalogEntry = entry, protocol = entry?.protocol ?: "Xiaomi")) {
            private var exec: CommandExecutor? = null
            private val guard = CommandGuard { if (XiaomiGaia.isAllowed(it.bytes)) null else "frame not on the Xiaomi allowlist" }

            private fun cmd(name: String, kind: CommandKind, bytes: ByteArray, reply: Int) = DeviceCommand("xiaomi.$name", kind, status, bytes, 2500,
                { r -> XiaomiGaia.parse(r)?.command == reply })

            override suspend fun start() {
                startUniversalSources()
                if (status == SupportStatus.UNSUPPORTED) return
                val raw = link.openRfcomm(StandardUuids.SPP, writable = true)
                    ?: return update { it.copy(notes = it.notes + "Control channel did not open. Close Xiaomi Earbuds and reconnect.") }
                val t = track(FramedTransport(raw, env.scope, env.clock, XiaomiGaia::split))
                exec = CommandExecutor(t, guard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
                launch {
                    t.incoming.collect { b ->
                        log(EventCategory.RX, "gaia", b)
                        XiaomiGaia.parse(b)?.let(XiaomiGaia::parseMode)?.let { m -> update { it.copy(noise = NoiseStatus(m, DataSource.VENDOR_PROTOCOL, env.clock())) } }
                    }
                }
                update { it.copy(capabilities = it.capabilities.with(Feature.NOISE_CONTROL, FeatureSupport(status, "GAIA 0x1004")), noiseModes = listOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.AMBIENT)) }
                if (status != SupportStatus.EXPERIMENTAL || env.experimentalEnabled.value) {
                    exec?.execute(cmd("handshake", CommandKind.READ, XiaomiGaia.handshake(), 0x8300))
                    refresh()
                }
            }

            override suspend fun refresh(): CommandOutcome =
                exec?.execute(cmd("mode.get", CommandKind.READ, XiaomiGaia.modeGet(), XiaomiGaia.CMD_MODE_GET_REPLY)) ?: CommandOutcome.NotConnected

            override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome {
                val e = exec ?: return CommandOutcome.NotConnected
                if (mode == NoiseMode.TALK_THRU) return CommandOutcome.Unsupported("no TalkThru")
                val ack = e.execute(cmd("mode.set.${mode.name.lowercase()}", CommandKind.WRITE, XiaomiGaia.modeSet(mode), XiaomiGaia.CMD_MODE_SET_ACK))
                if (ack !is CommandOutcome.Confirmed) return ack
                val read = refresh()
                if (read !is CommandOutcome.Confirmed) return read
                val now = XiaomiGaia.parse(read.response)?.let(XiaomiGaia::parseMode)
                return if (now == mode) read else CommandOutcome.Failed("headset reports $now after the change")
            }

            override suspend fun probe() = refresh()
        }
    }
}
