package org.southtyrol.transit.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.R
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.Progress
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.delayDescription
import org.southtyrol.transit.design.icon
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.TripStop
import java.time.Duration
import java.time.Instant
import org.southtyrol.transit.design.R as DesignR

/**
 * A planner leg's calls (boarding, intermediate stops, alighting) as timeline rows: every call, unlike
 * [org.southtyrol.transit.model.RouteProgress.legStops], which needs coordinates for the map. A call
 * without a time takes the previous one's; platforms come from the leg.
 */
fun legCalls(leg: Leg): List<TripStop> {
    val state = if (leg.cancelled) ServiceState.CANCELLED else ServiceState.NORMAL
    fun call(place: Place, sequence: Int, arrival: Instant, departure: Instant, predictedArrival: Instant?, predictedDeparture: Instant?, platform: String) =
        TripStop(Stop(place.id, place.name, place.point ?: Point(0.0, 0.0), stationKey = place.stopGlobalId, platform = platform), sequence, arrival, departure, predictedArrival, predictedDeparture, state)
    var last = leg.departure
    return buildList {
        add(call(leg.from, 0, leg.departure, leg.departure, leg.predictedDeparture, leg.predictedDeparture, leg.departurePlatform))
        leg.intermediate.forEachIndexed { i, s ->
            val arrival = s.arrival ?: s.departure ?: last
            val departure = s.departure ?: arrival
            last = departure
            add(call(s.place, i + 1, arrival, departure, s.predictedArrival, s.predictedDeparture, s.platform))
        }
        add(call(leg.to, leg.intermediate.size + 1, leg.arrival, leg.arrival, leg.predictedArrival, leg.predictedArrival, leg.arrivalPlatform))
    }
}

/**
 * The stops of a planner [leg] as the trip screen draws a run: times, rail and dots in the line's colour,
 * passed stops dimmed and, while the leg is under way, its vehicle before the next stop (estimated from
 * the times). Collapsed, only the first and last calls show, with a pill on the rail that opens the rest.
 * [nextNote] and [lastNote] add a line under the next and the last stop.
 */
@Composable
fun LegStops(
    leg: Leg, now: Instant, expanded: Boolean, onToggle: (() -> Unit)?, onStop: (String) -> Unit,
    nextNote: String? = null, lastNote: String? = null, live: Boolean = true,
) {
    val calls = remember(leg) { legCalls(leg) }
    // The first call still ahead; calls before it are passed. Under way only between the first and the last.
    val nextIndex = calls.indexOfFirst { (it.predictedDeparture ?: it.scheduledDeparture).isAfter(now) }.let { if (it < 0) calls.size else it }
    val underWay = nextIndex in 1..calls.lastIndex
    val color = ModeColors.container(leg.mode)
    val shown = if (expanded || calls.size <= 2) calls.indices.toList() else listOf(0, calls.lastIndex)
    Column {
        shown.forEach { i ->
            val call = calls[i]
            StopTimelineRow(
                call, first = i == 0, last = i == calls.lastIndex, passed = i < nextIndex, next = underWay && i == nextIndex, color = color,
                vehicle = if (underWay && i == nextIndex && i in shown) VehicleMarker.BEFORE_STOP else VehicleMarker.NONE,
                mode = leg.mode, estimated = true,
                onClick = { if (call.stop.stationKey.isNotBlank()) onStop(call.stop.stationKey) },
                note = when {
                    i == calls.lastIndex -> lastNote
                    underWay && i == nextIndex -> nextNote
                    else -> null
                },
                live = live,
            )
            if (i == 0 && calls.size > 2 && onToggle != null) {
                StopsPill(calls.size - 2, Duration.between(leg.departure, leg.arrival), expanded, Color(0xFF000000 or color).copy(alpha = if (nextIndex > 1) Progress.PASSED_ALPHA else 1f), onToggle)
            }
        }
    }
}

/** The tonal "N stops · 32 min" pill, sitting on the rail between the first and the next row shown. */
@Composable
private fun StopsPill(count: Int, duration: Duration, expanded: Boolean, rail: Color, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Spacer(Modifier.width(88.dp))
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(rail))
        }
        Spacer(Modifier.width(12.dp))
        // 40 dp tall, inside a 48 dp touch target.
        FilledTonalButton(
            onClick = onToggle,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
            modifier = Modifier.padding(vertical = 4.dp).heightIn(min = 40.dp),
        ) {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(pluralStringResource(R.plurals.intermediate_stops, count, count) + " · " + Format.duration(duration))
        }
    }
}

