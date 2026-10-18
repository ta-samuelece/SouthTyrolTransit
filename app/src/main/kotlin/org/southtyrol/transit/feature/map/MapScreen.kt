@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DepartureBoard
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.ElectricCar
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.IconButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LiveVehicle
import org.southtyrol.transit.data.MapRepository
import org.southtyrol.transit.data.MobilityRepository
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.data.TripRepository
import org.southtyrol.transit.data.UserSettings
import org.southtyrol.transit.data.LocateButtonPosition
import org.southtyrol.transit.data.SearchBarPosition
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.feature.common.rememberLocationPermission
import org.southtyrol.transit.feature.search.MapSearchBar
import org.southtyrol.transit.feature.search.MapSearchView
import org.southtyrol.transit.feature.search.MapSearchViewModel
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MapAttribution
import org.southtyrol.transit.map.MapViewport
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.map.TransitMap
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RealtimeFeed
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.ui.Navigator
import java.time.Instant
import javax.inject.Inject

sealed class MapSelection {
    data class VehicleSel(val vehicle: LiveVehicle, val trip: TripDetail?, val loading: Boolean) : MapSelection()
    data class StopSel(val stop: Stop, val departures: List<org.southtyrol.transit.model.Departure>? = null) : MapSelection()
    data class MobilitySel(val point: MobilityPoint) : MapSelection()
}

