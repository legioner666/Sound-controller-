package com.soniccontrol.core.automation

import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.NoiseMode
import com.soniccontrol.core.devices.TapType

/** "If" part of a rule. Every trigger is an edge (something happened), never a level that is re-checked over and over. */
enum class TriggerKind { DEVICE_CONNECTED, PLAYBACK_STARTED, PLAYBACK_STOPPED, BATTERY_LOW, SLEEP_TIMER_STARTED, TIME_OF_DAY, NOISE_MODE_CHANGED, GESTURE }

enum class BatteryPart { ANY_EARBUD, LEFT, RIGHT, CASE }

data class Trigger(
    val kind: TriggerKind,
    /** NOISE_MODE_CHANGED: the new mode (null = any change). */
    val noiseMode: NoiseMode? = null,
    /** BATTERY_LOW. */
    val part: BatteryPart = BatteryPart.ANY_EARBUD,
    val thresholdPercent: Int = 20,
    /** TIME_OF_DAY: minutes after local midnight, and ISO days 1 (Mon)..7 (Sun); empty = every day. */
    val minuteOfDay: Int = 0,
    val days: Set<Int> = emptySet(),
    /** GESTURE: only offered when the connected model reports touch events itself. */
    val side: EarSide? = null,
    val tap: TapType? = null,
)

enum class ConditionKind { TIME_WINDOW, NOISE_MODE_IS, PLAYER_PLAYING, PLAYER_NOT_PLAYING }

/** Extra "and only if" checks, evaluated both when the rule fires and again right before a delayed action runs. */
data class Condition(val kind: ConditionKind, val fromMinute: Int = 0, val toMinute: Int = 0, val noiseMode: NoiseMode? = null)

enum class ActionKind { SET_NOISE_MODE, SET_PLAYER_VOLUME, PAUSE_PLAYER, RESUME_PLAYER, APPLY_EQ_PROFILE, START_SLEEP_TIMER }

/** What an action changes; two rules acting on the same target in the same moment are a conflict resolved by priority. */
enum class ActionTarget { NOISE, PLAYER_VOLUME, PLAYBACK, EQ, SLEEP_TIMER }

data class Action(
    val kind: ActionKind,
    val noiseMode: NoiseMode? = null,
    val volumePercent: Int? = null,
    val eqProfileId: String? = null,
    val minutes: Int? = null,
) {
    val target: ActionTarget get() = when (kind) {
        ActionKind.SET_NOISE_MODE -> ActionTarget.NOISE
        ActionKind.SET_PLAYER_VOLUME -> ActionTarget.PLAYER_VOLUME
        ActionKind.PAUSE_PLAYER, ActionKind.RESUME_PLAYER -> ActionTarget.PLAYBACK
        ActionKind.APPLY_EQ_PROFILE -> ActionTarget.EQ
        ActionKind.START_SLEEP_TIMER -> ActionTarget.SLEEP_TIMER
    }

    /** A complete action (every parameter it needs, in range). Incomplete actions are never run. */
    val valid: Boolean get() = when (kind) {
        ActionKind.SET_NOISE_MODE -> noiseMode != null
        ActionKind.SET_PLAYER_VOLUME -> volumePercent != null && volumePercent in 0..100
        ActionKind.APPLY_EQ_PROFILE -> !eqProfileId.isNullOrBlank()
        ActionKind.START_SLEEP_TIMER -> minutes != null && minutes in 1..720
        ActionKind.PAUSE_PLAYER, ActionKind.RESUME_PLAYER -> true
    }
}

/**
 * One "If -> then" rule. [priority]: higher wins a conflict; equal priority -> the older rule ([createdAt], then [id]).
 * [deviceAddress] binds the rule to one headset (null = any).
 */
data class Rule(
    val id: String,
    val name: String,
    val trigger: Trigger,
    val actions: List<Action>,
    val conditions: List<Condition> = emptyList(),
    val enabled: Boolean = true,
    val priority: Int = 0,
    val delaySeconds: Int = 0,
    val deviceAddress: String? = null,
    val createdAt: Long = 0,
) {
    val valid: Boolean get() = name.isNotBlank() && actions.isNotEmpty() && actions.all { it.valid } && delaySeconds in 0..MAX_DELAY_SECONDS &&
        (trigger.kind != TriggerKind.BATTERY_LOW || trigger.thresholdPercent in 5..95) &&
        (trigger.kind != TriggerKind.TIME_OF_DAY || (trigger.minuteOfDay in 0 until 1440 && trigger.days.all { it in 1..7 }))

    companion object { const val MAX_DELAY_SECONDS = 3600 }
}
