package org.southtyrol.transit.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.LineVariant
import org.southtyrol.transit.model.Operator
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.Route
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransitScheduleDataSource
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.Trip
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.model.TripStop
import org.southtyrol.transit.model.TripUpdate
import org.southtyrol.transit.model.Vehicle
import java.time.Instant
import java.time.LocalDate

/** The feed's two-hour-old run labels, as seen on 2026-10-21 for line 201 at Funivia del Renon. */
class VehicleRunResolverTest {
    private val date = LocalDate.of(2026, 10, 21)
    private fun at(time: String): Instant = date.atTime(java.time.LocalTime.parse(time)).atZone(TransitZone).toInstant()
    private val now = at("21:32")
    private val renon = "it:22021:2083:1:2794"

    /** A run of line 201 calling at Funivia del Renon at [time], in [direction]. */
    private fun run(id: String, time: String, direction: Int = 0, route: String = "1-201-26a-5") = TripDetail(
        Trip(id, route, "s", "Renon", direction, ""), Route(route, "201", "", TransportMode.BUS), date,
        listOf(
            TripStop(Stop("it:22021:468:1:2805", "Stazione", Point(46.49, 11.35)), 1, at(time).minusSeconds(120), at(time).minusSeconds(120)),
            TripStop(Stop(renon, "Funivia del Renon", Point(46.4992, 11.363)), 2, at(time), at(time)),
        ),
        emptyList(), Freshness.SCHEDULED,
    )

    private val reported = run("412.TA.1-201-26a-5.5.H", "19:34")
    private val actual = run("413.TA.1-201-26a-5.5.H", "21:30")
    private val otherWay = run("300.TA.1-201-26a-5.5.R", "21:31", direction = 1)
    private val otherLine = run("9.TA.1-202-26a-5.5.H", "21:32", route = "1-202-26a-5")
    /** The next run, a little late: still the one its vehicle is on, and farther from now than [actual]. */
    private val onTime = run("500.TA.1-201-26a-5.5.H", "21:45")

    private val schedule = FakeSchedule(listOf(reported, actual, otherWay, otherLine, onTime))
    private fun vehicle(id: String, trip: TripDetail) = Vehicle(id, Point(46.4992, 11.363), trip.trip.id, trip.trip.routeId, null, now, stopId = renon, startDate = date)

    @Test fun aRunHoursBehindTheClockMovesToTheLinesRunDueNow() = runBlocking {
        val fixed = VehicleRunResolver(schedule).vehicles(listOf(vehicle("1", reported)), now).single()
        assertEquals(actual.trip.id, fixed.tripId)
        assertEquals(date, fixed.startDate)
    }

    @Test fun aRunDueAboutNowIsKept() = runBlocking {
        assertEquals(onTime.trip.id, VehicleRunResolver(schedule).vehicles(listOf(vehicle("2", onTime)), now).single().tripId)
    }

    @Test fun onlyTheSameLineAndDirectionQualify() = runBlocking {
        val narrow = FakeSchedule(listOf(reported, otherWay, otherLine))
        assertEquals(reported.trip.id, VehicleRunResolver(narrow).vehicles(listOf(vehicle("1", reported)), now).single().tripId)
    }

    @Test fun theReportedRunsTripUpdatesFollowTheVehicle() = runBlocking {
        val resolver = VehicleRunResolver(schedule)
        resolver.vehicles(listOf(vehicle("1", reported)), now)
        val update = TripUpdate(reported.trip.id, date = date, timestamp = now, state = ServiceState.NORMAL, stops = emptyList(), delay = 120)
        val stray = update.copy(tripId = actual.trip.id, delay = 900)
        val moved = resolver.updates(listOf(stray, update), now)
        assertEquals(listOf(actual.trip.id, actual.trip.id), moved.map { it.tripId })
        assertEquals(120, moved.first().delay)
    }

    @Test fun withoutATimetableTheFeedIsLeftAlone() = runBlocking {
        val v = vehicle("1", reported)
        assertEquals(listOf(v), VehicleRunResolver(FakeSchedule(emptyList(), available = false)).vehicles(listOf(v), now))
    }

    private class FakeSchedule(private val trips: List<TripDetail>, private val available: Boolean = true) : TransitScheduleDataSource {
        override suspend fun isAvailable() = available
        override suspend fun trip(tripId: String, serviceDate: LocalDate, language: String) = trips.firstOrNull { it.trip.id == tripId }
        override suspend fun scheduledDepartures(stopIds: Collection<String>, from: Instant, until: Instant, arrivals: Boolean, language: String, limit: Int): List<Departure> =
            // Each run ends at Renon: it is an arrival there, never a departure.
            if (!arrivals) emptyList() else trips.flatMap { t ->
                t.stops.filter { it.stop.id in stopIds && !it.scheduledArrival.isBefore(from) && !it.scheduledArrival.isAfter(until) }.map {
                    Departure(t.trip.id, it.stop.id, it.sequence, t.trip.routeId, t.route.shortName, t.route.mode, t.trip.headsign, it.scheduledArrival, serviceDate = t.serviceDate)
                }
            }
        override suspend fun searchStops(query: String, language: String, limit: Int) = emptyList<Stop>()
        override suspend fun nearbyStops(center: Point, radiusMeters: Double, language: String, limit: Int) = emptyList<Stop>()
        override suspend fun stopsIn(box: BoundingBox, language: String, limit: Int) = emptyList<Stop>()
        override suspend fun stop(id: String, language: String): Stop? = null
        override suspend fun stationStops(stationKey: String, language: String) = emptyList<Stop>()
        override suspend fun linesAtStops(stopIds: Collection<String>) = emptyList<Line>()
        override suspend fun searchLines(query: String) = emptyList<Line>()
        override suspend fun line(key: String): Line? = null
        override suspend fun lineVariants(line: Line, language: String) = emptyList<LineVariant>()
        override suspend fun variantTrips(line: Line, variant: LineVariant, from: Instant, until: Instant, language: String) = emptyList<Departure>()
        override suspend fun routes(ids: Collection<String>) = emptyMap<String, Route>()
        override suspend fun operators() = emptyList<Operator>()
    }
}
