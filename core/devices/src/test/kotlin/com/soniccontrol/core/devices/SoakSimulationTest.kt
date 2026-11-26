package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK 010: a 12-hour run in virtual time, modelled on the v0.8.12 field log (4 h 17 min, 321 RFCOMM opens, 20 failed GATT
 * reconnects, 0 recoveries). The headset closes every Fast Pair stream after 5 s (as the Tune Beam 2 does); GATT drops after
 * 1 h and stays unreachable for 2 h; RFCOMM fails for an hour; then the audio profile reconnects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoakSimulationTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun Job.activeDescendants(): Int = children.sumOf { (if (it.isActive) 1 else 0) + it.activeDescendants() }
    private val hour = 60 * 60_000L

    @Test fun twelveHoursStayBoundedRecoverAndLeakNothing() = runTest {
        val clock = { testScheduler.currentTime }
        val coordinator = ConnectionCoordinator(clock)
        val sessionJob = Job(backgroundScope.coroutineContext[Job])
        val sessionScope = CoroutineScope(backgroundScope.coroutineContext + sessionJob)
        val rf = StandardUuids.FAST_PAIR_MESSAGE_STREAM

        val openTimes = mutableListOf<Long>()
        var openNow = 0
        var maxOpen = 0
        var rfcommDown = false
        val link = FakeLink(sdp = setOf(rf), services = setOf(StandardUuids.BATTERY_SERVICE), characteristics = mapOf(StandardUuids.BATTERY_LEVEL to hex("50")))
        link.rfcommFactory = {
            if (rfcommDown) null else {
                openTimes += testScheduler.currentTime
                val t = FakeTransport()
                openNow++; maxOpen = maxOf(maxOpen, openNow)
                backgroundScope.launch {
                    delay(100)                               // the reader subscribes first; the headset speaks right after
                    t.push(hex("03 03 00 03 64 5d 5b"))   // battery: 100 / 93 / 91
                    delay(4_900)
                    t.close(); openNow--
                }
                t
            }
        }

        val env = DriverEnvironment(sessionScope, MutableStateFlow(false), MutableStateFlow(true), DiagnosticSink { }, clock, periodicRefresh = true, coordinator = coordinator)
        val s = object : BaseDriverSession(link, DeviceIdentity(link.address, "Fake"), env, "t", HeadphoneState("Fake")) {
            override suspend fun start() = startUniversalSources()
        }
        s.start(); runCurrent()

        advanceTimeBy(1 * hour); runCurrent()
        val baselineCoroutines = sessionJob.activeDescendants()

        // GATT lost for 2 h; every reconnect fails meanwhile
        link.gattStateFlow.value = ChannelState.CLOSED
        advanceTimeBy(2 * hour); runCurrent()
        val callsWhileDown = link.reconnectCalls
        assertTrue("GATT attempts bounded while down: $callsWhileDown", callsWhileDown in 5..40)

        // audio profile reconnects: one immediate trial, which succeeds
        link.reconnectResults += true
        coordinator.onRelevantEvent("audio connected"); runCurrent()
        assertNull(s.state.value.live.reconnect)
        assertEquals(1, s.state.value.live.reconnects)

        // RFCOMM fails for an hour: the circuit opens instead of hammering
        rfcommDown = true
        val opensBefore = link.opened.size
        advanceTimeBy(1 * hour); runCurrent()
        assertTrue("failed opens bounded: ${link.opened.size - opensBefore}", link.opened.size - opensBefore <= 10)
        rfcommDown = false
        coordinator.onRelevantEvent("Bluetooth ready")

        // steady state after recovery: sample live coroutines every hour; a leak would grow with every refresh (~12+/h)
        val samples = mutableListOf<Int>()
        repeat(8) { advanceTimeBy(1 * hour); runCurrent(); samples += sessionJob.activeDescendants() }

        // every rolling hour within budget, never two streams at once
        val perHourMax = openTimes.maxOfOrNull { start -> openTimes.count { it in start until start + hour } } ?: 0
        assertTrue("max opens in an hour: $perHourMax", perHourMax <= 40)
        assertTrue("streams kept being refreshed: ${openTimes.size}", openTimes.size >= 24)
        assertEquals("never two Fast Pair streams at the same time", 1, maxOpen)
        // nothing piles up: tracked sockets and live coroutines stay flat over 12 h
        assertTrue("tracked transports: ${s.trackedTransports}", s.trackedTransports <= 1)
        // An open stream adds a handful of short-lived coroutines (reader, framing); a leak would add one set per refresh, i.e. hundreds.
        assertTrue("coroutines bounded over 8 h: baseline $baselineCoroutines, hourly $samples", samples.max() <= baselineCoroutines + 6 && samples.count { it == baselineCoroutines } >= 4)
        // data is current at the end
        assertTrue(s.state.value.battery?.left?.isCurrent(testScheduler.currentTime) == true)
        sessionJob.cancel()
    }
}
