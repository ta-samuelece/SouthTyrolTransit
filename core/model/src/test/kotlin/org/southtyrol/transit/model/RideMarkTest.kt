package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RideMarkTest {
    private val t0 = Instant.parse("2026-10-29T14:00:00Z")
    private fun at(minute: Long) = t0.plusSeconds(minute * 60)

    /** A call arriving at [arrive] and leaving at [leave] (minutes from [t0]), [delay] minutes late when given. */
    private fun stop(key: String, sequence: Int, arrive: Long, leave: Long = arrive, delay: Long? = null) = TripStop(
        Stop("$key:p", key, Point(46.0, 11.0), stationKey = key), sequence, at(arrive), at(leave),
        predictedArrival = delay?.let { at(arrive + it) }, predictedDeparture = delay?.let { at(leave + it) },
    )

    // a (0) -> b (10, leaves 11) -> c (20).
    private fun run(delay: Long? = null) = TripDetail(
        Trip("t", "r", "s", "C", 0, "sh"), Route("r", "201", "", TransportMode.BUS), LocalDate.of(2026, 10, 29),
        listOf(stop("it:a", 1, 0, delay = delay), stop("it:b", 2, 10, 11, delay), stop("it:c", 3, 20, delay = delay)),
        emptyList(), Freshness.LIVE,
    )

    private fun step(stage: RideStage, index: Int) = RideStep(stage, index, t0, null)

    @Test fun callAtFindsTheCallAtTheStationAndTime() {
        assertEquals(1, RideFollow.callAt(run().stops, "it:b", at(11)))
    }

    @Test fun callAtFindsTheArrivalTimeOfAnArrivalsBoard() {
        assertEquals(1, RideFollow.callAt(run().stops, "it:b", at(10)))
    }

    @Test fun callAtIsNullWithoutAMatch() {
        assertNull(RideFollow.callAt(run().stops, "it:b", at(12)))
        assertNull(RideFollow.callAt(run().stops, "it:x", at(11)))
    }

    @Test fun callAtIsNullForABlankKey() {
        assertNull(RideFollow.callAt(run().stops, "", at(0)))
    }

    @Test fun callAtResolvesALoopByItsTime() {
        val loop = listOf(stop("it:a", 1, 0), stop("it:b", 2, 10), stop("it:a", 3, 20))
        assertEquals(0, RideFollow.callAt(loop, "it:a", at(0)))
        assertEquals(2, RideFollow.callAt(loop, "it:a", at(20)))
    }

    @Test fun beforeTheMarkedCallItCountsDownToItsArrival() {
        val now = at(5)
        assertEquals(at(10), RideFollow.markedTarget(run(), step(RideStage.WAITING, 0), 1, now))
        assertEquals(at(20), RideFollow.markedTarget(run(), step(RideStage.MOVING, 1), 2, now))
        assertEquals(at(10), RideFollow.markedTarget(run(), step(RideStage.MOVING, 1), 1, now))
        assertEquals(at(20), RideFollow.markedTarget(run(), step(RideStage.AT_STOP, 1), 2, now))
    }

    @Test fun waitingAtTheMarkedFirstCallCountsDownToItsDeparture() {
        assertEquals(at(2), RideFollow.markedTarget(run(delay = 2), step(RideStage.WAITING, 0), 0, at(-5)))
    }

    @Test fun aLiveDelayMovesTheTarget() {
        assertEquals(at(13), RideFollow.markedTarget(run(delay = 3), step(RideStage.MOVING, 1), 1, at(5)))
    }

    @Test fun standingAtTheMarkedCallItIsDueNow() {
        val now = at(10)
        assertEquals(now, RideFollow.markedTarget(run(), step(RideStage.AT_STOP, 1), 1, now))
    }

    @Test fun pastTheMarkedCallThereIsNoTarget() {
        val now = at(15)
        assertNull(RideFollow.markedTarget(run(), step(RideStage.MOVING, 2), 1, now))
        assertNull(RideFollow.markedTarget(run(), step(RideStage.AT_STOP, 1), 0, now))
        assertNull(RideFollow.markedTarget(run(), step(RideStage.ARRIVED, 2), 1, now))
        assertNull(RideFollow.markedTarget(run(), step(RideStage.CANCELLED, 0), 1, now))
    }

    @Test fun offsetPlacesTheMarkedCallOnTheRun() {
        assertEquals(0L, RideFollow.offsetSeconds(run(), 0))
        assertEquals(10 * 60L, RideFollow.offsetSeconds(run(), 1))
        assertEquals(20 * 60L, RideFollow.offsetSeconds(run(), 2))
    }

    @Test fun offsetIsClampedToTheRun() {
        // The first call leaves late while the marked call keeps its timetable arrival.
        val detail = run().let { d -> d.copy(stops = listOf(d.stops[0].copy(predictedDeparture = at(15))) + d.stops.drop(1)) }
        assertEquals(0L, RideFollow.offsetSeconds(detail, 1))
        assertEquals(RideFollow.totalSeconds(detail), RideFollow.offsetSeconds(detail, 2))
    }
}
