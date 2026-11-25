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

    private fun TestScope.session(link: FakeLink, fastPair: Boolean = false): BaseDriverSession {
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(fastPair), DiagnosticSink { }, { testScheduler.currentTime }, periodicRefresh = true)
        return object : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
            override suspend fun start() = startUniversalSources()
        }
    }

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
        assertEquals("waiting 2 s before attempt 1/5", s.state.value.live.reconnect)  // not shown as an attempt before one runs
        assertEquals("GATT link lost", s.state.value.live.stopReason)
        advanceTimeBy(2_001); runCurrent()               // first attempt fails
        advanceTimeBy(5_001); runCurrent()              // second succeeds
        assertEquals(2, link.reconnectCalls)
        assertNull(s.state.value.live.reconnect); assertNull(s.state.value.live.stopReason)
        assertEquals(1, s.state.value.live.reconnects)
        assertTrue("battery re-read after recovery", link.reads > readsBefore)
    }

    @Test fun recoveryIsBoundedAndReportsWhyUpdatesStopped() = runTest {
        val link = basLink(); val s = session(link); s.start(); runCurrent()
        link.gattStateFlow.value = ChannelState.CLOSED
        advanceTimeBy(10 * 60_000); runCurrent()
        assertEquals(5, link.reconnectCalls)
        assertEquals("gave up after 5 attempts", s.state.value.live.reconnect)
        assertEquals("GATT could not be restored", s.state.value.live.stopReason)
        advanceTimeBy(60 * 60_000); runCurrent()
        assertEquals(5, link.reconnectCalls)             // never an endless loop
    }

    @Test fun repeatedLossesStartOneRecoveryAtATime() = runTest {
        val link = basLink(); val s = session(link); s.start(); runCurrent()
        link.reconnectResults += true
        link.gattStateFlow.value = ChannelState.CLOSED; runCurrent()
        link.gattStateFlow.value = ChannelState.FAILED; link.gattStateFlow.value = ChannelState.CLOSED; runCurrent()
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, link.reconnectCalls)
    }

    @Test fun periodicFastPairRefreshWaitsForTheLongIntervalAndNeverStacksStreams() = runTest {
        val t1 = FakeTransport()
        val link = FakeLink(sdp = setOf(rf), rfcomm = mapOf(rf to t1))
        val s = session(link, fastPair = true); s.start(); runCurrent()
        assertEquals(1, link.opened.count { it == rf })
        advanceTimeBy(20 * 60_000); runCurrent()
        assertEquals(1, link.opened.count { it == rf })   // the first stream is still open: no second one is stacked on it
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
        val gaveUp = base.copy(headphone = HeadphoneState("Fake", live = LiveInfo(reconnect = "gave up after 5 attempts")))
        assertEquals(ControlStatus.UNAVAILABLE, gaveUp.controlStatus)
        assertEquals("plain GATT is not vendor control", ControlStatus.DATA_ONLY, base.copy(links = base.links.copy(gatt = ChannelState.CONFIRMED)).controlStatus)
        assertEquals(ControlStatus.CONNECTED, base.copy(links = base.links.copy(gatt = ChannelState.CONFIRMED, control = ChannelState.CONFIRMED)).controlStatus)
    }

    @Test fun fastPairRefreshIsAdaptiveBoundedAndSingleSocket() = runTest {
        val link = FakeLink(sdp = setOf(rf), rfcomm = mapOf(rf to FakeTransport()), services = setOf(bas), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")))
        val s = session(link, fastPair = true); s.start(); runCurrent()
        advanceTimeBy(60 * 60_000); runCurrent()
        // unchanged data backs off 30 s -> 5 min: far fewer than one open per 30 s, and never endless
        assertTrue("opens=${link.opened.size}", link.opened.size in 1..20)
    }

    private class Poked(link: DeviceLink, env: DriverEnvironment) : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
        override suspend fun start() = startUniversalSources()
        fun poke() = requestRefresh("test")
    }

    private fun TestScope.poked(link: FakeLink) = Poked(link, DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(true), DiagnosticSink { }, { testScheduler.currentTime }, periodicRefresh = true))

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
        assertTrue("stream was re-opened: $openTimes", openTimes.size >= 4)
        assertTrue("never faster than the minimum gap: $openTimes", openTimes.zipWithNext().all { (a, b) -> b - a >= 5_000 })
        assertTrue("backs off while nothing changes: $openTimes", openTimes.size <= 12)
    }

    @Test fun triggerFloodAndFailedOpensNeverCauseRapidOpens() = runTest {
        val openTimes = mutableListOf<Long>()
        val link = FakeLink(sdp = setOf(rf), services = setOf(bas), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")))
        link.rfcommFactory = { openTimes += testScheduler.currentTime; null }       // every open fails
        val s = poked(link); s.start(); runCurrent()
        repeat(100) { s.poke(); advanceTimeBy(100) }
        advanceTimeBy(10 * 60_000); runCurrent()
        assertTrue("opens: $openTimes", openTimes.filter { it > 0 }.zipWithNext().all { (a, b) -> b - a >= 5_000 })
        assertTrue("bounded: ${openTimes.size}", openTimes.size < 150)
        assertTrue("after failures the values are labelled last-known", s.state.value.battery?.single?.lastKnown == true)
    }
}

private fun CoroutineScope.launchLater(sched: kotlinx.coroutines.test.TestCoroutineScheduler, delayMs: Long, block: suspend () -> Unit) {
    launch(StandardTestDispatcher(sched)) { kotlinx.coroutines.delay(delayMs); block() }
}
