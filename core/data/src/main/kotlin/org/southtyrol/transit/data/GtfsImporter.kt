package org.southtyrol.transit.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.southtyrol.transit.model.ColorContrast
import org.southtyrol.transit.model.Geo
import org.southtyrol.transit.model.GtfsTime
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.Polyline
import org.southtyrol.transit.model.TextNormalizer
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class GtfsValidationException(message: String) : IllegalStateException(message)

data class ImportProgress(val file: String, val rows: Long)

data class ImportSummary(val stops: Int, val routes: Int, val trips: Int, val stopTimes: Long, val shapes: Int, val skippedStopTimes: Long, val firstDate: Int, val lastDate: Int)

object GtfsFiles {
    val required = listOf("agency.txt", "stops.txt", "routes.txt", "trips.txt", "stop_times.txt")

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** `it:22021:468:1:2805` and `Parentit:22021:468` both belong to station `it:22021:468`. */
    fun stationKey(stopId: String, parent: String): String {
        val base = parent.ifBlank { stopId }.removePrefix("Parent")
        val parts = base.split(':')
        return if (parts.size >= 3) parts.take(3).joinToString(":") else base
    }

    fun lineKey(type: Int, shortName: String, longName: String, routeId: String): String {
        val mode = TransportMode.fromGtfs(type)
        return "${mode.name}:${shortName.ifBlank { longName.ifBlank { routeId } }}"
    }

    fun ftsQuery(query: String): String? {
        val tokens = TextNormalizer.key(query).split(' ').filter { it.isNotBlank() }.take(6)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }
}

/**
 * Streams a GTFS zip into an empty [ScheduleDatabase] file. The caller owns transactional
 * activation: the target database is a brand-new file, so a failed import never touches the
 * schedule currently in use.
 */
class GtfsImporter(private val shapeToleranceMeters: Double = 4.0) {

