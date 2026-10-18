package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class JourneyFollowTest {
    private val t0 = Instant.parse("2026-10-17T11:12:00Z")
    private fun at(minute: Long) = t0.plusSeconds(minute * 60)
    private fun place(key: String, lat: Double) = Place(key, key.uppercase(), point = Point(lat, 11.3), stopGlobalId = "it:$key")

    private val a = place("a", 46.70)
    private val b = place("b", 46.60)
    private val bBus = place("b", 46.601)
    private val c = place("c", 46.55)
    private val d = place("d", 46.50)

    // Train a -> b (0..32), walk to the bus stop (32..35), bus b -> d via c (43..100).
    private val train = Leg(TransportMode.TRAIN, "R", "B", a, b, at(0), at(32))
    private val walk = Leg(TransportMode.WALK, "", "", b, bBus, at(32), at(35))
    private val bus = Leg(
        TransportMode.BUS, "201", "D", bBus, d, at(43), at(100),
        intermediate = listOf(IntermediateStop(c, at(70), at(70))),
    )
    private val journey = Journey("j", listOf(train, walk, bus), changes = 1)

    @Test fun beforeTheFirstDepartureTheStepIsBoarding() {
        val step = JourneyFollow.step(journey, at(-5))
        assertEquals(StepKind.BOARD, step.kind)
        assertEquals(0, step.legIndex)
        assertEquals(a, step.target)
        assertEquals(at(0), step.at)
    }

    @Test fun ridingTowardsAChangeSaysChange() {
        val step = JourneyFollow.step(journey, at(20))
        assertEquals(StepKind.RIDE, step.kind)
        assertTrue(step.change)
        assertEquals(b, step.target)
        assertEquals(2, step.nextTransitIndex)
    }

    @Test fun walkingToABoardingCountsDownToTheDeparture() {
        val step = JourneyFollow.step(journey, at(33))
        assertEquals(StepKind.WALK, step.kind)
        assertEquals(bBus, step.target)
        assertEquals(at(43), step.at)
    }

    @Test fun afterTheWalkTheStepIsBoardingTheBus() {
        val step = JourneyFollow.step(journey, at(38))
        assertEquals(StepKind.BOARD, step.kind)
        assertEquals(2, step.legIndex)
    }

    @Test fun ridingTheLastLegGetsOffAtTheDestinationAndCountsPassedStops() {
        val step = JourneyFollow.step(journey, at(80))
        assertEquals(StepKind.RIDE, step.kind)
        assertFalse(step.change)
        assertEquals(d, step.target)
        assertEquals(2, step.stopsPassed)
    }

    @Test fun aDelayMovesTheStepTime() {
        val late = journey.copy(legs = listOf(train, walk, bus.copy(predictedArrival = at(104))))
        val step = JourneyFollow.step(late, at(101))
        assertEquals(StepKind.RIDE, step.kind)
        assertEquals(at(104), step.at)
        assertEquals(240L, step.delaySeconds)
    }

    @Test fun afterTheArrivalTheJourneyIsArrivedThenFinished() {
        assertEquals(StepKind.ARRIVED, JourneyFollow.step(journey, at(100)).kind)
        assertFalse(JourneyFollow.finished(journey, at(101)))
        assertTrue(JourneyFollow.finished(journey, at(102)))
    }

    @Test fun aCancelledLegStillAheadIsOffPlan() {
        val cancelled = journey.copy(legs = listOf(train, walk, bus.copy(cancelled = true)))
        assertEquals(OffPlan.LegCancelled(2), JourneyFollow.offPlan(cancelled, at(10)))
        assertNull(JourneyFollow.offPlan(journey, at(10)))
    }

    @Test fun aLateArrivalBreaksTheConnection() {
        // Arrives 9 min late: 41 + 3 min walk is after the bus leaves at 43.
        val late = journey.copy(legs = listOf(train.copy(predictedArrival = at(41)), walk, bus))
        assertEquals(OffPlan.ConnectionBroken(2), JourneyFollow.offPlan(late, at(20)))
        val tight = journey.copy(legs = listOf(train.copy(predictedArrival = at(40)), walk, bus))
        assertNull(JourneyFollow.offPlan(tight, at(20)))
    }

    @Test fun stillAtTheBoardingStopAfterTheDepartureIsAMissedBoarding() {
        val there = Point(46.6012, 11.3)
        assertNull(JourneyFollow.offPlan(journey, at(44), there, 20f))
        assertEquals(OffPlan.MissedBoarding(2), JourneyFollow.offPlan(journey, at(46), there, 20f))
        // An inaccurate fix proves nothing.
        assertNull(JourneyFollow.offPlan(journey, at(46), there, 500f))
    }

    @Test fun farFromTheRouteWhileRidingIsOffRoute() {
        assertNull(JourneyFollow.offPlan(journey, at(80), Point(46.53, 11.301), 20f))
        assertEquals(OffPlan.OffRoute(2), JourneyFollow.offPlan(journey, at(80), Point(46.53, 11.4), 20f))
    }

    @Test fun aWalkAfterALateLegStartsWhenItArrives() {
        // The train arrives 4 min late: the walk to the bus moves with it, and the final walk with the bus.
        val finalWalk = Leg(TransportMode.WALK, "", "", d, place("e", 46.49), at(100), at(105))
        val late = Journey("j", listOf(train.copy(predictedArrival = at(36)), walk, bus.copy(predictedArrival = at(110)), finalWalk), 1)
        val live = LiveJourney.apply(late, emptyMap())
        assertEquals(at(39), live.legs[1].bestArrival)
        assertEquals(at(115), live.bestArrival)
        // Riding the late bus is not mistaken for having arrived.
        assertEquals(StepKind.RIDE, JourneyFollow.step(live, at(107)).kind)
        assertFalse(JourneyFollow.finished(live, at(108)))
        // Just off the late train, the walk to the bus is still the step.
        assertEquals(StepKind.WALK, JourneyFollow.step(live, at(37)).kind)
        // Nothing late: nothing changes.
        assertTrue(LiveJourney.apply(journey, emptyMap()) === journey)
    }

    @Test fun liveRunTimesReplaceThePlannedOnes() {
        fun call(p: Place, seq: Int, minute: Long, predicted: Long? = null) = TripStop(
            Stop("${p.stopGlobalId}:1", p.name, p.point!!, stationKey = p.stopGlobalId), seq, at(minute), at(minute),
            predicted?.let { at(it) }, predicted?.let { at(it) },
        )
        val run = TripDetail(
            Trip("t", "r", "s", "D", 0, ""), Route("r", "201", "", TransportMode.BUS), LocalDate.of(2026, 10, 17),
            listOf(call(place("z", 46.65), 1, 30), call(bBus, 2, 43, 45), call(c, 3, 70, 73), call(d, 4, 100, 103)),
            emptyList(), Freshness.LIVE,
        )
        val live = LiveJourney.apply(journey, mapOf(2 to run)).legs[2]
        assertEquals(at(45), live.predictedDeparture)
        assertEquals(at(103), live.predictedArrival)
        assertEquals(at(73), live.intermediate.single().predictedArrival)
        assertTrue(live.realtime)
        assertFalse(live.cancelled)

        val cancelled = LiveJourney.apply(journey, mapOf(2 to run.copy(state = ServiceState.CANCELLED))).legs[2]
        assertTrue(cancelled.cancelled)
        // A run that does not call at the leg's stops leaves it alone.
        val elsewhere = run.copy(stops = run.stops.take(1))
        assertEquals(bus, LiveJourney.apply(journey, mapOf(2 to elsewhere)).legs[2])
    }
}
