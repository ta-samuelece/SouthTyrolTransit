@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, FlowPreview::class)

package org.southtyrol.transit.feature.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LocalHospital
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.Signpost
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TheaterComedy
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.PlacesRepository
import org.southtyrol.transit.data.SavedItem
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.ConnectedToggleGroup
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.icon
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.TransportMode
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------
// Saved places: Home, Work and the user's own, with the search and editor sheets that create them.
// ---------------------------------------------------------------------------------------------

/** The icons a place of the user's own can carry. [key] is what is stored; never rename one. */
enum class PlaceIcon(val key: String, val vector: ImageVector, val label: Int) {
    STAR("star", Icons.Rounded.Star, R.string.place_icon_star),
    HEART("heart", Icons.Rounded.Favorite, R.string.place_icon_heart),
    SCHOOL("school", Icons.Rounded.School, R.string.place_icon_school),
    SPORT("sport", Icons.Rounded.FitnessCenter, R.string.place_icon_sport),
    SHOPPING("shopping", Icons.Rounded.ShoppingCart, R.string.place_icon_shopping),
    FOOD("food", Icons.Rounded.Restaurant, R.string.place_icon_food),
    DOCTOR("doctor", Icons.Rounded.LocalHospital, R.string.place_icon_doctor),
    MOUNTAINS("mountains", Icons.Rounded.Landscape, R.string.place_icon_mountains),
    PERSON("person", Icons.Rounded.Person, R.string.place_icon_person),
    THEATRE("theatre", Icons.Rounded.TheaterComedy, R.string.place_icon_theatre),
    STATION("station", Icons.Rounded.Train, R.string.place_icon_station),
    PIN("pin", Icons.Rounded.Place, R.string.place_icon_pin),
    ;

    companion object {
        fun of(key: String): PlaceIcon? = entries.firstOrNull { it.key == key }
    }
}

fun savedPlaceIcon(item: SavedItem): ImageVector = when (item.id) {
    SavedRepository.HOME -> Icons.Rounded.Home
    SavedRepository.WORK -> Icons.Rounded.Work
    else -> PlaceIcon.of(item.icon)?.vector ?: Icons.Rounded.Place
}

/** Home and Work are named in the current language, whatever it was when they were saved. */
@Composable
fun savedPlaceLabel(item: SavedItem): String = when (item.id) {
    SavedRepository.HOME -> stringResource(R.string.place_home)
    SavedRepository.WORK -> stringResource(R.string.place_work)
    else -> item.label
}

/** Saved places as they are listed: Home, Work, then the user's own in their saved order. */
fun List<SavedItem>.placesInOrder(): List<SavedItem> =
    filter { it.kind == SavedKind.PLACE && it.place != null }
        .sortedBy { when (it.id) { SavedRepository.HOME -> 0; SavedRepository.WORK -> 1; else -> 2 } }

fun placeIcon(type: PlaceType): ImageVector = when (type) {
    PlaceType.STOP -> TransportMode.BUS.icon()
    PlaceType.POI -> Icons.Rounded.Place
    PlaceType.ADDRESS, PlaceType.STREET -> Icons.Rounded.Signpost
    PlaceType.COORDINATE -> Icons.Rounded.MyLocation
    else -> Icons.Rounded.LocationOn
}

@Composable
fun placeTypeLabel(type: PlaceType): String = stringResource(
    when (type) {
        PlaceType.STOP -> R.string.place_type_stop
        PlaceType.POI -> R.string.place_type_poi
        PlaceType.ADDRESS, PlaceType.STREET -> R.string.place_type_address
        PlaceType.LOCALITY -> R.string.place_type_locality
        else -> R.string.place_type_place
    },
)

data class PlaceQueryState(val query: String = "", val results: List<Place> = emptyList(), val loading: Boolean = false, val error: DataError? = null)

