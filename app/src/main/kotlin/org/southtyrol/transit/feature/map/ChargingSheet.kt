@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.R
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.feature.common.InfoPill
import org.southtyrol.transit.feature.common.groupShape
import org.southtyrol.transit.model.Charging
import org.southtyrol.transit.model.ChargingConnector
import org.southtyrol.transit.model.ChargingGroup
import org.southtyrol.transit.model.ChargingStatus
import org.southtyrol.transit.model.ConnectorType
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.TransitZone
import java.time.Instant
import java.util.Locale

/** mobility-charging: the charging-station tile, the same in every theme (see MarkerImages). */
private val ChargingColor = Color(0xFFAD1457)
private val FastBadgeColor = Color(0xFFF2C048)
private val OnFastBadgeColor = Color(0xFF261900)

/** More points than this in one group read better as the count alone than as a strip of squares. */
private const val MAX_STATUS_SQUARES = 24

/**
 * A charging station: points grouped by connector, power and cable, each group with its free count and a
 * square per point. Operators report a status only when it changes, so the time shown is that report's,
 * never "Live".
 */
@Composable
fun ChargingSheet(p: MobilityPoint, now: Instant) {
    val c = p.charging ?: return
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(ChargingColor, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.EvStation, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column {
            Text(p.name.ifEmpty { stringResource(R.string.charging_station) }, style = MaterialTheme.typography.headlineSmall)
            Text(
                listOfNotNull(stringResource(R.string.charging_station), p.provider.ifEmpty { null }, c.city).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
        }
    }

    val available = p.available
    val updatedAt = p.updatedAt
    if (c.hasStatus && available != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.charging_points_free, available, available, c.points.size), style = MaterialTheme.typography.titleLarge)
            if (updatedAt != null) InfoPill(stringResource(R.string.charging_status_as_of, reportTime(updatedAt, now)), colors.surfaceContainerHighest, icon = Icons.Rounded.Schedule)
        }
    } else {
        Text(stringResource(R.string.mobility_no_current), style = MaterialTheme.typography.bodyLarge)
    }

    val groups = Charging.groups(c.points)
    if (groups.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            groups.forEachIndexed { i, g -> ChargingGroupRow(g, groupShape(i, groups.size)) }
        }
    }

    val pills = listOfNotNull(
        if (c.openAllDay) stringResource(R.string.charging_open_all_day) else null,
        if (c.privateWithPublicAccess) stringResource(R.string.charging_private_public) else null,
        if (c.rfid) stringResource(R.string.charging_rfid) else null,
    )
    if (c.fast || pills.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Spelled out first: the map's bolt badge alone is easy to miss. Its leading badge repeats that bolt.
            if (c.fast) InfoPill(stringResource(R.string.charging_fast), colors.surfaceContainerHighest, leading = { FastBadge() })
            pills.forEach { InfoPill(it, colors.surfaceContainerHighest) }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DirectionsButton(p.point)
        c.paymentUrl?.let { url ->
            val uri = LocalUriHandler.current
            FilledTonalButton(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.charging_how_to_pay))
            }
        }
    }
}

