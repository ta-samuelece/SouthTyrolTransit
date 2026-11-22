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

    private fun stop(headsign: String, italian: String, german: String, last: Boolean = false, trips: Int = 1) =
        GermanStopNames.HeadsignStop(headsign, italian, german, last, trips)

    @Test fun aDestinationNamingTheLastStopTakesItsGermanName() {
        val stops = listOf(
            stop("Lana Bivio Via Dogana", "Lana, Bivio Via Dogana", "Lana, Abzweigung Zollstraße", last = true),
            stop("Tiso", "Tiso", "Teis (Villnöss), Teis", last = true),
            stop("S.Lugano Chiesa", "S.Lugano, Chiesa", "S. Lugano (Truden), Kirche", last = true),
        )
        val names = GermanStopNames.headsigns(stops, emptyMap())
        assertEquals("Lana, Abzweigung Zollstraße", names["Lana Bivio Via Dogana"])
        assertEquals("Teis", names["Tiso"])
        assertEquals("S. Lugano, Kirche", names["S.Lugano Chiesa"])
    }

    @Test fun aDestinationOfLocalitiesIsTranslatedFromTheTripsOwnStops() {
        val stops = listOf(
            stop("Bronzolo - Laives", "Bronzolo, Paese", "Branzoll, Dorf"),
            stop("Bronzolo - Laives", "Laives, Centro", "Leifers, Zentrum", last = true),
            stop("Merano Stazione", "Merano, Piazza Teatro", "Meran, Theaterplatz"),
            stop("S. Valentino - Collepietra", "S. Valentino, Paese", "Gummer, Dorf"),
            stop("S. Valentino - Collepietra", "Collepietra, Chiesa", "Steinegg (Karneid), Kirche", last = true),
            stop("Fortezza - Rio di Pusteria", "Fortezza, Stazione", "Franzensfeste, Bahnhof"),
            stop("Fortezza - Rio di Pusteria", "Rio di Pusteria, Centro", "Rio di Pusteria, Zentrum", last = true),
        )
        val names = GermanStopNames.headsigns(stops, mapOf("Rio di Pusteria" to "Mühlbach"))
        assertEquals("Branzoll - Leifers", names["Bronzolo - Laives"])
        assertEquals("Meran Bahnhof", names["Merano Stazione"])
        assertEquals("Gummer - Steinegg", names["S. Valentino - Collepietra"])
        assertEquals("Franzensfeste - Mühlbach", names["Fortezza - Rio di Pusteria"])
    }

    @Test fun aDestinationWithAnyOtherPartStaysItalian() {
        val stops = listOf(
            stop("Bolzano per Viale Druso", "Bolzano, Viale Druso", "Bozen, Drususallee", last = true),
            stop("Navetta", "Bolzano, Fiera", "Bozen, Messe", last = true),
            stop("Corvara", "Corvara, Centro", "Corvara, Zentrum", last = true),
        )
        assertEquals(emptyMap<String, String>(), GermanStopNames.headsigns(stops, emptyMap()))
    }
}
