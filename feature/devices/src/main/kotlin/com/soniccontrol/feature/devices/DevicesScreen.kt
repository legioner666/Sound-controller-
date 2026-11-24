package com.soniccontrol.feature.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.designsystem.Banner
import com.soniccontrol.core.designsystem.BannerKind
import com.soniccontrol.core.designsystem.Dot
import com.soniccontrol.core.designsystem.EmptyState
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SectionHeader
import com.soniccontrol.core.designsystem.SegmentedTabs
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.designsystem.StatusBadge
import com.soniccontrol.core.designsystem.connectionLabel
import com.soniccontrol.core.designsystem.featureLabel
import com.soniccontrol.core.devices.BluetoothAvailability
import com.soniccontrol.core.devices.CatalogEntry
import com.soniccontrol.core.devices.DiscoveredDevice
import com.soniccontrol.core.devices.HeadphoneController
import com.soniccontrol.core.devices.SupportStatus

private enum class DevicesTab { MINE, CATALOG }

@Composable
fun DevicesScreen(controller: HeadphoneController, onRequestPermissions: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(DevicesTab.MINE) }
    val availability by controller.availability.collectAsStateWithLifecycle()
    val devices by controller.devices.collectAsStateWithLifecycle()
    val scanning by controller.scanning.collectAsStateWithLifecycle()
    val active by controller.active.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { controller.stopScan() } }

    SonicScreen(title = stringResource(R.string.devices_title)) {
        item {
            SegmentedTabs(DevicesTab.entries, tab, {
                stringResource(if (it == DevicesTab.MINE) R.string.devices_tab_mine else R.string.devices_tab_catalog)
            }) { tab = it }
        }
        if (tab == DevicesTab.MINE) {
            when (availability) {
                BluetoothAvailability.NO_PERMISSION -> item {
                    Banner(stringResource(R.string.bt_no_permission), BannerKind.WARNING, stringResource(R.string.action_allow), onRequestPermissions)
                }
                BluetoothAvailability.OFF -> item { Banner(stringResource(R.string.bt_off), BannerKind.WARNING) }
                BluetoothAvailability.UNAVAILABLE -> item { Banner(stringResource(R.string.bt_unavailable), BannerKind.ERROR) }
                BluetoothAvailability.READY -> Unit
            }
            active?.let { a ->
                item {
                    SonicCard(title = stringResource(R.string.devices_active)) {
                        Text(a.headphone.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(connectionLabel(a.connection), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.devices_driver, a.driverName, a.match.reason), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = controller::disconnect) { Text(stringResource(R.string.action_disconnect)) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (scanning) controller.stopScan() else controller.startScan() },
                        enabled = availability == BluetoothAvailability.READY,
                    ) {
                        Icon(Icons.Outlined.BluetoothSearching, null); Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (scanning) R.string.action_stop_scan else R.string.action_scan))
                    }
                }
                if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            val audio = devices.filter { it.audioConnected }
            val bonded = devices.filter { it.bonded && !it.audioConnected }
            val nearby = devices.filter { !it.bonded }.sortedByDescending { it.rssi ?: -127 }
            if (devices.isEmpty()) {
                item { EmptyState(stringResource(R.string.devices_empty_title), stringResource(R.string.devices_empty_body)) }
            }
            if (audio.isNotEmpty()) { item { SectionHeader(stringResource(R.string.devices_section_connected)) }; items(audio, key = { "a" + it.address }) { DeviceRow(it, active?.identity?.address == it.address, controller::connect) } }
            if (bonded.isNotEmpty()) { item { SectionHeader(stringResource(R.string.devices_section_paired)) }; items(bonded, key = { "b" + it.address }) { DeviceRow(it, active?.identity?.address == it.address, controller::connect) } }
            if (nearby.isNotEmpty()) { item { SectionHeader(stringResource(R.string.devices_section_nearby)) }; items(nearby, key = { "n" + it.address }) { DeviceRow(it, active?.identity?.address == it.address, controller::connect) } }
            item { Text(stringResource(R.string.devices_one_app_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(stringResource(R.string.catalog_search)) }, singleLine = true)
            }
            item { Text(stringResource(R.string.catalog_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val results = controller.catalog.search(query)
            results.groupBy { it.brand }.toSortedMap(compareBy { it.ordinal }).forEach { (brand, list) ->
                item(key = "h" + brand.name) { SectionHeader("${brand.displayName} · ${list.size}") }
                items(list, key = { "c" + it.brand.name + it.model }) { CatalogRow(it) }
            }
            item { Text(stringResource(R.string.catalog_trademarks), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DeviceRow(d: DiscoveredDevice, isActive: Boolean, onConnect: (String) -> Unit) {
    SonicCard(Modifier.clickable { onConnect(d.address) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Headphones, null, tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name ?: stringResource(R.string.devices_unnamed), style = MaterialTheme.typography.titleSmall)
                val details = buildList {
                    if (d.audioConnected) add(stringResource(R.string.devices_tag_audio))
                    if (d.bonded) add(stringResource(R.string.devices_tag_paired))
                    d.rssi?.let { add("$it dBm") }
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            d.catalogEntry?.let { StatusBadge(it.bestStatus) } ?: if (isActive) Dot(MaterialTheme.colorScheme.primary) else Unit
        }
    }
}

@Composable
private fun CatalogRow(e: CatalogEntry) {
    var open by rememberSaveable(e.brand.name + e.model) { mutableStateOf(false) }
    SonicCard(Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.fullName, style = MaterialTheme.typography.titleSmall)
                Text(e.protocol, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusBadge(e.bestStatus)
        }
        if (open) {
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                e.features.filterValues { it != SupportStatus.UNSUPPORTED }.forEach { (f, s) ->
                    Column { Text(featureLabel(f), style = MaterialTheme.typography.labelSmall); StatusBadge(s) }
                }
            }
            if (e.notes.isNotBlank()) Text(e.notes, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.catalog_battery_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
