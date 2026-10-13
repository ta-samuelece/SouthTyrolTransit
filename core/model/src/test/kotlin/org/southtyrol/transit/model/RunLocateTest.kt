package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RunLocateTest {
    private val t0 = Instant.parse("2026-10-07T15:00:00Z")
    private fun stop(key: String, lat: Double, minute: Long) = TripStop(
        Stop("$key:p", key, Point(lat, 11.0), stationKey = key), minute.toInt(), t0.plusSeconds(minute * 60), t0.plusSeconds(minute * 60),
    )
    private val run = listOf(stop("it:a", 46.0, 0), stop("it:b", 46.1, 10), stop("it:c", 46.2, 20))
    private val line = (0..20).map { Point(46.0 + it * 0.01, 11.0) }

    @Test fun aLateRunWithoutPredictionsIsPlacedWhereTheBusIs() {
        // The timetable says the run is over; the GPS says it is between the first two calls.
        val p = RunPositions.locate(run, line, Point(46.05, 11.0), t0.plusSeconds(30 * 60))
        assertEquals(1, p.nextIndex)
        assertFalse(p.atStop)
        assertFalse(p.estimated)
    }

    @Test fun aFixNextToACallStandsThere() {
        val p = RunPositions.locate(run, line, Point(46.1002, 11.0), t0.plusSeconds(5 * 60))
        assertTrue(p.atStop)
        assertEquals(2, p.nextIndex)
    }

    @Test fun pastTheLastCallItIsAtTheTerminus() {
        val p = RunPositions.locate(run, line, Point(46.2, 11.0), t0.plusSeconds(40 * 60))
        assertTrue(p.atStop)
        assertEquals(run.size, p.nextIndex)
    }

    @Test fun beforeTheFirstCallTheFirstIsNext() {
        val p = RunPositions.locate(run, line, Point(45.99, 11.0), t0.minusSeconds(5 * 60))
        assertEquals(0, p.nextIndex)
        assertFalse(p.atStop)
    }

    @Test fun withoutAShapeTheCallsFormTheLine() {
        val p = RunPositions.locate(run, emptyList(), Point(46.15, 11.0), t0)
        assertEquals(2, p.nextIndex)
    }

    @Test fun onAnOutAndBackRunTheTimesPickThePass() {
        val loop = listOf(stop("it:a", 46.0, 0), stop("it:b", 46.2, 10), stop("it:c", 46.0, 20))
        val outAndBack = line + line.reversed().drop(1)
        assertEquals(1, RunPositions.locate(loop, outAndBack, Point(46.1, 11.0), t0.plusSeconds(5 * 60)).nextIndex)
        assertEquals(2, RunPositions.locate(loop, outAndBack, Point(46.1, 11.0), t0.plusSeconds(15 * 60)).nextIndex)
    }
}
