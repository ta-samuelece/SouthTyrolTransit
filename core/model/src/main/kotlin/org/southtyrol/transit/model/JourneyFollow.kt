package org.southtyrol.transit.model

import java.time.Duration
import java.time.Instant

/**
 * Live times of matched timetable runs applied to a planned journey: each run (by leg index, see
 * [LegMatch]) updates its leg's boarding, alighting and intermediate times and its cancellation. A run
 * without a prediction for a call leaves the planner's time (which may carry the planner's own delay).
 * The planner's own live times can be refreshed first with [applyPlanner].
 * A walk after a late leg starts when that leg arrives and keeps its planned length.
 */
object LiveJourney {
    private const val TOLERANCE_SECONDS = 120L

    fun apply(journey: Journey, runs: Map<Int, TripDetail>): Journey {
        val legs = journey.legs.mapIndexed { i, leg -> runs[i]?.let { apply(leg, it) } ?: leg }.toMutableList()
        for (i in 1 until legs.size) {
            val walk = legs[i]
            if (walk.mode.isTransit) continue
            val late = Duration.between(walk.bestDeparture, legs[i - 1].bestArrival).seconds
            if (late > 0) legs[i] = walk.copy(predictedDeparture = walk.bestDeparture.plusSeconds(late), predictedArrival = walk.bestArrival.plusSeconds(late))
        }
        return if (legs == journey.legs) journey else journey.copy(legs = legs)
    }

    internal fun apply(leg: Leg, run: TripDetail): Leg {
        val stops = run.stops
        val boarding = Approaches.boardingIndex(stops, leg.from.stopGlobalId, leg.departure) ?: return leg
        val alighting = (boarding + 1 until stops.size).firstOrNull { at(stops[it], leg.to.stopGlobalId) } ?: return leg
        val board = stops[boarding]
        val alight = stops[alighting]
        val between = stops.subList(boarding + 1, alighting)
        val intermediate = leg.intermediate.map { s ->
            val call = between.firstOrNull { at(it, s.place.stopGlobalId) } ?: return@map s
            s.copy(
                predictedArrival = call.predictedArrival ?: s.predictedArrival,
                predictedDeparture = call.predictedDeparture ?: s.predictedDeparture,
            )
        }
        val lost = setOf(ServiceState.CANCELLED, ServiceState.SKIPPED)
        return leg.copy(
            predictedDeparture = board.predictedDeparture ?: leg.predictedDeparture,
            predictedArrival = alight.predictedArrival ?: leg.predictedArrival,
            intermediate = intermediate,
            realtime = leg.realtime || board.predictedDeparture != null || alight.predictedArrival != null,
            cancelled = leg.cancelled || run.state == ServiceState.CANCELLED || board.state in lost || alight.state in lost,
        )
    }

    /**
     * The planner's live stop times of the leg's run applied to it: boarding matched by station and
     * planned departure (within two minutes), alighting = the first later call at the leg's destination
     * station. Replaces the delays the leg was planned with. Null when neither end has a delay - nothing
     * fresh to show.
     */
    fun applyPlanner(leg: Leg, live: List<LiveStopTime>): Leg? {
        fun near(planned: Instant?, scheduled: Instant?) =
            planned != null && scheduled != null && kotlin.math.abs(Duration.between(planned, scheduled).seconds) <= TOLERANCE_SECONDS
        val boarding = live.indexOfFirst { it.stationKey == leg.from.stopGlobalId && near(leg.departure, it.scheduledDeparture) }
        if (boarding < 0) return null
        val alighting = (boarding + 1 until live.size).firstOrNull { live[it].stationKey == leg.to.stopGlobalId } ?: return null
        val board = live[boarding]
        val alight = live[alighting]
        val depDelay = board.departureDelayMinutes ?: board.arrivalDelayMinutes
        val arrDelay = alight.arrivalDelayMinutes ?: alight.departureDelayMinutes
        if (depDelay == null && arrDelay == null) return null
        val between = live.subList(boarding + 1, alighting)
        val intermediate = leg.intermediate.map { s ->
            val call = between.firstOrNull { it.stationKey == s.place.stopGlobalId && (near(s.arrival, it.scheduledArrival) || near(s.departure, it.scheduledDeparture)) }
                ?: return@map s
            val arr = call.arrivalDelayMinutes ?: call.departureDelayMinutes
            val dep = call.departureDelayMinutes ?: call.arrivalDelayMinutes
            s.copy(
                predictedArrival = s.arrival?.let { a -> arr?.let { a.plusSeconds(it * 60L) } } ?: s.predictedArrival,
                predictedDeparture = s.departure?.let { d -> dep?.let { d.plusSeconds(it * 60L) } } ?: s.predictedDeparture,
            )
        }
        return leg.copy(
            predictedDeparture = depDelay?.let { leg.departure.plusSeconds(it * 60L) } ?: leg.predictedDeparture,
            predictedArrival = arrDelay?.let { leg.arrival.plusSeconds(it * 60L) } ?: leg.predictedArrival,
            intermediate = intermediate,
            realtime = true,
        )
    }

