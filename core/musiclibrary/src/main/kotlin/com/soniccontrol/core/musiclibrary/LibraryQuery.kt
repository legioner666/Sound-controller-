package com.soniccontrol.core.musiclibrary

import java.text.Normalizer
import java.util.Locale

/** Pure search / sort / grouping over an in-memory track list. Nothing here leaves the device. */
object LibraryQuery {
    private val MARKS = Regex("\\p{Mn}+")

    private fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** Every whitespace-separated term must occur in the title, artist, album or folder (case and accent insensitive). */
    fun search(tracks: List<Track>, query: String): List<Track> {
        val terms = fold(query).split(' ', '\t').filter { it.isNotBlank() }
        if (terms.isEmpty()) return tracks
        return tracks.filter { t ->
            val hay = fold("${t.title} ${t.artist} ${t.album} ${t.folder}")
            terms.all { it in hay }
        }
    }

    fun sort(tracks: List<Track>, order: SortOrder, descending: Boolean = false): List<Track> {
        val cmp: Comparator<Track> = when (order) {
            SortOrder.TITLE -> compareBy({ fold(it.title) }, { it.uri })
            SortOrder.ARTIST -> compareBy({ fold(it.artist).ifEmpty { "\uffff" } }, { fold(it.album) }, { fold(it.title) }, { it.uri })
            SortOrder.ALBUM -> compareBy({ fold(it.album).ifEmpty { "\uffff" } }, { fold(it.title) }, { it.uri })
            SortOrder.DATE_ADDED -> compareBy({ it.dateAddedSeconds }, { it.uri })
            SortOrder.DURATION -> compareBy({ it.durationMs }, { it.uri })
        }
        return tracks.sortedWith(if (descending) cmp.reversed() else cmp)
    }

    private fun group(tracks: List<Track>, key: (Track) -> String): List<TrackGroup> =
        tracks.groupBy(key).map { (k, v) -> TrackGroup(k, v) }
            .sortedWith(compareBy({ it.name.isEmpty() }, { fold(it.name) }))

    fun albums(tracks: List<Track>) = group(tracks) { it.album }
    fun artists(tracks: List<Track>) = group(tracks) { it.artist }
    fun folders(tracks: List<Track>) = group(tracks) { it.folder }
}
