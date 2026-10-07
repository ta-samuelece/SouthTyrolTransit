package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripLiveTest {
    private val t0 = Instant.parse("2026-10-07T15:00:00Z")
    private fun stop(key: String, lat: Double, minute: Long, dwell: Long = 0) = TripStop(
        Stop("$key:p", key, Point(lat, 11.0), stationKey = key), (minute / 5).toInt(),
        t0.plusSeconds(minute * 60), t0.plusSeconds((minute + dwell) * 60),
    )
    private val run = listOf(stop("it:a", 46.0, 0), stop("it:b", 46.1, 10, dwell = 2), stop("it:c", 46.2, 20), stop("it:d", 46.3, 30))

    @Test fun liveDelaysAreAppliedPerCall() {
        val live = listOf(
            LiveStopTime("it:b", "B", t0.plusSeconds(600), t0.plusSeconds(720), 3, 4),
            LiveStopTime("it:c", "C", t0.plusSeconds(1200), t0.plusSeconds(1200), 5, 5),
        )
        val merged = LiveTripMerge.apply(run, live)!!
        assertNull(merged[0].predictedDeparture)
        assertEquals(t0.plusSeconds(13 * 60), merged[1].predictedArrival)
        assertEquals(t0.plusSeconds(16 * 60), merged[1].predictedDeparture)
        assertEquals(t0.plusSeconds(25 * 60), merged[2].predictedDeparture)
    }

    @Test fun unmatchedLiveDataIsIgnored() {
        assertNull(LiveTripMerge.apply(run, listOf(LiveStopTime("it:x", "X", t0, t0, 2, 2))))
        // Same station but a different call (an hour later): no match.
        assertNull(LiveTripMerge.apply(run, listOf(LiveStopTime("it:b", "B", t0.plusSeconds(4200), t0.plusSeconds(4200), 2, 2))))
    }

    @Test fun positionIsInterpolatedBetweenStops() {
        // Halfway between B (departs minute 12) and C (arrives minute 20): minute 16.
        val p = RunPositions.estimate(run, emptyList(), t0.plusSeconds(16 * 60))!!
        assertEquals(2, p.nextIndex)
        assertEquals(46.15, p.point.latitude, 0.001)
        assertTrue(p.estimated && !p.atStop)
    }

    @Test fun dwellingVehicleIsAtTheStop() {
        val p = RunPositions.estimate(run, emptyList(), t0.plusSeconds(11 * 60))!!
        assertTrue(p.atStop)
        assertEquals(2, p.nextIndex)
        assertEquals(46.1, p.point.latitude, 0.0001)
    }

    @Test fun noPositionOutsideTheRun() {
        assertNull(RunPositions.estimate(run, emptyList(), t0.minusSeconds(60)))
        assertNull(RunPositions.estimate(run, emptyList(), t0.plusSeconds(31 * 60)))
    }

    @Test fun positionFollowsTheShape() {
        // An L-shaped route from A via a corner to B: the estimate stays on the route (just past the
        // corner, as the northward leg is the longer one), not on the straight line between A and B.
        val a = Point(46.0, 11.0); val corner = Point(46.0, 11.1); val b = Point(46.1, 11.1)
        val mid = RunPositions.along(listOf(a, corner, b), a, b, 0.5)!!
        assertEquals(11.1, mid.longitude, 1e-9)
        assertTrue(mid.latitude in 46.005..46.03)
    }
}
