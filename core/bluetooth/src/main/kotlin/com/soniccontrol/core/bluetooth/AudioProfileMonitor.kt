package com.soniccontrol.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.soniccontrol.core.devices.AudioLinkState

/**
 * Actual audio connection of a headset, from the public A2DP and Headset profile proxies and their
 * connection-state broadcasts (TASK 009). A GATT or ACL link says nothing about audio: LE links come and go while
 * music keeps playing, and the other way round. UNKNOWN only means "the profile proxies are not available (yet)".
 */
@SuppressLint("MissingPermission")
class AudioProfileMonitor(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val onChange: (address: String, state: AudioLinkState) -> Unit,
) {
    @Volatile private var a2dp: BluetoothProfile? = null
    @Volatile private var headset: BluetoothProfile? = null
    @Volatile private var leAudio: BluetoothProfile? = null   // LE Audio (public BluetoothProfile.LE_AUDIO, Android 13+)
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val device: BluetoothDevice = (
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                ) ?: return
            val newState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            // The broadcast carries the profile's new state; the combined state also consults the other profile.
            val connected = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> true
                BluetoothProfile.STATE_DISCONNECTED -> stateOf(device.address) == AudioLinkState.CONNECTED && otherConnected(intent.action, device)
                else -> return
            }
            onChange(device.address, if (connected) AudioLinkState.CONNECTED else AudioLinkState.DISCONNECTED)
        }
    }

    private fun otherConnected(action: String?, d: BluetoothDevice): Boolean {
        return listOfNotNull(a2dp, headset, leAudio).filter { it !== (if (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) a2dp else if (action == LE_AUDIO_ACTION) leAudio else headset) }
            .any { runCatching { it.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false) }
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        val profiles = buildList { add(BluetoothProfile.A2DP); add(BluetoothProfile.HEADSET); if (Build.VERSION.SDK_INT >= 33) add(LE_AUDIO) }
        for (profile in profiles) {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    when (p) { BluetoothProfile.A2DP -> a2dp = proxy; LE_AUDIO -> leAudio = proxy; else -> headset = proxy }
                    runCatching { proxy.connectedDevices }.getOrNull()?.forEach { onChange(it.address, AudioLinkState.CONNECTED) }
                }
                override fun onServiceDisconnected(p: Int) { when (p) { BluetoothProfile.A2DP -> a2dp = null; LE_AUDIO -> leAudio = null; else -> headset = null } }
            }, profile)
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) addAction(LE_AUDIO_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** CONNECTED when A2DP or HFP is connected; DISCONNECTED when the proxies answer and neither is; UNKNOWN without proxies. */
    fun stateOf(address: String): AudioLinkState {
        val proxies = listOfNotNull(a2dp, headset, leAudio)
        if (proxies.isEmpty()) return AudioLinkState.UNKNOWN
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return AudioLinkState.UNKNOWN
        val any = proxies.map { p -> runCatching { p.getConnectionState(device) }.getOrNull() }
        return when {
            any.any { it == BluetoothProfile.STATE_CONNECTED } -> AudioLinkState.CONNECTED
            any.all { it == null } -> AudioLinkState.UNKNOWN
            else -> AudioLinkState.DISCONNECTED
        }
    }

    fun connectedAddresses(): Set<String> =
        listOfNotNull(a2dp, headset, leAudio).flatMap { p -> runCatching { p.connectedDevices }.getOrNull().orEmpty() }.map { it.address }.toSet()

    @Synchronized fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        a2dp?.let { runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) } }
        headset?.let { runCatching { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) } }
        leAudio?.let { runCatching { adapter.closeProfileProxy(LE_AUDIO, it) } }
        a2dp = null; headset = null; leAudio = null
    }

    private companion object {
        /** Public constants (API 33): BluetoothProfile.LE_AUDIO = 22 and BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED. */
        const val LE_AUDIO = 22
        const val LE_AUDIO_ACTION = "android.bluetooth.action.LE_AUDIO_CONNECTION_STATE_CHANGED"
    }
}
