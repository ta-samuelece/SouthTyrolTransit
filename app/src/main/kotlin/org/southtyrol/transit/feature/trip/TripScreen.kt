@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.trip

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
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.ExpandMore
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
    realtime: RealtimeRepository,
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
            val detail = trips.trip(tripId, runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }, language.current())
            if (detail == null) TripLoad.NotFound else TripLoad.Ready(detail)
        } catch (e: DataException) {
            TripLoad.Error(e.error)
        }
    }

    suspend fun poll() {
        trips.pollRealtime()
        trips.liveTrip(liveRef)?.let { liveTrip.value = it }
    }
}

@Composable
fun TripScreen(navigator: Navigator, tripId: String, serviceDate: String, liveRef: String = "") {
    val viewModel = hiltViewModel<TripViewModel, TripViewModel.Factory>(key = "$tripId|$serviceDate") { it.create(tripId, serviceDate, liveRef) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PollWhileVisible(20_000) { viewModel.poll() }
    Scaffold(
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
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                TripLoad.Loading -> TransitLoading(contained = true)
                TripLoad.NotFound -> MessageState(stringResource(R.string.trip_not_found))
                is TripLoad.Error -> ErrorState(s.error, onRetry = { viewModel.load() })
                is TripLoad.Ready -> TripContent(s.detail, navigator, viewModel.tripAlerts.collectAsStateWithLifecycle().value)
            }
        }
    }
}

@Composable
private fun TripContent(detail: TripDetail, navigator: Navigator, alerts: List<org.southtyrol.transit.model.ServiceAlert>) {
    val now = rememberNow()
    val dark = LocalDarkTheme.current
    val color = detail.route.color ?: ModeColors.container(detail.route.mode)
    val vehicle = detail.vehicle
    val vehicleFreshness = vehicle?.let { FreshnessPolicy.vehicle(it.timestamp, now) }
    // The next stop is the first one whose (predicted) departure is still ahead.
    val nextIndex = detail.stops.indexOfFirst { (it.predictedDeparture ?: it.scheduledDeparture).isAfter(now) }
    // Where the bus is: its GPS fix when the vehicle feed has one, otherwise estimated from the stop times.
    val estimate = remember(detail, now.epochSecond / 10) { org.southtyrol.transit.model.RunPositions.estimate(detail.stops, detail.shape, now) }
    val position = vehicle?.let { v -> org.southtyrol.transit.model.RunPosition(v.point, estimate?.nextIndex ?: nextIndex, atStop = false, estimated = false) } ?: estimate
    val mapState = org.southtyrol.transit.feature.common.rememberExpandableMapState()
    val routeLine = detail.shape.takeIf { it.size >= 2 } ?: detail.stops.map { it.stop.point }.filter { it.isValid }
    val hasMap = routeLine.size >= 2
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Bring the next stop into view once, below the header (and the compact map).
    androidx.compose.runtime.LaunchedEffect(detail.trip.id) {
        if (nextIndex > 3) listState.scrollToItem(nextIndex + (if (hasMap) 2 else 1) - 1)
    }
    val mapContent = remember(detail, position, color) {
        MapContent(
            stops = detail.stops.map { MapMarker(it.stop.id, it.stop.point, MarkerKind.STOP, color = color) },
            vehicles = listOfNotNull(
                position?.let { p ->
                    MapMarker(
                        "v:" + detail.trip.id, p.point, MarkerKind.VEHICLE, detail.route.shortName, detail.route.mode, color, vehicle?.bearing,
                        // Estimated positions are drawn dimmed, like stale GPS fixes.
                        stale = p.estimated || vehicleFreshness == Freshness.STALE,
                    )
                },
            ),
            lines = listOf(MapPolyline("trip", routeLine, color)),
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
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp), modifier = listModifier) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                Text(listOfNotNull(Format.mode(detail.route.mode), detail.route.operator?.name, Format.date(detail.serviceDate)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FreshnessIndicator(detail.freshness, detail.observedAt, now)
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
        itemsIndexed(detail.stops, key = { _, s -> s.sequence }) { index, stop ->
            val marker = when {
                position == null -> VehicleMarker.NONE
                position.atStop && index == position.nextIndex - 1 -> VehicleMarker.AT_STOP
                !position.atStop && index == position.nextIndex -> VehicleMarker.BEFORE_STOP
                else -> VehicleMarker.NONE
            }
            TripStopRow(
                stop, first = index == 0, last = index == detail.stops.lastIndex,
                passed = nextIndex >= 0 && index < nextIndex || nextIndex < 0, next = index == nextIndex, color = color,
                vehicle = marker, mode = detail.route.mode, estimated = position?.estimated ?: true,
                onClick = { navigator.stop(stop.stop.stationKey, stop.stop.name) },
            )
        }
    }
    }
}

