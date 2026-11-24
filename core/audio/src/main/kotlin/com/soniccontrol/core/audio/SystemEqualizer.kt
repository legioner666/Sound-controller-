package com.soniccontrol.core.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Software equaliser on the phone (not in the headphones), using Android's public DynamicsProcessing effect.
 *
 * Where it applies:
 * - audio sessions that players announce with ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION (most music apps);
 * - the global output mix (session 0) where the phone still allows it — deprecated by Android, so often refused.
 *
 * Safety: the input gain is lowered by the largest positive band gain and a limiter at -1 dBFS follows,
 * so enabling the equaliser never makes playback louder than without it.
 */
class SystemEqualizer {

    data class Status(
        val enabled: Boolean = false,
        val sessions: Int = 0,
        val globalActive: Boolean = false,
        val failures: Int = 0,
    ) {
        val active: Boolean get() = enabled && (sessions > 0 || globalActive)
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val effects = HashMap<Int, DynamicsProcessing>()
    private var gains: List<Float> = List(BANDS_HZ.size) { 0f }
    private var balance: Float = 0f

    @Synchronized
    fun setEnabled(on: Boolean) {
        _status.value = _status.value.copy(enabled = on)
        if (on) {
            attach(GLOBAL_SESSION)
            effects.keys.toList().forEach { apply(it) }
        } else {
            effects.values.forEach { runCatching { it.enabled = false } }
        }
        publish()
    }

    /** Gains in dB for [BANDS_HZ], clamped to ±12 dB. */
    @Synchronized
    fun setGains(db: List<Float>) {
        require(db.size == BANDS_HZ.size)
        gains = db.map { it.coerceIn(-12f, 12f) }
        effects.keys.toList().forEach { apply(it) }
    }

    /** -1 = left only … +1 = right only. Implemented by attenuating the opposite channel; never boosts. */
    @Synchronized
    fun setBalance(value: Float) {
        balance = value.coerceIn(-1f, 1f)
        effects.keys.toList().forEach { apply(it) }
    }

    @Synchronized
    fun onSessionOpened(sessionId: Int) {
        if (sessionId == GLOBAL_SESSION || sessionId == AudioEffect.ERROR_BAD_VALUE) return
        attach(sessionId)
        publish()
    }

    @Synchronized
    fun onSessionClosed(sessionId: Int) {
        effects.remove(sessionId)?.let { runCatching { it.release() } }
        publish()
    }

    @Synchronized
    fun releaseAll() {
        effects.values.forEach { runCatching { it.release() } }
        effects.clear()
        publish()
    }

    private fun attach(sessionId: Int) {
        if (effects.containsKey(sessionId)) return
        val dp = try {
            DynamicsProcessing(0, sessionId, CONFIG)
        } catch (e: RuntimeException) {
            // UnsupportedOperationException / IllegalStateException: the phone refused this session.
            _status.value = _status.value.copy(failures = _status.value.failures + 1)
            return
        }
        effects[sessionId] = dp
        apply(sessionId)
    }

    private fun apply(sessionId: Int) {
        val dp = effects[sessionId] ?: return
        try {
            val headroom = EqMath.headroomDb(gains)
            for (ch in 0 until CHANNELS) {
                dp.setInputGainbyChannel(ch, -headroom + EqMath.balanceAttenuationDb(balance, ch))
            }
            BANDS_HZ.indices.forEach { i ->
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, EqMath.cutoffHz(i), gains[i]))
            }
            dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
            dp.enabled = _status.value.enabled
        } catch (e: RuntimeException) {
            effects.remove(sessionId)?.let { runCatching { it.release() } }
            _status.value = _status.value.copy(failures = _status.value.failures + 1)
        }
    }

    private fun publish() {
        _status.value = _status.value.copy(
            sessions = effects.keys.count { it != GLOBAL_SESSION },
            globalActive = effects.containsKey(GLOBAL_SESSION),
        )
    }

    companion object {
        const val GLOBAL_SESSION = 0
        private const val CHANNELS = 2
        val BANDS_HZ = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

        private val CONFIG: DynamicsProcessing.Config by lazy {
            DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                true, BANDS_HZ.size, false, 0, false, 0, true,
            ).build()
        }
    }
}

/** Pure maths of the equaliser, unit-tested on the JVM. */
object EqMath {
    /** Upper edge of band [i]: geometric midpoint to the next centre; the last band ends at 20 kHz. */
    fun cutoffHz(i: Int): Float {
        val b = SystemEqualizer.BANDS_HZ
        return if (i == b.lastIndex) 20_000f else sqrt(b[i].toFloat() * b[i + 1].toFloat())
    }

    /** Attenuation needed so that no band ends above 0 dB. */
    fun headroomDb(gains: List<Float>): Float = (gains.maxOrNull() ?: 0f).coerceAtLeast(0f)

    /** 0 or negative dB for [channel] (0 = left, 1 = right); max 24 dB cut at full balance. */
    fun balanceAttenuationDb(balance: Float, channel: Int): Float = when {
        channel == 0 && balance > 0f -> -24f * balance
        channel == 1 && balance < 0f -> 24f * balance
        else -> 0f
    }
}
