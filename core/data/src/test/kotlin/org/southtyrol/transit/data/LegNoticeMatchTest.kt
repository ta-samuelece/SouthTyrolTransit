package org.southtyrol.transit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.southtyrol.transit.model.AlertSource
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.TransportMode
import java.time.Instant

class LegNoticeMatchTest {
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val place = Place("p", "Bozen", PlaceType.STOP, Point(46.5, 11.35))

    private fun leg(line: String = "201", mode: TransportMode = TransportMode.BUS) =
        Leg(mode, line, "Meran", place, place, now, now.plusSeconds(1800))

    private fun alert(
        id: String,
        header: String,
        description: String = "",
        lineNames: Set<String> = emptySet(),
        routes: Set<String> = emptySet(),
        stops: Set<String> = emptySet(),
        periods: List<Pair<Instant?, Instant?>> = emptyList(),
    ) = ServiceAlert(
        id, mapOf("de" to header), mapOf("de" to description),
        routes = routes, stops = stops, lineNames = lineNames, periods = periods, observedAt = now, source = AlertSource.EFA,
    )

    private fun match(alerts: List<ServiceAlert>, leg: Leg = leg(), notice: String = "Umleitung in Terlan", routeIds: Collection<String> = emptyList(), tripId: String? = null) =
        AlertMatcher.forNotice(alerts, leg, notice, routeIds, tripId, now)

    @Test fun textMatchKeepsOnlyTheAlertsWhoseTextContainsTheNotice() {
        val detour = alert("a", "Linie 201: Umleitung in Terlan wegen Bauarbeiten", lineNames = setOf("201"))
        val other = alert("b", "Linie 201: Haltestelle Siebeneich verlegt", lineNames = setOf("201"))
        val result = match(listOf(other, detour))
        assertEquals(NoticeMatch.Kind.TEXT, result.kind)
        assertEquals(listOf("a"), result.alerts.map { it.id })
    }

    @Test fun textMatchesInEveryLanguageAndTheReverseDirection() {
        val italian = ServiceAlert(
            "a", mapOf("de" to "Bauarbeiten"), mapOf("it" to "Deviazione"),
            lineNames = setOf("201"), observedAt = now, source = AlertSource.EFA,
        )
        val result = match(listOf(italian), notice = "Deviazione a Terlano")
        assertEquals(NoticeMatch.Kind.TEXT, result.kind)
        assertEquals(listOf("a"), result.alerts.map { it.id })
    }

    @Test fun noTextMatchFallsBackToEveryAlertOnTheLineInOrder() {
        val first = alert("a", "Fahrplanänderung Linie 201", lineNames = setOf("201"))
        val second = alert("b", "Haltestelle verlegt", lineNames = setOf("201"))
        val result = match(listOf(first, second), notice = "Streik am Freitag")
        assertEquals(NoticeMatch.Kind.LINE, result.kind)
        assertEquals(listOf("a", "b"), result.alerts.map { it.id })
    }

    @Test fun noCandidatesGivesNone() {
        val result = match(emptyList())
        assertEquals(NoticeMatch.Kind.NONE, result.kind)
        assertTrue(result.alerts.isEmpty())
    }

    @Test fun walkingLegAndLegWithoutLineGiveNone() {
        val alerts = listOf(alert("a", "Umleitung in Terlan", lineNames = setOf("201"), routes = setOf("r201")))
        val walk = match(alerts, leg = leg(mode = TransportMode.WALK), routeIds = listOf("r201"))
        assertEquals(NoticeMatch.Kind.NONE, walk.kind)
        assertTrue(walk.alerts.isEmpty())
        val blank = match(alerts, leg = leg(line = ""))
        assertEquals(NoticeMatch.Kind.NONE, blank.kind)
        assertTrue(blank.alerts.isEmpty())
    }

    @Test fun alertOnAnotherLineIsNotReturnedEvenWhenItsTextMatches() {
        val elsewhere = alert("a", "Umleitung in Terlan", lineNames = setOf("202"))
        val stopOnly = alert("b", "Umleitung in Terlan", stops = setOf("it:22021:468"))
        val result = match(listOf(elsewhere, stopOnly))
        assertEquals(NoticeMatch.Kind.NONE, result.kind)
        assertTrue(result.alerts.isEmpty())
    }

    @Test fun routeIdOfTheMatchedRunMatches() {
        val gtfs = alert("a", "Umleitung in Terlan", routes = setOf("r201"))
        val result = match(listOf(gtfs), routeIds = listOf("r201"))
        assertEquals(NoticeMatch.Kind.TEXT, result.kind)
        assertEquals(listOf("a"), result.alerts.map { it.id })
        assertEquals(NoticeMatch.Kind.NONE, match(listOf(gtfs)).kind)
    }

    @Test fun tripIdOfTheMatchedRunMatches() {
        val run = ServiceAlert("a", mapOf("de" to "Fahrt fällt aus"), emptyMap(), trips = setOf("t1"), observedAt = now)
        val result = match(listOf(run), routeIds = listOf("r201"), tripId = "t1")
        assertEquals(NoticeMatch.Kind.LINE, result.kind)
        assertEquals(listOf("a"), result.alerts.map { it.id })
    }

    @Test fun expiredAlertIsLeftOutAndUpcomingOneKept() {
        val expired = alert("a", "Umleitung in Terlan", lineNames = setOf("201"), periods = listOf(now.minusSeconds(7200) to now.minusSeconds(3600)))
        val upcoming = alert("b", "Umleitung in Terlan", lineNames = setOf("201"), periods = listOf(now.plusSeconds(3600) to now.plusSeconds(7200)))
        val result = match(listOf(expired, upcoming))
        assertEquals(NoticeMatch.Kind.TEXT, result.kind)
        assertEquals(listOf("b"), result.alerts.map { it.id })
    }
}
