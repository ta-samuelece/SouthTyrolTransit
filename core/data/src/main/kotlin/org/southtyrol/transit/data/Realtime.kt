package org.southtyrol.transit.data

import com.google.transit.realtime.GtfsRealtime as G
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.GtfsTime
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RealtimeFeed
import org.southtyrol.transit.model.RealtimeSnapshot
import org.southtyrol.transit.model.RealtimeTransitDataSource
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.StopUpdate
import org.southtyrol.transit.model.TripUpdate
import org.southtyrol.transit.model.Vehicle
import java.time.Duration
import java.time.Instant

data class ParsedFeed(val timestamp: Instant, val updates: List<TripUpdate>, val vehicles: List<Vehicle>, val alerts: List<ServiceAlert>)

object GtfsRealtimeParser {
    fun parse(bytes: ByteArray, received: Instant): ParsedFeed {
        val feed = G.FeedMessage.parseFrom(bytes)
        require(feed.hasHeader()) { "GTFS-RT header missing" }
        require(feed.header.incrementality == G.FeedHeader.Incrementality.FULL_DATASET) { "Differential GTFS-RT feeds are not supported" }
        val timestamp = if (feed.header.hasTimestamp() && feed.header.timestamp > 0) Instant.ofEpochSecond(feed.header.timestamp) else received

        val updates = feed.entityList.filter { it.hasTripUpdate() && !it.isDeleted && it.tripUpdate.trip.hasTripId() }.map { e ->
            val u = e.tripUpdate
            TripUpdate(
                tripId = u.trip.tripId,
                routeId = if (u.trip.hasRouteId()) u.trip.routeId else null,
                date = if (u.trip.hasStartDate()) runCatching { GtfsTime.date(u.trip.startDate) }.getOrNull() else null,
                timestamp = if (u.hasTimestamp() && u.timestamp > 0) Instant.ofEpochSecond(u.timestamp) else timestamp,
                state = tripState(u.trip.scheduleRelationship),
                stops = u.stopTimeUpdateList.map { s ->
                    StopUpdate(
                        stopId = if (s.hasStopId()) s.stopId else null,
                        sequence = if (s.hasStopSequence()) s.stopSequence else null,
                        arrivalTime = if (s.hasArrival() && s.arrival.hasTime() && s.arrival.time > 0) Instant.ofEpochSecond(s.arrival.time) else null,
                        departureTime = if (s.hasDeparture() && s.departure.hasTime() && s.departure.time > 0) Instant.ofEpochSecond(s.departure.time) else null,
                        arrivalDelay = if (s.hasArrival() && s.arrival.hasDelay()) s.arrival.delay else null,
                        departureDelay = if (s.hasDeparture() && s.departure.hasDelay()) s.departure.delay else null,
                        state = when (s.scheduleRelationship) { G.TripUpdate.StopTimeUpdate.ScheduleRelationship.SKIPPED -> ServiceState.SKIPPED; else -> ServiceState.NORMAL },
                        noData = s.scheduleRelationship == G.TripUpdate.StopTimeUpdate.ScheduleRelationship.NO_DATA,
                    )
                },
                delay = if (u.hasDelay()) u.delay else null,
                startTime = if (u.trip.hasStartTime()) u.trip.startTime else null,
            )
        }

        val vehicles = feed.entityList.filter { it.hasVehicle() && it.vehicle.hasPosition() && !it.isDeleted }.mapNotNull { e ->
            val v = e.vehicle; val p = v.position
            val point = Point(p.latitude.toDouble(), p.longitude.toDouble()).takeIf { it.isValid } ?: return@mapNotNull null
            Vehicle(
                id = if (v.hasVehicle() && v.vehicle.hasId()) v.vehicle.id else e.id,
                point = point,
                tripId = if (v.hasTrip() && v.trip.hasTripId()) v.trip.tripId else null,
                routeId = if (v.hasTrip() && v.trip.hasRouteId()) v.trip.routeId else null,
                bearing = if (p.hasBearing()) p.bearing else null,
                timestamp = if (v.hasTimestamp() && v.timestamp > 0) Instant.ofEpochSecond(v.timestamp) else timestamp,
                stopId = if (v.hasStopId()) v.stopId else null,
                occupancy = if (v.hasOccupancyStatus()) v.occupancyStatus.name else null,
                label = if (v.hasVehicle() && v.vehicle.hasLabel()) v.vehicle.label else "",
                currentStatus = if (v.hasCurrentStatus()) v.currentStatus.name else null,
                startDate = if (v.hasTrip() && v.trip.hasStartDate()) runCatching { GtfsTime.date(v.trip.startDate) }.getOrNull() else null,
            )
        }

        fun G.TranslatedString.strings(): Map<String, String> = translationList.filter { it.text.isNotBlank() }.associate { (if (it.hasLanguage()) it.language else "").lowercase() to it.text.trim() }

        val alerts = feed.entityList.filter { it.hasAlert() && !it.isDeleted }.map { e ->
            val a = e.alert
            val entities = a.informedEntityList
            ServiceAlert(
                id = e.id,
                headers = a.headerText.strings(),
                descriptions = a.descriptionText.strings(),
                routes = entities.filter { it.hasRouteId() }.map { it.routeId }.toSet(),
                // Store both the platform id and its station key so alerts match station-level views.
                stops = entities.filter { it.hasStopId() }.flatMap { listOf(it.stopId, GtfsFiles.stationKey(it.stopId, "")) }.toSet(),
                trips = entities.filter { it.hasTrip() && it.trip.hasTripId() }.map { it.trip.tripId }.toSet(),
                periods = a.activePeriodList.map { (if (it.hasStart() && it.start > 0) Instant.ofEpochSecond(it.start) else null) to (if (it.hasEnd() && it.end > 0) Instant.ofEpochSecond(it.end) else null) },
                observedAt = timestamp,
                effect = if (a.hasEffect()) a.effect.name else "",
                cause = if (a.hasCause()) a.cause.name else "",
                severity = if (a.hasSeverityLevel()) a.severityLevel.name else "",
                urls = if (a.hasUrl()) a.url.strings() else emptyMap(),
            )
        }
        return ParsedFeed(timestamp, updates, vehicles, alerts)
    }

