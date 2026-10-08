package org.southtyrol.transit.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.southtyrol.transit.model.AlertSource
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.DepartureBoardDataSource
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.GtfsTime
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RealtimeFeed
import org.southtyrol.transit.model.RealtimeTransitDataSource
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.StopUpdate
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.TripUpdate
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** In-memory [UserDao] for repository tests. */
class FakeUserDao : UserDao {
    val savedRows = MutableStateFlow<List<SavedRow>>(emptyList())
    val recentRows = MutableStateFlow<List<RecentRow>>(emptyList())
    val cacheRows = HashMap<String, CacheRow>()
    private val notified = HashMap<String, Long>()
    override fun saved(): Flow<List<SavedRow>> = savedRows
    override suspend fun saved(kind: String) = savedRows.value.filter { it.kind == kind }
    override fun isSaved(kind: String, id: String) = savedRows.map { rows -> rows.count { it.kind == kind && it.id == id } }
    override suspend fun save(row: SavedRow) { savedRows.value = savedRows.value.filterNot { it.kind == row.kind && it.id == row.id } + row }
    override suspend fun unsave(kind: String, id: String) { savedRows.value = savedRows.value.filterNot { it.kind == kind && it.id == id } }
    override fun recent(kind: String, limit: Int) = recentRows.map { rows -> rows.filter { it.kind == kind }.sortedByDescending { it.used }.take(limit) }
    override suspend fun touch(row: RecentRow) { recentRows.value = recentRows.value.filterNot { it.kind == row.kind && it.id == row.id } + row }
    override suspend fun trimRecent(kind: String, keep: Int) { val keepIds = recentRows.value.filter { it.kind == kind }.sortedByDescending { it.used }.take(keep).map { it.id }.toSet(); recentRows.value = recentRows.value.filter { it.kind != kind || it.id in keepIds } }
    override suspend fun clearRecent() { recentRows.value = emptyList() }
    override suspend fun cache(row: CacheRow) { cacheRows[row.key] = row }
    override suspend fun cache(key: String) = cacheRows[key]
    override suspend fun pruneCache(before: Long) { cacheRows.values.removeIf { it.fetchedAt < before } }
    override suspend fun notifiedAlerts() = notified.keys.toList()
    override suspend fun markNotified(row: NotifiedAlertRow) { notified.putIfAbsent(row.alertId, row.notifiedAt) }
    override suspend fun pruneNotified(before: Long) { notified.values.removeIf { it < before } }
}

/** Builds a tiny but tricky GTFS feed: past-midnight trips, calendar exceptions, translations, platforms. */
object SyntheticGtfs {
    fun zip(dir: File, variant: String = "a", brokenStopTimes: Boolean = false): File {
        val files = mapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone,agency_lang\nsta,STA,https://example.org,Europe/Rome,IT\n",
            "stops.txt" to """
                stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station,platform_code,wheelchair_boarding
                "it:1:100:1:1","Bolzano, Stazione","46.4966","11.3580","","","1","1"
                "it:1:100:1:2","Bolzano, Stazione","46.4967","11.3581","","","2","1"
                "it:1:200:0:1","Merano, Stazione","46.6720","11.1500","","","",""
                "it:1:300:0:1","Bressanone, Stazione","46.7099","11.6497","","","",""
                """.trimIndent() + "\n",
            "routes.txt" to "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\nr201,sta,201,Bolzano - Merano,3,FFEE00\nrR,sta,R,,2,\n",
            "trips.txt" to "route_id,service_id,trip_id,shape_id,trip_headsign,direction_id\nr201,WK,t1,s1,Merano,0\nr201,WK,t2,s1,Merano,0\nrR,SUN,t3,,Bressanone,1\nr201,WK,t4$variant,s1,Merano,0\n",
            "stop_times.txt" to buildString {
                append("trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n")
                append("t1,08:00:00,08:00:00,it:1:100:1:1,1,0,0\nt1,08:40:00,08:40:00,it:1:200:0:1,2,0,0\n")
                append("t2,24:30:00,24:30:00,it:1:100:1:2,1,0,0\nt2,25:10:00,25:10:00,it:1:200:0:1,2,0,0\n")
                append("t3,09:00:00,09:00:00,it:1:100:1:2,1,0,0\nt3,09:30:00,09:30:00,it:1:300:0:1,2,0,0\n")
                append("t4$variant,10:00:00,10:00:00,it:1:100:1:1,1,0,0\nt4$variant,10:40:00,10:40:00,it:1:200:0:1,2,0,0\n")
                if (brokenStopTimes) repeat(5) { append("ghost,10:00:00,10:00:00,nowhere,$it,0,0\n") }
            },
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20260101,20261231\n",
            "calendar_dates.txt" to "service_id,date,exception_type\nSUN,20261004,1\nWK,20261005,2\n",
            "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\ns1,46.4966,11.3580,1\ns1,46.55,11.30,2\ns1,46.5501,11.3001,3\ns1,46.6720,11.1500,4\n",
            "translations.txt" to "table_name,field_name,language,translation,record_id\nstops,stop_name,de,\"Bozen, Bahnhof\",it:1:100:1:1\nstops,stop_name,de,\"Bozen, Bahnhof\",it:1:100:1:2\ntrips,trip_headsign,de,Meran,t1\n",
        )
        val file = File(dir, "gtfs-$variant-$brokenStopTimes.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            }
        }
        return file
    }
}

