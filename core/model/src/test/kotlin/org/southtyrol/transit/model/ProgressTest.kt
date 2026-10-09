package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProgressTest {
    private val t0 = Instant.parse("2026-10-07T15:00:00Z")
    private fun stop(key: String, lat: Double, minute: Long) = TripStop(
        Stop("$key:p", key, Point(lat, 11.0), stationKey = key), (minute / 5).toInt(), t0.plusSeconds(minute * 60), t0.plusSeconds(minute * 60),
    )
    private val run = listOf(stop("it:a", 46.0, 0), stop("it:b", 46.1, 10), stop("it:c", 46.2, 20))
    private val line = (0..20).map { Point(46.0 + it * 0.01, 11.0) }

    @Test fun nextIndexIsTheFirstCallStillAhead() {
        assertEquals(0, RouteProgress.nextIndex(run, t0.minusSeconds(60)))
        assertEquals(1, RouteProgress.nextIndex(run, t0.plusSeconds(5 * 60)))
        assertEquals(3, RouteProgress.nextIndex(run, t0.plusSeconds(25 * 60)))
    }

    @Test fun splitProjectsTheVehicleOntoTheRoute() {
        // Slightly off the line, between two vertices.
        val split = RouteProgress.split(line, Point(46.055, 11.0005))
        assertEquals(46.055, split.done.last().latitude, 1e-6)
        assertEquals(11.0, split.done.last().longitude, 1e-6)
        assertEquals(split.done.last(), split.ahead.first())
        assertEquals(line.first(), split.done.first())
        assertEquals(line.last(), split.ahead.last())
    }

    @Test fun splitUsesTheStretchBetweenTheNeighbouringStops() {
        // Out and back on the same street: the vehicle on the way back must split the second pass.
        val outAndBack = line + line.reversed().drop(1)
        val split = RouteProgress.split(outAndBack, Point(46.05, 11.0), after = Point(46.2, 11.0), before = Point(46.0, 11.0))
        assertTrue("done covers the outward pass", split.done.size > line.size)
    }

    @Test fun withoutAPositionTheRunIsAllAheadOrAllDone() {
        assertEquals(line, RouteProgress.of(line, run, null, t0.minusSeconds(60))!!.ahead)
        assertEquals(line, RouteProgress.of(line, run, null, t0.plusSeconds(30 * 60))!!.done)
        assertNull(RouteProgress.of(line, run, null, t0.plusSeconds(5 * 60)))
    }

    @Test fun legStopsSkipCallsWithoutCoordinatesAndUsePredictions() {
        val leg = Leg(
            TransportMode.BUS, "201", "C", Place("a", "A", point = Point(46.0, 11.0)), Place("c", "C", point = Point(46.2, 11.0)),
            t0, t0.plusSeconds(20 * 60), predictedDeparture = t0.plusSeconds(120),
            intermediate = listOf(
                IntermediateStop(Place("b", "B", point = Point(46.1, 11.0)), t0.plusSeconds(600), t0.plusSeconds(600)),
                IntermediateStop(Place("x", "X"), t0.plusSeconds(700), t0.plusSeconds(700)),
            ),
        )
        val stops = RouteProgress.legStops(leg)
        assertEquals(listOf("A", "B", "C"), stops.map { it.stop.name })
        assertEquals(t0.plusSeconds(120), stops.first().predictedDeparture)
        assertEquals(0f, RouteProgress.legFraction(leg, t0), 0f)
        assertEquals(0.5f, RouteProgress.legFraction(leg, t0.plusSeconds(11 * 60)), 1e-3f)
        assertEquals(1f, RouteProgress.legFraction(leg, t0.plusSeconds(21 * 60)), 0f)
    }

    @Test fun withAPositionTheRunSplitsThere() {
        val position = RunPositions.estimate(run, line, t0.plusSeconds(5 * 60))!!
        val split = RouteProgress.of(line, run, position, t0.plusSeconds(5 * 60))!!
        assertEquals(46.05, split.done.last().latitude, 1e-6)
    }
}
