package com.soniccontrol.feature.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.KeyValue
import com.soniccontrol.core.designsystem.MonoStyle
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SectionHeader
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.LabController
import com.soniccontrol.core.devices.StandardUuids
import com.soniccontrol.core.diagnostics.DiagnosticLog
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(controller: HeadphoneController, lab: LabController, log: DiagnosticLog, experimentalEnabled: StateFlow<Boolean>) {
    val active by controller.active.collectAsStateWithLifecycle()
    val services by lab.services.collectAsStateWithLifecycle()
    val sdp by lab.sdpUuids.collectAsStateWithLifecycle()
    val events by log.events.collectAsStateWithLifecycle()
    val experimental by experimentalEnabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var exportResult by remember { mutableStateOf<Int?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exportResult = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(lab.exportReport().toByteArray()) } ?: error("no stream")
        }.fold({ R.string.lab_export_done }, { R.string.lab_export_failed })
    }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }

    SonicScreen(title = stringResource(R.string.lab_title), subtitle = stringResource(R.string.lab_subtitle)) {
        item { Banner(stringResource(R.string.lab_readonly_note), BannerKind.INFO) }
        val device = active
        if (device != null) {
            item {
                SonicCard(title = device.headphone.displayName) {
                    KeyValue(stringResource(R.string.lab_driver), device.driverName)
                    KeyValue(stringResource(R.string.lab_match), "${device.match.reason} (${device.match.score})")
                    KeyValue(stringResource(R.string.lab_protocol), device.headphone.protocol)
                    device.headphone.firmware?.let { KeyValue(stringResource(R.string.lab_firmware), it) }
                    device.headphone.catalogEntry?.let { KeyValue(stringResource(R.string.lab_catalog), it.fullName) }
                    device.headphone.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                SonicCard(title = stringResource(R.string.lab_actions)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = lab::discover) { Text(stringResource(R.string.lab_discover)) }
                        OutlinedButton(onClick = lab::readAll) { Text(stringResource(R.string.lab_read_all)) }
                        OutlinedButton(onClick = lab::subscribeAll) { Text(stringResource(R.string.lab_subscribe_all)) }
                        Button(onClick = lab::probe, enabled = experimental) { Text(stringResource(R.string.lab_probe)) }
                    }
                    Text(stringResource(if (experimental) R.string.lab_probe_note else R.string.lab_probe_locked), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (sdp.isNotEmpty()) {
                item {
                    SonicCard(title = stringResource(R.string.lab_sdp)) {
                        sdp.forEach { u -> KeyValue(StandardUuids.describe(u) ?: "—", u.toString(), mono = true) }
                    }
                }
            }
            item {
                SonicCard(title = stringResource(R.string.lab_gatt, services.size)) {
                    if (services.isEmpty()) Text(stringResource(R.string.lab_gatt_empty), style = MaterialTheme.typography.bodySmall)
                    services.forEach { s ->
                        SectionHeader(s.name ?: stringResource(R.string.lab_unknown_service))
                        Text(s.uuid.toString(), style = MonoStyle)
                        s.characteristics.forEach { c ->
                            Text("  ${c.name ?: c.uuid.toString()}", style = MaterialTheme.typography.bodySmall)
                            Text("  [${c.properties.joinToString(",")}]${if (c.subscribed) " ●" else ""}", style = MonoStyle)
                            c.lastValueHex?.let { Text("  = $it", style = MonoStyle, color = MaterialTheme.colorScheme.primary) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.lab_export_title)) {
                Text(stringResource(R.string.lab_export_body), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exporter.launch("sonic-control-diagnostics.json") }) { Text(stringResource(R.string.lab_export)) }
                    OutlinedButton(onClick = log::clear) { Text(stringResource(R.string.lab_clear_log)) }
                }
                exportResult?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item { SectionHeader(stringResource(R.string.lab_log, events.size)) }
        items(events.takeLast(300).reversed()) { e ->
            Text(
                "${timeFormat.format(Date(e.timestampMillis))} ${e.category.name} ${e.message}${e.hex?.let { "\n   $it" } ?: ""}",
                style = MonoStyle,
            )
        }
    }
}
