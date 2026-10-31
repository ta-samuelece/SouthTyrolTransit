package org.southtyrol.transit.feature.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.southtyrol.transit.LocalDarkTheme
import org.southtyrol.transit.R
import org.southtyrol.transit.map.CameraRequest
import org.southtyrol.transit.map.MapContent
import org.southtyrol.transit.map.TransitMap

/**
 * Maps inside a scrolling page. Small, the map is a static card in the list (gestures would fight the
 * list's scrolling); a tap enlarges it. Enlarged, it sits above the list at 60% of the height and can be
 * dragged and zoomed; its button or the back gesture shrinks it again.
 */
class ExpandableMapState(initiallyExpanded: Boolean = false) {
    var expanded by mutableStateOf(initiallyExpanded)
}

@Composable
fun rememberExpandableMapState(): ExpandableMapState =
    rememberSaveable(saver = androidx.compose.runtime.saveable.Saver({ it.expanded }, { ExpandableMapState(it) })) { ExpandableMapState() }

/**
 * Lays out a page whose list may contain a map: when [state] is expanded (and [hasMap]), the large
 * interactive map is shown above [list]; [list] receives the modifier to use (it takes the remaining height).
 */
@Composable
fun ExpandableMapPage(
    state: ExpandableMapState,
    hasMap: Boolean,
    content: MapContent,
    camera: CameraRequest,
    modifier: Modifier = Modifier,
    /** Where the enlarged map goes: where its small card is (near the top or the end of the page). */
    placement: MapPlacement = MapPlacement.TOP,
    header: @Composable ColumnScope.() -> Unit = {},
    list: @Composable (Modifier) -> Unit,
) {
    BackHandler(enabled = hasMap && state.expanded) { state.expanded = false }
    // One measure pass, no subcomposition of the whole page: the header, the enlarged map at 60% of the
    // page's height (8 dp from the list, 16 dp from the sides), the list in the rest.
    Layout(
        contents = listOf(
            { Column(Modifier.fillMaxWidth(), content = header) },
            { if (hasMap && state.expanded) MapCard(content, camera, expanded = true, onToggle = { state.expanded = false }) },
            { list(Modifier) },
        ),
        modifier = modifier,
    ) { (headers, maps, lists), constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val side = 16.dp.roundToPx()
        val gap = 8.dp.roundToPx()
        val headerPlaceables = headers.map { it.measure(Constraints(maxWidth = width, maxHeight = height)) }
        val mapHeight = (height * 0.6f).roundToInt()
        val mapPlaceables = maps.map { it.measure(Constraints.fixed((width - 2 * side).coerceAtLeast(0), mapHeight)) }
        val mapSpace = if (mapPlaceables.isEmpty()) 0 else mapHeight + gap
        val listHeight = (height - headerPlaceables.sumOf { it.height } - mapSpace).coerceAtLeast(0)
        val listPlaceables = lists.map { it.measure(Constraints.fixed(width, listHeight)) }
        layout(width, height) {
            var y = 0
            headerPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            if (placement == MapPlacement.TOP) {
                mapPlaceables.forEach { it.placeRelative(side, y + gap) }
                y += mapSpace
            }
            listPlaceables.forEach { it.placeRelative(0, y); y += it.height }
            if (placement == MapPlacement.BOTTOM) mapPlaceables.forEach { it.placeRelative(side, y) }
        }
    }
}

enum class MapPlacement { TOP, BOTTOM }

/** The small, static map card for use inside the list; shows nothing while the map is enlarged. */
@Composable
fun CompactMapCard(state: ExpandableMapState, content: MapContent, camera: CameraRequest, modifier: Modifier = Modifier) {
    // Pins its list item while the page is shown. Unpinned, scrolling the card out of view disposed the
    // map - its onDestroy waits 20-50 ms on the main thread for MapLibre's render thread - and scrolling
    // back built a new one, style and tiles included (Perfetto, 2026-10-31).
    val pinnable = LocalPinnableContainer.current
    DisposableEffect(pinnable) {
        val handle = pinnable?.pin()
        onDispose { handle?.release() }
    }
    if (!state.expanded) MapCard(content, camera, expanded = false, onToggle = { state.expanded = true }, modifier = modifier)
}

@Composable
private fun MapCard(content: MapContent, camera: CameraRequest, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (expanded) R.string.trip_map_collapse else R.string.trip_map_expand)
    // A new camera key on every size change re-fits the content to the new frame.
    val fitted = when (camera) {
        is CameraRequest.Fit -> camera.copy(key = camera.key * 2 + if (expanded) 1 else 0)
        is CameraRequest.Center -> camera.copy(key = camera.key * 2 + if (expanded) 1 else 0)
    }
    Surface(shape = RoundedCornerShape(28.dp), modifier = modifier) {
        Box {
            // The compass shares the top end corner with the enlarge button: keep it below the button.
            TransitMap(content, Modifier.fillMaxSize(), camera = fitted, darkTheme = LocalDarkTheme.current, interactive = expanded, compassTopMargin = 60.dp)
            // The small map ignores gestures; a tap anywhere on it enlarges it.
            if (!expanded) Box(Modifier.matchParentSize().clickable(onClickLabel = label, onClick = onToggle))
            FilledTonalIconButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(if (expanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull, contentDescription = label)
            }
        }
    }
}
