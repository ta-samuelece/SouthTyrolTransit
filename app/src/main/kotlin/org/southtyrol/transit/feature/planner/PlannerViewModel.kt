package org.southtyrol.transit.feature.planner

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.southtyrol.transit.data.Codec
import org.southtyrol.transit.data.JourneyRequest
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.PlacesRepository
import org.southtyrol.transit.data.RecentItem
import org.southtyrol.transit.data.SavedItem
import org.southtyrol.transit.data.SavedKind
import org.southtyrol.transit.data.SavedRepository
import org.southtyrol.transit.feature.common.placesInOrder
import org.southtyrol.transit.data.ScheduleRepository
import org.southtyrol.transit.data.SettingsRepository
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.RoutePreference
import org.southtyrol.transit.model.SearchOptions
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.WalkingSpeed
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject

/** Encodes a journey request into a navigation argument (no personal data leaves the device). */
object RequestCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(r: JourneyRequest, leaveNow: Boolean): String = buildJsonObject {
        put("from", json.parseToJsonElement(Codec.place(r.from)))
        put("to", json.parseToJsonElement(Codec.place(r.to)))
        if (!leaveNow) put("at", r.options.at.toInstant().epochSecond)
        put("arriveBy", r.options.arriveBy)
        put("preference", r.options.preference.name)
        put("excluded", kotlinx.serialization.json.JsonArray(r.options.excludedModes.map { JsonPrimitive(it.name) }))
        put("wheelchair", r.options.wheelchair)
        put("walking", r.options.walkingSpeed.name)
        put("maxWalk", r.options.maxWalkMinutes)
        if (r.options.withBike) put("bike", true)
    }.toString()

    /** Decodes a request; a missing time means "leave now" relative to [now]. */
    fun decode(text: String, now: Instant = Instant.now()): JourneyRequest? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val from = Codec.place(o.getValue("from").toString()) ?: return null
        val to = Codec.place(o.getValue("to").toString()) ?: return null
        val at = o["at"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochSecond) ?: now
        JourneyRequest(
            from, to,
            SearchOptions(
                at = at.atZone(TransitZone),
                arriveBy = o["arriveBy"]?.jsonPrimitive?.booleanOrNull ?: false,
                preference = o.enum("preference", RoutePreference.FEWEST_CHANGES),
                excludedModes = (o["excluded"]?.jsonArray?.mapNotNull { e -> runCatching { TransportMode.valueOf(e.jsonPrimitive.content) }.getOrNull() } ?: emptyList()).toSet(),
                wheelchair = o["wheelchair"]?.jsonPrimitive?.booleanOrNull ?: false,
                walkingSpeed = o.enum("walking", WalkingSpeed.NORMAL),
                maxWalkMinutes = o["maxWalk"]?.jsonPrimitive?.longOrNull?.toInt() ?: SearchOptions.DEFAULT_MAX_WALK_MINUTES,
                withBike = o["bike"]?.jsonPrimitive?.booleanOrNull ?: false,
            ),
        )
    }.getOrNull()

    fun isLeaveNow(text: String): Boolean = runCatching { json.parseToJsonElement(text).jsonObject["at"] == null }.getOrDefault(true)

    private inline fun <reified E : Enum<E>> JsonObject.enum(key: String, default: E): E =
        this[key]?.jsonPrimitive?.contentOrNull?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default
}

enum class Field { FROM, TO }

data class PlannerState(
    val from: Place? = null,
    val to: Place? = null,
    /** null = leave now. */
    val time: ZonedDateTime? = null,
    val arriveBy: Boolean = false,
    val preference: RoutePreference = RoutePreference.FEWEST_CHANGES,
    val excludedModes: Set<TransportMode> = emptySet(),
    val wheelchair: Boolean = false,
    val walkingSpeed: WalkingSpeed = WalkingSpeed.NORMAL,
    val withBike: Boolean = false,
    val maxWalkMinutes: Int = SearchOptions.DEFAULT_MAX_WALK_MINUTES,
    val locating: Field? = null,
    val locationError: LocationResult? = null,
)

