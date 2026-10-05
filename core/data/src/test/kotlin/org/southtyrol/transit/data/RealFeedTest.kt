package org.southtyrol.transit.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.southtyrol.transit.model.TransitZone
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * Imports the real STA feed when `-Dgtfs.real=<path to zip>` is given (skipped otherwise), then
 * joins it with captured GTFS-RT fixtures to check the identifier formats line up.
 */
@RunWith(RobolectricTestRunner::class)
class RealFeedTest {
    @Test fun importRealFeed() = runTest(timeout = 30.minutes) {
        val path = System.getProperty("gtfs.real")?.takeIf { it.isNotBlank() }
        assumeTrue(path != null && File(path).exists())
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ScheduleStore(context, TransitHttp(OkHttpClient()))
        val started = System.nanoTime()
        assertTrue(store.importFile(File(path!!), mapOf("source" to "local")))
        val seconds = (System.nanoTime() - started) / 1e9
        val info = store.loadInfo()!!
        val dbFile = File(File(context.filesDir, "schedule"), store.activeName()!!)
        println("REALFEED import=${"%.1f".format(seconds)}s stops=${info.stops} trips=${info.trips} size=${dbFile.length() / 1_000_000}MB dates=${info.firstDate}..${info.lastDate}")

        val schedule = GtfsSchedule(store)
        val bozen = schedule.searchStops("Bozen Bahnhof", "de").first()
        println("REALFEED search=${bozen.id} ${bozen.name}")
        val now = LocalDate.of(2026, 10, 5).atTime(8, 0).atZone(TransitZone).toInstant()
        val t0 = System.nanoTime()
        val board = schedule.scheduledDepartures(listOf(bozen.id), now, now.plusSeconds(3 * 3600), false, "de", 80)
        println("REALFEED board=${board.size} in ${(System.nanoTime() - t0) / 1_000_000}ms first=${board.take(5).map { "${it.line}→${it.destination}" }}")
        assertTrue(board.size > 10)

        val updates = GtfsRealtimeParser.parse(fixture("trip_updates.pb"), now).updates
        val matched = updates.count { u -> schedule.trip(u.tripId, u.date ?: LocalDate.of(2026, 10, 4), "it") != null }
        println("REALFEED realtime trip match=$matched/${updates.size}")
        assertTrue(matched * 2 >= updates.size)
        val lines = schedule.searchLines("201")
        val variants = schedule.lineVariants(lines.first(), "de")
        println("REALFEED line201 variants=${variants.size} stops=${variants.first().stops.size} shape=${variants.first().shape.size}")
    }
}

/** Full download through the FTP fallback and import; only with -PliveTests=true. */
@RunWith(RobolectricTestRunner::class)
class LiveDownloadTest {
    @Test fun ftpFallbackDownloadsAndImports() = runTest(timeout = 30.minutes) {
        assumeTrue(System.getProperty("live.tests") == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = ScheduleStore(context, TransitHttp(OkHttpClient()), sources = listOf(GtfsSource("dead", "https://gtfs.api.opendatahub.com/v1/dataset/does-not-exist/raw"), GtfsSources.StaFtp))
        assertTrue(store.refresh(allowFtp = true))
        println("LIVE imported ${store.loadInfo()}")
    }
}
