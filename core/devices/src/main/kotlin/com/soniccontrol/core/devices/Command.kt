package com.soniccontrol.core.devices

import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class CommandKind { READ, WRITE }

fun interface ResponseMatcher {
    fun matches(frame: ByteArray): Boolean
}

/** One frame a driver may send. Built only by driver allowlists; see each driver's guard. */
class DeviceCommand(
    val id: String,
    val kind: CommandKind,
    val status: SupportStatus,
    val bytes: ByteArray,
    val timeoutMillis: Long = 1500,
    /** Null means the protocol sends no reply; the outcome is then [CommandOutcome.SentUnconfirmed]. */
    val matcher: ResponseMatcher?,
    val note: String = "",
)

sealed interface CommandOutcome {
    /** The headset answered with a matching frame. */
    data class Confirmed(val response: ByteArray) : CommandOutcome
    /** Handed to Bluetooth; this protocol gives no confirmation. Never shown as "done". */
    data object SentUnconfirmed : CommandOutcome
    data class Rejected(val reason: String) : CommandOutcome
    data object Timeout : CommandOutcome
    data object NotConnected : CommandOutcome
    data class Unsupported(val reason: String) : CommandOutcome
    data class Failed(val message: String) : CommandOutcome
}

val CommandOutcome.isSuccess: Boolean get() = this is CommandOutcome.Confirmed

/** Driver-specific last check on raw bytes. Returns a refusal reason, or null to allow. */
fun interface CommandGuard {
    fun refusal(command: DeviceCommand): String?
}

/**
 * Sends commands one at a time over a transport: guard -> status gate -> write -> wait for the matching
 * reply with a timeout. Never reports success without a reply.
 */
class CommandExecutor(
    private val transport: BluetoothTransport,
    private val guard: CommandGuard,
    private val experimentalEnabled: () -> Boolean,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
    private val address: String? = null,
) {
    private val mutex = Mutex()

    suspend fun execute(command: DeviceCommand): CommandOutcome {
        guard.refusal(command)?.let { return reject(command, it) }
        when (command.status) {
            SupportStatus.UNSUPPORTED, SupportStatus.BASIC -> return reject(command, "status ${command.status} is never sent")
            SupportStatus.EXPERIMENTAL -> if (!experimentalEnabled()) return reject(command, "experimental features are off")
            SupportStatus.VERIFIED, SupportStatus.SUPPORTED -> Unit
        }
        if (!transport.canWrite) return reject(command, "channel is listen-only")

        return mutex.withLock {
            if (transport.state.value != TransportState.Open) return@withLock CommandOutcome.NotConnected
            log(EventCategory.TX, "${command.id}", command.bytes.toHex())
            val matcher = command.matcher
            try {
                if (matcher == null) {
                    transport.write(command.bytes)
                    CommandOutcome.SentUnconfirmed
                } else {
                    coroutineScope {
                        val reply = async(start = CoroutineStart.UNDISPATCHED) {
                            transport.incoming.first { matcher.matches(it) }
                        }
                        transport.write(command.bytes)
                        val response = withTimeoutOrNull(command.timeoutMillis) { reply.await() }
                        if (response == null) {
                            reply.cancel()
                            log(EventCategory.COMMAND, "${command.id}: no reply in ${command.timeoutMillis} ms")
                            CommandOutcome.Timeout
                        } else {
                            CommandOutcome.Confirmed(response)
                        }
                    }
                }
            } catch (e: TransportException) {
                log(EventCategory.ERROR, "${command.id}: ${e.message}")
                CommandOutcome.Failed(e.message ?: "transport error")
            }
        }
    }

    private fun reject(command: DeviceCommand, reason: String): CommandOutcome {
        log(EventCategory.COMMAND, "${command.id} refused: $reason")
        return CommandOutcome.Rejected(reason)
    }

    private fun log(category: EventCategory, message: String, hex: String? = null) =
        sink.log(DiagnosticEvent(clock(), category, message, hex, address))
}
