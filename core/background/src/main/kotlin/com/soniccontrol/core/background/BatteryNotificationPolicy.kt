package com.soniccontrol.core.background

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * When the optional battery notification (the `connectedDevice` foreground service) may exist. Owner decision (TASK 012):
 * the notification is OFF by default; battery, charging and earbud activity are shown on the app's main screen instead.
 *
 * A foreground service on Android must show a notification, so "notification off" means "service not running". Nothing
 * here or in the service hides, delays or works around that system rule.
 */
object BatteryNotificationPolicy {
    /** Default for a new install and for an install that never stored a choice. */
    const val DEFAULT_ENABLED = false

    /**
     * @param enabled the user's setting.
     * @param hasDevice a headset is active (connected or being recovered).
     * @param suppressed the sleep timer switched the app off (until it is opened by hand).
     * @param permitted Android allows it right now (Bluetooth permission, notifications allowed for the app).
     */
    fun shouldRun(enabled: Boolean, hasDevice: Boolean, suppressed: Boolean, permitted: Boolean): Boolean =
        enabled && hasDevice && !suppressed && permitted

    /**
     * Desired state from live inputs, without the permission check (that one is read when the value is applied).
     * A headset that reconnects while the setting is off never produces `true`; a setting change is emitted at once.
     */
    fun desired(enabled: Flow<Boolean>, hasDevice: Flow<Boolean>, suppressed: Flow<Boolean>): Flow<Boolean> =
        combine(enabled.distinctUntilChanged(), hasDevice.distinctUntilChanged(), suppressed.distinctUntilChanged()) { on, has, off ->
            shouldRun(on, has, off, permitted = true)
        }.distinctUntilChanged()
}
