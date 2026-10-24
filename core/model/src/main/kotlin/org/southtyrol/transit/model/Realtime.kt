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

    /**
     * A charging point's status counts as current up to this age. Operators report a status only when it
     * changes, so a quiet charger's last report is still its state; older ones are treated as dead feeds.
     */
    const val CHARGING_MAX_AGE = 7 * 24 * 3600L

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

    fun charging(updatedAt: Instant?, now: Instant): Freshness = when {
        updatedAt == null -> Freshness.UNAVAILABLE
        Duration.between(updatedAt, now).seconds <= CHARGING_MAX_AGE -> Freshness.LIVE
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
            if (match.state == ServiceState.CANCELLED) d.copy(state = ServiceState.CANCELLED, freshness = Freshness.LIVE, observedAt = observedAt, liveRef = match.liveRef)
            else d.copy(predicted = d.scheduled.plus(java.time.Duration.between(match.scheduled, match.predicted)), freshness = Freshness.LIVE, observedAt = observedAt, liveRef = match.liveRef)
        }
    }

    /** "201", " 201 ", "Bus 201" and "201 " compare equal; case and spacing are ignored. */
    internal fun lineKey(line: String) = line.uppercase().replace(Regex("^(BUS|TRAM|ZUG|TRENO)\\s+"), "").replace(Regex("\\s+"), "")

    internal fun similar(a: String, b: String): Boolean {
        val x = TextNormalizer.key(a); val y = TextNormalizer.key(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x) || x.split(' ').intersect(y.split(' ').toSet()).any { it.length > 3 })
    }
}

/**
 * Applies the journey planner's live stop times ([LiveStopTime]) to a timetable run where GTFS-RT has
 * no prediction. Calls are matched by station and scheduled time (within two minutes), so loops that
 * visit a station twice still match the right call.
 */
object LiveTripMerge {
    private const val TOLERANCE_SECONDS = 120L

    /** The merged stops, or null when no call could be matched (nothing live to show). */
    fun apply(stops: List<TripStop>, live: List<LiveStopTime>): List<TripStop>? {
        if (live.isEmpty()) return null
        val pool = live.toMutableList()
        var matched = false
        val merged = stops.map { s ->
            if (s.predictedArrival != null || s.predictedDeparture != null || s.state != ServiceState.NORMAL) return@map s
            fun gap(l: LiveStopTime): Long {
                val a = l.scheduledDeparture ?: l.scheduledArrival ?: return Long.MAX_VALUE
                return kotlin.math.abs(java.time.Duration.between(a, s.scheduledDeparture).seconds)
                    .coerceAtMost(l.scheduledArrival?.let { kotlin.math.abs(java.time.Duration.between(it, s.scheduledArrival).seconds) } ?: Long.MAX_VALUE)
            }
            val key = s.stop.stationKey.ifBlank { s.stop.id }
            val match = pool.filter { it.stationKey == key && gap(it) <= TOLERANCE_SECONDS }.minByOrNull(::gap) ?: return@map s
            pool.remove(match)
            val dep = match.departureDelayMinutes ?: match.arrivalDelayMinutes
            val arr = match.arrivalDelayMinutes ?: match.departureDelayMinutes
            if (dep == null && arr == null) return@map s
            matched = true
            s.copy(
                predictedArrival = arr?.let { s.scheduledArrival.plusSeconds(it * 60L) },
                predictedDeparture = dep?.let { s.scheduledDeparture.plusSeconds(it * 60L) },
            )
        }
        return if (matched) merged else null
    }
}

/**
 * Which run a live vehicle is really on. STA's vehicle feed labels many buses - most city lines, some
 * regional ones - with the run of their line scheduled two hours earlier (the UTC offset, so one hour in
 * winter), while their position and stop are current; their trip updates name the same wrong run. On
 * 2026-10-21, 27 of 52 vehicles reported a run 90-140 minutes behind the clock at their stop, and each
 * had exactly one run of its line and direction due there within 20 minutes.
 */
