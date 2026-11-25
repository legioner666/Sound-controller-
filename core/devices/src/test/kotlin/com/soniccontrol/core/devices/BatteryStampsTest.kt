package com.soniccontrol.core.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 009: percent, charging and source keep independent times. */
class BatteryStampsTest {
    private fun st(l: BatteryLevel?, t: Long, src: DataSource = DataSource.FAST_PAIR_STREAM) = BatteryStatus(left = l, source = src, timestampMillis = t)

    @Test fun chargingFlagGetsItsOwnTimeAndPercentKeepsItsOwn() {
        val a = BatteryMerge.prefer(null, st(BatteryLevel(80, false), 1_000))
        val b = BatteryMerge.prefer(a, st(BatteryLevel(null, true), 5_000))        // report without percent: only charging is new
        val l = b.left!!
        assertEquals(80, l.percent); assertTrue(l.lastKnown); assertEquals(1_000L, l.updatedMillis)
        assertEquals(true, l.charging); assertEquals(5_000L, l.chargingUpdatedMillis)
        assertEquals(DataSource.FAST_PAIR_STREAM, l.source)
    }

    @Test fun newPercentWithoutChargingKeepsOldChargingTime() {
        val a = BatteryMerge.prefer(null, st(BatteryLevel(80, true), 1_000))
        val b = BatteryMerge.prefer(a, st(BatteryLevel(79, null), 9_000, DataSource.VENDOR_NATIVE_EXPERIMENTAL))
        val l = b.left!!
        assertEquals(79, l.percent); assertEquals(9_000L, l.updatedMillis); assertEquals(false, l.lastKnown)
        assertEquals(true, l.charging); assertEquals(1_000L, l.chargingUpdatedMillis)
        assertEquals(DataSource.VENDOR_NATIVE_EXPERIMENTAL, l.source)
    }

    @Test fun chargingTimeIsNullWhenNeverReported() {
        val l = BatteryMerge.prefer(null, st(BatteryLevel(50, null), 2_000)).left!!
        assertNull(l.chargingUpdatedMillis); assertEquals(2_000L, l.updatedMillis)
    }
}
