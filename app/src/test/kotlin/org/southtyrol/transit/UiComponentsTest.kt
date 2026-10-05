package org.southtyrol.transit

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.southtyrol.transit.data.JourneyRequest
import org.southtyrol.transit.design.ErrorState
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.TransitTheme
import org.southtyrol.transit.feature.common.AlertCard
import org.southtyrol.transit.feature.common.DepartureRow
import org.southtyrol.transit.feature.journey.JourneyCard
import org.southtyrol.transit.feature.planner.RequestCodec
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.FareInformation
import org.southtyrol.transit.model.FareKind
import org.southtyrol.transit.model.FareTicket
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RoutePreference
import org.southtyrol.transit.model.SearchOptions
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import java.math.BigDecimal
import java.time.Instant
import java.time.ZonedDateTime

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class UiComponentsTest {
    @get:Rule val compose = createComposeRule()

    private val now = Instant.now()
    private val bozen = Place("66000468", "Bozen, Bahnhof", PlaceType.STOP, Point(46.4966, 11.3584), "Bozen", "it:22021:468")
    private val meran = Place("66000221", "Meran, Therme", PlaceType.STOP, Point(46.6705, 11.1585), "Meran", "it:22021:221")

    @Test fun journeySearchRequestSurvivesNavigation() {
        val at = ZonedDateTime.of(2026, 10, 5, 8, 15, 0, 0, TransitZone)
        val request = JourneyRequest(bozen, meran, SearchOptions(at = at, arriveBy = true, preference = RoutePreference.FEWEST_CHANGES, excludedModes = setOf(TransportMode.CABLE_CAR), wheelchair = true))
        val decoded = RequestCodec.decode(RequestCodec.encode(request, leaveNow = false))!!
        assertEquals(request.from, decoded.from)
        assertEquals(request.to, decoded.to)
        assertEquals(at.toInstant(), decoded.options.at.toInstant())
        assertTrue(decoded.options.arriveBy && decoded.options.wheelchair)
        assertEquals(RoutePreference.FEWEST_CHANGES, decoded.options.preference)
        assertEquals(setOf(TransportMode.CABLE_CAR), decoded.options.excludedModes)
        // "Leave now" requests are re-evaluated at decode time.
        val later = Instant.parse("2026-10-06T10:00:00Z")
        assertEquals(later, RequestCodec.decode(RequestCodec.encode(request, leaveNow = true), later)!!.options.at.toInstant())
    }

    @Test fun journeyResultCardShowsSummaryAndSelects() {
        val dep = now.plusSeconds(600)
        val journey = Journey(
            "j1",
            listOf(
                Leg(TransportMode.TRAIN, "R", "Meran", bozen, meran, dep, dep.plusSeconds(2400), predictedDeparture = dep.plusSeconds(300), predictedArrival = dep.plusSeconds(2700), realtime = true),
            ),
            changes = 0,
            fare = FareInformation(listOf(FareTicket("STANDARD", FareKind.SINGLE, BigDecimal("7.00"), "EUR")), "EFA"),
        )
        var selected: String? = null
        compose.setContent { TransitTheme(dynamicColor = false) { JourneyCard(journey, selected = false, onClick = { selected = journey.id }) } }
        compose.onNodeWithText("0 changes").assertIsDisplayed()
        compose.onNodeWithText("€ 7.00").assertIsDisplayed()
        compose.onNodeWithText("Up to 5 min late").assertIsDisplayed()
        compose.onNodeWithContentDescription("Departs", substring = true).performClick()
        assertEquals("j1", selected)
    }

    @Test fun stopDepartureShowsCountdownDelayAndOpensTrip() {
        val departure = Departure("t1", "it:22021:468:1:1", 3, "r", "201", TransportMode.BUS, "Meran", now.plusSeconds(180), predicted = now.plusSeconds(420), freshness = Freshness.LIVE, platform = "D", hasAlert = true)
        var opened = false
        compose.setContent { TransitTheme(dynamicColor = false) { DepartureRow(departure, now, 0, 1, onClick = { opened = true }) } }
        compose.onNodeWithText("Meran").assertIsDisplayed()
        compose.onNodeWithText("7 min").assertIsDisplayed()
        compose.onNodeWithText("Platform D").assertIsDisplayed()
        compose.onNodeWithContentDescription("4 minutes late", substring = true).performClick()
        assertTrue(opened)
    }

    @Test fun cancelledDepartureIsLabelledNotOnlyColoured() {
        val departure = Departure("t2", "s", 1, "r", "110", TransportMode.BUS, "Bronzolo", now.plusSeconds(300), state = ServiceState.CANCELLED, freshness = Freshness.LIVE)
        compose.setContent { TransitTheme(dynamicColor = false) { DepartureRow(departure, now, 0, 1, onClick = null) } }
        compose.onNodeWithContentDescription("Cancelled", substring = true).assertIsDisplayed()
    }

    @Test fun alertsExpandAndSwitchLanguage() {
        val alert = ServiceAlert(
            "a1", mapOf("de" to "Baustelle Linie 201", "it" to "Cantiere linea 201", "en" to "Construction line 201"),
            mapOf("de" to "Umleitung über Gries", "it" to "Deviazione via Gries", "en" to "Diversion via Gries"),
            lineNames = setOf("201"), periods = listOf(now.minusSeconds(3600) to now.plusSeconds(86_400)), observedAt = now, effect = "DETOUR",
        )
        compose.setContent { TransitTheme(dynamicColor = false) { AlertCard(alert, "en", now) } }
        compose.onNodeWithText("Construction line 201").assertIsDisplayed()
        compose.onNodeWithText("Construction line 201").performClick()
        compose.onNodeWithText("Diversion via Gries").assertIsDisplayed()
        compose.onNodeWithText("DE").performClick()
        compose.onNodeWithText("Umleitung über Gries").assertIsDisplayed()
    }

    @Test fun offlineStateExplainsAndRetries() {
        var retried = false
        compose.setContent { TransitTheme(dynamicColor = false) { ErrorState(DataError.Offline, onRetry = { retried = true }) } }
        compose.onNodeWithText("You are offline", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        assertTrue(retried)
    }

    @Test fun locationPermissionDeniedStateKeepsAppUsable() {
        compose.setContent {
            TransitTheme(dynamicColor = false) {
                MessageState(
                    androidx.compose.ui.res.stringResource(R.string.location_denied),
                    androidx.compose.ui.res.stringResource(R.string.location_denied_hint),
                )
            }
        }
        compose.onNodeWithText("Location permission was not granted.").assertIsDisplayed()
        compose.onNodeWithText("search for stops by name", substring = true).assertIsDisplayed()
    }
}
