package org.southtyrol.transit.model

import java.time.Duration
import java.time.Instant

/**
 * Finds the timetable departure a journey-planner leg rides on. Planner legs carry no trip id, so the
 * leg is matched like [LiveOverlay] matches the departure monitor: same line at the boarding station and
 * scheduled time within a minute, with the same train fallback (the planner labels trains "R", the
 * timetable "REG").
 */
object LegMatch {
    private const val TOLERANCE_SECONDS = 60L

    /** [candidates]: scheduled departures at the leg's boarding station around its planned departure. */
    fun departure(leg: Leg, candidates: List<Departure>): Departure? {
        if (!leg.mode.isTransit) return null
        fun gap(d: Departure) = kotlin.math.abs(Duration.between(d.scheduled, leg.departure).seconds)
        val inTime = candidates.filter { gap(it) <= TOLERANCE_SECONDS }
        val key = LiveOverlay.lineKey(leg.line)
        return inTime
            .filter { LiveOverlay.lineKey(it.line) == key }
            .minByOrNull { gap(it) + if (LiveOverlay.similar(it.destination, leg.destination)) 0 else 3600 }
            ?: inTime.filter { it.mode == leg.mode && LiveOverlay.similar(it.destination, leg.destination) }.minByOrNull(::gap)
    }
}

/** A vehicle on its way to the stop where the user boards. */
data class Approach(
    /** Where the vehicle is now: a live GPS fix, or estimated from the stop times. */
    val position: RunPosition,
    /** The route still to travel, from [position] to the boarding stop. */
    val path: List<Point>,
    /** Calls left before the boarding stop (0: the boarding stop is next). */
    val stopsBefore: Int,
    /** The stop the vehicle reaches next (the boarding stop itself when [atBoarding]). */
    val nextStop: Stop,
    val boardingIndex: Int,
    /** The vehicle is standing at the boarding stop now. */
    val atBoarding: Boolean = false,
)

/**
 * A leg's run up to the boarding stop: the calls the vehicle makes before the user gets on and, when it
 * can be placed, where it is now ([approach]).
 */
data class RunBeforeBoarding(
    /** The whole run, the planner's boarding delay applied to calls without a prediction of their own. */
    val stops: List<TripStop>,
    val boardingIndex: Int,
    /** Null when the vehicle is not under way yet, or is not placed at all (trains). */
    val approach: Approach?,
    /** The run has left its first stop. */
    val started: Boolean,
) {
    /** The call the vehicle stands at or has just left; null when it is not placed. */
    val reached: Int? get() = approach?.position?.nextIndex?.minus(1)?.takeIf { it >= 0 }

    /** Calls before the boarding stop. */
    val stopsBefore: Int get() = boardingIndex

    /**
     * The first call shown when the earlier stops open: one before the vehicle, or the last few before the
     * boarding stop when it is not placed. A single call left above is shown rather than offered.
     */
    val firstShown: Int
        get() {
            val position = approach?.position
            val first = when {
                position == null -> boardingIndex - UNPLACED_SHOWN
                position.atStop -> position.nextIndex - 2
                else -> position.nextIndex - 1
            }.coerceIn(0, boardingIndex - 1)
            return if (first == 1) 0 else first
        }

    /** The vehicle has passed call [index]: by its position when placed, otherwise by the times. */
    fun passed(index: Int, now: Instant): Boolean {
        val position = approach?.position ?: return !(stops[index].predictedDeparture ?: stops[index].scheduledDeparture).isAfter(now)
        return index < if (position.atStop) position.nextIndex - 1 else position.nextIndex
    }

    private companion object {
        const val UNPLACED_SHOWN = 4
    }
}

object Approaches {
    /**
     * Index of the user's boarding call in [stops]: a platform of [stationKey] (or the stop itself)
     * whose scheduled departure is closest to [departure], so loops calling twice pick the right one.
     */
    fun boardingIndex(stops: List<TripStop>, stationKey: String, departure: Instant): Int? =
        stops.indices
            .filter { stops[it].stop.stationKey == stationKey || stops[it].stop.id == stationKey }
            .minByOrNull { kotlin.math.abs(Duration.between(stops[it].scheduledDeparture, departure).seconds) }

