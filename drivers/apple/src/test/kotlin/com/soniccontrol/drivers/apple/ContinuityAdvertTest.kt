package com.soniccontrol.drivers.apple

import com.soniccontrol.core.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ContinuityAdvertTest {
    @Test fun parsesBudsAndCase() {
        // type 07, len 19, prefix 01, model 0x1420, status 0x20 (not flipped), buds 0x86 -> right 80, left 60, charge/case 0x45
        val ad = ContinuityAdvert.parse(Hex.decode("07 19 01 14 20 20 86 45 00"))!!
        assertEquals("AirPods Pro (2nd generation)", ad.modelName)
        assertEquals(60, ad.leftPercent); assertEquals(80, ad.rightPercent); assertEquals(50, ad.casePercent)
        assertEquals(true, ad.caseCharging)
    }

    @Test fun ignoresOtherMessages() { assertNull(ContinuityAdvert.parse(Hex.decode("10 05 01 02 03 04 05 06"))) }

    @Test fun strangersAirPodsAreFilteredByName() { assertFalse(AppleModels.fitsName(0x0A20, "My AirPods Pro")) }
}
