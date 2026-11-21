package org.southtyrol.transit.data

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class NameLanguageTest {
    @Test fun resolvesAgainstTheAppLanguage() {
        assertEquals("en-US", NameLanguage.APP.resolve("en-US"))
        assertEquals("de", NameLanguage.GERMAN.resolve("en-US"))
        assertEquals("it", NameLanguage.ITALIAN.resolve("de-DE"))
    }

    @Test fun aChosenNamesLanguageTakesTheTimetableNameOverAStoredOne() {
        assertEquals("Bolzano, Stazione di Bolzano", NameLanguage.APP.stopName("Bolzano, Stazione di Bolzano", "Bozen, Bahnhof Bozen"))
        assertEquals("Bozen, Bahnhof Bozen", NameLanguage.GERMAN.stopName("Bolzano, Stazione di Bolzano", "Bozen, Bahnhof Bozen"))
        assertEquals("Bolzano, Stazione di Bolzano", NameLanguage.ITALIAN.stopName("Bozen, Bahnhof Bozen", "Bolzano, Stazione di Bolzano"))
        assertEquals("Bozen, Bahnhof Bozen", NameLanguage.ITALIAN.stopName("Bozen, Bahnhof Bozen", null))
        assertEquals("Bozen, Bahnhof Bozen", NameLanguage.GERMAN.stopName("Bozen, Bahnhof Bozen", ""))
    }

    @Test fun settingRoundTripsAndUnknownValuesFallBackToApp() = runBlocking {
        val store = MemoryPreferences()
        val settings = SettingsRepository(store)
        assertEquals(NameLanguage.APP, settings.current().nameLanguage)
        settings.setNameLanguage(NameLanguage.GERMAN)
        assertEquals(NameLanguage.GERMAN, settings.current().nameLanguage)
        store.edit { it[stringPreferencesKey("nameLanguage")] = "LADIN" }
        assertEquals(NameLanguage.APP, settings.current().nameLanguage)
    }
}

class JourneyNoticeLanguageTest {
    private fun trip(vararg links: Pair<String, String>): ByteArray {
        fun point(usage: String, minute: Int) =
            """<itdPoint usage="$usage" name="Stop $usage" stopID="66000$minute"><itdDateTime><itdDate year="2026" month="11" day="20"/><itdTime hour="8" minute="$minute"/></itdDateTime></itdPoint>"""
        val infoLinks = links.joinToString("") { (lang, text) -> """<infoLink language="$lang"><infoLinkText>$text</infoLinkText></infoLink>""" }
        return """
            <itdRequest language="de"><itdTripRequest><itdItinerary><itdRouteList><itdRoute changes="0"><itdPartialRouteList>
            <itdPartialRoute type="PT">${point("departure", 10)}${point("arrival", 30)}
            <itdMeansOfTransport motType="3" name="Bus 201" shortname="201" destination="Bozen"/>$infoLinks</itdPartialRoute>
            </itdPartialRouteList></itdRoute></itdRouteList></itdItinerary></itdTripRequest></itdRequest>
        """.trimIndent().toByteArray()
    }

    private fun notices(bytes: ByteArray, noticeLanguage: String?) = EfaXml.journeys(bytes, noticeLanguage).single().legs.single().notices

    @Test fun picksTheAppLanguageNotice() {
        assertEquals(listOf("Umleitung"), notices(trip("de" to "Umleitung", "en" to "Diversion"), null))
        assertEquals(listOf("Diversion"), notices(trip("de" to "Umleitung", "en" to "Diversion"), "en"))
        assertEquals(listOf("Diversion"), notices(trip("de" to "Umleitung", "en" to "Diversion"), "en-US"))
    }

    @Test fun fallsBackToTheResponseLanguage() {
        assertEquals(listOf("Umleitung"), notices(trip("de" to "Umleitung"), "en"))
    }
}
