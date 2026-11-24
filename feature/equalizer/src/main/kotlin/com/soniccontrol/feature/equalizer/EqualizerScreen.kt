package com.soniccontrol.feature.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.audio.SystemEqualizer
import com.soniccontrol.core.database.EqProfile
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.SupportStatus
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Two equalisers, kept visibly separate:
 * the phone equaliser (software, works with any headphones) and the headphones' own equaliser (hardware, per driver).
 */
@Composable
fun EqualizerScreen(controller: HeadphoneController, db: SonicDatabase, systemEq: SystemEqualizer) {
    val active by controller.active.collectAsStateWithLifecycle()
    val profiles by db.eqProfiles.collectAsStateWithLifecycle()
    val settings by db.settings.collectAsStateWithLifecycle()
    val eqStatus by systemEq.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val selected = profiles.firstOrNull { it.id == settings.eqProfileId } ?: profiles.first()
    val gains = remember(selected.id, selected.gainsDb) { mutableStateListOf<Float>().apply { addAll(selected.gainsDb) } }
    var balance by remember(settings.balance) { mutableFloatStateOf(settings.balance) }
    var newName by remember { mutableStateOf("") }
    LaunchedEffect(gains.toList()) { if (!selected.builtIn) systemEq.setGains(gains.toList()) }

    SonicScreen(title = stringResource(R.string.eq_title), subtitle = stringResource(R.string.eq_subtitle)) {
        item {
            SonicCard(title = stringResource(R.string.eq_phone_title), trailing = { StatusBadge(SupportStatus.BASIC) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.eq_phone_body), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = settings.systemEq, onCheckedChange = { scope.launch { db.setSystemEq(it) } })
                }
                val statusText = when {
                    !settings.systemEq -> stringResource(R.string.eq_phone_status_off)
                    eqStatus.active -> stringResource(R.string.eq_phone_status_active, eqStatus.sessions) +
                        if (eqStatus.globalActive) stringResource(R.string.eq_phone_global) else ""
                    else -> stringResource(R.string.eq_phone_status_waiting)
                }
                Text(statusText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (settings.systemEq && eqStatus.failures > 0 && !eqStatus.active) {
                    Banner(stringResource(R.string.eq_phone_refused), BannerKind.WARNING)
                }
                Text(stringResource(R.string.eq_safe_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            val status = active?.headphone?.capabilities?.of(Feature.DEVICE_EQUALIZER)?.status ?: SupportStatus.UNSUPPORTED
            SonicCard(title = stringResource(R.string.eq_device_title), trailing = { StatusBadge(status) }) {
                Text(
                    stringResource(if (active == null) R.string.no_device_body else R.string.eq_device_unsupported),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            SonicCard(title = stringResource(R.string.eq_profiles)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { p ->
                        FilterChip(selected = p.id == selected.id, onClick = { scope.launch { db.setEqProfile(p.id) } }, label = { Text(p.name) })
                    }
                }
            }
        }
        item {
            SonicCard(title = selected.name) {
                EqProfile.BANDS.forEachIndexed { i, hz ->
                    val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                    val g = gains.getOrElse(i) { 0f }
                    val value = String.format(Locale.ROOT, "%+.1f dB", g)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 60.dp))
                        Slider(
                            value = g,
                            onValueChange = { v -> if (!selected.builtIn) gains[i] = (v * 2).roundToInt() / 2f },
                            valueRange = EqProfile.MIN_DB..EqProfile.MAX_DB,
                            enabled = !selected.builtIn,
                            modifier = Modifier.weight(1f).semantics { contentDescription = label; stateDescription = value },
                        )
                        Text(value, style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(min = 64.dp))
                    }
                }
                if (selected.builtIn) {
                    Text(stringResource(R.string.eq_builtin_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch { db.saveProfiles(profiles.map { if (it.id == selected.id) it.copy(gainsDb = gains.toList()).clamped() else it }) }
                        }) { Text(stringResource(R.string.action_save)) }
                        OutlinedButton(onClick = {
                            scope.launch { db.saveProfiles(profiles.filter { it.id != selected.id }); db.setEqProfile("flat") }
                        }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
        item {
            SonicCard(title = stringResource(R.string.eq_balance)) {
                val pct = (balance * 100).roundToInt()
                val desc = when {
                    pct < 0 -> stringResource(R.string.eq_balance_left, -pct)
                    pct > 0 -> stringResource(R.string.eq_balance_right, pct)
                    else -> stringResource(R.string.eq_balance_center)
                }
                Text(desc, style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = balance, onValueChange = { balance = (it * 20).roundToInt() / 20f },
                    onValueChangeFinished = { scope.launch { db.setBalance(balance) } },
                    valueRange = -1f..1f,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "balance"; stateDescription = desc },
                )
                Text(stringResource(R.string.eq_balance_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SonicCard(title = stringResource(R.string.eq_new_profile)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { newName = it.take(32) }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.eq_profile_name)) })
                    Button(enabled = newName.isNotBlank(), onClick = {
                        val p = EqProfile(UUID.randomUUID().toString(), newName.trim(), gains.toList()).clamped()
                        scope.launch { db.saveProfiles(profiles + p); db.setEqProfile(p.id); newName = "" }
                    }) { Text(stringResource(R.string.eq_create_from_current)) }
                }
            }
        }
    }
}
