@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)

package org.southtyrol.transit.feature.lines

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.data.AlertMatcher
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.AlertsState
import org.southtyrol.transit.data.DepartureRepository
import org.southtyrol.transit.data.BoardSource
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LineRepository
import org.southtyrol.transit.data.MapRepository
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.feature.common.AlertCard
import org.southtyrol.transit.feature.common.DepartureRow
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MapPolyline
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.map.TransitMap
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.LineVariant
import org.southtyrol.transit.ui.Navigator
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

data class LinesSearchState(val query: String = "", val loading: Boolean = true, val lines: List<Line> = emptyList(), val error: DataError? = null)

@HiltViewModel
class LinesViewModel @Inject constructor(private val repo: LineRepository, handle: SavedStateHandle) : ViewModel() {
    private val _state = MutableStateFlow(LinesSearchState(handle.get<String>("q").orEmpty()))
    val state: StateFlow<LinesSearchState> = _state.asStateFlow()
    private val handle = handle

    init {
        viewModelScope.launch {
            // Only a changed query starts a search: the search's own updates (loading, results) must not
            // re-trigger it, or the list keeps reloading in a loop.
            _state.map { it.query.trim() }.distinctUntilChanged().debounce(200).collectLatest { query ->
                try {
                    val lines = repo.search(query)
                    _state.update { if (it.query.trim() == query) it.copy(lines = lines, loading = false, error = null) else it }
                } catch (e: DataException) {
                    _state.update { it.copy(loading = false, error = e.error) }
                }
            }
        }
    }

    fun query(value: String) { handle["q"] = value; _state.update { it.copy(query = value, loading = it.loading || value.trim() != it.query.trim()) } }
}

