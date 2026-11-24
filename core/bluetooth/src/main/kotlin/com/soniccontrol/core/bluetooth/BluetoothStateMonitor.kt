package com.soniccontrol.core.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.soniccontrol.core.devices.BluetoothAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks whether Bluetooth is usable: present, on and permitted. */
class BluetoothStateMonitor(private val context: Context) {
    val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow(compute())
    val state: StateFlow<BluetoothAvailability> = _state.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) { refresh() }
    }

    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun refresh() { _state.value = compute() }

    private fun compute(): BluetoothAvailability {
        val a = adapter ?: return BluetoothAvailability.UNAVAILABLE
        if (!BluetoothPermissions.granted(context)) return BluetoothAvailability.NO_PERMISSION
        return if (runCatching { a.isEnabled }.getOrDefault(false)) BluetoothAvailability.READY else BluetoothAvailability.OFF
    }
}
