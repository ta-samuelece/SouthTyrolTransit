package org.southtyrol.transit.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class TimetableCheckTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val server = MockWebServer()
    private val store by lazy {
        ScheduleStore(context, TransitHttp(OkHttpClient(), baseBackoffMillis = 1), listOf(GtfsSource("opendatahub", server.url("/feed").toString())))
    }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun info(lastDate: Int) = ScheduleInfo(Instant.EPOCH, "opendatahub", "h", 20260101, lastDate, 1, 1, false, GtfsImporter.VERSION)

    @Test fun missingExpiredAndOutdatedTimetablesNeedTheUser() {
        assertEquals(TimetableNeed.MISSING, TimetableCheck.need(null, 20261030, null))
        assertEquals(TimetableNeed.EXPIRED, TimetableCheck.need(info(20261029), 20261030, null))
        assertEquals(TimetableNeed.EXPIRED, TimetableCheck.need(info(20261029), 20261030, true))
        assertEquals(TimetableNeed.UPDATE_AVAILABLE, TimetableCheck.need(info(20261231), 20261030, true))
        // The last service day itself is still valid; a server with nothing newer leaves nothing to do.
        assertNull(TimetableCheck.need(info(20261030), 20261030, null))
        assertNull(TimetableCheck.need(info(20261029), 20261030, false))
        assertNull(TimetableCheck.need(info(20261231), 20261030, null))
    }

    @Test fun serverIsAskedWithTheActiveFeedsValidators() = runTest {
        assertNull(store.updateAvailable())
        assertTrue(store.importFile(SyntheticGtfs.zip(context.cacheDir, "a"), mapOf("source" to "opendatahub", "etag" to "\"a\"", "lastModified" to "")))

        server.enqueue(MockResponse.Builder().code(304).build())
        assertFalse(store.updateAvailable()!!)
        assertEquals("\"a\"", server.takeRequest().headers["If-None-Match"])

        // A server ignoring the condition still names its feed: the same one, then a newer one.
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"a\"").body("zip").build())
        assertFalse(store.updateAvailable()!!)
        server.enqueue(MockResponse.Builder().code(200).addHeader("ETag", "\"b\"").body("zip").build())
        assertTrue(store.updateAvailable()!!)
        // Nothing to compare with, or no answer: unknown.
        server.enqueue(MockResponse.Builder().code(200).body("zip").build())
        assertNull(store.updateAvailable())
        server.enqueue(MockResponse.Builder().code(404).build())
        assertNull(store.updateAvailable())
    }

    @Test fun feedFromAnotherSourceIsNotCompared() = runTest {
        assertTrue(store.importFile(SyntheticGtfs.zip(context.cacheDir, "a"), mapOf("source" to "sta-ftp", "lastModified" to "x")))
        assertNull(store.updateAvailable())
        assertEquals(0, server.requestCount)
    }
}
