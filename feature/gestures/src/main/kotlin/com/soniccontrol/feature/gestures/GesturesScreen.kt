package com.soniccontrol.feature.gestures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.EmptyState
import com.soniccontrol.core.designsystem.KeyValue
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.designsystem.featureLabel
import com.soniccontrol.core.designsystem.gestureActionLabel
import com.soniccontrol.core.designsystem.layoutLabel
import com.soniccontrol.core.designsystem.settingLabel
import com.soniccontrol.core.designsystem.sideLabel
import com.soniccontrol.core.designsystem.statusLabel
import com.soniccontrol.core.designsystem.tapLabel
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.SideGestures
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

/**
 * Gesture editor (TASK 014). The list of layouts comes from the connected model's profile; nothing is offered that the model
 * does not have. Every change: backup -> one earbud -> read back -> result. Calls stay with the headset's firmware.
 */
@Composable
fun GesturesScreen(controller: HeadphoneController, experimentalEnabled: StateFlow<Boolean>) {
    val active by controller.active.collectAsStateWithLifecycle()
    val experimental by experimentalEnabled.collectAsStateWithLifecycle()
    val backup by controller.gestureBackup.collectAsStateWithLifecycle()
    SonicScreen(title = stringResource(R.string.gestures_title), subtitle = stringResource(R.string.gestures_subtitle)) {
        val device = active
        if (device == null) {
            item { EmptyState(stringResource(R.string.no_device_title), stringResource(R.string.no_device_body)) }
            return@SonicScreen
        }
        val h = device.headphone
        val caps = h.capabilities
        val support = h.gestureSupport
        item {
            SonicCard(title = featureLabel(Feature.TOUCH_GESTURES), trailing = { StatusBadge(caps.of(Feature.TOUCH_GESTURES).status) }) {
                when {
                    support == null -> Text(stringResource(R.string.gestures_not_supported), style = MaterialTheme.typography.bodyMedium)
                    !experimental -> Banner(stringResource(R.string.gestures_experimental_needed), BannerKind.WARNING)
                    else -> {
                        Text(stringResource(R.string.gestures_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { controller.readGestures() }) { Text(stringResource(R.string.gestures_read)) }
                        h.gestures?.let { g ->
                            Text(stringResource(R.string.gestures_read_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(g.readAtMillis))),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (support != null && experimental) {
            val refusal = com.soniccontrol.core.devices.GestureWritePolicy.refusal(backup, h.gestures)
            if (refusal != null && refusal != com.soniccontrol.core.devices.GestureWritePolicy.Refusal.NOT_READ) item {
                Banner(stringResource(R.string.gestures_write_blocked), BannerKind.WARNING)
            }
            for (side in EarSide.entries) item {
                val current = h.gestures?.side(side)
                SonicCard(title = sideLabel(side)) {
                    if (current == null) Text(stringResource(R.string.gestures_not_read), style = MaterialTheme.typography.bodySmall)
                    else SideDetails(current)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        support.layouts.forEach { l ->
                            FilterChip(
                                selected = current?.layout == l,
                                enabled = refusal == null && !h.busy,
                                onClick = { if (current?.layout != l) controller.setGestureLayout(side, l) },
                                label = { Text(layoutLabel(l)) },
                            )
                        }
                    }
                }
            }
            item {
                SonicCard(title = stringResource(R.string.gestures_backup_title)) {
                    val b = backup
                    if (b == null) Text(stringResource(R.string.gestures_backup_none), style = MaterialTheme.typography.bodySmall)
                    else {
                        KeyValue(sideLabel(EarSide.LEFT), b.left?.let { layoutLabel(it) } ?: (b.leftRaw ?: "—"))
                        KeyValue(sideLabel(EarSide.RIGHT), b.right?.let { layoutLabel(it) } ?: (b.rightRaw ?: "—"))
                        Text(stringResource(R.string.gestures_backup_saved, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(b.savedAt))),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (b.restorable) OutlinedButton(onClick = { controller.restoreGestureBackup() }) { Text(stringResource(R.string.gestures_restore)) }
                        else Text(stringResource(R.string.gestures_backup_unrestorable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                SonicCard(title = stringResource(R.string.gestures_evidence_title)) {
                    Text(support.evidence, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(if (support.reportsTapEvents) R.string.gestures_tap_events_yes else R.string.gestures_tap_events_no), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (h.settings.isNotEmpty()) item {
            SonicCard(title = stringResource(R.string.device_settings_title)) {
                h.settings.values.sortedBy { it.setting.ordinal }.forEach { s -> KeyValue(settingLabel(s.setting), s.value) }
                Text(stringResource(R.string.device_settings_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SonicCard(title = stringResource(R.string.capabilities_title)) {
                Feature.entries.forEach { f -> KeyValue(featureLabel(f), statusLabel(caps.of(f).status)) }
            }
        }
    }
}

@Composable
private fun SideDetails(s: SideGestures) {
    KeyValue(stringResource(R.string.gestures_layout), s.layout?.let { layoutLabel(it) } ?: stringResource(R.string.gestures_unknown_layout))
    s.slots.forEach { slot -> KeyValue(tapLabel(slot.tap), gestureActionLabel(slot.action)) }
}
