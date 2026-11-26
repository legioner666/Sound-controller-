package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 007: the state is one live flow; unsolicited data reaches it, GATT loss is recovered with bounded attempts. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStateTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val bas = StandardUuids.BATTERY_SERVICE
    private val rf = StandardUuids.FAST_PAIR_MESSAGE_STREAM

    private fun TestScope.session(link: FakeLink, fastPair: Boolean = false, coordinator: ConnectionCoordinator? = null, refresher: AdaptiveRefreshCoordinator? = null): BaseDriverSession {
        val clock = { testScheduler.currentTime }
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(fastPair), DiagnosticSink { }, clock, refreshRequests = refresher?.let { r -> r::requestNow }, coordinator = coordinator ?: ConnectionCoordinator(clock))
        return object : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
            override suspend fun start() = startUniversalSources()
        }
    }

    /** The app's wiring: one AdaptiveRefreshCoordinator drives [s]'s refreshes. */
    private fun TestScope.drive(s: DriverSession, refresher: AdaptiveRefreshCoordinator, mode: AdaptiveRefreshCoordinator.Mode = AdaptiveRefreshCoordinator.Mode.MAIN_SCREEN) {
        refresher.setMode(mode); refresher.start(); refresher.attach { s.refreshNow(it) }
    }
    private fun TestScope.refresher() = AdaptiveRefreshCoordinator(backgroundScope) { testScheduler.currentTime }

    private fun basLink(vararg extra: Pair<java.util.UUID, ByteArray>) = FakeLink(
        services = setOf(bas), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")) + extra,
    )

    @Test fun unsolicitedNotificationsReachTheFlowWithoutAnyCommand() = runTest {
        val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        val link = basLink(); link.notifyFlows[StandardUuids.BATTERY_LEVEL] = flow
        val s = session(link); s.start(); runCurrent()
        val seen = mutableListOf<Int?>()
        val job = s.state.onEach { seen += it.battery?.single?.percent }.launchIn(CoroutineScope(StandardTestDispatcher(testScheduler)))
        runCurrent()
        flow.emit(hex("4B")); runCurrent()
        flow.emit(hex("46")); runCurrent()
        assertEquals(70, s.state.value.battery!!.single!!.percent)
        assertTrue("collector saw each update as it came: $seen", seen.containsAll(listOf(75, 70)) && seen.last() == 70)
        assertEquals(1, s.state.value.live.subscriptions)
        assertTrue(s.state.value.live.lastRxMillis != null)
        job.cancel()
    }

    @Test fun gattLossIsRecoveredAndSourcesRestartedOnce() = runTest {
        val link = basLink(); val s = session(link); s.start(); runCurrent()
        val readsBefore = link.reads
        link.reconnectResults += listOf(false, true)
        link.gattStateFlow.value = ChannelState.CLOSED; runCurrent()
        assertEquals("waiting 2 s before attempt 1", s.state.value.live.reconnect)  // not shown as an attempt before one runs
        assertEquals("GATT link lost", s.state.value.live.stopReason)
        advanceTimeBy(2_001); runCurrent()               // first attempt fails
        advanceTimeBy(5_001); runCurrent()              // second succeeds
        assertEquals(2, link.reconnectCalls)
        assertNull(s.state.value.live.reconnect); assertNull(s.state.value.live.stopReason)
        assertEquals(1, s.state.value.live.reconnects)
        assertTrue("battery re-read after recovery", link.reads > readsBefore)
    }

    /**
     * TASK 010 (v0.8.12 field log: 20 failed GATT reconnects, 0 recoveries, nothing after the first cycle). Recovery never
     * gives up for good: after 5 failures the circuit opens, then one trial per cooldown, at most 20 attempts an hour, and a
     * relevant event (audio connected, Bluetooth on, app opened) gives an immediate trial.
     */
    @Test fun recoveryPausesBehindTheCircuitAndResumesOnARelevantEvent() = runTest {
        val clock = { testScheduler.currentTime }
        val coordinator = ConnectionCoordinator(clock)
        val link = basLink(); val s = session(link, coordinator = coordinator); s.start(); runCurrent()
        link.gattStateFlow.value = ChannelState.CLOSED
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(5, link.reconnectCalls)
        assertTrue(s.state.value.live.reconnect!!.startsWith("paused"))
        assertEquals("GATT could not be restored yet", s.state.value.live.stopReason)
        advanceTimeBy(60 * 60_000); runCurrent()
        assertTrue("bounded: ${link.reconnectCalls}", link.reconnectCalls in 6..25)   // one trial per cooldown, never a loop
        val before = link.reconnectCalls
        link.reconnectResults += true
        coordinator.onRelevantEvent("audio connected"); runCurrent()
        assertEquals(before + 1, link.reconnectCalls)
        assertNull(s.state.value.live.reconnect)
        assertEquals(1, s.state.value.live.reconnects)
    }

    @Test fun repeatedLossesStartOneRecoveryAtATime() = runTest {
        val link = basLink(); val s = session(link); s.start(); runCurrent()
        link.reconnectResults += true
        link.gattStateFlow.value = ChannelState.CLOSED; runCurrent()
        link.gattStateFlow.value = ChannelState.FAILED; link.gattStateFlow.value = ChannelState.CLOSED; runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, link.reconnectCalls)
    }

    @Test fun scheduledRefreshNeverStacksAStreamOnAnOpenOne() = runTest {
        val t1 = FakeTransport()
        val link = FakeLink(sdp = setOf(rf), rfcomm = mapOf(rf to t1))
        val r = refresher()
        val s = session(link, fastPair = true, refresher = r); s.start(); runCurrent()
        drive(s, r)
        assertEquals(1, link.opened.count { it == rf })
        advanceTimeBy(20 * 60_000); runCurrent()
        assertEquals(1, link.opened.count { it == rf })   // the first stream is still open: no second one is stacked on it
        assertTrue(s.refreshStats.refreshes > 200)
    }

    @Test fun batteryIsMarkedLastKnownWhenItsSourceIsLost() = runTest {
        val link = basLink(); val s = session(link); s.start(); runCurrent()
        assertTrue(!s.state.value.battery!!.single!!.lastKnown)
        link.gattStateFlow.value = ChannelState.CLOSED; runCurrent()
        assertTrue("old value stays visible but is labelled last-known", s.state.value.battery!!.single!!.lastKnown)
    }

    @Test fun controlStatusIsNeverDerivedFromSessionActiveAlone() {
        val id = DeviceIdentity("00:11:22:33:44:55", "Fake")
        val base = ActiveDevice(id, "t", "T", DriverMatch(1, null, "x"), ConnectionState.Active, HeadphoneState("Fake"), LinkStatus(audio = AudioLinkState.UNKNOWN, gatt = ChannelState.FAILED, rfcomm = ChannelState.CLOSED, control = ChannelState.CLOSED))
        assertEquals(ControlStatus.UNAVAILABLE, base.controlStatus)                       // v0.8.11: Active, but every channel closed
        val reconnecting = base.copy(headphone = HeadphoneState("Fake", live = LiveInfo(reconnect = "attempt 2/5")))
        assertEquals(ControlStatus.RECONNECTING, reconnecting.controlStatus)
        val gaveUp = base.copy(headphone = HeadphoneState("Fake", live = LiveInfo(reconnect = "paused: waiting for a Bluetooth event or 10 min")))
        assertEquals(ControlStatus.UNAVAILABLE, gaveUp.controlStatus)
        assertEquals("plain GATT is not vendor control", ControlStatus.UNAVAILABLE, base.copy(links = base.links.copy(gatt = ChannelState.CONFIRMED)).controlStatus)
        assertEquals(ControlStatus.CONNECTED, base.copy(links = base.links.copy(gatt = ChannelState.CONFIRMED, control = ChannelState.CONFIRMED)).controlStatus)
    }

    /**
     * Owner decision (TASK 011): polling frequency and the socket budget are separate. At 5 s on the main screen for an hour,
     * every refresh reads over the open GATT link (no socket), while new RFCOMM sockets stay at most ~30/h (< 40/h budget).
     */
    @Test fun gattReadsAreNotLimitedByTheSocketBudget() = runTest {
        val link = FakeLink(sdp = setOf(rf), services = setOf(bas), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")))
        link.rfcommFactory = { FakeTransport().also { t -> backgroundScope.launchLater(testScheduler, 5_000) { t.close() } } }
        val r = refresher()
        val s = session(link, fastPair = true, refresher = r); s.start(); runCurrent()
        drive(s, r)
        val readsBefore = link.reads
        advanceTimeBy(60 * 60_000); runCurrent()
        val st = s.refreshStats
        assertTrue("GATT reads ${st.gattReads} at the 5 s rhythm", st.gattReads >= 700)
        assertTrue("BAS really read: ${link.reads - readsBefore}", link.reads - readsBefore >= 700)
        val rfOpens = link.opened.count { it == rf }
        assertTrue("RFCOMM opens $rfOpens stay inside 40/h", rfOpens in 20..40)
        assertEquals("one refresh at a time", 0, st.skippedBusy)
    }

    @Test fun aFreshNotificationMakesTheTimedReadRedundant() = runTest {
        val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        val link = basLink(); link.notifyFlows[StandardUuids.BATTERY_LEVEL] = flow
        val r = refresher()
        val s = session(link, refresher = r); s.start(); runCurrent()
        drive(s, r, AdaptiveRefreshCoordinator.Mode.BACKGROUND)
        advanceTimeBy(59_000); runCurrent()
        flow.emit(hex("4B")); runCurrent()                // notification 1 s before the timed refresh
        val before = s.refreshStats
        advanceTimeBy(1_000); runCurrent()
        assertEquals(before.gattReadsSkipped + 1, s.refreshStats.gattReadsSkipped)
        assertEquals(before.gattReads, s.refreshStats.gattReads)
        assertEquals(75, s.state.value.battery?.single?.percent)
    }

    @Test fun aDriverNotificationRoutesThroughTheSingleLoop() = runTest {
        val link = basLink()
        val r = refresher()
        val s = Poked(link, DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(false), DiagnosticSink { }, { testScheduler.currentTime }, refreshRequests = r::requestNow))
        s.start(); runCurrent()
        drive(s, r, AdaptiveRefreshCoordinator.Mode.BACKGROUND); runCurrent()
        val ticks = r.status.value.ticks
        s.poke(); runCurrent()
        assertEquals(ticks + 1, r.status.value.ticks)
        assertEquals("test", r.status.value.lastReason)
    }

    @Test fun noPollingWithoutAnAttachedSession() = runTest {
        val link = basLink()
        val r = refresher()
        val s = session(link, refresher = r); s.start(); runCurrent()
        drive(s, r); runCurrent()
        r.detach()
        val reads = link.reads
        advanceTimeBy(30 * 60_000); runCurrent()
        assertEquals("headset disconnected: no reads at all", reads, link.reads)
    }

    private class Poked(link: DeviceLink, env: DriverEnvironment) : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
        override suspend fun start() = startUniversalSources()
        fun poke() = requestRefresh("test")
    }

    private fun TestScope.poked(link: FakeLink): Poked {
        val r = refresher()
        return Poked(link, DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(true), DiagnosticSink { }, { testScheduler.currentTime }, refreshRequests = r::requestNow))
            .also { drive(it, r) }
    }

    /** A real re-open: the headset closes each stream after ~5 s (as the Tune Beam 2 does) and every refresh opens a NEW transport. */
    @Test fun closedStreamIsReopenedWithBoundedSpacing() = runTest {
        val openTimes = mutableListOf<Long>()
        val link = FakeLink(sdp = setOf(rf), services = setOf(bas), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")))
        link.rfcommFactory = {
            openTimes += testScheduler.currentTime
            FakeTransport().also { t -> backgroundScope.launchLater(testScheduler, 5_000) { t.close() } }
        }
        val s = poked(link); s.start(); runCurrent()
        advanceTimeBy(20 * 60_000); runCurrent()
        assertTrue("stream was re-opened: $openTimes", openTimes.size >= 8)
        assertTrue("new sockets only when the stream data is old: $openTimes", openTimes.zipWithNext().all { (a, b) -> b - a >= 120_000 })
        assertTrue("paced although refreshes run every 5 s: $openTimes", openTimes.size <= 11)
    }

    @Test fun triggerFloodAndFailedOpensNeverCauseRapidOpens() = runTest {
        val openTimes = mutableListOf<Long>()
        val link = FakeLink(sdp = setOf(rf), services = setOf(bas), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")))
        link.rfcommFactory = { openTimes += testScheduler.currentTime; null }       // every open fails
        val s = poked(link); s.start(); runCurrent()
        repeat(100) { s.poke(); advanceTimeBy(100) }
        advanceTimeBy(10 * 60_000); runCurrent()
        assertTrue("opens: $openTimes", openTimes.filter { it > 0 }.zipWithNext().all { (a, b) -> b - a >= 120_000 })
        assertTrue("bounded: ${openTimes.size}", openTimes.size <= 6)
        assertTrue("after failures the values are labelled last-known", s.state.value.battery?.single?.lastKnown == true)
    }
}

private fun CoroutineScope.launchLater(sched: kotlinx.coroutines.test.TestCoroutineScheduler, delayMs: Long, block: suspend () -> Unit) {
    launch(StandardTestDispatcher(sched)) { kotlinx.coroutines.delay(delayMs); block() }
}
