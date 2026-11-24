package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.ManufacturerAdvert
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.devices.TransportState
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class JblProtocolTest {
    @Test fun haaFramesMatchCapturedBytes() {
        assertEquals("aa 21 01 30", Hex.encode(JblProtocol.P3.versionGet()))
        assertEquals("aa 25 01 00", Hex.encode(JblProtocol.P3.batteryGet()))
        assertEquals("aa 91 01 11", Hex.encode(JblProtocol.P3.listeningModeGet()))
        assertEquals("aa 9b 02 01 01", Hex.encode(JblProtocol.P3.enableNotifications()))
        assertEquals("aa 91 07 10 01 01 02 00 03 00", Hex.encode(JblProtocol.P3.listeningModeSet(NoiseMode.ANC)))
        assertEquals("aa 91 07 10 01 00 02 00 03 01", Hex.encode(JblProtocol.P3.listeningModeSet(NoiseMode.TALK_THRU)))
    }

    @Test fun haaBatteryMatchesFastPairCrossCheck() {
        // TUNE230NC capture: left 10, right 30, case 78.
        val f = JblProtocol.P3.parse(Hex.decode("aa 25 0d 01 00 00 0a 1e 00 4e 10 0e 78 0e cc 0c"))!!
        val b = JblProtocol.P3.parseBattery(f, 0)!!
        assertEquals(10, b.left?.percent); assertEquals(30, b.right?.percent); assertEquals(78, b.case?.percent)
    }

    @Test fun haaModeReport() {
        val f = JblProtocol.P3.parse(Hex.decode("aa 91 07 12 01 00 02 01 03 00"))!!
        assertEquals(NoiseMode.AMBIENT, JblProtocol.P3.parseListeningMode(f))
        val bad = JblProtocol.P3.parse(Hex.decode("aa 91 07 12 01 01 02 01 03 00"))!!
        assertNull(JblProtocol.P3.parseListeningMode(bad))
    }

    @Test fun splitHandlesFragmentsAndBothFamilies() {
        val (frames, used) = JblProtocol.splitHarman(Hex.decode("aa 25 01 00 00 dd 01 00 01 00 02 00 04 00 aa 91"))
        assertEquals(2, frames.size)
        assertEquals(14, used)
    }

    @Test fun p4GetAndTlv() {
        assertEquals("00 dd 01 00 01 00 04 00 04 00 0c 00", Hex.encode(JblProtocol.P4.getDeviceInfo(listOf(4, 12))))
        val tlv = JblProtocol.P4.parseTlv(Hex.decode("0d 00 01 00 50 0e 00 01 00 46 03 1f 01 00 64"))
        val b = JblProtocol.P4.parseBattery(tlv, 0)!!
        assertEquals(80, b.left?.percent); assertEquals(70, b.right?.percent); assertEquals(100, b.case?.percent)
    }

    @Test(expected = IllegalArgumentException::class) fun p4RejectsNonReadableIds() { JblProtocol.P4.getDeviceInfo(listOf(15)) }

    @Test fun raceFramesMatchTourPro3Capture() {
        assertEquals("05 5a 04 00 01 09 05 00", Hex.encode(JblProtocol.Race.modeGet()))
        assertEquals("05 5a 04 00 06 0e 00 0b", Hex.encode(JblProtocol.Race.modeSet(NoiseMode.OFF)))
        assertEquals("05 5a 06 00 06 0e 00 0a 0a 04", Hex.encode(JblProtocol.Race.modeSet(NoiseMode.AMBIENT)))
        val ev = JblProtocol.Race.parse(Hex.decode("05 5d 08 00 01 09 05 00 00 01 00 00"))!!
        assertEquals(NoiseMode.ANC, JblProtocol.Race.parseMode(ev))
    }

    @Test fun guardBlocksDestructiveAndUnknown() {
        assertNotNull(JblGuard.refusal(Hex.decode("aa 95 00")))
        assertNotNull(JblGuard.refusal(Hex.decode("00 dd 02 00 01 00 05 00 0f 00 01 00 01")))  // P4 SET
        assertNotNull(JblGuard.refusal(Hex.decode("00 dd 01 00 01 00 02 00 00 1f")))           // P4 GET of OTA id 7936
        assertNotNull(JblGuard.refusal(Hex.decode("05 5a 04 00 00 1c 00 00")))                 // RACE outside mode control
        assertNotNull(JblGuard.refusal(Hex.decode("aa 22 01 00")))                             // not on allowlist
        assertNull(JblGuard.refusal(JblProtocol.P3.batteryGet()))
        assertNull(JblGuard.refusal(JblProtocol.P4.getDeviceInfo(listOf(13, 14, 7939))))
    }

    @Test fun catalogKnowsTuneBeam3AndKeepsItExperimental() {
        val m = JblCatalog.findModel("JBL Tune Beam 3")!!
        assertEquals(JblStack.UNIDENTIFIED, m.stack)
        assertEquals("Tune Beam", JblCatalog.findModel("JBL TUNE BEAM")?.name)
        val e = JblCatalog.entries.first { it.model == "Tune Beam 3" }
        assertEquals(SupportStatus.EXPERIMENTAL, e.features[Feature.NOISE_CONTROL])
    }
}

