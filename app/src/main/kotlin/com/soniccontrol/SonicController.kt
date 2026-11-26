package com.soniccontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccontrol.core.bluetooth.AndroidDeviceLink
import com.soniccontrol.core.bluetooth.BluetoothPermissions
import com.soniccontrol.core.bluetooth.BluetoothStateMonitor
import com.soniccontrol.core.bluetooth.DeviceScanner
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.devices.ActionResult
import com.soniccontrol.core.devices.ActiveDevice
import com.soniccontrol.core.devices.AudioLinkState
import com.soniccontrol.core.devices.BluetoothAvailability
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.ConnectionAttempt
import com.soniccontrol.core.devices.EndpointCandidate
import com.soniccontrol.core.devices.EndpointResolver
import com.soniccontrol.core.devices.DeviceCatalog
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.DiscoveredDevice
import com.soniccontrol.core.devices.DriverRegistry
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.HeadphoneDriver
import com.soniccontrol.core.devices.LabController
import com.soniccontrol.core.devices.LinkFactory
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.ServiceInfo
import com.soniccontrol.core.devices.SessionManager
import com.soniccontrol.core.diagnostics.DiagnosticLog
import com.soniccontrol.core.diagnostics.DiagnosticReport
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.diagnostics.Redactor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Connects the UI to the session manager and the Android Bluetooth stack.
 * Session lifecycle (connect, disconnect, reconnect) is serialised inside [SessionManager].
 */
