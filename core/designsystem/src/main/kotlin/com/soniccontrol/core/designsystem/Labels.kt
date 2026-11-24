package com.soniccontrol.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.soniccontrol.core.devices.AudioLinkState
import com.soniccontrol.core.devices.CatalogNote
import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.CommandOutcome
import com.soniccontrol.core.devices.Notice
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
    ConnectionState.Starting -> stringResource(R.string.conn_starting)
    ConnectionState.Active -> stringResource(R.string.conn_active)
    is ConnectionState.Reconnecting -> stringResource(R.string.conn_reconnecting, c.attempt)
    is ConnectionState.Failed -> stringResource(R.string.conn_failed)
}

/** Honest wording for command results: only a confirmed reply is reported as done. */
@Composable
fun outcomeLabel(o: CommandOutcome): String = when (o) {
    is CommandOutcome.Confirmed -> stringResource(R.string.outcome_confirmed)
    CommandOutcome.SentUnconfirmed -> stringResource(R.string.outcome_unconfirmed)
    is CommandOutcome.Rejected -> stringResource(R.string.outcome_rejected)
    CommandOutcome.Timeout -> stringResource(R.string.outcome_timeout)
    CommandOutcome.NotConnected -> stringResource(R.string.outcome_not_connected)
    is CommandOutcome.Unsupported -> stringResource(R.string.outcome_unsupported)
    is CommandOutcome.Failed -> stringResource(R.string.outcome_failed)
}

@Composable
fun channelLabel(c: ChannelState): String = stringResource(
    when (c) {
        ChannelState.UNAVAILABLE -> R.string.ch_unavailable
        ChannelState.OPENING -> R.string.ch_opening
        ChannelState.OPEN -> R.string.ch_open
        ChannelState.CONFIRMED -> R.string.ch_confirmed
        ChannelState.FAILED -> R.string.ch_failed
        ChannelState.CLOSED -> R.string.ch_closed
    },
)

@Composable
fun audioLabel(a: AudioLinkState): String = stringResource(
    when (a) {
        AudioLinkState.UNKNOWN -> R.string.audio_unknown
        AudioLinkState.CONNECTED -> R.string.audio_connected
        AudioLinkState.DISCONNECTED -> R.string.audio_disconnected
    },
)

@Composable
fun noticeLabel(n: Notice): String = stringResource(
    when (n) {
        Notice.CLOSE_VENDOR_APP -> R.string.notice_close_vendor_app
        Notice.NO_CONTROL_CHANNEL -> R.string.notice_no_control_channel
        Notice.PROTOCOL_UNIDENTIFIED -> R.string.notice_protocol_unidentified
        Notice.VENDOR_NOT_IMPLEMENTED -> R.string.notice_vendor_not_implemented
        Notice.STANDARD_BATTERY_ONLY -> R.string.notice_standard_battery_only
        Notice.EXPERIMENTAL_OFF -> R.string.notice_experimental_off
    },
)

@Composable
fun catalogNoteLabel(n: CatalogNote): String = stringResource(
    when (n) {
        CatalogNote.PRIORITY_TEST_DEVICE -> R.string.catalog_note_priority
        CatalogNote.CONFIRMED_ON_HARDWARE -> R.string.catalog_note_confirmed
        CatalogNote.PLANNED_DRIVER -> R.string.catalog_note_planned
        CatalogNote.TEN_PERCENT_STEPS_NO_CONTROL -> R.string.catalog_note_apple
    },
)
