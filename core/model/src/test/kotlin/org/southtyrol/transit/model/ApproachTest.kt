package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ApproachTest {
    private val t0 = Instant.parse("2026-10-07T15:00:00Z")
    private fun stop(key: String, lat: Double, minute: Long, dwell: Long = 0) = TripStop(
        Stop("$key:p", key, Point(lat, 11.0), stationKey = key), (minute / 5).toInt(),
        t0.plusSeconds(minute * 60), t0.plusSeconds((minute + dwell) * 60),
    )
    // a(0) -> b(10, dwell 2) -> c(20) -> d(30), heading north; the user boards at c.
    private val run = listOf(stop("it:a", 46.0, 0), stop("it:b", 46.1, 10, dwell = 2), stop("it:c", 46.2, 20), stop("it:d", 46.3, 30))
    private val shape = (0..30).map { Point(46.0 + it * 0.01, 11.0) }
    private val boarding = 2

    private fun departure(line: String, minute: Long, destination: String = "Merano", mode: TransportMode = TransportMode.BUS, trip: String = "t$line$minute") =
        Departure(trip, "it:c:p", 1, "r", line, mode, destination, t0.plusSeconds(minute * 60))
    private fun leg(line: String, minute: Long, destination: String = "Merano", mode: TransportMode = TransportMode.BUS) =
        Leg(mode, line, destination, Place("c", "C", stopGlobalId = "it:c"), Place("d", "D"), t0.plusSeconds(minute * 60), t0.plusSeconds((minute + 10) * 60))

    @Test fun legMatchesSameLineAtTheSameMinute() {
        val match = LegMatch.departure(leg("201", 20), listOf(departure("201", 50), departure("202", 20), departure("201", 20)))
        assertEquals("t20120", match?.tripId)
    }

    @Test fun legMatchIgnoresBusPrefixAndSpacing() {
        assertEquals("t20120", LegMatch.departure(leg("Bus 201", 20), listOf(departure("201", 20)))?.tripId)
    }

    @Test fun trainsMatchOnModeMinuteAndDestination() {
        // The planner labels trains "R", the timetable "REG".
        val train = leg("R", 20, "Merano/Meran", TransportMode.TRAIN)
        val candidates = listOf(departure("REG", 20, "Brennero", TransportMode.TRAIN, "south"), departure("REG", 20, "Merano", TransportMode.TRAIN, "north"))
        assertEquals("north", LegMatch.departure(train, candidates)?.tripId)
    }

    @Test fun noMatchOutsideAMinuteOrForWalking() {
        assertNull(LegMatch.departure(leg("201", 20), listOf(departure("201", 22))))
        assertNull(LegMatch.departure(leg("201", 20, mode = TransportMode.WALK), listOf(departure("201", 20))))
    }

    @Test fun boardingIndexPicksTheCallClosestInTime() {
        val loop = run + stop("it:c", 46.2, 40)
        assertEquals(2, Approaches.boardingIndex(loop, "it:c", t0.plusSeconds(20 * 60)))
        assertEquals(4, Approaches.boardingIndex(loop, "it:c", t0.plusSeconds(40 * 60)))
        assertNull(Approaches.boardingIndex(run, "it:x", t0))
    }

    @Test fun estimatedApproachFollowsTheShapeToTheBoardingStop() {
        val approach = Approaches.of(run, shape, boarding, gps = null, now = t0.plusSeconds(5 * 60))!!
        assertTrue(approach.position.estimated)
        assertEquals(1, approach.stopsBefore) // b, then c
        assertEquals("it:b", approach.nextStop.stationKey)
        assertEquals(46.05, approach.path.first().latitude, 1e-6)
        assertEquals(46.2, approach.path.last().latitude, 1e-6)
        assertTrue(approach.path.zipWithNext().all { (p, q) -> q.latitude >= p.latitude })
    }

    @Test fun gpsFixWinsOverTheEstimate() {
        val gps = Point(46.07, 11.0)
        val approach = Approaches.of(run, shape, boarding, gps = gps, now = t0.plusSeconds(5 * 60))!!
        assertEquals(false, approach.position.estimated)
        assertEquals(gps, approach.path.first())
    }

    @Test fun gpsBeforeTheRunStartsIsPlacedByTheShape() {
        // Waiting at the terminus before departure: no estimate, but the GPS fix still places it.
        val approach = Approaches.of(run, shape, boarding, gps = Point(46.0, 11.0), now = t0.minusSeconds(120))!!
        assertEquals(0, approach.position.nextIndex)
        assertEquals(2, approach.stopsBefore)
    }

    @Test fun vehicleAtTheBoardingStopIsReportedAsArrived() {
        val dwelling = run.toMutableList().also { it[2] = stop("it:c", 46.2, 20, dwell = 2) }
        val approach = Approaches.of(dwelling, shape, boarding, gps = null, now = t0.plusSeconds(21 * 60))!!
        assertTrue(approach.atBoarding)
        assertEquals(0, approach.stopsBefore)
    }

    @Test fun noApproachOnceThePickupStopIsPassedOrBeforeTheRun() {
        assertNull(Approaches.of(run, shape, boarding, gps = null, now = t0.plusSeconds(25 * 60)))
        assertNull(Approaches.of(run, shape, boarding, gps = null, now = t0.minusSeconds(60)))
    }

    @Test fun withoutAShapeThePathRunsThroughTheRemainingStops() {
        val approach = Approaches.of(run, emptyList(), boarding, gps = null, now = t0.plusSeconds(5 * 60))!!
        assertEquals(listOf(46.1, 46.2), approach.path.drop(1).map { it.latitude })
    }

    @Test fun boardingDelayShiftsOnlyUnpredictedCallsUpToBoarding() {
        val predicted = run.toMutableList().also { it[0] = it[0].copy(predictedDeparture = t0.plusSeconds(60)) }
        val shifted = Approaches.withBoardingDelay(predicted, boarding, 180)
        assertEquals(t0.plusSeconds(60), shifted[0].predictedDeparture)
        assertEquals(t0.plusSeconds(13 * 60), shifted[1].predictedArrival)
        assertEquals(t0.plusSeconds(23 * 60), shifted[2].predictedDeparture)
        assertNull(shifted[3].predictedArrival)
    }
}
