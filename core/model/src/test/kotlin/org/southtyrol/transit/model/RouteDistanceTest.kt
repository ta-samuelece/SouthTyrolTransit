package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RouteDistanceTest {
    private val t0 = Instant.parse("2026-10-07T15:00:00Z")
    private fun stop(key: String, lat: Double, minute: Long, meters: Double?) = TripStop(
        Stop("$key:p", key, Point(lat, 11.0), stationKey = key), (minute / 5).toInt(), t0.plusSeconds(minute * 60), t0.plusSeconds(minute * 60),
        distanceMeters = meters,
    )
    // 0.1 degrees of latitude is about 11.1 km; the timetable's distances follow the route.
    private val run = listOf(stop("it:a", 46.0, 0, 0.0), stop("it:b", 46.1, 10, 11_120.0), stop("it:c", 46.2, 20, 22_240.0))
    private val line = (0..20).map { Point(46.0 + it * 0.01, 11.0) }

    @Test fun betweenSubtractsTheStopsDistances() {
        assertEquals(22_240.0, RouteDistance.between(run, 0, 2)!!, 0.1)
        assertNull(RouteDistance.between(run.map { it.copy(distanceMeters = null) }, 0, 2))
        assertNull(RouteDistance.between(run, 2, 0))
    }

    @Test fun aLiveFixGivesTheDistanceToTheNextStop() {
        // Three quarters of the way from b to c.
        val d = RouteDistance.of(line, run, RunPosition(Point(46.175, 11.0), 2, atStop = false, estimated = false))!!
        assertEquals(2_780.0, d.toNextStopMeters, 120.0)
        assertEquals(22_240.0 - d.toNextStopMeters, d.travelledMeters, 0.1)
        assertEquals(d.toNextStopMeters, d.remainingMeters, 0.1)
    }

    @Test fun aFixBeforeTheStretchCountsAsItsStart() {
        // Reported before the previous stop: placed at the start of the stretch, never beyond the timetable's gap.
        val d = RouteDistance.of(line, run, RunPosition(Point(46.05, 11.0), 2, atStop = false, estimated = false))!!
        assertEquals(11_120.0, d.toNextStopMeters, 5.0)
        assertTrue(d.toNextStopMeters <= 11_120.0)
    }

    @Test fun nothingForEstimatesStopsOrMissingDistances() {
        assertNull(RouteDistance.of(line, run, RunPosition(Point(46.175, 11.0), 2, atStop = false, estimated = true)))
        assertNull(RouteDistance.of(line, run, RunPosition(Point(46.1, 11.0), 2, atStop = true, estimated = false)))
        assertNull(RouteDistance.of(line, run, null))
        assertNull(RouteDistance.of(line, run.map { it.copy(distanceMeters = null) }, RunPosition(Point(46.175, 11.0), 2, atStop = false, estimated = false)))
    }

    @Test fun lengthIsFirstToLastStop() {
        val detail = TripDetail(Trip("t", "r", "s", "c", 0, ""), Route("r", "R", "", TransportMode.TRAIN), java.time.LocalDate.of(2026, 10, 7), run, line, Freshness.SCHEDULED)
        assertEquals(22_240.0, detail.lengthMeters!!, 0.1)
        assertNull(detail.copy(stops = run.map { it.copy(distanceMeters = null) }).lengthMeters)
    }
}

class LegDistanceAndBikesTest {
    private val t0 = Instant.parse("2026-10-07T15:00:00Z")
    private fun stop(key: String, minute: Long, meters: Double?) = TripStop(
        Stop("$key:1:1", key, Point(46.0 + minute / 100.0, 11.0), stationKey = key), minute.toInt(), t0.plusSeconds(minute * 60), t0.plusSeconds(minute * 60),
        distanceMeters = meters,
    )
    private val run = TripDetail(
        Trip("t", "r", "s", "c", 0, "", "R 1834", Bikes.ALLOWED), Route("r", "R", "", TransportMode.TRAIN), java.time.LocalDate.of(2026, 10, 7),
        listOf(stop("it:a", 0, 0.0), stop("it:b", 10, 9_800.0), stop("it:c", 20, 23_400.0), stop("it:d", 30, 28_600.0)), emptyList(), Freshness.SCHEDULED,
    )
    private fun leg(from: String, to: String, minute: Long, mode: TransportMode = TransportMode.TRAIN) = Leg(
        mode, "R", "d", Place(from, from, stopGlobalId = from), Place(to, to, stopGlobalId = to), t0.plusSeconds(minute * 60), t0.plusSeconds((minute + 20) * 60),
    )

    @Test fun legDistanceRunsFromBoardingToAlighting() {
        assertEquals(18_800.0, LegDistance.of(run, leg("it:b", "it:d", 10))!!, 0.1)
        assertNull(LegDistance.of(run, leg("it:b", "it:x", 10)))
        assertNull(LegDistance.of(run.copy(stops = run.stops.map { it.copy(distanceMeters = null) }), leg("it:b", "it:d", 10)))
    }

    @Test fun aJourneyIsExcludedOnlyByARunKnownToTakeNoBikes() {
        val walk = leg("it:x", "it:a", 0, TransportMode.WALK)
        val journey = Journey("j", listOf(walk, leg("it:a", "it:c", 0), leg("it:c", "it:d", 25, TransportMode.BUS)), 1)
        assertEquals(mapOf(1 to Bikes.ALLOWED, 2 to Bikes.UNKNOWN), JourneyBikes.legs(journey, mapOf(1 to Bikes.ALLOWED)))
        assertEquals(false, JourneyBikes.excluded(journey, mapOf(1 to Bikes.ALLOWED)))
        assertEquals(true, JourneyBikes.excluded(journey, mapOf(1 to Bikes.ALLOWED, 2 to Bikes.NOT_ALLOWED)))
    }
}