@HiltViewModel
class MapViewModel @Inject constructor(
    private val map: MapRepository,
    private val mobility: MobilityRepository,
    private val realtime: RealtimeRepository,
    private val trips: TripRepository,
    private val settings: SettingsRepository,
    private val location: LocationProvider,
    private val language: LanguageProvider,
    private val departures: org.southtyrol.transit.data.DepartureRepository,
    alerts: org.southtyrol.transit.data.AlertRepository,
    saved: org.southtyrol.transit.data.SavedRepository,
    schedule: org.southtyrol.transit.model.TransitScheduleDataSource,
) : ViewModel() {
    val savedStops: StateFlow<List<Stop>> = org.southtyrol.transit.feature.common.SavedStopsFlow(saved, schedule, language).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val alertState = alerts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), org.southtyrol.transit.data.AlertsState())
    private val viewport = MutableStateFlow<MapViewport?>(null)
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()
    private val _mobility = MutableStateFlow<List<MobilityPoint>>(emptyList())
    val mobilityPoints: StateFlow<List<MobilityPoint>> = _mobility.asStateFlow()
    private val _mobilityFailed = MutableStateFlow<Set<MobilityKind>>(emptySet())
    val mobilityFailed: StateFlow<Set<MobilityKind>> = _mobilityFailed.asStateFlow()
    private val _selection = MutableStateFlow<MapSelection?>(null)
    val selection: StateFlow<MapSelection?> = _selection.asStateFlow()
    private val _camera = MutableStateFlow<CameraRequest?>(null)
    val camera: StateFlow<CameraRequest?> = _camera.asStateFlow()
    private val _locationProblem = MutableStateFlow<LocationResult?>(null)
    val locationProblem: StateFlow<LocationResult?> = _locationProblem.asStateFlow()
    private val _user = MutableStateFlow<Point?>(null)
    val user: StateFlow<Point?> = _user.asStateFlow()

    val prefs: StateFlow<UserSettings> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    val vehicles: StateFlow<List<LiveVehicle>> = realtime.snapshot.map { map.vehicles(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val vehicleFeed = combine(realtime.snapshot, realtime.status) { s, st -> s.vehiclesFetchedAt to st[RealtimeFeed.VEHICLE_POSITIONS]?.error }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null to null)

    init {
        viewModelScope.launch {
            combine(viewport.filterNotNull(), prefs) { v, p -> v to p }.debounce(400).collectLatest { (v, p) ->
                _stops.value = if (p.showStops && v.zoom >= 13.5) runCatching { map.stops(v.box, language.current()) }.getOrDefault(emptyList()) else emptyList()
                if (p.mobilityLayers.isNotEmpty() && v.zoom >= 11) {
                    val result = mobility.points(v.box, p.mobilityLayers)
                    _mobility.value = result.points
                    _mobilityFailed.value = result.failed
                } else {
                    _mobility.value = emptyList()
                }
            }
        }
    }

    fun onViewport(v: MapViewport) { viewport.value = v }

    /** When the map is recreated (e.g. returning via the tab), continue where the user left off. */
    fun resumeCamera() {
        val v = viewport.value ?: return
        _camera.value = CameraRequest.Center(v.center, v.zoom, key = _camera.value?.key ?: 0)
    }
    suspend fun poll() { if (prefs.value.showVehicles) map.pollVehicles() }

    fun select(marker: MapMarker) {
        when (marker.kind) {
            MarkerKind.VEHICLE -> {
                val v = vehicles.value.firstOrNull { "v:" + it.vehicle.id == marker.id } ?: return
                _selection.value = MapSelection.VehicleSel(v, null, true)
                viewModelScope.launch {
                    val tripId = v.vehicle.tripId
                    val date = v.vehicle.startDate ?: java.time.LocalDate.now(org.southtyrol.transit.model.TransitZone)
                    val detail = if (tripId == null) null else runCatching { trips.trip(tripId, date, language.current()) }.getOrNull()?.let { trips.merge(it, realtime.snapshot.value) }
                    _selection.update { s -> if (s is MapSelection.VehicleSel && s.vehicle.vehicle.id == v.vehicle.id) s.copy(trip = detail, loading = false) else s }
                }
            }
            MarkerKind.STOP -> (_stops.value + savedStops.value).firstOrNull { it.id == marker.id }?.let(::selectStop)
            MarkerKind.PARKING, MarkerKind.BIKE_SHARING, MarkerKind.CAR_SHARING -> _mobility.value.firstOrNull { it.id == marker.id }?.let { _selection.value = MapSelection.MobilitySel(it) }
            else -> Unit
        }
    }

    private fun selectStop(stop: Stop) {
        _selection.value = MapSelection.StopSel(stop)
        // Preview of the next departures right in the sheet.
        viewModelScope.launch {
            val now = Instant.now()
            val next = runCatching {
                val (list, source) = departures.load(stop.id, false, now, language.current(), java.time.Duration.ofHours(2))
                val live = if (source == org.southtyrol.transit.data.BoardSource.SCHEDULE) departures.liveOverlay(stop.id, now, false, language.current()) else null
                departures.merge(list, source, realtime.snapshot.value, alertState.value.alerts, false, now, live = live).departures.take(5)
            }.getOrDefault(emptyList())
            _selection.update { s -> if (s is MapSelection.StopSel && s.stop.id == stop.id) s.copy(departures = next) else s }
        }
    }

    /** From the locate button's menu: fly to a saved stop and open its sheet. */
    fun focusStop(stop: Stop) {
        _camera.value = CameraRequest.Center(stop.point, 16.0, key = (_camera.value?.key ?: 0) + 1)
        selectStop(stop)
    }

    /**
     * True once, the first time it is asked: the map then tells the user about the saved-stops menu. Read
     * from the store rather than [prefs], whose first value is the default and would repeat the hint.
     */
    suspend fun takeSavedStopsHint(): Boolean {
        if (settings.current().savedStopsHintShown) return false
        settings.setSavedStopsHintShown(true)
        return true
    }

    fun clearSelection() { _selection.value = null }

    fun centerOnMe() = viewModelScope.launch {
        when (val r = location.current()) {
            is LocationResult.Found -> { _user.value = r.point; _camera.value = CameraRequest.Center(r.point, 15.0, key = (_camera.value?.key ?: 0) + 1) }
            else -> _locationProblem.value = r
        }
    }

    fun locationDenied() { _locationProblem.value = LocationResult.PermissionDenied }
    fun clearLocationProblem() { _locationProblem.value = null }
}

