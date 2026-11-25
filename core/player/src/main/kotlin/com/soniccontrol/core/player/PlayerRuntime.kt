package com.soniccontrol.core.player

import android.content.Context
import com.soniccontrol.core.musiclibrary.MusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** One shared repository per process: the app UI and the playback service must never write the same file from two copies. */
object PlayerRuntime {
    @Volatile private var repo: MusicRepository? = null

    fun repository(context: Context): MusicRepository = repo ?: synchronized(this) {
        repo ?: MusicRepository(File(context.applicationContext.filesDir, "music.txt"), CoroutineScope(SupervisorJob() + Dispatchers.IO))
            .also { it.load(); repo = it }
    }
}
