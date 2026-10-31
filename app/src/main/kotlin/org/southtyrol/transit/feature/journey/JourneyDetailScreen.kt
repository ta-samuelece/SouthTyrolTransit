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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
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
import org.southtyrol.transit.R
import org.southtyrol.transit.feature.common.LegStops
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.feature.follow.StartFollowSheet
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.Progress
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
import org.southtyrol.transit.model.Approach
import org.southtyrol.transit.model.Approaches
import org.southtyrol.transit.model.Bikes
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.LegDistance
import org.southtyrol.transit.model.RouteProgress
import org.southtyrol.transit.model.RunPosition
import org.southtyrol.transit.model.RunPositions
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.ui.Navigator
import java.time.Duration
import java.time.Instant

@Composable
fun JourneyDetailScreen(navigator: Navigator, viewModel: JourneyDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val found = (state as? DetailState.Found)?.journey
    val headerNow = rememberNow()
    var showStart by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            // The journey's times are its name: they are the title, duration and changes the subtitle.
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        found?.let { "${Format.time(it.bestDeparture)} – ${Format.time(it.bestArrival)}" } ?: stringResource(R.string.journey_title),
                        maxLines = 1,
                    )
                },
                subtitle = found?.let { j ->
                    { Text(listOf(Format.duration(j.duration), pluralStringResource(R.plurals.changes, j.changes, j.changes)).joinToString(" · ")) }
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    found?.let { FreshnessIndicator(if (it.hasRealtime) Freshness.LIVE else Freshness.SCHEDULED, null, headerNow, Modifier.padding(end = 12.dp), compact = true) }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            // Following makes sense until the journey has arrived.
            val followedId by viewModel.followedId.collectAsStateWithLifecycle()
            if (found != null && headerNow.isBefore(found.bestArrival)) {
                val following = followedId == found.id
                androidx.compose.material3.ExtendedFloatingActionButton(
                    onClick = { if (following) navigator.follow() else showStart = true },
                    icon = { Icon(Icons.Rounded.Navigation, contentDescription = null) },
                    text = { Text(stringResource(if (following) R.string.follow_following else R.string.follow_start)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                DetailState.Loading -> TransitLoading(contained = true)
                DetailState.Missing -> MessageState(stringResource(R.string.journey_missing), stringResource(R.string.journey_missing_hint), action = stringResource(R.string.action_back), onAction = navigator::back)
                is DetailState.Error -> ErrorState(s.error, onRetry = viewModel::load)
                // Room below the list for the Start button.
                is DetailState.Found -> JourneyDetailContent(s.journey, navigator, Modifier.fillMaxSize(), showHeader = false, bottomPadding = 88.dp, withBike = viewModel.withBike)
            }
        }
    }
    if (showStart && found != null) {
        StartFollowSheet(
            journey = found,
            onDismiss = { showStart = false },
            onStart = { useLocation ->
                showStart = false
                val started = viewModel.startFollowing(found, useLocation)
                if (started) navigator.follow()
                started
            },
        )
    }
}

/**
 * Rich itinerary: header, route map, leg timeline with transfers, then fare information. The times
 * header is left out ([showHeader] false) where the top app bar already shows them. [withBike]: the search
 * was for travelling with a bike, so a leg whose bike rule the timetable does not give gets a warning.
 */
@Composable
fun JourneyDetailContent(journey: Journey, navigator: Navigator, modifier: Modifier = Modifier, showHeader: Boolean = true, bottomPadding: androidx.compose.ui.unit.Dp = 16.dp, withBike: Boolean = false) {
    val now = rememberNow(10_000)
    val live = hiltViewModel<JourneyLiveViewModel, JourneyLiveViewModel.Factory>(key = "live:" + journey.id) { it.create(journey) }
    val legRuns by live.legRuns.collectAsStateWithLifecycle()
    val matched by live.matched.collectAsStateWithLifecycle()
    PollWhileVisible(20_000) { live.poll() }
    val map = rememberJourneyMap(journey, legRuns, now)
    val mapState = org.southtyrol.transit.feature.common.rememberExpandableMapState()
    org.southtyrol.transit.feature.common.ExpandableMapPage(mapState, map.hasLines, map.content, map.camera, modifier) { listModifier ->
    LazyColumn(listModifier, contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeader) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${Format.time(journey.bestDeparture)} – ${Format.time(journey.bestArrival)}", style = TimeStyles.hero, modifier = Modifier.semantics { heading() })
                }
                Text(
                    listOf(Format.duration(journey.duration), pluralStringResource(R.plurals.changes, journey.changes, journey.changes)).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only without a top bar of its own (the detail pane beside the results); the screen's bar shows all this.
                FreshnessIndicator(if (journey.hasRealtime) Freshness.LIVE else Freshness.SCHEDULED, null, now)
            }
        }
        if (map.hasLines && !mapState.expanded) item {
            org.southtyrol.transit.feature.common.CompactMapCard(mapState, map.content, map.camera, Modifier.fillMaxWidth().height(220.dp))
        }
        itemsIndexed(journey.legs, contentType = { _, leg -> if (leg.mode == TransportMode.WALK) "walk" else "transit" }) { index, leg ->
            val previous = journey.legs.getOrNull(index - 1)
            if (previous != null && previous.mode.isTransit && leg.mode.isTransit) TransferRow(Duration.between(previous.bestArrival, leg.bestDeparture))
            if (leg.mode == TransportMode.WALK) WalkLeg(leg, now)
            else TransitLeg(leg, now, onStop = { gid -> navigator.stop(gid) }, run = legRuns[index], bikeWarning = withBike && matched && legRuns[index]?.trip?.bikes.let { it == null || it == Bikes.UNKNOWN })
        }
        item { FareSection(journey) }
    }
    }
}

