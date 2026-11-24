package com.soniccontrol.core.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

sealed interface TransportState {
    data object Closed : TransportState
    data object Opening : TransportState
    data object Open : TransportState
    data class Failed(val reason: String) : TransportState
}

/**
 * A byte channel to a headset's vendor protocol: a GATT characteristic pair or an RFCOMM socket.
 * [incoming] carries raw chunks as received; drivers reassemble frames themselves.
 */
interface BluetoothTransport {
    val description: String
    val state: StateFlow<TransportState>
    val incoming: SharedFlow<ByteArray>

    /** False for listen-only channels; [write] then always throws. */
    val canWrite: Boolean

    suspend fun close()

    /** Throws [TransportException] when the bytes could not be handed to the Bluetooth stack. */
    suspend fun write(bytes: ByteArray)
}

private val UNKNOWN_CHANNEL: StateFlow<ChannelState> = kotlinx.coroutines.flow.MutableStateFlow(ChannelState.UNAVAILABLE)

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ManufacturerAdvert(val address: String, val rssi: Int, val companyId: Int, val data: ByteArray, val timestampMillis: Long) {
    override fun equals(other: Any?) = other is ManufacturerAdvert && address == other.address && timestampMillis == other.timestampMillis &&
        data.contentEquals(other.data)
    override fun hashCode() = address.hashCode() * 31 + data.contentHashCode()
}

/**
 * What a driver may ask of one physical device. Implemented over the Android Bluetooth APIs in core:bluetooth
 * and by fakes in tests. Every method is safe to call when the channel does not exist: it returns null/empty.
 */
interface DeviceLink {
    val address: String
    val name: String?

    /** GATT connection state (CONFIRMED = services discovered). */
    val gattState: StateFlow<ChannelState> get() = UNKNOWN_CHANNEL

    /** Aggregate state of RFCOMM channels opened through this link. */
    val rfcommState: StateFlow<ChannelState> get() = UNKNOWN_CHANNEL

    /** Classic SDP service records (cached by Android). Never triggers a write to the device. */
    fun sdpUuids(): Set<UUID>

    /** Connects GATT over LE if needed and discovers services. Empty when the device has no reachable GATT server. */
    suspend fun gattServices(): Set<UUID>

    /** Standard GATT read. */
    suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray?

    /** GATT notifications of one characteristic (enables the CCCD). Null when not available. */
    suspend fun notifications(service: UUID, characteristic: UUID): Flow<ByteArray>?

    /**
     * Vendor channel over GATT. When [notify]/[write] are null the first notify/indicate and
     * write/write-without-response characteristic of [service] are used.
     */
    suspend fun openGattChannel(service: UUID, notify: UUID? = null, write: UUID? = null): BluetoothTransport?

    /** RFCOMM channel located by SDP UUID. [writable] false opens a listen-only transport. */
    suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport?

    /** Passive BLE advertisements carrying manufacturer data of [companyId] (used for AirPods battery). */
    fun manufacturerAdverts(companyId: Int): Flow<ManufacturerAdvert>

    /** Releases every channel this link opened. Safe to call more than once. */
    suspend fun close() {}
}
