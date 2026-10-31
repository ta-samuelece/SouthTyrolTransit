@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.trip

import org.southtyrol.transit.feature.common.StopTimelineRow
import org.southtyrol.transit.feature.common.VehicleMarker
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.remember
import org.southtyrol.transit.design.icon
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import org.southtyrol.transit.feature.follow.FollowRideViewModel
import org.southtyrol.transit.feature.follow.FollowingRideCard
import org.southtyrol.transit.feature.follow.canFollow
import org.southtyrol.transit.feature.follow.rememberRideFollower
import org.southtyrol.transit.feature.follow.rideSession
import org.southtyrol.transit.follow.FollowText
import org.southtyrol.transit.follow.RideSession
import org.southtyrol.transit.follow.RideView
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.animation.animateContentSize
import org.southtyrol.transit.model.localized
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.TripRepository
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MapPolyline
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.map.TransitMap
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.model.TripStop
import org.southtyrol.transit.ui.Navigator
import java.time.Duration
import java.time.LocalDate

sealed class TripLoad {
    data object Loading : TripLoad()
    data class Ready(val detail: TripDetail) : TripLoad()
    data object NotFound : TripLoad()
    data class Error(val error: DataError) : TripLoad()
}

@HiltViewModel(assistedFactory = TripViewModel.Factory::class)
class TripViewModel @AssistedInject constructor(
    @Assisted("trip") private val tripId: String,
    @Assisted("date") private val date: String,
    @Assisted("live") private val liveRef: String,
    private val trips: TripRepository,
    private val realtime: RealtimeRepository,
    private val language: LanguageProvider,
    private val alerts: org.southtyrol.transit.data.AlertRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory { fun create(@Assisted("trip") tripId: String, @Assisted("date") date: String, @Assisted("live") liveRef: String): TripViewModel }

    private val base = MutableStateFlow<TripLoad>(TripLoad.Loading)
    private val liveTrip = MutableStateFlow<org.southtyrol.transit.data.LiveTrip?>(null)

    /**
     * Static trip merged with realtime (GTFS-RT, then the journey planner's live times for this run);
     * re-merged whenever either arrives.
     */
    val state: StateFlow<TripLoad> = combine(base, realtime.snapshot, liveTrip) { load, snapshot, live ->
        if (load is TripLoad.Ready) TripLoad.Ready(trips.merge(load.detail, snapshot, liveTrip = live)) else load
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TripLoad.Loading)

    /** Active notices for this trip's line (or this specific run). */
    val tripAlerts: StateFlow<List<org.southtyrol.transit.model.ServiceAlert>> = combine(base, alerts.state) { load, a ->
        val detail = (load as? TripLoad.Ready)?.detail ?: return@combine emptyList()
        val now = java.time.Instant.now()
        val name = detail.route.shortName.ifBlank { detail.route.longName }
        val stopKeys = detail.stops.flatMap { listOf(it.stop.id, org.southtyrol.transit.data.GtfsFiles.stationKey(it.stop.id, "")) }.toSet()
        a.alerts.filter { alert ->
            val lineWide = alert.routes.isEmpty() && alert.lineNames.isEmpty() && alert.trips.isEmpty()
            alert.active(now) && (
                detail.trip.id in alert.trips || detail.route.id in alert.routes || (name.isNotBlank() && name in alert.lineNames) ||
                    // Same rule as the departure board: stop-wide notices apply to every run calling there.
                    (lineWide && alert.stops.any { it in stopKeys })
                )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        load()
        viewModelScope.launch { runCatching { alerts.refresh(language.current()) } }
    }

    fun load() = viewModelScope.launch {
        base.value = try {
            val detail = trips.trip(tripId, runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now(org.southtyrol.transit.model.TransitZone) }, language.current())
            if (detail == null) TripLoad.NotFound else TripLoad.Ready(detail)
        } catch (e: DataException) {
            TripLoad.Error(e.error)
        }
    }

    /** The planner reference: the one the screen was opened with, else looked up ([lookUpRef]). */
    private var ref = liveRef
    private var lastLookup: java.time.Instant? = null

    suspend fun poll() {
        trips.pollRealtime()
        if (ref.isBlank()) ref = lookUpRef()
        trips.liveTrip(ref)?.let { liveTrip.value = it }
    }

    /**
     * Finds the run on the journey planner's board when the screen was opened without a reference (from a
     * line, or a train GTFS-RT never covers). Not while GTFS-RT has an update for the run, and at most every
     * two minutes while the planner has no live time for it yet.
     */
    private suspend fun lookUpRef(): String {
        val detail = (base.value as? TripLoad.Ready)?.detail ?: return ""
        val now = java.time.Instant.now()
        if (realtime.snapshot.value.update(detail.trip.id, detail.serviceDate) != null) return ""
        if (lastLookup?.let { java.time.Duration.between(it, now) < java.time.Duration.ofMinutes(2) } == true) return ""
        lastLookup = now
        return trips.findLiveRef(detail, language.current(), now)
    }
}

