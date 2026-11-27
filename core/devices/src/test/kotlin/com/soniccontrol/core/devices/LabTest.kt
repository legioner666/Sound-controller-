package com.soniccontrol.core.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabTest {
    private fun x(id: Int, before: NoiseMode?, after: NoiseMode?, side: EarSide = EarSide.LEFT, tap: TapType = TapType.SINGLE) =
        GestureExperiment(id, side, tap, 0, before, 1, after)

    @Test fun twoStateCycleIsFound() {
        val f = CycleAnalyzer.analyze(listOf(x(1, NoiseMode.ANC, NoiseMode.AMBIENT), x(2, NoiseMode.AMBIENT, NoiseMode.ANC), x(3, NoiseMode.ANC, NoiseMode.AMBIENT))).single()
        assertEquals(CycleFinding.Verdict.CYCLE, f.verdict)
        assertEquals(listOf(NoiseMode.ANC, NoiseMode.AMBIENT), f.cycle)
    }

    @Test fun threeStateCycleWithOff() {
        val f = CycleAnalyzer.analyze(listOf(x(1, NoiseMode.OFF, NoiseMode.ANC), x(2, NoiseMode.ANC, NoiseMode.AMBIENT), x(3, NoiseMode.AMBIENT, NoiseMode.OFF))).single()
        assertEquals(listOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.AMBIENT), f.cycle)
    }

    @Test fun openChainIsNotClaimedAsACycle() {
        val f = CycleAnalyzer.analyze(listOf(x(1, NoiseMode.OFF, NoiseMode.ANC), x(2, NoiseMode.ANC, NoiseMode.AMBIENT), x(3, NoiseMode.OFF, NoiseMode.ANC))).single()
        assertEquals(CycleFinding.Verdict.NOT_ENOUGH_DATA, f.verdict); assertNull(f.cycle)
    }

    @Test fun inconsistentNoChangeAndTooFew() {
        assertEquals(CycleFinding.Verdict.INCONSISTENT, CycleAnalyzer.analyze(listOf(x(1, NoiseMode.ANC, NoiseMode.AMBIENT), x(2, NoiseMode.ANC, NoiseMode.OFF), x(3, NoiseMode.AMBIENT, NoiseMode.ANC))).single().verdict)
        assertEquals(CycleFinding.Verdict.NO_CHANGE, CycleAnalyzer.analyze(listOf(x(1, NoiseMode.ANC, NoiseMode.ANC), x(2, NoiseMode.OFF, NoiseMode.OFF), x(3, NoiseMode.ANC, NoiseMode.ANC))).single().verdict)
        assertEquals(CycleFinding.Verdict.NOT_ENOUGH_DATA, CycleAnalyzer.analyze(listOf(x(1, NoiseMode.ANC, NoiseMode.AMBIENT))).single().verdict)
        // Runs without a reading on either side do not count.
        assertEquals(CycleFinding.Verdict.NOT_ENOUGH_DATA, CycleAnalyzer.analyze(listOf(x(1, null, NoiseMode.ANC), x(2, NoiseMode.ANC, null), x(3, NoiseMode.ANC, NoiseMode.AMBIENT))).single().verdict)
    }

    @Test fun gesturesAreGroupedBySideAndTap() {
        val f = CycleAnalyzer.analyze(listOf(x(1, NoiseMode.ANC, NoiseMode.AMBIENT), x(2, NoiseMode.OFF, NoiseMode.TALK_THRU, EarSide.RIGHT, TapType.DOUBLE)))
        assertEquals(2, f.size)
    }

    @Test fun recorderKeepsOnlyChangesAndExperimentFrames() {
        val r = LabRecorder()
        val s0 = HeadphoneState("JBL", noise = NoiseStatus(NoiseMode.OFF, DataSource.VENDOR_PROTOCOL, 1))
        r.onState(s0, 1); assertTrue(r.timeline.value.isEmpty())          // not recording
        r.setRecording(true)
        r.onState(s0, 2); r.onState(s0, 3)                                 // a poll repeating the state adds nothing
        assertTrue(r.timeline.value.isEmpty())
        r.onState(s0.copy(noise = NoiseStatus(NoiseMode.ANC, DataSource.VENDOR_PROTOCOL, 4)), 4)
        assertEquals(LabEventType.NOISE_MODE, r.timeline.value.single().type)
        r.setRecording(false)
        r.startExperiment(EarSide.LEFT, TapType.SINGLE, NoiseMode.ANC, 10)
        r.onFrame(11, "listening mode: AMBIENT", "aa 91 09 12 01 00 02 01 03 00 04 07")
        val done = r.finishExperiment(NoiseMode.AMBIENT, 12)!!
        assertEquals(NoiseMode.AMBIENT, done.after)
        assertTrue(done.observed.any { it.type == LabEventType.FRAME })
        assertNull(r.finishExperiment(NoiseMode.OFF, 13))                  // nothing running
    }

    @Test fun intermediateModesAreKeptAndMultiStepRunsAreNotACycleOrNoChange() {
        // The owner's run #2 (lab package, build 20): ANC -> AMBIENT -> ANC within one experiment.
        val r = LabRecorder()
        r.startExperiment(EarSide.LEFT, TapType.SINGLE, NoiseMode.ANC, 0)
        r.onFrame(1, "candidate touch event 0x23", "aa 23 03 01 02 03", touchCandidate = true)
        r.onState(HeadphoneState("JBL", noise = NoiseStatus(NoiseMode.AMBIENT, DataSource.VENDOR_PROTOCOL, 2)), 2)
        r.onFrame(3, "candidate touch event 0x23", "aa 23 03 01 02 03", touchCandidate = true)
        r.onState(HeadphoneState("JBL", noise = NoiseStatus(NoiseMode.ANC, DataSource.VENDOR_PROTOCOL, 4)), 4)
        val x = r.finishExperiment(NoiseMode.ANC, 5)!!
        assertEquals(listOf(NoiseMode.ANC, NoiseMode.AMBIENT, NoiseMode.ANC), x.path)
        assertEquals(2, x.transitions); assertEquals(2, x.touchCandidates)
        val f = CycleAnalyzer.analyze(listOf(x(1, NoiseMode.OFF, NoiseMode.ANC), x, x(3, NoiseMode.AMBIENT, NoiseMode.ANC).copy(path = listOf(NoiseMode.AMBIENT, NoiseMode.ANC)))).single()
        assertEquals(listOf(listOf(NoiseMode.ANC, NoiseMode.AMBIENT, NoiseMode.ANC)), f.multiStepPaths)
        assertTrue(f.transitions.none { it.first == it.second })     // never recorded as ANC -> ANC
        assertEquals(CycleFinding.Verdict.NOT_ENOUGH_DATA, f.verdict)
    }

    @Test fun gestureWritePolicy() {
        val known = SideGestures(GestureLayout.AMBIENT_SOUND, emptyList(), "06 09 07 04 08 00 0c a1")
        val unknown = SideGestures(null, emptyList(), "06 05 07 05 08 05 0c 05")
        val ok = GestureState(known, known.copy(layout = GestureLayout.PLAYBACK), DataSource.VENDOR_NATIVE_EXPERIMENTAL, 1)
        assertNull(GestureWritePolicy.refusal(null, ok))
        assertEquals(GestureWritePolicy.Refusal.NOT_READ, GestureWritePolicy.refusal(null, null))
        assertEquals(GestureWritePolicy.Refusal.UNKNOWN_CURRENT, GestureWritePolicy.refusal(null, ok.copy(left = unknown)))
        val badBackup = GestureBackup(null, GestureLayout.PLAYBACK, unknown.rawHex, "09 08 0a 05 0b 06 0e a1", 1)
        assertEquals(GestureWritePolicy.Refusal.UNRESTORABLE_BACKUP, GestureWritePolicy.refusal(badBackup, ok))
    }
}
