package org.southtyrol.transit.model

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/*
 * Car park forecasts from the Open Data Hub (ParkingStation, data types parking-forecast-*). The feed
 * predicts OCCUPIED spaces every 30 minutes up to 4 hours ahead, each with a conservative (low) and an
 * optimistic (high) value; everything here is turned into free spaces, which is what a driver wants.
 */

/** A measured half hour, or the current value. */
data class ParkingSlot(val at: Instant, val free: Int)

/** A forecast half hour: the expected free spaces and the likely range around them. */
data class ForecastSlot(val at: Instant, val free: Int, val freeLow: Int, val freeHigh: Int)

sealed interface ParkingOutlook {
    /** A forecast leaves fewer than [ParkingForecasts.FULL_SHARE] of the spaces free, first at [from]. */
    data class LikelyFull(val from: Instant) : ParkingOutlook
    /** The fewest free spaces forecast, at [at]. */
    data class FillingUp(val free: Int, val at: Instant) : ParkingOutlook
    /** The most free spaces forecast, at [at]. */
    data class Emptying(val free: Int, val at: Instant) : ParkingOutlook
    data object Steady : ParkingOutlook
}

data class ParkingForecast(
    val capacity: Int,
    /** Up to two hours of measured half hours before the current one, oldest first. */
    val past: List<ParkingSlot>,
    val now: ParkingSlot,
    /** Future half hours only, soonest first. */
    val forecast: List<ForecastSlot>,
    val outlook: ParkingOutlook,
)

/** One raw forecast value: [horizon] minutes ahead, the [band] it belongs to, OCCUPIED spaces at [at]. */
data class RawParkingForecast(val horizon: Int, val band: Band, val occupied: Double, val at: Instant) {
    enum class Band { EXPECTED, LOW, HIGH }
}

object ParkingForecasts {
    /**
     * A forecast issued longer ago than this is not shown. Forecasts are issued once an hour (at :05), so
     * a normal one is up to an hour old; past 90 minutes at least one update has been missed.
     */
    const val MAX_AGE = 5400L

    /** How much history the chart shows before the current half hour. */
    val HISTORY: Duration = Duration.ofHours(2)

    /** Below this share of the capacity left free, the car park counts as likely full. */
    const val FULL_SHARE = 0.05

    private const val HALF_HOUR = 1800L

    /**
     * Builds the chart's data, or null when there is nothing worth showing: no usable capacity, a
     * placeholder value (some operators send capacity -1 and 9999 free), no current free count, or no
     * forecast issued within [MAX_AGE] that still reaches into the future.
     */
    fun build(
        capacity: Int?,
        currentFree: Int?,
        history: List<Pair<Instant, Int>>,
        raw: List<RawParkingForecast>,
        now: Instant,
    ): ParkingForecast? {
        val cap = capacity?.takeIf { it > 0 } ?: return null
        val free = currentFree?.takeIf { it in 0..cap * 2 } ?: return null

        // A value for horizon h is issued h minutes before the moment it predicts.
        val expected = raw.filter { it.band == RawParkingForecast.Band.EXPECTED }
        val issued = expected.maxOfOrNull { it.at.minusSeconds(it.horizon * 60L) } ?: return null
        if (Duration.between(issued, now).seconds > MAX_AGE) return null

        val byHorizon = raw.groupBy { it.horizon }
        val forecast = expected.map { e ->
            val band = byHorizon[e.horizon].orEmpty()
            val low = band.firstOrNull { it.band == RawParkingForecast.Band.LOW }?.occupied ?: e.occupied
            val high = band.firstOrNull { it.band == RawParkingForecast.Band.HIGH }?.occupied ?: e.occupied
            // More cars means fewer free spaces, so the range swaps ends.
            val ends = listOf(freeOf(cap, low), freeOf(cap, high), freeOf(cap, e.occupied))
            ForecastSlot(nearestHalfHour(e.at), freeOf(cap, e.occupied), ends.min(), ends.max())
        }.filter { it.at.isAfter(now) }.distinctBy { it.at }.sortedBy { it.at }
        if (forecast.size < 2) return null

        val current = floorHalfHour(now)
        val start = current.minus(HISTORY)
        val past = history
            .filter { (at, value) -> value in 0..cap * 2 && !at.isBefore(start) && at.isBefore(current) }
            .groupBy { floorHalfHour(it.first) }
            .map { (slot, values) -> ParkingSlot(slot, values.maxBy { it.first }.second.coerceIn(0, cap)) }
            .sortedBy { it.at }

        val nowSlot = ParkingSlot(now, free.coerceIn(0, cap))
        return ParkingForecast(cap, past, nowSlot, forecast, outlook(cap, nowSlot.free, forecast))
    }

    /** Full beats filling up beats getting emptier; a change smaller than 5% (at least 10 spaces) is steady. */
    fun outlook(capacity: Int, free: Int, forecast: List<ForecastSlot>): ParkingOutlook {
        forecast.firstOrNull { it.free < capacity * FULL_SHARE }?.let { return ParkingOutlook.LikelyFull(it.at) }
        val threshold = maxOf(10.0, capacity * FULL_SHARE)
        val fewest = forecast.minBy { it.free }
        if (free - fewest.free > threshold) return ParkingOutlook.FillingUp(fewest.free, fewest.at)
        val most = forecast.maxBy { it.free }
        if (most.free - free > threshold) return ParkingOutlook.Emptying(most.free, most.at)
        return ParkingOutlook.Steady
    }

    /** "About 190 free": tens from 50 up, exact below, where a few spaces matter. */
    fun roundFree(free: Int): Int = if (free >= 50) ((free + 5) / 10) * 10 else free

    private fun freeOf(capacity: Int, occupied: Double): Int = (capacity - occupied).roundToInt().coerceIn(0, capacity)

    // Europe/Rome is a whole number of hours from UTC, so half hours line up in either.
    private fun floorHalfHour(at: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(at.epochSecond, HALF_HOUR) * HALF_HOUR)

    private fun nearestHalfHour(at: Instant): Instant = Instant.ofEpochSecond(Math.floorDiv(at.epochSecond + HALF_HOUR / 2, HALF_HOUR) * HALF_HOUR)
}
