package com.soniccontrol.core.automation

import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.TapType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Inputs. The runtime turns Bluetooth, player and clock changes into these; nothing else reaches the engine. */
sealed interface AutomationEvent {
    data class DeviceConnected(val address: String) : AutomationEvent
    data class DeviceDisconnected(val address: String) : AutomationEvent
    data class Playback(val playing: Boolean) : AutomationEvent
    /** Battery percentages; null = no current reading (stale or last-known values are passed as null by the runtime). */
    data class Battery(val address: String, val left: Int?, val right: Int?, val case: Int?) : AutomationEvent
    data object SleepTimerStarted : AutomationEvent
    /** A confirmed, fresh noise mode read from the headset (driver report). Unconfirmed or stale readings are not sent. */
    data class NoiseModeObserved(val address: String, val mode: NoiseMode) : AutomationEvent
    /** The user switched the mode in Sonic Control itself. */
    data object ManualNoiseChange : AutomationEvent
    data class Gesture(val address: String, val side: EarSide, val tap: TapType) : AutomationEvent
    /** Once a minute: local time. [dayKey] identifies the calendar day (one TIME_OF_DAY run per rule and day). */
    data class MinuteTick(val minuteOfDay: Int, val isoDayOfWeek: Int, val dayKey: Long) : AutomationEvent
}

/** What the engine may know about "now" when it evaluates conditions. */
data class AutomationSnapshot(
    val activeAddress: String?,
    /** Current noise mode only if it is confirmed and fresh; null otherwise (conditions on it then fail as "stale data"). */
    val freshNoiseMode: NoiseMode?,
    val playing: Boolean,
    val minuteOfDay: Int,
)

sealed interface ActionResult {
    /** The headset (or the local player/settings) confirmed the change. */
    data object Done : ActionResult
    /** Sent, but the headset gave no confirmation: shown as such, never as done. */
    data object Unconfirmed : ActionResult
    data class Failed(val reason: String) : ActionResult
}

fun interface ActionRunner { suspend fun run(action: Action, rule: Rule): ActionResult }

enum class JournalOutcome { EXECUTED, UNCONFIRMED, FAILED, SKIPPED }

enum class SkipReason {
    DISABLED, CONDITION, STALE_DATA, DEVICE_MISMATCH, DUPLICATE_EVENT, RATE_LIMIT, CIRCUIT_BREAKER, CONFLICT, MANUAL_OVERRIDE,
    ALREADY_IN_STATE, DEVICE_DISCONNECTED, SUPERSEDED, INVALID_RULE,
}

data class JournalEntry(
    val atMillis: Long,
    val ruleId: String,
    val ruleName: String,
    val trigger: TriggerKind,
    val action: ActionKind?,
    val outcome: JournalOutcome,
    val skip: SkipReason? = null,
    val detail: String = "",
)

/**
 * Edge-triggered "If -> then" engine (TASK 014).
 *
 * Protections:
 *  - loops: changes caused by the engine itself (expected within [EXPECT_MILLIS]) never trigger rules; per-rule rate limit and a
 *    global circuit breaker as a second line;
 *  - repeated Bluetooth events: a rule fires at most once per trigger within [DEBOUNCE_MILLIS] (connect: [CONNECT_DEBOUNCE_MILLIS]);
 *  - stale data: only confirmed fresh readings come in; a condition on the noise mode without a fresh reading fails;
 *  - conflicts: rules acting on the same target in one moment -> highest priority wins, the others are logged as CONFLICT;
 *    a delayed action is dropped if a higher-priority action on its target ran after it was scheduled;
 *  - manual priority: after the user changes the noise mode (in the app or on the headset) no rule changes it for
 *    [manualHoldMillis]; rules never re-apply a mode periodically, so a 5-second poll can never fight the user.
 */
