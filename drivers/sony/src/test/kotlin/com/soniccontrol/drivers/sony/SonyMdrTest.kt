package com.soniccontrol.drivers.sony

import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.protocol.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyMdrTest {
    @Test fun handshakeAndAckMatchCaptures() {
        assertEquals("3e 0c 00 00 00 00 02 00 00 0e 3c", Hex.encode(SonyMdr.encode(0x0C, 0, SonyMdr.HANDSHAKE)))
        assertEquals("3e 01 01 00 00 00 00 02 3c", Hex.encode(SonyMdr.ack(0)))
    }

    @Test fun ambientLevelSetMatchesCapture() {
        val frame = SonyMdr.encode(0x0C, 1, SonyMdr.ncSetV2(NoiseMode.AMBIENT, 10))
        assertEquals("3e 0c 01 00 00 00 07 68 17 01 01 01 00 0a a0 3c", Hex.encode(frame))
    }

    @Test fun escapingRoundTrip() {
        val payload = Hex.decode("3c 3d 3e 01")
        val raw = SonyMdr.encode(0x0C, 0, payload)
        assertFalse(raw.copyOfRange(1, raw.size - 1).any { it == 0x3C.toByte() || it == 0x3E.toByte() })
        assertArrayEquals(payload, SonyMdr.decode(raw)!!.payload)
    }

    @Test fun badChecksumRejected() {
        assertNull(SonyMdr.decode(Hex.decode("3e 0c 00 00 00 00 02 00 00 0f 3c")))
    }

    @Test fun parsesNotify() {
        assertEquals(SonyMdr.NcState(NoiseMode.AMBIENT, 10), SonyMdr.parseNc(Hex.decode("69 17 01 01 01 00 0a")))
        assertEquals(NoiseMode.OFF, SonyMdr.parseNc(Hex.decode("67 17 01 00 00 00 0a"))?.mode)
        assertEquals(NoiseMode.ANC, SonyMdr.parseNc(Hex.decode("67 02 11 01 02 01 00 00"))?.mode)
    }

    @Test fun allowlist() {
        assertTrue(SonyMdr.isAllowedPayload(SonyMdr.ncGetV2()))
        assertTrue(SonyMdr.isAllowedPayload(SonyMdr.ncSetV2(NoiseMode.ANC, 5)))
        assertFalse(SonyMdr.isAllowedPayload(Hex.decode("68 17 01 01 01 00 30")))
        assertFalse(SonyMdr.isAllowedPayload(Hex.decode("f2 10")))
    }
}
