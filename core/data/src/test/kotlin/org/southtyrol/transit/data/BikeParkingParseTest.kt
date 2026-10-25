package org.southtyrol.transit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.MobilityKind
import java.time.Instant

/** Real payloads captured 25 Oct 2026 (docs/fixtures/odh_bikeparking_*.json, odh_ebike_*.json). */
class BikeParkingParseTest {
    private fun text(name: String) = fixture(name).decodeToString()
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private val sites = OdhBikeParkingSource(TransitHttp(okhttp3.OkHttpClient()))
        .parse(text("odh_bikeparking_stations.json"), text("odh_bikeparking_values.json"), now)
    private fun site(code: String) = sites.single { it.id == "BIKE_PARKING:$code" }

    private val ebikes = OdhEbikeSharingSource(TransitHttp(okhttp3.OkHttpClient()))
        .parse(text("odh_ebike_stations.json"), text("odh_ebike_values.json"), now)
    private fun ebike(prefix: String) = ebikes.single { it.name.startsWith(prefix) }

    @Test fun dropsTheOperatorsDemoSite() {
        assertEquals(4, sites.size)
        assertTrue(sites.none { it.id == "BIKE_PARKING:2174" })
        assertTrue(sites.all { it.kind == MobilityKind.BIKE_PARKING && it.bikeParking != null })
    }

    @Test fun individualBoxesWithTheirCountsAndFacilities() {
        val p = site("1931")
        assertEquals("Bressanone", p.name)
        assertEquals(4, p.available)
        assertEquals(18, p.capacity)
        assertEquals(Freshness.LIVE, p.freshness)
        val b = p.bikeParking!!
        assertFalse(b.depot)
        assertEquals(4, b.regularFree)
        assertEquals(0, b.electricFree)
        assertEquals(0, b.electricPlaces)
        assertTrue(b.covered && b.surveillance && b.bookingRequired)
        assertFalse(p.outOfService)
        assertEquals("Bahnhofplatz 3, Brixen", b.address("de"))
        assertEquals("Piazza stazione 3", b.address("it"))
        assertEquals("Piazza stazione/Bahnhofsplatz 3", b.address("fr"))
    }

    @Test fun sharedDepotsAndFullSites() {
        val depot = site("1930")
        assertTrue(depot.bikeParking!!.depot)
        assertEquals(147, depot.available)
        assertEquals(176, depot.capacity)
        assertEquals(0, site("2091").available)
        assertEquals("Sluderno", site("22").name)
    }

    @Test fun staleValuesAreNotCurrent() {
        val later = OdhBikeParkingSource(TransitHttp(okhttp3.OkHttpClient()))
            .parse(text("odh_bikeparking_stations.json"), text("odh_bikeparking_values.json"), now.plusSeconds(4 * 3600))
        assertTrue(later.all { it.freshness == Freshness.STALE })
    }

    @Test fun ebikeStationsCountBikesToRentAndFreeDocks() {
        val open = ebike("Fie allo Sciliar")
        assertEquals(MobilityKind.EBIKE_SHARING, open.kind)
        assertEquals("Fie allo Sciliar - Voels am Schlern", open.name)
        assertEquals("Ecospazio", open.provider)
        assertEquals(2, open.available)
        assertEquals(10, open.capacity)
        val e = open.ebikeSharing!!
        assertEquals(2, e.freeDocks)
        assertTrue(e.inService)
        assertFalse(open.outOfService)
    }

    @Test fun offlineAndOutOfServiceStationsAreClosed() {
        assertTrue(ebike("Renon Arena").outOfService)
        assertTrue(ebike("Nova Ponente Scuola").outOfService)
        assertEquals(1, ebike("Renon Arena").ebikeSharing!!.bikes)
    }

    @Test fun missingValuesKeepTheStations() {
        val bare = OdhEbikeSharingSource(TransitHttp(okhttp3.OkHttpClient())).parse(text("odh_ebike_stations.json"), """{"data":[]}""", now)
        assertEquals(3, bare.size)
        assertTrue(bare.all { it.available == null && it.freshness == Freshness.UNAVAILABLE && !it.outOfService })
        assertNull(bare.first().updatedAt)
    }
}
