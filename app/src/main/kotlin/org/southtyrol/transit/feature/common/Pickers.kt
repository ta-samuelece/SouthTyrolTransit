@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)

package org.southtyrol.transit.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.data.EfaClient
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.MapRepository
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.ModeGlyph
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.map.MapViewport
import org.southtyrol.transit.map.TransitMap
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Geo
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransitScheduleDataSource
import org.southtyrol.transit.model.TransportMode
import javax.inject.Inject

/** Saved stops highlighted on maps (station key → stop). */
/** Saved stops with coordinates; stops saved without a location are looked up in the timetable. */
fun SavedStopsFlow(repo: SavedRepository, schedule: TransitScheduleDataSource, language: LanguageProvider): kotlinx.coroutines.flow.Flow<List<Stop>> =
    repo.saved.map { items ->
        items.filter { it.kind == SavedKind.STOP }.mapNotNull { item ->
            val point = item.place?.point?.takeIf { it.isValid }
                ?: runCatching { schedule.stop(item.id, language.current())?.point }.getOrNull()?.takeIf { it.isValid }
            point?.let { Stop(item.id, item.label, it, stationKey = item.id) }
        }
    }

data class StopQueryState(val query: String = "", val loading: Boolean = false, val results: List<Stop> = emptyList(), val error: DataError? = null)

/** Stop search by name: offline timetable when available, otherwise the EFA stop finder. */
@HiltViewModel
class StopSearchViewModel @Inject constructor(
    private val schedule: TransitScheduleDataSource,
    private val efa: EfaClient,
    private val language: LanguageProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(StopQueryState())
    val state: StateFlow<StopQueryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Only a changed query starts a search: the search's own updates (loading, results) must not
            // re-trigger it, or the list keeps reloading in a loop.
            _state.map { it.query.trim() }.distinctUntilChanged().debounce(250).collectLatest { query ->
                if (query.length < 2) { _state.update { it.copy(results = emptyList(), loading = false, error = null) }; return@collectLatest }
                _state.update { it.copy(loading = true) }
                try {
                    val lang = language.current()
                    val results = if (schedule.isAvailable()) schedule.searchStops(query, lang) else efa.search(query, lang)
                        .filter { it.type == PlaceType.STOP && it.stopGlobalId.isNotBlank() && it.point != null }
                        .map { Stop(it.stopGlobalId, it.name, it.point!!, stationKey = it.stopGlobalId) }
                    _state.update { it.copy(results = results, loading = false, error = null) }
                } catch (e: DataException) {
                    _state.update { it.copy(results = emptyList(), loading = false, error = e.error) }
                }
            }
        }
    }

    fun query(value: String) = _state.update { it.copy(query = value) }
}

/** Bottom sheet to find a stop by name or on the map. */
@Composable
fun StopSearchSheet(onPick: (Stop) -> Unit, onDismiss: () -> Unit, viewModel: StopSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::query, singleLine = true,
                placeholder = { Text(stringResource(R.string.departures_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.query("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) } },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        LazyColumn(Modifier.fillMaxHeight().imePadding(), contentPadding = PaddingValues(16.dp)) {
            item {
                SegmentedListItem(
                    onClick = { mapOpen = true }, shapes = ListItemDefaults.segmentedShapes(0, 1),
                    leadingContent = { Icon(Icons.Rounded.Map, contentDescription = null) },
                ) { Text(stringResource(R.string.pick_on_map)) }
            }
            if (state.loading) item { TransitLoading() }
            state.error?.let { item { StatusBanner(Format.error(it), kind = BannerKind.ERROR) } }
            itemsIndexed(state.results, key = { _, s -> s.id }) { i, stop ->
                SegmentedListItem(
                    onClick = { onPick(stop) }, shapes = ListItemDefaults.segmentedShapes(i, state.results.size),
                    leadingContent = { ModeGlyph(TransportMode.BUS) },
                    modifier = Modifier.padding(top = if (i == 0) 12.dp else 0.dp),
                ) { Text(stop.name) }
            }
        }
    }
    if (mapOpen) MapPickerDialog(allowPoint = false, onStop = { mapOpen = false; onPick(it) }, onPoint = {}, onDismiss = { mapOpen = false })
}

data class LineQueryState(val query: String = "", val loading: Boolean = true, val available: Boolean = true, val results: List<org.southtyrol.transit.model.Line> = emptyList())

/** Line search over the downloaded timetable (lines only exist there). */
@HiltViewModel
class LineSearchViewModel @Inject constructor(
    private val lines: org.southtyrol.transit.data.LineRepository,
    private val schedule: TransitScheduleDataSource,
) : ViewModel() {
    private val _state = MutableStateFlow(LineQueryState())
    val state: StateFlow<LineQueryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Only a changed query starts a search: the search's own updates (loading, results) must not
            // re-trigger it, or the list keeps reloading in a loop.
            _state.map { it.query.trim() }.distinctUntilChanged().debounce(200).collectLatest { query ->
                val available = runCatching { schedule.isAvailable() }.getOrDefault(false)
                val results = if (available) runCatching { lines.search(query) }.getOrDefault(emptyList()) else emptyList()
                _state.update { if (it.query.trim() == query) it.copy(loading = false, available = available, results = results) else it }
            }
        }
    }

    fun query(value: String) { _state.update { it.copy(query = value, loading = it.loading || value.trim() != it.query.trim()) } }
}

