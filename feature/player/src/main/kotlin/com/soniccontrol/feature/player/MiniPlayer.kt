package com.soniccontrol.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.player.PlayerConnection

/** Shown above the navigation bar on every screen while a queue exists. */
@Composable
fun MiniPlayer(player: PlayerConnection, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val st by player.state.collectAsStateWithLifecycle()
    val track = st.current ?: return
    Surface(modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.player_open), onClick = onOpen), tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist.ifEmpty { stringResource(R.string.music_unknown_artist) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { player.togglePlay() }) {
                Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = stringResource(if (st.isPlaying) R.string.player_pause else R.string.player_play))
            }
            IconButton(onClick = { player.next() }) { Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.player_next)) }
        }
    }
}
