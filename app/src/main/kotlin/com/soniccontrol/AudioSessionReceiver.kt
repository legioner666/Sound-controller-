package com.soniccontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/**
 * Music players announce their audio sessions so equaliser apps can attach effects.
 * Only the session id is used; the player's package name is not stored.
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        if (session <= 0) return
        val eq = (context.applicationContext as? SonicApp)?.container?.systemEq ?: return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> eq.onSessionOpened(session)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> eq.onSessionClosed(session)
        }
    }
}
