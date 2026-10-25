package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BikeParkingTest {
    private val brixen = Point(46.7114, 11.6503)

    private fun site(id: String, at: Point, free: Int?, freshness: Freshness = Freshness.LIVE, outOfService: Boolean = false) = MobilityPoint(
        id = id, kind = MobilityKind.BIKE_PARKING, name = id, point = at, available = free, capacity = 10, updatedAt = null,
        provider = "STA", freshness = freshness,
        bikeParking = BikeParkingDetails(false, emptyMap(), free, 0, 0, covered = true, surveillance = true, bookingRequired = true, outOfService = outOfService),
    )

    private fun ebike(state: Boolean, freshness: Freshness) = MobilityPoint(
        id = "e", kind = MobilityKind.EBIKE_SHARING, name = "e", point = brixen, available = 1, capacity = 4, updatedAt = null,
        provider = "Ecospazio", freshness = freshness, ebikeSharing = EbikeSharingDetails(1, 3, 4, inService = state),
    )

    @Test fun nearestSiteWithSpaceForAFullOne() {
        val full = site("full", brixen, 0)
        val near = site("near", Point(46.7124, 11.6503), 3)
        val nearer = site("nearer", Point(46.7118, 11.6503), 1)
        val emptyToo = site("empty", Point(46.7115, 11.6503), 0)
        val closed = site("closed", Point(46.7116, 11.6503), 5, outOfService = true)
        val stale = site("stale", Point(46.7116, 11.6504), 5, freshness = Freshness.STALE)
        val (best, meters) = BikeParking.nearestWithSpace(full, listOf(full, near, nearer, emptyToo, closed, stale))!!
        assertEquals("nearer", best.id)
        assertTrue(meters in 30.0..60.0)
    }

    @Test fun noSuggestionWhenThereIsSpaceOrNothingNearby() {
        val far = site("far", Point(46.8, 11.6503), 4)
        assertNull(BikeParking.nearestWithSpace(site("open", brixen, 2), listOf(far)))
        assertNull(BikeParking.nearestWithSpace(site("full", brixen, 0), listOf(far)))
        assertNull(BikeParking.nearestWithSpace(site("unknown", brixen, 0, freshness = Freshness.STALE), listOf(site("x", brixen, 3))))
    }

    @Test fun outOfServiceOnlyFromACurrentReport() {
        assertTrue(site("a", brixen, 3, outOfService = true).outOfService)
        assertFalse(site("b", brixen, 3, freshness = Freshness.STALE, outOfService = true).outOfService)
        assertTrue(ebike(false, Freshness.LIVE).outOfService)
        assertFalse(ebike(true, Freshness.LIVE).outOfService)
        assertFalse(ebike(false, Freshness.UNAVAILABLE).outOfService)
    }

    @Test fun addressFallsBackToEnglishThenGerman() {
        val d = BikeParkingDetails(false, mapOf("de" to "Bahnhofplatz 3", "it" to "Piazza stazione 3"), null, null, 0, false, false, false, false)
        assertEquals("Bahnhofplatz 3", d.address("lld"))
        assertEquals("Piazza stazione 3", d.address("it"))
    }
}
