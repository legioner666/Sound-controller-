package com.soniccontrol.feature.battery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soniccontrol.core.database.BatteryHistory
import com.soniccontrol.core.database.BatteryRecord
import com.soniccontrol.core.database.SonicDatabase
import com.soniccontrol.core.designsystem.BatteryCard
import com.soniccontrol.core.designsystem.KeyValue
import com.soniccontrol.core.designsystem.R
import com.soniccontrol.core.designsystem.SonicCard
import com.soniccontrol.core.designsystem.SonicScreen
import com.soniccontrol.core.devices.HeadphoneController
import kotlinx.coroutines.launch

@Composable
fun BatteryScreen(controller: HeadphoneController, db: SonicDatabase, deviceKey: (String) -> String) {
    val active by controller.active.collectAsStateWithLifecycle()
    val history by db.batteryHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SonicScreen(title = stringResource(R.string.battery_lab_title), subtitle = stringResource(R.string.battery_lab_subtitle)) {
        val device = active
        item { BatteryCard(device?.headphone?.battery) }
        val records = device?.let { d -> history.filter { it.deviceKey == deviceKey(d.identity.address) } }.orEmpty()
        item {
            SonicCard(title = stringResource(R.string.battery_history)) {
                if (records.size < 2) {
                    Text(stringResource(R.string.battery_history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    HistoryChart(records.takeLast(300))
                    val rate = BatteryHistory.dischargeRatePerHour(records)
                    val remaining = BatteryHistory.remainingHours(device?.headphone?.battery?.lowestListeningPercent, rate)
                    KeyValue(stringResource(R.string.battery_points), records.size.toString())
                    KeyValue(stringResource(R.string.battery_rate), rate?.let { "%.1f %%/h".format(it) } ?: stringResource(R.string.battery_not_enough_data))
                    KeyValue(stringResource(R.string.battery_remaining), remaining?.let { "%.1f h".format(it) } ?: stringResource(R.string.battery_not_enough_data))
                    Text(stringResource(R.string.battery_estimate_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = { scope.launch { db.clearBatteryHistory() } }) { Text(stringResource(R.string.battery_clear_history)) }
            }
        }
    }
}

@Composable
private fun HistoryChart(records: List<BatteryRecord>) {
    val pts = records.mapNotNull { r -> r.lowest?.let { r.timeMillis to it } }
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val desc = stringResource(R.string.battery_chart_desc, pts.size)
    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = desc }) {
        if (pts.size < 2) return@Canvas
        val t0 = pts.first().first.toFloat(); val t1 = pts.last().first.toFloat().coerceAtLeast(t0 + 1)
        listOf(0f, 0.5f, 1f).forEach { f -> drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), 1f) }
        val path = Path()
        pts.forEachIndexed { i, (t, v) ->
            val x = (t - t0) / (t1 - t0) * size.width
            val y = size.height * (1f - v / 100f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 3.dp.toPx()))
    }
}
