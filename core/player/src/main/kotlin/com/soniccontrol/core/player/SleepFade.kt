package com.soniccontrol.core.player

/** How the length of the fade is given (TASK 013). */
enum class FadeLengthMode { PERCENT, MINUTES }

/**
 * User settings of the sleep fade, kept across restarts (see [SleepFadeStore]). Defaults keep the pre-TASK 013 behaviour:
 * fade on, during the last 10 % of the time, down to 0 %.
 *
 * Volumes are the app player's own volume (0..100 %), never Android's system volume.
 */
data class SleepFadeSettings(
    val enabled: Boolean = true,
    val mode: FadeLengthMode = FadeLengthMode.PERCENT,
    /** Fade length as a share of the whole timer, [MIN_PERCENT]..[MAX_PERCENT]. */
    val percent: Int = DEFAULT_PERCENT,
    /** Fade length in minutes, [MIN_MINUTES]..[MAX_FADE_MINUTES]; never longer than the timer itself. */
    val minutes: Int = DEFAULT_MINUTES,
    /** Volume at the end, 0..100 % of the player scale; in effect never above the volume the fade starts from. */
    val targetPercent: Int = 0,
    /** Last timer length the user picked (for the preview graph before a timer runs). */
    val lastTimerMinutes: Int = DEFAULT_TIMER_MINUTES,
) {
    fun normalized(): SleepFadeSettings = copy(
        percent = percent.coerceIn(MIN_PERCENT, MAX_PERCENT),
        minutes = minutes.coerceIn(MIN_MINUTES, MAX_FADE_MINUTES),
        targetPercent = targetPercent.coerceIn(0, 100),
        lastTimerMinutes = lastTimerMinutes.coerceIn(1, SleepTimer.MAX_MINUTES),
    )

    /** Fade length for a timer of [totalMillis]; 0 when the fade is off. */
    fun fadeMillis(totalMillis: Long): Long {
        if (!enabled || totalMillis <= 0) return 0
        val n = normalized()
        return when (n.mode) {
            FadeLengthMode.PERCENT -> totalMillis * n.percent / 100
            FadeLengthMode.MINUTES -> (n.minutes * 60_000L).coerceAtMost(totalMillis)
        }
    }

    /** True when the minutes setting is longer than the timer and is therefore cut to the whole timer. */
    fun minutesCappedBy(totalMillis: Long): Boolean = enabled && mode == FadeLengthMode.MINUTES && normalized().minutes * 60_000L > totalMillis

    val target: Float get() = targetPercent.coerceIn(0, 100) / 100f

    companion object {
        const val MIN_PERCENT = 5
        const val MAX_PERCENT = 100
        const val DEFAULT_PERCENT = 10
        const val MIN_MINUTES = 1
        const val MAX_FADE_MINUTES = 120
        const val DEFAULT_MINUTES = 5
        const val DEFAULT_TIMER_MINUTES = 30

        internal const val K_ENABLED = "fade_enabled"
        internal const val K_MODE = "fade_mode"
        internal const val K_PERCENT = "fade_percent"
        internal const val K_MINUTES = "fade_minutes"
        internal const val K_TARGET = "fade_target_percent"
        internal const val K_LAST_TIMER = "last_timer_minutes"

        /** Pure decoding from stored values (missing or invalid → defaults). */
        fun decode(read: (String) -> Any?): SleepFadeSettings {
            val d = SleepFadeSettings()
            return SleepFadeSettings(
                enabled = read(K_ENABLED) as? Boolean ?: d.enabled,
                mode = (read(K_MODE) as? String)?.let { runCatching { FadeLengthMode.valueOf(it) }.getOrNull() } ?: d.mode,
                percent = read(K_PERCENT) as? Int ?: d.percent,
                minutes = read(K_MINUTES) as? Int ?: d.minutes,
                targetPercent = read(K_TARGET) as? Int ?: d.targetPercent,
                lastTimerMinutes = read(K_LAST_TIMER) as? Int ?: d.lastTimerMinutes,
            ).normalized()
        }

        fun encode(s: SleepFadeSettings): Map<String, Any> = s.normalized().let {
            mapOf(K_ENABLED to it.enabled, K_MODE to it.mode.name, K_PERCENT to it.percent, K_MINUTES to it.minutes, K_TARGET to it.targetPercent, K_LAST_TIMER to it.lastTimerMinutes)
        }
    }
}

