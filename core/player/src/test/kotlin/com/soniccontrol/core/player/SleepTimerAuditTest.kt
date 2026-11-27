package com.soniccontrol.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Regression tests for the independent audit of commit 2aae71c (TASK 013). */
class SleepTimerAuditTest {
    private var now = 50_000L

    // ---- changing the duration of a running timer ------------------------------------------------------------------

    @Test fun newDurationDuringTheFadeContinuesFromTheCurrentLevel() {
        val t = SleepTimer({ now }, SleepFadeSettings(percent = 50), userVolume = 0.8f)
        t.set(10)
        now += 7 * 60_000L + 30_000L                // halfway through the fade: 0.4
        val level = t.volume()
        assertEquals(0.4f, level, 0.001f)
        t.set(30)                                    // user picks another duration
        assertEquals("no jump back to 0.8", level, t.volume(), 0.0001f)
        now += 10 * 60_000L
        assertEquals("held until the new fade starts", level, t.volume(), 0.0001f)
        assertEquals(0.8f, t.userVolume, 0f)         // the user's own volume is unchanged (restored on the next start)
        now += 10 * 60_000L                          // 20 of 30 min: fade 15..30, a third done
        assertEquals(level * 2f / 3f, t.volume(), 0.002f)
    }

    @Test fun newDurationBeforeTheFadeKeepsTheUsersVolume() {
        val t = SleepTimer({ now }, SleepFadeSettings(), userVolume = 0.6f)
        t.set(30); now += 60_000L
        t.set(60)
        assertEquals(0.6f, t.volume(), 0f)
    }

    @Test fun firstStartUsesTheGivenCurrentLevelNeverAboveTheUsersVolume() {
        val t = SleepTimer({ now }, SleepFadeSettings(), userVolume = 0.7f)
        t.set(10, currentLevel = 0.3f)               // e.g. during the 1.5 s way back after a cancel
        assertEquals(0.3f, t.volume(), 0f)
        val u = SleepTimer({ now }, SleepFadeSettings(), userVolume = 0.5f)
        u.set(10, currentLevel = 0.9f)
        assertEquals(0.5f, u.volume(), 0f)
    }

    @Test fun cancelReturnsSmoothlyNotAtOnce() {
        val r = VolumeRestore(from = 0.1f, to = 0.8f, startAt = now)
        assertEquals(0.1f, r.volumeAt(now), 0f)
        var last = 0.1f; var t = now; var worst = 0f
        while (!r.isDone(t)) { t += 50; val v = r.volumeAt(t); worst = maxOf(worst, abs(v - last)); assertTrue(v >= last); last = v }
        assertEquals(0.8f, last, 0f)
        assertTrue("step per 50 ms: $worst", worst <= 0.7f / (VolumeRestore.DEFAULT_MILLIS / 50f) + 0.0001f)
        assertEquals(VolumeRestore.DEFAULT_MILLIS, t - now)
    }

    // ---- surviving a stopped process --------------------------------------------------------------------------------

    @Test fun snapshotRestoresTheSamePlan() {
        val a = SleepTimer({ now }, SleepFadeSettings(percent = 40, targetPercent = 10), userVolume = 0.9f)
        a.set(20); now += 14 * 60_000L; a.onManualVolume(0.5f)
        val snap = a.snapshot()!!
        // New process: fresh timer with the same saved settings and user volume.
        val b = SleepTimer({ now }, SleepFadeSettings(percent = 40, targetPercent = 10), userVolume = 0.5f)
        assertEquals(SleepTimerSnapshot.Verdict.RESTORE, snap.check(now, bootCount = 3, savedBootCount = 3))
        b.restore(snap)
        repeat(30) { now += 10_000L; assertEquals(a.volume(), b.volume(), 0.0001f); assertEquals(a.remainingMillis(), b.remainingMillis()) }
        now += 10 * 60_000L
        assertTrue(b.consumeIfExpired())
    }

