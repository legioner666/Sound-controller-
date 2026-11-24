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
        assertTrue(json.contains("aa 12 06 xx xx xx xx xx xx"))
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
