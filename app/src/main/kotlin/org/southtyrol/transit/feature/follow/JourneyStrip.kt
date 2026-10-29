package org.southtyrol.transit.feature.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.StatusColors
import org.southtyrol.transit.design.TimeStyles
import org.southtyrol.transit.design.rememberNow
import org.southtyrol.transit.follow.FollowText
import org.southtyrol.transit.follow.FollowView
import org.southtyrol.transit.follow.RideSession
import org.southtyrol.transit.follow.RideView
import org.southtyrol.transit.model.RideStage
import org.southtyrol.transit.model.StepKind
import org.southtyrol.transit.model.TransportMode
import java.time.Instant

/**
 * The journey being followed, minimised: a strip under the navigation bar on every screen but the
 * Following screen itself, as the notification words it. One button: tapping it opens the journey. It
 * paints the gesture area below it, in [journeyStripColor], which the bar's rounded corners show.
 */
@Composable
fun JourneyStrip(view: FollowView?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    val now = rememberNow(1_000)
    val off = view?.offPlan
    // A whole sentence for TalkBack, as the notification says it.
    val description = view?.let { FollowText.title(res, it) + ". " + FollowText.text(res, it, now) } ?: stringResource(R.string.follow_strip_open)
    StripFrame(error = off != null, description, onOpen, modifier) {
        val step = view?.step
        val leg = step?.let { view.journey.legs[it.legIndex] }
        when {
            off != null -> Icon(Icons.Rounded.Error, contentDescription = null)
            step == null || leg == null -> Icon(Icons.Rounded.Navigation, contentDescription = null)
            step.kind == StepKind.ARRIVED -> Icon(Icons.Rounded.Flag, contentDescription = null)
            !leg.mode.isTransit -> Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF000000 or ModeColors.container(TransportMode.WALK))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp)) }
            else -> LineBadge(leg.line, leg.mode, showIcon = false)
        }
        StripText(view?.let { FollowText.strip(res, it) } ?: stringResource(R.string.follow_strip_open))
        when {
            off != null -> Text(stringResource(R.string.follow_find_another_way), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            step == null -> Unit
            step.kind == StepKind.ARRIVED -> Text(Format.time(step.at), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            else -> Countdown(step.at, step.delaySeconds, view.live, now)
        }
    }
}

/**
 * The ride being followed, minimised: the same strip, on every screen but the ride's own trip screen.
 * Tapping it opens that trip. Red when the run is cancelled; the line badge grey and the age instead of
 * a countdown when the vehicle's position is not current.
 */
@Composable
fun RideStrip(ride: RideSession, view: RideView?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    val now = rememberNow(1_000)
    val stage = view?.step?.stage
    val description = FollowText.rideTitle(res, ride, view) + (view?.let { ". " + FollowText.rideText(res, ride, it, now) } ?: "")
    StripFrame(error = stage == RideStage.CANCELLED, description, onOpen, modifier) {
        when (stage) {
            RideStage.CANCELLED -> Icon(Icons.Rounded.Error, contentDescription = null)
            RideStage.ARRIVED -> Icon(Icons.Rounded.Flag, contentDescription = null)
            // The strip is always dark: the dark theme's status-stale.
            else -> LineBadge(ride.line, ride.mode, showIcon = false, color = if (view?.stale == true) stripStatus().stale.toArgb().toLong() and 0xFFFFFF else null)
        }
        StripText(view?.let { FollowText.rideStrip(res, ride, it) } ?: FollowText.rideTitle(res, ride, null))
        val step = view?.step
        when {
            step == null || stage == RideStage.CANCELLED -> Unit
            stage == RideStage.ARRIVED -> Text(Format.time(step.at), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            view.stale && stage == RideStage.MOVING -> view.detail.vehicle?.let { vehicle ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(Format.age(vehicle.timestamp, now), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            else -> Countdown(step.at, step.delaySeconds, view.live, now)
        }
    }
}

/** The strip's frame: one button over the whole row, then the gesture area in the strip's colour. */
@Composable
private fun StripFrame(error: Boolean, description: String, onOpen: () -> Unit, modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    val openLabel = stringResource(R.string.follow_strip_open_action)
    CompositionLocalProvider(LocalContentColor provides contentColor(error)) {
        Column(modifier.fillMaxWidth().background(stripColor(error))) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .clearAndSetSemantics { contentDescription = description }
                    .padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            // The gesture area below takes the strip's colour; the navigation bar no longer pads for it.
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun RowScope.StripText(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
)

/** The countdown to [at]: live green with the live glyph only when a live time backs it; a delay as "+3". */
@Composable
private fun Countdown(at: Instant, delaySeconds: Long?, live: Boolean, now: Instant) {
    val status = stripStatus()
    val delayMinutes = (delaySeconds ?: 0) / 60
    if (delayMinutes > 0) Text("+$delayMinutes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = status.delayed)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (live) Icons.Rounded.Podcasts else Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (live) status.live else LocalContentColor.current)
        Text(Format.countdown(at, now), style = TimeStyles.medium, fontWeight = FontWeight.SemiBold, color = if (live) status.live else Color.Unspecified, maxLines = 1)
    }
}

/** The strip is dark in every theme, so its status colours are always the dark theme's. */
@Composable
private fun stripStatus(): StatusColors = StatusColors.Dark

/**
 * The strip's colour: dark in every theme, as Google Maps' trip strip - the inverse surface in the light
 * theme, black in the dark and pure-black themes - and error when the journey is off plan.
 */
@Composable
fun journeyStripColor(view: FollowView?): Color = stripColor(view?.offPlan != null)

/** [journeyStripColor] for a followed ride: error when the run is cancelled. */
@Composable
fun rideStripColor(view: RideView?): Color = stripColor(view?.step?.stage == RideStage.CANCELLED)

@Composable
private fun stripColor(error: Boolean): Color = when {
    error -> MaterialTheme.colorScheme.error
    LocalDarkTheme.current -> Color.Black
    else -> MaterialTheme.colorScheme.inverseSurface
}

@Composable
private fun contentColor(error: Boolean): Color = when {
    error -> MaterialTheme.colorScheme.onError
    // On black, the dark theme's own text colour; on the light theme's inverse surface, its pair.
    LocalDarkTheme.current -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.inverseOnSurface
}
