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

    // ---- running timer (survives the process being stopped while music is paused; not a reboot) ----------------------

    private const val K_T_START = "timer_started_at"
    private const val K_T_END = "timer_ends_at"
    private const val K_T_TOTAL = "timer_total"
    private const val K_T_ANCHOR_AT = "timer_anchor_at"
    private const val K_T_ANCHOR_V = "timer_anchor_volume"
    private const val K_T_BOOT = "timer_boot_count"

    /** Android's boot counter (-1 when unavailable; then a reboot is still caught by the monotonic clock being behind). */
    fun bootCount(context: Context): Int =
        runCatching { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    fun saveTimer(context: Context, s: SleepTimerSnapshot?) {
        init(context)
        prefs?.edit(commit = true) {
            if (s == null) listOf(K_T_START, K_T_END, K_T_TOTAL, K_T_ANCHOR_AT, K_T_ANCHOR_V, K_T_BOOT).forEach(::remove)
            else {
                putLong(K_T_START, s.startedAt); putLong(K_T_END, s.endsAt); putLong(K_T_TOTAL, s.totalMillis)
                putLong(K_T_ANCHOR_AT, s.anchorAt); putFloat(K_T_ANCHOR_V, s.anchorVolume); putInt(K_T_BOOT, bootCount(context))
            }
        }
    }

    /** Saved timer and the boot it belongs to, or null. */
    fun loadTimer(context: Context): Pair<SleepTimerSnapshot, Int>? {
        init(context)
        val p = prefs ?: return null
        if (!p.contains(K_T_END)) return null
        return SleepTimerSnapshot(
            p.getLong(K_T_START, 0), p.getLong(K_T_END, 0), p.getLong(K_T_TOTAL, 0), p.getLong(K_T_ANCHOR_AT, 0), p.getFloat(K_T_ANCHOR_V, 1f),
        ) to p.getInt(K_T_BOOT, Int.MIN_VALUE)
    }
}
