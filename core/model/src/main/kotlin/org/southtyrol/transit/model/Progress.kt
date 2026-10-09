package org.southtyrol.transit.model

import java.time.Instant

/** A route split where the vehicle is: the part already travelled and the part still ahead. */
data class RouteSplit(val done: List<Point>, val ahead: List<Point>)

/**
 * Progress along a run, shared by every view that draws one (trip map, journey legs), so "already
 * travelled" looks the same everywhere: the travelled part and passed stops are dimmed.
 */
object RouteProgress {
    /**
     * Index of the next call: the first stop whose (predicted) departure is still ahead of [now];
     * [stops].size when the run is over. Calls before it count as passed.
     */
    fun nextIndex(stops: List<TripStop>, now: Instant): Int =
        stops.indexOfFirst { (it.predictedDeparture ?: it.scheduledDeparture).isAfter(now) }.let { if (it < 0) stops.size else it }

    /**
     * Splits [line] at [at], projected onto the nearest segment. [after] and [before] (the stops either
     * side of the vehicle) narrow the search to that stretch, so a route passing the same street twice
     * splits at the right pass.
     */
    fun split(line: List<Point>, at: Point, after: Point? = null, before: Point? = null): RouteSplit {
        if (line.size < 2) return RouteSplit(emptyList(), line)
        fun nearest(p: Point, from: Int = 0) = (from until line.size).minByOrNull { Geo.distance(line[it], p) } ?: from
        val low = after?.let { nearest(it) } ?: 0
        val high = before?.let { nearest(it, low) }?.takeIf { it > low } ?: line.lastIndex
        var best = low
        var bestPoint = line[low]
        var bestDistance = Double.MAX_VALUE
        for (i in low until high) {
            val p = project(line[i], line[i + 1], at)
            val d = Geo.distance(p, at)
            if (d < bestDistance) { bestDistance = d; best = i; bestPoint = p }
        }
        return RouteSplit(line.subList(0, best + 1) + bestPoint, listOf(bestPoint) + line.subList(best + 1, line.size))
    }

    /**
     * The route split for a run at [now]: at the vehicle when its [position] is known, otherwise all
     * ahead before the run starts and all done after it ends. Null while the run is between those
     * without a position (nothing honest to draw).
     */
    fun of(line: List<Point>, stops: List<TripStop>, position: RunPosition?, now: Instant): RouteSplit? {
        if (line.size < 2) return null
        if (position != null) {
            val after = stops.getOrNull(position.nextIndex - 1)?.stop?.point
            val before = stops.getOrNull(position.nextIndex)?.stop?.point
            return split(line, position.point, after, before)
        }
        val next = nextIndex(stops, now)
        return when (next) {
            0 -> RouteSplit(emptyList(), line)
            stops.size -> RouteSplit(line, emptyList())
            else -> null
        }
    }

    /**
     * A planner leg's calls as a run (from, intermediate stops, to), so legs get the same progress logic
     * as timetable trips - no timetable needed. Calls without coordinates or times are skipped.
     */
    fun legStops(leg: Leg): List<TripStop> = buildList {
        fun call(place: Place, sequence: Int, arrival: Instant?, departure: Instant?, predictedArrival: Instant?, predictedDeparture: Instant?) {
            val point = place.point ?: return
            val arr = arrival ?: departure ?: return
            add(TripStop(Stop(place.id, place.name, point, stationKey = place.stopGlobalId), sequence, arr, departure ?: arr, predictedArrival, predictedDeparture))
        }
        call(leg.from, 0, leg.departure, leg.departure, leg.predictedDeparture, leg.predictedDeparture)
        leg.intermediate.forEachIndexed { i, s -> call(s.place, i + 1, s.arrival, s.departure, s.predictedArrival, s.predictedDeparture) }
        call(leg.to, leg.intermediate.size + 1, leg.arrival, leg.arrival, leg.predictedArrival, leg.predictedArrival)
    }

    /** Share of a leg already behind at [now], by time: 0 before it starts, 1 once it has arrived. */
    fun legFraction(leg: Leg, now: Instant): Float {
        val start = leg.bestDeparture; val end = leg.bestArrival
        if (!now.isAfter(start)) return 0f
        if (!now.isBefore(end)) return 1f
        val total = java.time.Duration.between(start, end).seconds.coerceAtLeast(1)
        return (java.time.Duration.between(start, now).seconds.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Projection of [p] onto the segment [a]-[b] (flat approximation, fine at city scale). */
    internal fun project(a: Point, b: Point, p: Point): Point {
        val scale = kotlin.math.cos(Math.toRadians(a.latitude))
        val ax = a.longitude * scale; val bx = b.longitude * scale; val px = p.longitude * scale
        val dx = bx - ax; val dy = b.latitude - a.latitude
        val length = dx * dx + dy * dy
        if (length == 0.0) return a
        val t = (((px - ax) * dx + (p.latitude - a.latitude) * dy) / length).coerceIn(0.0, 1.0)
        return Point(a.latitude + dy * t, a.longitude + (b.longitude - a.longitude) * t)
    }
}
