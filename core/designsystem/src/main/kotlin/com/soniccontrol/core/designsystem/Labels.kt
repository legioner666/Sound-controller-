package com.soniccontrol.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.ConnectionState
import com.soniccontrol.core.devices.Feature
import com.soniccontrol.core.devices.NoiseMode

@Composable
fun noiseLabel(mode: NoiseMode): String = stringResource(
    when (mode) {
        NoiseMode.OFF -> R.string.noise_off
        NoiseMode.ANC -> R.string.noise_anc
        NoiseMode.AMBIENT -> R.string.noise_ambient
        NoiseMode.TALK_THRU -> R.string.noise_talkthru
    },
)

@Composable
fun featureLabel(f: Feature): String = stringResource(
    when (f) {
        Feature.CONNECTION_STATUS -> R.string.feature_connection
        Feature.SYSTEM_VOLUME -> R.string.feature_volume
        Feature.MEDIA_CONTROL -> R.string.feature_media
        Feature.BATTERY -> R.string.feature_battery
        Feature.BATTERY_PER_COMPONENT -> R.string.feature_battery_components
        Feature.NOISE_CONTROL -> R.string.feature_noise
        Feature.TALK_THRU -> R.string.feature_talkthru
        Feature.DEVICE_EQUALIZER -> R.string.feature_device_eq
        Feature.TOUCH_GESTURES -> R.string.feature_gestures
        Feature.SPATIAL_AUDIO -> R.string.feature_spatial
        Feature.FIND_DEVICE -> R.string.feature_find
        Feature.MULTIPOINT -> R.string.feature_multipoint
        Feature.WEAR_DETECTION -> R.string.feature_wear
    },
)

@Composable
fun connectionLabel(c: ConnectionState): String = when (c) {
    ConnectionState.Idle -> stringResource(R.string.conn_idle)
    ConnectionState.Connecting -> stringResource(R.string.conn_connecting)
    ConnectionState.Connected -> stringResource(R.string.conn_connected)
    is ConnectionState.Reconnecting -> stringResource(R.string.conn_reconnecting, c.attempt)
    is ConnectionState.Failed -> stringResource(R.string.conn_failed, c.reason)
}

/** Honest wording for command results: only a confirmed reply is reported as done. */
@Composable
fun outcomeLabel(o: CommandOutcome): String = when (o) {
    is CommandOutcome.Confirmed -> stringResource(R.string.outcome_confirmed)
    CommandOutcome.SentUnconfirmed -> stringResource(R.string.outcome_unconfirmed)
    is CommandOutcome.Rejected -> stringResource(R.string.outcome_rejected, o.reason)
    CommandOutcome.Timeout -> stringResource(R.string.outcome_timeout)
    CommandOutcome.NotConnected -> stringResource(R.string.outcome_not_connected)
    is CommandOutcome.Unsupported -> stringResource(R.string.outcome_unsupported, o.reason)
    is CommandOutcome.Failed -> stringResource(R.string.outcome_failed, o.message)
}
