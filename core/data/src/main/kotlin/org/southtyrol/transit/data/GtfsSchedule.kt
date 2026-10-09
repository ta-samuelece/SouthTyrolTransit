package org.southtyrol.transit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.southtyrol.transit.model.Accessibility
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.CalendarRule
import org.southtyrol.transit.model.ColorContrast
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.Geo
import org.southtyrol.transit.model.GtfsTime
import org.southtyrol.transit.model.Languages
import org.southtyrol.transit.model.Line
import org.southtyrol.transit.model.LineVariant
import org.southtyrol.transit.model.Operator
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.Polyline
import org.southtyrol.transit.model.Route
import org.southtyrol.transit.model.ServiceCalendar
import org.southtyrol.transit.model.Stop
import org.southtyrol.transit.model.StopDirection
import org.southtyrol.transit.model.TransitScheduleDataSource
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.Trip
import org.southtyrol.transit.model.TripDetail
import org.southtyrol.transit.model.TripStop
import java.time.Instant
import java.time.LocalDate

/**
 * [TransitScheduleDataSource] backed by the imported GTFS database. Stops are exposed as station
 * groups: [Stop.id] is the station key (e.g. `it:22021:468`) and covers all its platforms.
 */
class GtfsSchedule(private val store: ScheduleStore) : TransitScheduleDataSource {
    private val cacheLock = Mutex()
    private var cacheVersion = -1
    private var calendar: List<CalendarRule> = emptyList()
    private val services = LinkedHashMap<LocalDate, Set<String>>()
    private var agencies: Map<String, Operator> = emptyMap()

    private fun dao(): ScheduleDao = store.database()?.schedule() ?: throw DataException(DataError.NoSchedule)

    override suspend fun isAvailable(): Boolean = store.activeName() != null

    private suspend fun <T> io(block: suspend ScheduleDao.() -> T): T = withContext(Dispatchers.IO) { guarded { dao().block() } }

    private suspend fun prepare(dao: ScheduleDao) = cacheLock.withLock {
        val version = store.version.value
        if (version == cacheVersion && calendar.isNotEmpty()) return@withLock
        calendar = dao.calendar().map { CalendarRule(it.serviceId, GtfsTime.fromDateKey(it.start), GtfsTime.fromDateKey(it.end), CalendarRule.days(it.weekdays)) }
        agencies = dao.agencies().associate { it.id to Operator(it.id, it.name, it.url, it.phone) }
        services.clear()
        cacheVersion = version
    }

    private suspend fun activeServices(dao: ScheduleDao, date: LocalDate): Set<String> {
        prepare(dao)
        cacheLock.withLock { services[date] }?.let { return it }
        val exceptions = dao.calendarDates(GtfsTime.dateKey(date)).associate { it.serviceId to it.added }
        val active = ServiceCalendar.activeServices(date, calendar, exceptions)
        cacheLock.withLock {
            services[date] = active
            while (services.size > 10) services.remove(services.keys.first())
        }
        return active
    }

    /** GTFS base names are Italian here; only languages with translations are looked up. */
    private suspend fun translationLanguage(dao: ScheduleDao, language: String): String? {
        val base = (dao.meta("feedLanguage") ?: "it").lowercase()
        for (lang in Languages.fallbacks(language)) {
            if (lang == base) return null
            if (lang == "de") return "de"
        }
        return null
    }

    private suspend fun translate(dao: ScheduleDao, table: String, ids: Collection<String>, language: String): Map<String, String> {
        val lang = translationLanguage(dao, language) ?: return emptyMap()
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(500).flatMap { dao.translations(table, lang, it) }.associate { it.recordId to it.text }
    }

    /** Trip headsigns: per-trip translation first, then the headsign-text dictionary. */
    private suspend fun headsigns(dao: ScheduleDao, trips: Map<String, String>, language: String): Map<String, String> {
        val byTrip = translate(dao, "trips", trips.keys, language)
        val missing = trips.filterKeys { it !in byTrip }
        if (missing.isEmpty()) return byTrip
        val byText = translate(dao, "headsign", missing.values.filter { it.isNotBlank() }.distinct(), language)
        return byTrip + missing.mapNotNull { (trip, text) -> byText[text]?.let { trip to it } }
    }

    private fun StopRow.toStop(name: String = this.name) = Stop(
        id = id, name = name, point = Point(lat, lon), code = code, stationKey = stationKey, platform = platform,
        wheelchair = Accessibility.fromGtfs(wheelchair),
    )

