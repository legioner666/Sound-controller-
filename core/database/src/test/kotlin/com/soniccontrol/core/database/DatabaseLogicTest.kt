package com.soniccontrol.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseLogicTest {
    private fun rec(minutes: Int, pct: Int) = BatteryRecord("d", minutes * 60_000L, pct, pct + 2, null, null, "FAST_PAIR_STREAM")

    @Test fun dischargeRateNeedsRealData() {
        assertNull(BatteryHistory.dischargeRatePerHour(listOf(rec(0, 90), rec(10, 89))))
        val run = listOf(rec(0, 100), rec(15, 95), rec(30, 90), rec(45, 85), rec(60, 80))
        assertEquals(20.0, BatteryHistory.dischargeRatePerHour(run)!!, 0.01)
        assertEquals(4.0, BatteryHistory.remainingHours(80, 20.0)!!, 0.01)
    }

    @Test fun rateUsesOnlyLatestDischargeRun() {
        val run = listOf(rec(0, 50), rec(30, 100), rec(60, 95), rec(90, 90))
        assertEquals(10.0, BatteryHistory.dischargeRatePerHour(run)!!, 0.01)
    }

    @Test fun appendOnlyOnChangeOrInterval() {
        assertTrue(BatteryHistory.shouldAppend(null, rec(0, 50)))
        assertFalse(BatteryHistory.shouldAppend(rec(0, 50), rec(1, 50)))
        assertTrue(BatteryHistory.shouldAppend(rec(0, 50), rec(1, 49)))
        assertTrue(BatteryHistory.shouldAppend(rec(0, 50), rec(6, 50)))
    }

    @Test fun codecsRoundTripAndClamp() {
        val p = EqProfile("x", "My|Name", List(10) { 20f })
        val back = Codecs.decodeProfiles(Codecs.encodeProfiles(listOf(p))).single()
        assertEquals("My Name", back.name)
        assertEquals(EqProfile.MAX_DB, back.gainsDb.max(), 0f)
        val h = listOf(rec(1, 40))
        assertEquals(h, Codecs.decodeHistory(Codecs.encodeHistory(h)))
    }
}
