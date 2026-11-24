package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.soniccontrol.core.devices.BleObservation
import com.soniccontrol.core.devices.DiscoveredDevice
import com.soniccontrol.core.devices.DiscoverySource
import com.soniccontrol.core.devices.ManufacturerAdvert
import com.soniccontrol.core.diagnostics.DiagnosticEvent
import com.soniccontrol.core.diagnostics.DiagnosticSink
import com.soniccontrol.core.diagnostics.EventCategory
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update

/**
 * Finds headsets: bonded devices, devices connected for audio (A2DP / headset profiles) and nearby BLE advertisers.
 * Permission checks happen in the caller ([BluetoothPermissions]); scan results never feed location logic.
 */
@SuppressLint("MissingPermission")
class DeviceScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val sink: DiagnosticSink,
    private val clock: () -> Long,
) {
    private val found = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val devices: StateFlow<Map<String, DiscoveredDevice>> = found.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val observed = java.util.concurrent.ConcurrentHashMap<String, BleObservation>()
    private val fastPairData = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val _fastPairAdverts = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, ByteArray>>(extraBufferCapacity = 64)
    val fastPairAdverts: Flow<Pair<String, ByteArray>> = _fastPairAdverts

    /** Every advertiser seen in the last [maxAgeMillis], including unnamed ones (the Lab and the endpoint resolver need them). */
    fun observations(maxAgeMillis: Long = 90_000): List<BleObservation> {
        val now = clock()
        return observed.values.filter { now - it.lastSeenMillis <= maxAgeMillis }
    }

    /** Fast Pair model ids seen in advertisements, by address. */
    val fastPairModels = HashMap<String, String>()

    private fun log(msg: String, address: String? = null) = sink.log(DiagnosticEvent(clock(), EventCategory.SCAN, msg, null, address))

    private fun merge(address: String, name: String?, rssi: Int?, source: DiscoverySource, bonded: Boolean? = null, audio: Boolean? = null) {
        found.update { m ->
            val old = m[address]
            val d = DiscoveredDevice(
                address = address,
                name = name ?: old?.name,
                rssi = rssi ?: old?.rssi,
                bonded = bonded ?: old?.bonded ?: false,
                audioConnected = audio ?: old?.audioConnected ?: false,
                sources = (old?.sources ?: emptySet()) + source,
                lastSeenMillis = clock(),
            )
            m + (address to d)
        }
    }

    fun loadBonded() {
        val bonded = runCatching { adapter.bondedDevices }.getOrNull() ?: return
        bonded.forEach { merge(it.address, runCatching { it.name }.getOrNull(), null, DiscoverySource.BONDED, bonded = true) }
        log("bonded devices: ${bonded.size}")
    }

    /** Marks devices currently connected for audio. Uses the public profile proxies. */
    fun loadAudioConnected() {
        found.update { m -> m.mapValues { it.value.copy(audioConnected = false) } }
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    runCatching { proxy.connectedDevices }.getOrNull()?.forEach { d: BluetoothDevice ->
                        merge(d.address, runCatching { d.name }.getOrNull(), null, DiscoverySource.AUDIO_CONNECTED, bonded = true, audio = true)
                    }
                    adapter.closeProfileProxy(p, proxy)
                }
                override fun onServiceDisconnected(p: Int) = Unit
            }, profile)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onResult)
        override fun onScanFailed(errorCode: Int) {
            log("scan failed: $errorCode"); _scanning.value = false
        }
    }

    private fun onResult(r: ScanResult) {
        val record = r.scanRecord
        val name = record?.deviceName ?: runCatching { r.device.name }.getOrNull()
        val fpData = record?.getServiceData(FAST_PAIR_UUID)
        if (observed.size < 300 || observed.containsKey(r.device.address)) {
            observed[r.device.address] = BleObservation(
                address = r.device.address,
                name = name,
                rssi = r.rssi,
                serviceUuids = record?.serviceUuids?.map { it.uuid }?.toSet() ?: emptySet(),
                manufacturerIds = buildSet {
                    val m = record?.manufacturerSpecificData
                    if (m != null) for (i in 0 until m.size()) add(m.keyAt(i))
                },
                hasFastPairData = fpData != null,
                connectable = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) r.isConnectable else true,
                lastSeenMillis = clock(),
            )
        }
        if (fpData != null) { fastPairData[r.device.address] = fpData; _fastPairAdverts.tryEmit(r.device.address to fpData) }
        record?.getServiceData(FAST_PAIR_UUID)?.takeIf { it.size == 3 }?.let {
            fastPairModels[r.device.address] = it.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        }
        // Unnamed advertisers are usually not headsets; keep the list readable.
        if (name.isNullOrBlank() && r.device.address !in found.value) return
        merge(r.device.address, name, r.rssi, DiscoverySource.BLE_SCAN)
    }

    fun startScan() {
        if (_scanning.value) return
        val scanner = adapter.bluetoothLeScanner ?: return log("BLE scanner unavailable (Bluetooth off?)")
        loadBonded(); loadAudioConnected()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, scanCallback) }.onSuccess { _scanning.value = true; log("BLE scan started") }
            .onFailure { log("scan start failed: ${it.message}") }
    }

    /** Runs a scan for [millis] when none is active, so connection planning can see LE endpoints. Returns when done. */
    suspend fun scanFor(millis: Long) {
        val wasScanning = _scanning.value
        if (!wasScanning) startScan()
        kotlinx.coroutines.delay(millis)
        if (!wasScanning) stopScan()
    }

    fun stopScan() {
        if (!_scanning.value) return
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCallback) }
        _scanning.value = false
        log("BLE scan stopped")
    }

    /** LE addresses whose advertised name matches [name] or its "-LE"/"LE-" twin. */
    fun leTwins(name: String?): List<String> {
        if (name.isNullOrBlank()) return emptyList()
        val keys = setOf(name, "$name-LE", "$name LE", "LE-$name", "LE $name").map { it.lowercase() }
        return found.value.values.filter { DiscoverySource.BLE_SCAN in it.sources && it.name?.lowercase() in keys }
            .sortedByDescending { it.rssi ?: -127 }.map { it.address }
    }

    /** Low-power filtered scan for one company's manufacturer data, active while collected. */
    fun adverts(companyId: Int): Flow<ManufacturerAdvert> = callbackFlow {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { close(); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getManufacturerSpecificData(companyId) ?: return
                trySend(ManufacturerAdvert(result.device.address, result.rssi, companyId, data, clock()))
            }
        }
        val filter = ScanFilter.Builder().setManufacturerData(companyId, ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(listOf(filter), settings, cb) }
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    companion object {
        private val FAST_PAIR_UUID: ParcelUuid = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
    }
}