    /**
     * Applies the planner's delay at the boarding stop ([delaySeconds]) to the calls up to it that have no
     * prediction of their own, so the estimated position agrees with the boarding time the user is shown.
     */
    fun withBoardingDelay(stops: List<TripStop>, boardingIndex: Int, delaySeconds: Long?): List<TripStop> {
        if (delaySeconds == null || delaySeconds == 0L) return stops
        return stops.mapIndexed { i, s ->
            if (i > boardingIndex || s.predictedArrival != null || s.predictedDeparture != null || s.state != ServiceState.NORMAL) s
            else s.copy(predictedArrival = s.scheduledArrival.plusSeconds(delaySeconds), predictedDeparture = s.scheduledDeparture.plusSeconds(delaySeconds))
        }
    }

    /**
     * The approach to [boardingIndex] at [now], or null when the vehicle cannot be placed (not yet on its
     * way and no GPS fix) or has already left the boarding stop.
     */
    fun of(stops: List<TripStop>, shape: List<Point>, boardingIndex: Int, gps: Point?, now: Instant): Approach? {
        if (boardingIndex !in stops.indices) return null
        val position = gps?.let { RunPositions.locate(stops, shape, it, now) } ?: RunPositions.estimate(stops, shape, now) ?: return null
        val target = stops[boardingIndex].stop.point
        // Dwelling at the boarding stop: the estimate already points at the following call.
        if (position.atStop && position.nextIndex - 1 == boardingIndex) {
            return Approach(position, listOf(target), stopsBefore = 0, nextStop = stops[boardingIndex].stop, boardingIndex = boardingIndex, atBoarding = true)
        }
        // Past the boarding stop: the user is on board (or missed it) - no longer an approach.
        if (position.nextIndex > boardingIndex) return null
        return Approach(
            position = position,
            path = path(shape, position.point, stops.subList(position.nextIndex, boardingIndex + 1).map { it.stop.point }, target),
            stopsBefore = boardingIndex - position.nextIndex,
            nextStop = stops[position.nextIndex].stop,
            boardingIndex = boardingIndex,
        )
    }

    /**
     * The run a planner [leg] rides, before its boarding stop, at [now]: what the leg card shows as earlier
     * stops. [placeVehicle] false (trains, which report no position) gives the times only. Null when the
     * leg boards at the run's first stop, the boarding call is not found, or the vehicle has left it.
     */
    fun beforeBoarding(leg: Leg, stops: List<TripStop>, shape: List<Point>, gps: Point?, now: Instant, placeVehicle: Boolean): RunBeforeBoarding? {
        val boarding = boardingIndex(stops, leg.from.stopGlobalId, leg.departure)?.takeIf { it > 0 } ?: return null
        val run = withBoardingDelay(stops, boarding, leg.departureDelaySeconds)
        fun leaves(s: TripStop) = s.predictedDeparture ?: s.scheduledDeparture
        val started = !now.isBefore(leaves(run.first()))
        if (!now.isBefore(leaves(run[boarding])) && !(placeVehicle && started)) return null
        val approach = if (placeVehicle) of(run, shape, boarding, gps, now) else null
        // Under way but not before the boarding stop any more: the leg is the user's ride now.
        if (placeVehicle && started && approach == null) return null
        return RunBeforeBoarding(run, boarding, approach, started)
    }

    /**
     * From [from] to [target] along [shape] when it covers that stretch in order; otherwise straight
     * through the remaining stops ([via]).
     */
    internal fun path(shape: List<Point>, from: Point, via: List<Point>, target: Point): List<Point> {
        if (shape.size >= 2) {
            val a = shape.indices.minByOrNull { Geo.distance(shape[it], from) }!!
            val b = (a until shape.size).minByOrNull { Geo.distance(shape[it], target) }!!
            if (b > a) return listOf(from) + shape.subList(a + 1, b + 1) + target
        }
        return (listOf(from) + via).distinct().ifEmpty { listOf(from, target) }.let { if (it.size < 2) it + target else it }
    }
}
