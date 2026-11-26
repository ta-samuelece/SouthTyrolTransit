package org.southtyrol.transit.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.TripSchedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class TripScheduleStoreTest {
    private val home = Place("", "Laurinstraße 8", PlaceType.ADDRESS, Point(46.67, 11.16), "Meran")
    private val station = Place("66000468", "Bozen, Bahnhof", PlaceType.STOP, Point(46.49, 11.35), "Bozen", "it:22021:468")
    private val schedule = TripSchedule(
        "", "To work", home, station, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), LocalTime.of(7, 15), LocalTime.of(8, 0), arriveBy = true, enabled = false,
    )

    private fun schedules(items: List<SavedItem>) = items.filter { it.kind == SavedKind.TRIP_SCHEDULE }

    @Test fun codecRoundTrip() {
        val text = Codec.schedule(schedule)
        assertTrue(text, text.contains("\"days\":[1,5]") && text.contains("\"start\":\"07:15\"") && text.contains("\"end\":\"08:00\""))
        assertEquals(schedule, Codec.schedule(text))
    }

    @Test fun badPayloadsDecodeToNull() {
        assertNull(Codec.schedule("not json"))
        assertNull(Codec.schedule("{}"))
        assertNull(Codec.schedule(Codec.schedule(schedule).replace("[1,5]", "[1,9]")))
        assertNull(Codec.schedule(Codec.schedule(schedule).replace("07:15", "7.15")))
        assertNull(SavedItem(SavedKind.TRIP_SCHEDULE, "schedule:x", "Broken", "", "{\"name\":1}").tripSchedule)
        assertNull(SavedItem(SavedKind.PLACE, "place:x", "A place", "", Codec.place(home)).tripSchedule)
    }

    @Test fun saveUpdateListAndRemove() = runBlocking {
        var now = 1_000L
        val user = FakeUserDao()
        val saved = SavedRepository(user) { Instant.ofEpochMilli(now++) }
        val first = saved.saveSchedule(schedule)
        val second = saved.saveSchedule(schedule.copy(name = "Home again", from = station, to = home))
        assertTrue(first.startsWith(SavedRepository.SCHEDULE_PREFIX))
        assertTrue(first != second)

        val listed = schedules(saved.saved.first())
        // Creation order, oldest first.
        assertEquals(listOf("To work", "Home again"), listed.map { it.label })
        assertEquals("Laurinstraße 8 → Bozen, Bahnhof", listed[0].subtitle)
        assertEquals(schedule.copy(id = first), listed[0].tripSchedule)

        val created = user.savedRows.value.single { it.id == first }.created
        saved.saveSchedule(schedule.copy(id = first, name = "To the office", enabled = true))
        val updated = schedules(saved.saved.first())
        assertEquals(listOf("To the office", "Home again"), updated.map { it.label })
        assertEquals(true, updated[0].tripSchedule!!.enabled)
        assertEquals(created, user.savedRows.value.single { it.id == first }.created)

        saved.remove(SavedKind.TRIP_SCHEDULE, first)
        assertEquals(listOf(second), schedules(saved.saved.first()).map { it.id })
    }

    @Test fun rowsOfAnUnknownKindAreSkipped() = runBlocking {
        val user = FakeUserDao()
        user.save(SavedRow("FUTURE_KIND", "x", "From a newer version", "", "{}", created = 1))
        val saved = SavedRepository(user)
        saved.saveSchedule(schedule)
        assertEquals(listOf(SavedKind.TRIP_SCHEDULE), saved.saved.first().map { it.kind })
    }
}
