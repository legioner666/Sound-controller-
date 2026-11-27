package com.soniccontrol.core.automation

import com.soniccontrol.core.automation.AutomationEvent.Battery
import com.soniccontrol.core.automation.AutomationEvent.DeviceConnected
import com.soniccontrol.core.automation.AutomationEvent.DeviceDisconnected
import com.soniccontrol.core.automation.AutomationEvent.ManualNoiseChange
import com.soniccontrol.core.automation.AutomationEvent.MinuteTick
import com.soniccontrol.core.automation.AutomationEvent.NoiseModeObserved
import com.soniccontrol.core.automation.AutomationEvent.Playback
import com.soniccontrol.core.automation.AutomationEvent.SleepTimerStarted
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.TapType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationEngineTest {
    private val dev = "AA:BB:CC:00:11:22"

    /** A fake world: the headset's noise mode, the player, and every action the engine ran. */
    private inner class World(val scope: TestScope) {
        var noise: NoiseMode? = NoiseMode.OFF
        var playing = false
        var active: String? = dev
        var minute = 8 * 60
        var noiseResult: ActionResult = ActionResult.Done
        val ran = mutableListOf<Pair<String, ActionKind>>()
        val engine = AutomationEngine(scope.backgroundScope, { scope.testScheduler.currentTime }, { AutomationSnapshot(active, noise, playing, minute) }, ActionRunner { a, r ->
            ran += r.id to a.kind
            when (a.kind) {
                ActionKind.SET_NOISE_MODE -> { if (noiseResult == ActionResult.Done) noise = a.noiseMode; noiseResult }
                ActionKind.PAUSE_PLAYER -> { playing = false; ActionResult.Done }
                ActionKind.RESUME_PLAYER -> { playing = true; ActionResult.Done }
                else -> ActionResult.Done
            }
        })
        fun skips() = engine.journal.value.filter { it.outcome == JournalOutcome.SKIPPED }.map { it.ruleId to it.skip }
    }

    private fun rule(id: String, trigger: Trigger, vararg actions: Action, priority: Int = 0, delay: Int = 0, device: String? = null, conditions: List<Condition> = emptyList(), created: Long = 0) =
        Rule(id, "rule $id", trigger, actions.toList(), conditions, true, priority, delay, device, created)
    private fun anc() = Action(ActionKind.SET_NOISE_MODE, noiseMode = NoiseMode.ANC)
    private fun ambient() = Action(ActionKind.SET_NOISE_MODE, noiseMode = NoiseMode.AMBIENT)

    @Test fun connectTriggersOnceDespiteRepeatedBluetoothEvents() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("c", Trigger(TriggerKind.DEVICE_CONNECTED), anc())))
        w.engine.onEvent(DeviceConnected(dev)); w.engine.onEvent(DeviceConnected(dev))
        testScheduler.advanceTimeBy(5_000); w.engine.onEvent(DeviceConnected(dev))
        assertEquals(listOf("c" to ActionKind.SET_NOISE_MODE), w.ran)
        testScheduler.advanceTimeBy(60_000); w.noise = NoiseMode.OFF; w.engine.onEvent(DeviceConnected(dev))
        assertEquals(2, w.ran.size)                                   // a real reconnection later fires again
    }

    @Test fun deviceBindingIsRespected() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("b", Trigger(TriggerKind.DEVICE_CONNECTED), anc(), device = "11:22:33:44:55:66")))
        w.engine.onEvent(DeviceConnected(dev))
        assertTrue(w.ran.isEmpty())
    }

    @Test fun ownNoiseChangeNeverTriggersNoiseRulesSoNoLoop() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(
            rule("a", Trigger(TriggerKind.DEVICE_CONNECTED), anc()),
            rule("loop", Trigger(TriggerKind.NOISE_MODE_CHANGED, noiseMode = NoiseMode.ANC), ambient()),
        ))
        w.engine.onEvent(NoiseModeObserved(dev, NoiseMode.OFF))      // first reading
        w.engine.onEvent(DeviceConnected(dev))
        w.engine.onEvent(NoiseModeObserved(dev, NoiseMode.ANC))       // the result of rule a
        assertEquals(listOf("a" to ActionKind.SET_NOISE_MODE), w.ran)
    }

    @Test fun manualChangeOnTheHeadsetWinsAndPollingNeverRevertsIt() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(
            rule("t", Trigger(TriggerKind.PLAYBACK_STARTED), anc()),
        ))
        w.engine.onEvent(NoiseModeObserved(dev, NoiseMode.OFF))
        w.engine.onEvent(Playback(false))
        w.noise = NoiseMode.AMBIENT
        w.engine.onEvent(NoiseModeObserved(dev, NoiseMode.AMBIENT))   // user tapped the earbud
        repeat(12) { testScheduler.advanceTimeBy(5_000); w.engine.onEvent(NoiseModeObserved(dev, NoiseMode.AMBIENT)) }   // 5-s polls
        w.engine.onEvent(Playback(true))
        assertTrue(w.ran.isEmpty())
        assertTrue(w.skips().contains("t" to SkipReason.MANUAL_OVERRIDE))
        testScheduler.advanceTimeBy(AutomationEngine.DEFAULT_MANUAL_HOLD_MILLIS)
        w.engine.onEvent(Playback(false)); testScheduler.advanceTimeBy(5_000); w.engine.onEvent(Playback(true))
        assertEquals(listOf("t" to ActionKind.SET_NOISE_MODE), w.ran)  // after the hold, rules work again
    }

    @Test fun manualChangeInTheAppAlsoHasPriority() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("t", Trigger(TriggerKind.PLAYBACK_STARTED), anc())))
        w.engine.onEvent(Playback(false))
        w.engine.onEvent(ManualNoiseChange)
        w.engine.onEvent(Playback(true))
        assertTrue(w.ran.isEmpty())
    }

    @Test fun conflictsAreResolvedByPriorityThenAge() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(
            rule("low", Trigger(TriggerKind.DEVICE_CONNECTED), ambient(), priority = 1),
            rule("high", Trigger(TriggerKind.DEVICE_CONNECTED), anc(), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 40), priority = 5),
            rule("old", Trigger(TriggerKind.DEVICE_CONNECTED), Action(ActionKind.APPLY_EQ_PROFILE, eqProfileId = "bass"), created = 1),
            rule("new", Trigger(TriggerKind.DEVICE_CONNECTED), Action(ActionKind.APPLY_EQ_PROFILE, eqProfileId = "voice"), created = 2),
        ))
        w.engine.onEvent(DeviceConnected(dev))
        assertTrue(("high" to ActionKind.SET_NOISE_MODE) in w.ran)
        assertTrue(("old" to ActionKind.APPLY_EQ_PROFILE) in w.ran)
        assertTrue(w.ran.none { it.first == "low" || it.first == "new" })
        assertEquals(setOf("low" to SkipReason.CONFLICT, "new" to SkipReason.CONFLICT), w.skips().toSet())
    }

    @Test fun staleNoiseDataFailsTheCondition() = runTest {
        val w = World(this)
        w.noise = null                                                // no fresh confirmed reading
        w.engine.setRules(listOf(rule("s", Trigger(TriggerKind.PLAYBACK_STARTED), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 30),
            conditions = listOf(Condition(ConditionKind.NOISE_MODE_IS, noiseMode = NoiseMode.ANC)))))
        w.engine.onEvent(Playback(false)); w.engine.onEvent(Playback(true))
        assertTrue(w.ran.isEmpty())
        assertEquals(listOf("s" to SkipReason.STALE_DATA), w.skips())
    }

    @Test fun batteryFiresOnceOnTheCrossingWithHysteresis() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("bat", Trigger(TriggerKind.BATTERY_LOW, part = BatteryPart.LEFT, thresholdPercent = 20), Action(ActionKind.PAUSE_PLAYER))))
        w.engine.onEvent(Battery(dev, 25, 90, 50))
        w.engine.onEvent(Battery(dev, 19, 90, 50))
        w.engine.onEvent(Battery(dev, 18, 90, 50)); w.engine.onEvent(Battery(dev, 21, 90, 50)); w.engine.onEvent(Battery(dev, 19, 90, 50))
        assertEquals(1, w.ran.size)
        w.engine.onEvent(Battery(dev, null, 90, 50))                  // stale: nothing decided
        testScheduler.advanceTimeBy(3_600_000)                         // charged in the case for an hour
        w.engine.onEvent(Battery(dev, 40, 90, 50)); testScheduler.advanceTimeBy(3_600_000); w.engine.onEvent(Battery(dev, 15, 90, 50))
        assertEquals(2, w.ran.size)                                   // re-armed after charging
    }

    @Test fun ownPauseDoesNotTriggerPlaybackRules() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(
            rule("p", Trigger(TriggerKind.PLAYBACK_STARTED), Action(ActionKind.PAUSE_PLAYER)),
            rule("q", Trigger(TriggerKind.PLAYBACK_STOPPED), Action(ActionKind.RESUME_PLAYER)),
        ))
        w.engine.onEvent(Playback(false)); w.engine.onEvent(Playback(true))
        w.engine.onEvent(Playback(false))                              // caused by rule p
        assertEquals(listOf("p" to ActionKind.PAUSE_PLAYER), w.ran)
    }

    @Test fun ownSleepTimerDoesNotTriggerSleepRules() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(
            rule("start", Trigger(TriggerKind.DEVICE_CONNECTED), Action(ActionKind.START_SLEEP_TIMER, minutes = 30)),
            rule("onTimer", Trigger(TriggerKind.SLEEP_TIMER_STARTED), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 30)),
        ))
        w.engine.onEvent(DeviceConnected(dev)); w.engine.onEvent(SleepTimerStarted)
        assertEquals(listOf("start" to ActionKind.START_SLEEP_TIMER), w.ran)
        testScheduler.advanceTimeBy(60_000); w.engine.onEvent(SleepTimerStarted)   // started by the user later
        assertEquals(2, w.ran.size)
    }

    @Test fun rateLimitStopsARunawayRule() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("r", Trigger(TriggerKind.PLAYBACK_STARTED), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 50))))
        repeat(6) { w.engine.onEvent(Playback(false)); testScheduler.advanceTimeBy(4_000); w.engine.onEvent(Playback(true)); testScheduler.advanceTimeBy(4_000) }
        assertEquals(AutomationEngine.RATE_LIMIT, w.ran.size)
        assertTrue(w.skips().any { it.second == SkipReason.RATE_LIMIT })
    }

    @Test fun delayedActionIsRecheckedAndCancelledOnDisconnect() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(
            rule("d", Trigger(TriggerKind.DEVICE_CONNECTED), anc(), delay = 30),
            rule("cond", Trigger(TriggerKind.PLAYBACK_STARTED), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 20), delay = 10,
                conditions = listOf(Condition(ConditionKind.PLAYER_PLAYING))),
        ))
        w.engine.onEvent(DeviceConnected(dev))
        w.engine.onEvent(DeviceDisconnected(dev))
        testScheduler.advanceTimeBy(31_000); testScheduler.runCurrent()
        assertTrue(w.ran.isEmpty())
        assertTrue(w.skips().contains("d" to SkipReason.DEVICE_DISCONNECTED))
        w.playing = true
        w.engine.onEvent(Playback(false)); w.engine.onEvent(Playback(true))
        w.playing = false                                              // stopped before the delay ends
        testScheduler.advanceTimeBy(11_000); testScheduler.runCurrent()
        assertTrue(w.ran.isEmpty())
        assertTrue(w.skips().contains("cond" to SkipReason.CONDITION))
    }

    @Test fun timeOfDayFiresOncePerDayOnChosenDays() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("m", Trigger(TriggerKind.TIME_OF_DAY, minuteOfDay = 7 * 60, days = setOf(1, 2, 3, 4, 5)), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 40))))
        w.engine.onEvent(MinuteTick(7 * 60, 1, 100)); w.engine.onEvent(MinuteTick(7 * 60, 1, 100))
        testScheduler.advanceTimeBy(86_400_000); w.engine.onEvent(MinuteTick(7 * 60, 6, 105))   // Saturday
        testScheduler.advanceTimeBy(86_400_000); w.engine.onEvent(MinuteTick(7 * 60, 2, 101))
        assertEquals(2, w.ran.size)
    }

    @Test fun alreadyInStateSendsNothingAndUnconfirmedIsNotDone() = runTest {
        val w = World(this)
        w.noise = NoiseMode.ANC
        w.engine.setRules(listOf(rule("x", Trigger(TriggerKind.DEVICE_CONNECTED), anc())))
        w.engine.onEvent(DeviceConnected(dev))
        assertTrue(w.ran.isEmpty())
        w.noise = NoiseMode.OFF; w.noiseResult = ActionResult.Unconfirmed
        testScheduler.advanceTimeBy(60_000); w.engine.onEvent(DeviceConnected(dev))
        assertEquals(JournalOutcome.UNCONFIRMED, w.engine.journal.value.last().outcome)
    }

    @Test fun gestureRulesMatchSideAndTap() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("g", Trigger(TriggerKind.GESTURE, side = EarSide.LEFT, tap = TapType.TRIPLE), Action(ActionKind.PAUSE_PLAYER))))
        w.engine.onEvent(AutomationEvent.Gesture(dev, EarSide.RIGHT, TapType.TRIPLE))
        w.engine.onEvent(AutomationEvent.Gesture(dev, EarSide.LEFT, TapType.TRIPLE))
        assertEquals(1, w.ran.size)
    }

    @Test fun invalidRulesNeverRun() = runTest {
        val w = World(this)
        w.engine.setRules(listOf(rule("bad", Trigger(TriggerKind.DEVICE_CONNECTED), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 150))))
        w.engine.onEvent(DeviceConnected(dev))
        assertTrue(w.ran.isEmpty())
        assertEquals(listOf("bad" to SkipReason.INVALID_RULE), w.skips())
    }

    @Test fun timeWindowWrapsMidnight() {
        assertTrue(AutomationEngine.inWindow(23 * 60, 22 * 60, 6 * 60))
        assertTrue(AutomationEngine.inWindow(5 * 60, 22 * 60, 6 * 60))
        assertTrue(!AutomationEngine.inWindow(12 * 60, 22 * 60, 6 * 60))
        assertTrue(AutomationEngine.inWindow(10, 10, 10))
    }

    @Test fun codecRoundTripAndDamagedLinesAreDropped() {
        val rules = listOf(
            Rule("1", "Ранок; ANC = on | test %", Trigger(TriggerKind.TIME_OF_DAY, minuteOfDay = 450, days = setOf(1, 5)),
                listOf(Action(ActionKind.SET_NOISE_MODE, noiseMode = NoiseMode.ANC), Action(ActionKind.SET_PLAYER_VOLUME, volumePercent = 35)),
                listOf(Condition(ConditionKind.TIME_WINDOW, 400, 500), Condition(ConditionKind.NOISE_MODE_IS, noiseMode = NoiseMode.OFF)),
                enabled = false, priority = 3, delaySeconds = 12, deviceAddress = "AA:BB:CC:00:11:22", createdAt = 99),
            Rule("2", "EQ", Trigger(TriggerKind.BATTERY_LOW, part = BatteryPart.CASE, thresholdPercent = 15), listOf(Action(ActionKind.APPLY_EQ_PROFILE, eqProfileId = "my|eq:1"))),
            Rule("3", "G", Trigger(TriggerKind.GESTURE, side = EarSide.RIGHT, tap = TapType.HOLD), listOf(Action(ActionKind.START_SLEEP_TIMER, minutes = 45))),
        )
        val text = RuleCodec.encode(rules)
        assertEquals(rules, RuleCodec.decode(text))
        val damaged = text.lines().toMutableList().apply { this[1] = this[1].replace("k=BATTERY_LOW", "k=NOPE") }.joinToString("\n") + "\ngarbage line"
        assertEquals(listOf("1", "3"), RuleCodec.decode(damaged).map { it.id })
    }
}
