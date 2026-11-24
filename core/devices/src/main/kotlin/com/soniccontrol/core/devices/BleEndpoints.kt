package com.soniccontrol.core.devices

import java.util.UUID

/** One BLE advertiser seen during a scan. Holds only what is needed to recognise a headset's LE endpoint. */
data class BleObservation(
    val address: String,
    val name: String?,
    val rssi: Int,
    val serviceUuids: Set<UUID> = emptySet(),
    val manufacturerIds: Set<Int> = emptySet(),
    val hasFastPairData: Boolean = false,
    val connectable: Boolean = true,
    val lastSeenMillis: Long = 0,
)

/** GATT transport requested for one connection attempt. Mirrors BluetoothDevice.TRANSPORT_*. */
enum class GattTransport { LE, AUTO, BREDR }

/** One recorded connection attempt, exported in the diagnostics (addresses are hashed on export). */
data class ConnectionAttempt(
    val channel: String,
    val transport: String,
    val endpointKind: String,
    val address: String,
    val bondState: String,
    val durationMillis: Long,
    val status: Int?,
    val newState: Int?,
    val outcome: String,
    val detail: String? = null,
)

/** How an LE endpoint was matched to the headset, so the Lab can explain a choice. */
data class EndpointCandidate(val observation: BleObservation, val score: Int, val reasons: List<String>) {
    val auto: Boolean get() = score >= EndpointResolver.AUTO_THRESHOLD
}

/**
 * Finds the LE endpoint of a headset among scan results by a combination of signs, not by name alone.
 * Dual-mode earbuds frequently advertise under an address that differs from the classic one.
 * Only candidates that reach [AUTO_THRESHOLD] are connected automatically; the rest are offered in the Lab.
 */
object EndpointResolver {
    const val AUTO_THRESHOLD = 4

    fun rank(
        classicAddress: String,
        headsetName: String?,
        brandHints: Set<String>,
        observations: Collection<BleObservation>,
    ): List<EndpointCandidate> {
        val name = normalise(headsetName)
        return observations.mapNotNull { o ->
            var score = 0
            val why = ArrayList<String>()
            if (o.address.equals(classicAddress, ignoreCase = true)) { score += 4; why += "same-address" }
            val n = normalise(o.name)
            if (name.isNotEmpty() && n.isNotEmpty()) {
                when {
                    n == name -> { score += 4; why += "name-equal" }
                    n.removeSuffix("le").trim() == name || n.removePrefix("le").trim() == name -> { score += 4; why += "name-le-twin" }
                    n.contains(name) || name.contains(n) -> { score += 2; why += "name-partial" }
                }
            }
            if (n.isNotEmpty() && brandHints.any { n.contains(it.lowercase()) }) { score += 1; why += "brand-in-name" }
            if (o.hasFastPairData) { score += 1; why += "fast-pair-data" }
            val identity = score // signal strength alone never identifies a headset
            if (o.rssi > -65) { score += 1; why += "strong-signal" }
            if (!o.connectable) { score -= 3; why += "not-connectable" }
            if (identity <= 0 || score <= 0) null else EndpointCandidate(o, score, why)
        }.sortedWith(compareByDescending<EndpointCandidate> { it.score }.thenByDescending { it.observation.rssi })
    }

    private fun normalise(s: String?): String =
        s?.lowercase()?.replace(Regex("[^a-z0-9 ]"), " ")?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
}

/** Why the headphones cannot be controlled right now, derived from recorded attempts. Used for human-readable guidance. */
enum class ConnectionAdvice { NONE, WAITING, NO_LE_ENDPOINT, GATT_REFUSED, NO_SERVICES, CONTROL_UNAVAILABLE }

object ConnectionAdvisor {
    fun advise(attempts: List<ConnectionAttempt>, links: LinkStatus): ConnectionAdvice {
        if (links.control == ChannelState.CONFIRMED) return ConnectionAdvice.NONE
        if (links.gatt == ChannelState.OPENING || links.rfcomm == ChannelState.OPENING || links.control == ChannelState.OPENING) return ConnectionAdvice.WAITING
        val gatt = attempts.filter { it.channel == "gatt" }
        if (gatt.isNotEmpty() && gatt.none { it.outcome == "OK" }) {
            return when {
                gatt.all { it.outcome == "NO_CALLBACK_TIMEOUT" } -> ConnectionAdvice.NO_LE_ENDPOINT
                gatt.any { it.outcome.startsWith("STATUS_") } -> ConnectionAdvice.GATT_REFUSED
                gatt.any { it.outcome == "CONNECTED_NO_SERVICES" } -> ConnectionAdvice.NO_SERVICES
                else -> ConnectionAdvice.CONTROL_UNAVAILABLE
            }
        }
        return if (links.control == ChannelState.UNAVAILABLE || links.control == ChannelState.FAILED) ConnectionAdvice.CONTROL_UNAVAILABLE else ConnectionAdvice.WAITING
    }
}
