package com.soniccontrol.core.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPairStreamTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ev(s: String) = FastPairSession { 1L }.onFrame(hex(s))

    @Test fun modelId() = assertEquals(FastPairEvent.ModelId("AABBCC"), ev("03010003AABBCC"))
    @Test fun bleAddress() = assertEquals(FastPairEvent.BleAddress("AA:BB:CC:DD:EE:FF"), ev("03020006AABBCCDDEEFF"))

    @Test fun batteryLevels() {
        val b = (ev("0303000357417F") as FastPairEvent.Battery).status
        assertEquals(87, b.left?.percent); assertEquals(65, b.right?.percent); assertNull(b.case?.percent)
        assertEquals(false, b.left!!.charging); assertEquals(false, b.right!!.charging)
    }

    @Test fun batteryCharging() {
        val b = (ev("03030003D7C1FF") as FastPairEvent.Battery).status
        assertEquals(87, b.left?.percent); assertEquals(65, b.right?.percent)
        assertEquals(true, b.left!!.charging); assertEquals(true, b.right!!.charging); assertNull(b.case?.percent)
    }

    @Test fun remainingTime() {
        assertEquals(FastPairEvent.RemainingMinutes(240), ev("03040001F0"))
        assertEquals(FastPairEvent.RemainingMinutes(300), ev("03040002012C"))
    }

    @Test fun activeComponents() {
        assertEquals(FastPairEvent.Active(ActiveComponents(left = false, right = false)), ev("0306000100"))
        assertEquals(FastPairEvent.Active(ActiveComponents(left = false, right = true)), ev("0306000101"))
        assertEquals(FastPairEvent.Active(ActiveComponents(left = true, right = false)), ev("0306000102"))
        assertEquals(FastPairEvent.Active(ActiveComponents(left = true, right = true)), ev("0306000103"))
    }

    @Test fun firmware() = assertEquals(FastPairEvent.Firmware("1.2.3"), ev("03090005312E322E33"))

    @Test fun invalidFramesChangeNothing() {
        val s = FastPairSession { 1L }
        assertNull(s.onFrame(hex("0303000457417F")))          // length says 4, only 3 bytes
        assertNull(s.onFrame(hex("0303000")))                   // incomplete
        assertNull(s.onFrame(hex("0302000 5AABBCCDDEE".replace(" ", ""))))  // wrong address length
        assertNull(s.onFrame(hex("0306000104")))                // undefined bits
        assertNull(s.onFrame(hex("0303000264 41".replace(" ", ""))))  // two battery values are not valid
        assertTrue(s.capabilities.codes.isEmpty())
    }

    @Test fun invalidChargeIsUnknownNotAPercentage() {
        for (v in 101..126) {
            val b = (ev("03030001" + v.toString(16).padStart(2, '0')) as FastPairEvent.Battery).status
            assertNull("level $v", b.single?.percent)
        }
    }

    @Test fun unknownCodeIsReportedAndNotACapability() {
        val s = FastPairSession { 1L }
        assertEquals(FastPairEvent.Unknown(3, 0x7E), s.onFrame(hex("037E0000")))
        assertTrue(s.capabilities.codes.isEmpty())
        s.onFrame(hex("03040001F0"))
        assertTrue(s.capabilities.remainingTime)
    }

    @Test fun fragmentedAndCoalescedStream() {
        val a = hex("03010003AABBCC"); val b = hex("0306000103")
        val all = a + b
        // two messages in one buffer
        val (frames, used) = FastPair.split(all)
        assertEquals(2, frames.size); assertEquals(all.size, used)
        // fragmented: only the first 5 bytes, then the rest
        val (none, usedNone) = FastPair.split(all.copyOfRange(0, 5))
        assertTrue(none.isEmpty()); assertEquals(0, usedNone)
        val (rest, _) = FastPair.split(all)
        assertEquals(2, rest.size)
    }

    @Test fun corruptHeaderResynchronises() {
        val good = hex("0306000103")
        val junk = hex("03 01 FF FF".replace(" ", "")) // declares 65 535 bytes
        val (frames, used) = FastPair.split(junk + good)
        assertEquals(1, frames.size)
        assertEquals(junk.size + good.size, used)
        assertTrue(frames[0].contentEquals(good))
    }

    @Test fun bufferLimitIsEnforcedByPayloadCap() {
        val over = hex("0303" + (FastPair.MAX_PAYLOAD + 1).toString(16).padStart(4, '0')) + ByteArray(8)
        assertNull(FastPair.parse(over))
    }

    @Test fun onlyTheActiveComponentsRequestMayBeWritten() {
        assertTrue(FastPair.isAllowedWrite(hex("03050000")))
        assertFalse(FastPair.isAllowedWrite(hex("03050001 00".replace(" ", ""))))
        assertFalse(FastPair.isAllowedWrite(hex("0301 0000".replace(" ", ""))))
        assertFalse(FastPair.isAllowedWrite(byteArrayOf()))
    }

    @Test fun psmMustBeInDynamicRange() {
        assertEquals(0x80, FastPair.parsePsm(hex("8000")))
        assertEquals(0xC1, FastPair.parsePsm(hex("00C1")))
        assertNull(FastPair.parsePsm(hex("0001")))
        assertNull(FastPair.parsePsm(hex("0102030405")))
    }
}
