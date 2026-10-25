package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ParkingForecastTest {
    private val now = Instant.parse("2026-10-25T09:47:00Z")
    private fun at(s: String) = Instant.parse("2026-10-25T${s}:00Z")
    private val E = RawParkingForecast.Band.EXPECTED
    private val L = RawParkingForecast.Band.LOW
    private val H = RawParkingForecast.Band.HIGH

    // P03 Piazza Walther (403 spaces) on 25 Oct 2026; values are OCCUPIED spaces, issued at 09:05.
    private val raw = listOf(
        RawParkingForecast(30, E, 156.8, at("09:35")),
        RawParkingForecast(60, E, 172.7, at("10:05")), RawParkingForecast(60, L, 147.8, at("10:05")), RawParkingForecast(60, H, 188.2, at("10:05")),
        RawParkingForecast(90, E, 192.9, at("10:35")),
        RawParkingForecast(120, E, 204.0, at("11:05")),
        RawParkingForecast(150, E, 209.0, at("11:35")),
        RawParkingForecast(180, E, 212.2, at("12:05")),
        RawParkingForecast(210, E, 208.4, at("12:35")),
        RawParkingForecast(240, E, 203.8, at("13:05")),
    )

    @Test fun forecastsBecomeFreeSpacesOnHalfHours() {
        val f = ParkingForecasts.build(403, 292, emptyList(), raw, now)!!
        assertEquals(7, f.forecast.size)
        val first = f.forecast.first()
        assertEquals(at("10:00"), first.at)
        assertEquals(230, first.free)
        assertEquals(215, first.freeLow)
        assertEquals(255, first.freeHigh)
        assertEquals(at("13:00"), f.forecast.last().at)
    }

    @Test fun slotsAlreadyPastAreDropped() {
        val f = ParkingForecasts.build(403, 292, emptyList(), raw, now)!!
        assertEquals(true, f.forecast.all { it.at.isAfter(now) })
    }

    @Test fun staleForecastsShowNothing() {
        // Issued 09:05: still shown just before the next hourly forecast, gone once one is missed.
        assertEquals(true, ParkingForecasts.build(403, 292, emptyList(), raw, at("10:06")) != null)
        assertNull(ParkingForecasts.build(403, 292, emptyList(), raw, at("10:36")))
    }

    @Test fun placeholderCapacitiesShowNothing() {
        assertNull(ParkingForecasts.build(-1, 9999, emptyList(), raw, now))
        assertNull(ParkingForecasts.build(200, 9999, emptyList(), raw, now))
        assertNull(ParkingForecasts.build(403, null, emptyList(), raw, now))
        assertNull(ParkingForecasts.build(403, 292, emptyList(), emptyList(), now))
    }

    @Test fun historyIsTheLastValueOfEachHalfHourInTheLastTwoHours() {
        val history = listOf(
            at("07:10") to 290, // older than two hours before the current half hour
            at("07:31") to 302, at("07:58") to 300,
            at("08:10") to 306,
            at("08:40") to 307,
            at("09:05") to 295, at("09:29") to 291,
            at("09:40") to 289, // the current half hour: that is the "now" bar
        )
        val f = ParkingForecasts.build(403, 292, history, raw, now)!!
        assertEquals(listOf(at("07:30"), at("08:00"), at("08:30"), at("09:00")), f.past.map { it.at })
        assertEquals(listOf(300, 306, 307, 291), f.past.map { it.free })
        assertEquals(292, f.now.free)
    }

    @Test fun fillingUpNamesTheFewestFreeSpaces() {
        val f = ParkingForecasts.build(403, 292, emptyList(), raw, now)!!
        assertEquals(ParkingOutlook.FillingUp(191, at("12:00")), f.outlook)
    }

    @Test fun outlookRules() {
        val slots = { frees: List<Int> -> frees.mapIndexed { i, v -> ForecastSlot(at("10:00").plusSeconds(1800L * i), v, v, v) } }
        assertEquals(ParkingOutlook.LikelyFull(at("10:30")), ParkingForecasts.outlook(200, 18, slots(listOf(12, 8, 4))))
        assertEquals(ParkingOutlook.Emptying(180, at("11:00")), ParkingForecasts.outlook(300, 80, slots(listOf(120, 150, 180))))
        assertEquals(ParkingOutlook.Steady, ParkingForecasts.outlook(300, 100, slots(listOf(105, 95, 110))))
    }

    @Test fun roundsToTensFromFifty() {
        assertEquals(190, ParkingForecasts.roundFree(191))
        assertEquals(230, ParkingForecasts.roundFree(225))
        assertEquals(37, ParkingForecasts.roundFree(37))
    }
}
