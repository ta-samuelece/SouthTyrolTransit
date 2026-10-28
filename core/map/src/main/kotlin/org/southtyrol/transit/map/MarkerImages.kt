package org.southtyrol.transit.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ElectricBike
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import org.southtyrol.transit.design.ModeColors
import org.southtyrol.transit.design.icon
import org.southtyrol.transit.model.ColorContrast
import org.southtyrol.transit.model.LineNames
import org.southtyrol.transit.model.TransportMode
import kotlin.math.ceil
import kotlin.math.max

/*
 * Bitmaps for the map's mobility points and live vehicles (the "Map POI Markers" design): shape tells the
 * kind - stops stay circle layers, mobility points are rounded squares with an optional availability pill,
 * vehicles are a mode-icon circle with the line number in a tag of the mode's badge shape. Every image is
 * drawn centred on the coordinate, so layers anchor at the centre; the unused half is transparent.
 */

/** Grey fill of a vehicle whose position is not current (status-stale). */
internal const val STALE_VEHICLE_COLOR = 0x6F7977L

/** Heading between these bearings puts the line tag on the left, so the arrow never hides behind it. */
internal fun tagOnLeft(bearing: Float?): Boolean {
    if (bearing == null) return false
    val b = ((bearing % 360f) + 360f) % 360f
    return b in 20f..160f
}

/** Line numbers longer than this are shortened on the map; the sheet shows the full name. */
private const val MAX_TAG_CHARS = 6

internal fun tagText(label: String): String = label.trim().let { if (it.length > MAX_TAG_CHARS) it.take(MAX_TAG_CHARS - 1) + "…" else it }

internal fun MapMarker.isMobility(): Boolean = kind in MobilityKinds

private val MobilityKinds = setOf(
    MarkerKind.PARKING, MarkerKind.BIKE_SHARING, MarkerKind.CAR_SHARING, MarkerKind.CHARGING, MarkerKind.BIKE_PARKING, MarkerKind.EBIKE_SHARING,
)

/** mobility-* tiles; e-bike sharing shares the charging magenta, its glyph tells it apart from car chargers. */
internal fun mobilityColor(kind: MarkerKind): Long = when (kind) {
    MarkerKind.PARKING -> 0x1565C0
    MarkerKind.BIKE_SHARING -> 0x2E7D32
    MarkerKind.CHARGING, MarkerKind.EBIKE_SHARING -> 0xAD1457
    MarkerKind.BIKE_PARKING -> 0x005662
    else -> 0x6A1B9A
}

private fun mobilityIcon(kind: MarkerKind): ImageVector = when (kind) {
    MarkerKind.PARKING -> Icons.Rounded.LocalParking
    MarkerKind.BIKE_SHARING, MarkerKind.BIKE_PARKING -> Icons.Rounded.PedalBike
    MarkerKind.CHARGING -> Icons.Rounded.EvStation
    MarkerKind.EBIKE_SHARING -> Icons.Rounded.ElectricBike
    else -> Icons.Rounded.DirectionsCar
}

/** Fill and glyph of the corner badge (tertiary amber with on-tertiary-container), the same in every theme. */
private const val BADGE_COLOR = 0xF2C048L
private const val ON_BADGE_COLOR = 0x261900L

/** Corner radius in dp of a vehicle's line tag; follows ModeShapes.badge, simplified to one radius. */
private fun tagRadius(mode: TransportMode): Float = when (mode) {
    TransportMode.BUS -> 12f
    TransportMode.CITY_BUS -> 8f
    TransportMode.TRAIN -> 4f
    else -> 6f
}

