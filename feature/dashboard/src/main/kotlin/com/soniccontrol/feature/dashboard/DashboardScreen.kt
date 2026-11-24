package com.soniccontrol.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.audio.AudioController
import com.soniccontrol.core.database.AppSettings
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.BatteryCard
import com.soniccontrol.core.designsystem.EmptyState
import com.soniccontrol.core.designsystem.LevelBadge
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SegmentedTabs
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.SourceNote
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.designsystem.connectionLabel
import com.soniccontrol.core.designsystem.noiseLabel
import com.soniccontrol.core.devices.ActiveDevice
import com.soniccontrol.core.devices.BatteryStatus
import com.soniccontrol.core.devices.BluetoothAvailability
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneController
import kotlinx.coroutines.flow.StateFlow

@Composable
fun DashboardScreen(
    controller: HeadphoneController,
    audio: AudioController,
    settings: StateFlow<AppSettings>,
    onOpenDevices: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val active by controller.active.collectAsStateWithLifecycle()
    val availability by controller.availability.collectAsStateWithLifecycle()
    val appSettings by settings.collectAsStateWithLifecycle()
    val volume by audio.volume.collectAsStateWithLifecycle()
    LaunchedEffect(active?.identity?.address) { audio.refreshVolume() }

    SonicScreen(title = stringResource(R.string.app_name), subtitle = stringResource(R.string.dashboard_subtitle)) {
        item { AvailabilityBanner(availability, onRequestPermissions) }
        val device = active
        if (device == null) {
            item {
                EmptyState(
                    title = stringResource(R.string.dashboard_empty_title),
                    body = stringResource(R.string.dashboard_empty_body),
                ) { Button(onClick = onOpenDevices) { Text(stringResource(R.string.action_find_headphones)) } }
            }
        } else {
            item { DeviceHeader(device, onRefresh = controller::refresh) }
            item { BatteryCard(device.headphone.battery) }
            if (device.headphone.noiseModes.isNotEmpty()) {
                item {
                    val support = device.headphone.capabilities.of(Feature.NOISE_CONTROL)
                    val usable = device.headphone.capabilities.usable(Feature.NOISE_CONTROL, appSettings.experimental)
                    SonicCard(title = stringResource(R.string.noise_title), trailing = { StatusBadge(support.status) }) {
                        if (usable) {
                            val current = device.headphone.noise?.mode
                            SegmentedTabs(device.headphone.noiseModes, current ?: device.headphone.noiseModes.first(), { noiseLabel(it) }) {
                                controller.setNoiseMode(it)
                            }
                            if (current == null) Text(stringResource(R.string.noise_state_unknown), style = MaterialTheme.typography.bodySmall)
                            device.headphone.noise?.let { SourceNote(it.source, it.timestampMillis) }
                        } else {
                            Text(stringResource(R.string.noise_locked_experimental), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.media_title)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = audio::previous) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.media_previous)) }
                    Spacer(Modifier.width(12.dp))
                    FilledTonalIconButton(onClick = audio::playPause) { Icon(Icons.Filled.Pause, stringResource(R.string.media_play_pause)) }
                    Spacer(Modifier.width(12.dp))
                    IconButton(onClick = audio::next) { Icon(Icons.Filled.SkipNext, stringResource(R.string.media_next)) }
                }
                Text(stringResource(R.string.volume_label, volume.current, volume.max), style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = volume.current.toFloat(),
                    onValueChange = { audio.setVolume(it.toInt()) },
                    valueRange = 0f..volume.max.coerceAtLeast(1).toFloat(),
                    steps = (volume.max - 1).coerceAtLeast(0),
                )
                Text(stringResource(R.string.volume_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun AvailabilityBanner(a: BluetoothAvailability, onRequestPermissions: () -> Unit) {
    when (a) {
        BluetoothAvailability.READY -> Unit
        BluetoothAvailability.OFF -> Banner(stringResource(R.string.bt_off), BannerKind.WARNING)
        BluetoothAvailability.NO_PERMISSION -> Banner(stringResource(R.string.bt_no_permission), BannerKind.WARNING, stringResource(R.string.action_allow), onRequestPermissions)
        BluetoothAvailability.UNAVAILABLE -> Banner(stringResource(R.string.bt_unavailable), BannerKind.ERROR)
    }
}

@Composable
private fun DeviceHeader(device: ActiveDevice, onRefresh: () -> Unit) {
    SonicCard(modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Headphones, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.headphone.displayName, style = MaterialTheme.typography.titleLarge)
                Text(connectionLabel(device.connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.action_refresh)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LevelBadge(device.headphone.capabilities.level)
            Text(device.headphone.protocol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (device.headphone.busy) Text(stringResource(R.string.working), style = MaterialTheme.typography.labelSmall)
    }
}

