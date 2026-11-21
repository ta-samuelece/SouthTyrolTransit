package org.southtyrol.transit.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GermanStopNamesTest {
    private val italian = buildMap {
        repeat(5) { put("bz$it", "Bolzano, Fermata $it") }
        put("bz-cable", "Bolzano, Funivia del Renon")
        repeat(3) { put("co$it", "Corvara, Fermata $it") }
        put("passeier", "Corvara, Maso")
        put("plain", "Merano")
        put("bare", "Touriseum")
        put("bare-corvara", "Sassongher")
    }
    private val german = buildMap {
        repeat(5) { put("bz$it", "Bozen, Haltestelle $it") }
        put("bz-cable", "Bolzano, Rittner Seilbahn")
        repeat(3) { put("co$it", "Corvara, Haltestelle $it") }
        put("passeier", "Rabenstein (Moos i. P.), Hof")
        put("plain", "Meran, Bahnhof")
        put("unknown", "Nirgendwo, Halt")
        put("bare", "Bolzano, Touriseum")
        put("bare-corvara", "Corvara, Sassongher")
    }

    @Test fun anItalianLocalityTakesTheGermanOneMostOfItsStopsUse() {
        assertEquals("Bozen, Rittner Seilbahn", GermanStopNames.corrected(italian, german)["bz-cable"])
    }

    @Test fun aStopWithoutALocalityInTheTimetableStillLosesAnItalianOne() {
        val names = GermanStopNames.corrected(italian, german)
        assertEquals("Bozen, Touriseum", names["bare"])
        assertEquals("Corvara, Sassongher", names["bare-corvara"])
    }

    @Test fun localitiesTheSameInBothLanguagesStay() {
        val names = GermanStopNames.corrected(italian, german)
        assertEquals("Corvara, Haltestelle 0", names["co0"])
        assertEquals("Rabenstein (Moos i. P.), Hof", names["passeier"])
    }

    @Test fun everythingElseIsUnchanged() {
        val names = GermanStopNames.corrected(italian, german)
        assertEquals("Bozen, Haltestelle 1", names["bz1"])
        assertEquals("Meran, Bahnhof", names["plain"])
        assertEquals("Nirgendwo, Halt", names["unknown"])
        assertEquals(german.keys, names.keys)
    }
}
