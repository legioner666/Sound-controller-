package com.soniccontrol.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolUtilsTest {
    @Test fun hexRoundTrip() {
        val b = Hex.decode("aa 21:01-30")
        assertArrayEquals(bytesOf(0xAA, 0x21, 0x01, 0x30), b)
        assertEquals("aa 21 01 30", b.toHex())
    }

    @Test(expected = IllegalArgumentException::class) fun hexRejectsOddLength() { Hex.decode("abc") }

    @Test fun crc16ModbusMatchesNothingReferenceFrame() {
        // Reference frame "get noise control": 55 60 01 1E C0 00 00 01 | B1 1D
        assertEquals(0x1DB1, Crc.crc16Modbus(Hex.decode("5560011EC0000001")))
    }

    @Test fun jsonEscapesAndNests() {
        val s = Json.stringify(mapOf("a" to "q\"\n\\", "n" to listOf(1, null, true)), indent = 0)
        assertEquals("{\"a\":\"q\\\"\\n\\\\\",\"n\":[1,null,true]}", s)
    }

    @Test fun streamBufferJoinsFragments() {
        val buf = StreamBuffer { b -> if (b.size >= 3) listOf(b.copyOfRange(0, 3)) to 3 else emptyList<ByteArray>() to 0 }
        assertEquals(0, buf.feed(bytesOf(1, 2), 0).size)
        val out = buf.feed(bytesOf(3, 4), 10)
        assertEquals(1, out.size)
        assertArrayEquals(bytesOf(1, 2, 3), out[0])
        assertEquals(1, buf.pending())
    }

    @Test fun streamBufferDropsStaleData() {
        val buf = StreamBuffer(maxAgeMillis = 100) { b -> if (b.size >= 3) listOf(b.copyOfRange(0, 3)) to 3 else emptyList<ByteArray>() to 0 }
        buf.feed(bytesOf(1, 2), 0)
        assertEquals(0, buf.feed(bytesOf(3), 500).size)
    }
}