    private fun tripState(value: G.TripDescriptor.ScheduleRelationship) = when (value.name) {
        "CANCELED", "DELETED" -> ServiceState.CANCELLED
        "ADDED", "DUPLICATED", "UNSCHEDULED", "NEW", "REPLACEMENT" -> ServiceState.ADDED
        "SCHEDULED" -> ServiceState.NORMAL
        else -> ServiceState.UNKNOWN
    }
}

/**
 * STA GTFS-Realtime via Open Data Hub. The provider-published files host is primary (it is what the
 * ODH GTFS API proxies, see its datasets.yml); the GTFS API is the fallback.
 */
class OdhRealtime(private val http: TransitHttp) : RealtimeTransitDataSource {
    override suspend fun fetch(feed: RealtimeFeed): ByteArray {
        val urls = listOf(
            "https://files.opendatahub.com/gtfs-rt/feeds/sta/${feed.path}.pb",
            "https://gtfs.api.opendatahub.com/v1/realtime/sta-time-tables/${feed.path}",
        )
        var error: Exception? = null
        for (url in urls) {
            try {
                return http.bytes(url, "application/x-protobuf", maxBytes = 20L * 1024 * 1024)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
        throw error ?: IllegalStateException("No realtime source")
    }
}

data class FeedStatus(val lastSuccess: Instant? = null, val lastAttempt: Instant? = null, val error: DataError? = null)

/**
 * Holds the latest realtime snapshot. Refreshes are throttled per feed so several visible screens
 * never multiply network requests, and nothing polls unless a screen asks for it.
 */
class RealtimeRepository(
    private val source: RealtimeTransitDataSource,
    private val user: UserDao,
    private val clock: () -> Instant = Instant::now,
) {
    private val mutex = Mutex()
    private val _snapshot = MutableStateFlow(RealtimeSnapshot())
    val snapshot: StateFlow<RealtimeSnapshot> = _snapshot.asStateFlow()
    private val _status = MutableStateFlow(RealtimeFeed.entries.associateWith { FeedStatus() })
    val status: StateFlow<Map<RealtimeFeed, FeedStatus>> = _status.asStateFlow()

    private fun minInterval(feed: RealtimeFeed) = when (feed) {
        RealtimeFeed.SERVICE_ALERTS -> Duration.ofMinutes(2)
        else -> Duration.ofSeconds(20)
    }

    /** Refreshes the given feeds if their data is older than the per-feed minimum interval. */
    suspend fun refresh(feeds: Set<RealtimeFeed>, force: Boolean = false) = mutex.withLock {
        for (feed in feeds) {
            val now = clock()
            val state = _status.value.getValue(feed)
            if (!force && state.lastAttempt != null && Duration.between(state.lastAttempt, now) < minInterval(feed)) continue
            try {
                val bytes = source.fetch(feed)
                val parsed = withContext(Dispatchers.Default) { GtfsRealtimeParser.parse(bytes, now) }
                apply(feed, parsed, now)
                if (feed == RealtimeFeed.SERVICE_ALERTS) withContext(Dispatchers.IO) { user.cache(CacheRow(CACHE_ALERTS, bytes, now.toEpochMilli())) }
                _status.update { it + (feed to FeedStatus(now, now, null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.update { it + (feed to state.copy(lastAttempt = now, error = e.toDataError())) }
            }
        }
    }

    private fun apply(feed: RealtimeFeed, parsed: ParsedFeed, now: Instant) {
        _snapshot.update { s ->
            when (feed) {
                RealtimeFeed.TRIP_UPDATES -> s.copy(updates = parsed.updates.groupBy { it.tripId }, updatesFetchedAt = now)
                RealtimeFeed.VEHICLE_POSITIONS -> s.copy(vehicles = parsed.vehicles, vehiclesFetchedAt = now)
                RealtimeFeed.SERVICE_ALERTS -> s.copy(alerts = parsed.alerts, alertsFetchedAt = now)
            }
        }
    }

    /** Restores the last alert feed from disk; its fetch time is kept so the UI can mark it stale. */
    suspend fun restoreCachedAlerts() {
        if (_snapshot.value.alertsFetchedAt != null) return
        val row = withContext(Dispatchers.IO) { user.cache(CACHE_ALERTS) } ?: return
        val at = Instant.ofEpochMilli(row.fetchedAt)
        runCatching { withContext(Dispatchers.Default) { GtfsRealtimeParser.parse(row.payload, at) } }.getOrNull()?.let { parsed ->
            _snapshot.update { if (it.alertsFetchedAt == null) it.copy(alerts = parsed.alerts, alertsFetchedAt = at) else it }
        }
    }

    companion object {
        const val CACHE_ALERTS = "gtfsrt:service-alerts"
    }
}
