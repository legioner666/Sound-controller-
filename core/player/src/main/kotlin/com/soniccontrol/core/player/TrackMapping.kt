package com.soniccontrol.core.player

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.soniccontrol.core.musiclibrary.Track

internal object TrackMapping {
    private const val K_FOLDER = "folder"
    private const val K_ADDED = "added"
    private const val K_ALBUM_ID = "albumId"
    private const val K_DURATION = "durationMs"

    fun toItem(t: Track): MediaItem {
        val extras = Bundle().apply { putString(K_FOLDER, t.folder); putLong(K_ADDED, t.dateAddedSeconds); putLong(K_ALBUM_ID, t.albumId ?: 0L); putLong(K_DURATION, t.durationMs) }
        val meta = MediaMetadata.Builder()
            .setTitle(t.title).setArtist(t.artist.ifEmpty { null }).setAlbumTitle(t.album.ifEmpty { null })
            .setIsPlayable(true).setIsBrowsable(false).setExtras(extras)
            .also { b -> t.albumId?.let { b.setArtworkUri(ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), it)) } }
            .build()
        return MediaItem.Builder().setMediaId(t.uri).setUri(t.uri).setMediaMetadata(meta).build()
    }

    fun fromItem(i: MediaItem): Track {
        val m = i.mediaMetadata
        val x = m.extras
        return Track(
            uri = i.mediaId, title = m.title?.toString().orEmpty(), artist = m.artist?.toString().orEmpty(), album = m.albumTitle?.toString().orEmpty(),
            durationMs = x?.getLong(K_DURATION) ?: 0L, folder = x?.getString(K_FOLDER).orEmpty(),
            dateAddedSeconds = x?.getLong(K_ADDED) ?: 0L, albumId = x?.getLong(K_ALBUM_ID)?.takeIf { it > 0 },
        )
    }
}
