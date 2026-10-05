package org.southtyrol.transit.model

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** All schedules are expressed in the network's timezone, never the device timezone. */
val TransitZone: ZoneId = ZoneId.of("Europe/Rome")

enum class TransportMode {
    BUS, CITY_BUS, TRAIN, CABLE_CAR, FUNICULAR, TRAM, ON_DEMAND, WALK, OTHER;

    val isTransit: Boolean get() = this != WALK

    companion object {
        /** GTFS route_type including the extended (Hierarchical Vehicle Type) ranges. */
        fun fromGtfs(routeType: Int): TransportMode = when (routeType) {
            0, in 900..999 -> TRAM
            1, 2, in 100..199, in 400..499 -> TRAIN
            3, 11, in 200..299, in 700..799 -> BUS
            5, 6, in 1300..1399 -> CABLE_CAR
            7, in 1400..1499 -> FUNICULAR
            in 1500..1599 -> ON_DEMAND
            else -> OTHER
        }

        /** EFA "motType" codes as documented by the STA EFA XML interface. */
        fun fromEfa(motType: Int?): TransportMode = when (motType) {
            0, 1, 13, 14, 15, 16, 18 -> TRAIN
            2, 3, 4 -> TRAM
            5 -> CITY_BUS
            6, 7, 17, 19, 21 -> BUS
            8 -> CABLE_CAR
            10, 20 -> ON_DEMAND
            99, 100 -> WALK
            else -> OTHER
        }
    }
}

enum class Freshness { LIVE, SCHEDULED, STALE, UNAVAILABLE }

enum class ServiceState { NORMAL, CANCELLED, SKIPPED, ADDED, UNKNOWN }

enum class Accessibility {
    UNKNOWN, ACCESSIBLE, NOT_ACCESSIBLE;

    companion object {
        fun fromGtfs(value: Int) = when (value) { 1 -> ACCESSIBLE; 2 -> NOT_ACCESSIBLE; else -> UNKNOWN }
    }
}

data class Point(val latitude: Double, val longitude: Double) {
    val isValid: Boolean get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0)
}

enum class PlaceType { STOP, ADDRESS, STREET, POI, LOCALITY, COORDINATE, UNKNOWN }

/** A location that can be used as journey origin/destination. [id] is opaque to the UI. */
data class Place(
    val id: String,
    val name: String,
    val type: PlaceType = PlaceType.STOP,
    val point: Point? = null,
    val locality: String = "",
    /** Network-wide stop identifier (e.g. `it:22021:468`) when the place is a stop. */
    val stopGlobalId: String = "",
)

data class Stop(
    val id: String,
    val name: String,
    val point: Point,
    val code: String = "",
    /** Station grouping key shared by all platforms of the same stop area (e.g. `it:22021:468`). */
    val stationKey: String = "",
    val platform: String = "",
    val wheelchair: Accessibility = Accessibility.UNKNOWN,
    val distanceMeters: Double? = null,
)

data class Operator(val id: String, val name: String, val url: String = "", val phone: String = "")

data class Route(
    val id: String,
    val shortName: String,
    val longName: String,
    val mode: TransportMode,
    val operator: Operator? = null,
    val color: Long? = null,
    val textColor: Long? = null,
)

/** A passenger-facing line, i.e. all GTFS route variants sharing the same public name and mode. */
data class Line(
    val key: String,
    val name: String,
    val mode: TransportMode,
    val routeIds: List<String>,
    val longName: String = "",
    val color: Long? = null,
    val textColor: Long? = null,
)

data class Trip(val id: String, val routeId: String, val serviceId: String, val headsign: String, val direction: Int, val shapeId: String)

data class TripStop(
    val stop: Stop,
    val sequence: Int,
    val scheduledArrival: Instant,
    val scheduledDeparture: Instant,
    val predictedArrival: Instant? = null,
    val predictedDeparture: Instant? = null,
    val state: ServiceState = ServiceState.NORMAL,
)

