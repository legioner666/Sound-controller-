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
        DataSource.APPLE_CONTINUITY_ADVERT -> SupportStatus.EXPERIMENTAL
        DataSource.VENDOR_PROTOCOL -> SupportStatus.SUPPORTED
    }

    /** Starts the passive battery sources. Safe for any device: nothing is written to the headset. */
    protected fun startUniversalSources() {
        launch { readBleBatteryService() }
        if (env.fastPairListening.value) launch { listenFastPair() }
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

    private suspend fun listenFastPair() {
        if (link.sdpUuids().isNotEmpty() && StandardUuids.FAST_PAIR_MESSAGE_STREAM !in link.sdpUuids()) return
        val raw = link.openRfcomm(StandardUuids.FAST_PAIR_MESSAGE_STREAM, writable = false) ?: return
        val t = track(FramedTransport(raw, env.scope, env.clock, FastPair::split))
        log(EventCategory.RFCOMM, "listening to Fast Pair Message Stream")
        t.incoming.collect { frame ->
            val m = FastPair.parse(frame) ?: return@collect
            log(EventCategory.RX, "Fast Pair ${m.group}/${m.code}", frame)
            FastPair.parseBattery(m, env.clock())?.let { acceptBattery(it) }
            FastPair.parseFirmware(m)?.let { fw -> update { it.copy(firmware = it.firmware ?: fw) } }
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
