package org.southtyrol.transit.feature.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    BoxWithConstraints(modifier) {
        val expandedHeight = maxHeight * 0.6f
        val expandedMap: @Composable (Modifier) -> Unit = { m ->
            if (hasMap && state.expanded) MapCard(content, camera, expanded = true, onToggle = { state.expanded = false }, modifier = m.fillMaxWidth().height(expandedHeight))
        }
        Column(Modifier.fillMaxSize()) {
            header()
            if (placement == MapPlacement.TOP) expandedMap(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
            list(Modifier.weight(1f))
            if (placement == MapPlacement.BOTTOM) expandedMap(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
    }
}

enum class MapPlacement { TOP, BOTTOM }

/** The small, static map card for use inside the list; shows nothing while the map is enlarged. */
@Composable
fun CompactMapCard(state: ExpandableMapState, content: MapContent, camera: CameraRequest, modifier: Modifier = Modifier) {
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
            TransitMap(content, Modifier.fillMaxSize(), camera = fitted, darkTheme = LocalDarkTheme.current, interactive = expanded)
            // The small map ignores gestures; a tap anywhere on it enlarges it.
            if (!expanded) Box(Modifier.matchParentSize().clickable(onClickLabel = label, onClick = onToggle))
            FilledTonalIconButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(if (expanded) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull, contentDescription = label)
            }
        }
    }
}
