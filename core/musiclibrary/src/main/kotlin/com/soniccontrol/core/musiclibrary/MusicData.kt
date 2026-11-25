package com.soniccontrol.core.musiclibrary

data class Playlist(val id: String, val name: String, val trackUris: List<String> = emptyList())

/** Saved queue: restored paused after the process or the phone restarts. */
data class QueueSnapshot(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    /** 0 = off, 1 = all, 2 = one (same numbering as Media3). */
    val repeatMode: Int = 0,
)

/** Everything persisted by the player. */
data class MusicData(
    val imported: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val queue: QueueSnapshot? = null,
)

/** Small tab-separated line format: no JSON dependency, tolerant of unknown or corrupt lines (they are skipped). */
object MusicDataCodec {
    private fun esc(s: String) = buildString { for (c in s) when (c) { '\\' -> append("\\\\"); '\t' -> append("\\t"); '\n' -> append("\\n"); '\r' -> append("\\r"); else -> append(c) } }

    private fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 't' -> append('\t'); 'n' -> append('\n'); 'r' -> append('\r'); else -> append(s[i + 1]) }
                i += 2
            } else { append(c); i++ }
        }
    }

    private fun trackFields(t: Track) = listOf(t.uri, t.title, t.artist, t.album, t.durationMs.toString(), t.folder, t.dateAddedSeconds.toString(), t.albumId?.toString() ?: "")

    private fun parseTrack(f: List<String>): Track? {
        if (f.size < 8 || f[0].isBlank()) return null
        return Track(f[0], f[1], f[2], f[3], f[4].toLongOrNull() ?: 0, f[5], f[6].toLongOrNull() ?: 0, f[7].toLongOrNull())
    }

    fun encode(d: MusicData): String = buildString {
        fun line(vararg f: String) { append(f.joinToString("\t") { esc(it) }).append('\n') }
        line("v", "1")
        d.imported.forEach { line("i", *trackFields(it).toTypedArray()) }
        d.playlists.forEach { line("p", it.id, it.name, *it.trackUris.toTypedArray()) }
        d.favorites.forEach { line("f", it) }
        d.queue?.let { q ->
            line("q", q.index.toString(), q.positionMs.toString(), q.shuffle.toString(), q.repeatMode.toString())
            q.tracks.forEach { line("t", *trackFields(it).toTypedArray()) }
        }
    }

    fun decode(text: String): MusicData {
        val imported = ArrayList<Track>(); val playlists = ArrayList<Playlist>(); val fav = LinkedHashSet<String>()
        val qTracks = ArrayList<Track>(); var qHead: List<String>? = null
        for (raw in text.lineSequence()) {
            if (raw.isEmpty()) continue
            val f = raw.split('\t').map { unesc(it) }
            when (f[0]) {
                "i" -> parseTrack(f.drop(1))?.let { imported += it }
                "p" -> if (f.size >= 3 && f[1].isNotBlank()) playlists += Playlist(f[1], f[2], f.drop(3))
                "f" -> if (f.size >= 2 && f[1].isNotBlank()) fav += f[1]
                "q" -> if (f.size >= 5) qHead = f.drop(1)
                "t" -> parseTrack(f.drop(1))?.let { qTracks += it }
            }
        }
        val queue = qHead?.let { h ->
            if (qTracks.isEmpty()) null else QueueSnapshot(
                qTracks, (h[0].toIntOrNull() ?: 0).coerceIn(0, qTracks.lastIndex), (h[1].toLongOrNull() ?: 0).coerceAtLeast(0),
                h[2].toBooleanStrictOrNull() ?: false, (h[3].toIntOrNull() ?: 0).coerceIn(0, 2),
            )
        }
        return MusicData(imported.distinctBy { it.uri }, playlists.distinctBy { it.id }, fav, queue)
    }
}
