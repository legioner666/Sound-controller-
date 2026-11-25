package com.soniccontrol.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.soniccontrol.core.musiclibrary.MediaStoreScanner
import com.soniccontrol.core.musiclibrary.MusicRepository
import com.soniccontrol.core.musiclibrary.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The track list the UI shows: the phone's MediaStore music plus files the user added through the file picker. */
class LibraryController(private val context: Context, private val scope: CoroutineScope, val repository: MusicRepository = PlayerRuntime.repository(context)) {
    private val scanner = MediaStoreScanner(context)
    private val scanned = MutableStateFlow<List<Track>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _hasPermission = MutableStateFlow(scanner.hasPermission())
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()
    private val _lastImport = MutableStateFlow<Pair<Int, Int>?>(null)
    /** (added, unreadable) of the last import, shown once. */
    val lastImport: StateFlow<Pair<Int, Int>?> = _lastImport.asStateFlow()

    val tracks: StateFlow<List<Track>> = combine(scanned, repository.data) { s, d -> (s + d.imported).distinctBy { it.uri } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Re-checks the permission and rescans. Cheap enough to call when the Music tab opens. */
    fun refresh() {
        _hasPermission.value = scanner.hasPermission()
        if (!_hasPermission.value) { scanned.value = emptyList(); return }
        if (_scanning.value) return
        _scanning.value = true
        scope.launch { try { scanned.value = scanner.scan() } finally { _scanning.value = false } }
    }

    fun import(uris: List<Uri>) {
        scope.launch {
            var ok = 0; var bad = 0
            val tracks = ArrayList<Track>()
            for (u in uris) {
                runCatching { context.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val t = scanner.describe(u)
                if (t != null) { tracks += t; ok++ } else bad++
            }
            if (tracks.isNotEmpty()) repository.importTracks(tracks)
            _lastImport.value = ok to bad
        }
    }

    fun consumeImportResult() { _lastImport.value = null }
}
