package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    protected fun update(transform: (HeadphoneState) -> HeadphoneState) =
        _state.update { st -> transform(st).let { it.copy(live = it.live.copy(stateUpdatedMillis = env.clock())) } }

    protected fun updateLive(transform: (LiveInfo) -> LiveInfo) = update { it.copy(live = transform(it.live)) }

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

    protected fun log(category: EventCategory, message: String, bytes: ByteArray? = null) {
        if (category == EventCategory.RX) _state.update { it.copy(live = it.live.copy(lastRxMillis = env.clock())) }
        logRaw(category, message, bytes)
    }

    private fun logRaw(category: EventCategory, message: String, bytes: ByteArray?) =
        env.sink.log(DiagnosticEvent(env.clock(), category, "[$driverId] $message", bytes?.toHex(), link.address))

    protected fun launch(block: suspend () -> Unit): Job = env.scope.launch { block() }.also { synchronized(jobs) { jobs += it } }

    /** Tracks a transport for [stop]; ended ones are dropped first, so the list never grows with every refresh (TASK 010). */
    protected fun <T : BluetoothTransport> track(t: T): T = t.also {
        synchronized(transports) { transports.removeAll { x -> x.state.value.isEnded }; transports += it }
    }

    /** Transports this session still holds (open or opening); bounded by the coordinator budget. For diagnostics and tests. */
    val trackedTransports: Int get() = synchronized(transports) { transports.count { !it.state.value.isEnded } }

    protected fun acceptBattery(status: BatteryStatus) {
        if (!status.hasAnyValue && !status.hasChargingInfo) return
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
        DataSource.VENDOR_NATIVE_EXPERIMENTAL -> SupportStatus.EXPERIMENTAL
    }

    /** Starts the passive battery sources. Safe for any device: nothing is written to the headset. */
    /**
     * Order matters: the cheap Fast Pair stream goes first (it can reveal the LE address), then GATT.
     * Without the stream, the L2CAP variant is tried through GATT. All of it is read-only.
     */
    protected fun startUniversalSources() {
        launch { listenFastPairAdverts() }
        link.expectFastPairStream(env.fastPairListening.value && StandardUuids.FAST_PAIR_MESSAGE_STREAM in link.sdpUuids())
        superviseGatt()
        if (!env.fastPairListening.value) {
            initialStreamAttempted = true
            launchGattSources()
            return
        }
        launch {
            val stream = openFastPairStream()
            initialStreamAttempted = true
            if (stream != null) launch { consumeStream(stream) }
            launchGattSources()
            if (stream == null) {
                openFastPairL2cap()?.let { launch { consumeStream(it) } }
            } else {
                // RFCOMM that closes early (the headset or the system dropped it): fall back to L2CAP once.
                launch {
                    stream.state.first { it != TransportState.Open }
                    log(EventCategory.RFCOMM, "Fast Pair RFCOMM stream closed; trying L2CAP")
                    openFastPairL2cap()?.let { launch { consumeStream(it) } }
                }
            }
        }
    }

    private suspend fun readGattSources() {
        readDeviceInformation()
        readBleBatteryService()
    }

    private var gattSourcesJob: Job? = null

    /** (Re)starts the GATT-based sources; a previous run (from a lost connection) is cancelled first, so subscriptions never pile up. */
    private fun launchGattSources() {
        gattSourcesJob?.cancel()
        gattSourcesJob = launch { readGattSources() }
    }

    // ---- recovery (TASK 007) --------------------------------------------------------------------------------------

    private var recoveryJob: Job? = null
    private val recoveryDelaysMillis = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L)

    /** The GATT link was lost: drivers release whatever depended on it (control channel, subscriptions). */
    protected open suspend fun onGattLost() {}

    /** GATT is back after a loss; sources and (via [onGattLost] reset) the vendor channel are re-established. */
    protected open suspend fun onGattRestored() {}

    /** Watches GATT and recovers it after loss with bounded, backed-off attempts; audio is never touched. */
    protected fun superviseGatt() {
        launch {
            var wasUp = link.gattState.value == ChannelState.CONFIRMED
            link.gattState.collect { st ->
                when {
                    st == ChannelState.CONFIRMED -> wasUp = true
                    st == ChannelState.CLOSED && wasUp -> {
                        wasUp = false
                        log(EventCategory.GATT, "GATT lost; recovering")
                        updateLive { it.copy(stopReason = "GATT link lost") }
                        startRecovery()
                    }
                }
            }
        }
    }

    protected fun startRecovery() {
        if (recoveryJob?.isActive == true) return   // exactly one recovery job per session, never parallel
        recoveryJob = launch {
            onGattLost()
            markBatteryStale()   // the live source is gone: old values stay visible but are labelled last-known
            var attempt = 0
            val first = recoveryDelaysMillis.first()
            updateLive { it.copy(reconnect = "waiting ${first / 1000} s before attempt 1") }
            kotlinx.coroutines.delay(first)   // a link that drops for a moment usually comes back on its own
            while (true) {
                if (link.gattState.value == ChannelState.CONFIRMED) { recovered(attempt); return@launch }
                when (val d = env.coordinator.tryAcquire(ConnectionCoordinator.Kind.GATT)) {
                    is ConnectionCoordinator.Decision.Denied -> {
                        // Circuit open or budget used: wait for a relevant Bluetooth event or the cooldown, never spin.
                        val wait = env.coordinator.cooldownRemaining(ConnectionCoordinator.Kind.GATT).coerceAtLeast(RECOVERY_PAUSE_MIN_MILLIS)
                        updateLive { it.copy(reconnect = "paused: waiting for a Bluetooth event or ${wait / 60_000} min", stopReason = "GATT could not be restored yet") }
                        log(EventCategory.GATT, "GATT recovery paused (${d.reason})")
                        val seen = env.coordinator.wake.value
                        kotlinx.coroutines.withTimeoutOrNull(wait) { env.coordinator.wake.first { it != seen } }
                    }
                    ConnectionCoordinator.Decision.Allowed -> {
                        attempt++
                        updateLive { it.copy(reconnect = "attempt $attempt") }
                        log(EventCategory.GATT, "GATT recovery attempt $attempt: fresh scan and endpoint verification")
                        if (link.reconnectGatt()) {
                            env.coordinator.reportSuccess(ConnectionCoordinator.Kind.GATT)
                            recovered(attempt)
                            return@launch
                        }
                        env.coordinator.reportFailure(ConnectionCoordinator.Kind.GATT)
                        val wait = recoveryDelaysMillis.getOrElse(attempt) { recoveryDelaysMillis.last() }
                        updateLive { it.copy(reconnect = "waiting ${wait / 1000} s after attempt $attempt") }
                        val seen = env.coordinator.wake.value
                        kotlinx.coroutines.withTimeoutOrNull(wait) { env.coordinator.wake.first { it != seen } }
                    }
                }
            }
        }
    }

    private suspend fun recovered(attempts: Int) {
        updateLive { it.copy(reconnect = null, stopReason = null, reconnects = it.reconnects + 1, subscriptions = link.activeSubscriptions) }
        log(EventCategory.GATT, "GATT recovered after $attempts attempt(s)")
        launchGattSources()
        onGattRestored()
    }

    /** A notification hinted at a change: ask the single refresh loop for an immediate refresh (or refresh here without one). */
    protected fun requestRefresh(reason: String) {
        env.refreshRequests?.invoke(reason) ?: launch { refreshNow(reason) }
    }

    /** Marks every battery component as "last known": its source is gone or silent, so it must not look current. */
    protected fun markBatteryStale() = update { s ->
        val b = s.battery ?: return@update s
        fun BatteryLevel?.stale() = this?.copy(lastKnown = true)
        s.copy(battery = b.copy(left = b.left.stale(), right = b.right.stale(), case = b.case.stale(), single = b.single.stale()))
    }

    private val refreshMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastBasMillis: Long? = null
    @Volatile private var lastFastPairDataMillis: Long? = null
    @Volatile private var failedRefreshOpens = 0
    @Volatile private var initialStreamAttempted = false

    /** Counters for tests and diagnostics: how each refresh was served. */
    data class RefreshStats(val refreshes: Int = 0, val skippedBusy: Int = 0, val gattReads: Int = 0, val gattReadsSkipped: Int = 0, val vendorReads: Int = 0, val streamOpens: Int = 0)
    @Volatile var refreshStats = RefreshStats()
        private set

    /**
     * One scheduled refresh (owner decision, TASK 011). Sources in priority order:
     * 1. Bluetooth notifications — already pushed into the state; a fresh one makes the read below redundant.
     * 2. A read over the GATT link that is already open (Battery Service, the driver's vendor GET). No socket is created,
     *    so the RFCOMM budget does not apply.
     * 3. A Fast Pair stream that is already open delivers by itself: nothing to do.
     * 4. Re-opening the read-only Fast Pair RFCOMM stream — only when its data is older than [FAST_PAIR_REOPEN_MIN_AGE_MILLIS]
     *    and within [ConnectionCoordinator]'s socket budget.
     * Never runs twice at once: a refresh that finds another one running is skipped, not queued.
     */
    override suspend fun refreshNow(reason: String) {
        if (!refreshMutex.tryLock()) { refreshStats = refreshStats.copy(skippedBusy = refreshStats.skippedBusy + 1); return }
        try {
            refreshStats = refreshStats.copy(refreshes = refreshStats.refreshes + 1)
            val now = env.clock()
            if (link.gattState.value == ChannelState.CONFIRMED) {
                if (lastBasMillis?.let { now - it < NOTIFICATION_DEDUP_MILLIS } == true) {
                    refreshStats = refreshStats.copy(gattReadsSkipped = refreshStats.gattReadsSkipped + 1)
                } else if (StandardUuids.BATTERY_SERVICE in link.gattServices()) {
                    refreshStats = refreshStats.copy(gattReads = refreshStats.gattReads + 1)
                    rereadBasOnce()
                }
                if (vendorRefresh(reason)) refreshStats = refreshStats.copy(vendorReads = refreshStats.vendorReads + 1)
            }
            refreshFastPairIfNeeded(now, reason)
        } finally {
            refreshMutex.unlock()
        }
    }

    /** Driver hook for a cheap read-only query over its already open control channel; returns true if one was sent. */
    protected open suspend fun vendorRefresh(reason: String): Boolean = false

    private suspend fun refreshFastPairIfNeeded(now: Long, reason: String) {
        if (!env.fastPairListening.value || !initialStreamAttempted) return   // the session's own first open comes first
        if (link.sdpUuids().isNotEmpty() && StandardUuids.FAST_PAIR_MESSAGE_STREAM !in link.sdpUuids()) return
        if (currentStream?.state?.value == TransportState.Open) return                          // data flows by itself
        val dataAge = lastFastPairDataMillis?.let { now - it } ?: Long.MAX_VALUE
        val openAge = lastRefreshOpenMillis?.let { now - it } ?: Long.MAX_VALUE
        if (dataAge < FAST_PAIR_REOPEN_MIN_AGE_MILLIS || openAge < FAST_PAIR_REOPEN_MIN_AGE_MILLIS) return
        val stream = openFastPairStream()
        if (stream == null) {
            if (++failedRefreshOpens >= 2) markBatteryStale()
            return
        }
        failedRefreshOpens = 0
        refreshStats = refreshStats.copy(streamOpens = refreshStats.streamOpens + 1)
        log(EventCategory.RFCOMM, "Fast Pair refresh ($reason)")
        launch { consumeStream(stream) }
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
        val flow = link.notifications(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL) ?: return
        updateLive { it.copy(subscriptions = link.activeSubscriptions) }
        flow.collect { onBas(it) }
    }

    private fun onBas(value: ByteArray) {
        if (value.isEmpty()) return
        val pct = value[0].toInt() and 0xFF
        if (pct > 100) return
        lastBasMillis = env.clock()
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
        when (val d = env.coordinator.tryAcquire(ConnectionCoordinator.Kind.RFCOMM)) {
            is ConnectionCoordinator.Decision.Denied -> { logDenied("RFCOMM", d.reason); return null }
            ConnectionCoordinator.Decision.Allowed -> lastDenied = null
        }
        lastRefreshOpenMillis = env.clock()   // every attempt, at start or on refresh, paces the next one
        val raw = link.openRfcomm(StandardUuids.FAST_PAIR_MESSAGE_STREAM, writable = writable)
        if (raw == null) { env.coordinator.reportFailure(ConnectionCoordinator.Kind.RFCOMM); return null }
        env.coordinator.reportSuccess(ConnectionCoordinator.Kind.RFCOMM)
        log(EventCategory.RFCOMM, "Fast Pair Message Stream open (RFCOMM, ${if (writable) "experimental request allowed" else "listen-only"})")
        return track(FramedTransport(raw, env.scope, env.clock, FastPair::split)).also { currentStream = it }
    }

    @Volatile private var currentStream: BluetoothTransport? = null
    @Volatile private var lastRefreshOpenMillis: Long? = null
    @Volatile private var lastDenied: String? = null

    /** A denial is logged once per reason change, so a closed circuit cannot flood the bounded diagnostics log. */
    private fun logDenied(kind: String, reason: String) {
        if (lastDenied == reason) return
        lastDenied = reason
        log(EventCategory.RFCOMM, "$kind open skipped: $reason")
    }

    private var l2capAttempts = 0
    private companion object {
        const val MAX_L2CAP_ATTEMPTS = 3
        const val PSM_READS = 3
        const val GATT_WAIT_MILLIS = 30_000L
        const val RECOVERY_PAUSE_MIN_MILLIS = 60_000L
        /** A notification this recent already answered what a timed read would ask. */
        const val NOTIFICATION_DEDUP_MILLIS = 3_000L
        /** Fast Pair data younger than this is not refreshed with a new socket: at most ~30 opens/h, inside the 40/h socket budget with room for events. */
        const val FAST_PAIR_REOPEN_MIN_AGE_MILLIS = 120_000L
    }

    /**
     * L2CAP Message Stream through the PSM characteristic of the actual GATT endpoint (secure channel, read-only).
     * Waits a bounded time for GATT, re-reads an "unknown" PSM a few times, and makes at most [MAX_L2CAP_ATTEMPTS] tries
     * per session, so a headset that refuses can never cause an endless reconnect loop. Never touches the JBL control channel.
     */
    private suspend fun openFastPairL2cap(): BluetoothTransport? {
        while (l2capAttempts < MAX_L2CAP_ATTEMPTS && !link.l2capDenied) {
            l2capAttempts++
            if (env.coordinator.tryAcquire(ConnectionCoordinator.Kind.L2CAP) is ConnectionCoordinator.Decision.Denied) { log(EventCategory.RFCOMM, "L2CAP skipped: coordinator budget or circuit"); return null }
            val up = kotlinx.coroutines.withTimeoutOrNull(GATT_WAIT_MILLIS) { link.gattState.first { it == ChannelState.CONFIRMED } }
            if (up == null) { log(EventCategory.RFCOMM, "L2CAP: GATT endpoint not available (attempt $l2capAttempts/$MAX_L2CAP_ATTEMPTS)"); continue }
            if (StandardUuids.FAST_PAIR_SERVICE !in link.gattServices()) { log(EventCategory.RFCOMM, "L2CAP: endpoint has no Fast Pair service"); return null }
            var psm: Int? = null
            for (read in 1..PSM_READS) {
                val raw = link.readCharacteristic(StandardUuids.FAST_PAIR_SERVICE, StandardUuids.FAST_PAIR_PSM)
                when (val st = raw?.let { FastPair.parsePsmState(it) }) {
                    is FastPair.PsmState.Ready -> { psm = st.psm; break }
                    FastPair.PsmState.Unknown -> { log(EventCategory.RFCOMM, "L2CAP PSM state unknown (read $read/$PSM_READS)"); kotlinx.coroutines.delay(1_000) }
                    FastPair.PsmState.Unavailable -> { log(EventCategory.RFCOMM, "L2CAP PSM state: unavailable"); return null }
                    FastPair.PsmState.Invalid, null -> { log(EventCategory.RFCOMM, "L2CAP PSM characteristic invalid or unreadable (${raw?.size ?: -1} bytes)"); return null }
                }
            }
            if (psm == null) continue
            val raw = link.openL2cap(psm, writable = false)
            if (raw != null) {
                env.coordinator.reportSuccess(ConnectionCoordinator.Kind.L2CAP)
                log(EventCategory.RFCOMM, "Fast Pair Message Stream open (L2CAP PSM $psm, secure, listen-only)")
                return track(FramedTransport(raw, env.scope, env.clock, FastPair::split)).also { currentStream = it }
            }
            env.coordinator.reportFailure(ConnectionCoordinator.Kind.L2CAP)
            log(EventCategory.RFCOMM, "L2CAP PSM $psm did not open (attempt $l2capAttempts/$MAX_L2CAP_ATTEMPTS)")
            if (link.l2capDenied) { log(EventCategory.RFCOMM, "L2CAP security refusal confirmed: not retrying in this session"); return null }
            kotlinx.coroutines.delay(2_000L * l2capAttempts)
        }
        return null
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
        // The reader ends when the stream ends (TASK 010): before, every refresh left a collector on a dead socket behind.
        kotlinx.coroutines.coroutineScope {
            val reader = launch { readFrames(t, answered) }
            t.state.first { it.isEnded }
            reader.cancel()
        }
        runCatching { t.close() }
    }

    private suspend fun readFrames(t: BluetoothTransport, answered: kotlinx.coroutines.CompletableDeferred<Boolean>) {
        t.incoming.collect { frame ->
            lastFastPairDataMillis = env.clock()
            val e = fastPairSession.onFrame(frame)
            if (e == null) { log(EventCategory.ERROR, "Fast Pair: invalid frame ignored", frame); return@collect }
            if (e is FastPairEvent.Unknown) {
                // Unknown messages can carry identifiers (Find Hub EID, account-derived data): only group, code and length are kept.
                log(EventCategory.RX, "Fast Pair unknown group=0x${e.group.toString(16)} code=0x${e.code.toString(16)} length=${frame.size - 4}")
            } else {
                log(EventCategory.RX, "Fast Pair ${e::class.simpleName}", frame)
            }
            update { it.copy(fastPair = fastPairSession.capabilities) }
            when (e) {
                is FastPairEvent.ModelId -> { streamModelId = e.hex; update { it.copy(modelId = e.hex) } }
                is FastPairEvent.Battery -> acceptBattery(e.status)
                is FastPairEvent.Firmware -> update { it.copy(firmware = it.firmware ?: e.version) }
                is FastPairEvent.RemainingMinutes -> update { it.copy(remainingMinutes = e.minutes) }
                is FastPairEvent.Active -> { answered.complete(true); update { it.copy(active = e.components, activeUpdatedMillis = env.clock(), activeSource = DataSource.FAST_PAIR_STREAM) } }
                is FastPairEvent.BleAddress -> launch {
                    // Priority GATT candidate for this session only; if GATT opens now, read the standard sources again.
                    if (link.suggestEndpoint(e.address, streamModelId)) launchGattSources()
                }
                is FastPairEvent.HearableAncState -> {
                    // Fast Pair Hearable controls: which modes the headset offers in its cycle (UI toggles) and the current one.
                    val a = e.anc
                    val value = "current ${a.current}; in cycle ${a.supported.sorted().joinToString("/")}; settable ${a.settable.sorted().joinToString("/")}"
                    update { it.copy(settings = it.settings + (DeviceSetting.FAST_PAIR_ANC to SettingReading(DeviceSetting.FAST_PAIR_ANC, value, DataSource.FAST_PAIR_STREAM, env.clock(), com.soniccontrol.core.protocol.Hex.encode(frame)))) }
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

    /** Re-reads the passive battery source once (a cheap, confirmed read) — used when the app returns to the foreground. */
    protected suspend fun rereadBasOnce() {
        if (StandardUuids.BATTERY_SERVICE !in link.gattServices()) return
        link.readCharacteristic(StandardUuids.BATTERY_SERVICE, StandardUuids.BATTERY_LEVEL)?.let { onBas(it) }
    }

    override suspend fun refresh(): CommandOutcome { rereadBasOnce(); return CommandOutcome.Unsupported("this device has no vendor refresh") }
    override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome = CommandOutcome.Unsupported("noise control is not available")
    override suspend fun probe(): CommandOutcome = CommandOutcome.Unsupported("no protocol probe for this device")
}
