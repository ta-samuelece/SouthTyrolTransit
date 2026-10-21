package org.southtyrol.transit.follow

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.southtyrol.transit.data.LanguageProvider
import org.southtyrol.transit.data.LiveTrip
import org.southtyrol.transit.data.RealtimeRepository
import org.southtyrol.transit.data.TripRepository
import org.southtyrol.transit.location.LocationProvider
import org.southtyrol.transit.location.LocationResult
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.JourneyFollow
import org.southtyrol.transit.model.LiveJourney
import org.southtyrol.transit.model.OffPlan
import org.southtyrol.transit.model.StepKind
import org.southtyrol.transit.model.TripDetail
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Follows one journey until it ends: matches its transit legs to timetable runs, polls realtime and the
 * journey planner's live times of every leg still ahead, reads the position when allowed, and publishes
 * a [FollowView] to [ActiveJourney] on every change and at least every [RECOMPUTE_MS]. Stops the journey itself once [JourneyFollow.finished].
 */
@Singleton
class FollowEngine @Inject constructor(
    private val trips: TripRepository,
    private val realtime: RealtimeRepository,
    private val language: LanguageProvider,
    private val location: LocationProvider,
    private val active: ActiveJourney,
) {
    suspend fun run(session: FollowSession) = coroutineScope {
        val runs = MutableStateFlow<Map<Int, TripDetail>>(emptyMap())
        val fix = MutableStateFlow<LocationResult.Found?>(null)
        val planner = MutableStateFlow<Map<Int, LiveTrip>>(emptyMap())
        val startedAt = Instant.now()
        // The journey with the latest times, so polling skips legs already arrived.
        var latest = session.journey
        launch {
            // The timetable may still be downloading: try again until some leg matches.
            while (true) {
                runs.value = trips.forJourney(session.journey, language.current())
                if (runs.value.isNotEmpty()) break
                delay(MATCH_RETRY_MS)
            }
        }
        launch {
            while (true) {
                if (runs.value.isNotEmpty()) trips.pollRealtime()
                delay(POLL_MS)
            }
        }
        launch {
            // The planner's delays as of planning go stale: fetch them again, as the trip screen does. A
            // failed or empty answer keeps the last one; an arrived leg keeps its last times.
            while (true) {
                val now = Instant.now()
                val ahead = latest.legs.withIndex().filter { (_, leg) -> leg.mode.isTransit && leg.liveRef.isNotBlank() && leg.bestArrival.isAfter(now) }
                val fetched = ahead.mapNotNull { (i, leg) -> trips.liveTrip(leg.liveRef)?.takeIf { it.stops.isNotEmpty() }?.let { i to it } }.toMap()
                if (fetched.isNotEmpty()) planner.value = planner.value + fetched
                delay(POLL_MS)
            }
        }
        if (session.useLocation) launch { location.updates(LOCATION_INTERVAL_MS, LOCATION_DISTANCE_M).collect { fix.value = it } }
        val ticks = flow { while (true) { emit(Unit); delay(RECOMPUTE_MS) } }
        // A location-based finding must hold for a while: a train dwelling at the platform is not a missed train.
        var suspected: Pair<OffPlan, Instant>? = null
        combine(runs, realtime.snapshot, planner, fix, ticks) { byLeg, snapshot, live, position, _ -> Inputs(byLeg, snapshot, live, position) }
            .collect { (byLeg, snapshot, live, position) ->
                val now = Instant.now()
                // The planner's latest live times replace those it planned with, however old: a failed
                // fetch keeps the last delay, only its freshness ages. GTFS-RT predictions then win.
                val refreshed = live.mapNotNull { (i, trip) -> LiveJourney.applyPlanner(session.journey.legs[i], trip.stops)?.let { i to (it to trip.fetchedAt) } }.toMap()
                val planned = if (refreshed.isEmpty()) session.journey else session.journey.copy(legs = session.journey.legs.mapIndexed { i, leg -> refreshed[i]?.first ?: leg })
                val merged = byLeg.mapValues { (_, run) -> trips.merge(run, snapshot, now) }
                val journey = LiveJourney.apply(planned, merged)
                latest = journey
                if (JourneyFollow.finished(journey, now)) {
                    active.stop()
                    return@collect
                }
                val step = JourneyFollow.step(journey, now)
                val found = JourneyFollow.offPlan(journey, now, position?.point, position?.accuracyMeters)
                val offPlan = when (found) {
                    is OffPlan.MissedBoarding, is OffPlan.OffRoute -> {
                        val since = suspected?.takeIf { it.first == found }?.second ?: now
                        suspected = found to since
                        found.takeIf { Duration.between(since, now).seconds >= CONFIRM_SECONDS }
                    }
                    else -> { suspected = null; found }
                }
                // The times that matter now: the step's leg, or for a walk the leg it leads to.
                val index = if (step.kind == StepKind.WALK) step.nextTransitIndex ?: step.legIndex else step.legIndex
                val run = merged[index]
                // GTFS-RT first; otherwise the planner's live times as of their last fetch (or the start),
                // live only while recent. A matched run without a prediction must not hide the planner's delay.
                val runLive = run?.freshness == Freshness.LIVE
                val plannerLive = journey.legs[index].realtime
                val plannerAt = refreshed[index]?.second ?: startedAt
                val freshness = when {
                    runLive -> Freshness.LIVE
                    plannerLive -> if (Duration.between(plannerAt, now) < PLANNER_LIVE) Freshness.LIVE else Freshness.STALE
                    else -> run?.freshness ?: Freshness.SCHEDULED
                }
                active.publish(
                    session,
                    FollowView(
                        journey = journey,
                        step = step,
                        offPlan = offPlan,
                        runs = merged,
                        fix = position?.point,
                        freshness = freshness,
                        updatedAt = if (runLive || !plannerLive) run?.observedAt else plannerAt,
                    ),
                )
            }
    }

    private data class Inputs(
        val runs: Map<Int, TripDetail>,
        val snapshot: org.southtyrol.transit.model.RealtimeSnapshot,
        val planner: Map<Int, LiveTrip>,
        val fix: LocationResult.Found?,
    )

    private companion object {
        const val RECOMPUTE_MS = 15_000L
        const val POLL_MS = 30_000L
        const val MATCH_RETRY_MS = 120_000L
        const val LOCATION_INTERVAL_MS = 10_000L
        const val LOCATION_DISTANCE_M = 20f
        const val CONFIRM_SECONDS = 30L

        /** How long the planner's live times count as live: the age at which GTFS-RT predictions expire. */
        val PLANNER_LIVE: Duration = Duration.ofSeconds(org.southtyrol.transit.model.FreshnessPolicy.TRIP_UPDATE_MAX_AGE)
    }
}
