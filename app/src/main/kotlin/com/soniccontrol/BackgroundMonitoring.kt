package com.soniccontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.soniccontrol.core.background.AppShutdown
import com.soniccontrol.core.background.BatteryNotificationPolicy
import com.soniccontrol.core.background.DeviceMonitor
import com.soniccontrol.core.background.DeviceMonitorService
import com.soniccontrol.core.background.DeviceMonitorSource
import com.soniccontrol.core.background.MonitorSummary
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.devices.ActiveDevice
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.currentCharging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Decides when the optional battery notification (foreground service of type connectedDevice) runs: only while the user's
 * "Battery notification" setting is on (default OFF, TASK 012), a headset is active and the sleep timer has not switched the
 * app off. Setting changes apply at once. With the setting off a reconnecting headset never brings the notification back.
 * If Android refuses the service or notifications are not allowed nothing is bypassed; it is retried when the app is opened.
 * Independent of the music player and of the adaptive refresh (which keep working with the notification off).
 */
class BackgroundMonitoring(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: SonicDatabase,
    private val controller: HeadphoneController,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceMonitorSource {

    override val summary: Flow<MonitorSummary?> = combine(controller.active, minuteTicker()) { a, now -> a?.let { toSummary(it, now) } }

    override val notificationAllowed: Boolean
        get() = BatteryNotificationPolicy.shouldRun(
            enabled = db.settings.value.batteryNotification, hasDevice = controller.active.value != null,
            suppressed = AppShutdown.suppressed.value, permitted = permitted(),
        )

    /** Bumped when the app comes to the foreground, so a refused start or a revoked permission is re-evaluated. */
    private val recheck = MutableStateFlow(0)

    private fun minuteTicker(): Flow<Long> = flow { while (true) { emit(clock()); delay(60_000) } }

    private fun toSummary(a: ActiveDevice, now: Long): MonitorSummary {
        val b = a.headphone.battery
        val newest = listOfNotNull(b?.timestampMillis, a.headphone.live.lastRxMillis).maxOrNull()
        return MonitorSummary(
            deviceName = a.headphone.displayName, connected = a.links.audio == com.soniccontrol.core.devices.AudioLinkState.CONNECTED ||
                (a.links.audio == com.soniccontrol.core.devices.AudioLinkState.UNKNOWN && (a.controlStatus == com.soniccontrol.core.devices.ControlStatus.CONNECTED || a.telemetryStatus(now) == com.soniccontrol.core.devices.TelemetryStatus.LIVE)),
            left = b?.left?.percent, right = b?.right?.percent, case = b?.case?.percent, single = b?.single?.percent,
            // Charging only while its report is current (TASK 010): an old charging=true is never shown as "charging now".
            leftCharging = b?.left?.currentCharging(now) == true, rightCharging = b?.right?.currentCharging(now) == true, caseCharging = b?.case?.currentCharging(now) == true,
            // Stale whenever telemetry is not live, regardless of the control channel (the main screen uses the same rule).
            stale = a.telemetryStatus(now) != com.soniccontrol.core.devices.TelemetryStatus.LIVE || (newest != null && now - newest > STALE_MILLIS), reconnect = a.headphone.live.reconnect,
        )
    }

    override fun onStopRequested() { scope.launch { db.setBatteryNotification(false) } }

    /** Android's own limits: Bluetooth permission (12+) and notifications allowed for the app (13+ runtime permission). */
    private fun permitted(): Boolean {
        val bt = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return bt && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** MainActivity onStart. */
    fun onAppForeground() { recheck.value++ }

    fun start() {
        DeviceMonitor.source = this
        scope.launch {
            val desired = BatteryNotificationPolicy.desired(
                enabled = db.settings.map { it.batteryNotification },
                hasDevice = controller.active.map { it != null },
                suppressed = AppShutdown.suppressed,
            )
            combine(desired, recheck) { want, _ -> want }.collect { want ->
                if (want && permitted()) { if (!DeviceMonitorService.running) DeviceMonitorService.start(context) }
                else if (DeviceMonitorService.running) DeviceMonitorService.stop(context)
            }
        }
    }

    private companion object { const val STALE_MILLIS = 10 * 60_000L }
}
