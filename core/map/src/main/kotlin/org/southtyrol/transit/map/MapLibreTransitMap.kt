package org.southtyrol.transit.map

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.gson.JsonObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.Geo
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.design.ModeColors
import org.maplibre.geojson.Point as GeoPoint

private const val SRC_LINES = "st-lines"
private const val SRC_STOPS = "st-stops"
private const val SRC_STOPS_PLAIN = "st-stops-plain"
private const val L_STOPS_PLAIN = "st-stops-plain-layer"
private const val SRC_VEHICLES = "st-vehicles"
private const val SRC_POIS = "st-pois"
private const val L_LINES = "st-lines-layer"
private const val L_LINES_DASHED = "st-lines-dashed-layer"
private const val L_STOP_CLUSTER = "st-stop-cluster"
private const val L_STOP_CLUSTER_COUNT = "st-stop-cluster-count"
private const val L_STOPS = "st-stops-layer"
private const val L_POIS = "st-pois-layer"
private const val L_POI_POINTS = "st-pois-points"
private const val L_POI_LABEL = "st-pois-label"
private const val L_VEHICLE_CLUSTER = "st-vehicle-cluster"
private const val L_VEHICLE_ARROW = "st-vehicle-arrow"
private const val L_VEHICLES = "st-vehicles-layer"
private const val L_VEHICLE_DOT = "st-vehicles-dot"
/** From this zoom vehicles show icon and line number, and mobility points appear. */
private const val FULL_DETAIL_ZOOM = 13f
/** From this zoom mobility points carry their availability pill. */
private const val COUNT_ZOOM = 16f
/** Saved stops: amber fill, readable against both map styles. */
private const val HIGHLIGHT = 0xFFF2B705.toInt()
private const val FONT = "Noto Sans Regular"

/** Base line opacity, and the dimming of a route part already travelled and of passed stops. */
private const val LINE_OPACITY = 0.9f
private const val PASSED_OPACITY = org.southtyrol.transit.design.Progress.PASSED_ALPHA

private class MapHolder {
    var map: MapLibreMap? = null
    var style: Style? = null
    var appliedCamera: CameraRequest? = null
    var markers: Map<String, MapMarker> = emptyMap()
    var images: MarkerImages? = null
    /** Marker images the current style already holds; a new style starts empty. */
    val added = HashSet<String>()
}

