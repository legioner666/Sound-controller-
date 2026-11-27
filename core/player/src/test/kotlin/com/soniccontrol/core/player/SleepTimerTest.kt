package com.soniccontrol.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SleepTimerTest {
    private var now = 1_000L
    private val t = SleepTimer({ now })

    private fun maxStep(timer: SleepTimer, untilMillis: Long, stepMillis: Long = 250): Float {
        var last = timer.volume(); var worst = 0f
        while (now < untilMillis) { now += stepMillis; val v = timer.volume(); worst = maxOf(worst, abs(v - last)); last = v }
        return worst
    }

    // ---- unchanged basics ---------------------------------------------------------------------------------------

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

    // ---- default = the behaviour before TASK 013 -------------------------------------------------------------------

    @Test fun defaultIsLastTenPercentDownToZero() {
        val d = SleepFadeSettings()
        assertTrue(d.enabled); assertEquals(FadeLengthMode.PERCENT, d.mode); assertEquals(10, d.percent); assertEquals(0, d.targetPercent)
        t.set(10)                                   // 10 min: fade window = last 60 s
        assertEquals(1f, t.volume(), 0f)
        now += 9 * 60_000L - 1                      // just before 90 %
        assertEquals(1f, t.volume(), 0f)
        assertEquals(1L, t.fadeStartDelayMillis())
        now += 1
        assertEquals(1f, t.volume(), 0.001f)        // fade begins at 100 %
        now += 30_000L
        assertEquals(0.5f, t.volume(), 0.001f)      // halfway through the fade
        now += 29_000L
        assertEquals(1f / 60f, t.volume(), 0.001f)
        now += 1_000L
        assertEquals(0f, t.volume(), 0f)
        now += 5_000L
        assertEquals("never negative, never restored upward", 0f, t.volume(), 0f)
    }

    @Test fun volumeNeverRisesOnItsOwnAndAnExplicitNewTimerRestores() {
        t.set(20)
        var last = t.volume()
        repeat(1200) { now += 1_000L; val f = t.volume(); assertTrue("$f > $last", f <= last); last = f }
        assertEquals(0f, last, 0f)
        t.set(20)                                   // explicit new timer = manual action
        assertEquals(1f, t.volume(), 0f)
        t.cancel(); assertEquals(1f, t.volume(), 0f)
        assertNull(t.fadeStartDelayMillis())
    }

    // ---- TASK 013 ---------------------------------------------------------------------------------------------------

    @Test fun fadeStartsFromTheActualPlayerVolumeNotFromFull() {
        val timer = SleepTimer({ now }, SleepFadeSettings(), userVolume = 0.6f)
        timer.set(10)
        now += 9 * 60_000L
        assertEquals(0.6f, timer.volume(), 0.001f)
        now += 30_000L
        assertEquals(0.3f, timer.volume(), 0.001f)
        now += 30_000L
        assertEquals(0f, timer.volume(), 0.001f)
    }

    @Test fun lengthAsPercentFromFiveToHundred() {
        for (p in listOf(5, 20, 50, 100)) {
            now = 1_000L
            val timer = SleepTimer({ now }, SleepFadeSettings(percent = p))
            timer.set(60)
            assertEquals("p=$p", 60 * 60_000L * (100 - p) / 100, timer.fadeStartDelayMillis())
        }
        assertEquals(5, SleepFadeSettings(percent = 1).normalized().percent)
        assertEquals(100, SleepFadeSettings(percent = 300).normalized().percent)
        val all = SleepTimer({ now }, SleepFadeSettings(percent = 100), 0.8f)
        now = 0; all.set(10)
        assertEquals(0L, all.fadeStartDelayMillis())
        now += 5 * 60_000L
        assertEquals("100 %: fade over the whole time", 0.4f, all.volume(), 0.001f)
    }

    @Test fun lengthInMinutesIsCappedByTheTimer() {
        val s = SleepFadeSettings(mode = FadeLengthMode.MINUTES, minutes = 12)
        assertEquals(12 * 60_000L, s.fadeMillis(60 * 60_000L))
        assertEquals(10 * 60_000L, s.fadeMillis(10 * 60_000L))
        assertTrue(s.minutesCappedBy(10 * 60_000L)); assertFalse(s.minutesCappedBy(60 * 60_000L))
        val timer = SleepTimer({ now }, s)
        timer.set(60)
        assertEquals(48 * 60_000L, timer.fadeStartDelayMillis())
    }

    @Test fun endVolumeBetweenZeroAndTheCurrentVolume() {
        val timer = SleepTimer({ now }, SleepFadeSettings(targetPercent = 20), userVolume = 0.6f)
        timer.set(10)
        now += 10 * 60_000L - 1
        assertEquals(0.2f, timer.volume(), 0.002f)
        // A target above the volume the fade starts from is cut to it: nothing ever goes up.
        val high = SleepTimer({ now }, SleepFadeSettings(targetPercent = 90), userVolume = 0.5f)
        high.set(10); now += 10 * 60_000L - 1
        assertEquals(0.5f, high.volume(), 0.001f)
    }

    @Test fun fadeOffHoldsTheVolume() {
        val timer = SleepTimer({ now }, SleepFadeSettings(enabled = false), userVolume = 0.7f)
        timer.set(10)
        now += 10 * 60_000L - 1
        assertEquals(0.7f, timer.volume(), 0f)
        assertEquals(0L, SleepFadeSettings(enabled = false).fadeMillis(600_000))
    }

    @Test fun timerEndsWhateverTheEndVolume() {
        val timer = SleepTimer({ now }, SleepFadeSettings(targetPercent = 100), userVolume = 1f)
        timer.set(1)
        now += 60_000L
        assertTrue(timer.consumeIfExpired())
        val off = SleepTimer({ now }, SleepFadeSettings(enabled = false))
        off.set(1); now += 60_000L
        assertTrue(off.consumeIfExpired())
    }

    @Test fun manualVolumeChangeDuringTheFadeReplansFromTheNewLevelWithoutJumps() {
        val timer = SleepTimer({ now }, SleepFadeSettings(percent = 50), userVolume = 0.8f)
        now = 0; timer.set(10)                      // fade over the last 5 min: 0.8 → 0
        now = 7 * 60_000L + 30_000L                 // halfway: 0.4
        assertEquals(0.4f, timer.volume(), 0.001f)
        timer.onManualVolume(0.6f)                  // user turns it up a bit
        assertEquals("starts exactly at the new level", 0.6f, timer.volume(), 0.0001f)
        assertEquals(0.6f, timer.userVolume, 0f)
        val worst = maxStep(timer, 10 * 60_000L - 1)
        assertTrue("smooth after the change: max step $worst", worst < 0.002f)
        assertEquals(0f, timer.volume(), 0.002f)
        // Turned down instead: continues down from there.
        now = 0; timer.set(10); now = 8 * 60_000L
        timer.onManualVolume(0.1f)
        assertEquals(0.1f, timer.volume(), 0.0001f)
        now = 9 * 60_000L
        assertEquals(0.05f, timer.volume(), 0.001f)
    }

    @Test fun manualVolumeBeforeTheFadeIsTheNewStart() {
        val timer = SleepTimer({ now }, SleepFadeSettings(), userVolume = 1f)
        now = 0; timer.set(10)
        now = 2 * 60_000L; timer.onManualVolume(0.5f)
        now = 9 * 60_000L
        assertEquals(0.5f, timer.volume(), 0.001f)
        now = 9 * 60_000L + 30_000L
        assertEquals(0.25f, timer.volume(), 0.001f)
    }

    @Test fun settingsChangesWhileRunningApplyAtOnceWithoutJumps() {
        val timer = SleepTimer({ now }, SleepFadeSettings(percent = 50), userVolume = 1f)
        now = 0; timer.set(10)
        now = 6 * 60_000L                           // 1 min into a 5 min fade: 0.8
        val before = timer.volume()
        assertEquals(0.8f, before, 0.001f)
        timer.updateSettings(SleepFadeSettings(percent = 10, targetPercent = 30))   // shorter fade, higher end
        assertEquals("no jump on change", before, timer.volume(), 0.0001f)
        now = 8 * 60_000L
        assertEquals("holds until the new fade start", before, timer.volume(), 0.0001f)
        now = 9 * 60_000L + 30_000L
        assertEquals(0.55f, timer.volume(), 0.002f)  // halfway 0.8 → 0.3
        now = 10 * 60_000L
        assertEquals(0.3f, timer.volume(), 0.002f)

        // Turning the fade off mid-way keeps the current level (no jump up, no further fade).
        val t2 = SleepTimer({ now }, SleepFadeSettings(percent = 50))
        now = 0; t2.set(10); now = 7 * 60_000L + 30_000L
        val mid = t2.volume()
        t2.updateSettings(SleepFadeSettings(enabled = false))
        assertEquals(mid, t2.volume(), 0.0001f)
        now = 9 * 60_000L
        assertEquals(mid, t2.volume(), 0.0001f)
        // ...and back on: continues down from there.
        t2.updateSettings(SleepFadeSettings(enabled = true, percent = 50))
        assertEquals(mid, t2.volume(), 0.0001f)
        // Re-enabled at 9:00 with 1 min left: 0.5 → 0 over 60 s = about 0.2 % per 250 ms step.
        val worst = maxStep(t2, 10 * 60_000L - 1)
        assertTrue("smooth: $worst", worst < 0.0025f)
    }

    @Test fun defaultFadeHasNoAudibleSteps() {
        t.set(30)
        val worst = maxStep(t, 1_000L + 30 * 60_000L - 1)
        assertTrue("max step per 250 ms: $worst", worst <= 1f / (180_000f / 250f) + 0.0001f)
    }
}
