package org.southtyrol.transit.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Easing
import androidx.compose.animation.scaleIn
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.ui.Modifier
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.southtyrol.transit.feature.follow.FollowViewModel
import org.southtyrol.transit.feature.follow.JourneyStrip
import org.southtyrol.transit.feature.follow.journeyStripColor
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
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
    MAP(MapRoute, MapRoute::class, R.string.nav_map, Icons.Outlined.Map, Icons.Rounded.Map),
    ALERTS(AlertsRoute, AlertsRoute::class, R.string.nav_alerts, Icons.Outlined.Notifications, Icons.Rounded.Notifications),
    ;

    companion object {
        fun of(tab: StartTab) = when (tab) {
            StartTab.PLAN -> PLAN
            // Stops was merged into the map's search: a stored choice opens the map.
            StartTab.DEPARTURES -> MAP
            StartTab.MAP -> MAP
            StartTab.ALERTS -> ALERTS
            // Settings is no longer a tab (it opens from the map's search bar menu): a stored choice falls back to Plan.
            StartTab.SETTINGS -> PLAN
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

    /** Set by the map while its search covers the screen; the bottom bar hides meanwhile. */
    var searchOpen by mutableStateOf(false)

    fun back() { nav.popBackStack() }
    fun results(request: String) = nav.navigate(ResultsRoute(request))
    fun journey(request: String, id: String) = nav.navigate(JourneyRoute(request, id))
    fun follow() = nav.navigate(FollowRoute) { launchSingleTop = true }
    fun stop(stationKey: String, name: String = "") = nav.navigate(StopRoute(stationKey, name)) { launchSingleTop = true }
    fun trip(tripId: String, serviceDate: String, liveRef: String = "") = nav.navigate(TripRoute(tripId, serviceDate, liveRef)) { launchSingleTop = true }
    fun lines() = nav.navigate(LinesRoute)
    fun line(key: String) = nav.navigate(LineRoute(key)) { launchSingleTop = true }
    fun saved() = nav.navigate(SavedRoute) { launchSingleTop = true }
    fun settings() = nav.navigate(SettingsRoute) { launchSingleTop = true }
    fun about() = nav.navigate(AboutRoute) { launchSingleTop = true }
    fun licenses() = nav.navigate(LicensesRoute) { launchSingleTop = true }
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
fun TransitApp(
    startTab: StartTab = StartTab.PLAN,
    openStop: Pair<String, String>? = null,
    onStopOpened: () -> Unit = {},
    openFollow: Boolean = false,
    onFollowOpened: () -> Unit = {},
) {
    val nav = rememberNavController()
    val start = remember { TopLevel.of(startTab) }
    val navigator = remember(nav) { Navigator(nav, start) }
    val entry by nav.currentBackStackEntryAsState()
    LaunchedEffect(entry) { navigator.sync(entry?.destination) }
    // Opened from the widget: show that stop under the Map tab, which holds stop search.
    LaunchedEffect(openStop) {
        val (key, name) = openStop ?: return@LaunchedEffect
        navigator.select(TopLevel.MAP)
        navigator.stop(key, name)
        onStopOpened()
    }
    // Opened from the followed journey's notification: show it under the Plan tab, where journeys start.
    LaunchedEffect(openFollow) {
        if (!openFollow) return@LaunchedEffect
        navigator.select(TopLevel.PLAN)
        navigator.follow()
        onFollowOpened()
    }
    val reduced = LocalReducedMotion.current
    val suite = rememberNavigationSuiteScaffoldState()
    val hideBar = navigator.searchOpen && entry?.destination?.hasRoute(MapRoute::class) == true
    LaunchedEffect(hideBar, reduced) {
        when {
            reduced -> suite.snapTo(if (hideBar) NavigationSuiteScaffoldValue.Hidden else NavigationSuiteScaffoldValue.Visible)
            hideBar -> suite.hide()
            else -> suite.show()
        }
    }

    // Sub-views slide in from the end; switching tabs (and reduced motion) is a quick fade.
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(180)) else slideInHorizontally(tween(300)) { it / 5 } + fadeIn(tween(300))
    }
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced || isTabSwitch()) fadeOut(tween(120)) else slideOutHorizontally(tween(300)) { -it / 10 } + fadeOut(tween(200))
    }
    // Back, by gesture or button, looks like the system's predictive back: the closing screen shrinks to a
    // rounded card that leans away from the swiped edge, the previous screen settles behind it, and on
    // release the card fades out. Navigation Compose seeks these with the gesture and, on release, plays
    // the rest; the card stays opaque for the first half and is gone by 80%, so it never lingers.
    // The gesture needs its own predictive transitions: without them the library's default (a shrink with
    // no fade) runs, which is what left the closed card on top after the gesture.
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(180)) else backEnter()
    }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (reduced || isTabSwitch()) fadeOut(tween(120)) else backExit(BackEventCompat.EDGE_LEFT)
    }
    val predictivePopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition = {
        if (reduced || isTabSwitch()) fadeIn(tween(BACK_MS)) else backEnter()
    }
    val predictivePopExit: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition = { edge ->
        if (reduced || isTabSwitch()) fadeOut(tween(BACK_MS, easing = BackFade)) else backExit(edge)
    }

    // Offers a newer GitHub release (if any) on start; also shows download progress for manual updates.
    org.southtyrol.transit.update.UpdatePrompt()

    // The followed journey, minimised: a strip under the bar on every screen but its own and the map search.
    val follow = hiltViewModel<FollowViewModel>()
    val followed by follow.session.collectAsStateWithLifecycle()
    val followView by follow.view.collectAsStateWithLifecycle()
    val showStrip = followed != null && !hideBar && entry?.destination?.hasRoute(FollowRoute::class) != true
    val stripColor = journeyStripColor(followView)
    Column(
        // Behind the bar's rounded bottom corners the strip's colour shows: its "lips".
        Modifier.fillMaxSize().then(if (showStrip) Modifier.background(stripColor) else Modifier),
    ) {
        NavigationSuiteScaffold(
            modifier = Modifier.weight(1f).then(
                if (showStrip) {
                    // The strip pads for the gesture area, so the bar does not.
                    Modifier.consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                        .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                } else {
                    Modifier
                },
            ),
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
            state = suite,
        ) {
            NavHost(
                nav, startDestination = start.route,
                enterTransition = enter, exitTransition = exit, popEnterTransition = popEnter, popExitTransition = popExit,
                predictivePopEnterTransition = predictivePopEnter, predictivePopExitTransition = predictivePopExit,
            ) {
                screen<PlanRoute> { PlannerScreen(navigator) }
                screen<MapRoute> { MapScreen(navigator) }
                screen<AlertsRoute> { AlertsScreen(navigator) }
                screen<SettingsRoute> { SettingsScreen(navigator) }
                screen<SavedRoute> { SavedScreen(navigator) }
                screen<ResultsRoute> { ResultsScreen(navigator) }
                screen<JourneyRoute> { JourneyDetailScreen(navigator) }
                screen<FollowRoute> { org.southtyrol.transit.feature.follow.FollowScreen(navigator) }
                screen<StopRoute> { e -> e.toRoute<StopRoute>().let { StopScreen(navigator, it.stationKey, it.name) } }
                screen<TripRoute> { e -> e.toRoute<TripRoute>().let { TripScreen(navigator, it.tripId, it.serviceDate, it.liveRef) } }
                screen<LinesRoute> { LinesScreen(navigator) }
                screen<LineRoute> { e -> LineScreen(navigator, e.toRoute<LineRoute>().key) }
                screen<AboutRoute> { AboutScreen(navigator) }
                screen<LicensesRoute> { org.southtyrol.transit.feature.settings.LicensesScreen(navigator) }
                screen<TicketsRoute> { TicketsScreen(navigator) }
            }
        }
        AnimatedVisibility(
            visible = showStrip,
            enter = if (reduced) EnterTransition.None else expandVertically(tween(250)) + fadeIn(tween(250)),
            exit = if (reduced) ExitTransition.None else shrinkVertically(tween(200)) + fadeOut(tween(200)),
        ) {
            // The last state stays while it slides away after the journey ended.
            val last = remember { mutableStateOf(followView) }
            if (followView != null) last.value = followView
            JourneyStrip(last.value, onOpen = navigator::follow)
        }
    }
}

private const val BACK_MS = 350

/**
 * Easings over the whole back transition, whose first part the gesture drives (release usually comes
 * between 20% and 50%) and whose rest plays after release.
 */
/** The card reaches 90% in the first third, then holds. */
private val BackShrink = Easing { f -> (f / 0.35f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) } }

/** Opaque while held (as the system's card), then fades out fast: gone at 80%, so never left on top. */
private val BackFade = Easing { f -> ((f - 0.5f) / 0.3f).coerceIn(0f, 1f) }

/** The screen behind settles from a slight shrink while the card shrinks away. */
private val BackParallax = Easing { f -> (f / 0.6f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) } }

/** The closing screen: shrinks to a card leaning away from the swiped edge, then fades out. */
private fun backExit(edge: Int): ExitTransition {
    val away = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
    return scaleOut(tween(BACK_MS, easing = BackShrink), targetScale = 0.9f) +
        slideOutHorizontally(tween(BACK_MS, easing = BackShrink)) { away * it / 16 } +
        fadeOut(tween(BACK_MS, easing = BackFade))
}

/** The screen revealed behind it: grows from 95% as the card leaves. */
private fun backEnter(): EnterTransition = scaleIn(tween(BACK_MS, easing = BackParallax), initialScale = 0.95f)

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
