package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.BaseDriverSession
import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.CommandExecutor
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.DeviceCapabilities
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
import com.soniccontrol.core.protocol.u8

class JblDriver : HeadphoneDriver {
    override val id = JblCatalog.DRIVER_ID
    override val brand = Brand.JBL
    override val displayName = "JBL"
    override val catalog: List<CatalogEntry> = JblCatalog.entries

    init {
        StandardUuids.register(JblProtocol.HARMAN_SERVICE, "JBL Harman control service")
        StandardUuids.register(JblProtocol.HARMAN_NOTIFY, "JBL Harman notify")
        StandardUuids.register(JblProtocol.HARMAN_WRITE, "JBL Harman write")
        StandardUuids.register(JblProtocol.AIROHA_RACE_RFCOMM, "Airoha RACE")
    }

    override fun match(identity: DeviceIdentity): DriverMatch? {
        JblCatalog.findModel(identity.name)?.let { m ->
            return DriverMatch(90, catalog.first { it.model == m.name }, "catalog name match")
        }
        if (JblProtocol.AIROHA_RACE_RFCOMM in identity.sdpUuids && identity.name?.contains("JBL", true) == true) {
            return DriverMatch(70, null, "JBL name with Airoha RACE record")
        }
        if (identity.name?.replace(Regex("^(?i)le[-_ ]"), "")?.startsWith("JBL", ignoreCase = true) == true) {
            return DriverMatch(50, null, "JBL name prefix")
        }
        return null
    }

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession {
        val model = JblCatalog.findModel(identity.name)
        val entry = model?.let { m -> catalog.first { it.model == m.name } }
        return JblSession(link, identity, env, model, entry)
    }
}