@Composable
fun TripScreen(navigator: Navigator, tripId: String, serviceDate: String, liveRef: String = "") {
    val viewModel = hiltViewModel<TripViewModel, TripViewModel.Factory>(key = "$tripId|$serviceDate") { it.create(tripId, serviceDate, liveRef) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val headerNow = rememberNow()
    PollWhileVisible(20_000) { viewModel.poll() }
    // Following this run: a card on top, Stop at the bottom; else a button to start, with Undo after it.
    val res = androidx.compose.ui.platform.LocalResources.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val followModel = hiltViewModel<FollowRideViewModel>()
    val undo = stringResource(R.string.ride_undo)
    val follower = rememberRideFollower(
        onStarted = { ride ->
            scope.launch {
                val result = snackbar.showSnackbar(
                    res.getString(R.string.ride_started, FollowText.line(res, ride), ride.headsign), actionLabel = undo,
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                )
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) followModel.stop()
            }
        },
        viewModel = followModel,
    )
    val followed = follower.ride?.takeIf { it.isRun(tripId, serviceDate) }
    val followedView = followModel.rideView.collectAsStateWithLifecycle().value?.takeIf { followed != null }
    val ready = (state as? TripLoad.Ready)?.detail
    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        floatingActionButton = {
            if (ready != null && followed == null && ready.canFollow(headerNow)) androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { follower.request(ready.rideSession(liveRef)) },
                icon = { Icon(Icons.Rounded.PushPin, contentDescription = null) },
                text = { Text(stringResource(R.string.ride_follow)) },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        },
        bottomBar = {
            if (followed != null) Surface(color = MaterialTheme.colorScheme.surface) {
                androidx.compose.material3.OutlinedButton(
                    onClick = follower.stop,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize))
                    Spacer(Modifier.width(androidx.compose.material3.ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.follow_stop))
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    val detail = (state as? TripLoad.Ready)?.detail
                    if (detail != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineBadge(detail.route.shortName.ifBlank { detail.route.longName }, detail.route.mode, color = detail.route.color, textColor = detail.route.textColor)
                        Text(detail.trip.headsign, maxLines = 1)
                    } else Text(stringResource(R.string.trip_title))
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                // How current the run's data is sits in the header.
                actions = {
                    (state as? TripLoad.Ready)?.detail?.let { FreshnessIndicator(it.freshness, it.observedAt, headerNow, Modifier.padding(end = 12.dp), compact = true) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                TripLoad.Loading -> TransitLoading(contained = true)
                TripLoad.NotFound -> MessageState(stringResource(R.string.trip_not_found))
                is TripLoad.Error -> ErrorState(s.error, onRetry = { viewModel.load() })
                is TripLoad.Ready -> TripContent(
                    s.detail, navigator, viewModel.tripAlerts.collectAsStateWithLifecycle().value,
                    following = followed?.let { ride -> followedView?.let { ride to it } },
                )
            }
        }
    }
}

