@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.southtyrol.transit.feature.follow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import org.southtyrol.transit.R
import org.southtyrol.transit.data.JourneyRequest
import org.southtyrol.transit.design.BannerKind
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.FreshnessIndicator
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.MessageState
import org.southtyrol.transit.design.Progress
import org.southtyrol.transit.design.SectionHeader
import org.southtyrol.transit.design.StatusBanner
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.TransitLoading
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.feature.common.CompactMapCard
import org.southtyrol.transit.feature.common.ExpandableMapPage
import org.southtyrol.transit.feature.common.LegStops
import org.southtyrol.transit.feature.common.rememberExpandableMapState
import org.southtyrol.transit.feature.journey.TransferRow
import org.southtyrol.transit.feature.journey.TransitLeg
import org.southtyrol.transit.feature.journey.WalkLeg
import org.southtyrol.transit.feature.journey.rememberJourneyMap
import org.southtyrol.transit.feature.planner.RequestCodec
import org.southtyrol.transit.follow.ActiveJourney
import org.southtyrol.transit.follow.FollowController
import org.southtyrol.transit.follow.FollowSession
import org.southtyrol.transit.follow.FollowText
import org.southtyrol.transit.follow.FollowView
import org.southtyrol.transit.map.MapMarker
import org.southtyrol.transit.map.MarkerKind
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.OffPlan
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.StepKind
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.ui.Navigator
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class FollowViewModel @Inject constructor(active: ActiveJourney, private val controller: FollowController) : ViewModel() {
    val session = active.session
    val view = active.view
    val followed = active.followed
    val ride = active.ride
    val rideView = active.rideView

    fun stop() = controller.stop()

    /**
     * The request to search another way: from where the traveller is (their position, else the stop where
     * the plan broke) to the same destination, leaving now.
     */
    fun anotherWay(session: FollowSession, view: FollowView, now: Instant): String? {
        val request = RequestCodec.decode(session.request, now) ?: return null
        val from = view.fix?.let { Place("", "", PlaceType.COORDINATE, it) }
            ?: view.offPlan?.let { view.journey.legs[it.legIndex].from }
            ?: view.journey.legs[view.step.legIndex].from
        val replan = JourneyRequest(from, request.to, request.options.copy(at = now.atZone(TransitZone), arriveBy = false))
        return RequestCodec.encode(replan, leaveNow = true)
    }
}

/**
 * The journey being followed: what to do now (the step), where the traveller is on the current leg, what
 * comes next, and a warning with a search for another way when the plan breaks. Reads the state the
 * foreground service keeps; shows a message once nothing is followed any more.
 */
