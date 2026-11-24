package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.soniccontrol.core.devices.BluetoothTransport
import com.soniccontrol.core.devices.CharacteristicInfo
import com.soniccontrol.core.devices.DeviceLink
import com.soniccontrol.core.devices.ManufacturerAdvert
import com.soniccontrol.core.devices.ServiceInfo
import com.soniccontrol.core.devices.StandardUuids
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.protocol.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * [DeviceLink] over the Android Bluetooth stack. One GATT connection per link, created on first use.
 * Dual-mode earbuds sometimes expose GATT under a separate LE address ("NAME-LE"); [leCandidates]
 * supplies such addresses from recent scan results.
 */
@SuppressLint("MissingPermission")
class AndroidDeviceLink(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
    private val scanner: DeviceScanner,
    private val leCandidates: () -> List<String>,
) : DeviceLink {
    override val address: String = device.address
    override val name: String? = runCatching { device.name }.getOrNull()

    private val gattLock = Mutex()
    private var gatt: GattClient? = null
    private var gattTried = false
    private val transports = mutableListOf<BluetoothTransport>()
    private val lastValues = HashMap<UUID, String>()
    private val subscribed = HashSet<UUID>()

    override fun sdpUuids(): Set<UUID> = runCatching { device.uuids?.map { it.uuid }?.toSet() }.getOrNull() ?: emptySet()

    /** Asks Android to refresh the SDP record cache (standard service discovery, no vendor command). */
    fun refreshSdp() { runCatching { device.fetchUuidsWithSdp() } }

    private suspend fun gatt(): GattClient? = gattLock.withLock {
        gatt?.let { if (it.connected.value) return@withLock it }
        if (gattTried && gatt == null) return@withLock null
        gattTried = true
        val candidates = listOf(device) + leCandidates().filter { it != device.address }.mapNotNull { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        for (d in candidates) {
            val c = GattClient(context, d, sink, clock)
            if (c.connect()) { gatt = c; return@withLock c }
            c.close()
        }
        null
    }

    override suspend fun gattServices(): Set<UUID> = gatt()?.services?.map { it.uuid }?.toSet() ?: emptySet()

    override suspend fun readCharacteristic(service: UUID, characteristic: UUID): ByteArray? {
        val g = gatt() ?: return null
        val c = g.find(service, characteristic) ?: return null
        return g.read(c)?.also { lastValues[characteristic] = it.toHex() }
    }

    override suspend fun notifications(service: UUID, characteristic: UUID): Flow<ByteArray>? {
        val g = gatt() ?: return null
        val c = g.find(service, characteristic) ?: return null
        if (!g.enableNotifications(c)) return null
        subscribed += characteristic
        return g.notifications.filter { it.service == service && it.characteristic == characteristic }.map { it.value }
    }

    override suspend fun openGattChannel(service: UUID, notify: UUID?, write: UUID?): BluetoothTransport? {
        val g = gatt() ?: return null
        val svc = g.services.firstOrNull { it.uuid == service } ?: return null
        val n = notify?.let { svc.getCharacteristic(it) } ?: svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        } ?: return null
        val w = write?.let { svc.getCharacteristic(it) } ?: svc.characteristics.firstOrNull {
            it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        } ?: return null
        if (!g.enableNotifications(n)) return null
        subscribed += n.uuid
        return GattChannelTransport(g, n, w, scope, sink, clock).also { synchronized(transports) { transports += it } }
    }

    override suspend fun openRfcomm(uuid: UUID, writable: Boolean): BluetoothTransport? {
        runCatching { adapter.cancelDiscovery() }
        return RfcommTransport.open(device, uuid, writable, scope, sink, clock)?.also { synchronized(transports) { transports += it } }
    }

    override fun manufacturerAdverts(companyId: Int): Flow<ManufacturerAdvert> = scanner.adverts(companyId)

    // ---------------- Lab (read-only inspection) ----------------

    suspend fun snapshot(): List<ServiceInfo> {
        val g = gatt() ?: return emptyList()
        return g.services.map { s ->
            ServiceInfo(s.uuid, StandardUuids.describe(s.uuid), s.characteristics.map { c ->
                CharacteristicInfo(c.uuid, StandardUuids.describe(c.uuid), GattClient.propertyNames(c.properties), lastValues[c.uuid], c.uuid in subscribed)
            })
        }
    }

    /** Reads every readable characteristic. Reads never change the headset. */
    suspend fun readAll() {
        val g = gatt() ?: return
        for (s in g.services) for (c in s.characteristics) {
            if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) g.read(c)?.let { lastValues[c.uuid] = it.toHex() }
        }
    }

    /** Subscribes to every notify/indicate characteristic (standard CCCD write) and records values. */
    suspend fun subscribeAll(onValue: (UUID, ByteArray) -> Unit) {
        val g = gatt() ?: return
        for (s in g.services) for (c in s.characteristics) {
            if (c.uuid !in subscribed && g.enableNotifications(c)) subscribed += c.uuid
        }
        g.notifications.collect { n -> lastValues[n.characteristic] = n.value.toHex(); onValue(n.characteristic, n.value) }
    }

    suspend fun close() {
        val ts = synchronized(transports) { transports.toList().also { transports.clear() } }
        ts.forEach { runCatching { it.close() } }
        gattLock.withLock { gatt?.close(); gatt = null; gattTried = false }
    }
}