    @Test fun snapshotVerdicts() {
        val s = SleepTimerSnapshot(startedAt = 1_000, endsAt = 61_000, totalMillis = 60_000, anchorAt = 1_000, anchorVolume = 1f)
        assertEquals(SleepTimerSnapshot.Verdict.RESTORE, s.check(30_000, 5, 5))
        assertEquals(SleepTimerSnapshot.Verdict.EXPIRED, s.check(61_000, 5, 5))
        assertEquals(SleepTimerSnapshot.Verdict.EXPIRED, s.check(9_000_000, 5, 5))
        assertEquals("rebooted: other boot count", SleepTimerSnapshot.Verdict.DISCARD, s.check(30_000, 6, 5))
        assertEquals("rebooted: monotonic clock behind the start", SleepTimerSnapshot.Verdict.DISCARD, s.check(500, -1, -1))
        assertEquals(SleepTimerSnapshot.Verdict.DISCARD, s.copy(endsAt = 99).check(30_000, 5, 5))
        assertEquals(SleepTimerSnapshot.Verdict.DISCARD, s.copy(anchorAt = 70_000).check(30_000, 5, 5))
        assertEquals(SleepTimerSnapshot.Verdict.DISCARD, s.copy(totalMillis = 0, endsAt = 1_000).check(30_000, 5, 5))
    }

    @Test fun noSnapshotWithoutATimer() {
        val t = SleepTimer({ now })
        assertNull(t.snapshot())
        t.set(5); t.cancel()
        assertNull(t.snapshot())
        t.set(5); now += 5 * 60_000L; assertTrue(t.consumeIfExpired())
        assertNull("ended timer is not saved", t.snapshot())
    }

    // ---- monotonic time ---------------------------------------------------------------------------------------------

    @Test fun onlyTheInjectedMonotonicClockCounts() {
        // The service passes SystemClock.elapsedRealtime; the timer never reads the wall clock itself.
        val t = SleepTimer({ now })
        t.set(10)
        val before = t.remainingMillis()
        Thread.sleep(5)                                  // real (wall) time passes, monotonic test clock does not
        assertEquals(before, t.remainingMillis())
        now += 1_000
        assertEquals(before!! - 1_000, t.remainingMillis())
    }

    // ---- durations 1, 5, 10 and typed by hand -----------------------------------------------------------------------

    @Test fun shortDurationsWork() {
        for (m in listOf(1, 5, 10)) {
            val t = SleepTimer({ now })
            assertEquals(m * 60_000L, t.set(m))
            assertEquals("default fade = last 10 %", m * 60_000L * 9 / 10, t.fadeStartDelayMillis())
            now += m * 60_000L
            assertTrue(t.consumeIfExpired())
        }
    }

    @Test fun typedMinutes() {
        assertEquals(1, SleepTimer.parseMinutes("1"))
        assertEquals(45, SleepTimer.parseMinutes(" 45 "))
        assertEquals(720, SleepTimer.parseMinutes("720"))
        listOf("", "0", "721", "-5", "1.5", "abc", "99999", "１２").forEach { assertNull(it, SleepTimer.parseMinutes(it)) }
    }

    // ---- access to the app's own commands ---------------------------------------------------------------------------

    @Test fun onlyTheAppItselfGetsTimerAndBassCommands() {
        assertTrue(ControllerAccess.isInternal(10123, "com.soniccontrol", 10123, "com.soniccontrol"))
        assertFalse("other app", ControllerAccess.isInternal(10200, "com.other.player", 10123, "com.soniccontrol"))
        assertFalse("system UI", ControllerAccess.isInternal(10050, "com.android.systemui", 10123, "com.soniccontrol"))
        assertFalse("spoofed package name", ControllerAccess.isInternal(10200, "com.soniccontrol", 10123, "com.soniccontrol"))
        assertFalse("shared uid, other package", ControllerAccess.isInternal(10123, "com.other", 10123, "com.soniccontrol"))
    }
}
