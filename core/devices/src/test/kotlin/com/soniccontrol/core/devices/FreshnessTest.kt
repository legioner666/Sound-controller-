package com.soniccontrol.core.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 010: each reading is judged by its own age; an old charging=true is never "charging now". */
class FreshnessTest {
    private val min = 60_000L

    @Test fun oldChargingIsNotCurrent() {
        val l = BatteryLevel(80, charging = true, updatedMillis = 0, chargingUpdatedMillis = 0)
        assertEquals(true, l.currentCharging(5 * min))
        assertNull("88 minutes later it is history", l.currentCharging(88 * min))
        assertNull("last-known never shows charging", l.copy(lastKnown = true).currentCharging(1 * min))
    }

    @Test fun chargingUsesItsOwnTimeNotThePercentTime() {
        val l = BatteryLevel(80, charging = true, updatedMillis = 50 * min, chargingUpdatedMillis = 0)
        assertNull(l.currentCharging(55 * min))
        assertTrue(l.isCurrent(55 * min))
    }

    @Test fun percentFreshness() {
        val l = BatteryLevel(70, updatedMillis = 0)
        assertTrue(l.isCurrent(9 * min))
        assertFalse(l.isCurrent(11 * min))
        assertFalse(BatteryLevel(null).isCurrent(0))
    }

    private fun device(lastRx: Long?, activeAt: Long? = null) = ActiveDevice(
        DeviceIdentity("00:11:22:33:44:55", "Fake"), "t", "T", DriverMatch(1, null, "x"), ConnectionState.Active,
        HeadphoneState("Fake", live = LiveInfo(lastRxMillis = lastRx), active = ActiveComponents(left = true, right = false), activeUpdatedMillis = activeAt),
        LinkStatus(audio = AudioLinkState.CONNECTED),
    )

    /** v0.8.12 log: audio connected, last RX 5290 s ago — must read as audio on, data outdated, control unavailable. */
    @Test fun audioTelemetryAndControlAreSeparate() {
        val d = device(lastRx = 0, activeAt = 0)
        val now = 5_290_000L
        assertEquals(AudioLinkState.CONNECTED, d.links.audio)
        assertEquals(TelemetryStatus.STALE, d.telemetryStatus(now))
        assertEquals(ControlStatus.UNAVAILABLE, d.controlStatus)
        assertNull("active earbuds from 88 min ago are not current", d.currentActive(now))
        assertEquals(TelemetryStatus.LIVE, d.telemetryStatus(5 * min))
        assertEquals(TelemetryStatus.NONE, device(null).telemetryStatus(now))
    }
}
