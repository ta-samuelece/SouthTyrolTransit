@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.saved

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Work
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
fun SavedScreen(navigator: Navigator, viewModel: SavedViewModel = hiltViewModel()) {
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    var adding by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var addingLine by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (adding) org.southtyrol.transit.feature.common.StopSearchSheet(onPick = { viewModel.addStop(it); adding = false }, onDismiss = { adding = false })
    if (addingLine) org.southtyrol.transit.feature.common.LineSearchSheet(onPick = { viewModel.addLine(it); addingLine = false }, onDismiss = { addingLine = false })
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
        val places = items.filter { it.kind == SavedKind.PLACE }
        val stops = items.filter { it.kind == SavedKind.STOP }
        val lines = items.filter { it.kind == SavedKind.LINE }
        val journeys = items.filter { it.kind == SavedKind.JOURNEY }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item {
                // Saved stops and lines drive the "My stops and lines" alert filter and notifications.
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { adding = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.saved_add_stop))
                    }
                    androidx.compose.material3.FilledTonalButton(onClick = { addingLine = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = null); androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.saved_add_line))
                    }
                }
            }
            if (items.isEmpty() && recent.isEmpty()) item {
                MessageState(stringResource(R.string.saved_empty), stringResource(R.string.saved_empty_hint), icon = Icons.Rounded.BookmarkBorder)
            }
            section(R.string.saved_places, places) { item, index ->
                SavedRow(item, index, places.size, when (item.id) { SavedRepository.HOME -> Icons.Rounded.Home; SavedRepository.WORK -> Icons.Rounded.Work; else -> Icons.Rounded.Place }, onRemove = { viewModel.remove(item) }) {
                    navigator.plan()
                }
            }
            section(R.string.saved_stops, stops) { item, index ->
                SavedRow(item, index, stops.size, Icons.Rounded.Star, onRemove = { viewModel.remove(item) }) { navigator.stop(item.id, item.label) }
            }
            section(R.string.saved_lines, lines) { item, index ->
                val mode = runCatching { TransportMode.valueOf(item.subtitle.substringBefore('|')) }.getOrDefault(TransportMode.BUS)
                SegmentedListItem(
                    onClick = { navigator.line(item.id) },
                    shapes = ListItemDefaults.segmentedShapes(index, lines.size),
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
                itemsIndexed(recent, key = { _, r -> "r:" + r.id }) { index, r ->
                    SegmentedListItem(
                        onClick = { r.route?.let { (from, to) -> navigator.results(leaveNow(from, to)) } },
                        shapes = ListItemDefaults.segmentedShapes(index, recent.size),
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
    item { SectionHeader(stringResource(title)) }
    itemsIndexed(items, key = { _, i -> i.kind.name + i.id }) { index, item -> row(item, index) }
}

@Composable
private fun SavedRow(item: SavedItem, index: Int, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onRemove: () -> Unit, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        supportingContent = item.subtitle.takeIf { it.isNotBlank() && item.kind == SavedKind.PLACE }?.let { { Text(it) } },
        trailingContent = { IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.action_remove)) } },
    ) { Text(item.label) }
}
