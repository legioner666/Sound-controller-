package com.soniccontrol

import com.soniccontrol.core.automation.Action
import com.soniccontrol.core.automation.ActionKind
import com.soniccontrol.core.automation.ActionResult
import com.soniccontrol.core.automation.ActionRunner
import com.soniccontrol.core.automation.AutomationController
import com.soniccontrol.core.automation.AutomationEngine
import com.soniccontrol.core.automation.AutomationEvent
import com.soniccontrol.core.automation.AutomationSnapshot
import com.soniccontrol.core.automation.JournalEntry
import com.soniccontrol.core.automation.Rule
import com.soniccontrol.core.automation.RuleCodec
import com.soniccontrol.core.background.AppShutdown
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.ConnectionState
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.FRESH_MILLIS
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.isCurrent
import com.soniccontrol.core.player.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Feeds the automation engine (TASK 014) from the app's single sources of truth: the active headset state (confirmed, fresh
 * readings only), the own player, the clock and manual mode changes. Actions use the same paths as the UI. Nothing runs while
 * the sleep timer has switched the app off. No extra Bluetooth connection, socket or polling is created here.
 */
class AutomationRuntime(
    private val scope: CoroutineScope,
    private val controller: SonicController,
    private val player: PlayerConnection,
    private val db: SonicDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutomationController {

    override val rules: StateFlow<List<Rule>> = db.automationRules.map { RuleCodec.decode(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val engine = AutomationEngine(scope, clock, ::snapshot, ActionRunner { a, _ -> run(a) })
    override val journal: StateFlow<List<JournalEntry>> = engine.journal

    private fun freshNoise(): NoiseMode? {
        val n = controller.active.value?.headphone?.noise ?: return null
        return n.mode.takeIf { n.source == DataSource.VENDOR_PROTOCOL && clock() - n.timestampMillis in 0..FRESH_MILLIS }
    }

    private fun snapshot(): AutomationSnapshot {
        val a = controller.active.value
        val now = LocalDateTime.now()
        return AutomationSnapshot(
            activeAddress = a?.takeIf { it.connection == ConnectionState.Active }?.identity?.address,
            freshNoiseMode = freshNoise(),
            playing = player.state.value.isPlaying,
            minuteOfDay = now.hour * 60 + now.minute,
        )
    }

    private suspend fun run(a: Action): ActionResult = when (a.kind) {
        ActionKind.SET_NOISE_MODE -> when (val o = controller.setNoiseModeForAutomation(a.noiseMode!!)) {
            is CommandOutcome.Confirmed -> ActionResult.Done
            CommandOutcome.SentUnconfirmed -> ActionResult.Unconfirmed
            else -> ActionResult.Failed(o.toString())
        }
        ActionKind.SET_PLAYER_VOLUME -> { player.setPlayerVolume(a.volumePercent!! / 100f); ActionResult.Done }
        ActionKind.PAUSE_PLAYER -> { player.pause(); ActionResult.Done }
        ActionKind.RESUME_PLAYER -> { player.resume(); ActionResult.Done }
        ActionKind.APPLY_EQ_PROFILE ->
            if (db.eqProfiles.value.any { it.id == a.eqProfileId }) { db.setEqProfile(a.eqProfileId!!); ActionResult.Done }
            else ActionResult.Failed("equaliser profile not found")
        ActionKind.START_SLEEP_TIMER -> { player.setSleepTimerMinutes(a.minutes!!); ActionResult.Done }
    }

    private fun emit(e: AutomationEvent) {
        if (AppShutdown.suppressed.value) return
        scope.launch { engine.onEvent(e) }
    }

    fun start() {
        scope.launch { rules.collect { engine.setRules(it) } }
        // Device connected / disconnected (session active), once per address change.
        scope.launch {
            var last: String? = null
            controller.active.map { a -> a?.takeIf { it.connection == ConnectionState.Active }?.identity?.address }.distinctUntilChanged().collect { value ->
                last?.let { if (it != value) emit(AutomationEvent.DeviceDisconnected(it)) }
                value?.let { emit(AutomationEvent.DeviceConnected(it)) }
                last = value
            }
        }
        // Confirmed noise-mode readings (every new reading; the engine itself detects real changes).
        scope.launch {
            controller.active.map { a -> a?.headphone?.noise?.takeIf { it.source == DataSource.VENDOR_PROTOCOL }?.let { a.identity.address to it } }
                .distinctUntilChanged().collect { p -> p?.let { (addr, n) -> if (clock() - n.timestampMillis <= FRESH_MILLIS) emit(AutomationEvent.NoiseModeObserved(addr, n.mode)) } }
        }
        // Battery: only current readings; last-known values are passed as "no reading".
        scope.launch {
            controller.active.map { a -> a?.headphone?.battery?.let { b -> a.identity.address to b } }.distinctUntilChanged().collect { p ->
                val (addr, b) = p ?: return@collect
                val now = clock()
                fun v(l: com.soniccontrol.core.devices.BatteryLevel?) = l?.takeIf { it.isCurrent(now) }?.percent
                emit(AutomationEvent.Battery(addr, v(b.left) ?: v(b.single), v(b.right) ?: v(b.single), v(b.case)))
            }
        }
        scope.launch { controller.manualNoise.collect { emit(AutomationEvent.ManualNoiseChange) } }
        scope.launch { player.state.map { it.isPlaying }.distinctUntilChanged().collect { emit(AutomationEvent.Playback(it)) } }
        scope.launch {
            player.state.map { it.sleepRemainingMillis != null }.distinctUntilChanged().collect { running -> if (running) emit(AutomationEvent.SleepTimerStarted) }
        }
        // Clock: one tick per minute, aligned to the minute.
        scope.launch {
            while (true) {
                val t = LocalDateTime.now()
                emit(AutomationEvent.MinuteTick(t.hour * 60 + t.minute, t.dayOfWeek.value, t.toLocalDate().toEpochDay()))
                delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
            }
        }
    }

    private fun save(list: List<Rule>) { scope.launch { db.saveAutomationRules(RuleCodec.encode(list)) } }
    override fun upsert(rule: Rule) = save(rules.value.filterNot { it.id == rule.id } + rule)
    override fun delete(id: String) = save(rules.value.filterNot { it.id == id })
    override fun setEnabled(id: String, enabled: Boolean) = save(rules.value.map { if (it.id == id) it.copy(enabled = enabled) else it })
}
