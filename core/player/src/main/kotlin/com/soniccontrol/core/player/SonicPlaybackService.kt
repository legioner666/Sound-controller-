package com.soniccontrol.core.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    /** One loop for the whole timer: checks the end by the wall clock and applies the planned player volume. */
    private val timerTick = Runnable { onTimerTick() }
    /** Smooth way back to the user's volume after a mid-fade cancel. */
    private var restore: VolumeRestore? = null
    private val restoreTick = object : Runnable {
        override fun run() {
            val r = restore ?: return
            val now = SystemClock.elapsedRealtime()
            applyVolume(r.volumeAt(now))
            if (r.isDone(now)) restore = null else main.postDelayed(this, RESTORE_STEP_MILLIS)
        }
    }
    /** Last volume this service set itself; anything else seen in onVolumeChanged is a manual change. */
    private var appliedVolume = 1f
    private val scope = kotlinx.coroutines.MainScope()
    private var bass: BassBoost? = null
    private var bassStrength = 0
    private var announcedSession = 0
    private val saveRunnable = Runnable { saveQueueNow() }
    private companion object {
        const val FADE_STEP_MILLIS = 250L
        /** Longest wait between checks before the ramp: a Handler delay does not count deep sleep, the wall clock does. */
        const val MAX_IDLE_CHECK_MILLIS = 15_000L
        const val VOLUME_EPSILON = 0.002f
        const val RESTORE_STEP_MILLIS = 50L
    }

    override fun onCreate() {
        super.onCreate()
        if (com.soniccontrol.core.background.AppShutdown.isSuppressed(this)) { stopSelf(); return }   // sleep timer ended: stay off until the app is opened by hand
        SleepFadeStore.init(this)
        // Monotonic clock: counts deep sleep, unaffected by the user or the network changing the wall-clock time.
        timer = SleepTimer(SystemClock::elapsedRealtime, SleepFadeStore.settings.value, SleepFadeStore.playerVolume(this))
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        session = MediaSession.Builder(this, player).setCallback(Callback()).build()
        restoreQueue()
        applyVolume(timer.userVolume)                 // the user's player volume (never a faded one from a previous timer)
        restoreSavedTimer()
        player.addListener(object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_TIMELINE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                        Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) scheduleSave()
            }
            override fun onAudioSessionIdChanged(audioSessionId: Int) = announceAudioSession(audioSessionId)
            override fun onVolumeChanged(volume: Float) {
                if (kotlin.math.abs(volume - appliedVolume) <= VOLUME_EPSILON) return   // our own fade step
                // Manual change (app slider or any media controller): saved, and the rest of the fade starts from it.
                appliedVolume = volume
                restore = null; main.removeCallbacks(restoreTick)    // the user's own level wins over a restore ramp
                timer.onManualVolume(volume)
                saveTimer()
                SleepFadeStore.savePlayerVolume(this@SonicPlaybackService, volume)
                if (timer.isRunning) reschedule()
                publishExtras()
            }
        })
        announceAudioSession(player.audioSessionId)
        SleepTimerAlarm.onAlarm = { main.post { if (::timer.isInitialized && timer.isRunning) reschedule() } }
        scope.launch {
            // Settings changed in the UI: applied at once, also while the timer runs (no jump, see SleepTimer.updateSettings).
            SleepFadeStore.settings.collect { s ->
                if (s == timer.settings) return@collect
                timer.updateSettings(s)
                saveTimer()
                if (timer.isRunning) reschedule()
                publishExtras()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (com.soniccontrol.core.background.AppShutdown.isSuppressed(this)) null else session

    /**
     * Swiping the app away stops the service unless music is playing or a sleep timer runs. Even if Android later stops the
     * process, a running timer is saved and its alarm still ends it (SleepTimerAlarm).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (::timer.isInitialized && timer.isRunning) return
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        if (!::player.isInitialized) { super.onDestroy(); return }   // started while suppressed: nothing was created
        main.removeCallbacks(timerTick); main.removeCallbacks(restoreTick); main.removeCallbacks(saveRunnable)
        scope.cancel()
        SleepTimerAlarm.onAlarm = null
        // A running timer is NOT cancelled here: it stays saved and its alarm stays set, so it still ends on time.
        saveTimer()
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
     * Timer end — whatever the target volume: playback stops, the queue and position are saved (paused), and the whole app is
     * switched off: device monitoring and the battery notification, Bluetooth sessions, notifications, the UI. Nothing may
     * restart until the user opens the app by hand (AppShutdown, see SonicApp). The faded volume is not saved: the next start
     * is at the user's own player volume.
     */
    private fun onTimerTick() { if (applyPlanOrEnd()) scheduleNext() }

    /** Re-plans at once after a change (settings, manual volume, alarm) and schedules the next check. */
    private fun reschedule() {
        main.removeCallbacks(timerTick)
        if (applyPlanOrEnd()) scheduleNext()
    }

    /** Ends the timer when its time is up (monotonic clock), otherwise applies the planned player volume. False when nothing runs. */
    private fun applyPlanOrEnd(): Boolean {
        if (timer.consumeIfExpired()) { endTimer(); return false }
        if (!timer.isRunning) return false
        applyVolume(timer.volume())
        return true
    }

    /** Next check: every 250 ms while the volume goes down, otherwise at the ramp start (at most every 15 s) or the end. */
    private fun scheduleNext() {
        val left = timer.remainingMillis() ?: return
        val toRamp = timer.fadeStartDelayMillis() ?: left
        val next = if (toRamp <= 0) FADE_STEP_MILLIS else minOf(toRamp, MAX_IDLE_CHECK_MILLIS)
        main.postDelayed(timerTick, minOf(next, left).coerceAtLeast(1))
    }

    private fun endTimer() {
        main.removeCallbacks(timerTick); main.removeCallbacks(restoreTick); restore = null
        SleepTimerAlarm.cancel(this)
        SleepFadeStore.saveTimer(this, null)
        applyVolume(0f)
        player.pause()
        saveQueueNow()
        player.stop()
        com.soniccontrol.core.background.AppShutdown.suppress(this)   // stops monitoring + sessions + battery notification, closes the UI (see SonicApp)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyVolume(v: Float) {
        appliedVolume = v.coerceIn(0f, 1f)
        if (kotlin.math.abs(player.volume - appliedVolume) > 0.0005f) player.volume = appliedVolume
    }

    /**
     * New duration, or cancel (minutes <= 0). A new duration continues from the volume the player has right now (no jump back
     * to the start volume, TASK 013 audit). Cancelling returns to the user's volume over 1.5 s instead of at once.
     */
    private fun setSleepTimer(minutes: Int) {
        main.removeCallbacks(timerTick); main.removeCallbacks(restoreTick); restore = null
        SleepTimerAlarm.cancel(this)
        val total = timer.set(minutes, currentLevel = appliedVolume)
        if (total != null) {
            SleepFadeStore.update(this, SleepFadeStore.settings.value.copy(lastTimerMinutes = minutes.coerceAtMost(SleepTimer.MAX_MINUTES)))
            timer.curve()?.let { SleepTimerAlarm.schedule(this, it.endsAt) }
            saveTimer()
            reschedule()
        } else {
            saveTimer()
            if (kotlin.math.abs(appliedVolume - timer.userVolume) > VOLUME_EPSILON) {
                restore = VolumeRestore(appliedVolume, timer.userVolume, SystemClock.elapsedRealtime())
                main.post(restoreTick)
            }
        }
        publishExtras()
    }

    /** Saved timer from before the process was stopped: continue it, or drop it when it is over or from another boot. */
    private fun restoreSavedTimer() {
        val (snap, boot) = SleepFadeStore.loadTimer(this) ?: return
        when (snap.check(SystemClock.elapsedRealtime(), SleepFadeStore.bootCount(this), boot)) {
            SleepTimerSnapshot.Verdict.RESTORE -> {
                timer.restore(snap)
                SleepTimerAlarm.schedule(this, snap.endsAt)
                reschedule()
            }
            // Ended while nothing ran (the alarm switched the app off then); the user has opened the app since: just forget it.
            SleepTimerSnapshot.Verdict.EXPIRED, SleepTimerSnapshot.Verdict.DISCARD -> SleepFadeStore.saveTimer(this, null)
        }
    }

    private fun saveTimer() = SleepFadeStore.saveTimer(this, timer.snapshot())

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

    private fun isInternal(c: MediaSession.ControllerInfo): Boolean = ControllerAccess.isInternal(c.uid, c.packageName, Process.myUid(), packageName)

    /** Timer and Bass Boost state goes to the app's own controllers only. */
    private fun publishExtras() {
        val s = session ?: return
        val extras = Bundle().apply {
            putLong(PlayerCommands.EXTRA_SLEEP_REMAINING, timer.remainingMillis() ?: -1L)
            putFloat(PlayerCommands.EXTRA_PLAYER_VOLUME, timer.userVolume)
            timer.curve()?.let { c ->
                putLong(PlayerCommands.EXTRA_SLEEP_STARTED_AT, c.startedAt); putLong(PlayerCommands.EXTRA_SLEEP_ENDS_AT, c.endsAt)
                putLong(PlayerCommands.EXTRA_FADE_ANCHOR_AT, c.anchorAt); putFloat(PlayerCommands.EXTRA_FADE_ANCHOR_VOLUME, c.anchorVolume)
            }
            putInt(PlayerCommands.EXTRA_BASS_STRENGTH, bassStrength)
            putBoolean(PlayerCommands.EXTRA_BASS_SUPPORTED, bassSupported)
        }
        s.connectedControllers.filter(::isInternal).forEach { s.setSessionExtras(it, extras) }
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
        /**
         * Only Sonic Control's own controller gets the sleep-timer and Bass Boost commands (and sees their state). Other
         * controllers (system media controls, watches, cars, other apps) get the standard media commands only.
         */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().apply {
                if (isInternal(controller)) add(PlayerCommands.SLEEP_TIMER).add(PlayerCommands.BASS_BOOST)
            }.build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) { publishExtras() }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            // Second check (Media3 already refuses commands that were not granted in onConnect).
            if (!isInternal(controller)) return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_PERMISSION_DENIED))
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
