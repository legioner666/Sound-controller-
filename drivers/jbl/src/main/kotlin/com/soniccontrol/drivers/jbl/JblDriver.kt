package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.BaseDriverSession
import com.soniccontrol.core.devices.Brand
import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.BatteryLevel
import com.soniccontrol.core.devices.BatteryStatus
import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.Notice
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
import kotlinx.coroutines.sync.withLock

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


    private val controlMutex = kotlinx.coroutines.sync.Mutex()
    private var controlStarted = false

    override suspend fun start() {
        startUniversalSources()
        selectControlChannel()
        // GATT can come up later (for example on the LE address a Fast Pair stream reports after a reconnect):
        // run the selection again then, once, without duplicating sessions or subscriptions.
        launch { link.gattState.collect { if (it == ChannelState.CONFIRMED) selectControlChannel() } }
    }

    /** GATT dropped: the old vendor channel is dead. Release it so the next CONFIRMED GATT opens a fresh one (no duplicates). */
    override suspend fun onGattLost() {
        controlMutex.withLock {
            if (!controlStarted) return@withLock
            controlStarted = false
            harman?.let { runCatching { it.close() } }
            harman = null; harmanExec = null
            update { it.copy(control = ChannelState.CLOSED) }
        }
    }

    /**
     * The headset pushes mode changes by itself; as a safety net for missed notifications the listening mode is re-read
     * with the confirmed GET at a long interval, only while the control channel is confirmed. Battery is not polled here
     * (its GET has no confirmed reply on this model).
     */
    /**
     * Scheduled refresh over the already open Harman GATT channel: a read-only listening-mode GET (no new socket). Battery
     * arrives by notification / Battery Service; the mode has no notification on the Tune Beam 2, so it is asked here.
     */
    override suspend fun vendorRefresh(reason: String): Boolean {
        val exec = harmanExec ?: return false
        if (stack != JblStack.HARMAN_AA || state.value.control != ChannelState.CONFIRMED || state.value.busy) return false
        exec.execute(JblCommands.p3ModeGet(commandStatus()))
        return true
    }

    private suspend fun selectControlChannel() = controlMutex.withLock {
        if (controlStarted) return@withLock
        val services = link.gattServices()
        val sdp = link.sdpUuids()
        log(EventCategory.DRIVER, "GATT services: ${services.size}, SDP records: ${sdp.size}")

        when {
            JblProtocol.HARMAN_SERVICE in services -> { controlStarted = true; openHarman() }
            JblProtocol.AIROHA_RACE_RFCOMM in sdp || services.any { it.toString().startsWith(JblProtocol.AIROHA_GATT_PREFIX) } -> { controlStarted = true; openRace() }
            else -> update {
                it.copy(
                    protocol = "No JBL control channel found",
                    control = ChannelState.UNAVAILABLE,
                    notices = it.notices + Notice.NO_CONTROL_CHANNEL + Notice.STANDARD_BATTERY_ONLY,
                )
            }
        }
    }

    // ---------------- Harman GATT (H-AA / Protocol 4) ----------------

    private suspend fun openHarman() {
        controlOpening()
        update { it.copy(notices = it.notices - Notice.NO_CONTROL_CHANNEL - Notice.STANDARD_BATTERY_ONLY) }
        val raw = link.openGattChannel(JblProtocol.HARMAN_SERVICE, JblProtocol.HARMAN_NOTIFY, JblProtocol.HARMAN_WRITE)
            ?: link.openGattChannel(JblProtocol.HARMAN_SERVICE)
        if (raw == null) {
            controlFailed()
            update { it.copy(protocol = "Harman service present but the channel could not be opened") }
            return
        }
        val t = track(FramedTransport(raw, env.scope, env.clock, JblProtocol::splitHarman))
        controlOpened(t)
        harman = t
        harmanExec = CommandExecutor(t, JblGuard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
        launch { t.incoming.collect { onHarmanFrame(it) } }

        stack = when (model?.stack) {
            JblStack.HARMAN_AA, JblStack.PROTOCOL_4 -> model.stack
            else -> JblStack.UNIDENTIFIED
        }
        applyStack()
        if (stack == JblStack.UNIDENTIFIED) {
            notice(Notice.PROTOCOL_UNIDENTIFIED)
            if (!env.experimentalEnabled.value) notice(Notice.EXPERIMENTAL_OFF)
        }
        if (stack == JblStack.HARMAN_AA && commandStatus() != SupportStatus.EXPERIMENTAL) {
            // Known H-AA model: read state with confirmed queries only.
            harmanExec?.execute(JblCommands.p3EnableNotifications(commandStatus()))
            refresh()
        }
    }

    /** Frames identify the protocol and confirm the channel only after full validation. */
    private fun onHarmanFrame(frame: ByteArray) {
        log(EventCategory.RX, "harman", frame)
        JblProtocol.P3.errorReplyFor(frame)?.let { cmd ->
            // Observed `AA EE 01 <cmd>`. Semantics are not established: not an ACK, confirms neither the command nor the channel.
            log(EventCategory.DRIVER, "headset answered AA EE for command 0x${cmd.toString(16)} (meaning unknown, not treated as success)")
            return
        }
        val p3 = JblProtocol.P3.validate(frame)
        val p4 = if (p3 == null) JblProtocol.P4.validate(frame) else null
        if (p3 == null && p4 == null) {
            log(EventCategory.DRIVER, "frame not recognised, ignored")
            return
        }
        controlConfirmed()
        if (p3 != null) {
            if (stack == JblStack.UNIDENTIFIED) identified(JblStack.HARMAN_AA)
            if (stack != JblStack.HARMAN_AA) return
            val f = p3
            JblProtocol.P3.parseNative(f)?.let { n ->
                // A native notification means the earbuds changed something (case, wear, mode). Contents are not interpreted
                // (no verified Tune Beam 2 sample); the state is re-read through the verified Fast Pair source instead.
                log(EventCategory.DRIVER, "native notification subtype=0x${n.subtype.toString(16)} bodyLength=${n.bodyLength}: refreshing state")
                JblProtocol.P3.parseNativeBattery(f)?.let { nb ->
                    val s = state.value
                    val verified = s.modelId.equals("B484FB", ignoreCase = true) && s.firmware?.startsWith("0.2.8") == true
                    if (verified) {
                        val now = env.clock()
                        fun lv(v: Int?) = v?.let { BatteryLevel(it, null, false, now, null, DataSource.VENDOR_NATIVE_EXPERIMENTAL) }
                        acceptBattery(BatteryStatus(left = lv(nb.left), right = lv(nb.right), case = lv(nb.case), source = DataSource.VENDOR_NATIVE_EXPERIMENTAL, timestampMillis = now))
                    } else log(EventCategory.DRIVER, "native battery notification decoded but not applied: model/firmware not B484FB / 0.2.8.x")
                }
                requestRefresh("jbl native notification")
                return
            }
            JblProtocol.P3.parseBattery(f, env.clock())?.let { acceptBattery(it) }
            JblProtocol.P3.parseListeningMode(f)?.let { mode ->
                update { it.copy(noise = NoiseStatus(mode, DataSource.VENDOR_PROTOCOL, env.clock())) }
            }
            JblProtocol.P3.unknownSlotValue(f)?.let { v -> log(EventCategory.DRIVER, "listening mode slot 4 = 0x${v.toString(16)} (meaning not established, ignored)") }
        } else if (p4 != null) {
            if (stack == JblStack.UNIDENTIFIED) identified(JblStack.PROTOCOL_4)
            if (stack != JblStack.PROTOCOL_4) return
            val payload = this.p4.feed(p4) ?: return
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

    private fun identified(s: JblStack) {
        stack = s
        log(EventCategory.DRIVER, "protocol identified from a validated frame: $s")
        update { it.copy(notices = it.notices - Notice.PROTOCOL_UNIDENTIFIED - Notice.EXPERIMENTAL_OFF) }
        applyStack()
    }

    // ---------------- Airoha RACE ----------------

    private suspend fun openRace() {
        stack = JblStack.AIROHA_RACE
        applyStack()
        controlOpening()
        val raw = link.openRfcomm(JblProtocol.AIROHA_RACE_RFCOMM, writable = true)
        if (raw == null) {
            controlFailed()
            update { it.copy(protocol = "Airoha RACE record present, channel did not open") }
            return
        }
        val t = track(FramedTransport(raw, env.scope, env.clock, JblProtocol.Race::split))
        controlOpened(t)
        race = t
        raceExec = CommandExecutor(t, JblGuard, { env.experimentalEnabled.value }, env.sink, env.clock, link.address)
        launch {
            t.incoming.collect { frame ->
                log(EventCategory.RX, "race", frame)
                val f = JblProtocol.Race.validate(frame) ?: return@collect
                controlConfirmed()
                JblProtocol.Race.parseMode(f)?.let { mode ->
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
        rereadBasOnce()
        val s = commandStatus()
        return when (stack) {
            JblStack.HARMAN_AA -> {
                val exec = harmanExec ?: return CommandOutcome.NotConnected
                val m = exec.execute(JblCommands.p3ModeGet(s))
                val b = exec.execute(JblCommands.p3Battery(s))
                if (m is CommandOutcome.Confirmed) m else b
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
                val reported = JblProtocol.Race.validate(read.response)?.let(JblProtocol.Race::parseMode)
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
