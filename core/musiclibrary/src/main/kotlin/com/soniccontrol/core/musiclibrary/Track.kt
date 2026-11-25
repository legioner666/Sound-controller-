package com.soniccontrol.core.musiclibrary

/** One playable local file. [id] is the content URI string, which is stable for the file. Empty artist/album mean "unknown". */
data class Track(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val folder: String = "",
    val dateAddedSeconds: Long = 0,
    val albumId: Long? = null,
) {
    val id: String get() = uri
}

enum class SortOrder { TITLE, ARTIST, ALBUM, DATE_ADDED, DURATION }

data class TrackGroup(val name: String, val tracks: List<Track>)