@Composable
fun LineSearchSheet(onPick: (org.southtyrol.transit.model.Line) -> Unit, onDismiss: () -> Unit, viewModel: LineSearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::query, singleLine = true,
                placeholder = { Text(stringResource(R.string.lines_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.query("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) } },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        LazyColumn(Modifier.fillMaxHeight().imePadding(), contentPadding = PaddingValues(16.dp)) {
            when {
                state.loading && state.results.isEmpty() -> item { TransitLoading() }
                !state.available -> item { StatusBanner(stringResource(R.string.saved_lines_need_timetable), kind = BannerKind.INFO) }
            }
            itemsIndexed(state.results, key = { _, l -> l.key }) { i, line ->
                SegmentedListItem(
                    onClick = { onPick(line) }, shapes = ListItemDefaults.segmentedShapes(i, state.results.size),
                    leadingContent = { org.southtyrol.transit.design.LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor) },
                ) { Text(line.longName.ifBlank { line.name }) }
            }
        }
    }
}

@HiltViewModel
class MapPickerViewModel @Inject constructor(
    private val map: MapRepository,
    saved: SavedRepository,
    private val language: LanguageProvider,
    schedule: TransitScheduleDataSource,
    private val location: org.southtyrol.transit.location.LocationProvider,
) : ViewModel() {
    private val _user = MutableStateFlow<Point?>(null)
    val user: StateFlow<Point?> = _user.asStateFlow()
    private val _camera = MutableStateFlow<CameraRequest>(CameraRequest.Center(Geo.Bolzano, 13.8))
    val camera: StateFlow<CameraRequest> = _camera.asStateFlow()
    private val _locationProblem = MutableStateFlow<org.southtyrol.transit.location.LocationResult?>(null)
    val locationProblem: StateFlow<org.southtyrol.transit.location.LocationResult?> = _locationProblem.asStateFlow()

    fun hasLocationPermission() = location.hasPermission()

    /** Centres on the device position; [quiet] skips the error message (used when opening the picker). */
    fun locate(quiet: Boolean = false) = viewModelScope.launch {
        when (val r = location.current()) {
            is org.southtyrol.transit.location.LocationResult.Found -> {
                _user.value = r.point
                _camera.value = CameraRequest.Center(r.point, 15.5, key = _camera.value.key + 1)
            }
            else -> if (!quiet) _locationProblem.value = r
        }
    }

    fun locationDenied() { _locationProblem.value = org.southtyrol.transit.location.LocationResult.PermissionDenied }
    fun clearLocationProblem() { _locationProblem.value = null }

    /** Starts where the user is when location is already allowed, else at the first saved stop or Bolzano. */
    fun start(fallback: Point?) {
        if (fallback != null && _camera.value.key == 0) _camera.value = CameraRequest.Center(fallback, 13.8, key = 1)
        if (location.hasPermission()) locate(quiet = true)
    }

    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()
    val savedStops: StateFlow<List<Stop>> = SavedStopsFlow(saved, schedule, language).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val viewport = MutableStateFlow<MapViewport?>(null)
    val center: StateFlow<MapViewport?> = viewport.asStateFlow()

    init {
        viewModelScope.launch {
            viewport.debounce(300).collectLatest { v ->
                _stops.value = if (v != null && v.zoom >= 13.5) runCatching { map.stops(v.box, language.current()) }.getOrDefault(emptyList()) else emptyList()
            }
        }
    }

    fun onViewport(v: MapViewport) { viewport.value = v }
}

/**
 * Full-screen map to pick a stop (tap a stop marker) or, when [allowPoint], any location under
 * the centre crosshair.
 */
@Composable
fun MapPickerDialog(
    allowPoint: Boolean,
    onStop: (Stop) -> Unit,
    onPoint: (Point) -> Unit,
    onDismiss: () -> Unit,
    viewModel: MapPickerViewModel = hiltViewModel(),
) {
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    val saved by viewModel.savedStops.collectAsStateWithLifecycle()
    val viewport by viewModel.center.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val camera by viewModel.camera.collectAsStateWithLifecycle()
    val problem by viewModel.locationProblem.collectAsStateWithLifecycle()
    val dark = LocalDarkTheme.current
    val savedKeys = saved.map { it.id }.toSet()
    val content = remember(stops, saved, user) {
        val loaded = stops.map { MapMarker(it.id, it.point, MarkerKind.STOP, highlighted = it.id in savedKeys) }
        val extra = saved.filter { s -> stops.none { it.id == s.id } }.map { MapMarker(it.id, it.point, MarkerKind.STOP, highlighted = true) }
        MapContent(stops = loaded + extra, clusterStops = false, pois = listOfNotNull(user?.let { MapMarker("me", it, MarkerKind.USER) }))
    }
    val byId = remember(stops, saved) { (stops + saved).associateBy { it.id } }
    LaunchedEffect(Unit) { viewModel.start(saved.firstOrNull()?.point) }
    val requestLocation = rememberLocationPermission { granted -> if (granted) viewModel.locate() else viewModel.locationDenied() }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val deniedText = stringResource(R.string.location_denied)
    val unavailableText = stringResource(R.string.location_unavailable)
    LaunchedEffect(problem) {
        val p = problem ?: return@LaunchedEffect
        snackbar.showSnackbar(if (p is org.southtyrol.transit.location.LocationResult.PermissionDenied) deniedText else unavailableText)
        viewModel.clearLocationProblem()
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            TransitMap(
                content, Modifier.fillMaxSize(), camera = camera, darkTheme = dark,
                onMarkerClick = { m -> byId[m.id]?.let(onStop) },
                onViewportChanged = viewModel::onViewport,
            )
            if (allowPoint) {
                Box(
                    Modifier.align(Alignment.Center).size(22.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)) }
            }
            Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 4.dp,
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (allowPoint) R.string.pick_hint_point else R.string.pick_hint_stop),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                    FilledTonalIconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel)) }
                }
            }
            androidx.compose.material3.FloatingActionButton(
                onClick = { if (viewModel.hasLocationPermission()) viewModel.locate() else requestLocation() },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 16.dp, bottom = if (allowPoint) 96.dp else 24.dp),
            ) { Icon(Icons.Rounded.MyLocation, contentDescription = stringResource(R.string.map_center_me)) }
            androidx.compose.material3.SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
            if (allowPoint) {
                Button(
                    onClick = { viewport?.center?.let(onPoint) },
                    enabled = viewport != null,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text(stringResource(R.string.pick_use_point), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Date then time picker (in South Tyrol time); [onPicked] receives the chosen moment. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialogs(initial: java.time.Instant?, onPicked: (java.time.Instant) -> Unit, onDismiss: () -> Unit) {
    val start = (initial ?: java.time.Instant.now()).atZone(org.southtyrol.transit.model.TransitZone)
    var date by rememberSaveable { mutableStateOf<Long?>(null) }
    if (date == null) {
        val initialMillis = start.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val dateState = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { androidx.compose.material3.TextButton(onClick = { date = dateState.selectedDateMillis ?: initialMillis }) { Text(stringResource(R.string.action_next)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { androidx.compose.material3.DatePicker(dateState) }
    } else {
        val timeState = androidx.compose.material3.rememberTimePickerState(start.hour, start.minute)
        androidx.compose.material3.TimePickerDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.planner_pick_time)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val day = java.time.Instant.ofEpochMilli(date!!).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    onPicked(day.atTime(timeState.hour, timeState.minute).atZone(org.southtyrol.transit.model.TransitZone).toInstant())
                }) { Text(stringResource(R.string.action_done)) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        ) { androidx.compose.material3.TimePicker(timeState) }
    }
}
