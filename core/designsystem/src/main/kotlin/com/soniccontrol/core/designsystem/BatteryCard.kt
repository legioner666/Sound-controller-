package com.soniccontrol.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.soniccontrol.core.devices.BatteryStatus

@Composable
fun BatteryCard(battery: BatteryStatus?) {
    SonicCard(title = stringResource(R.string.battery_title)) {
        if (battery == null || !battery.hasAnyValue) {
            Text(stringResource(R.string.battery_none_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SonicCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (battery.left != null || battery.right != null) {
                BatteryGauge(stringResource(R.string.battery_left), battery.left)
                BatteryGauge(stringResource(R.string.battery_right), battery.right)
                if (battery.case != null) BatteryGauge(stringResource(R.string.battery_case), battery.case)
            } else {
                BatteryGauge(stringResource(R.string.battery_headset), battery.single)
            }
        }
        SourceNote(battery.source, battery.timestampMillis)
    }
}
