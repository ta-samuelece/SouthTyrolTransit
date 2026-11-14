@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.southtyrol.transit.feature.common

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.FitInsets
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.TransitMap

/** Whether the map in a [MapLipPage] is open: large, live and draggable. Survives configuration changes. */
@Stable
class MapLipState(initiallyOpen: Boolean = false) {
    var open by mutableStateOf(initiallyOpen)
}

@Composable
fun rememberMapLipState(): MapLipState = rememberSaveable(saver = Saver({ it.open }, { MapLipState(it) })) { MapLipState() }

/**
 * Whether a [MapLipPage]'s lip should slim down for [listState]: once the list has scrolled a little, and
 * back only when a scroll reaches the very top, so the lip's own growing or shrinking (which can leave
 * nothing to scroll) never flips it back by itself. Same rule as the Alerts dashboard.
 */
@Composable
fun rememberLipSlim(listState: LazyListState): Boolean {
    var slim by rememberSaveable { mutableStateOf(false) }
    val after = with(LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(listState) {
        snapshotFlow { Triple(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, listState.isScrollInProgress) }
            .collect { (index, offset, scrolling) ->
                if (!slim && (index > 0 || offset > after)) slim = true
                else if (slim && scrolling && index == 0 && offset == 0) slim = false
            }
    }
    return slim
}

/** The lip's row of buttons: 12 dp above and 16 dp below a 48 dp button, as in [org.southtyrol.transit.design.TopLip]. */
private val ROW = 76.dp
/** How much map shows below the row while the lip is closed, and while the list has scrolled. */
private val PEEK = 48.dp
private val PEEK_SLIM = 20.dp
/** The sheet's rounded corners; the map runs on under them. */
private val CORNER = 28.dp
/** The open map's share of the page height. */
private const val OPEN_SHARE = 0.65f
/** A drag faster than this, in dp per second, opens or closes the map whatever the distance. */
private const val FLING = 600f

/**
 * A page whose subject has a place on the map (a journey, a ride, a stop): the map fills the lip from under
 * the status bar, with the back button and [end] (the live indicator) on it in the surface tone, and the page
 * below as a [sheet] in `surface` with 28 dp top corners. No title: the sheet says what the page is.
 *
 * Closed, the map is a still strip [PEEK] tall below the buttons ([PEEK_SLIM] while [slim]); a tap on it, a
 * tap on the sheet's drag handle or a drag of the handle opens it at [OPEN_SHARE] of the page, live, and the
 * sheet moves down. A drag back up, a touch on the sheet, or Back closes it again. Without a map ([hasMap]
 * false) the lip is only the row of buttons on the secondary container. [topInset] is the space the lip
 * reaches under (the status bar), 0 for a pane inside another screen; [onBack] null leaves out the button.
 *
 * The map is resized only when it settles, not on every frame of the sheet's movement: MapLibre's surface is
 * slow to resize. Opening, it takes its open height at once and the sheet uncovers it; closing, it keeps that
 * height until the sheet has covered it, then shrinks and fits the camera to the strip again.
 */
@Composable
fun MapLipPage(
    state: MapLipState,
    hasMap: Boolean,
    content: MapContent,
    camera: CameraRequest,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    slim: Boolean = false,
    topInset: Dp = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding(),
    end: @Composable () -> Unit = {},
    sheet: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val open = hasMap && state.open
    BackHandler(enabled = open) { state.open = false }
    BoxWithConstraints(modifier.fillMaxSize().background(colors.secondaryContainer)) {
        val row = topInset + ROW
        val closed = when {
            !hasMap -> row
            slim -> row + PEEK_SLIM
            else -> row + PEEK
        }
        val opened = maxOf(closed, maxHeight * OPEN_SHARE)
        val target = if (open) opened else closed
        val lip = remember { Animatable(target, Dp.VectorConverter) }
        var mapHeight by remember { mutableStateOf(target) }
        var dragging by remember { mutableStateOf(false) }
        // Where the finger holds the sheet while the handle is dragged; null otherwise.
        var held by remember { mutableStateOf<Dp?>(null) }
        val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
        LaunchedEffect(target) {
            if (dragging) return@LaunchedEffect
            mapHeight = maxOf(mapHeight, target)
            lip.animateTo(target, spec)
            mapHeight = target
        }

        if (hasMap) {
            // A new camera key at every new map height fits the content to the part that shows: clear of the
            // buttons (beside them while the strip is narrow, below them once open) and of the sheet's corners.
            val fitted = remember(camera, mapHeight, open, topInset) {
                val key = camera.key * 4099 + mapHeight.value.toInt()
                val insets = if (open) FitInsets(top = row.value.toInt(), bottom = (CORNER + 48.dp).value.toInt(), side = 48)
                else FitInsets(top = (topInset + 24.dp).value.toInt(), bottom = (CORNER + 12.dp).value.toInt(), side = 72)
                when (camera) {
                    is CameraRequest.Fit -> camera.copy(key = key, insets = insets)
                    is CameraRequest.Center -> camera.copy(key = key)
                }
            }
            val openLabel = stringResource(R.string.trip_map_expand)
            Box(Modifier.fillMaxWidth().height(mapHeight + CORNER)) {
                TransitMap(
                    content, Modifier.fillMaxSize(), camera = fitted, darkTheme = LocalDarkTheme.current, interactive = open,
                    // The credit stays clear of the sheet's corners; the compass stays below the buttons.
                    attributionPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = CORNER + 6.dp),
                    compassTopMargin = row,
                )
                // The still map ignores gestures; a tap anywhere on it opens it.
                if (!open) Box(Modifier.matchParentSize().clickable(onClickLabel = openLabel) { state.open = true })
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = topInset)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) FilledIconButton(
                onClick = onBack,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.surface, contentColor = colors.onSurface),
                modifier = Modifier.size(48.dp),
            ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Spacer(Modifier.weight(1f))
            end()
        }

        Surface(
            shape = RoundedCornerShape(topStart = CORNER, topEnd = CORNER),
            color = colors.surface,
            // Placed at the lip's edge in the layout phase, so the sheet's movement recomposes nothing.
            modifier = Modifier.fillMaxSize().layout { measurable, constraints ->
                val y = (held ?: lip.value).roundToPx().coerceIn(0, constraints.maxHeight)
                val height = constraints.maxHeight - y
                val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, y) }
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                if (hasMap) DragHandle(
                    open = open,
                    onToggle = { state.open = !state.open },
                    onDragStart = { dragging = true; mapHeight = opened; held = lip.value },
                    onDrag = { delta -> held = ((held ?: lip.value) + delta).coerceIn(closed, opened) },
                    onDragEnd = { velocity ->
                        val at = held ?: lip.value
                        lip.snapTo(at)
                        held = null
                        dragging = false
                        val toOpen = when {
                            velocity > FLING -> true
                            velocity < -FLING -> false
                            else -> at > (closed + opened) / 2
                        }
                        if (toOpen != state.open) state.open = toOpen
                        else {
                            lip.animateTo(if (toOpen) opened else closed, spec)
                            mapHeight = if (toOpen) opened else closed
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    sheet()
                    if (open) {
                        // Over the sheet while the map is open: the first touch closes it instead of reaching the page.
                        val close by rememberUpdatedState { state.open = false }
                        Box(
                            Modifier.matchParentSize().pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(pass = PointerEventPass.Initial).consume()
                                    close()
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
}

/** A [MapLipPage] with no map yet (loading, an error, nothing to show): the lip is only the back button. */
@Composable
fun NoMapLip(onBack: () -> Unit, modifier: Modifier = Modifier, end: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    MapLipPage(rememberMapLipState(), hasMap = false, MapContent(), CameraRequest.Fit(emptyList()), onBack, modifier, end = end, sheet = content)
}

/**
 * The sheet's drag handle (32 x 4 dp, as on a bottom sheet) across the full width: dragged, it moves the
 * sheet; tapped (TalkBack, switch access), it opens or closes the map.
 */
@Composable
private fun DragHandle(
    open: Boolean,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Dp) -> Unit,
    onDragEnd: suspend (velocityDp: Float) -> Unit,
) {
    val density = LocalDensity.current
    val drag by rememberUpdatedState(onDrag)
    val draggable = rememberDraggableState { delta -> drag(with(density) { delta.toDp() }) }
    Box(
        Modifier.fillMaxWidth().height(32.dp)
            .draggable(
                draggable, Orientation.Vertical,
                onDragStarted = { onDragStart() },
                onDragStopped = { velocity -> onDragEnd(with(density) { velocity.toDp().value }) },
            )
            .clickable(onClickLabel = stringResource(if (open) R.string.trip_map_collapse else R.string.trip_map_expand), onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
    }
}