@Composable
internal fun MapLibreTransitMap(
    content: MapContent,
    modifier: Modifier,
    camera: CameraRequest?,
    darkTheme: Boolean,
    interactive: Boolean,
    onMarkerClick: (MapMarker) -> Unit,
    onViewportChanged: (MapViewport) -> Unit,
    compassTopMargin: Dp?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val style = LocalMapStyle.current
    val styleUrl = if (darkTheme) style.darkUrl else style.lightUrl
    val holder = remember { MapHolder() }
    var styleVersion by remember { mutableStateOf(0) }
    val clickHandler by rememberUpdatedState(onMarkerClick)
    val viewportHandler by rememberUpdatedState(onViewportChanged)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true).attributionEnabled(false).logoEnabled(false)).apply {
            onCreate(Bundle())
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
            holder.map = null; holder.style = null
        }
    }

    LaunchedEffect(mapView, styleUrl) {
        mapView.getMapAsync { map ->
            holder.map = map
            map.uiSettings.isRotateGesturesEnabled = interactive
            map.uiSettings.isScrollGesturesEnabled = interactive
            map.uiSettings.isZoomGesturesEnabled = interactive
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isCompassEnabled = interactive
            compassTopMargin?.let { top ->
                val ui = map.uiSettings
                ui.setCompassMargins(ui.compassMarginLeft, (top.value * density).toInt(), ui.compassMarginRight, ui.compassMarginBottom)
            }
            if (map.cameraPosition.target == null || holder.appliedCamera == null) {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(Geo.Bolzano.latitude, Geo.Bolzano.longitude), 9.0))
            }
            map.setStyle(Style.Builder().fromUri(styleUrl)) { loaded ->
                installLayers(loaded)
                holder.images = MarkerImages(density, darkTheme)
                holder.added.clear()
                holder.style = loaded
                styleVersion++
            }
            map.addOnCameraIdleListener {
                val bounds = map.projection.visibleRegion.latLngBounds
                val target = map.cameraPosition.target ?: return@addOnCameraIdleListener
                viewportHandler(
                    MapViewport(
                        BoundingBox(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast),
                        map.cameraPosition.zoom,
                        Point(target.latitude, target.longitude),
                    ),
                )
            }
            map.addOnMapClickListener { latLng ->
                val screen = map.projection.toScreenLocation(latLng)
                val box = android.graphics.RectF(screen.x - 24 * density, screen.y - 24 * density, screen.x + 24 * density, screen.y + 24 * density)
                val clusters = map.queryRenderedFeatures(box, L_STOP_CLUSTER, L_VEHICLE_CLUSTER)
                if (clusters.isNotEmpty()) {
                    val geometry = clusters.first().geometry() as? GeoPoint
                    if (geometry != null) map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(geometry.latitude(), geometry.longitude()), map.cameraPosition.zoom + 2))
                    return@addOnMapClickListener true
                }
                val hit = map.queryRenderedFeatures(box, L_VEHICLES, L_VEHICLE_DOT, L_POIS, L_POI_POINTS, L_STOPS, L_STOPS_PLAIN).firstNotNullOfOrNull { f -> f.getStringProperty("id")?.let { holder.markers[it] } }
                if (hit != null) clickHandler(hit)
                hit != null
            }
        }
    }

    // Push data whenever content or style changes; GeoJSON sources diff efficiently on the native side.
    LaunchedEffect(content, styleVersion) {
        val loaded = holder.style ?: return@LaunchedEffect
        if (!loaded.isFullyLoaded) return@LaunchedEffect
        holder.markers = (content.stops + content.vehicles + content.pois).associateBy { it.id }
        loaded.getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(FeatureCollection.fromFeatures(content.lines.map { it.toFeature() }))
        val stopFeatures = content.stops.map { it.toFeature() }
        loaded.getSourceAs<GeoJsonSource>(SRC_STOPS)?.setGeoJson(FeatureCollection.fromFeatures(if (content.clusterStops) stopFeatures else emptyList()))
        loaded.getSourceAs<GeoJsonSource>(SRC_STOPS_PLAIN)?.setGeoJson(FeatureCollection.fromFeatures(if (content.clusterStops) emptyList() else stopFeatures))
        val images = holder.images ?: return@LaunchedEffect
        loaded.getSourceAs<GeoJsonSource>(SRC_VEHICLES)?.setGeoJson(FeatureCollection.fromFeatures(content.vehicles.map { it.toVehicleFeature(loaded, images, holder.added) }))
        loaded.getSourceAs<GeoJsonSource>(SRC_POIS)?.setGeoJson(FeatureCollection.fromFeatures(content.pois.map { if (it.isMobility()) it.toMobilityFeature(loaded, images, holder.added) else it.toFeature() }))
    }

    LaunchedEffect(camera, styleVersion) {
        val map = holder.map ?: return@LaunchedEffect
        val request = camera ?: return@LaunchedEffect
        if (request == holder.appliedCamera) return@LaunchedEffect
        holder.appliedCamera = request
        when (request) {
            is CameraRequest.Center -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(request.point.latitude, request.point.longitude), request.zoom), 600)
            is CameraRequest.Fit -> {
                val valid = request.points.filter { it.isValid }
                when {
                    valid.size >= 2 -> {
                        val bounds = LatLngBounds.Builder().includes(valid.map { LatLng(it.latitude, it.longitude) }).build()
                        val pad = (request.paddingDp * density).toInt()
                        runCatching { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad), 600) }
                    }
                    valid.size == 1 -> map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(valid[0].latitude, valid[0].longitude), 15.0), 600)
                }
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun MapMarker.toFeature(): Feature {
    val props = JsonObject().apply {
        addProperty("id", id)
        addProperty("kind", kind.name)
        addProperty("label", label)
        addProperty("color", "#%06X".format(color ?: defaultColor()))
        addProperty("stale", stale)
        addProperty("selected", selected)
        addProperty("highlighted", highlighted)
        if (bearing != null) addProperty("bearing", bearing)
    }
    return Feature.fromGeometry(GeoPoint.fromLngLat(point.longitude, point.latitude), props)
}

private fun Style.ensureImage(added: MutableSet<String>, key: String, draw: () -> Bitmap) {
    if (added.add(key)) addImage(key, draw())
}

private fun MapMarker.toVehicleFeature(style: Style, images: MarkerImages, added: MutableSet<String>): Feature {
    val feature = toFeature()
    val key = images.vehicleKey(this)
    style.ensureImage(added, key) { images.vehicle(this) }
    feature.addStringProperty("icon", key)
    feature.addStringProperty("color", "#%06X".format(images.vehicleColor(this)))
    // A position that is not current gets no heading, so it never looks live.
    if (bearing != null && !stale) {
        val arrow = images.arrowKey(this)
        style.ensureImage(added, arrow) { images.arrow(this) }
        feature.addStringProperty("arrow", arrow)
    }
    return feature
}

