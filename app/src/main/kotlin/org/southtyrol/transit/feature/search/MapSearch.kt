@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)

package org.southtyrol.transit.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.EfaClient
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LineRepository
import org.southtyrol.transit.data.SavedItem
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.ModeGlyph
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.feature.common.groupGap
import org.southtyrol.transit.feature.common.groupedItemColors
import org.southtyrol.transit.feature.common.rememberLocationPermission
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransitScheduleDataSource
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.ui.Navigator
import javax.inject.Inject

sealed class NearbyState {
    data object Idle : NearbyState()
    data object Locating : NearbyState()
    data class Found(val stops: List<Stop>, val outsideRegion: Boolean = false) : NearbyState()
    data object PermissionDenied : NearbyState()
    data object Unavailable : NearbyState()
    data class Error(val error: DataError) : NearbyState()
}

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val stops: List<Stop> = emptyList(),
    val lines: List<Line> = emptyList(),
    val error: DataError? = null,
)

/** The map's search: stops and lines by name, plus saved and nearby stops while nothing is typed. */
@HiltViewModel
class MapSearchViewModel @Inject constructor(
    private val schedule: TransitScheduleDataSource,
    private val efa: EfaClient,
    private val lines: LineRepository,
    private val location: LocationProvider,
    private val language: LanguageProvider,
    saved: SavedRepository,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val _search = MutableStateFlow(SearchState(handle.get<String>("q").orEmpty()))
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private val _nearby = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val nearby: StateFlow<NearbyState> = _nearby.asStateFlow()
    private val _open = MutableStateFlow(handle.get<Boolean>("open") ?: false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    val savedStops: StateFlow<List<SavedItem>> = saved.saved.map { list -> list.filter { it.kind == SavedKind.STOP } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            // Only a changed query starts a search: the search's own updates (loading, results) must not
            // re-trigger it, or the list keeps reloading in a loop.
            _search.map { it.query.trim() }.distinctUntilChanged().debounce(250).collectLatest { query ->
                if (query.length < 2) { _search.update { it.copy(stops = emptyList(), lines = emptyList(), loading = false, error = null) }; return@collectLatest }
                _search.update { it.copy(loading = true) }
                val lang = language.current()
                // Lines come only from the offline timetable; without it the section simply stays empty.
                val found = runCatching { lines.search(query) }.getOrDefault(emptyList())
                try {
                    val stops = if (schedule.isAvailable()) schedule.searchStops(query, lang) else efaStops(query, lang)
                    _search.update { it.copy(stops = stops, lines = found, loading = false, error = null) }
                } catch (e: DataException) {
                    _search.update { it.copy(stops = emptyList(), lines = found, loading = false, error = e.error) }
                }
            }
        }
        // Restored open (after the process was stopped): fill nearby stops again.
        if (_open.value && location.hasPermission()) locate()
    }

    /** Without a cached timetable, stop search goes through the EFA stop finder. */
    private suspend fun efaStops(query: String, lang: String): List<Stop> = efa.search(query, lang)
        .filter { it.type == PlaceType.STOP && it.stopGlobalId.isNotBlank() && it.point != null }
        .map { Stop(it.stopGlobalId, it.name, it.point!!, stationKey = it.stopGlobalId) }

    fun query(value: String) { handle["q"] = value; _search.update { it.copy(query = value) } }

    private var focusPending = false

    /** Opening the search refreshes nearby stops when location is already allowed. */
    fun open() {
        setOpen(true)
        focusPending = true
        if (location.hasPermission() && _nearby.value !is NearbyState.Locating) locate()
    }

    /** Closing forgets the typed text, so the next search starts on saved and nearby stops. */
    fun close() { query(""); setOpen(false) }

    /** True once per opening: the field takes focus then, not when coming back from a stop board. */
    fun takeFocus(): Boolean = focusPending.also { focusPending = false }

    private fun setOpen(value: Boolean) { handle["open"] = value; _open.value = value }

    fun locate() {
        _nearby.value = NearbyState.Locating
        viewModelScope.launch {
            when (val result = location.current()) {
                is LocationResult.Found -> {
                    try {
                        val lang = language.current()
                        val stops = if (schedule.isAvailable()) schedule.nearbyStops(result.point, 800.0, lang, 25) else efaNearby(result.point, lang)
                        _nearby.value = NearbyState.Found(stops, result.point !in org.southtyrol.transit.model.Geo.SouthTyrol)
                    } catch (e: DataException) {
                        _nearby.value = NearbyState.Error(e.error)
                    }
                }
                LocationResult.PermissionDenied -> _nearby.value = NearbyState.PermissionDenied
                LocationResult.Unavailable -> _nearby.value = NearbyState.Unavailable
            }
        }
    }

    private suspend fun efaNearby(point: Point, lang: String): List<Stop> = efa.nearbyStops(point, 800, lang)
        .filter { it.stopGlobalId.isNotBlank() && it.point != null }
        .map { Stop(it.stopGlobalId, it.name, it.point!!, stationKey = it.stopGlobalId, distanceMeters = org.southtyrol.transit.model.Geo.distance(point, it.point!!)) }
        .distinctBy { it.id }

    fun denied() { _nearby.value = NearbyState.PermissionDenied }
}

/**
 * The search bar floating over the map; tapping it opens [MapSearchView]. Its trailing app mark opens
 * the app menu - saved items, lines and settings - the one place Settings is reached from.
 */
