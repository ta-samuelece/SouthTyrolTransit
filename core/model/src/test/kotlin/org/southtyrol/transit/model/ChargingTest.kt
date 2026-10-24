package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChargingTest {
    private val ccs400 = ChargingConnector(ConnectorType.CCS, 400.0, dc = true, cable = true)
    private val t2Socket = ChargingConnector(ConnectorType.TYPE_2, 7.4, dc = false, cable = false)

    @Test fun statusesFromEveryVocabulary() {
        assertEquals(ChargingStatus.FREE, Charging.status("AVAILABLE"))
        assertEquals(ChargingStatus.FREE, Charging.status("Available"))
        assertEquals(ChargingStatus.IN_USE, Charging.status("CHARGING"))
        assertEquals(ChargingStatus.OUT_OF_ORDER, Charging.status("INOPERATIVE"))
        assertEquals(ChargingStatus.OUT_OF_ORDER, Charging.status("OutOfService"))
        assertEquals(ChargingStatus.UNKNOWN, Charging.status("Unknown"))
        assertEquals(ChargingStatus.UNKNOWN, Charging.status("1"))
        assertEquals(ChargingStatus.UNKNOWN, Charging.status(null))
        assertNull(Charging.status("REMOVED"))
    }

    @Test fun connectorTypesFromStandardsAndOutletCodes() {
        assertEquals(ConnectorType.CCS, Charging.connectorType("IEC_62196_T2_COMBO"))
        assertEquals(ConnectorType.TYPE_2, Charging.connectorType("IEC_62196_T2"))
        assertEquals(ConnectorType.TYPE_2, Charging.connectorType("Type2Mennekes"))
        assertEquals(ConnectorType.CHADEMO, Charging.connectorType("CHADEMO"))
        assertEquals(ConnectorType.DOMESTIC, Charging.connectorType("DOMESTIC_F"))
        assertEquals(ConnectorType.OTHER, Charging.connectorType(null))
    }

    @Test fun hydrogenIsNotACharger() {
        assertTrue(Charging.isHydrogen("700 bar small vehicles"))
        assertFalse(Charging.isHydrogen("Type2Mennekes"))
    }

    @Test fun fastMeansDcOfFiftyKilowattsOrMore() {
        assertTrue(Charging.isFast(ccs400))
        assertFalse(Charging.isFast(ChargingConnector(ConnectorType.CCS, 24.0, dc = true, cable = true)))
        assertFalse(Charging.isFast(ChargingConnector(ConnectorType.TYPE_2, 50.0, dc = false, cable = true)))
        assertFalse(Charging.isFast(null))
    }

    @Test fun groupsByConnectorMostPowerfulFirst() {
        val points = listOf(
            ChargingPoint("1", t2Socket, ChargingStatus.FREE),
            ChargingPoint("2", ccs400, ChargingStatus.IN_USE),
            ChargingPoint("3", null, ChargingStatus.UNKNOWN),
            ChargingPoint("4", ccs400, ChargingStatus.FREE),
            ChargingPoint("5", t2Socket, ChargingStatus.OUT_OF_ORDER),
        )
        val groups = Charging.groups(points)
        assertEquals(listOf(ccs400, t2Socket, null), groups.map { it.connector })
        assertEquals(1, groups[0].free)
        assertEquals(1, groups[1].outOfOrder)
        assertFalse(groups[2].hasStatus)
    }

    @Test fun stationSummary() {
        val details = ChargingDetails(null, null, false, false, false, null, listOf(
            ChargingPoint("1", ccs400, ChargingStatus.OUT_OF_ORDER),
            ChargingPoint("2", t2Socket, ChargingStatus.OUT_OF_ORDER),
        ))
        assertTrue(details.outOfService)
        assertTrue(details.fast)
        assertEquals(0, details.free)
        assertFalse(details.copy(points = listOf(ChargingPoint("1", null, ChargingStatus.UNKNOWN))).hasStatus)
    }

    @Test fun codeNamesFallBackToTheAddress() {
        assertEquals("Hotel Luna", Charging.title("Hotel Luna", "Via Piave, 2"))
        assertEquals("Piazza della Vittoria 48", Charging.title("BZ_P_VITTORIA", "Piazza della Vittoria 48"))
        assertNull(Charging.title("IT*BEC*EH000137*1", null))
        assertEquals("Bozen", Charging.title("Bozen", null))
    }

    @Test fun upperCaseCitiesAreTitleCased() {
        assertEquals("Bolzano - Bozen", Charging.cityName("BOLZANO - BOZEN"))
        assertEquals("Aldino - Aldein", Charging.cityName("ALDINO - ALDEIN"))
        assertEquals("Bozen", Charging.cityName("Bozen"))
        assertNull(Charging.cityName(" "))
    }

    @Test fun reportedStatusesStayCurrentForAWeek() {
        val now = Instant.parse("2026-10-24T20:00:00Z")
        assertEquals(Freshness.LIVE, FreshnessPolicy.charging(Instant.parse("2026-10-19T08:00:00Z"), now))
        assertEquals(Freshness.STALE, FreshnessPolicy.charging(Instant.parse("2026-10-16T08:00:00Z"), now))
        assertEquals(Freshness.UNAVAILABLE, FreshnessPolicy.charging(null, now))
    }
}