/** Where the vehicle is drawn on a stop timeline: between the previous stop and this one, or at this stop. */
enum class VehicleMarker { NONE, BEFORE_STOP, AT_STOP }

/**
 * One call of a run on a rail in the line's [color] (trip screen, followed journey): time (struck through
 * when it moved), dot, stop name and delay. [passed] calls and rail are dimmed ([Progress.PASSED_ALPHA]),
 * [next] is emphasised, [note] adds a line under the name ("Next stop", "Get off"). Predictions count as
 * live only while [live]: a prediction from a feed gone stale is shown, but not as a live delay.
 */
@Composable
fun StopTimelineRow(
    stop: TripStop, first: Boolean, last: Boolean, passed: Boolean, next: Boolean, color: Long,
    vehicle: VehicleMarker, mode: TransportMode, estimated: Boolean, onClick: () -> Unit,
    note: String? = null, live: Boolean = true,
) {
    val lineColor = Color(0xFF000000 or color)
    val skipped = stop.state == ServiceState.SKIPPED || stop.state == ServiceState.CANCELLED
    val scheduled = if (last) stop.scheduledArrival else stop.scheduledDeparture
    val predicted = if (last) stop.predictedArrival else stop.predictedDeparture
    val timeText = Format.time(predicted ?: scheduled)
    val delaySeconds = predicted?.let { Duration.between(scheduled, it).seconds }
    val delayText = delayDescription(delaySeconds, stop.state)
    val vehicleText = if (vehicle != VehicleMarker.NONE) stringResource(if (vehicle == VehicleMarker.AT_STOP) R.string.trip_vehicle_at_stop else R.string.trip_vehicle_approaching) else null
    val passedText = if (passed) stringResource(R.string.stop_passed) else null
    val railAlpha = if (passed) Progress.PASSED_ALPHA else 1f
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(stop.stop.name, timeText, delayText, note, vehicleText).joinToString(", ")
                if (passedText != null) stateDescription = passedText
            },
    ) {
        Column(Modifier.width(88.dp).padding(vertical = 8.dp)) {
            Text(timeText, style = TimeStyles.medium, maxLines = 1, softWrap = false, textDecoration = if (skipped) TextDecoration.LineThrough else null)
            if (predicted != null && predicted != scheduled) Text(Format.time(scheduled), style = MaterialTheme.typography.labelSmall, textDecoration = TextDecoration.LineThrough)
        }
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(4.dp).weight(1f).background(if (first) Color.Transparent else lineColor.copy(alpha = railAlpha)))
                Box(
                    Modifier.size(if (next) 18.dp else 12.dp).clip(CircleShape)
                        .background(if (next) lineColor else MaterialTheme.colorScheme.surface)
                        .border(3.dp, lineColor.copy(alpha = railAlpha), CircleShape),
                )
                Box(Modifier.width(4.dp).weight(1f).background(if (last) Color.Transparent else lineColor.copy(alpha = railAlpha)))
            }
            // The vehicle itself: on the line just before this stop (on its way) or on this stop (dwelling).
            if (vehicle != VehicleMarker.NONE) {
                Surface(
                    shape = CircleShape,
                    color = lineColor.copy(alpha = if (estimated) 0.85f else 1f),
                    contentColor = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier.align(if (vehicle == VehicleMarker.AT_STOP) Alignment.Center else Alignment.TopCenter)
                        .then(if (vehicle == VehicleMarker.AT_STOP) Modifier else Modifier.offset(y = (-12).dp)).size(24.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(mode.icon(), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(stop.stop.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (next) FontWeight.SemiBold else FontWeight.Normal, color = if (passed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            if (note != null) Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stop.stop.platform.isNotBlank()) Text(stringResource(DesignR.string.ds_platform_short, stop.stop.platform), style = MaterialTheme.typography.labelMedium)
                DelayLabel(delaySeconds, stop.state, if (predicted != null && live) Freshness.LIVE else Freshness.SCHEDULED)
            }
        }
    }
}
