@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, FlowPreview::class)

package org.southtyrol.transit.feature.departures

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocationOff
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
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
import org.southtyrol.transit.R
import org.southtyrol.transit.data.EfaClient
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.SavedItem
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.ModeGlyph
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.feature.common.TopLevelBar
import org.southtyrol.transit.feature.common.rememberLocationPermission
import org.southtyrol.transit.feature.stop.StopContent
import org.southtyrol.transit.feature.stop.StopViewModel
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
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

data class StopSearchState(val query: String = "", val loading: Boolean = false, val results: List<Stop> = emptyList(), val error: DataError? = null)

@HiltViewModel
class DeparturesViewModel @Inject constructor(
    private val schedule: TransitScheduleDataSource,
    private val efa: EfaClient,
    private val location: LocationProvider,
    private val language: LanguageProvider,
    saved: SavedRepository,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val _search = MutableStateFlow(StopSearchState(handle.get<String>("q").orEmpty()))
    val search: StateFlow<StopSearchState> = _search.asStateFlow()
    private val _nearby = MutableStateFlow<NearbyState>(NearbyState.Idle)
    val nearby: StateFlow<NearbyState> = _nearby.asStateFlow()
    private val _selected = MutableStateFlow(handle.get<String>("selected"))
    val selected: StateFlow<String?> = _selected.asStateFlow()

    val savedStops: StateFlow<List<SavedItem>> = saved.saved.map { list -> list.filter { it.kind == SavedKind.STOP } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            // Only a changed query starts a search: the search's own updates (loading, results) must not
            // re-trigger it, or the list keeps reloading in a loop.
            _search.map { it.query.trim() }.distinctUntilChanged().debounce(250).collectLatest { query ->
                if (query.length < 2) { _search.update { it.copy(results = emptyList(), loading = false, error = null) }; return@collectLatest }
                _search.update { it.copy(loading = true) }
                val lang = language.current()
                try {
                    val results = if (schedule.isAvailable()) schedule.searchStops(query, lang) else efaStops(query, lang)
                    _search.update { it.copy(results = results, loading = false, error = null) }
                } catch (e: DataException) {
                    _search.update { it.copy(results = emptyList(), loading = false, error = e.error) }
                }
            }
        }
        if (location.hasPermission()) locate()
    }

    /** Without a cached timetable, stop search goes through the EFA stop finder. */
    private suspend fun efaStops(query: String, lang: String): List<Stop> = efa.search(query, lang)
        .filter { it.type == PlaceType.STOP && it.stopGlobalId.isNotBlank() && it.point != null }
        .map { Stop(it.stopGlobalId, it.name, it.point!!, stationKey = it.stopGlobalId) }

    fun query(value: String) { handle["q"] = value; _search.update { it.copy(query = value) } }
    fun select(key: String?) { handle["selected"] = key; _selected.value = key }

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

@Composable
fun DeparturesScreen(navigator: Navigator, viewModel: DeparturesViewModel = hiltViewModel()) {
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopLevelBar(stringResource(R.string.nav_departures)) }) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val twoPane = maxWidth >= 840.dp
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    StopList(viewModel, navigator, Modifier.weight(0.4f).fillMaxHeight()) { stop -> viewModel.select(stop.id) }
                    VerticalDivider()
                    val key = selected
                    if (key != null) {
                        val stopVm = hiltViewModel<StopViewModel, StopViewModel.Factory>(key = key) { it.create(key, "") }
                        StopContent(stopVm, navigator, Modifier.weight(0.6f).fillMaxHeight(), showHeader = true)
                    } else {
                        MessageState(stringResource(R.string.departures_pick_stop), modifier = Modifier.weight(0.6f))
                    }
                }
            } else {
                StopList(viewModel, navigator, Modifier.fillMaxSize()) { stop -> navigator.stop(stop.id, stop.name) }
            }
        }
    }
}

