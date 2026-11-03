@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The lip's title and its rank, which sets the direction it slides in. */
@Immutable
private data class LipTitle(val text: String, val level: Int)

/**
 * The top lip of a tab's main view: a band in the secondary container, from under the status bar, holding
 * the title between a button on each side, with the page below as a sheet in `surface` whose top corners are
 * rounded like the hero (28 dp).
 *
 * [lipContent], when given, stays in the lip under the title row (a view's own dashboard), [lipContentGap]
 * above the sheet. [expandedContent]
 * opens below it, at its full height, and pushes the sheet down; only a screen too short for it makes it
 * scroll. While it is open and [modal], a touch or the start of a scroll anywhere on the sheet calls
 * [onDismiss] and goes no further; a non-modal panel (a search field filtering the sheet) leaves the sheet
 * usable. Controls in the lip sit on the secondary container, so unselected toggles need the `surface` tone
 * and selected ones `primary`, or they vanish into the lip.
 *
 * The title slides up to a new text when [titleLevel] rises and back down when it falls, so a view can step
 * from its name to its question and on to an open panel. [start] and [end] are 48 dp icon buttons.
 */
@Composable
fun TopLip(
    title: String,
    start: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleLevel: Int = 0,
    expanded: Boolean = false,
    onDismiss: () -> Unit = {},
    modal: Boolean = true,
    lipContentGap: Dp = 24.dp,
    lipContent: (@Composable ColumnScope.() -> Unit)? = null,
    expandedContent: @Composable ColumnScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    Column(modifier.fillMaxSize().background(colors.secondaryContainer)) {
        CompositionLocalProvider(LocalContentColor provides colors.onSecondaryContainer) {
            Row(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                start()
                AnimatedContent(
                    targetState = LipTitle(title, titleLevel),
                    transitionSpec = {
                        val up = targetState.level >= initialState.level
                        (slideInVertically(motion.defaultSpatialSpec()) { if (up) it else -it } + fadeIn(motion.defaultEffectsSpec()))
                            .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { if (up) -it else it } + fadeOut(motion.fastEffectsSpec()))
                            .using(SizeTransform(clip = true))
                    },
                    contentAlignment = Alignment.Center,
                    label = "lipTitle",
                    modifier = Modifier.weight(1f),
                ) { shown ->
                    Text(
                        shown.text,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                    )
                }
                end()
            }
            if (lipContent != null) {
                // The open panel follows 12 dp below the dashboard; on its own the dashboard ends the lip [lipContentGap] below.
                val gap by animateDpAsState(if (expanded) 12.dp else lipContentGap, motion.defaultSpatialSpec(), label = "lipGap")
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = gap),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = lipContent,
                )
            }
            AnimatedVisibility(
                expanded,
                enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = expandedContent,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Box {
                content()
                if (expanded && modal) {
                    // Over the sheet while the lip is open: the first touch closes it instead of reaching the page.
                    val dismiss by rememberUpdatedState(onDismiss)
                    Box(
                        Modifier.matchParentSize().pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(pass = PointerEventPass.Initial).consume()
                                dismiss()
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        },
                    )
                }
            }
        }
    }
}
