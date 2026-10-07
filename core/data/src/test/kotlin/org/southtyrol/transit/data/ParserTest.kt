package org.southtyrol.transit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.model.AlertSource
import org.southtyrol.transit.model.FareKind
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.localized
import java.io.File
import java.io.StringReader
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalTime

fun fixture(name: String): ByteArray = File(System.getProperty("fixtures") ?: "../../docs/fixtures", name).readBytes()

class CsvTest {
    @Test fun parsesQuotedFieldsAndLineEndings() {
        val csv = Csv(StringReader("﻿a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",\"multi\nline\"\n1,,3\n\nlast,row,\"\""))
        assertEquals(listOf("a", "b", "c"), csv.row())
        assertEquals(listOf("x, y", "he said \"hi\"", "multi\nline"), csv.row())
        assertEquals(listOf("1", "", "3"), csv.row())
        assertEquals(listOf(""), csv.row())
        assertEquals(listOf("last", "row", ""), csv.row())
        assertNull(csv.row())
    }

    @Test fun recordsByHeader() {
        val names = mutableListOf<String>()
        Csv(StringReader("stop_id,stop_name\n\"1\",\"A\"\n2,B")).forEachRecord { names += it["stop_name"] + it["stop_id"] }
        assertEquals(listOf("A1", "B2"), names)
    }

    @Test fun unterminatedQuoteFails() {
        assertThrows(IllegalArgumentException::class.java) { Csv(StringReader("a\n\"open")).apply { row(); row() } }
    }

    @Test fun stationKeysAndSearch() {
        assertEquals("it:22021:468", GtfsFiles.stationKey("it:22021:468:1:2805", ""))
        assertEquals("it:22015:116", GtfsFiles.stationKey("it:22015:116:50:70151", "Parentit:22015:116"))
        assertEquals("at:43:4848", GtfsFiles.stationKey("Parentat:43:4848", ""))
        assertEquals("bozen* bahnhof*", GtfsFiles.ftsQuery(" Bozen,  Bahnhof "))
        assertNull(GtfsFiles.ftsQuery("  - "))
        assertEquals("BUS:201", GtfsFiles.lineKey(3, "201", "", "1-201"))
    }
}

class EfaXmlTest {
    @Test fun parsesJourneysWithFaresAndRealtimeTimes() {
        val journeys = EfaXml.journeys(fixture("efa_trip_ok.xml"))
        assertEquals(4, journeys.size)
        val first = journeys.first()
        assertEquals(2, first.legs.size)
        assertEquals(1, first.changes)
        val train = first.legs[0]
        assertEquals(TransportMode.TRAIN, train.mode)
        assertEquals("R", train.line)
        assertEquals("R 17129", train.lineName)
        assertEquals("Meran", train.destination)
        assertEquals("it:22021:468", train.from.stopGlobalId)
        assertEquals(LocalTime.of(8, 1), train.departure.atZone(TransitZone).toLocalTime())
        assertTrue(train.intermediate.isNotEmpty())
        assertTrue(train.geometry.size > 2)
        assertEquals("212", first.legs[1].line)
        assertNotNull(first.fare)
        val fare = first.fare!!
        assertEquals(BigDecimal("9.00"), fare.single?.price)
        assertEquals(BigDecimal("5.16"), fare.valueCard?.price)
        assertTrue(fare.tickets.all { it.kind != FareKind.OTHER || it.price > BigDecimal.ZERO })
        assertEquals(BigDecimal("7.00"), journeys[1].fare?.single?.price)
        assertEquals(journeys.map { it.id }.distinct().size, journeys.size)
    }

    @Test fun tripParametersUseNetworkLocalDateAndTime() {
        val at = java.time.ZonedDateTime.of(2026, 10, 4, 23, 30, 0, 0, java.time.ZoneId.of("UTC"))
        val params = EfaClient.tripParams(
            org.southtyrol.transit.model.Place("66000468", "Bozen", PlaceType.STOP),
            org.southtyrol.transit.model.Place("", "Here", PlaceType.COORDINATE, org.southtyrol.transit.model.Point(46.5, 11.35)),
            org.southtyrol.transit.model.SearchOptions(at = at, arriveBy = true, excludedModes = setOf(TransportMode.CABLE_CAR), wheelchair = true),
            "en",
        )
        assertEquals("20261005", params["itdDate"]) // 23:30 UTC is 01:30 on the 5th in Rome
        assertEquals("0130", params["itdTime"])
        assertEquals("arr", params["itdTripDateTimeDepArr"])
        assertEquals("stop", params["type_origin"])
        assertEquals("coord", params["type_destination"])
        assertEquals("11.350000:46.500000:WGS84[DD.DDDDD]", params["name_destination"])
        assertEquals("1", params["exclMOT_8"])
        assertEquals("on", params["wheelchair"])
    }

