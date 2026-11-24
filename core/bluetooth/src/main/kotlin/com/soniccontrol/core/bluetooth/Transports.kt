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
import kotlinx.coroutines.isActive
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
    private val job: Job = scope.launch {
        launch { client.connected.collect { if (!it) _state.value = TransportState.Closed } }
        client.notifications.collect { n -> if (n.characteristic == notifyChar.uuid) _incoming.emit(n.value) }
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
class RfcommTransport private constructor(
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

    companion object {
        suspend fun open(
            device: BluetoothDevice, uuid: UUID, writable: Boolean, scope: CoroutineScope, sink: DiagnosticSink, clock: () -> Long,
        ): RfcommTransport? = withContext(Dispatchers.IO) {
            sink.log(DiagnosticEvent(clock(), EventCategory.RFCOMM, "opening RFCOMM $uuid", null, device.address))
            try {
                val s = device.createRfcommSocketToServiceRecord(uuid)
                s.connect()
                RfcommTransport(s, uuid, writable, scope, sink, clock)
            } catch (e: IOException) {
                sink.log(DiagnosticEvent(clock(), EventCategory.RFCOMM, "RFCOMM $uuid failed: ${e.message}", null, device.address))
                null
            } catch (e: SecurityException) {
                sink.log(DiagnosticEvent(clock(), EventCategory.PERMISSION, "RFCOMM: ${e.message}", null, device.address))
                null
            }
        }
    }
}
