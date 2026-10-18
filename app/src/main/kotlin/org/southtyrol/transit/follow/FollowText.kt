package org.southtyrol.transit.follow

import android.content.res.Resources
import org.southtyrol.transit.R
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.LineNames
import org.southtyrol.transit.model.OffPlan
import org.southtyrol.transit.model.StepKind
import java.time.Duration
import java.time.Instant
import org.southtyrol.transit.design.R as DesignR

/**
 * The words of a followed journey, shared by the notification and the screen so both always say the
 * same. Plain [Resources], no Compose: the notification is built in the service.
 */
object FollowText {
    fun time(res: Resources, instant: Instant): String = Format.time(instant, res.configuration.locales[0])

    /** "Bus 201", "Train R"; the mode alone when the line has no short name. */
    fun line(res: Resources, leg: Leg): String {
        val mode = res.getString(Format.modeName(leg.mode))
        val name = LineNames.badge(leg.line, leg.mode)
        return if (name.isBlank()) mode else res.getString(R.string.follow_line, mode, name)
    }

    /** "in 6 min", or "now" once due. */
    fun inTime(res: Resources, at: Instant, now: Instant): String {
        val minutes = minutes(at, now)
        return if (minutes <= 0) res.getString(R.string.follow_in_now) else res.getQuantityString(R.plurals.follow_in_minutes, minutes, minutes)
    }

    /** The status bar chip: "6 min", "Now". */
    fun chip(res: Resources, at: Instant, now: Instant): String {
        val minutes = minutes(at, now)
        return if (minutes <= 0) res.getString(DesignR.string.ds_now) else res.getQuantityString(R.plurals.follow_chip_minutes, minutes, minutes)
    }

    /** "Live" or "Scheduled", as the screen's freshness pill says. */
    fun source(res: Resources, view: FollowView): String =
        res.getString(if (view.live) DesignR.string.ds_live else DesignR.string.ds_scheduled)

    /** Whole minutes until [at], rounded up, so "1 min" shows until the moment itself. */
    private fun minutes(at: Instant, now: Instant): Int =
        Math.floorDiv(Duration.between(now, at).seconds + 59, 60L).coerceIn(0, 999).toInt()

    fun title(res: Resources, view: FollowView): String {
        val step = view.step
        val leg = view.journey.legs[step.legIndex]
        view.offPlan?.let { return res.getString(R.string.follow_off_title) }
        return when (step.kind) {
            StepKind.RIDE, StepKind.BOARD -> res.getString(R.string.follow_n_line_to, line(res, leg), leg.destination.ifBlank { leg.to.name })
            StepKind.WALK -> res.getString(R.string.follow_n_walk, step.target.name)
            StepKind.ARRIVED -> res.getString(R.string.follow_arrived)
        }
    }

    fun text(res: Resources, view: FollowView, now: Instant): String {
        view.offPlan?.let { return offPlan(res, view.journey, it) }
        val step = view.step
        val legs = view.journey.legs
        val target = step.target.name
        return when (step.kind) {
            StepKind.RIDE ->
                res.getString(if (step.change) R.string.follow_n_change else R.string.follow_n_get_off, target, inTime(res, step.at, now))
            StepKind.BOARD -> res.getString(R.string.follow_n_leaves, target, inTime(res, step.at, now))
            StepKind.WALK -> legs.getOrNull(step.legIndex + 1)?.takeIf { it.mode.isTransit }
                ?.let { res.getString(R.string.follow_n_walk_then, line(res, it), inTime(res, step.at, now)) }
                ?: res.getString(R.string.follow_n_arrive, inTime(res, step.at, now))
            StepKind.ARRIVED -> res.getString(R.string.follow_arrived_body, target, time(res, step.at))
        }
    }

    fun offPlan(res: Resources, journey: Journey, off: OffPlan): String {
        val leg = journey.legs[off.legIndex]
        return when (off) {
            is OffPlan.LegCancelled -> res.getString(R.string.follow_off_cancelled, line(res, leg), time(res, leg.departure))
            is OffPlan.ConnectionBroken -> res.getString(R.string.follow_off_connection, line(res, leg), time(res, leg.bestDeparture))
            is OffPlan.MissedBoarding -> res.getString(R.string.follow_off_missed, line(res, leg), time(res, leg.bestDeparture))
            is OffPlan.OffRoute -> res.getString(R.string.follow_off_route)
        }
    }
}