    suspend fun import(zipFile: File, db: SupportSQLiteDatabase, meta: Map<String, String>, progress: (ImportProgress) -> Unit = {}): ImportSummary {
        ZipFile(zipFile).use { zip ->
            val totalSize = zip.entries().asSequence().sumOf { it.size.coerceAtLeast(0) }
            if (totalSize > 3_000_000_000L) throw GtfsValidationException("Archive too large")
            for (name in GtfsFiles.required) if (zip.getEntry(name) == null) throw GtfsValidationException("Missing $name")
            if (zip.getEntry("calendar.txt") == null && zip.getEntry("calendar_dates.txt") == null) throw GtfsValidationException("Missing calendar")

            suspend fun read(name: String, block: suspend (Csv.Record) -> Unit): Long {
                val entry = zip.getEntry(name) ?: return 0
                var count = 0L
                zip.getInputStream(entry).reader(Charsets.UTF_8).use { reader ->
                    progress(ImportProgress(name, 0))
                    db.beginTransaction()
                    try {
                        val csv = Csv(reader)
                        val header = csv.row()?.map { it.trim() } ?: return@use
                        val record = Csv.Record(header)
                        while (true) {
                            val values = csv.row() ?: break
                            if (values.size == 1 && values[0].isEmpty()) continue
                            record.values = values
                            block(record)
                            if (++count % 20_000 == 0L) {
                                currentCoroutineContext().ensureActive()
                                db.setTransactionSuccessful(); db.endTransaction(); db.beginTransaction()
                                progress(ImportProgress(name, count))
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
                return count
            }

            fun statement(table: String, columns: List<String>): SupportSQLiteStatement =
                db.compileStatement("INSERT OR REPLACE INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})")

            fun SupportSQLiteStatement.put(vararg values: Any?) {
                clearBindings()
                values.forEachIndexed { i, v ->
                    when (v) {
                        null -> bindNull(i + 1)
                        is Double -> bindDouble(i + 1, v)
                        is Int -> bindLong(i + 1, v.toLong())
                        is Long -> bindLong(i + 1, v)
                        is Boolean -> bindLong(i + 1, if (v) 1 else 0)
                        else -> bindString(i + 1, v.toString())
                    }
                }
                executeInsert()
            }

            // agency.txt — the app assumes the network timezone; reject feeds that disagree.
            var feedLanguage = "it"
            statement("agency", listOf("id", "name", "url", "phone")).use { st ->
                read("agency.txt") { r ->
                    val zone = r["agency_timezone"]
                    if (zone.isNotBlank() && zone != TransitZone.id) throw GtfsValidationException("Unsupported agency timezone $zone")
                    r["agency_lang"].takeIf { it.isNotBlank() }?.let { feedLanguage = it.lowercase() }
                    st.put(r["agency_id"], r["agency_name"], r["agency_url"], r["agency_phone"])
                }
            }

            // translations.txt (both the spec layout and the legacy table/field layout are keyed by name).
            val stopNameTranslations = HashMap<String, MutableSet<String>>()
            statement("translations", listOf("tableName", "recordId", "language", "text")).use { st ->
                read("translations.txt") { r ->
                    val table = r["table_name"]; val field = r["field_name"]; val id = r["record_id"]; val text = r["translation"]
                    if (id.isBlank() || text.isBlank()) return@read
                    when {
                        table == "stops" && field == "stop_name" -> { st.put("stops", id, r["language"], text); stopNameTranslations.getOrPut(id) { HashSet() } += text }
                        table == "trips" && field == "trip_headsign" -> st.put("trips", id, r["language"], text)
                        table == "routes" && field in setOf("route_long_name", "route_short_name") -> st.put("routes.$field", id, r["language"], text)
                    }
                }
            }

            // stops.txt
            val stopIndex = HashMap<String, Int>(8192)
            val searchTexts = HashMap<String, MutableSet<String>>()
            statement("stops", listOf("idx", "id", "name", "lat", "lon", "code", "stationKey", "platform", "wheelchair", "locationType")).use { st ->
                read("stops.txt") { r ->
                    val id = r["stop_id"]
                    val lat = r["stop_lat"].toDoubleOrNull(); val lon = r["stop_lon"].toDoubleOrNull()
                    if (id.isBlank() || lat == null || lon == null || !Point(lat, lon).isValid) return@read
                    val idx = stopIndex.size + 1
                    stopIndex[id] = idx
                    val key = GtfsFiles.stationKey(id, r["parent_station"])
                    val name = r["stop_name"]
                    st.put(idx, id, name, lat, lon, r["stop_code"], key, r["platform_code"], r.int("wheelchair_boarding"), r.int("location_type"))
                    val texts = searchTexts.getOrPut(key) { HashSet() }
                    texts += TextNormalizer.key(name)
                    stopNameTranslations[id]?.forEach { texts += TextNormalizer.key(it) }
                }
            }
            if (stopIndex.isEmpty()) throw GtfsValidationException("No stops")
            statement("stop_search", listOf("stationKey", "text")).use { st ->
                db.beginTransaction()
                try {
                    for ((key, texts) in searchTexts) st.put(key, texts.joinToString(" "))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }

            // routes.txt
            var routes = 0
            statement("routes", listOf("id", "shortName", "longName", "type", "agencyId", "color", "textColor", "lineKey")).use { st ->
                read("routes.txt") { r ->
                    val type = r.int("route_type", 3)
                    val color = ColorContrast.parseHex(r["route_color"])?.let { "%06X".format(it) }.orEmpty()
                    val text = ColorContrast.parseHex(r["route_text_color"])?.let { "%06X".format(it) }.orEmpty()
                    st.put(r["route_id"], r["route_short_name"], r["route_long_name"], type, r["agency_id"], color, text, GtfsFiles.lineKey(type, r["route_short_name"], r["route_long_name"], r["route_id"]))
                    routes++
                }
            }

            // trips.txt
            val tripIndex = HashMap<String, Int>(65536)
            statement("trips", listOf("idx", "id", "routeId", "serviceId", "headsign", "direction", "shapeId", "wheelchair")).use { st ->
                read("trips.txt") { r ->
                    val id = r["trip_id"]
                    if (id.isBlank()) return@read
                    val idx = tripIndex.size + 1
                    tripIndex[id] = idx
                    st.put(idx, id, r["route_id"], r["service_id"], r["trip_headsign"], r.int("direction_id"), r["shape_id"], r.int("wheelchair_accessible"))
                }
            }

            // stop_times.txt — the bulk of the feed. Missing times are interpolated from the previous stop.
            var skipped = 0L
            var lastTrip = ""; var lastTime = 0
            val stopTimes = statement("stop_times", listOf("tripIdx", "sequence", "stopIdx", "arrival", "departure", "flags")).use { st ->
                read("stop_times.txt") { r ->
                    val trip = tripIndex[r["trip_id"]]; val stop = stopIndex[r["stop_id"]]
                    if (trip == null || stop == null) { skipped++; return@read }
                    val tripId = r["trip_id"]
                    if (tripId != lastTrip) { lastTrip = tripId; lastTime = 0 }
                    val arrivalText = r["arrival_time"]; val departureText = r["departure_time"]
                    val arrival = if (arrivalText.isBlank()) departureText.takeIf { it.isNotBlank() }?.let(GtfsTime::seconds) ?: lastTime else GtfsTime.seconds(arrivalText)
                    val departure = if (departureText.isBlank()) arrival else GtfsTime.seconds(departureText)
                    lastTime = departure
                    st.put(trip, r.int("stop_sequence"), stop, arrival, departure, (r.int("pickup_type") and 0xf) or ((r.int("drop_off_type") and 0xf) shl 4))
                }
            }
            if (stopTimes == 0L) throw GtfsValidationException("No stop times")

            // Not every trip carries its own headsign translation; derive a text-level dictionary
            // from the trips that do so identical headsigns are translated consistently.
            db.execSQL(
                """
                INSERT OR IGNORE INTO translations (tableName, recordId, language, text)
                SELECT 'headsign', t.headsign, tr.language, tr.text
                FROM trips t JOIN translations tr ON tr.tableName = 'trips' AND tr.recordId = t.id
                WHERE t.headsign != '' GROUP BY t.headsign, tr.language
                """.trimIndent(),
            )
            if (skipped > stopTimes / 100) throw GtfsValidationException("Broken GTFS references ($skipped stop_times)")

            // calendar.txt / calendar_dates.txt
            var firstDate = Int.MAX_VALUE; var lastDate = 0
            statement("calendar", listOf("serviceId", "start", "end", "weekdays")).use { st ->
                read("calendar.txt") { r ->
                    val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                    val mask = days.foldIndexed(0) { i, acc, day -> if (r[day] == "1") acc or (1 shl i) else acc }
                    val start = r.int("start_date"); val end = r.int("end_date")
                    firstDate = minOf(firstDate, start); lastDate = maxOf(lastDate, end)
                    st.put(r["service_id"], start, end, mask)
                }
            }
            statement("calendar_dates", listOf("serviceId", "date", "added")).use { st ->
                read("calendar_dates.txt") { r ->
                    val date = r.int("date")
                    val added = r["exception_type"] == "1"
                    if (added) { firstDate = minOf(firstDate, date); lastDate = maxOf(lastDate, date) }
                    st.put(r["service_id"], date, added)
                }
            }

            // shapes.txt — hundreds of MB raw; stored simplified as encoded polylines per shape.
            var shapes = 0
            statement("shapes", listOf("id", "part", "polyline")).use { st ->
                var current = ""; var part = 0
                val points = ArrayList<Pair<Int, Point>>(4096)
                val parts = HashMap<String, Int>()
                fun flush() {
                    if (current.isEmpty() || points.isEmpty()) return
                    points.sortBy { it.first }
                    val simplified = Geo.simplify(points.map { it.second }, shapeToleranceMeters)
                    st.put(current, part, Polyline.encode(simplified))
                    points.clear()
                }
                read("shapes.txt") { r ->
                    val id = r["shape_id"]
                    if (id != current) {
                        flush()
                        current = id
                        part = parts.getOrDefault(id, -1) + 1
                        parts[id] = part
                        if (part == 0) shapes++
                    }
                    val lat = r["shape_pt_lat"].toDoubleOrNull() ?: return@read
                    val lon = r["shape_pt_lon"].toDoubleOrNull() ?: return@read
                    points += r.int("shape_pt_sequence") to Point(lat, lon)
                }
                db.beginTransaction()
                try { flush(); db.setTransactionSuccessful() } finally { db.endTransaction() }
            }

            val summary = ImportSummary(stopIndex.size, routes, tripIndex.size, stopTimes, shapes, skipped, if (firstDate == Int.MAX_VALUE) 0 else firstDate, lastDate)
            statement("meta", listOf("key", "value")).use { st ->
                db.beginTransaction()
                try {
                    (meta + mapOf(
                        "stops" to summary.stops.toString(), "routes" to summary.routes.toString(), "trips" to summary.trips.toString(),
                        "stopTimes" to summary.stopTimes.toString(), "shapes" to summary.shapes.toString(),
                        "firstDate" to summary.firstDate.toString(), "lastDate" to summary.lastDate.toString(),
                        "hasShapes" to (summary.shapes > 0).toString(),
                        "feedLanguage" to feedLanguage,
                    )).forEach { (k, v) -> st.put(k, v) }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            db.execSQL("ANALYZE")
            return summary
        }
    }
}
