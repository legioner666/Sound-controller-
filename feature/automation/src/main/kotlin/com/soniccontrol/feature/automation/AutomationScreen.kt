package com.soniccontrol.feature.automation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.devices.SupportStatus
import com.soniccontrol.core.player.PlayerConnection
import com.soniccontrol.feature.player.SleepTimerCard

/** Automation page. The sleep timer here is the same single timer as on the player (TASK 013): one timer, one fade. */
@Composable
fun AutomationScreen(player: PlayerConnection) {
    SonicScreen(title = stringResource(R.string.automation_title), subtitle = stringResource(R.string.automation_subtitle)) {
        item { SleepTimerCard(player, title = stringResource(R.string.sleep_title)) }
        item {
            SonicCard(title = stringResource(R.string.scenarios_title), trailing = { StatusBadge(SupportStatus.UNSUPPORTED) }) {
                Text(stringResource(R.string.scenarios_planned), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
