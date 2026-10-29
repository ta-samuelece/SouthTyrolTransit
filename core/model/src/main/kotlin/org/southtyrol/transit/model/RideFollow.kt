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

    /** Seconds of the run behind at [now], within 0..[totalSeconds]. */
    fun elapsedSeconds(detail: TripDetail, now: Instant): Long {
        val first = detail.stops.firstOrNull() ?: return 0
        return Duration.between(first.predictedDeparture ?: first.scheduledDeparture, now).seconds.coerceIn(0, totalSeconds(detail))
    }
}
