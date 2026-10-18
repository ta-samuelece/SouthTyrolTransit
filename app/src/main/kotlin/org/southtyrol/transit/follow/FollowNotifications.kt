package org.southtyrol.transit.follow

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import org.southtyrol.transit.MainActivity
import org.southtyrol.transit.R
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.JourneyFollow
import org.southtyrol.transit.model.StepKind
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.work.Notifications
import java.time.Duration
import java.time.Instant

/**
 * The ongoing notification of a followed journey: the current step, a Stop action, and a tap that
 * opens the journey. On Android 16+ it is a Live Update: a progress bar with one segment per leg, a
 * point at each change, and a status bar chip counting down to the step.
 */
object FollowNotifications {
    /** [session] null: the placeholder the service shows while it shuts down. */
    fun build(context: Context, session: FollowSession?, view: FollowView?, now: Instant = Instant.now()): Notification {
        val res = context.resources
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_FOLLOW)
            // Until there is a step: the route of the app mark, as on the always-on display.
            .setSmallIcon(R.drawable.ic_app_mark)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openIntent(context))
            .setContentTitle(res.getString(R.string.follow_title))
        if (session == null) return builder.build()
        builder.addAction(0, res.getString(R.string.follow_n_stop), stopIntent(context))
            // Android 14+ lets the user swipe it away (only media, calls and a few system apps can forbid
            // that): the service posts it again at once, until the journey ends or Stop is tapped.
            .setDeleteIntent(serviceIntent(context, REQUEST_DISMISSED, FollowJourneyService.ACTION_DISMISSED))
        if (view == null) {
            val journey = session.journey
            return builder
                .setContentText(res.getString(R.string.follow_subtitle, FollowText.time(res, journey.bestArrival), journey.legs.last().to.name))
                .build()
        }
        val text = FollowText.text(res, view, now)
        val total = JourneyFollow.totalSeconds(view.journey).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val elapsed = JourneyFollow.elapsedSeconds(view.journey, now).toInt()
        val mode = view.journey.legs[view.step.legIndex].mode
        builder
            // The step's own icon, also shown in the status bar chip: the vehicle, a change, a walk, the flag.
            .setSmallIcon(stepIcon(view))
            .setColor(modeColor(mode))
            .setContentTitle(FollowText.title(res, view))
            .setContentText(text)
            .setSubText(FollowText.source(res, view))
            .setShortCriticalText(FollowText.chip(res, view.step.at, now))
            .setRequestPromotedOngoing(true)
        if (Build.VERSION.SDK_INT >= 36) builder.setStyle(progress(context, view.journey, elapsed, mode))
        else builder.setStyle(NotificationCompat.BigTextStyle().bigText(text)).setProgress(total, elapsed, false)
        return builder.build()
    }

    @DrawableRes
    private fun stepIcon(view: FollowView): Int = when {
        view.step.kind == StepKind.ARRIVED -> R.drawable.ic_follow_arrived
        view.step.kind == StepKind.RIDE && view.step.change -> R.drawable.ic_follow_change
        else -> modeIcon(view.journey.legs[view.step.legIndex].mode)
    }

    @DrawableRes
    private fun modeIcon(mode: TransportMode): Int = when (mode) {
        TransportMode.TRAIN -> R.drawable.ic_mode_train
        TransportMode.TRAM -> R.drawable.ic_mode_tram
        TransportMode.CABLE_CAR -> R.drawable.ic_mode_cable_car
        TransportMode.FUNICULAR -> R.drawable.ic_mode_funicular
        TransportMode.WALK -> R.drawable.ic_mode_walk
        TransportMode.ON_DEMAND -> R.drawable.ic_mode_taxi
        else -> R.drawable.ic_mode_bus
    }

    private fun modeColor(mode: TransportMode): Int = (0xFF000000 or ModeColors.container(mode)).toInt()

    /** [icon] in white on a circle of [color]: the marker riding the progress bar, as vehicles on the map. */
    private fun badge(context: Context, @DrawableRes icon: Int, color: Int): IconCompat {
        val size = (BADGE_DP * context.resources.displayMetrics.density).toInt()
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        val inset = size / 5
        ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
            setBounds(inset, inset, size - inset, size - inset)
            draw(canvas)
        }
        return IconCompat.createWithBitmap(bitmap)
    }

    /**
     * One segment per transit leg in its mode's colour and one grey stretch for the walking and waiting
     * between; the system's gap and the change of colour mark each junction. No points: Android draws them
     * only as rounded squares (it never passes an app icon to them). The marker is the current step's mode
     * on a circle of its colour.
     */
    @RequiresApi(36)
    private fun progress(context: Context, journey: Journey, elapsed: Int, mode: TransportMode): NotificationCompat.ProgressStyle {
        fun offset(at: Instant) = Duration.between(journey.bestDeparture, at).seconds.coerceAtLeast(0).toInt()
        // Lengths and colours, with walks and waits merged into one grey stretch.
        val segments = mutableListOf<Pair<Int, Int>>()
        fun add(length: Int, color: Int) {
            if (length <= 0) return
            val last = segments.lastOrNull()
            if (last != null && last.second == color && color == GREY) segments[segments.lastIndex] = last.first + length to color
            else segments += length to color
        }
        var covered = 0
        journey.legs.forEach { leg ->
            val from = maxOf(offset(leg.bestDeparture), covered)
            val to = offset(leg.bestArrival)
            add(from - covered, GREY)
            if (to > from) {
                add(to - from, if (leg.mode.isTransit) modeColor(leg.mode) else GREY)
                covered = to
            }
        }
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(badge(context, modeIcon(mode), modeColor(mode)))
        segments.forEach { (length, color) -> style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(length).setColor(color)) }
        return style.setProgress(elapsed)
    }

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_OPEN, MainActivity.openFollowIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun stopIntent(context: Context): PendingIntent = serviceIntent(context, REQUEST_STOP, FollowJourneyService.ACTION_STOP)

    private fun serviceIntent(context: Context, request: Int, action: String): PendingIntent = PendingIntent.getService(
        context, request, Intent(context, FollowJourneyService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private const val REQUEST_OPEN = 20
    private const val REQUEST_STOP = 21
    private const val REQUEST_DISMISSED = 22
    private const val GREY = 0xFF90A4AE.toInt()
    private const val BADGE_DP = 24
}
