package org.southtyrol.transit.feature.stop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.southtyrol.transit.data.AlertMatcher
import org.southtyrol.transit.data.AlertRepository
import org.southtyrol.transit.data.Board
import org.southtyrol.transit.data.BoardSource
import org.southtyrol.transit.data.DepartureRepository
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LineRepository
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.Geo
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransitScheduleDataSource
import java.time.Duration
import java.time.Instant

data class StopState(
    val stop: Stop? = null,
    val name: String = "",
    val platforms: List<Stop> = emptyList(),
    val lines: List<Line> = emptyList(),
    val arrivals: Boolean = false,
    val loading: Boolean = true,
    val scheduled: List<Departure> = emptyList(),
    val source: BoardSource = BoardSource.SCHEDULE,
    val loadedAt: Instant? = null,
    val error: DataError? = null,
    val distanceMeters: Double? = null,
    /** Start of the shown window; null = live ("now", moving with time). */
    val start: Instant? = null,
    /** End of the shown window; null = [DEFAULT_WINDOW] after the start. */
    val end: Instant? = null,
    /** Extending the window while the current board stays visible. */
    val loadingMore: Boolean = false,
    /** Live times from the departure monitor, overlaid where GTFS-RT has none. */
    val liveTimes: org.southtyrol.transit.data.LiveTimes? = null,
    /** False when arrivals would equal departures, so the toggle is hidden. */
    val showArrivalsToggle: Boolean = false,
) {
    val live: Boolean get() = start == null && end == null
}

private val DEFAULT_WINDOW: Duration = Duration.ofHours(4)
private val STEP: Duration = Duration.ofHours(1)

@HiltViewModel(assistedFactory = StopViewModel.Factory::class)
class StopViewModel @AssistedInject constructor(
    @Assisted("key") val stationKey: String,
    @Assisted("name") initialName: String,
    private val schedule: TransitScheduleDataSource,
    private val departures: DepartureRepository,
    private val realtime: RealtimeRepository,
    private val lines: LineRepository,
    private val saved: SavedRepository,
    alerts: AlertRepository,
    private val language: LanguageProvider,
    private val location: LocationProvider,
) : ViewModel() {
    @AssistedFactory
    interface Factory { fun create(@Assisted("key") stationKey: String, @Assisted("name") name: String): StopViewModel }

    private val _state = MutableStateFlow(StopState(name = initialName))
    val state: StateFlow<StopState> = _state.asStateFlow()

    val isSaved = saved.isSaved(SavedKind.STOP, stationKey).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val alertState = alerts.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), org.southtyrol.transit.data.AlertsState())

    /** Alerts for this stop or the lines serving it. */
    val stopAlerts: StateFlow<List<ServiceAlert>> = combine(alertState, _state) { a, s ->
        val now = Instant.now()
        a.alerts.filter { alert -> alert.active(now) && (AlertMatcher.affectsStop(alert, stationKey) || s.lines.any { AlertMatcher.affectsLine(alert, it) }) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Scheduled board merged with the latest realtime snapshot (recomputed on every RT refresh). */
    val board: StateFlow<Board?> = combine(_state, realtime.snapshot, alertState) { s, snapshot, a ->
        if (s.loadedAt == null) null else departures.merge(s.scheduled, s.source, snapshot, a.alerts, s.arrivals, keepFrom = s.start, live = s.liveTimes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            val lang = language.current()
            val stop = runCatching { schedule.stop(stationKey, lang) }.getOrNull()
            val platforms = runCatching { schedule.stationStops(stationKey, lang) }.getOrDefault(emptyList())
            val served = runCatching { lines.atStop(stationKey) }.getOrDefault(emptyList())
            val toggle = departures.arrivalsDiffer(stationKey)
            _state.update { it.copy(stop = stop, name = stop?.name ?: it.name, platforms = platforms, lines = served, showArrivalsToggle = toggle) }
            if (stop != null && location.hasPermission()) {
                (location.current() as? LocationResult.Found)?.let { found -> _state.update { it.copy(distanceMeters = Geo.distance(found.point, stop.point)) } }
            }
        }
    }

    /** Reloads the scheduled board when it is older than a minute (or on demand). */
    suspend fun refresh(force: Boolean = false) {
        val s = _state.value
        val now = Instant.now()
        if (force || s.loadedAt == null || Duration.between(s.loadedAt, now) > Duration.ofMinutes(1)) {
            val from = s.start ?: now
            val until = s.end ?: from.plus(DEFAULT_WINDOW)
            try {
                val (list, source) = departures.load(stationKey, s.arrivals, from, language.current(), Duration.between(from, until).coerceAtLeast(STEP))
                _state.update { it.copy(scheduled = list, source = source, loadedAt = now, loading = false, loadingMore = false, error = null) }
            } catch (e: DataException) {
                _state.update { it.copy(loading = false, loadingMore = false, error = e.error) }
            }
        }
        val current = _state.value
        if (current.source == BoardSource.SCHEDULE) {
            departures.pollRealtime()
            // Live times only exist around now; skip the overlay for boards far in the past or future.
            val from = current.start ?: now
            if (Duration.between(now, from).abs() < Duration.ofHours(3)) {
                val live = departures.liveOverlay(stationKey, from.coerceAtLeast(now.minus(Duration.ofMinutes(30))), current.arrivals, language.current())
                _state.update { if (it.arrivals == current.arrivals) it.copy(liveTimes = live) else it }
            }
        }
    }

    fun setArrivals(value: Boolean) {
        if (value == _state.value.arrivals) return
        _state.update { it.copy(arrivals = value, loading = true, loadedAt = null, scheduled = emptyList(), liveTimes = null) }
        viewModelScope.launch { refresh(force = true) }
    }

    /** Shows departures from an hour before the current window start. */
    fun earlier() = reload { s ->
        val from = s.start ?: Instant.now()
        s.copy(start = from.minus(STEP), end = s.end ?: from.plus(DEFAULT_WINDOW))
    }

    /** Extends the window by an hour (keeps what is shown). */
    fun later() = reload { s ->
        // A live board stays live (keeps moving with time); only its end moves out.
        s.copy(end = (s.end ?: (s.start ?: Instant.now()).plus(DEFAULT_WINDOW)).plus(STEP))
    }

    /** Starts the board at a chosen date and time. */
    fun setTime(time: Instant) = reload(clear = true) { it.copy(start = time, end = null) }

    /** Back to the live board. */
    fun now() = reload(clear = true) { it.copy(start = null, end = null) }

    private fun reload(clear: Boolean = false, change: (StopState) -> StopState) {
        _state.update { change(it).let { n -> if (clear) n.copy(loading = true, loadedAt = null, scheduled = emptyList()) else n.copy(loadingMore = true) } }
        viewModelScope.launch { refresh(force = true) }
    }

    fun toggleSaved() = viewModelScope.launch {
        val stop = _state.value.stop ?: Stop(stationKey, _state.value.name, _state.value.platforms.firstOrNull()?.point ?: Point(0.0, 0.0), stationKey = stationKey)
        if (isSaved.value) saved.remove(SavedKind.STOP, stationKey) else saved.saveStop(stop)
    }

}
