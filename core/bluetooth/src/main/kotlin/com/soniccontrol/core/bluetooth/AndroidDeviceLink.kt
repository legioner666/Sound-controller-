package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CharacteristicInfo
import com.soniccontrol.core.devices.ConnectionAttempt
import com.soniccontrol.core.devices.EndpointResolver
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.ManufacturerAdvert
import com.soniccontrol.core.devices.ServiceInfo
import com.soniccontrol.core.devices.StandardUuids
import com.soniccontrol.core.devices.TransportState
import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * [DeviceLink] over the Android Bluetooth stack. One GATT connection per link, created on first use.
 * Dual-mode earbuds sometimes expose GATT under a separate LE address ("NAME-LE"); [leCandidates]
 * supplies such addresses from recent scan results.
 */
private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

@SuppressLint("MissingPermission")
class AndroidDeviceLink(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
    private val scanner: DeviceScanner,
    /** Brand words that often appear in the advertised name of the LE endpoint (e.g. "jbl"). */
    private val brandHints: Set<String> = emptySet(),
    /** LE endpoint chosen by the user in the Lab; tried first. */
    private val manualEndpoint: () -> String? = { null },
    /** 0 = LE first; odd values try the AUTO transport first, so each reconnect varies the strategy a little. */
    private val variant: Int = 0,
) : DeviceLink {
    override val address: String = device.address
    override val name: String? = runCatching { device.name }.getOrNull()

    private val _gattState = MutableStateFlow(ChannelState.UNAVAILABLE)
    override val gattState: StateFlow<ChannelState> = _gattState.asStateFlow()
    private val _rfcommState = MutableStateFlow(ChannelState.UNAVAILABLE)
    override val rfcommState: StateFlow<ChannelState> = _rfcommState.asStateFlow()
    @Volatile private var closed = false

    private val gattLock = Mutex()
    private var gatt: GattClient? = null
    private var gattTried = false
    private val transports = mutableListOf<BluetoothTransport>()
    private val lastValues = HashMap<UUID, String>()
    private val subscribed = HashSet<UUID>()

    override fun sdpUuids(): Set<UUID> = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull() ?: emptySet()

    /** Asks Android to refresh the SDP record cache (standard service discovery, no vendor command). */
    fun refreshSdp() { runCatching { device.fetchUuidsWithSdp() } }

    private val attemptLog = java.util.Collections.synchronizedList(ArrayList<ConnectionAttempt>())
    override val attempts: List<ConnectionAttempt> get() = synchronized(attemptLog) { attemptLog.toList() }

    /** GATT and RFCOMM are opened one after another, never at the same time. */
    private val channelLock = Mutex()

    private fun record(a: ConnectionAttempt) { synchronized(attemptLog) { attemptLog += a } }

    @Volatile private var streamEndpoint: String? = null
    @Volatile private var streamModel: String? = null

    /** Addresses this link has learned about, so exports can mask them. */
    val endpointAddresses: List<String> get() = listOfNotNull(streamEndpoint)

    override suspend fun suggestEndpoint(address: String, modelIdHex: String?): Boolean {
        if (closed || !MAC.matches(address)) return false
        val previous = streamEndpoint
        streamEndpoint = address.uppercase()
        streamModel = modelIdHex
        if (previous != null && previous != streamEndpoint) sink.log(DiagnosticEvent(clock(), EventCategory.CONNECTION, "headset reported a new LE address (rotation)", null, device.address))
        if (gatt?.connected?.value == true) return true // never swap an endpoint that already works
        gattTried = false
        return gatt() != null
    }

    override suspend fun openL2cap(psm: Int, writable: Boolean): BluetoothTransport? = channelLock.withLock {
        if (closed) return@withLock null
        for (insecure in listOf(false, true)) {
            val started = clock()
            val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var socket: android.bluetooth.BluetoothSocket? = null
                try {
                    val sk = if (insecure) device.createInsecureL2capChannel(psm) else device.createL2capChannel(psm)
                    socket = sk
                    sk.connect()
                    RfcommTransport(sk, java.util.UUID(0, psm.toLong()), writable, scope, sink, clock)
                } catch (e: java.io.IOException) {
                    runCatching { socket?.close() }
                    record(ConnectionAttempt("l2cap", if (insecure) "insecure" else "secure", "classic-address", device.address,
                        GattClient.bondName(device.bondState), clock() - started, null, null, RfcommTransport.classify(e.message), e.message))
                    null
                } catch (e: SecurityException) {
                    runCatching { socket?.close() }
                    null
                }
            }
            if (t != null) {
                record(ConnectionAttempt("l2cap", if (insecure) "insecure" else "secure", "classic-address", device.address,
                    GattClient.bondName(device.bondState), clock() - started, null, null, "OK", "psm=$psm"))
                synchronized(transports) { transports.add(t) }
                return@withLock t
            }
        }
        null
    }

    /** An endpoint learned from the headset itself must expose Fast Pair, and its Model ID must match when we know it. */
    private suspend fun verify(c: GattClient, step: Step): String? {
        if (step.kind != "fast-pair-endpoint") return null
        val svc = c.services.firstOrNull { it.uuid == com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_SERVICE } ?: return "WRONG_DEVICE_NO_FAST_PAIR"
        val expected = streamModel ?: return null
        val ch = svc.getCharacteristic(com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_MODEL_ID) ?: return null
        val got = c.read(ch)?.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
        return if (got != null && got.isNotEmpty() && !got.equals(expected, true)) "WRONG_DEVICE_MODEL_MISMATCH" else null
    }

    private data class Step(val device: BluetoothDevice, val kind: String, val transport: Int, val timeout: Long)

    /**
     * Scan first, then try endpoints in a fixed order with a small budget:
     * manual endpoint, resolved LE endpoints, then the classic address (LE, AUTO). BR/EDR GATT is the last resort.
     */
    private suspend fun plan(): List<Step> {
        if (scanner.observations().none { it.connectable }) {
            sink.log(DiagnosticEvent(clock(), EventCategory.SCAN, "scan before connect (6 s)", null, device.address))
            runCatching { scanner.scanFor(6_000) }
        }
        val le = BluetoothDevice.TRANSPORT_LE
        val auto = BluetoothDevice.TRANSPORT_AUTO
        val resolved = EndpointResolver.rank(device.address, name, brandHints, scanner.observations())
            .filter { it.auto && !it.observation.address.equals(device.address, true) }
            .take(2).mapNotNull { c -> runCatching { adapter.getRemoteDevice(c.observation.address) }.getOrNull() }
        val manual = manualEndpoint()?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        val steps = ArrayList<Step>()
        streamEndpoint?.let { a -> runCatching { adapter.getRemoteDevice(a) }.getOrNull() }?.let { steps += Step(it, "fast-pair-endpoint", le, 8_000) }
        manual?.let { steps += Step(it, "manual-le", le, 8_000) }
        resolved.forEach { steps += Step(it, "le-endpoint", le, 8_000) }
        val classicFirst = if (variant % 2 == 0) listOf(le, auto) else listOf(auto, le)
        classicFirst.forEach { steps += Step(device, "classic-address", it, 6_000) }
        steps += Step(device, "classic-address", BluetoothDevice.TRANSPORT_BREDR, 5_000)
        return steps.distinctBy { Triple(it.device.address, it.transport, it.kind) }.take(5)
    }

    private suspend fun gatt(): GattClient? = channelLock.withLock { gattLock.withLock {
        if (closed) return@withLock null
        gatt?.let { if (it.connected.value) return@withLock it }
        if (gattTried && gatt == null) return@withLock null
        gattTried = true
        _gattState.value = ChannelState.OPENING
        val deadline = clock() + 28_000
        for (step in plan()) {
            if (closed || clock() > deadline) break
            val c = GattClient(context, step.device, sink, clock)
            val r = c.connect(step.transport, step.timeout)
            record(ConnectionAttempt(
                channel = "gatt", transport = GattClient.transportName(step.transport), endpointKind = step.kind,
                address = step.device.address, bondState = GattClient.bondName(step.device.bondState),
                durationMillis = r.durationMillis, status = r.status, newState = r.newState, outcome = r.outcome,
                detail = "services=${r.services}",
            ))
            val wrong = if (r.ok) verify(c, step) else null
            if (wrong != null) {
                record(ConnectionAttempt("gatt", "LE", step.kind, step.device.address, GattClient.bondName(step.device.bondState), 0, null, null, wrong))
                c.close()
                continue
            }
            if (r.ok) {
                gatt = c
                _gattState.value = ChannelState.CONFIRMED // services were discovered
                scope.launch { c.connected.collect { up -> if (!up && gatt === c) _gattState.value = ChannelState.CLOSED } }
                return@withLock c
            }
            c.close()
        }
        _gattState.value = ChannelState.FAILED
        null
    } }

    override suspend fun gattServices(): Set<UUID> = gatt()?.services?.map { it.uuid }?.toSet() ?: emptySet()

    override suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray? {
        val g = gatt() ?: return null
        val c = g.find(service, characteristic) ?: return null
        return g.read(c)?.also { lastValues[characteristic] = it.toHex() }
    }

    override suspend fun notifications(service: UUID, characteristic: UUID): Flow<ByteArray>? {
        val g = gatt() ?: return null
        val c = g.find(service, characteristic) ?: return null
        if (!g.enableNotifications(c)) return null
        subscribed += characteristic
        return g.notifications.filter { it.service == service && it.characteristic == characteristic }.map { it.value }
    }

    override suspend fun openGattChannel(service: UUID, notify: UUID?, write: UUID?): BluetoothTransport? {
        val g = gatt() ?: return null
        val svc = g.services.firstOrNull { it.uuid == service } ?: return null
        val n = notify?.let { svc.getCharacteristic(it) } ?: svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        } ?: return null
        val w = write?.let { svc.getCharacteristic(it) } ?: svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        } ?: return null
        if (!g.enableNotifications(n)) return null
        subscribed += n.uuid
        return GattChannelTransport(g, n, w, scope, sink, clock).also { synchronized(transports) { transports += it } }
    }

    override suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport? = channelLock.withLock {
        if (closed) return@withLock null
        runCatching { adapter.cancelDiscovery() }
        if (writable && !_rfcommState.value.isUp) _rfcommState.value = ChannelState.OPENING
        val started = clock()
        val result = RfcommTransport.open(device, uuid, writable, scope, sink, clock)
        record(ConnectionAttempt(
            channel = if (writable) "rfcomm" else "rfcomm-listen", transport = if (result.insecure) "insecure" else "secure",
            endpointKind = "classic-address", address = device.address, bondState = GattClient.bondName(device.bondState),
            durationMillis = clock() - started, status = null, newState = null,
            outcome = if (result.transport != null) "OK" else result.failure ?: "FAILED", detail = result.detail,
        ))
        val t = result.transport
        if (t == null) {
            // A failed listen-only Fast Pair stream is not the control channel and must not mark RFCOMM as failed.
            if (writable && !openRfcommUp()) _rfcommState.value = ChannelState.FAILED
            return@withLock null
        }
        synchronized(transports) { transports.add(t) }
        if (writable) _rfcommState.value = ChannelState.OPEN
        scope.launch {
            t.state.collect { st ->
                if (writable && st != TransportState.Open && !openRfcommUp()) _rfcommState.value = ChannelState.CLOSED
            }
        }
        t
    }

    private fun openRfcommUp(): Boolean = synchronized(transports) {
        transports.any { it is RfcommTransport && it.state.value == TransportState.Open }
    }

    override fun fastPairAdverts(): Flow<ByteArray> {
        val mine = HashSet<String>().apply { add(device.address) }
        return scanner.fastPairAdverts.filter { (addr, _) ->
            addr in mine || EndpointResolver.rank(device.address, name, brandHints, scanner.observations().filter { it.address == addr })
                .any { it.auto }
        }.map { it.second }
    }

    override fun manufacturerAdverts(companyId: Int): Flow<ManufacturerAdvert> = scanner.adverts(companyId)

    // ---------------- Lab (read-only inspection) ----------------

    suspend fun snapshot(): List<ServiceInfo> {
        val g = gatt() ?: return emptyList()
        return g.services.map { s ->
            ServiceInfo(s.uuid, StandardUuids.describe(s.uuid), s.characteristics.map { c ->
                CharacteristicInfo(c.uuid, StandardUuids.describe(c.uuid), GattClient.propertyNames(c.properties), lastValues[c.uuid], c.uuid in subscribed)
            })
        }
    }

    /** Reads every readable characteristic. Reads never change the headset. */
    suspend fun readAll() {
        val g = gatt() ?: return
        for (s in g.services) for (c in s.characteristics) {
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) g.read(c)?.let { lastValues[c.uuid] = it.toHex() }
        }
    }

    /** Subscribes to every notify/indicate characteristic (standard CCCD write) and records values. */
    suspend fun subscribeAll(onValue: (UUID, ByteArray) -> Unit) {
        val g = gatt() ?: return
        for (s in g.services) for (c in s.characteristics) {
            if (c.uuid !in subscribed && g.enableNotifications(c)) subscribed += c.uuid
        }
        g.notifications.collect { n -> lastValues[n.characteristic] = n.value.toHex(); onValue(n.characteristic, n.value) }
    }

    override suspend fun close() {
        closed = true
        val ts = synchronized(transports) { transports.toList().also { transports.clear() } }
        ts.forEach { runCatching { it.close() } }
        gattLock.withLock { gatt?.close(); gatt = null; gattTried = false }
        if (_gattState.value != ChannelState.UNAVAILABLE) _gattState.value = ChannelState.CLOSED
        if (_rfcommState.value != ChannelState.UNAVAILABLE) _rfcommState.value = ChannelState.CLOSED
    }
}
