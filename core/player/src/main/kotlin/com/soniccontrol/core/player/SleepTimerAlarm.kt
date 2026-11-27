package com.soniccontrol.core.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Backstop for the sleep timer with the screen off. While music plays the player's wake lock keeps the timer exact.
 * While it is paused the phone may sleep deeply and a Handler delay stops counting, so an inexact while-idle alarm wakes the
 * CPU at the end and the running service checks the timer by the wall clock. No exact-alarm permission is needed or asked
 * for; in Doze Android may deliver it some minutes late, which is the system's limit and is not worked around.
 */
internal object SleepTimerAlarm {
    /** Set by the running playback service; null when it is not running (then there is no timer to end). */
    @Volatile var onAlarm: (() -> Unit)? = null

    private fun pending(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 7101, Intent(context, SleepTimerAlarmReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, atWallMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atWallMillis, pi) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }
}

class SleepTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { SleepTimerAlarm.onAlarm?.invoke() }
}
