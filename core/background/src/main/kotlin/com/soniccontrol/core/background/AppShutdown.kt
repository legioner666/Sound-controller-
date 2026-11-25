package com.soniccontrol.core.background

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Sonic Control is off until the user opens it again" — set when the sleep timer ends.
 * While [suppressed] is true nothing may start a service, a Bluetooth session or a notification. The flag is persisted
 * (a restarted process must not bring services back) and is cleared only by a manual open of the app UI.
 */
object AppShutdown {
    private const val PREFS = "sonic_shutdown"
    private const val KEY = "suppressed"

    private val _suppressed = MutableStateFlow(false)
    val suppressed: StateFlow<Boolean> = _suppressed.asStateFlow()

    private val _closeUi = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when the open UI must close (finishAndRemoveTask). */
    val closeUi: SharedFlow<Unit> = _closeUi.asSharedFlow()

    private var initialised = false

    /** Loads the persisted flag; call once from Application.onCreate. */
    @Synchronized fun init(context: Context) {
        if (initialised) return
        initialised = true
        _suppressed.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    /** Persisted synchronously (commit): the process may die right after. */
    fun suppress(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).commit()
        _suppressed.value = true
        _closeUi.tryEmit(Unit)
    }

    /** Manual open of the app. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, false).commit()
        _suppressed.value = false
    }

    fun isSuppressed(context: Context): Boolean = init(context).let { _suppressed.value }
}
