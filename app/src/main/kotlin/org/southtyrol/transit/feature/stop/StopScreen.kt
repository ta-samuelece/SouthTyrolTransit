@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.stop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.data.BoardSource
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.ConnectedToggleGroup
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.WheelchairIcon
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.feature.common.DepartureRow
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.map.TransitMap
import org.southtyrol.transit.model.Accessibility
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.ui.Navigator

@Composable
fun StopScreen(navigator: Navigator, stationKey: String, name: String) {
    val viewModel = hiltViewModel<StopViewModel, StopViewModel.Factory>(key = stationKey) { it.create(stationKey, name) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.isSaved.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(state.name.ifBlank { stringResource(R.string.stop_title) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                subtitle = state.stop?.code?.takeIf { it.isNotBlank() }?.let { { Text(stringResource(R.string.stop_code, it)) } },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    IconButton(onClick = { viewModel.toggleSaved() }) {
                        Icon(if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = stringResource(if (saved) R.string.action_unsave_stop else R.string.action_save_stop))
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        StopContent(viewModel, navigator, Modifier.padding(padding).fillMaxSize())
    }
}

/** Live board + stop details; also used as the detail pane next to the stop list on large screens. */
@Composable
fun StopContent(viewModel: StopViewModel, navigator: Navigator, modifier: Modifier = Modifier, showHeader: Boolean = false) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    val now = rememberNow()
    val mapState = org.southtyrol.transit.feature.common.rememberExpandableMapState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var showAllLines by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var pickTime by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (pickTime) org.southtyrol.transit.feature.common.DateTimePickerDialogs(
        initial = state.start,
        onPicked = { pickTime = false; viewModel.setTime(it) },
        onDismiss = { pickTime = false },
    )
    val dark = LocalDarkTheme.current
    val language = AppLanguage.current()

    // Live screens refresh every 30 s only while visible.
    PollWhileVisible(30_000, key = state.arrivals) { viewModel.refresh() }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; viewModel.refresh(force = true); refreshing = false } },
        modifier = modifier,
    ) {
        val points = state.platforms.map { it.point }.filter { it.isValid }
        // The chosen direction's platforms are highlighted on the map.
        val mapContent = MapContent(
            stops = state.platforms.map { MapMarker(it.id, it.point, MarkerKind.STOP, it.platform, selected = state.direction?.platformIds?.contains(it.id) ?: true) },
            clusterStops = false,
        )
        val camera = CameraRequest.Fit(points + points.take(1))
        // The small map is the last thing on the page, so the enlarged one stays at the bottom too.
        org.southtyrol.transit.feature.common.ExpandableMapPage(
            mapState, points.isNotEmpty(), mapContent, camera, Modifier.fillMaxSize(),
            placement = org.southtyrol.transit.feature.common.MapPlacement.BOTTOM,
        ) { listModifier ->
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = listModifier) {
            if (showHeader) item {
                Text(state.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.lines.isNotEmpty()) FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Large stations serve dozens of lines; keep the live board in view.
                        // Tap a line to show only its departures (several can be combined); long-press opens the line.
                        val shown = if (showAllLines) state.lines else state.lines.take(8)
                        shown.forEach { line ->
                            LineFilterBadge(
                                line, selected = line.key in state.lineFilter, anySelected = state.lineFilter.isNotEmpty(),
                                onToggle = { viewModel.toggleLine(line.key) }, onOpen = { navigator.line(line.key) },
                            )
                        }
                        if (state.lines.size > 8) {
                            MoreLinesPill(if (showAllLines) stringResource(R.string.action_collapse) else "+${state.lines.size - 8}") { showAllLines = !showAllLines }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.distanceMeters?.let { Text(stringResource(R.string.stop_distance, Format.distance(it)), style = MaterialTheme.typography.labelLarge) }
                        if (state.stop?.wheelchair == Accessibility.ACCESSIBLE) WheelchairIcon()
                    }
                    if (state.lines.size > 1) Text(
                        stringResource(R.string.stop_line_filter_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Hidden where arrivals would just repeat the departures (most intermediate stops).
                    if (state.showArrivalsToggle || state.arrivals) ConnectedToggleGroup(
                        options = listOf(false, true), selected = state.arrivals, onSelect = viewModel::setArrivals,
                        label = { stringResource(if (it) R.string.stop_arrivals else R.string.stop_departures) }, modifier = Modifier.fillMaxWidth(),
                    )
                    // Two-way stops: pick a side of the road, named after where the buses go next.
                    if (state.directions.size >= 2 && board?.source != BoardSource.NETWORK) {
                        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Single choice, styled like the choice chips in Settings: a checkmark marks the selected one.
                            DirectionChip(stringResource(R.string.stop_all_directions), state.direction == null) { viewModel.setDirection(null) }
                            state.directions.forEach { direction ->
                                DirectionChip(direction.towards.joinToString(" · "), state.direction == direction) {
                                    viewModel.setDirection(if (state.direction == direction) null else direction)
                                }
                            }
                        }
                    }
                    board?.let { FreshnessIndicator(it.realtime, it.realtimeFetchedAt ?: state.loadedAt, now) }
                    if (board?.source == BoardSource.NETWORK) StatusBanner(stringResource(R.string.stop_network_board), kind = BannerKind.INFO)
                    else if (board?.realtime == Freshness.STALE || board?.realtime == Freshness.UNAVAILABLE) StatusBanner(stringResource(R.string.stop_realtime_unavailable), kind = BannerKind.WARNING)
                }
            }
            item { SectionHeader(stringResource(if (state.arrivals) R.string.stop_arrivals else R.string.stop_departures)) }
            item {
                // When: live ("Now") or a chosen date and time, plus a way back to live.
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.FilterChip(
                        selected = !state.live,
                        onClick = { pickTime = true },
                        leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        label = { Text(state.start?.let { Format.dateTime(it) } ?: stringResource(R.string.stop_time_now)) },
                    )
                    if (!state.live) androidx.compose.material3.TextButton(onClick = viewModel::now) { Text(stringResource(R.string.stop_back_to_now)) }
                }
            }
            val all = board?.departures.orEmpty()
            val list = visible
            val filtered = state.lineFilter.isNotEmpty() || (state.direction != null && board?.source == BoardSource.SCHEDULE)
            if (list.isNotEmpty()) item {
                androidx.compose.material3.TextButton(
                    onClick = viewModel::earlier, enabled = !state.loadingMore,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ExpandLess, contentDescription = null)
                    Text(stringResource(if (state.arrivals) R.string.stop_earlier_arrivals else R.string.stop_earlier_departures), modifier = Modifier.padding(start = 8.dp))
                }
            }
            when {
                state.loading && list.isEmpty() -> item { TransitLoading() }
                state.error != null && list.isEmpty() -> item { ErrorState(state.error!!, onRetry = { scope.launch { viewModel.refresh(force = true) } }) }
                list.isEmpty() && filtered && all.isNotEmpty() -> item {
                    MessageState(stringResource(R.string.stop_no_departures_filtered), action = stringResource(R.string.stop_clear_filters), onAction = viewModel::clearFilters)
                }
                list.isEmpty() -> item { MessageState(stringResource(R.string.stop_no_departures), stringResource(R.string.stop_no_departures_hint)) }
                else -> itemsIndexed(list, key = { _, d -> d.key }) { index, d ->
                    DepartureRow(
                        d, now, index, list.size,
                        onClick = if (d.tripLinked) ({ navigator.trip(d.tripId, d.serviceDate.toString(), d.liveRef) }) else null,
                        modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                    )
                }
            }
            if (state.loadingMore) item { TransitLoading() }
            if (all.isNotEmpty() || !state.live) item {
                androidx.compose.material3.TextButton(
                    onClick = viewModel::later, enabled = !state.loadingMore,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ExpandMore, contentDescription = null)
                    Text(stringResource(if (state.arrivals) R.string.stop_later_arrivals else R.string.stop_later_departures), modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (points.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.stop_location)) }
                if (!mapState.expanded) item {
                    org.southtyrol.transit.feature.common.CompactMapCard(mapState, mapContent, camera, Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(200.dp))
                }
                if (state.platforms.any { it.platform.isNotBlank() }) item {
                    Text(
                        stringResource(R.string.stop_platforms, state.platforms.mapNotNull { it.platform.ifBlank { null } }.distinct().sorted().joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        }
    }
}

