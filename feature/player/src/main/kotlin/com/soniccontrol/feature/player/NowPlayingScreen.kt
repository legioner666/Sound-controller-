package com.soniccontrol.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SegmentedTabs
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.noiseLabel
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.player.PlayerConnection
import kotlinx.coroutines.delay

/** Full-screen player: transport, seek, queue, sleep timer, bass boost and the headphone's own listening modes. */
@Composable
fun NowPlayingScreen(player: PlayerConnection, headphones: HeadphoneController, onClose: () -> Unit) {
    val st by player.state.collectAsStateWithLifecycle()
    val active by headphones.active.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    // Position is read only while this screen is visible (no background ticking).
    LaunchedEffect(st.current?.uri, st.isPlaying) {
        while (true) { position = player.currentPositionMs(); delay(500) }
    }
    val duration = st.durationMs.coerceAtLeast(1)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.player_close)) }
                Text(stringResource(R.string.player_now_playing), style = MaterialTheme.typography.titleMedium)
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val t = st.current
                Text(t?.title ?: stringResource(R.string.player_empty_queue), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (t != null) Text(
                    listOf(t.artist.ifEmpty { stringResource(R.string.music_unknown_artist) }, t.album).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item {
            Column {
                Slider(
                    value = scrubbing ?: (position.toFloat() / duration).coerceIn(0f, 1f),
                    onValueChange = { scrubbing = it },
                    onValueChangeFinished = { scrubbing?.let { player.seekTo((it * duration).toLong()) }; scrubbing = null },
                    enabled = st.current != null,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(((scrubbing ?: (position.toFloat() / duration)) * duration).toLong()), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(st.durationMs), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconToggleButton(checked = st.shuffle, onCheckedChange = { player.setShuffle(it) }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = stringResource(R.string.player_shuffle), tint = if (st.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { player.previous() }) { Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.player_prev)) }
                FilledIconButton(onClick = { player.togglePlay() }) {
                    Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play))
                }
                IconButton(onClick = { player.next() }) { Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next)) }
                IconButton(onClick = { player.cycleRepeat() }) {
                    Icon(
                        if (st.repeatMode == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = stringResource(when (st.repeatMode) { 1 -> R.string.player_repeat_all; 2 -> R.string.player_repeat_one; else -> R.string.player_repeat_off }),
                        tint = if (st.repeatMode == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        val device = active
        val modes = device?.headphone?.noiseModes.orEmpty()
        if (device != null && modes.isNotEmpty()) item {
            SonicCard(title = stringResource(R.string.player_headphone_mode)) {
                val current = device.headphone.noise?.mode ?: modes.first()
                SegmentedTabs(modes, current, { noiseLabel(it) }) { headphones.setNoiseMode(it) }
            }
        }
        item { SleepTimerCard(player) }
        item {
            SonicCard(title = stringResource(R.string.player_bass)) {
                if (st.bassSupported) {
                    var v by remember(st.bassStrength) { mutableStateOf(st.bassStrength / 1000f) }
                    Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { player.setBassBoost((v * 1000).toInt()) })
                } else {
                    Text(stringResource(R.string.player_bass_unsupported), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.player_effects_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Text(stringResource(R.string.player_queue), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (st.queue.isEmpty()) item { Text(stringResource(R.string.player_empty_queue), style = MaterialTheme.typography.bodySmall) }
        itemsIndexed(st.queue, key = { i, t -> "$i/${t.uri}" }) { i, t ->
            Row(Modifier.fillMaxWidth().clickable { player.jumpTo(i) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (i == st.currentIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(t.artist.ifEmpty { stringResource(R.string.music_unknown_artist) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { player.removeFromQueue(i) }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove)) }
            }
        }
    }
}
