package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted driver: each session start can be held open, fail, or succeed; state changes are observable. */
private class ScriptedDriver : HeadphoneDriver {
    override val id = "scripted"
    override val brand = Brand.OTHER
    override val displayName = "Scripted"
    override val catalog = emptyList<CatalogEntry>()
    val sessions = mutableListOf<Session>()
    var failStarts = 0
    var holdStart: CompletableDeferred<Unit>? = null

    override fun match(identity: DeviceIdentity) = DriverMatch(1, null, "test")

    override fun createSession(link: DeviceLink, identity: DeviceIdentity, env: DriverEnvironment): DriverSession =
        Session(identity.address).also { sessions += it }

    inner class Session(val address: String) : DriverSession {
        override val driverId = "scripted"
        val mutable = MutableStateFlow(HeadphoneState(address))
        override val state: StateFlow<HeadphoneState> = mutable.asStateFlow()
        var stopped = false
        override suspend fun start() {
            holdStart?.await()
            if (failStarts > 0) { failStarts--; error("start failed") }
            mutable.value = mutable.value.copy(control = ChannelState.CONFIRMED)
        }
        override suspend fun stop() { stopped = true }
        override suspend fun refresh(): CommandOutcome = CommandOutcome.Confirmed(byteArrayOf(1))
        override suspend fun setNoiseMode(mode: NoiseMode): CommandOutcome = CommandOutcome.Confirmed(byteArrayOf(1))
        override suspend fun probe(): CommandOutcome = CommandOutcome.Unsupported("-")
    }
}

private class Links : LinkFactory {
    val created = mutableListOf<FakeLink>()
    var audio = AudioLinkState.CONNECTED
    override fun identity(address: String) = DeviceIdentity(address, "Dev $address")
    override fun audioState(address: String) = audio
    override fun create(address: String, scope: CoroutineScope) = FakeLink(address = address).also { created += it }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    // Not backgroundScope: advanceUntilIdle() does not run background work, and the manager's actor must run.
    private fun TestScope.manager(driver: ScriptedDriver, links: Links, auto: Boolean = true) = SessionManager(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        registry = DriverRegistry(emptyList(), driver),
        links = links,
        experimental = MutableStateFlow(false),
        fastPair = MutableStateFlow(false),
        sink = DiagnosticSink { },
        clock = { testScheduler.currentTime },
        autoReconnect = { auto },
        policy = ReconnectPolicy(1_000, 8_000, 3),
    )

    @Test fun activeOnlyAfterStartAndControlReflected() = runTest {
        val d = ScriptedDriver(); val links = Links()
        d.holdStart = CompletableDeferred()
        val m = manager(d, links)
        m.connect("A"); advanceUntilIdle()
        assertEquals(ConnectionState.Starting, m.active.value?.connection)
        assertEquals(ChannelState.UNAVAILABLE, m.active.value?.links?.control)
        d.holdStart!!.complete(Unit); advanceUntilIdle()
        assertEquals(ConnectionState.Active, m.active.value?.connection)
        assertEquals(ChannelState.CONFIRMED, m.active.value?.links?.control)
        assertEquals(AudioLinkState.CONNECTED, m.active.value?.links?.audio)
    }

    @Test fun disconnectIsNotBlockedByASlowStart() = runTest {
        val d = ScriptedDriver(); val links = Links()
        d.holdStart = CompletableDeferred()
        val m = manager(d, links)
        m.connect("A"); advanceUntilIdle()
        m.disconnect(); advanceUntilIdle()
        assertNull(m.active.value)
        assertTrue(d.sessions.single().stopped)
        assertTrue(links.created.single().closed)
        d.holdStart!!.complete(Unit); advanceUntilIdle()
        assertNull(m.active.value) // the late start result is ignored
    }

