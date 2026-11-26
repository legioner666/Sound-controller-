package com.soniccontrol.core.devices

import com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Mode
import com.soniccontrol.core.devices.AdaptiveRefreshCoordinator.Settings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 011 (owner decision): one refresh loop whose rhythm follows the app lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdaptiveRefreshCoordinatorTest {
    private class Recorder(val clock: () -> Long, val workMillis: Long = 0) {
        val ticks = mutableListOf<Pair<Long, String>>()
        var running = 0
        var maxConcurrent = 0
        suspend fun refresh(reason: String) {
            running++; maxConcurrent = maxOf(maxConcurrent, running)
            ticks += clock() to reason
            if (workMillis > 0) delay(workMillis)
            running--
        }
        /** Tick times strictly after [t]. */
        fun timesSince(t: Long) = ticks.filter { it.first > t }.map { it.first }
    }

    private fun TestScope.setup(mode: Mode = Mode.MAIN_SCREEN, workMillis: Long = 0): Pair<AdaptiveRefreshCoordinator, Recorder> {
        val clock = { testScheduler.currentTime }
        val c = AdaptiveRefreshCoordinator(backgroundScope, clock)
        val r = Recorder(clock, workMillis)
        c.setMode(mode)
        c.start()
        c.attach { r.refresh(it) }
        runCurrent()
        return c to r
    }

    @Test fun intervalsFollowTheOwnerDefaults() = runTest {
        val c = AdaptiveRefreshCoordinator(backgroundScope) { testScheduler.currentTime }
        assertNull("nothing attached: stopped", c.intervalMillis())
        c.attach { }
        c.setMode(Mode.MAIN_SCREEN); assertEquals(5_000L, c.intervalMillis())
        c.setMode(Mode.SECTION); assertEquals(10_000L, c.intervalMillis())
        c.setMode(Mode.BACKGROUND); assertEquals(60_000L, c.intervalMillis())
        c.setMode(Mode.LOCKED); assertEquals(60_000L, c.intervalMillis())
    }

    @Test fun settingsChangeTheIntervalsAndOnlyAllowedValuesAreAccepted() = runTest {
        val c = AdaptiveRefreshCoordinator(backgroundScope) { testScheduler.currentTime }
        c.attach { }
        c.setSettings(Settings(15, 300))
        c.setMode(Mode.MAIN_SCREEN); assertEquals(15_000L, c.intervalMillis())
        c.setMode(Mode.SECTION); assertEquals(30_000L, c.intervalMillis())
        c.setMode(Mode.LOCKED); assertEquals(300_000L, c.intervalMillis())
        c.setSettings(Settings(30, 30))
        c.setMode(Mode.SECTION); assertEquals("a section never polls slower than the background", 30_000L, c.intervalMillis())
        listOf(Settings::class to { Settings(7, 60) }, Settings::class to { Settings(5, 45) }).forEach { (_, make) ->
            assertTrue(runCatching { make() }.isFailure)
        }
    }

    @Test fun mainScreenTicksEveryFiveSecondsAfterAnImmediateFirstRefresh() = runTest {
        val (_, r) = setup(Mode.MAIN_SCREEN)
        assertEquals(listOf(0L to "connected"), r.ticks)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(13, r.ticks.size)
        assertTrue(r.ticks.map { it.first }.zipWithNext().all { (a, b) -> b - a == 5_000L })
    }

    @Test fun sectionTicksEveryTenSeconds() = runTest {
        val (c, r) = setup(Mode.MAIN_SCREEN)
        c.setMode(Mode.SECTION); runCurrent()
        val t0 = testScheduler.currentTime
        advanceTimeBy(60_000); runCurrent()
        val times = r.timesSince(t0)
        assertEquals("foreground → foreground is no reason for an extra refresh", 6, times.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a == 10_000L })
    }

    @Test fun goingToTheBackgroundSlowsDownToSixtySecondsWithoutAnExtraRefresh() = runTest {
        val (c, r) = setup(Mode.MAIN_SCREEN)
        advanceTimeBy(2_000); runCurrent()
        c.setMode(Mode.BACKGROUND); runCurrent()
        val t0 = testScheduler.currentTime
        advanceTimeBy(59_000); runCurrent()
        assertTrue("no tick in the first 59 s: ${r.timesSince(t0)}", r.timesSince(t0).isEmpty())
        advanceTimeBy(5 * 60_000); runCurrent()
        assertEquals(listOf(60_000L, 120_000L, 180_000L, 240_000L, 300_000L).map { it + t0 }, r.timesSince(t0))
    }

    @Test fun lockedScreenUsesTheBackgroundIntervalAndUnlockingIntoTheBackgroundAddsNoRefresh() = runTest {
        val (c, r) = setup(Mode.BACKGROUND)
        c.setMode(Mode.LOCKED); runCurrent()
        val t0 = testScheduler.currentTime
        advanceTimeBy(120_000); runCurrent()
        assertEquals(2, r.timesSince(t0).size)
        val t1 = testScheduler.currentTime
        c.setMode(Mode.BACKGROUND); runCurrent()
        assertTrue(r.timesSince(t1).isEmpty())
    }

    @Test fun returningToTheForegroundRefreshesImmediately() = runTest {
        for (from in listOf(Mode.BACKGROUND, Mode.LOCKED)) for (to in listOf(Mode.MAIN_SCREEN, Mode.SECTION)) {
            val (c, r) = setup(from)
            advanceTimeBy(30_000); runCurrent()
            val t0 = testScheduler.currentTime
            c.setMode(to); runCurrent()
            assertEquals("$from → $to", listOf(t0 to "foreground"), r.ticks.filter { it.first >= t0 })
            val next = if (to == Mode.MAIN_SCREEN) 5_000L else 10_000L
            advanceTimeBy(next); runCurrent()
            assertEquals("$from → $to: the interval restarts after the immediate refresh", listOf(t0 + next), r.timesSince(t0))
            c.close()
        }
    }

    @Test fun aNotificationRefreshesAtOnceAndRestartsTheTimer() = runTest {
        val (c, r) = setup(Mode.BACKGROUND)
        advanceTimeBy(40_000); runCurrent()
        c.requestNow("notification"); runCurrent()
        assertEquals(40_000L to "notification", r.ticks.last())
        advanceTimeBy(59_000); runCurrent()
        assertEquals("no timed refresh right behind the immediate one", 40_000L, r.ticks.last().first)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(100_000L to "interval", r.ticks.last())
    }

    @Test fun refreshesNeverOverlapAndAFloodCollapses() = runTest {
        val (c, r) = setup(Mode.MAIN_SCREEN, workMillis = 3_000)
        repeat(200) { c.requestNow("flood $it"); advanceTimeBy(10) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals("never two refreshes at once", 1, r.maxConcurrent)
        // 62 s of 3-s refreshes with nothing in between could not exceed ~21; a flood that queued would give 200+.
        assertTrue("flood collapsed: ${r.ticks.size}", r.ticks.size <= 25)
        assertTrue("no duplicate requests at the same moment", r.ticks.map { it.first }.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun disconnectStopsPollingAndReconnectResumes() = runTest {
        val (c, r) = setup(Mode.MAIN_SCREEN)
        c.detach(); runCurrent()
        val t0 = testScheduler.currentTime
        c.requestNow("stray notification")
        advanceTimeBy(10 * 60_000); runCurrent()
        assertTrue("nothing while disconnected: ${r.timesSince(t0)}", r.timesSince(t0).isEmpty())
        assertNull(c.status.value.intervalMillis)
        val r2 = Recorder({ testScheduler.currentTime })
        c.attach { r2.refresh(it) }; runCurrent()
        assertEquals("connected", r2.ticks.single().second)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, r2.ticks.size)
        assertTrue("the old target is never called again", r.timesSince(t0).isEmpty())
    }

    @Test fun sleepTimerStopsEverythingUntilTheAppIsOpenedAgain() = runTest {
        val (c, r) = setup(Mode.MAIN_SCREEN)
        c.shutdown(); runCurrent()
        val t0 = testScheduler.currentTime
        c.requestNow("notification"); c.setMode(Mode.BACKGROUND); c.setMode(Mode.MAIN_SCREEN); c.attach { r.refresh(it) }
        advanceTimeBy(60 * 60_000); runCurrent()
        assertTrue("full stop: ${r.timesSince(t0)}", r.timesSince(t0).isEmpty())
        assertTrue(c.status.value.shutdown)
        c.resume(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue("monitoring resumes only after the manual open", r.timesSince(t0).isNotEmpty())
    }

    @Test fun startingTwiceRunsOneLoopAndCloseLeavesNothingRunning() = runTest {
        val (c, r) = setup(Mode.MAIN_SCREEN)
        c.start(); c.start(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals("one loop: one tick per interval", 7, r.ticks.size)
        val root = backgroundScope.coroutineContext[Job]!!
        assertEquals("only the loop itself", 1, root.children.count { it.isActive })
        repeat(500) { c.setMode(if (it % 2 == 0) Mode.SECTION else Mode.MAIN_SCREEN); c.requestNow("x"); runCurrent() }
        assertEquals("mode changes and requests add no coroutines", 1, root.children.count { it.isActive })
        c.close(); runCurrent()
        assertFalse(c.running)
        assertEquals(0, root.children.count { it.isActive })
    }

    @Test fun aFailingRefreshDoesNotStopTheLoop() = runTest {
        val c = AdaptiveRefreshCoordinator(backgroundScope) { testScheduler.currentTime }
        var calls = 0
        c.setMode(Mode.MAIN_SCREEN); c.start()
        c.attach { calls++; error("read failed") }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(5, calls)
        assertTrue(c.running)
    }

    @Test fun statusReportsModeIntervalAndLastReason() = runTest {
        val (c, _) = setup(Mode.MAIN_SCREEN)
        c.requestNow("bas notification"); runCurrent()
        val s = c.status.value
        assertEquals(Mode.MAIN_SCREEN, s.mode)
        assertEquals(5_000L, s.intervalMillis)
        assertTrue(s.attached)
        assertEquals("bas notification", s.lastReason)
        assertEquals(2L, s.ticks)
    }
}
