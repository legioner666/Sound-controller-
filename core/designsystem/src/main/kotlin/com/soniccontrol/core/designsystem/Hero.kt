package com.soniccontrol.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soniccontrol.core.devices.BatteryLevel
import com.soniccontrol.core.devices.BatteryStatus
import com.soniccontrol.core.devices.ConnectionAdvice

/** Home-screen header: generic earbuds drawing, one-line name, short status and three separate battery gauges. */
@Composable
fun HeadphoneHero(name: String, status: String, battery: BatteryStatus?, details: List<String> = emptyList()) {
    SonicCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EarbudsIllustration(Modifier.fillMaxWidth().height(110.dp))
            Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val single = battery?.single
                if (battery != null && battery.left == null && battery.right == null && single != null) {
                    BatteryGauge(stringResource(R.string.battery_headset), single, diameter = 76)
                } else {
                    BatteryGauge(stringResource(R.string.battery_left), battery?.left, diameter = 76)
                    BatteryGauge(stringResource(R.string.battery_right), battery?.right, diameter = 76)
                    BatteryGauge(stringResource(R.string.battery_case), battery?.case, diameter = 76)
                }
            }
            details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (battery == null || !battery.hasAnyValue) {
                Text(stringResource(R.string.hero_no_data), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                SourceNote(battery.source, battery.timestampMillis)
            }
        }
    }
}

/** Neutral original drawing: two earbuds and a case. It is not a picture of any particular product. */
@Composable
private fun EarbudsIllustration(modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val fill = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.clearAndSetSemantics {}) {
        val cx = size.width / 2
        val h = size.height
        fun bud(x: Float, mirror: Float) {
            val w = h * 0.34f
            drawRoundRect(fill, Offset(x - w / 2, h * 0.08f), Size(w, h * 0.5f), CornerRadius(w / 2))
            drawRoundRect(line, Offset(x - w / 2, h * 0.08f), Size(w, h * 0.5f), CornerRadius(w / 2), style = Stroke(3.dp.toPx()))
            drawRoundRect(fill, Offset(x + mirror * w * 0.1f - w * 0.16f, h * 0.5f), Size(w * 0.32f, h * 0.42f), CornerRadius(w * 0.16f))
            drawRoundRect(line, Offset(x + mirror * w * 0.1f - w * 0.16f, h * 0.5f), Size(w * 0.32f, h * 0.42f), CornerRadius(w * 0.16f), style = Stroke(3.dp.toPx()))
        }
        bud(cx - h * 0.62f, -1f)
        bud(cx - h * 0.18f, -1f)
        val caseW = h * 0.62f
        val left = cx + h * 0.18f
        drawRoundRect(fill, Offset(left, h * 0.22f), Size(caseW, h * 0.64f), CornerRadius(h * 0.22f))
        drawRoundRect(line, Offset(left, h * 0.22f), Size(caseW, h * 0.64f), CornerRadius(h * 0.22f), style = Stroke(3.dp.toPx()))
        drawLine(line, Offset(left + 6.dp.toPx(), h * 0.44f), Offset(left + caseW - 6.dp.toPx(), h * 0.44f), 3.dp.toPx())
    }
}

@Composable
fun adviceTitle(a: ConnectionAdvice): String = stringResource(
    when (a) {
        ConnectionAdvice.NO_LE_ENDPOINT -> R.string.advice_no_endpoint_title
        ConnectionAdvice.GATT_REFUSED -> R.string.advice_refused_title
        ConnectionAdvice.NO_SERVICES -> R.string.advice_no_services_title
        ConnectionAdvice.CONTROL_UNAVAILABLE, ConnectionAdvice.NONE -> R.string.advice_unavailable_title
        ConnectionAdvice.WAITING -> R.string.advice_waiting
    },
)

@Composable
fun adviceBody(a: ConnectionAdvice): String = stringResource(
    when (a) {
        ConnectionAdvice.NO_LE_ENDPOINT -> R.string.advice_no_endpoint
        ConnectionAdvice.GATT_REFUSED -> R.string.advice_refused
        ConnectionAdvice.NO_SERVICES -> R.string.advice_no_services
        ConnectionAdvice.CONTROL_UNAVAILABLE, ConnectionAdvice.NONE -> R.string.advice_unavailable
        ConnectionAdvice.WAITING -> R.string.advice_waiting
    },
)

/** Compact bar chart of the 10-band curve; read-only preview. */
@Composable
fun MiniEqCurve(gainsDb: List<Float>, maxDb: Float, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier.clearAndSetSemantics {}) {
        val n = gainsDb.size.coerceAtLeast(1)
        val slot = size.width / n
        val mid = size.height / 2
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        gainsDb.forEachIndexed { i, g ->
            val hgt = (g / maxDb).coerceIn(-1f, 1f) * (size.height / 2 - 2.dp.toPx())
            val top = if (hgt >= 0) mid - hgt else mid
            drawRoundRect(bar, Offset(i * slot + slot * 0.2f, top), Size(slot * 0.6f, kotlin.math.abs(hgt).coerceAtLeast(2.dp.toPx())), CornerRadius(3.dp.toPx()))
        }
    }
}