    /** Collapses platform rows into one station entry with a centroid and the most common name. */
    private suspend fun stations(dao: ScheduleDao, rows: List<StopRow>, language: String, origin: Point? = null): List<Stop> {
        val names = translate(dao, "stops", rows.map { it.id }, language)
        return rows.groupBy { it.stationKey }.map { (key, platforms) ->
            val name = platforms.map { names[it.id] ?: it.name }.groupingBy { it }.eachCount().maxBy { it.value }.key
            val center = Point(platforms.sumOf { it.lat } / platforms.size, platforms.sumOf { it.lon } / platforms.size)
            val wheelchair = when {
                platforms.all { it.wheelchair == 1 } -> Accessibility.ACCESSIBLE
                platforms.all { it.wheelchair == 2 } -> Accessibility.NOT_ACCESSIBLE
                else -> Accessibility.UNKNOWN
            }
            Stop(key, name, center, platforms.firstNotNullOfOrNull { it.code.ifBlank { null } }.orEmpty(), key, "", wheelchair, origin?.let { Geo.distance(it, center) })
        }
    }

    override suspend fun searchStops(query: String, language: String, limit: Int): List<Stop> = io {
        val match = GtfsFiles.ftsQuery(query) ?: return@io emptyList()
        val rows = searchStops(match, limit * 8)
        val key = org.southtyrol.transit.model.TextNormalizer.key(query)
        stations(this, rows, language).sortedWith(compareBy<Stop> { !org.southtyrol.transit.model.TextNormalizer.key(it.name).contains(key) }.thenBy { it.name.length }).take(limit)
    }

    override suspend fun nearbyStops(center: Point, radiusMeters: Double, language: String, limit: Int): List<Stop> = io {
        val box = Geo.around(center, radiusMeters)
        val rows = stopsIn(box.south, box.north, box.west, box.east, 3000)
        stations(this, rows, language, center).filter { (it.distanceMeters ?: 0.0) <= radiusMeters }.sortedBy { it.distanceMeters }.take(limit)
    }

    override suspend fun stopsIn(box: BoundingBox, language: String, limit: Int): List<Stop> = io {
        stations(this, stopsIn(box.south, box.north, box.west, box.east, limit * 3), language).take(limit)
    }

    override suspend fun stop(id: String, language: String): Stop? = io {
        val platforms = station(id).ifEmpty { listOfNotNull(stop(id)) }
        if (platforms.isEmpty()) null else stations(this, platforms, language).firstOrNull()
    }

    override suspend fun stationStops(stationKey: String, language: String): List<Stop> = io {
        val rows = station(stationKey)
        val names = translate(this, "stops", rows.map { it.id }, language)
        rows.map { it.toStop(names[it.id] ?: it.name) }
    }

    private fun platformIds(dao: ScheduleDao, ids: Collection<String>): suspend () -> List<String> = {
        ids.flatMap { id -> dao.station(id).map { it.id }.ifEmpty { listOf(id) } }.distinct()
    }

    override suspend fun scheduledDepartures(stopIds: Collection<String>, from: Instant, until: Instant, arrivals: Boolean, language: String, limit: Int): List<Departure> = io {
        val platforms = platformIds(this, stopIds)()
        if (platforms.isEmpty()) return@io emptyList()
        prepare(this)
        val firstDay = from.atZone(TransitZone).toLocalDate().minusDays(1)
        val lastDay = until.atZone(TransitZone).toLocalDate()
        val result = ArrayList<Departure>()
        var day = firstDay
        while (!day.isAfter(lastDay)) {
            val low = GtfsTime.secondsSinceOrigin(day, from).coerceAtLeast(0)
            val high = GtfsTime.secondsSinceOrigin(day, until)
            if (high >= 0 && low <= high && low < 7 * 86400) {
                val active = activeServices(this, day)
                // The stop_times rows include every service day's trips; only active ones count. Page
                // through the rows until enough active departures are found, so a busy station whose
                // first rows are mostly other days' trips is not cut short by the SQL limit.
                val rows = ArrayList<BoardRow>()
                val page = (limit * 4).coerceAtLeast(100)
                var offset = 0
                while (rows.size < limit) {
                    val batch = if (arrivals) arrivals(platforms, low.toInt(), high.toInt(), page, offset) else departures(platforms, low.toInt(), high.toInt(), page, offset)
                    batch.filterTo(rows) { r ->
                        r.serviceId in active &&
                            if (arrivals) (r.flags shr 4) and 0xf != 1 && r.sequence != firstSequence(r) else r.flags and 0xf != 1 && r.sequence != r.lastSequence
                    }
                    if (batch.size < page || offset > MAX_BOARD_ROWS) break
                    offset += page
                }
                val headsigns = headsigns(this, rows.associate { it.tripId to it.headsign }, language)
                for (r in rows) result += r.toDeparture(day, arrivals, headsigns[r.tripId])
            }
            day = day.plusDays(1)
        }
        result.sortedBy { it.scheduled }.distinctBy { it.key }.take(limit)
    }

