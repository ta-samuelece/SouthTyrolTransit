package org.southtyrol.transit.feature.schedules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.R
import org.southtyrol.transit.data.JourneyRepository
import org.southtyrol.transit.data.JourneyRequest
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.di.AppLanguage
import org.southtyrol.transit.di.NamesLanguage
import org.southtyrol.transit.feature.common.PollWhileVisible
import org.southtyrol.transit.feature.follow.StartFollowSheet
import org.southtyrol.transit.feature.journey.JourneyCard
import org.southtyrol.transit.feature.planner.RequestCodec
import org.southtyrol.transit.follow.FollowController
import org.southtyrol.transit.follow.FollowSession
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.SearchOptions
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TripSchedule
import org.southtyrol.transit.ui.Navigator
import org.southtyrol.transit.ui.TripScheduleRoute
import java.time.Instant
import java.time.format.TextStyle
import javax.inject.Inject

data class NextTripsState(
    /** The schedule has been looked up; [schedule] null then means it was deleted. */
    val loaded: Boolean = false,
    val schedule: TripSchedule? = null,
    val window: TripSchedule.Window? = null,
    val loading: Boolean = false,
    /** The window has been planned at least once, so an empty [journeys] means none fall in it. */
    val planned: Boolean = false,
    val journeys: List<Journey> = emptyList(),
    /** The encoded request each journey came from, for its detail screen and for following it. */
    val requests: Map<String, String> = emptyMap(),
    val error: DataError? = null,
)

@HiltViewModel
class NextTripsViewModel @Inject constructor(
    handle: SavedStateHandle,
    saved: SavedRepository,
    private val journeys: JourneyRepository,
    private val settings: SettingsRepository,
    @NamesLanguage private val language: LanguageProvider,
    private val follow: FollowController,
) : ViewModel() {
    private val id = handle.toRoute<TripScheduleRoute>().id
    private val _state = MutableStateFlow(NextTripsState())
    val state: StateFlow<NextTripsState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            saved.saved.map { items -> items.firstOrNull { it.kind == SavedKind.TRIP_SCHEDULE && it.id == id }?.tripSchedule }
                .distinctUntilChanged()
                .collect { schedule -> _state.update { it.copy(loaded = true, schedule = schedule) } }
        }
    }

    /** Plans the schedule's next window again; the screen calls it while visible and on Retry. */
    fun refresh() {
        val schedule = _state.value.schedule ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val window = schedule.nextWindow(Instant.now())
            // A new window starts from an empty list, so the old window's trips never show under the new line.
            _state.update { if (it.window == window) it.copy(loading = true, error = null) else it.copy(window = window, loading = true, planned = false, journeys = emptyList(), requests = emptyMap(), error = null) }
            if (window == null) { _state.update { it.copy(loading = false, planned = true) }; return@launch }
            try {
                val (found, requests) = plan(schedule, window)
                _state.update { it.copy(loading = false, planned = true, journeys = found, requests = requests) }
            } catch (e: DataException) {
                android.util.Log.w("Transit", "Schedule planning failed: ${e.error}", e.cause)
                _state.update { it.copy(loading = false, error = e.error) }
            }
        }
    }

    /**
     * Leaving between: from the window's start, then later from the last departure + 60 s until one leaves after
     * the end. Arriving between: arriving by the window's end, then earlier from the first arrival − 60 s until
     * one arrives before the start. At most [MAX_CALLS] planner calls.
     */
    private suspend fun plan(schedule: TripSchedule, window: TripSchedule.Window): Pair<List<Journey>, Map<String, String>> {
        val prefs = settings.current()
        val options = SearchOptions(arriveBy = schedule.arriveBy, wheelchair = prefs.wheelchair, walkingSpeed = prefs.walkingSpeed)
        var at = if (schedule.arriveBy) window.end else window.start
        val found = mutableListOf<Journey>()
        val requests = mutableMapOf<String, String>()
        for (call in 1..MAX_CALLS) {
            val request = JourneyRequest(schedule.from, schedule.to, options.copy(at = at.atZone(TransitZone)))
            val result = try {
                journeys.plan(request, language.current(), noticeLanguage = AppLanguage.current())
            } catch (e: DataException) {
                if (e.error == DataError.NotFound) emptyList() else throw e
            }
            val encoded = RequestCodec.encode(request, leaveNow = false)
            result.forEach { requests.putIfAbsent(it.id, encoded) }
            found += result
            if (result.isEmpty()) break
            if (schedule.arriveBy) {
                val first = result.minOf { it.arrival }
                if (first.isBefore(window.start)) break
                at = first.minusSeconds(60)
            } else {
                val last = result.maxOf { it.departure }
                if (last.isAfter(window.end)) break
                at = last.plusSeconds(60)
            }
        }
        val kept = schedule.connectionsIn(window, found)
        return kept to requests.filterKeys { id -> kept.any { it.id == id } }
    }

    fun request(journey: Journey): String? = _state.value.requests[journey.id]

    /** Starts following [journey], replacing any other; false when Android refused the service. */
    fun startFollowing(journey: Journey, useLocation: Boolean): Boolean {
        val request = request(journey) ?: return false
        return follow.start(FollowSession(journey, request, useLocation))
    }

    private companion object { const val MAX_CALLS = 4 }
}

