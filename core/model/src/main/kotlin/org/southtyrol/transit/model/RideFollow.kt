package org.southtyrol.transit.model

import java.time.Duration
import java.time.Instant

/** Where a followed ride (one timetable run) stands. */
enum class RideStage {
    /** Not yet left its first stop. */
    WAITING,
    /** Between two calls, heading for [RideStep.stopIndex]. */
    MOVING,
    /** Standing at the call [RideStep.stopIndex]. */
    AT_STOP,
    /** At its last stop, or past the time it arrives there. */
    ARRIVED,
    CANCELLED,
}

/**
 * The current state of a followed ride. [at] is the moment it is about: leaving (WAITING, AT_STOP),
 * reaching the next call (MOVING), arriving (ARRIVED), the run's planned start (CANCELLED).
 */
data class RideStep(
    val stage: RideStage,
    val stopIndex: Int,
    val at: Instant,
    /** The delay behind [at] when a prediction backs it. */
    val delaySeconds: Long?,
)

/**
 * Following one run until it reaches its last stop. The run comes merged with realtime
 * ([TripDetail.vehicle] and predictions), as the trip screen shows it; a GPS fix places it, otherwise its
 * times do ([RunPositions]).
 */
object RideFollow {
    /** How long an arrived ride stays shown before following ends by itself. */
    const val ARRIVED_GRACE_SECONDS = 60L

    /**
     * A ride whose GPS still has it on the way is followed this long past its last arrival time (a late
     * run without predictions); then following ends whatever the data says.
     */
    const val OVERRUN_SECONDS = 30 * 60L

    /** Where the vehicle is: its GPS fix placed on the run, else estimated from the times. Null when neither works. */
    fun position(detail: TripDetail, now: Instant): RunPosition? =
        detail.vehicle?.let { RunPositions.locate(detail.stops, detail.shape, it.point, now) }
            ?: RunPositions.estimate(detail.stops, detail.shape, now)

    fun step(detail: TripDetail, position: RunPosition?, now: Instant): RideStep {
        val stops = detail.stops
        require(stops.isNotEmpty()) { "A run without stops cannot be followed" }
        val last = stops.lastIndex
        fun dep(i: Int) = stops[i].predictedDeparture ?: stops[i].scheduledDeparture
        fun arr(i: Int) = stops[i].predictedArrival ?: stops[i].scheduledArrival
        fun delay(predicted: Instant?, scheduled: Instant) = predicted?.let { Duration.between(scheduled, it).seconds }
        val first = stops[0]
        if (detail.state == ServiceState.CANCELLED) return RideStep(RideStage.CANCELLED, 0, first.scheduledDeparture, null)

        val gps = position?.takeIf { !it.estimated }
        val gpsAtLast = gps != null && gps.atStop && gps.nextIndex - 1 == last && last > 0 && !now.isBefore(dep(0))
        // A fix still on the way keeps a late run going past its arrival time, for a while.
        val gpsOnTheWay = gps != null && !gpsAtLast && now.isBefore(arr(last).plusSeconds(OVERRUN_SECONDS))
        if (gpsAtLast || (!now.isBefore(arr(last)) && !gpsOnTheWay)) {
            // A fix at the terminus before the expected time: it arrived now, not later.
            val at = if (gpsAtLast && now.isBefore(arr(last))) now else arr(last)
            return RideStep(RideStage.ARRIVED, last, at, delay(stops[last].predictedArrival, stops[last].scheduledArrival))
        }
        val waiting = RideStep(RideStage.WAITING, 0, dep(0), delay(first.predictedDeparture, first.scheduledDeparture))
        if (position == null) {
            // Nothing places it: before its first departure it waits there, else it heads for the next call due.
            if (now.isBefore(dep(0))) return waiting
            val next = (1..last).firstOrNull { arr(it).isAfter(now) } ?: last
            return RideStep(RideStage.MOVING, next, arr(next), delay(stops[next].predictedArrival, stops[next].scheduledArrival))
        }
        if (position.atStop || position.nextIndex == 0) {
            val index = (position.nextIndex - 1).coerceIn(0, last)
            if (index == 0) return waiting
            val call = stops[index]
            return RideStep(RideStage.AT_STOP, index, dep(index), delay(call.predictedDeparture, call.scheduledDeparture))
        }
        val next = position.nextIndex.coerceIn(1, last)
        val call = stops[next]
        return RideStep(RideStage.MOVING, next, arr(next), delay(call.predictedArrival, call.scheduledArrival))
    }

    /** Past any reason to keep following, whatever the data: [OVERRUN_SECONDS] after the last arrival. */
    fun expired(detail: TripDetail, now: Instant): Boolean {
        val end = detail.stops.lastOrNull()?.let { it.predictedArrival ?: it.scheduledArrival } ?: return true
        return !now.isBefore(end.plusSeconds(OVERRUN_SECONDS))
    }

    /** Length of the run in seconds, from its first departure to its last arrival, at least 1. */
    fun totalSeconds(detail: TripDetail): Long {
        val first = detail.stops.firstOrNull() ?: return 1
        val last = detail.stops.last()
        return Duration.between(first.predictedDeparture ?: first.scheduledDeparture, last.predictedArrival ?: last.scheduledArrival).seconds.coerceAtLeast(1)
    }

    /**
     * The call of [stops] the user picked on a board: the first one at [stationKey] whose scheduled departure
     * or arrival is [scheduled] (a run serving the station twice resolves by its time). Null when [stationKey]
     * is blank or no call matches.
     */
    fun callAt(stops: List<TripStop>, stationKey: String, scheduled: Instant): Int? {
        if (stationKey.isBlank()) return null
        return stops.indexOfFirst { it.stop.stationKey == stationKey && (it.scheduledDeparture == scheduled || it.scheduledArrival == scheduled) }
            .takeIf { it >= 0 }
    }

    /**
     * When the run reaches the marked call [index] (its departure when it is the first call), [now] while it
     * stands there, or null once that call is behind it, arrived or cancelled.
     */
    fun markedTarget(detail: TripDetail, step: RideStep, index: Int, now: Instant): Instant? {
        val call = detail.stops.getOrNull(index) ?: return null
        val arrival = call.predictedArrival ?: call.scheduledArrival
        return when (step.stage) {
            RideStage.CANCELLED, RideStage.ARRIVED -> null
            RideStage.WAITING -> if (index == 0) call.predictedDeparture ?: call.scheduledDeparture else arrival
            RideStage.MOVING -> arrival.takeIf { step.stopIndex <= index }
            RideStage.AT_STOP -> when {
                step.stopIndex < index -> arrival
                step.stopIndex == index -> now
                else -> null
            }
        }
    }

    /** Where the marked call [index] lies on the [totalSeconds] scale: its arrival (departure for the first call), clamped. */
    fun offsetSeconds(detail: TripDetail, index: Int): Long {
        val first = detail.stops.firstOrNull() ?: return 0
        val call = detail.stops.getOrNull(index) ?: return 0
        val at = if (index == 0) call.predictedDeparture ?: call.scheduledDeparture else call.predictedArrival ?: call.scheduledArrival
        return Duration.between(first.predictedDeparture ?: first.scheduledDeparture, at).seconds.coerceIn(0, totalSeconds(detail))
    }

    /** Seconds of the run behind at [now], within 0..[totalSeconds]. */
    fun elapsedSeconds(detail: TripDetail, now: Instant): Long {
        val first = detail.stops.firstOrNull() ?: return 0
        return Duration.between(first.predictedDeparture ?: first.scheduledDeparture, now).seconds.coerceIn(0, totalSeconds(detail))
    }
}
