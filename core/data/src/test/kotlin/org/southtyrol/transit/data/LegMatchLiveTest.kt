package org.southtyrol.transit.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.southtyrol.transit.model.Approaches
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.RealtimeFeed
import org.southtyrol.transit.model.RealtimeTransitDataSource
import org.southtyrol.transit.model.SearchOptions
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * Plans real journeys with the live STA planner and matches every transit leg to its run in the real
 * timetable (`TripRepository.forLeg`), the way the journey view finds a leg's vehicle. Needs both
 * `-PgtfsReal=<zip>` and `-PliveTests=true`; skipped otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class LegMatchLiveTest {
    @Test fun plannerLegsMatchTimetableRuns() = runTest(timeout = 30.minutes) {
        val path = System.getProperty("gtfs.real")?.takeIf { it.isNotBlank() }
        assumeTrue(path != null && File(path).exists() && System.getProperty("live.tests") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ScheduleStore(context, TransitHttp(OkHttpClient()))
        assertTrue(store.importFile(File(path!!), mapOf("source" to "local")))
        val schedule = GtfsSchedule(store)
        val offline = object : RealtimeTransitDataSource { override suspend fun fetch(feed: RealtimeFeed) = throw java.io.IOException("offline") }
        val trips = TripRepository(schedule, RealtimeRepository(offline, FakeUserDao()))
        val efa = EfaClient(TransitHttp(OkHttpClient()))

        suspend fun station(query: String): Place {
            val stop = schedule.searchStops(query, "de").first()
            return Place(stop.id, stop.name, PlaceType.STOP, stop.point, "", stop.stationKey)
        }
        val pairs = listOf(
            "Bozen Bahnhof" to "Meran Bahnhof",
            "Bozen Bahnhof" to "Brixen Bahnhof",
            "Bozen Waltherplatz" to "Bozen Krankenhaus",
            "Meran Bahnhof" to "Schenna",
            "Bruneck Bahnhof" to "Sand in Taufers",
        )
        var legs = 0
        var matched = 0
        var boarded = 0
        for ((a, b) in pairs) {
            val from = station(a); val to = station(b)
            val journeys = efa.plan(from, to, SearchOptions(), "de").take(3)
            for (journey in journeys) for (leg in journey.legs.filter { it.mode.isTransit }) {
                legs++
                val run = trips.forLeg(leg, "de")
                val boarding = run?.let { Approaches.boardingIndex(it.stops, leg.from.stopGlobalId, leg.departure) }
                if (run != null) matched++
                if (boarding != null) boarded++
                println("LEGMATCH ${leg.line.padEnd(6)} ${leg.mode} ${leg.from.name} (${leg.from.stopGlobalId}) ${leg.departure} -> " +
                    (run?.let { "trip ${it.trip.id} route ${it.route.shortName} boarding=$boarding" } ?: "NO MATCH"))
            }
        }
        println("LEGMATCH matched=$matched/$legs boardingFound=$boarded at ${Instant.now()}")
        assertTrue("most planner legs should match a timetable run", legs > 0 && matched * 10 >= legs * 8)
        assertTrue(boarded == matched)
    }
}
