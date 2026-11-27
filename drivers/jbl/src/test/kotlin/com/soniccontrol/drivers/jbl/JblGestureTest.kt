package com.soniccontrol.drivers.jbl

import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.DeviceIdentity
import com.soniccontrol.core.devices.DeviceSetting
import com.soniccontrol.core.devices.DriverEnvironment
import com.soniccontrol.core.devices.DriverSession
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.FakeLink
import com.soniccontrol.core.devices.FakeTransport
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.GestureAction
import com.soniccontrol.core.devices.GestureLayout
import com.soniccontrol.core.devices.NoiseLevelKind
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.devices.TapType
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TASK 014. Vectors: the published PacketLogger capture of the JBL app with a JBL Tune Beam 2 (jbl-on-mac #39/#40) and our own
 * Tune Beam 2 captures (B484FB, fw 0.2.8.0). Expected bytes are written here by hand from those captures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JblGestureTest {
    private fun h(s: String) = Hex.decode(s)
    private fun hx(b: ByteArray) = Hex.encode(b)

    // ---------------------------------------------------------------- codecs

    @Test fun gestureFramesMatchTheCapture() {
        assertEquals("aa 77 02 01 ff", hx(JblHarman.gestureQuery(null)))
        assertEquals("aa 77 02 01 fd", hx(JblHarman.gestureQuery(EarSide.LEFT)))
        assertEquals("aa 77 02 01 fe", hx(JblHarman.gestureQuery(EarSide.RIGHT)))
        val expected = mapOf(
            (EarSide.LEFT to GestureLayout.PLAYBACK) to "aa 77 09 00 06 08 07 05 08 06 0c a1",
            (EarSide.LEFT to GestureLayout.VOLUME) to "aa 77 09 00 06 01 07 02 08 00 0c a1",
            (EarSide.LEFT to GestureLayout.NONE) to "aa 77 09 00 06 00 07 00 08 00 0c 00",
            (EarSide.LEFT to GestureLayout.AMBIENT_SOUND) to "aa 77 09 00 06 09 07 04 08 00 0c a1",
            (EarSide.RIGHT to GestureLayout.AMBIENT_SOUND) to "aa 77 09 00 09 0b 0a 04 0b 00 0e a1",
            (EarSide.RIGHT to GestureLayout.PLAYBACK) to "aa 77 09 00 09 08 0a 05 0b 06 0e a1",
            (EarSide.RIGHT to GestureLayout.VOLUME) to "aa 77 09 00 09 01 0a 02 0b 00 0e a1",
            (EarSide.RIGHT to GestureLayout.NONE) to "aa 77 09 00 09 00 0a 00 0b 00 0e 00",
        )
        for ((k, v) in expected) assertEquals("$k", v, hx(JblHarman.gestureSet(k.first, k.second)))
    }

    @Test fun gestureReportsDecode() {
        val both = JblHarman.parseGestureReport(h("aa 77 11 02 06 09 07 04 08 00 0c a1 09 01 0a 02 0b 00 0e a1"))!!
        assertEquals(GestureLayout.AMBIENT_SOUND, both[EarSide.LEFT]?.layout)
        assertEquals(GestureLayout.VOLUME, both[EarSide.RIGHT]?.layout)
        val l = both.getValue(EarSide.LEFT).slots
        assertEquals(listOf(TapType.SINGLE, TapType.DOUBLE, TapType.TRIPLE, TapType.HOLD), l.map { it.tap })
        assertEquals(listOf(GestureAction.NOISE_CYCLE, GestureAction.TALK_THRU, GestureAction.NONE, GestureAction.VOICE_ASSISTANT), l.map { it.action })
        assertEquals(GestureLayout.PLAYBACK, JblHarman.parseGestureReport(h("aa 77 09 02 06 08 07 05 08 06 0c a1"))!![EarSide.LEFT]?.layout)
        assertEquals(GestureLayout.AMBIENT_SOUND, JblHarman.parseGestureReport(h("aa 77 09 02 09 0b 0a 04 0b 00 0e a1"))!![EarSide.RIGHT]?.layout)
        assertEquals(GestureLayout.NONE, JblHarman.parseGestureReport(h("aa 77 09 02 09 00 0a 00 0b 00 0e 00"))!![EarSide.RIGHT]?.layout)
    }

    @Test fun unknownCombinationIsKeptRawAndNeverGuessed() {
        val m = JblHarman.parseGestureReport(h("aa 77 09 02 06 05 07 05 08 05 0c 05"))!!
        assertNull(m.getValue(EarSide.LEFT).layout)
        assertEquals("06 05 07 05 08 05 0c 05", m.getValue(EarSide.LEFT).rawHex)
        // A left-side "ambient" action on the right earbud is not the right earbud's ambient layout.
        assertNull(JblHarman.parseGestureReport(h("aa 77 09 02 09 09 0a 04 0b 00 0e a1"))!![EarSide.RIGHT]?.layout)
    }

    @Test fun malformedGestureFramesAreRejected() {
        assertNull(JblHarman.parseGestureReport(h("aa 77 09 00 06 01 07 02 08 00 0c a1")))     // our write echoed: not a report
        assertTrue(JblHarman.isGestureEcho(h("aa 77 09 00 06 01 07 02 08 00 0c a1")))
        assertNull(JblHarman.parseGestureReport(h("aa 77 09 02 06 01 07 02 08 00")))           // truncated
        assertNull(JblHarman.parseGestureReport(h("aa 77 0a 02 06 01 07 02 08 00 0c a1")))     // length byte wrong
        assertNull(JblHarman.parseGestureReport(h("aa 77 09 02 06 01 06 02 08 00 0c a1")))     // duplicate slot
        assertNull(JblHarman.parseGestureReport(h("aa 77 09 02 06 01 07 02 08 00 0d a1")))     // foreign slot id
        assertNull(JblHarman.parseGestureReport(h("aa 77 09 02 06 01 07 02 09 00 0b a1")))     // sides mixed
        assertNull(JblProtocol.P3.validate(h("aa 77 01 00")))
        assertNotNull(JblProtocol.P3.validate(h("aa 77 09 02 06 08 07 05 08 06 0c a1")))
    }

    @Test fun levelsAndTheAncLevelInTheModeReport() {
        assertEquals("aa 91 03 20 04 05", hx(JblHarman.levelSet(NoiseLevelKind.ANC, 5)))
        assertEquals("aa 91 03 20 a1 07", hx(JblHarman.levelSet(NoiseLevelKind.AMBIENT, 7)))
        assertEquals(mapOf(NoiseLevelKind.ANC to 5, NoiseLevelKind.AMBIENT to 3), JblHarman.parseLevelReport(h("aa 91 05 22 04 05 a1 03")))
        assertNull(JblHarman.parseLevelReport(h("aa 91 03 22 04 08")))           // out of range
        assertNull(JblHarman.parseLevelReport(h("aa 91 03 22 05 03")))           // unknown id
        assertNull(JblHarman.parseLevelReport(h("aa 91 05 22 04 05 04 03")))     // duplicate
        // Our own Tune Beam 2 capture: the slot we could not explain before is the ANC level.
        val f = JblProtocol.P3.validate(h("aa 91 09 12 01 00 02 00 03 00 04 07"))!!
        assertEquals(7, JblHarman.ancLevelInModeReport(f))
        assertNull(JblHarman.ancLevelInModeReport(JblProtocol.P3.validate(h("aa 91 09 12 01 01 02 00 03 00 04 ff"))!!))
        assertTrue(runCatching { JblHarman.levelSet(NoiseLevelKind.ANC, 8) }.isFailure)
    }

    @Test fun readOnlySettingsDecode() {
        assertEquals("aa 06 03 00 01 00 0c", hx(JblHarman.featureQuery(JblHarman.FEATURE_SPATIAL)))
        assertEquals("aa 06 03 00 01 00 20", hx(JblHarman.featureQuery(JblHarman.FEATURE_VOLUME_LIMITER)))
        assertEquals("head_tracking", JblHarman.parseFeatureReport(h("aa 06 06 00 02 00 0c 02 01 03"))!![0x0C]!!.let(JblHarman::spatialOf))
        assertEquals("on, level 2", JblHarman.parseFeatureReport(h("aa 06 06 00 02 00 0d 02 01 02"))!![0x0D]!!.let(JblHarman::voiceAwareOf))
        assertEquals("on", JblHarman.parseFeatureReport(h("aa 06 05 00 02 00 20 01 01"))!![0x20]!!.let(JblHarman::limiterOf))
        assertNull(JblHarman.parseFeatureReport(h("aa 06 06 00 02 00 0c 02 01")))       // truncated
        assertEquals("audio", JblHarman.parseSmartAudio(h("aa 80 09 02 00 01 2e 00 18 01 ff ff")))
        assertEquals("normal", JblHarman.parseSmartAudio(h("aa 80 09 02 c5 00 2e 00 18 01 ff ff")))
        assertEquals("video", JblHarman.parseSmartAudio(h("aa 80 09 02 c5 00 2e 00 78 00 ff ff")))
        assertNull(JblHarman.parseSmartAudio(h("aa 80 09 02 01 02 03 04 05 06 07 08")))
        val eq = h("aa a2 74 00 04 03" + " 00".repeat(114))
        assertEquals("bass", JblHarman.parseEqReport(eq))
        assertNull(JblHarman.parseEqReport(h("aa a2 74 00 04 ff" + " 00".repeat(114))))
        assertNull(JblHarman.parseEqReport(h("aa a2 74 00 04")))
    }

    @Test fun anEchoedShortLongLengthFrameDoesNotStallTheStream() {
        val (frames, used) = JblProtocol.splitHarman(h("aa a2 02 01 ff aa 91 09 12 01 01 02 00 03 00 04 07"))
        assertEquals(2, frames.size); assertEquals(17, used)
        assertEquals("aa 91 09 12 01 01 02 00 03 00 04 07", hx(frames[1]))
        val (eq, _) = JblProtocol.splitHarman(h("aa a2 74 00 04 03" + " 00".repeat(114)))
        assertEquals(120, eq.single().size)
    }

    // ---------------------------------------------------------------- guard: no free-form bytes

    @Test fun onlyCapturedFramesMayLeaveThePhone() {
        listOf("aa 77 02 01 ff", "aa 77 09 00 06 08 07 05 08 06 0c a1", "aa 91 03 20 a1 07", "aa 91 01 21", "aa 06 03 00 01 00 0d", "aa 80 01 01", "aa a2 02 01 ff")
            .forEach { assertNull(it, JblGuard.refusal(h(it))) }
        listOf(
            "aa 77 09 00 06 05 07 05 08 05 0c 05",     // a combination no captured layout has
            "aa 77 09 00 06 09 07 04 08 00 0c a1 09 01", // two sides in one write
            "aa 77 0b 00 06 01 07 02 08 00 0c a1 0e a1", // the Tune 230NC dialect is not ported
            "aa 91 03 20 04 08",                         // level out of range
            "aa 06 05 00 00 00 20 01 01",                // feature WRITE (limiter): not implemented
            "aa 80 09 00 00 01 2e 00 18 01 ff ff",       // Smart Audio write: not implemented
            "aa 36 01 11",                               // find-my-buds of another model
            "aa 95 00",                                  // factory reset
        ).forEach { assertNotNull(it, JblGuard.refusal(h(it))) }
    }

    // ---------------------------------------------------------------- session

    /** A scripted Tune Beam 2: keeps the layouts per earbud and answers like the captures. */
    private class Headset(var left: String = "06 09 07 04 08 00 0c a1", var right: String = "09 08 0a 05 0b 06 0e a1") {
        var ignoreWrites = false
        var alsoChangeOther = false
        var reportAfterWrite = true
        var ancLevel = 7
        fun transport() = FakeTransport { req ->
            val r = Hex.encode(req)
            when {
                r == "aa 91 01 11" -> listOf(Hex.decode("aa 91 09 12 01 00 02 00 03 00 04 %02x".format(ancLevel)))
                r == "aa 9b 02 01 01" -> listOf(Hex.decode("aa ee 01 9b"))
                r == "aa 25 01 00" -> listOf(Hex.decode("aa ee 01 25"))
                r.startsWith("aa 77 02 01") -> if (!reportAfterWrite) emptyList() else listOf(Hex.decode("aa 77 11 02 $left $right"))
                r.startsWith("aa 77 09 00") -> {
                    val body = r.removePrefix("aa 77 09 00 ")
                    if (!ignoreWrites) {
                        if (body.startsWith("06")) left = body else right = body
                        if (alsoChangeOther) { if (body.startsWith("06")) right = "09 00 0a 00 0b 00 0e 00" else left = "06 00 07 00 08 00 0c 00" }
                    }
                    listOf(req)                                       // echo
                }
                r.startsWith("aa 91 03 20 04") -> { ancLevel = req[5].toInt(); emptyList() }
                else -> emptyList()
            }
        }
    }

    private fun TestScope.session(ch: FakeTransport, name: String = "JBL Tune Beam 2", experimental: Boolean = true): Pair<DriverSession, FakeLink> {
        val link = FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch)
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(experimental), MutableStateFlow(false), DiagnosticSink { }, { testScheduler.currentTime })
        return JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", name), env) to link
    }

    @Test fun tuneBeam2ShowsGestureSupportAsExperimental() = runTest(UnconfinedTestDispatcher()) {
        val (s, _) = session(Headset().transport()); s.start()
        assertEquals(SupportStatus.EXPERIMENTAL, s.state.value.capabilities.of(Feature.TOUCH_GESTURES).status)
        assertEquals(GestureLayout.entries.toList(), s.state.value.gestureSupport?.layouts)
        assertFalse(s.state.value.gestureSupport!!.reportsTapEvents)
        assertEquals("7", s.state.value.settings[DeviceSetting.ANC_LEVEL]?.value)      // from the mode report read on start
    }

    @Test fun readsBothEarbuds() = runTest(UnconfinedTestDispatcher()) {
        val (s, _) = session(Headset().transport()); s.start()
        assertTrue(s.readGestures() is CommandOutcome.Confirmed)
        assertEquals(GestureLayout.AMBIENT_SOUND, s.state.value.gestures?.left?.layout)
        assertEquals(GestureLayout.PLAYBACK, s.state.value.gestures?.right?.layout)
    }

    @Test fun experimentalOffSendsNothing() = runTest(UnconfinedTestDispatcher()) {
        val ch = Headset().transport()
        val (s, _) = session(ch, experimental = false); s.start()
        assertTrue(s.readGestures() is CommandOutcome.Rejected)
        assertTrue(s.setGestureLayout(EarSide.LEFT, GestureLayout.VOLUME) is CommandOutcome.Rejected)
        assertTrue(ch.written.none { Hex.encode(it).startsWith("aa 77") })
    }

    @Test fun changesOneEarbudAndConfirmsByReadingBack() = runTest(UnconfinedTestDispatcher()) {
        val ch = Headset().transport()
        val (s, _) = session(ch); s.start()
        val r = s.setGestureLayout(EarSide.LEFT, GestureLayout.VOLUME)
        assertTrue("$r", r is CommandOutcome.Confirmed)
        assertEquals(GestureLayout.VOLUME, s.state.value.gestures?.left?.layout)
        assertEquals(GestureLayout.PLAYBACK, s.state.value.gestures?.right?.layout)
        val sent = ch.written.map { Hex.encode(it) }.filter { it.startsWith("aa 77") }
        assertEquals(listOf("aa 77 02 01 ff", "aa 77 09 00 06 01 07 02 08 00 0c a1", "aa 77 02 01 ff"), sent)
    }

    @Test fun ignoredWriteIsAFailureNotASuccess() = runTest(UnconfinedTestDispatcher()) {
        val hs = Headset().apply { ignoreWrites = true }
        val (s, _) = session(hs.transport()); s.start()
        val r = s.setGestureLayout(EarSide.LEFT, GestureLayout.VOLUME)
        assertTrue("$r", r is CommandOutcome.Failed)
        assertEquals(GestureLayout.AMBIENT_SOUND, s.state.value.gestures?.left?.layout)      // the reported state, not the request
    }

    @Test fun otherEarbudChangingIsAFailure() = runTest(UnconfinedTestDispatcher()) {
        val hs = Headset().apply { alsoChangeOther = true }
        val (s, _) = session(hs.transport()); s.start()
        assertTrue(s.setGestureLayout(EarSide.LEFT, GestureLayout.VOLUME) is CommandOutcome.Failed)
    }

    @Test fun noReadBackMeansUnknownNotDone() = runTest(UnconfinedTestDispatcher()) {
        val hs = Headset().apply { reportAfterWrite = false }
        val (s, _) = session(hs.transport()); s.start()
        val r = s.setGestureLayout(EarSide.RIGHT, GestureLayout.NONE)
        assertFalse(r is CommandOutcome.Confirmed)
    }

    @Test fun unknownInitialLayoutIsNeverOverwritten() = runTest(UnconfinedTestDispatcher()) {
        // Audit of d6f29ed: a combination the app cannot restore must block every write, on either earbud.
        val hs = Headset(left = "06 05 07 05 08 05 0c 05")
        val ch = hs.transport()
        val (s, _) = session(ch); s.start()
        assertTrue(s.readGestures() is CommandOutcome.Confirmed)
        assertNull(s.state.value.gestures?.left?.layout)
        assertTrue(s.setGestureLayout(EarSide.RIGHT, GestureLayout.VOLUME) is CommandOutcome.Failed)
        assertTrue(s.setGestureLayout(EarSide.LEFT, GestureLayout.PLAYBACK) is CommandOutcome.Failed)
        assertTrue(ch.written.none { Hex.encode(it).startsWith("aa 77 09") })
        assertEquals("06 05 07 05 08 05 0c 05", hs.left)
    }

    @Test fun touchCandidateIsLabelledButNeverAReport() {
        val f = h("aa 23 03 01 02 03")
        assertTrue(JblHarman.isTouchCandidate(f))
        assertTrue(JblHarman.describe(f)!!.startsWith("candidate touch event"))
        assertNull(JblProtocol.P3.validate(f))                    // never confirms the channel or the state
        assertFalse(JblHarman.isTouchCandidate(h("aa 23 04 01 02 03 04")))
    }

    @Test fun sameLayoutIsNotWrittenAgain() = runTest(UnconfinedTestDispatcher()) {
        val ch = Headset().transport()
        val (s, _) = session(ch); s.start()
        assertTrue(s.setGestureLayout(EarSide.RIGHT, GestureLayout.PLAYBACK) is CommandOutcome.Confirmed)
        assertTrue(ch.written.none { Hex.encode(it).startsWith("aa 77 09") })
    }

    @Test fun otherModelsGetNoGestureCommands() = runTest(UnconfinedTestDispatcher()) {
        val ch = Headset().transport()
        val (s, _) = session(ch, name = "JBL Tune 230NC TWS"); s.start()
        assertNull(s.state.value.gestureSupport)
        assertTrue(s.readGestures() is CommandOutcome.Unsupported)
        assertTrue(s.setGestureLayout(EarSide.LEFT, GestureLayout.VOLUME) is CommandOutcome.Unsupported)
        assertTrue(ch.written.none { Hex.encode(it).startsWith("aa 77") })
    }

    @Test fun sameNameButOtherFastPairModelIdIsOtherHardware() = runTest(UnconfinedTestDispatcher()) {
        val ch = Headset().transport()
        val fp = FakeTransport()
        val uuid = com.soniccontrol.core.devices.StandardUuids.FAST_PAIR_MESSAGE_STREAM
        val link = FakeLink(services = setOf(JblProtocol.HARMAN_SERVICE), gattChannel = ch, sdp = setOf(uuid), rfcomm = mapOf(uuid to fp))
        val env = DriverEnvironment(backgroundScope, MutableStateFlow(true), MutableStateFlow(true), DiagnosticSink { }, { testScheduler.currentTime })
        val s = JblDriver().createSession(link, DeviceIdentity("AA:BB:CC:00:11:22", "JBL Tune Beam 2"), env)
        s.start(); testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertNotNull(s.state.value.gestureSupport)
        fp.push(Hex.decode("03 01 00 03 12 34 56"))                                  // a different model id
        testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals("123456", s.state.value.modelId)
        assertNull(s.state.value.gestureSupport)
        assertTrue(s.readGestures() is CommandOutcome.Unsupported)
        assertTrue(ch.written.none { Hex.encode(it).startsWith("aa 77") })
    }

    @Test fun ancLevelIsConfirmedFromTheModeReport() = runTest(UnconfinedTestDispatcher()) {
        val (s, _) = session(Headset().transport()); s.start()
        val r = s.setNoiseLevel(NoiseLevelKind.ANC, 5)
        assertTrue("$r", r is CommandOutcome.Confirmed)
        assertEquals("5", s.state.value.settings[DeviceSetting.ANC_LEVEL]?.value)
    }

    @Test fun ambientLevelWithoutAConfirmedQueryIsNotReportedAsDone() = runTest(UnconfinedTestDispatcher()) {
        val (s, _) = session(Headset().transport()); s.start()
        assertEquals(CommandOutcome.SentUnconfirmed, s.setNoiseLevel(NoiseLevelKind.AMBIENT, 3))
    }

    @Test fun readSettingsDecodesWhatTheHeadsetAnswers() = runTest(UnconfinedTestDispatcher()) {
        val ch = FakeTransport { req ->
            when (Hex.encode(req)) {
                "aa 91 01 11" -> listOf(h("aa 91 09 12 01 01 02 00 03 00 04 06"))
                "aa 06 03 00 01 00 0c" -> listOf(h("aa 06 06 00 02 00 0c 02 01 01"))
                "aa 80 01 01" -> listOf(h("aa 80 09 02 c5 00 2e 00 78 00 ff ff"))
                "aa a2 02 01 ff" -> listOf(h("aa a2 74 00 04 07" + " 00".repeat(114)))
                else -> emptyList()
            }
        }
        val (s, _) = session(ch); s.start()
        assertTrue(s.readSettings() is CommandOutcome.Confirmed)
        val st = s.state.value.settings
        assertEquals("6", st[DeviceSetting.ANC_LEVEL]?.value)
        assertEquals("off", st[DeviceSetting.SPATIAL_SOUND]?.value)
        assertEquals("video", st[DeviceSetting.SMART_AUDIO_VIDEO]?.value)
        assertEquals("studio", st[DeviceSetting.EQ_PRESET]?.value)
        assertNull(st[DeviceSetting.VOICE_AWARE])                                    // no answer: nothing invented
        assertEquals(ChannelState.CONFIRMED, s.state.value.control)
    }
}
