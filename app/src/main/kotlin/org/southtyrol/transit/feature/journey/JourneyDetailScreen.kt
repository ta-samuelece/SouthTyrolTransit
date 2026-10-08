@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.journey

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusPill
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MapPolyline
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.map.TransitMap
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.ui.Navigator
import java.time.Duration
import java.time.Instant

@Composable
fun JourneyDetailScreen(navigator: Navigator, viewModel: JourneyDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journey_title)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                DetailState.Loading -> TransitLoading(contained = true)
                DetailState.Missing -> MessageState(stringResource(R.string.journey_missing), stringResource(R.string.journey_missing_hint), action = stringResource(R.string.action_back), onAction = navigator::back)
                is DetailState.Error -> ErrorState(s.error, onRetry = viewModel::load)
                is DetailState.Found -> JourneyDetailContent(s.journey, navigator, Modifier.fillMaxSize())
            }
        }
    }
}

/** Rich itinerary: header, route map, leg timeline with transfers, then fare information. */
@Composable
fun JourneyDetailContent(journey: Journey, navigator: Navigator, modifier: Modifier = Modifier) {
    val now = rememberNow()
    val dark = LocalDarkTheme.current
    val lines = remember(journey) {
        journey.legs.mapIndexedNotNull { i, leg ->
            val points = leg.geometry.ifEmpty { listOfNotNull(leg.from.point) + leg.intermediate.mapNotNull { it.place.point } + listOfNotNull(leg.to.point) }
            if (points.size < 2) null else MapPolyline("leg$i", points, if (leg.mode == TransportMode.WALK) 0x78909C else ModeColors.container(leg.mode), if (leg.mode == TransportMode.WALK) 3f else 6f, dashed = leg.mode == TransportMode.WALK)
        }
    }
    val endpoints = remember(journey) {
        listOfNotNull(
            journey.legs.first().from.point?.let { MapMarker("from", it, MarkerKind.ORIGIN, "A") },
            journey.legs.last().to.point?.let { MapMarker("to", it, MarkerKind.DESTINATION, "B") },
        )
    }
    val mapState = org.southtyrol.transit.feature.common.rememberExpandableMapState()
    val mapContent = remember(lines, endpoints) { MapContent(pois = endpoints, lines = lines) }
    val camera = remember(journey) { CameraRequest.Fit(lines.flatMap { it.points }) }
    org.southtyrol.transit.feature.common.ExpandableMapPage(mapState, lines.isNotEmpty(), mapContent, camera, modifier) { listModifier ->
    LazyColumn(listModifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${Format.time(journey.bestDeparture)} – ${Format.time(journey.bestArrival)}", style = TimeStyles.hero, modifier = Modifier.semantics { heading() })
                }
                Text(
                    listOf(Format.duration(journey.duration), pluralStringResource(R.plurals.changes, journey.changes, journey.changes)).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FreshnessIndicator(if (journey.hasRealtime) Freshness.LIVE else Freshness.SCHEDULED, null, now)
            }
        }
        if (lines.isNotEmpty() && !mapState.expanded) item {
            org.southtyrol.transit.feature.common.CompactMapCard(mapState, mapContent, camera, Modifier.fillMaxWidth().height(220.dp))
        }
        itemsIndexed(journey.legs) { index, leg ->
            val previous = journey.legs.getOrNull(index - 1)
            if (previous != null && previous.mode.isTransit && leg.mode.isTransit) TransferRow(Duration.between(previous.bestArrival, leg.bestDeparture))
            if (leg.mode == TransportMode.WALK) WalkLeg(leg) else TransitLeg(leg, now, onStop = { gid -> navigator.stop(gid) })
        }
        item { FareSection(journey) }
    }
    }
}

