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

    @Test fun firstNinetyPercentIsFullVolumeThenLinearFadeToZero() {
        t.set(10)                                   // 10 min: fade window = last 60 s
        assertEquals(1f, t.fadeFactor(), 0f)
        now += 9 * 60_000L - 1                      // just before 90 %
        assertEquals(1f, t.fadeFactor(), 0f)
        assertEquals(1L, t.fadeStartDelayMillis())
        now += 1
        assertEquals(1f, t.fadeFactor(), 0.001f)    // fade begins at 100 %
        now += 30_000L
        assertEquals(0.5f, t.fadeFactor(), 0.001f)  // halfway through the fade
        now += 29_000L
        assertEquals(1f / 60f, t.fadeFactor(), 0.001f)
        now += 1_000L
        assertEquals(0f, t.fadeFactor(), 0f)
        now += 5_000L
        assertEquals("never negative, never restored upward", 0f, t.fadeFactor(), 0f)
    }

    @Test fun factorIsMonotonicallyNonIncreasingAndRestoresOnlyByAnExplicitNewTimer() {
        t.set(20)
        var last = 1f
        repeat(1200) { now += 1_000L; val f = t.fadeFactor(); assertTrue("$f > $last", f <= last); last = f }
        assertEquals(0f, last, 0f)
        t.set(20)                                   // explicit new timer = manual action
        assertEquals(1f, t.fadeFactor(), 0f)
        t.cancel(); assertEquals(1f, t.fadeFactor(), 0f)
        assertNull(t.fadeStartDelayMillis())
    }

    @Test fun fadeStartDelayIsNinetyPercentOfTheTime() {
        t.set(30)
        assertEquals((30 * 60_000L * 0.9).toLong(), t.fadeStartDelayMillis())
    }
}
