package com.soniccontrol.core.musiclibrary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MusicLibraryTest {
    private fun t(n: Int, title: String, artist: String = "", album: String = "", dur: Long = 1000L * n, folder: String = "Music/A", added: Long = n.toLong()) =
        Track("content://x/$n", title, artist, album, dur, folder, added)

    private val lib = listOf(
        t(1, "Zebra", "Émile", "Beta"), t(2, "alpha", "Bob", "Beta"), t(3, "Čaj", "", ""), t(4, "Midnight Signal", "Demo Artist", "Sonic Collection", folder = "Music/B"),
    )

    @Test fun searchIsCaseAndAccentInsensitiveAndAllTermsMustMatch() {
        assertEquals(listOf("Zebra"), LibraryQuery.search(lib, "emile").map { it.title })
        assertEquals(listOf("Čaj"), LibraryQuery.search(lib, "caj").map { it.title })
        assertEquals(listOf("Midnight Signal"), LibraryQuery.search(lib, "demo SIGNAL").map { it.title })
        assertTrue(LibraryQuery.search(lib, "demo zebra").isEmpty())
        assertEquals(lib.size, LibraryQuery.search(lib, "   ").size)
        assertEquals(listOf("Midnight Signal"), LibraryQuery.search(lib, "music/b").map { it.title })
    }

    @Test fun sortingAndUnknownArtistsLast() {
        assertEquals(listOf("alpha", "Čaj", "Midnight Signal", "Zebra"), LibraryQuery.sort(lib, SortOrder.TITLE).map { it.title })
        assertEquals("Čaj", LibraryQuery.sort(lib, SortOrder.ARTIST).last().title)            // unknown artist after named ones
        assertEquals(listOf(4, 3, 2, 1), LibraryQuery.sort(lib, SortOrder.DURATION, descending = true).map { it.uri.last().digitToInt() })
        assertEquals(listOf("Zebra", "alpha", "Čaj", "Midnight Signal"), LibraryQuery.sort(lib, SortOrder.DATE_ADDED).map { it.title })
    }

    @Test fun groupingByAlbumArtistFolder() {
        assertEquals(listOf("Beta", "Sonic Collection", ""), LibraryQuery.albums(lib).map { it.name })
        assertEquals(2, LibraryQuery.albums(lib).first().tracks.size)
        assertEquals(listOf("Bob", "Demo Artist", "Émile", ""), LibraryQuery.artists(lib).map { it.name })
        assertEquals(setOf("Music/A", "Music/B"), LibraryQuery.folders(lib).map { it.name }.toSet())
    }

    @Test fun codecRoundTripsAwkwardText() {
        val tricky = Track("content://x/9", "Tab\there \\ back\nline", "A\\B", "Al\rbum", 12345, "F/o", 99, 7)
        val d = MusicData(
            imported = listOf(tricky), playlists = listOf(Playlist("p1", "Road\ttrip", listOf("content://x/9", "content://x/1"))),
            favorites = setOf("content://x/1"), queue = QueueSnapshot(listOf(tricky, lib[0]), 1, 4500, shuffle = true, repeatMode = 2),
        )
        assertEquals(d, MusicDataCodec.decode(MusicDataCodec.encode(d)))
    }

    @Test fun decoderSkipsCorruptLinesAndClampsValues() {
        val text = "garbage\nv\t1\ni\tonly\tthree\tfields\np\t\t\nq\t99\t-5\tmaybe\t9\nt\tcontent://x/1\tT\t\t\t10\t\t0\t\nf\t\n"
        val d = MusicDataCodec.decode(text)
        assertTrue(d.imported.isEmpty()); assertTrue(d.playlists.isEmpty()); assertTrue(d.favorites.isEmpty())
        val q = d.queue!!
        assertEquals(0, q.index); assertEquals(0L, q.positionMs); assertEquals(false, q.shuffle); assertEquals(2, q.repeatMode)
        assertNull(MusicDataCodec.decode("q\t0\t0\ttrue\t0\n").queue)         // a queue without tracks is dropped
        assertEquals(MusicData(), MusicDataCodec.decode(""))
    }

    private fun TestScope.repo(f: File) = MusicRepository(f, backgroundScope, UnconfinedTestDispatcher(testScheduler), newId = run { var n = 0; { "id${++n}" } })

    @Test fun repositoryPersistsPlaylistsFavoritesAndQueue() = runTest(UnconfinedTestDispatcher()) {
        val f = File.createTempFile("music", ".txt").also { it.delete(); it.deleteOnExit() }
        val r = repo(f); r.load()
        val id = r.createPlaylist("  Gym  ", listOf("u1", "u1"))!!
        r.addToPlaylist(id, "u2"); r.addToPlaylist(id, "u2")
        r.toggleFavorite("u1"); r.toggleFavorite("u3"); r.toggleFavorite("u3")
        r.importTracks(listOf(lib[0], lib[0]))
        r.saveQueue(QueueSnapshot(lib, 2, 1000))
        assertNull(r.createPlaylist("   "))
        val reloaded = repo(f).also { it.load() }.data.value
        assertEquals(listOf("u1", "u2"), reloaded.playlists.single().trackUris)
        assertEquals("Gym", reloaded.playlists.single().name)
        assertEquals(setOf("u1"), reloaded.favorites)
        assertEquals(1, reloaded.imported.size)
        assertEquals(2, reloaded.queue!!.index)
        r.removeFromPlaylist(id, "u1"); r.renamePlaylist(id, "Run"); r.deletePlaylist("nope")
        assertEquals(listOf("u2"), r.data.value.playlists.single().trackUris); assertEquals("Run", r.data.value.playlists.single().name)
        r.deletePlaylist(id); assertTrue(r.data.value.playlists.isEmpty())
    }

    @Test fun damagedFileStartsEmpty() = runTest(UnconfinedTestDispatcher()) {
        val f = File.createTempFile("music", ".txt").also { it.writeBytes(byteArrayOf(0, -1, 5, 9, 10, -80)); it.deleteOnExit() }
        val r = repo(f); r.load()
        assertNotNull(r.data.value); assertTrue(r.data.value.playlists.isEmpty())
    }
}
