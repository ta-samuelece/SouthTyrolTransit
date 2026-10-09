package org.southtyrol.transit.data

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.RealtimeFeed
import org.southtyrol.transit.model.RealtimeTransitDataSource
import java.time.Instant
import java.util.concurrent.TimeUnit

class NetworkTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).callTimeout(3, TimeUnit.SECONDS).build()
    private val http = TransitHttp(client, maxAttempts = 3, baseBackoffMillis = 1, maxRetryAfterSeconds = 1)

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun url(path: String = "/") = server.url(path).toString()

    @Test fun retriesTransientErrors() = runTest {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(502).build())
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())
        assertEquals("ok", http.bytes(url(), "text/plain").decodeToString())
        assertEquals(3, server.requestCount)
    }

    @Test fun sendsUserAgentAndAccept() = runTest {
        server.enqueue(MockResponse.Builder().body("x").build())
        http.bytes(url(), "application/xml")
        val request = server.takeRequest()
        assertEquals("application/xml", request.headers["Accept"])
        assertTrue(request.headers["User-Agent"]!!.startsWith("SouthTyrolTransit"))
    }

    @Test fun doesNotRetryClientErrors() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        val error = runCatching { guarded { http.bytes(url(), "text/plain") } }.exceptionOrNull() as DataException
        assertEquals(DataError.NotFound, error.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun longRetryAfterIsThrottling() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
        val error = runCatching { guarded { http.bytes(url(), "text/plain") } }.exceptionOrNull() as DataException
        assertEquals(DataError.Throttled(120), error.error)
        assertEquals(1, server.requestCount)
    }

    @Test fun serverErrorAfterRetries() = runTest {
        repeat(3) { server.enqueue(MockResponse.Builder().code(500).build()) }
        val error = runCatching { guarded { http.bytes(url(), "text/plain") } }.exceptionOrNull() as DataException
        assertEquals(DataError.Server(500), error.error)
    }

    @Test fun timeoutIsReported() = runTest {
        repeat(3) { server.enqueue(MockResponse.Builder().bodyDelay(2, TimeUnit.SECONDS).body("late").build()) }
        val error = runCatching { guarded { http.bytes(url(), "text/plain") } }.exceptionOrNull() as DataException
        assertTrue(error.error == DataError.Timeout || error.error == DataError.Offline)
    }

    @Test fun disconnectIsOffline() = runTest {
        repeat(3) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        val error = runCatching { guarded { http.bytes(url(), "text/plain") } }.exceptionOrNull() as DataException
        assertEquals(DataError.Offline, error.error)
    }

    @Test fun efaOverHttp() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(fixture("efa_trip_ok.xml"))).build())
        val efa = EfaClient(http, url("/apb/"))
        val journeys = efa.plan(
            org.southtyrol.transit.model.Place("66000468", "Bozen", org.southtyrol.transit.model.PlaceType.STOP),
            org.southtyrol.transit.model.Place("x", "Meran"),
            org.southtyrol.transit.model.SearchOptions(), "de",
        )
        assertEquals(4, journeys.size)
        val request = server.takeRequest()
        assertEquals("/apb/XML_TRIP_REQUEST2", request.url.encodedPath)
        assertEquals("stop", request.url.queryParameter("type_origin"))
        assertEquals("LEASTTIME", request.url.queryParameter("routeType"))
    }

    @Test fun mapStopsWithoutTimetableComeFromTheCoordinateSearch() = runTest {
        server.enqueue(MockResponse.Builder().body(Buffer().write(fixture("efa_coord.xml"))).build())
        val center = org.southtyrol.transit.model.Point(46.4979, 11.3541)
        val places = EfaClient(http, url("/apb/")).nearbyStops(center, 1500, "de", max = 400)
        val request = server.takeRequest()
        assertEquals("/apb/XML_COORD_REQUEST", request.url.encodedPath)
        assertEquals("400", request.url.queryParameter("max"))
        // A small box around Waltherplatz: stops outside it (e.g. the station) are dropped.
        val box = org.southtyrol.transit.model.BoundingBox(46.4970, 11.3530, 46.4990, 11.3560)
        val stops = MapRepository.onlineStops(places, box)
        assertTrue(stops.isNotEmpty())
        assertTrue(stops.all { it.point in box && it.id == it.stationKey && it.id.startsWith("it:") })
        assertTrue(stops.none { it.name.contains("Bahnhof") })
    }

    @Test fun onlyConnectivityFailuresCountAsOffline() {
        assertEquals(DataError.Offline, java.net.UnknownHostException("x").toDataError())
        assertEquals(DataError.Parse, BadDataException("Download too large").toDataError())
        assertEquals(DataError.Parse, java.util.zip.ZipException("corrupt").toDataError())
        assertEquals(DataError.Unknown, TransferFailedException("Incomplete FTP download").toDataError())
        assertEquals(DataError.Parse, GtfsValidationException("Missing stops.txt").toDataError())
    }

    @Test fun largeDownloadClientHasNoCallTimeout() {
        val big = TransitHttp.forLargeDownloads(http)
        assertEquals(0, big.client.callTimeoutMillis)
        assertEquals(client.readTimeoutMillis, big.client.readTimeoutMillis)
    }

    @Test fun malformedEfaIsParseError() = runTest {
        server.enqueue(MockResponse.Builder().body("<html>maintenance</html>").build())
        try {
            EfaClient(http, url("/apb/")).search("Bozen", "de")
            fail()
        } catch (e: DataException) {
            assertEquals(DataError.Parse, e.error)
        }
    }

    @Test fun emptyEfaIsParseError() = runTest {
        server.enqueue(MockResponse.Builder().body("").build())
        try {
            EfaClient(http, url("/apb/")).search("Bozen", "de")
            fail()
        } catch (e: DataException) {
            assertEquals(DataError.Parse, e.error)
        }
    }

    @Test fun realtimeFailureIsIsolated() = runTest {
        val failing = object : RealtimeTransitDataSource {
            override suspend fun fetch(feed: RealtimeFeed): ByteArray = if (feed == RealtimeFeed.VEHICLE_POSITIONS) throw java.io.IOException("down") else fixture("trip_updates.pb")
        }
        val repo = RealtimeRepository(failing, FakeUserDao(), clock = { Instant.parse("2026-10-04T18:30:00Z") })
        repo.refresh(setOf(RealtimeFeed.TRIP_UPDATES, RealtimeFeed.VEHICLE_POSITIONS))
        assertTrue(repo.snapshot.value.updates.isNotEmpty())
        assertEquals(DataError.Offline, repo.status.value.getValue(RealtimeFeed.VEHICLE_POSITIONS).error)
        assertEquals(null, repo.status.value.getValue(RealtimeFeed.TRIP_UPDATES).error)
    }

    @Test fun retryAfterParsing() {
        assertEquals(30L, TransitHttp.parseRetryAfter("30"))
        assertEquals(60L, TransitHttp.parseRetryAfter("Sun, 04 Oct 2026 18:31:00 GMT", Instant.parse("2026-10-04T18:30:00Z")))
        assertEquals(null, TransitHttp.parseRetryAfter("soon"))
    }
}
