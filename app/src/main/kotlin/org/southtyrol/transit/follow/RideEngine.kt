package org.southtyrol.transit.follow

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LiveTrip
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.TripRepository
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.RideFollow
import org.southtyrol.transit.model.RideStage
import org.southtyrol.transit.model.TripDetail
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows one run until it reaches its last stop: loads it from the timetable, polls realtime and the
 * journey planner's live times as the trip screen does, and publishes a [RideView] to [ActiveJourney] on
 * every change and at least every [RECOMPUTE_MS]. Once the run has arrived or is cancelled, and stays so
 * for [RideFollow.ARRIVED_GRACE_SECONDS], it ends following itself and leaves a final notice.
 */
@Singleton
class RideEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trips: TripRepository,
    private val realtime: RealtimeRepository,
    private val language: LanguageProvider,
    private val active: ActiveJourney,
) {
    suspend fun run(ride: RideSession) = coroutineScope {
        val base = MutableStateFlow<TripDetail?>(null)
        val planner = MutableStateFlow<LiveTrip?>(null)
        launch {
            // The timetable may still be downloading: try again until the run is there.
            while (base.value == null) {
                base.value = try {
                    trips.trip(ride.tripId, ride.serviceDate, language.current())?.takeIf { it.stops.isNotEmpty() }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                if (base.value == null) delay(LOAD_RETRY_MS)
            }
        }
        launch {
            var ref = ride.liveRef
            var lastLookup: Instant? = null
            while (true) {
                trips.pollRealtime()
                val detail = base.value
                // Opened without the planner's reference (from the map, a line, any train): find it on its
                // board, as the trip screen does - not while GTFS-RT covers the run, at most every 2 min.
                val now = Instant.now()
                if (ref.isBlank() && detail != null && realtime.snapshot.value.update(detail.trip.id, detail.serviceDate) == null &&
                    lastLookup?.let { Duration.between(it, now) < LOOKUP_EVERY } != true
                ) {
                    lastLookup = now
                    ref = trips.findLiveRef(detail, language.current(), now)
                }
                trips.liveTrip(ref)?.let { planner.value = it }
                delay(POLL_MS)
            }
        }
        val ticks = flow { while (true) { emit(Unit); delay(RECOMPUTE_MS) } }
        // Since when the run has been over (arrived or cancelled): a passing glitch in the data must not end it.
        var overSince: Instant? = null
        combine(base.filterNotNull(), realtime.snapshot, planner, ticks) { detail, snapshot, live, _ -> Triple(detail, snapshot, live) }
            .collect { (detail, snapshot, live) ->
                val now = Instant.now()
                val merged = trips.merge(detail, snapshot, now, live)
                val position = RideFollow.position(merged, now)
                val step = RideFollow.step(merged, position, now)
                val view = RideView(
                    detail = merged,
                    position = position,
                    step = step,
                    stale = merged.vehicle?.let { FreshnessPolicy.vehicle(it.timestamp, now) == Freshness.STALE } == true,
                )
                val over = step.stage == RideStage.ARRIVED || step.stage == RideStage.CANCELLED
                overSince = if (over) overSince ?: now else null
                val since = overSince
                if (RideFollow.expired(merged, now) || since != null && Duration.between(since, now).seconds >= RideFollow.ARRIVED_GRACE_SECONDS) {
                    if (over) FollowNotifications.postRideEnded(context, ride, view)
                    active.stop()
                    return@collect
                }
                active.publish(ride, view)
            }
    }

    private companion object {
        const val RECOMPUTE_MS = 15_000L
        const val POLL_MS = 30_000L
        const val LOAD_RETRY_MS = 120_000L
        val LOOKUP_EVERY: Duration = Duration.ofMinutes(2)
    }
}
