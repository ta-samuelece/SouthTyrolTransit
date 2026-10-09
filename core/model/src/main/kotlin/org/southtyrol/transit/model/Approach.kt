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
        val estimate = RunPositions.estimate(stops, shape, now)
        val position = when {
            gps != null -> RunPosition(gps, estimate?.nextIndex ?: nextIndexAlong(stops, shape, gps), atStop = false, estimated = false)
            else -> estimate ?: return null
        }
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

    /** Next call for a GPS fix without usable stop times: the first stop at or beyond its shape position. */
    internal fun nextIndexAlong(stops: List<TripStop>, shape: List<Point>, point: Point): Int {
        if (shape.size < 2) return stops.indices.minByOrNull { Geo.distance(stops[it].stop.point, point) } ?: 0
        val at = shape.indices.minByOrNull { Geo.distance(shape[it], point) } ?: 0
        return stops.indices.firstOrNull { i -> (shape.indices.minByOrNull { Geo.distance(shape[it], stops[i].stop.point) } ?: 0) >= at } ?: stops.lastIndex
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
