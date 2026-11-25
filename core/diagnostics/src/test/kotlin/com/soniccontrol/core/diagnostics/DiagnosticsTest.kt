package com.soniccontrol.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val mac = "12:34:56:78:9A:BC"
    private val redactor = Redactor("s", listOf(mac), listOf("Anna's Tune Beam 3"))

    @Test fun anonymizerIsStableAndSalted() {
        val a = Anonymizer("salt1")
        assertEquals(a.address("AA:BB:CC:DD:EE:FF"), a.address("aa:bb:cc:dd:ee:ff"))
        assertNotEquals(a.address("AA:BB:CC:DD:EE:FF"), Anonymizer("salt2").address("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun reportContainsNoRawAddressOrName() {
        val log = DiagnosticLog(clock = { 1L })
        log.log(EventCategory.GATT, "connected to $mac (Anna's Tune Beam 3)", address = mac)
        val nameHex = "Anna's Tune Beam 3".toByteArray().joinToString(" ") { "%02x".format(it) }
        log.log(EventCategory.RX, "frame", hex = "aa 12 06 bc 9a 78 56 34 12 $nameHex", address = mac)
        val json = DiagnosticReport.build("0.1", 34, "Phone", 1L, mapOf("name" to "Anna's Tune Beam 3", "valueHex" to "12 34 56 78 9a bc"), log.events.value, redactor)
        assertFalse(json.contains(mac))
        assertFalse(json.contains("Anna"))
        assertFalse(json.contains("bc 9a 78 56 34 12"))
        assertFalse(json.contains("12 34 56 78 9a bc"))
        assertFalse(json.contains(nameHex.substring(0, 20)))
        assertTrue(json.contains("<aa cmd=0x12 length=")) // unknown frame: shape only, no bytes
        assertTrue(json.contains("dev-"))
    }

    @Test fun hexKeepsProtocolBytes() {
        assertEquals("aa 25 01 00", redactor.hex("aa 25 01 00"))
        assertEquals("<unparseable>", redactor.hex("zz"))
    }

    @Test fun identifyingCharacteristicsDropped() {
        assertEquals("<redacted>", redactor.characteristicValue("00002a25-0000-1000-8000-00805f9b34fb", "31 32 33"))
        assertEquals("31 32 33", redactor.characteristicValue("00002a26-0000-1000-8000-00805f9b34fb", "31 32 33"))
    }

    @Test fun nameMatchIsCaseInsensitive() {
        assertEquals("<device-name> left", redactor.text("ANNA'S TUNE BEAM 3 left"))
    }

    @Test fun logIsBounded() {
        val log = DiagnosticLog(capacity = 3, clock = { 0L })
        repeat(5) { log.log(EventCategory.INFO, "e$it") }
        assertEquals(listOf("e2", "e3", "e4"), log.events.value.map { it.message })
    }
}

class HexPolicyTest {
    private val r = Redactor("salt", listOf("AA:BB:CC:00:11:22"), listOf("Anna's buds"))
    private fun ex(msg: String, hex: String) = HexPolicy.export(msg, hex, r)

    @org.junit.Test fun knownSafeFramesKeepTheirBytes() {
        org.junit.Assert.assertEquals("03 03 00 03 7f 62 5b", ex("[jbl] Fast Pair Battery", "03 03 00 03 7f 62 5b"))
        org.junit.Assert.assertEquals("03 06 00 01 01", ex("x", "03 06 00 01 01"))
        org.junit.Assert.assertEquals("03 01 00 03 b4 84 fb", ex("x", "03 01 00 03 b4 84 fb"))
        org.junit.Assert.assertEquals("aa 91 09 12 01 00 02 00 03 00 04 07", ex("x", "aa 91 09 12 01 00 02 00 03 00 04 07"))
        org.junit.Assert.assertEquals("aa ee 01 9b", ex("x", "aa ee 01 9b"))
        org.junit.Assert.assertEquals("aa 9b 02 01 01", ex("x", "aa 9b 02 01 01"))
    }

    @org.junit.Test fun bleAddressPayloadIsNeverExported() {
        org.junit.Assert.assertEquals("03 02 00 06 xx xx xx xx xx xx", ex("x", "03 02 00 06 aa bb cc 00 11 22"))
        org.junit.Assert.assertEquals("03 02 00 06 xx xx xx xx xx xx", ex("x", "03 02 00 06 12 34 56 78 9a bc"))   // even an address nobody knows
    }

    @org.junit.Test fun unknownMessagesExportOnlyTheirShape() {
        org.junit.Assert.assertEquals("<fast-pair group=0x3 code=0xb length=24>", ex("x", "03 0b 00 18 " + (1..24).joinToString(" ") { "%02x".format(it) }))
        org.junit.Assert.assertEquals("<fast-pair group=0x3 code=0xa length=8>", ex("x", "03 0a 00 08 " + (1..8).joinToString(" ") { "%02x".format(it) }))
        org.junit.Assert.assertEquals("<fast-pair group=0x7 code=0x10 length=4>", ex("x", "07 10 00 04 01 02 03 04"))
        org.junit.Assert.assertEquals("<aa cmd=0x12 length=20>", ex("x", "aa 12 14 " + (1..20).joinToString(" ") { "%02x".format(it) }))
        org.junit.Assert.assertEquals("<5 bytes, header de ad>", ex("x", "de ad be ef 01"))
    }

    @org.junit.Test fun longAndMalformedInputIsBoundedAndIdentifiersMasked() {
        org.junit.Assert.assertEquals("<100 bytes>", ex("x", (1..100).joinToString(" ") { "%02x".format(it) }))
        org.junit.Assert.assertEquals("<unparseable>", ex("x", "zz 01"))
        org.junit.Assert.assertEquals("<unparseable>", ex("x", "1ff 01"))
        // a truncated Fast Pair frame (length mismatch) falls back to the generic shape
        org.junit.Assert.assertEquals("<6 bytes, header 03 03>", ex("x", "03 03 00 09 01 02"))
    }

    @org.junit.Test fun gattReadsAreAllowlistedByCharacteristic() {
        org.junit.Assert.assertEquals("62", ex("read 00002a19-0000-1000-8000-00805f9b34fb", "62"))
        org.junit.Assert.assertEquals("30 2e 32", ex("read 00002a26-0000-1000-8000-00805f9b34fb", "30 2e 32"))
        org.junit.Assert.assertEquals("<6 bytes>", ex("read 00002a23-0000-1000-8000-00805f9b34fb", "aa bb cc 00 11 22"))
        org.junit.Assert.assertEquals("01 00 a1", ex("read fe2c1239-8366-4814-8eb0-01de32100bea", "01 00 a1"))
    }
}
