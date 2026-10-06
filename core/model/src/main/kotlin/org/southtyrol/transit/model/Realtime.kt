package org.southtyrol.transit.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class StopUpdate(
    val stopId: String?,
    val sequence: Int?,
    val arrivalTime: Instant?,
    val departureTime: Instant?,
    val arrivalDelay: Int?,
    val departureDelay: Int?,
    val state: ServiceState,
    val noData: Boolean = false,
)

data class TripUpdate(
    val tripId: String,
    val routeId: String? = null,
    val date: LocalDate?,
    val timestamp: Instant,
    val state: ServiceState,
    val stops: List<StopUpdate>,
    val delay: Int? = null,
    val startTime: String? = null,
)

data class RealtimeSnapshot(
    val updates: Map<String, List<TripUpdate>> = emptyMap(),
    val vehicles: List<Vehicle> = emptyList(),
    val alerts: List<ServiceAlert> = emptyList(),
    val updatesFetchedAt: Instant? = null,
    val vehiclesFetchedAt: Instant? = null,
    val alertsFetchedAt: Instant? = null,
) {
    fun update(tripId: String, date: LocalDate): TripUpdate? = updates[tripId]?.firstOrNull { it.date == null || it.date == date }

    fun vehicle(tripId: String, date: LocalDate): Vehicle? = vehicles.firstOrNull { it.tripId == tripId && (it.startDate == null || it.startDate == date) }
}

/** Explicit, documented staleness rules. Durations in seconds. */
object FreshnessPolicy {
    /** A trip prediction older than this is ignored; the departure falls back to schedule with a stale hint. */
    const val TRIP_UPDATE_MAX_AGE = 300L

    /** A feed fetch older than this means "realtime currently unavailable". */
    const val FEED_MAX_AGE = 180L

    /** Vehicles are drawn normally up to this age. */
    const val VEHICLE_LIVE_AGE = 120L

    /** Vehicles between LIVE and this age are drawn dimmed and labelled stale; older ones are hidden. */
    const val VEHICLE_HIDE_AGE = 600L

    /** Mobility (parking etc.) values older than this are not shown as current. */
    const val MOBILITY_MAX_AGE = 3 * 3600L

    fun vehicle(timestamp: Instant, now: Instant): Freshness {
        val age = Duration.between(timestamp, now).seconds
        return when {
            age <= VEHICLE_LIVE_AGE -> Freshness.LIVE
            age <= VEHICLE_HIDE_AGE -> Freshness.STALE
            else -> Freshness.UNAVAILABLE
        }
    }

    fun feed(fetchedAt: Instant?, now: Instant): Freshness = when {
        fetchedAt == null -> Freshness.UNAVAILABLE
        Duration.between(fetchedAt, now).seconds <= FEED_MAX_AGE -> Freshness.LIVE
        else -> Freshness.STALE
    }

    fun mobility(updatedAt: Instant?, now: Instant): Freshness = when {
        updatedAt == null -> Freshness.UNAVAILABLE
        Duration.between(updatedAt, now).seconds <= MOBILITY_MAX_AGE -> Freshness.LIVE
        else -> Freshness.STALE
    }
}

object RealtimeMerge {
    /** Merges a scheduled departure (or arrival when [arrival] is true) with realtime data. */
    fun departure(base: Departure, update: TripUpdate?, now: Instant, arrival: Boolean = false): Departure {
        update ?: return base
        if (update.date != null && update.date != base.serviceDate) return base
        if (Duration.between(update.timestamp, now).seconds > FreshnessPolicy.TRIP_UPDATE_MAX_AGE) {
            return base.copy(freshness = Freshness.STALE, observedAt = update.timestamp)
        }
        if (update.state == ServiceState.CANCELLED) return base.copy(state = ServiceState.CANCELLED, freshness = Freshness.LIVE, observedAt = update.timestamp)
        return when (val predicted = predict(base.scheduled, base.sequence, base.stopId, update, arrival)) {
            is Prediction.Skipped -> base.copy(state = ServiceState.SKIPPED, freshness = Freshness.LIVE, observedAt = update.timestamp)
            is Prediction.Time -> base.copy(
                predicted = predicted.at,
                freshness = Freshness.LIVE,
                observedAt = update.timestamp,
                state = if (update.state == ServiceState.ADDED) ServiceState.ADDED else base.state,
            )
            Prediction.None -> base.copy(freshness = Freshness.SCHEDULED, observedAt = update.timestamp)
        }
    }

    sealed class Prediction {
        data object None : Prediction()
        data object Skipped : Prediction()
        data class Time(val at: Instant) : Prediction()
    }

