package org.southtyrol.transit.follow

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FollowStep
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.OffPlan
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RideStep
import org.southtyrol.transit.model.RunPosition
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.TripDetail
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** What the user follows: a planned journey or a single ride. One at a time. */
sealed interface Followed

/**
 * A journey the user started following. [request] is the [org.southtyrol.transit.feature.planner.RequestCodec]
 * text it was planned from, kept to search for another way when the plan breaks.
 */
data class FollowSession(val journey: Journey, val request: String, val useLocation: Boolean) : Followed

/**
 * One run the user follows to its last stop, picked on the map or on a board. [line], [mode] and
 * [headsign] are what the notification says until the run is loaded; [liveRef] is the journey planner's
 * reference when the run was opened with one.
 */
data class RideSession(
    val tripId: String,
    val serviceDate: LocalDate,
    val liveRef: String,
    val line: String,
    val mode: TransportMode,
    val headsign: String,
) : Followed {
    fun isRun(tripId: String, serviceDate: String): Boolean = this.tripId == tripId && this.serviceDate.toString() == serviceDate
}

/** Where a followed journey stands, recomputed by [FollowEngine] and shown by the screen and the notification. */
data class FollowView(
    /** The journey with the latest live times applied. */
    val journey: Journey,
    val step: FollowStep,
    val offPlan: OffPlan?,
    /** Matched timetable runs by leg index, merged with realtime (for the map). */
    val runs: Map<Int, TripDetail>,
    /** The traveller's last usable position, when location is in use. */
    val fix: Point?,
    /** Freshness of the times behind the current step (its leg, or the leg a walk leads to). */
    val freshness: Freshness,
    val updatedAt: Instant?,
) {
    /** The step's time is a current live time: drawn live (green, live glyph) only then. */
    val live: Boolean get() = freshness == Freshness.LIVE && step.delaySeconds != null
}

/** Where a followed ride stands, recomputed by [RideEngine] and shown by the trip screen, the strip and the notification. */
data class RideView(
    /** The run merged with realtime, as the trip screen shows it. */
    val detail: TripDetail,
    val position: RunPosition?,
    val step: RideStep,
    /** The vehicle's GPS position is too old to trust: drawn grey, never as live. */
    val stale: Boolean,
) {
    /** The step's time is a current live time: drawn live (green, live glyph) only then. */
    val live: Boolean get() = detail.freshness == Freshness.LIVE && step.delaySeconds != null

    /** No GPS fix places the vehicle: its position comes from the times. */
    val estimated: Boolean get() = position?.estimated ?: true
}

/**
 * The one active journey or ride, in memory only: if Android ends the app, it is gone. The foreground
 * service computes [view] and [rideView]; the screens, the strip and the notification only read them.
 */
@Singleton
class ActiveJourney @Inject constructor() {
    private val _followed = MutableStateFlow<Followed?>(null)
    /** Whatever is followed; the service runs while it is set. */
    val followed: StateFlow<Followed?> = _followed.asStateFlow()
    private val _session = MutableStateFlow<FollowSession?>(null)
    val session: StateFlow<FollowSession?> = _session.asStateFlow()
    private val _view = MutableStateFlow<FollowView?>(null)
    val view: StateFlow<FollowView?> = _view.asStateFlow()
    private val _ride = MutableStateFlow<RideSession?>(null)
    val ride: StateFlow<RideSession?> = _ride.asStateFlow()
    private val _rideView = MutableStateFlow<RideView?>(null)
    val rideView: StateFlow<RideView?> = _rideView.asStateFlow()

    /** Replaces any journey or ride being followed. */
    fun start(session: FollowSession) = replace(session)

    /** Replaces any journey or ride being followed. */
    fun start(ride: RideSession) = replace(ride)

    fun stop() = replace(null)

    private fun replace(followed: Followed?) {
        _view.value = null
        _rideView.value = null
        _session.value = followed as? FollowSession
        _ride.value = followed as? RideSession
        _followed.value = followed
    }

    /** Ignored when [session] is no longer the active one (a late result of a replaced journey). */
    internal fun publish(session: FollowSession, view: FollowView) {
        if (_session.value === session) _view.value = view
    }

    /** Ignored when [ride] is no longer the active one. */
    internal fun publish(ride: RideSession, view: RideView) {
        if (_ride.value === ride) _rideView.value = view
    }
}

/** Starts and stops following; the UI's only entry point. */
@Singleton
class FollowController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val active: ActiveJourney,
) {
    /** False when the foreground service could not be started; nothing is followed then. */
    fun start(session: FollowSession): Boolean {
        active.start(session)
        return startService()
    }

    /** False when the foreground service could not be started; nothing is followed then. */
    fun start(ride: RideSession): Boolean {
        active.start(ride)
        return startService()
    }

    private fun startService(): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, FollowJourneyService::class.java))
        true
    } catch (e: Exception) {
        // Background start restrictions (Android 12+) or a disallowed service type.
        active.stop()
        false
    }

    fun stop() = active.stop()
}
