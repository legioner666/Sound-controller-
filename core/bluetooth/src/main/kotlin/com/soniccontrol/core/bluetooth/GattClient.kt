package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.soniccontrol.core.devices.StandardUuids
import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Coroutine wrapper around [BluetoothGatt]. Android allows one outstanding GATT operation at a time,
 * so every operation is serialised and has a timeout.
 *
 * Permission: callers obtain an instance only after BLUETOOTH_CONNECT was granted (see [BluetoothPermissions]).
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    val device: BluetoothDevice,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
) {
    data class Notification(val service: UUID, val characteristic: UUID, val value: ByteArray)

    private var gatt: BluetoothGatt? = null
    private val ops = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Boolean>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256)
    val notifications: SharedFlow<Notification> = _notifications.asSharedFlow()

    var services: List<BluetoothGattService> = emptyList()
        private set

    private fun log(cat: EventCategory, msg: String, hex: String? = null) =
        sink.log(DiagnosticEvent(clock(), cat, msg, hex, device.address))

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val up = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            log(EventCategory.GATT, "connection state $newState (status $status)")
            _connected.value = up
            connectWaiter?.complete(up)
            if (!up) {
                pending?.complete(null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) g.services else null)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log(EventCategory.GATT, "MTU $mtu (status $status)")
            pending?.complete(mtu)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            _notifications.tryEmit(Notification(c.service.uuid, c.uuid, value.copyOf()))
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val v = c.value?.copyOf() ?: return
                _notifications.tryEmit(Notification(c.service.uuid, c.uuid, v))
            }
        }
    }

    /** Connects over LE and discovers services. Returns false on failure or timeout. */
    suspend fun connect(timeoutMillis: Long = 15_000): Boolean = ops.withLock {
        if (_connected.value && services.isNotEmpty()) return@withLock true
        val waiter = CompletableDeferred<Boolean>()
        connectWaiter = waiter
        log(EventCategory.GATT, "connecting GATT (LE)")
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(timeoutMillis) { waiter.await() } ?: false
        connectWaiter = null
        if (!ok) {
            log(EventCategory.GATT, "GATT connection failed")
            gatt?.close(); gatt = null
            return@withLock false
        }
        val g = gatt ?: return@withLock false
        @Suppress("UNCHECKED_CAST")
        val discovered = runOp(10_000) { g.discoverServices() } as? List<BluetoothGattService>
        services = discovered ?: emptyList()
        log(EventCategory.GATT, "services discovered: ${services.size}")
        runOp(3_000) { g.requestMtu(247) }
        services.isNotEmpty()
    }

    /** Must be called with [ops] held. */
    private suspend fun runOp(timeoutMillis: Long, start: () -> Boolean): Any? {
        val d = CompletableDeferred<Any?>()
        pending = d
        val started = try { start() } catch (e: SecurityException) { log(EventCategory.ERROR, "permission: ${e.message}"); false }
        if (!started) { pending = null; return null }
        val r = withTimeoutOrNull(timeoutMillis) { d.await() }
        pending = null
        return r
    }

    fun find(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        services.firstOrNull { it.uuid == service }?.getCharacteristic(characteristic)

    suspend fun read(c: BluetoothGattCharacteristic): ByteArray? = ops.withLock {
        val g = gatt ?: return@withLock null
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return@withLock null
        val v = runOp(5_000) { g.readCharacteristic(c) } as? ByteArray
        log(EventCategory.GATT, "read ${c.uuid}", v?.toHex())
        v
    }

    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray): Boolean = ops.withLock {
        val g = gatt ?: return@withLock false
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val r = runOp(5_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
            }
        }
        r == true
    }

    /** Enables notifications or indications (whichever the characteristic offers) by writing the standard CCCD. */
    suspend fun enableNotifications(c: BluetoothGattCharacteristic): Boolean = ops.withLock {
        val g = gatt ?: return@withLock false
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) return@withLock false
        if (!g.setCharacteristicNotification(c, true)) return@withLock false
        val d = c.getDescriptor(StandardUuids.CCCD) ?: return@withLock true
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val r = runOp(5_000) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { d.value = value; g.writeDescriptor(d) }
            }
        }
        log(EventCategory.GATT, "subscribe ${c.uuid}: ${r == true}")
        r == true
    }

    fun close() {
        log(EventCategory.GATT, "closing GATT")
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        services = emptyList()
        _connected.value = false
        pending?.complete(null)
    }

    companion object {
        fun propertyNames(p: Int): Set<String> = buildSet {
            if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResponse")
            if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
            if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("signedWrite")
            if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("broadcast")
        }
    }
}