@Composable
private fun ChargingGroupRow(g: ChargingGroup, shape: Shape) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val connector = g.connector
    val title = connector?.let { connectorTitle(it) } ?: pluralStringResource(R.plurals.charging_points, g.statuses.size, g.statuses.size)
    val detail = connector?.let { connectorDetail(it) } ?: stringResource(R.string.charging_connector_unknown)
    val free = if (g.hasStatus) pluralStringResource(R.plurals.charging_free, g.free, g.free) else null
    val of = if (g.hasStatus) stringResource(R.string.charging_of, g.statuses.size) else null
    val broken = if (g.outOfOrder > 0) pluralStringResource(R.plurals.charging_out_of_order, g.outOfOrder, g.outOfOrder) else null
    // TalkBack hears the row as one sentence; the squares only repeat the counts.
    val sentence = listOfNotNull(title, detail, listOfNotNull(free, of).joinToString(" ").ifEmpty { null }, broken).joinToString(", ")
    Surface(shape = shape, color = colors.surfaceContainer, modifier = Modifier.clearAndSetSemantics { contentDescription = sentence }) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(colors.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(
                    connector?.let { connectorShort(it.type) } ?: g.statuses.size.toString(),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer,
                    maxLines = 2, textAlign = TextAlign.Center,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (g.hasStatus && g.statuses.size <= MAX_STATUS_SQUARES) {
                    FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        g.statuses.sortedBy { it.ordinal }.forEach { StatusSquare(it) }
                    }
                }
                if (broken != null) {
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = status.cancelled, modifier = Modifier.size(14.dp))
                        Text(broken, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = status.cancelled)
                    }
                }
            }
            if (free != null && of != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(free, style = MaterialTheme.typography.titleMedium, color = if (g.free > 0) status.live else colors.onSurfaceVariant)
                    Text(of, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** The map marker's fast-charging badge (mobility-badge with on-mobility-badge), at pill-icon size. */
@Composable
private fun FastBadge() {
    Box(Modifier.size(16.dp).background(FastBadgeColor, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = OnFastBadgeColor, modifier = Modifier.size(12.dp))
    }
}

/** One point: free green, in use amber, out of order an outlined red square, unknown grey. */
@Composable
private fun StatusSquare(s: ChargingStatus) {
    val status = LocalStatusColors.current
    val shape = RoundedCornerShape(3.dp)
    val m = Modifier.size(10.dp)
    when (s) {
        ChargingStatus.FREE -> Box(m.background(status.live, shape))
        ChargingStatus.IN_USE -> Box(m.background(status.delayed, shape))
        ChargingStatus.OUT_OF_ORDER -> Box(m.border(2.dp, status.cancelled, shape))
        ChargingStatus.UNKNOWN -> Box(m.background(status.stale, shape))
    }
}

@Composable
private fun connectorTitle(c: ChargingConnector): String {
    val name = connectorName(c.type)
    return c.powerKw?.let { "$name · ${power(it, Format.locale())} kW" } ?: name
}

@Composable
private fun connectorDetail(c: ChargingConnector): String = listOfNotNull(
    if (c.dc) "DC" else "AC",
    when (c.cable) {
        true -> stringResource(R.string.charging_cable)
        false -> stringResource(R.string.charging_socket)
        null -> null
    },
).joinToString(" · ")

@Composable
private fun connectorName(type: ConnectorType): String = when (type) {
    ConnectorType.CCS -> "CCS"
    ConnectorType.CHADEMO -> "CHAdeMO"
    ConnectorType.TYPE_2 -> stringResource(R.string.charging_type2)
    ConnectorType.TYPE_1 -> stringResource(R.string.charging_type1)
    ConnectorType.DOMESTIC -> stringResource(R.string.charging_domestic)
    ConnectorType.OTHER -> stringResource(R.string.charging_other)
}

/** The glyph's label: the connector's short name; household and unnamed sockets read "AC". */
@Composable
private fun connectorShort(type: ConnectorType): String = when (type) {
    ConnectorType.DOMESTIC, ConnectorType.OTHER -> "AC"
    else -> connectorName(type)
}

/** 22 kW, 7,4 kW: whole numbers without a decimal, else one, in the app's locale. */
private fun power(kw: Double, locale: Locale): String =
    if (kw % 1.0 == 0.0) kw.toInt().toString() else String.format(locale, "%.1f", kw)

/** A report from today shows its time; an older one its date and time. */
@Composable
private fun reportTime(at: Instant, now: Instant): String =
    if (at.atZone(TransitZone).toLocalDate() == now.atZone(TransitZone).toLocalDate()) Format.time(at) else Format.dateTime(at)
