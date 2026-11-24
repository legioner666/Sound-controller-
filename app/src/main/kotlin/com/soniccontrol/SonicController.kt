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
import com.soniccontrol.core.devices.BluetoothAvailability
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.ConnectionState
import com.soniccontrol.core.devices.DeviceCatalog
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DiscoveredDevice
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.DriverRegistry
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.HeadphoneDriver
import com.soniccontrol.core.devices.LabController
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.ReconnectPolicy
import com.soniccontrol.core.devices.ServiceInfo
import com.soniccontrol.core.diagnostics.Anonymizer
import com.soniccontrol.core.diagnostics.DiagnosticLog
import com.soniccontrol.core.diagnostics.DiagnosticReport
import com.soniccontrol.core.diagnostics.EventCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Connects the UI to drivers and the Android Bluetooth stack. One active device at a time.
 * Bluetooth calls happen only after [BluetoothPermissions] reports the permissions as granted.
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
    private val policy = ReconnectPolicy()

    override val availability: StateFlow<BluetoothAvailability> = monitor.state
    override val catalog: DeviceCatalog = registry.catalog
    override val drivers: List<HeadphoneDriver> = registry.all

    override val devices: StateFlow<List<DiscoveredDevice>> =
        (scanner?.devices ?: MutableStateFlow(emptyMap())).map { m ->
            m.values.map { d -> d.copy(catalogEntry = registry.resolve(DeviceIdentity(d.address, d.name)).second.entry) }
                .sortedWith(compareByDescending<DiscoveredDevice> { it.audioConnected }.thenBy { it.name ?: "~" })
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val scanning: StateFlow<Boolean> = scanner?.scanning ?: MutableStateFlow(false)

    private val _active = MutableStateFlow<ActiveDevice?>(null)
    override val active: StateFlow<ActiveDevice?> = _active.asStateFlow()

    private val _results = MutableSharedFlow<ActionResult>(extraBufferCapacity = 16)
    override val results: SharedFlow<ActionResult> = _results

    val experimental: StateFlow<Boolean> = db.settings.map { it.experimental }.stateIn(scope, SharingStarted.Eagerly, false)
    private val fastPair: StateFlow<Boolean> = db.settings.map { it.fastPairListening }.stateIn(scope, SharingStarted.Eagerly, true)

    private var session: DriverSession? = null
    private var link: AndroidDeviceLink? = null
    private var sessionScope: CoroutineScope? = null
    private var connectJob: Job? = null
    @Volatile private var salt: String? = null

    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    override val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()
    private val _sdp = MutableStateFlow<List<UUID>>(emptyList())
    override val sdpUuids: StateFlow<List<UUID>> = _sdp.asStateFlow()

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
            onAcl(device.address, intent.action == BluetoothDevice.ACTION_ACL_CONNECTED)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, aclReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { salt = db.anonymizationSalt() }
        scope.launch {
            availability.collect { a ->
                if (a == BluetoothAvailability.READY) {
                    scanner?.loadBonded(); scanner?.loadAudioConnected()
                    delay(800)
                    autoConnect()
                }
            }
        }
    }

    private fun ready() = availability.value == BluetoothAvailability.READY && BluetoothPermissions.canConnect(context)

    /** On start: reconnect to the last device if it is connected for audio right now. */
    private suspend fun autoConnect() {
        val s = db.settings.value
        if (!s.autoReconnect || _active.value != null) return
        val last = s.lastDevice ?: devices.value.firstOrNull { it.audioConnected }?.address ?: return
        if (devices.value.any { it.address == last && it.audioConnected }) connect(last)
    }

    private fun onAcl(address: String, connected: Boolean) {
        log.log(EventCategory.CONNECTION, if (connected) "ACL connected" else "ACL disconnected", address = address)
        scanner?.loadAudioConnected()
        val current = _active.value
        if (!connected && current?.identity?.address == address) {
            scope.launch { stopSession() }
            _active.update { it?.copy(connection = if (db.settings.value.autoReconnect) ConnectionState.Reconnecting(1, 0) else ConnectionState.Idle) }
        }
        if (connected && db.settings.value.autoReconnect && (current?.identity?.address == address && current.connection !is ConnectionState.Connected || current == null && db.settings.value.lastDevice == address)) {
            scope.launch { delay(1_500); connect(address) }
        }
    }

    override fun refreshAvailability() {
        monitor.refresh()
        if (ready()) { scanner?.loadBonded(); scanner?.loadAudioConnected() }
    }

    override fun startScan() { if (ready() && BluetoothPermissions.canScan(context)) scanner?.startScan() }
    override fun stopScan() { scanner?.stopScan() }

    override fun connect(address: String) {
        if (!ready()) return
        val a = adapter ?: return
        connectJob?.cancel()
        connectJob = scope.launch {
            stopSession()
            val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return@launch
            val name = runCatching { device.name }.getOrNull()
            val sdp = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull() ?: emptySet()
            val identity = DeviceIdentity(address, name, sdp, scanner?.fastPairModels?.get(address))
            val (driver, match) = registry.resolve(identity)
            log.log(EventCategory.DRIVER, "driver ${driver.id} selected: ${match.reason}", address = address)

            for (attempt in 1..policy.maxAttempts) {
                val ss = CoroutineScope(scope.coroutineContext + SupervisorJob())
                val l = AndroidDeviceLink(context, a, device, ss, log, clock, scanner!!) { scanner.leTwins(name) }
                val env = DriverEnvironment(ss, experimental, fastPair, log, clock)
                val s = driver.createSession(l, identity, env)
                session = s; link = l; sessionScope = ss
                _active.value = ActiveDevice(identity, driver.id, driver.displayName, match, ConnectionState.Connecting, s.state.value)
                ss.launch {
                    var lastBattery = 0L
                    s.state.collect { st ->
                        _active.update { it?.copy(headphone = st) }
                        val b = st.battery
                        if (b != null && b.timestampMillis != lastBattery) { lastBattery = b.timestampMillis; db.recordBattery(address, b) }
                    }
                }
                val ok = runCatching { s.start() }.onFailure { log.log(EventCategory.ERROR, "session start: ${it.message}", address = address) }.isSuccess
                if (ok) {
                    _active.update { it?.copy(connection = ConnectionState.Connected) }
                    db.setLastDevice(address)
                    discover()
                    return@launch
                }
                stopSession()
                val wait = policy.delayFor(attempt + 1) ?: break
                _active.update { it?.copy(connection = ConnectionState.Reconnecting(attempt, wait)) }
                delay(wait)
            }
            _active.update { it?.copy(connection = ConnectionState.Failed("could not start a session")) }
        }
    }

    private suspend fun stopSession() {
        runCatching { session?.stop() }
        runCatching { link?.close() }
        sessionScope?.cancel()
        session = null; link = null; sessionScope = null
        _services.value = emptyList(); _sdp.value = emptyList()
    }

    override fun disconnect() {
        connectJob?.cancel()
        scope.launch {
            stopSession()
            _active.value = null
            db.setLastDevice(null)
        }
    }

    private fun act(name: String, block: suspend (DriverSession) -> CommandOutcome) {
        val s = session ?: return
        scope.launch {
            val outcome = runCatching { block(s) }.getOrElse { CommandOutcome.Failed(it.message ?: "error") }
            _results.emit(ActionResult(name, outcome))
        }
    }

    override fun refresh() = act("refresh") { it.refresh() }
    override fun setNoiseMode(mode: NoiseMode) = act("noise:${mode.name}") { it.setNoiseMode(mode) }

    // ---------------- LabController ----------------

    override fun discover() {
        val l = link ?: return
        scope.launch {
            l.refreshSdp()
            _services.value = l.snapshot()
            delay(1_500)
            _sdp.value = l.sdpUuids().sortedBy { it.toString() }
        }
    }

    override fun readAll() {
        val l = link ?: return
        scope.launch { l.readAll(); _services.value = l.snapshot() }
    }

    override fun subscribeAll() {
        val l = link ?: return
        val ss = sessionScope ?: return
        ss.launch {
            var last = 0L
            l.subscribeAll { _, _ ->
                val now = clock()
                if (now - last > 500) { last = now; ss.launch { _services.value = l.snapshot() } }
            }
        }
        scope.launch { delay(2_000); _services.value = l.snapshot() }
    }

    override fun probe() = act("probe") { it.probe() }

    override fun exportReport(): String {
        val a = _active.value
        val device = a?.let {
            mapOf(
                "name" to it.headphone.displayName,
                "driver" to it.driverId,
                "match" to it.match.reason,
                "protocol" to it.headphone.protocol,
                "firmware" to it.headphone.firmware,
                "catalogModel" to it.headphone.catalogEntry?.fullName,
                "supportLevel" to it.headphone.capabilities.level.name,
                "capabilities" to it.headphone.capabilities.features.mapKeys { k -> k.key.name }.mapValues { v -> v.value.status.name + " " + v.value.note },
                "sdp" to _sdp.value.map { u -> u.toString() },
                "gatt" to _services.value.map { s -> mapOf("uuid" to s.uuid.toString(), "characteristics" to s.characteristics.map { c -> mapOf("uuid" to c.uuid.toString(), "properties" to c.properties.toList(), "value" to c.lastValueHex) }) },
                "notes" to it.headphone.notes,
            )
        }
        return DiagnosticReport.build(
            appVersion = appVersion,
            androidSdk = Build.VERSION.SDK_INT,
            phoneModel = Build.MODEL,
            createdAtMillis = clock(),
            device = device,
            events = log.events.value,
            anonymizer = Anonymizer(salt ?: UUID.randomUUID().toString()),
        )
    }
}