/** Where the bus is drawn on the timeline: between the previous stop and this one, or at this stop. */
private enum class VehicleMarker { NONE, BEFORE_STOP, AT_STOP }

@Composable
private fun TripStopRow(
    stop: TripStop, first: Boolean, last: Boolean, passed: Boolean, next: Boolean, color: Long,
    vehicle: VehicleMarker, mode: org.southtyrol.transit.model.TransportMode, estimated: Boolean, onClick: () -> Unit,
) {
    val lineColor = androidx.compose.ui.graphics.Color(0xFF000000 or color)
    val skipped = stop.state == ServiceState.SKIPPED || stop.state == ServiceState.CANCELLED
    val scheduled = if (last) stop.scheduledArrival else stop.scheduledDeparture
    val predicted = if (last) stop.predictedArrival else stop.predictedDeparture
    val timeText = Format.time(predicted ?: scheduled)
    val delayText = org.southtyrol.transit.design.delayDescription(predicted?.let { Duration.between(scheduled, it).seconds }, stop.state)
    val vehicleText = if (vehicle != VehicleMarker.NONE) stringResource(if (vehicle == VehicleMarker.AT_STOP) R.string.trip_vehicle_at_stop else R.string.trip_vehicle_approaching) else null
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(onClick = onClick).heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(stop.stop.name, timeText, delayText, vehicleText).joinToString(", ") },
    ) {
        Column(Modifier.width(88.dp).padding(vertical = 8.dp)) {
            Text(timeText, style = TimeStyles.medium, maxLines = 1, softWrap = false, textDecoration = if (skipped) TextDecoration.LineThrough else null)
            if (predicted != null && predicted != scheduled) Text(org.southtyrol.transit.design.Format.time(scheduled), style = MaterialTheme.typography.labelSmall, textDecoration = TextDecoration.LineThrough)
        }
        Box(Modifier.width(28.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(4.dp).weight(1f).background(if (first) androidx.compose.ui.graphics.Color.Transparent else lineColor.copy(alpha = if (passed) 0.35f else 1f)))
                Box(
                    Modifier.size(if (next) 18.dp else 12.dp).clip(CircleShape)
                        .background(if (next) lineColor else MaterialTheme.colorScheme.surface)
                        .border(3.dp, lineColor.copy(alpha = if (passed) 0.35f else 1f), CircleShape),
                )
                Box(Modifier.width(4.dp).weight(1f).background(if (last) androidx.compose.ui.graphics.Color.Transparent else lineColor.copy(alpha = if (passed) 0.35f else 1f)))
            }
            // The bus itself: on the line just before this stop (on its way) or on this stop (dwelling).
            if (vehicle != VehicleMarker.NONE) {
                Surface(
                    shape = CircleShape,
                    color = lineColor.copy(alpha = if (estimated) 0.85f else 1f),
                    contentColor = androidx.compose.ui.graphics.Color.White,
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stop.stop.platform.isNotBlank()) Text(stringResource(org.southtyrol.transit.design.R.string.ds_platform_short, stop.stop.platform), style = MaterialTheme.typography.labelMedium)
                DelayLabel(predicted?.let { Duration.between(scheduled, it).seconds }, stop.state, if (predicted != null) Freshness.LIVE else Freshness.SCHEDULED)
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
