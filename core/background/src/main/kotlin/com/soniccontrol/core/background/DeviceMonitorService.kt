package com.soniccontrol.core.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Optional battery notification (TASK 012): a foreground service of type connectedDevice whose notification shows the
 * headset's battery. OFF by default; runs only while the user's "Battery notification" setting is on and a headset is
 * active (see [BatteryNotificationPolicy]). It adds no Bluetooth work of its own, never touches audio and is independent
 * of the playback service and the sleep timer.
 *
 * Android requires a visible notification for every foreground service. That rule is respected: when the notification is
 * not wanted the service simply does not run.
 */
class DeviceMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DeviceMonitor.source?.onStopRequested()
            return abort()
        }
        // Sleep timer ended, setting switched off meanwhile, or no source: never show the notification.
        val src = DeviceMonitor.source
        if (AppShutdown.isSuppressed(this) || src == null || !src.notificationAllowed) return abort()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(null), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
            running = true
        } catch (_: Exception) {
            // Not allowed to run in the foreground right now (permission or background-start limits). Never bypassed.
            stopSelf(); return START_NOT_STICKY
        }
        job?.cancel()
        job = scope.launch {
            src.summary.distinctUntilChanged().collect { s ->
                // Re-checked on every update: a switched-off setting never gets its notification re-posted.
                if (s == null || !src.notificationAllowed || AppShutdown.suppressed.value) stopNow()
                else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s))
            }
        }
        // Not sticky: after the system kills the process nothing restarts by itself; the app starts it again when opened.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    /**
     * A service started with startForegroundService must enter the foreground before it stops, or Android ends the app.
     * So it enters with a silent notification and leaves at once, removing it; if even that is refused it just stops.
     */
    private fun abort(): Int {
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, build(null), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        }
        stopNow()
        return START_NOT_STICKY
    }

    private fun stopNow() {
        job?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        running = false
        stopSelf()
    }

    private fun ensureChannel() {
        // Always (re)created: this updates the visible channel name after an app update, settings stay the user's.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
    }

    private fun build(s: MonitorSummary?): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE) }
        val stop = PendingIntent.getService(this, 1, Intent(this, DeviceMonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val labels = MonitorText.Labels(
            getString(R.string.monitor_left), getString(R.string.monitor_right), getString(R.string.monitor_case), getString(R.string.monitor_headset),
            getString(R.string.monitor_charging), getString(R.string.monitor_stale), getString(R.string.monitor_reconnecting), getString(R.string.monitor_unknown),
        )
        val title = s?.deviceName ?: getString(R.string.monitor_title)
        val text = s?.let { MonitorText.body(it, labels) }?.ifEmpty { null } ?: getString(R.string.monitor_waiting)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title).setContentText(text.lineSequence().first()).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.monitor_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "device_monitor"
        private const val NOTIFICATION_ID = 4001
        private const val ACTION_STOP = "com.soniccontrol.background.STOP"

        /** True while the service is in the foreground with its notification. */
        @Volatile var running: Boolean = false
            private set

        fun start(context: Context): Boolean = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, DeviceMonitorService::class.java)); true
        }.getOrDefault(false)

        fun stop(context: Context) { context.stopService(Intent(context, DeviceMonitorService::class.java)) }
    }
}
