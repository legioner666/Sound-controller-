package com.soniccontrol.core.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * The one and only refresh loop of the process (owner decision, TASK 011). It decides WHEN the headset state is refreshed;
 * the session decides HOW (notifications first, then a read over the open GATT link, then the open Fast Pair stream, and a
 * new RFCOMM socket only when needed and within [ConnectionCoordinator]'s socket budget).
 *
 * | Mode | Interval |
 * |---|---|
 * | main screen open | [Settings.activeSeconds] (default 5 s) |
 * | another section open | 2 × active (default 10 s) |
 * | app in background | [Settings.backgroundSeconds] (default 60 s) |
 * | screen locked | [Settings.backgroundSeconds] (default 60 s) |
 * | no headset session | stopped |
 * | sleep timer ended ([shutdown]) | stopped until [resume] |
 *
 * Returning to the foreground and [requestNow] (a Bluetooth notification, the user pulling to refresh) refresh at once and
 * restart the interval, so a timed refresh never follows right behind an immediate one. Refreshes run one after another in
 * this single loop: two can never overlap, and immediate requests arriving meanwhile collapse into one.
 */
class AdaptiveRefreshCoordinator(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    enum class Mode { MAIN_SCREEN, SECTION, BACKGROUND, LOCKED }

    data class Settings(val activeSeconds: Int = DEFAULT_ACTIVE_SECONDS, val backgroundSeconds: Int = DEFAULT_BACKGROUND_SECONDS) {
        init {
            require(activeSeconds in ACTIVE_CHOICES) { "active interval $activeSeconds s is not one of $ACTIVE_CHOICES" }
            require(backgroundSeconds in BACKGROUND_CHOICES) { "background interval $backgroundSeconds s is not one of $BACKGROUND_CHOICES" }
        }
    }

    data class Status(
        val mode: Mode,
        /** Null while stopped (no headset, or after the sleep timer). */
        val intervalMillis: Long?,
        val attached: Boolean,
        val shutdown: Boolean,
        val ticks: Long,
        val lastTickMillis: Long?,
        val lastReason: String?,
    )

    private data class Config(val mode: Mode, val settings: Settings, val target: (suspend (String) -> Unit)?, val shutdown: Boolean)

    private val config = MutableStateFlow(Config(Mode.BACKGROUND, Settings(), null, false))
    private val immediate = Channel<String>(Channel.CONFLATED)
    /** Signals any configuration change to the waiting loop; conflated, so it never grows. */
    private val reschedule = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var ticks = 0L
    private var lastTickMillis: Long? = null
    private var lastReason: String? = null

    private val _status = MutableStateFlow(statusOf(config.value))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** True while the single loop runs (for tests and diagnostics). */
    val running: Boolean get() = job?.isActive == true

    /** Stops the loop for good (process teardown). */
    @Synchronized fun close() { job?.cancel(); job = null }

    /** Starts the loop once; later calls do nothing, so there is never a second loop. */
    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun setMode(mode: Mode) {
        val before = config.value.mode
        if (before == mode) return
        config.value = config.value.copy(mode = mode)
        publish()
        // Back to the foreground: refresh at once. Any other change only re-times the wait (the loop re-reads the interval).
        if (!before.isForeground && mode.isForeground) immediate.trySend("foreground")
    }

    fun setSettings(settings: Settings) { config.value = config.value.copy(settings = settings); publish() }

    /** A headset session is running: [target] performs one refresh. Replaces any earlier target. */
    fun attach(target: suspend (String) -> Unit) { config.value = config.value.copy(target = target); publish(); immediate.trySend("connected") }

    /** The headset disconnected: polling stops until a new session attaches. */
    fun detach() { config.value = config.value.copy(target = null); publish() }

    /** Refresh as soon as possible regardless of the timer (a Bluetooth notification, a user action). */
    fun requestNow(reason: String) { immediate.trySend(reason) }

    /** Sleep timer ended: no refresh at all until [resume] (the user opens the app again). */
    fun shutdown() { config.value = config.value.copy(shutdown = true); publish() }

    fun resume() { config.value = config.value.copy(shutdown = false); publish() }

    /** The current interval; null while stopped. */
    fun intervalMillis(): Long? = intervalMillis(config.value)

    private fun intervalMillis(c: Config): Long? {
        if (c.shutdown || c.target == null) return null
        val s = c.settings
        val seconds = when (c.mode) {
            Mode.MAIN_SCREEN -> s.activeSeconds
            Mode.SECTION -> (s.activeSeconds * 2).coerceAtMost(s.backgroundSeconds)
            Mode.BACKGROUND, Mode.LOCKED -> s.backgroundSeconds
        }
        return seconds * 1000L
    }

    private suspend fun loop() {
        while (true) {
            val c = config.value
            val interval = intervalMillis(c)
            if (interval == null) {
                // Stopped: drop stale immediate requests and wait for a configuration that runs again.
                while (immediate.tryReceive().isSuccess) Unit
                config.first { it != c }
                continue
            }
            while (reschedule.tryReceive().isSuccess) Unit   // the configuration read above is current
            val reason: String? = select {
                immediate.onReceive { it }
                reschedule.onReceive { null }              // mode/settings/target changed: re-time without a refresh
                onTimeout(interval) { "interval" }
            }
            if (reason == null) continue
            val target = config.value.target ?: continue
            if (config.value.shutdown) continue
            ticks++
            lastTickMillis = clock()
            lastReason = reason
            _status.value = statusOf(config.value)
            try {
                target(reason)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed refresh is the session's business (it logs and marks data stale); the loop keeps its rhythm.
            }
        }
    }

    private fun publish() { _status.value = statusOf(config.value); reschedule.trySend(Unit) }

    private fun statusOf(c: Config) = Status(c.mode, intervalMillis(c), c.target != null, c.shutdown, ticks, lastTickMillis, lastReason)

    companion object {
        const val DEFAULT_ACTIVE_SECONDS = 5
        const val DEFAULT_BACKGROUND_SECONDS = 60
        val ACTIVE_CHOICES = listOf(5, 10, 15, 30)
        val BACKGROUND_CHOICES = listOf(30, 60, 120, 300)
        val Mode.isForeground: Boolean get() = this == Mode.MAIN_SCREEN || this == Mode.SECTION
    }
}
