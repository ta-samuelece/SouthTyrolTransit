package org.southtyrol.transit.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DepartureBoard
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.DepartureBoard
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.southtyrol.transit.R
import org.southtyrol.transit.data.StartTab
import org.southtyrol.transit.design.LocalReducedMotion
import org.southtyrol.transit.feature.alerts.AlertsScreen
import org.southtyrol.transit.feature.departures.DeparturesScreen
import org.southtyrol.transit.feature.journey.JourneyDetailScreen
import org.southtyrol.transit.feature.journey.ResultsScreen
import org.southtyrol.transit.feature.lines.LineScreen
import org.southtyrol.transit.feature.lines.LinesScreen
import org.southtyrol.transit.feature.map.MapScreen
import org.southtyrol.transit.feature.planner.PlannerScreen
import org.southtyrol.transit.feature.saved.SavedScreen
import org.southtyrol.transit.feature.settings.AboutScreen
import org.southtyrol.transit.feature.settings.SettingsScreen
import org.southtyrol.transit.feature.settings.TicketsScreen
import org.southtyrol.transit.feature.stop.StopScreen
import org.southtyrol.transit.feature.trip.TripScreen
import kotlin.reflect.KClass

enum class TopLevel(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    PLAN(PlanRoute, PlanRoute::class, R.string.nav_plan, Icons.Outlined.Route, Icons.Rounded.Route),
    DEPARTURES(DeparturesRoute, DeparturesRoute::class, R.string.nav_departures, Icons.Outlined.DepartureBoard, Icons.Rounded.DepartureBoard),
    MAP(MapRoute, MapRoute::class, R.string.nav_map, Icons.Outlined.Map, Icons.Rounded.Map),
    ALERTS(AlertsRoute, AlertsRoute::class, R.string.nav_alerts, Icons.Outlined.Notifications, Icons.Rounded.Notifications),
    SETTINGS(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Outlined.Settings, Icons.Rounded.Settings),
    ;

    companion object {
        fun of(tab: StartTab) = when (tab) {
            StartTab.PLAN -> PLAN
            StartTab.DEPARTURES -> DEPARTURES
            StartTab.MAP -> MAP
            StartTab.ALERTS -> ALERTS
            StartTab.SETTINGS -> SETTINGS
        }

        fun of(destination: NavDestination?): TopLevel? = destination?.let { d -> entries.firstOrNull { d.hasRoute(it.routeClass) } }
    }
}

/**
 * Navigation callbacks shared by all screens, so screens never depend on the NavController.
 * The selected tab stays highlighted while its sub-views (stop, trip, line, …) are open.
 */
class Navigator(private val nav: NavHostController, start: TopLevel) {
    var tab by mutableStateOf(start)
        private set

    fun back() { nav.popBackStack() }
    fun results(request: String) = nav.navigate(ResultsRoute(request))
    fun journey(request: String, id: String) = nav.navigate(JourneyRoute(request, id))
    fun stop(stationKey: String, name: String = "") = nav.navigate(StopRoute(stationKey, name)) { launchSingleTop = true }
    fun trip(tripId: String, serviceDate: String) = nav.navigate(TripRoute(tripId, serviceDate)) { launchSingleTop = true }
    fun lines() = nav.navigate(LinesRoute)
    fun line(key: String) = nav.navigate(LineRoute(key)) { launchSingleTop = true }
    fun saved() = nav.navigate(SavedRoute) { launchSingleTop = true }
    fun about() = nav.navigate(AboutRoute) { launchSingleTop = true }
    fun tickets() = nav.navigate(TicketsRoute) { launchSingleTop = true }
    fun plan() = select(TopLevel.PLAN)

    /**
     * Tapping a tab always shows that tab's main view: re-selecting pops its sub-views, switching
     * restores the tab's main view state (scroll, map camera, search) without its old sub-views.
     */
    fun select(target: TopLevel) {
        if (target != tab) {
            tab = target
            nav.navigate(target.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
        nav.popBackStack(target.route, inclusive = false)
    }

    internal fun sync(destination: NavDestination?) { TopLevel.of(destination)?.let { tab = it } }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
    TopLevel.of(initialState.destination) != null && TopLevel.of(targetState.destination) != null

@Composable
fun TransitApp(startTab: StartTab = StartTab.PLAN, openStop: Pair<String, String>? = null, onStopOpened: () -> Unit = {}) {
    val nav = rememberNavController()
    val start = remember { TopLevel.of(startTab) }
    val navigator = remember(nav) { Navigator(nav, start) }
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) { navigator.sync(entry?.destination) }
    // Opened from the widget: show that stop under the Departures tab.
    LaunchedEffect(openStop) {
        val (key, name) = openStop ?: return@LaunchedEffect
        navigator.select(TopLevel.DEPARTURES)
        navigator.stop(key, name)
        onStopOpened()
    }
    val reduced = LocalReducedMotion.current

    // Material shared-axis style: sub-views slide in from the end and back out on (predictive)
    // back; switching tabs is a quick fade-through.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(180)) else slideInHorizontally(tween(300)) { it / 5 } + fadeIn(tween(300))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced || isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(300)) { -it / 10 } + fadeOut(tween(200))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(180)) else slideInHorizontally(tween(300)) { -it / 10 } + fadeIn(tween(300))
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced || isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(300)) { it / 5 } + fadeOut(tween(200))
    }

    // Offers a newer GitHub release (if any) on start; also shows download progress for manual updates.
    org.southtyrol.transit.update.UpdatePrompt()

    NavigationSuiteScaffold(
        navigationItems = {
            TopLevel.entries.forEach { item ->
                val selected = navigator.tab == item
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { navigator.select(item) },
                    icon = { Icon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                    label = { Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
        },
    ) {
        NavHost(
            nav, startDestination = start.route,
            enterTransition = enter, exitTransition = exit, popEnterTransition = popEnter, popExitTransition = popExit,
        ) {
            composable<PlanRoute> { PlannerScreen(navigator) }
            composable<DeparturesRoute> { DeparturesScreen(navigator) }
            composable<MapRoute> { MapScreen(navigator) }
            composable<AlertsRoute> { AlertsScreen(navigator) }
            composable<SettingsRoute> { SettingsScreen(navigator) }
            composable<SavedRoute> { SavedScreen(navigator) }
            composable<ResultsRoute> { ResultsScreen(navigator) }
            composable<JourneyRoute> { JourneyDetailScreen(navigator) }
            composable<StopRoute> { e -> e.toRoute<StopRoute>().let { StopScreen(navigator, it.stationKey, it.name) } }
            composable<TripRoute> { e -> e.toRoute<TripRoute>().let { TripScreen(navigator, it.tripId, it.serviceDate) } }
            composable<LinesRoute> { LinesScreen(navigator) }
            composable<LineRoute> { e -> LineScreen(navigator, e.toRoute<LineRoute>().key) }
            composable<AboutRoute> { AboutScreen(navigator) }
            composable<TicketsRoute> { TicketsScreen(navigator) }
        }
    }
}
