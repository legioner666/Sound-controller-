package com.soniccontrol.core.player

/**
 * The one sleep timer of the app (the playback service owns it; this class keeps the rules testable).
 *  - one timer at a time, a new request replaces the old one, a non-positive duration cancels;
 *  - optional fade of the PLAYER's volume (TASK 013): starts from the player's actual volume, length as a share of the time
 *    (5..100 %) or in minutes, down to a target between 0 and that volume; Android's system volume is never touched;
 *  - settings may change while the timer runs, and a manual volume change during the fade re-plans the rest from the new
 *    level — both without jumps (see [FadeCurve]);
 *  - the end is the end whatever the target volume: [consumeIfExpired] fires once and the service shuts everything down;
 *  - [clock] is monotonic (SystemClock.elapsedRealtime in the app: counts deep sleep, ignores wall-clock changes);
 *  - a running timer can be saved as a [SleepTimerSnapshot] and restored after the process was stopped.
 */
class SleepTimer(private val clock: () -> Long, settings: SleepFadeSettings = SleepFadeSettings(), userVolume: Float = 1f) {
    private var startedAt = 0L
    private var endsAt: Long? = null
    private var totalMillis = 0L
    private var anchorAt = 0L
    private var anchorVolume = 1f

    var settings: SleepFadeSettings = settings.normalized()
        private set

    /** The volume the user chose for the player (0..1). The fade only ever lowers from it; it is what is saved. */
    var userVolume: Float = userVolume.coerceIn(0f, 1f)
        private set

    val isRunning: Boolean get() = endsAt != null

    /**
     * Starts or re-starts the timer; returns the delay to the end, or null when [minutes] <= 0 cancelled it.
     * The plan starts from [currentLevel], the volume the player has right now: a new duration for a running (maybe already
     * fading) timer never jumps back up to the starting volume. Without a timer that is simply the user's volume.
     */
    fun set(minutes: Int, currentLevel: Float = volume()): Long? {
        if (minutes <= 0) { cancel(); return null }
        val d = minutes.coerceAtMost(MAX_MINUTES) * 60_000L
        val now = clock()
        startedAt = now; totalMillis = d; endsAt = now + d
        anchorAt = now; anchorVolume = currentLevel.coerceIn(0f, userVolume)
        return d
    }

    /** The running timer for saving, or null. */
    fun snapshot(): SleepTimerSnapshot? = endsAt?.let { SleepTimerSnapshot(startedAt, it, totalMillis, anchorAt, anchorVolume) }

    /** Puts a saved timer back (the caller checked it with [SleepTimerSnapshot.check]). */
    fun restore(s: SleepTimerSnapshot) {
        startedAt = s.startedAt; endsAt = s.endsAt; totalMillis = s.totalMillis; anchorAt = s.anchorAt; anchorVolume = s.anchorVolume.coerceIn(0f, 1f)
    }

    fun cancel() { endsAt = null; totalMillis = 0 }

    /** New fade settings, applied at once: the plan continues from the current level (no jump). */
    fun updateSettings(s: SleepFadeSettings) {
        val n = s.normalized()
        if (endsAt != null) { val now = clock(); anchorVolume = volume(); anchorAt = now }
        settings = n
    }

    /** The user moved the player's volume (in the app or from any media controller). The rest of the fade starts from it. */
    fun onManualVolume(v: Float) {
        userVolume = v.coerceIn(0f, 1f)
        if (endsAt != null) { anchorAt = clock(); anchorVolume = userVolume }
    }

    /** Current plan, or null with no timer. */
    fun curve(): FadeCurve? = endsAt?.let { e -> FadeCurve(startedAt, e, anchorAt, anchorVolume, e - settings.fadeMillis(totalMillis), settings.target) }

    /** Player volume to apply now: the user's volume without a timer, otherwise the plan's value. */
    fun volume(): Float = curve()?.volumeAt(clock()) ?: userVolume

    /** Gain relative to the user's volume (1.0 = not faded), kept for callers that think in factors. */
    fun fadeFactor(): Float = if (userVolume <= 0f) 1f else (volume() / userVolume).coerceIn(0f, 1f)

    /** Delay until the volume starts to go down (0 while it is going down), or null with no timer. */
    fun fadeStartDelayMillis(): Long? = curve()?.let { (it.rampStartAt - clock()).coerceAtLeast(0) }

    /** Milliseconds left, or null when no timer runs. Never negative. */
    fun remainingMillis(): Long? = endsAt?.let { (it - clock()).coerceAtLeast(0) }

    fun totalMillis(): Long = totalMillis

    /** True exactly once when the time is up, then the timer is cleared. */
    fun consumeIfExpired(): Boolean {
        val e = endsAt ?: return false
        if (clock() < e) return false
        endsAt = null
        return true
    }

    companion object {
        const val MAX_MINUTES = 12 * 60

        /** Minutes typed by hand: digits only, 1..[MAX_MINUTES]; anything else is null (refused, nothing starts). */
        fun parseMinutes(text: String): Int? {
            val t = text.trim()
            if (t.isEmpty() || t.length > 4 || !t.all { it in '0'..'9' }) return null
            return t.toInt().takeIf { it in 1..MAX_MINUTES }
        }
        /** Default fade: the last 10 % of the time (unchanged since before TASK 013). */
        const val FADE_FRACTION = SleepFadeSettings.DEFAULT_PERCENT / 100.0
    }
}
