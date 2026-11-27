package com.soniccontrol

import com.soniccontrol.core.automation.AutomationController
import com.soniccontrol.core.devices.CycleAnalyzer
import com.soniccontrol.core.devices.DriverRegistry
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.HciImport
import com.soniccontrol.core.devices.ImportedFrame
import com.soniccontrol.core.devices.LabRecorder
import com.soniccontrol.core.devices.LabTools
import com.soniccontrol.core.devices.TapType
import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticLog
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.diagnostics.HciSnoop
import com.soniccontrol.core.protocol.Hex
import com.soniccontrol.core.protocol.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lab (TASK 014): passive observation, gesture experiments (state read before and after), HCI log import and test packages.
 * It only reads: the experiments use the driver's normal read-only refresh, nothing else is ever sent.
 */
class LabRuntime(
    private val scope: CoroutineScope,
    private val controller: SonicController,
    private val log: DiagnosticLog,
    private val registry: DriverRegistry,
    private val automation: AutomationController,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : LabTools {
    override val recorder = LabRecorder()
    private val _hci = MutableStateFlow<HciImport?>(null)
    override val hciImport: StateFlow<HciImport?> = _hci.asStateFlow()

    private fun describe(bytes: ByteArray): String? {
        val id = controller.active.value?.driverId
        val drivers = registry.all.sortedByDescending { it.id == id }
        return drivers.firstNotNullOfOrNull { it.describeFrame(bytes) }
    }

    /** Bytes of a frame only when the driver decoded it as a known, non-identifying report; otherwise its length. */
    private fun safeHex(label: String?, hex: String): String {
        val generic = label == null || listOf("not decoded", "unrecognised", "H-AA 0x").any { label.contains(it) }
        return if (generic) "<${hex.split(' ').count { it.isNotEmpty() }} bytes>" else hex
    }

    fun start() {
        scope.launch { controller.active.collect { a -> recorder.onState(a?.headphone, clock()) } }
        scope.launch {
            var lastSeen: DiagnosticEvent? = null
            log.events.collect { list ->
                // Only new events: after the last one handled (or, if it was trimmed away, after its time). The backlog is skipped.
                val from = lastSeen?.let { e ->
                    val i = list.indexOfLast { it === e }
                    if (i >= 0) i + 1 else list.indexOfFirst { it.timestampMillis > e.timestampMillis }.let { if (it < 0) list.size else it }
                } ?: list.size
                for (e in list.drop(from.coerceAtLeast(0))) {
                    if (e.category == EventCategory.RX && e.hex != null) {
                        val bytes = runCatching { Hex.decode(e.hex!!) }.getOrNull() ?: continue
                        val label = describe(bytes) ?: e.message
                        recorder.onFrame(e.timestampMillis, label, e.hex, touchCandidate = registry.all.any { it.isTouchCandidate(bytes) })
                    }
                }
                lastSeen = list.lastOrNull()
            }
        }
    }

    override fun startExperiment(side: EarSide, tap: TapType) {
        scope.launch {
            controller.refreshAwait()
            recorder.startExperiment(side, tap, controller.active.value?.headphone?.noise?.mode, clock())
        }
    }

    override fun finishExperiment() {
        scope.launch {
            controller.refreshAwait()
            recorder.finishExperiment(controller.active.value?.headphone?.noise?.mode, clock())
        }
    }

    override fun importHci(open: () -> java.io.InputStream?) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    open()?.use { HciSnoop.parse(it) } ?: HciSnoop.Result(0, emptyList(), false, "file could not be opened")
                }.getOrElse { HciSnoop.Result(0, emptyList(), false, it.message ?: "read error") }
            }
            val frames = ArrayList<ImportedFrame>()
            var other = 0
            for (v in result.values) {
                val label = describe(v.value)
                // Only values a headset driver recognises are kept; anything else (other devices, unknown data) is dropped.
                if (label == null) { other++; continue }
                frames += ImportedFrame(v.millis, v.direction == HciSnoop.Direction.HEADSET_TO_PHONE, label, Hex.encode(v.value))
            }
            _hci.value = HciImport(result.records, frames, other, result.truncated, result.error)
        }
    }

    override fun buildPackage(): String {
        val a = controller.active.value
        val h = a?.headphone
        val experiments = recorder.experiments.value
        val pkg = linkedMapOf<String, Any?>(
            "format" to "sonic-control-lab-package/1",
            "app" to appVersion,
            "createdAtMillis" to clock(),
            "privacy" to "no Bluetooth addresses, no device names of other devices; frames only when decoded as known reports",
            "device" to h?.let {
                linkedMapOf(
                    "model" to (it.catalogEntry?.fullName ?: "unknown"), "fastPairModelId" to it.modelId, "firmware" to it.firmware,
                    "protocol" to it.protocol, "control" to it.control.name,
                )
            },
            "capabilities" to h?.capabilities?.features?.entries?.associate { (f, s) -> f.name to "${s.status} ${s.note}".trim() },
            "gestureSupport" to h?.gestureSupport?.let { mapOf("layouts" to it.layouts.map { l -> l.name }, "status" to it.status.name, "evidence" to it.evidence, "reportsTapEvents" to it.reportsTapEvents) },
            "gestures" to h?.gestures?.let { g ->
                mapOf("source" to g.source.name, "left" to g.left?.let { mapOf("layout" to it.layout?.name, "raw" to it.rawHex) },
                    "right" to g.right?.let { mapOf("layout" to it.layout?.name, "raw" to it.rawHex) })
            },
            "settings" to h?.settings?.values?.associate { it.setting.name to mapOf("value" to it.value, "source" to it.source.name, "raw" to it.raw) },
            "experiments" to experiments.map { x ->
                linkedMapOf("id" to x.id, "side" to x.side.name, "tap" to x.tap.name, "before" to x.before?.name, "after" to x.after?.name,
                    "path" to x.path.map { it.name }, "modeChanges" to x.transitions, "touchCandidates" to x.touchCandidates,
                    "durationMs" to x.endedAt?.let { it - x.startedAt },
                    "observed" to x.observed.map { e -> mapOf("dtMs" to e.atMillis - x.startedAt, "type" to e.type.name, "summary" to e.summary, "hex" to e.hex?.let { hx -> safeHex(e.summary, hx) }) })
            },
            "cycleAnalysis" to CycleAnalyzer.analyze(experiments).map { f ->
                mapOf("side" to f.side.name, "tap" to f.tap.name, "runs" to f.runs, "verdict" to f.verdict.name, "cycle" to f.cycle?.map { it.name },
                    "transitions" to f.transitions.map { "${it.first}->${it.second}" }, "multiStepPaths" to f.multiStepPaths.map { p -> p.joinToString("->") })
            },
            "timeline" to recorder.timeline.value.takeLast(300).map { e -> mapOf("t" to e.atMillis, "type" to e.type.name, "summary" to e.summary, "hex" to e.hex?.let { hx -> safeHex(e.summary, hx) }) },
            "hciImport" to _hci.value?.let { i ->
                mapOf("records" to i.records, "keptFrames" to i.frames.size, "droppedValues" to i.otherValues, "truncated" to i.truncated, "error" to i.error,
                    "frames" to i.frames.take(2000).map { f -> mapOf("ms" to f.millis, "dir" to if (f.fromHeadset) "headset->phone" else "phone->headset", "label" to f.label, "hex" to safeHex(f.label, f.hex)) })
            },
            "automationJournal" to automation.journal.value.takeLast(100).map { j ->
                mapOf("t" to j.atMillis, "trigger" to j.trigger.name, "action" to j.action?.name, "outcome" to j.outcome.name, "skip" to j.skip?.name, "detail" to j.detail)
            },
        )
        return Json.stringify(pkg)
    }
}