    private fun at(call: TripStop, stationKey: String) =
        stationKey.isNotBlank() && (call.stop.stationKey == stationKey || call.stop.id == stationKey)
}

/** What the traveller does now on a followed journey. */
enum class StepKind {
    /** Walking: towards [FollowStep.target], usually to the next boarding stop. */
    WALK,
    /** Waiting for the transit leg [FollowStep.legIndex] to leave from [FollowStep.target]. */
    BOARD,
    /** On board, getting off at [FollowStep.target]; a change follows when [FollowStep.change]. */
    RIDE,
    ARRIVED,
}

/**
 * The current step of a followed journey. [at] is the moment it is about: leaving (BOARD, and WALK
 * towards a boarding), getting off (RIDE), arriving (ARRIVED and a final WALK).
 */
data class FollowStep(
    val kind: StepKind,
    val legIndex: Int,
    val target: Place,
    val at: Instant,
    /** The delay behind [at] when a live or planner prediction backs it. */
    val delaySeconds: Long?,
    /** The next transit leg after this step, if any. */
    val nextTransitIndex: Int?,
    /** RIDE only: getting off is a change to [nextTransitIndex], not the destination. */
    val change: Boolean = false,
    /** RIDE only: calls of [RouteProgress.legStops] already passed. */
    val stopsPassed: Int = 0,
)

/** Why a followed journey can no longer go as planned. */
sealed interface OffPlan {
    val legIndex: Int

    /** The leg is cancelled, or its boarding or alighting call is. */
    data class LegCancelled(override val legIndex: Int) : OffPlan

    /** The leg before arrives too late to make this transit leg. */
    data class ConnectionBroken(override val legIndex: Int) : OffPlan

    /** With location: the traveller is still at the boarding stop well after the leg left. */
    data class MissedBoarding(override val legIndex: Int) : OffPlan

    /** With location: the traveller is far from the leg's route while riding it. */
    data class OffRoute(override val legIndex: Int) : OffPlan
}

/**
 * Follows a planned journey by time: live times where the journey carries them ([LiveJourney]), the
 * timetable otherwise. Location, when available, only detects that the traveller left the plan.
 */
object JourneyFollow {
    /** Within this of the boarding stop counts as still standing there. */
    const val BOARD_RADIUS_METERS = 150.0
    /** A boarding counts as missed this long after the vehicle's departure. */
    const val MISSED_AFTER_SECONDS = 120L
    /** Riding farther than this from the leg's route counts as off the route. */
    const val OFF_ROUTE_METERS = 800.0
    /** Fixes less accurate than this are ignored. */
    const val MAX_FIX_ACCURACY_METERS = 100f
    /** Following ends this long after the journey's arrival. */
    const val ARRIVED_GRACE_SECONDS = 120L