    @Test fun parsesEveningResponseWithRealtimeBlocks() {
        val journeys = EfaXml.journeys(fixture("efa_trip_evening.xml"))
        assertTrue(journeys.size >= 5)
    }

    @Test fun parsesWalkingLegs() {
        val journeys = EfaXml.journeys(fixture("efa_trip_walk.xml"))
        assertEquals(4, journeys.size)
        val legs = journeys.first().legs
        assertEquals(TransportMode.WALK, legs.first().mode)
        assertEquals(455, legs.first().distanceMeters)
        assertEquals("", legs.first().line)
        assertEquals(PlaceType.COORDINATE, legs.first().from.type)
        assertEquals(TransportMode.TRAIN, legs[1].mode)
        assertEquals(0, journeys.first().changes)
        assertTrue(journeys.first().walkingDuration.toMinutes() >= 17)
    }

    @Test fun unidentifiedOriginIsReported() {
        val error = assertThrows(EfaFailure::class.java) { EfaXml.journeys(fixture("efa_trip.txt")) }
        assertEquals(EfaCodes.ORIGIN_NOT_FOUND, error.code)
    }

    @Test fun stopFinder() {
        val unique = EfaXml.places(fixture("efa_stopfinder_list.xml"))
        assertEquals(1, unique.size)
        assertEquals("66000998", unique[0].id)
        assertEquals(PlaceType.STOP, unique[0].type)
        assertEquals("it:22021:998", unique[0].stopGlobalId)
        assertTrue(EfaXml.places(fixture("efa_search.txt")).isEmpty())
        assertTrue(EfaXml.places(fixture("search_de.bin")).isNotEmpty())
    }

    @Test fun departureMonitor() {
        val departures = EfaXml.departures(fixture("efa_dm.xml"), Instant.parse("2026-10-04T18:00:00Z"))
        assertEquals(15, departures.size)
        val train = departures.first()
        assertEquals("R", train.line)
        assertEquals("Brenner", train.destination)
        assertEquals(TransportMode.TRAIN, train.mode)
        assertEquals(300L, train.delaySeconds)
        assertEquals(Freshness.LIVE, train.freshness)
        assertEquals("3", train.platform)
        assertFalse(train.tripLinked)
        assertEquals("131", departures[1].line)
    }

    @Test fun mergesLanguagesByMatchQuality() {
        val street = Place("s1", "Bahnhofstraße, Bressanone", PlaceType.STREET) to 152
        val stationDe = Place("66000468", "Bozen, Bahnhof Bozen", PlaceType.STOP) to 1000
        val stationIt = Place("66000468", "Bolzano, Stazione di Bolzano", PlaceType.STOP) to 900
        val merged = EfaXml.mergeRanked(listOf(listOf(street, stationIt), listOf(stationDe)))
        assertEquals(listOf("66000468", "s1"), merged.map { it.id })
        assertEquals("Bolzano, Stazione di Bolzano", merged.first().name) // preferred language spelling kept
    }

    @Test fun coordinateStops() {
        val stops = EfaXml.coordStops(fixture("efa_coord.xml"))
        assertEquals(17, stops.size)
        assertEquals("it:22021:2084", stops.first().stopGlobalId)
        assertNotNull(stops.first().point)
    }

    @Test fun addInfoAlerts() {
        val alerts = EfaXml.alerts(fixture("efa_addinfo.xml"), Instant.parse("2026-10-04T18:00:00Z"))
        assertTrue(alerts.size in 30..65)
        val line280 = alerts.first { "280" in it.lineNames }
        assertEquals(AlertSource.EFA, line280.source)
        assertTrue(line280.headers.localized("de").startsWith("Linie 280"))
        assertTrue(line280.headers.localized("it").startsWith("Linea 280"))
        assertTrue(line280.descriptions.localized("de").contains("Gemeinde Mals"))
        assertFalse(line280.descriptions.localized("de").contains("<p>"))
        assertTrue(alerts.any { it.stops.contains("it:22021:2444") })
        assertTrue(alerts.any { it.effect == "NO_SERVICE" })
    }

