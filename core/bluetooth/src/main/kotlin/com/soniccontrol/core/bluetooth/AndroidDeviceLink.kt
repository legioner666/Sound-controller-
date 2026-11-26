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
import com.soniccontrol.core.devices.isEnded
import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    // Fast Pair stream first, GATT after it: GATT planning waits briefly so a reported LE address is the first candidate.
    @Volatile private var streamPending = false
    private val streamDone = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val endpointKnown = kotlinx.coroutines.CompletableDeferred<Unit>()

    override fun expectFastPairStream(expected: Boolean) { streamPending = expected; if (!expected) streamDone.complete(Unit) }

    private suspend fun awaitStreamHint() {
        if (!streamPending || endpointKnown.isCompleted) return
        kotlinx.coroutines.withTimeoutOrNull(4_000) { streamDone.await() }
        if (streamOpened && !endpointKnown.isCompleted) kotlinx.coroutines.withTimeoutOrNull(1_500) { endpointKnown.await() }
    }

    @Volatile private var streamOpened = false
    /** The LE device a GATT connection was actually made to (may differ from the classic [device]). */
    @Volatile private var gattDevice: BluetoothDevice? = null
    @Volatile private var streamEndpoint: String? = null
    @Volatile private var streamModel: String? = null

    /** Addresses this link has learned about, so exports can mask them. */
    val endpointAddresses: List<String> get() = listOfNotNull(streamEndpoint)

    override suspend fun suggestEndpoint(address: String, modelIdHex: String?): Boolean {
        if (closed || !MAC.matches(address)) return false
        val previous = streamEndpoint
        streamEndpoint = address.uppercase()
        endpointKnown.complete(Unit)
        streamModel = modelIdHex
        if (previous != null && previous != streamEndpoint) sink.log(DiagnosticEvent(clock(), EventCategory.CONNECTION, "headset reported a new LE address (rotation)", null, device.address))
        if (gatt?.connected?.value == true) return true // never swap an endpoint that already works
        gattTried = false
        return gatt() != null
    }

    @Volatile private var l2capSecurityDenied = false
    override val l2capDenied: Boolean get() = l2capSecurityDenied

    override suspend fun openL2cap(psm: Int, writable: Boolean): BluetoothTransport? = channelLock.withLock {
        if (l2capSecurityDenied) return@withLock null // a confirmed security refusal ends L2CAP for this session; authentication is never bypassed
        if (closed) return@withLock null
        // The PSM was read over GATT from this device, so the channel must go to the same LE endpoint, never the classic address.
        val target = gattDevice ?: return@withLock null
        val started = clock()
        var socket: android.bluetooth.BluetoothSocket? = null
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // connect() ignores coroutine cancellation; closing the socket is what aborts it.
            val guard = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                ?.invokeOnCompletion { cause -> if (cause != null) runCatching { socket?.close() } }
            try {
                // Google specifies a secure (encrypted) channel, so there is no insecure fallback here.
                val sk = target.createL2capChannel(psm)
                socket = sk
                val watchdog = scope.launch { kotlinx.coroutines.delay(8_000); runCatching { sk.close() } }
                try { sk.connect() } finally { watchdog.cancel() }
                currentCoroutineContext().ensureActive()
                Result.success(RfcommTransport(sk, java.util.UUID(0, psm.toLong()), writable, scope, sink, clock))
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { socket?.close() }; throw e
            } catch (e: java.io.IOException) {
                runCatching { socket?.close() }; Result.failure(e)
            } catch (e: SecurityException) {
                runCatching { socket?.close() }; Result.failure(e)
            } finally { guard?.dispose() }
        }
        val t = result.getOrNull()
        record(ConnectionAttempt("l2cap", "secure", "gatt-endpoint", target.address, GattClient.bondName(target.bondState), clock() - started, null, null,
            if (t != null) "OK" else RfcommTransport.classify(result.exceptionOrNull()?.message), if (t != null) "psm=$psm" else result.exceptionOrNull()?.message))
        if (t == null && result.exceptionOrNull()?.message?.contains("security", ignoreCase = true) == true) {
            l2capSecurityDenied = true
            sink.log(DiagnosticEvent(clock(), EventCategory.RFCOMM, "L2CAP refused by Android security clearance: no further L2CAP attempts this session", null, target.address))
        }
        t?.also { synchronized(transports) { transports.removeAll { x -> x.state.value.isEnded }; transports.add(it) } }
    }

    /**
     * An endpoint learned from the headset itself must expose Fast Pair (0xFE2C) and, when the stream told us the Model ID,
     * return exactly that Model ID. Anything unreadable or different is rejected (fail closed). The Model ID names a product,
     * not one physical unit, so a matching ID is necessary but not proof of identity; it is combined with the
     * stream-delivered address, which only the paired headset produced.
     */
    private suspend fun verify(c: GattClient, step: Step): String? {
        if (step.kind != "fast-pair-endpoint") return null
        val svc = c.services.firstOrNull { it.uuid == com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_SERVICE } ?: return "WRONG_DEVICE_NO_FAST_PAIR"
        val expected = streamModel ?: return "UNVERIFIED_NO_MODEL_ID"
        val ch = svc.getCharacteristic(com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_MODEL_ID) ?: return "UNVERIFIED_NO_MODEL_CHARACTERISTIC"
        val bytes = c.read(ch) ?: return "UNVERIFIED_MODEL_UNREADABLE"
        if (bytes.size != 3) return "UNVERIFIED_MODEL_LENGTH"
        val got = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
        return if (got.equals(expected, true)) null else "WRONG_DEVICE_MODEL_MISMATCH"
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
        return steps.distinctBy { Triple(it.device.address, it.transport, it.kind) } // bounded by the 28 s budget in gatt(), not by a count
    }

    private suspend fun gatt(): GattClient? { awaitStreamHint(); return gattLocked() }

    private suspend fun gattLocked(): GattClient? = channelLock.withLock { gattLock.withLock {
        if (closed) return@withLock null
        gatt?.let { if (it.connected.value) return@withLock it }
        gatt?.let {
            // The link dropped: release the dead client and subscriptions, then plan a fresh connection (recovery after GATT loss).
            runCatching { it.close() }
            gatt = null; gattTried = false; subscribed.clear()
        }
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
                gattDevice = step.device
                _gattState.value = ChannelState.CONFIRMED // services were discovered
                scope.launch { c.connected.first { !it }; if (gatt === c) _gattState.value = ChannelState.CLOSED }   // one-shot, ends with this client
                return@withLock c
            }
            c.close()
        }
        _gattState.value = ChannelState.FAILED
        null
    } }

    /**
     * Recovery after GATT loss (TASK 009). Always a fresh cycle: the old client and its subscriptions are closed, stale endpoint
     * knowledge (cached observations, the previously reported LE address) is dropped, a new scan runs, and every candidate is
     * verified again. Previously a failed first cycle left a "tried" flag set, so attempts 2..5 returned instantly without touching Bluetooth.
     */
    override suspend fun reconnectGatt(): Boolean {
        if (closed) return false
        channelLock.withLock { gattLock.withLock {
            if (gatt?.connected?.value == true) return true
            gatt?.let { runCatching { it.close() } }
            gatt = null; gattTried = false; subscribed.clear()
            _gattState.value = ChannelState.CLOSED
            val stale = streamEndpoint
            streamEndpoint = null
            scanner.invalidateObservations()
            sink.log(DiagnosticEvent(clock(), EventCategory.CONNECTION, "recovery: old GATT closed, endpoint cache invalidated${if (stale != null) " (stale LE address dropped)" else ""}", null, device.address))
        } }
        return gatt() != null
    }

    override val activeSubscriptions: Int get() = subscribed.size

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
        return GattChannelTransport(g, n, w, scope, sink, clock).also { synchronized(transports) { transports.removeAll { x -> x.state.value.isEnded }; transports += it } }
    }

    override suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport? = channelLock.withLock {
        if (closed) return@withLock null
        runCatching { adapter.cancelDiscovery() }
        val isFastPair = uuid == com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_MESSAGE_STREAM
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
        if (isFastPair) { streamOpened = t != null; streamDone.complete(Unit) }
        if (t == null) {
            // A failed listen-only Fast Pair stream is not the control channel and must not mark RFCOMM as failed.
            if (writable && !openRfcommUp()) _rfcommState.value = ChannelState.FAILED
            return@withLock null
        }
        synchronized(transports) { transports.removeAll { x -> x.state.value.isEnded }; transports.add(t) }
        if (writable) _rfcommState.value = ChannelState.OPEN
        // One-shot watcher (TASK 010): ends with the socket instead of collecting a dead StateFlow forever.
        if (writable) scope.launch {
            t.state.first { it != TransportState.Open && it != TransportState.Opening }
            if (!openRfcommUp()) _rfcommState.value = ChannelState.CLOSED
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

    /** Transports the link still holds open; for diagnostics (TASK 010 socket-leak check). */
    val openTransports: Int get() = synchronized(transports) { transports.count { !it.state.value.isEnded } }

    override suspend fun close() {
        closed = true
        val ts = synchronized(transports) { transports.toList().also { transports.clear() } }
        ts.forEach { runCatching { it.close() } }
        gattLock.withLock { gatt?.close(); gatt = null; gattTried = false }
        if (_gattState.value != ChannelState.UNAVAILABLE) _gattState.value = ChannelState.CLOSED
        if (_rfcommState.value != ChannelState.UNAVAILABLE) _rfcommState.value = ChannelState.CLOSED
    }
}
