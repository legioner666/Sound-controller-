package com.soniccontrol.feature.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.automation.Action
import com.soniccontrol.core.automation.ActionKind
import com.soniccontrol.core.automation.AutomationController
import com.soniccontrol.core.automation.BatteryPart
import com.soniccontrol.core.automation.Condition
import com.soniccontrol.core.automation.ConditionKind
import com.soniccontrol.core.automation.JournalOutcome
import com.soniccontrol.core.automation.Rule
import com.soniccontrol.core.automation.Trigger
import com.soniccontrol.core.automation.TriggerKind
import com.soniccontrol.core.database.EqProfile
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.noiseLabel
import com.soniccontrol.core.designsystem.sideLabel
import com.soniccontrol.core.designsystem.tapLabel
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.TapType
import com.soniccontrol.core.player.PlayerConnection
import com.soniccontrol.feature.player.SleepTimerCard
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Automation page: the single sleep timer (TASK 013) and the "If -> then" rules (TASK 014). */
@Composable
fun AutomationScreen(
    player: PlayerConnection,
    automation: AutomationController,
    controller: HeadphoneController,
    eqProfiles: StateFlow<List<EqProfile>>,
    experimentalEnabled: StateFlow<Boolean>,
) {
    val rules by automation.rules.collectAsStateWithLifecycle()
    val journal by automation.journal.collectAsStateWithLifecycle()
    val active by controller.active.collectAsStateWithLifecycle()
    val profiles by eqProfiles.collectAsStateWithLifecycle()
    val experimental by experimentalEnabled.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Rule?>(null) }

    SonicScreen(title = stringResource(R.string.automation_title), subtitle = stringResource(R.string.automation_subtitle)) {
        item { SleepTimerCard(player, title = stringResource(R.string.sleep_title)) }
        item {
            SonicCard(title = stringResource(R.string.rules_title)) {
                Text(stringResource(R.string.rules_manual_priority), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rules.isEmpty()) Text(stringResource(R.string.rules_empty), style = MaterialTheme.typography.bodyMedium)
                rules.sortedWith(com.soniccontrol.core.automation.AutomationEngine.RULE_ORDER).forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall)
                            Text(ruleSummary(r), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = r.enabled, onCheckedChange = { automation.setEnabled(r.id, it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editing = r }) { Text(stringResource(R.string.rules_edit)) }
                        TextButton(onClick = { automation.delete(r.id) }) { Text(stringResource(R.string.rules_delete)) }
                    }
                }
                if (editing == null) Button(onClick = {
                    editing = Rule(UUID.randomUUID().toString(), "", Trigger(TriggerKind.DEVICE_CONNECTED), emptyList(), createdAt = System.currentTimeMillis())
                }) { Text(stringResource(R.string.rules_add)) }
            }
        }
        editing?.let { draft ->
            item {
                RuleEditor(
                    initial = draft, profiles = profiles, activeAddress = active?.identity?.address,
                    tapEvents = active?.headphone?.gestureSupport?.reportsTapEvents == true,
                    noiseModes = active?.headphone?.noiseModes?.ifEmpty { null } ?: NoiseMode.entries.toList(),
                    onSave = { automation.upsert(it); editing = null }, onCancel = { editing = null },
                )
            }
        }
        if (!experimental) item { Banner(stringResource(R.string.rules_noise_needs_experimental), BannerKind.INFO) }
        item {
            SonicCard(title = stringResource(R.string.journal_title)) {
                if (journal.isEmpty()) Text(stringResource(R.string.journal_empty), style = MaterialTheme.typography.bodySmall)
                val fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM)
                journal.takeLast(40).asReversed().forEach { j ->
                    val outcome = when (j.outcome) {
                        JournalOutcome.EXECUTED -> stringResource(R.string.journal_executed)
                        JournalOutcome.UNCONFIRMED -> stringResource(R.string.journal_unconfirmed)
                        JournalOutcome.FAILED -> stringResource(R.string.journal_failed)
                        JournalOutcome.SKIPPED -> stringResource(R.string.journal_skipped, j.skip?.let { skipLabel(it) } ?: "")
                    }
                    Text("${fmt.format(Date(j.atMillis))} · ${j.ruleName} · ${j.action?.let { actionKindLabel(it) } ?: triggerLabel(j.trigger)} · $outcome" +
                        (if (j.detail.isNotBlank()) " (${j.detail})" else ""), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ruleSummary(r: Rule): String {
    val t = triggerLabel(r.trigger.kind)
    val names = r.actions.map { actionKindLabel(it.kind) }
    val a = names.joinToString(", ")
    val delay = stringResource(R.string.rules_delay_short, r.delaySeconds)
    val prio = stringResource(R.string.rules_priority_short, r.priority)
    val bound = stringResource(R.string.rules_bound_short)
    val conds = stringResource(R.string.rules_conditions_short, r.conditions.size)
    val extra = buildList {
        if (r.delaySeconds > 0) add(delay)
        if (r.priority != 0) add(prio)
        if (r.deviceAddress != null) add(bound)
        if (r.conditions.isNotEmpty()) add(conds)
    }
    return "$t → $a" + if (extra.isEmpty()) "" else " · " + extra.joinToString(" · ")
}

@Composable
private fun RuleEditor(
    initial: Rule,
    profiles: List<EqProfile>,
    activeAddress: String?,
    tapEvents: Boolean,
    noiseModes: List<NoiseMode>,
    onSave: (Rule) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var trigger by remember(initial.id) { mutableStateOf(initial.trigger) }
    val conditions = remember(initial.id) { mutableStateListOf<Condition>().apply { addAll(initial.conditions) } }
    val actions = remember(initial.id) { mutableStateListOf<Action>().apply { addAll(initial.actions) } }
    var delay by remember(initial.id) { androidx.compose.runtime.mutableFloatStateOf(initial.delaySeconds.toFloat()) }
    var priority by remember(initial.id) { androidx.compose.runtime.mutableFloatStateOf(initial.priority.toFloat()) }
    var bound by remember(initial.id) { mutableStateOf(initial.deviceAddress != null) }
    var draftAction by remember(initial.id) { mutableStateOf(Action(ActionKind.SET_NOISE_MODE, noiseMode = NoiseMode.ANC)) }

    SonicCard(title = stringResource(R.string.rules_editor_title)) {
        OutlinedTextField(name, { name = it.take(60) }, label = { Text(stringResource(R.string.rules_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Text(stringResource(R.string.rules_if), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriggerKind.entries.forEach { k ->
                val available = k != TriggerKind.GESTURE || tapEvents
                FilterChip(selected = trigger.kind == k, enabled = available, onClick = { trigger = trigger.copy(kind = k) }, label = { Text(triggerLabel(k)) })
            }
        }
        if (!tapEvents) Text(stringResource(R.string.rules_gesture_unavailable), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (trigger.kind) {
            TriggerKind.BATTERY_LOW -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BatteryPart.entries.forEach { p -> FilterChip(selected = trigger.part == p, onClick = { trigger = trigger.copy(part = p) }, label = { Text(partLabel(p)) }) }
                }
                Text(stringResource(R.string.rules_threshold, trigger.thresholdPercent), style = MaterialTheme.typography.bodySmall)
                Slider(trigger.thresholdPercent.toFloat(), { trigger = trigger.copy(thresholdPercent = Math.round(it)) }, valueRange = 5f..95f, steps = 17)
            }
            TriggerKind.TIME_OF_DAY -> {
                TimeFields(trigger.minuteOfDay) { trigger = trigger.copy(minuteOfDay = it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..7).forEach { d ->
                        FilterChip(selected = d in trigger.days, onClick = { trigger = trigger.copy(days = if (d in trigger.days) trigger.days - d else trigger.days + d) },
                            label = { Text(dayLabel(d)) })
                    }
                }
                Text(stringResource(R.string.rules_days_hint), style = MaterialTheme.typography.labelSmall)
            }
            TriggerKind.NOISE_MODE_CHANGED -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = trigger.noiseMode == null, onClick = { trigger = trigger.copy(noiseMode = null) }, label = { Text(stringResource(R.string.rules_any)) })
                noiseModes.forEach { m -> FilterChip(selected = trigger.noiseMode == m, onClick = { trigger = trigger.copy(noiseMode = m) }, label = { Text(noiseLabel(m)) }) }
            }
            TriggerKind.GESTURE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EarSide.entries.forEach { s -> FilterChip(selected = trigger.side == s, onClick = { trigger = trigger.copy(side = s) }, label = { Text(sideLabel(s)) }) }
                TapType.entries.forEach { t -> FilterChip(selected = trigger.tap == t, onClick = { trigger = trigger.copy(tap = t) }, label = { Text(tapLabel(t)) }) }
            }
            else -> Unit
        }

        Text(stringResource(R.string.rules_and_only_if), style = MaterialTheme.typography.titleSmall)
        conditions.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(conditionText(c), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { conditions.removeAt(i) }) { Text(stringResource(R.string.rules_remove)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { conditions += Condition(ConditionKind.TIME_WINDOW, 22 * 60, 7 * 60) }) { Text(stringResource(R.string.cond_time_window_add)) }
            OutlinedButton(onClick = { conditions += Condition(ConditionKind.PLAYER_PLAYING) }) { Text(conditionKindLabel(ConditionKind.PLAYER_PLAYING)) }
            OutlinedButton(onClick = { conditions += Condition(ConditionKind.PLAYER_NOT_PLAYING) }) { Text(conditionKindLabel(ConditionKind.PLAYER_NOT_PLAYING)) }
            noiseModes.forEach { m -> OutlinedButton(onClick = { conditions += Condition(ConditionKind.NOISE_MODE_IS, noiseMode = m) }) { Text(stringResource(R.string.cond_noise_is, noiseLabel(m))) } }
        }
        conditions.withIndex().filter { it.value.kind == ConditionKind.TIME_WINDOW }.forEach { (i, c) ->
            Text(stringResource(R.string.cond_from), style = MaterialTheme.typography.labelSmall)
            TimeFields(c.fromMinute) { conditions[i] = c.copy(fromMinute = it) }
            Text(stringResource(R.string.cond_to), style = MaterialTheme.typography.labelSmall)
            TimeFields(conditions[i].toMinute) { conditions[i] = conditions[i].copy(toMinute = it) }
        }

        Text(stringResource(R.string.rules_then), style = MaterialTheme.typography.titleSmall)
        actions.forEachIndexed { i, a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(actionText(a, profiles), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.removeAt(i) }) { Text(stringResource(R.string.rules_remove)) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionKind.entries.forEach { k -> FilterChip(selected = draftAction.kind == k, onClick = { draftAction = defaultAction(k, profiles) }, label = { Text(actionKindLabel(k)) }) }
        }
        when (draftAction.kind) {
            ActionKind.SET_NOISE_MODE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                noiseModes.forEach { m -> FilterChip(selected = draftAction.noiseMode == m, onClick = { draftAction = draftAction.copy(noiseMode = m) }, label = { Text(noiseLabel(m)) }) }
            }
            ActionKind.SET_PLAYER_VOLUME -> {
                Text(stringResource(R.string.action_volume_value, draftAction.volumePercent ?: 50), style = MaterialTheme.typography.bodySmall)
                Slider((draftAction.volumePercent ?: 50).toFloat(), { draftAction = draftAction.copy(volumePercent = Math.round(it)) }, valueRange = 0f..100f)
            }
            ActionKind.APPLY_EQ_PROFILE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                profiles.forEach { p -> FilterChip(selected = draftAction.eqProfileId == p.id, onClick = { draftAction = draftAction.copy(eqProfileId = p.id) }, label = { Text(p.name) }) }
            }
            ActionKind.START_SLEEP_TIMER -> {
                Text(stringResource(R.string.action_sleep_value, draftAction.minutes ?: 30), style = MaterialTheme.typography.bodySmall)
                Slider((draftAction.minutes ?: 30).toFloat(), { draftAction = draftAction.copy(minutes = Math.round(it)) }, valueRange = 5f..180f, steps = 34)
            }
            else -> Unit
        }
        OutlinedButton(onClick = { if (draftAction.valid) actions += draftAction }, enabled = draftAction.valid) { Text(stringResource(R.string.rules_add_action)) }

        Text(stringResource(R.string.rules_delay, delay.toInt()), style = MaterialTheme.typography.bodySmall)
        Slider(delay, { delay = (Math.round(it / 5f) * 5).toFloat() }, valueRange = 0f..600f)
        Text(stringResource(R.string.rules_priority, priority.toInt()), style = MaterialTheme.typography.bodySmall)
        Slider(priority, { priority = Math.round(it).toFloat() }, valueRange = 0f..10f, steps = 9)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rules_bind_device), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = bound, enabled = activeAddress != null || initial.deviceAddress != null, onCheckedChange = { bound = it })
        }

        val rule = initial.copy(
            name = name.trim(), trigger = trigger, conditions = conditions.toList(), actions = actions.toList(),
            delaySeconds = delay.toInt(), priority = priority.toInt(), deviceAddress = if (bound) (initial.deviceAddress ?: activeAddress) else null,
        )
        if (!rule.valid) Text(stringResource(R.string.rules_incomplete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(rule) }, enabled = rule.valid) { Text(stringResource(R.string.rules_save)) }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.rules_cancel)) }
        }
    }
}

