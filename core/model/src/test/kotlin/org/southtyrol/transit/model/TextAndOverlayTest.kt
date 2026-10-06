package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class TextAndOverlayTest {
    @Test fun decodesNamedNumericAndHexEntities() {
        assertEquals("Bozen – „Sperre“ 5°", HtmlEntities.decode("Bozen &ndash; &bdquo;Sperre&ldquo; 5&deg;"))
        assertEquals("Änderung è ' –", HtmlEntities.decode("&Auml;nderung &egrave; &#39; &#x2013;"))
        assertEquals("unknown &foo; stays", HtmlEntities.decode("unknown &foo; stays"))
    }

    @Test fun decodesDoubleEncodedText() {
        assertEquals("Bahnhof – Zentrum", HtmlEntities.decode("Bahnhof &amp;ndash; Zentrum"))
        assertEquals("A & B", HtmlEntities.decode("A &amp;amp; B"))
    }

    @Test fun stripHtmlRemovesMarkupAndEntityEncodedTags() {
        assertEquals("Line 1\nGeänderte Route", TextNormalizer.stripHtml("<p>Line 1</p><b>Ge&auml;nderte</b>&nbsp;Route"))
        assertEquals("Text\nmore", TextNormalizer.stripHtml("Text&lt;br&gt;more"))
    }

    private val t0 = Instant.parse("2026-10-06T07:00:00Z")
    private fun timetable(line: String, at: Instant, destination: String = "Bolzano") = Departure("trip$line$at", "stop", 3, "r", line, TransportMode.BUS, destination, at)
    private fun efa(line: String, at: Instant, delayMin: Long?, destination: String = "Bolzano", cancelled: Boolean = false) = Departure(
        "efa:$line", "stop", 0, "", line, TransportMode.BUS, destination, at,
        predicted = delayMin?.let { at.plusSeconds(it * 60) },
        state = if (cancelled) ServiceState.CANCELLED else ServiceState.NORMAL,
        freshness = if (delayMin != null || cancelled) Freshness.LIVE else Freshness.SCHEDULED, tripLinked = false,
    )

    @Test fun overlayAppliesEfaDelaysToTimetableDepartures() {
        val board = listOf(timetable("201", t0), timetable("10A", t0.plusSeconds(300)))
        val live = listOf(efa("201", t0, 4), efa("10A", t0.plusSeconds(300), null))
        val merged = LiveOverlay.apply(board, live, t0)
        assertEquals(240L, merged[0].delaySeconds)
        assertEquals(Freshness.LIVE, merged[0].freshness)
        assertNull(merged[1].predicted)
    }

    @Test fun overlayMatchesSameLineByDestinationAndUsesEachEntryOnce() {
        val board = listOf(timetable("3", t0, "Casanova"), timetable("3", t0, "Via Alto Adige"))
        val live = listOf(efa("3", t0, 2, "Via Alto Adige"), efa("3", t0, 6, "Casanova"))
        val merged = LiveOverlay.apply(board, live, t0)
        assertEquals(360L, merged[0].delaySeconds)
        assertEquals(120L, merged[1].delaySeconds)
    }

    @Test fun overlayKeepsGtfsRealtimeAndIgnoresDistantTimes() {
        val withRt = timetable("201", t0).copy(predicted = t0.plusSeconds(60), freshness = Freshness.LIVE)
        val far = timetable("201", t0.plusSeconds(600))
        val merged = LiveOverlay.apply(listOf(withRt, far), listOf(efa("201", t0, 9), efa("201", t0.plusSeconds(130), 1)), t0)
        assertEquals(60L, merged[0].delaySeconds)
        assertNull(merged[1].predicted)
    }

    @Test fun overlayMarksCancellations() {
        val merged = LiveOverlay.apply(listOf(timetable("110", t0)), listOf(efa("110", t0, null, cancelled = true)), t0)
        assertEquals(ServiceState.CANCELLED, merged[0].state)
    }

    @Test fun overlayMatchesTrainsByModeTimeAndDestination() {
        val train = timetable("REG", t0, "Merano").copy(mode = TransportMode.TRAIN)
        val bus = timetable("201", t0, "Merano")
        val live = listOf(efa("R 16719", t0, 5, "Meran").copy(mode = TransportMode.TRAIN))
        val merged = LiveOverlay.apply(listOf(bus, train), live, t0)
        assertNull(merged[0].predicted)
        assertEquals(300L, merged[1].delaySeconds)
    }

    @Test fun englishFallsBackToThePhonesSystemLanguages() {
        val notice = mapOf("de" to "Linie 280 Planeil", "it" to "Linea 280 Planol", "en" to "")
        assertEquals("Linie 280 Planeil", notice.localized("en,de-DE"))
        assertEquals("Linea 280 Planol", notice.localized("en,it-IT,de"))
        assertEquals("Linea 280 Planol", notice.localized("en"))
        assertEquals("de", notice.localizedLanguage("en,de"))
        assertEquals(listOf("lld", "de", "it", "en"), Languages.fallbacks("lld"))
    }
}
