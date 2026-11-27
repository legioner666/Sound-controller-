package com.soniccontrol.core.player

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Last sleep-fade settings and the player's own volume (TASK 013). SharedPreferences in the app's private storage, so the
 * values survive restarts and APK updates. Same process for UI and service: the UI writes here, the service follows [settings].
 */
object SleepFadeStore {
    private const val PREFS = "sonic_sleep_fade"
    private const val K_PLAYER_VOLUME = "player_volume"

    private val _settings = MutableStateFlow(SleepFadeSettings())
    val settings: StateFlow<SleepFadeSettings> = _settings.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Synchronized fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _settings.value = SleepFadeSettings.decode { p.all[it] }
    }

    fun update(context: Context, s: SleepFadeSettings) {
        init(context)
        val n = s.normalized()
        _settings.value = n
        prefs?.edit {
            SleepFadeSettings.encode(n).forEach { (k, v) ->
                when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is String -> putString(k, v) }
            }
        }
    }

    /** The user's player volume (0..1). Never the faded value: after a timer the next start is at the user's volume. */
    fun playerVolume(context: Context): Float { init(context); return (prefs?.getFloat(K_PLAYER_VOLUME, 1f) ?: 1f).coerceIn(0f, 1f) }

    fun savePlayerVolume(context: Context, v: Float) { init(context); prefs?.edit { putFloat(K_PLAYER_VOLUME, v.coerceIn(0f, 1f)) } }
}