    @Test fun rejectsDoctype() {
        assertThrows(IllegalArgumentException::class.java) {
            EfaXml.places("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><itdRequest>&e;</itdRequest>""".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { EfaXml.places(ByteArray(0)) }
    }

    @Test fun malformedXmlFails() {
        assertThrows(Exception::class.java) { EfaXml.journeys("<itdRequest><itdTripRequest>".toByteArray()) }
    }
}

class GtfsRealtimeParserTest {
    private val now = Instant.parse("2026-10-04T18:30:00Z")

    @Test fun tripUpdates() {
        val feed = GtfsRealtimeParser.parse(fixture("trip_updates.pb"), now)
        assertTrue(feed.updates.isNotEmpty())
        val u = feed.updates.first()
        assertTrue(u.tripId.contains(".TA.") || u.tripId.isNotBlank())
        assertNotNull(u.date)
        assertTrue(feed.updates.flatMap { it.stops }.any { it.departureDelay != null || it.departureTime != null })
        assertTrue(feed.updates.all { it.state != ServiceState.UNKNOWN })
    }

    @Test fun vehiclePositions() {
        val feed = GtfsRealtimeParser.parse(fixture("vehicle_positions.pb"), now)
        assertTrue(feed.vehicles.isNotEmpty())
        assertTrue(feed.vehicles.all { it.point.isValid })
        assertTrue(feed.vehicles.any { it.tripId != null && it.routeId != null })
    }

    @Test fun serviceAlertsAreMultilingual() {
        val feed = GtfsRealtimeParser.parse(fixture("service_alerts.pb"), now)
        assertTrue(feed.alerts.isNotEmpty())
        val a = feed.alerts.first()
        assertTrue(a.headers.keys.containsAll(listOf("de", "it", "en")))
        assertEquals(AlertSource.GTFS_RT, a.source)
        assertTrue(feed.alerts.any { alert -> alert.stops.any { it.count { c -> c == ':' } == 2 } || alert.routes.isNotEmpty() })
    }

    @Test fun olderFixtureStillParses() {
        val feed = GtfsRealtimeParser.parse(fixture("trip_pb.bin"), now)
        assertTrue(feed.updates.isNotEmpty())
    }

    @Test fun garbageFails() {
        assertThrows(Exception::class.java) { GtfsRealtimeParser.parse(byteArrayOf(1, 2, 3, 4, 5), now) }
    }
}

class MobilityParseTest {
    @Test fun dropsDeadSensorsAndMarksStale() {
        val body = """{"offset":0,"data":[
            {"mvalidtime":"2017-10-22 23:00:59.000+0000","mvalue":114,"scode":"113","scoordinate":{"srid":4326,"x":11.33818,"y":46.49869},"smetadata.capacity":114,"sname":"P13","sorigin":"FAMAS"},
            {"mvalidtime":"2026-10-04 18:00:00.000+0000","mvalue":46,"scode":"104","scoordinate":{"srid":4326,"x":11.358216,"y":46.500551},"smetadata.capacity":150,"sname":"P04","sorigin":"FAMAS"},
            {"mvalidtime":"2026-10-03 10:00:00.000+0000","mvalue":3,"scode":"105","scoordinate":{"srid":4326,"x":11.35,"y":46.5},"sname":"P05","sorigin":"FAMAS"}
        ]}"""
        val source = OdhMobilitySource(TransitHttp(okhttp3.OkHttpClient()), MobilityKind.PARKING)
        val points = source.parse(body, MobilityKind.PARKING, Instant.parse("2026-10-04T18:30:00Z"))
        assertEquals(2, points.size)
        assertEquals(Freshness.LIVE, points.first { it.name == "P04" }.freshness)
        assertEquals(150, points.first { it.name == "P04" }.capacity)
        assertEquals(Freshness.STALE, points.first { it.name == "P05" }.freshness)
    }
}

class LiveTripParserTest {
    @Test fun parsesStopSequenceWithLiveDelays() {
        // Railjet 87 München → Bologna, captured 7 Oct 2026 17:32 (live delays at the first stops).
        val stops = EfaXml.stopSequence(fixture("efa_stopseq.xml"))
        assertEquals(15, stops.size)
        val munich = stops.first()
        assertEquals(null, munich.scheduledArrival)
        assertEquals(12, munich.departureDelayMinutes)
        val bozen = stops.first { it.stationKey == "it:22021:468" }
        assertEquals(java.time.Instant.parse("2026-10-07T15:27:00Z"), bozen.scheduledArrival)
        assertEquals(java.time.Instant.parse("2026-10-07T15:31:00Z"), bozen.scheduledDeparture)
        assertEquals(0, bozen.departureDelayMinutes)
        assertEquals(null, stops.last().scheduledDeparture)
    }

    @Test fun departureBoardEntriesCarryATripReference() {
        val departures = EfaXml.departures(fixture("efa_dm_live.xml"), java.time.Instant.parse("2026-10-07T15:32:00Z"))
        val railjet = departures.first { it.line.contains("87") || it.liveRef.contains("05100") }
        assertEquals("apb:05100:E:H:26a|66000468|34|20261007|1731", railjet.liveRef)
    }
}
