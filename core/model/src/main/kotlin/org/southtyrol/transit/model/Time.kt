package org.southtyrol.transit.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object GtfsTime {
    /** Parses `H:MM:SS` / `HH:MM:SS`, allowing hours ≥ 24 for trips running past midnight. */
    fun seconds(value: String): Int {
        val parts = value.trim().split(':')
        require(parts.size == 3) { "Invalid GTFS time '$value'" }
        val h = parts[0].toInt(); val m = parts[1].toInt(); val s = parts[2].toInt()
        require(h in 0..240 && m in 0..59 && s in 0..59) { "Invalid GTFS time '$value'" }
        return h * 3600 + m * 60 + s
    }

    fun format(seconds: Int): String = "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

    /**
     * GTFS times are measured from "noon minus 12h" of the service day, which differs from midnight
     * on DST transition days.
     */
    fun instant(date: LocalDate, seconds: Int, zone: ZoneId = TransitZone): Instant =
        date.atTime(12, 0).atZone(zone).toInstant().minusSeconds(43200).plusSeconds(seconds.toLong())

    /** Inverse of [instant]: seconds since the service-day origin of [date]. */
    fun secondsSinceOrigin(date: LocalDate, at: Instant, zone: ZoneId = TransitZone): Long =
        at.epochSecond - instant(date, 0, zone).epochSecond

    fun date(value: String): LocalDate = LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)

    fun dateKey(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

    fun fromDateKey(key: Int): LocalDate = LocalDate.of(key / 10000, key / 100 % 100, key % 100)
}

/** A calendar.txt row. */
data class CalendarRule(val id: String, val start: LocalDate, val end: LocalDate, val weekdays: Set<DayOfWeek>) {
    fun active(date: LocalDate, exceptions: Map<LocalDate, Boolean> = emptyMap()): Boolean =
        exceptions[date] ?: (!date.isBefore(start) && !date.isAfter(end) && date.dayOfWeek in weekdays)

    companion object {
        /** Weekday bitmask with Monday = bit 0. */
        fun mask(days: Set<DayOfWeek>) = days.fold(0) { acc, d -> acc or (1 shl (d.value - 1)) }
        fun days(mask: Int) = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
    }
}

/**
 * Resolves which service ids run on a date given calendar rules and calendar_dates exceptions
 * (true = exception_type 1 "added", false = exception_type 2 "removed"). Services that only exist
 * in calendar_dates are supported.
 */
object ServiceCalendar {
    fun activeServices(date: LocalDate, rules: Collection<CalendarRule>, exceptions: Map<String, Boolean>): Set<String> {
        val result = HashSet<String>()
        for (rule in rules) if (rule.active(date)) result += rule.id
        for ((service, added) in exceptions) if (added) result += service else result -= service
        return result
    }
}
