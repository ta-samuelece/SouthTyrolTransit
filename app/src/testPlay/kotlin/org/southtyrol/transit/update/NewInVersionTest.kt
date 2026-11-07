package org.southtyrol.transit.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewInVersionTest {
    @Test fun showsOnceAfterAnUpdateToAVersionWithCards() {
        assertTrue(shouldShowNewIn(firstInstall = 1_000, lastUpdate = 2_000, seenVersion = "0.2.0", version = "0.2.1", hasCards = true))
        // Moving over from the GitHub build, or the first Play version with the sheet: nothing recorded yet.
        assertTrue(shouldShowNewIn(firstInstall = 1_000, lastUpdate = 2_000, seenVersion = "", version = "0.2.1", hasCards = true))
    }

    @Test fun neverOnAFreshInstall() {
        assertFalse(shouldShowNewIn(firstInstall = 1_000, lastUpdate = 1_000, seenVersion = "", version = "0.2.1", hasCards = true))
        // A restore brings the last version's record along, but it is still a fresh install.
        assertFalse(shouldShowNewIn(firstInstall = 1_000, lastUpdate = 1_000, seenVersion = "0.2.0", version = "0.2.1", hasCards = true))
    }

    @Test fun notTwiceForTheSameVersion() {
        assertFalse(shouldShowNewIn(firstInstall = 1_000, lastUpdate = 2_000, seenVersion = "0.2.1", version = "0.2.1", hasCards = true))
    }

    @Test fun notForAHotfixWithoutCards() {
        assertFalse(shouldShowNewIn(firstInstall = 1_000, lastUpdate = 2_000, seenVersion = "0.2.0", version = "0.2.1", hasCards = false))
    }
}