@Composable
private fun TripContent(
    detail: TripDetail,
    navigator: Navigator,
    alerts: List<org.southtyrol.transit.model.ServiceAlert>,
    following: Pair<RideSession, RideView>? = null,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Ticks only between flings: a tick recomposes the timeline and the map.
    val now = rememberNow(holdWhile = { listState.isScrollInProgress })
    val dark = LocalDarkTheme.current
    val color = detail.route.color ?: ModeColors.container(detail.route.mode)
    val vehicle = detail.vehicle
    val vehicleFreshness = vehicle?.let { FreshnessPolicy.vehicle(it.timestamp, now) }
    // Where the bus is: its GPS fix placed on the run when the vehicle feed has one, otherwise estimated
    // from the stop times.
    val position = remember(detail, now.epochSecond / 10) { org.southtyrol.transit.model.RideFollow.position(detail, now) }
    // The next stop: where the GPS fix puts the bus (the stop it stands at counts), otherwise the first
    // one whose (predicted) departure is still ahead.
    val nextIndex = if (position != null && !position.estimated) position.nextIndex - (if (position.atStop) 1 else 0)
    else detail.stops.indexOfFirst { (it.predictedDeparture ?: it.scheduledDeparture).isAfter(now) }
    // Distances along the run (travelled, to go, to the next stop): only for a fresh GPS fix between two stops.
    val runDistance = remember(detail, position, vehicleFreshness) {
        if (vehicleFreshness == Freshness.LIVE) org.southtyrol.transit.model.RouteDistance.of(detail.shape, detail.stops, position) else null
    }
    val firstDistance = detail.stops.firstOrNull()?.distanceMeters
    val mapState = org.southtyrol.transit.feature.common.rememberExpandableMapState()
    val routeLine = detail.shape.takeIf { it.size >= 2 } ?: detail.stops.map { it.stop.point }.filter { it.isValid }
    val hasMap = routeLine.size >= 2
    // Bring the next stop into view once, below the header (and the compact map).
    androidx.compose.runtime.LaunchedEffect(detail.trip.id) {
        // Not while following: the card on top already says where it is.
        if (nextIndex > 3 && following == null) listState.scrollToItem(nextIndex + (if (hasMap) 2 else 1) - 1)
    }
    val mapContent = remember(detail, position, color, nextIndex) {
        // Same split as the timeline: passed calls and the stretch already travelled are dimmed.
        val passedUpTo = if (nextIndex < 0) detail.stops.size else nextIndex
        val progress = org.southtyrol.transit.model.RouteProgress.of(routeLine, detail.stops, position, now)
        MapContent(
            stops = detail.stops.mapIndexed { i, it -> MapMarker(it.stop.id, it.stop.point, MarkerKind.STOP, color = color, stale = i < passedUpTo) },
            vehicles = listOfNotNull(
                position?.let { p ->
                    // The feed sends no heading: follow the route toward the next call (see VehicleHeadings).
                    val target = if (p.atStop) p.nextIndex + 1 else p.nextIndex
                    val heading = vehicle?.bearing ?: target.takeIf { it in detail.stops.indices }?.let {
                        org.southtyrol.transit.model.VehicleHeadings.toward(p.point, detail.stops.map { s -> s.stop.point }, it, detail.shape)
                    }
                    MapMarker(
                        "v:" + detail.trip.id, p.point, MarkerKind.VEHICLE, detail.route.shortName, detail.route.mode, color, heading,
                        // Estimated positions are drawn dimmed, like stale GPS fixes.
                        stale = p.estimated || vehicleFreshness == Freshness.STALE,
                    )
                },
            ),
            lines = progress?.let {
                listOfNotNull(
                    it.done.takeIf { d -> d.size >= 2 }?.let { d -> MapPolyline("trip-done", d, color, opacity = org.southtyrol.transit.design.Progress.PASSED_ALPHA) },
                    it.ahead.takeIf { a -> a.size >= 2 }?.let { a -> MapPolyline("trip", a, color) },
                )
            } ?: listOf(MapPolyline("trip", routeLine, color)),
            clusterStops = false,
        )
    }
    val camera = remember(detail.trip.id) { CameraRequest.Fit(routeLine) }

    // Notices for this run sit in one collapsed card above the timeline, which stays visible while the
    // list scrolls to the next stop; expanding it reveals every notice in full.
    org.southtyrol.transit.feature.common.ExpandableMapPage(
        mapState, hasMap, mapContent, camera,
        header = {
            if (alerts.isNotEmpty()) TripNoticesCard(alerts, now, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).heightIn(max = 480.dp))
        },
    ) { listModifier ->
    // Room below the last stop for the Follow button, which floats over the list.
    val bottom = if (following == null && detail.canFollow(now)) 88.dp else 16.dp
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottom), verticalArrangement = Arrangement.spacedBy(0.dp), modifier = listModifier) {
        if (following != null) item(key = "following") {
            FollowingRideCard(following.first, following.second, Modifier.padding(bottom = 16.dp))
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Text(
                    listOfNotNull(detail.trip.shortName.ifBlank { null }, Format.mode(detail.route.mode), detail.route.operator?.name, Format.date(detail.serviceDate)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Length and bikes, from the timetable; each only when it is published.
                val length = detail.lengthMeters
                if (length != null || detail.trip.bikes != org.southtyrol.transit.model.Bikes.UNKNOWN) {
                    val pill = MaterialTheme.colorScheme.surfaceContainerHighest
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (length != null) org.southtyrol.transit.feature.common.InfoPill(
                            pluralStringResource(R.plurals.trip_length, detail.stops.size, Format.distance(length), detail.stops.size), pill,
                            icon = androidx.compose.material.icons.Icons.Rounded.Straighten,
                        )
                        org.southtyrol.transit.feature.common.BikesPill(detail.trip.bikes, pill)
                    }
                }
                // How far the vehicle has come, only from a live GPS fix.
                if (runDistance != null && length != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (runDistance.travelledMeters / length).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.trip_distance_progress, Format.distance(runDistance.travelledMeters), Format.distance(runDistance.remainingMeters)),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.state == ServiceState.CANCELLED) Text(stringResource(org.southtyrol.transit.design.R.string.ds_cancelled), color = LocalStatusColors.current.cancelled, style = MaterialTheme.typography.titleMedium)
                val positionText = when {
                    vehicle != null -> stringResource(R.string.trip_vehicle_seen, Format.age(vehicle.timestamp, now)) + if (vehicleFreshness == Freshness.STALE) " · " + stringResource(org.southtyrol.transit.design.R.string.ds_stale) else ""
                    position != null && detail.freshness == Freshness.LIVE -> stringResource(R.string.trip_position_estimated_live)
                    position != null -> stringResource(R.string.trip_position_estimated_schedule)
                    else -> null
                }
                if (positionText != null) Text(positionText, style = MaterialTheme.typography.labelLarge)
                else Text(stringResource(R.string.trip_no_vehicle), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Compact: a static preview in the list; tap it (or the expand button) for the large, interactive map.
        if (hasMap && !mapState.expanded) item {
            org.southtyrol.transit.feature.common.CompactMapCard(mapState, mapContent, camera, Modifier.fillMaxWidth().height(220.dp).padding(bottom = 12.dp))
        }
        itemsIndexed(detail.stops, key = { _, s -> s.sequence }, contentType = { _, _ -> "stop" }) { index, stop ->
            val marker = when {
                position == null -> VehicleMarker.NONE
                position.atStop && index == position.nextIndex - 1 -> VehicleMarker.AT_STOP
                !position.atStop && index == position.nextIndex -> VehicleMarker.BEFORE_STOP
                else -> VehicleMarker.NONE
            }
            StopTimelineRow(
                stop, first = index == 0, last = index == detail.stops.lastIndex,
                passed = nextIndex >= 0 && index < nextIndex || nextIndex < 0, next = index == nextIndex, color = color,
                vehicle = marker, mode = detail.route.mode, estimated = position?.estimated ?: true,
                onClick = { navigator.stop(stop.stop.stationKey, stop.stop.name) },
                note = if (runDistance != null && marker == VehicleMarker.BEFORE_STOP) stringResource(R.string.trip_next_stop_in, Format.distance(runDistance.toNextStopMeters)) else null,
                distance = if (index > 0 && firstDistance != null) stop.distanceMeters?.let { Format.distance(it - firstDistance) } else null,
            )
        }
    }
    }
}

