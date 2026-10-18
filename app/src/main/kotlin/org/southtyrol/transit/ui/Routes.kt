package org.southtyrol.transit.ui

import kotlinx.serialization.Serializable

@Serializable object PlanRoute
@Serializable object MapRoute
@Serializable object AlertsRoute
@Serializable object SavedRoute

/** [request] is a [org.southtyrol.transit.feature.planner.RequestCodec]-encoded journey request. */
@Serializable data class ResultsRoute(val request: String)
@Serializable data class JourneyRoute(val request: String, val journeyId: String)
/** The journey being followed; it lives in [org.southtyrol.transit.follow.ActiveJourney], not in the route. */
@Serializable object FollowRoute
@Serializable data class StopRoute(val stationKey: String, val name: String = "")
@Serializable data class TripRoute(val tripId: String, val serviceDate: String, val liveRef: String = "")
@Serializable object LinesRoute
@Serializable data class LineRoute(val key: String)
@Serializable object SettingsRoute
@Serializable object AboutRoute
@Serializable object LicensesRoute
@Serializable object TicketsRoute