@Composable
fun MapSearchBar(onOpen: () -> Unit, navigator: Navigator, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        onClick = onOpen, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        // The one raised surface on the map: tone alone does not separate it from light map tiles.
        shadowElevation = 3.dp, modifier = modifier.fillMaxWidth().height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.map_search_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Box(
                        Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_app_mark), contentDescription = stringResource(R.string.map_menu),
                            tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                    fun go(action: () -> Unit) { menu = false; action() }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_saved)) }, onClick = { go(navigator::saved) },
                        leadingIcon = { Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.lines_title)) }, onClick = { go(navigator::lines) },
                        leadingIcon = { Icon(Icons.Rounded.Route, contentDescription = null) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_title)) }, onClick = { go(navigator::settings) },
                        leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                    )
                }
            }
        }
    }
}

/**
 * The search covering the map: saved and nearby stops while nothing is typed, then matching stops and
 * lines. A stop opens its departure board, a line its line view; going back returns here.
 */
@Composable
fun MapSearchView(viewModel: MapSearchViewModel, navigator: Navigator, modifier: Modifier = Modifier) {
    val search by viewModel.search.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val saved by viewModel.savedStops.collectAsStateWithLifecycle()
    val requestPermission = rememberLocationPermission { granted -> if (granted) viewModel.locate() else viewModel.denied() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (viewModel.takeFocus()) focus.requestFocus() }
    val onStop: (Stop) -> Unit = { navigator.stop(it.id, it.name) }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            TextField(
                value = search.query, onValueChange = viewModel::query, singleLine = true,
                placeholder = { Text(stringResource(R.string.map_search_hint)) },
                leadingIcon = { IconButton(onClick = viewModel::close) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                trailingIcon = { if (search.query.isNotEmpty()) IconButton(onClick = { viewModel.query("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) } },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
            )
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = navigator::lines, label = { Text(stringResource(R.string.lines_title)) }, leadingIcon = { Icon(Icons.Rounded.Route, contentDescription = null) })
                    }
                }
                if (search.query.isNotBlank()) results(search, onStop, onLine = { navigator.line(it.key) })
                else suggestions(saved, nearby, onStop, requestPermission, viewModel::locate)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.results(search: SearchState, onStop: (Stop) -> Unit, onLine: (Line) -> Unit) {
    if (search.loading && search.stops.isEmpty() && search.lines.isEmpty()) item { TransitLoading() }
    search.error?.let { item { StatusBanner(Format.error(it), kind = BannerKind.ERROR) } }
    if (!search.loading && search.stops.isEmpty() && search.lines.isEmpty() && search.error == null && search.query.trim().length >= 2) item {
        Text(stringResource(R.string.search_no_results), Modifier.padding(16.dp))
    }
    if (search.stops.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.departures_results)) }
        itemsIndexed(search.stops, key = { _, s -> "r:" + s.id }) { i, stop -> StopItem(stop, i, search.stops.size, onStop) }
    }
    if (search.lines.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.lines_title)) }
        itemsIndexed(search.lines, key = { _, l -> "l:" + l.key }) { i, line ->
            SegmentedListItem(
                onClick = { onLine(line) },
                shapes = ListItemDefaults.segmentedShapes(i, search.lines.size), colors = groupedItemColors(), modifier = Modifier.groupGap(i, search.lines.size),
                leadingContent = { LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor) },
                supportingContent = { Text(Format.mode(line.mode)) },
            ) { Text(line.longName.ifBlank { line.name }, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.suggestions(
    saved: List<SavedItem>, nearby: NearbyState, onStop: (Stop) -> Unit, requestPermission: () -> Unit, retry: () -> Unit,
) {
    if (saved.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.departures_saved)) }
        itemsIndexed(saved, key = { _, s -> "s:" + s.id }) { i, item ->
            SegmentedListItem(
                onClick = { onStop(Stop(item.id, item.label, item.place?.point ?: Point(0.0, 0.0), stationKey = item.id)) },
                shapes = ListItemDefaults.segmentedShapes(i, saved.size), colors = groupedItemColors(), modifier = Modifier.groupGap(i, saved.size),
                leadingContent = { Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            ) { Text(item.label) }
        }
    }
    item { SectionHeader(stringResource(R.string.departures_nearby)) }
    when (nearby) {
        NearbyState.Idle -> item {
            MessageState(stringResource(R.string.departures_nearby_prompt), stringResource(R.string.departures_nearby_privacy), icon = Icons.Rounded.NearMe, action = stringResource(R.string.departures_use_location), onAction = requestPermission)
        }
        NearbyState.Locating -> item { TransitLoading() }
        NearbyState.PermissionDenied -> item {
            MessageState(stringResource(R.string.location_denied), stringResource(R.string.location_denied_hint), icon = Icons.Rounded.LocationOff)
        }
        NearbyState.Unavailable -> item { MessageState(stringResource(R.string.location_unavailable), icon = Icons.Rounded.LocationOff, action = stringResource(org.southtyrol.transit.design.R.string.ds_retry), onAction = retry) }
        is NearbyState.Error -> item { StatusBanner(Format.error(nearby.error), kind = BannerKind.ERROR) }
        is NearbyState.Found -> if (nearby.stops.isEmpty()) item {
            Text(stringResource(if (nearby.outsideRegion) R.string.departures_outside_region else R.string.departures_nearby_none), Modifier.padding(16.dp))
        } else {
            itemsIndexed(nearby.stops, key = { _, s -> "n:" + s.id }) { i, stop -> StopItem(stop, i, nearby.stops.size, onStop) }
        }
    }
}

@Composable
private fun StopItem(stop: Stop, index: Int, count: Int, onStop: (Stop) -> Unit) {
    SegmentedListItem(
        onClick = { onStop(stop) },
        shapes = ListItemDefaults.segmentedShapes(index, count), colors = groupedItemColors(), modifier = Modifier.groupGap(index, count),
        leadingContent = { ModeGlyph(TransportMode.BUS) },
        supportingContent = stop.distanceMeters?.let { { Text(Format.distance(it)) } },
    ) { Text(stop.name) }
}