object VehicleRuns {
    /** A reported run due at the vehicle's stop further than this from the clock is not the one it is on. */
    val MAX_OFFSET: Duration = Duration.ofMinutes(60)

    /** How far from the clock the actual run may be due at that stop. */
    val WINDOW: Duration = Duration.ofMinutes(20)

    data class Run(val tripId: String, val serviceDate: LocalDate)

    /** The run's call at [stopId] closest to [now] (a loop calls twice), or null when it does not call there. */
    fun callAt(stops: List<TripStop>, stopId: String, now: Instant): TripStop? =
        stops.filter { it.stop.id == stopId }.minByOrNull { gap(it.scheduledDeparture, now) }

    fun misplaced(call: TripStop, now: Instant): Boolean = gap(call.scheduledDeparture, now) > MAX_OFFSET.seconds

    /** [calls] of the same line at the vehicle's stop that could be its run: within [WINDOW], closest first. */
    fun candidates(calls: List<Departure>, now: Instant): List<Departure> =
        calls.filter { gap(it.scheduled, now) <= WINDOW.seconds }.sortedBy { gap(it.scheduled, now) }

    private fun gap(at: Instant, now: Instant) = kotlin.math.abs(Duration.between(at, now).seconds)
}

/**
 * A heading for vehicles whose feed sends none (STA's never does): the direction the vehicle takes to
 * the stop it is heading to, read off the trip's route shape where the vehicle is now - so a bus about
 * to turn a corner points down its road, not across the block. Without a shape it points straight at
 * that stop. GTFS-RT names the stop, but STOPPED_AT names the one the vehicle stands at, and a fix right
 * next to the named stop says nothing yet - both then aim at the call after it.
 */
object VehicleHeadings {
    /** Closer than this to its target, a vehicle's direction to it is noise. */
    const val NEAR_METERS = 25.0

    /** How far ahead along the route the heading is taken, smoothing over vertices and GPS jitter. */
    const val LOOKAHEAD_METERS = 30.0

    /** Stretches this much outside the window between the two calls still count, for imprecise call offsets. */
    private const val WINDOW_SLACK_METERS = 60.0

    /**
     * The vehicle's heading. [stopPoint] is the stop GTFS-RT names; [calls] are the trip's stops in order
     * (id, point) and [path] its shape, when known. Null when there is no direction: no usable stop, or
     * the vehicle is at the end of its run.
     */
    fun bearing(vehicle: Vehicle, stopPoint: Point?, calls: List<Pair<String, Point>>?, path: List<Point> = emptyList()): Float? {
        if (vehicle.bearing != null) return vehicle.bearing
        if (!calls.isNullOrEmpty()) {
            val target = targetIndex(vehicle, calls) ?: return null
            return toward(vehicle.point, calls.map { it.second }, target, path)
        }
        val stop = stopPoint ?: return null
        if (vehicle.stopId == null || vehicle.currentStatus == "STOPPED_AT" || Geo.distance(vehicle.point, stop) < NEAR_METERS) return null
        return Geo.bearing(vehicle.point, stop)
    }

    /** Index of the call the vehicle is heading to, or null at the end of its run. */
    fun targetIndex(vehicle: Vehicle, calls: List<Pair<String, Point>>): Int? {
        if (calls.isEmpty()) return null
        val named = vehicle.stopId?.let { id -> calls.indexOfFirst { it.first == id } } ?: -1
        // Without a named stop, the nearest call stands in for it.
        val start = if (named >= 0) named else calls.indices.minBy { Geo.distance(calls[it].second, vehicle.point) }
        return (start until calls.size).firstOrNull { i ->
            Geo.distance(calls[i].second, vehicle.point) >= NEAR_METERS &&
                !(i == start && (vehicle.currentStatus == "STOPPED_AT" || named < 0))
        }
    }

