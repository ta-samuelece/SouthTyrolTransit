package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class GtfsTimeTest {
    @Test fun parsesTimesBeyondMidnight() {
        assertEquals(90061, GtfsTime.seconds("25:01:01"))
        assertEquals(7 * 3600 + 8 * 60, GtfsTime.seconds("7:08:00"))
        assertEquals("25:01:01", GtfsTime.format(90061))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidMinutes() { GtfsTime.seconds("12:60:00") }
    @Test(expected = IllegalArgumentException::class) fun rejectsMissingSeconds() { GtfsTime.seconds("12:00") }

    @Test fun timeAfterMidnightBelongsToPreviousServiceDay() {
        val date = LocalDate.of(2026, 10, 4)
        val at = GtfsTime.instant(date, GtfsTime.seconds("24:30:00")).atZone(TransitZone)
        assertEquals(LocalDate.of(2026, 10, 5), at.toLocalDate())
        assertEquals(LocalTime.of(0, 30), at.toLocalTime())
    }

    @Test fun dstSpringForwardUsesNoonAnchor() {
        // 29 March 2026: clocks jump 02:00 -> 03:00 in Europe/Rome. The service day is 23 hours long.
        val date = LocalDate.of(2026, 3, 29)
        assertEquals(date.atTime(12, 0).atZone(TransitZone).toInstant(), GtfsTime.instant(date, 43200))
        // "08:00:00" on that day is 08:00 local wall clock time.
        assertEquals(LocalTime.of(8, 0), GtfsTime.instant(date, 8 * 3600).atZone(TransitZone).toLocalTime())
        // Times before the jump are offset by one hour relative to midnight (GTFS spec behaviour).
        assertEquals(LocalTime.of(0, 0).minusHours(1), GtfsTime.instant(date, 0).atZone(TransitZone).toLocalTime())
    }

    @Test fun dstFallBackDayIsTwentyFiveHoursLong() {
        val date = LocalDate.of(2026, 10, 25)
        // 25 October 2026: clocks go back 03:00 -> 02:00. The noon anchor makes "00:00:00" 01:00 local.
        assertEquals(LocalTime.of(1, 0), GtfsTime.instant(date, 0).atZone(TransitZone).toLocalTime())
        assertEquals(LocalTime.of(18, 0), GtfsTime.instant(date, 18 * 3600).atZone(TransitZone).toLocalTime())
        val midnight = date.atStartOfDay(TransitZone).toInstant()
        val nextMidnight = date.plusDays(1).atStartOfDay(TransitZone).toInstant()
        assertEquals(Duration.ofHours(25), Duration.between(midnight, nextMidnight))
    }

    @Test fun transitZoneIsIndependentOfDeviceZone() {
        val date = LocalDate.of(2026, 7, 1)
        val inRome = GtfsTime.instant(date, 8 * 3600)
        val inNewYork = GtfsTime.instant(date, 8 * 3600, TransitZone)
        assertEquals(inRome, inNewYork)
        assertEquals(LocalTime.of(2, 0), inRome.atZone(ZoneId.of("America/New_York")).toLocalTime())
    }

    @Test fun secondsSinceOriginRoundTrips() {
        val date = LocalDate.of(2026, 10, 25)
        assertEquals(30000L, GtfsTime.secondsSinceOrigin(date, GtfsTime.instant(date, 30000)))
        assertEquals(date, GtfsTime.fromDateKey(GtfsTime.dateKey(date)))
    }
}

class CalendarTest {
    private val d = LocalDate.of(2026, 10, 4) // Sunday

    @Test fun calendarExceptionWins() {
        val rule = CalendarRule("a", d, d.plusDays(5), setOf(DayOfWeek.SUNDAY))
        assertTrue(rule.active(d))
        assertFalse(rule.active(d, mapOf(d to false)))
        assertTrue(rule.active(d.plusDays(1), mapOf(d.plusDays(1) to true)))
        assertFalse(rule.active(d.plusDays(7)))
    }

    @Test fun activeServicesCombinesCalendarAndDates() {
        val rules = listOf(
            CalendarRule("weekday", d.minusDays(30), d.plusDays(30), CalendarRule.days(0b0011111)),
            CalendarRule("sunday", d.minusDays(30), d.plusDays(30), setOf(DayOfWeek.SUNDAY)),
        )
        assertEquals(setOf("sunday"), ServiceCalendar.activeServices(d, rules, emptyMap()))
        assertEquals(setOf("special"), ServiceCalendar.activeServices(d, rules, mapOf("sunday" to false, "special" to true)))
        assertEquals(setOf("weekday", "sunday"), ServiceCalendar.activeServices(d, rules, mapOf("weekday" to true)))
        assertEquals(0b1000000, CalendarRule.mask(setOf(DayOfWeek.SUNDAY)))
    }
}

class RealtimeMergeTest {
    private val now = Instant.parse("2026-10-04T12:00:00Z")
    private val base = Departure("trip", "stop", 2, "r", "10", TransportMode.BUS, "Merano", now)
    private fun stop(seq: Int = 2, delay: Int? = -60, state: ServiceState = ServiceState.NORMAL, noData: Boolean = false) =
        StopUpdate("stop$seq", seq, null, null, delay, delay, state, noData)
    private fun update(age: Long = 0, state: ServiceState = ServiceState.NORMAL, stops: List<StopUpdate> = listOf(stop())) =
        TripUpdate("trip", null, base.serviceDate, now.minusSeconds(age), state, stops)

    @Test fun earlyRunning() {
        val d = RealtimeMerge.departure(base, update(), now)
        assertEquals(now.minusSeconds(60), d.predicted)
        assertEquals(-60L, d.delaySeconds)
        assertEquals(Freshness.LIVE, d.freshness)
    }

    @Test fun delayPropagatesDownstream() {
        val d = RealtimeMerge.departure(base, update(stops = listOf(stop(seq = 1, delay = 240))), now)
        assertEquals(240L, d.delaySeconds)
    }

    @Test fun delayDoesNotPropagateUpstream() {
        val d = RealtimeMerge.departure(base, update(stops = listOf(stop(seq = 5, delay = 240))), now)
        assertNull(d.predicted)
        assertEquals(Freshness.SCHEDULED, d.freshness)
    }

    @Test fun noDataBreaksPropagation() {
        val d = RealtimeMerge.departure(base, update(stops = listOf(stop(seq = 1, delay = 240, noData = true))), now)
        assertNull(d.predicted)
    }

    @Test fun absoluteTimeWins() {
        val u = update(stops = listOf(StopUpdate("stop", 2, null, now.plusSeconds(90), null, 30, ServiceState.NORMAL)))
        assertEquals(now.plusSeconds(90), RealtimeMerge.departure(base, u, now).predicted)
    }

    @Test fun cancellation() {
        assertEquals(ServiceState.CANCELLED, RealtimeMerge.departure(base, update(state = ServiceState.CANCELLED), now).state)
    }

    @Test fun skippedStop() {
        assertEquals(ServiceState.SKIPPED, RealtimeMerge.departure(base, update(stops = listOf(stop(state = ServiceState.SKIPPED))), now).state)
    }

    @Test fun staleUpdateIsNotUsedForPredictions() {
        val d = RealtimeMerge.departure(base, update(age = FreshnessPolicy.TRIP_UPDATE_MAX_AGE + 1), now)
        assertNull(d.predicted)
        assertEquals(Freshness.STALE, d.freshness)
    }

    @Test fun differentServiceDateDoesNotJoin() {
        assertEquals(base, RealtimeMerge.departure(base, update().copy(date = base.serviceDate.minusDays(1)), now))
    }

    @Test fun tripStopsMerge() {
        val stops = (1..3).map { TripStop(Stop("s$it", "S$it", Point(46.0, 11.0)), it, now.plusSeconds(it * 60L), now.plusSeconds(it * 60L)) }
        val (merged, freshness) = RealtimeMerge.tripStops(stops, update(stops = listOf(stop(seq = 2, delay = 120))), base.serviceDate, now)
        assertEquals(Freshness.LIVE, freshness)
        assertNull(merged[0].predictedDeparture)
        assertEquals(now.plusSeconds(240), merged[1].predictedDeparture)
        assertEquals(now.plusSeconds(300), merged[2].predictedDeparture)
    }

    @Test fun vehicleFreshnessPolicy() {
        assertEquals(Freshness.LIVE, FreshnessPolicy.vehicle(now.minusSeconds(30), now))
        assertEquals(Freshness.STALE, FreshnessPolicy.vehicle(now.minusSeconds(300), now))
        assertEquals(Freshness.UNAVAILABLE, FreshnessPolicy.vehicle(now.minusSeconds(3600), now))
        assertEquals(Freshness.UNAVAILABLE, FreshnessPolicy.feed(null, now))
    }
}

class TextTest {
    @Test fun languageFallback() {
        assertEquals("Hallo", mapOf("de" to "Hallo").localized("lld"))
        assertEquals("Ciao", mapOf("it" to "Ciao", "en" to "Hello").localized("it-IT"))
        assertEquals("Hello", mapOf("it" to "Ciao", "en" to "Hello").localized("fr"))
        assertEquals("Bun di", mapOf("de" to "Hallo", "lld" to "Bun di").localized("lld"))
        assertEquals("Bun di", mapOf("de" to "Hallo", "ld1" to "Bun di").localized("lld"))
        assertEquals("Hallo", mapOf("de" to "Hallo", "en" to " ").localized("en"))
        assertEquals("", emptyMap<String, String>().localized("en"))
    }

    @Test fun efaLanguages() {
        assertEquals("de", Languages.efa("de-AT"))
        assertEquals("it", Languages.efa("lld"))
        assertEquals("en", Languages.efa("fr"))
        assertEquals("de", Languages.efaStopFinder("en"))
        assertEquals("it", Languages.efaStopFinder("lld"))
    }

    @Test fun normalizedKeys() {
        assertEquals("bozen sud messe", TextNormalizer.key("Bozen Süd - Messe"))
        assertEquals("Ein Dienst.\nZweite & Zeile", TextNormalizer.stripHtml("<p>Ein Dienst.</p><p>Zweite &amp; Zeile</p>"))
    }

    @Test fun alertDeduplication() {
        val t = Instant.parse("2026-10-04T10:00:00Z")
        val rt = ServiceAlert("1", mapOf("de" to "Baustelle Linie 201"), mapOf(), routes = setOf("r1"), observedAt = t)
        val efa = ServiceAlert("e1", mapOf("de" to "Baustelle Linie 201", "it" to "Cantiere linea 201"), mapOf("de" to "Umleitung"), lineNames = setOf("201"), observedAt = t, source = AlertSource.EFA)
        val other = ServiceAlert("2", mapOf("de" to "Streik"), mapOf(), observedAt = t)
        val merged = AlertDeduplicator.merge(listOf(efa, rt, other))
        assertEquals(2, merged.size)
        val m = merged.first { it.id == "1" }
        assertEquals("Cantiere linea 201", m.headers["it"])
        assertEquals("Umleitung", m.descriptions["de"])
        assertEquals(setOf("r1"), m.routes)
        assertEquals(setOf("201"), m.lineNames)
    }

    @Test fun alertActivity() {
        val t = Instant.parse("2026-10-04T10:00:00Z")
        val a = ServiceAlert("1", emptyMap(), emptyMap(), periods = listOf(t to t.plusSeconds(3600)), observedAt = t)
        assertTrue(a.active(t.plusSeconds(10)))
        assertFalse(a.active(t.plusSeconds(3600)))
        assertTrue(a.upcoming(t.minusSeconds(1)))
        assertTrue(a.expired(t.plusSeconds(4000)))
        assertTrue(ServiceAlert("2", emptyMap(), emptyMap(), observedAt = t).active(t))
    }
}

class GeoTest {
    @Test fun polylineRoundTrip() {
        val points = listOf(Point(38.5, -120.2), Point(40.7, -120.95), Point(43.252, -126.453))
        val encoded = Polyline.encode(points)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        assertEquals(points, Polyline.decode(encoded))
    }

    @Test fun simplifyKeepsEndpointsAndCorners() {
        val line = (0..100).map { Point(46.0 + it * 1e-5, 11.0) } + Point(46.001, 11.01)
        val simplified = Geo.simplify(line, 2.0)
        assertEquals(3, simplified.size)
        assertEquals(line.first(), simplified.first())
        assertEquals(line.last(), simplified.last())
    }

    @Test fun distanceIsReasonable() {
        val bozenMeran = Geo.distance(Point(46.4966, 11.3584), Point(46.6720, 11.1500))
        assertTrue(bozenMeran in 24_000.0..26_000.0)
        assertTrue(Geo.Bolzano in Geo.SouthTyrol)
    }

    @Test fun contrast() {
        assertEquals(0xFFFFFFL, ColorContrast.readableOn(0x003366, null))
        assertEquals(0x000000L, ColorContrast.readableOn(0xFFEE00, 0xFFFFFF))
        assertEquals(0xFFFFFFL, ColorContrast.readableOn(0x000000, 0xFFFFFF))
        assertEquals(0x1A2B3CL, ColorContrast.parseHex("#1a2b3c"))
        assertNull(ColorContrast.parseHex("xyz"))
    }

    @Test fun modes() {
        assertEquals(TransportMode.TRAIN, TransportMode.fromGtfs(2))
        assertEquals(TransportMode.CABLE_CAR, TransportMode.fromGtfs(6))
        assertEquals(TransportMode.BUS, TransportMode.fromGtfs(3))
        assertEquals(TransportMode.CITY_BUS, TransportMode.fromEfa(5))
        assertEquals(TransportMode.WALK, TransportMode.fromEfa(100))
    }
}