private fun MapMarker.toMobilityFeature(style: Style, images: MarkerImages, added: MutableSet<String>): Feature {
    val feature = toFeature()
    val plain = images.mobilityKey(this, withCount = false)
    style.ensureImage(added, plain) { images.mobility(this, withCount = false) }
    feature.addStringProperty("icon", plain)
    val counted = if (label.isBlank()) plain else images.mobilityKey(this, withCount = true)
    style.ensureImage(added, counted) { images.mobility(this, withCount = true) }
    feature.addStringProperty("iconCount", counted)
    return feature
}

private fun MapMarker.defaultColor(): Long = when (kind) {
    MarkerKind.PARKING, MarkerKind.BIKE_SHARING, MarkerKind.CAR_SHARING -> mobilityColor(kind)
    MarkerKind.ORIGIN -> 0x2E7D32
    MarkerKind.DESTINATION -> 0xC62828
    MarkerKind.USER -> 0x1A73E8
    MarkerKind.ALERT -> 0xE65100
    MarkerKind.STOP -> 0x455A64
    MarkerKind.VEHICLE -> ModeColors.container(mode ?: TransportMode.BUS)
}

private fun MapPolyline.toFeature(): Feature {
    val props = JsonObject().apply {
        addProperty("id", id)
        addProperty("color", "#%06X".format(color))
        addProperty("width", widthDp)
        addProperty("dashed", dashed)
        addProperty("opacity", LINE_OPACITY * opacity.coerceIn(0f, 1f))
    }
    return Feature.fromGeometry(LineString.fromLngLats(points.map { GeoPoint.fromLngLat(it.longitude, it.latitude) }), props)
}

