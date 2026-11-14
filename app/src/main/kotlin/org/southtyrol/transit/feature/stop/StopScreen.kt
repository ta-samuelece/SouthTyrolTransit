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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.ui.semantics.heading
import org.southtyrol.transit.feature.common.MapLipPage
import org.southtyrol.transit.feature.common.rememberLipSlim
import org.southtyrol.transit.feature.common.rememberMapLipState
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.AltRoute
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
import org.southtyrol.transit.feature.common.PageButton
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
    Scaffold(
        // The map lip reaches under the status bar itself.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
    ) { padding ->
        StopContent(viewModel, navigator, Modifier.padding(padding).fillMaxSize())
    }
}

/**
 * Live board + stop details under the map lip of the stop's platforms; how current the board is sits on the
 * map, the stop's name and its favourite star head the sheet.
 */
@Composable
fun StopContent(viewModel: StopViewModel, navigator: Navigator, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val board by viewModel.board.collectAsStateWithLifecycle()
    val saved by viewModel.isSaved.collectAsStateWithLifecycle()
    val visible by viewModel.visible.collectAsStateWithLifecycle()
    // The board's countdowns tick only between flings: a tick recomposes every visible row.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val now = rememberNow(holdWhile = { listState.isScrollInProgress })
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val dark = LocalDarkTheme.current
    val language = AppLanguage.current()

    // Live screens refresh every 30 s only while visible.
    PollWhileVisible(30_000, key = state.arrivals) { viewModel.refresh() }

    // Built once per platforms and direction: the board's clock and polls must not hand the map a new
    // content instance, which it compares marker by marker.
    val platforms = state.platforms
    val direction = state.direction
    val points = remember(platforms) { platforms.map { it.point }.filter { it.isValid } }
    // The chosen direction's platforms are highlighted on the map.
    val mapContent = remember(platforms, direction) {
        MapContent(
            stops = platforms.map { MapMarker(it.id, it.point, MarkerKind.STOP, it.platform, selected = direction?.platformIds?.contains(it.id) ?: true) },
            clusterStops = false,
        )
    }
    val camera = remember(points) { CameraRequest.Fit(points + points.take(1)) }
    MapLipPage(
        rememberMapLipState(), points.isNotEmpty(), mapContent, camera, navigator::back, modifier,
        slim = rememberLipSlim(listState),
        end = { board?.let { FreshnessIndicator(it.realtime, it.realtimeFetchedAt ?: state.loadedAt, now, compact = true) } },
    ) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; viewModel.refresh(force = true); refreshing = false } },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "name") {
                // The stop's name heads the sheet, with its code under it and the favourite star at its end.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.name.ifBlank { stringResource(R.string.stop_title) }, style = MaterialTheme.typography.headlineSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
                        )
                        state.stop?.code?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(R.string.stop_code, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { viewModel.toggleSaved() }) {
                        Icon(if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder, contentDescription = stringResource(if (saved) R.string.action_unsave_stop else R.string.action_save_stop))
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Large stations serve dozens of lines: one row that scrolls sideways keeps the live board in view.
                    // Tap a line to show only its departures (several can be combined); long-press opens the line.
                    val filterLines by viewModel.filterLines.collectAsStateWithLifecycle()
                    if (filterLines.isNotEmpty()) Row(
                        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        filterLines.forEach { line ->
                            LineFilterBadge(
                                line, selected = line.key in state.lineFilter,
                                onToggle = { viewModel.toggleLine(line.key) }, onOpen = { navigator.line(line.key) },
                            )
                        }
                    }
                    // Departures/arrivals only where arrivals differ (hidden at most intermediate stops); a direction
                    // only at two-way stops, picked from a sheet that names each side in full. With both, the toggle
                    // takes its own row and the time sits beside the direction; otherwise the time leads one row.
                    // The wheelchair mark ends the first row.
                    val toggle = state.showArrivalsToggle || state.arrivals
                    val directions = state.directions.takeIf { it.size >= 2 && board?.source != BoardSource.NETWORK }
                    val accessible = state.stop?.wheelchair == Accessibility.ACCESSIBLE
                    val arrivalsToggle = @Composable { modifier: Modifier ->
                        ConnectedToggleGroup(
                            options = listOf(false, true), selected = state.arrivals, onSelect = viewModel::setArrivals,
                            label = { stringResource(if (it) R.string.stop_arrivals else R.string.stop_departures) },
                            modifier = modifier,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (toggle && directions != null) arrivalsToggle(Modifier.weight(1f)) else {
                            StopTimeButton(viewModel)
                            when {
                                toggle -> arrivalsToggle(Modifier.weight(1f))
                                directions != null -> DirectionButton(directions, state.direction, viewModel::setDirection, state.lines, Modifier.weight(1f))
                                else -> androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            }
                        }
                        if (accessible) WheelchairIcon()
                    }
                    if (toggle && directions != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StopTimeButton(viewModel)
                        DirectionButton(directions, state.direction, viewModel::setDirection, state.lines, Modifier.weight(1f))
                    }
                    if (board?.source == BoardSource.NETWORK) StatusBanner(stringResource(R.string.stop_network_board), kind = BannerKind.INFO)
                    else if (board?.realtime == Freshness.STALE || board?.realtime == Freshness.UNAVAILABLE) StatusBanner(stringResource(R.string.stop_realtime_unavailable), kind = BannerKind.WARNING)
                }
            }
            val all = board?.departures.orEmpty()
            val list = visible
            val filtered = state.lineFilter.isNotEmpty() || (state.direction != null && board?.source == BoardSource.SCHEDULE)
            val earlier: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {
                item(key = "earlier") {
                    PageButton(
                        stringResource(if (state.arrivals) R.string.stop_earlier_arrivals else R.string.stop_earlier_departures),
                        androidx.compose.material.icons.Icons.Rounded.ArrowUpward, viewModel::earlier,
                        Modifier.padding(vertical = 8.dp), enabled = !state.loadingMore,
                    )
                }
            }
            when {
                state.loading && list.isEmpty() -> item { TransitLoading() }
                state.error != null && list.isEmpty() -> item { ErrorState(state.error!!, onRetry = { scope.launch { viewModel.refresh(force = true) } }) }
                list.isEmpty() && filtered && all.isNotEmpty() -> item {
                    MessageState(stringResource(R.string.stop_no_departures_filtered), action = stringResource(R.string.stop_clear_filters), onAction = viewModel::clearFilters)
                }
                list.isEmpty() -> item { MessageState(stringResource(R.string.stop_no_departures), stringResource(R.string.stop_no_departures_hint)) }
                else -> {
                    // On the live departure board the next departure is the hero, below the "earlier" button;
                    // once earlier ones are loaded they come first and the board is a plain list.
                    val hero = list.first().takeIf { state.live && !state.arrivals && Format.minutesUntil(it.best, now) >= -1 }
                    val rest = if (hero != null) list.drop(1) else list
                    earlier()
                    // The hero heads the list: its bottom corners join the first row, which continues the group.
                    val offset = if (hero != null) 1 else 0
                    if (hero != null) item(key = "hero:" + hero.key) {
                        org.southtyrol.transit.feature.common.NextDepartureCard(
                            hero, now,
                            onClick = if (hero.tripLinked) ({ navigator.trip(hero.tripId, hero.serviceDate.toString(), hero.liveRef) }) else null,
                            joined = rest.isNotEmpty(),
                            modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                        )
                    }
                    itemsIndexed(rest, key = { _, d -> d.key }, contentType = { _, _ -> "departure" }) { index, d ->
                        DepartureRow(
                            d, now, index + offset, rest.size + offset,
                            onClick = if (d.tripLinked) ({ navigator.trip(d.tripId, d.serviceDate.toString(), d.liveRef) }) else null,
                            modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                        )
                    }
                }
            }
            if (state.loadingMore) item { TransitLoading() }
            if (all.isNotEmpty() || !state.live) item {
                PageButton(
                    stringResource(if (state.arrivals) R.string.stop_later_arrivals else R.string.stop_later_departures),
                    androidx.compose.material.icons.Icons.Rounded.ArrowDownward, viewModel::later,
                    Modifier.padding(vertical = 8.dp), enabled = !state.loadingMore,
                )
            }
            // The map of the platforms is the lip; their names stay at the end of the page.
            if (state.platforms.any { it.platform.isNotBlank() }) {
                item { SectionHeader(stringResource(R.string.stop_location)) }
                item {
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
 * Selected lines are outlined. The others keep their full colour: they can still be tapped, so fading
 * them would drop the badge text below readable contrast.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LineFilterBadge(line: org.southtyrol.transit.model.Line, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    val openLabel = stringResource(R.string.stop_open_line)
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(50))
            // The outline's space is always reserved, so selecting a line never shifts the row.
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50))
            .padding(2.dp)
            .combinedClickable(onLongClickLabel = openLabel, onLongClick = onOpen, onClick = onToggle)
            .semantics { toggleableState = ToggleableState(selected); role = androidx.compose.ui.semantics.Role.Checkbox },
    ) {
        LineBadge(line.name, line.mode, color = line.color, textColor = line.textColor)
    }
}

