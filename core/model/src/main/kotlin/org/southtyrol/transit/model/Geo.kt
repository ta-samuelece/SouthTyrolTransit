package org.southtyrol.transit.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    /** Rough bounding box of South Tyrol plus neighbouring termini served by STA. */
    val SouthTyrol = BoundingBox(south = 46.2, west = 10.3, north = 47.1, east = 12.5)
    val Bolzano = Point(46.4983, 11.3548)

    fun distance(a: Point, b: Point): Double {
        val dy = Math.toRadians(b.latitude - a.latitude)
        val dx = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dy / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dx / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun around(center: Point, meters: Double): BoundingBox {
        val dLat = meters / 111_320.0
        val dLon = meters / (111_320.0 * cos(Math.toRadians(center.latitude)).coerceAtLeast(0.01))
        return BoundingBox(center.latitude - dLat, center.longitude - dLon, center.latitude + dLat, center.longitude + dLon)
    }

    fun bounds(points: Collection<Point>): BoundingBox? {
        if (points.isEmpty()) return null
        return BoundingBox(points.minOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.latitude }, points.maxOf { it.longitude })
    }

    /** Douglas–Peucker simplification on a local equirectangular projection; [tolerance] in meters. */
    fun simplify(points: List<Point>, tolerance: Double): List<Point> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val kx = cos(Math.toRadians(points[0].latitude)) * 111_320.0
        val ky = 111_320.0
        val stack = ArrayDeque<Long>()
        stack.addLast(0L shl 32 or points.lastIndex.toLong())
        while (stack.isNotEmpty()) {
            val packed = stack.removeLast()
            val start = (packed ushr 32).toInt(); val end = (packed and 0xffffffffL).toInt()
            val ax = points[start].longitude * kx; val ay = points[start].latitude * ky
            val dx = points[end].longitude * kx - ax; val dy = points[end].latitude * ky - ay
            val len2 = dx * dx + dy * dy
            var maxDistance = 0.0; var index = -1
            for (i in start + 1 until end) {
                val px = points[i].longitude * kx - ax; val py = points[i].latitude * ky - ay
                val t = if (len2 == 0.0) 0.0 else ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                val ex = t * dx - px; val ey = t * dy - py
                val d = ex * ex + ey * ey
                if (d > maxDistance) { maxDistance = d; index = i }
            }
            if (index >= 0 && sqrt(maxDistance) > tolerance) {
                keep[index] = true
                stack.addLast(start.toLong() shl 32 or index.toLong())
                stack.addLast(index.toLong() shl 32 or end.toLong())
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    operator fun contains(p: Point) = p.latitude in south..north && p.longitude in west..east
    val center: Point get() = Point((south + north) / 2, (west + east) / 2)
}

/** Google encoded polyline format (precision 5), used to store simplified shapes compactly. */
object Polyline {
    fun encode(points: List<Point>): String {
        val out = StringBuilder()
        var lastLat = 0L; var lastLon = 0L
        for (p in points) {
            val lat = (p.latitude * 1e5).roundToLong(); val lon = (p.longitude * 1e5).roundToLong()
            write(lat - lastLat, out); write(lon - lastLon, out)
            lastLat = lat; lastLon = lon
        }
        return out.toString()
    }

    private fun write(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) { out.append(((0x20L or (v and 0x1f)) + 63).toInt().toChar()); v = v shr 5 }
        out.append((v + 63).toInt().toChar())
    }

    fun decode(encoded: String): List<Point> {
        val result = ArrayList<Point>(encoded.length / 4)
        var index = 0; var lat = 0L; var lon = 0L
        fun next(): Long {
            var shift = 0; var acc = 0L
            while (true) {
                require(index < encoded.length) { "Truncated polyline" }
                val b = encoded[index++].code - 63
                acc = acc or ((b and 0x1f).toLong() shl shift); shift += 5
                if (b < 0x20) break
            }
            return if (acc and 1L != 0L) (acc shr 1).inv() else acc shr 1
        }
        while (index < encoded.length) { lat += next(); lon += next(); result += Point(lat / 1e5, lon / 1e5) }
        return result
    }
}

object ColorContrast {
    private fun channel(c: Long): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4) }
    fun luminance(rgb: Long): Double = 0.2126 * channel((rgb shr 16) and 0xff) + 0.7152 * channel((rgb shr 8) and 0xff) + 0.0722 * channel(rgb and 0xff)
    fun ratio(a: Long, b: Long): Double { val la = luminance(a); val lb = luminance(b); return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05) }

    /** Returns [preferred] when it reaches WCAG AA (4.5:1) on [background], else black or white, whichever contrasts more. */
    fun readableOn(background: Long, preferred: Long?): Long {
        if (preferred != null && ratio(background, preferred) >= 4.5) return preferred
        return if (ratio(background, 0x000000) >= ratio(background, 0xFFFFFF)) 0x000000 else 0xFFFFFF
    }

    fun parseHex(value: String): Long? = value.trim().removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)
}
