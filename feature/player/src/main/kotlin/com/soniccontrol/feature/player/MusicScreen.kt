package com.soniccontrol.feature.player

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.EmptyState
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.musiclibrary.LibraryQuery
import com.soniccontrol.core.musiclibrary.SortOrder
import com.soniccontrol.core.musiclibrary.Track
import com.soniccontrol.core.musiclibrary.TrackGroup
import com.soniccontrol.core.player.LibraryController
import com.soniccontrol.core.player.PlayerConnection

private enum class MusicTab { TRACKS, ALBUMS, ARTISTS, FOLDERS, PLAYLISTS, FAVORITES }

private sealed interface Drill {
    data class Group(val title: String, val tracks: List<Track>) : Drill
    data class Playlist(val id: String) : Drill
}

@Composable
fun MusicScreen(library: LibraryController, player: PlayerConnection, onOpenPlayer: () -> Unit) {
    val tracks by library.tracks.collectAsStateWithLifecycle()
    val hasPermission by library.hasPermission.collectAsStateWithLifecycle()
    val scanning by library.scanning.collectAsStateWithLifecycle()
    val data by library.repository.data.collectAsStateWithLifecycle()
    val importResult by library.lastImport.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(MusicTab.TRACKS) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOrder.TITLE) }
    var sortMenu by remember { mutableStateOf(false) }
    var drill by remember { mutableStateOf<Drill?>(null) }
    var addToPlaylist by remember { mutableStateOf<Track?>(null) }
    var newPlaylist by remember { mutableStateOf(false) }
    var newPlaylistWith by remember { mutableStateOf<Track?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { library.refresh() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) library.import(uris) }
    val audioPermissions = remember {
        buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)   // for lock-screen controls
        }.toTypedArray()
    }
    LaunchedEffect(Unit) { library.refresh(); if (data.queue != null) player.connect() }

    val unknownAlbum = stringResource(R.string.music_unknown_album)
    val unknownArtist = stringResource(R.string.music_unknown_artist)
    val unknownFolder = stringResource(R.string.music_unknown_folder)
    val favorites = data.favorites
    val byUri = remember(tracks) { tracks.associateBy { it.uri } }

    fun play(list: List<Track>, start: Int, shuffle: Boolean = false) { player.playTracks(list, start, shuffle); onOpenPlayer() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tab_music), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { library.refresh() }, enabled = !scanning) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.music_rescan)) }
            IconButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.music_import)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MusicTab.entries.toList()) { t ->
                FilterChip(selected = tab == t, onClick = { tab = t; drill = null }, label = {
                    Text(stringResource(when (t) {
                        MusicTab.TRACKS -> R.string.music_tracks; MusicTab.ALBUMS -> R.string.music_albums; MusicTab.ARTISTS -> R.string.music_artists
                        MusicTab.FOLDERS -> R.string.music_folders; MusicTab.PLAYLISTS -> R.string.music_playlists; MusicTab.FAVORITES -> R.string.music_favorites
                    }))
                })
            }
        }
        if (tab != MusicTab.PLAYLISTS || drill != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, placeholder = { Text(stringResource(R.string.music_search)) },
                )
                Column {
                    TextButton(onClick = { sortMenu = true }) { Text(stringResource(R.string.music_sort)) }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        SortOrder.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text(stringResource(when (o) { SortOrder.TITLE -> R.string.sort_title; SortOrder.ARTIST -> R.string.sort_artist; SortOrder.ALBUM -> R.string.sort_album; SortOrder.DATE_ADDED -> R.string.sort_added; SortOrder.DURATION -> R.string.sort_duration }) + if (o == sort) " ✓" else "") },
                                onClick = { sort = o; sortMenu = false },
                            )
                        }
                    }
                }
            }
        }

        importResult?.let { (ok, bad) ->
            Text(stringResource(R.string.music_import_result, ok, bad), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp).clickable { library.consumeImportResult() })
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!hasPermission && tracks.isEmpty()) item {
                SonicCard(title = stringResource(R.string.music_perm_title)) {
                    Text(stringResource(R.string.music_perm_body), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { permissionLauncher.launch(audioPermissions) }) { Text(stringResource(R.string.music_perm_grant)) }
                        OutlinedButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.music_import)) }
                    }
                }
            }

            val d = drill
            fun visible(base: List<Track>) = LibraryQuery.sort(LibraryQuery.search(base, query), sort)

            if (d is Drill.Group || d is Drill.Playlist) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { drill = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.playlist_back)) }
                    Text(when (d) { is Drill.Group -> d.title; is Drill.Playlist -> data.playlists.firstOrNull { it.id == d.id }?.name.orEmpty(); else -> "" }, style = MaterialTheme.typography.titleMedium)
                }
            }

            when {
                d is Drill.Playlist -> {
                    val pl = data.playlists.firstOrNull { it.id == d.id }
                    val list = pl?.trackUris?.mapNotNull { byUri[it] }.orEmpty()
                    if (list.isEmpty()) item { Text(stringResource(R.string.playlist_empty), style = MaterialTheme.typography.bodySmall) }
                    else {
                        item { PlayAllRow(list, ::play) }
                        items(list, key = { it.uri }) { t -> TrackRow(t, t.uri in favorites, onPlay = { play(list, list.indexOf(t)) }, player, library, onAdd = { addToPlaylist = t }, removeFromPlaylist = { library.repository.removeFromPlaylist(d.id, t.uri) }) }
                    }
                }
                d is Drill.Group -> {
                    val list = visible(d.tracks)
                    item { PlayAllRow(list, ::play) }
                    items(list, key = { it.uri }) { t -> TrackRow(t, t.uri in favorites, onPlay = { play(list, list.indexOf(t)) }, player, library, onAdd = { addToPlaylist = t }) }
                }
                tab == MusicTab.TRACKS || tab == MusicTab.FAVORITES -> {
                    val base = if (tab == MusicTab.FAVORITES) tracks.filter { it.uri in favorites } else tracks
                    val list = visible(base)
                    if (tracks.isEmpty() && (hasPermission || tab == MusicTab.FAVORITES)) item { EmptyState(stringResource(R.string.music_empty_title), stringResource(R.string.music_empty_body)) {} }
                    else if (list.isEmpty()) item { Text(stringResource(R.string.music_no_results), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
                    else {
                        item { PlayAllRow(list, ::play) }
                        items(list, key = { it.uri }) { t -> TrackRow(t, t.uri in favorites, onPlay = { play(list, list.indexOf(t)) }, player, library, onAdd = { addToPlaylist = t }) }
                    }
                }
                tab == MusicTab.ALBUMS || tab == MusicTab.ARTISTS || tab == MusicTab.FOLDERS -> {
                    val groups: List<TrackGroup> = LibraryQuery.search(tracks, query).let {
                        when (tab) { MusicTab.ALBUMS -> LibraryQuery.albums(it); MusicTab.ARTISTS -> LibraryQuery.artists(it); else -> LibraryQuery.folders(it) }
                    }
                    val unknown = when (tab) { MusicTab.ALBUMS -> unknownAlbum; MusicTab.ARTISTS -> unknownArtist; else -> unknownFolder }
                    items(groups, key = { it.name }) { g ->
                        val name = g.name.ifEmpty { unknown }
                        Row(Modifier.fillMaxWidth().clickable { drill = Drill.Group(name, g.tracks) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.music_tracks_count, g.tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                tab == MusicTab.PLAYLISTS -> {
                    item { OutlinedButton(onClick = { newPlaylist = true }) { Text(stringResource(R.string.playlist_new)) } }
                    if (data.playlists.isEmpty()) item { Text(stringResource(R.string.playlist_none), style = MaterialTheme.typography.bodySmall) }
                    items(data.playlists, key = { it.id }) { p ->
                        var menu by remember { mutableStateOf(false) }
                        var rename by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().clickable { drill = Drill.Playlist(p.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(stringResource(R.string.music_tracks_count, p.trackUris.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Column {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) }, onClick = { menu = false; rename = true })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) }, onClick = { menu = false; library.repository.deletePlaylist(p.id) })
                                }
                            }
                        }
                        if (rename) NameDialog(stringResource(R.string.playlist_rename), p.name, { rename = false }) { library.repository.renamePlaylist(p.id, it); rename = false }
                    }
                }
            }
        }
    }

    if (newPlaylist) NameDialog(stringResource(R.string.playlist_new), "", { newPlaylist = false }) { library.repository.createPlaylist(it); newPlaylist = false }
    newPlaylistWith?.let { t -> NameDialog(stringResource(R.string.playlist_new), "", { newPlaylistWith = null }) { library.repository.createPlaylist(it, listOf(t.uri)); newPlaylistWith = null } }
    addToPlaylist?.let { t ->
        AlertDialog(
            onDismissRequest = { addToPlaylist = null },
            title = { Text(stringResource(R.string.playlist_add_to)) },
            text = {
                Column {
                    if (data.playlists.isEmpty()) Text(stringResource(R.string.playlist_none), style = MaterialTheme.typography.bodySmall)
                    data.playlists.forEach { p ->
                        Text(p.name, Modifier.fillMaxWidth().clickable { library.repository.addToPlaylist(p.id, t.uri); addToPlaylist = null }.padding(vertical = 12.dp))
                    }
                    TextButton(onClick = { newPlaylistWith = t; addToPlaylist = null }) { Text(stringResource(R.string.playlist_new)) }
                }
            },
            confirmButton = { TextButton(onClick = { addToPlaylist = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PlayAllRow(list: List<Track>, play: (List<Track>, Int, Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Button(onClick = { play(list, 0, false) }) { Icon(Icons.Filled.PlayArrow, null); Text(stringResource(R.string.player_play_all)) }
        OutlinedButton(onClick = { play(list, 0, true) }) { Icon(Icons.Filled.Shuffle, null); Text(stringResource(R.string.player_shuffle_all)) }
    }
}

@Composable
private fun TrackRow(
    t: Track, favorite: Boolean, onPlay: () -> Unit, player: PlayerConnection, library: LibraryController,
    onAdd: () -> Unit, removeFromPlaylist: (() -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(t.artist.ifEmpty { stringResource(R.string.music_unknown_artist) }, formatTime(t.durationMs)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { library.repository.toggleFavorite(t.uri) }) {
            Icon(if (favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = stringResource(if (favorite) R.string.favorite_remove else R.string.favorite_add),
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_play_next)) }, onClick = { menu = false; player.playNext(t) })
                DropdownMenuItem(text = { Text(stringResource(R.string.queue_add)) }, onClick = { menu = false; player.enqueue(t) })
                DropdownMenuItem(text = { Text(stringResource(R.string.playlist_add_to)) }, onClick = { menu = false; onAdd() })
                removeFromPlaylist?.let { r -> DropdownMenuItem(text = { Text(stringResource(R.string.playlist_remove)) }, onClick = { menu = false; r() }) }
            }
        }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(60) }, singleLine = true, label = { Text(stringResource(R.string.playlist_name)) }) },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.playlist_create)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