    fun step(journey: Journey, now: Instant): FollowStep {
        val legs = journey.legs
        fun nextTransit(after: Int) = (after + 1 until legs.size).firstOrNull { legs[it].mode.isTransit }
        val i = legs.indexOfFirst { now.isBefore(it.bestArrival) }
        if (i < 0) {
            val last = legs.last()
            return FollowStep(StepKind.ARRIVED, legs.lastIndex, last.to, last.bestArrival, last.arrivalDelaySeconds, null)
        }
        val leg = legs[i]
        val next = nextTransit(i)
        if (!leg.mode.isTransit) {
            // Walking to a boarding: the departure is what counts; the last walk: the arrival.
            val boarding = legs.getOrNull(i + 1)?.takeIf { it.mode.isTransit }
            return FollowStep(
                StepKind.WALK, i, leg.to,
                boarding?.bestDeparture ?: leg.bestArrival,
                boarding?.departureDelaySeconds ?: leg.arrivalDelaySeconds,
                next,
            )
        }
        if (now.isBefore(leg.bestDeparture)) {
            return FollowStep(StepKind.BOARD, i, leg.from, leg.bestDeparture, leg.departureDelaySeconds, i)
        }
        return FollowStep(
            StepKind.RIDE, i, leg.to, leg.bestArrival, leg.arrivalDelaySeconds, next,
            change = next != null,
            stopsPassed = RouteProgress.nextIndex(RouteProgress.legStops(leg), now),
        )
    }

    /**
     * The first reason the journey cannot go on as planned at [now], or null. [fix] (with its accuracy)
     * is the traveller's position when location is in use.
     */
    fun offPlan(journey: Journey, now: Instant, fix: Point? = null, accuracyMeters: Float? = null): OffPlan? {
        val legs = journey.legs
        legs.indices.firstOrNull { legs[it].cancelled && now.isBefore(legs[it].bestArrival) }?.let { return OffPlan.LegCancelled(it) }
        broken(legs, now)?.let { return OffPlan.ConnectionBroken(it) }
        val usable = fix?.takeIf { accuracyMeters == null || accuracyMeters <= MAX_FIX_ACCURACY_METERS } ?: return null
        for ((i, leg) in legs.withIndex()) {
            if (!leg.mode.isTransit || !now.isBefore(leg.bestArrival)) continue
            val boarding = leg.from.point
            if (now.isAfter(leg.bestDeparture.plusSeconds(MISSED_AFTER_SECONDS)) && boarding != null && Geo.distance(boarding, usable) < BOARD_RADIUS_METERS) {
                return OffPlan.MissedBoarding(i)
            }
            if (now.isAfter(leg.bestDeparture) && distanceToRoute(leg, usable) > OFF_ROUTE_METERS) return OffPlan.OffRoute(i)
        }
        return null
    }

    /** A transit leg not yet left whose previous transit leg (plus the walks between) arrives after it leaves. */
    private fun broken(legs: List<Leg>, now: Instant): Int? {
        var previous: Int? = null
        for ((j, leg) in legs.withIndex()) {
            if (!leg.mode.isTransit) continue
            val p = previous
            previous = j
            if (p == null || !now.isBefore(leg.bestDeparture)) continue
            val walk = (p + 1 until j).fold(Duration.ZERO) { acc, k -> acc + Duration.between(legs[k].departure, legs[k].arrival) }
            if (legs[p].bestArrival.plus(walk).isAfter(leg.bestDeparture)) return j
        }
        return null
    }

    /** Distance from [point] to the leg's route: its geometry, else the line through its calls. */
    fun distanceToRoute(leg: Leg, point: Point): Double {
        val line = leg.geometry.ifEmpty { listOfNotNull(leg.from.point) + leg.intermediate.mapNotNull { it.place.point } + listOfNotNull(leg.to.point) }
        if (line.isEmpty()) return 0.0
        if (line.size == 1) return Geo.distance(line[0], point)
        return line.zipWithNext().minOf { (a, b) -> Geo.distance(RouteProgress.project(a, b, point), point) }
    }

    /** Following ends a short while after the arrival, so the "arrived" step is seen. */
    fun finished(journey: Journey, now: Instant): Boolean = !now.isBefore(journey.bestArrival.plusSeconds(ARRIVED_GRACE_SECONDS))

    /** Length of the whole journey in seconds, at least 1. */
    fun totalSeconds(journey: Journey): Long = Duration.between(journey.bestDeparture, journey.bestArrival).seconds.coerceAtLeast(1)

    /** Seconds of the journey behind at [now], within 0..[totalSeconds]. */
    fun elapsedSeconds(journey: Journey, now: Instant): Long =
        Duration.between(journey.bestDeparture, now).seconds.coerceIn(0, totalSeconds(journey))
}
