package com.soniccontrol.feature.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.KeyValue
import com.soniccontrol.core.designsystem.MonoStyle
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.featureLabel
import com.soniccontrol.core.designsystem.noiseLabel
import com.soniccontrol.core.designsystem.settingLabel
import com.soniccontrol.core.designsystem.sideLabel
import com.soniccontrol.core.designsystem.statusLabel
import com.soniccontrol.core.designsystem.tapLabel
import com.soniccontrol.core.devices.ActiveDevice
import com.soniccontrol.core.devices.CycleAnalyzer
import com.soniccontrol.core.devices.CycleFinding
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.LabTools
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.devices.TapType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TASK 014: confirmed capabilities and read-only settings of the connected model. */
@Composable
fun CapabilitiesCard(device: ActiveDevice, controller: HeadphoneController, experimental: Boolean) {
    val h = device.headphone
    SonicCard(title = stringResource(R.string.lab_caps_title)) {
        Feature.entries.forEach { f ->
            val s = h.capabilities.of(f)
            if (s.status != SupportStatus.UNSUPPORTED) KeyValue(featureLabel(f), statusLabel(s.status) + if (s.note.isNotBlank()) " · ${s.note}" else "")
        }
        h.settings.values.sortedBy { it.setting.ordinal }.forEach { KeyValue(settingLabel(it.setting), "${it.value} (${it.source})") }
        Button(onClick = { controller.readSettings() }, enabled = experimental && h.noiseLevelSupport != null) { Text(stringResource(R.string.lab_read_settings)) }
        Text(stringResource(if (h.noiseLevelSupport != null) R.string.lab_read_settings_note else R.string.lab_read_settings_none),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Passive observation and before/after gesture experiments. */
@Composable
fun ObservationCard(tools: LabTools) {
    val recording by tools.recorder.recording.collectAsStateWithLifecycle()
    val timeline by tools.recorder.timeline.collectAsStateWithLifecycle()
    val experiments by tools.recorder.experiments.collectAsStateWithLifecycle()
    var side by remember { mutableStateOf(EarSide.LEFT) }
    var tap by remember { mutableStateOf(TapType.SINGLE) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    val running = experiments.lastOrNull()?.takeIf { !it.finished }

    SonicCard(title = stringResource(R.string.lab_experiment_title)) {
        Text(stringResource(R.string.lab_experiment_body), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EarSide.entries.forEach { s -> FilterChip(selected = side == s, enabled = running == null, onClick = { side = s }, label = { Text(sideLabel(s)) }) }
            TapType.entries.forEach { t -> FilterChip(selected = tap == t, enabled = running == null, onClick = { tap = t }, label = { Text(tapLabel(t)) }) }
        }
        if (running == null) Button(onClick = { tools.startExperiment(side, tap) }) { Text(stringResource(R.string.lab_experiment_start)) }
        else {
            Text(stringResource(R.string.lab_experiment_now, sideLabel(running.side), tapLabel(running.tap)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Button(onClick = { tools.finishExperiment() }) { Text(stringResource(R.string.lab_experiment_done)) }
        }
        experiments.filter { it.finished }.takeLast(10).asReversed().forEach { x ->
            val path = if (x.path.size > 1) x.path.map { noiseLabel(it) } else listOf(x.before?.let { noiseLabel(it) } ?: "?", x.after?.let { noiseLabel(it) } ?: "?")
            Text("#${x.id} ${sideLabel(x.side)} · ${tapLabel(x.tap)}: ${path.joinToString(" → ")}" +
                (if (x.transitions > 1) " · " + stringResource(R.string.lab_multi_step) else "") +
                " · " + stringResource(R.string.lab_touch_candidates, x.touchCandidates),
                style = MaterialTheme.typography.bodySmall, color = if (x.transitions > 1) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
        }
        CycleAnalyzer.analyze(experiments).forEach { f ->
            val verdict = when (f.verdict) {
                CycleFinding.Verdict.CYCLE -> stringResource(R.string.lab_cycle_found, f.cycle!!.joinToString(" → ") { it.name })
                CycleFinding.Verdict.NO_CHANGE -> stringResource(R.string.lab_cycle_no_change)
                CycleFinding.Verdict.INCONSISTENT -> stringResource(R.string.lab_cycle_inconsistent)
                CycleFinding.Verdict.NOT_ENOUGH_DATA -> stringResource(R.string.lab_cycle_more_runs)
            }
            KeyValue("${sideLabel(f.side)} · ${tapLabel(f.tap)} (${f.runs})", verdict)
        }
    }
    SonicCard(title = stringResource(R.string.lab_observe_title)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.lab_observe_toggle), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = recording, onCheckedChange = { tools.recorder.setRecording(it) })
        }
        if (timeline.isEmpty()) Text(stringResource(R.string.lab_observe_empty), style = MaterialTheme.typography.bodySmall)
        timeline.takeLast(40).asReversed().forEach { e ->
            Text("${fmt.format(Date(e.atMillis))} ${e.type} ${e.summary}${e.hex?.let { "\n   $it" } ?: ""}", style = MonoStyle)
        }
        OutlinedButton(onClick = { tools.recorder.clear() }) { Text(stringResource(R.string.lab_observe_clear)) }
    }
}

/** HCI snoop import and the test package for analysis. */
@Composable
fun ImportAndPackageCard(tools: LabTools) {
    val context = LocalContext.current
    val imported by tools.hciImport.collectAsStateWithLifecycle()
    var packageResult by remember { mutableStateOf<Int?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) tools.importHci { context.contentResolver.openInputStream(uri) }
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        packageResult = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(tools.buildPackage().toByteArray()) } ?: error("no stream")
        }.fold({ R.string.lab_export_done }, { R.string.lab_export_failed })
    }
    SonicCard(title = stringResource(R.string.lab_hci_title)) {
        Text(stringResource(R.string.lab_hci_body), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.lab_hci_import_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.lab_hci_pick)) }
        imported?.let { i ->
            i.error?.let { Text(stringResource(R.string.lab_hci_error, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.lab_hci_result, i.records, i.frames.size, i.otherValues) + if (i.truncated) " · " + stringResource(R.string.lab_hci_truncated) else "",
                style = MaterialTheme.typography.bodySmall)
            i.frames.take(60).forEach { f -> Text("${f.millis} ms ${if (f.fromHeadset) "←" else "→"} ${f.label}\n   ${f.hex}", style = MonoStyle) }
        }
    }
    SonicCard(title = stringResource(R.string.lab_package_title)) {
        Text(stringResource(R.string.lab_package_body), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { saver.launch("sonic-control-lab-package.json") }) { Text(stringResource(R.string.lab_package_make)) }
        packageResult?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}
