package org.southtyrol.transit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.model.ParkingForecasts
import org.southtyrol.transit.model.ParkingOutlook
import org.southtyrol.transit.model.RawParkingForecast
import java.time.Instant

/** Real payloads for P03 Piazza Walther, captured 25 Oct 2026 10:03 UTC (docs/fixtures/odh_parking_*.json). */
class ParkingForecastParseTest {
    private val source = OdhParkingForecastSource(TransitHttp(okhttp3.OkHttpClient()))
    private val now = Instant.parse("2026-10-25T10:03:56Z")
    private val raw = source.parseForecast(fixture("odh_parking_forecast.json").decodeToString())
    private val history = source.parseHistory(fixture("odh_parking_free_history.json").decodeToString())

    @Test fun readsTheThreeBandsAndSkipsTheErrorValues() {
        assertEquals(24, raw.size)
        assertEquals(8, raw.count { it.band == RawParkingForecast.Band.LOW })
        assertEquals(8, raw.count { it.band == RawParkingForecast.Band.HIGH })
        assertEquals((30..240 step 30).toList(), raw.filter { it.band == RawParkingForecast.Band.EXPECTED }.map { it.horizon }.sorted())
    }

    @Test fun readsTheFreeHistory() {
        assertEquals(29, history.size)
        assertEquals(Instant.parse("2026-10-25T09:55:00Z") to 291, history.maxBy { it.first })
    }

    @Test fun buildsTheChartFromRealData() {
        val f = ParkingForecasts.build(403, 291, history, raw, now)!!
        assertEquals(listOf(307, 295, 291, 291), f.past.map { it.free })
        assertEquals(Instant.parse("2026-10-25T10:30:00Z"), f.forecast.first().at)
        assertTrue(f.forecast.all { it.freeLow <= it.free && it.free <= it.freeHigh })
        assertTrue(f.outlook is ParkingOutlook.FillingUp)
    }
}