internal class JblSession(
    link: DeviceLink,
    identity: DeviceIdentity,
    env: DriverEnvironment,
    private val model: JblModelInfo?,
    entry: CatalogEntry?,
) : BaseDriverSession(
    link, identity, env, JblCatalog.DRIVER_ID,
    HeadphoneState(displayName = entry?.fullName ?: identity.name ?: identity.address, catalogEntry = entry, protocol = "JBL: identifying…"),
) {
    private var stack: JblStack = JblStack.UNIDENTIFIED
    private var harman: BluetoothTransport? = null
    private var race: BluetoothTransport? = null
    private var harmanExec: CommandExecutor? = null
    private var raceExec: CommandExecutor? = null
    private val p4 = JblProtocol.P4.Assembler()

    /** Supported for models whose stack is known and confirmed; experimental for everything identified at runtime. */
    private fun commandStatus(): SupportStatus =
        if (model != null && model.stack == stack) JblCatalog.statusFor(model) else SupportStatus.EXPERIMENTAL

    override suspend fun start() {
        startUniversalSources()
        val services = link.gattServices()
        val sdp = link.sdpUuids()
        log(EventCategory.DRIVER, "GATT services: ${services.size}, SDP records: ${sdp.size}")

        when {
            JblProtocol.HARMAN_SERVICE in services -> openHarman()
            JblProtocol.AIROHA_RACE_RFCOMM in sdp || services.any { it.toString().startsWith(JblProtocol.AIROHA_GATT_PREFIX) } -> openRace()
            else -> update {
                it.copy(protocol = "No JBL control channel found", notes = it.notes + "Battery from standard sources only.")
            }
        }
    }

    // ---------------- Harman GATT (H-AA / Protocol 4) ----------------

    private suspend fun openHarman() {
        val raw = link.openGattChannel(JblProtocol.HARMAN_SERVICE, JblProtocol.HARMAN_NOTIFY, JblProtocol.HARMAN_WRITE)
            ?: link.openGattChannel(JblProtocol.HARMAN_SERVICE)
            ?: return update { it.copy(protocol = "Harman service present but not usable") }
        val t = track(FramedTransport(raw, env.scope, env.clock, JblProtocol::splitHarman))
        harman = t
        harmanExec = CommandExecutor(t, JblGuard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
        launch { t.incoming.collect { onHarmanFrame(it) } }

        stack = when (model?.stack) {
            JblStack.HARMAN_AA, JblStack.PROTOCOL_4 -> model.stack
            else -> JblStack.UNIDENTIFIED
        }
        applyStack()
        if (stack == JblStack.HARMAN_AA && commandStatus() != SupportStatus.EXPERIMENTAL) {
            // Known H-AA model: read state with confirmed queries only.
            harmanExec?.execute(JblCommands.p3EnableNotifications(commandStatus()))
            refresh()
        }
    }

    private fun onHarmanFrame(frame: ByteArray) {
        log(EventCategory.RX, "harman", frame)
        if (frame.u8(0) == JblProtocol.P3.HEADER) {
            if (stack == JblStack.UNIDENTIFIED) { stack = JblStack.HARMAN_AA; applyStack() }
            val f = JblProtocol.P3.parse(frame) ?: return
            JblProtocol.P3.parseBattery(f, env.clock())?.let { acceptBattery(it) }
            JblProtocol.P3.parseListeningMode(f)?.let { mode ->
                update { it.copy(noise = NoiseStatus(mode, DataSource.VENDOR_PROTOCOL, env.clock())) }
            }
        } else if (JblProtocol.P4.looksLikeP4(frame)) {
            if (stack == JblStack.UNIDENTIFIED) { stack = JblStack.PROTOCOL_4; applyStack() }
            val packet = JblProtocol.P4.parse(frame) ?: return
            val payload = p4.feed(packet) ?: return
            val tlv = JblProtocol.P4.parseTlv(payload)
            JblProtocol.P4.parseBattery(tlv, env.clock())?.let { acceptBattery(it) }
            JblProtocol.P4.parseFirmware(tlv)?.let { fw -> update { it.copy(firmware = fw) } }
            JblProtocol.P4.parseProductId(tlv)?.let { pid ->
                val known = JblCatalog.findByPid(pid)
                val p4 = pid in JblCatalog.PROTOCOL_4_PIDS
                update { it.copy(notes = it.notes + "Product id 0x${pid.toString(16)}${known?.let { k -> " (${k.name})" } ?: ""}${if (p4) ", listed as Protocol 4" else ""}") }
            }
        }
    }

    // ---------------- Airoha RACE ----------------

    private suspend fun openRace() {
        stack = JblStack.AIROHA_RACE
        applyStack()
        val raw = link.openRfcomm(JblProtocol.AIROHA_RACE_RFCOMM, writable = true)
            ?: return update { it.copy(protocol = "Airoha RACE record present, channel did not open", notes = it.notes + "Close the JBL app: only one app can hold this channel.") }
        val t = track(FramedTransport(raw, env.scope, env.clock, JblProtocol.Race::split))
        race = t
        raceExec = CommandExecutor(t, JblGuard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
        launch {
            t.incoming.collect { frame ->
                log(EventCategory.RX, "race", frame)
                JblProtocol.Race.parse(frame)?.let(JblProtocol.Race::parseMode)?.let { mode ->
                    update { it.copy(noise = NoiseStatus(mode, DataSource.VENDOR_PROTOCOL, env.clock())) }
                }
            }
        }
        if (commandStatus() != SupportStatus.EXPERIMENTAL || env.experimentalEnabled.value) refresh()
    }

    private fun applyStack() {
        val s = commandStatus()
        val caps = when (stack) {
            JblStack.HARMAN_AA -> DeviceCapabilities.universalPlus(
                Feature.BATTERY_PER_COMPONENT to FeatureSupport(s, "H-AA 0x25"),
                Feature.NOISE_CONTROL to FeatureSupport(s, "H-AA 0x91"),
                Feature.TALK_THRU to FeatureSupport(s, "H-AA 0x91 slot 3"),
            )
            JblStack.AIROHA_RACE -> DeviceCapabilities.universalPlus(
                Feature.NOISE_CONTROL to FeatureSupport(s, "RACE 0x0E06"),
                Feature.TALK_THRU to FeatureSupport(s, "RACE 0x0E06"),
            )
            JblStack.PROTOCOL_4 -> DeviceCapabilities.universalPlus(
                Feature.BATTERY_PER_COMPONENT to FeatureSupport(SupportStatus.EXPERIMENTAL, "Protocol 4 GET, read-only"),
                Feature.NOISE_CONTROL to FeatureSupport(SupportStatus.UNSUPPORTED, "no verified Protocol 4 command"),
            )
            JblStack.UNIDENTIFIED -> DeviceCapabilities.UNIVERSAL
        }
        val modes = if (stack == JblStack.HARMAN_AA || stack == JblStack.AIROHA_RACE) NoiseMode.entries.toList() else emptyList()
        val label = when (stack) {
            JblStack.HARMAN_AA -> "JBL H-AA"
            JblStack.PROTOCOL_4 -> "JBL Protocol 4"
            JblStack.AIROHA_RACE -> "Airoha RACE"
            JblStack.UNIDENTIFIED -> "JBL Harman service, variant unknown (run the Lab probe)"
        }
        update { st ->
            // Keep battery capabilities already earned from passive sources.
            val kept = st.capabilities.features.filterKeys { it == Feature.BATTERY || (it == Feature.BATTERY_PER_COMPONENT && caps.features[it] == null) }
            st.copy(capabilities = caps.copy(features = caps.features + kept), noiseModes = modes, protocol = label)
        }
    }

    override suspend fun refresh(): CommandOutcome {
        val s = commandStatus()
        return when (stack) {
            JblStack.HARMAN_AA -> {
                val exec = harmanExec ?: return CommandOutcome.NotConnected
                val b = exec.execute(JblCommands.p3Battery(s))
                val m = exec.execute(JblCommands.p3ModeGet(s))
                if (b is CommandOutcome.Confirmed) b else m
            }
            JblStack.PROTOCOL_4 -> {
                val exec = harmanExec ?: return CommandOutcome.NotConnected
                exec.execute(JblCommands.p4Get(SupportStatus.EXPERIMENTAL, listOf(JblProtocol.P4.FID_BATTERY_LEFT, JblProtocol.P4.FID_BATTERY_RIGHT, JblProtocol.P4.FID_BATTERY_CASE)))
            }
            JblStack.AIROHA_RACE -> raceExec?.execute(JblCommands.raceModeGet(s)) ?: CommandOutcome.NotConnected
            JblStack.UNIDENTIFIED -> CommandOutcome.Unsupported("protocol not identified yet; run the Lab probe")
        }
    }

    override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome {
        val s = commandStatus()
        return when (stack) {
            JblStack.HARMAN_AA -> harmanExec?.execute(JblCommands.p3ModeSet(s, mode)) ?: CommandOutcome.NotConnected
            JblStack.AIROHA_RACE -> {
                val exec = raceExec ?: return CommandOutcome.NotConnected
                val set = exec.execute(JblCommands.raceModeSet(s, mode))
                if (set !is CommandOutcome.Confirmed) return set
                // The ack echoes the request; the state is trusted only after reading it back.
                val read = exec.execute(JblCommands.raceModeGet(s))
                if (read !is CommandOutcome.Confirmed) return read
                val reported = JblProtocol.Race.parse(read.response)?.let(JblProtocol.Race::parseMode)
                if (reported == mode) read else CommandOutcome.Failed("headset reports $reported after the change")
            }
            else -> CommandOutcome.Unsupported("no verified noise-control command for this JBL protocol")
        }
    }

    /** Lab action: identify a Harman-service device with two read-only queries (Protocol 4 GET, then H-AA version). */
    override suspend fun probe(): CommandOutcome {
        val exec = harmanExec ?: return raceExec?.execute(JblCommands.raceModeGet(SupportStatus.EXPERIMENTAL))
            ?: CommandOutcome.Unsupported("no JBL control channel is open")
        if (stack != JblStack.UNIDENTIFIED) return refresh()
        log(EventCategory.DRIVER, "probe: Protocol 4 GET product id + firmware")
        val p4 = exec.execute(JblCommands.p4Get(SupportStatus.EXPERIMENTAL, listOf(JblProtocol.P4.FID_PRODUCT_ID, JblProtocol.P4.FID_FIRMWARE)))
        if (p4 is CommandOutcome.Rejected) return p4
        if (stack != JblStack.UNIDENTIFIED) return refresh()
        log(EventCategory.DRIVER, "probe: H-AA version query")
        val aa = exec.execute(JblCommands.p3Version(SupportStatus.EXPERIMENTAL))
        if (stack != JblStack.UNIDENTIFIED) refresh()
        return if (stack == JblStack.UNIDENTIFIED) CommandOutcome.Failed("no reply to either probe; the variant is still unknown") else aa
    }
}
