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

/**
 * Three independent questions (TASK 010): is audio connected ([LinkStatus.audio]), is telemetry arriving ([TelemetryStatus]),
 * can commands be sent ([ControlStatus])? A plain GATT link or a Fast Pair stream is telemetry, never control.
 */
enum class TelemetryStatus {
    /** Data (battery, active earbuds, notifications) arrived within [FRESH_MILLIS]. */
    LIVE,
    /** Data arrived once, but not recently: everything shown is last-known. */
    STALE,
    /** No data at all in this session. */
    NONE,
}

/** Whether the app can currently send commands to the headset; independent of audio and of telemetry. */
enum class ControlStatus {
    /** The vendor command channel was confirmed by a valid frame. */
    CONNECTED,
    RECONNECTING,
    UNAVAILABLE,
}

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
        links.control == ChannelState.CONFIRMED -> ControlStatus.CONNECTED
        headphone.live.reconnect?.let { !it.startsWith("paused") && !it.startsWith("gave up") } == true || connection is ConnectionState.Reconnecting || connection == ConnectionState.Starting -> ControlStatus.RECONNECTING
        else -> ControlStatus.UNAVAILABLE
    }

    /** Newest moment any telemetry arrived: received bytes, a battery reading or an active-components report. */
    val lastTelemetryMillis: Long?
        get() = listOfNotNull(headphone.live.lastRxMillis, headphone.activeUpdatedMillis, headphone.battery?.let { b ->
            listOfNotNull(b.left?.updatedMillis, b.right?.updatedMillis, b.case?.updatedMillis, b.single?.updatedMillis).maxOrNull()
        }).maxOrNull()

    fun telemetryStatus(now: Long): TelemetryStatus = when (val t = lastTelemetryMillis) {
        null -> TelemetryStatus.NONE
        else -> if (now - t <= FRESH_MILLIS) TelemetryStatus.LIVE else TelemetryStatus.STALE
    }

    /** Active earbuds as current state: null when unknown or older than [FRESH_MILLIS]. */
    fun currentActive(now: Long): ActiveComponents? =
        headphone.active?.takeIf { headphone.activeUpdatedMillis?.let { now - it in 0..FRESH_MILLIS } == true }
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

    /** TASK 014. Results arrive on [results]; the state shows only what the headset reported. */
    fun readGestures() {}
    /** Saves a backup first (reading the headset if needed), then changes one earbud; see [DriverSession.setGestureLayout]. */
    fun setGestureLayout(side: EarSide, layout: GestureLayout) {}
    /** Backup of the connected headset's touch controls, or null. */
    val gestureBackup: StateFlow<GestureBackup?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)
    fun restoreGestureBackup() {}
    fun setNoiseLevel(kind: NoiseLevelKind, level: Int) {}
    fun readSettings() {}
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
