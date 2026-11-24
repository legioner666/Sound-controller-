package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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

    /** Outcome of one [connect] call, recorded for diagnostics. */
    data class ConnectResult(val ok: Boolean, val status: Int?, val newState: Int?, val durationMillis: Long, val outcome: String, val services: Int)

    @Volatile private var lastStatus: Int? = null
    @Volatile private var lastNewState: Int? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _notifications = MutableSharedFlow<Notification>(extraBufferCapacity = 256)
    val notifications: SharedFlow<Notification> = _notifications.asSharedFlow()

    var services: List<BluetoothGattService> = emptyList()
        private set

    private fun log(cat: EventCategory, msg: String, hex: String? = null) =
        sink.log(DiagnosticEvent(clock(), cat, msg, hex, device.address))

    /** Callbacks from a BluetoothGatt that was already closed or replaced are ignored. */
    private fun isStale(g: BluetoothGatt): Boolean {
        val current = gatt
        if (current == null && connectWaiter != null) return false // connectGatt has not returned yet
        if (current != null && g === current) return false
        log(EventCategory.GATT, "ignored callback from a stale GATT object")
        return true
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (isStale(g)) return
            val up = newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS
            lastStatus = status; lastNewState = newState
            log(EventCategory.GATT, "connection state $newState (status $status)")
            _connected.value = up
            connectWaiter?.complete(up)
            if (!up) {
                pending?.complete(null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (isStale(g)) return
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) g.services else null)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (isStale(g)) return
            log(EventCategory.GATT, "MTU $mtu (status $status)")
            pending?.complete(mtu)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (isStale(g)) return
            pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (isStale(g)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                pending?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value?.copyOf() else null)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (isStale(g)) return
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (isStale(g)) return
            pending?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (isStale(g)) return
            _notifications.tryEmit(Notification(c.service.uuid, c.uuid, value.copyOf()))
        }

        @Deprecated("Android 12 and lower")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (isStale(g)) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val v = c.value?.copyOf() ?: return
                _notifications.tryEmit(Notification(c.service.uuid, c.uuid, v))
            }
        }
    }

    /**
     * Connects with the given transport and discovers services. Records GATT status, new state, bond state and duration.
     * A timeout with no callback at all means the address did not answer as a connectable GATT peripheral.
     */
    suspend fun connect(transport: Int = BluetoothDevice.TRANSPORT_LE, timeoutMillis: Long = 10_000): ConnectResult = ops.withLock {
        if (_connected.value && services.isNotEmpty()) return@withLock ConnectResult(true, lastStatus, lastNewState, 0, "ALREADY_CONNECTED", services.size)
        val started = clock()
        val waiter = CompletableDeferred<Boolean>()
        connectWaiter = waiter
        lastStatus = null; lastNewState = null
        log(EventCategory.GATT, "connecting GATT transport=${transportName(transport)} bond=${bondName(device.bondState)} timeout=${timeoutMillis}ms")
        gatt?.close()
        gatt = device.connectGatt(context, false, callback, transport)
        val ok = withTimeoutOrNull(timeoutMillis) { waiter.await() }
        connectWaiter = null
        if (ok != true) {
            val outcome = if (ok == null) "NO_CALLBACK_TIMEOUT" else "STATUS_${lastStatus}"
            log(EventCategory.GATT, "GATT connection failed: $outcome (status=$lastStatus state=$lastNewState)")
            gatt?.close(); gatt = null
            return@withLock ConnectResult(false, lastStatus, lastNewState, clock() - started, outcome, 0)
        }
        val g = gatt ?: return@withLock ConnectResult(false, lastStatus, lastNewState, clock() - started, "GATT_OBJECT_GONE", 0)
        @Suppress("UNCHECKED_CAST")
        val discovered = runOp(10_000) { g.discoverServices() } as? List<BluetoothGattService>
        services = discovered ?: emptyList()
        log(EventCategory.GATT, "services discovered: ${services.size}")
        runOp(3_000) { g.requestMtu(247) }
        val outcome = if (services.isNotEmpty()) "OK" else "CONNECTED_NO_SERVICES"
        ConnectResult(services.isNotEmpty(), lastStatus, lastNewState, clock() - started, outcome, services.size)
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
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
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
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
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
        fun transportName(t: Int) = when (t) {
            BluetoothDevice.TRANSPORT_LE -> "LE"
            BluetoothDevice.TRANSPORT_BREDR -> "BREDR"
            else -> "AUTO"
        }

        fun bondName(b: Int) = when (b) {
            BluetoothDevice.BOND_BONDED -> "BONDED"
            BluetoothDevice.BOND_BONDING -> "BONDING"
            else -> "NONE"
        }

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