@Composable
fun MapScreen(navigator: Navigator, viewModel: MapViewModel = hiltViewModel(), searchViewModel: MapSearchViewModel = hiltViewModel()) {
    val searchOpen by searchViewModel.open.collectAsStateWithLifecycle()
    BackHandler(enabled = searchOpen) { searchViewModel.close() }
    // The bottom bar hides while the search covers the map; leaving this screen (a stop board) brings it back.
    DisposableEffect(searchOpen) {
        navigator.searchOpen = searchOpen
        onDispose { navigator.searchOpen = false }
    }
    val stops by viewModel.stops.collectAsStateWithLifecycle()
    val vehicles by viewModel.vehicles.collectAsStateWithLifecycle()
    val mobility by viewModel.mobilityPoints.collectAsStateWithLifecycle()
    val failed by viewModel.mobilityFailed.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val camera by viewModel.camera.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val showStops = prefs.showStops
    val feed by viewModel.vehicleFeed.collectAsStateWithLifecycle()
    val problem by viewModel.locationProblem.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val savedStops by viewModel.savedStops.collectAsStateWithLifecycle()
    val now = rememberNow(10_000)
    val dark = LocalDarkTheme.current
    val snackbar = remember { SnackbarHostState() }
    val requestLocation = rememberLocationPermission { granted -> if (granted) viewModel.centerOnMe() else viewModel.locationDenied() }

    LaunchedEffect(Unit) { viewModel.resumeCamera() }
    PollWhileVisible(20_000) { viewModel.poll() }

    val deniedText = stringResource(R.string.location_denied)
    val unavailableText = stringResource(R.string.location_unavailable)
    LaunchedEffect(problem) {
        val p = problem ?: return@LaunchedEffect
        snackbar.showSnackbar(if (p is LocationResult.PermissionDenied) deniedText else unavailableText)
        viewModel.clearLocationProblem()
    }

    // Once ever, when there is a saved stop to jump to: say that the locate button can do that.
    val hintText = stringResource(R.string.map_saved_stops_hint)
    val hasSaved = savedStops.isNotEmpty()
    val locateShown = prefs.locateButton != LocateButtonPosition.HIDDEN
    LaunchedEffect(hasSaved, locateShown) {
        if (hasSaved && locateShown && viewModel.takeSavedStopsHint()) snackbar.showSnackbar(hintText, withDismissAction = true)
    }
    val scope = rememberCoroutineScope()
    val noSavedText = stringResource(R.string.map_saved_stops_none)
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    val selectedId = when (val s = selection) {
        is MapSelection.VehicleSel -> "v:" + s.vehicle.vehicle.id
        is MapSelection.StopSel -> s.stop.id
        is MapSelection.MobilitySel -> s.point.id
        null -> null
    }
    val content = remember(stops, savedStops, showStops, vehicles, mobility, prefs.showVehicles, selectedId, user, now.epochSecond / 30) {
        val savedKeys = savedStops.map { it.id }.toSet()
        // Saved stops are always visible and highlighted, even when zoomed out.
        val stopMarkers = if (!showStops) emptyList() else
            stops.map { MapMarker(it.id, it.point, MarkerKind.STOP, selected = it.id == selectedId, highlighted = it.id in savedKeys) } +
                savedStops.filter { s -> stops.none { it.id == s.id } }.map { MapMarker(it.id, it.point, MarkerKind.STOP, selected = it.id == selectedId, highlighted = true) }
        MapContent(
            stops = stopMarkers,
            clusterStops = false,
            vehicles = if (!prefs.showVehicles) emptyList() else vehicles.map { v ->
                val mode = v.route?.mode ?: TransportMode.BUS
                MapMarker(
                    "v:" + v.vehicle.id, v.vehicle.point, MarkerKind.VEHICLE, v.route?.shortName ?: v.vehicle.label, mode,
                    v.route?.color ?: ModeColors.container(mode), v.vehicle.bearing,
                    stale = FreshnessPolicy.vehicle(v.vehicle.timestamp, now) != Freshness.LIVE, selected = "v:" + v.vehicle.id == selectedId,
                )
            },
            pois = mobility.map { p ->
                MapMarker(
                    p.id, p.point, when (p.kind) { MobilityKind.PARKING -> MarkerKind.PARKING; MobilityKind.BIKE_SHARING -> MarkerKind.BIKE_SHARING; MobilityKind.CAR_SHARING -> MarkerKind.CAR_SHARING },
                    label = if (p.freshness == Freshness.LIVE) p.available?.toString().orEmpty() else "", stale = p.freshness != Freshness.LIVE, selected = p.id == selectedId,
                )
            } + listOfNotNull(user?.let { MapMarker("me", it, MarkerKind.USER) }),
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
        // The map is intentionally edge-to-edge; overlays handle their own insets.
        // Where the search bar's app mark is while its menu is open (root coordinates), else null.
        var searchMenu by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            val locate = prefs.locateButton
            val barAtBottom = prefs.searchBar == SearchBarPosition.BOTTOM
            TransitMap(
                content, Modifier.fillMaxSize(), camera = camera, darkTheme = dark, onMarkerClick = viewModel::select, onViewportChanged = viewModel::onViewport,
                // With the search bar at the bottom the map credit goes in the top column, under the live
                // pill; otherwise it takes the bottom corner away from the locate button.
                attributionAlignment = if (locate == LocateButtonPosition.BOTTOM_START) Alignment.BottomEnd else Alignment.BottomStart,
                attributionPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                attribution = !barAtBottom,
                // The map runs under the status bar: keep the compass below it, and below the search bar
                // (12 dp margin, 56 dp bar, 8 dp gap) when the bar is at the top.
                compassTopMargin = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + if (barAtBottom) 12.dp else 76.dp,
            )

            Column(Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!barAtBottom) MapSearchBar(onOpen = searchViewModel::open, onMenu = { searchMenu = it })
                val (fetchedAt, error) = feed
                if (prefs.showVehicles && error != null && (fetchedAt == null || FreshnessPolicy.feed(fetchedAt, now) != Freshness.LIVE)) {
                    StatusBanner(stringResource(R.string.map_vehicles_unavailable), kind = BannerKind.ERROR)
                }
                if (failed.isNotEmpty()) StatusBanner(stringResource(R.string.map_layer_failed), kind = BannerKind.WARNING)
                // An empty vehicle feed is not an error: it is said in the freshness pill, not in a banner.
                if (prefs.showVehicles && fetchedAt != null) FreshnessIndicator(
                    FreshnessPolicy.feed(fetchedAt, now), fetchedAt, now,
                    extra = if (vehicles.isEmpty()) stringResource(R.string.map_no_vehicles_short) else null,
                )
                if (barAtBottom) MapAttribution(dark)
            }

            // At the bottom the search bar shares the row with the locate button: it leaves the button's
            // corner free (the FAB menu's own 16 dp margin, the 56 dp button, 16 dp between them).
            if (barAtBottom) MapSearchBar(
                onOpen = searchViewModel::open, onMenu = { searchMenu = it },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(
                    start = if (locate == LocateButtonPosition.BOTTOM_START) 88.dp else 16.dp,
                    end = if (locate == LocateButtonPosition.BOTTOM_END) 88.dp else 16.dp,
                    bottom = 16.dp,
                ),
            )

            // Besides the search bar, only the locate button floats over the map; what is shown is chosen in Settings.
            if (locate != LocateButtonPosition.HIDDEN) {
                // A tap anywhere else closes the open menu instead of reaching the map.
                if (menuOpen) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { menuOpen = false } })
                LocateFabMenu(
                    position = locate,
                    savedStops = savedStops,
                    expanded = menuOpen,
                    onExpandedChange = { menuOpen = it },
                    onLocate = requestLocation,
                    onNoSavedStops = { scope.launch { snackbar.showSnackbar(noSavedText, withDismissAction = true) } },
                    onStop = viewModel::focusStop,
                    modifier = Modifier
                        .align(
                            if (locate == LocateButtonPosition.BOTTOM_START) Alignment.BottomStart else Alignment.BottomEnd,
                        )
                        .navigationBarsPadding(),
                )
            }

            // The app mark's menu, from where the mark is; over the map and the locate button.
            searchMenu?.let { anchor ->
                org.southtyrol.transit.feature.search.SearchBarMenu(navigator, opensUp = barAtBottom, anchor = anchor, onClose = { searchMenu = null })
            }

            if (searchOpen) MapSearchView(searchViewModel, navigator)
        }
    }

    selection?.let { sel ->
        // Fully expanded only: the stop sheet grows when its departures arrive, and a partially expanded
        // anchor that moves under the sheet made it collapse and dismiss itself.
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = viewModel::clearSelection, sheetState = sheetState) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (sel) {
                    is MapSelection.VehicleSel -> VehicleSheet(sel, now, onTrip = { id, date -> viewModel.clearSelection(); navigator.trip(id, date) })
                    is MapSelection.StopSel -> {
                        Text(sel.stop.name, style = MaterialTheme.typography.headlineSmall)
                        val next = sel.departures
                        when {
                            next == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { TransitLoading() }
                            next.isEmpty() -> Text(stringResource(R.string.stop_no_departures), style = MaterialTheme.typography.bodyMedium)
                            else -> Column {
                                next.forEachIndexed { i, d ->
                                    org.southtyrol.transit.feature.common.DepartureRow(
                                        d, now, i, next.size,
                                        onClick = if (d.tripLinked) ({ viewModel.clearSelection(); navigator.trip(d.tripId, d.serviceDate.toString(), d.liveRef) }) else null,
                                    )
                                }
                            }
                        }
                        // The main action fills the row; directions is the secondary, tonal icon button beside it.
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.clearSelection(); navigator.stop(sel.stop.id, sel.stop.name) },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Icon(Icons.Rounded.DepartureBoard, contentDescription = null, modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize))
                                Spacer(Modifier.width(androidx.compose.material3.ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.map_open_departures))
                            }
                            DirectionsButton(sel.stop.point, compact = true)
                        }
                    }
                    is MapSelection.MobilitySel -> MobilitySheet(sel.point, now)
                }
            }
        }
    }
}

