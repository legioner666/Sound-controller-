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
}
