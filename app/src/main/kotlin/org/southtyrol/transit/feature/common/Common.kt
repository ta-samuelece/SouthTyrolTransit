@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.common

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.southtyrol.transit.R
import org.southtyrol.transit.design.Countdown
import org.southtyrol.transit.design.DelayLabel
import org.southtyrol.transit.design.Format
import org.southtyrol.transit.design.LineBadge
import org.southtyrol.transit.design.LocalStatusColors
import org.southtyrol.transit.design.delayDescription
import org.southtyrol.transit.model.Bikes
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.Languages
import org.southtyrol.transit.model.localized
import org.southtyrol.transit.model.localizedLanguage
import java.time.Instant

/**
 * Runs [action] immediately and then every [periodMillis] while the screen is at least STARTED.
 * Polling stops automatically when the app goes to the background or the screen leaves composition.
 */
@Composable
fun PollWhileVisible(periodMillis: Long, key: Any? = Unit, action: suspend () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(action)
    LaunchedEffect(lifecycle, key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                current()
                delay(periodMillis)
            }
        }
    }
}

/** Returns a launcher that asks for foreground location and reports whether it was granted. */
@Composable
fun rememberLocationPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        callback(result.values.any { it })
    }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

/**
 * Top bar of a tab's main view. Settings is not reached from here: it sits in the menu behind the
 * app mark in the map's search bar.
 */
@Composable
fun TopLevelBar(
    title: String,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = { actions() },
        scrollBehavior = scrollBehavior,
    )
}

/**
 * Loads earlier or later items of a list (departures, connections): a small tonal button, centred, so it
 * does not compete with the content. The same control everywhere a list pages in time.
 */
@Composable
fun PageButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    androidx.compose.foundation.layout.Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.FilledTonalButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(androidx.compose.material3.ButtonDefaults.IconSize))
            Spacer(Modifier.width(androidx.compose.material3.ButtonDefaults.IconSpacing))
            Text(label)
        }
    }
}

/**
 * The next departure as the hero of a board: the one number people open the board for, with a large
 * countdown, in the muted secondary container (like the map's location button) so it leads without shouting. Same merged TalkBack sentence as [DepartureRow].
 */
