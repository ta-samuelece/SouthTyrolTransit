package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class RideFollowTest {
    private val t0 = Instant.parse("2026-10-29T14:00:00Z")
    private fun at(minute: Long) = t0.plusSeconds(minute * 60)
    private fun stop(key: String, lat: Double, minute: Long, delay: Long? = null) = TripStop(
        Stop("$key:p", key, Point(lat, 11.0), stationKey = key), minute.toInt(), at(minute), at(minute),
        predictedArrival = delay?.let { at(minute + it) }, predictedDeparture = delay?.let { at(minute + it) },
    )
    private val line = (0..20).map { Point(46.0 + it * 0.01, 11.0) }

    // a (0) -> b (10) -> c (20), north along the line.
    private fun run(delay: Long? = null, gps: Point? = null, gpsAt: Instant = t0, state: ServiceState = ServiceState.NORMAL) = TripDetail(
        Trip("t", "r", "s", "C", 0, "sh"), Route("r", "201", "", TransportMode.BUS), LocalDate.of(2026, 10, 29),
        listOf(stop("it:a", 46.0, 0, delay), stop("it:b", 46.1, 10, delay), stop("it:c", 46.2, 20, delay)),
        line, Freshness.LIVE, state = state,
        vehicle = gps?.let { Vehicle("v", it, "t", "r", null, gpsAt) },
    )

    private fun step(detail: TripDetail, now: Instant) = RideFollow.step(detail, RideFollow.position(detail, now), now)

    @Test fun beforeItLeavesItWaitsAtTheFirstStop() {
        val s = step(run(delay = 2), at(-5))
        assertEquals(RideStage.WAITING, s.stage)
        assertEquals(0, s.stopIndex)
        assertEquals(at(2), s.at)
        assertEquals(120L, s.delaySeconds)
    }

    @Test fun betweenStopsItHeadsForTheNextCall() {
        val s = step(run(gps = Point(46.05, 11.0), gpsAt = at(5)), at(5))
        assertEquals(RideStage.MOVING, s.stage)
        assertEquals(1, s.stopIndex)
        assertEquals(at(10), s.at)
        assertNull(s.delaySeconds)
    }

    @Test fun aFixAtACallStandsThereUntilItLeaves() {
        val s = step(run(delay = 1, gps = Point(46.1002, 11.0), gpsAt = at(11)), at(11))
        assertEquals(RideStage.AT_STOP, s.stage)
        assertEquals(1, s.stopIndex)
        assertEquals(at(11), s.at)
        assertEquals(60L, s.delaySeconds)
    }

    @Test fun withoutAFixTheTimesPlaceIt() {
        val s = step(run(), at(15))
        assertEquals(RideStage.MOVING, s.stage)
        assertEquals(2, s.stopIndex)
    }

    @Test fun pastTheLastArrivalItHasArrived() {
        val s = step(run(), at(21))
        assertEquals(RideStage.ARRIVED, s.stage)
        assertEquals(2, s.stopIndex)
        assertEquals(at(20), s.at)
    }

    @Test fun aFixAtTheTerminusArrivesBeforeTheTimetableSays() {
        assertEquals(RideStage.ARRIVED, step(run(gps = Point(46.2, 11.0), gpsAt = at(18)), at(18)).stage)
    }

    @Test fun aLateRunWithAFixOnTheWayIsStillFollowedAfterItsArrivalTime() {
        val s = step(run(gps = Point(46.15, 11.0), gpsAt = at(25)), at(25))
        assertEquals(RideStage.MOVING, s.stage)
        assertEquals(2, s.stopIndex)
        assertFalse(RideFollow.expired(run(), at(25)))
        // ...but not forever.
        assertEquals(RideStage.ARRIVED, step(run(gps = Point(46.15, 11.0), gpsAt = at(51)), at(51)).stage)
        assertTrue(RideFollow.expired(run(), at(51)))
    }

    @Test fun aCancelledRunSaysSo() {
        val s = step(run(state = ServiceState.CANCELLED), at(5))
        assertEquals(RideStage.CANCELLED, s.stage)
        assertEquals(at(0), s.at)
    }

    @Test fun progressRunsFromTheFirstDepartureToTheLastArrival() {
        val detail = run(delay = 2)
        assertEquals(20 * 60L, RideFollow.totalSeconds(detail))
        assertEquals(0L, RideFollow.elapsedSeconds(detail, at(1)))
        assertEquals(10 * 60L, RideFollow.elapsedSeconds(detail, at(12)))
        assertEquals(20 * 60L, RideFollow.elapsedSeconds(detail, at(40)))
    }
}
