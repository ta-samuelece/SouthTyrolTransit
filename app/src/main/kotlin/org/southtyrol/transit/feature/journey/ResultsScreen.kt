@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package org.southtyrol.transit.feature.journey

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.southtyrol.transit.R
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.StatusPill
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.feature.planner.displayName
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.ui.Navigator
import java.time.Duration

@Composable
fun ResultsScreen(navigator: Navigator, viewModel: ResultsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saved by viewModel.isSaved.collectAsStateWithLifecycle()
    val request = state.request
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                title = {
                    if (request != null) Column {
                        Text(displayName(request.from), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(R.string.planner_to), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(displayName(request.to), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::toggleSaved) {
                        Icon(if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, contentDescription = stringResource(if (saved) R.string.action_unsave_journey else R.string.action_save_journey))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val twoPane = maxWidth >= 840.dp
            val list = @Composable { modifier: Modifier ->
                ResultsList(state, viewModel, modifier) { journey ->
                    if (twoPane) viewModel.select(journey.id) else navigator.journey(viewModel.encoded, journey.id)
                }
            }
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    list(Modifier.weight(0.42f).fillMaxHeight())
                    VerticalDivider()
                    val selected = state.journeys.firstOrNull { it.id == state.selectedId } ?: state.sorted.firstOrNull()
                    if (selected != null) JourneyDetailContent(selected, navigator, Modifier.weight(0.58f).fillMaxHeight())
                }
            } else {
                list(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ResultsList(state: ResultsState, viewModel: ResultsViewModel, modifier: Modifier, onOpen: (Journey) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultSort.entries.forEach { sort ->
                    FilterChip(selected = state.sort == sort, onClick = { viewModel.sort(sort) }, label = { Text(stringResource(sortLabel(sort))) })
                }
            }
        }
        when {
            state.loading && state.journeys.isEmpty() -> item { TransitLoading(contained = true) }
            state.error != null && state.journeys.isEmpty() -> item {
                if (state.error == DataError.NotFound) {
                    org.southtyrol.transit.design.MessageState(stringResource(R.string.results_none), stringResource(R.string.results_none_hint))
                } else {
                    ErrorState(state.error, onRetry = viewModel::retry)
                }
            }
            else -> {
                if (state.journeys.isNotEmpty()) item {
                    OutlinedButton(onClick = viewModel::earlier, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.results_earlier)) }
                }
                items(state.sorted, key = { it.id }) { journey ->
                    JourneyCard(journey, selected = journey.id == state.selectedId, onClick = { onOpen(journey) }, modifier = Modifier.animateItem())
                }
                if (state.loading) item { TransitLoading() }
                if (state.journeys.isNotEmpty()) item {
                    OutlinedButton(onClick = viewModel::later, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.results_later)) }
                }
                item {
                    Text(stringResource(R.string.results_source), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun sortLabel(sort: ResultSort) = when (sort) {
    ResultSort.RECOMMENDED -> R.string.sort_recommended
    ResultSort.EARLIEST_ARRIVAL -> R.string.sort_earliest_arrival
    ResultSort.FEWEST_CHANGES -> R.string.pref_fewest_changes
    ResultSort.LEAST_WALKING -> R.string.pref_least_walking
}

/**
 * Journey summary. Departure and arrival times are the visual anchors; lines form a compact chain;
 * delay/cancellation/notice state is shown with icon + text.
 */
@Composable
fun JourneyCard(journey: Journey, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val now = rememberNow()
    val changes = pluralStringResource(R.plurals.changes, journey.changes, journey.changes)
    val walk = journey.walkingDuration.toMinutes()
    val description = stringResource(
        R.string.journey_card_a11y, Format.time(journey.bestDeparture), Format.time(journey.bestArrival), Format.duration(journey.duration), changes,
    )
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().animateContentSize().semantics { contentDescription = description },
        shape = RoundedCornerShape(if (selected) 32.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (journey.cancelled) BorderStroke(2.dp, status.cancelled) else null,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                TimeColumn(journey.departure, journey.bestDeparture, journey.cancelled)
                Text("  –  ", style = TimeStyles.large, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TimeColumn(journey.arrival, journey.bestArrival, journey.cancelled)
                Spacer(Modifier.weight(1f))
                Text(Format.duration(journey.duration), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                journey.legs.forEachIndexed { index, leg ->
                    if (index > 0) Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                    if (leg.mode == TransportMode.WALK) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, contentDescription = Format.mode(TransportMode.WALK), modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(Duration.between(leg.departure, leg.arrival).toMinutes().toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LineBadge(leg.line, leg.mode)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SyncAlt, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(changes, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (walk > 0) {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.journey_walk_minutes, walk.toInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                val minutesAway = Format.minutesUntil(journey.bestDeparture, now)
                if (minutesAway in 0..59) Text(stringResource(R.string.journey_leaves_in, Format.countdown(journey.bestDeparture, now)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                journey.fare?.single?.let { Text("€ ${it.price}", style = MaterialTheme.typography.labelLarge) }
            }
            val delay = journey.maxDelaySeconds
            if (journey.cancelled || journey.hasRealtime || journey.hasNotices) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        journey.cancelled -> StatusPill(stringResource(org.southtyrol.transit.design.R.string.ds_cancelled), status.cancelled, Icons.Rounded.Warning)
                        delay >= 120 -> StatusPill(stringResource(R.string.journey_delayed, (delay / 60).toInt()), status.delayed, Icons.Rounded.Podcasts)
                        journey.hasRealtime -> StatusPill(stringResource(R.string.journey_live_on_time), status.live, Icons.Rounded.Podcasts)
                    }
                    if (journey.hasNotices) StatusPill(stringResource(org.southtyrol.transit.design.R.string.ds_has_alert), status.delayed, Icons.Rounded.Warning)
                }
            }
        }
    }
}

@Composable
private fun TimeColumn(scheduled: java.time.Instant, best: java.time.Instant, cancelled: Boolean) {
    val status = LocalStatusColors.current
    val changed = best != scheduled
    Column {
        if (changed) Text(Format.time(scheduled), style = MaterialTheme.typography.labelMedium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            Format.time(best), style = TimeStyles.large,
            color = when { cancelled -> status.cancelled; changed && best.isAfter(scheduled) -> status.delayed; else -> MaterialTheme.colorScheme.onSurface },
            textDecoration = if (cancelled) TextDecoration.LineThrough else null,
        )
    }
}

