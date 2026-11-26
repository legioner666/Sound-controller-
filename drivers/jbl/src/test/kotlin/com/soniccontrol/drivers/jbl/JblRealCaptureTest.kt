package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.FakeLink
import com.soniccontrol.core.devices.FakeTransport
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests built from frames captured on a real JBL Tune Beam 2 (Fast Pair model B484FB, firmware 0.2.8.0). */
@OptIn(ExperimentalCoroutinesApi::class)
class JblRealCaptureTest {
    private fun h(s: String) = Hex.decode(s)

    private val captured = mapOf(
        "aa 91 01 11" to ("aa 91 09 12 01 00 02 00 03 00 04 07" to NoiseMode.OFF),
        "aa 91 07 10 01 01 02 00 03 00" to ("aa 91 09 12 01 01 02 00 03 00 04 07" to NoiseMode.ANC),
        "aa 91 07 10 01 00 02 01 03 00" to ("aa 91 09 12 01 00 02 01 03 00 04 07" to NoiseMode.AMBIENT),
        "aa 91 07 10 01 00 02 00 03 01" to ("aa 91 09 12 01 00 02 00 03 01 04 07" to NoiseMode.TALK_THRU),
    )

    @Test fun allCapturedReplyFramesDecode() {
        for ((reply, mode) in captured.values) {
            val f = JblProtocol.P3.validate(h(reply))
            assertNotNull("valid: $reply", f)
            assertEquals(mode, JblProtocol.P3.parseListeningMode(f!!))
            assertEquals(7, JblProtocol.P3.unknownSlotValue(f))
        }
        // SET OFF produces the same report as GET in the capture
        assertEquals(NoiseMode.OFF, JblProtocol.P3.parseListeningMode(JblProtocol.P3.validate(h("aa 91 09 12 01 00 02 00 03 00 04 07"))!!))
    }

    @Test fun slot4IsNeverInterpreted() {
        // Changing the slot 4 value must not change the mode.
        val a = JblProtocol.P3.parseListeningMode(JblProtocol.P3.validate(h("aa 91 09 12 01 01 02 00 03 00 04 00"))!!)
        val b = JblProtocol.P3.parseListeningMode(JblProtocol.P3.validate(h("aa 91 09 12 01 01 02 00 03 00 04 ff"))!!)
        assertEquals(NoiseMode.ANC, a); assertEquals(NoiseMode.ANC, b)
    }

    @Test fun incompleteCorruptAndContradictoryReports() {
        assertNull(JblProtocol.P3.validate(h("aa 91 05 12 01 00 02 00")))                       // slot 3 missing
        assertNull(JblProtocol.P3.validate(h("aa 91 09 12 01 00 01 00 03 00 04 07")))           // duplicate slot 1
        assertNull(JblProtocol.P3.validate(h("aa 91 09 12 01 00 02 00 05 00 04 07")))           // unknown slot id 5
        assertNull(JblProtocol.P3.validate(h("aa 91 09 12 01 02 02 00 03 00 04 07")))           // value out of range
        assertNull(JblProtocol.P3.validate(h("aa 91 0a 12 01 00 02 00 03 00 04 07")))           // length longer than frame
        assertNull(JblProtocol.P3.validate(h("aa 91 09 12 01 00 02 00 03 00 04")))              // truncated
        // Two modes on at once is syntactically valid but contradictory: no mode is guessed.
        val both = JblProtocol.P3.validate(h("aa 91 09 12 01 01 02 01 03 00 04 07"))
        assertNotNull(both); assertNull(JblProtocol.P3.parseListeningMode(both!!))
    }

    @Test fun aaEeIsASeparateTypeAndNeverASuccess() {
        assertEquals(0x9B, JblProtocol.P3.errorReplyFor(h("aa ee 01 9b")))
        assertEquals(0x25, JblProtocol.P3.errorReplyFor(h("aa ee 01 25")))
        assertNull(JblProtocol.P3.validate(h("aa ee 01 9b")))
        assertNull(JblProtocol.P3.errorReplyFor(h("aa ee 02 9b 00")))
        assertNull(JblProtocol.P3.errorReplyFor(h("aa ee 01")))
    }

