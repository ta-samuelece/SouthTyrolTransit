package org.southtyrol.transit.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.TransportMode
import java.time.Instant

class SearchHistoryTest {
    @Test fun keepsOpenedStopsAndLinesNewestFirstAndClearsOnlyItself() = runBlocking {
        var now = Instant.parse("2026-10-30T08:00:00Z")
        val user = FakeUserDao()
        val repo = SavedRepository(user) { now }
        val stop = Stop("it:22021:468", "Bolzano, Stazione", Point(46.497, 11.358), stationKey = "it:22021:468")
        val line = Line("BUS:201", "201", TransportMode.BUS, listOf("r1"), "Bolzano - Merano", color = 0xFF0055AA, textColor = 0xFFFFFFFF)
        repo.addRecentPlace(PlacesRepository.run { stop.toPlace() })

        repo.addSearchedStop(stop)
        now = now.plusSeconds(60); repo.addSearchedLine(line)
        assertEquals(
            listOf(SearchHistoryItem.OfLine(line.copy(routeIds = emptyList())), SearchHistoryItem.OfStop(stop)),
            repo.searchHistory().first(),
        )

        // Opening the stop again moves it to the top instead of adding it twice.
        now = now.plusSeconds(60); repo.addSearchedStop(stop)
        assertEquals(listOf("stop:it:22021:468", "line:BUS:201"), repo.searchHistory().first().map { it.key })

        repo.clearSearchHistory()
        assertEquals(emptyList<SearchHistoryItem>(), repo.searchHistory().first())
        assertEquals(1, repo.recentPlaces().first().size)
    }
}
