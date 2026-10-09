package org.southtyrol.transit.feature.journey

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.AlertsState
import org.southtyrol.transit.data.JourneyRepository
import org.southtyrol.transit.data.JourneyRequest
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.data.TripRepository
import org.southtyrol.transit.feature.planner.RequestCodec
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.TicketingProvider
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.ui.JourneyRoute
import org.southtyrol.transit.ui.ResultsRoute
import java.time.Instant
import javax.inject.Inject

enum class ResultSort { RECOMMENDED, EARLIEST_ARRIVAL, FEWEST_CHANGES, LEAST_WALKING }

data class ResultsState(
    val request: JourneyRequest? = null,
    val loading: Boolean = true,
    val journeys: List<Journey> = emptyList(),
    val error: DataError? = null,
    val fetchedAt: Instant? = null,
    val sort: ResultSort = ResultSort.RECOMMENDED,
    val selectedId: String? = null,
) {
    val sorted: List<Journey>
        get() = when (sort) {
            ResultSort.RECOMMENDED -> journeys
            ResultSort.EARLIEST_ARRIVAL -> journeys.sortedWith(compareBy<Journey> { it.bestArrival }.thenBy { it.duration })
            ResultSort.FEWEST_CHANGES -> journeys.sortedWith(compareBy<Journey> { it.changes }.thenBy { it.bestArrival })
            ResultSort.LEAST_WALKING -> journeys.sortedWith(compareBy<Journey> { it.walkingDuration }.thenBy { it.bestArrival })
        }
}

@HiltViewModel
class ResultsViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val journeys: JourneyRepository,
    private val saved: SavedRepository,
    private val language: LanguageProvider,
) : ViewModel() {
    val encoded: String = handle.toRoute<ResultsRoute>().request
    private val base = RequestCodec.decode(encoded)
    private val _state = MutableStateFlow(ResultsState(request = base, sort = handle.get<String>("sort")?.let { runCatching { ResultSort.valueOf(it) }.getOrNull() } ?: ResultSort.RECOMMENDED))
    val state: StateFlow<ResultsState> = _state.asStateFlow()
    private var job: Job? = null
    private val handle = handle

    val isSaved: StateFlow<Boolean> = base?.let { r -> saved.isSaved(SavedKind.JOURNEY, "${r.from.id}|${r.from.name}->${r.to.id}|${r.to.name}") }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false) ?: MutableStateFlow(false)

    init { search(base) }

    fun retry() = search(_state.value.request)

    private fun search(request: JourneyRequest?) {
        if (request == null) { _state.update { it.copy(loading = false, error = DataError.Parse) }; return }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, request = request) }
            try {
                val result = journeys.plan(request, language.current())
                _state.update { it.copy(loading = false, journeys = result, fetchedAt = Instant.now(), error = if (result.isEmpty()) DataError.NotFound else null) }
            } catch (e: DataException) {
                android.util.Log.w("Transit", "Journey planning failed: ${e.error}", e.cause)
                _state.update { it.copy(loading = false, error = e.error) }
            }
        }
    }

    /** Later connections: search again from just after the last departure in the list. */
    fun later() {
        val request = _state.value.request ?: return
        val last = _state.value.journeys.maxOfOrNull { it.departure } ?: return
        search(request.copy(options = request.options.copy(at = last.plusSeconds(60).atZone(request.options.at.zone), arriveBy = false)))
    }

    fun earlier() {
        val request = _state.value.request ?: return
        val first = _state.value.journeys.minOfOrNull { it.arrival } ?: return
        search(request.copy(options = request.options.copy(at = first.minusSeconds(60).atZone(request.options.at.zone), arriveBy = true)))
    }

    fun sort(value: ResultSort) { handle["sort"] = value.name; _state.update { it.copy(sort = value) } }
    fun select(id: String?) { _state.update { it.copy(selectedId = id) } }

    fun toggleSaved() {
        val r = base ?: return
        viewModelScope.launch {
            if (isSaved.value) saved.remove(SavedKind.JOURNEY, "${r.from.id}|${r.from.name}->${r.to.id}|${r.to.name}") else saved.saveJourney(r.from, r.to)
        }
    }
}

sealed class DetailState {
    data object Loading : DetailState()
    data class Found(val journey: Journey) : DetailState()
    data object Missing : DetailState()
    data class Error(val error: DataError) : DetailState()
}

@HiltViewModel
class JourneyDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val journeys: JourneyRepository,
    private val language: LanguageProvider,
    alerts: AlertRepository,
    val ticketing: TicketingProvider,
) : ViewModel() {
    private val route = handle.toRoute<JourneyRoute>()
    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state: StateFlow<DetailState> = _state.asStateFlow()
    val alerts: StateFlow<AlertsState> = alerts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertsState())

    init { load() }

    fun load() {
        journeys.journey(route.journeyId)?.let { _state.value = DetailState.Found(it); return }
        // After process death the in-memory results are gone: re-run the same request and match by id.
        viewModelScope.launch {
            val request = RequestCodec.decode(route.request) ?: run { _state.value = DetailState.Missing; return@launch }
            try {
                val found = journeys.plan(request, language.current()).firstOrNull { it.id == route.journeyId }
                _state.value = found?.let { DetailState.Found(it) } ?: DetailState.Missing
            } catch (e: DataException) {
                _state.value = DetailState.Error(e.error)
            }
        }
    }
}

/**
 * Live vehicles for a journey's transit legs: each leg is matched to its timetable run (needs the
 * downloaded timetable), then merged with realtime like the trip screen. Keyed by journey id - the
 * two-pane results view hosts one per selected journey.
 */
@HiltViewModel(assistedFactory = JourneyLiveViewModel.Factory::class)
class JourneyLiveViewModel @AssistedInject constructor(
    @Assisted private val journey: Journey,
    private val trips: TripRepository,
    realtime: RealtimeRepository,
    private val language: LanguageProvider,
) : ViewModel() {
    @AssistedFactory
    interface Factory { fun create(journey: Journey): JourneyLiveViewModel }

    /** Static runs by leg index; legs without a match are absent. */
    private val runs = MutableStateFlow<Map<Int, TripDetail>>(emptyMap())

    /** Runs merged with the latest realtime snapshot (GTFS-RT predictions and vehicle positions). */
    val legRuns: StateFlow<Map<Int, TripDetail>> = combine(runs, realtime.snapshot) { byLeg, snapshot ->
        byLeg.mapValues { (_, detail) -> trips.merge(detail, snapshot) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        viewModelScope.launch {
            val lang = language.current()
            runs.value = journey.legs.withIndex()
                .filter { it.value.mode.isTransit }
                .mapNotNull { (i, leg) -> trips.forLeg(leg, lang)?.let { i to it } }
                .toMap()
        }
    }

    /** Refreshes trip updates and vehicle positions; throttled by the repository. */
    suspend fun poll() { if (runs.value.isNotEmpty()) trips.pollRealtime() }
}
