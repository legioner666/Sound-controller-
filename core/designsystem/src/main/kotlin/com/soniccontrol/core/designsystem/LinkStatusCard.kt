package com.soniccontrol.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.soniccontrol.core.devices.AudioLinkState
import com.soniccontrol.core.devices.ChannelState
import com.soniccontrol.core.devices.LinkStatus

/** One row per channel; only CONFIRMED is green, so "connected" is never implied by an unconfirmed channel. */
@Composable
fun LinkStatusRows(links: LinkStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinkRow(stringResource(R.string.link_audio), audioLabel(links.audio), audioColor(links.audio))
        if (links.gatt != ChannelState.UNAVAILABLE) LinkRow(stringResource(R.string.link_gatt), channelLabel(links.gatt), channelColor(links.gatt))
        if (links.rfcomm != ChannelState.UNAVAILABLE) LinkRow(stringResource(R.string.link_rfcomm), channelLabel(links.rfcomm), channelColor(links.rfcomm))
        LinkRow(stringResource(R.string.link_control), channelLabel(links.control), channelColor(links.control))
    }
}

/** Short headline: what the user can actually rely on right now. */
@Composable
fun controlSummary(links: LinkStatus): String = stringResource(
    when (links.control) {
        ChannelState.CONFIRMED -> R.string.control_summary_confirmed
        ChannelState.UNAVAILABLE -> R.string.control_summary_none
        else -> R.string.control_summary_pending
    },
)

@Composable
private fun LinkRow(label: String, value: String, color: Color) {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun channelColor(c: ChannelState): Color = when (c) {
    ChannelState.CONFIRMED -> MaterialTheme.colorScheme.primary
    ChannelState.OPEN, ChannelState.OPENING -> SonicColors.Amber
    ChannelState.FAILED -> MaterialTheme.colorScheme.error
    ChannelState.CLOSED, ChannelState.UNAVAILABLE -> MaterialTheme.colorScheme.outline
}

@Composable
private fun audioColor(a: AudioLinkState): Color = when (a) {
    AudioLinkState.CONNECTED -> MaterialTheme.colorScheme.primary
    AudioLinkState.DISCONNECTED -> MaterialTheme.colorScheme.error
    AudioLinkState.UNKNOWN -> MaterialTheme.colorScheme.outline
}
