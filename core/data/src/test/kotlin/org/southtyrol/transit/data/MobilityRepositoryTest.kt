package org.southtyrol.transit.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.MobilityDataSource
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.Point
import java.time.Instant

class MobilityRepositoryTest {
    private val now = Instant.parse("2026-10-25T12:00:00Z")

    private class Fake(override val kind: MobilityKind, var points: List<MobilityPoint> = emptyList(), var fail: Boolean = false) : MobilityDataSource {
        var calls = 0
        override suspend fun points(box: BoundingBox, now: Instant): List<MobilityPoint> {
            calls++
            if (fail) throw DataException(DataError.Offline)
            return points
        }
    }

    private fun point(kind: MobilityKind, updatedAt: Instant?) = MobilityPoint(
        "${kind.name}:1", kind, "x", Point(46.5, 11.35), 3, 10, updatedAt, "p", if (updatedAt == null) Freshness.UNAVAILABLE else Freshness.STALE,
    )

    @Test fun aRegionWithoutAnyReportHidesTheLayer() = runBlocking {
        // Values older than a week never reach here (the source drops them); a station with no value stays.
        val bikes = Fake(MobilityKind.BIKE_SHARING, listOf(point(MobilityKind.BIKE_SHARING, null)))
        val parking = Fake(MobilityKind.PARKING)
        assertEquals(setOf(MobilityKind.BIKE_SHARING), MobilityRepository(listOf(bikes, parking)).deadLayers(now))
        // Only the checked kinds are asked.
        assertEquals(0, parking.calls)
    }

    @Test fun oneCurrentStationKeepsTheLayer() = runBlocking {
        val bikes = Fake(MobilityKind.BIKE_SHARING, listOf(point(MobilityKind.BIKE_SHARING, null), point(MobilityKind.BIKE_SHARING, now.minusSeconds(5 * 86_400))))
        assertEquals(emptySet<MobilityKind>(), MobilityRepository(listOf(bikes)).deadLayers(now))
    }

    @Test fun aFailedCheckKeepsTheLayerAndIsAskedAgain() = runBlocking {
        val bikes = Fake(MobilityKind.BIKE_SHARING, fail = true)
        val repo = MobilityRepository(listOf(bikes))
        assertEquals(emptySet<MobilityKind>(), repo.deadLayers(now))
        bikes.fail = false
        assertEquals(setOf(MobilityKind.BIKE_SHARING), repo.deadLayers(now.plusSeconds(60)))
        assertEquals(2, bikes.calls)
    }

    @Test fun aResultIsKeptUntilTheNextCheckIsDue() = runBlocking {
        val bikes = Fake(MobilityKind.BIKE_SHARING)
        val repo = MobilityRepository(listOf(bikes))
        assertEquals(setOf(MobilityKind.BIKE_SHARING), repo.deadLayers(now))
        bikes.points = listOf(point(MobilityKind.BIKE_SHARING, now))
        assertEquals(setOf(MobilityKind.BIKE_SHARING), repo.deadLayers(now.plusSeconds(3600)))
        assertEquals(emptySet<MobilityKind>(), repo.deadLayers(now.plus(MobilityRepository.REGION_CHECK_EVERY)))
        assertEquals(2, bikes.calls)
    }
}
