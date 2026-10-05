package org.southtyrol.transit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.southtyrol.transit.data.ScheduleStore
import org.southtyrol.transit.data.TransitHttp
import java.io.File

/**
 * Developer helper: imports a GTFS zip pushed to the device, e.g.
 * `adb push google_transit_shp.zip /data/local/tmp/sta.zip` then run with
 * `-e feed /data/local/tmp/sta.zip`. Skipped when no feed argument is given.
 */
@RunWith(AndroidJUnit4::class)
class ImportFeedOnDevice {
    @Test fun importPushedFeed() = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("feed")
        assumeTrue(path != null && File(path).canRead())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = System.currentTimeMillis()
        val imported = ScheduleStore(context, TransitHttp(OkHttpClient())).importFile(File(path!!), mapOf("source" to "adb"))
        android.util.Log.i("ImportFeedOnDevice", "imported=$imported in ${System.currentTimeMillis() - started} ms")
        assertTrue(imported || File(context.filesDir, "schedule/active").exists())
    }
}
