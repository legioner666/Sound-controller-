package com.soniccontrol

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.devices.BluetoothAvailability
import com.soniccontrol.core.devices.HeadphoneController

/** First run: what the app does and does not do, permissions, and connecting the first headphones. */
@Composable
fun OnboardingFlow(controller: HeadphoneController, onRequestPermissions: () -> Unit, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val availability by controller.availability.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsStateWithLifecycle()
    val active by controller.active.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp)) {
            LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            AnimatedContent(step, label = "onboarding", modifier = Modifier.weight(1f)) { s ->
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (s) {
                        0 -> {
                            Icon(Icons.Outlined.Headphones, null, tint = MaterialTheme.colorScheme.primary)
                            Title(stringResource(R.string.onb_welcome_title))
                            Text(stringResource(R.string.onb_welcome_body), style = MaterialTheme.typography.bodyLarge)
                            SonicCard(title = stringResource(R.string.onb_statuses_title)) {
                                com.soniccontrol.core.devices.SupportStatus.entries.forEach { st ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        StatusBadge(st)
                                        Spacer(Modifier.padding(4.dp))
                                        Text(statusExplanation(st), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        1 -> {
                            Title(stringResource(R.string.onb_permission_title))
                            Text(stringResource(R.string.onb_permission_body), style = MaterialTheme.typography.bodyLarge)
                            when (availability) {
                                BluetoothAvailability.READY -> Banner(stringResource(R.string.onb_permission_ok), BannerKind.INFO)
                                BluetoothAvailability.NO_PERMISSION ->
                                    Button(onClick = onRequestPermissions) { Text(stringResource(R.string.action_allow)) }
                                BluetoothAvailability.OFF -> Banner(stringResource(R.string.bt_off), BannerKind.WARNING)
                                BluetoothAvailability.UNAVAILABLE -> Banner(stringResource(R.string.bt_unavailable), BannerKind.ERROR)
                            }
                        }
                        else -> {
                            Title(stringResource(R.string.onb_connect_title))
                            Text(stringResource(R.string.onb_connect_body), style = MaterialTheme.typography.bodyLarge)
                            val audio = devices.filter { it.audioConnected }
                            if (audio.isEmpty()) {
                                Banner(stringResource(R.string.onb_connect_none), BannerKind.INFO)
                            }
                            audio.forEach { d ->
                                OutlinedButton(onClick = { controller.connect(d.address) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(d.name ?: stringResource(R.string.devices_unnamed))
                                }
                            }
                            active?.let { Text(stringResource(R.string.onb_connected_as, it.headphone.displayName, it.driverName), style = MaterialTheme.typography.bodyMedium) }
                            Text(stringResource(R.string.onb_diagnostics_hint), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onb_skip)) }
                Button(onClick = { if (step < 2) step++ else onFinish() }) {
                    Text(stringResource(if (step < 2) R.string.onb_next else R.string.onb_done))
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun statusExplanation(s: com.soniccontrol.core.devices.SupportStatus): String = stringResource(
    when (s) {
        com.soniccontrol.core.devices.SupportStatus.VERIFIED -> R.string.onb_status_verified
        com.soniccontrol.core.devices.SupportStatus.SUPPORTED -> R.string.onb_status_supported
        com.soniccontrol.core.devices.SupportStatus.EXPERIMENTAL -> R.string.onb_status_experimental
        com.soniccontrol.core.devices.SupportStatus.BASIC -> R.string.onb_status_basic
        com.soniccontrol.core.devices.SupportStatus.UNSUPPORTED -> R.string.onb_status_unsupported
    },
)