/**
 * When the board starts: the shared [org.southtyrol.transit.feature.common.TimeButton], as tall as the
 * departures/arrivals toggle. Now is the clock alone; a chosen start shows its time, and its × returns to the
 * live board.
 */
@Composable
private fun StopTimeButton(viewModel: StopViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickTime by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (pickTime) org.southtyrol.transit.feature.common.DateTimePickerDialogs(
        initial = state.start,
        onPicked = { pickTime = false; viewModel.setTime(it) },
        onDismiss = { pickTime = false },
    )
    org.southtyrol.transit.feature.common.TimeButton(
        state.start?.atZone(org.southtyrol.transit.model.TransitZone),
        onPick = { pickTime = true }, onClear = viewModel::now,
        nowDescription = R.string.stop_time_now_description, atDescription = R.string.stop_time_at_description,
        clearDescription = R.string.stop_back_to_now, size = androidx.compose.material3.ButtonDefaults.MinHeight,
    )
}

/**
 * The direction as a [org.southtyrol.transit.feature.common.ChoiceButton]: all directions, or where the buses
 * go next. In its sheet a direction reads as its main next stop with the second one under it, then the lines
 * leaving from that side as badges, or one "All lines" pill when every line of the stop calls there.
 */
@Composable
private fun DirectionButton(
    directions: List<org.southtyrol.transit.model.StopDirection>,
    selected: org.southtyrol.transit.model.StopDirection?,
    onSelect: (org.southtyrol.transit.model.StopDirection?) -> Unit,
    lines: List<org.southtyrol.transit.model.Line>,
    modifier: Modifier = Modifier,
) {
    val all = stringResource(R.string.stop_all_directions)
    org.southtyrol.transit.feature.common.ChoiceButton(
        title = stringResource(R.string.stop_direction), options = listOf(null) + directions, selected = selected, onSelect = onSelect,
        label = { it?.towards?.joinToString(" · ") ?: all }, icon = Icons.Rounded.AltRoute, modifier = modifier,
        sheetLabel = { it?.towards?.firstOrNull() ?: all },
        supporting = { d ->
            val served = d?.let { lines.filter { line -> line.key in it.lineKeys } }.orEmpty()
            val second = d?.towards?.getOrNull(1)
            if (served.isEmpty() && second == null) null else ({
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    second?.let { Text(stringResource(R.string.stop_direction_also, it)) }
                    when {
                        served.isEmpty() -> Unit
                        served.size == lines.size -> org.southtyrol.transit.feature.common.InfoPill(
                            stringResource(R.string.stop_direction_all_lines), MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            served.forEach { LineBadge(it.name, it.mode, color = it.color, textColor = it.textColor, showIcon = false) }
                        }
                    }
                }
            })
        },
    )
}