/**
 * The volume plan of one running timer: hold [anchorVolume] until the ramp starts, then go in a straight line to the target
 * at [endsAt]. The ramp starts at the fade start or at [anchorAt], whichever is later, so re-anchoring (a manual volume change,
 * a settings change) continues from the current level: no jumps, and never upward on its own.
 */
data class FadeCurve(
    val startedAt: Long,
    val endsAt: Long,
    val anchorAt: Long,
    val anchorVolume: Float,
    val fadeStartAt: Long,
    val targetSetting: Float,
) {
    /** Effective end volume: from 0 up to the volume the fade starts from, never above it. */
    val target: Float get() = targetSetting.coerceIn(0f, anchorVolume.coerceIn(0f, 1f))
    val rampStartAt: Long get() = maxOf(anchorAt, fadeStartAt).coerceAtMost(endsAt)

    fun volumeAt(t: Long): Float {
        val v0 = anchorVolume.coerceIn(0f, 1f)
        val rs = rampStartAt
        if (rs >= endsAt || t <= rs) return v0          // fade off (or nothing left of it): hold the level
        if (t >= endsAt) return target
        val k = (t - rs).toFloat() / (endsAt - rs).toFloat()
        return (v0 + (target - v0) * k).coerceIn(0f, 1f)
    }

    /** Points for the graph, from the timer start to its end (time in ms since start, volume 0..1). */
    fun samples(count: Int = 48): List<Pair<Long, Float>> {
        val n = count.coerceAtLeast(2)
        val span = (endsAt - startedAt).coerceAtLeast(1)
        val pts = (0 until n).map { i -> startedAt + span * i / (n - 1) }.toMutableList()
        pts += listOf(rampStartAt, fadeStartAt).filter { it in startedAt..endsAt }
        return pts.distinct().sorted().map { t -> (t - startedAt) to plannedAt(t) }
    }

    /** Before the anchor the plan was the anchor level too (what the graph shows for the past). */
    private fun plannedAt(t: Long): Float = volumeAt(maxOf(t, anchorAt).coerceAtMost(endsAt))

    companion object {
        /** Plan for a timer that is not running yet (the preview graph). */
        fun preview(totalMillis: Long, volume: Float, settings: SleepFadeSettings): FadeCurve {
            val total = totalMillis.coerceAtLeast(1)
            return FadeCurve(0, total, 0, volume.coerceIn(0f, 1f), total - settings.fadeMillis(total), settings.target)
        }
    }
}

/** A running timer as saved to storage, in [android.os.SystemClock.elapsedRealtime] time of one boot. */
data class SleepTimerSnapshot(val startedAt: Long, val endsAt: Long, val totalMillis: Long, val anchorAt: Long, val anchorVolume: Float) {
    enum class Verdict { RESTORE, EXPIRED, DISCARD }

    /**
     * RESTORE: same boot and time left. EXPIRED: same boot, the end has passed while nothing ran. DISCARD: the phone was
     * restarted (boot count differs, or the monotonic clock is behind the saved start) or the data is not sane.
     */
    fun check(now: Long, bootCount: Int, savedBootCount: Int): Verdict = when {
        bootCount != savedBootCount || now < startedAt -> Verdict.DISCARD
        totalMillis <= 0 || endsAt != startedAt + totalMillis || anchorAt < startedAt || anchorAt > endsAt -> Verdict.DISCARD
        now >= endsAt -> Verdict.EXPIRED
        else -> Verdict.RESTORE
    }
}

/** Short smooth return to the user's volume when a timer is cancelled mid-fade (no sudden jump up). */
data class VolumeRestore(val from: Float, val to: Float, val startAt: Long, val durationMillis: Long = DEFAULT_MILLIS) {
    fun volumeAt(t: Long): Float {
        if (durationMillis <= 0 || t >= startAt + durationMillis) return to
        if (t <= startAt) return from
        return from + (to - from) * (t - startAt).toFloat() / durationMillis
    }
    fun isDone(t: Long): Boolean = t >= startAt + durationMillis

    companion object { const val DEFAULT_MILLIS = 1_500L }
}

/** Who may use the app's own session commands (sleep timer, Bass Boost): only Sonic Control itself. */
object ControllerAccess {
    fun isInternal(controllerUid: Int, controllerPackage: String, appUid: Int, appPackage: String): Boolean =
        controllerUid == appUid && controllerPackage == appPackage
}
