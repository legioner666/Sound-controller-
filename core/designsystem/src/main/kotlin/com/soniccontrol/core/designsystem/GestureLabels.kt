package com.soniccontrol.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.soniccontrol.core.devices.DeviceSetting
import com.soniccontrol.core.devices.EarSide
import com.soniccontrol.core.devices.GestureAction
import com.soniccontrol.core.devices.GestureLayout
import com.soniccontrol.core.devices.TapType

@Composable
fun sideLabel(s: EarSide): String = stringResource(if (s == EarSide.LEFT) R.string.side_left else R.string.side_right)

@Composable
fun tapLabel(t: TapType): String = stringResource(
    when (t) { TapType.SINGLE -> R.string.tap_single; TapType.DOUBLE -> R.string.tap_double; TapType.TRIPLE -> R.string.tap_triple; TapType.HOLD -> R.string.tap_hold },
)

@Composable
fun layoutLabel(l: GestureLayout): String = stringResource(
    when (l) {
        GestureLayout.NONE -> R.string.layout_none
        GestureLayout.AMBIENT_SOUND -> R.string.layout_ambient
        GestureLayout.VOLUME -> R.string.layout_volume
        GestureLayout.PLAYBACK -> R.string.layout_playback
    },
)

@Composable
fun gestureActionLabel(a: GestureAction?): String = stringResource(
    when (a) {
        null -> R.string.gaction_unknown
        GestureAction.NONE -> R.string.gaction_none
        GestureAction.PLAY_PAUSE -> R.string.gaction_play_pause
        GestureAction.NEXT_TRACK -> R.string.gaction_next
        GestureAction.PREVIOUS_TRACK -> R.string.gaction_previous
        GestureAction.VOLUME_UP -> R.string.gaction_volume_up
        GestureAction.VOLUME_DOWN -> R.string.gaction_volume_down
        GestureAction.NOISE_CYCLE -> R.string.gaction_noise_cycle
        GestureAction.TALK_THRU -> R.string.gaction_talkthru
        GestureAction.VOICE_ASSISTANT -> R.string.gaction_assistant
    },
)

@Composable
fun settingLabel(s: DeviceSetting): String = stringResource(
    when (s) {
        DeviceSetting.ANC_LEVEL -> R.string.setting_anc_level
        DeviceSetting.AMBIENT_LEVEL -> R.string.setting_ambient_level
        DeviceSetting.EQ_PRESET -> R.string.setting_eq_preset
        DeviceSetting.SMART_AUDIO_VIDEO -> R.string.setting_smart_audio
        DeviceSetting.SPATIAL_SOUND -> R.string.setting_spatial
        DeviceSetting.VOICE_AWARE -> R.string.setting_voice_aware
        DeviceSetting.VOLUME_LIMITER -> R.string.setting_volume_limiter
        DeviceSetting.FAST_PAIR_ANC -> R.string.setting_fast_pair_anc
    },
)
