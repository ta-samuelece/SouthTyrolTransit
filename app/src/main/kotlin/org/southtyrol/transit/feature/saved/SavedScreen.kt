@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.saved

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditLocationAlt
import androidx.compose.material.icons.rounded.MoreVert
import org.southtyrol.transit.data.Codec
import org.southtyrol.transit.feature.common.PlaceEditorSheet
import org.southtyrol.transit.feature.common.PlaceGlyph
import org.southtyrol.transit.feature.common.PlaceSearchSheet
import org.southtyrol.transit.feature.common.PlacesViewModel
import org.southtyrol.transit.feature.common.placesInOrder
import org.southtyrol.transit.feature.common.savedPlaceLabel
import org.southtyrol.transit.feature.common.groupGap
import org.southtyrol.transit.feature.common.groupedItemColors
import org.southtyrol.transit.data.RecentItem
import org.southtyrol.transit.data.SavedItem
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.ui.Navigator
import javax.inject.Inject

@HiltViewModel
class SavedViewModel @Inject constructor(private val repo: SavedRepository) : ViewModel() {
    val saved: StateFlow<List<SavedItem>?> = repo.saved.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val recent: StateFlow<List<RecentItem>> = repo.recentJourneys(10).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun remove(item: SavedItem) = viewModelScope.launch { repo.remove(item.kind, item.id) }
    fun addStop(stop: org.southtyrol.transit.model.Stop) = viewModelScope.launch { repo.saveStop(stop) }
    fun addLine(line: org.southtyrol.transit.model.Line) = viewModelScope.launch { repo.saveLine(line) }
    fun clearRecents() = viewModelScope.launch { repo.clearRecents() }
}

@Composable
fun SavedScreen(navigator: Navigator, viewModel: SavedViewModel = hiltViewModel(), places: PlacesViewModel = hiltViewModel()) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    // Place flows: "Add place" searches then opens the editor; "Change location" searches for the item it names.
    var addingPlace by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var newPlace by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var relocating by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var renaming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val placeItems = saved.orEmpty().placesInOrder()
    var adding by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var addingLine by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (adding) org.southtyrol.transit.feature.common.StopSearchSheet(onPick = { viewModel.addStop(it); adding = false }, onDismiss = { adding = false })
    if (addingLine) org.southtyrol.transit.feature.common.LineSearchSheet(onPick = { viewModel.addLine(it); addingLine = false }, onDismiss = { addingLine = false })
    if (addingPlace) {
        PlaceSearchSheet(stringResource(R.string.place_add_hint), onChoose = { newPlace = Codec.place(it); addingPlace = false }, onDismiss = { addingPlace = false }, withSaved = false, viewModel = places)
    }
    newPlace?.let { Codec.place(it) }?.let { place -> PlaceEditorSheet(place, placeItems.firstOrNull { it.isPlace(place) }, onDismiss = { newPlace = null }, viewModel = places) }
    placeItems.firstOrNull { it.id == relocating }?.let { item ->
        PlaceSearchSheet(stringResource(R.string.place_add_hint), onChoose = { places.relocate(item, it); relocating = null }, onDismiss = { relocating = null }, withSaved = false, viewModel = places)
    }
    placeItems.firstOrNull { it.id == renaming }?.let { item ->
        item.place?.let { place -> PlaceEditorSheet(place, item, onDismiss = { renaming = null }, viewModel = places) }
    }
    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text(stringResource(R.string.settings_saved_manage)) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val items = saved ?: return@Scaffold
        val stops = items.filter { it.kind == SavedKind.STOP }
        val lines = items.filter { it.kind == SavedKind.LINE }
        val journeys = items.filter { it.kind == SavedKind.JOURNEY }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                // Saved stops and lines drive the "My stops and lines" alert filter and notifications.
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { adding = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.saved_add_stop))
                    }
                    androidx.compose.material3.FilledTonalButton(onClick = { addingLine = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.saved_add_line))
                    }
                    androidx.compose.material3.FilledTonalButton(onClick = { addingPlace = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.place_add))
                    }
                }
            }
            if (items.isEmpty() && recent.isEmpty()) item {
                MessageState(stringResource(R.string.saved_empty), stringResource(R.string.saved_empty_hint), icon = Icons.Rounded.BookmarkBorder)
            }
            // Home and Work first, then the user's own places, which alone can be renamed and reordered.
            val own = placeItems.filterNot { it.isHomeOrWork }
            section(R.string.saved_places, placeItems) { item, index ->
                val position = own.indexOf(item)
                SavedPlaceRow(
                    item, index, placeItems.size,
                    onRename = if (item.isHomeOrWork) null else { { renaming = item.id } },
                    onRelocate = { relocating = item.id },
                    onMoveUp = if (position > 0) { { places.move(item, up = true) } } else null,
                    onMoveDown = if (position in 0 until own.size - 1) { { places.move(item, up = false) } } else null,
                    onRemove = { places.remove(item) },
                    onClick = { navigator.plan() },
                )
            }
            section(R.string.saved_stops, stops) { item, index ->
                SavedRow(item, index, stops.size, Icons.Rounded.Star, onRemove = { viewModel.remove(item) }) { navigator.stop(item.id, item.label) }
            }
            section(R.string.saved_lines, lines) { item, index ->
                val mode = runCatching { TransportMode.valueOf(item.subtitle.substringBefore('|')) }.getOrDefault(TransportMode.BUS)
                SegmentedListItem(
                    onClick = { navigator.line(item.id) },
                    shapes = ListItemDefaults.segmentedShapes(index, lines.size), colors = groupedItemColors(), modifier = Modifier.groupGap(index, lines.size),
                    leadingContent = { LineBadge(item.label, mode) },
                    trailingContent = { IconButton(onClick = { viewModel.remove(item) }) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_remove)) } },
                ) { Text(item.subtitle.substringAfter('|').ifBlank { item.label }) }
            }
            section(R.string.saved_journeys, journeys) { item, index ->
                SavedRow(item, index, journeys.size, Icons.Rounded.Bookmark, onRemove = { viewModel.remove(item) }) {
                    item.route?.let { (from, to) -> navigator.results(leaveNow(from, to)) }
                }
            }
            if (recent.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.saved_recent)) { TextButton(onClick = { viewModel.clearRecents() }) { Text(stringResource(R.string.action_clear)) } }
                }
                itemsIndexed(recent, key = { _, r -> "r:" + r.id }, contentType = { _, _ -> "recent" }) { index, r ->
                    SegmentedListItem(
                        onClick = { r.route?.let { (from, to) -> navigator.results(leaveNow(from, to)) } },
                        shapes = ListItemDefaults.segmentedShapes(index, recent.size), colors = groupedItemColors(), modifier = Modifier.groupGap(index, recent.size),
                        leadingContent = { Icon(Icons.Rounded.History, contentDescription = null) },
                    ) { Text(r.label) }
                }
            }
            item { Text(stringResource(R.string.saved_local_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp)) }
        }
    }
}

