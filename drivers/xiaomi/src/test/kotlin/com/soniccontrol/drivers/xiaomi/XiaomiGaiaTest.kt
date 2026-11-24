package com.soniccontrol.drivers.xiaomi

import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiGaiaTest {
    @Test fun framesMatchCaptures() {
        assertEquals("ff 03 00 00 00 0a 03 00", Hex.encode(XiaomiGaia.handshake()))
        assertEquals("ff 03 00 01 00 1d 10 04 01", Hex.encode(XiaomiGaia.modeSet(NoiseMode.ANC)))
    }

    @Test fun rebootValueIsNeverAllowed() {
        assertFalse(XiaomiGaia.isAllowed(Hex.decode("ff 03 00 01 00 1d 10 04 04")))
        assertTrue(XiaomiGaia.isAllowed(XiaomiGaia.modeGet()))
    }

    @Test fun parsesReply() {
        val f = XiaomiGaia.parse(Hex.decode("ff 03 00 05 00 1d 11 03 02 01 01 00 00"))!!
        assertEquals(NoiseMode.AMBIENT, XiaomiGaia.parseMode(f))
    }
}
