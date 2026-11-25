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
    val services: Set<UUID> = emptySet(),
    val sdp: Set<UUID> = emptySet(),
    val gattChannel: BluetoothTransport? = null,
    val rfcomm: Map<UUID, BluetoothTransport> = emptyMap(),
    val characteristics: Map<UUID, ByteArray> = emptyMap(),
    val adverts: Flow<ManufacturerAdvert> = emptyFlow(),
) : DeviceLink {
    var closed = false
    val opened = mutableListOf<UUID>()
    val suggested = mutableListOf<Pair<String, String?>>()
    var openedWritable: Boolean? = null
    override val gattState: StateFlow<ChannelState> = MutableStateFlow(if (services.isEmpty()) ChannelState.UNAVAILABLE else ChannelState.CONFIRMED)
    override fun sdpUuids() = sdp
    override suspend fun gattServices() = services
    override suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray? = characteristics[characteristic]
    override suspend fun notifications(service: UUID, characteristic: UUID): Flow<ByteArray>? = null
    override suspend fun openGattChannel(service: UUID, notify: UUID?, write: UUID?) = gattChannel
    override suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport? { opened += uuid; openedWritable = writable; return rfcomm[uuid] }
    override suspend fun suggestEndpoint(address: String, modelIdHex: String?): Boolean { suggested += address to modelIdHex; return false }
    override fun manufacturerAdverts(companyId: Int): Flow<ManufacturerAdvert> = adverts
    override suspend fun close() { closed = true }
}