/** A journey's map: its legs (dimmed up to where they are now), endpoints, and approaching or ridden vehicles. */
internal class JourneyMap(val content: MapContent, val camera: CameraRequest.Fit, val hasLines: Boolean)

/**
 * The map of [journey] at [now], with the matched runs [legRuns] by leg index; [extra] adds markers (e.g.
 * the user); [focus] is the leg index the camera shows (the whole journey when null).
 */
@Composable
internal fun rememberJourneyMap(journey: Journey, legRuns: Map<Int, TripDetail>, now: Instant, extra: List<MapMarker> = emptyList(), focus: Int? = null): JourneyMap {
    val approaches = remember(journey, legRuns, now) { legApproaches(journey, legRuns, now) }
    val legLines = remember(journey) {
        journey.legs.mapIndexedNotNull { i, leg ->
            val points = leg.geometry.ifEmpty { listOfNotNull(leg.from.point) + leg.intermediate.mapNotNull { it.place.point } + listOfNotNull(leg.to.point) }
            if (points.size < 2) null else i to MapPolyline("leg$i", points, if (leg.mode == TransportMode.WALK) 0x78909C else ModeColors.container(leg.mode), if (leg.mode == TransportMode.WALK) 3f else 6f, dashed = leg.mode == TransportMode.WALK)
        }
    }
    val lines = remember(legLines) { legLines.map { it.second } }
    val endpoints = remember(journey) {
        listOfNotNull(
            journey.legs.first().from.point?.let { MapMarker("from", it, MarkerKind.ORIGIN, "A") },
            journey.legs.last().to.point?.let { MapMarker("to", it, MarkerKind.DESTINATION, "B") },
        )
    }
    val mapContent = remember(legLines, endpoints, approaches, legRuns, now, extra) {
        // The approach is drawn under the legs; each vehicle carries its line badge.
        val ahead = approaches.map { (i, a) -> MapPolyline("approach$i", a.approach.path, ModeColors.container(journey.legs[i].mode), 4f, dashed = true) }
        val vehicles = approaches.map { (i, a) ->
            val leg = journey.legs[i]
            val stale = a.run.vehicle?.let { FreshnessPolicy.vehicle(it.timestamp, now) == Freshness.STALE } ?: false
            MapMarker("v:leg$i", a.approach.position.point, MarkerKind.VEHICLE, leg.line, leg.mode, ModeColors.container(leg.mode),
                // The feed sends no heading: the direction of the route still to travel to the boarding stop.
                a.run.vehicle?.bearing ?: org.southtyrol.transit.model.VehicleHeadings.alongPath(a.approach.position.point, a.approach.path),
                stale = a.approach.position.estimated || stale,
            )
        }
        // Each leg dimmed up to where its vehicle (or the walker) is now; the leg being ridden shows its vehicle.
        val progress = legLines.map { (i, line) -> i to legProgress(journey.legs[i], line, legRuns[i], now) }
        val riding = progress.mapNotNull { (i, p) ->
            val leg = journey.legs[i]
            val position = p.position?.takeIf { leg.mode.isTransit && i !in approaches } ?: return@mapNotNull null
            MapMarker(
                "ride:leg$i", position.point, MarkerKind.VEHICLE, leg.line, leg.mode, ModeColors.container(leg.mode),
                legRuns[i]?.vehicle?.bearing ?: org.southtyrol.transit.model.VehicleHeadings.alongPath(position.point, legLines.firstOrNull { it.first == i }?.second?.points.orEmpty()),
                stale = position.estimated,
            )
        }
        MapContent(pois = endpoints + extra, vehicles = vehicles + riding, lines = ahead + progress.flatMap { it.second.lines })
    }
    // An approaching vehicle is still before the boarding stop, so outside the journey's own bounds:
    // re-fit once when a leg's vehicle first appears, to include it and the route it still travels.
    // With a [focus] leg only that leg (and its approaching vehicle) is fitted, re-fitting as the focus moves.
    val camera = remember(journey.id, approaches.keys, focus) {
        val focused = focus?.let { f -> legLines.firstOrNull { it.first == f }?.second?.points.orEmpty() + approaches[f]?.approach?.path.orEmpty() }
        CameraRequest.Fit(focused?.takeIf { it.isNotEmpty() } ?: (lines.flatMap { it.points } + approaches.values.flatMap { it.approach.path }))
    }
    return JourneyMap(mapContent, camera, lines.isNotEmpty())
}

