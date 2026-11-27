package com.soniccontrol.feature.automation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.soniccontrol.core.automation.ActionKind
import com.soniccontrol.core.automation.BatteryPart
import com.soniccontrol.core.automation.ConditionKind
import com.soniccontrol.core.automation.SkipReason
import com.soniccontrol.core.automation.TriggerKind
import com.soniccontrol.core.designsystem.R

@Composable
internal fun triggerLabel(k: TriggerKind): String = stringResource(
    when (k) {
        TriggerKind.DEVICE_CONNECTED -> R.string.trigger_connected
        TriggerKind.PLAYBACK_STARTED -> R.string.trigger_play_started
        TriggerKind.PLAYBACK_STOPPED -> R.string.trigger_play_stopped
        TriggerKind.BATTERY_LOW -> R.string.trigger_battery_low
        TriggerKind.SLEEP_TIMER_STARTED -> R.string.trigger_sleep_started
        TriggerKind.TIME_OF_DAY -> R.string.trigger_time
        TriggerKind.NOISE_MODE_CHANGED -> R.string.trigger_noise_changed
        TriggerKind.GESTURE -> R.string.trigger_gesture
    },
)

@Composable
internal fun actionKindLabel(k: ActionKind): String = stringResource(
    when (k) {
        ActionKind.SET_NOISE_MODE -> R.string.action_noise
        ActionKind.SET_PLAYER_VOLUME -> R.string.action_volume
        ActionKind.PAUSE_PLAYER -> R.string.action_pause
        ActionKind.RESUME_PLAYER -> R.string.action_resume
        ActionKind.APPLY_EQ_PROFILE -> R.string.action_eq
        ActionKind.START_SLEEP_TIMER -> R.string.action_sleep
    },
)

@Composable
internal fun conditionKindLabel(k: ConditionKind): String = stringResource(
    when (k) {
        ConditionKind.TIME_WINDOW -> R.string.cond_time_window_add
        ConditionKind.NOISE_MODE_IS -> R.string.cond_noise_any
        ConditionKind.PLAYER_PLAYING -> R.string.cond_playing
        ConditionKind.PLAYER_NOT_PLAYING -> R.string.cond_not_playing
    },
)

@Composable
internal fun partLabel(p: BatteryPart): String = stringResource(
    when (p) {
        BatteryPart.ANY_EARBUD -> R.string.part_any
        BatteryPart.LEFT -> R.string.side_left
        BatteryPart.RIGHT -> R.string.side_right
        BatteryPart.CASE -> R.string.part_case
    },
)

@Composable
internal fun dayLabel(isoDay: Int): String = stringResource(
    when (isoDay) { 1 -> R.string.day_mon; 2 -> R.string.day_tue; 3 -> R.string.day_wed; 4 -> R.string.day_thu; 5 -> R.string.day_fri; 6 -> R.string.day_sat; else -> R.string.day_sun },
)

@Composable
internal fun skipLabel(s: SkipReason): String = stringResource(
    when (s) {
        SkipReason.DISABLED -> R.string.skip_disabled
        SkipReason.CONDITION -> R.string.skip_condition
        SkipReason.STALE_DATA -> R.string.skip_stale
        SkipReason.DEVICE_MISMATCH -> R.string.skip_device
        SkipReason.DUPLICATE_EVENT -> R.string.skip_duplicate
        SkipReason.RATE_LIMIT -> R.string.skip_rate
        SkipReason.CIRCUIT_BREAKER -> R.string.skip_breaker
        SkipReason.CONFLICT -> R.string.skip_conflict
        SkipReason.MANUAL_OVERRIDE -> R.string.skip_manual
        SkipReason.ALREADY_IN_STATE -> R.string.skip_already
        SkipReason.DEVICE_DISCONNECTED -> R.string.skip_disconnected
        SkipReason.SUPERSEDED -> R.string.skip_superseded
        SkipReason.INVALID_RULE -> R.string.skip_invalid
    },
)
