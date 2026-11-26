package com.soniccontrol.core.background

import kotlinx.coroutines.flow.Flow

/** The app plugs its live device state in here; the service never reaches into UI or Activity code. */
interface DeviceMonitorSource {
    /** Null while no headset is active. */
    val summary: Flow<MonitorSummary?>
    /** Current answer of [BatteryNotificationPolicy] (setting on, headset active, not switched off by the sleep timer). */
    val notificationAllowed: Boolean
    /** The user pressed "Turn off" in the notification: switch the setting off. */
    fun onStopRequested()
}

object DeviceMonitor {
    @Volatile var source: DeviceMonitorSource? = null
}
