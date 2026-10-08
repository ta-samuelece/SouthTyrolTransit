package org.southtyrol.transit.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.southtyrol.transit.model.AlertDataSource
import org.southtyrol.transit.model.AlertDeduplicator
import org.southtyrol.transit.model.AlertSource
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.DepartureBoardDataSource
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.Geo
import org.southtyrol.transit.model.GeocodingLocationDataSource
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.JourneyPlannerDataSource
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.LineVariant
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RealtimeFeed
import org.southtyrol.transit.model.LiveOverlay
import org.southtyrol.transit.model.RealtimeMerge
import org.southtyrol.transit.model.RealtimeSnapshot
import org.southtyrol.transit.model.Route
import org.southtyrol.transit.model.SearchOptions
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TextNormalizer
import org.southtyrol.transit.model.TransitScheduleDataSource
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.model.Vehicle
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// ---------------------------------------------------------------------------------------------
// Serialization of small user-owned values (saved places, recent searches, cached alerts).
// ---------------------------------------------------------------------------------------------

object Codec {
    private val json = Json { ignoreUnknownKeys = true }

    fun place(p: Place): String = placeObject(p).toString()

    private fun placeObject(p: Place) = buildJsonObject {
        put("id", p.id); put("name", p.name); put("type", p.type.name); put("locality", p.locality); put("gid", p.stopGlobalId)
        p.point?.let { put("lat", it.latitude); put("lon", it.longitude) }
    }

    fun place(text: String): Place? = runCatching { place(json.parseToJsonElement(text).jsonObject) }.getOrNull()

    private fun place(o: JsonObject): Place {
        val lat = o["lat"]?.jsonPrimitive?.doubleOrNull; val lon = o["lon"]?.jsonPrimitive?.doubleOrNull
        return Place(
            id = o.str("id"), name = o.str("name"),
            type = runCatching { PlaceType.valueOf(o.str("type")) }.getOrDefault(PlaceType.UNKNOWN),
            point = if (lat != null && lon != null) Point(lat, lon) else null,
            locality = o.str("locality"), stopGlobalId = o.str("gid"),
        )
    }

    fun route(from: Place, to: Place): String = buildJsonObject { put("from", placeObject(from)); put("to", placeObject(to)) }.toString()

