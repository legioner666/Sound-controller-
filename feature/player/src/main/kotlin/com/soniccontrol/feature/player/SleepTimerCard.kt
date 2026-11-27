package com.soniccontrol.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SegmentedTabs
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.player.FadeCurve
import com.soniccontrol.core.player.FadeLengthMode
import com.soniccontrol.core.player.PlayerConnection
import com.soniccontrol.core.player.SleepFadeSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The app's one sleep timer (TASK 013), shown on the player and on the Automation page: duration, player volume, optional
 * fade (length as % of the time or in minutes, end volume up to the current volume), and a graph of the planned volume.
 * Every change applies at once, also while the timer runs, and is kept across restarts.
 */
@Composable
fun SleepTimerCard(player: PlayerConnection, title: String = stringResource(R.string.player_sleep)) {
    val st by player.state.collectAsStateWithLifecycle()
    val settings by player.fadeSettings.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = st.sleepRemainingMillis != null
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(1_000) } }

    SonicCard(title = title) {
        val remaining = st.sleepRemainingMillis
        if (remaining != null) {
            val left = (st.sleepEndsAt - now).coerceIn(0, remaining.coerceAtLeast(0) + 1_000)
            Text(stringResource(R.string.player_sleep_left, formatTime(left)), style = MaterialTheme.typography.titleMedium)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = remaining == null, onClick = { player.setSleepTimerMinutes(0) }, label = { Text(stringResource(R.string.player_sleep_off)) })
            val runningMinutes = if (remaining != null) ((st.sleepEndsAt - st.sleepStartedAt) / 60_000L).toInt() else null
            TIMER_CHOICES.forEach { m ->
                FilterChip(selected = runningMinutes == m, onClick = { player.setSleepTimerMinutes(m) }, label = { Text(stringResource(R.string.player_sleep_min, m)) })
            }
        }

        // The player's own volume: where the fade starts from. Moving it during the fade re-plans the rest from the new level.
        var vol by remember(st.playerVolume) { mutableFloatStateOf(st.playerVolume) }
        Text(stringResource(R.string.sleep_player_volume, (vol * 100).roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(value = vol, onValueChange = { vol = it; player.setPlayerVolume(it) })

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.sleep_fade_toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = settings.enabled, onCheckedChange = { player.updateFadeSettings(settings.copy(enabled = it)) })
        }

        val totalMillis = if (remaining != null) st.sleepEndsAt - st.sleepStartedAt else settings.lastTimerMinutes * 60_000L
        if (settings.enabled) FadeControls(settings, totalMillis, vol) { player.updateFadeSettings(it) }
        else Text(stringResource(R.string.sleep_fade_off_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val curve = st.fadeCurve(settings) ?: FadeCurve.preview(totalMillis, vol, settings)
        if (remaining == null) Text(stringResource(R.string.sleep_graph_preview, settings.lastTimerMinutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(stringResource(R.string.sleep_graph_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FadeGraph(curve, nowAt = if (remaining != null) now else null)

        Text(stringResource(R.string.sleep_end_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FadeControls(settings: SleepFadeSettings, totalMillis: Long, playerVolume: Float, onChange: (SleepFadeSettings) -> Unit) {
    Text(stringResource(R.string.sleep_fade_length), style = MaterialTheme.typography.labelLarge)
    SegmentedTabs(listOf(FadeLengthMode.PERCENT, FadeLengthMode.MINUTES), settings.mode, {
        stringResource(if (it == FadeLengthMode.PERCENT) R.string.sleep_fade_mode_percent else R.string.sleep_fade_mode_minutes)
    }) { onChange(settings.copy(mode = it)) }

    if (settings.mode == FadeLengthMode.PERCENT) {
        var p by remember(settings.percent) { mutableFloatStateOf(settings.percent.toFloat()) }
        Text(stringResource(R.string.sleep_fade_percent_value, p.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = p, onValueChange = { p = it }, valueRange = SleepFadeSettings.MIN_PERCENT.toFloat()..SleepFadeSettings.MAX_PERCENT.toFloat(),
            steps = SleepFadeSettings.MAX_PERCENT - SleepFadeSettings.MIN_PERCENT - 1,
            onValueChangeFinished = { onChange(settings.copy(percent = p.roundToInt())) },
        )
    } else {
        var m by remember(settings.minutes) { mutableFloatStateOf(settings.minutes.toFloat()) }
        Text(stringResource(R.string.sleep_fade_minutes_value, m.roundToInt()), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = m, onValueChange = { m = it }, valueRange = SleepFadeSettings.MIN_MINUTES.toFloat()..SleepFadeSettings.MAX_FADE_MINUTES.toFloat(),
            steps = SleepFadeSettings.MAX_FADE_MINUTES - SleepFadeSettings.MIN_MINUTES - 1,
            onValueChangeFinished = { onChange(settings.copy(minutes = m.roundToInt())) },
        )
        if (settings.minutesCappedBy(totalMillis)) Text(stringResource(R.string.sleep_fade_capped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
    Text(stringResource(R.string.sleep_fade_starts_before, durationText(settings.fadeMillis(totalMillis))), style = MaterialTheme.typography.bodySmall)

    // End volume: from 0 up to the current player volume.
    val maxTarget = (playerVolume * 100).roundToInt().coerceIn(0, 100)
    var t by remember(settings.targetPercent, maxTarget) { mutableFloatStateOf(settings.targetPercent.coerceAtMost(maxTarget).toFloat()) }
    Text(stringResource(R.string.sleep_fade_target, t.roundToInt()), style = MaterialTheme.typography.bodyMedium)
    if (maxTarget > 0) Slider(
        value = t, onValueChange = { t = it }, valueRange = 0f..maxTarget.toFloat(),
        onValueChangeFinished = { onChange(settings.copy(targetPercent = t.roundToInt())) },
    )
}

@Composable
private fun durationText(ms: Long): String {
    val sec = ((ms + 999) / 1000).toInt()
    return if (sec < 60) stringResource(R.string.sleep_fade_seconds, sec) else stringResource(R.string.player_sleep_min, (sec + 59) / 60)
}

/** Planned player volume from the timer start to its end; the past part is dimmed and "now" is marked. */
@Composable
private fun FadeGraph(curve: FadeCurve, nowAt: Long?) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val past = MaterialTheme.colorScheme.onSurfaceVariant
    val pts = remember(curve) { curve.samples(64) }
    val span = (curve.endsAt - curve.startedAt).coerceAtLeast(1)
    val desc = stringResource(
        R.string.sleep_graph_desc, (curve.volumeAt(curve.rampStartAt) * 100).roundToInt(), (curve.target * 100).roundToInt(),
        durationText(curve.endsAt - curve.rampStartAt),
    )
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = desc }) {
        val w = size.width; val h = size.height
        fun x(ms: Long) = w * ms.toFloat() / span
        fun y(v: Float) = h - h * v.coerceIn(0f, 1f)
        listOf(0f, 0.5f, 1f).forEach { v -> drawLine(grid, Offset(0f, y(v)), Offset(w, y(v)), strokeWidth = 1f) }
        val path = Path().apply { pts.forEachIndexed { i, (t, v) -> if (i == 0) moveTo(x(t), y(v)) else lineTo(x(t), y(v)) } }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
        nowAt?.let { n ->
            val rel = (n - curve.startedAt).coerceIn(0, span)
            drawRect(past.copy(alpha = 0.12f), size = androidx.compose.ui.geometry.Size(x(rel), h))
            drawLine(past, Offset(x(rel), 0f), Offset(x(rel), h), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawCircle(line, radius = 5.dp.toPx(), center = Offset(x(rel), y(curve.volumeAt(n))))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Text("0", style = MaterialTheme.typography.labelSmall, color = past)
        Spacer(Modifier.weight(1f))
        if (nowAt != null) { Text(stringResource(R.string.sleep_graph_now), style = MaterialTheme.typography.labelSmall, color = line); Spacer(Modifier.width(12.dp)) }
        Text(formatTime(span), style = MaterialTheme.typography.labelSmall, color = past)
    }
}

private val TIMER_CHOICES = listOf(15, 30, 60, 120)
