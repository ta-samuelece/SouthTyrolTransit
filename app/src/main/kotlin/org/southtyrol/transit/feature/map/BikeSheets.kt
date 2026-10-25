@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ElectricBike
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.Roofing
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.R
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.feature.common.InfoPill
import org.southtyrol.transit.feature.common.groupShape
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.MobilityPoint
import java.time.Instant

/** mobility-bike-parking and mobility-charging (shared by e-bike sharing), the same in every theme (see MarkerImages). */
private val BikeParkingColor = Color(0xFF005662)
private val EbikeColor = Color(0xFFAD1457)

/**
 * A Bicibox site: individual boxes or a shared depot, its free places (split by socket where it has
 * places with one), its facilities and, when it is full, the nearest site nearby that still has space.
 */
@Composable
fun BikeParkingSheet(p: MobilityPoint, now: Instant, nearest: Pair<MobilityPoint, Double>?, onShow: (MobilityPoint) -> Unit) {
    val b = p.bikeParking ?: return
    val colors = MaterialTheme.colorScheme
    val kindLabel = stringResource(if (b.depot) R.string.bike_boxes_depot else R.string.bike_boxes_individual)
    SheetHeader(
        title = p.name.ifEmpty { stringResource(R.string.map_layer_bike_boxes) },
        subtitle = listOf(stringResource(R.string.map_layer_bike_boxes), kindLabel, p.provider).filter { it.isNotEmpty() }.joinToString(" · "),
        color = BikeParkingColor,
    ) { BoxedBike() }

    val available = p.available
    val capacity = p.capacity
    val live = p.freshness == Freshness.LIVE
    when {
        p.outOfService -> Text(stringResource(R.string.mobility_out_of_service), style = MaterialTheme.typography.bodyLarge)
        live && available != null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            val count = when {
                capacity == null -> pluralStringResource(R.plurals.charging_free, available, available)
                b.depot -> pluralStringResource(R.plurals.bike_places_free_of, capacity, available, capacity)
                else -> pluralStringResource(R.plurals.bike_boxes_free_of, capacity, available, capacity)
            }
            Text(if (available == 0) stringResource(R.string.bike_parking_full) + " · " + count else count, style = MaterialTheme.typography.titleLarge)
            FreshnessIndicator(p.freshness, p.updatedAt, now, compact = true)
        }
        else -> {
            Text(stringResource(R.string.mobility_no_current), style = MaterialTheme.typography.bodyLarge)
            FreshnessIndicator(p.freshness, p.updatedAt, now, compact = true)
        }
    }

    // Split only where the site has places with a socket: today's sites have none, and a row of "0 of 0" says nothing.
    val regular = b.regularFree
    val electric = b.electricFree
    if (live && !p.outOfService && b.electricPlaces > 0 && regular != null && electric != null && capacity != null) {
        val plain = stringResource(if (b.depot) R.string.bike_parking_places_without_socket else R.string.bike_parking_without_socket)
        val sockets = stringResource(if (b.depot) R.string.bike_parking_places_with_socket else R.string.bike_parking_with_socket)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CountRow(Icons.Rounded.PedalBike, stringResource(R.string.bike_parking_bikes), plain, regular, capacity - b.electricPlaces, groupShape(0, 2))
            CountRow(Icons.Rounded.ElectricBike, stringResource(R.string.bike_parking_ebikes), sockets, electric, b.electricPlaces, groupShape(1, 2))
        }
    }

    nearest?.let { (site, meters) ->
        val kind = stringResource(if (site.bikeParking?.depot == true) R.string.bike_boxes_depot else R.string.bike_boxes_individual)
        StatusBanner(
            stringResource(R.string.bike_parking_nearest, "${site.name}, $kind", site.available ?: 0, Format.distance(meters)),
            kind = BannerKind.INFO, icon = Icons.Rounded.Info,
            action = stringResource(R.string.map_show_on_map), onAction = { onShow(site) },
        )
    }

    val facts = listOfNotNull(
        if (b.covered) Icons.Rounded.Roofing to stringResource(R.string.bike_parking_covered) else null,
        if (b.surveillance) Icons.Rounded.Videocam to stringResource(R.string.bike_parking_surveillance) else null,
        if (b.bookingRequired) Icons.Rounded.EventAvailable to stringResource(R.string.bike_parking_booking) else null,
    )
    if (facts.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            facts.forEach { (icon, text) -> InfoPill(text, colors.surfaceContainerHighest, icon = icon) }
        }
    }

    b.address(Format.locale().language)?.let { address ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Text(address, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }

    DirectionsButton(p.point)
}

