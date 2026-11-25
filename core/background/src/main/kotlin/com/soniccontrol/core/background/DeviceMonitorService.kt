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
 * Foreground service of type connectedDevice. It adds no Bluetooth work of its own: it keeps the process (and so the
 * device sessions, recovery and notifications that already run there) alive and shows the current battery state.
 * Started only by the app while the user's "background monitoring" setting is on; stoppable from its notification.
 * It never touches audio and is independent of the playback service.
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
            stopSelf()
            return START_NOT_STICKY
        }
        val first = build(null)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, first, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        } catch (_: Exception) {
            // Not allowed to run in the foreground right now (permission or background-start limits). Never bypassed.
            stopSelf(); return START_NOT_STICKY
        }
        job?.cancel()
        job = scope.launch {
            val src = DeviceMonitor.source
            if (src == null) { stopSelf(); return@launch }
            src.summary.distinctUntilChanged().collect { s ->
                if (s == null) stopSelf() else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s))
            }
        }
        // Not sticky: after the system kills the process nothing restarts by itself; the app starts it again when opened.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.monitor_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }
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

        fun start(context: Context): Boolean = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, DeviceMonitorService::class.java)); true
        }.getOrDefault(false)

        fun stop(context: Context) { context.stopService(Intent(context, DeviceMonitorService::class.java)) }
    }
}
