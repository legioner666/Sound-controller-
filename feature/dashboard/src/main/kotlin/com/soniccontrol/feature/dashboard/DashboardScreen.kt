package com.soniccontrol.feature.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import com.soniccontrol.core.database.EqProfile
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.designsystem.HeadphoneHero
import com.soniccontrol.core.designsystem.MiniEqCurve
import com.soniccontrol.core.designsystem.adviceBody
import com.soniccontrol.core.designsystem.adviceTitle
import com.soniccontrol.core.devices.ConnectionAdvice
import com.soniccontrol.core.devices.ConnectionAdvisor
import com.soniccontrol.core.devices.ConnectionState
import com.soniccontrol.core.devices.LabController
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
import com.soniccontrol.core.designsystem.LinkStatusRows
import com.soniccontrol.core.designsystem.connectionLabel
import com.soniccontrol.core.designsystem.controlSummary
import com.soniccontrol.core.designsystem.noticeLabel
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
    lab: LabController,
    audio: AudioController,
    db: SonicDatabase,
    onOpenDevices: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenLab: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val active by controller.active.collectAsStateWithLifecycle()
    val availability by controller.availability.collectAsStateWithLifecycle()
    val appSettings by db.settings.collectAsStateWithLifecycle()
    val profiles by db.eqProfiles.collectAsStateWithLifecycle()
    val attempts by lab.attempts.collectAsStateWithLifecycle()
    val volume by audio.volume.collectAsStateWithLifecycle()
    LaunchedEffect(active?.identity?.address) { audio.refreshVolume() }
    // Returning to the app refreshes at once through the single AdaptiveRefreshCoordinator (TASK 011): no second request here.
    val links = active?.links
    LaunchedEffect(links?.gatt, links?.rfcomm, links?.control, active?.connection) { lab.refreshAttempts() }

    SonicScreen(title = stringResource(R.string.app_name), subtitle = null) {
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
            item {
                // Audio, telemetry and control are shown separately (TASK 010); freshness is re-checked every 30 s.
                val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
                val currentActive = device.currentActive(now)
                HeadphoneHero(
                    name = device.headphone.displayName,
                    status = stringResource(R.string.hero_status_line, connectionBrief(device), controlStatusLabel(device) + " · " + telemetryLabel(device, now)),
                    battery = device.headphone.battery,
                    leftActive = currentActive?.left,
                    rightActive = currentActive?.right,
                    details = buildList {
                        val activeStale = device.headphone.active != null && currentActive == null
                        device.headphone.remainingMinutes?.let { add(stringResource(R.string.hero_remaining, it / 60, it % 60)) }
                        device.headphone.active?.let { a ->
                            add(stringResource(when { a.left && a.right -> R.string.hero_active_both; a.left -> R.string.hero_active_left; a.right -> R.string.hero_active_right; else -> R.string.hero_active_none }) + if (activeStale) stringResource(R.string.hero_active_last_known) else "")
                        }
                        device.headphone.firmware?.let { add(stringResource(R.string.hero_firmware, it)) }
                    },
                )
            }
            val advice = ConnectionAdvisor.advise(attempts, device.links)
            if (advice != ConnectionAdvice.NONE && device.connection !is ConnectionState.Idle) {
                item {
                    SonicCard(title = adviceTitle(advice)) {
                        Text(adviceBody(advice), style = MaterialTheme.typography.bodyMedium)
                        if (advice != ConnectionAdvice.WAITING) {
                            Text(stringResource(R.string.advice_steps), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { controller.connect(device.identity.address) }) { Text(stringResource(R.string.action_try_again)) }
                                OutlinedButton(onClick = onOpenLab) { Text(stringResource(R.string.action_open_lab)) }
                            }
                        }
                    }
                }
            }
            device.headphone.notices.forEach { n -> item { Banner(noticeLabel(n), BannerKind.INFO) } }
            if (device.headphone.noiseModes.isNotEmpty()) {
                item {
                    val usable = device.headphone.capabilities.usable(Feature.NOISE_CONTROL, appSettings.experimental)
                    SonicCard(title = stringResource(R.string.noise_title)) {
                        if (usable) {
                            val current = device.headphone.noise?.mode
                            SegmentedTabs(device.headphone.noiseModes, current ?: device.headphone.noiseModes.first(), { noiseLabel(it) }) {
                                controller.setNoiseMode(it)
                            }
                            if (current == null) Text(stringResource(R.string.noise_state_unknown), style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(stringResource(R.string.noise_locked_experimental), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item {
            val profile = profiles.firstOrNull { it.id == appSettings.eqProfileId } ?: profiles.firstOrNull()
            SonicCard(title = stringResource(R.string.eq_mini_title), trailing = { OutlinedButton(onClick = onOpenEqualizer) { Text(stringResource(R.string.action_open_eq)) } }) {
                if (profile != null) {
                    MiniEqCurve(profile.gainsDb, EqProfile.MAX_DB, Modifier.fillMaxWidth().height(56.dp))
                    Text(
                        if (appSettings.systemEq) stringResource(R.string.eq_mini_on, profile.name) else stringResource(R.string.eq_mini_off),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SonicCard {
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
            }
        }
    }
}

@Composable
private fun connectionBrief(d: ActiveDevice): String = when (d.links.audio) {
    com.soniccontrol.core.devices.AudioLinkState.CONNECTED -> stringResource(R.string.link_audio_brief_on)
    com.soniccontrol.core.devices.AudioLinkState.DISCONNECTED -> stringResource(R.string.link_audio_brief_off)
    com.soniccontrol.core.devices.AudioLinkState.UNKNOWN -> stringResource(R.string.link_audio_brief_unknown)
}

@Composable
private fun telemetryLabel(d: ActiveDevice, now: Long): String {
    val status = d.telemetryStatus(now)
    val label = stringResource(when (status) {
        com.soniccontrol.core.devices.TelemetryStatus.LIVE -> R.string.telemetry_live
        com.soniccontrol.core.devices.TelemetryStatus.STALE -> R.string.telemetry_stale
        com.soniccontrol.core.devices.TelemetryStatus.NONE -> R.string.telemetry_none
    })
    // No fresh data: the last known values stay on screen together with their age (owner decision, TASK 011).
    val t = d.lastTelemetryMillis ?: return label
    if (status == com.soniccontrol.core.devices.TelemetryStatus.LIVE && now - t < com.soniccontrol.core.designsystem.SHOW_AGE_AFTER_MILLIS) return label
    return label + " · " + (com.soniccontrol.core.designsystem.updatedAgo(t, now) ?: return label)
}

/** One status for control, derived from real channel state (never from "session active"); same source as the background notification. */
@Composable
private fun controlStatusLabel(d: ActiveDevice): String = stringResource(when (d.controlStatus) {
    com.soniccontrol.core.devices.ControlStatus.CONNECTED -> R.string.control_connected
    com.soniccontrol.core.devices.ControlStatus.RECONNECTING -> R.string.control_reconnecting
    com.soniccontrol.core.devices.ControlStatus.UNAVAILABLE -> R.string.control_unavailable
})

@Composable
fun AvailabilityBanner(a: BluetoothAvailability, onRequestPermissions: () -> Unit) {
    when (a) {
        BluetoothAvailability.READY -> Unit
        BluetoothAvailability.OFF -> Banner(stringResource(R.string.bt_off), BannerKind.WARNING)
        BluetoothAvailability.NO_PERMISSION -> Banner(stringResource(R.string.bt_no_permission), BannerKind.WARNING, stringResource(R.string.action_allow), onRequestPermissions)
        BluetoothAvailability.UNAVAILABLE -> Banner(stringResource(R.string.bt_unavailable), BannerKind.ERROR)
    }
}

