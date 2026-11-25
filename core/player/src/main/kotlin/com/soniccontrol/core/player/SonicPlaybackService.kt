package com.soniccontrol.core.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.soniccontrol.core.musiclibrary.QueueSnapshot

/**
 * Local music playback (Media3 ExoPlayer + MediaSession).
 *  - audio focus is handled by the player (calls and other apps pause it; it resumes only when the system says so),
 *  - becoming-noisy handling pauses when the headphones disconnect, so music never starts on the phone speaker,
 *  - the queue is restored PAUSED, and nothing starts music after a reboot: there is no boot receiver and no playback-resumption callback.
 * Independent of the headphone monitoring service.
 */
@androidx.media3.common.util.UnstableApi
class SonicPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())
    private lateinit var timer: SleepTimer
    private val timerTick = Runnable { onTimerFired() }
    private val fadeTick = object : Runnable {
        override fun run() {
            if (timer.remainingMillis() == null) return
            player.volume = timer.fadeFactor()
            if (timer.fadeFactor() > 0f) main.postDelayed(this, FADE_STEP_MILLIS)
        }
    }
    private var bass: BassBoost? = null
    private var bassStrength = 0
    private var announcedSession = 0
    private val saveRunnable = Runnable { saveQueueNow() }
    private companion object { const val FADE_STEP_MILLIS = 250L }

    override fun onCreate() {
        super.onCreate()
        if (com.soniccontrol.core.background.AppShutdown.isSuppressed(this)) { stopSelf(); return }   // sleep timer ended: stay off until the app is opened by hand
        timer = SleepTimer(System::currentTimeMillis)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).setCallback(Callback()).build()
        restoreQueue()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) scheduleSave()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) = announceAudioSession(audioSessionId)
        })
        announceAudioSession(player.audioSessionId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (com.soniccontrol.core.background.AppShutdown.isSuppressed(this)) null else session

    /** Swiping the app away stops the service unless music is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (!::player.isInitialized) { super.onDestroy(); return }   // started while suppressed: nothing was created
        main.removeCallbacks(timerTick); main.removeCallbacks(fadeTick); main.removeCallbacks(saveRunnable)
        saveQueueNow()
        releaseBass()
        announceAudioSessionClosed()
        session?.run { release(); session = null }
        player.release()
        super.onDestroy()
    }

    // ---- queue persistence --------------------------------------------------------------------------------------

    private fun restoreQueue() {
        val q = PlayerRuntime.repository(this).data.value.queue ?: return
        player.setMediaItems(q.tracks.map(TrackMapping::toItem), q.index, q.positionMs)
        player.shuffleModeEnabled = q.shuffle
        player.repeatMode = q.repeatMode
        player.playWhenReady = false          // restored paused, always
        player.prepare()
    }

    private fun scheduleSave() { main.removeCallbacks(saveRunnable); main.postDelayed(saveRunnable, 1_500) }

    private fun saveQueueNow() {
        val items = (0 until player.mediaItemCount).map { TrackMapping.fromItem(player.getMediaItemAt(it)) }
        val repo = PlayerRuntime.repository(this)
        if (items.isEmpty()) { if (repo.data.value.queue != null) repo.saveQueue(null); return }
        repo.saveQueue(QueueSnapshot(items, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.shuffleModeEnabled, player.repeatMode))
    }

    // ---- sleep timer / bass boost -------------------------------------------------------------------------------

    /**
     * Timer end: playback stops completely, the queue is saved (paused), and the whole app is switched off — device monitoring,
     * Bluetooth sessions, notifications, the UI — and may not restart until the user opens the app again. The faded volume is NOT
     * restored here; this service instance simply ends (a new one starts at full volume only when the user starts it).
     */
    private fun onTimerFired() {
        if (!timer.consumeIfExpired()) { publishExtras(); return }
        main.removeCallbacks(fadeTick)
        player.volume = 0f
        player.pause()
        saveQueueNow()
        player.stop()
        com.soniccontrol.core.background.AppShutdown.suppress(this)   // stops monitoring + sessions, closes the UI (see SonicApp)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(timerTick); main.removeCallbacks(fadeTick)
        player.volume = 1f                                        // user changed or cancelled the timer: manual restore
        timer.set(minutes)?.let { total ->
            main.postDelayed(timerTick, total)
            main.postDelayed(fadeTick, timer.fadeStartDelayMillis() ?: total)
        }
        publishExtras()
    }

    private fun setBass(strength: Int) {
        bassStrength = strength.coerceIn(0, 1000)
        applyBass()
        publishExtras()
    }

    private fun applyBass() {
        runCatching {
            if (bass == null && bassStrength > 0 && announcedSession > 0) bass = BassBoost(0, announcedSession)
            bass?.let { b -> if (b.strengthSupported) { b.setStrength(bassStrength.toShort()); b.enabled = bassStrength > 0 } }
        }
    }

    private fun releaseBass() { runCatching { bass?.release() }; bass = null }

    private fun publishExtras() {
        session?.setSessionExtras(Bundle().apply {
            putLong(PlayerCommands.EXTRA_SLEEP_REMAINING, timer.remainingMillis() ?: -1L)
            putInt(PlayerCommands.EXTRA_BASS_STRENGTH, bassStrength)
            putBoolean(PlayerCommands.EXTRA_BASS_SUPPORTED, bassSupported)
        })
    }

    private val bassSupported: Boolean by lazy {
        runCatching { AudioEffect.queryEffects()?.any { it.type == AudioEffect.EFFECT_TYPE_BASS_BOOST } == true }.getOrDefault(false)
    }

    // ---- audio session announcement for the equaliser -----------------------------------------------------------

    private fun announceAudioSession(id: Int) {
        if (id == C.AUDIO_SESSION_ID_UNSET || id == announcedSession) return
        announceAudioSessionClosed()
        announcedSession = id
        releaseBass(); applyBass()
        sendBroadcast(Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).setPackage(packageName)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, id).putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName))
        publishExtras()
    }

    private fun announceAudioSessionClosed() {
        if (announcedSession <= 0) return
        sendBroadcast(Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).setPackage(packageName)
            .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, announcedSession).putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName))
        announcedSession = 0
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(PlayerCommands.SLEEP_TIMER).add(PlayerCommands.BASS_BOOST).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) { publishExtras() }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlayerCommands.SLEEP_TIMER.customAction -> setSleepTimer(args.getInt(PlayerCommands.ARG_MINUTES, 0))
                PlayerCommands.BASS_BOOST.customAction -> setBass(args.getInt(PlayerCommands.ARG_STRENGTH, 0))
                else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Items added by the app already carry their URI; nothing is resolved from any outside service. */
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }
}