/** A transit leg's vehicle on its way to the boarding stop, with the run it was matched to. */
private data class LegApproach(val run: TripDetail, val approach: Approach)

/** Approaches by leg index for the legs whose vehicle can be placed right now. */
private fun legApproaches(journey: Journey, runs: Map<Int, TripDetail>, now: Instant): Map<Int, LegApproach> =
    runs.mapNotNull { (i, run) ->
        val leg = journey.legs.getOrNull(i) ?: return@mapNotNull null
        val boarding = Approaches.boardingIndex(run.stops, leg.from.stopGlobalId, leg.departure) ?: return@mapNotNull null
        // Without GTFS-RT predictions, the planner's delay at the boarding stop moves the estimate.
        val stops = Approaches.withBoardingDelay(run.stops, boarding, leg.departureDelaySeconds)
        Approaches.of(stops, run.shape, boarding, run.vehicle?.point, now)?.let { i to LegApproach(run, it) }
    }.toMap()

/** A leg's map lines split at its progress, and where its vehicle (or walker) is while under way. */
private class LegProgressLines(val lines: List<MapPolyline>, val position: RunPosition?)

private fun legProgress(leg: Leg, line: MapPolyline, run: TripDetail?, now: Instant): LegProgressLines {
    val fraction = RouteProgress.legFraction(leg, now)
    if (fraction <= 0f) return LegProgressLines(listOf(line), null)
    if (fraction >= 1f) return LegProgressLines(listOf(line.copy(opacity = Progress.PASSED_ALPHA)), null)
    // Under way: the matched run's GPS fix when there is one, otherwise estimated from the leg's own times.
    val stops = RouteProgress.legStops(leg)
    val estimate = RunPositions.estimate(stops, line.points, now)
    val gps = run?.vehicle?.point?.takeIf { leg.mode.isTransit }
    val position = gps?.let { RunPositions.locate(stops, line.points, it, now) } ?: estimate
    val split = RouteProgress.of(line.points, stops, position, now) ?: return LegProgressLines(listOf(line), position)
    return LegProgressLines(
        listOfNotNull(
            split.done.takeIf { it.size >= 2 }?.let { line.copy(id = line.id + "-done", points = it, opacity = Progress.PASSED_ALPHA) },
            split.ahead.takeIf { it.size >= 2 }?.let { line.copy(points = it) },
        ),
        position,
    )
}