    @Test fun rapidSwitchingKeepsOnlyTheLastDevice() = runTest {
        val d = ScriptedDriver(); val links = Links()
        val m = manager(d, links)
        m.connect("A"); m.connect("B"); m.connect("C"); advanceUntilIdle()
        assertEquals("C", m.active.value?.identity?.address)
        assertEquals(ConnectionState.Active, m.active.value?.connection)
        assertEquals(listOf(true, true, false), d.sessions.map { it.stopped })
        assertEquals(listOf(true, true, false), links.created.map { it.closed })
    }

    @Test fun staleStateUpdatesAreDropped() = runTest {
        val d = ScriptedDriver(); val links = Links()
        val m = manager(d, links)
        m.connect("A"); advanceUntilIdle()
        val old = d.sessions.single()
        m.connect("B"); advanceUntilIdle()
        old.mutable.value = old.mutable.value.copy(displayName = "ghost")
        advanceUntilIdle()
        assertEquals("B", m.active.value?.headphone?.displayName)
    }

    @Test fun failedStartRetriesWithBackoffThenGivesUp() = runTest {
        val d = ScriptedDriver().apply { failStarts = 10 }
        val m = manager(d, Links())
        m.connect("A"); runCurrent()
        assertEquals(ConnectionState.Reconnecting(1, 1_000), m.active.value?.connection)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(ConnectionState.Reconnecting(2, 2_000), m.active.value?.connection)
        advanceUntilIdle()
        assertTrue(m.active.value?.connection is ConnectionState.Failed)
        assertEquals(4, d.sessions.size) // first try + 3 retries
    }

    @Test fun retryAbandonedAfterDisconnect() = runTest {
        val d = ScriptedDriver().apply { failStarts = 1 }
        val m = manager(d, Links())
        m.connect("A"); runCurrent()
        m.disconnect(); advanceUntilIdle()
        assertNull(m.active.value)
        assertEquals(1, d.sessions.size)
    }

    @Test fun audioLossStopsSessionAndAudioReturnRestarts() = runTest {
        val d = ScriptedDriver(); val links = Links()
        val m = manager(d, links)
        m.connect("A"); advanceUntilIdle()
        m.onAudioLink("A", connected = false); advanceUntilIdle()
        assertTrue(d.sessions[0].stopped)
        assertEquals(ConnectionState.Reconnecting(0, 0), m.active.value?.connection)
        assertEquals(AudioLinkState.DISCONNECTED, m.active.value?.links?.audio)
        assertEquals(ChannelState.UNAVAILABLE, m.active.value?.links?.control)
        m.onAudioLink("A", connected = true); advanceUntilIdle()
        assertEquals(2, d.sessions.size)
        assertEquals(ConnectionState.Active, m.active.value?.connection)
    }

    @Test fun noAutoReconnectGoesIdle() = runTest {
        val d = ScriptedDriver()
        val m = manager(d, Links(), auto = false)
        m.connect("A"); advanceUntilIdle()
        m.onAudioLink("A", false); m.onAudioLink("A", true); advanceUntilIdle()
        assertEquals(ConnectionState.Idle, m.active.value?.connection)
        assertEquals(1, d.sessions.size)
    }

    @Test fun eventsForOtherDevicesAreIgnored() = runTest {
        val d = ScriptedDriver()
        val m = manager(d, Links())
        m.connect("A"); advanceUntilIdle()
        m.onAudioLink("B", false); advanceUntilIdle()
        assertFalse(d.sessions[0].stopped)
    }

    @Test fun actionResultFromReplacedSessionIsDropped() = runTest {
        val d = ScriptedDriver()
        val m = manager(d, Links())
        m.connect("A"); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        val result = CompletableDeferred<CommandOutcome?>()
        backgroundScope.launch { result.complete(m.withSession { gate.await(); it.refresh() }) }
        runCurrent()
        m.connect("B"); advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()
        assertNull(result.await())
    }

    private fun TestScope.runCurrent() = testScheduler.runCurrent()
}

