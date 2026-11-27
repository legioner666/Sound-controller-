package com.soniccontrol.core.devices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lab timeline entry types (TASK 014). Everything here is observation: the recorder never sends anything. */
enum class LabEventType { CONNECTION, NOISE_MODE, BATTERY, ACTIVE_EARBUDS, GESTURES, SETTING, FRAME, EXPERIMENT }

data class LabEvent(val atMillis: Long, val type: LabEventType, val summary: String, val hex: String? = null)

/** One gesture experiment: the state read before, what arrived while the user tapped, and the state read after. */
data class GestureExperiment(
    val id: Int,
    val side: EarSide,
    val tap: TapType,
    val startedAt: Long,
    val before: NoiseMode?,
    val endedAt: Long? = null,
    val after: NoiseMode? = null,
    /** Frames and state changes that arrived between the two readings. */
    val observed: List<LabEvent> = emptyList(),
    /** Every noise mode seen during the run, in order, starting with [before]: ANC -> AMBIENT -> ANC keeps the middle step. */
    val path: List<NoiseMode> = listOfNotNull(before),
    /** Frames the driver marks as candidate touch events (unconfirmed meaning), counted during the run. */
    val touchCandidates: Int = 0,
) {
    val finished: Boolean get() = endedAt != null
    /** Mode changes seen during the run. More than one means several taps (or another cause): not used to infer the cycle. */
    val transitions: Int get() = path.zipWithNext().count { (a, b) -> a != b }
}

/** What the experiments say about one gesture (side + tap). */
data class CycleFinding(
    val side: EarSide,
    val tap: TapType,
    val runs: Int,
    /** before -> after pairs of the runs used for the analysis (at most one mode change each), in order of appearance. */
    val transitions: List<Pair<NoiseMode, NoiseMode>>,
    /** Full paths of runs with several mode changes: shown, never used to claim a cycle. */
    val multiStepPaths: List<List<NoiseMode>> = emptyList(),
    /** The cycle order when every observed state always led to the same next state, e.g. [ANC, AMBIENT]. Null otherwise. */
    val cycle: List<NoiseMode>?,
    val verdict: Verdict,
) {
    enum class Verdict { CYCLE, NO_CHANGE, INCONSISTENT, NOT_ENOUGH_DATA }
}

object CycleAnalyzer {
    /** Needs at least [minRuns] finished runs with both readings; a cycle is only claimed when it is closed and consistent. */
    fun analyze(experiments: List<GestureExperiment>, minRuns: Int = 3): List<CycleFinding> =
        experiments.filter { it.finished }.groupBy { it.side to it.tap }.map { (key, runs) ->
            val single = runs.filter { it.transitions <= 1 }
            val multi = runs.filter { it.transitions > 1 }.map { it.path }
            val pairs = single.mapNotNull { r -> if (r.before != null && r.after != null) r.before to r.after else null }
            val next = HashMap<NoiseMode, MutableSet<NoiseMode>>()
            pairs.forEach { (a, b) -> next.getOrPut(a) { LinkedHashSet() } += b }
            val verdict: CycleFinding.Verdict
            var cycle: List<NoiseMode>? = null
            when {
                pairs.size < minRuns -> verdict = CycleFinding.Verdict.NOT_ENOUGH_DATA
                pairs.all { it.first == it.second } -> verdict = CycleFinding.Verdict.NO_CHANGE
                next.values.any { it.size > 1 } -> verdict = CycleFinding.Verdict.INCONSISTENT
                else -> {
                    // Walk from the first observed state; closed when it returns to the start through known steps only.
                    val start = pairs.first().first
                    val path = mutableListOf(start)
                    var cur = start
                    while (true) {
                        val n = next[cur]?.single() ?: break
                        if (n == start) { cycle = path.toList(); break }
                        if (n in path) break
                        path += n; cur = n
                    }
                    verdict = if (cycle != null) CycleFinding.Verdict.CYCLE else CycleFinding.Verdict.NOT_ENOUGH_DATA
                }
            }
            CycleFinding(key.first, key.second, runs.size, pairs, multiStepPaths = multi, cycle = cycle, verdict = verdict)
        }
}

/**
 * Passive observation for the Lab: turns state changes and received frames into a timeline, and keeps gesture experiments.
 * Pure (no Android); the app feeds it. Bounded memory.
 */
class LabRecorder(private val capacity: Int = 500) {
    private val _timeline = MutableStateFlow<List<LabEvent>>(emptyList())
    val timeline: StateFlow<List<LabEvent>> = _timeline.asStateFlow()
    private val _experiments = MutableStateFlow<List<GestureExperiment>>(emptyList())
    val experiments: StateFlow<List<GestureExperiment>> = _experiments.asStateFlow()
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var last: HeadphoneState? = null
    private var nextId = 1

    fun setRecording(on: Boolean) { _recording.value = on }