@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val places: PlacesRepository,
    private val saved: SavedRepository,
    private val settings: SettingsRepository,
    private val location: LocationProvider,
    private val language: LanguageProvider,
    timetable: ScheduleRepository,
    private val handle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<PlannerState> = _state.asStateFlow()

    /** The endpoint the place search sheet is open for; null while it is closed. */
    private val _searchField = MutableStateFlow<Field?>(null)
    val searchField: StateFlow<Field?> = _searchField.asStateFlow()

    val recentJourneys: StateFlow<List<RecentItem>> = saved.recentJourneys(5).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savedPlaces: StateFlow<List<SavedItem>> = saved.saved.map { it.placesInOrder() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val savedJourneys: StateFlow<List<SavedItem>> = saved.saved.map { items -> items.filter { it.kind == SavedKind.JOURNEY } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scheduleInfo = timetable.info
    val scheduleStatus = timetable.status

    init {
        viewModelScope.launch {
            timetable.loadInfo()
            val prefs = settings.current()
            _state.update { if (handle.get<String>(KEY) == null) it.copy(wheelchair = prefs.wheelchair, walkingSpeed = prefs.walkingSpeed) else it }
        }
    }

    private fun restore(): PlannerState {
        val text = handle.get<String>(KEY) ?: return PlannerState()
        val request = RequestCodec.decode(text) ?: return PlannerState()
        val leaveNow = RequestCodec.isLeaveNow(text)
        return PlannerState(
            request.from, request.to, if (leaveNow) null else request.options.at, request.options.arriveBy, request.options.preference,
            request.options.excludedModes, request.options.wheelchair, request.options.walkingSpeed, request.options.withBike,
            maxWalkMinutes = request.options.maxWalkMinutes,
        )
    }

    private fun persist() {
        val s = _state.value
        if (s.from != null && s.to != null) handle[KEY] = RequestCodec.encode(JourneyRequest(s.from, s.to, options(s)), s.time == null)
    }

    private fun update(block: (PlannerState) -> PlannerState) { _state.update(block); persist() }

    fun openSearch(field: Field) { _searchField.value = field }
    fun closeSearch() { _searchField.value = null }

    fun choose(place: Place) {
        val field = _searchField.value ?: return
        update { if (field == Field.FROM) it.copy(from = place) else it.copy(to = place) }
        closeSearch()
    }

    /**
     * A saved place tapped on the Plan screen: plans from the current location to [to], leaving now. [onReady]
     * runs once the location is found (to open the results); a failure shows the usual location snackbar.
     */
    fun planFromHere(to: Place, onReady: () -> Unit) {
        update { it.copy(to = to, time = null) }
        useCurrentLocation(Field.FROM, then = onReady)
    }

    /** "Start from here" in a saved place's long-press menu. */
    fun startFrom(place: Place) = update { it.copy(from = place) }

    fun swap() = update { it.copy(from = it.to, to = it.from) }
    fun setTime(time: ZonedDateTime?) = update { it.copy(time = time) }
    fun setArriveBy(value: Boolean) = update { it.copy(arriveBy = value) }
    fun setPreference(value: RoutePreference) = update { it.copy(preference = value) }
    fun toggleMode(mode: TransportMode) = update { it.copy(excludedModes = if (mode in it.excludedModes) it.excludedModes - mode else it.excludedModes + mode) }
    fun setWheelchair(value: Boolean) = update { it.copy(wheelchair = value) }
    fun setWalkingSpeed(value: WalkingSpeed) = update { it.copy(walkingSpeed = value) }
    fun setWithBike(value: Boolean) = update { it.copy(withBike = value) }
    fun setMaxWalk(minutes: Int) = update { it.copy(maxWalkMinutes = minutes) }

    fun useRoute(from: Place, to: Place) = update { it.copy(from = from, to = to) }

    /** Fills [field] with the device location (only called after the user granted permission). */
    fun useCurrentLocation(field: Field, then: (() -> Unit)? = null) {
        _state.update { it.copy(locating = field, locationError = null) }
        closeSearch()
        viewModelScope.launch {
            when (val result = location.current()) {
                is LocationResult.Found -> {
                    // Plan from the exact coordinate; the reverse-geocoded name is only a label.
                    val nearest = places.reverse(result.point, language.current())
                    val place = Place("", nearest.name, PlaceType.COORDINATE, result.point, nearest.locality)
                    update { if (field == Field.FROM) it.copy(from = place, locating = null) else it.copy(to = place, locating = null) }
                    then?.invoke()
                }
                else -> _state.update { it.copy(locating = null, locationError = result) }
            }
        }
    }

    fun locationDenied() { _state.update { it.copy(locating = null, locationError = LocationResult.PermissionDenied) } }
    fun clearLocationError() { _state.update { it.copy(locationError = null) } }

    fun canSearch(): Boolean = _state.value.let { it.from != null && it.to != null }

    /** Builds the navigation argument for the results screen. */
    fun request(): String? {
        val s = _state.value
        val from = s.from ?: return null
        val to = s.to ?: return null
        return RequestCodec.encode(JourneyRequest(from, to, options(s)), s.time == null)
    }

    private fun options(s: PlannerState) = SearchOptions(
        at = s.time ?: ZonedDateTime.now(TransitZone), arriveBy = s.arriveBy, preference = s.preference,
        excludedModes = s.excludedModes, wheelchair = s.wheelchair, walkingSpeed = s.walkingSpeed, withBike = s.withBike,
        maxWalkMinutes = s.maxWalkMinutes,
    )

    suspend fun homePlace(): Place? = saved.saved.first().firstOrNull { it.kind == SavedKind.PLACE && it.id == SavedRepository.HOME }?.place

    companion object {
        private const val KEY = "planner-request"
    }
}