/**
 * A served line as a filter: tap to show only its departures, long-press to open the line page.
 * While some lines are selected, the others are dimmed and selected ones are outlined.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LineFilterBadge(line: org.southtyrol.transit.model.Line, selected: Boolean, anySelected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val openLabel = stringResource(R.string.stop_open_line)
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            // The outline's space is always reserved, so selecting a line never shifts the row.
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50))
            .padding(2.dp)
            .alpha(if (anySelected && !selected) 0.4f else 1f)
            .combinedClickable(onLongClickLabel = openLabel, onLongClick = onOpen, onClick = onToggle)
            .semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.Checkbox },
    ) {
        LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor)
    }
}

/** "+N" / "Show less" next to the line badges, sized and shaped like them (same height and outline slot). */
@Composable
private fun MoreLinesPill(label: String, onClick: () -> Unit) {
    Box(Modifier.padding(2.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick)) {
        Box(
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50))
                .heightIn(min = 26.dp)
                .padding(horizontal = 10.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = if (selected) ({
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Rounded.Check, contentDescription = null,
                modifier = Modifier.size(androidx.compose.material3.FilterChipDefaults.IconSize),
            )
        }) else null,
        modifier = Modifier.semantics { role = androidx.compose.ui.semantics.Role.RadioButton },
    )
}
