package org.southtyrol.transit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.southtyrol.transit.data.EfaXml
import java.time.Instant

/** The EFA parsers must behave identically with Android's XML stack and the JVM's. */
@RunWith(AndroidJUnit4::class)
class EfaOnDeviceTest {
    private fun asset(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }

    @Test fun journeys() {
        assertEquals(4, EfaXml.journeys(asset("efa_trip_ok.xml")).size)
        assertEquals(4, EfaXml.journeys(asset("efa_trip_walk.xml")).size)
    }

    @Test fun departuresAlertsPlaces() {
        assertEquals(15, EfaXml.departures(asset("efa_dm.xml"), Instant.now()).size)
        assertTrue(EfaXml.alerts(asset("efa_addinfo.xml"), Instant.parse("2026-10-04T18:00:00Z")).size > 30)
        assertEquals(1, EfaXml.places(asset("efa_stopfinder_list.xml")).size)
    }
}
