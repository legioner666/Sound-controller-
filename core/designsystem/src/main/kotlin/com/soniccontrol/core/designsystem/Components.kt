package com.soniccontrol.core.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soniccontrol.core.devices.BatteryLevel
import com.soniccontrol.core.devices.currentCharging
import com.soniccontrol.core.devices.isCurrent
import com.soniccontrol.core.devices.DataSource
import com.soniccontrol.core.devices.SupportLevel
import com.soniccontrol.core.devices.SupportStatus
import java.text.DateFormat
import java.util.Date

/** Standard screen body: title and a lazy column with consistent spacing. */
@Composable
fun SonicScreen(title: String, subtitle: String? = null, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        content()
    }
}

@Composable
fun SonicCard(modifier: Modifier = Modifier, title: String? = null, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null || trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (title != null) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    } else Spacer(Modifier.weight(1f))
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

@Composable
fun statusLabel(status: SupportStatus): String = stringResource(
    when (status) {
        SupportStatus.VERIFIED -> R.string.status_verified
        SupportStatus.SUPPORTED -> R.string.status_supported
        SupportStatus.EXPERIMENTAL -> R.string.status_experimental
        SupportStatus.BASIC -> R.string.status_basic
        SupportStatus.UNSUPPORTED -> R.string.status_unsupported
    },
)

@Composable
fun StatusBadge(status: SupportStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        SupportStatus.VERIFIED -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        SupportStatus.SUPPORTED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        SupportStatus.EXPERIMENTAL -> SonicColors.Amber.copy(alpha = 0.22f) to MaterialTheme.colorScheme.onSurface
        SupportStatus.BASIC -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        SupportStatus.UNSUPPORTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Pill(statusLabel(status), bg, fg, modifier)
}

@Composable
fun LevelBadge(level: SupportLevel, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (level) {
            SupportLevel.UNIVERSAL -> R.string.level_universal
            SupportLevel.ADVANCED -> R.string.level_advanced
            SupportLevel.FULL -> R.string.level_full
        },
    )
    Pill(text, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, modifier)
}

@Composable
fun Pill(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1)
    }
}

enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun Banner(text: String, kind: BannerKind = BannerKind.INFO, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val (icon, tint) = when (kind) {
        BannerKind.INFO -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
        BannerKind.WARNING -> Icons.Outlined.WarningAmber to SonicColors.Amber
        BannerKind.ERROR -> Icons.Outlined.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** Age of a last-known value: "updated 45 s ago", "… 3 min ago", "… 2 h ago". Null when the time is unknown. */
@Composable
fun updatedAgo(updatedMillis: Long?, now: Long): String? {
    val t = updatedMillis ?: return null
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.updated_ago_seconds, s.toInt())
        s < 3600 -> stringResource(R.string.updated_ago_minutes, (s / 60).toInt())
        else -> stringResource(R.string.updated_ago_hours, (s / 3600).toInt())
    }
}

/** A value this old gets its age shown next to it, even before it counts as stale. */
const val SHOW_AGE_AFTER_MILLIS = 120_000L

/** Ring gauge for one battery component. Shows "—" when there is no real reading. */
@Composable
fun BatteryGauge(label: String, level: BatteryLevel?, modifier: Modifier = Modifier, diameter: Int = 84, active: Boolean? = null) {
    val pct = level?.percent
    // Freshness is time-based (TASK 010): re-evaluated every 30 s even when no new data arrives.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
    val chargingNow = level?.currentCharging(now) == true
    val stale = pct != null && level?.isCurrent(now) == false
    val animated by animateFloatAsState(targetValue = (pct ?: 0) / 100f, animationSpec = tween(700), label = "battery")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = when {
        pct == null -> MaterialTheme.colorScheme.outline
        pct <= 15 -> MaterialTheme.colorScheme.error
        pct <= 30 -> SonicColors.Amber
        else -> MaterialTheme.colorScheme.primary
    }
    val noReading = stringResource(R.string.battery_no_reading)
    val charging = stringResource(R.string.battery_charging)
    val desc = "$label: " + (pct?.let { "$it%" } ?: noReading) + if (chargingNow) ", $charging" else ""
    Column(modifier.semantics { contentDescription = desc }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(diameter.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 8.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (pct != null) drawArc(color, 135f, 270f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(pct?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleMedium)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AnimatedVisibility(chargingNow) {
            Text("⚡ $charging", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (active == false) Text(stringResource(R.string.battery_inactive), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val age = level?.updatedMillis?.takeIf { pct != null && now - it >= SHOW_AGE_AFTER_MILLIS }?.let { updatedAgo(it, now) }
        if (stale || age != null) {
            Text(
                listOfNotNull(if (stale) stringResource(R.string.battery_last_known) else null, age).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun sourceLabel(source: DataSource): String = stringResource(
    when (source) {
        DataSource.VENDOR_PROTOCOL -> R.string.source_vendor
        DataSource.VENDOR_NATIVE_EXPERIMENTAL -> R.string.source_vendor_native
        DataSource.FAST_PAIR_STREAM -> R.string.source_fast_pair
        DataSource.BLE_BATTERY_SERVICE -> R.string.source_ble_bas
        DataSource.APPLE_CONTINUITY_ADVERT -> R.string.source_apple_advert
        DataSource.FAST_PAIR_ADVERT -> R.string.source_fp_advert
    },
)

/** "Source: …, 14:03" under every value read from a device. */
@Composable
fun SourceNote(source: DataSource, timestampMillis: Long) {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestampMillis))
    Text(
        stringResource(R.string.source_note, sourceLabel(source), time),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.58f), overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun <T> SegmentedTabs(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    SonicCard {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) { Spacer(Modifier.height(4.dp)); action() }
    }
}
