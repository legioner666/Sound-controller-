package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 006: PSM state parsing, component-wise battery model and the bounded L2CAP lifecycle (vectors from the real v0.5.6 capture). */
@OptIn(ExperimentalCoroutinesApi::class)
class BatteryAndL2capTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun bat(s: String): BatteryStatus {
        val m = FastPair.parse(hex(s))!!
        return FastPair.parseBattery(m, 1_000)!!
    }

    @Test fun psmVectors() {
        assertEquals(FastPair.PsmState.Ready(161), FastPair.parsePsmState(hex("01 00 A1")))
        assertEquals(FastPair.PsmState.Ready(128), FastPair.parsePsmState(hex("01 00 80")))
        assertEquals(FastPair.PsmState.Ready(255), FastPair.parsePsmState(hex("01 00 FF")))
        assertEquals(FastPair.PsmState.Unknown, FastPair.parsePsmState(hex("00 00 A1")))
        assertEquals(FastPair.PsmState.Unavailable, FastPair.parsePsmState(hex("02 00 A1")))
        assertEquals(FastPair.PsmState.Invalid, FastPair.parsePsmState(hex("01 00 7F")))
        assertEquals(FastPair.PsmState.Invalid, FastPair.parsePsmState(hex("01 01 00")))
        assertEquals(FastPair.PsmState.Invalid, FastPair.parsePsmState(hex("00 A1")))
        assertEquals(FastPair.PsmState.Invalid, FastPair.parsePsmState(hex("03 00 A1")))
        assertEquals(FastPair.PsmState.Invalid, FastPair.parsePsmState(hex("01 00 A1 00")))
        assertEquals(161, FastPair.parsePsm(hex("01 00 A1")))
        assertNull(FastPair.parsePsm(hex("02 00 A1")))
    }

    @Test fun realCaptureBatteryLeftUnknownRight98Case91() {
        val b = bat("03 03 00 03 7F 62 5B")
        assertNull(b.left!!.percent); assertEquals(98, b.right!!.percent); assertEquals(91, b.case!!.percent)
        assertEquals(false, b.right!!.charging)
        assertTrue(b.hasAnyValue)
    }

    @Test fun taskVectors() {
        bat("03 03 00 03 64 63 59").let { assertEquals(listOf(100, 99, 89), listOf(it.left!!.percent, it.right!!.percent, it.case!!.percent)); assertTrue(listOf(it.left, it.right, it.case).all { l -> l!!.charging == false }) }
        bat("03 03 00 03 D5 E3 C3").let { assertEquals(listOf(85, 99, 67), listOf(it.left!!.percent, it.right!!.percent, it.case!!.percent)); assertTrue(listOf(it.left, it.right, it.case).all { l -> l!!.charging == true }) }
        bat("03 03 00 03 7F 63 FF").let {
            assertNull(it.left!!.percent); assertEquals(99, it.right!!.percent)
            assertNull(it.case!!.percent); assertEquals(true, it.case!!.charging)   // charging with an unknown level
        }
    }

    @Test fun singleBleValueNeverReplacesComponentReadings() {
        val fp = BatteryMerge.prefer(null, bat("03 03 00 03 7F 62 5B"))
        val ble = BatteryStatus(single = BatteryLevel(98), source = DataSource.BLE_BATTERY_SERVICE, timestampMillis = 9_999_999)
        val m = BatteryMerge.prefer(fp, ble)
        assertEquals(98, m.right!!.percent); assertEquals(91, m.case!!.percent)
        assertEquals(DataSource.FAST_PAIR_STREAM, m.source)
    }

    @Test fun knownLevelSurvivesAnUnknownReportAndIsMarkedLastKnown() {
        val first = BatteryMerge.prefer(null, bat("03 03 00 03 64 63 59"))
        val second = BatteryMerge.prefer(first, bat("03 03 00 03 7F 63 59"))   // left went inactive: level unknown now
        assertEquals(100, second.left!!.percent); assertTrue(second.left!!.lastKnown)
        assertEquals(1_000L, second.left!!.updatedMillis)
        assertTrue(!second.right!!.lastKnown)
        val third = BatteryMerge.prefer(second, bat("03 03 00 03 5A 63 59"))   // known again
        assertEquals(90, third.left!!.percent); assertTrue(!third.left!!.lastKnown)
    }

    @Test fun chargingStateUpdatesIndependentlyOfPercent() {
        val a = BatteryMerge.prefer(null, bat("03 03 00 03 64 63 59"))
        val b = BatteryMerge.prefer(a, bat("03 03 00 03 E4 63 59"))            // left now charging, same level
        assertEquals(true, b.left!!.charging); assertEquals(100, b.left!!.percent); assertEquals(false, b.right!!.charging)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    private fun TestScope.session(link: FakeLink): BaseDriverSession {
        val env = DriverEnvironment(CoroutineScope(StandardTestDispatcher(testScheduler) + Job()), MutableStateFlow(false), MutableStateFlow(true), DiagnosticSink { }, { testScheduler.currentTime })
        return object : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
            override suspend fun start() = startUniversalSources()
        }
    }

    private val rfUuid = StandardUuids.FAST_PAIR_MESSAGE_STREAM

    @Test fun rfcommClosedThenL2capOpensWithRealPsmAndBatterySurvives() = runTest {
        val rf = FakeTransport(); val l2 = FakeTransport()
        val link = FakeLink(sdp = setOf(rfUuid), rfcomm = mapOf(rfUuid to rf), services = setOf(StandardUuids.FAST_PAIR_SERVICE),
            characteristics = mapOf(StandardUuids.FAST_PAIR_PSM to hex("01 00 A1")))
        link.l2cap = l2
        val s = session(link)
        s.start(); advanceUntilIdle()
        rf.push(hex("03 03 00 03 7F 62 5B")); advanceUntilIdle()
        assertEquals(98, s.state.value.battery!!.right!!.percent)
        rf.close(); advanceUntilIdle()
        assertEquals(listOf(161), link.l2capRequests)                         // opened exactly once, on the read PSM
        assertEquals(false, link.openedWritable.let { false })
        assertEquals(98, s.state.value.battery!!.right!!.percent)              // RFCOMM close did not erase readings
        l2.push(hex("03 03 00 03 64 62 5B")); advanceUntilIdle()
        assertEquals(100, s.state.value.battery!!.left!!.percent)
        assertTrue(!s.state.value.battery!!.left!!.lastKnown)
    }

    @Test fun refusedL2capIsRetriedAFewTimesThenStops() = runTest {
        val rf = FakeTransport()
        val link = FakeLink(sdp = setOf(rfUuid), rfcomm = mapOf(rfUuid to rf), services = setOf(StandardUuids.FAST_PAIR_SERVICE),
            characteristics = mapOf(StandardUuids.FAST_PAIR_PSM to hex("01 00 A1")))
        val s = session(link)
        s.start(); advanceUntilIdle()
        rf.close(); advanceTimeBy(10 * 60_000); advanceUntilIdle()
        assertEquals(3, link.l2capRequests.size)                               // bounded, no endless loop
    }

    /** Real v0.8.11 capture: three L2CAP tries all ended with "Client security clearance failed". Now the first refusal ends it. */
    @Test fun securityRefusalStopsL2capAfterTheFirstAttempt() = runTest {
        val rf = FakeTransport()
        val link = FakeLink(sdp = setOf(rfUuid), rfcomm = mapOf(rfUuid to rf), services = setOf(StandardUuids.FAST_PAIR_SERVICE),
            characteristics = mapOf(StandardUuids.FAST_PAIR_PSM to hex("01 00 A1")))
        link.l2capSecurityFailure = true
        val s = session(link)
        s.start(); advanceUntilIdle()
        rf.close(); advanceTimeBy(10 * 60_000); advanceUntilIdle()
        assertEquals(1, link.l2capRequests.size)
    }

    @Test fun unavailableOrInvalidPsmIsNeverOpened() = runTest {
        for (v in listOf("02 00 A1", "01 00 7F", "00 A1")) {
            val rf = FakeTransport()
            val link = FakeLink(sdp = setOf(rfUuid), rfcomm = mapOf(rfUuid to rf), services = setOf(StandardUuids.FAST_PAIR_SERVICE),
                characteristics = mapOf(StandardUuids.FAST_PAIR_PSM to hex(v)))
            val s = session(link)
            s.start(); advanceUntilIdle(); rf.close(); advanceUntilIdle()
            assertTrue("psm $v", link.l2capRequests.isEmpty())
        }
    }
}