@Composable
fun NextTripsScreen(navigator: Navigator, viewModel: NextTripsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val schedule = state.schedule
    // Live times come with each new plan, so the list is planned again every 2 minutes while it is visible.
    PollWhileVisible(120_000, key = schedule) { viewModel.refresh() }
    var following by rememberSaveable { mutableStateOf<String?>(null) }
    val now = rememberNow()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(schedule?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = navigator::back) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (schedule != null) IconButton(onClick = { navigator.editTripSchedule(schedule.id) }) {
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.schedule_edit))
                    }
                },
            )
        },
    ) { padding ->
        if (!state.loaded) { TransitLoading(contained = true, modifier = Modifier.padding(padding)); return@Scaffold }
        if (schedule == null) {
            MessageState(
                stringResource(R.string.schedule_missing), icon = Icons.Rounded.EventRepeat, modifier = Modifier.padding(padding),
                action = stringResource(R.string.action_back), onAction = navigator::back,
            )
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            state.window?.let { window -> item { WindowLine(schedule, window, now) } }
            val error = state.error
            when {
                error != null && state.journeys.isEmpty() -> item { ErrorState(error, onRetry = viewModel::refresh) }
                !state.planned -> item { TransitLoading(contained = true) }
                state.journeys.isEmpty() -> item {
                    MessageState(stringResource(R.string.schedule_none), stringResource(R.string.schedule_none_hint), icon = Icons.Rounded.EventRepeat)
                }
                else -> {
                    // The soonest connection that can still be caught carries the countdown, as on the results.
                    val next = state.journeys.firstOrNull { Format.minutesUntil(it.bestDeparture, now) >= 0 && !it.cancelled }
                    items(state.journeys, key = { it.id }, contentType = { "journey" }) { journey ->
                        val departed = Format.minutesUntil(journey.bestDeparture, now) < 0
                        Column(Modifier.animateItem()) {
                            if (!departed) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.schedule_leave_by, Format.time(journey.bestDeparture)),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                                )
                                FilledTonalButton(onClick = { following = journey.id }) {
                                    Icon(Icons.Rounded.Navigation, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                                    Text(stringResource(R.string.schedule_follow))
                                }
                            }
                            JourneyCard(
                                journey, selected = false, emphasized = journey.id == next?.id, now = now,
                                onClick = { viewModel.request(journey)?.let { navigator.journey(it, journey.id) } },
                            )
                        }
                    }
                }
            }
        }
    }
    state.journeys.firstOrNull { it.id == following }?.let { journey ->
        StartFollowSheet(
            journey = journey,
            onDismiss = { following = null },
            onStart = { useLocation ->
                following = null
                val started = viewModel.startFollowing(journey, useLocation)
                if (started) navigator.follow()
                started
            },
        )
    }
}

/** "Today, leave 07:15–08:00", or the weekday when the next window is another day. */
@Composable
private fun WindowLine(schedule: TripSchedule, window: TripSchedule.Window, now: Instant) {
    val start = Format.time(window.start)
    val end = Format.time(window.end)
    val text = if (window.date == now.atZone(TransitZone).toLocalDate()) {
        stringResource(if (schedule.arriveBy) R.string.schedule_window_today_arrive else R.string.schedule_window_today_leave, start, end)
    } else {
        val locale = Format.locale()
        val day = window.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
        stringResource(if (schedule.arriveBy) R.string.schedule_window_day_arrive else R.string.schedule_window_day_leave, day, start, end)
    }
    Text(text, style = MaterialTheme.typography.titleMedium)
}
