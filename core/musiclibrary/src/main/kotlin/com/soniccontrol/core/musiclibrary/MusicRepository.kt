package com.soniccontrol.core.musiclibrary

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * Imported files, playlists, favourites and the saved queue, stored in one private file on the phone.
 * Writes are atomic (temp file + rename). A missing or damaged file starts empty; it never crashes the player.
 */
class MusicRepository(
    private val file: File,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val _data = MutableStateFlow(MusicData())
    val data: StateFlow<MusicData> = _data.asStateFlow()
    private val writeLock = Mutex()

    /** Synchronous load, called once at start-up (the file is small). */
    fun load() {
        _data.value = runCatching { if (file.exists()) MusicDataCodec.decode(file.readText()) else MusicData() }.getOrDefault(MusicData())
    }

    private fun mutate(transform: (MusicData) -> MusicData) {
        _data.update(transform)
        val snapshot = _data.value
        scope.launch(io) { writeLock.withLock { write(snapshot) } }
    }

    private fun write(d: MusicData) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(MusicDataCodec.encode(d))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    fun importTracks(tracks: List<Track>) = mutate { d -> d.copy(imported = (d.imported + tracks).distinctBy { it.uri }) }
    fun removeImported(uri: String) = mutate { d -> d.copy(imported = d.imported.filterNot { it.uri == uri }) }

    fun toggleFavorite(uri: String) = mutate { d -> d.copy(favorites = if (uri in d.favorites) d.favorites - uri else d.favorites + uri) }

    fun createPlaylist(name: String, initial: List<String> = emptyList()): String? {
        val n = name.trim().take(60)
        if (n.isEmpty()) return null
        val id = newId()
        mutate { d -> d.copy(playlists = d.playlists + Playlist(id, n, initial.distinct())) }
        return id
    }

    fun renamePlaylist(id: String, name: String) {
        val n = name.trim().take(60)
        if (n.isEmpty()) return
        mutate { d -> d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(name = n) else it }) }
    }

    fun deletePlaylist(id: String) = mutate { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    /** Adds a track once; adding it again keeps a single entry. */
    fun addToPlaylist(id: String, uri: String) = mutate { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id && uri !in it.trackUris) it.copy(trackUris = it.trackUris + uri) else it })
    }

    fun removeFromPlaylist(id: String, uri: String) = mutate { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) it.copy(trackUris = it.trackUris - uri) else it })
    }

    fun saveQueue(q: QueueSnapshot?) = mutate { d -> d.copy(queue = q) }
}
