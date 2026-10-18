package org.southtyrol.transit.feature.follow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import org.southtyrol.transit.model.StepKind
import org.southtyrol.transit.model.TransportMode
import java.time.Instant

/**
 * The journey being followed, minimised: a strip under the navigation bar on every screen but the
 * Following screen itself, as the notification words it. One button: tapping it opens the journey. It
 * paints the gesture area below it; [container] is its colour, which the bar's rounded corners show.
 */
@Composable
fun JourneyStrip(view: FollowView?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalResources.current
    val now = rememberNow(1_000)
    val off = view?.offPlan
    val content = contentColor(view)
    // A whole sentence for TalkBack, as the notification says it.
    val description = view?.let { FollowText.title(res, it) + ". " + FollowText.text(res, it, now) } ?: stringResource(R.string.follow_strip_open)
    val openLabel = stringResource(R.string.follow_strip_open_action)
    CompositionLocalProvider(LocalContentColor provides content) {
        Column(modifier.fillMaxWidth().background(journeyStripColor(view))) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
                    .clearAndSetSemantics { contentDescription = description }
                    .padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
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
                Text(
                    view?.let { FollowText.strip(res, it) } ?: stringResource(R.string.follow_strip_open),
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                when {
                    off != null -> Text(stringResource(R.string.follow_find_another_way), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    step == null -> Unit
                    step.kind == StepKind.ARRIVED -> Text(Format.time(step.at), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    else -> Countdown(view, now)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            // The gesture area below takes the strip's colour; the navigation bar no longer pads for it.
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** The countdown to the step: live green with the live glyph only when a live time backs it; a delay as "+3". */
@Composable
private fun Countdown(view: FollowView, now: Instant) {
    val status = stripStatus()
    val delayMinutes = (view.step.delaySeconds ?: 0) / 60
    if (delayMinutes > 0) Text("+$delayMinutes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = status.delayed)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (view.live) Icons.Rounded.Podcasts else Icons.Rounded.Schedule, contentDescription = null, modifier = Modifier.size(16.dp), tint = if (view.live) status.live else LocalContentColor.current)
        Text(Format.countdown(view.step.at, now), style = TimeStyles.medium, fontWeight = FontWeight.SemiBold, color = if (view.live) status.live else Color.Unspecified, maxLines = 1)
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
fun journeyStripColor(view: FollowView?): Color = when {
    view?.offPlan != null -> MaterialTheme.colorScheme.error
    LocalDarkTheme.current -> Color.Black
    else -> MaterialTheme.colorScheme.inverseSurface
}

@Composable
private fun contentColor(view: FollowView?): Color = when {
    view?.offPlan != null -> MaterialTheme.colorScheme.onError
    // On black, the dark theme's own text colour; on the light theme's inverse surface, its pair.
    LocalDarkTheme.current -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.inverseOnSurface
}
