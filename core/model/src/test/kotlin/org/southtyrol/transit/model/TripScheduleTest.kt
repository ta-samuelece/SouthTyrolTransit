package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.TimeZone

class TripScheduleTest {
    private val home = Place("home", "Home", PlaceType.ADDRESS)
    private val work = Place("66000468", "Bozen, Bahnhof")
    private val weekdays = setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
    private val schedule = TripSchedule("schedule:1", "To work", home, work, weekdays, LocalTime.of(7, 15), LocalTime.of(8, 0), arriveBy = false, enabled = true)

    // 2026-11-23 is a Monday, 2026-11-27 a Friday.
    private val monday = LocalDate.of(2026, 11, 23)
    private fun rome(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(TransitZone).toInstant()

    @Test fun beforeTodaysWindowGivesToday() {
        val window = schedule.nextWindow(rome(monday, 6, 30))!!
        assertEquals(monday, window.date)
        assertEquals(Instant.parse("2026-11-23T06:15:00Z"), window.start)
        assertEquals(Instant.parse("2026-11-23T07:00:00Z"), window.end)
    }

    @Test fun insideTodaysWindowStillGivesToday() {
        assertEquals(monday, schedule.nextWindow(rome(monday, 7, 40))!!.date)
    }

    @Test fun afterTodaysWindowMovesToTheNextChosenDay() {
        assertEquals(monday.plusDays(1), schedule.nextWindow(rome(monday, 8, 0))!!.date)
        assertEquals(monday.plusDays(1), schedule.nextWindow(rome(monday, 22, 0))!!.date)
    }

    @Test fun todayNotChosenGivesTheNextChosenDay() {
        val midweek = schedule.copy(days = setOf(WEDNESDAY))
        assertEquals(monday.plusDays(2), midweek.nextWindow(rome(monday, 6, 0))!!.date)
    }

    @Test fun fridayEveningGoesToMonday() {
        val friday = monday.plusDays(4)
        assertEquals(monday.plusDays(7), schedule.nextWindow(rome(friday, 19, 0))!!.date)
        assertEquals(monday.plusDays(7), schedule.nextWindow(rome(friday.plusDays(1), 9, 0))!!.date)
    }

    @Test fun aSingleDayAfterItsWindowIsAWeekLater() {
        val mondays = schedule.copy(days = setOf(MONDAY))
        val window = mondays.nextWindow(rome(monday, 9, 0))!!
        assertEquals(monday.plusDays(7), window.date)
        assertEquals(rome(monday.plusDays(7), 7, 15), window.start)
    }

    @Test fun noDaysHasNoWindow() {
        assertNull(schedule.copy(days = emptySet()).nextWindow(rome(monday, 6, 0)))
    }

    @Test fun theDeviceZoneDoesNotMatter() {
        val now = rome(monday, 23, 30) // already Tuesday in UTC, still Monday evening in New York
        val expected = schedule.nextWindow(now)
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val window = schedule.nextWindow(now)!!
            assertEquals(expected, window)
            assertEquals(monday.plusDays(1), window.date)
            assertEquals(Instant.parse("2026-11-24T06:15:00Z"), window.start)
            assertEquals(Instant.parse("2026-11-24T07:00:00Z"), window.end)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun windowsOnDaylightSavingDays() {
        // Clocks go forward on Sunday 2026-03-29 at 02:00 and back on Sunday 2026-10-25 at 03:00.
        val night = schedule.copy(days = setOf(DayOfWeek.SUNDAY), start = LocalTime.of(1, 0), end = LocalTime.of(4, 0))
        val spring = night.nextWindow(Instant.parse("2026-03-28T12:00:00Z"))!!
        assertEquals(LocalDate.of(2026, 3, 29), spring.date)
        assertEquals(Instant.parse("2026-03-29T00:00:00Z"), spring.start)
        assertEquals(Instant.parse("2026-03-29T02:00:00Z"), spring.end)
        assertEquals(Duration.ofHours(2), Duration.between(spring.start, spring.end))
        val autumn = night.nextWindow(Instant.parse("2026-10-24T12:00:00Z"))!!
        assertEquals(Duration.ofHours(4), Duration.between(autumn.start, autumn.end))
        val morning = schedule.copy(days = setOf(DayOfWeek.SUNDAY)).nextWindow(Instant.parse("2026-10-24T12:00:00Z"))!!
        assertEquals(Instant.parse("2026-10-25T06:15:00Z"), morning.start)
    }

    @Test fun validation() {
        assertTrue(schedule.problems.isEmpty())
        assertEquals(setOf(TripSchedule.Problem.NAME_BLANK), schedule.copy(name = " ").problems)
        assertEquals(setOf(TripSchedule.Problem.SAME_PLACE), schedule.copy(to = home).problems)
        // Same id but another name is another place, as for the planner.
        assertTrue(schedule.copy(to = home.copy(name = "Elsewhere")).problems.isEmpty())
        assertEquals(setOf(TripSchedule.Problem.NO_DAYS), schedule.copy(days = emptySet()).problems)
        assertEquals(setOf(TripSchedule.Problem.START_NOT_BEFORE_END), schedule.copy(end = LocalTime.of(7, 15)).problems)
        assertEquals(setOf(TripSchedule.Problem.START_NOT_BEFORE_END), schedule.copy(end = LocalTime.of(7, 0)).problems)
        assertEquals(TripSchedule.Problem.entries.toSet(), TripSchedule.problems("", home, home, emptySet(), LocalTime.NOON, LocalTime.NOON))
        // Places not chosen yet are no same-place problem.
        assertTrue(TripSchedule.problems("x", null, null, setOf(SATURDAY), LocalTime.MIN, LocalTime.NOON).isEmpty())
    }

    private fun journey(id: String, departure: Instant, arrival: Instant) =
        Journey(id, listOf(Leg(TransportMode.BUS, "201", "Bozen", home, work, departure, arrival, predictedDeparture = departure.plusSeconds(600))), changes = 0)

    @Test fun leaveBetweenKeepsScheduledDeparturesInTheWindow() {
        val window = schedule.window(monday)
        val early = journey("early", rome(monday, 7, 14), rome(monday, 7, 40))
        val first = journey("first", rome(monday, 7, 15), rome(monday, 7, 50))
        val last = journey("last", rome(monday, 8, 0), rome(monday, 8, 30))
        val late = journey("late", rome(monday, 8, 1), rome(monday, 8, 30))
        val mid = journey("mid", rome(monday, 7, 30), rome(monday, 8, 5))
        val kept = schedule.connectionsIn(window, listOf(late, last, mid, early, first, mid))
        assertEquals(listOf("first", "mid", "last"), kept.map { it.id })
    }

    @Test fun arriveBetweenKeepsScheduledArrivalsInTheWindow() {
        val arrive = schedule.copy(arriveBy = true)
        val window = arrive.window(monday)
        val tooEarly = journey("tooEarly", rome(monday, 6, 30), rome(monday, 7, 10))
        val a = journey("a", rome(monday, 6, 50), rome(monday, 7, 15))
        val b = journey("b", rome(monday, 7, 20), rome(monday, 8, 0))
        val tooLate = journey("tooLate", rome(monday, 7, 40), rome(monday, 8, 1))
        assertEquals(listOf("a", "b"), arrive.connectionsIn(window, listOf(b, tooLate, a, tooEarly, b)).map { it.id })
    }
}
