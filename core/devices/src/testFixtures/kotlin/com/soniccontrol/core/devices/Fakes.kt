package com.soniccontrol.core.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.UUID

/**
 * Transport whose replies are produced by [responder] for each written frame — a scripted headset.
 * Replies can be split into chunks to simulate fragmentation.
 */
class FakeTransport(var responder: (ByteArray) -> List<ByteArray> = { emptyList() }) : BluetoothTransport {
    val written = mutableListOf<ByteArray>()
    val stateFlow = MutableStateFlow<TransportState>(TransportState.Open)
    val inbox = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    var closed = false
    override val description = "fake"
    override val state: StateFlow<TransportState> = stateFlow
    override val incoming: SharedFlow<ByteArray> = inbox
    override var canWrite = true
    override suspend fun close() { closed = true; stateFlow.value = TransportState.Closed }
    override suspend fun write(bytes: ByteArray) {
        if (stateFlow.value != TransportState.Open) throw TransportException("closed")
        written += bytes
        responder(bytes).forEach { inbox.emit(it) }
    }
    /** Unsolicited frame from the headset. */
    suspend fun push(bytes: ByteArray) = inbox.emit(bytes)
}

class FakeLink(
    override val address: String = "00:11:22:33:44:55",
    override val name: String? = "Fake",
    var services: Set<UUID> = emptySet(),
    val sdp: Set<UUID> = emptySet(),
    var gattChannel: BluetoothTransport? = null,
    val rfcomm: Map<UUID, BluetoothTransport> = emptyMap(),
    val characteristics: Map<UUID, ByteArray> = emptyMap(),
    val adverts: Flow<ManufacturerAdvert> = emptyFlow(),
) : DeviceLink {
    var closed = false
    var l2cap: BluetoothTransport? = null
    val l2capRequests = mutableListOf<Int>()
    /** Emulates Android's "Client security clearance failed": the first request is refused and every later one is blocked. */
    var l2capSecurityFailure = false
    private var denied = false
    override val l2capDenied: Boolean get() = denied
    override suspend fun openL2cap(psm: Int, writable: Boolean): BluetoothTransport? {
        if (denied) return null
        l2capRequests += psm
        if (l2capSecurityFailure) { denied = true; return null }
        return l2cap
    }
    val opened = mutableListOf<UUID>()
    val suggested = mutableListOf<Pair<String, String?>>()
    var openedWritable: Boolean? = null
    val gattStateFlow = MutableStateFlow(if (services.isEmpty()) ChannelState.UNAVAILABLE else ChannelState.CONFIRMED)
    override val gattState: StateFlow<ChannelState> get() = gattStateFlow
    override fun sdpUuids() = sdp
    override suspend fun gattServices() = services
    override suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray? { reads++; return characteristics[characteristic] }
    val notifyFlows = mutableMapOf<UUID, Flow<ByteArray>>()
    override suspend fun notifications(service: UUID, characteristic: UUID): Flow<ByteArray>? = notifyFlows[characteristic]
    /** Scripted results of successive reconnect attempts (default: always fails). On success GATT becomes CONFIRMED again. */
    val reconnectResults = ArrayDeque<Boolean>()
    var reconnectCalls = 0
    var reads = 0
    override suspend fun reconnectGatt(): Boolean {
        reconnectCalls++
        val ok = reconnectResults.removeFirstOrNull() ?: false
        gattStateFlow.value = if (ok) ChannelState.CONFIRMED else ChannelState.FAILED
        return ok
    }
    override val activeSubscriptions: Int get() = notifyFlows.size
    override suspend fun openGattChannel(service: UUID, notify: UUID?, write: UUID?) = gattChannel
    /** When set, every open asks the factory (a fresh transport per open, or null = failed open). */
    var rfcommFactory: ((UUID) -> BluetoothTransport?)? = null
    override suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport? { opened += uuid; openedWritable = writable; return rfcommFactory?.invoke(uuid) ?: if (rfcommFactory != null) null else rfcomm[uuid] }
    override suspend fun suggestEndpoint(address: String, modelIdHex: String?): Boolean { suggested += address to modelIdHex; return false }
    override fun manufacturerAdverts(companyId: Int): Flow<ManufacturerAdvert> = adverts
    override suspend fun close() { closed = true }
}
