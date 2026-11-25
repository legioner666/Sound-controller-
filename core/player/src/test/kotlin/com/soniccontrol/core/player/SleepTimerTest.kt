package com.soniccontrol.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerTest {
    private var now = 1_000L
    private val t = SleepTimer { now }

    @Test fun countsDownFiresOnceAndClears() {
        assertEquals(15 * 60_000L, t.set(15))
        now += 14 * 60_000L
        assertEquals(60_000L, t.remainingMillis()); assertFalse(t.consumeIfExpired())
        now += 61_000L
        assertEquals(0L, t.remainingMillis())
        assertTrue(t.consumeIfExpired()); assertFalse(t.consumeIfExpired()); assertNull(t.remainingMillis())
    }

    @Test fun newRequestReplacesAndZeroCancelsAndLimitApplies() {
        t.set(5); now += 60_000; t.set(30)
        assertEquals(30 * 60_000L, t.remainingMillis())
        assertNull(t.set(0)); assertNull(t.remainingMillis())
        assertEquals(SleepTimer.MAX_MINUTES * 60_000L, t.set(100_000))
        t.cancel(); assertNull(t.remainingMillis())
    }
}
