package com.soniccontrol.feature.gestures

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.EmptyState
import com.soniccontrol.core.designsystem.KeyValue
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.designsystem.featureLabel
import com.soniccontrol.core.designsystem.statusLabel
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneController

/** Gestures and other model-specific controls: shows exactly what the connected headset supports. */
@Composable
fun GesturesScreen(controller: HeadphoneController) {
    val active by controller.active.collectAsStateWithLifecycle()
    SonicScreen(title = stringResource(R.string.gestures_title), subtitle = stringResource(R.string.gestures_subtitle)) {
        val device = active
        if (device == null) {
            item { EmptyState(stringResource(R.string.no_device_title), stringResource(R.string.no_device_body)) }
            return@SonicScreen
        }
        val caps = device.headphone.capabilities
        item {
            SonicCard(title = featureLabel(Feature.TOUCH_GESTURES), trailing = { StatusBadge(caps.of(Feature.TOUCH_GESTURES).status) }) {
                Text(stringResource(R.string.gestures_unavailable), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            SonicCard(title = stringResource(R.string.capabilities_title)) {
                Feature.entries.forEach { f -> KeyValue(featureLabel(f), statusLabel(caps.of(f).status)) }
            }
        }
    }
}