    /**
     * The heading at [point] on the way to call [target] of [calls]: along [path] between the previous
     * call and the target where the path is known, else straight at the target.
     */
    fun toward(point: Point, calls: List<Point>, target: Int, path: List<Point>): Float {
        val straight = Geo.bearing(point, calls[target])
        if (path.size < 2) return straight
        val route = Route(path)
        // Each call's distance along the path, in order, so a loop's second pass maps past its first.
        var from = 0
        val offsets = calls.map { c ->
            val i = (from until path.size).minByOrNull { Geo.distance(path[it], c) } ?: from
            from = i
            route.cumulative[i]
        }
        val lo = (if (target > 0) offsets[target - 1] else 0.0) - WINDOW_SLACK_METERS
        val hi = offsets[target] + WINDOW_SLACK_METERS
        return route.headingAt(point, lo..hi) ?: straight
    }

    /** The heading at [point] along [path] in its direction of travel, for a leg with no calls to aim at. */
    fun alongPath(point: Point, path: List<Point>): Float? = if (path.size < 2) null else Route(path).headingAt(point, null)

    private class Route(val path: List<Point>) {
        val cumulative = DoubleArray(path.size).also { c -> for (i in 1 until path.size) c[i] = c[i - 1] + Geo.distance(path[i - 1], path[i]) }

        /** Projects [point] on the nearest segment within [window] (distance along the path), then looks ahead. */
        fun headingAt(point: Point, window: ClosedFloatingPointRange<Double>?): Float? {
            var best: Pair<Point, Double>? = null
            var bestGap = Double.MAX_VALUE
            for (i in 0 until path.lastIndex) {
                if (window != null && (cumulative[i + 1] < window.start || cumulative[i] > window.endInclusive)) continue
                val p = RouteProgress.project(path[i], path[i + 1], point)
                val gap = Geo.distance(p, point)
                if (gap < bestGap) { bestGap = gap; best = p to cumulative[i] + Geo.distance(path[i], p) }
            }
            val (at, along) = best ?: return if (window != null) headingAt(point, null) else null
            val ahead = pointAt(along + LOOKAHEAD_METERS)
            return if (Geo.distance(at, ahead) < 1.0) null else Geo.bearing(at, ahead)
        }

        private fun pointAt(distance: Double): Point {
            if (distance >= cumulative.last()) return path.last()
            val i = (1 until path.size).first { cumulative[it] >= distance }
            val span = cumulative[i] - cumulative[i - 1]
            val f = if (span <= 0.0) 0.0 else (distance - cumulative[i - 1]) / span
            val a = path[i - 1]; val b = path[i]
            return Point(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
        }
    }
}

/** Where a run is now: a live GPS fix, or a position estimated from the (live or scheduled) stop times. */
data class RunPosition(
    val point: Point,
    /** Index of the next stop; the vehicle is between stop index-1 and index (or at index when [atStop]). */
    val nextIndex: Int,
    val atStop: Boolean,
    val estimated: Boolean,
)

object RunPositions {
    /**
     * Estimates the vehicle's position at [now] by interpolating between the last departed and the next
     * stop, along [shape] when available. Null before the first departure or after the final arrival.
     */
    fun estimate(stops: List<TripStop>, shape: List<Point>, now: Instant): RunPosition? {
        if (stops.size < 2) return null
        fun dep(s: TripStop) = s.predictedDeparture ?: s.scheduledDeparture
        fun arr(s: TripStop) = s.predictedArrival ?: s.scheduledArrival
        if (now.isBefore(dep(stops.first())) || !now.isBefore(arr(stops.last()))) return null
        val next = stops.indexOfFirst { arr(it).isAfter(now) }.takeIf { it > 0 } ?: return null
        val prev = next - 1
        // Dwelling at the previous stop until its departure.
        if (!now.isAfter(dep(stops[prev]))) return RunPosition(stops[prev].stop.point, next, atStop = true, estimated = true)
        val total = java.time.Duration.between(dep(stops[prev]), arr(stops[next])).seconds.coerceAtLeast(1)
        val fraction = (java.time.Duration.between(dep(stops[prev]), now).seconds.toDouble() / total).coerceIn(0.0, 1.0)
        val from = stops[prev].stop.point
        val to = stops[next].stop.point
        return RunPosition(along(shape, from, to, fraction) ?: Point(from.latitude + (to.latitude - from.latitude) * fraction, from.longitude + (to.longitude - from.longitude) * fraction), next, atStop = false, estimated = true)
    }

