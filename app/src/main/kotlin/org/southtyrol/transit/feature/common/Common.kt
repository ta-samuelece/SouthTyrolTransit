@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.common

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@Composable
fun TopLevelBar(title: String, scrollBehavior: TopAppBarScrollBehavior? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            actions()
        },
        scrollBehavior = scrollBehavior,
    )
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
    val description = listOfNotNull("$modeName ${departure.line}", departure.destination, countdown, time, delay, platform, alertText).joinToString(", ")
    SegmentedListItem(
        onClick = { onClick?.invoke() },
        enabled = true,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description },
        leadingContent = { LineBadge(departure.line, departure.mode, color = departure.color, textColor = departure.textColor) },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (platform != null) Text(platform, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DelayLabel(departure.delaySeconds, departure.state, departure.freshness)
                if (departure.hasAlert) Icon(Icons.Rounded.Warning, contentDescription = null, tint = LocalStatusColors.current.delayed, modifier = Modifier.size(16.dp))
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

/** Expandable service alert card with validity, scope and multilingual text. */
@Composable
fun AlertCard(
    alert: ServiceAlert,
    language: String,
    now: Instant,
    modifier: Modifier = Modifier,
    onLine: ((String) -> Unit)? = null,
    lineNames: Collection<String> = alert.lineNames,
    stopNames: List<String> = emptyList(),
) {
    var expanded by rememberSaveable(alert.id) { mutableStateOf(false) }
    // Keyed by the app language too: after switching language in Settings the restored state must not
    // keep showing the previous one.
    var shownLanguage by rememberSaveable(alert.id, language) { mutableStateOf(language) }
    val status = LocalStatusColors.current
    val header = alert.headers.localized(shownLanguage)
    val body = alert.descriptions.localized(shownLanguage)
    val languages = (alert.headers.keys + alert.descriptions.keys).map { Languages.normalize(it) }.filter { it.isNotBlank() }.distinct().sorted()
    ElevatedCard(modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.clickable { expanded = !expanded }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    if (alert.isDisruption) Icons.Rounded.ReportProblem else Icons.Rounded.Warning,
                    contentDescription = null,
                    tint = if (alert.isDisruption) status.cancelled else status.delayed,
                    modifier = Modifier.padding(top = 2.dp).size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(header.ifBlank { stringResource(R.string.alerts_untitled) }, style = MaterialTheme.typography.titleMedium)
                    Text(validity(alert, now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand))
            }
            val lines = lineNames.distinct().sortedWith(compareBy({ it.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it })).take(12)
            if (lines.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { name ->
                        androidx.compose.material3.AssistChip(onClick = { onLine?.invoke(name) }, label = { Text(name) }, modifier = Modifier.heightIn(min = 32.dp))
                    }
                }
            }
            if (stopNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.alerts_stops, stopNames.take(4).joinToString(", ") + if (stopNames.size > 4) " …" else ""),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