@Composable
private fun StopList(viewModel: DeparturesViewModel, navigator: Navigator, modifier: Modifier, onStop: (Stop) -> Unit) {
    val search by viewModel.search.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val saved by viewModel.savedStops.collectAsStateWithLifecycle()
    val requestPermission = rememberLocationPermission { granted -> if (granted) viewModel.locate() else viewModel.denied() }
    var mapOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (mapOpen) {
        org.southtyrol.transit.feature.common.MapPickerDialog(
            allowPoint = false,
            onStop = { mapOpen = false; onStop(it) },
            onPoint = {},
            onDismiss = { mapOpen = false },
        )
    }

    LazyColumn(modifier, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            OutlinedTextField(
                value = search.query, onValueChange = viewModel::query, singleLine = true,
                placeholder = { Text(stringResource(R.string.departures_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (search.query.isNotEmpty()) IconButton(onClick = { viewModel.query("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) } },
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = requestPermission, label = { Text(stringResource(R.string.departures_nearby)) }, leadingIcon = { Icon(Icons.Rounded.NearMe, contentDescription = null) })
                AssistChip(onClick = { mapOpen = true }, label = { Text(stringResource(R.string.pick_on_map_short)) }, leadingIcon = { Icon(Icons.Rounded.Map, contentDescription = null) })
                AssistChip(onClick = navigator::lines, label = { Text(stringResource(R.string.lines_title)) }, leadingIcon = { Icon(Icons.Rounded.Route, contentDescription = null) })
            }
        }
        if (search.query.isNotBlank()) {
            item { SectionHeader(stringResource(R.string.departures_results)) }
            if (search.loading) item { TransitLoading() }
            search.error?.let { item { StatusBanner(Format.error(it), kind = BannerKind.ERROR) } }
            if (!search.loading && search.results.isEmpty() && search.error == null && search.query.trim().length >= 2) item {
                Text(stringResource(R.string.search_no_results), Modifier.padding(16.dp))
            }
            itemsIndexed(search.results, key = { _, s -> "r:" + s.id }) { i, stop -> StopItem(stop, i, search.results.size, onStop) }
        } else {
            if (saved.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.departures_saved)) }
                itemsIndexed(saved, key = { _, s -> "s:" + s.id }) { i, item ->
                    SegmentedListItem(
                        onClick = { onStop(Stop(item.id, item.label, item.place?.point ?: Point(0.0, 0.0), stationKey = item.id)) },
                        shapes = ListItemDefaults.segmentedShapes(i, saved.size),
                        leadingContent = { Icon(Icons.Rounded.Star, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    ) { Text(item.label) }
                }
            }
            item { SectionHeader(stringResource(R.string.departures_nearby)) }
            when (val n = nearby) {
                NearbyState.Idle -> item {
                    MessageState(stringResource(R.string.departures_nearby_prompt), stringResource(R.string.departures_nearby_privacy), icon = Icons.Rounded.NearMe, action = stringResource(R.string.departures_use_location), onAction = requestPermission)
                }
                NearbyState.Locating -> item { TransitLoading() }
                NearbyState.PermissionDenied -> item {
                    MessageState(stringResource(R.string.location_denied), stringResource(R.string.location_denied_hint), icon = Icons.Rounded.LocationOff)
                }
                NearbyState.Unavailable -> item { MessageState(stringResource(R.string.location_unavailable), icon = Icons.Rounded.LocationOff, action = stringResource(org.southtyrol.transit.design.R.string.ds_retry), onAction = viewModel::locate) }
                is NearbyState.Error -> item { StatusBanner(Format.error(n.error), kind = BannerKind.ERROR) }
                is NearbyState.Found -> if (n.stops.isEmpty()) item {
                    Text(stringResource(if (n.outsideRegion) R.string.departures_outside_region else R.string.departures_nearby_none), Modifier.padding(16.dp))
                } else {
                    itemsIndexed(n.stops, key = { _, s -> "n:" + s.id }) { i, stop -> StopItem(stop, i, n.stops.size, onStop) }
                }
            }
        }
    }
}

@Composable
private fun StopItem(stop: Stop, index: Int, count: Int, onStop: (Stop) -> Unit) {
    SegmentedListItem(
        onClick = { onStop(stop) },
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { ModeGlyph(TransportMode.BUS) },
        supportingContent = stop.distanceMeters?.let { { Text(Format.distance(it)) } },
    ) { Text(stop.name) }
}