private fun installLayers(style: Style) {
    style.addSource(GeoJsonSource(SRC_LINES))
    style.addSource(GeoJsonSource(SRC_STOPS, GeoJsonOptions().withCluster(true).withClusterMaxZoom(13).withClusterRadius(42)))
    style.addSource(GeoJsonSource(SRC_STOPS_PLAIN))
    style.addSource(GeoJsonSource(SRC_VEHICLES, GeoJsonOptions().withCluster(true).withClusterMaxZoom(10).withClusterRadius(36)))
    style.addSource(GeoJsonSource(SRC_POIS))

    // Solid and dashed lines need separate layers: line-dasharray cannot be data-driven on Android.
    style.addLayer(
        LineLayer(L_LINES, SRC_LINES).withFilter(Expression.neq(get("dashed"), literal(true))).withProperties(
            PropertyFactory.lineColor(Expression.toColor(get("color"))),
            PropertyFactory.lineWidth(toNumber(get("width"))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(toNumber(get("opacity"))),
        ),
    )
    style.addLayer(
        LineLayer(L_LINES_DASHED, SRC_LINES).withFilter(Expression.eq(get("dashed"), literal(true))).withProperties(
            PropertyFactory.lineColor(Expression.toColor(get("color"))),
            PropertyFactory.lineWidth(toNumber(get("width"))),
            PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(toNumber(get("opacity"))),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 1.2f)),
        ),
    )
    // Stop clusters and stops.
    style.addLayer(
        CircleLayer(L_STOP_CLUSTER, SRC_STOPS).withFilter(has("point_count")).withProperties(
            PropertyFactory.circleColor("#455A64"),
            PropertyFactory.circleOpacity(0.8f),
            PropertyFactory.circleRadius(Expression.step(get("point_count"), literal(14f), Expression.stop(20, 18f), Expression.stop(100, 22f))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(L_STOP_CLUSTER_COUNT, SRC_STOPS).withFilter(has("point_count")).withProperties(
            PropertyFactory.textField(Expression.toString(get("point_count"))),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textFont(arrayOf(FONT)),
            PropertyFactory.textAllowOverlap(true),
        ),
    )
    style.addLayer(
        CircleLayer(L_STOPS, SRC_STOPS).withFilter(not(has("point_count"))).withProperties(
            PropertyFactory.circleColor(Expression.switchCase(Expression.eq(get("highlighted"), literal(true)), Expression.color(HIGHLIGHT), Expression.color(android.graphics.Color.WHITE))),
            PropertyFactory.circleRadius(Expression.switchCase(Expression.eq(get("highlighted"), literal(true)), literal(9f), Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(12, 3.5f), Expression.stop(16, 7f)))),
            PropertyFactory.circleStrokeColor(Expression.toColor(get("color"))),
            PropertyFactory.circleStrokeWidth(Expression.switchCase(Expression.eq(get("selected"), literal(true)), literal(4f), literal(2.5f))),
            PropertyFactory.circleOpacity(Expression.switchCase(Expression.eq(get("stale"), literal(true)), literal(PASSED_OPACITY), literal(1f))),
            PropertyFactory.circleStrokeOpacity(Expression.switchCase(Expression.eq(get("stale"), literal(true)), literal(PASSED_OPACITY), literal(1f))),
        ),
    )
    style.addLayer(
        CircleLayer(L_STOPS_PLAIN, SRC_STOPS_PLAIN).withProperties(
            PropertyFactory.circleColor(Expression.switchCase(Expression.eq(get("highlighted"), literal(true)), Expression.color(HIGHLIGHT), Expression.color(android.graphics.Color.WHITE))),
            PropertyFactory.circleRadius(Expression.switchCase(Expression.eq(get("highlighted"), literal(true)), literal(9f), Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(8, 3f), Expression.stop(16, 7f)))),
            PropertyFactory.circleStrokeColor(Expression.toColor(get("color"))),
            PropertyFactory.circleStrokeWidth(2.5f),
            PropertyFactory.circleOpacity(Expression.switchCase(Expression.eq(get("stale"), literal(true)), literal(PASSED_OPACITY), literal(1f))),
            PropertyFactory.circleStrokeOpacity(Expression.switchCase(Expression.eq(get("stale"), literal(true)), literal(PASSED_OPACITY), literal(1f))),
        ),
    )
    // Mobility points (marker images, shown from the town zoom) and journey endpoints (plain circles).
    style.addLayer(
        CircleLayer(L_POI_POINTS, SRC_POIS).withFilter(not(has("icon"))).withProperties(
            PropertyFactory.circleColor(Expression.toColor(get("color"))),
            PropertyFactory.circleRadius(10f),
            PropertyFactory.circleOpacity(Expression.switchCase(Expression.eq(get("stale"), literal(true)), literal(0.45f), literal(1f))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(L_POI_LABEL, SRC_POIS).withFilter(not(has("icon"))).withProperties(
            PropertyFactory.textField(get("label")),
            PropertyFactory.textSize(10f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textFont(arrayOf(FONT)),
            PropertyFactory.textAllowOverlap(true),
        ),
    )
    style.addLayer(
        SymbolLayer(L_POIS, SRC_POIS).withFilter(has("icon")).withProperties(
            PropertyFactory.iconImage(Expression.step(Expression.zoom(), get("icon"), Expression.stop(COUNT_ZOOM, get("iconCount")))),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.switchCase(Expression.eq(get("selected"), literal(true)), literal(1f), literal(0f))),
        ).apply { minZoom = FULL_DETAIL_ZOOM },
    )
    // Vehicles: cluster bubble, heading arrow, body and line label. Stale vehicles are translucent.
    style.addLayer(
        CircleLayer(L_VEHICLE_CLUSTER, SRC_VEHICLES).withFilter(has("point_count")).withProperties(
            PropertyFactory.circleColor("#00796B"),
            PropertyFactory.circleRadius(16f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer("$L_VEHICLE_CLUSTER-count", SRC_VEHICLES).withFilter(has("point_count")).withProperties(
            PropertyFactory.textField(Expression.toString(get("point_count"))),
            PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textFont(arrayOf(FONT)),
            PropertyFactory.textAllowOverlap(true),
        ),
    )
    // Region zoom: a dot in the vehicle's colour.
    style.addLayer(
        CircleLayer(L_VEHICLE_DOT, SRC_VEHICLES).withFilter(not(has("point_count"))).withProperties(
            PropertyFactory.circleColor(Expression.toColor(get("color"))),
            PropertyFactory.circleRadius(5f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ).apply { maxZoom = FULL_DETAIL_ZOOM },
    )
    // Town zoom and closer: mode icon with the line number, heading arrow on top.
    style.addLayer(
        SymbolLayer(L_VEHICLES, SRC_VEHICLES).withFilter(not(has("point_count"))).withProperties(
            PropertyFactory.iconImage(get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.symbolSortKey(Expression.switchCase(Expression.eq(get("selected"), literal(true)), literal(1f), literal(0f))),
        ).apply { minZoom = FULL_DETAIL_ZOOM },
    )
    style.addLayer(
        SymbolLayer(L_VEHICLE_ARROW, SRC_VEHICLES).withFilter(Expression.all(not(has("point_count")), has("arrow"))).withProperties(
            PropertyFactory.iconImage(get("arrow")),
            PropertyFactory.iconRotate(toNumber(get("bearing"))),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        ).apply { minZoom = FULL_DETAIL_ZOOM },
    )
}
