package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothSocket
import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.TransportException
import com.soniccontrol.core.devices.TransportState
import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Vendor channel over a pair of GATT characteristics. */
class GattChannelTransport(
    private val client: GattClient,
    private val notifyChar: BluetoothGattCharacteristic,
    private val writeChar: BluetoothGattCharacteristic,
    scope: CoroutineScope,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
) : BluetoothTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Open)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    // Ends with the GATT client (TASK 010): no notification collector outlives a disconnected client.
    private val job: Job = scope.launch {
        val reader = launch { client.notifications.collect { n -> if (n.characteristic == notifyChar.uuid) _incoming.emit(n.value) } }
        client.connected.first { !it }
        _state.value = TransportState.Closed
        reader.cancel()
    }

    override val description = "GATT ${writeChar.uuid}"
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    override val incoming: SharedFlow<ByteArray> = _incoming
    override val canWrite = true

    override suspend fun close() {
        job.cancel()
        _state.value = TransportState.Closed
    }

    override suspend fun write(bytes: ByteArray) {
        if (_state.value != TransportState.Open) throw TransportException("GATT channel closed")
        if (!client.write(writeChar, bytes)) throw TransportException("GATT write failed")
        sink.log(DiagnosticEvent(clock(), EventCategory.TX, "gatt write ${writeChar.uuid}", bytes.toHex(), client.device.address))
    }
}

/** RFCOMM socket located by SDP UUID. Listen-only when [writable] is false. */
@SuppressLint("MissingPermission")
class RfcommTransport internal constructor(
    private val socket: BluetoothSocket,
    private val uuid: UUID,
    override val canWrite: Boolean,
    scope: CoroutineScope,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
) : BluetoothTransport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Open)
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    private val writeLock = Mutex()
    private val address = socket.remoteDevice.address

    private val reader: Job = scope.launch(Dispatchers.IO) {
        val buf = ByteArray(1024)
        try {
            val input = socket.inputStream
            while (isActive) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) _incoming.emit(buf.copyOf(n))
            }
        } catch (e: IOException) {
            sink.log(DiagnosticEvent(clock(), EventCategory.RFCOMM, "rfcomm $uuid closed: ${e.message}", null, address))
        } finally {
            _state.value = TransportState.Closed
        }
    }

    override val description = "RFCOMM $uuid"
    override val state: StateFlow<TransportState> = _state.asStateFlow()
    override val incoming: SharedFlow<ByteArray> = _incoming

    override suspend fun close() {
        reader.cancel()
        withContext(Dispatchers.IO) { runCatching { socket.close() } }
        _state.value = TransportState.Closed
    }

    override suspend fun write(bytes: ByteArray) {
        if (!canWrite) throw TransportException("listen-only channel")
        if (_state.value != TransportState.Open) throw TransportException("RFCOMM closed")
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    socket.outputStream.write(bytes)
                    socket.outputStream.flush()
                } catch (e: IOException) {
                    throw TransportException("RFCOMM write failed: ${e.message}", e)
                }
            }
        }
    }

    /** Result of an open attempt: the transport or a classified failure. */
    class OpenResult(val transport: RfcommTransport?, val insecure: Boolean, val failure: String?, val detail: String?)

    companion object {
        /** Maps Android's generic socket messages to a cause that a human can act on. */
        fun classify(message: String?): String = when {
            message == null -> "IO_ERROR"
            message.contains("read failed", true) || message.contains("socket might closed", true) -> "REMOTE_CLOSED"
            message.contains("timeout", true) -> "TIMEOUT"
            message.contains("refused", true) || message.contains("not available", true) -> "SERVICE_NOT_AVAILABLE"
            message.contains("Device or resource busy", true) -> "BUSY"
            else -> "IO_ERROR"
        }

        /** Secure socket first; on failure one insecure attempt (same RFCOMM channel, link-level encryption not required). */
        suspend fun open(
            device: BluetoothDevice, uuid: UUID, writable: Boolean, scope: CoroutineScope, sink: DiagnosticSink, clock: () -> Long,
        ): OpenResult {
            val first = openOnce(device, uuid, writable, false, scope, sink, clock)
            if (first.transport != null || first.failure == "PERMISSION") return first
            val second = openOnce(device, uuid, writable, true, scope, sink, clock)
            return if (second.transport != null) second else OpenResult(null, true, second.failure, "secure=${first.failure}; insecure=${second.failure}")
        }

        private suspend fun openOnce(
            device: BluetoothDevice, uuid: UUID, writable: Boolean, insecure: Boolean, scope: CoroutineScope, sink: DiagnosticSink, clock: () -> Long,
        ): OpenResult = withContext(Dispatchers.IO) {
            sink.log(DiagnosticEvent(clock(), EventCategory.RFCOMM, "opening RFCOMM $uuid insecure=$insecure", null, device.address))
            var socket: BluetoothSocket? = null
            // connect() blocks and ignores coroutine cancellation; closing the socket aborts it, so nothing leaks.
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause -> if (cause != null) runCatching { socket?.close() } }
            try {
                val s = if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid) else device.createRfcommSocketToServiceRecord(uuid)
                socket = s
                s.connect()
                coroutineContext.ensureActive()
                OpenResult(RfcommTransport(s, uuid, writable, scope, sink, clock), insecure, null, null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                runCatching { socket?.close() }
                throw e
            } catch (e: IOException) {
                runCatching { socket?.close() }
                val cause = classify(e.message)
                sink.log(DiagnosticEvent(clock(), EventCategory.RFCOMM, "RFCOMM $uuid failed (insecure=$insecure): $cause: ${e.message}", null, device.address))
                OpenResult(null, insecure, cause, e.message)
            } catch (e: SecurityException) {
                runCatching { socket?.close() }
                sink.log(DiagnosticEvent(clock(), EventCategory.PERMISSION, "RFCOMM: ${e.message}", null, device.address))
                OpenResult(null, insecure, "PERMISSION", e.message)
            } finally {
                cancelHandle?.dispose()
            }
        }
    }
}