    override suspend fun stationDirections(stationKey: String, language: String): List<StopDirection> = io {
        val platforms = station(stationKey).map { it.id }
        if (platforms.size < 2) return@io emptyList()
        val rows = nextStops(platforms)
        val names = translate(this, "stops", rows.map { it.nextId }.distinct(), language)
        val town = (stop(stationKey) ?: station(stationKey).firstOrNull())?.name?.substringBefore(", ", "")
        fun label(row: NextStopRow): String {
            val name = names[row.nextId] ?: row.nextName
            // "Bolzano, Via Sorrento" reads as "Via Sorrento" at a stop in Bolzano.
            return if (town != null && town.isNotBlank() && name.startsWith("$town, ")) name.removePrefix("$town, ") else name
        }
        val byPlatform = rows.groupBy { it.platformId }.mapNotNull { (platform, next) ->
            val total = next.sumOf { it.calls }
            // The main next stops (at least a fifth of the departures), most frequent first, at most two.
            val towards = next.groupBy(::label).mapValues { (_, r) -> r.sumOf { it.calls } }
                .filterValues { it * 5 >= total }.entries.sortedByDescending { it.value }.take(2).map { it.key }
            if (towards.isEmpty()) null else platform to towards
        }
        // Platforms heading to the same places form one direction.
        val directions = byPlatform.groupBy({ it.second.toSet() }, { it.first })
            .map { (_, ids) -> StopDirection(ids.toSet(), byPlatform.first { it.first == ids.first() }.second) }
        if (directions.size in 2..6) directions else emptyList()
    }

    override suspend fun arrivalsDiffer(stopIds: Collection<String>): Boolean = io {
        val platforms = platformIds(this, stopIds)()
        platforms.isEmpty() || arrivalsDiffer(platforms)
    }

    /** Arrivals at the first stop of a trip are meaningless; GTFS sequences usually start at 0 or 1. */
    private fun firstSequence(row: BoardRow) = if (row.arrival == row.departure && row.sequence <= 1) row.sequence else Int.MIN_VALUE

    private fun BoardRow.toDeparture(day: LocalDate, arrivals: Boolean, headsign: String?): Departure {
        val color = ColorContrast.parseHex(color)
        return Departure(
            tripId = tripId, stopId = stopId, sequence = sequence, routeId = routeId,
            line = shortName.ifBlank { longName }, mode = TransportMode.fromGtfs(type),
            destination = headsign ?: this.headsign, scheduled = GtfsTime.instant(day, if (arrivals) arrival else departure),
            freshness = Freshness.SCHEDULED, platform = platform, serviceDate = day,
            color = color, textColor = color?.let { ColorContrast.readableOn(it, ColorContrast.parseHex(textColor)) },
            operator = agencies[agencyId]?.name.orEmpty(),
        )
    }

    private fun LineRow.toLine(): Line {
        val c = ColorContrast.parseHex(color)
        return Line(lineKey, shortName.ifBlank { longName }, TransportMode.fromGtfs(type), routeIds.split('|').filter { it.isNotBlank() }, longName, c, c?.let { ColorContrast.readableOn(it, ColorContrast.parseHex(textColor)) })
    }

    override suspend fun linesAtStops(stopIds: Collection<String>): List<Line> = io {
        val platforms = platformIds(this, stopIds)()
        val keys = lineKeysAtStops(platforms)
        if (keys.isEmpty()) emptyList() else lines(keys).map { it.toLine() }.sortedWith(lineOrder)
    }

    override suspend fun searchLines(query: String): List<Line> = io { searchLines(query.trim()).map { it.toLine() }.sortedWith(lineOrder) }

    override suspend fun line(key: String): Line? = io { lines(listOf(key)).firstOrNull()?.toLine() }

    override suspend fun lineVariants(line: Line, language: String): List<LineVariant> = io {
        val rows = variants(line.routeIds)
        val seen = HashSet<List<String>>()
        rows.mapNotNull { v ->
            val trip = trip(v.sampleTrip) ?: return@mapNotNull null
            val stops = tripStops(trip.idx)
            val pattern = stops.map { it.stationKey }
            if (!seen.add(pattern)) return@mapNotNull null
            val names = translate(this, "stops", stops.map { it.stopId }, language)
            val headsign = headsigns(this, mapOf(v.sampleTrip to v.headsign), language)[v.sampleTrip] ?: v.headsign
            LineVariant(
                id = "${v.routeId}|${v.direction}|${v.shapeId}", routeId = v.routeId, direction = v.direction, headsign = headsign.ifBlank { stops.lastOrNull()?.name.orEmpty() },
                stops = stops.map { Stop(it.stationKey, names[it.stopId] ?: it.name, Point(it.lat, it.lon), it.code, it.stationKey, it.platform, Accessibility.fromGtfs(it.wheelchair)) },
                shape = shape(v.shapeId).flatMap { Polyline.decode(it.polyline) }.ifEmpty { stops.map { Point(it.lat, it.lon) } },
                sampleTripId = v.sampleTrip, tripCount = v.trips,
            )
        }
    }