data class TripDetail(
    val trip: Trip,
    val route: Route,
    val serviceDate: LocalDate,
    val stops: List<TripStop>,
    val shape: List<Point>,
    val freshness: Freshness,
    val state: ServiceState = ServiceState.NORMAL,
    val vehicle: Vehicle? = null,
    val observedAt: Instant? = null,
)

data class Departure(
    val tripId: String,
    val stopId: String,
    val sequence: Int,
    val routeId: String,
    val line: String,
    val mode: TransportMode,
    val destination: String,
    val scheduled: Instant,
    val predicted: Instant? = null,
    val state: ServiceState = ServiceState.NORMAL,
    val freshness: Freshness = Freshness.SCHEDULED,
    val platform: String = "",
    val observedAt: Instant? = null,
    val serviceDate: LocalDate = scheduled.atZone(TransitZone).toLocalDate(),
    val color: Long? = null,
    val textColor: Long? = null,
    val operator: String = "",
    val hasAlert: Boolean = false,
    /** True when the trip can be opened in the GTFS-backed trip view. */
    val tripLinked: Boolean = true,
) {
    val best: Instant get() = predicted ?: scheduled
    val delaySeconds: Long? get() = predicted?.let { Duration.between(scheduled, it).seconds }
    val key: String get() = "$tripId|$serviceDate|$stopId|$sequence"
}

data class IntermediateStop(
    val place: Place,
    val arrival: Instant?,
    val departure: Instant?,
    val predictedArrival: Instant? = null,
    val predictedDeparture: Instant? = null,
    val platform: String = "",
)

data class Leg(
    val mode: TransportMode,
    val line: String,
    val destination: String,
    val from: Place,
    val to: Place,
    val departure: Instant,
    val arrival: Instant,
    val predictedDeparture: Instant? = null,
    val predictedArrival: Instant? = null,
    val intermediate: List<IntermediateStop> = emptyList(),
    val geometry: List<Point> = emptyList(),
    val operator: String = "",
    val departurePlatform: String = "",
    val arrivalPlatform: String = "",
    val realtime: Boolean = false,
    val cancelled: Boolean = false,
    val notices: List<String> = emptyList(),
    val distanceMeters: Int? = null,
    val lineName: String = "",
) {
    val bestDeparture: Instant get() = predictedDeparture ?: departure
    val bestArrival: Instant get() = predictedArrival ?: arrival
    val departureDelaySeconds: Long? get() = predictedDeparture?.let { Duration.between(departure, it).seconds }
    val arrivalDelaySeconds: Long? get() = predictedArrival?.let { Duration.between(arrival, it).seconds }
}

enum class FareKind { SINGLE, VALUE_CARD, SUBSCRIPTION_LEVEL, OTHER }

data class FareTicket(val name: String, val kind: FareKind, val price: BigDecimal, val currency: String, val traveller: String = "")

/** Fare data exactly as reported by the journey planner; never computed by the app. */
data class FareInformation(val tickets: List<FareTicket>, val source: String) {
    val single: FareTicket? get() = tickets.firstOrNull { it.kind == FareKind.SINGLE }
    val valueCard: FareTicket? get() = tickets.firstOrNull { it.kind == FareKind.VALUE_CARD }
}

data class Journey(val id: String, val legs: List<Leg>, val changes: Int, val fare: FareInformation? = null) {
    init { require(legs.isNotEmpty()) { "A journey needs at least one leg" } }

    val departure: Instant get() = legs.first().departure
    val arrival: Instant get() = legs.last().arrival
    val bestDeparture: Instant get() = legs.first().bestDeparture
    val bestArrival: Instant get() = legs.last().bestArrival
    val duration: Duration get() = Duration.between(bestDeparture, bestArrival)
    val walkingDuration: Duration
        get() = legs.filter { it.mode == TransportMode.WALK }.fold(Duration.ZERO) { acc, l -> acc + Duration.between(l.departure, l.arrival) }
    val walkingMeters: Int get() = legs.filter { it.mode == TransportMode.WALK }.sumOf { it.distanceMeters ?: 0 }
    val hasRealtime: Boolean get() = legs.any { it.realtime }
    val cancelled: Boolean get() = legs.any { it.cancelled }
    val maxDelaySeconds: Long get() = legs.maxOf { maxOf(it.departureDelaySeconds ?: 0, it.arrivalDelaySeconds ?: 0) }
    val transitLegs: List<Leg> get() = legs.filter { it.mode.isTransit }
    val hasNotices: Boolean get() = legs.any { it.notices.isNotEmpty() }
}