@Composable
fun FollowScreen(navigator: Navigator, viewModel: FollowViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val view by viewModel.view.collectAsStateWithLifecycle()
    val now = rememberNow(1_000)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.follow_title), maxLines = 1) },
                subtitle = (view?.journey ?: session?.journey)?.let { j ->
                    { Text(stringResource(R.string.follow_subtitle, Format.time(j.bestArrival), j.legs.last().to.name), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                },
                navigationIcon = { IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = {
                    view?.let { FreshnessIndicator(it.freshness, it.updatedAt, now, Modifier.padding(end = 12.dp), compact = true) }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val current = session
            val shown = view
            when {
                current == null -> MessageState(
                    stringResource(R.string.follow_none_title), stringResource(R.string.follow_none_body),
                    icon = Icons.Rounded.Navigation, action = stringResource(R.string.action_back), onAction = navigator::back,
                )
                shown == null -> TransitLoading(contained = true)
                else -> FollowContent(current, shown, now, navigator, viewModel)
            }
        }
    }
}

@Composable
private fun FollowContent(session: FollowSession, view: FollowView, now: Instant, navigator: Navigator, viewModel: FollowViewModel) {
    val res = LocalResources.current
    val step = view.step
    val leg = view.journey.legs[step.legIndex]
    val user = remember(view.fix) { listOfNotNull(view.fix?.let { MapMarker("user", it, MarkerKind.USER) }) }
    // The map moves with the engine's pace, not with the countdown's every second.
    // Zoomed on the current step: the leg being walked, waited for or ridden.
    val map = rememberJourneyMap(view.journey, view.runs, rememberNow(15_000), user, focus = step.legIndex)
    val mapState = rememberExpandableMapState()
    ExpandableMapPage(mapState, map.hasLines, map.content, map.camera, Modifier.fillMaxSize()) { listModifier ->
        LazyColumn(listModifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            view.offPlan?.let { off ->
                item {
                    StatusBanner(
                        FollowText.offPlan(res, view.journey, off),
                        kind = if (off is OffPlan.OffRoute) BannerKind.WARNING else BannerKind.ERROR,
                        icon = Icons.Rounded.Error,
                        action = stringResource(R.string.follow_find_another_way),
                        onAction = { viewModel.anotherWay(session, view, Instant.now())?.let(navigator::results) },
                    )
                }
            }
            if (map.hasLines && !mapState.expanded) item {
                CompactMapCard(mapState, map.content, map.camera, Modifier.fillMaxWidth().height(140.dp))
            }
            item {
                if (step.kind == StepKind.ARRIVED) {
                    MessageState(stringResource(R.string.follow_arrived), FollowText.text(res, view, now), icon = Icons.Rounded.Flag)
                } else {
                    StepHero(view, leg, now)
                }
            }
            // Every step of the itinerary: done ones dimmed, the current one open with its stops.
            item { SectionHeader(stringResource(R.string.follow_itinerary)) }
            val legs = view.journey.legs
            legs.forEachIndexed { i, each ->
                val previous = legs.getOrNull(i - 1)
                val done = step.kind == StepKind.ARRIVED || i < step.legIndex
                item(key = "leg$i") {
                    Column(Modifier.alpha(if (done) Progress.PASSED_ALPHA else 1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (previous != null && previous.mode.isTransit && each.mode.isTransit) TransferRow(Duration.between(previous.bestArrival, each.bestDeparture))
                        when {
                            !each.mode.isTransit -> WalkLeg(each, now)
                            i == step.legIndex && !done -> CurrentLegCard(view, each, now) { key -> navigator.stop(key) }
                            else -> TransitLeg(each, now, onStop = { key -> navigator.stop(key) })
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { viewModel.stop(); navigator.back() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.follow_stop))
                }
            }
        }
    }
}

/** What to do now, with the countdown to it: live green only when a current live time backs it. */
@Composable
private fun StepHero(view: FollowView, leg: Leg, now: Instant) {
    val step = view.step
    val label = when (step.kind) {
        StepKind.RIDE -> if (step.change) R.string.follow_change_at else R.string.follow_get_off_at
        StepKind.BOARD -> R.string.follow_leaves_from
        else -> R.string.follow_walk_to
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (leg.mode.isTransit) {
                    LineBadge(leg.line, leg.mode, large = true)
                    Text(stringResource(R.string.follow_to, leg.destination.ifBlank { leg.to.name }), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, contentDescription = null)
                    Text(FollowText.text(LocalResources.current, view, now), style = MaterialTheme.typography.titleMedium)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Text(step.target.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
                }
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Format.countdown(step.at, now), style = TimeStyles.display,
                        color = if (view.live) LocalStatusColors.current.live else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.time(step.at), style = MaterialTheme.typography.labelMedium)
                        if (step.delaySeconds != null) DelayLabel(step.delaySeconds, ServiceState.NORMAL, if (view.live) Freshness.LIVE else Freshness.SCHEDULED)
                    }
                }
            }
        }
    }
}

/**
 * The current transit leg, open: its line, then its calls as on the trip screen - passed ones dimmed,
 * the vehicle before the next one while riding (estimated from the times), the last saying get off or
 * change.
 */
@Composable
private fun CurrentLegCard(view: FollowView, leg: Leg, now: Instant, onStop: (String) -> Unit) {
    // Its last stop is a change when another transit leg follows, whether riding it yet or still waiting.
    val change = view.journey.legs.drop(view.step.legIndex + 1).any { it.mode.isTransit }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineBadge(leg.line, leg.mode, large = true)
                Text(leg.destination.ifBlank { leg.to.name }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            LegStops(
                leg, now, expanded = true, onToggle = null, onStop = onStop,
                nextNote = stringResource(R.string.follow_next_stop),
                lastNote = stringResource(if (change) R.string.follow_change_here else R.string.follow_get_off_here),
                live = view.freshness == Freshness.LIVE,
            )
        }
    }
}
