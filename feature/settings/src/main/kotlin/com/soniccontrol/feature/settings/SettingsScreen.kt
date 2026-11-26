package com.soniccontrol.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.database.AppSettings
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.database.ThemeMode
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SegmentedTabs
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import kotlinx.coroutines.launch

/** Third-party components and sources, shown in the app. Full texts: LICENSES.md in the repository. */
private val NOTICES = listOf(
    "Earshot — MIT License, Copyright (c) 2026 Earshot contributors (Apple proximity-advert parser, JBL model table)",
    "omarchy-headphones — MIT License, Copyright (c) 2026 Jacek Becela (protocol documentation for JBL, Sony, Nothing, Xiaomi)",
    "AndroidX, Jetpack Compose, Material Components — Apache License 2.0",
    "Kotlin, kotlinx.coroutines — Apache License 2.0",
)

@Composable
fun SettingsScreen(db: SonicDatabase, appVersion: String) {
    val settings by db.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmExperimental by remember { mutableStateOf(false) }
    // The notification permission only decides whether the monitoring notification is visible; monitoring still works without it.
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    SonicScreen(title = stringResource(R.string.settings_title)) {
        item {
            SonicCard(title = stringResource(R.string.settings_theme)) {
                SegmentedTabs(ThemeMode.entries, settings.theme, {
                    stringResource(when (it) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.LIGHT -> R.string.theme_light; ThemeMode.DARK -> R.string.theme_dark })
                }) { scope.launch { db.setTheme(it) } }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.settings_language)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Text(stringResource(R.string.settings_language_body), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text(stringResource(R.string.settings_language_open)) }
                } else {
                    Text(stringResource(R.string.settings_language_system), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.settings_devices)) {
                ToggleRow(stringResource(R.string.settings_auto_reconnect), stringResource(R.string.settings_auto_reconnect_body), settings.autoReconnect) {
                    scope.launch { db.setAutoReconnect(it) }
                }
                ToggleRow(stringResource(R.string.settings_fast_pair), stringResource(R.string.settings_fast_pair_body), settings.fastPairListening) {
                    scope.launch { db.setFastPairListening(it) }
                }
                ToggleRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_body), settings.backgroundMonitoring) {
                    if (it && android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    scope.launch { db.setBackgroundMonitoring(it) }
                }
                ToggleRow(stringResource(R.string.settings_experimental), stringResource(R.string.settings_experimental_body), settings.experimental) {
                    if (it) confirmExperimental = true else scope.launch { db.setExperimental(false) }
                }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.settings_refresh)) {
                Text(stringResource(R.string.settings_refresh_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_refresh_active), style = MaterialTheme.typography.titleSmall)
                SegmentedTabs(AppSettings.REFRESH_ACTIVE_CHOICES, settings.refreshActiveSeconds, { stringResource(R.string.seconds_short, it) }) {
                    scope.launch { db.setRefreshActiveSeconds(it) }
                }
                Text(stringResource(R.string.settings_refresh_background), style = MaterialTheme.typography.titleSmall)
                SegmentedTabs(AppSettings.REFRESH_BACKGROUND_CHOICES, settings.refreshBackgroundSeconds, { stringResource(R.string.seconds_short, it) }) {
                    scope.launch { db.setRefreshBackgroundSeconds(it) }
                }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.settings_privacy)) {
                Text(stringResource(R.string.settings_privacy_body), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SonicCard(title = stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_version, appVersion), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.catalog_trademarks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleSmall)
                NOTICES.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (confirmExperimental) {
        AlertDialog(
            onDismissRequest = { confirmExperimental = false },
            title = { Text(stringResource(R.string.experimental_dialog_title)) },
            text = { Text(stringResource(R.string.experimental_dialog_body)) },
            confirmButton = { TextButton(onClick = { confirmExperimental = false; scope.launch { db.setExperimental(true) } }) { Text(stringResource(R.string.action_enable)) } },
            dismissButton = { TextButton(onClick = { confirmExperimental = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
