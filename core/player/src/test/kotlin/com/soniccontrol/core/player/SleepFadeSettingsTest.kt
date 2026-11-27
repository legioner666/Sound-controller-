package com.soniccontrol.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepFadeSettingsTest {

    @Test fun missingValuesGiveTheOldBehaviour() {
        val s = SleepFadeSettings.decode { null }
        assertEquals(SleepFadeSettings(), s)
        assertEquals(60_000L, s.fadeMillis(10 * 60_000L))
        assertEquals(0f, s.target, 0f)
    }

    @Test fun lastSettingsRoundTrip() {
        val s = SleepFadeSettings(enabled = false, mode = FadeLengthMode.MINUTES, percent = 35, minutes = 17, targetPercent = 25, lastTimerMinutes = 120)
        val stored = SleepFadeSettings.encode(s)
        assertEquals(s, SleepFadeSettings.decode { stored[it] })
    }

    @Test fun invalidStoredValuesAreClampedOrIgnored() {
        val stored = mapOf("fade_mode" to "SIDEWAYS", "fade_percent" to 0, "fade_minutes" to 999, "fade_target_percent" to -5, "fade_enabled" to "yes")
        val s = SleepFadeSettings.decode { stored[it] }
        assertEquals(FadeLengthMode.PERCENT, s.mode)
        assertEquals(SleepFadeSettings.MIN_PERCENT, s.percent)
        assertEquals(SleepFadeSettings.MAX_FADE_MINUTES, s.minutes)
        assertEquals(0, s.targetPercent)
        assertTrue(s.enabled)
    }

    @Test fun previewGraphMatchesTheTimer() {
        val settings = SleepFadeSettings(percent = 20, targetPercent = 10)
        val c = FadeCurve.preview(60 * 60_000L, 0.6f, settings)
        assertEquals(48 * 60_000L, c.rampStartAt)
        assertEquals(0.6f, c.volumeAt(0), 0f)
        assertEquals(0.6f, c.volumeAt(48 * 60_000L), 0f)
        assertEquals(0.35f, c.volumeAt(54 * 60_000L), 0.001f)
        assertEquals(0.1f, c.volumeAt(60 * 60_000L), 0.001f)
        // Same numbers as the running timer.
        var now = 0L
        val timer = SleepTimer({ now }, settings, 0.6f); timer.set(60)
        for (m in listOf(0, 30, 48, 51, 54, 59)) { now = m * 60_000L; assertEquals("m=$m", c.volumeAt(now), timer.volume(), 0.0001f) }
    }

    @Test fun graphSamplesCoverTheWholeTimerAndIncludeTheBend() {
        val c = FadeCurve.preview(30 * 60_000L, 1f, SleepFadeSettings(percent = 20))
        val pts = c.samples(10)
        assertEquals(0L, pts.first().first); assertEquals(30 * 60_000L, pts.last().first)
        assertTrue(pts.any { it.first == 24 * 60_000L && it.second == 1f })
        assertEquals(0f, pts.last().second, 0f)
        assertTrue(pts.zipWithNext().all { (a, b) -> b.second <= a.second })
    }
}