    /**
     * GTFS-RT semantics: a stop time update applies to its stop and its delay propagates downstream
     * until the next update. NO_DATA breaks propagation; nothing propagates upstream except an explicit
     * trip-level delay.
     */
    fun predict(scheduled: Instant, sequence: Int, stopId: String, update: TripUpdate, arrival: Boolean): Prediction {
        val stops = update.stops
        val exact = stops.firstOrNull { if (it.sequence != null) it.sequence == sequence else it.stopId == stopId }
        if (exact != null) {
            if (exact.state == ServiceState.SKIPPED) return Prediction.Skipped
            if (exact.noData) return Prediction.None
            val time = if (arrival) exact.arrivalTime ?: exact.departureTime else exact.departureTime ?: exact.arrivalTime
            if (time != null) return Prediction.Time(time)
            val delay = if (arrival) exact.arrivalDelay ?: exact.departureDelay else exact.departureDelay ?: exact.arrivalDelay
            if (delay != null) return Prediction.Time(scheduled.plusSeconds(delay.toLong()))
        }
        val previous = stops.filter { it.sequence != null && it.sequence < sequence }.maxByOrNull { it.sequence!! }
        if (previous != null) {
            if (previous.noData) return Prediction.None
            val delay = previous.departureDelay ?: previous.arrivalDelay ?: return Prediction.None
            return Prediction.Time(scheduled.plusSeconds(delay.toLong()))
        }
        return update.delay?.let { Prediction.Time(scheduled.plusSeconds(it.toLong())) } ?: Prediction.None
    }

    fun tripStops(stops: List<TripStop>, update: TripUpdate?, serviceDate: LocalDate, now: Instant): Pair<List<TripStop>, Freshness> {
        if (update == null || (update.date != null && update.date != serviceDate)) return stops to Freshness.SCHEDULED
        if (Duration.between(update.timestamp, now).seconds > FreshnessPolicy.TRIP_UPDATE_MAX_AGE) return stops to Freshness.STALE
        if (update.state == ServiceState.CANCELLED) return stops.map { it.copy(state = ServiceState.CANCELLED) } to Freshness.LIVE
        return stops.map { s ->
            val dep = predict(s.scheduledDeparture, s.sequence, s.stop.id, update, arrival = false)
            val arr = predict(s.scheduledArrival, s.sequence, s.stop.id, update, arrival = true)
            if (dep is Prediction.Skipped) s.copy(state = ServiceState.SKIPPED)
            else s.copy(predictedDeparture = (dep as? Prediction.Time)?.at, predictedArrival = (arr as? Prediction.Time)?.at)
        } to Freshness.LIVE
    }
}

/**
 * Live times from the journey planner's departure monitor (EFA), applied to timetable departures that
 * GTFS-RT has no prediction for. The GTFS-RT trip-updates feed is often empty while EFA still reports
 * delays (the same source the planner shows), so without this a stop board would look on time.
 * Matching: same line name and scheduled time within a minute; destination breaks ties. Each live
 * entry is used at most once.
 */
object LiveOverlay {
    private const val TOLERANCE_SECONDS = 60L

    fun apply(list: List<Departure>, live: List<Departure>, observedAt: Instant?): List<Departure> {
        val pool = live.filter { it.predicted != null || it.state == ServiceState.CANCELLED }.toMutableList()
        if (pool.isEmpty()) return list
        return list.map { d ->
            if (d.predicted != null || d.state != ServiceState.NORMAL || d.freshness == Freshness.LIVE) return@map d
            fun gap(it: Departure) = kotlin.math.abs(java.time.Duration.between(it.scheduled, d.scheduled).seconds)
            val match = pool
                .filter { lineKey(it.line) == lineKey(d.line) && gap(it) <= TOLERANCE_SECONDS }
                .minByOrNull { gap(it) + if (similar(it.destination, d.destination)) 0 else 3600 }
                // Trains are named differently ("REG" in the timetable, "R 16719" in EFA): fall back to
                // the same mode, departing at the same minute, towards the same place.
                ?: pool.filter { it.mode == d.mode && gap(it) <= TOLERANCE_SECONDS && similar(it.destination, d.destination) }.minByOrNull(::gap)
                ?: return@map d
            pool.remove(match)
            if (match.state == ServiceState.CANCELLED) d.copy(state = ServiceState.CANCELLED, freshness = Freshness.LIVE, observedAt = observedAt)
            else d.copy(predicted = d.scheduled.plus(java.time.Duration.between(match.scheduled, match.predicted)), freshness = Freshness.LIVE, observedAt = observedAt)
        }
    }

    /** "201", " 201 ", "Bus 201" and "201 " compare equal; case and spacing are ignored. */
    internal fun lineKey(line: String) = line.uppercase().replace(Regex("^(BUS|TRAM|ZUG|TRENO)\\s+"), "").replace(Regex("\\s+"), "")

    private fun similar(a: String, b: String): Boolean {
        val x = TextNormalizer.key(a); val y = TextNormalizer.key(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x) || x.split(' ').intersect(y.split(' ').toSet()).any { it.length > 3 })
    }
}
