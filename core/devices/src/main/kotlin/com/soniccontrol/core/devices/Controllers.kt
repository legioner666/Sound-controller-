package com.soniccontrol.core.devices

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class BluetoothAvailability { READY, OFF, NO_PERMISSION, UNAVAILABLE }

enum class DiscoverySource { BONDED, BLE_SCAN, AUDIO_CONNECTED }

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int? = null,
    val bonded: Boolean = false,
    val audioConnected: Boolean = false,
    val sources: Set<DiscoverySource> = emptySet(),
    val catalogEntry: CatalogEntry? = null,
    val lastSeenMillis: Long = 0,
)

/**
 * Lifecycle of the driver session. [Active] only means the session runs; what is actually reachable
 * is in [LinkStatus]. The UI never shows "connected" from this alone.
 */
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Starting : ConnectionState
    data object Active : ConnectionState
    /** Waiting for Android to reconnect the headset, or retrying a failed start. */
    data class Reconnecting(val attempt: Int, val inMillis: Long) : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

data class CharacteristicInfo(
    val uuid: UUID,
    val name: String?,
    val properties: Set<String>,
    val lastValueHex: String? = null,
    val subscribed: Boolean = false,
)

data class ServiceInfo(val uuid: UUID, val name: String?, val characteristics: List<CharacteristicInfo>)

/** Whether the app can currently read/control the headset; independent of whether audio is connected. */
enum class ControlStatus { CONNECTED, RECONNECTING, UNAVAILABLE }

data class ActiveDevice(
    val identity: DeviceIdentity,
    val driverId: String,
    val driverName: String,
    val match: DriverMatch,
    val connection: ConnectionState,
    val headphone: HeadphoneState,
    val links: LinkStatus = LinkStatus(),
    /** When the newest [headphone] state reached the single source every screen reads (TASK 007 diagnostics). */
    val stateDeliveredMillis: Long? = null,
) {
    /** Never derived from [connection] alone: a session can be Active while every channel is down. */
    val controlStatus: ControlStatus get() = when {
        links.control == ChannelState.CONFIRMED || links.gatt == ChannelState.CONFIRMED || links.rfcomm == ChannelState.CONFIRMED -> ControlStatus.CONNECTED
        headphone.live.reconnect?.startsWith("gave up") == false || connection is ConnectionState.Reconnecting || connection == ConnectionState.Starting -> ControlStatus.RECONNECTING
        else -> ControlStatus.UNAVAILABLE
    }
}

/** Short user-facing result of an action; [success] is true only for confirmed results. */
data class ActionResult(val action: String, val outcome: CommandOutcome)

/** Device discovery and control, as seen by the UI. Implemented in the app module. */
interface HeadphoneController {
    val availability: StateFlow<BluetoothAvailability>
    val devices: StateFlow<List<DiscoveredDevice>>
    val scanning: StateFlow<Boolean>
    val active: StateFlow<ActiveDevice?>
    val catalog: DeviceCatalog
    val drivers: List<HeadphoneDriver>
    val results: SharedFlow<ActionResult>

    fun refreshAvailability()
    fun startScan()
    fun stopScan()
    fun connect(address: String)
    fun disconnect()
    fun refresh()
    fun setNoiseMode(mode: NoiseMode)
}

/** Read-only GATT inspection for the Lab. */
interface LabController {
    val services: StateFlow<List<ServiceInfo>>
    val sdpUuids: StateFlow<List<UUID>>
    fun discover()
    fun readAll()
    fun subscribeAll()
    /** Runs the active driver's read-only protocol probe. Requires experimental features. */
    fun probe()
    fun exportReport(): String

    /** LE endpoints found by scanning, ranked by how likely each is the headset's own GATT endpoint. */
    val endpoints: StateFlow<List<EndpointCandidate>>
    /** Endpoint the user chose manually (tried first on the next connection), or null. */
    val selectedEndpoint: StateFlow<String?>
    val attempts: StateFlow<List<ConnectionAttempt>>
    fun scanEndpoints()
    /** Chooses (or clears with null) the LE endpoint and reconnects the active headset with it. */
    fun selectEndpoint(address: String?)
    fun refreshAttempts()
}
