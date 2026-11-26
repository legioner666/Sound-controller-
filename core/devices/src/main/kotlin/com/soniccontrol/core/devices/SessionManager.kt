package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Platform side of [SessionManager]: how to reach a device. Android implements it over BluetoothAdapter. */
interface LinkFactory {
    /** What is known about [address] without talking to it; null when it cannot be reached at all. */
    fun identity(address: String): DeviceIdentity?
    fun audioState(address: String): AudioLinkState
    /** A new link bound to [scope]; the manager closes it. */
    fun create(address: String, scope: CoroutineScope): DeviceLink?
}

/**
 * Owns the single active driver session. All lifecycle changes (connect, disconnect, audio link events,
 * retries, start results) are processed one at a time by an actor, so they cannot interleave.
 *
 * Every session gets a generation number. Anything that finishes after its session was replaced —
 * state updates, start results, scheduled retries, action results — is dropped.
 * Starting a session (GATT/RFCOMM I/O) runs outside the actor, so disconnect is never blocked by it.
 */
class SessionManager(
    private val scope: CoroutineScope,
    private val registry: DriverRegistry,
    private val links: LinkFactory,
    private val experimental: StateFlow<Boolean>,
    private val fastPair: StateFlow<Boolean>,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
    private val autoReconnect: () -> Boolean,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val onStarted: suspend (address: String) -> Unit = {},
    /** One coordinator for the whole process: budgets and breakers survive session restarts, so restart loops stay bounded too. */
    val coordinator: ConnectionCoordinator = ConnectionCoordinator(clock),
    /** The single refresh loop of the process (TASK 011). The running session is attached to it; stopping detaches it. The owner calls [AdaptiveRefreshCoordinator.start]. */
    val refresher: AdaptiveRefreshCoordinator = AdaptiveRefreshCoordinator(scope, clock),
) {
    private sealed interface Op {
        data class Connect(val address: String) : Op
        data object Disconnect : Op
        data class Audio(val address: String, val connected: Boolean) : Op
        data class Started(val gen: Long, val address: String, val attempt: Int, val ok: Boolean) : Op
        data class Retry(val gen: Long, val address: String, val attempt: Int) : Op
    }

    private class Running(val gen: Long, val address: String, val session: DriverSession, val link: DeviceLink, val scope: CoroutineScope)

    private val ops = Channel<Op>(Channel.UNLIMITED)
    private val _active = MutableStateFlow<ActiveDevice?>(null)
    val active: StateFlow<ActiveDevice?> = _active.asStateFlow()

    @Volatile private var gen = 0L
    @Volatile private var running: Running? = null
    private var retryJob: Job? = null

    init {
        scope.launch {
            for (op in ops) {
                try {
                    handle(op)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log(EventCategory.ERROR, "session manager: ${e.message}", null)
                }
            }
        }
    }

    fun connect(address: String) { ops.trySend(Op.Connect(address)) }
    fun disconnect() { ops.trySend(Op.Disconnect) }
    fun onAudioLink(address: String, connected: Boolean) { ops.trySend(Op.Audio(address, connected)) }

    /** A relevant Bluetooth event (audio connected, adapter on, app opened, ACL up): paused recoveries get a trial attempt. */
    fun wake(reason: String) { coordinator.onRelevantEvent(reason); log(EventCategory.CONNECTION, "coordinator wake: $reason", null) }

    /** Number of the running device session (increments on every start/stop); null when none runs. */
    val currentSessionGen: Long? get() = running?.gen

    /** The link of the current session, for read-only Lab inspection. */
    /** The running session, for diagnostics only. */
    fun currentSession(): DriverSession? = running?.session

    fun currentLink(): DeviceLink? = running?.link

    /**
     * Runs [block] on the current session. Returns null when there is none, or when the session was
     * replaced while [block] ran (the result would describe a device that is no longer active).
     */
    suspend fun <T> withSession(block: suspend (DriverSession) -> T): T? {
        val r = running ?: return null
        val result = block(r.session)
        return if (r.gen == gen && running === r) result else null
    }

    private suspend fun handle(op: Op) {
        when (op) {
            is Op.Connect -> {
                cancelRetry()
                stop()
                start(op.address, attempt = 1)
            }
            Op.Disconnect -> {
                cancelRetry()
                stop()
                _active.value = null
            }
            is Op.Audio -> onAudio(op)
            is Op.Started -> handleStarted(op)
            is Op.Retry -> if (op.gen == gen && running == null && _active.value?.identity?.address == op.address) start(op.address, op.attempt)
        }
    }

    private suspend fun onAudio(op: Op.Audio) {
        val a = _active.value ?: return
        if (a.identity.address != op.address) return
        val audio = if (op.connected) AudioLinkState.CONNECTED else AudioLinkState.DISCONNECTED
        _active.update { it?.copy(links = it.links.copy(audio = audio)) }
        if (!op.connected) {
            cancelRetry()
            stop()
            val next = if (autoReconnect()) ConnectionState.Reconnecting(0, 0) else ConnectionState.Idle
            _active.update { it?.copy(connection = next, links = LinkStatus(audio = AudioLinkState.DISCONNECTED)) }
        } else if (running != null) {
            wake("audio connected")
        } else if (running == null && autoReconnect()) {
            cancelRetry()
            start(op.address, attempt = 1)
        }
    }

    private suspend fun handleStarted(op: Op.Started) {
        if (op.gen != gen) return // a newer connect or a disconnect happened meanwhile
        if (op.ok) {
            _active.update { it?.copy(connection = ConnectionState.Active) }
            onStarted(op.address)
            return
        }
        stop()
        val wait = policy.delayFor(op.attempt)
        if (wait == null) {
            _active.update { it?.copy(connection = ConnectionState.Failed("session could not start after ${op.attempt - 1} retries")) }
            return
        }
        _active.update { it?.copy(connection = ConnectionState.Reconnecting(op.attempt, wait)) }
        val scheduled = gen
        retryJob = scope.launch {
            delay(wait)
            ops.send(Op.Retry(scheduled, op.address, op.attempt + 1))
        }
    }

    private fun start(address: String, attempt: Int) {
        gen += 1
        val myGen = gen
        val identity = links.identity(address)
        if (identity == null) {
            _active.value = null
            log(EventCategory.CONNECTION, "device unreachable", address)
            return
        }
        val (driver, match) = registry.resolve(identity)
        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val link = links.create(address, sessionScope)
        if (link == null) {
            sessionScope.cancel()
            _active.value = ActiveDevice(identity, driver.id, driver.displayName, match, ConnectionState.Failed("Bluetooth unavailable"), HeadphoneState(identity.name ?: address))
            return
        }
        val session = driver.createSession(link, identity, DriverEnvironment(sessionScope, experimental, fastPair, sink, clock, refreshRequests = refresher::requestNow, coordinator = coordinator))
        val audio = links.audioState(address)
        running = Running(myGen, address, session, link, sessionScope)
        _active.value = ActiveDevice(identity, driver.id, driver.displayName, match, ConnectionState.Starting, session.state.value, LinkStatus(audio = audio))
        log(EventCategory.DRIVER, "session $myGen: driver ${driver.id} (${match.reason}), attempt $attempt", address)

        sessionScope.launch {
            combine(session.state, link.gattState, link.rfcommState) { st, g, r -> Triple(st, g, r) }.collect { (st, g, r) ->
                if (gen != myGen) return@collect
                _active.update { it?.copy(headphone = st, links = it.links.copy(gatt = g, rfcomm = r, control = st.control), stateDeliveredMillis = clock()) }
            }
        }
        sessionScope.launch {
            val ok = try {
                session.start(); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(EventCategory.ERROR, "session $myGen start failed: ${e.message}", address); false
            }
            if (ok && gen == myGen) refresher.attach { reason -> if (gen == myGen) session.refreshNow(reason) }
            ops.send(Op.Started(myGen, address, attempt, ok))
        }
    }

    private suspend fun stop() {
        val r = running ?: return
        running = null
        gen += 1
        refresher.detach()
        try { r.session.stop() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        try { r.link.close() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        r.scope.cancel()
        log(EventCategory.CONNECTION, "session ${r.gen} stopped", r.address)
    }

    private fun cancelRetry() { retryJob?.cancel(); retryJob = null }

    private fun log(category: EventCategory, message: String, address: String?) =
        sink.log(DiagnosticEvent(clock(), category, message, null, address))
}
