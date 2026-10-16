package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LineNamesTest {
    @Test fun ropewaysDropTheGenericWordAndArticle() {
        assertEquals("Rittner", LineNames.badge("Rittner Seilbahn", TransportMode.CABLE_CAR))
        assertEquals("Kohlern", LineNames.badge("Seilbahn Kohlern", TransportMode.CABLE_CAR))
        assertEquals("Meran 2000", LineNames.badge("Seilbahn Meran 2000", TransportMode.CABLE_CAR))
        assertEquals("Renon", LineNames.badge("Funivia del Renon", TransportMode.CABLE_CAR))
        assertEquals("Mendola", LineNames.badge("Funicolare della Mendola", TransportMode.FUNICULAR))
        assertEquals("Mendel", LineNames.badge("Standseilbahn Mendel", TransportMode.FUNICULAR))
    }

    @Test fun bilingualNamesUseTheFirstLanguage() {
        assertEquals("Rittner", LineNames.badge("Rittner Seilbahn/Funivia del Renon", TransportMode.CABLE_CAR))
    }

    @Test fun shortOrGenericOrLongNamesFallBack() {
        assertEquals("RS", LineNames.badge("RS", TransportMode.CABLE_CAR))
        assertEquals("", LineNames.badge("Seilbahn", TransportMode.CABLE_CAR))
        assertEquals("", LineNames.badge("Seilbahn Vigiljoch Ultental Talstation", TransportMode.CABLE_CAR))
    }

    @Test fun otherModesAreUntouched() {
        assertEquals("Seilbahn Express", LineNames.badge("Seilbahn Express", TransportMode.BUS))
        assertEquals("201", LineNames.badge(" 201 ", TransportMode.BUS))
    }
}
