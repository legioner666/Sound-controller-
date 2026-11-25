package com.soniccontrol.core.background

import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorTextTest {
    private val l = MonitorText.Labels("L", "R", "Case", "Headset", "charging", "last known", "reconnecting", "?")

    @Test fun componentsShowUnknownAsQuestionMarkNeverZero() {
        val s = MonitorSummary("Tune Beam 2", true, left = null, right = 98, case = 91)
        assertEquals("L ? · R 98% · Case 91%", MonitorText.body(s, l))
    }

    @Test fun chargingStaleAndReconnectAreAppended() {
        val s = MonitorSummary("X", true, left = 100, right = 99, case = 89, caseCharging = true, stale = true, reconnect = "attempt 2/5")
        assertEquals("L 100% · R 99% · Case 89% ⚡\nlast known · reconnecting: attempt 2/5", MonitorText.body(s, l))
    }

    @Test fun singleBatteryHeadsetAndEmptySummary() {
        assertEquals("Headset 80%", MonitorText.body(MonitorSummary("H", true, single = 80), l))
        assertEquals("", MonitorText.body(MonitorSummary("H", true), l))
    }
}
