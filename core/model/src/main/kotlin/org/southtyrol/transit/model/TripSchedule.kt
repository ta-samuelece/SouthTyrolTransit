package org.southtyrol.transit.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * A trip the user makes regularly: from [from] to [to] on [days], leaving (or, with [arriveBy], arriving)
 * between [start] and [end] in Europe/Rome time. A window never crosses midnight. [id] is empty until saved.
 */
data class TripSchedule(
    val id: String,
    val name: String,
    val from: Place,
    val to: Place,
    val days: Set<DayOfWeek>,
    val start: LocalTime,
    val end: LocalTime,
    val arriveBy: Boolean,
    val enabled: Boolean,
) {
    enum class Problem { NAME_BLANK, SAME_PLACE, NO_DAYS, START_NOT_BEFORE_END }

    /** One day's window: [start] and [end] are that [date]'s times in [TransitZone]. */
    data class Window(val date: LocalDate, val start: Instant, val end: Instant)

    val problems: Set<Problem> get() = problems(name, from, to, days, start, end)

    /**
     * The window to show at [now]: today's when today is a chosen day and its end is still ahead (a window
     * already under way counts), otherwise the first chosen day after today, up to a week ahead.
     */
    fun nextWindow(now: Instant): Window? {
        val today = now.atZone(TransitZone).toLocalDate()
        for (offset in 0L..7L) {
            val date = today.plusDays(offset)
            if (date.dayOfWeek !in days) continue
            val window = window(date)
            if (offset > 0 || now.isBefore(window.end)) return window
        }
        return null
    }

    fun window(date: LocalDate) = Window(date, date.atTime(start).atZone(TransitZone).toInstant(), date.atTime(end).atZone(TransitZone).toInstant())

    /**
     * The [journeys] inside [window], once each by id, by departure: by the first leg's scheduled departure
     * when leaving between, by the last leg's scheduled arrival when arriving between.
     */
    fun connectionsIn(window: Window, journeys: List<Journey>): List<Journey> = journeys
        .filter { j -> (if (arriveBy) j.arrival else j.departure).let { !it.isBefore(window.start) && !it.isAfter(window.end) } }
        .distinctBy { it.id }
        .sortedBy { it.departure }

    companion object {
        /** What keeps a schedule from being saved. From and To count as the same place as the planner sees it. */
        fun problems(name: String, from: Place?, to: Place?, days: Set<DayOfWeek>, start: LocalTime, end: LocalTime): Set<Problem> = buildSet {
            if (name.isBlank()) add(Problem.NAME_BLANK)
            if (from != null && to != null && from.id == to.id && from.name == to.name) add(Problem.SAME_PLACE)
            if (days.isEmpty()) add(Problem.NO_DAYS)
            if (!start.isBefore(end)) add(Problem.START_NOT_BEFORE_END)
        }
    }
}