class AutomationEngine(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val snapshot: () -> AutomationSnapshot,
    private val runner: ActionRunner,
    private val manualHoldMillis: Long = DEFAULT_MANUAL_HOLD_MILLIS,
) {
    private val mutex = Mutex()
    private var rules: List<Rule> = emptyList()
    private val _journal = MutableStateFlow<List<JournalEntry>>(emptyList())
    val journal: StateFlow<List<JournalEntry>> = _journal.asStateFlow()

    // edge/state memory
    private var lastNoise: NoiseMode? = null
    private var lastPlaying: Boolean? = null
    private val batteryArmed = HashMap<String, Boolean>()
    private val lastFired = HashMap<String, Long>()               // rule|triggerKey -> time
    private val runs = HashMap<String, ArrayDeque<Long>>()         // rule -> execution times
    private val globalRuns = ArrayDeque<Long>()
    private var breakerUntil = 0L
    private var manualNoiseUntil = 0L
    private val timeFiredDay = HashMap<String, Long>()
    private val lastConnect = HashMap<String, Long>()
    private val executedOnTarget = HashMap<ActionTarget, Pair<Int, Long>>()   // target -> (priority, time)

    // expectations of our own effects (loop protection)
    private var expectNoise: Pair<NoiseMode, Long>? = null
    private var expectPlaying: Pair<Boolean, Long>? = null
    private var expectSleepUntil = 0L

    private val pending = HashMap<String, Job>()

    fun setRules(list: List<Rule>) { rules = list }

    suspend fun onEvent(e: AutomationEvent) = mutex.withLock { handle(e) }

    private suspend fun handle(e: AutomationEvent) {
        val now = clock()
        val fired: List<Pair<Rule, String>> = when (e) {
            is AutomationEvent.DeviceConnected -> {
                val prev = lastConnect[e.address]
                lastConnect[e.address] = now
                // A new connection: forget per-connection memory (the first mode reading is not a "change").
                lastNoise = null; manualNoiseUntil = 0
                if (prev != null && now - prev < CONNECT_DEBOUNCE_MILLIS) emptyList()
                else match(TriggerKind.DEVICE_CONNECTED, address = e.address) { true }
            }
            is AutomationEvent.DeviceDisconnected -> {
                lastNoise = null
                cancelPending("device disconnected") { r -> r.actions.any { it.target == ActionTarget.NOISE } || r.deviceAddress != null }
                emptyList()
            }
            is AutomationEvent.Playback -> {
                val before = lastPlaying
                lastPlaying = e.playing
                val ours = expectPlaying?.let { (p, until) -> p == e.playing && now <= until } == true
                if (ours) expectPlaying = null
                if (before == null || before == e.playing || ours) emptyList()
                else match(if (e.playing) TriggerKind.PLAYBACK_STARTED else TriggerKind.PLAYBACK_STOPPED) { true }
            }
            is AutomationEvent.Battery -> batteryCrossings(e)
            is AutomationEvent.SleepTimerStarted -> if (now <= expectSleepUntil) { expectSleepUntil = 0; emptyList() } else match(TriggerKind.SLEEP_TIMER_STARTED) { true }
            is AutomationEvent.ManualNoiseChange -> { manualNoiseUntil = now + manualHoldMillis; expectNoise = null; emptyList() }
            is AutomationEvent.NoiseModeObserved -> {
                val before = lastNoise
                lastNoise = e.mode
                val ours = expectNoise?.let { (m, until) -> m == e.mode && now <= until } == true
                if (ours) expectNoise = null
                when {
                    before == null || before == e.mode -> emptyList()     // first reading or a poll repeating the same value: no edge
                    ours -> emptyList()                                     // our own action: never a trigger
                    else -> {
                        // Changed by the user (headset gesture, vendor app or our UI): manual priority.
                        manualNoiseUntil = now + manualHoldMillis
                        match(TriggerKind.NOISE_MODE_CHANGED, address = e.address) { t -> t.noiseMode == null || t.noiseMode == e.mode }
                    }
                }
            }
            is AutomationEvent.Gesture -> match(TriggerKind.GESTURE, address = e.address) { t ->
                (t.side == null || t.side == e.side) && (t.tap == null || t.tap == e.tap)
            }.map { (r, k) -> r to "$k|${e.side}|${e.tap}" }
            is AutomationEvent.MinuteTick -> rules.filter { r ->
                r.trigger.kind == TriggerKind.TIME_OF_DAY && r.trigger.minuteOfDay == e.minuteOfDay &&
                    (r.trigger.days.isEmpty() || e.isoDayOfWeek in r.trigger.days) && timeFiredDay[r.id] != e.dayKey
            }.onEach { timeFiredDay[it.id] = e.dayKey }.map { it to "time" }
        }
        if (fired.isNotEmpty()) dispatch(fired, now)
    }

    /** Rules of [kind] whose trigger matches; device binding is checked against the event's device. */
    private fun match(kind: TriggerKind, address: String? = null, accept: (Trigger) -> Boolean): List<Pair<Rule, String>> =
        rules.filter { it.trigger.kind == kind && accept(it.trigger) }.mapNotNull { r ->
            if (address != null && r.deviceAddress != null && !r.deviceAddress.equals(address, ignoreCase = true)) null else r to kind.name
        }

    private fun batteryCrossings(e: AutomationEvent.Battery): List<Pair<Rule, String>> = rules.filter { it.trigger.kind == TriggerKind.BATTERY_LOW }.mapNotNull { r ->
        if (r.deviceAddress != null && !r.deviceAddress.equals(e.address, ignoreCase = true)) return@mapNotNull null
        val t = r.trigger
        val value = when (t.part) {
            BatteryPart.LEFT -> e.left
            BatteryPart.RIGHT -> e.right
            BatteryPart.CASE -> e.case
            BatteryPart.ANY_EARBUD -> listOfNotNull(e.left, e.right).minOrNull()
        } ?: return@mapNotNull null                                  // no current reading: nothing decided
        val key = "${r.id}|${e.address}"
        val armed = batteryArmed[key] ?: true
        when {
            value < t.thresholdPercent && armed -> { batteryArmed[key] = false; r to "battery" }
            value >= t.thresholdPercent + BATTERY_HYSTERESIS -> { batteryArmed[key] = true; null }
            else -> null
        }
    }

    private suspend fun dispatch(fired: List<Pair<Rule, String>>, now: Long) {
        val snap = snapshot()
        val accepted = ArrayList<Rule>()
        for ((r, key) in fired) {
            val skip = when {
                !r.enabled -> SkipReason.DISABLED
                !r.valid -> SkipReason.INVALID_RULE
                r.deviceAddress != null && !r.deviceAddress.equals(snap.activeAddress, ignoreCase = true) -> SkipReason.DEVICE_MISMATCH
                isDuplicate(r, key, now) -> SkipReason.DUPLICATE_EVENT
                else -> conditionsSkip(r, snap)
            }
            if (skip != null) { if (skip != SkipReason.DISABLED) log(r, null, JournalOutcome.SKIPPED, skip); continue }
            lastFired["${r.id}|$key"] = now
            accepted += r
        }
        // Conflicts within this moment: per target, the highest priority (then oldest) rule keeps its action.
        val winners = HashMap<ActionTarget, Rule>()
        for (r in accepted.sortedWith(RULE_ORDER)) for (a in r.actions) winners.putIfAbsent(a.target, r)
        for (r in accepted) {
            val actions = r.actions.filter { a ->
                val w = winners.getValue(a.target)
                if (w.id != r.id) { log(r, a.kind, JournalOutcome.SKIPPED, SkipReason.CONFLICT, "rule \"${w.name}\" wins"); false } else true
            }
            if (actions.isEmpty()) continue
            if (r.delaySeconds == 0) runActions(r, actions, now)
            else schedule(r, actions, now)
        }
    }

    private fun isDuplicate(r: Rule, key: String, now: Long): Boolean {
        val last = lastFired["${r.id}|$key"] ?: return false
        val window = if (r.trigger.kind == TriggerKind.DEVICE_CONNECTED) CONNECT_DEBOUNCE_MILLIS else DEBOUNCE_MILLIS
        return now - last < window
    }

    private fun conditionsSkip(r: Rule, snap: AutomationSnapshot): SkipReason? {
        for (c in r.conditions) {
            val ok = when (c.kind) {
                ConditionKind.TIME_WINDOW -> inWindow(snap.minuteOfDay, c.fromMinute, c.toMinute)
                ConditionKind.PLAYER_PLAYING -> snap.playing
                ConditionKind.PLAYER_NOT_PLAYING -> !snap.playing
                ConditionKind.NOISE_MODE_IS -> {
                    val m = snap.freshNoiseMode ?: return SkipReason.STALE_DATA
                    m == c.noiseMode
                }
            }
            if (!ok) return SkipReason.CONDITION
        }
        return null
    }

    private fun schedule(r: Rule, actions: List<Action>, scheduledAt: Long) {
        pending.remove(r.id)?.cancel()
        pending[r.id] = scope.launch {
            delay(r.delaySeconds * 1000L)
            mutex.withLock {
                pending.remove(r.id)
                val current = rules.firstOrNull { it.id == r.id }
                if (current == null || !current.enabled) { log(r, null, JournalOutcome.SKIPPED, SkipReason.DISABLED, "rule changed while waiting"); return@withLock }
                val snap = snapshot()
                if (r.deviceAddress != null && !r.deviceAddress.equals(snap.activeAddress, ignoreCase = true)) { log(r, null, JournalOutcome.SKIPPED, SkipReason.DEVICE_DISCONNECTED); return@withLock }
                conditionsSkip(r, snap)?.let { log(r, null, JournalOutcome.SKIPPED, it, "re-checked after the delay"); return@withLock }
                val still = actions.filter { a ->
                    val last = executedOnTarget[a.target]
                    if (last != null && last.second > scheduledAt && last.first > r.priority) { log(r, a.kind, JournalOutcome.SKIPPED, SkipReason.SUPERSEDED); false } else true
                }
                runActions(r, still, clock())
            }
        }
    }

    private fun cancelPending(why: String, which: (Rule) -> Boolean) {
        val ids = pending.keys.filter { id -> rules.firstOrNull { it.id == id }?.let(which) ?: true }
        for (id in ids) {
            pending.remove(id)?.cancel()
            rules.firstOrNull { it.id == id }?.let { log(it, null, JournalOutcome.SKIPPED, SkipReason.DEVICE_DISCONNECTED, why) }
        }
    }

    private suspend fun runActions(r: Rule, actions: List<Action>, now: Long) {
        if (actions.isEmpty()) return
        if (now < breakerUntil) { log(r, null, JournalOutcome.SKIPPED, SkipReason.CIRCUIT_BREAKER); return }
        val mine = runs.getOrPut(r.id) { ArrayDeque() }
        while (mine.isNotEmpty() && now - mine.first() > RATE_WINDOW_MILLIS) mine.removeFirst()
        if (mine.size >= RATE_LIMIT) { log(r, null, JournalOutcome.SKIPPED, SkipReason.RATE_LIMIT, "$RATE_LIMIT runs in ${RATE_WINDOW_MILLIS / 60_000} min"); return }
        while (globalRuns.isNotEmpty() && now - globalRuns.first() > 60_000) globalRuns.removeFirst()
        if (globalRuns.size >= GLOBAL_LIMIT_PER_MINUTE) {
            breakerUntil = now + BREAKER_MILLIS
            log(r, null, JournalOutcome.SKIPPED, SkipReason.CIRCUIT_BREAKER, "more than $GLOBAL_LIMIT_PER_MINUTE actions in a minute: paused")
            return
        }
        mine.addLast(now); globalRuns.addLast(now)
        val snap = snapshot()
        for (a in actions) {
            if (a.target == ActionTarget.NOISE) {
                if (now < manualNoiseUntil) { log(r, a.kind, JournalOutcome.SKIPPED, SkipReason.MANUAL_OVERRIDE); continue }
                if (snap.freshNoiseMode == a.noiseMode) { log(r, a.kind, JournalOutcome.SKIPPED, SkipReason.ALREADY_IN_STATE); continue }
                expectNoise = a.noiseMode!! to now + EXPECT_MILLIS
            }
            if (a.kind == ActionKind.PAUSE_PLAYER) expectPlaying = false to now + EXPECT_MILLIS
            if (a.kind == ActionKind.RESUME_PLAYER) expectPlaying = true to now + EXPECT_MILLIS
            if (a.kind == ActionKind.START_SLEEP_TIMER) expectSleepUntil = now + EXPECT_MILLIS
            val result = runCatching { runner.run(a, r) }.getOrElse { ActionResult.Failed(it.message ?: "error") }
            executedOnTarget[a.target] = r.priority to clock()
            when (result) {
                ActionResult.Done -> log(r, a.kind, JournalOutcome.EXECUTED)
                ActionResult.Unconfirmed -> log(r, a.kind, JournalOutcome.UNCONFIRMED, detail = "sent, not confirmed by the headset")
                is ActionResult.Failed -> { log(r, a.kind, JournalOutcome.FAILED, detail = result.reason); if (a.target == ActionTarget.NOISE) expectNoise = null }
            }
        }
    }

    private fun log(r: Rule, action: ActionKind?, outcome: JournalOutcome, skip: SkipReason? = null, detail: String = "") {
        val e = JournalEntry(clock(), r.id, r.name, r.trigger.kind, action, outcome, skip, detail)
        _journal.value = (_journal.value + e).takeLast(JOURNAL_SIZE)
    }

    companion object {
        const val DEFAULT_MANUAL_HOLD_MILLIS = 30 * 60_000L
        const val EXPECT_MILLIS = 15_000L
        const val DEBOUNCE_MILLIS = 3_000L
        const val CONNECT_DEBOUNCE_MILLIS = 15_000L
        const val BATTERY_HYSTERESIS = 5
        const val RATE_LIMIT = 3
        const val RATE_WINDOW_MILLIS = 10 * 60_000L
        const val GLOBAL_LIMIT_PER_MINUTE = 20
        const val BREAKER_MILLIS = 5 * 60_000L
        const val JOURNAL_SIZE = 200

        val RULE_ORDER: Comparator<Rule> = compareByDescending<Rule> { it.priority }.thenBy { it.createdAt }.thenBy { it.id }

        /** [from]..[to] in minutes after midnight; a window that crosses midnight (from > to) wraps. Equal = all day. */
        fun inWindow(minute: Int, from: Int, to: Int): Boolean = when {
            from == to -> true
            from < to -> minute in from until to
            else -> minute >= from || minute < to
        }
    }
}
