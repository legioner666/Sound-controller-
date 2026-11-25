package com.soniccontrol.core.player

/**
 * Pure sleep-timer arithmetic. The service owns the real timer; this keeps the rules testable:
 * one timer at a time, a new request replaces the old one, a non-positive duration cancels.
 */
class SleepTimer(private val clock: () -> Long) {
    private var endsAt: Long? = null

    /** Returns the delay to schedule, or null when the timer was cancelled. */
    fun set(minutes: Int): Long? {
        if (minutes <= 0) { endsAt = null; return null }
        val d = minutes.coerceAtMost(MAX_MINUTES) * 60_000L
        endsAt = clock() + d
        return d
    }

    fun cancel() { endsAt = null }

    /** Milliseconds left, or null when no timer runs. Never negative. */
    fun remainingMillis(): Long? = endsAt?.let { (it - clock()).coerceAtLeast(0) }

    /** True exactly once when the time is up, then the timer is cleared. */
    fun consumeIfExpired(): Boolean {
        val e = endsAt ?: return false
        if (clock() < e) return false
        endsAt = null
        return true
    }

    companion object { const val MAX_MINUTES = 12 * 60 }
}