/**
 * The locate button as a FAB menu. Unlike the spec's menu, a tap keeps its long-standing job (centre on
 * the user); a long press opens the saved stops, and the button then turns into the menu's close button.
 */
@Composable
private fun LocateFabMenu(
    position: LocateButtonPosition,
    savedStops: List<Stop>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onLocate: () -> Unit,
    onNoSavedStops: () -> Unit,
    onStop: (Stop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val openMenu: () -> Unit = {
        if (!expanded) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (savedStops.isEmpty()) onNoSavedStops() else onExpandedChange(true)
        }
    }
    val currentOpenMenu by rememberUpdatedState(openMenu)
    val longPressLabel = stringResource(R.string.map_saved_stops_show)
    BackHandler(enabled = expanded) { onExpandedChange(false) }
    FloatingActionButtonMenu(
        expanded = expanded,
        horizontalAlignment = if (position == LocateButtonPosition.BOTTOM_START) Alignment.Start else Alignment.End,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { if (expanded) onExpandedChange(false) else onLocate() },
                // A muted secondary tone: the map, not this button, is the content.
                containerColor = ToggleFloatingActionButtonDefaults.containerColor(colors.secondaryContainer, colors.secondary),
                modifier = Modifier
                    // Seen before the button's own click handling: after a long press the release is
                    // consumed here, so the button does not also take it as a tap.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial); true }
                            if (ended == null) {
                                currentOpenMenu()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                    }
                    .semantics { if (!expanded) onLongClick(longPressLabel) { currentOpenMenu(); true } },
            ) {
                val iconColor = ToggleFloatingActionButtonDefaults.iconColor(colors.onSecondaryContainer, colors.onSecondary)
                Icon(
                    if (checkedProgress > 0.5f) Icons.Rounded.Close else Icons.Rounded.MyLocation,
                    contentDescription = stringResource(if (expanded) R.string.map_saved_stops_close else R.string.map_center_me),
                    modifier = with(ToggleFloatingActionButtonDefaults) { Modifier.animateIcon({ checkedProgress }, color = iconColor) },
                )
            }
        },
    ) {
        savedStops.forEach { stop ->
            FloatingActionButtonMenuItem(
                onClick = { onExpandedChange(false); onStop(stop) },
                text = { Text(stop.name) },
                icon = { Icon(Icons.Rounded.Place, contentDescription = null) },
                containerColor = colors.secondaryContainer,
            )
        }
    }
}