private class FakeChannel(val reply: (ByteArray) -> List<ByteArray>) : BluetoothTransport {
    val written = mutableListOf<ByteArray>()
    private val flow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val description = "fake"
    override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.Open)
    override val incoming: SharedFlow<ByteArray> = flow
    override val canWrite = true
    override suspend fun close() {}
    override suspend fun write(bytes: ByteArray) { written += bytes; reply(bytes).forEach { flow.emit(it) } }
}

private class Link(private val channel: BluetoothTransport, override val name: String) : DeviceLink {
    override val address = "AA:BB:CC:00:11:22"
    override fun sdpUuids() = emptySet<UUID>()
    override suspend fun gattServices() = setOf(JblProtocol.HARMAN_SERVICE)
    override suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray? = null
    override suspend fun notifications(service: UUID, characteristic: UUID): Flow<ByteArray>? = null
    override suspend fun openGattChannel(service: UUID, notify: UUID?, write: UUID?) = channel
    override suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport? = null
    override fun manufacturerAdverts(companyId: Int): Flow<ManufacturerAdvert> = emptyFlow()
}

@OptIn(ExperimentalCoroutinesApi::class)
class JblSessionTest {
    private fun haaDevice() = FakeChannel { req ->
        when (Hex.encode(req)) {
            "aa 9b 02 01 01" -> listOf(Hex.decode("aa 9b 03 02 01 00"))
            "aa 25 01 00" -> listOf(Hex.decode("aa 25 0d 01 00 00 0a 1e 00 4e 10 0e 78 0e cc 0c"))
            "aa 91 01 11" -> listOf(Hex.decode("aa 91 07 12 01 00 02 01 03 00"))
            "aa 91 07 10 01 01 02 00 03 00" -> listOf(Hex.decode("aa 91 07 12 01 01 02 00 03 00"))
            else -> emptyList()
        }
    }

    @Test fun knownHaaModelReadsStateAndSwitchesMode() = runTest(UnconfinedTestDispatcher()) {
        val ch = haaDevice()
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(false), MutableStateFlow(false), DiagnosticSink { }, { 1L })
        val s = JblDriver().createSession(Link(ch, "JBL TUNE230NC TWS"), DeviceIdentity("AA:BB:CC:00:11:22", "JBL TUNE230NC TWS"), env)
        s.start()
        assertEquals(30, s.state.value.battery?.right?.percent)
        assertEquals(NoiseMode.AMBIENT, s.state.value.noise?.mode)
        assertTrue(s.setNoiseMode(NoiseMode.ANC) is CommandOutcome.Confirmed)
        assertEquals(NoiseMode.ANC, s.state.value.noise?.mode)
    }

    @Test fun tuneBeam3SendsNothingUntilProbeWithOptIn() = runTest(UnconfinedTestDispatcher()) {
        val ch = FakeChannel { req -> if (Hex.encode(req) == "aa 21 01 30") listOf(Hex.decode("aa 22 02 30 00")) else emptyList() }
        val experimental = MutableStateFlow(false)
        val env = DriverEnvironment(backgroundScope, experimental, MutableStateFlow(false), DiagnosticSink { }, { 1L })
        val s = JblDriver().createSession(Link(ch, "JBL Tune Beam 3"), DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 3"), env)
        s.start()
        assertTrue(ch.written.isEmpty())
        assertTrue(s.probe() is CommandOutcome.Rejected)
        assertTrue(ch.written.isEmpty())
        experimental.value = true
        s.probe()
        // Probe order: Protocol 4 GET first, then the H-AA version query; then the now-identified state is read.
        assertArrayEquals(JblProtocol.P4.getDeviceInfo(listOf(4, 12)), ch.written[0])
        assertArrayEquals(Hex.decode("aa 21 01 30"), ch.written[1])
        assertEquals("JBL H-AA", s.state.value.protocol)
    }
}