/** Place search (stops, addresses, POIs) and every change to saved places, shared by the planner and the Saved screen. */
@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val places: PlacesRepository,
    private val saved: SavedRepository,
    private val language: LanguageProvider,
) : ViewModel() {
    private val _state = MutableStateFlow(PlaceQueryState())
    val state: StateFlow<PlaceQueryState> = _state.asStateFlow()

    val savedPlaces: StateFlow<List<SavedItem>> = saved.saved.map { it.placesInOrder() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recentPlaces: StateFlow<List<Place>> = saved.recentPlaces(8).map { items -> items.mapNotNull { it.place } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            // Only a changed query starts a search, never the search's own updates.
            _state.map { it.query.trim() }.distinctUntilChanged().debounce(300).collectLatest { query ->
                if (query.length < 2) { _state.update { it.copy(results = emptyList(), loading = false, error = null) }; return@collectLatest }
                _state.update { it.copy(loading = true) }
                val (results, error) = places.search(query, language.current())
                _state.update { it.copy(results = results, loading = false, error = error) }
            }
        }
    }

    fun query(text: String) = _state.update { it.copy(query = text) }
    fun reset() { _state.value = PlaceQueryState() }

    /** Any point picked on the map, labelled with the nearest address; planned as a coordinate. */
    fun resolvePoint(point: Point, fallbackName: String, onPlace: (Place) -> Unit) = viewModelScope.launch {
        val nearest = places.reverse(point, language.current())
        onPlace(Place("", nearest.name.ifBlank { fallbackName }, PlaceType.ADDRESS, point, nearest.locality))
    }

    /** Saves [place] as Home or Work (fixed ids), as a new place ([id] null) or over the saved place [id]. */
    fun save(id: String?, label: String, icon: String, place: Place) = viewModelScope.launch {
        if (id == null) saved.addPlace(label, icon, place) else saved.savePlace(id, label, place, icon)
    }

    fun relocate(item: SavedItem, place: Place) = viewModelScope.launch { saved.savePlace(item.id, item.label, place, item.icon) }
    fun move(item: SavedItem, up: Boolean) = viewModelScope.launch { saved.movePlace(item.id, up) }
    fun remove(item: SavedItem) = viewModelScope.launch { saved.remove(SavedKind.PLACE, item.id) }
}

/**
 * Bottom sheet for choosing a stop, address, POI or point on the map. [onUseLocation] adds the current
 * location row; [withSaved] lists saved and recent places before typing and offers a star on each result,
 * which saves the place (or edits it, when it is saved already).
 */
@Composable
fun PlaceSearchSheet(
    hint: String,
    onChoose: (Place) -> Unit,
    onDismiss: () -> Unit,
    onUseLocation: (() -> Unit)? = null,
    withSaved: Boolean = true,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    val recents by viewModel.recentPlaces.collectAsStateWithLifecycle()
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Place?>(null) }
    val pointName = stringResource(R.string.point_on_map)
    if (mapOpen) {
        MapPickerDialog(
            allowPoint = true,
            onStop = { stop ->
                mapOpen = false
                // The journey planner accepts the network stop id (gid) directly.
                val id = stop.stationKey.ifBlank { stop.id }
                onChoose(Place(id, stop.name, PlaceType.STOP, stop.point, "", id))
            },
            onPoint = { mapOpen = false; viewModel.resolvePoint(it, pointName, onChoose) },
            onDismiss = { mapOpen = false },
        )
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        viewModel.reset()
        runCatching { focus.requestFocus() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::query,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(hint) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.query("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { state.results.firstOrNull()?.let(onChoose) }),
            )
            Spacer(Modifier.size(8.dp))
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxHeight().imePadding()) {
            if (state.query.isBlank()) {
                val actions = listOfNotNull(
                    onUseLocation?.let { Triple(R.string.location_use_current, Icons.Rounded.MyLocation, it) },
                    Triple(R.string.pick_on_map, Icons.Rounded.Map, { mapOpen = true }),
                )
                itemsIndexed(actions) { index, (label, icon, action) -> SheetItem(stringResource(label), null, icon, index, actions.size, onClick = action) }
                if (withSaved && savedPlaces.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.planner_saved_places)) }
                    itemsIndexed(savedPlaces, key = { _, s -> "s:" + s.id }) { index, item ->
                        val place = item.place ?: return@itemsIndexed
                        SheetItem(savedPlaceLabel(item), place.name, savedPlaceIcon(item), index, savedPlaces.size) { onChoose(place) }
                    }
                }
                if (withSaved && recents.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.planner_recent_places)) }
                    itemsIndexed(recents) { index, place -> SheetItem(place.name, locality(place), Icons.Rounded.History, index, recents.size) { onChoose(place) } }
                }
            } else {
                if (state.loading) item { TransitLoading() }
                state.error?.let { error ->
                    item { StatusBanner(Format.error(error) + if (state.results.isNotEmpty()) "\n" + stringResource(R.string.search_offline_results) else "", kind = BannerKind.WARNING) }
                }
                if (!state.loading && state.results.isEmpty() && state.error == null && state.query.trim().length >= 2) {
                    item { Text(stringResource(R.string.search_no_results), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                itemsIndexed(state.results, key = { _, p -> p.id + p.name }) { index, place ->
                    val savedAs = if (withSaved) savedPlaces.firstOrNull { it.isPlace(place) } else null
                    SegmentedListItem(
                        onClick = { onChoose(place) },
                        shapes = ListItemDefaults.segmentedShapes(index, state.results.size), colors = groupedItemColors(), modifier = Modifier.groupGap(index, state.results.size),
                        leadingContent = { Icon(savedAs?.let(::savedPlaceIcon) ?: placeIcon(place.type), contentDescription = placeTypeLabel(place.type)) },
                        supportingContent = (listOfNotNull(savedAs?.let { savedPlaceLabel(it) }, locality(place)).joinToString(" · ").ifBlank { null })?.let { { Text(it) } },
                        trailingContent = if (!withSaved) null else {
                            {
                                IconButton(onClick = { editing = place }) {
                                    if (savedAs != null) Icon(Icons.Rounded.Star, contentDescription = stringResource(R.string.place_edit_saved, savedPlaceLabel(savedAs)), tint = MaterialTheme.colorScheme.primary)
                                    else Icon(Icons.Rounded.StarBorder, contentDescription = stringResource(R.string.place_save, place.name))
                                }
                            }
                        },
                    ) { Text(place.name) }
                }
            }
        }
    }
    // After the search sheet, so the editor opens on top of it.
    editing?.let { place -> PlaceEditorSheet(place, savedPlaces.firstOrNull { it.isPlace(place) }, onDismiss = { editing = null }, viewModel = viewModel) }
}

