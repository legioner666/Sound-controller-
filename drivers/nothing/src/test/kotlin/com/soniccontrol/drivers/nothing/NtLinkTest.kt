package com.soniccontrol.drivers.nothing

import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtLinkTest {
    @Test fun noiseGetMatchesReferenceFrame() {
        assertEquals("55 60 01 1e c0 00 00 01 b1 1d", Hex.encode(NtLink.noiseGet()))
    }

    @Test fun batteryParsing() {
        val payload = Hex.decode("03 02 55 03 0f 04 d5")
        val frame = NtLink.frame(0x07, NtLink.DIR_ANSWER, payload)
        val b = NtLink.parseBattery(NtLink.parse(frame)!!, 0)!!
        assertEquals(85, b.left?.percent); assertEquals(15, b.right?.percent); assertEquals(85, b.case?.percent)
        assertEquals(true, b.case?.charging)
    }

    @Test fun modes() {
        val f = NtLink.parse(NtLink.frame(0x1E, NtLink.DIR_ANSWER, Hex.decode("01 07 00")))!!
        assertEquals(NoiseMode.AMBIENT, NtLink.parseNoise(f)?.first)
        assertTrue(NtLink.isAllowed(NtLink.noiseSet(NoiseMode.OFF)))
        assertFalse(NtLink.isAllowed(NtLink.frame(0x40, NtLink.DIR_SET, Hex.decode("01"))))
    }

    @Test fun corruptedCrcRejected() {
        val f = NtLink.noiseGet(); f[f.size - 1] = 0
        assertEquals(null, NtLink.parse(f))
    }
}