@RunWith(RobolectricTestRunner::class)
class ScheduleTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = ScheduleStore(context, TransitHttp(OkHttpClient()))
    private val schedule = GtfsSchedule(store)

    private fun at(date: String, time: String) = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(TransitZone).toInstant()

    @Test fun importAndQuery() = runTest {
        assertFalse(schedule.isAvailable())
        assertTrue(store.importFile(SyntheticGtfs.zip(context.cacheDir), mapOf("source" to "test")))
        assertTrue(schedule.isAvailable())
        val info = store.loadInfo()!!
        assertEquals(4, info.stops)
        assertEquals(20260101, info.firstDate)
        assertTrue(info.hasShapes)

        // Search finds the station by its German and Italian names, accent/case-insensitively.
        val stations = schedule.searchStops("bozen bahn", "de")
        assertEquals(listOf("it:1:100"), stations.map { it.id })
        assertEquals("Bozen, Bahnhof", stations.first().name)
        assertEquals("Bolzano, Stazione", schedule.searchStops("bolzano", "it").first().name)
        assertEquals(2, schedule.stationStops("it:1:100", "it").size)

        // Monday 5 Oct 2026 is removed for WK via calendar_dates; Sunday 4 Oct adds SUN.
        val sunday = schedule.scheduledDepartures(listOf("it:1:100"), at("2026-10-04", "07:00"), at("2026-10-05", "02:00"), false, "it", 50)
        assertEquals(listOf("t3"), sunday.map { it.tripId })
        assertEquals(TransportMode.TRAIN, sunday.first().mode)

        // Friday 2 Oct: t2 runs at 24:30 → 00:30 on Saturday; queried from Saturday it still appears.
        val afterMidnight = schedule.scheduledDepartures(listOf("it:1:100"), at("2026-10-03", "00:00"), at("2026-10-03", "01:00"), false, "de", 50)
        assertEquals(listOf("t2"), afterMidnight.map { it.tripId })
        assertEquals(LocalDate.of(2026, 10, 2), afterMidnight.first().serviceDate)
        assertEquals(LocalTime.of(0, 30), afterMidnight.first().scheduled.atZone(TransitZone).toLocalTime())

        val friday = schedule.scheduledDepartures(listOf("it:1:100"), at("2026-10-02", "07:00"), at("2026-10-02", "12:00"), false, "de", 50)
        assertEquals(listOf("t1", "t4a"), friday.map { it.tripId })
        assertEquals("Meran", friday.first().destination) // per-trip translation
        assertEquals("Meran", friday.last().destination) // headsign-text dictionary fallback
        assertEquals("Merano", schedule.scheduledDepartures(listOf("it:1:100"), at("2026-10-02", "07:00"), at("2026-10-02", "12:00"), false, "it", 50).last().destination)
        assertEquals(0xFFEE00L, friday.first().color)
        assertEquals(0x000000L, friday.first().textColor)

        // Terminating trips are not departures, but are arrivals.
        assertTrue(schedule.scheduledDepartures(listOf("it:1:200"), at("2026-10-02", "07:00"), at("2026-10-02", "12:00"), false, "it", 50).isEmpty())
        assertEquals(2, schedule.scheduledDepartures(listOf("it:1:200"), at("2026-10-02", "07:00"), at("2026-10-02", "12:00"), true, "it", 50).size)

        val nearby = schedule.nearbyStops(Point(46.4966, 11.3580), 500.0, "it")
        assertEquals("it:1:100", nearby.first().id)

        val lines = schedule.linesAtStops(listOf("it:1:100"))
        assertEquals(listOf("R", "201"), lines.map { it.name })
        val line201 = schedule.searchLines("201").single()
        val variants = schedule.lineVariants(line201, "it")
        assertEquals(1, variants.size)
        assertEquals(2, variants.first().stops.size)
        assertTrue(variants.first().shape.size in 3..4)
        assertEquals(2, schedule.variantTrips(line201, variants.first(), at("2026-10-02", "06:00"), at("2026-10-02", "12:00"), "it").size)

        val trip = schedule.trip("t2", LocalDate.of(2026, 10, 2), "de")!!
        assertEquals(2, trip.stops.size)
        assertEquals("Bozen, Bahnhof", trip.stops.first().stop.name)
        assertEquals(LocalTime.of(1, 10), trip.stops.last().scheduledArrival.atZone(TransitZone).toLocalTime())
    }

    @Test fun directionsArePlatformsLabelledByTheirNextStops() = runTest {
        assertTrue(store.importFile(SyntheticGtfs.zip(context.cacheDir), mapOf("source" to "test")))
        // Platform 1 only goes to Merano; platform 2 to Merano and Bressanone. The town prefix
        // ("Bolzano, ") would be dropped for stops in the same town; these are in other towns.
        val directions = schedule.stationDirections("it:1:100", "it")
        assertEquals(2, directions.size)
        val one = directions.single { "it:1:100:1:1" in it.platformIds }
        assertEquals(listOf("Merano, Stazione"), one.towards)
        val two = directions.single { "it:1:100:1:2" in it.platformIds }
        assertEquals(setOf("Merano, Stazione", "Bressanone, Stazione"), two.towards.toSet())
        // A stop with a single platform offers no choice.
        assertTrue(schedule.stationDirections("it:1:200", "it").isEmpty())
    }

    @Test fun reimportOfSameFeedIsSkippedAndNewFeedSwaps() = runTest {
        val zip = SyntheticGtfs.zip(context.cacheDir, "a")
        assertTrue(store.importFile(zip, emptyMap()))
        assertFalse(store.importFile(zip, emptyMap()))
        val firstName = store.activeName()
        assertTrue(store.importFile(SyntheticGtfs.zip(context.cacheDir, "b"), emptyMap()))
        assertTrue(store.activeName() != firstName)
        assertNotNull(schedule.trip("t4b", LocalDate.of(2026, 10, 2), "it"))
        assertNull(schedule.trip("t4a", LocalDate.of(2026, 10, 2), "it"))
    }

    @Test fun brokenFeedKeepsWorkingSchedule() = runTest {
        assertTrue(store.importFile(SyntheticGtfs.zip(context.cacheDir, "a"), emptyMap()))
        val active = store.activeName()
        try {
            store.importFile(SyntheticGtfs.zip(context.cacheDir, "c", brokenStopTimes = true), emptyMap())
            fail("Broken feed must be rejected")
        } catch (e: GtfsValidationException) {
            assertTrue(e.message!!.contains("Broken"))
        }
        assertEquals(active, store.activeName())
        assertNotNull(schedule.trip("t1", LocalDate.of(2026, 10, 2), "it"))
        val files = File(context.filesDir, "schedule").listFiles()!!.filter { it.name.startsWith("schedule-") && it.name.endsWith(".db") }
        assertEquals(1, files.size)
    }

    @Test fun noScheduleFallsBackToNetworkBoard() = runTest {
        val network = object : DepartureBoardDataSource {
            override suspend fun departures(stopGlobalId: String, at: Instant, arrivals: Boolean, language: String, limit: Int) =
                EfaXml.departures(fixture("efa_dm.xml"), at)
        }
        val realtime = RealtimeRepository(object : RealtimeTransitDataSource { override suspend fun fetch(feed: RealtimeFeed) = throw java.io.IOException() }, FakeUserDao())
        val repo = DepartureRepository(schedule, network, realtime)
        val (list, source) = repo.load("it:22021:468", false, Instant.parse("2026-10-04T18:00:00Z"), "de")
        assertEquals(BoardSource.NETWORK, source)
        assertEquals(15, list.size)
        val board = repo.merge(list, source, realtime.snapshot.value, emptyList(), false, Instant.parse("2026-10-04T18:00:00Z"))
        assertEquals(Freshness.LIVE, board.realtime)
    }

    @Test fun scheduledDeparturesMergeRealtimeAndAlerts() = runTest {
        store.importFile(SyntheticGtfs.zip(context.cacheDir), emptyMap())
        val now = at("2026-10-02", "07:55")
        val list = schedule.scheduledDepartures(listOf("it:1:100"), now, at("2026-10-02", "12:00"), false, "it", 50)
        val source = object : RealtimeTransitDataSource { override suspend fun fetch(feed: RealtimeFeed) = throw java.io.IOException() }
        val realtime = RealtimeRepository(source, FakeUserDao(), clock = { now })
        val repo = DepartureRepository(schedule, object : DepartureBoardDataSource { override suspend fun departures(stopGlobalId: String, at: Instant, arrivals: Boolean, language: String, limit: Int) = emptyList<Departure>() }, realtime) { now }

        // Without a realtime feed everything is scheduled.
        val scheduled = repo.merge(list, BoardSource.SCHEDULE, realtime.snapshot.value, emptyList(), false, now)
        assertEquals(Freshness.UNAVAILABLE, scheduled.realtime)
        assertTrue(scheduled.departures.all { it.predicted == null })

        val snapshot = realtime.snapshot.value.copy(
            updates = mapOf(
                "t1" to listOf(TripUpdate("t1", "r201", LocalDate.of(2026, 10, 2), now, ServiceState.NORMAL, listOf(StopUpdate("it:1:100:1:1", 1, null, null, 120, 120, ServiceState.NORMAL)))),
                "t4a" to listOf(TripUpdate("t4a", "r201", LocalDate.of(2026, 10, 2), now, ServiceState.CANCELLED, emptyList())),
            ),
            updatesFetchedAt = now,
        )
        val alert = ServiceAlert("a", mapOf("de" to "Umleitung"), emptyMap(), routes = setOf("r201"), observedAt = now)
        val board = repo.merge(list, BoardSource.SCHEDULE, snapshot, listOf(alert), false, now)
        assertEquals(Freshness.LIVE, board.realtime)
        assertEquals(120L, board.departures.first { it.tripId == "t1" }.delaySeconds)
        assertEquals(ServiceState.CANCELLED, board.departures.first { it.tripId == "t4a" }.state)
        assertTrue(board.departures.all { it.hasAlert })

        // Stale feed: predictions are not shown as live.
        val later = now.plusSeconds(600)
        val stale = repo.merge(list, BoardSource.SCHEDULE, snapshot, emptyList(), false, later)
        assertEquals(Freshness.STALE, stale.realtime)
        assertTrue(stale.departures.none { it.predicted != null })
    }

    @Test fun offlinePlaceSearchUsesSchedule() = runTest {
        store.importFile(SyntheticGtfs.zip(context.cacheDir), emptyMap())
        val offlineGeocoder = object : org.southtyrol.transit.model.GeocodingLocationDataSource {
            override suspend fun search(query: String, language: String): List<Place> = throw DataException(DataError.Offline)
            override suspend fun reverse(point: Point, language: String): Place? = null
        }
        val (places, error) = PlacesRepository(offlineGeocoder, schedule).search("Bozen", "de")
        assertEquals(DataError.Offline, error)
        assertEquals(PlaceType.STOP, places.single().type)
        assertEquals("it:1:100", places.single().stopGlobalId)
    }

    @Test fun cachedAlertsSurviveRestartAsStale() = runTest {
        val user = FakeUserDao()
        val now = Instant.parse("2026-10-04T18:00:00Z")
        val rtSource = object : RealtimeTransitDataSource { override suspend fun fetch(feed: RealtimeFeed) = fixture("service_alerts.pb") }
        val efa = object : org.southtyrol.transit.model.AlertDataSource { override suspend fun alerts(language: String) = EfaXml.alerts(fixture("efa_addinfo.xml"), now) }
        val online = AlertRepository(RealtimeRepository(rtSource, user) { now }, efa, user) { now }
        online.refresh("de")
        val live = online.state.first()
        assertEquals(Freshness.LIVE, live.freshness)
        assertTrue(live.alerts.any { it.source == AlertSource.EFA })
        assertTrue(live.alerts.any { it.source == AlertSource.GTFS_RT })

        // Next day, offline: the cached alerts are shown but marked stale.
        val tomorrow = now.plusSeconds(86_400)
        val offlineRt = object : RealtimeTransitDataSource { override suspend fun fetch(feed: RealtimeFeed) = throw java.io.IOException() }
        val offlineEfa = object : org.southtyrol.transit.model.AlertDataSource { override suspend fun alerts(language: String) = throw DataException(DataError.Offline) }
        val offline = AlertRepository(RealtimeRepository(offlineRt, user) { tomorrow }, offlineEfa, user) { tomorrow }
        offline.refresh("de")
        val state = offline.state.first()
        assertEquals(Freshness.STALE, state.freshness)
        assertTrue(state.alerts.isNotEmpty())
        assertNotNull(state.error)
    }

    @Test fun savedAndRecentItems() = runTest {
        val user = FakeUserDao()
        val saved = SavedRepository(user)
        val place = Place("66000468", "Bozen, Bahnhof", PlaceType.STOP, Point(46.49, 11.35), "Bozen", "it:22021:468")
        saved.savePlace(SavedRepository.HOME, "Home", place)
        assertEquals(place, saved.saved.first().single().place)
        saved.addRecentJourney(place, place.copy(id = "2", name = "Meran"))
        assertEquals("Meran", saved.recentJourneys().first().single().route!!.second.name)
        saved.remove(SavedKind.PLACE, SavedRepository.HOME)
        assertTrue(saved.saved.first().isEmpty())
        assertEquals(GtfsTime.dateKey(LocalDate.of(2026, 10, 4)), 20261004)
    }
}
