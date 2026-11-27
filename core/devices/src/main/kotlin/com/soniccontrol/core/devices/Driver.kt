package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Everything known about a device before a driver talks to it. Gathered without sending any vendor command. */
data class DeviceIdentity(
    val address: String,
    val name: String?,
    val sdpUuids: Set<UUID> = emptySet(),
    /** Google Fast Pair model id from the BLE advertisement, if seen (e.g. "71f20a"). */
    val fastPairModelId: String? = null,
)

data class DriverMatch(val score: Int, val entry: CatalogEntry?, val reason: String)

class DriverEnvironment(
    val scope: CoroutineScope,
    val experimentalEnabled: StateFlow<Boolean>,
    /** Listen to the Fast Pair Message Stream for battery (read-only). */
    val fastPairListening: StateFlow<Boolean>,
    val sink: DiagnosticSink,
    val clock: () -> Long,
    /**
     * Where a session sends "refresh me now" (a Bluetooth notification hinted at a change). In the app this is
     * [AdaptiveRefreshCoordinator.requestNow], so every refresh goes through the one loop; null = the session refreshes itself.
     */
    val refreshRequests: ((String) -> Unit)? = null,
    /** The single gate for automatic connection attempts (budget + circuit breaker), shared by all sessions of the process. */
    val coordinator: ConnectionCoordinator = ConnectionCoordinator(clock),
)

/** Liveness of the state itself (TASK 007): when data last arrived, why updates may have stopped, what recovery is doing. */
data class LiveInfo(
    val lastRxMillis: Long? = null,
    val stateUpdatedMillis: Long? = null,
    val subscriptions: Int = 0,
    /** Null when everything is running; otherwise a short English reason (shown in Lab/diagnostics). */
    val stopReason: String? = null,
    /** Null = idle; e.g. "attempt 2/5" or "gave up". */
    val reconnect: String? = null,
    val reconnects: Int = 0,
)

data class HeadphoneState(
    val displayName: String,
    val catalogEntry: CatalogEntry? = null,
    val capabilities: DeviceCapabilities = DeviceCapabilities.UNIVERSAL,
    val battery: BatteryStatus? = null,
    val noise: NoiseStatus? = null,
    /** Modes this device can be switched to, in display order. */
    val noiseModes: List<NoiseMode> = emptyList(),
    val firmware: String? = null,
    /** From the Fast Pair Message Stream, when the headset provides it. Null = unknown, never a guess. */
    val remainingMinutes: Int? = null,
    val active: ActiveComponents? = null,
    /** When and from where [active] was last reported; independent of the battery timestamps. */
    val activeUpdatedMillis: Long? = null,
    val activeSource: DataSource? = null,
    val modelId: String? = null,
    val fastPair: FastPairCapabilities? = null,
    /** Short, human-readable protocol identification, shown in the Lab. */
    val protocol: String = "",
    /** Technical notes for the Lab (English, not localised). */
    val notes: List<String> = emptyList(),
    /** Localised notices for the user. */
    val notices: Set<Notice> = emptySet(),
    /** Vendor control channel. CONFIRMED only after a fully validated frame from the headset. */
    val control: ChannelState = ChannelState.UNAVAILABLE,
    val busy: Boolean = false,
    val live: LiveInfo = LiveInfo(),
    /** Touch-control protocol of this model, or null when there is none (TASK 014). */
    val gestureSupport: GestureSupport? = null,
    /** Last touch controls read from the headset (validated report only). */
    val gestures: GestureState? = null,
    /** Noise-control levels this model accepts, or null. */
    val noiseLevelSupport: NoiseLevelSupport? = null,
    /** Read-only settings decoded from validated reports, newest per setting. */
    val settings: Map<DeviceSetting, SettingReading> = emptyMap(),
)

/** A live, driver-owned control session for one connected device. */
interface DriverSession {
    val driverId: String
    val state: StateFlow<HeadphoneState>

    /** Opens channels and reads state. Must not send anything that changes the device. */
    suspend fun start()
    suspend fun stop()

    /** Re-reads battery and modes. */
    suspend fun refresh(): CommandOutcome

    /**
     * One scheduled refresh, called only by [AdaptiveRefreshCoordinator]. Read-only; never overlaps with itself; cheap sources
     * first. Default: nothing to do.
     */
    suspend fun refreshNow(reason: String) {}
    suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome

    /** Explicit user action from the Lab: identify the protocol variant with read-only queries. */
    suspend fun probe(): CommandOutcome

    /** Reads the touch controls of both earbuds (read-only). Default: this device has no gesture protocol. */
    suspend fun readGestures(): CommandOutcome = CommandOutcome.Unsupported("no touch-control protocol for this device")

    /**
     * Changes one earbud to [layout] and reads both earbuds back. Confirmed only when the headset then reports the requested
     * layout on [side] and the other earbud unchanged; anything else is a failure with the reported state kept.
     */
    suspend fun setGestureLayout(side: EarSide, layout: GestureLayout): CommandOutcome =
        CommandOutcome.Unsupported("no touch-control protocol for this device")

    /** Changes the strength of ANC or Ambient Aware and reads it back. */
    suspend fun setNoiseLevel(kind: NoiseLevelKind, level: Int): CommandOutcome = CommandOutcome.Unsupported("no level control for this device")

    /** Lab: reads the model's read-only settings (EQ preset, Smart Audio, levels...). Never writes. */
    suspend fun readSettings(): CommandOutcome = CommandOutcome.Unsupported("no readable settings for this device")
}

/** One manufacturer family. New models are added to [catalog] and, if needed, to the driver's command table. */
interface HeadphoneDriver {
    val id: String
    val brand: Brand
    val displayName: String
    val catalog: List<CatalogEntry>

    /** Score 0..100 that this driver should handle [identity]; null = not ours. */
    fun match(identity: DeviceIdentity): DriverMatch?

    fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession

    /** Short technical label for a received frame (Lab timeline, HCI import), or null when it is not this driver's protocol. */
    fun describeFrame(frame: ByteArray): String? = null

    /** True for frames that may be touch events of this protocol (meaning not confirmed): counted in Lab experiments only. */
    fun isTouchCandidate(frame: ByteArray): Boolean = false
}

class DriverRegistry(private val drivers: List<HeadphoneDriver>, val fallback: HeadphoneDriver) {
    val all: List<HeadphoneDriver> get() = drivers + fallback

    val catalog: DeviceCatalog = DeviceCatalog(all.flatMap { it.catalog })

    fun resolve(identity: DeviceIdentity): Pair<HeadphoneDriver, DriverMatch> {
        val best = drivers.mapNotNull { d -> d.match(identity)?.let { d to it } }.maxByOrNull { it.second.score }
        return best ?: (fallback to (fallback.match(identity) ?: DriverMatch(0, null, "generic")))
    }
}
