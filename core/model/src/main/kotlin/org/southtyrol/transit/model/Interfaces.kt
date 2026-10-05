package org.southtyrol.transit.model

import java.time.Instant
import java.time.LocalDate

/** A direction/pattern of a line: ordered stops plus geometry. */
data class LineVariant(
    val id: String,
    val routeId: String,
    val direction: Int,
    val headsign: String,
    val stops: List<Stop>,
    val shape: List<Point>,
    val sampleTripId: String,
    val tripCount: Int,
)

/** Static schedule (GTFS) queries. Implementations must be main-safe. */
interface TransitScheduleDataSource {
    suspend fun isAvailable(): Boolean
    suspend fun searchStops(query: String, language: String, limit: Int = 40): List<Stop>
    suspend fun nearbyStops(center: Point, radiusMeters: Double, language: String, limit: Int = 40): List<Stop>
    suspend fun stopsIn(box: BoundingBox, language: String, limit: Int = 3000): List<Stop>
    suspend fun stop(id: String, language: String): Stop?
    suspend fun stationStops(stationKey: String, language: String): List<Stop>
    suspend fun scheduledDepartures(stopIds: Collection<String>, from: Instant, until: Instant, arrivals: Boolean, language: String, limit: Int): List<Departure>
    suspend fun linesAtStops(stopIds: Collection<String>): List<Line>
    suspend fun searchLines(query: String): List<Line>
    suspend fun line(key: String): Line?
    suspend fun lineVariants(line: Line, language: String): List<LineVariant>
    suspend fun variantTrips(line: Line, variant: LineVariant, from: Instant, until: Instant, language: String): List<Departure>
    suspend fun trip(tripId: String, serviceDate: LocalDate, language: String): TripDetail?
    suspend fun routes(ids: Collection<String>): Map<String, Route>
    suspend fun operators(): List<Operator>
}

enum class RealtimeFeed(val path: String) { TRIP_UPDATES("trip-updates"), VEHICLE_POSITIONS("vehicle-positions"), SERVICE_ALERTS("service-alerts") }

interface RealtimeTransitDataSource {
    suspend fun fetch(feed: RealtimeFeed): ByteArray
}

interface GeocodingLocationDataSource {
    suspend fun search(query: String, language: String): List<Place>
    suspend fun reverse(point: Point, language: String): Place?
}

interface JourneyPlannerDataSource {
    suspend fun plan(from: Place, to: Place, options: SearchOptions, language: String): List<Journey>
}

/** Network departure board, used when no static schedule is cached yet. */
interface DepartureBoardDataSource {
    suspend fun departures(stopGlobalId: String, at: Instant, arrivals: Boolean, language: String, limit: Int): List<Departure>
}

interface AlertDataSource {
    suspend fun alerts(language: String): List<ServiceAlert>
}

interface MobilityDataSource {
    val kind: MobilityKind
    suspend fun points(box: BoundingBox, now: Instant): List<MobilityPoint>
}

/**
 * Ticket sales/validation. No public API exists for South Tyrol, so the shipped implementation only
 * links to the official service. A legitimate API could implement purchase flows behind this later.
 */
interface TicketingProvider {
    val canPurchaseInApp: Boolean
    val informationUrl: String
    val tariffInfoUrl: String
}

class OfficialTicketingLink : TicketingProvider {
    override val canPurchaseInApp = false
    override val informationUrl = "https://www.suedtirolmobil.info/"
    override val tariffInfoUrl = "https://www.suedtirolmobil.info/"
}

/** Contract for a future server that watches GTFS-RT/EFA and sends FCM pushes (see docs/PUSH_BACKEND.md). */
interface PushBackend {
    val available: Boolean
    suspend fun subscribe(token: String, routes: Set<String>, stops: Set<String>, language: String)
    suspend fun unsubscribe(token: String)
}

object NoPushBackend : PushBackend {
    override val available = false
    override suspend fun subscribe(token: String, routes: Set<String>, stops: Set<String>, language: String) = Unit
    override suspend fun unsubscribe(token: String) = Unit
}