    private fun deviceChannel() = FakeTransport { req ->
        when (Hex.encode(req)) {
            "aa 9b 02 01 01" -> listOf(h("aa ee 01 9b"))
            "aa 25 01 00" -> listOf(h("aa ee 01 25"))
            else -> captured[Hex.encode(req)]?.let { listOf(h(it.first)) } ?: if (Hex.encode(req) == "aa 91 07 10 01 00 02 00 03 00") listOf(h("aa 91 09 12 01 00 02 00 03 00 04 07")) else emptyList()
        }
    }

    @Test fun sessionReadsModeAndSwitchesAllFourModesWithoutFalseTimeouts() = runTest(UnconfinedTestDispatcher()) {
        val ch = deviceChannel()
        val link = FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(false), DiagnosticSink { }, { 1L })
        val s = JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 2"), env)
        s.start()
        assertEquals(NoiseMode.OFF, s.state.value.noise?.mode)             // from the real GET reply
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        for (mode in listOf(NoiseMode.ANC, NoiseMode.AMBIENT, NoiseMode.TALK_THRU, NoiseMode.OFF)) {
            val r = s.setNoiseMode(mode)
            assertTrue("$mode -> $r", r is CommandOutcome.Confirmed)
            assertEquals(mode, s.state.value.noise?.mode)
        }
    }

    @Test fun errorReplyDoesNotConfirmTheControlChannelOrTheCommand() = runTest(UnconfinedTestDispatcher()) {
        val ch = FakeTransport { req -> if (Hex.encode(req) == "aa 9b 02 01 01") listOf(h("aa ee 01 9b")) else emptyList() }
        val link = FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(false), DiagnosticSink { }, { 1L })
        val s = JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 2"), env)
        s.start()
        assertTrue(s.state.value.control != ChannelState.CONFIRMED)
        assertNull(s.state.value.noise)
    }

    @Test fun controlIsInitialisedWhenGattComesUpAfterTheSessionStarted() = runTest(UnconfinedTestDispatcher()) {
        val ch = deviceChannel()
        val link = FakeLink()                       // after a reconnect: no GATT services yet
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(false), DiagnosticSink { }, { 1L })
        val s = JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 2"), env)
        s.start()
        assertEquals("No JBL control channel found", s.state.value.protocol)
        // The Fast Pair stream reports the LE address, GATT opens on it:
        link.services = setOf(JblProtocol.HARMAN_SERVICE); link.gattChannel = ch
        link.gattStateFlow.value = ChannelState.CONFIRMED
        testScheduler.advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals(NoiseMode.OFF, s.state.value.noise?.mode)
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals(1, ch.written.count { Hex.encode(it) == "aa 91 01 11" })   // one GET, no duplicated session
        link.gattStateFlow.value = ChannelState.CLOSED; link.gattStateFlow.value = ChannelState.CONFIRMED
        testScheduler.advanceTimeBy(60_000); testScheduler.runCurrent()
        assertEquals(1, ch.written.count { Hex.encode(it) == "aa 91 01 11" })   // a repeated CONFIRMED does not subscribe twice
    }

    private fun TestScope.jbl(link: FakeLink): com.soniccontrol.core.devices.DriverSession {
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(false), DiagnosticSink { }, { testScheduler.currentTime })
        return JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 2"), env)
    }

    @Test fun gestureChangeIsShownWithoutAnyCommand() = runTest(UnconfinedTestDispatcher()) {
        val ch = deviceChannel()
        val s = jbl(FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)); s.start()
        assertEquals(NoiseMode.OFF, s.state.value.noise?.mode)
        ch.push(h("aa 91 09 12 01 01 02 00 03 00 04 07"))            // user double-taps the earbud: unsolicited report
        assertEquals(NoiseMode.ANC, s.state.value.noise?.mode)
        ch.push(h("aa 91 09 12 01 00 02 01 03 00 04 07"))
        assertEquals(NoiseMode.AMBIENT, s.state.value.noise?.mode)
    }

    @Test fun gattLossReopensTheVendorChannelOnceAndReadsModeAgain() = runTest(UnconfinedTestDispatcher()) {
        val first = deviceChannel()
        val link = FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = first)
        val s = jbl(link); s.start()
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        val second = deviceChannel()
        link.gattChannel = second; link.reconnectResults += true
        link.gattStateFlow.value = ChannelState.CLOSED
        testScheduler.advanceTimeBy(3_100); testScheduler.runCurrent()
        testScheduler.advanceTimeBy(20_000); testScheduler.runCurrent()      // notify-on answers AA EE, so its timeout passes before the GET
        assertTrue(first.closed)                                      // the dead channel was released
        assertEquals(1, second.written.count { Hex.encode(it) == "aa 91 01 11" })
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals(1, s.state.value.live.reconnects)
    }

    /** TASK 011: the driver never polls on its own; the single refresh loop asks for the mode with the confirmed read-only GET. */
    @Test fun modeIsReadOnlyWhenTheRefreshLoopAsksAndOnlyWithTheConfirmedGet() = runTest(UnconfinedTestDispatcher()) {
        val ch = deviceChannel()
        val s = jbl(FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)); s.start()
        val gets = { ch.written.count { Hex.encode(it) == "aa 91 01 11" } }
        val before = gets()
        testScheduler.advanceTimeBy(30 * 60_000); testScheduler.runCurrent()
        assertEquals("no polling loop inside the driver", before, gets())
        s.refreshNow("interval"); testScheduler.runCurrent()
        assertEquals(before + 1, gets())
        s.refreshNow("interval"); testScheduler.runCurrent()
        assertEquals(before + 2, gets())
        assertTrue(ch.written.none { Hex.encode(it).startsWith("aa 91 07 10") })   // refreshing never sends SET commands
    }

    private fun TestScope.jblFp(link: FakeLink): com.soniccontrol.core.devices.DriverSession {
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(true), DiagnosticSink { }, { testScheduler.currentTime })
        return JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 2"), env)
    }

    /** The real v0.6.7 packet must reach the reactive state; it was previously dropped by validate() as "not recognised". */
    @Test fun nativeBatteryNotificationUpdatesTheReactiveStateForB484FB() = runTest(UnconfinedTestDispatcher()) {
        val fp = FakeTransport()
        val ch = deviceChannel()
        val link = FakeLink(sdp = setOf(com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_MESSAGE_STREAM), rfcomm = mapOf(com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_MESSAGE_STREAM to fp),
            services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)
        val s = jblFp(link); s.start()
        fp.push(h("03 01 00 03 b4 84 fb")); fp.push(h("03 09 00 07 30 2e 32 2e 38 2e 30"))
        assertEquals("B484FB", s.state.value.modelId?.uppercase()); assertEquals("0.2.8.0", s.state.value.firmware)
        ch.push(h("aa 06 10 00 02 00 1a 02 00 64 00 1b 02 00 5d 00 1c 02 00 5b"))
        val b = s.state.value.battery!!
        assertEquals(listOf(100, 93, 91), listOf(b.left?.percent, b.right?.percent, b.case?.percent))
        assertEquals(com.soniccontrol.core.devices.DataSource.VENDOR_NATIVE_EXPERIMENTAL, b.left?.source)
        // a later notification with new values changes the flow value (earbud returned to the case)
        ch.push(h("aa 06 10 00 02 00 1a 02 00 63 00 1b 02 00 5d 00 1c 02 00 5a"))
        assertEquals(listOf(99, 93, 90), listOf(s.state.value.battery!!.left?.percent, s.state.value.battery!!.right?.percent, s.state.value.battery!!.case?.percent))
    }

    @Test fun nativeBatteryIsIgnoredForOtherModelsAndFirmware() = runTest(UnconfinedTestDispatcher()) {
        val ch = deviceChannel()
        val s = jbl(FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)); s.start()   // model/firmware unknown
        ch.push(h("aa 06 10 00 02 00 1a 02 00 64 00 1b 02 00 5d 00 1c 02 00 5b"))
        assertNull(s.state.value.battery)
        ch.push(h("aa 06 05 00 02 00 06 01 05"))                      // unknown semantics: never interpreted
        assertNull(s.state.value.battery)
    }
}