@Composable
fun LinesScreen(navigator: Navigator, viewModel: LinesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lines_title)) },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                OutlinedTextField(
                    value = state.query, onValueChange = viewModel::query, singleLine = true,
                    placeholder = { Text(stringResource(R.string.lines_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.query("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) } },
                    shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            when {
                state.error != null -> item { ErrorState(state.error!!) }
                state.loading && state.lines.isEmpty() -> item { TransitLoading() }
                state.lines.isEmpty() -> item { MessageState(stringResource(R.string.search_no_results)) }
                else -> itemsIndexed(state.lines, key = { _, l -> l.key }) { index, line ->
                    SegmentedListItem(
                        onClick = { navigator.line(line.key) },
                        shapes = ListItemDefaults.segmentedShapes(index, state.lines.size),
                        leadingContent = { LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor) },
                        supportingContent = { Text(Format.mode(line.mode)) },
                    ) { Text(line.longName.ifBlank { line.name }, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

data class LineState(
    val line: Line? = null,
    val variants: List<LineVariant> = emptyList(),
    val selected: Int = 0,
    val trips: List<Departure> = emptyList(),
    val loading: Boolean = true,
    val error: DataError? = null,
)

@HiltViewModel(assistedFactory = LineViewModel.Factory::class)
class LineViewModel @AssistedInject constructor(
    @Assisted private val key: String,
    private val repo: LineRepository,
    private val departures: DepartureRepository,
    private val map: MapRepository,
    private val realtime: RealtimeRepository,
    private val saved: SavedRepository,
    alerts: AlertRepository,
    private val language: LanguageProvider,
) : ViewModel() {
    @AssistedFactory
    interface Factory { fun create(key: String): LineViewModel }

    private val _state = MutableStateFlow(LineState())
    val state: StateFlow<LineState> = _state.asStateFlow()
    val isSaved = saved.isSaved(SavedKind.LINE, key).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val alertState = alerts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertsState())

    val lineAlerts = combine(alertState, _state) { a, s ->
        val line = s.line ?: return@combine emptyList()
        a.alerts.filter { it.active(Instant.now()) && AlertMatcher.affectsLine(it, line) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Upcoming trips with realtime applied and vehicles currently assigned to this line. */
    val live = combine(_state, realtime.snapshot, alertState) { s, snapshot, a ->
        val line = s.line
        val trips = departures.merge(s.trips, BoardSource.SCHEDULE, snapshot, a.alerts, arrivals = false)
        Triple(trips, if (line == null) emptyList() else map.vehiclesForLine(line, snapshot), snapshot.vehiclesFetchedAt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            val line = repo.line(key) ?: run { _state.update { it.copy(loading = false, error = DataError.NotFound) }; return@launch }
            val variants = repo.variants(line, language.current())
            _state.update { it.copy(line = line, variants = variants, loading = false, error = null) }
            loadTrips()
        } catch (e: DataException) {
            _state.update { it.copy(loading = false, error = e.error) }
        }
    }

    fun select(index: Int) { _state.update { it.copy(selected = index, trips = emptyList()) }; viewModelScope.launch { loadTrips() } }

    private suspend fun loadTrips() {
        val s = _state.value
        val line = s.line ?: return
        val variant = s.variants.getOrNull(s.selected) ?: return
        val now = Instant.now()
        val trips = runCatching { repo.trips(line, variant, now.minus(Duration.ofMinutes(20)), now.plus(Duration.ofHours(4)), language.current()) }.getOrDefault(emptyList())
        _state.update { it.copy(trips = trips) }
    }

    suspend fun poll() = map.pollVehicles()

    fun toggleSaved() = viewModelScope.launch {
        val line = _state.value.line ?: return@launch
        if (isSaved.value) saved.remove(SavedKind.LINE, key) else saved.saveLine(line)
    }
}

@Composable
fun LineScreen(navigator: Navigator, key: String) {
    val viewModel = hiltViewModel<LineViewModel, LineViewModel.Factory>(key = key) { it.create(key) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val alerts by viewModel.lineAlerts.collectAsStateWithLifecycle()
    val saved by viewModel.isSaved.collectAsStateWithLifecycle()
    val now = rememberNow()
    val dark = LocalDarkTheme.current
    PollWhileVisible(30_000) { viewModel.poll() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    state.line?.let { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor, large = true)
                            Text(line.longName.ifBlank { Format.mode(line.mode) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { viewModel.toggleSaved() }) {
                        Icon(if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = stringResource(if (saved) R.string.action_unsave_line else R.string.action_save_line))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> TransitLoading(contained = true)
                state.error != null -> ErrorState(state.error!!, onRetry = { viewModel.load() })
                else -> {
                    val line = state.line!!
                    val variant = state.variants.getOrNull(state.selected)
                    val color = line.color ?: ModeColors.container(line.mode)
                    val (trips, vehicles, vehiclesAt) = live ?: Triple(org.southtyrol.transit.data.Board(emptyList(), BoardSource.SCHEDULE, Freshness.UNAVAILABLE, null), emptyList<org.southtyrol.transit.model.Vehicle>(), null as Instant?)
                    val mapState = org.southtyrol.transit.feature.common.rememberExpandableMapState()
                    val hasMap = variant != null && variant.shape.size >= 2
                    val mapContent = MapContent(
                        stops = variant?.stops.orEmpty().map { MapMarker(it.id, it.point, MarkerKind.STOP, color = color) },
                        vehicles = vehicles.map { v -> MapMarker("v:" + v.id, v.point, MarkerKind.VEHICLE, line.name, line.mode, color, v.bearing, stale = FreshnessPolicy.vehicle(v.timestamp, now) == Freshness.STALE) },
                        lines = listOfNotNull(variant?.let { MapPolyline("line", it.shape, color) }),
                        clusterStops = false,
                    )
                    val camera = CameraRequest.Fit(variant?.shape.orEmpty(), key = state.selected)
                    org.southtyrol.transit.feature.common.ExpandableMapPage(mapState, hasMap, mapContent, camera) { listModifier ->
                    LazyColumn(listModifier, contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (state.variants.size > 1) item {
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.variants.forEachIndexed { index, v ->
                                    FilterChip(selected = index == state.selected, onClick = { viewModel.select(index) }, label = { Text("→ " + v.headsign, maxLines = 1) })
                                }
                            }
                        }
                        if (hasMap && !mapState.expanded) item {
                            org.southtyrol.transit.feature.common.CompactMapCard(mapState, mapContent, camera, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(240.dp))
                        }
                        item {
                            Column(Modifier.padding(16.dp)) {
                                Text(pluralStringResource(R.plurals.vehicles_live, vehicles.size, vehicles.size), style = MaterialTheme.typography.labelLarge)
                                FreshnessIndicator(if (vehiclesAt == null) Freshness.UNAVAILABLE else FreshnessPolicy.feed(vehiclesAt, now), vehiclesAt, now)
                            }
                        }
                        if (alerts.isNotEmpty()) {
                            item { SectionHeader(stringResource(R.string.line_alerts)) }
                            itemsIndexed(alerts, key = { _, a -> "a:" + a.id }) { _, alert -> AlertCard(alert, AppLanguage.textLanguages(), now, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
                        }
                        item { SectionHeader(stringResource(R.string.line_next_trips)) }
                        val upcoming = trips.departures.take(12)
                        if (upcoming.isEmpty()) item { Text(stringResource(R.string.line_no_trips), Modifier.padding(horizontal = 16.dp)) }
                        itemsIndexed(upcoming, key = { _, d -> "t:" + d.key }) { index, d ->
                            DepartureRow(d, now, index, upcoming.size, onClick = { navigator.trip(d.tripId, d.serviceDate.toString()) }, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (variant != null) {
                            item { SectionHeader(stringResource(R.string.line_stops)) }
                            itemsIndexed(variant.stops, key = { i, s -> "s:$i:" + s.id }) { index, stop ->
                                SegmentedListItem(
                                    onClick = { navigator.stop(stop.stationKey, stop.name) },
                                    shapes = ListItemDefaults.segmentedShapes(index, variant.stops.size),
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    leadingContent = { Text("${index + 1}", style = MaterialTheme.typography.labelLarge) },
                                ) { Text(stop.name) }
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}
