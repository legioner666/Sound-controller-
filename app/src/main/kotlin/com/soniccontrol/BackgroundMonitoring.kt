package com.soniccontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccontrol.core.background.DeviceMonitor
import com.soniccontrol.core.background.DeviceMonitorService
import com.soniccontrol.core.background.DeviceMonitorSource
import com.soniccontrol.core.background.MonitorSummary
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.devices.ActiveDevice
import com.soniccontrol.core.devices.HeadphoneController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Decides when the foreground "device monitoring" service runs: only while the user's setting is on AND a headset is active.
 * It is independent of the music player. If Android refuses to start it (permission, background-start limits) nothing is bypassed;
 * it is simply retried the next time the app is opened.
 */
class BackgroundMonitoring(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: SonicDatabase,
    private val controller: HeadphoneController,
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceMonitorSource {

    override val summary: Flow<MonitorSummary?> = combine(controller.active, minuteTicker()) { a, now -> a?.let { toSummary(it, now) } }

    private fun minuteTicker(): Flow<Long> = flow { while (true) { emit(clock()); delay(60_000) } }

    private fun toSummary(a: ActiveDevice, now: Long): MonitorSummary {
        val b = a.headphone.battery
        val newest = listOfNotNull(b?.timestampMillis, a.headphone.live.lastRxMillis).maxOrNull()
        return MonitorSummary(
            deviceName = a.headphone.displayName, connected = a.links.audio == com.soniccontrol.core.devices.AudioLinkState.CONNECTED ||
                (a.links.audio == com.soniccontrol.core.devices.AudioLinkState.UNKNOWN && (a.controlStatus == com.soniccontrol.core.devices.ControlStatus.CONNECTED || a.controlStatus == com.soniccontrol.core.devices.ControlStatus.DATA_ONLY)),
            left = b?.left?.percent, right = b?.right?.percent, case = b?.case?.percent, single = b?.single?.percent,
            leftCharging = b?.left?.charging == true, rightCharging = b?.right?.charging == true, caseCharging = b?.case?.charging == true,
            stale = a.controlStatus == com.soniccontrol.core.devices.ControlStatus.RECONNECTING || a.controlStatus == com.soniccontrol.core.devices.ControlStatus.UNAVAILABLE || (newest != null && now - newest > STALE_MILLIS), reconnect = a.headphone.live.reconnect,
        )
    }

    override fun onStopRequested() { scope.launch { db.setBackgroundMonitoring(false) } }

    private fun canStart(): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun start() {
        DeviceMonitor.source = this
        scope.launch {
            combine(db.settings.map { it.backgroundMonitoring }.distinctUntilChanged(), controller.active.map { it != null }.distinctUntilChanged()) { on, has -> on && has }
                .distinctUntilChanged()
                .collect { want -> if (want && canStart()) DeviceMonitorService.start(context) else DeviceMonitorService.stop(context) }
        }
    }

    private companion object { const val STALE_MILLIS = 10 * 60_000L }
}