@Composable
private fun TransferRow(wait: Duration) {
    Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.journey_transfer, Format.duration(wait)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** Walking is visually lighter than transit: a dotted rail and one line of text. */
@Composable
private fun WalkLeg(leg: Leg) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(4) {
                Box(Modifier.padding(vertical = 3.dp).size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
            }
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            val minutes = Duration.between(leg.departure, leg.arrival).toMinutes().toInt()
            val distance = leg.distanceMeters?.let { " · " + Format.distance(it.toDouble()) }.orEmpty()
            Text(stringResource(R.string.journey_walk_to, minutes, leg.to.name) + distance, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransitLeg(leg: Leg, now: Instant, onStop: (String) -> Unit) {
    var expanded by rememberSaveable(leg.departure.epochSecond, leg.line) { mutableStateOf(false) }
    val status = LocalStatusColors.current
    val (lineColor, _) = ModeColors.colors(leg.mode, null, null)
    ElevatedCard(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineBadge(leg.line, leg.mode, large = true)
                Column(Modifier.weight(1f)) {
                    Text(leg.destination.ifBlank { leg.to.name }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(leg.lineName.takeIf { it != leg.line }.orEmpty(), leg.operator).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (leg.cancelled) StatusPill(stringResource(org.southtyrol.transit.design.R.string.ds_cancelled), status.cancelled, Icons.Rounded.Warning)
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(6.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(lineColor))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StopLine(leg.departure, leg.predictedDeparture, leg.from.name, leg.departurePlatform, leg.cancelled, onClick = leg.from.stopGlobalId.takeIf { it.isNotBlank() }?.let { { onStop(it) } })
                    if (leg.intermediate.isNotEmpty()) {
                        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(pluralStringResource(R.plurals.intermediate_stops, leg.intermediate.size, leg.intermediate.size) + " · " + Format.duration(Duration.between(leg.departure, leg.arrival)))
                        }
                        AnimatedVisibility(expanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                leg.intermediate.forEach { stop ->
                                    val time = stop.departure ?: stop.arrival
                                    Row {
                                        Text(time?.let { Format.time(stop.predictedDeparture ?: it) } ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp), maxLines = 1)
                                        Text(stop.place.name, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    StopLine(leg.arrival, leg.predictedArrival, leg.to.name, leg.arrivalPlatform, leg.cancelled, onClick = leg.to.stopGlobalId.takeIf { it.isNotBlank() }?.let { { onStop(it) } })
                }
            }
            leg.notices.forEach { notice ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = status.delayed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (leg.realtime) FreshnessIndicator(Freshness.LIVE, null, now)
        }
    }
}

@Composable
private fun StopLine(scheduled: Instant, predicted: Instant?, name: String, platform: String, cancelled: Boolean, onClick: (() -> Unit)?) {
    val changed = predicted != null && predicted != scheduled
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 40.dp),
    ) {
        Column(Modifier.width(88.dp)) {
            Text(Format.time(predicted ?: scheduled), style = TimeStyles.medium, maxLines = 1, softWrap = false, textDecoration = if (cancelled) TextDecoration.LineThrough else null)
            if (changed) Text(Format.time(scheduled), style = MaterialTheme.typography.labelSmall, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (platform.isNotBlank()) Text(stringResource(org.southtyrol.transit.design.R.string.ds_platform, platform), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (predicted != null) DelayLabel(Duration.between(scheduled, predicted).seconds, if (cancelled) ServiceState.CANCELLED else ServiceState.NORMAL, Freshness.LIVE)
            }
        }
    }
}

/** Fares exactly as returned by the planner; otherwise an explicit "unavailable" with guidance. */
@Composable
private fun FareSection(journey: Journey) {
    SectionHeader(stringResource(R.string.fare_title))
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val fare = journey.fare
            if (fare != null && (fare.single != null || fare.valueCard != null)) {
                fare.single?.let { FareRow(stringResource(R.string.fare_single), "${it.currency} ${it.price}") }
                fare.valueCard?.let { FareRow(stringResource(R.string.fare_value_card), "${it.currency} ${it.price}") }
                Text(stringResource(R.string.fare_reported_by_planner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.fare_unavailable), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun FareRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.Unspecified)
    }
}