    override suspend fun variantTrips(line: Line, variant: LineVariant, from: Instant, until: Instant, language: String): List<Departure> = io {
        val (routeId, direction, shapeId) = variant.id.split('|').let { Triple(it[0], it[1].toInt(), it.getOrElse(2) { "" }) }
        val route = routes(listOf(routeId)).firstOrNull()
        prepare(this)
        val result = ArrayList<Departure>()
        var day = from.atZone(TransitZone).toLocalDate().minusDays(1)
        val last = until.atZone(TransitZone).toLocalDate()
        while (!day.isAfter(last)) {
            val low = GtfsTime.secondsSinceOrigin(day, from).coerceAtLeast(0)
            val high = GtfsTime.secondsSinceOrigin(day, until)
            if (high >= 0 && low <= high) {
                val active = activeServices(this, day)
                val rows = variantTrips(routeId, direction, shapeId, low.toInt(), high.toInt()).filter { it.serviceId in active }
                val names = headsigns(this, rows.associate { it.tripId to it.headsign }, language)
                rows.forEach { t ->
                    result += Departure(
                        tripId = t.tripId, stopId = t.stopId, sequence = t.sequence, routeId = routeId, line = line.name, mode = line.mode,
                        destination = (names[t.tripId] ?: t.headsign).ifBlank { variant.headsign }, scheduled = GtfsTime.instant(day, t.departure), serviceDate = day,
                        color = line.color, textColor = line.textColor, operator = route?.agencyId?.let { agencies[it]?.name }.orEmpty(),
                    )
                }
            }
            day = day.plusDays(1)
        }
        result.sortedBy { it.scheduled }
    }

    override suspend fun trip(tripId: String, serviceDate: LocalDate, language: String): TripDetail? = io {
        prepare(this)
        val trip = trip(tripId) ?: return@io null
        val route = routes(listOf(trip.routeId)).firstOrNull() ?: return@io null
        val stops = tripStops(trip.idx)
        val names = translate(this, "stops", stops.map { it.stopId }, language)
        val headsign = headsigns(this, mapOf(tripId to trip.headsign), language)[tripId] ?: trip.headsign
        val shape = if (trip.shapeId.isBlank()) emptyList() else shape(trip.shapeId).flatMap { Polyline.decode(it.polyline) }
        TripDetail(
            trip = Trip(trip.id, trip.routeId, trip.serviceId, headsign, trip.direction, trip.shapeId),
            route = route.toRoute(),
            serviceDate = serviceDate,
            stops = stops.map {
                TripStop(
                    Stop(it.stopId, names[it.stopId] ?: it.name, Point(it.lat, it.lon), it.code, it.stationKey, it.platform, Accessibility.fromGtfs(it.wheelchair)),
                    it.sequence, GtfsTime.instant(serviceDate, it.arrival), GtfsTime.instant(serviceDate, it.departure),
                )
            },
            shape = shape.ifEmpty { stops.map { Point(it.lat, it.lon) } },
            freshness = Freshness.SCHEDULED,
        )
    }

    private fun RouteRow.toRoute(): Route {
        val c = ColorContrast.parseHex(color)
        return Route(id, shortName, longName, TransportMode.fromGtfs(type), agencies[agencyId], c, c?.let { ColorContrast.readableOn(it, ColorContrast.parseHex(textColor)) })
    }

    override suspend fun routes(ids: Collection<String>): Map<String, Route> = io {
        prepare(this)
        ids.chunked(500).flatMap { routes(it) }.associate { it.id to it.toRoute() }
    }

    override suspend fun operators(): List<Operator> = io { agencies().map { Operator(it.id, it.name, it.url, it.phone) } }

    companion object {
        /** Trains first, then numeric line order ("2" < "10" < "201"), then alphabetic. */
        val lineOrder: Comparator<Line> = compareBy<Line>({ it.mode != TransportMode.TRAIN }, { it.name.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it.name })
    }
}

/** Safety cap on stop_times rows scanned per service day for one board. */
private const val MAX_BOARD_ROWS = 20_000
