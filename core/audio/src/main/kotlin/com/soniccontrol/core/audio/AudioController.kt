package com.soniccontrol.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VolumeState(val current: Int, val max: Int)

/**
 * Universal-tier audio functions using only public Android APIs:
 * media volume (never above the system maximum, no boost), media keys, Bluetooth output detection.
 * The sleep timer lives in the player (TASK 013: one timer, player volume only, never the system volume).
 */
class AudioController(context: Context, private val scope: CoroutineScope) {
    private val audio = context.getSystemService(AudioManager::class.java)

    private val _volume = MutableStateFlow(readVolume())
    val volume: StateFlow<VolumeState> = _volume.asStateFlow()

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

    companion object {
        private val BT_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }
}