    fun clear() { _timeline.value = emptyList(); _experiments.value = emptyList(); nextId = 1 }

    private fun add(e: LabEvent) {
        if (!_recording.value && running() == null) return
        _timeline.value = (_timeline.value + e).takeLast(capacity)
        running()?.let { r -> replace(r.copy(observed = (r.observed + e).takeLast(100))) }
    }

    private fun running(): GestureExperiment? = _experiments.value.lastOrNull()?.takeIf { !it.finished }
    private fun replace(x: GestureExperiment) { _experiments.value = _experiments.value.map { if (it.id == x.id) x else it } }

    /** State changes become timeline entries. Only differences are recorded (a repeated poll adds nothing). */
    fun onState(state: HeadphoneState?, now: Long) {
        val old = last
        last = state
        if (state == null) { if (old != null) add(LabEvent(now, LabEventType.CONNECTION, "disconnected")); return }
        diff(old, state).forEach { (t, s) -> add(LabEvent(now, t, s)) }
        val mode = state.noise?.mode
        running()?.let { r -> if (mode != null && r.path.lastOrNull() != mode) replace(r.copy(path = r.path + mode)) }
    }

    fun onFrame(now: Long, label: String, hex: String?, touchCandidate: Boolean = false) {
        add(LabEvent(now, LabEventType.FRAME, label, hex))
        if (touchCandidate) running()?.let { replace(it.copy(touchCandidates = it.touchCandidates + 1)) }
    }

    fun startExperiment(side: EarSide, tap: TapType, before: NoiseMode?, now: Long): GestureExperiment {
        running()?.let { replace(it.copy(endedAt = now)) }
        val x = GestureExperiment(nextId++, side, tap, now, before)
        _experiments.value = _experiments.value + x
        add(LabEvent(now, LabEventType.EXPERIMENT, "start #${x.id}: $side $tap, before=${before ?: "unknown"}"))
        return x
    }

    fun finishExperiment(after: NoiseMode?, now: Long): GestureExperiment? {
        val r = running() ?: return null
        add(LabEvent(now, LabEventType.EXPERIMENT, "end #${r.id}: after=${after ?: "unknown"}"))
        val cur = running() ?: r
        val path = if (after != null && cur.path.lastOrNull() != after) cur.path + after else cur.path
        val done = cur.copy(endedAt = now, after = after, path = path)
        replace(done)
        return done
    }

    companion object {
        fun diff(old: HeadphoneState?, new: HeadphoneState): List<Pair<LabEventType, String>> {
            val out = ArrayList<Pair<LabEventType, String>>()
            if (old == null) out += LabEventType.CONNECTION to "session: ${new.protocol.ifBlank { new.displayName }}"
            if (old?.noise?.mode != new.noise?.mode && new.noise != null) out += LabEventType.NOISE_MODE to "${old?.noise?.mode ?: "unknown"} -> ${new.noise.mode} (${new.noise.source})"
            fun pct(b: BatteryStatus?) = b?.let { listOf(it.left?.percent, it.right?.percent, it.case?.percent, it.single?.percent) }
            if (pct(old?.battery) != pct(new.battery) && new.battery != null) out += LabEventType.BATTERY to
                "L ${new.battery.left?.percent ?: "-"} R ${new.battery.right?.percent ?: "-"} case ${new.battery.case?.percent ?: "-"} (${new.battery.source})"
            if (old?.active != new.active && new.active != null) out += LabEventType.ACTIVE_EARBUDS to "left=${new.active.left} right=${new.active.right}"
            if (old?.gestures?.let { it.left?.rawHex to it.right?.rawHex } != new.gestures?.let { it.left?.rawHex to it.right?.rawHex } && new.gestures != null)
                out += LabEventType.GESTURES to "left=${new.gestures.left?.layout ?: new.gestures.left?.rawHex} right=${new.gestures.right?.layout ?: new.gestures.right?.rawHex}"
            for ((k, v) in new.settings) if (old?.settings?.get(k)?.value != v.value) out += LabEventType.SETTING to "$k = ${v.value}"
            return out
        }
    }
}

/** One headset frame found in an imported HCI log. */
data class ImportedFrame(val millis: Long, val fromHeadset: Boolean, val label: String, val hex: String)

data class HciImport(val records: Int, val frames: List<ImportedFrame>, val otherValues: Int, val truncated: Boolean, val error: String? = null)

/** Lab tools of the app (TASK 014). Observation and read-only queries only; nothing here can write arbitrary bytes. */
interface LabTools {
    val recorder: LabRecorder
    val hciImport: StateFlow<HciImport?>
    /** Reads the state (read-only GET), then waits for the user's gesture. */
    fun startExperiment(side: EarSide, tap: TapType)
    /** Reads the state again and closes the experiment. */
    fun finishExperiment()
    fun importHci(open: () -> java.io.InputStream?)
    /** JSON test package for analysis: no addresses, no names of other devices. */
    fun buildPackage(): String
}