@Composable
fun NextDepartureCard(departure: Departure, now: Instant, onClick: (() -> Unit)?, modifier: Modifier = Modifier, joined: Boolean = false) {
    val delay = delayDescription(departure.delaySeconds, departure.state)
    val platform = departure.platform.takeIf { it.isNotBlank() }?.let { stringResource(org.southtyrol.transit.design.R.string.ds_platform, it) }
    val title = stringResource(R.string.stop_next_departure)
    val alertText = if (departure.hasAlert) stringResource(org.southtyrol.transit.design.R.string.ds_has_alert) else null
    val bikesText = bikesText(departure.bikes)
    val distance = departure.remainingMeters?.let { stringResource(R.string.departure_distance_to, Format.distance(it), departure.destination) }
    val description = listOfNotNull(
        title, "${Format.mode(departure.mode)} ${departure.line}", departure.tripName.ifBlank { null }, departure.destination,
        Format.countdown(departure.best, now), Format.time(departure.best), delay, platform, alertText, bikesText, distance,
    ).joinToString(", ")
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    androidx.compose.material3.Surface(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        // [joined]: the first member of the departure list below - inner (flat) bottom corners and the
        // list's gap, so it reads as the head of the group.
        shape = if (joined) androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
            else androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = content,
        modifier = modifier.fillMaxWidth().padding(bottom = if (joined) ListItemDefaults.SegmentedGap else 0.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LineBadge(departure.line, departure.mode, color = departure.color, textColor = departure.textColor, large = true)
                        // The train number ("R 1834"), when the timetable publishes one.
                        if (departure.tripName.isNotBlank()) Text(departure.tripName, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                    Text(
                        departure.destination, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (platform != null) Text(platform, style = MaterialTheme.typography.labelMedium)
                        DelayLabel(departure.delaySeconds, departure.state, departure.freshness)
                        if (departure.hasAlert) Icon(Icons.Rounded.Warning, contentDescription = null, tint = LocalStatusColors.current.delayed, modifier = Modifier.size(16.dp))
                    }
                    if (departure.bikes != Bikes.UNKNOWN || distance != null) {
                        val pill = MaterialTheme.colorScheme.surfaceContainerLowest
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BikesPill(departure.bikes, pill)
                            if (distance != null) InfoPill(distance, pill, icon = Icons.Rounded.Straighten)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Countdown(departure.scheduled, departure.predicted, departure.state, departure.freshness, now, style = org.southtyrol.transit.design.TimeStyles.display)
            }
        }
    }
}

/**
 * One departure: line identity, destination and platform on the left, the countdown hero on the
 * right. Merged semantics read as one sentence for TalkBack.
 */
@Composable
fun DepartureRow(
    departure: Departure,
    now: Instant,
    index: Int,
    count: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val delay = delayDescription(departure.delaySeconds, departure.state)
    val time = Format.time(departure.best)
    val platform = departure.platform.takeIf { it.isNotBlank() }?.let { stringResource(org.southtyrol.transit.design.R.string.ds_platform, it) }
    val modeName = Format.mode(departure.mode)
    val countdown = if (Format.minutesUntil(departure.best, now) < -1) Format.time(departure.best) else Format.countdown(departure.best, now)
    val alertText = if (departure.hasAlert) stringResource(org.southtyrol.transit.design.R.string.ds_has_alert) else null
    val bikesText = bikesText(departure.bikes)
    val description = listOfNotNull(
        "$modeName ${departure.line}", departure.tripName.ifBlank { null }, departure.destination, countdown, time, delay, platform, alertText, bikesText,
    ).joinToString(", ")
    // A grouped list: each row on its own container, a small gap between rows (none after the last).
    SegmentedListItem(
        onClick = { onClick?.invoke() },
        enabled = true,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = groupedItemColors(),
        // Centred: the row has the three-line minimum height, and top-aligned content left an empty band
        // at the bottom of every row once rows got a visible container.
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .groupGap(index, count)
            .semantics(mergeDescendants = true) { contentDescription = description },
        leadingContent = { LineBadge(departure.line, departure.mode, color = departure.color, textColor = departure.textColor) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val secondary = MaterialTheme.colorScheme.onSurfaceVariant
                if (departure.tripName.isNotBlank()) Text(departure.tripName, style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
                if (platform != null) Text(platform, style = MaterialTheme.typography.labelMedium, color = secondary)
                DelayLabel(departure.delaySeconds, departure.state, departure.freshness)
                if (departure.hasAlert) Icon(Icons.Rounded.Warning, contentDescription = null, tint = LocalStatusColors.current.delayed, modifier = Modifier.size(16.dp))
                // Allowed: the icon alone. Not allowed: the crossed-out icon always with its words.
                when (departure.bikes) {
                    Bikes.ALLOWED -> BikeIcon(allowed = true, tint = secondary)
                    Bikes.NOT_ALLOWED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        BikeIcon(allowed = false, tint = secondary, background = MaterialTheme.colorScheme.surfaceContainer)
                        Text(stringResource(R.string.bikes_not_allowed), style = MaterialTheme.typography.labelMedium, color = secondary, maxLines = 1)
                    }
                    Bikes.UNKNOWN -> Unit
                }
            }
        },
        trailingContent = { Countdown(departure.scheduled, departure.predicted, departure.state, departure.freshness, now) },
    ) {
        Text(
            departure.destination,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (departure.state == ServiceState.CANCELLED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The bike rule in words for TalkBack and pills; null when the timetable says nothing. */
@Composable
fun bikesText(bikes: Bikes): String? = when (bikes) {
    Bikes.ALLOWED -> stringResource(R.string.bikes_allowed)
    Bikes.NOT_ALLOWED -> stringResource(R.string.bikes_not_allowed)
    Bikes.UNKNOWN -> null
}

/**
 * The bicycle icon, crossed out when bikes are not allowed. The slash is cut out of the icon in
 * [background] (the surface it sits on) so it reads at 16 dp.
 */
@Composable
fun BikeIcon(allowed: Boolean, modifier: Modifier = Modifier, tint: androidx.compose.ui.graphics.Color = androidx.compose.material3.LocalContentColor.current, background: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface) {
    Icon(
        Icons.Rounded.PedalBike, contentDescription = null, tint = tint,
        modifier = modifier.size(16.dp).then(
            if (allowed) Modifier else Modifier.drawWithContent {
                drawContent()
                val start = androidx.compose.ui.geometry.Offset(size.width * 0.1f, size.height * 0.1f)
                val end = androidx.compose.ui.geometry.Offset(size.width * 0.9f, size.height * 0.9f)
                drawLine(background, start, end, strokeWidth = 4.dp.toPx())
                drawLine(tint, start, end, strokeWidth = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            },
        ),
    )
}

/** A small pill with an icon and a short text ("Bikes allowed", "31.8 km · 8 stops") on [container]. */
@Composable
fun InfoPill(text: String, container: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, leading: (@Composable () -> Unit)? = null) {
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape, color = container, modifier = modifier) {
        Row(
            Modifier.heightIn(min = 28.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Bikes allowed" / crossed-out "No bikes" as a pill; nothing when the timetable says nothing. */
@Composable
fun BikesPill(bikes: Bikes, container: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    if (bikes == Bikes.UNKNOWN) return
    InfoPill(bikesText(bikes).orEmpty(), container, modifier, leading = { BikeIcon(allowed = bikes == Bikes.ALLOWED, background = container) })
}

/**
 * Grouped (segmented) list items all look alike: each on its own surfaceContainer segment, with
 * [groupGap] between them. The library's default segmented colours are indistinguishable from the page in
 * dark mode, so every `SegmentedListItem` passes these.
 */
@Composable
fun groupedItemColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** The gap after item [index] of [count] in a grouped list (none after the last). */
fun Modifier.groupGap(index: Int, count: Int): Modifier = padding(bottom = if (index < count - 1) ListItemDefaults.SegmentedGap else 0.dp)

/**
 * Corner shape for item [index] of [count] in a grouped list: the group is rounded outside (20 dp) and
 * nearly square between items (4 dp), like the expressive segmented lists.
 */
fun groupShape(index: Int, count: Int): androidx.compose.ui.graphics.Shape {
    val outer = 20.dp
    val inner = 4.dp
    return androidx.compose.foundation.shape.RoundedCornerShape(
        topStart = if (index == 0) outer else inner, topEnd = if (index == 0) outer else inner,
        bottomStart = if (index == count - 1) outer else inner, bottomEnd = if (index == count - 1) outer else inner,
    )
}

/**
 * Expandable service alert card with validity, scope and multilingual text. Severity shows as the
 * icon's container (error for disruptions, tertiary for other notices); affected lines are real line
 * badges where [lineRoutes] knows their mode and colour.
 */
@Composable
fun AlertCard(
    alert: ServiceAlert,
    language: String,
    now: Instant,
    modifier: Modifier = Modifier,
    onLine: ((String) -> Unit)? = null,
    lineNames: Collection<String> = alert.lineNames,
    stopNames: List<String> = emptyList(),
    lineRoutes: Map<String, org.southtyrol.transit.model.Route> = emptyMap(),
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
) {
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    // Keyed by the app language too: after switching language in Settings the restored state must not
    // keep showing the previous one.
    var shownLanguage by rememberSaveable(alert.id, language) { mutableStateOf(language) }
    val header = alert.headers.localized(shownLanguage)
    val body = alert.descriptions.localized(shownLanguage)
    val languages = (alert.headers.keys + alert.descriptions.keys).map { Languages.normalize(it) }.filter { it.isNotBlank() }.distinct().sorted()
    androidx.compose.material3.Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        // TalkBack hears the state ("collapsed") and the action ("double-tap to expand"), not just a chevron.
        val stateText = stringResource(if (expanded) R.string.state_expanded else R.string.state_collapsed)
        Column(
            Modifier
                .clickable(
                    onClickLabel = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                    role = androidx.compose.ui.semantics.Role.Button,
                ) { expanded = !expanded }
                .semantics { stateDescription = stateText }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                val (container, onContainer) = if (alert.isDisruption) {
                    MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                }
                androidx.compose.foundation.layout.Box(
                    Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).background(container),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (alert.isDisruption) Icons.Rounded.ReportProblem else Icons.Rounded.Warning, contentDescription = null, tint = onContainer, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(header.ifBlank { stringResource(R.string.alerts_untitled) }, style = MaterialTheme.typography.titleMedium)
                    Text(validity(alert, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            }
            val lines = lineNames.distinct().sortedWith(compareBy({ it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it })).take(12)
            if (lines.isNotEmpty()) {
                FlowRow(Modifier.padding(start = 54.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { name -> AlertLineBadge(name, lineRoutes[name], onLine) }
                }
            }
            if (stopNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.alerts_stops, stopNames.take(4).joinToString(", ") + if (stopNames.size > 4) " …" else ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 54.dp),
                )
            }
            if (expanded && body.isNotBlank()) {
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (expanded) {
                val uri = LocalUriHandler.current
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (languages.size > 1) {
                        Text(stringResource(R.string.alerts_language), style = MaterialTheme.typography.labelMedium)
                        languages.forEach { lang ->
                            androidx.compose.material3.FilterChip(
                                selected = (alert.headers.localizedLanguage(shownLanguage) ?: alert.descriptions.localizedLanguage(shownLanguage)) == lang,
                                onClick = { shownLanguage = lang },
                                label = { Text(lang.uppercase()) },
                            )
                        }
                    }
                    alert.urls.localized(language).takeIf { it.startsWith("https://") }?.let { url ->
                        androidx.compose.material3.TextButton(onClick = { uri.openUri(url) }) { Text(stringResource(R.string.alerts_more_info)) }
                    }
                }
            }
        }
    }
}

/** An affected line as its badge; tapping it filters by the line where [onLine] is given. */
@Composable
private fun AlertLineBadge(name: String, route: org.southtyrol.transit.model.Route?, onLine: ((String) -> Unit)?) {
    // Lines named only in planner notices have no known mode: a neutral badge without a mode icon.
    val badge = @Composable {
        if (route != null) LineBadge(name, route.mode, color = route.color, textColor = route.textColor)
        else LineBadge(name, org.southtyrol.transit.model.TransportMode.OTHER, showIcon = false)
    }
    if (onLine == null) badge()
    else androidx.compose.foundation.layout.Box(
        Modifier.minimumInteractiveComponentSize().clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Button) { onLine(name) },
        contentAlignment = Alignment.Center,
    ) { badge() }
}

@Composable
internal fun validity(alert: ServiceAlert, now: Instant): String {
    val from = alert.validFrom
    val until = alert.validUntil
    return when {
        from != null && from.isAfter(now) && until != null -> stringResource(R.string.alerts_from_until, Format.dateTime(from), Format.dateTime(until))
        from != null && from.isAfter(now) -> stringResource(R.string.alerts_from, Format.dateTime(from))
        until != null -> stringResource(R.string.alerts_until, Format.dateTime(until))
        else -> stringResource(R.string.alerts_until_further_notice)
    }
}
