package org.southtyrol.transit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.model.ChargingStatus
import org.southtyrol.transit.model.ConnectorType
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.MobilityKind
import java.time.Instant

/** Real payloads from central Bolzano, captured 24 Oct 2026 (docs/fixtures/odh_charging_*.json). */
class ChargingParseTest {
    private val source = OdhChargingSource(TransitHttp(okhttp3.OkHttpClient()))
    private fun text(name: String) = fixture(name).decodeToString()

    // Four days after the capture: the status of 20 Oct is then more than a week old.
    private val now = Instant.parse("2026-10-28T12:00:00Z")
    private val points = source.parse(text("odh_charging_stations.json"), text("odh_charging_plugs.json"), text("odh_charging_status.json"), now)
    private fun station(code: String) = points.single { it.id == "CHARGING:$code" }

    @Test fun dropsHydrogenAndPlannedStations() {
        assertEquals(6, points.size)
        assertTrue(points.none { it.id == "CHARGING:326" })
        assertTrue(points.none { it.name.startsWith("Erbacherhof") })
        assertTrue(points.all { it.kind == MobilityKind.CHARGING && it.charging != null })
    }

    @Test fun joinsPlugsAndStatusesToTheirStation() {
        val p = station("Neogy-AMPECO:77436")
        assertEquals("Piazza della Vittoria - Siegesplatz 48", p.name)
        assertEquals("Bolzano - Bozen", p.charging!!.city)
        assertEquals("Neogy", p.provider)
        assertEquals(1, p.available)
        assertEquals(3, p.capacity)
        assertEquals(Freshness.LIVE, p.freshness)
        assertEquals(Instant.parse("2026-10-24T20:07:00Z").epochSecond / 60, p.updatedAt!!.epochSecond / 60)
        assertTrue(p.charging!!.openAllDay)
        assertTrue(p.charging!!.rfid)
        assertFalse(p.charging!!.fast)
    }

    @Test fun describesConnectorsAndFastCharging() {
        val c = station("Neogy-AMPECO:77594").charging!!
        assertTrue(c.fast)
        val ccs = c.points.single { it.connector?.type == ConnectorType.CCS }.connector!!
        assertEquals(100.0, ccs.powerKw!!, 0.01)
        assertEquals(true, ccs.cable)
        assertEquals(1, c.points.count { it.status == ChargingStatus.OUT_OF_ORDER })
    }

    @Test fun statusesOlderThanAWeekAreUnknown() {
        val p = station("Neogy-AMPECO:78231")
        assertEquals(1, p.charging!!.points.count { it.status == ChargingStatus.UNKNOWN })
        assertEquals(2, p.available)
    }

    @Test fun olderOutletFeedsAndUndocumentedStatuses() {
        val p = station("IT*ETN*E00411")
        assertEquals("Hotel Luna", p.name)
        assertEquals("Route220", p.provider)
        assertNull(p.available)
        val c = p.charging!!
        assertTrue(c.privateWithPublicAccess)
        assertTrue(c.openAllDay)
        assertEquals("https://evway.net/app/", c.paymentUrl)
        val connector = c.points.single().connector!!
        assertEquals(ConnectorType.TYPE_2, connector.type)
        assertEquals(7.4, connector.powerKw!!, 0.01)
        assertEquals(false, connector.cable)
        assertFalse(connector.dc)
    }

    @Test fun stationsWithoutDetailsStillShow() {
        val coded = station("Be Charge srl:IT*BEC*EH000137*1")
        assertEquals("", coded.name)
        assertEquals("Be Charge srl", coded.provider)
        assertEquals(Freshness.UNAVAILABLE, coded.freshness)
        assertNull(coded.charging!!.points.single().connector)

        val unlisted = station("Neogy-AMPECO:78284")
        assertEquals(3, unlisted.charging!!.points.size)
        assertNull(unlisted.available)
    }

    @Test fun missingStatusCallKeepsTheStations() {
        val withoutStatus = source.parse(text("odh_charging_stations.json"), text("odh_charging_plugs.json"), null, now)
        assertEquals(6, withoutStatus.size)
        assertTrue(withoutStatus.all { it.available == null })
    }
}
