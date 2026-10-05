package org.southtyrol.transit.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.TransportMode

/*
 * Provider-agnostic map API. Feature code only uses these types; the MapLibre implementation lives
 * in MapLibreTransitMap.kt and can be swapped for another SDK without touching features.
 */

enum class MarkerKind { STOP, VEHICLE, PARKING, BIKE_SHARING, CAR_SHARING, ORIGIN, DESTINATION, USER, ALERT }

@Immutable
data class MapMarker(
    val id: String,
    val point: Point,
    val kind: MarkerKind,
    val label: String = "",
    val mode: TransportMode? = null,
    /** 0xRRGGBB; null uses the mode/kind default. */
    val color: Long? = null,
    val bearing: Float? = null,
    val stale: Boolean = false,
    val selected: Boolean = false,
    /** Emphasised marker, e.g. a saved stop. */
    val highlighted: Boolean = false,
)

@Immutable
data class MapPolyline(val id: String, val points: List<Point>, val color: Long, val widthDp: Float = 5f, val dashed: Boolean = false)

@Immutable
data class MapContent(
    val stops: List<MapMarker> = emptyList(),
    val vehicles: List<MapMarker> = emptyList(),
    val pois: List<MapMarker> = emptyList(),
    val lines: List<MapPolyline> = emptyList(),
    val clusterStops: Boolean = true,
)

/** One-shot camera instruction; a new [key] re-applies an otherwise identical request. */
@Immutable
sealed class CameraRequest {
    abstract val key: Int

    data class Center(val point: Point, val zoom: Double, override val key: Int = 0) : CameraRequest()
    data class Fit(val points: List<Point>, val paddingDp: Int = 48, override val key: Int = 0) : CameraRequest()
}

data class MapViewport(val box: BoundingBox, val zoom: Double, val center: Point)

data class MapStyle(val lightUrl: String, val darkUrl: String)

val LocalMapStyle = staticCompositionLocalOf {
    MapStyle(
        lightUrl = "https://tiles.openfreemap.org/styles/positron",
        darkUrl = "https://tiles.openfreemap.org/styles/dark",
    )
}

/**
 * Interactive transit map. Stops and vehicles cluster when zoomed out; tapping a marker reports it,
 * tapping a cluster zooms in.
 */
@Composable
fun TransitMap(
    content: MapContent,
    modifier: Modifier = Modifier,
    camera: CameraRequest? = null,
    darkTheme: Boolean = false,
    interactive: Boolean = true,
    onMarkerClick: (MapMarker) -> Unit = {},
    onViewportChanged: (MapViewport) -> Unit = {},
    attributionAlignment: Alignment = Alignment.BottomStart,
    attributionPadding: PaddingValues = PaddingValues(6.dp),
) {
    Box(modifier) {
        MapLibreTransitMap(content, Modifier.fillMaxSize(), camera, darkTheme, interactive, onMarkerClick, onViewportChanged)
        // Required data credit (ODbL) shown compactly instead of the SDK's info button.
        Text(
            "© OpenStreetMap · OpenFreeMap",
            style = MaterialTheme.typography.labelSmall,
            color = if (darkTheme) Color(0xCCFFFFFF) else Color(0xCC000000),
            modifier = Modifier.align(attributionAlignment).padding(attributionPadding)
                .background(if (darkTheme) Color(0x66000000) else Color(0x99FFFFFF), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
