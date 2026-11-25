package com.soniccontrol.core.musiclibrary

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the phone's own audio library through MediaStore. Metadata never leaves the device. */
class MediaStoreScanner(private val context: Context) {

    fun hasPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    /** Music files only (ringtones and notification sounds are excluded). Empty without permission. */
    suspend fun scan(): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 0", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val dur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val rel = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id)).toString()
                    out += Track(
                        uri = uri,
                        title = c.getString(title)?.takeIf { it.isNotBlank() } ?: c.getString(name).orEmpty().substringBeforeLast('.'),
                        artist = c.getString(artist)?.takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty(),
                        album = c.getString(album)?.takeIf { it != MediaStore.UNKNOWN_STRING }.orEmpty(),
                        durationMs = c.getLong(dur), folder = c.getString(rel).orEmpty().trimEnd('/'),
                        dateAddedSeconds = c.getLong(added), albumId = c.getLong(albumId).takeIf { it > 0 },
                    )
                }
            }
        }
        out
    }

    /** Describes a file picked through the system file picker (no storage permission needed). Null when it cannot be read as audio. */
    suspend fun describe(uri: Uri): Track? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withContext null
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            Track(
                uri = uri.toString(),
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: name?.substringBeforeLast('.') ?: "?",
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = dur, folder = "", dateAddedSeconds = System.currentTimeMillis() / 1000,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
