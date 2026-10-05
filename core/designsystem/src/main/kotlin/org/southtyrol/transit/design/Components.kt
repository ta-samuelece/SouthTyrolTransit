@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AccessibleForward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsTransit
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalTaxi
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Tram
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TransportMode
import java.time.Instant

fun TransportMode.icon(): ImageVector = when (this) {
    TransportMode.TRAIN -> Icons.Rounded.Train
    TransportMode.TRAM -> Icons.Rounded.Tram
    TransportMode.CABLE_CAR, TransportMode.FUNICULAR -> Icons.Rounded.DirectionsTransit
    TransportMode.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
    TransportMode.ON_DEMAND -> Icons.Rounded.LocalTaxi
    else -> Icons.Rounded.DirectionsBus
}

/** A ticking clock for countdowns; updates every [periodMillis] while composed. */
@Composable
fun rememberNow(periodMillis: Long = 15_000): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            now = Instant.now()
            delay(periodMillis - System.currentTimeMillis() % periodMillis)
        }
    }
    return now
}

/** Line identity: mode-specific shape + icon + contrast-checked colors. */
@Composable
fun LineBadge(
    name: String,
    mode: TransportMode,
    modifier: Modifier = Modifier,
    color: Long? = null,
    textColor: Long? = null,
    showIcon: Boolean = true,
    large: Boolean = false,
) {
    val (bg, fg) = ModeColors.colors(mode, color, textColor)
    val description = stringResource(R.string.ds_line_a11y, Format.mode(mode), name)
    Row(
        modifier = modifier
            .clip(ModeShapes.badge(mode))
            .background(bg)
            .heightIn(min = if (large) 32.dp else 26.dp)
            .padding(horizontal = if (large) 10.dp else 7.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showIcon) Icon(mode.icon(), contentDescription = null, tint = fg, modifier = Modifier.size(if (large) 20.dp else 16.dp))
        if (name.isNotBlank()) {
            Text(
                name,
                color = fg,
                style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

/** Mode glyph inside an expressive shape, used as list leading content. */
@Composable
fun ModeGlyph(mode: TransportMode, modifier: Modifier = Modifier, color: Long? = null) {
    val (bg, fg) = ModeColors.colors(mode, color, null)
    val shape = when (mode) {
        TransportMode.TRAIN -> MaterialShapes.Square.toShape()
        TransportMode.CABLE_CAR, TransportMode.FUNICULAR -> MaterialShapes.Arch.toShape()
        TransportMode.WALK -> MaterialShapes.Pill.toShape()
        else -> MaterialShapes.Cookie4Sided.toShape()
    }
    Box(modifier.size(40.dp).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Icon(mode.icon(), contentDescription = Format.mode(mode), tint = fg, modifier = Modifier.size(22.dp))
    }
}

/**
 * The departure hero: countdown ("Now", "3 min") with the clock time secondary. Live predictions
 * use the status color and a live glyph; scheduled-only times say so explicitly.
 */
@Composable
fun Countdown(
    scheduled: Instant,
    predicted: Instant?,
    state: ServiceState,
    freshness: Freshness,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val status = LocalStatusColors.current
    val target = predicted ?: scheduled
    val cancelled = state == ServiceState.CANCELLED || state == ServiceState.SKIPPED
    val live = freshness == Freshness.LIVE && predicted != null
    // Departures that have clearly left (shown when browsing earlier times) get their clock time, not "Now".
    val past = Format.minutesUntil(target, now) < -1
    Column(modifier, horizontalAlignment = Alignment.End) {
        val text = if (cancelled) Format.time(scheduled) else if (past) Format.time(target) else Format.countdown(target, now)
        AnimatedContent(text, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "countdown") { value ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (live && !cancelled) Icon(Icons.Rounded.Podcasts, contentDescription = stringResource(R.string.ds_live), tint = status.live, modifier = Modifier.size(16.dp))
                Text(
                    value,
                    style = TimeStyles.large,
                    color = when {
                        cancelled -> status.cancelled
                        past -> MaterialTheme.colorScheme.onSurfaceVariant
                        live -> status.live
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (cancelled) TextDecoration.LineThrough else null,
                    maxLines = 1,
                )
            }
        }
        if (!cancelled && !past && Format.minutesUntil(target, now) < 60) {
            Text(Format.time(target), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "+4", "−1", "On time" or a cancellation label — always text + color, never color alone. */
@Composable
fun DelayLabel(delaySeconds: Long?, state: ServiceState, freshness: Freshness, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    when {
        state == ServiceState.CANCELLED -> StatusPill(stringResource(R.string.ds_cancelled), status.cancelled, Icons.Rounded.Error, modifier)
        state == ServiceState.SKIPPED -> StatusPill(stringResource(R.string.ds_skipped), status.cancelled, Icons.Rounded.Error, modifier)
        state == ServiceState.ADDED -> StatusPill(stringResource(R.string.ds_added), status.early, null, modifier)
        freshness == Freshness.STALE -> StatusPill(stringResource(R.string.ds_stale), status.stale, Icons.Rounded.History, modifier)
        delaySeconds == null -> Unit
        else -> {
            val minutes = Math.floorDiv(delaySeconds + 30, 60L).toInt()
            val description = delayDescription(delaySeconds, state).orEmpty()
            val (text, color) = when {
                minutes >= 1 -> stringResource(R.string.ds_delay_late, minutes) to status.delayed
                minutes <= -1 -> stringResource(R.string.ds_delay_early, -minutes) to status.early
                else -> stringResource(R.string.ds_on_time) to status.onTime
            }
            Text(
                text, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                modifier = modifier.clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

/** Accessibility text for a delay, used by list items that merge their semantics. */
@Composable
fun delayDescription(delaySeconds: Long?, state: ServiceState): String? {
    val minutes = delaySeconds?.let { Math.floorDiv(it + 30, 60L).toInt() }
    return when {
        state == ServiceState.CANCELLED -> stringResource(R.string.ds_cancelled)
        state == ServiceState.SKIPPED -> stringResource(R.string.ds_skipped)
        minutes == null -> null
        minutes >= 1 -> androidx.compose.ui.res.pluralStringResource(R.plurals.ds_delay_late_a11y, minutes, minutes)
        minutes <= -1 -> androidx.compose.ui.res.pluralStringResource(R.plurals.ds_delay_early_a11y, -minutes, -minutes)
        else -> stringResource(R.string.ds_on_time)
    }
}

@Composable
fun StatusPill(text: String, color: Color, icon: ImageVector?, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Data freshness indicator ("Live · updated 20 s ago", "Scheduled", "Not current"). */
@Composable
fun FreshnessIndicator(freshness: Freshness, fetchedAt: Instant?, now: Instant, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    val (label, color, icon) = when (freshness) {
        Freshness.LIVE -> Triple(stringResource(R.string.ds_live), status.live, Icons.Rounded.Podcasts)
        Freshness.SCHEDULED -> Triple(stringResource(R.string.ds_scheduled), MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.Schedule)
        Freshness.STALE -> Triple(stringResource(R.string.ds_stale), status.stale, Icons.Rounded.History)
        Freshness.UNAVAILABLE -> Triple(stringResource(R.string.ds_realtime_unavailable), status.stale, Icons.Rounded.CloudOff)
    }
    val age = fetchedAt?.let { stringResource(R.string.ds_updated_ago, Format.age(it, now)) }
    Row(
        modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(listOfNotNull(label, age).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

enum class BannerKind { INFO, WARNING, ERROR }

/** Non-intrusive inline banner for offline / stale / partial-data states. */
@Composable
fun StatusBanner(
    text: String,
    modifier: Modifier = Modifier,
    kind: BannerKind = BannerKind.WARNING,
    icon: ImageVector = if (kind == BannerKind.ERROR) Icons.Rounded.CloudOff else Icons.Rounded.Warning,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    visible: Boolean = true,
) {
    AnimatedVisibility(visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val (container, content) = when (kind) {
            BannerKind.INFO -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            BannerKind.WARNING -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            BannerKind.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        }
        Surface(modifier.fillMaxWidth(), color = container, contentColor = content, shape = MaterialTheme.shapes.large) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (action != null && onAction != null) {
                    androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** Expressive morphing loading indicator; static progress when animations are disabled. */
@Composable
fun TransitLoading(modifier: Modifier = Modifier, contained: Boolean = false) {
    val label = stringResource(R.string.ds_loading)
    Box(modifier.fillMaxWidth().padding(32.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (LocalReducedMotion.current) {
            androidx.compose.material3.CircularProgressIndicator(progress = { 0.75f })
        } else if (contained) {
            ContainedLoadingIndicator(Modifier.size(64.dp))
        } else {
            LoadingIndicator(Modifier.size(56.dp))
        }
    }
}

/** Empty or error state with an expressive shape-framed glyph. */
@Composable
fun MessageState(
    title: String,
    body: String? = null,
    icon: ImageVector = Icons.Rounded.DirectionsTransit,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(96.dp).clip(MaterialShapes.Cookie9Sided.toShape())
                .background(if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) Button(onClick = onAction, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text(action) }
    }
}

@Composable
fun ErrorState(error: DataError, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    MessageState(
        title = Format.error(error),
        icon = if (error == DataError.Offline) Icons.Rounded.CloudOff else Icons.Rounded.Error,
        modifier = modifier,
        action = if (onRetry != null) stringResource(R.string.ds_retry) else null,
        onAction = onRetry,
        isError = error != DataError.Offline,
    )
}

/** Connected toggle group (Material 3 Expressive) for a small set of exclusive options. */
@Composable
fun <T> ConnectedToggleGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    icon: ((T) -> ImageVector?)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                icon?.invoke(option)?.let {
                    Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun WheelchairIcon(modifier: Modifier = Modifier) {
    Icon(Icons.AutoMirrored.Rounded.AccessibleForward, contentDescription = stringResource(R.string.ds_wheelchair_accessible), modifier = modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable
fun FullScreenLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TransitLoading(contained = true) }
}
