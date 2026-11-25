package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.bytesOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDevicesTest {
    private val sink = DiagnosticSink { }

    private fun cmd(status: SupportStatus, matcher: ResponseMatcher? = ResponseMatcher { it[0] == 0x42.toByte() }) =
        DeviceCommand("t", CommandKind.READ, status, bytesOf(1, 2), 200, matcher)

    @Test fun confirmedOnlyWithMatchingReply() = runTest {
        val t = FakeTransport { listOf(bytesOf(0x41), bytesOf(0x42, 0x01)) }
        val r = CommandExecutor(t, { null }, { false }, sink, { 0 }).execute(cmd(SupportStatus.SUPPORTED))
        assertTrue(r is CommandOutcome.Confirmed)
        assertEquals(0x01.toByte(), (r as CommandOutcome.Confirmed).response[1])
    }

    @Test fun timeoutWhenNoReply() = runTest {
        val r = CommandExecutor(FakeTransport(), { null }, { false }, sink, { 0 }).execute(cmd(SupportStatus.SUPPORTED))
        assertEquals(CommandOutcome.Timeout, r)
    }

    @Test fun experimentalNeedsOptIn() = runTest {
        val t = FakeTransport { listOf(bytesOf(0x42)) }
        val off = CommandExecutor(t, { null }, { false }, sink, { 0 }).execute(cmd(SupportStatus.EXPERIMENTAL))
        assertTrue(off is CommandOutcome.Rejected)
        assertTrue(t.written.isEmpty())
        val on = CommandExecutor(t, { null }, { true }, sink, { 0 }).execute(cmd(SupportStatus.EXPERIMENTAL))
        assertTrue(on is CommandOutcome.Confirmed)
    }

    @Test fun unsupportedAndBasicAreNeverSent() = runTest {
        val t = FakeTransport()
        val e = CommandExecutor(t, { null }, { true }, sink, { 0 })
        assertTrue(e.execute(cmd(SupportStatus.UNSUPPORTED)) is CommandOutcome.Rejected)
        assertTrue(e.execute(cmd(SupportStatus.BASIC)) is CommandOutcome.Rejected)
        assertTrue(t.written.isEmpty())
    }

    @Test fun guardRefusalStopsWrite() = runTest {
        val t = FakeTransport()
        val r = CommandExecutor(t, { "blocked" }, { true }, sink, { 0 }).execute(cmd(SupportStatus.VERIFIED))
        assertEquals(CommandOutcome.Rejected("blocked"), r)
        assertTrue(t.written.isEmpty())
    }

    @Test fun closedTransportIsNotConnected() = runTest {
        val t = FakeTransport().apply { stateFlow.value = TransportState.Closed }
        assertEquals(CommandOutcome.NotConnected, CommandExecutor(t, { null }, { false }, sink, { 0 }).execute(cmd(SupportStatus.SUPPORTED)))
    }

    @Test fun noMatcherMeansUnconfirmed() = runTest {
        val r = CommandExecutor(FakeTransport(), { null }, { false }, sink, { 0 }).execute(cmd(SupportStatus.SUPPORTED, matcher = null))
        assertEquals(CommandOutcome.SentUnconfirmed, r)
    }

    @Test fun supportLevelIsDerived() {
        assertEquals(SupportLevel.UNIVERSAL, DeviceCapabilities.UNIVERSAL.level)
        val adv = DeviceCapabilities.universalPlus(Feature.NOISE_CONTROL to FeatureSupport(SupportStatus.EXPERIMENTAL))
        assertEquals(SupportLevel.ADVANCED, adv.level)
        val full = DeviceCapabilities.universalPlus(Feature.NOISE_CONTROL to FeatureSupport(SupportStatus.VERIFIED))
        assertEquals(SupportLevel.FULL, full.level)
        assertFalse(adv.usable(Feature.NOISE_CONTROL, experimentalEnabled = false))
        assertTrue(adv.usable(Feature.NOISE_CONTROL, experimentalEnabled = true))
        assertFalse(adv.usable(Feature.DEVICE_EQUALIZER, true))
    }

    @Test fun fastPairBatteryParsing() {
        val (frames, used) = FastPair.split(bytesOf(0x03, 0x03, 0x00, 0x03, 0x0A, 0x7F, 0xA2, 0x03))
        assertEquals(7, used)
        val b = FastPair.parseBattery(FastPair.parse(frames.single())!!, 5)!!
        assertEquals(10, b.left?.percent)
        assertNull(b.right?.percent) // 0x7F = earbud in case
        assertEquals(34, b.case?.percent)
        assertEquals(true, b.case?.charging)
    }

    @Test fun batteryMergeKeepsRicherFreshReading() {
        val rich = BatteryStatus(left = BatteryLevel(50), right = BatteryLevel(60), source = DataSource.FAST_PAIR_STREAM, timestampMillis = 0)
        val single = BatteryStatus(single = BatteryLevel(55), source = DataSource.BLE_BATTERY_SERVICE, timestampMillis = 1000)
        assertEquals(rich.left!!.percent, BatteryMerge.prefer(rich, single).left!!.percent)
        // TASK 006: a single BLE Battery Service value never replaces component readings, however old they are.
        val later = single.copy(timestampMillis = 10 * 60_000L)
        val merged = BatteryMerge.prefer(rich, later)
        assertEquals(50, merged.left!!.percent); assertEquals(60, merged.right!!.percent)
    }

    @Test fun catalogMatchesExactNamesOnly() {
        val c = DeviceCatalog(listOf(
            CatalogEntry(Brand.JBL, "Tune Beam", "jbl", "p", emptyMap()),
            CatalogEntry(Brand.JBL, "Tune Beam 3", "jbl", "p", emptyMap()),
        ))
        assertEquals("Tune Beam 3", c.findByName("LE-JBL Tune Beam 3")?.model)
        assertEquals("Tune Beam", c.findByName("JBL TUNE BEAM")?.model)
        assertNull(c.findByName("JBL Tune Beam 4"))
    }

    @Test fun reconnectBackoff() {
        val p = ReconnectPolicy(1000, 30_000, 8)
        assertEquals(1000L, p.delayFor(1)); assertEquals(2000L, p.delayFor(2)); assertEquals(30_000L, p.delayFor(8))
        assertNull(p.delayFor(9)); assertNull(p.delayFor(0))
    }
}
