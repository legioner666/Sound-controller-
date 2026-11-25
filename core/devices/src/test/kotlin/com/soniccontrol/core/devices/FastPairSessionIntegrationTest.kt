package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FastPairSessionIntegrationTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun TestScope.session(link: FakeLink, experimental: Boolean): BaseDriverSession {
        val env = DriverEnvironment(
            CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
            MutableStateFlow(experimental), MutableStateFlow(true), DiagnosticSink { }, { testScheduler.currentTime },
        )
        return object : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
            override suspend fun start() = startUniversalSources()
        }
    }

    private fun stream() = FakeTransport()
    private val uuid = StandardUuids.FAST_PAIR_MESSAGE_STREAM

    @Test fun streamRevealsEndpointBatteryTimeAndActive() = runTest {
        val t = stream()
        val link = FakeLink(sdp = setOf(uuid), rfcomm = mapOf(uuid to t))
        val s = session(link, experimental = false)
        s.start(); advanceUntilIdle()
        t.push(hex("03010003AABBCC")); t.push(hex("03020006AABBCCDDEEFF"))
        t.push(hex("0303000357417F")); t.push(hex("03040002012C")); t.push(hex("0306000103"))
        advanceUntilIdle()
        val st = s.state.value
        assertEquals(87, st.battery?.left?.percent)
        assertEquals(DataSource.FAST_PAIR_STREAM, st.battery?.source)
        assertEquals(300, st.remainingMinutes)
        assertEquals(ActiveComponents(true, true), st.active)
        assertEquals("AABBCC", st.modelId)
        assertEquals(listOf("AA:BB:CC:DD:EE:FF" to "AABBCC"), link.suggested)
        assertEquals(false, link.openedWritable)       // listen-only without experimental
        assertTrue(t.written.isEmpty())
    }

    @Test fun corruptFramesAreIgnoredAndSendNothing() = runTest {
        val t = stream()
        val link = FakeLink(sdp = setOf(uuid), rfcomm = mapOf(uuid to t))
        val s = session(link, false)
        s.start(); advanceUntilIdle()
        t.push(hex("0303000457417F")); t.push(hex("0306000109")); t.push(hex("03020003AABBCC"))
        advanceUntilIdle()
        assertNull(s.state.value.battery); assertNull(s.state.value.active); assertTrue(link.suggested.isEmpty())
    }

    @Test fun onlyExperimentalSendsTheSingleWhitelistedRequest() = runTest {
        val t = stream()
        val link = FakeLink(sdp = setOf(uuid), rfcomm = mapOf(uuid to t))
        val s = session(link, experimental = true)
        s.start(); advanceUntilIdle()
        assertEquals(true, link.openedWritable)
        assertEquals(1, t.written.size)
        assertTrue(FastPair.isAllowedWrite(t.written.single()))
    }
}
