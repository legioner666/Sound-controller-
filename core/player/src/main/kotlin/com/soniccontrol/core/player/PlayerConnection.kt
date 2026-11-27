package com.soniccontrol.core.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.soniccontrol.core.musiclibrary.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUiState(
    val connected: Boolean = false,
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val shuffle: Boolean = false,
    /** 0 = off, 1 = all, 2 = one. */
    val repeatMode: Int = 0,
    val durationMs: Long = 0,
    val sleepRemainingMillis: Long? = null,
    /** Plan of the running sleep timer (null without a timer); combine with the settings via [fadeCurve]. */
    val sleepStartedAt: Long = 0,
    val sleepEndsAt: Long = 0,
    val fadeAnchorAt: Long = 0,
    val fadeAnchorVolume: Float = 1f,
    /** The user's player volume 0..1 (the app player's own volume, not Android's system volume). */
    val playerVolume: Float = 1f,
    val bassSupported: Boolean = false,
    val bassStrength: Int = 0,
) {
    /** The running timer's volume plan with [settings], or null without a timer. */
    fun fadeCurve(settings: SleepFadeSettings): FadeCurve? {
        if (sleepRemainingMillis == null || sleepEndsAt <= sleepStartedAt) return null
        val total = sleepEndsAt - sleepStartedAt
        return FadeCurve(sleepStartedAt, sleepEndsAt, fadeAnchorAt, fadeAnchorVolume, sleepEndsAt - settings.fadeMillis(total), settings.target)
    }
}

/** The app's handle on the playback service. Everything goes through the media session, like any other controller. */
class PlayerConnection(context: Context) {
    private val app = context.applicationContext.also { SleepFadeStore.init(it) }

    /** Last sleep-fade settings (kept across restarts); changes apply at once, also to a running timer. */
    val fadeSettings: StateFlow<SleepFadeSettings> = SleepFadeStore.settings

    fun updateFadeSettings(s: SleepFadeSettings) = SleepFadeStore.update(app, s)
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pending: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private val waiting = ArrayDeque<(MediaController) -> Unit>()

    /** Connects (and starts) the service on first use. Safe to call repeatedly. */
    fun connect() {
        if (controller != null || pending != null) return
        val token = SessionToken(app, ComponentName(app.packageName, "com.soniccontrol.core.player.SonicPlaybackService"))
        val f = MediaController.Builder(app, token).setListener(object : MediaController.Listener {
            override fun onExtrasChanged(c: MediaController, extras: Bundle) = refresh()
            override fun onDisconnected(c: MediaController) { controller = null; pending = null; _state.value = PlayerUiState() }
        }).buildAsync()
        pending = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull()
            pending = null
            if (c == null) return@addListener
            controller = c
            c.addListener(object : Player.Listener { override fun onEvents(p: Player, events: Player.Events) = refresh() })
            refresh()
            while (waiting.isNotEmpty()) waiting.removeFirst()(c)
        }, MoreExecutors.directExecutor())
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { waiting.addLast(block); connect() }
    }

    private fun refresh() {
        val c = controller ?: return
        val extras = c.sessionExtras
        val items = (0 until c.mediaItemCount).map { TrackMapping.fromItem(c.getMediaItemAt(it)) }
        val idx = c.currentMediaItemIndex
        _state.value = PlayerUiState(
            connected = true, current = items.getOrNull(idx), isPlaying = c.isPlaying, queue = items, currentIndex = if (items.isEmpty()) -1 else idx,
            shuffle = c.shuffleModeEnabled, repeatMode = c.repeatMode, durationMs = c.duration.takeIf { it > 0 } ?: items.getOrNull(idx)?.durationMs ?: 0L,
            sleepRemainingMillis = extras.getLong(PlayerCommands.EXTRA_SLEEP_REMAINING, -1L).takeIf { it >= 0 },
            sleepStartedAt = extras.getLong(PlayerCommands.EXTRA_SLEEP_STARTED_AT, 0L), sleepEndsAt = extras.getLong(PlayerCommands.EXTRA_SLEEP_ENDS_AT, 0L),
            fadeAnchorAt = extras.getLong(PlayerCommands.EXTRA_FADE_ANCHOR_AT, 0L), fadeAnchorVolume = extras.getFloat(PlayerCommands.EXTRA_FADE_ANCHOR_VOLUME, 1f),
            playerVolume = extras.getFloat(PlayerCommands.EXTRA_PLAYER_VOLUME, SleepFadeStore.playerVolume(app)),
            bassSupported = extras.getBoolean(PlayerCommands.EXTRA_BASS_SUPPORTED, false), bassStrength = extras.getInt(PlayerCommands.EXTRA_BASS_STRENGTH, 0),
        )
    }

    fun currentPositionMs(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0L

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        withController { c ->
            c.setMediaItems(tracks.map(TrackMapping::toItem), startIndex.coerceIn(0, tracks.lastIndex), 0L)
            c.shuffleModeEnabled = shuffle
            c.prepare(); c.play()
        }
    }

    fun togglePlay() = withController { c -> if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0); c.play() } }
    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun jumpTo(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0L); c.play() } }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun cycleRepeat() = withController { c -> c.repeatMode = when (c.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } }

    fun enqueue(track: Track) = withController { c -> val had = c.mediaItemCount > 0; c.addMediaItem(TrackMapping.toItem(track)); if (!had) c.prepare() }
    fun playNext(track: Track) = withController { c ->
        val had = c.mediaItemCount > 0
        c.addMediaItem(if (had) c.currentMediaItemIndex + 1 else 0, TrackMapping.toItem(track)); if (!had) c.prepare()
    }
    fun removeFromQueue(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }
    fun clearQueue() = withController { it.clearMediaItems() }

    fun setSleepTimerMinutes(minutes: Int) = withController { c ->
        c.sendCustomCommand(PlayerCommands.SLEEP_TIMER, Bundle().apply { putInt(PlayerCommands.ARG_MINUTES, minutes) })
    }

    /** Player volume 0..1 (never the system volume). During a fade the rest of the fade continues from this level. */
    fun setPlayerVolume(v: Float) = withController { it.volume = v.coerceIn(0f, 1f) }

    fun setBassBoost(strength: Int) = withController { c ->
        c.sendCustomCommand(PlayerCommands.BASS_BOOST, Bundle().apply { putInt(PlayerCommands.ARG_STRENGTH, strength) })
    }

    fun release() { controller?.release(); controller = null }
}