private fun leaveNow(from: org.southtyrol.transit.model.Place, to: org.southtyrol.transit.model.Place) =
    org.southtyrol.transit.feature.planner.RequestCodec.encode(org.southtyrol.transit.data.JourneyRequest(from, to, org.southtyrol.transit.model.SearchOptions()), leaveNow = true)

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: Int, items: List<SavedItem>, row: @Composable (SavedItem, Int) -> Unit) {
    if (items.isEmpty()) return
    item(contentType = "header") { SectionHeader(stringResource(title)) }
    itemsIndexed(items, key = { _, i -> i.kind.name + i.id }, contentType = { _, i -> i.kind.name }) { index, item -> row(item, index) }
}

@Composable
private fun SavedRow(item: SavedItem, index: Int, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onRemove: () -> Unit, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count), colors = groupedItemColors(), modifier = Modifier.groupGap(index, count),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        supportingContent = item.subtitle.takeIf { it.isNotBlank() && item.kind == SavedKind.PLACE }?.let { { Text(it) } },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_remove)) } },
    ) { Text(item.label) }
}

@Composable
private fun SavedPlaceRow(
    item: SavedItem, index: Int, count: Int,
    onRename: (() -> Unit)?, onRelocate: () -> Unit, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?, onRemove: () -> Unit, onClick: () -> Unit,
) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val label = savedPlaceLabel(item)
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count), colors = groupedItemColors(), modifier = Modifier.groupGap(index, count),
        leadingContent = { PlaceGlyph(item) },
        supportingContent = item.subtitle.takeIf { it.isNotBlank() }?.let { { Text(it) } },
        trailingContent = {
            androidx.compose.foundation.layout.Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.place_options, label)) }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val entries = listOfNotNull(
                        onRename?.let { Triple(R.string.place_rename, Icons.Rounded.Edit, it) },
                        Triple(R.string.place_change_location, Icons.Rounded.EditLocationAlt, onRelocate),
                        onMoveUp?.let { Triple(R.string.place_move_up, Icons.Rounded.ArrowUpward, it) },
                        onMoveDown?.let { Triple(R.string.place_move_down, Icons.Rounded.ArrowDownward, it) },
                        Triple(R.string.action_remove, Icons.Rounded.Delete, onRemove),
                    )
                    entries.forEach { (text, icon, action) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(text)) },
                            leadingIcon = { Icon(icon, contentDescription = null) },
                            onClick = { menu = false; action() },
                        )
                    }
                }
            }
        },
    ) { Text(label) }
}