data class Vehicle(
    val id: String,
    val point: Point,
    val tripId: String?,
    val routeId: String?,
    val bearing: Float?,
    val timestamp: Instant,
    val stopId: String? = null,
    val occupancy: String? = null,
    val label: String = "",
    val currentStatus: String? = null,
    val startDate: LocalDate? = null,
)

enum class AlertSource { GTFS_RT, EFA }

data class ServiceAlert(
    val id: String,
    val headers: Map<String, String>,
    val descriptions: Map<String, String>,
    val routes: Set<String> = emptySet(),
    val stops: Set<String> = emptySet(),
    val trips: Set<String> = emptySet(),
    /** Public line names (e.g. "201") for alerts sourced without GTFS route ids. */
    val lineNames: Set<String> = emptySet(),
    val periods: List<Pair<Instant?, Instant?>> = emptyList(),
    val observedAt: Instant,
    val effect: String = "",
    val cause: String = "",
    val severity: String = "",
    val urls: Map<String, String> = emptyMap(),
    val source: AlertSource = AlertSource.GTFS_RT,
) {
    fun active(at: Instant) = periods.isEmpty() || periods.any { (start, end) -> (start == null || !at.isBefore(start)) && (end == null || at.isBefore(end)) }
    fun upcoming(at: Instant) = !active(at) && periods.any { (start, _) -> start != null && start.isAfter(at) }
    fun expired(at: Instant) = !active(at) && !upcoming(at)
    val validFrom: Instant? get() = periods.mapNotNull { it.first }.minOrNull()
    val validUntil: Instant? get() = if (periods.isEmpty() || periods.any { it.second == null }) null else periods.mapNotNull { it.second }.maxOrNull()

    /** True when the alert reports a reduction of service (cancellations, stops not served, ...). */
    val isDisruption: Boolean get() = effect in DisruptiveEffects

    companion object {
        val DisruptiveEffects = setOf("NO_SERVICE", "REDUCED_SERVICE", "SIGNIFICANT_DELAYS", "DETOUR", "STOP_MOVED", "MODIFIED_SERVICE")
    }
}

enum class MobilityKind { PARKING, BIKE_SHARING, CAR_SHARING }

data class MobilityPoint(
    val id: String,
    val kind: MobilityKind,
    val name: String,
    val point: Point,
    val available: Int?,
    val capacity: Int?,
    val updatedAt: Instant?,
    val provider: String,
    val freshness: Freshness,
)

enum class RoutePreference { FASTEST, FEWEST_CHANGES, LEAST_WALKING }

enum class WalkingSpeed { SLOW, NORMAL, FAST }

data class SearchOptions(
    val at: ZonedDateTime = ZonedDateTime.now(TransitZone),
    val arriveBy: Boolean = false,
    val preference: RoutePreference = RoutePreference.FASTEST,
    val excludedModes: Set<TransportMode> = emptySet(),
    val wheelchair: Boolean = false,
    val walkingSpeed: WalkingSpeed = WalkingSpeed.NORMAL,
)

/** Typed, UI-describable failures. Repositories never leak transport exceptions to ViewModels. */
sealed class DataError {
    data object Offline : DataError()
    data object Timeout : DataError()
    data class Throttled(val retryAfterSeconds: Long?) : DataError()
    data class Server(val status: Int) : DataError()
    data object Parse : DataError()
    data object NoSchedule : DataError()
    data object NotFound : DataError()
    data class Planner(val code: String) : DataError()
    data object Unknown : DataError()
}

class DataException(val error: DataError, cause: Throwable? = null) : Exception(error.toString(), cause)

/** Value with an explicit observation time so the UI can always show its age. */
data class Timed<T>(val value: T, val fetchedAt: Instant?, val freshness: Freshness)
