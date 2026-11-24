package com.soniccontrol.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VolumeState(val current: Int, val max: Int)

data class SleepTimerState(val endsAtMillis: Long, val totalMillis: Long)

/**
 * Universal-tier audio functions using only public Android APIs:
 * media volume (never above the system maximum, no boost), media keys, Bluetooth output detection,
 * and a sleep timer that only ever lowers the volume.
 */
class AudioController(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)

    private val _volume = MutableStateFlow(readVolume())
    val volume: StateFlow<VolumeState> = _volume.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleep.asStateFlow()
    private var sleepJob: Job? = null

    private fun readVolume() = VolumeState(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))

    fun refreshVolume() { _volume.value = readVolume() }

    /** Sets media volume within the system range; shows the system UI so the user sees the change. */
    fun setVolume(index: Int) {
        val v = readVolume()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, v.max), AudioManager.FLAG_SHOW_UI)
        refreshVolume()
    }

    fun playPause() = mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    fun next() = mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
    fun previous() = mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    private fun mediaKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    /** Name of the Bluetooth output currently available to media, if any. */
    fun bluetoothOutputName(): String? =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type in BT_TYPES }?.productName?.toString()

    fun isMusicActive(): Boolean = audio.isMusicActive

    /**
     * Sleep timer: during the last [fadeMillis] the media volume steps down to zero, then playback is paused
     * and the original volume restored. Runs while the app process is alive.
     */
    fun startSleepTimer(totalMillis: Long, fadeMillis: Long = 60_000) {
        sleepJob?.cancel()
        val start = System.currentTimeMillis()
        _sleep.value = SleepTimerState(start + totalMillis, totalMillis)
        sleepJob = scope.launch {
            delay((totalMillis - fadeMillis).coerceAtLeast(0))
            val original = readVolume().current
            val steps = original.coerceAtLeast(1)
            val stepDelay = fadeMillis.coerceAtLeast(1) / steps
            var v = original
            while (isActive && v > 0) {
                v--
                // Only ever lower the volume.
                if (readVolume().current > v) audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                delay(stepDelay)
            }
            if (audio.isMusicActive) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            delay(1_000)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, original, 0)
            refreshVolume()
            _sleep.value = null
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel(); sleepJob = null; _sleep.value = null
    }

    companion object {
        private val BT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
