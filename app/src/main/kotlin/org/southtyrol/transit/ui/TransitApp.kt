package org.southtyrol.transit.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.scaleOut
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
    fun trip(tripId: String, serviceDate: String, liveRef: String = "") = nav.navigate(TripRoute(tripId, serviceDate, liveRef)) { launchSingleTop = true }
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

    // Sub-views slide in from the end; switching tabs (and reduced motion) is a quick fade.
    // Back follows the system's predictive-back look (as in the stock Settings app): while the gesture
    // is held, the current screen shrinks to a rounded card with the previous one visible behind it;
    // on release it slides away. Navigation Compose seeks these pop transitions with the gesture, so
    // the curves keep most of the gesture range in the "shrunk card" phase.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(180)) else slideInHorizontally(tween(300)) { it / 5 } + fadeIn(tween(300))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced || isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(300)) { -it / 10 } + fadeOut(tween(200))
    }
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(180))
        else slideInHorizontally(tween(BACK_MS, easing = BackParallax)) { -it / 10 } + fadeIn(tween(BACK_MS, easing = BackParallax), initialAlpha = 0.6f)
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced || isTabSwitch()) fadeOut(tween(120))
        else scaleOut(tween(BACK_MS, easing = BackShrink), targetScale = 0.9f) +
            slideOutHorizontally(tween(BACK_MS, easing = BackSlide)) { it } +
            fadeOut(tween(BACK_MS, easing = BackSlide))
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
            screen<PlanRoute> { PlannerScreen(navigator) }
            screen<DeparturesRoute> { DeparturesScreen(navigator) }
            screen<MapRoute> { MapScreen(navigator) }
            screen<AlertsRoute> { AlertsScreen(navigator) }
            screen<SettingsRoute> { SettingsScreen(navigator) }
            screen<SavedRoute> { SavedScreen(navigator) }
            screen<ResultsRoute> { ResultsScreen(navigator) }
            screen<JourneyRoute> { JourneyDetailScreen(navigator) }
            screen<StopRoute> { e -> e.toRoute<StopRoute>().let { StopScreen(navigator, it.stationKey, it.name) } }
            screen<TripRoute> { e -> e.toRoute<TripRoute>().let { TripScreen(navigator, it.tripId, it.serviceDate, it.liveRef) } }
            screen<LinesRoute> { LinesScreen(navigator) }
            screen<LineRoute> { e -> LineScreen(navigator, e.toRoute<LineRoute>().key) }
            screen<AboutRoute> { AboutScreen(navigator) }
            screen<TicketsRoute> { TicketsScreen(navigator) }
        }
    }
}

private const val BACK_MS = 400

/** Scale reaches 90% in the first half of the back gesture, then holds while the card slides away. */
private val BackShrink = androidx.compose.animation.core.Easing { f -> (f / 0.5f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) } }

/** Barely moves while the gesture is held (a small nudge), then slides off quickly on release. */
private val BackSlide = androidx.compose.animation.core.Easing { f ->
    if (f < 0.6f) 0.06f * (f / 0.6f) else 0.06f + 0.94f * ((f - 0.6f) / 0.4f).let { it * it }
}

/** The screen behind settles from a slight offset while the card shrinks. */
private val BackParallax = androidx.compose.animation.core.Easing { f -> (f / 0.6f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) } }

/**
 * A destination whose content gets rounded corners while it leaves (e.g. shrinking into a card on
 * predictive back). Entering and resting screens stay square.
 */
private inline fun <reified T : Any> androidx.navigation.NavGraphBuilder.screen(
    noinline content: @Composable androidx.compose.animation.AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable<T> { entry ->
    // Same timing as the shrink, so during a predictive-back gesture the corners round as the card shrinks.
    val radius by transition.animateDp(
        transitionSpec = { tween(BACK_MS, easing = BackShrink) },
        label = "backCorners",
    ) { state -> if (state == androidx.compose.animation.EnterExitState.PostExit) 32.dp else 0.dp }
    // The screen being revealed underneath starts dimmed and brightens as the card shrinks away, so the
    // card's edge stays visible even where both screens share the same background (as in stock Settings).
    val scrim by transition.animateFloat(
        transitionSpec = { tween(BACK_MS, easing = BackParallax) },
        label = "backScrim",
    ) { state -> if (state == androidx.compose.animation.EnterExitState.PreEnter) 0.32f else 0f }
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(radius)),
    ) {
        content(entry)
        if (scrim > 0f) androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = scrim)),
        )
    }
}
