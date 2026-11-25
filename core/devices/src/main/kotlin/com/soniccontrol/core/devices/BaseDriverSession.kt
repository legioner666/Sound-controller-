package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Shared plumbing for drivers: state, job and transport lifetime, logging, and the universal,
 * read-only battery sources (BLE Battery Service and Fast Pair Message Stream).
 */
abstract class BaseDriverSession(
    protected val link: DeviceLink,
    protected val identity: DeviceIdentity,
    protected val env: DriverEnvironment,
    final override val driverId: String,
    initial: HeadphoneState,
) : DriverSession {

    private val _state = MutableStateFlow(initial)
    final override val state: StateFlow<HeadphoneState> = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private val transports = mutableListOf<BluetoothTransport>()

    protected fun update(transform: (HeadphoneState) -> HeadphoneState) = _state.update(transform)

    protected fun notice(n: Notice) = update { it.copy(notices = it.notices + n) }
    protected fun clearNotice(n: Notice) = update { it.copy(notices = it.notices - n) }

    // ---- vendor control channel state ----

    protected fun controlOpening() = update { it.copy(control = ChannelState.OPENING) }

    protected fun controlFailed() = update { it.copy(control = ChannelState.FAILED, notices = it.notices + Notice.CLOSE_VENDOR_APP) }

    protected fun controlUnavailable() = update { it.copy(control = ChannelState.UNAVAILABLE) }

    /** Marks the transport open (not yet confirmed) and follows it until it closes. */
    protected fun controlOpened(t: BluetoothTransport) {
        update { it.copy(control = ChannelState.OPEN) }
        launch {
            t.state.collect { s ->
                if (s is TransportState.Closed || s is TransportState.Failed) {
                    update { st -> if (st.control.isUp) st.copy(control = ChannelState.CLOSED) else st }
                }
            }
        }
    }

    /** Call only after a received frame passed full validation (framing, length, checksum, known message). */
    protected fun controlConfirmed() {
        if (state.value.control != ChannelState.CONFIRMED) {
            update { it.copy(control = ChannelState.CONFIRMED, notices = it.notices - Notice.CLOSE_VENDOR_APP) }
            log(EventCategory.DRIVER, "control channel confirmed by a valid frame")
        }
    }

    protected fun log(category: EventCategory, message: String, bytes: ByteArray? = null) =
        env.sink.log(DiagnosticEvent(env.clock(), category, "[$driverId] $message", bytes?.toHex(), link.address))

    protected fun launch(block: suspend () -> Unit): Job = env.scope.launch { block() }.also { synchronized(jobs) { jobs += it } }

    protected fun <T : BluetoothTransport> track(t: T): T = t.also { synchronized(transports) { transports += it } }

    protected fun acceptBattery(status: BatteryStatus) {
        if (!status.hasAnyValue) return
        update { s ->
            val merged = BatteryMerge.prefer(s.battery, status)
            val caps = if (merged.left != null || merged.right != null) {
                s.capabilities.with(Feature.BATTERY_PER_COMPONENT, FeatureSupport(statusFor(merged.source), merged.source.name))
                    .with(Feature.BATTERY, FeatureSupport(statusFor(merged.source), merged.source.name))
            } else {
                s.capabilities.with(Feature.BATTERY, FeatureSupport(statusFor(merged.source), merged.source.name))
            }
            s.copy(battery = merged, capabilities = caps)
        }
    }

    /** Standard/vendor-pushed sources are passive reads; vendor protocol readings carry the driver's own status. */
    protected open fun statusFor(source: DataSource): SupportStatus = when (source) {
        DataSource.BLE_BATTERY_SERVICE, DataSource.FAST_PAIR_STREAM -> SupportStatus.SUPPORTED
        DataSource.APPLE_CONTINUITY_ADVERT, DataSource.FAST_PAIR_ADVERT -> SupportStatus.EXPERIMENTAL
        DataSource.VENDOR_PROTOCOL -> SupportStatus.SUPPORTED
    }

    /** Starts the passive battery sources. Safe for any device: nothing is written to the headset. */
    /**
     * Order matters: the cheap Fast Pair stream goes first (it can reveal the LE address), then GATT.
     * Without the stream, the L2CAP variant is tried through GATT. All of it is read-only.
     */
    protected fun startUniversalSources() {
        launch { listenFastPairAdverts() }
        if (!env.fastPairListening.value) {
            launch { readGattSources() }
            return
        }
        launch {
            val stream = openFastPairStream()
            if (stream != null) launch { consumeStream(stream) }
            launch { readGattSources() }
            if (stream == null) openFastPairL2cap()?.let { launch { consumeStream(it) } }
        }
    }

    private suspend fun readGattSources() {
        readDeviceInformation()
        readBleBatteryService()
    }

    /** Standard Device Information Service: firmware revision (read-only). Used for identification only. */
    private suspend fun readDeviceInformation() {
        if (StandardUuids.DEVICE_INFORMATION !in link.gattServices()) return
        link.readCharacteristic(StandardUuids.DEVICE_INFORMATION, StandardUuids.FIRMWARE_REVISION)
            ?.let { String(it, Charsets.UTF_8).trim('\u0000', ' ') }
            ?.takeIf { it.isNotBlank() && it.all { c -> c.code in 0x20..0x7E } }
            ?.let { fw -> update { st -> st.copy(firmware = st.firmware ?: fw) } }
    }

    private suspend fun readBleBatteryService() {
        val services = link.gattServices()
        if (StandardUuids.BATTERY_SERVICE !in services) return
        link.readCharacteristic(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)?.let { onBas(it) }
        link.notifications(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)?.collect { onBas(it) }
    }

    private fun onBas(value: ByteArray) {
        if (value.isEmpty()) return
        val pct = value[0].toInt() and 0xFF
        if (pct > 100) return
        log(EventCategory.RX, "BLE Battery Service: $pct%", value)
        acceptBattery(BatteryStatus(single = BatteryLevel(pct), source = DataSource.BLE_BATTERY_SERVICE, timestampMillis = env.clock()))
    }

    /** Passive: parses Fast Pair advertisements already in the air. Nothing is sent. */
    private suspend fun listenFastPairAdverts() {
        link.fastPairAdverts().collect { data ->
            FastPairAdvert.parseBattery(data, env.clock())?.let {
                log(EventCategory.RX, "Fast Pair advertisement battery", data)
                acceptBattery(it)
            }
        }
    }

    private val fastPairSession = FastPairSession(env.clock)
    private var streamModelId: String? = null

    private suspend fun openFastPairStream(): BluetoothTransport? {
        if (link.sdpUuids().isNotEmpty() && StandardUuids.FAST_PAIR_MESSAGE_STREAM !in link.sdpUuids()) return null
        val writable = env.experimentalEnabled.value
        val raw = link.openRfcomm(StandardUuids.FAST_PAIR_MESSAGE_STREAM, writable = writable) ?: return null
        log(EventCategory.RFCOMM, "Fast Pair Message Stream open (RFCOMM, ${if (writable) "experimental request allowed" else "listen-only"})")
        return track(FramedTransport(raw, env.scope, env.clock, FastPair::split))
    }

    private suspend fun openFastPairL2cap(): BluetoothTransport? {
        if (StandardUuids.FAST_PAIR_SERVICE !in link.gattServices()) return null
        val psm = link.readCharacteristic(StandardUuids.FAST_PAIR_SERVICE, StandardUuids.FAST_PAIR_PSM)?.let { FastPair.parsePsm(it) } ?: return null
        val raw = link.openL2cap(psm, writable = false) ?: return null
        log(EventCategory.RFCOMM, "Fast Pair Message Stream open (L2CAP PSM $psm, listen-only)")
        return track(FramedTransport(raw, env.scope, env.clock, FastPair::split))
    }

    /** Reads stream frames; the one allowed request (active components) is sent only with experimental features on. */
    private suspend fun consumeStream(t: BluetoothTransport) {
        val answered = kotlinx.coroutines.CompletableDeferred<Boolean>()
        if (t.canWrite && env.experimentalEnabled.value && FastPair.isAllowedWrite(FastPair.ACTIVE_COMPONENTS_REQUEST)) {
            launch {
                runCatching { t.write(FastPair.ACTIVE_COMPONENTS_REQUEST) }
                    .onSuccess { log(EventCategory.TX, "Fast Pair active components request", FastPair.ACTIVE_COMPONENTS_REQUEST) }
                    .onFailure { log(EventCategory.ERROR, "Fast Pair request not sent: ${it.message}") }
                val ok = kotlinx.coroutines.withTimeoutOrNull(3_000) { answered.await() } ?: false
                if (!ok) log(EventCategory.INFO, "Fast Pair: no active components reply within 3 s")
            }
        }
        t.incoming.collect { frame ->
            val e = fastPairSession.onFrame(frame)
            if (e == null) { log(EventCategory.ERROR, "Fast Pair: invalid frame ignored", frame); return@collect }
            log(EventCategory.RX, "Fast Pair ${e::class.simpleName}", frame)
            update { it.copy(fastPair = fastPairSession.capabilities) }
            when (e) {
                is FastPairEvent.ModelId -> { streamModelId = e.hex; update { it.copy(modelId = e.hex) } }
                is FastPairEvent.Battery -> acceptBattery(e.status)
                is FastPairEvent.Firmware -> update { it.copy(firmware = it.firmware ?: e.version) }
                is FastPairEvent.RemainingMinutes -> update { it.copy(remainingMinutes = e.minutes) }
                is FastPairEvent.Active -> { answered.complete(true); update { it.copy(active = e.components) } }
                is FastPairEvent.BleAddress -> launch {
                    // Priority GATT candidate for this session only; if GATT opens now, read the standard sources again.
                    if (link.suggestEndpoint(e.address, streamModelId)) readGattSources()
                }
                is FastPairEvent.Unknown -> Unit
            }
        }
    }

    override suspend fun stop() {
        synchronized(jobs) { jobs.forEach { it.cancel() }; jobs.clear() }
        val toClose = synchronized(transports) { transports.toList().also { transports.clear() } }
        toClose.forEach { runCatching { it.close() } }
    }

    override suspend fun refresh(): CommandOutcome = CommandOutcome.Unsupported("this device has no vendor refresh")
    override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome = CommandOutcome.Unsupported("noise control is not available")
    override suspend fun probe(): CommandOutcome = CommandOutcome.Unsupported("no protocol probe for this device")
}
