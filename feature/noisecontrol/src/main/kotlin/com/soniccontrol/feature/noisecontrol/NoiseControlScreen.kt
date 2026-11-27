package com.soniccontrol.feature.noisecontrol

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.NoiseAware
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.EmptyState
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.SourceNote
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.designsystem.noiseLabel
import com.soniccontrol.core.designsystem.outcomeLabel
import com.soniccontrol.core.devices.ActionResult
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.SupportStatus
import kotlinx.coroutines.flow.StateFlow

@Composable
fun NoiseControlScreen(controller: HeadphoneController, experimentalEnabled: StateFlow<Boolean>) {
    val active by controller.active.collectAsStateWithLifecycle()
    val experimental by experimentalEnabled.collectAsStateWithLifecycle()
    var last by remember { mutableStateOf<ActionResult?>(null) }
    var lastLevel by remember { mutableStateOf<ActionResult?>(null) }
    LaunchedEffect(Unit) { controller.results.collect { if (it.action.startsWith("noise")) last = it; if (it.action.startsWith("level")) lastLevel = it } }

    SonicScreen(title = stringResource(R.string.noise_title), subtitle = stringResource(R.string.noise_subtitle)) {
        val device = active
        if (device == null) {
            item { EmptyState(stringResource(R.string.no_device_title), stringResource(R.string.no_device_body)) }
            return@SonicScreen
        }
        val support = device.headphone.capabilities.of(Feature.NOISE_CONTROL)
        val usable = device.headphone.capabilities.usable(Feature.NOISE_CONTROL, experimental)
        item {
            SonicCard(title = device.headphone.displayName, trailing = { StatusBadge(support.status) }) {
                if (support.note.isNotBlank()) Text(support.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    support.status == SupportStatus.UNSUPPORTED || device.headphone.noiseModes.isEmpty() ->
                        Text(stringResource(R.string.noise_unsupported), style = MaterialTheme.typography.bodyMedium)
                    !usable -> Banner(stringResource(R.string.noise_locked_experimental), BannerKind.WARNING)
                    else -> {
                        val current = device.headphone.noise?.mode
                        device.headphone.noiseModes.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { mode ->
                                    ModeTile(mode, selected = mode == current, modifier = Modifier.weight(1f)) { controller.setNoiseMode(mode) }
                                }
                                if (row.size == 1) Column(Modifier.weight(1f)) {}
                            }
                        }
                        if (current == null) Text(stringResource(R.string.noise_state_unknown), style = MaterialTheme.typography.bodySmall)
                        device.headphone.noise?.let { SourceNote(it.source, it.timestampMillis) }
                    }
                }
                last?.let { Text(outcomeLabel(it.outcome), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        // TASK 014: strength of ANC / Ambient Aware, only where the model profile has it; every change is read back.
        val levels = device.headphone.noiseLevelSupport
        if (levels != null && experimental) item {
            SonicCard(title = stringResource(R.string.noise_levels_title), trailing = { StatusBadge(levels.status) }) {
                for (kind in listOf(com.soniccontrol.core.devices.NoiseLevelKind.ANC, com.soniccontrol.core.devices.NoiseLevelKind.AMBIENT).filter { it in levels.kinds }) {
                    val setting = if (kind == com.soniccontrol.core.devices.NoiseLevelKind.ANC) com.soniccontrol.core.devices.DeviceSetting.ANC_LEVEL else com.soniccontrol.core.devices.DeviceSetting.AMBIENT_LEVEL
                    val reported = device.headphone.settings[setting]?.value?.toIntOrNull()
                    var value by remember(reported) { androidx.compose.runtime.mutableFloatStateOf((reported ?: levels.maxLevel).toFloat()) }
                    Text(stringResource(if (kind == com.soniccontrol.core.devices.NoiseLevelKind.ANC) R.string.noise_level_anc else R.string.noise_level_ambient,
                        reported?.toString() ?: stringResource(R.string.noise_level_unknown)), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Slider(
                        value = value, onValueChange = { value = it }, valueRange = 1f..levels.maxLevel.toFloat(), steps = levels.maxLevel - 2,
                        onValueChangeFinished = { val v = Math.round(value); if (v != reported) controller.setNoiseLevel(kind, v) },
                    )
                }
                Text(stringResource(R.string.noise_levels_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lastLevel?.let { Text(outcomeLabel(it.outcome), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
        if (support.status == SupportStatus.EXPERIMENTAL) {
            item { Banner(stringResource(R.string.experimental_explainer), BannerKind.INFO) }
        }
    }
}

@Composable
private fun ModeTile(mode: NoiseMode, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "border")
    val icon = when (mode) {
        NoiseMode.OFF -> Icons.Outlined.VolumeOff
        NoiseMode.ANC -> Icons.Outlined.NoiseAware
        NoiseMode.AMBIENT -> Icons.Outlined.Hearing
        NoiseMode.TALK_THRU -> Icons.Outlined.RecordVoiceOver
    }
    OutlinedCard(
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp).semantics { role = Role.RadioButton; this.selected = selected },
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(noiseLabel(mode), style = MaterialTheme.typography.labelLarge)
        }
    }
}
