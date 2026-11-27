package com.soniccontrol.core.devices

import com.soniccontrol.core.protocol.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK 014: Fast Pair Hearable controls "Notify ANC state" (group 0x08, code 0x13) is read passively and strictly. */
class HearableControlsTest {
    private fun m(hex: String) = FastPair.parse(Hex.decode(hex))!!

    @Test fun specExampleDecodes() {
        // UI toggles transparent+off+ANC, settable the same, current = off (the spec's example 1).
        val a = FastPair.parseHearableAnc(m("08 13 00 04 02 a8 a8 20"))!!
        assertEquals(setOf("transparent", "off", "anc"), a.supported)
        assertEquals("off", a.current)
    }

    @Test fun strictness() {
        assertNull(FastPair.parseHearableAnc(m("08 13 00 04 01 a8 a8 20")))     // other version
        assertNull(FastPair.parseHearableAnc(m("08 13 00 04 02 a8 a8 28")))     // two current bits
        assertNull(FastPair.parseHearableAnc(m("08 13 00 04 02 a9 a8 20")))     // undefined bit
        assertNull(FastPair.parseHearableAnc(m("08 13 00 03 02 a8 a8")))        // short
        assertNull(FastPair.parseHearableAnc(m("08 12 00 04 02 a8 a8 20")))     // Set, not Notify
    }

    @Test fun sessionEventAndNoCapabilityClaim() {
        val s = FastPairSession { 1L }
        val e = s.onFrame(Hex.decode("08 13 00 04 02 a8 a8 08"))
        assertTrue(e is FastPairEvent.HearableAncState)
        assertEquals("anc", (e as FastPairEvent.HearableAncState).anc.current)
        assertTrue(s.capabilities.codes.isEmpty())
        assertTrue(s.onFrame(Hex.decode("08 13 00 04 02 ff ff ff")) is FastPairEvent.Unknown)
    }
}