/** Draws marker images at the map's density and remembers which ones the current style already has. */
internal class MarkerImages(private val density: Float, private val dark: Boolean) {
    private val ring = if (dark) 0xFFDEE4E1.toInt() else 0xFF171D1B.toInt()
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)

    fun vehicleKey(m: MapMarker): String {
        val mode = m.mode ?: TransportMode.BUS
        return "veh|$mode|${vehicleColor(m)}|${tagText(LineNames.badge(m.label, mode))}|${m.selected}|${m.stale}|${tagOnLeft(m.bearing)}"
    }

    fun arrowKey(m: MapMarker): String = "arrow|${vehicleColor(m)}|${m.selected}"

    fun mobilityKey(m: MapMarker, withCount: Boolean): String =
        "poi|${m.kind}|${m.color ?: ""}|${m.badge}|${if (withCount) m.label.trim() else ""}|${m.selected}|$dark"

    fun vehicleColor(m: MapMarker): Long = if (m.stale) STALE_VEHICLE_COLOR else (m.color ?: ModeColors.container(m.mode ?: TransportMode.BUS))

    fun vehicle(m: MapMarker): Bitmap {
        val mode = m.mode ?: TransportMode.BUS
        val fill = vehicleColor(m)
        val on = ColorContrast.readableOn(fill, null)
        val r = if (m.selected) 18f else 15f
        val ringOut = if (m.selected) 3f else 0f
        val text = tagText(LineNames.badge(m.label, mode))
        val textPaint = paint(on).apply { typeface = bold; textSize = 14f * density; fontFeatureSettings = "tnum" }
        val textW = textPaint.measureText(text) / density
        val inner = if (m.selected) 22f else 19f
        val tagW = if (text.isEmpty()) 0f else inner + textW + 9f
        val half = max(r + ringOut, tagW) + 1f
        val halfH = r + ringOut + 1f
        val (bitmap, canvas) = canvas(half, halfH)
        val c = half * density
        val cy = halfH * density
        val left = tagOnLeft(m.bearing)
        if (tagW > 0f) {
            val rad = tagRadius(mode) * density
            val x0 = if (left) c - tagW * density else c
            val x1 = if (left) c else c + tagW * density
            val rect = RectF(x0, cy - 12f * density, x1, cy + 12f * density)
            canvas.drawRoundRect(rect, rad, rad, paint(0xFFFFFF))
            rect.inset(2f * density, 2f * density)
            canvas.drawRoundRect(rect, rad - 2f * density, rad - 2f * density, paint(fill))
            val tx = if (left) c - (tagW - 9f) * density else c + inner * density
            val fm = textPaint.fontMetrics
            canvas.drawText(text, tx, cy - (fm.ascent + fm.descent) / 2f, textPaint)
        }
        if (m.selected) canvas.drawCircle(c, cy, (r + ringOut) * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring })
        canvas.drawCircle(c, cy, r * density, paint(0xFFFFFF))
        canvas.drawCircle(c, cy, (r - 2f) * density, paint(fill))
        val glyph = if (m.stale) Icons.Rounded.History else mode.icon()
        drawIcon(canvas, glyph, c, cy, if (m.selected) 22f else 18f, on)
        return bitmap
    }

    /** The heading arrow alone, centred on the vehicle and pointing north; the layer rotates it. */
    fun arrow(m: MapMarker): Bitmap {
        val half = 30f
        val (bitmap, canvas) = canvas(half, half)
        val c = half * density
        val tip = if (m.selected) 28f else 25f
        val path = Path().apply {
            moveTo(c, c - tip * density)
            lineTo(c + 6f * density, c - (tip - 8f) * density)
            lineTo(c - 6f * density, c - (tip - 8f) * density)
            close()
        }
        canvas.drawPath(path, paint(vehicleColor(m)))
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeJoin = Paint.Join.ROUND })
        return bitmap
    }

    /** The compass cone behind the user's dot: a wedge pointing north that fades outwards; the layer rotates it. */
    fun userCone(): Bitmap {
        val half = 56f
        val (bitmap, canvas) = canvas(half, half)
        val c = half * density
        val reach = (half - 2f) * density
        val gradient = android.graphics.RadialGradient(c, c, reach, intArrayOf(0x991A73E8.toInt(), 0x001A73E8), null, android.graphics.Shader.TileMode.CLAMP)
        // 70 degrees wide, about as precise as a phone compass is.
        canvas.drawArc(RectF(c - reach, c - reach, c + reach, c + reach), -90f - 35f, 70f, true, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = gradient })
        return bitmap
    }

    fun mobility(m: MapMarker, withCount: Boolean): Bitmap {
        // A colour on the marker overrides the kind's, e.g. grey for a charging station out of service.
        val fill = m.color ?: mobilityColor(m.kind)
        val s = if (m.selected) 18f else 14f
        val ringOut = if (m.selected) 3f else 0f
        val count = if (withCount) m.label.trim() else ""
        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 11f * density; fontFeatureSettings = "tnum" }
        val pillW = if (count.isEmpty()) 0f else max(18f, countPaint.measureText(count) / density + 10f)
        val pillX = if (m.selected) 10f else 6f
        val pillY = if (m.selected) 8f else 4f
        // The badge sits on the opposite corner from the pill, centred just outside the tile's corner.
        val badgeAt = s + 1f
        val half = max(max(s + ringOut, if (pillW > 0f) max(pillX + pillW, pillY + 18f) else 0f), if (m.badge) badgeAt + 8f else 0f) + 1f
        val (bitmap, canvas) = canvas(half, half)
        val c = half * density
        val rad = (if (m.selected) 10f else 8f) * density
        if (m.selected) {
            val ringRect = RectF(c - (s + ringOut) * density, c - (s + ringOut) * density, c + (s + ringOut) * density, c + (s + ringOut) * density)
            canvas.drawRoundRect(ringRect, rad + ringOut * density, rad + ringOut * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring })
        }
        val tile = RectF(c - s * density, c - s * density, c + s * density, c + s * density)
        canvas.drawRoundRect(tile, rad, rad, paint(0xFFFFFF))
        tile.inset(2f * density, 2f * density)
        canvas.drawRoundRect(tile, rad - 2f * density, rad - 2f * density, paint(fill))
        if (m.kind == MarkerKind.BIKE_PARKING) {
            // A bike inside a box: an outline with a heavier lid, the bike smaller inside it.
            val w = (if (m.selected) 20f else 16f) * density
            val h = (if (m.selected) 18f else 15f) * density
            val stroke = (if (m.selected) 2f else 1.5f) * density
            val lid = (if (m.selected) 3f else 2.5f) * density
            val box = RectF(c - w / 2f + stroke / 2f, c - h / 2f + stroke / 2f, c + w / 2f - stroke / 2f, c + h / 2f - stroke / 2f)
            val outline = paint(0xFFFFFF).apply { style = Paint.Style.STROKE; strokeWidth = stroke }
            canvas.drawRoundRect(box, 2.5f * density, 2.5f * density, outline)
            canvas.drawRect(c - w / 2f + stroke / 2f, c - h / 2f, c + w / 2f - stroke / 2f, c - h / 2f + lid, paint(0xFFFFFF))
            drawIcon(canvas, Icons.Rounded.PedalBike, c, c + lid / 4f, if (m.selected) 13f else 11f, 0xFFFFFF)
        } else {
            drawIcon(canvas, mobilityIcon(m.kind), c, c, if (m.selected) 22f else 18f, 0xFFFFFF)
        }
        if (m.badge) {
            val bx = c - badgeAt * density
            canvas.drawCircle(bx, bx, 8f * density, paint(0xFFFFFF))
            canvas.drawCircle(bx, bx, 6.5f * density, paint(BADGE_COLOR))
            drawIcon(canvas, Icons.Rounded.Bolt, bx, bx, 11f, ON_BADGE_COLOR)
        }
        if (pillW > 0f) {
            // Nothing left reads in a neutral colour rather than the kind's, still with its number.
            val none = count == "0"
            val bg = if (dark) 0x252B2A else 0xFFFFFF
            val border = when { none && dark -> 0x899390L; none -> 0x3F4947L; dark -> 0xFFFFFFL; else -> fill }
            val fg = when { none && dark -> 0xBEC9C6L; none -> 0x3F4947L; dark -> 0xDEE4E1L; else -> fill }
            val pill = RectF(c + pillX * density, c + pillY * density, c + (pillX + pillW) * density, c + (pillY + 18f) * density)
            val pr = 9f * density
            canvas.drawRoundRect(pill, pr, pr, paint(bg.toLong()))
            canvas.drawRoundRect(pill, pr, pr, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (0xFF000000 or border).toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density })
            countPaint.color = (0xFF000000 or fg).toInt()
            countPaint.textAlign = Paint.Align.CENTER
            val fm = countPaint.fontMetrics
            canvas.drawText(count, pill.centerX(), pill.centerY() - (fm.ascent + fm.descent) / 2f, countPaint)
        }
        return bitmap
    }

    private fun canvas(halfW: Float, halfH: Float): Pair<Bitmap, Canvas> {
        val bitmap = Bitmap.createBitmap(ceil(2 * halfW * density).toInt(), ceil(2 * halfH * density).toInt(), Bitmap.Config.ARGB_8888)
        return bitmap to Canvas(bitmap)
    }

    private fun paint(rgb: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (0xFF000000 or rgb).toInt() }

    private fun paint(rgb: Int) = paint(rgb.toLong())

    private fun drawIcon(canvas: Canvas, icon: ImageVector, cx: Float, cy: Float, sizeDp: Float, rgb: Long) {
        val size = sizeDp * density
        val path = Path()
        collectPaths(icon.root, path)
        path.transform(Matrix().apply { setScale(size / icon.viewportWidth, size / icon.viewportHeight); postTranslate(cx - size / 2f, cy - size / 2f) })
        canvas.drawPath(path, paint(rgb))
    }

    private fun collectPaths(group: VectorGroup, into: Path) {
        // Material Symbols shift their grid with a group translation; scale and rotation are not used.
        val shift = Matrix().apply { setTranslate(group.translationX, group.translationY) }
        val local = Path()
        for (node in group) when (node) {
            is VectorGroup -> collectPaths(node, local)
            is VectorPath -> local.addPath(node.pathData.toPath().asAndroidPath().apply {
                fillType = if (node.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            })
        }
        into.addPath(local, shift)
    }
}