/** Compact trip sheet: line, destination, next stop, delay and data freshness. */
@Composable
private fun VehicleSheet(sel: MapSelection.VehicleSel, now: Instant, onTrip: (String, String) -> Unit) {
    val v = sel.vehicle
    val mode = v.route?.mode ?: TransportMode.BUS
    Row(verticalAlignment = Alignment.CenterVertically) {
        LineBadge(v.route?.shortName ?: v.vehicle.label, mode, color = v.route?.color, textColor = v.route?.textColor, large = true)
        Spacer(Modifier.width(12.dp))
        Text(sel.trip?.trip?.headsign ?: Format.mode(mode), style = MaterialTheme.typography.titleLarge)
    }
    FreshnessIndicator(FreshnessPolicy.vehicle(v.vehicle.timestamp, now).let { if (it == Freshness.LIVE) Freshness.LIVE else Freshness.STALE }, v.vehicle.timestamp, now)
    when {
        sel.loading -> TransitLoading()
        sel.trip != null -> {
            // Placed by the bus's own position, like the trip timeline (the stop it stands at counts as next).
            val position = org.southtyrol.transit.model.RunPositions.locate(sel.trip.stops, sel.trip.shape, v.vehicle.point, now)
            val next = sel.trip.stops.getOrNull(position.nextIndex - (if (position.atStop) 1 else 0))
            if (next != null) {
                Text(stringResource(R.string.map_next_stop, next.stop.name, Format.time(next.predictedDeparture ?: next.scheduledDeparture)), style = MaterialTheme.typography.bodyLarge)
                DelayLabel(next.predictedDeparture?.let { java.time.Duration.between(next.scheduledDeparture, it).seconds }, next.state, sel.trip.freshness)
            }
            OutlinedButton(onClick = { onTrip(sel.trip.trip.id, sel.trip.serviceDate.toString()) }) { Text(stringResource(R.string.map_open_trip)) }
        }
        else -> Text(stringResource(R.string.map_trip_unknown), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MobilitySheet(p: MobilityPoint, now: Instant) {
    Text(p.name, style = MaterialTheme.typography.headlineSmall)
    Text(
        when (p.kind) {
            MobilityKind.PARKING -> stringResource(R.string.map_layer_parking)
            MobilityKind.BIKE_SHARING -> stringResource(R.string.map_layer_bikes)
            MobilityKind.CAR_SHARING -> stringResource(R.string.map_layer_cars)
        } + " · " + p.provider,
        style = MaterialTheme.typography.bodyMedium,
    )
    val available = p.available
    val capacity = p.capacity
    if (p.freshness == Freshness.LIVE && available != null) {
        Text(
            if (capacity != null) stringResource(R.string.mobility_available_of, available, capacity) else stringResource(R.string.mobility_available, available),
            style = MaterialTheme.typography.titleLarge,
        )
    } else {
        Text(stringResource(R.string.mobility_no_current), style = MaterialTheme.typography.bodyLarge)
    }
    FreshnessIndicator(p.freshness, p.updatedAt, now)
    DirectionsButton(p.point)
    Text(stringResource(R.string.mobility_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Opens Google Maps (the app when installed, otherwise the website) with directions to [point]; [compact]
 * is a tonal icon button for a row whose main action is something else.
 */
@Composable
private fun DirectionsButton(point: Point, compact: Boolean = false) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    // No app can open the link (no browser either): nothing to do.
    val open = { runCatching { uri.openUri("https://www.google.com/maps/dir/?api=1&destination=${point.latitude},${point.longitude}") }; Unit }
    if (compact) {
        androidx.compose.material3.FilledTonalIconButton(onClick = open, modifier = Modifier.size(width = 56.dp, height = 48.dp)) {
            Icon(Icons.Rounded.Directions, contentDescription = stringResource(R.string.map_get_directions))
        }
        return
    }
    OutlinedButton(onClick = open) {
        Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize))
        Spacer(Modifier.width(androidx.compose.material3.ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.map_get_directions))
    }
}