/** An e-bike sharing station: e-bikes to rent and free docks to return one, or that it is closed. */
@Composable
fun EbikeSharingSheet(p: MobilityPoint, now: Instant) {
    val e = p.ebikeSharing ?: return
    SheetHeader(
        title = p.name.ifEmpty { stringResource(R.string.map_layer_ebike_sharing) },
        subtitle = listOf(stringResource(R.string.map_layer_ebike_sharing), p.provider).filter { it.isNotEmpty() }.joinToString(" · "),
        color = EbikeColor,
    ) { Icon(Icons.Rounded.ElectricBike, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }

    val bikes = e.bikes
    val live = p.freshness == Freshness.LIVE
    when {
        p.outOfService -> Text(stringResource(R.string.mobility_out_of_service), style = MaterialTheme.typography.bodyLarge)
        live && bikes != null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text(pluralStringResource(R.plurals.ebike_to_rent, bikes, bikes), style = MaterialTheme.typography.titleLarge)
            FreshnessIndicator(p.freshness, p.updatedAt, now, compact = true)
        }
        else -> {
            Text(stringResource(R.string.mobility_no_current), style = MaterialTheme.typography.bodyLarge)
            FreshnessIndicator(p.freshness, p.updatedAt, now, compact = true)
        }
    }

    val docks = e.docks
    val freeDocks = e.freeDocks
    if (live && !p.outOfService && bikes != null && freeDocks != null && docks != null) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CountRow(Icons.Rounded.ElectricBike, stringResource(R.string.ebike_rent_row), stringResource(R.string.ebike_rent_detail), bikes, docks, groupShape(0, 2), countText = bikes.toString())
            CountRow(Icons.Rounded.PedalBike, stringResource(R.string.ebike_docks_row), stringResource(R.string.ebike_docks_detail), freeDocks, docks, groupShape(1, 2))
        }
    }

    DirectionsButton(p.point)
}

@Composable
private fun SheetHeader(title: String, subtitle: String, color: Color, glyph: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp).background(color, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { glyph() }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The bike box glyph: a bike inside a box with a heavier lid, as on the map tile. */
@Composable
private fun BoxedBike() {
    Box(Modifier.size(width = 20.dp, height = 18.dp).border(2.dp, Color.White, RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(Color.White, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
        Icon(Icons.Rounded.PedalBike, contentDescription = null, tint = Color.White, modifier = Modifier.padding(top = 2.dp).size(13.dp))
    }
}

/**
 * One row of a grouped count list: a glyph, what is counted, and the count ("N free" unless [countText]
 * says otherwise) over "of M"; TalkBack hears the row as one sentence.
 */
@Composable
private fun CountRow(icon: ImageVector, title: String, detail: String, count: Int, of: Int, shape: Shape, countText: String? = null) {
    val colors = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    val value = countText ?: pluralStringResource(R.plurals.charging_free, count, count)
    val total = stringResource(R.string.charging_of, of)
    Surface(shape = shape, color = colors.surfaceContainer, modifier = Modifier.clearAndSetSemantics { contentDescription = "$title, $detail, $value $total" }) {
        Row(Modifier.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(colors.secondaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(value, style = MaterialTheme.typography.titleMedium, color = if (count > 0) status.live else colors.onSurfaceVariant)
                Text(total, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}
