@file:OptIn(ExperimentalMaterial3Api::class)

package org.southtyrol.transit.whatsnew

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.southtyrol.transit.BuildConfig
import org.southtyrol.transit.R
import org.southtyrol.transit.feature.common.MarkdownText
import javax.inject.Inject

val WhatsNewIcon.vector: ImageVector
    get() = when (this) {
        WhatsNewIcon.SPARKLE -> Icons.Rounded.AutoAwesome
        WhatsNewIcon.PLACE -> Icons.Rounded.Place
        WhatsNewIcon.BUS -> Icons.Rounded.DirectionsBus
        WhatsNewIcon.TRAIN -> Icons.Rounded.Train
        WhatsNewIcon.MAP -> Icons.Rounded.Map
        WhatsNewIcon.LAYERS -> Icons.Rounded.Layers
        WhatsNewIcon.ROUTE -> Icons.Rounded.Route
        WhatsNewIcon.BIKE -> Icons.Rounded.PedalBike
        WhatsNewIcon.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
        WhatsNewIcon.CAR -> Icons.Rounded.DirectionsCar
        WhatsNewIcon.SEARCH -> Icons.Rounded.Search
        WhatsNewIcon.STAR -> Icons.Rounded.Star
        WhatsNewIcon.NOTIFICATION -> Icons.Rounded.Notifications
        WhatsNewIcon.ALERT -> Icons.Rounded.Warning
        WhatsNewIcon.WIDGET -> Icons.Rounded.Widgets
        WhatsNewIcon.LANGUAGE -> Icons.Rounded.Language
        WhatsNewIcon.SPEED -> Icons.Rounded.Speed
        WhatsNewIcon.DOWNLOAD -> Icons.Rounded.Download
        WhatsNewIcon.FIX -> Icons.Rounded.BugReport
        WhatsNewIcon.PALETTE -> Icons.Rounded.Palette
        WhatsNewIcon.TICKET -> Icons.Rounded.ConfirmationNumber
        WhatsNewIcon.SCHEDULE -> Icons.Rounded.Schedule
    }

/** The coloured card at the top of a sheet: what is happening, the version, and a line or two about it. */
@Composable
fun WhatsNewHero(
    icon: ImageVector,
    title: String,
    version: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    content: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    trailing: @Composable RowScope.() -> Unit = {},
    details: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .background(container, RoundedCornerShape(28.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = content)
            Text(title, style = MaterialTheme.typography.titleSmall, color = content, modifier = Modifier.weight(1f))
            trailing()
        }
        // A preview's long version number does not fit at the full size.
        val versionSize = if (version.length > SHORT_VERSION) 34.sp else 48.sp
        Text(version, fontSize = versionSize, lineHeight = versionSize * 1.1f, fontWeight = FontWeight.SemiBold, color = content)
        details()
    }
}

private const val SHORT_VERSION = 8

/**
 * The scrolling part of a sheet: the highlight cards and the way to the full notes or, for a release
 * without cards, the plain notes. Whatever sits above and below it stays in place.
 */
@Composable
fun ColumnScope.WhatsNewBody(highlights: List<LocalizedHighlight>, notes: String, heading: String, onAllChanges: () -> Unit) {
    Column(
        Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (highlights.isEmpty()) {
            if (notes.isNotBlank()) MarkdownText(notes, Modifier.padding(horizontal = 4.dp))
            return@Column
        }
        Text(heading, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            highlights.forEachIndexed { i, card -> HighlightCard(card, cardShape(i, highlights.size)) }
        }
        if (notes.isNotBlank()) {
            TextButton(onClick = onAllChanges, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.update_all_changes), modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun cardShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) 20.dp else 4.dp
    val bottom = if (index == count - 1) 20.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@Composable
private fun HighlightCard(card: LocalizedHighlight, shape: RoundedCornerShape) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, shape).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(card.icon.vector, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (card.body.isNotBlank()) Text(card.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The buttons under a sheet, on a divider; they never scroll away. */
@Composable
fun WhatsNewFooter(buttons: @Composable RowScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = buttons,
    )
}

/** The full notes, under its own title, with a way back to the cards. Fills the sheet. */
@Composable
fun WhatsNewAllChanges(title: String, subtitle: String?, notes: String, onBack: () -> Unit, footer: @Composable RowScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxHeight()) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ExpandLess, contentDescription = stringResource(R.string.update_back_to_highlights))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MarkdownText(notes, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp))
        WhatsNewFooter(footer)
    }
}

@HiltViewModel
class WhatsNewViewModel @Inject constructor(private val bundled: BundledWhatsNew) : ViewModel() {
    suspend fun load(): WhatsNew = bundled.load()
}

/**
 * The running build's own notes, offline: Settings' "What's new in this version", and on the Play build the
 * "New in X" sheet after an update, which passes its own [heroTitle].
 */
@Composable
fun WhatsNewReadSheet(onDismiss: () -> Unit, heroTitle: String? = null, viewModel: WhatsNewViewModel = hiltViewModel()) {
    val language = LocalLocale.current.platformLocale.language
    var content by remember { mutableStateOf<LocalizedWhatsNew?>(null) }
    LaunchedEffect(language) { content = viewModel.load().inLanguage(language) }
    val loaded = content ?: return
    val version = BuildConfig.VERSION_NAME
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val footer: @Composable RowScope.() -> Unit = {
        Spacer(Modifier.weight(1f))
        Button(onClick = close) { Text(stringResource(R.string.update_got_it)) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (showAll) {
            WhatsNewAllChanges(stringResource(R.string.update_whats_new_in, version), null, loaded.notes, onBack = { showAll = false }, footer = footer)
        } else {
            WhatsNewHero(Icons.Rounded.AutoAwesome, heroTitle ?: stringResource(R.string.update_whats_new), version)
            WhatsNewBody(loaded.highlights, loaded.notes, stringResource(R.string.update_whats_new_in, version), onAllChanges = { showAll = true })
            WhatsNewFooter(footer)
        }
    }
}
