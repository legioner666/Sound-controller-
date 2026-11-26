package com.soniccontrol.core.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single gate for every Bluetooth connection attempt the app makes on its own (TASK 010).
 *
 * The v0.8.12 field log (4 h 17 min) showed 321 RFCOMM opens, 20 failed GATT reconnects and recovery that stopped for good
 * after one cycle. Every automatic open now asks this coordinator first:
 *
 * - **Budget:** at most [Limits.perHour] opens of each kind in any rolling hour, so a flood of events or a loop can never
 *   open sockets without bound.
 * - **Circuit breaker:** after [Limits.failuresToOpen] failures in a row a kind is OPEN (no attempts). It becomes HALF_OPEN
 *   (one trial attempt) after [Limits.cooldownMillis] or at once on a relevant event ([onRelevantEvent]: audio profile
 *   connected, Bluetooth switched on, the app opened, data received). A success closes it; a failed trial re-opens it.
 *
 * Pure Kotlin and clock-driven, so 12-hour runs are simulated in unit tests. Thread-safe; all state is bounded.
 */
class ConnectionCoordinator(
    private val clock: () -> Long,
    private val limits: Map<Kind, Limits> = DEFAULT_LIMITS,
) {
    enum class Kind { RFCOMM, L2CAP, GATT }
    enum class Breaker { CLOSED, OPEN, HALF_OPEN }

    data class Limits(val perHour: Int, val failuresToOpen: Int, val cooldownMillis: Long)

    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
    }

    data class KindState(
        val breaker: Breaker = Breaker.CLOSED,
        val consecutiveFailures: Int = 0,
        val opensLastHour: Int = 0,
        val attempts: Long = 0,
        val successes: Long = 0,
        val failures: Long = 0,
        val denied: Long = 0,
        val openedAtMillis: Long? = null,
    )

    data class Snapshot(val kinds: Map<Kind, KindState>, val lastWakeReason: String?, val lastWakeMillis: Long?, val wakes: Long)

    private class Mutable(var state: KindState = KindState(), val opens: ArrayDeque<Long> = ArrayDeque())

    private val lock = Any()
    private val kinds = Kind.entries.associateWith { Mutable() }
    private var lastWakeReason: String? = null
    private var lastWakeMillis: Long? = null
    private var wakes = 0L

    private val _snapshot = MutableStateFlow(snapshotLocked())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    /** Increments on every relevant event; a paused recovery waits for this to change or for the cooldown. */
    private val _wake = MutableStateFlow(0L)
    val wake: StateFlow<Long> = _wake.asStateFlow()

    private fun limitsOf(kind: Kind) = limits[kind] ?: DEFAULT_LIMITS.getValue(kind)

    /** Asks for one attempt of [kind]. An Allowed decision counts against the hourly budget even if the open then fails. */
    fun tryAcquire(kind: Kind): Decision = synchronized(lock) {
        val m = kinds.getValue(kind)
        val l = limitsOf(kind)
        val now = clock()
        prune(m, now)
        if (m.state.breaker == Breaker.OPEN) {
            val since = m.state.openedAtMillis ?: now
            if (now - since >= l.cooldownMillis) m.state = m.state.copy(breaker = Breaker.HALF_OPEN)
        }
        val decision = when {
            m.state.breaker == Breaker.OPEN -> Decision.Denied("circuit open after ${m.state.consecutiveFailures} failures; waiting for a Bluetooth event or the cooldown")
            m.opens.size >= l.perHour -> Decision.Denied("hourly budget of ${l.perHour} ${kind.name} opens used")
            else -> Decision.Allowed
        }
        if (decision is Decision.Allowed) {
            m.opens.addLast(now)
            m.state = m.state.copy(attempts = m.state.attempts + 1)
        } else {
            m.state = m.state.copy(denied = m.state.denied + 1)
        }
        publishLocked()
        decision
    }

    fun reportSuccess(kind: Kind) = synchronized(lock) {
        val m = kinds.getValue(kind)
        m.state = m.state.copy(breaker = Breaker.CLOSED, consecutiveFailures = 0, successes = m.state.successes + 1, openedAtMillis = null)
        publishLocked()
    }

    fun reportFailure(kind: Kind) = synchronized(lock) {
        val m = kinds.getValue(kind)
        val l = limitsOf(kind)
        val failures = m.state.consecutiveFailures + 1
        val open = m.state.breaker == Breaker.HALF_OPEN || failures >= l.failuresToOpen
        m.state = m.state.copy(
            consecutiveFailures = failures,
            failures = m.state.failures + 1,
            breaker = if (open) Breaker.OPEN else m.state.breaker,
            openedAtMillis = if (open) clock() else m.state.openedAtMillis,
        )
        publishLocked()
    }

    /** A relevant event: every OPEN breaker gets one trial attempt and paused recoveries wake up. */
    fun onRelevantEvent(reason: String) = synchronized(lock) {
        for (m in kinds.values) if (m.state.breaker == Breaker.OPEN) m.state = m.state.copy(breaker = Breaker.HALF_OPEN)
        lastWakeReason = reason
        lastWakeMillis = clock()
        wakes++
        _wake.value = wakes
        publishLocked()
    }

    fun breaker(kind: Kind): Breaker = synchronized(lock) { kinds.getValue(kind).state.breaker }

    /** Milliseconds until an OPEN breaker of [kind] allows a trial on its own; 0 when not open. */
    fun cooldownRemaining(kind: Kind): Long = synchronized(lock) {
        val m = kinds.getValue(kind)
        if (m.state.breaker != Breaker.OPEN) return 0
        val since = m.state.openedAtMillis ?: return 0
        (limitsOf(kind).cooldownMillis - (clock() - since)).coerceAtLeast(0)
    }

    private fun prune(m: Mutable, now: Long) {
        while (m.opens.isNotEmpty() && now - m.opens.first() >= HOUR) m.opens.removeFirst()
    }

    private fun publishLocked() { _snapshot.value = snapshotLocked() }

    private fun snapshotLocked(): Snapshot {
        val now = clock()
        return Snapshot(
            kinds = kinds.mapValues { (_, m) -> prune(m, now); m.state.copy(opensLastHour = m.opens.size) },
            lastWakeReason = lastWakeReason, lastWakeMillis = lastWakeMillis, wakes = wakes,
        )
    }

    companion object {
        const val HOUR = 60 * 60_000L
        val DEFAULT_LIMITS = mapOf(
            Kind.RFCOMM to Limits(perHour = 40, failuresToOpen = 4, cooldownMillis = 10 * 60_000L),
            Kind.L2CAP to Limits(perHour = 3, failuresToOpen = 3, cooldownMillis = 60 * 60_000L),
            Kind.GATT to Limits(perHour = 20, failuresToOpen = 5, cooldownMillis = 10 * 60_000L),
        )
    }
}
