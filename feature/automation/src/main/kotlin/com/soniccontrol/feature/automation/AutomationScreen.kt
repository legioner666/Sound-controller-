package com.soniccontrol.feature.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.audio.AudioController
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.devices.SupportStatus
import kotlinx.coroutines.delay

@Composable
fun AutomationScreen(audio: AudioController) {
    val timer by audio.sleepTimer.collectAsStateWithLifecycle()
    var minutes by remember { mutableStateOf(30) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer) { while (timer != null) { now = System.currentTimeMillis(); delay(1_000) } }

    SonicScreen(title = stringResource(R.string.automation_title), subtitle = stringResource(R.string.automation_subtitle)) {
        item {
            SonicCard(title = stringResource(R.string.sleep_title), trailing = { StatusBadge(SupportStatus.BASIC) }) {
                Text(stringResource(R.string.sleep_body), style = MaterialTheme.typography.bodyMedium)
                val t = timer
                if (t == null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 45, 60, 90).forEach { m ->
                            FilterChip(selected = m == minutes, onClick = { minutes = m }, label = { Text(stringResource(R.string.minutes_short, m)) })
                        }
                    }
                    Button(onClick = { audio.startSleepTimer(minutes * 60_000L) }) { Text(stringResource(R.string.sleep_start)) }
                } else {
                    val left = ((t.endsAtMillis - now) / 1000).coerceAtLeast(0)
                    Text(stringResource(R.string.sleep_remaining, left / 60, left % 60), style = MaterialTheme.typography.headlineSmall)
                    OutlinedButton(onClick = audio::cancelSleepTimer) { Text(stringResource(R.string.action_cancel)) }
                }
                Text(stringResource(R.string.sleep_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SonicCard(title = stringResource(R.string.scenarios_title), trailing = { StatusBadge(SupportStatus.UNSUPPORTED) }) {
                Text(stringResource(R.string.scenarios_planned), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
