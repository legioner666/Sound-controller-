package com.soniccontrol.core.player

/**
 * Pure sleep-timer arithmetic. The service owns the real timer; this keeps the rules testable:
 * one timer at a time, a new request replaces the old one, a non-positive duration cancels.
 */
class SleepTimer(private val clock: () -> Long) {
    private var endsAt: Long? = null
    private var totalMillis = 0L

    /** Returns the delay to schedule, or null when the timer was cancelled. */
    fun set(minutes: Int): Long? {
        if (minutes <= 0) { endsAt = null; totalMillis = 0; return null }
        val d = minutes.coerceAtMost(MAX_MINUTES) * 60_000L
        totalMillis = d
        endsAt = clock() + d
        return d
    }

    fun cancel() { endsAt = null; totalMillis = 0 }

    /** Delay until the fade starts (90 % of the time has passed), or null with no timer. */
    fun fadeStartDelayMillis(): Long? = endsAt?.let { (it - clock() - (totalMillis * FADE_FRACTION).toLong()).coerceAtLeast(0) }

    /**
     * Gain for the player's own volume: 1.0 during the first 90 % of the time, then a linear ramp down to 0.0 at the end.
     * This is the app player's volume only; the phone's system volume is never touched.
     */
    fun fadeFactor(): Float {
        val e = endsAt ?: return 1f
        val window = totalMillis * FADE_FRACTION
        if (window <= 0.0) return 1f
        return ((e - clock()) / window).toFloat().coerceIn(0f, 1f)
    }

    /** Milliseconds left, or null when no timer runs. Never negative. */
    fun remainingMillis(): Long? = endsAt?.let { (it - clock()).coerceAtLeast(0) }

    /** True exactly once when the time is up, then the timer is cleared. */
    fun consumeIfExpired(): Boolean {
        val e = endsAt ?: return false
        if (clock() < e) return false
        endsAt = null
        return true
    }

    companion object {
        const val MAX_MINUTES = 12 * 60
        /** The last 10 % of the time is the fade. */
        const val FADE_FRACTION = 0.10
    }
}
