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
        // Waiting at the terminus before departure: no estimate, but the GPS fix places it at the first
        // call, like a dwelling estimate - b is the only call left before the boarding stop.
        val approach = Approaches.of(run, shape, boarding, gps = Point(46.0, 11.0), now = t0.minusSeconds(120))!!
        assertTrue(approach.position.atStop)
        assertEquals(1, approach.position.nextIndex)
        assertEquals(1, approach.stopsBefore)
        assertEquals("it:b", approach.nextStop.stationKey)
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

    private fun at(minute: Long) = t0.plusSeconds(minute * 60)

    @Test fun beforeBoardingPlacesTheVehicleAmongTheEarlierStops() {
        val before = Approaches.beforeBoarding(leg("201", 20), run, shape, gps = null, now = at(5), placeVehicle = true)!!
        assertEquals(2, before.boardingIndex)
        assertEquals(2, before.stopsBefore)
        assertEquals(0, before.reached) // left a, on its way to b
        assertTrue(before.passed(0, at(5)))
        assertEquals(false, before.passed(1, at(5)))
    }

    @Test fun beforeBoardingIsNothingAtTheFirstStopOrOnceTheVehicleHasLeft() {
        assertNull(Approaches.beforeBoarding(leg("201", 0).copy(from = Place("a", "A", stopGlobalId = "it:a"), departure = at(0)), run, shape, null, at(-5), placeVehicle = true))
        assertNull(Approaches.beforeBoarding(leg("201", 20), run, shape, null, at(25), placeVehicle = true))
        assertNull(Approaches.beforeBoarding(leg("201", 20, mode = TransportMode.TRAIN), run, shape, null, at(25), placeVehicle = false))
    }

    @Test fun beforeTheRunStartsTheTimesAreShownWithoutAVehicle() {
        val before = Approaches.beforeBoarding(leg("201", 20), run, shape, null, at(-5), placeVehicle = true)!!
        assertEquals(false, before.started)
        assertNull(before.approach)
        assertEquals(false, before.passed(0, at(-5)))
    }

    @Test fun trainsGetTheTimesOnlyPassedByTheClock() {
        val before = Approaches.beforeBoarding(leg("R", 20, mode = TransportMode.TRAIN), run, shape, null, at(15), placeVehicle = false)!!
        assertTrue(before.started)
        assertNull(before.approach)
        assertTrue(before.passed(1, at(15))) // b left at 12
    }

    @Test fun earlierStopsOpenOneStopBeforeTheVehicle() {
        val long = (0..7).map { stop("it:s$it", 46.0 + it * 0.04, it * 5L) }
        val boardAt7 = Leg(TransportMode.BUS, "201", "Merano", Place("s7", "S7", stopGlobalId = "it:s7"), Place("x", "X"), at(35), at(45))
        // Between s4 (20) and s5 (25): opens at s4, the stop it has just left.
        assertEquals(4, Approaches.beforeBoarding(boardAt7, long, emptyList(), null, at(22), placeVehicle = true)!!.firstShown)
        // Standing at s4 (GPS): one stop before it.
        assertEquals(3, Approaches.beforeBoarding(boardAt7, long, emptyList(), long[4].stop.point, at(20), placeVehicle = true)!!.firstShown)
        // Not placed: the last four before the boarding stop.
        assertEquals(3, Approaches.beforeBoarding(boardAt7, long, emptyList(), null, at(22), placeVehicle = false)!!.firstShown)
        // Between s1 and s2: a single stop above would be left, so all are shown.
        assertEquals(0, Approaches.beforeBoarding(boardAt7, long, emptyList(), null, at(7), placeVehicle = true)!!.firstShown)
    }
}
