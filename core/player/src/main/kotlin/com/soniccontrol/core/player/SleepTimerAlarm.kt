package com.soniccontrol.core.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.soniccontrol.core.background.AppShutdown

/**
 * Backstop for the sleep timer. While music plays the player's wake lock keeps the timer exact. While it is paused (screen
 * off, UI closed) the phone may sleep deeply or Android may stop the process; an inexact while-idle alarm on the monotonic
 * clock (ELAPSED_REALTIME_WAKEUP) wakes the CPU at the end:
 *  - service running → it checks the timer and, if the time is up, ends it;
 *  - process was stopped → the saved timer (SleepFadeStore) is checked here and, if the time is up, the app is switched off
 *    (AppShutdown: no automatic restart until opened by hand). Nothing is playing in that case, the queue was saved on pause.
 * No exact-alarm permission is needed or asked for; in Doze Android may deliver it some minutes late (system limit).
 */
internal object SleepTimerAlarm {
    /** Set by the running playback service; null when it is not running. */
    @Volatile var onAlarm: (() -> Unit)? = null

    private fun pending(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, 7101, Intent(context, SleepTimerAlarmReceiver::class.java), flags or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, atElapsedMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMillis, pi) }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    /** Alarm with no running service: decide from the saved timer. */
    fun onAlarmWithoutService(context: Context) {
        val (snap, boot) = SleepFadeStore.loadTimer(context) ?: return
        when (snap.check(SystemClock.elapsedRealtime(), SleepFadeStore.bootCount(context), boot)) {
            SleepTimerSnapshot.Verdict.EXPIRED -> { SleepFadeStore.saveTimer(context, null); AppShutdown.suppress(context) }
            SleepTimerSnapshot.Verdict.RESTORE -> schedule(context, snap.endsAt)   // early delivery: wait for the end
            SleepTimerSnapshot.Verdict.DISCARD -> SleepFadeStore.saveTimer(context, null)
        }
    }
}

class SleepTimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val hook = SleepTimerAlarm.onAlarm
        if (hook != null) hook() else SleepTimerAlarm.onAlarmWithoutService(context.applicationContext)
    }
}