    /** A fix this close to a call counts as standing there. */
    private const val AT_STOP_METERS = 40.0

    /** Stretches this much farther from the fix than the nearest one still count as candidates (loops, parallel streets). */
    private const val CANDIDATE_SLACK_METERS = 50.0

    /**
     * Places a GPS fix on the run by geometry: between which calls it is, or at which it stands. The stop
     * times only break ties where the route passes the same place twice, so a late run without
     * predictions (or one running past its scheduled end) is still placed where the bus really is.
     */
    fun locate(stops: List<TripStop>, shape: List<Point>, gps: Point, now: Instant): RunPosition {
        if (stops.isEmpty()) return RunPosition(gps, 0, atStop = false, estimated = false)
        val line = shape.takeIf { it.size >= 2 } ?: stops.map { it.stop.point }
        if (line.size < 2) {
            val near = Geo.distance(stops[0].stop.point, gps) <= AT_STOP_METERS
            return RunPosition(gps, if (near) 1 else 0, atStop = near, estimated = false)
        }
        val cumulative = DoubleArray(line.size)
        for (i in 1 until line.size) cumulative[i] = cumulative[i - 1] + Geo.distance(line[i - 1], line[i])
        // Each call's distance along the line, in order, so a loop's second pass maps past its first.
        var from = 0
        val stopOffsets = stops.map { s ->
            val i = (from until line.size).minByOrNull { Geo.distance(line[it], s.stop.point) } ?: from
            from = i
            cumulative[i]
        }
        val segments = (0 until line.lastIndex).map { i ->
            val p = RouteProgress.project(line[i], line[i + 1], gps)
            Triple(Geo.distance(p, gps), cumulative[i] + Geo.distance(line[i], p), i)
        }
        val best = segments.minOf { it.first }
        // Where the times expect it: midway along the stretch towards the expected next call.
        val hint = (estimate(stops, shape, now)?.nextIndex ?: RouteProgress.nextIndex(stops, now)).coerceIn(0, stops.lastIndex)
        val expected = if (hint == 0) stopOffsets[0] else (stopOffsets[hint - 1] + stopOffsets[hint]) / 2
        val offset = segments.filter { it.first <= best + CANDIDATE_SLACK_METERS }.minBy { kotlin.math.abs(it.second - expected) }.second
        val standing = stops.indices
            .filter { Geo.distance(stops[it].stop.point, gps) <= AT_STOP_METERS }
            .minByOrNull { kotlin.math.abs(stopOffsets[it] - offset) }
        if (standing != null) return RunPosition(gps, standing + 1, atStop = true, estimated = false)
        // Not standing at a call it projects onto (e.g. still before the first one): that call is next.
        val next = stopOffsets.indexOfFirst { it >= offset }
        // Beyond the last call: at the terminus.
        return if (next < 0) RunPosition(gps, stops.size, atStop = true, estimated = false) else RunPosition(gps, next, atStop = false, estimated = false)
    }

    /** The point [fraction] of the way from [from] to [to] following [shape], or null without a usable shape. */
    internal fun along(shape: List<Point>, from: Point, to: Point, fraction: Double): Point? {
        if (shape.size < 2) return null
        val a = shape.indices.minByOrNull { Geo.distance(shape[it], from) } ?: return null
        val b = shape.indices.minByOrNull { Geo.distance(shape[it], to) } ?: return null
        if (b <= a) return null
        val section = shape.subList(a, b + 1)
        val lengths = section.zipWithNext { p, q -> Geo.distance(p, q) }
        val target = lengths.sum() * fraction
        var walked = 0.0
        for ((i, len) in lengths.withIndex()) {
            if (walked + len >= target) {
                val t = if (len == 0.0) 0.0 else (target - walked) / len
                val p = section[i]; val q = section[i + 1]
                return Point(p.latitude + (q.latitude - p.latitude) * t, p.longitude + (q.longitude - p.longitude) * t)
            }
            walked += len
        }
        return section.last()
    }
}
