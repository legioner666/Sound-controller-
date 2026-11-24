package com.soniccontrol.core.devices

/** Exponential back-off for automatic reconnection after an unexpected disconnect. */
class ReconnectPolicy(
    private val initialDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 30_000,
    val maxAttempts: Int = 8,
) {
    /** Delay before attempt number [attempt] (1-based), or null when giving up. */
    fun delayFor(attempt: Int): Long? {
        if (attempt < 1 || attempt > maxAttempts) return null
        val shift = (attempt - 1).coerceAtMost(20)
        return (initialDelayMillis shl shift).coerceAtMost(maxDelayMillis)
    }
}
