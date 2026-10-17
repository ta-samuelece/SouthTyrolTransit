package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class VehicleHeadingsTest {
    private val here = Point(46.5000, 11.3500)
    private val north = Point(46.5100, 11.3500)
    private val east = Point(46.5000, 11.3650)
    private val south = Point(46.4900, 11.3500)

    private fun vehicle(stopId: String?, status: String? = "IN_TRANSIT_TO", bearing: Float? = null, point: Point = here) =
        Vehicle("1", point, "t", "r", bearing, Instant.EPOCH, stopId = stopId, currentStatus = status)

    @Test fun bearingPointsAtTheCompass() {
        assertEquals(0f, Geo.bearing(here, north), 0.5f)
        assertEquals(90f, Geo.bearing(here, east), 0.5f)
        assertEquals(180f, Geo.bearing(here, south), 0.5f)
    }

    @Test fun headsForTheNamedStop() {
        assertEquals(90f, VehicleHeadings.bearing(vehicle("e"), east, null)!!, 0.5f)
    }

    @Test fun feedBearingWins() {
        assertEquals(42f, VehicleHeadings.bearing(vehicle("e", bearing = 42f), east, null)!!, 0f)
    }

    @Test fun stoppedAtAStopAimsAtTheNextCall() {
        val calls = listOf("s" to south, "h" to here, "n" to north)
        val v = vehicle("h", status = "STOPPED_AT")
        assertEquals(true, VehicleHeadings.needsTrip(v, here))
        assertEquals(0f, VehicleHeadings.bearing(v, here, calls)!!, 0.5f)
    }

    @Test fun rightNextToTheNamedStopAimsPastIt() {
        val calls = listOf("s" to south, "h" to here, "e" to east)
        assertEquals(90f, VehicleHeadings.bearing(vehicle("h"), here, calls)!!, 0.5f)
    }

    @Test fun withoutAStopUsesTheCallAfterTheNearest() {
        val calls = listOf("s" to south, "h" to here, "n" to north)
        assertEquals(0f, VehicleHeadings.bearing(vehicle(null), null, calls)!!, 0.5f)
    }

    @Test fun endOfRunOrNothingKnownHasNoHeading() {
        assertNull(VehicleHeadings.bearing(vehicle("h", status = "STOPPED_AT"), here, listOf("s" to south, "h" to here)))
        assertNull(VehicleHeadings.bearing(vehicle(null), null, null))
    }
}
