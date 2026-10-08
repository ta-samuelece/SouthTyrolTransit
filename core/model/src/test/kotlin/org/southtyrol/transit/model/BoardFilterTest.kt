package org.southtyrol.transit.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class BoardFilterTest {
    private val t0 = Instant.parse("2026-10-08T07:00:00Z")
    private fun dep(line: String, route: String, platform: String) = Departure("t$line$platform", platform, 1, route, line, TransportMode.BUS, "x", t0)
    private val board = listOf(dep("8", "r8", "p1"), dep("8", "r8", "p2"), dep("6", "r6", "p1"), dep("9", "efa:9", "p2"))
    private val line8 = Line("8", "8", TransportMode.BUS, listOf("r8"))
    private val line9 = Line("9", "9", TransportMode.BUS, listOf("r9"))

    @Test fun noFilterKeepsEverything() = assertEquals(board, BoardFilter.apply(board, emptyList(), null))

    @Test fun linesMatchByRouteOrName() {
        // Line 9 matches the network departure by name although its route id differs.
        assertEquals(listOf("8", "8", "9"), BoardFilter.apply(board, listOf(line8, line9), null).map { it.line })
    }

    @Test fun directionKeepsItsPlatformsAndCombinesWithLines() {
        val towardsP2 = StopDirection(setOf("p2"), listOf("Via Gaismair"))
        assertEquals(listOf("p2", "p2"), BoardFilter.apply(board, emptyList(), towardsP2).map { it.stopId })
        assertEquals(listOf("8"), BoardFilter.apply(board, listOf(line8), towardsP2).map { it.line })
    }
}
