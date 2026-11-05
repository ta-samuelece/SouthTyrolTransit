package org.southtyrol.transit.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WhatsNewTest {
    private val sample = """
        {"version":"0.1.8","future":"ignored","highlights":[
          {"icon":"place","title":{"en":"Saved places","de":"Gespeicherte Orte"},"body":{"en":"Keep Home."}},
          {"icon":"not-known-yet","title":{"en":"Something new"}},
          {"icon":"bus","title":{"de":"Nur Deutsch"}}
        ],"notes":{"en":"## What's new","it":"## Novità"}}
    """.trimIndent()

    @Test fun picksTheLanguageAndFallsBackToEnglish() {
        val german = WhatsNewParser.parse(sample)!!.inLanguage("de")
        assertEquals("Gespeicherte Orte", german.highlights[0].title)
        assertEquals("Keep Home.", german.highlights[0].body)
        assertEquals("## What's new", german.notes)
        assertEquals("## Novità", WhatsNewParser.parse(sample)!!.inLanguage("it").notes)
    }

    @Test fun anUnknownIconShowsTheSparkle() {
        val cards = WhatsNewParser.parse(sample)!!.inLanguage("en").highlights
        assertEquals(WhatsNewIcon.PLACE, cards[0].icon)
        assertEquals(WhatsNewIcon.SPARKLE, cards[1].icon)
    }

    @Test fun aCardWithoutATitleInReachIsDropped() {
        assertEquals(2, WhatsNewParser.parse(sample)!!.inLanguage("en").highlights.size)
        assertEquals(3, WhatsNewParser.parse(sample)!!.inLanguage("de").highlights.size)
    }

    @Test fun unreadableContentIsNull() {
        assertNull(WhatsNewParser.parse("not json"))
    }

    private val notesDir = File("../docs/release-notes")

    /** The release script checks highlight files against this list; the app has to know the same names. */
    @Test fun iconListMatchesTheApp() {
        val listed = File(notesDir, "icons.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        assertEquals(WhatsNewIcon.entries.map { it.key }.toSet(), listed)
    }

    @Test fun everyHighlightsFileIsReadableAndUsesKnownIcons() {
        val known = WhatsNewIcon.entries.map { it.key }.toSet()
        val files = notesDir.listFiles { f -> f.name.endsWith(".highlights.json") }.orEmpty()
        for (file in files) {
            val parsed = WhatsNewParser.parse(file.readText())
            assertNotNull(file.name, parsed)
            assertTrue(file.name, parsed!!.highlights.isNotEmpty())
            for (card in parsed.highlights) assertTrue("${file.name}: ${card.icon}", card.icon in known)
        }
    }
}