private fun defaultAction(k: ActionKind, profiles: List<EqProfile>) = when (k) {
    ActionKind.SET_NOISE_MODE -> Action(k, noiseMode = NoiseMode.ANC)
    ActionKind.SET_PLAYER_VOLUME -> Action(k, volumePercent = 50)
    ActionKind.APPLY_EQ_PROFILE -> Action(k, eqProfileId = profiles.firstOrNull()?.id)
    ActionKind.START_SLEEP_TIMER -> Action(k, minutes = 30)
    else -> Action(k)
}

@Composable
private fun TimeFields(minuteOfDay: Int, onChange: (Int) -> Unit) {
    var h by remember(minuteOfDay) { mutableStateOf((minuteOfDay / 60).toString()) }
    var m by remember(minuteOfDay) { mutableStateOf("%02d".format(minuteOfDay % 60)) }
    fun push() { val hh = h.toIntOrNull(); val mm = m.toIntOrNull(); if (hh != null && mm != null && hh in 0..23 && mm in 0..59) onChange(hh * 60 + mm) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(h, { h = it.filter { c -> c in '0'..'9' }.take(2); push() }, label = { Text(stringResource(R.string.rules_hour)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        OutlinedTextField(m, { m = it.filter { c -> c in '0'..'9' }.take(2); push() }, label = { Text(stringResource(R.string.rules_minute)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
}

private fun hm(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
private fun conditionText(c: Condition): String = when (c.kind) {
    ConditionKind.TIME_WINDOW -> stringResource(R.string.cond_time_window, hm(c.fromMinute), hm(c.toMinute))
    ConditionKind.NOISE_MODE_IS -> stringResource(R.string.cond_noise_is, c.noiseMode?.let { noiseLabel(it) } ?: "?")
    else -> conditionKindLabel(c.kind)
}

@Composable
private fun actionText(a: Action, profiles: List<EqProfile>): String = when (a.kind) {
    ActionKind.SET_NOISE_MODE -> actionKindLabel(a.kind) + ": " + (a.noiseMode?.let { noiseLabel(it) } ?: "?")
    ActionKind.SET_PLAYER_VOLUME -> stringResource(R.string.action_volume_value, a.volumePercent ?: 0)
    ActionKind.APPLY_EQ_PROFILE -> actionKindLabel(a.kind) + ": " + (profiles.firstOrNull { it.id == a.eqProfileId }?.name ?: a.eqProfileId.orEmpty())
    ActionKind.START_SLEEP_TIMER -> stringResource(R.string.action_sleep_value, a.minutes ?: 0)
    else -> actionKindLabel(a.kind)
}
