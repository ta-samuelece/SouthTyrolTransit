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
import org.southtyrol.transit.model.TripDetail
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A journey the user started following. [request] is the [org.southtyrol.transit.feature.planner.RequestCodec]
 * text it was planned from, kept to search for another way when the plan breaks.
 */
data class FollowSession(val journey: Journey, val request: String, val useLocation: Boolean)

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

/**
 * The one active journey, in memory only: if Android ends the app, the journey is gone. The foreground
 * service computes [view]; the screen and the notification only read it.
 */
@Singleton
class ActiveJourney @Inject constructor() {
    private val _session = MutableStateFlow<FollowSession?>(null)
    val session: StateFlow<FollowSession?> = _session.asStateFlow()
    private val _view = MutableStateFlow<FollowView?>(null)
    val view: StateFlow<FollowView?> = _view.asStateFlow()

    /** Replaces any journey being followed. */
    fun start(session: FollowSession) {
        _view.value = null
        _session.value = session
    }

    fun stop() {
        _session.value = null
        _view.value = null
    }

    /** Ignored when [session] is no longer the active one (a late result of a replaced journey). */
    internal fun publish(session: FollowSession, view: FollowView) {
        if (_session.value === session) _view.value = view
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
        return try {
            ContextCompat.startForegroundService(context, Intent(context, FollowJourneyService::class.java))
            true
        } catch (e: Exception) {
            // Background start restrictions (Android 12+) or a disallowed service type.
            active.stop()
            false
        }
    }

    fun stop() = active.stop()
}
