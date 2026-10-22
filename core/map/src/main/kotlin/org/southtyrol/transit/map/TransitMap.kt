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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.MapBase
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

/** A line on the map; [opacity] below 1 marks a part already travelled (see RouteProgress). */
@Immutable
data class MapPolyline(val id: String, val points: List<Point>, val color: Long, val widthDp: Float = 5f, val dashed: Boolean = false, val opacity: Float = 1f)

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

/**
 * A base map: a vector style per theme, optionally with [raster] tiles laid over it (below every transit
 * layer). The three URLs must be one design in different colours - the same roads, labels and places in
 * every theme - so a style without a dark variant uses its light one throughout. [blackUrl] is for dark
 * mode with pure black backgrounds ([LocalMapPureBlack]). [credit] is the data credit [MapAttribution]
 * shows, and must name every source drawn.
 */
@Immutable
data class MapStyle(
    val lightUrl: String,
    val darkUrl: String,
    val blackUrl: String = darkUrl,
    val raster: RasterTiles? = null,
    val credit: String = OSM_CREDIT,
)

/** XYZ raster tiles ({z}/{x}/{y} in [urlTemplate]), requested only inside [bounds] and from [minZoom]. */
@Immutable
data class RasterTiles(val urlTemplate: String, val bounds: BoundingBox, val minZoom: Float, val maxZoom: Float)

private const val OSM_CREDIT = "© OpenStreetMap · OpenFreeMap"
private const val PROVINCE_CREDIT = "$OSM_CREDIT · Prov. Bozen-Bolzano"
private const val OFM = "https://tiles.openfreemap.org/styles/"

/** Province of Bolzano WMTS (keyless, provincial geodata under CC0), Web Mercator grid; see API_DISCOVERY.md. */
private fun provinceTiles(layer: String, format: String) = RasterTiles(
    urlTemplate = "https://geoservices.buergernetz.bz.it/mapproxy/root/service?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0" +
        "&LAYER=$layer&STYLE=default&TILEMATRIXSET=EPSG_3857&FORMAT=$format&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}",
    // The layers' own extent; outside it (and zoomed far out) the vector style underneath shows.
    bounds = BoundingBox(south = 46.17, west = 10.35, north = 47.15, east = 12.53),
    minZoom = 10f,
    maxZoom = 19f,
)

/** The style for this base map; [standard] is the app's default style (a build may override its URLs). */
fun MapBase.style(standard: MapStyle): MapStyle = when (this) {
    MapBase.STANDARD -> standard
    // Liberty has no dark variant (Fiord is Positron's, with far less detail): the same map in every theme.
    MapBase.DETAILED -> MapStyle(lightUrl = OFM + "liberty", darkUrl = OFM + "liberty")
    MapBase.AERIAL -> standard.copy(raster = provinceTiles("p_bz-Orthoimagery:Aerial-2023-RGB", "image/png"), credit = PROVINCE_CREDIT)
    MapBase.TOPOGRAPHIC -> standard.copy(raster = provinceTiles("p_bz-BasemapImagery:TopographicMap-Dynamic", "image/png"), credit = PROVINCE_CREDIT)
}

/** Positron and its two dark recolourings: Fiord for dark mode, Dark Matter for pure black. */
val LocalMapStyle = staticCompositionLocalOf {
    MapStyle(
        lightUrl = OFM + "positron",
        darkUrl = OFM + "fiord",
        blackUrl = OFM + "dark",
    )
}

/** Dark mode with pure black backgrounds is on: the map uses [MapStyle.blackUrl]. */
val LocalMapPureBlack = staticCompositionLocalOf { false }

/**
 * Interactive transit map. Stops and vehicles cluster when zoomed out; tapping a marker reports it,
 * tapping a cluster zooms in. [compassTopMargin] moves the compass (top end, shown once the map is
 * rotated) below what the caller lays over the top of the map - the status bar of an edge-to-edge map, a
 * search bar, a button in that corner; null keeps the SDK's margin. A caller that
 * places the credit itself (in its own overlay column) passes [attribution] false and shows [MapAttribution].
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
    compassTopMargin: Dp? = null,
    attribution: Boolean = true,
) {
    Box(modifier) {
        MapLibreTransitMap(content, Modifier.fillMaxSize(), camera, darkTheme, interactive, onMarkerClick, onViewportChanged, compassTopMargin)
        if (attribution) MapAttribution(darkTheme, Modifier.align(attributionAlignment).padding(attributionPadding))
    }
}

/** Required data credit (ODbL, plus the Province for its layers) shown compactly instead of the SDK's info button. */
@Composable
fun MapAttribution(darkTheme: Boolean, modifier: Modifier = Modifier) {
    Text(
        LocalMapStyle.current.credit,
        style = MaterialTheme.typography.labelSmall,
        color = if (darkTheme) Color(0xCCFFFFFF) else Color(0xCC000000),
        modifier = modifier
            .background(if (darkTheme) Color(0x66000000) else Color(0x99FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
