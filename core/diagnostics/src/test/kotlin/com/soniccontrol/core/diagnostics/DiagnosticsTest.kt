package com.soniccontrol.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun anonymizerIsStableAndSalted() {
        val a = Anonymizer("salt1")
        assertEquals(a.address("AA:BB:CC:DD:EE:FF"), a.address("aa:bb:cc:dd:ee:ff"))
        assertNotEquals(a.address("AA:BB:CC:DD:EE:FF"), Anonymizer("salt2").address("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun reportContainsNoRawAddress() {
        val log = DiagnosticLog(clock = { 1L })
        log.log(EventCategory.GATT, "connected to 12:34:56:78:9A:BC", address = "12:34:56:78:9A:BC")
        val json = DiagnosticReport.build("0.1", 34, "Phone", 1L, mapOf("note" to "seen 12:34:56:78:9A:BC"), log.events.value, Anonymizer("s"))
        assertFalse(json.contains("12:34:56:78:9A:BC"))
        assertTrue(json.contains("dev-"))
        assertTrue(json.contains("sonic-control-diagnostics"))
    }

    @Test fun logIsBounded() {
        val log = DiagnosticLog(capacity = 3, clock = { 0L })
        repeat(5) { log.log(EventCategory.INFO, "e$it") }
        assertEquals(listOf("e2", "e3", "e4"), log.events.value.map { it.message })
    }
}
