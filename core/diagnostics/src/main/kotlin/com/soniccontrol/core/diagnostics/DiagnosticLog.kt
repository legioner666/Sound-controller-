package com.soniccontrol.core.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EventCategory { SCAN, CONNECTION, GATT, RFCOMM, TX, RX, COMMAND, DRIVER, PERMISSION, ERROR, INFO }

data class DiagnosticEvent(
    val timestampMillis: Long,
    val category: EventCategory,
    val message: String,
    /** Raw bytes as hex, for TX/RX events. */
    val hex: String? = null,
    /** Device address; anonymised on export. */
    val address: String? = null,
)

/** Where drivers and transports report what they do. */
fun interface DiagnosticSink {
    fun log(event: DiagnosticEvent)
}

/** In-memory ring buffer; nothing is written to disk or sent anywhere unless the user exports it. */
class DiagnosticLog(private val capacity: Int = 3000, private val clock: () -> Long = System::currentTimeMillis) : DiagnosticSink {
    private val _events = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = _events.asStateFlow()

    override fun log(event: DiagnosticEvent) {
        _events.update { old -> (if (old.size >= capacity) old.drop(old.size - capacity + 1) else old) + event }
    }

    fun log(category: EventCategory, message: String, hex: String? = null, address: String? = null) =
        log(DiagnosticEvent(clock(), category, message, hex, address))

    fun clear() { _events.value = emptyList() }
}