    fun route(text: String): Pair<Place, Place>? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        place(o.getValue("from").jsonObject) to place(o.getValue("to").jsonObject)
    }.getOrNull()

    fun alerts(list: List<ServiceAlert>): String = buildJsonArray {
        for (a in list) add(buildJsonObject {
            put("id", a.id); put("source", a.source.name); put("effect", a.effect); put("cause", a.cause); put("severity", a.severity)
            put("observedAt", a.observedAt.epochSecond)
            put("headers", JsonObject(a.headers.mapValues { JsonPrimitive(it.value) }))
            put("descriptions", JsonObject(a.descriptions.mapValues { JsonPrimitive(it.value) }))
            put("urls", JsonObject(a.urls.mapValues { JsonPrimitive(it.value) }))
            put("routes", JsonArray(a.routes.map(::JsonPrimitive))); put("stops", JsonArray(a.stops.map(::JsonPrimitive)))
            put("trips", JsonArray(a.trips.map(::JsonPrimitive))); put("lines", JsonArray(a.lineNames.map(::JsonPrimitive)))
            put("periods", JsonArray(a.periods.map { (s, e) -> JsonArray(listOf(JsonPrimitive(s?.epochSecond), JsonPrimitive(e?.epochSecond))) }))
        })
    }.toString()

    fun alerts(text: String): List<ServiceAlert> = runCatching {
        json.parseToJsonElement(text).jsonArray.map { e ->
            val o = e.jsonObject
            fun map(k: String) = (o[k] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
            fun set(k: String) = (o[k] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            ServiceAlert(
                id = o.str("id"), headers = map("headers"), descriptions = map("descriptions"), routes = set("routes"), stops = set("stops"),
                trips = set("trips"), lineNames = set("lines"),
                periods = (o["periods"] as? JsonArray)?.map { p ->
                    val arr = p.jsonArray
                    arr[0].jsonPrimitive.longOrNull?.let(Instant::ofEpochSecond) to arr[1].jsonPrimitive.longOrNull?.let(Instant::ofEpochSecond)
                }.orEmpty(),
                observedAt = Instant.ofEpochSecond(o["observedAt"]?.jsonPrimitive?.longOrNull ?: 0),
                effect = o.str("effect"), cause = o.str("cause"), severity = o.str("severity"), urls = map("urls"),
                source = runCatching { AlertSource.valueOf(o.str("source")) }.getOrDefault(AlertSource.EFA),
            )
        }
    }.getOrDefault(emptyList())

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}

// ---------------------------------------------------------------------------------------------
// Alerts
// ---------------------------------------------------------------------------------------------

data class AlertsState(val alerts: List<ServiceAlert> = emptyList(), val fetchedAt: Instant? = null, val freshness: Freshness = Freshness.UNAVAILABLE, val error: DataError? = null)

object AlertMatcher {
    fun affectsDeparture(alert: ServiceAlert, d: Departure): Boolean =
        d.routeId in alert.routes || d.tripId in alert.trips || (alert.lineNames.isNotEmpty() && d.line in alert.lineNames) ||
            (alert.routes.isEmpty() && alert.lineNames.isEmpty() && alert.trips.isEmpty() && (d.stopId in alert.stops || GtfsFiles.stationKey(d.stopId, "") in alert.stops))

    fun affectsStop(alert: ServiceAlert, stationKey: String): Boolean = stationKey in alert.stops

    fun affectsLine(alert: ServiceAlert, line: Line): Boolean =
        alert.routes.any { it in line.routeIds } || (line.name.isNotBlank() && line.name in alert.lineNames)
}

/**
 * GTFS-RT Service Alerts are the standard source; EFA AddInfo adds the richer STA notices. Both are
 * de-duplicated and cached so the last known alerts remain visible offline (marked stale).
 */
class AlertRepository(
    private val realtime: RealtimeRepository,
    private val efa: AlertDataSource,
    private val user: UserDao,
    private val clock: () -> Instant = Instant::now,
) {
    private val efaAlerts = MutableStateFlow<Pair<List<ServiceAlert>, Instant?>>(emptyList<ServiceAlert>() to null)
    private val efaError = MutableStateFlow<DataError?>(null)
    private val mutex = Mutex()
    private var lastEfaAttempt: Instant = Instant.EPOCH

    val state: Flow<AlertsState> = combine(realtime.snapshot, realtime.status, efaAlerts, efaError) { snapshot, status, efa, error ->
        val now = clock()
        val merged = AlertDeduplicator.merge(snapshot.alerts + efa.first).filter { !it.expired(now) }
        val fetched = listOfNotNull(snapshot.alertsFetchedAt, efa.second).maxOrNull()
        val rtError = status[RealtimeFeed.SERVICE_ALERTS]?.error
        AlertsState(
            alerts = merged.sortedWith(compareByDescending<ServiceAlert> { it.isDisruption }.thenByDescending { it.validFrom ?: Instant.EPOCH }),
            fetchedAt = fetched,
            freshness = when {
                fetched == null -> Freshness.UNAVAILABLE
                Duration.between(fetched, now) <= Duration.ofMinutes(15) -> Freshness.LIVE
                else -> Freshness.STALE
            },
            error = if (rtError != null && error != null) rtError else null,
        )
    }

    suspend fun refresh(language: String, force: Boolean = false) {
        realtime.restoreCachedAlerts()
        restoreEfa()
        realtime.refresh(setOf(RealtimeFeed.SERVICE_ALERTS), force)
        mutex.withLock {
            val now = clock()
            if (!force && Duration.between(lastEfaAttempt, now) < Duration.ofMinutes(10)) return
            lastEfaAttempt = now
            try {
                val alerts = efa.alerts(language)
                efaAlerts.value = alerts to now
                efaError.value = null
                withContext(Dispatchers.IO) { user.cache(CacheRow(CACHE_EFA, Codec.alerts(alerts).encodeToByteArray(), now.toEpochMilli())) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                efaError.value = e.toDataError()
            }
        }
    }

    private suspend fun restoreEfa() {
        if (efaAlerts.value.second != null) return
        val row = withContext(Dispatchers.IO) { user.cache(CACHE_EFA) } ?: return
        val alerts = Codec.alerts(row.payload.decodeToString())
        efaAlerts.update { if (it.second == null) alerts to Instant.ofEpochMilli(row.fetchedAt) else it }
    }

    companion object {
        const val CACHE_EFA = "efa:addinfo"
    }
}

// ---------------------------------------------------------------------------------------------
// Departures
// ---------------------------------------------------------------------------------------------

enum class BoardSource { SCHEDULE, NETWORK }

data class Board(
    val departures: List<Departure>,
    val source: BoardSource,
    val realtime: Freshness,
    val realtimeFetchedAt: Instant?,
)

/** Departure-monitor results used as a live overlay on a timetable board. */
data class LiveTimes(val departures: List<Departure>, val fetchedAt: Instant)

class DepartureRepository(
    private val schedule: TransitScheduleDataSource,
    private val network: DepartureBoardDataSource,
    private val realtime: RealtimeRepository,
    private val clock: () -> Instant = Instant::now,
) {
    /** Scheduled departures for a station from GTFS, or the EFA board when no schedule is cached. */
    suspend fun load(stationKey: String, arrivals: Boolean, from: Instant, language: String, window: Duration = Duration.ofHours(4)): Pair<List<Departure>, BoardSource> {
        return if (schedule.isAvailable()) {
            schedule.scheduledDepartures(listOf(stationKey), from.minusSeconds(15 * 60), from.plus(window), arrivals, language, (window.toHours() * 30).toInt().coerceIn(120, 400)) to BoardSource.SCHEDULE
        } else {
            // The online board is limited by count, not time: ask for more when a longer window is shown.
            network.departures(stationKey, from, arrivals, language, (window.toHours() * 10).toInt().coerceIn(40, 150)) to BoardSource.NETWORK
        }
    }

    /**
     * Live times from the online departure monitor for a timetable board, applied by [merge] where
     * GTFS-RT has none. Empty on any error: the overlay is a best-effort extra.
     */
    suspend fun liveOverlay(stationKey: String, from: Instant, arrivals: Boolean, language: String): LiveTimes {
        val list = try { network.departures(stationKey, from, arrivals, language, 80) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { emptyList() }
        return LiveTimes(list, clock())
    }

    /** Directions at a station (timetable only; empty without one or when there is no real choice). */
    suspend fun directions(stationKey: String, language: String): List<org.southtyrol.transit.model.StopDirection> =
        if (!schedule.isAvailable()) emptyList() else runCatching { schedule.stationDirections(stationKey, language) }.getOrDefault(emptyList())

    /** Whether the arrivals board would differ from the departures board (else the toggle is pointless). */
    suspend fun arrivalsDiffer(stationKey: String): Boolean = !schedule.isAvailable() || runCatching { schedule.arrivalsDiffer(listOf(stationKey)) }.getOrDefault(true)

    /**
     * Applies realtime and alert flags; drops departures that have clearly left, or, when the board
     * starts at a chosen time ([keepFrom]), those before that time.
     */
    fun merge(
        list: List<Departure>, source: BoardSource, snapshot: RealtimeSnapshot, alerts: List<ServiceAlert>, arrivals: Boolean,
        now: Instant = clock(), keepFrom: Instant? = null, live: LiveTimes? = null,
    ): Board {
        val feed = FreshnessPolicy.feed(snapshot.updatesFetchedAt, now)
        val overlayFresh = live != null && Duration.between(live.fetchedAt, now) < Duration.ofMinutes(3)
        val gtfsRt = list.map { d -> if (source == BoardSource.SCHEDULE && feed == Freshness.LIVE) RealtimeMerge.departure(d, snapshot.update(d.tripId, d.serviceDate), now, arrivals) else d }
        val withLive = if (source == BoardSource.SCHEDULE && overlayFresh) LiveOverlay.apply(gtfsRt, live!!.departures, live.fetchedAt) else gtfsRt
        val merged = withLive.map { withRt ->
            val active = alerts.filter { it.active(now) }
            withRt.copy(hasAlert = active.any { AlertMatcher.affectsDeparture(it, withRt) })
        }.filter { d ->
            val keep = if (d.state == ServiceState.CANCELLED) d.scheduled else d.best
            !keep.isBefore(keepFrom ?: now.minusSeconds(60))
        }.sortedBy { it.best }
        // "Live" only when some departure actually carries live data: a fresh but empty GTFS-RT feed
        // must not make a pure timetable look live.
        val anyLive = merged.any { it.freshness == Freshness.LIVE }
        val realtimeState = when {
            anyLive -> Freshness.LIVE
            source == BoardSource.NETWORK || overlayFresh -> Freshness.SCHEDULED
            feed == Freshness.LIVE -> Freshness.SCHEDULED
            else -> feed
        }
        val fetchedAt = when {
            source == BoardSource.NETWORK -> now
            overlayFresh && (snapshot.updatesFetchedAt == null || live!!.fetchedAt.isAfter(snapshot.updatesFetchedAt)) -> live!!.fetchedAt
            else -> snapshot.updatesFetchedAt
        }
        return Board(merged, source, realtimeState, fetchedAt)
    }

    fun refreshRealtime() = setOf(RealtimeFeed.TRIP_UPDATES)

    suspend fun pollRealtime() = realtime.refresh(refreshRealtime())
}

// ---------------------------------------------------------------------------------------------
// Journey planning
// ---------------------------------------------------------------------------------------------

data class JourneyRequest(val from: Place, val to: Place, val options: SearchOptions)

class JourneyRepository(private val planner: JourneyPlannerDataSource, private val saved: SavedRepository) {
    private val _results = MutableStateFlow<Map<String, Journey>>(emptyMap())
    private var lastRequest: JourneyRequest? = null

    suspend fun plan(request: JourneyRequest, language: String): List<Journey> {
        if (request.from.name == request.to.name && request.from.id == request.to.id) throw DataException(DataError.Planner(EfaCodes.SAME_PLACE))
        val journeys = planner.plan(request.from, request.to, request.options, language)
        lastRequest = request
        _results.update { it + journeys.associateBy { j -> j.id } }
        saved.addRecentJourney(request.from, request.to)
        if (request.from.type != PlaceType.COORDINATE) saved.addRecentPlace(request.from)
        if (request.to.type != PlaceType.COORDINATE) saved.addRecentPlace(request.to)
        return journeys
    }

    fun journey(id: String): Journey? = _results.value[id]
    fun lastRequest(): JourneyRequest? = lastRequest
}

// ---------------------------------------------------------------------------------------------
// Places, saved items and recents (local only)
// ---------------------------------------------------------------------------------------------

class PlacesRepository(private val geocoder: GeocodingLocationDataSource, private val schedule: TransitScheduleDataSource) {
    /** Network place search with an offline fallback to stops from the cached schedule. */
    suspend fun search(query: String, language: String): Pair<List<Place>, DataError?> {
        if (query.isBlank()) return emptyList<Place>() to null
        return try {
            geocoder.search(query, language) to null
        } catch (e: DataException) {
            val offline = runCatching { schedule.searchStops(query, language, 15) }.getOrDefault(emptyList()).map { it.toPlace() }
            offline to e.error
        }
    }

    suspend fun reverse(point: Point, language: String): Place = runCatching { geocoder.reverse(point, language) }.getOrNull()
        ?: Place("", "", PlaceType.COORDINATE, point)

    companion object {
        /** EFA accepts the network-wide stop id as a stop reference. */
        fun Stop.toPlace() = Place(id, name, PlaceType.STOP, point, "", stationKey)
    }
}

enum class SavedKind { STOP, LINE, PLACE, JOURNEY }

data class SavedItem(val kind: SavedKind, val id: String, val label: String, val subtitle: String, val payload: String) {
    val place: Place? get() = if (kind == SavedKind.PLACE || kind == SavedKind.STOP) Codec.place(payload) else null
    val route: Pair<Place, Place>? get() = if (kind == SavedKind.JOURNEY) Codec.route(payload) else null
}

data class RecentItem(val id: String, val label: String, val subtitle: String, val payload: String, val used: Instant) {
    val place: Place? get() = Codec.place(payload)
    val route: Pair<Place, Place>? get() = Codec.route(payload)
}

class SavedRepository(private val user: UserDao, private val clock: () -> Instant = Instant::now) {
    val saved: Flow<List<SavedItem>> = user.saved().map { rows ->
        rows.mapNotNull { r -> runCatching { SavedKind.valueOf(r.kind) }.getOrNull()?.let { SavedItem(it, r.id, r.label, r.subtitle, r.payload) } }
    }

    fun isSaved(kind: SavedKind, id: String): Flow<Boolean> = user.isSaved(kind.name, id).map { it > 0 }

    suspend fun saveStop(stop: Stop) = user.save(SavedRow(SavedKind.STOP.name, stop.id, stop.name, "", Codec.place(PlacesRepository.run { stop.toPlace() }), clock().toEpochMilli()))
    suspend fun saveLine(line: Line) = user.save(SavedRow(SavedKind.LINE.name, line.key, line.name, line.mode.name + "|" + line.longName, "", clock().toEpochMilli()))
    suspend fun savePlace(id: String, label: String, place: Place) = user.save(SavedRow(SavedKind.PLACE.name, id, label, place.name, Codec.place(place), clock().toEpochMilli()))
    suspend fun saveJourney(from: Place, to: Place) = user.save(SavedRow(SavedKind.JOURNEY.name, "${from.id}|${from.name}->${to.id}|${to.name}", "${from.name} → ${to.name}", "", Codec.route(from, to), clock().toEpochMilli()))
    suspend fun remove(kind: SavedKind, id: String) = user.unsave(kind.name, id)

    fun recentPlaces(limit: Int = 8): Flow<List<RecentItem>> = user.recent(KIND_PLACE, limit).map { rows -> rows.map { RecentItem(it.id, it.label, it.subtitle, it.payload, Instant.ofEpochMilli(it.used)) } }
    fun recentJourneys(limit: Int = 6): Flow<List<RecentItem>> = user.recent(KIND_JOURNEY, limit).map { rows -> rows.map { RecentItem(it.id, it.label, it.subtitle, it.payload, Instant.ofEpochMilli(it.used)) } }

    suspend fun addRecentPlace(place: Place) {
        val key = place.id.ifBlank { TextNormalizer.key(place.name) }
        if (key.isBlank()) return
        user.touch(RecentRow(KIND_PLACE, key, place.name, place.locality, Codec.place(place), clock().toEpochMilli()))
        user.trimRecent(KIND_PLACE, 30)
    }

    suspend fun addRecentJourney(from: Place, to: Place) {
        user.touch(RecentRow(KIND_JOURNEY, "${from.id}|${from.name}->${to.id}|${to.name}", "${from.name} → ${to.name}", "", Codec.route(from, to), clock().toEpochMilli()))
        user.trimRecent(KIND_JOURNEY, 20)
    }

    suspend fun clearRecents() = user.clearRecent()

    companion object {
        const val KIND_PLACE = "place"
        const val KIND_JOURNEY = "journey"
        const val HOME = "home"
        const val WORK = "work"
    }
}

// ---------------------------------------------------------------------------------------------
// Lines, trips and the live map
// ---------------------------------------------------------------------------------------------

data class LiveVehicle(val vehicle: Vehicle, val route: Route?, val freshness: Freshness)

/** Journey-planner live times of one run (see [TripLiveDataSource]). */
data class LiveTrip(val stops: List<org.southtyrol.transit.model.LiveStopTime>, val fetchedAt: Instant)

class TripRepository(
    private val schedule: TransitScheduleDataSource,
    private val realtime: RealtimeRepository,
    private val live: org.southtyrol.transit.model.TripLiveDataSource? = null,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun trip(tripId: String, date: LocalDate, language: String): TripDetail? = schedule.trip(tripId, date, language)

    /** Best effort: null on any error or when there is no reference. */
    suspend fun liveTrip(ref: String): LiveTrip? {
        if (ref.isBlank() || live == null) return null
        return try { LiveTrip(live.liveTrip(ref), clock()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }

    fun merge(detail: TripDetail, snapshot: RealtimeSnapshot, now: Instant = clock(), liveTrip: LiveTrip? = null): TripDetail {
        val feed = FreshnessPolicy.feed(snapshot.updatesFetchedAt, now)
        val update = if (feed == Freshness.LIVE) snapshot.update(detail.trip.id, detail.serviceDate) else null
        val (gtfsStops, gtfsFreshness) = RealtimeMerge.tripStops(detail.stops, update, detail.serviceDate, now)
        // GTFS-RT first; the journey planner's live times fill the calls it has no prediction for.
        val planner = liveTrip?.takeIf { Duration.between(it.fetchedAt, now) < Duration.ofMinutes(3) }
            ?.let { org.southtyrol.transit.model.LiveTripMerge.apply(gtfsStops, it.stops) }
        val stops = planner ?: gtfsStops
        val freshness = if (planner != null) Freshness.LIVE else gtfsFreshness
        val vehicle = snapshot.vehicle(detail.trip.id, detail.serviceDate)?.takeIf { FreshnessPolicy.vehicle(it.timestamp, now) != Freshness.UNAVAILABLE }
        return detail.copy(
            stops = stops,
            freshness = when {
                planner != null -> Freshness.LIVE
                feed == Freshness.LIVE -> freshness
                feed == Freshness.STALE -> Freshness.STALE
                else -> Freshness.SCHEDULED
            },
            state = if (update?.state == ServiceState.CANCELLED) ServiceState.CANCELLED else detail.state,
            vehicle = vehicle,
            observedAt = if (planner != null) liveTrip.fetchedAt else update?.timestamp,
        )
    }

    suspend fun pollRealtime() = realtime.refresh(setOf(RealtimeFeed.TRIP_UPDATES, RealtimeFeed.VEHICLE_POSITIONS))
}

class LineRepository(private val schedule: TransitScheduleDataSource) {
    suspend fun search(query: String): List<Line> = schedule.searchLines(query)
    suspend fun line(key: String): Line? = schedule.line(key)
    suspend fun variants(line: Line, language: String): List<LineVariant> = schedule.lineVariants(line, language)
    suspend fun trips(line: Line, variant: LineVariant, from: Instant, until: Instant, language: String) = schedule.variantTrips(line, variant, from, until, language)
    suspend fun atStop(stationKey: String): List<Line> = schedule.linesAtStops(listOf(stationKey))
}

class MapRepository(private val schedule: TransitScheduleDataSource, private val realtime: RealtimeRepository, private val clock: () -> Instant = Instant::now) {
    private var routes: Map<String, Route> = emptyMap()

    suspend fun stops(box: BoundingBox, language: String): List<Stop> = if (schedule.isAvailable()) schedule.stopsIn(box, language, 1500) else emptyList()

    suspend fun vehicles(snapshot: RealtimeSnapshot, now: Instant = clock()): List<LiveVehicle> {
        val missing = snapshot.vehicles.mapNotNull { it.routeId }.filter { it !in routes }.toSet()
        if (missing.isNotEmpty() && schedule.isAvailable()) routes = routes + runCatching { schedule.routes(missing) }.getOrDefault(emptyMap())
        return snapshot.vehicles.mapNotNull { v ->
            val freshness = FreshnessPolicy.vehicle(v.timestamp, now)
            if (freshness == Freshness.UNAVAILABLE) null else LiveVehicle(v, v.routeId?.let { routes[it] }, freshness)
        }
    }

    suspend fun pollVehicles() = realtime.refresh(setOf(RealtimeFeed.VEHICLE_POSITIONS, RealtimeFeed.TRIP_UPDATES))

    fun vehiclesForLine(line: Line, snapshot: RealtimeSnapshot, now: Instant = clock()): List<Vehicle> =
        snapshot.vehicles.filter { it.routeId in line.routeIds && FreshnessPolicy.vehicle(it.timestamp, now) != Freshness.UNAVAILABLE }

    companion object {
        fun modeOf(v: LiveVehicle): TransportMode = v.route?.mode ?: TransportMode.BUS
        fun inRegion(p: Point) = p in Geo.SouthTyrol
    }
}
