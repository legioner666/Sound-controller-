package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.FakeLink
import com.soniccontrol.core.devices.FakeTransport
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.Notice
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JblGuardFuzzTest {
    private val allowed = JblCommands.allowedFrames

    @Test fun randomFramesAreNeverAllowed() {
        val rnd = Random(42)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(0, 40)) { rnd.nextInt(256).toByte() }
            val ok = JblGuard.refusal(b) == null
            if (ok) {
                // Only exact allowlisted frames, or well-formed P4 GETs of readable ids, may pass.
                val p4 = JblProtocol.P4.parse(b)
                assertTrue(allowed.any { it.contentEquals(b) } || (p4 != null && p4.command == JblProtocol.P4.CMD_GET_DEVICE_INFO))
            }
        }
    }

    @Test fun truncatedAndExtendedAllowlistedFramesAreRefused() {
        for (f in allowed) {
            for (n in 0 until f.size) assertNotNull("truncated ${Hex.encode(f)} to $n", JblGuard.refusal(f.copyOf(n)))
            assertNotNull(JblGuard.refusal(f + byteArrayOf(0)))
        }
    }

    @Test fun parsersNeverThrowOnGarbage() {
        val rnd = Random(7)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt(256).toByte() }
            JblProtocol.P3.validate(b); JblProtocol.P4.validate(b); JblProtocol.Race.validate(b)
            JblProtocol.P4.parseTlv(b); JblProtocol.splitHarman(b); JblProtocol.Race.split(b)
        }
    }

    @Test fun validationRejectsMalformedReplies() {
        assertNull(JblProtocol.P3.validate(Hex.decode("aa 22 00")))                     // empty version reply
        assertNull(JblProtocol.P3.validate(Hex.decode("aa 77 01 00")))                  // unknown command
        assertNull(JblProtocol.P3.validate(Hex.decode("aa 25 03 01 00 00")))            // battery too short
        assertNull(JblProtocol.P3.validate(Hex.decode("aa 91 07 12 01 02 02 00 03 00"))) // slot value out of range
        assertNull(JblProtocol.P3.validate(Hex.decode("aa 91 07 12 01 00 02 00 03")))  // length mismatch
        assertNull(JblProtocol.P4.validate(Hex.decode("00 dd 01 00 01 01 00 00")))      // index >= count
        assertNull(JblProtocol.P4.validate(Hex.decode("00 dd 01 00 01 00 03 00 0d 00 05"))) // incomplete TLV
        assertNotNull(JblProtocol.P4.validate(Hex.decode("00 dd 03 00 01 00 05 00 0d 00 01 00 50")))
        assertNull(JblProtocol.Race.validate(JblProtocol.Race.modeGet()))               // our own command echoed back
    }

    @Test fun p4BatteryMatcherWantsRequestedIds() {
        val cmd = JblCommands.p4Get(SupportStatus.EXPERIMENTAL, listOf(13, 14))
        assertFalse(cmd.matcher!!.matches(Hex.decode("00 dd 03 00 01 00 05 00 04 00 01 00 50"))) // product id only
        assertTrue(cmd.matcher!!.matches(Hex.decode("00 dd 03 00 01 00 05 00 0d 00 01 00 50")))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class JblIntegrationTest {
    private fun TestScope.env(experimental: Boolean = false) =
        DriverEnvironment(backgroundScope, MutableStateFlow(experimental), MutableStateFlow(false), DiagnosticSink { }, { 1L })

    @Test fun unrecognisedFramesNeitherIdentifyNorConfirm() = runTest(UnconfinedTestDispatcher()) {
        val ch = FakeTransport()
        val s = JblDriver().createSession(FakeLink(name = "JBL Tune Beam 3", services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch),
            DeviceIdentity("A", "JBL Tune Beam 3"), env())
        s.start()
        assertEquals(ChannelState.OPEN, s.state.value.control)
        assertTrue(Notice.PROTOCOL_UNIDENTIFIED in s.state.value.notices)
        ch.push(Hex.decode("aa 77 01 00")); ch.push(Hex.decode("aa 25 03 01 00 00")); ch.push(Hex.decode("12 34 56"))
        assertEquals(ChannelState.OPEN, s.state.value.control)
        assertTrue(s.state.value.protocol.contains("unknown"))
        ch.push(Hex.decode("00 dd 03 00 01 00 0a 00 0d 00 01 00 50 0e 00 01 00 46"))
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals("JBL Protocol 4", s.state.value.protocol)
        assertEquals(80, s.state.value.battery?.left?.percent)
    }

    @Test fun channelThatCannotOpenIsReportedAsFailed() = runTest(UnconfinedTestDispatcher()) {
        val s = JblDriver().createSession(FakeLink(name = "JBL Tune Beam 3", services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = null),
            DeviceIdentity("A", "JBL Tune Beam 3"), env())
        s.start()
        assertEquals(ChannelState.FAILED, s.state.value.control)
        assertTrue(Notice.CLOSE_VENDOR_APP in s.state.value.notices)
    }

    @Test fun noVendorChannelMeansUniversalOnly() = runTest(UnconfinedTestDispatcher()) {
        val s = JblDriver().createSession(FakeLink(name = "JBL Tune Beam 3"), DeviceIdentity("A", "JBL Tune Beam 3"), env())
        s.start()
        assertEquals(ChannelState.UNAVAILABLE, s.state.value.control)
        assertTrue(Notice.NO_CONTROL_CHANNEL in s.state.value.notices)
        assertTrue(s.setNoiseMode(NoiseMode.ANC) is CommandOutcome.Unsupported)
    }

    private fun tourPro3(mode: () -> Int) = FakeTransport { req ->
        when (Hex.encode(req)) {
            "05 5a 04 00 01 09 05 00" -> listOf(Hex.decode("05 5b 09 00 01 09 05 00 00 %02x 00 00 00".format(mode())))
            else -> if (req.size > 5 && req[4] == 0x06.toByte()) listOf(Hex.decode("05 5b 03 00 06 0e 00")) else emptyList()
        }
    }

    @Test fun raceModeChangeIsConfirmedByReadBack() = runTest(UnconfinedTestDispatcher()) {
        var current = 0x00
        val ch = tourPro3 { current }
        ch.responder = { req ->
            if (req.size == 10 && req[4] == 0x06.toByte()) current = req[8].toInt()
            if (req.size == 8 && req[4] == 0x06.toByte()) current = 0
            tourPro3 { current }.responder(req)
        }
        val link = FakeLink(name = "JBL Tour Pro 3", sdp = setOf(JblProtocol.AIROHA_RACE_RFCOMM), rfcomm = mapOf(JblProtocol.AIROHA_RACE_RFCOMM to ch))
        val s = JblDriver().createSession(link, DeviceIdentity("A", "JBL Tour Pro 3", link.sdp), env())
        s.start()
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
        assertEquals(NoiseMode.OFF, s.state.value.noise?.mode)
        assertTrue(s.setNoiseMode(NoiseMode.ANC) is CommandOutcome.Confirmed)
        assertEquals(NoiseMode.ANC, s.state.value.noise?.mode)
    }

    @Test fun raceAckWithoutStateChangeIsAFailure() = runTest(UnconfinedTestDispatcher()) {
        val ch = tourPro3 { 0x00 } // acknowledges, but stays Off
        val link = FakeLink(name = "JBL Tour Pro 3", sdp = setOf(JblProtocol.AIROHA_RACE_RFCOMM), rfcomm = mapOf(JblProtocol.AIROHA_RACE_RFCOMM to ch))
        val s = JblDriver().createSession(link, DeviceIdentity("A", "JBL Tour Pro 3", link.sdp), env())
        s.start()
        assertTrue(s.setNoiseMode(NoiseMode.ANC) is CommandOutcome.Failed)
    }

    @Test fun experimentalRaceModelSendsNothingWithoutOptIn() = runTest(UnconfinedTestDispatcher()) {
        val ch = tourPro3 { 0 }
        val link = FakeLink(name = "JBL Live Buds 3", sdp = setOf(JblProtocol.AIROHA_RACE_RFCOMM), rfcomm = mapOf(JblProtocol.AIROHA_RACE_RFCOMM to ch))
        val s = JblDriver().createSession(link, DeviceIdentity("A", "JBL Live Buds 3", link.sdp), env())
        s.start()
        assertTrue(ch.written.isEmpty())
        assertTrue(s.setNoiseMode(NoiseMode.ANC) is CommandOutcome.Rejected)
        assertTrue(ch.written.isEmpty())
    }
}