@SuppressLint("MissingPermission")
class SonicController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: SonicDatabase,
    private val log: DiagnosticLog,
    private val registry: DriverRegistry,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : HeadphoneController, LabController {

    private val monitor = BluetoothStateMonitor(context)
    private val adapter = monitor.adapter
    private val scanner = adapter?.let { DeviceScanner(context, it, log, clock) }
    private var audioMonitor: com.soniccontrol.core.bluetooth.AudioProfileMonitor? = null

    override val availability: StateFlow<BluetoothAvailability> = monitor.state
    override val catalog: DeviceCatalog = registry.catalog
    override val drivers: List<HeadphoneDriver> = registry.all

    override val devices: StateFlow<List<DiscoveredDevice>> =
        (scanner?.devices ?: MutableStateFlow(emptyMap())).map { m ->
            m.values.map { d -> d.copy(catalogEntry = registry.resolve(DeviceIdentity(d.address, d.name)).second.entry) }
                .sortedWith(compareByDescending<DiscoveredDevice> { it.audioConnected }.thenBy { it.name ?: "~" })
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val scanning: StateFlow<Boolean> = scanner?.scanning ?: MutableStateFlow(false)

    val experimental: StateFlow<Boolean> = db.settings.map { it.experimental }.stateIn(scope, SharingStarted.Eagerly, false)
    private val fastPair: StateFlow<Boolean> = db.settings.map { it.fastPairListening }.stateIn(scope, SharingStarted.Eagerly, true)

    /** Manual LE endpoint choices are bound to the classic address of the headset they were chosen for. */
    private val manualByDevice = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val manualEndpoint = MutableStateFlow<String?>(null)
    private val attemptCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val _endpoints = MutableStateFlow<List<EndpointCandidate>>(emptyList())
    override val endpoints: StateFlow<List<EndpointCandidate>> = _endpoints.asStateFlow()
    override val selectedEndpoint: StateFlow<String?> = manualEndpoint.asStateFlow()
    private val _attempts = MutableStateFlow<List<ConnectionAttempt>>(emptyList())
    override val attempts: StateFlow<List<ConnectionAttempt>> = _attempts.asStateFlow()

    private fun brandHints(name: String?): Set<String> =
        name?.lowercase()?.split(' ', '-')?.firstOrNull { it.length >= 3 }?.let { setOf(it) } ?: emptySet()

    private fun rankEndpoints(): List<EndpointCandidate> {
        val id = active.value?.identity ?: return emptyList()
        val seen = scanner?.observations().orEmpty()
        val ranked = EndpointResolver.rank(id.address, id.name, brandHints(id.name), seen)
        // Also list the strongest unmatched advertisers so the user can pick one by hand.
        val rest = seen.filter { o -> ranked.none { it.observation.address == o.address } && o.connectable }
            .sortedByDescending { it.rssi }.take(8).map { EndpointCandidate(it, 0, listOf("unmatched")) }
        return ranked + rest
    }

    private val links = object : LinkFactory {
        override fun identity(address: String): DeviceIdentity? {
            val device = adapter?.let { runCatching { it.getRemoteDevice(address) }.getOrNull() } ?: return null
            val name = runCatching { device.name }.getOrNull()
            val sdp = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull() ?: emptySet()
            return DeviceIdentity(address, name, sdp, scanner?.fastPairModels?.get(address))
        }

        override fun audioState(address: String): AudioLinkState {
            audioMonitor?.stateOf(address)?.let { if (it != AudioLinkState.UNKNOWN) return it }
            val d = devices.value.firstOrNull { it.address == address } ?: return AudioLinkState.UNKNOWN
            return if (d.audioConnected) AudioLinkState.CONNECTED else AudioLinkState.UNKNOWN
        }

        override fun create(address: String, scope: CoroutineScope): DeviceLink? {
            if (!ready()) return null
            val a = adapter ?: return null
            val s = scanner ?: return null
            val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return null
            val name = runCatching { device.name }.getOrNull()
            val variant = attemptCounts.merge(address, 1, Int::plus)?.minus(1) ?: 0
            return AndroidDeviceLink(context, a, device, scope, log, clock, s, brandHints(name), { manualByDevice[address] }, variant)
        }
    }

    private val sessions = SessionManager(
        scope = scope, registry = registry, links = links, experimental = experimental, fastPair = fastPair,
        sink = log, clock = clock, autoReconnect = { db.settings.value.autoReconnect },
        onStarted = { address -> db.setLastDevice(address); discover() },
    )

    override val active: StateFlow<ActiveDevice?> = sessions.active

    // ---------------- Adaptive refresh (TASK 011) ----------------

    /** The single refresh loop of the process; the running session is attached to it by [SessionManager]. */
    val refresher: com.soniccontrol.core.devices.AdaptiveRefreshCoordinator get() = sessions.refresher

    @Volatile private var uiForeground = false
    @Volatile private var uiMainScreen = true
    @Volatile private var screenLocked = false

    /** MainActivity ON_START / ON_STOP. */
    fun setForeground(foreground: Boolean) { uiForeground = foreground; applyRefreshMode() }

    /** The visible navigation route is the main (home) screen or another section. */
    fun setMainScreen(main: Boolean) { uiMainScreen = main; applyRefreshMode() }

    private fun applyRefreshMode() {
        val mode = when {
            screenLocked -> com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Mode.LOCKED
            !uiForeground -> com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Mode.BACKGROUND
            uiMainScreen -> com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Mode.MAIN_SCREEN
            else -> com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Mode.SECTION
        }
        refresher.setMode(mode)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            screenLocked = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                // Screen on without a secure lock screen counts as unlocked; with one, USER_PRESENT follows on unlock.
                Intent.ACTION_SCREEN_ON -> (c.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
                else -> screenLocked
            }
            applyRefreshMode()
        }
    }

    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 16)
    override val results: SharedFlow<ActionResult> = _results

    @Volatile private var salt: String? = null
    private var labJob: Job? = null

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    override val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()
    private val _sdp = MutableStateFlow<List<UUID>>(emptyList())
    override val sdpUuids: StateFlow<List<UUID>> = _sdp.asStateFlow()

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice = (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                ) ?: return
            onAcl(device.address, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, aclReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // System broadcasts; SCREEN_OFF/ON are only delivered to receivers registered at runtime.
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(context, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenLocked = !(context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isInteractive
        applyRefreshMode()
        if (com.soniccontrol.core.background.AppShutdown.suppressed.value) refresher.shutdown()
        refresher.start()
        scope.launch {
            db.settings.map { it.refreshActiveSeconds to it.refreshBackgroundSeconds }.distinctUntilChanged().collect { (a, b) ->
                refresher.setSettings(com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Settings(a, b))
            }
        }
        scope.launch { salt = db.anonymizationSalt() }
        scope.launch {
            availability.collect { a ->
                if (a == BluetoothAvailability.READY) {
                    sessions.wake("Bluetooth ready")
                    ensureAudioMonitor()
                    scanner?.loadBonded(); scanner?.loadAudioConnected()
                    delay(800)
                    autoConnect()
                }
            }
        }
        // Lab data belongs to one session: clear it whenever the active device changes.
        scope.launch {
            active.map { it?.identity?.address }.distinctUntilChanged().collect {
                labJob?.cancel(); _services.value = emptyList(); _sdp.value = emptyList()
            }
        }
        // Battery history: only real readings, recorded once per new timestamp.
        scope.launch {
            var last = 0L
            active.collect { a ->
                val b = a?.headphone?.battery ?: return@collect
                if (b.timestampMillis != last) { last = b.timestampMillis; db.recordBattery(a.identity.address, b) }
            }
        }
    }

    private fun ready() = availability.value == BluetoothAvailability.READY && BluetoothPermissions.canConnect(context)

    /** On start: reconnect to the last device if Android has it connected for audio right now. */
    /** Sleep timer ended: close the session and Bluetooth helpers WITHOUT forgetting the last device. Reversed only by [refreshAvailability] after a manual open. */
    fun shutdown() {
        refresher.shutdown()   // full stop of monitoring: no refresh of any kind until the app is opened by hand
        sessions.disconnect()
        scanner?.stopScan()
        audioMonitor?.stop(); audioMonitor = null
        log.log(EventCategory.CONNECTION, "shutdown: sleep timer ended; nothing restarts until the app is opened")
    }

    private fun autoConnect() {
        val s = db.settings.value
        if (com.soniccontrol.core.background.AppShutdown.suppressed.value) return
        if (!s.autoReconnect || active.value != null) return
        val last = s.lastDevice ?: devices.value.firstOrNull { it.audioConnected }?.address ?: return
        if (devices.value.any { it.address == last && it.audioConnected }) sessions.connect(last)
    }

    /** Audio profile (A2DP/HFP) state changed: this, not ACL or GATT, drives the audio link and the session lifecycle. */
    private fun onAudioProfile(address: String, state: AudioLinkState) {
        log.log(EventCategory.CONNECTION, "audio profile: $state", address = address)
        if (com.soniccontrol.core.background.AppShutdown.suppressed.value) return
        scanner?.loadAudioConnected()
        if (state == AudioLinkState.UNKNOWN) return
        sessions.onAudioLink(address, state == AudioLinkState.CONNECTED)
        if (state == AudioLinkState.CONNECTED && active.value == null && db.settings.value.autoReconnect && db.settings.value.lastDevice == address) {
            scope.launch { delay(1_500); if (active.value == null) sessions.connect(address) }
        }
    }

    private fun onAcl(address: String, connected: Boolean) {
        log.log(EventCategory.CONNECTION, if (connected) "ACL connected (not an audio signal)" else "ACL disconnected (not an audio signal)", address = address)
        // Informational only: LE/ACL links say nothing about audio. Sessions start and stop from the audio profile monitor.
        // A new link to the active headset is still a good moment for a paused recovery to try once (TASK 010).
        if (connected && active.value?.identity?.address == address) sessions.wake("ACL connected")
    }

    /** Process-lifetime monitor (it holds only profile proxies and one receiver); started once Bluetooth is ready and permitted. */
    private fun ensureAudioMonitor() {
        if (com.soniccontrol.core.background.AppShutdown.suppressed.value) return
        if (audioMonitor == null && adapter != null && BluetoothPermissions.canConnect(context)) {
            audioMonitor = com.soniccontrol.core.bluetooth.AudioProfileMonitor(context, adapter, ::onAudioProfile).also { it.start() }
        }
    }

    override fun refreshAvailability() {
        monitor.refresh()
        if (!com.soniccontrol.core.background.AppShutdown.suppressed.value) refresher.resume()
        if (active.value != null) sessions.wake("app opened")
        if (ready()) { ensureAudioMonitor(); if (active.value == null) scope.launch { delay(800); autoConnect() } }
        if (ready()) { scanner?.loadBonded(); scanner?.loadAudioConnected() }
    }

    override fun startScan() { if (ready() && BluetoothPermissions.canScan(context)) scanner?.startScan() }
    override fun stopScan() { scanner?.stopScan() }

    override fun connect(address: String) { if (ready() && !com.soniccontrol.core.background.AppShutdown.suppressed.value) sessions.connect(address) }

    override fun disconnect() {
        sessions.disconnect()
        scope.launch { db.setLastDevice(null) }
    }

    private fun act(name: String, block: suspend (DriverSession) -> CommandOutcome) {
        scope.launch {
            val outcome = runCatching { sessions.withSession(block) }.getOrElse { CommandOutcome.Failed(it.message ?: "error") }
            // Null: no session, or the device changed while the command ran; nothing to report about the new one.
            if (outcome != null) _results.emit(ActionResult(name, outcome))
        }
    }

    override fun refresh() = act("refresh") { it.refresh() }
    override fun setNoiseMode(mode: NoiseMode) = act("noise:${mode.name}") { it.setNoiseMode(mode) }

    // ---------------- LabController ----------------

    private fun androidLink(): AndroidDeviceLink? = sessions.currentLink() as? AndroidDeviceLink

    override fun discover() {
        val l = androidLink() ?: return
        labJob?.cancel()
        labJob = scope.launch {
            l.refreshSdp()
            _services.value = l.snapshot()
            delay(1_500)
            _sdp.value = l.sdpUuids().sortedBy { it.toString() }
        }
    }

    override fun readAll() {
        val l = androidLink() ?: return
        scope.launch { l.readAll(); if (androidLink() === l) _services.value = l.snapshot() }
    }

    override fun subscribeAll() {
        val l = androidLink() ?: return
        labJob?.cancel()
        labJob = scope.launch {
            var last = 0L
            launch { delay(2_000); if (androidLink() === l) _services.value = l.snapshot() }
            l.subscribeAll { _, _ ->
                val now = clock()
                if (now - last > 500) { last = now; scope.launch { if (androidLink() === l) _services.value = l.snapshot() } }
            }
        }
    }

    override fun probe() = act("probe") { it.probe() }

    override fun scanEndpoints() {
        val sc = scanner ?: return
        if (!ready() || !BluetoothPermissions.canScan(context)) return
        scope.launch { sc.scanFor(8_000); _endpoints.value = rankEndpoints() }
    }

    override fun selectEndpoint(address: String?) {
        val device = active.value?.identity?.address ?: return
        if (address == null) manualByDevice.remove(device) else manualByDevice[device] = address
        manualEndpoint.value = address
        sessions.connect(device)
    }

    override fun refreshAttempts() {
        manualEndpoint.value = active.value?.identity?.address?.let { manualByDevice[it] }
        _attempts.value = androidLink()?.attempts.orEmpty()
        _endpoints.value = rankEndpoints()
    }

    override fun exportReport(): String {
        val redactor = Redactor(
            salt = salt ?: UUID.randomUUID().toString(),
            knownAddresses = devices.value.map { it.address } + listOfNotNull(active.value?.identity?.address) +
                scanner?.observations().orEmpty().map { it.address } + manualByDevice.values + androidLink()?.endpointAddresses.orEmpty(),
            knownNames = devices.value.mapNotNull { it.name } + listOfNotNull(active.value?.identity?.name),
        )
        val a = active.value
        val headsetName = a?.identity?.name
        val attemptsOut = androidLink()?.attempts.orEmpty().map { t ->
            mapOf(
                "channel" to t.channel, "transport" to t.transport, "endpoint" to t.endpointKind, "device" to redactor.address(t.address),
                "bond" to t.bondState, "durationMs" to t.durationMillis, "gattStatus" to t.status, "newState" to t.newState,
                "outcome" to t.outcome, "detail" to t.detail,
            )
        }
        val observations = scanner?.observations().orEmpty().map { o ->
            // Names of other people's devices are not exported; only whether the name matches the headset.
            val nameMatches = headsetName != null && o.name != null && o.name!!.contains(headsetName, ignoreCase = true)
            mapOf(
                "device" to redactor.address(o.address), "named" to (o.name != null), "nameMatchesHeadset" to nameMatches,
                "rssi" to o.rssi, "connectable" to o.connectable, "fastPairData" to o.hasFastPairData,
                "services" to o.serviceUuids.map { it.toString() }, "manufacturerIds" to o.manufacturerIds.map { id -> "0x" + id.toString(16) },
            )
        }
        val resolver = rankEndpoints().map { c -> mapOf("device" to redactor.address(c.observation.address), "score" to c.score, "reasons" to c.reasons, "auto" to c.auto) }
        val coord = sessions.coordinator.snapshot.value
        val diag = mapOf(
            "coordinator" to mapOf(
                "kinds" to coord.kinds.mapKeys { it.key.name }.mapValues { (_, k) -> mapOf("breaker" to k.breaker.name, "consecutiveFailures" to k.consecutiveFailures, "opensLastHour" to k.opensLastHour, "attempts" to k.attempts, "successes" to k.successes, "failures" to k.failures, "denied" to k.denied) },
                "wakes" to coord.wakes, "lastWakeReason" to coord.lastWakeReason, "lastWakeAgeSeconds" to coord.lastWakeMillis?.let { (clock() - it) / 1000 },
                "openTransports" to androidLink()?.openTransports,
                "deviceSession" to sessions.currentSessionGen,
            ),
            "refresh" to refresher.status.value.let { r ->
                val st = (sessions.currentSession() as? com.soniccontrol.core.devices.BaseDriverSession)?.refreshStats
                mapOf(
                    "mode" to r.mode.name, "intervalSeconds" to r.intervalMillis?.let { it / 1000 }, "attached" to r.attached, "stoppedBySleepTimer" to r.shutdown,
                    "loopRunning" to refresher.running, "ticks" to r.ticks, "lastReason" to r.lastReason, "lastTickAgeSeconds" to r.lastTickMillis?.let { (clock() - it) / 1000 },
                    "session" to st?.let { x -> mapOf("refreshes" to x.refreshes, "skippedBusy" to x.skippedBusy, "gattReads" to x.gattReads, "gattReadsSkippedAfterNotification" to x.gattReadsSkipped, "vendorReads" to x.vendorReads, "fastPairStreamOpens" to x.streamOpens) },
                )
            },
            "telemetry" to a?.let { d -> mapOf("status" to d.telemetryStatus(clock()).name, "control" to d.controlStatus.name, "audio" to d.links.audio.name) },
            "connectionAttempts" to attemptsOut, "bleObservations" to observations, "endpointCandidates" to resolver,
            "manualEndpointSelected" to (manualEndpoint.value != null),
            "fastPair" to a?.let { d ->
                mapOf(
                    "modelId" to d.headphone.modelId, "capabilities" to d.headphone.fastPair?.codes?.map { c -> "0x03" + c.toString(16).padStart(2, '0') },
                    "remainingMinutes" to d.headphone.remainingMinutes, "active" to d.headphone.active?.let { x -> mapOf("left" to x.left, "right" to x.right) },
                    "streamEndpointLearned" to (androidLink()?.endpointAddresses?.isNotEmpty() == true),
                )
            },
            "live" to a?.let { d ->
                val l = d.headphone.live; val now = clock()
                fun age(t: Long?) = t?.let { x -> (now - x) / 1000 }
                mapOf(
                    "lastRxAgeSeconds" to age(l.lastRxMillis), "stateUpdatedAgeSeconds" to age(l.stateUpdatedMillis),
                    "stateDeliveredAgeSeconds" to age(d.stateDeliveredMillis), "subscriptions" to l.subscriptions,
                    "stopReason" to l.stopReason, "reconnect" to l.reconnect, "reconnectsDone" to l.reconnects,
                )
            },
            "battery" to a?.headphone?.battery?.let { b ->
                fun comp(c: com.soniccontrol.core.devices.BatteryLevel?) = c?.let { x -> mapOf("percent" to x.percent, "charging" to x.charging, "lastKnown" to x.lastKnown, "ageSeconds" to x.updatedMillis?.let { t -> (clock() - t) / 1000 }) }
                mapOf("source" to b.source.name, "left" to comp(b.left), "right" to comp(b.right), "case" to comp(b.case), "single" to comp(b.single))
            },
            "bondState" to a?.identity?.address?.let { addr -> runCatching { adapter?.getRemoteDevice(addr)?.bondState }.getOrNull() }?.let { b -> if (b == BluetoothDevice.BOND_BONDED) "BONDED" else "NOT_BONDED" },
        )
        val device = (a?.let {
            mapOf(
                "catalogModel" to it.headphone.catalogEntry?.fullName,
                "driver" to it.driverId,
                "match" to it.match.reason,
                "protocol" to it.headphone.protocol,
                "firmware" to it.headphone.firmware,
                "supportLevel" to it.headphone.capabilities.level.name,
                "connection" to it.connection.toString(),
                "links" to mapOf("audio" to it.links.audio.name, "gatt" to it.links.gatt.name, "rfcomm" to it.links.rfcomm.name, "control" to it.links.control.name),
                "capabilities" to it.headphone.capabilities.features.mapKeys { k -> k.key.name }.mapValues { v -> v.value.status.name + " " + v.value.note },
                "sdp" to _sdp.value.map { u -> u.toString() },
                "gatt" to _services.value.map { s ->
                    mapOf("uuid" to s.uuid.toString(), "characteristics" to s.characteristics.map { c ->
                        mapOf("uuid" to c.uuid.toString(), "properties" to c.properties.toList(), "value" to redactor.characteristicValue(c.uuid.toString(), c.lastValueHex))
                    })
                },
                "notices" to it.headphone.notices.map { n -> n.name },
                "notes" to it.headphone.notes,
            )
        } ?: emptyMap<String, Any?>()) + diag
        val process = ProcessInfo
        val meta = mapOf(
            "build" to mapOf("versionName" to appVersion, "versionCode" to BuildConfig.VERSION_CODE, "gitCommit" to BuildConfig.GIT_COMMIT, "buildType" to BuildConfig.BUILD_TYPE),
            "process" to mapOf("sessionId" to process.sessionId, "startedAtMillis" to process.startedAtMillis(), "uptimeSeconds" to (clock() - process.startedAtMillis()) / 1000),
        )
        return DiagnosticReport.build(appVersion, Build.VERSION.SDK_INT, Build.MODEL, clock(), device, log.events.value, redactor, meta)
    }
}