/** Collapsed summary of this run's service notices; tap to expand all of them with their full text. */
@Composable
private fun TripNoticesCard(alerts: List<org.southtyrol.transit.model.ServiceAlert>, now: java.time.Instant, modifier: Modifier = Modifier) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val language = org.southtyrol.transit.di.AppLanguage.textLanguages()
    val status = org.southtyrol.transit.design.LocalStatusColors.current
    val disruption = alerts.any { it.isDisruption }
    androidx.compose.material3.Card(
        onClick = { expanded = !expanded },
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column(Modifier.padding(16.dp).then(if (expanded) Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    if (disruption) androidx.compose.material.icons.Icons.Rounded.ReportProblem else androidx.compose.material.icons.Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = if (disruption) status.cancelled else status.delayed,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(pluralStringResource(R.plurals.trip_notices, alerts.size, alerts.size), style = MaterialTheme.typography.titleMedium)
                    if (!expanded) Text(
                        alerts.first().headers.localized(language),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                androidx.compose.material3.Icon(
                    if (expanded) androidx.compose.material.icons.Icons.Rounded.ExpandLess else androidx.compose.material.icons.Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                )
            }
            if (expanded) alerts.forEachIndexed { index, alert ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(alert.headers.localized(language).ifBlank { stringResource(R.string.alerts_untitled) }, style = MaterialTheme.typography.titleSmall)
                    Text(org.southtyrol.transit.feature.common.validity(alert, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val body = alert.descriptions.localized(language)
                    if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
