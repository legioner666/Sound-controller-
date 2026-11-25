package com.soniccontrol.core.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** Custom commands the app sends to the playback service (same process, but the session is the only door). */
object PlayerCommands {
    val SLEEP_TIMER = SessionCommand("com.soniccontrol.player.SLEEP_TIMER", Bundle.EMPTY)
    val BASS_BOOST = SessionCommand("com.soniccontrol.player.BASS_BOOST", Bundle.EMPTY)
    const val ARG_MINUTES = "minutes"
    const val ARG_STRENGTH = "strength"
    const val EXTRA_SLEEP_REMAINING = "sleepRemainingMillis"
    const val EXTRA_BASS_SUPPORTED = "bassSupported"
    const val EXTRA_BASS_STRENGTH = "bassStrength"
}
