package org.southtyrol.transit.follow

import android.content.res.Resources
import org.southtyrol.transit.R
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.LineNames
import org.southtyrol.transit.model.OffPlan
import org.southtyrol.transit.model.RideStage
import org.southtyrol.transit.model.StepKind
import org.southtyrol.transit.model.TransportMode
import java.time.Instant
import org.southtyrol.transit.design.R as DesignR

/**
 * The words of a followed journey or ride, shared by the notification, the strip and the screens so all
 * always say the same. Plain [Resources], no Compose: the notification is built in the service.
 */
object FollowText {
    fun time(res: Resources, instant: Instant): String = Format.time(instant, res.configuration.locales[0])

    /** "Bus 201", "Train R"; the mode alone when the line has no short name. */
    fun line(res: Resources, leg: Leg): String = line(res, leg.mode, leg.line)

    /** [line] for a followed ride. */
    fun line(res: Resources, ride: RideSession): String = line(res, ride.mode, ride.line)

    private fun line(res: Resources, mode: TransportMode, line: String): String {
        val modeName = res.getString(Format.modeName(mode))
        val name = LineNames.badge(line, mode)
        return if (name.isBlank()) modeName else res.getString(R.string.follow_line, modeName, name)
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

    /** Whole minutes until [at], rounded down like every countdown in the app ([Format.minutesUntil]), so they agree. */
    private fun minutes(at: Instant, now: Instant): Int = Format.minutesUntil(at, now).coerceIn(0, 999).toInt()

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

    /** The in-app strip's one line: what to do now, without the countdown it shows beside it. */
    fun strip(res: Resources, view: FollowView): String {
        view.offPlan?.let { return offPlan(res, view.journey, it) }
        val step = view.step
        val target = step.target.name
        return when (step.kind) {
            StepKind.RIDE -> res.getString(if (step.change) R.string.follow_strip_change else R.string.follow_strip_get_off, target)
            StepKind.BOARD -> res.getString(R.string.follow_strip_leaves, target)
            // A walk to a boarding names the line it leads to.
            StepKind.WALK -> res.getString(R.string.follow_n_walk, target) +
                (view.journey.legs.getOrNull(step.legIndex + 1)?.takeIf { it.mode.isTransit }?.let { " · " + LineNames.badge(it.line, it.mode) } ?: "")
            StepKind.ARRIVED -> res.getString(R.string.follow_arrived)
        }
    }

    /** "Bus 201 to Meran/Merano"; "Bus 201 reached Meran/Merano" once there. */
    fun rideTitle(res: Resources, ride: RideSession, view: RideView?): String {
        val line = line(res, ride)
        val end = view?.detail?.stops?.lastOrNull()?.stop?.name
        if (view?.step?.stage == RideStage.ARRIVED && end != null) return res.getString(R.string.ride_n_reached, line, end)
        return res.getString(R.string.follow_n_line_to, line, ride.headsign.ifBlank { end.orEmpty() })
    }

    fun rideText(res: Resources, ride: RideSession, view: RideView, now: Instant): String {
        val step = view.step
        val name = view.detail.stops[step.stopIndex].stop.name
        return when (step.stage) {
            RideStage.WAITING -> res.getString(R.string.follow_n_leaves, name, inTime(res, step.at, now))
            RideStage.MOVING -> if (view.stale) res.getString(R.string.ride_n_last_seen, lastSeen(view)) else res.getString(R.string.ride_n_next, name, inTime(res, step.at, now))
            RideStage.AT_STOP -> res.getString(R.string.ride_n_at, name, inTime(res, step.at, now))
            RideStage.ARRIVED -> res.getString(R.string.ride_n_arrived, time(res, step.at))
            RideStage.CANCELLED -> res.getString(R.string.follow_off_cancelled, line(res, ride), time(res, step.at))
        }
    }

    /** The in-app strip's one line for a ride, without the countdown it shows beside it. */
    fun rideStrip(res: Resources, ride: RideSession, view: RideView): String {
        val step = view.step
        val name = view.detail.stops[step.stopIndex].stop.name
        val estimated = if (view.estimated && step.stage == RideStage.MOVING) " · " + res.getString(R.string.ride_estimated) else ""
        return when (step.stage) {
            RideStage.WAITING -> res.getString(R.string.follow_strip_leaves, name)
            RideStage.MOVING -> if (view.stale) res.getString(R.string.ride_strip_last_seen, lastSeen(view)) else res.getString(R.string.ride_strip_next, name) + estimated
            RideStage.AT_STOP -> res.getString(R.string.ride_strip_at, name)
            RideStage.ARRIVED -> res.getString(R.string.ride_strip_reached, name)
            RideStage.CANCELLED -> res.getString(R.string.follow_off_cancelled, line(res, ride), time(res, step.at))
        }
    }

    /** "Live", "Scheduled" or "Not current", as the freshness pill says. */
    fun rideSource(res: Resources, view: RideView): String = res.getString(
        when {
            view.stale -> DesignR.string.ds_stale
            view.live -> DesignR.string.ds_live
            else -> DesignR.string.ds_scheduled
        },
    )

    /** The call a stale fix last placed the vehicle after. */
    private fun lastSeen(view: RideView): String = view.detail.stops[(view.step.stopIndex - 1).coerceAtLeast(0)].stop.name

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
