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
    /** Long-interval safety-net polling (Fast Pair re-open, JBL mode GET). On in the app; tests with idle-waiting turn it on explicitly. */
    val periodicRefresh: Boolean = false,
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
    suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome

    /** Explicit user action from the Lab: identify the protocol variant with read-only queries. */
    suspend fun probe(): CommandOutcome
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
}

class DriverRegistry(private val drivers: List<HeadphoneDriver>, val fallback: HeadphoneDriver) {
    val all: List<HeadphoneDriver> get() = drivers + fallback

    val catalog: DeviceCatalog = DeviceCatalog(all.flatMap { it.catalog })

    fun resolve(identity: DeviceIdentity): Pair<HeadphoneDriver, DriverMatch> {
        val best = drivers.mapNotNull { d -> d.match(identity)?.let { d to it } }.maxByOrNull { it.second.score }
        return best ?: (fallback to (fallback.match(identity) ?: DriverMatch(0, null, "generic")))
    }
}