private fun locality(place: Place): String? = place.locality.takeIf { it.isNotBlank() && !place.name.contains(it) }

@Composable
private fun SheetItem(title: String, subtitle: String?, icon: ImageVector, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count), colors = groupedItemColors(), modifier = Modifier.groupGap(index, count),
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
    ) { Text(title) }
}

private enum class Slot { HOME, WORK, OTHER }

/**
 * Saves [place], or edits [existing] when it is saved already: Home and Work are chosen here, a place of the
 * user's own gets a name and an icon. Choosing a slot that holds another place says it will be replaced.
 */
@Composable
fun PlaceEditorSheet(place: Place, existing: SavedItem?, onDismiss: () -> Unit, viewModel: PlacesViewModel = hiltViewModel()) {
    val savedPlaces by viewModel.savedPlaces.collectAsStateWithLifecycle()
    var slot by rememberSaveable { mutableStateOf(Slot.OTHER.name) }
    var name by rememberSaveable { mutableStateOf(existing?.label ?: place.name.take(MAX_NAME)) }
    var icon by rememberSaveable { mutableStateOf(existing?.icon?.ifBlank { null } ?: PlaceIcon.STAR.key) }
    val chosen = Slot.valueOf(slot)
    val homeLabel = stringResource(R.string.place_home)
    val workLabel = stringResource(R.string.place_work)
    val fixed = existing?.isHomeOrWork == true
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.place_save_title), style = MaterialTheme.typography.titleLarge)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(placeIcon(place.type), contentDescription = null)
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(place.name, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(placeTypeLabel(place.type), locality(place)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when {
                fixed -> Text(stringResource(R.string.place_saved_as, savedPlaceLabel(existing!!)), style = MaterialTheme.typography.bodyLarge)
                existing == null -> {
                    Text(stringResource(R.string.place_save_as), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    ConnectedToggleGroup(
                        options = Slot.entries.toList(), selected = chosen, onSelect = { slot = it.name },
                        label = { stringResource(when (it) { Slot.HOME -> R.string.place_home; Slot.WORK -> R.string.place_work; Slot.OTHER -> R.string.place_other }) },
                        icon = { when (it) { Slot.HOME -> Icons.Rounded.Home; Slot.WORK -> Icons.Rounded.Work; Slot.OTHER -> Icons.Rounded.Star } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (existing == null && chosen != Slot.OTHER) {
                val taken = savedPlaces.firstOrNull { it.id == if (chosen == Slot.HOME) SavedRepository.HOME else SavedRepository.WORK }?.place
                if (taken != null) {
                    StatusBanner(
                        stringResource(if (chosen == Slot.HOME) R.string.place_replaces_home else R.string.place_replaces_work, taken.name),
                        kind = BannerKind.WARNING,
                    )
                }
            }
            if (!fixed && chosen == Slot.OTHER) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME) },
                    label = { Text(stringResource(R.string.place_name)) },
                    supportingText = { Text(stringResource(R.string.place_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.place_icon), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceIcon.entries.forEach { option -> IconChoice(option, selected = option.key == icon) { icon = option.key } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (existing != null) TextButton(onClick = { viewModel.remove(existing); onDismiss() }) { Text(stringResource(R.string.action_remove)) }
                Spacer(Modifier.weight(1f))
                if (fixed) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Button(
                        enabled = chosen != Slot.OTHER || name.isNotBlank(),
                        onClick = {
                            when (chosen) {
                                Slot.HOME -> viewModel.save(SavedRepository.HOME, homeLabel, "", place)
                                Slot.WORK -> viewModel.save(SavedRepository.WORK, workLabel, "", place)
                                Slot.OTHER -> viewModel.save(existing?.id, name.trim(), icon, place)
                            }
                            onDismiss()
                        },
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}

private const val MAX_NAME = 30

@Composable
private fun IconChoice(option: PlaceIcon, selected: Boolean, onClick: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(option.vector, contentDescription = stringResource(option.label)) }
    }
}

/** The round glyph a saved place is shown with on cards and in lists. */
@Composable
fun PlaceGlyph(item: SavedItem, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, modifier = modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(savedPlaceIcon(item), contentDescription = null, modifier = Modifier.size(22.dp)) }
    }
}