@Composable
internal fun TransferRow(wait: Duration) {
    Row(Modifier.padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.SyncAlt, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.journey_transfer, Format.duration(wait)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
    }
}

private const val WALK_DOTS = 4

/** Walking is visually lighter than transit: a dotted rail and one line of text. */
@Composable
internal fun WalkLeg(leg: Leg, now: Instant) {
    val walked = (RouteProgress.legFraction(leg, now) * WALK_DOTS).toInt()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 12.dp)) {
        Column(Modifier.width(24.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            repeat(WALK_DOTS) { dot ->
                val color = MaterialTheme.colorScheme.outline
                Box(Modifier.padding(vertical = 3.dp).size(4.dp).clip(CircleShape).background(if (dot < walked) color.copy(alpha = Progress.PASSED_ALPHA) else color))
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

/**
 * A transit leg's card. [run] is the timetable run it was matched to, if any: it adds the train number,
 * the leg's distance and the bike rule. [bikeWarning] says the bike rule is not known.
 */
@Composable
internal fun TransitLeg(leg: Leg, now: Instant, onStop: (String) -> Unit, run: TripDetail? = null, bikeWarning: Boolean = false) {
    var expanded by rememberSaveable(leg.departure.epochSecond, leg.line) { mutableStateOf(false) }
    val status = LocalStatusColors.current
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineBadge(leg.line, leg.mode, large = true)
                Column(Modifier.weight(1f)) {
                    Text(leg.destination.ifBlank { leg.to.name }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val trainNumber = run?.trip?.shortName.orEmpty().takeIf { n -> n.isNotBlank() && !leg.lineName.contains(n.substringAfterLast(' ')) }.orEmpty()
                    val sub = listOf(trainNumber, leg.lineName.takeIf { it != leg.line }.orEmpty(), leg.operator).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // From the matched timetable run: how far this leg goes and whether bikes ride along.
            val distance = run?.let { LegDistance.of(it, leg) }
            val bikes = run?.trip?.bikes ?: Bikes.UNKNOWN
            if (distance != null || bikes != Bikes.UNKNOWN) {
                val pill = MaterialTheme.colorScheme.surfaceContainerHighest
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (distance != null) org.southtyrol.transit.feature.common.InfoPill(Format.distance(distance), pill, icon = Icons.Rounded.Straighten)
                    org.southtyrol.transit.feature.common.BikesPill(bikes, pill)
                }
            }
            if (bikeWarning) androidx.compose.material3.Surface(
                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.journey_bikes_unknown, "${Format.mode(leg.mode)} ${leg.line}".trim()), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (leg.cancelled) StatusPill(stringResource(org.southtyrol.transit.design.R.string.ds_cancelled), status.cancelled, Icons.Rounded.Warning)
            // The stops as the trip screen draws a run: rail, dots, passed ones dimmed. Above them, the matched
            // run before the boarding stop; trains report no position, so they get its times only.
            val before = remember(leg, run, now) {
                run?.let { Approaches.beforeBoarding(leg, it.stops, it.shape, it.vehicle?.point, now, placeVehicle = leg.mode != TransportMode.TRAIN) }
            }
            val stale = run?.vehicle?.let { FreshnessPolicy.vehicle(it.timestamp, now) == Freshness.STALE } ?: false
            LegStops(leg, now, expanded, onToggle = { expanded = !expanded }, onStop = onStop, before = before, beforeStale = stale)
            leg.notices.forEach { notice ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Warning, contentDescription = null, tint = status.delayed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(notice, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Fares exactly as returned by the planner; nothing at all when it reports none. */
@Composable
private fun FareSection(journey: Journey) {
    val fare = journey.fare
    if (fare != null && (fare.single != null || fare.valueCard != null)) {
        SectionHeader(stringResource(R.string.fare_title))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fare.single?.let { FareRow(stringResource(R.string.fare_single), "${it.currency} ${it.price}") }
            fare.valueCard?.let { FareRow(stringResource(R.string.fare_value_card), "${it.currency} ${it.price}") }
            Text(stringResource(R.string.fare_reported_by_planner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
