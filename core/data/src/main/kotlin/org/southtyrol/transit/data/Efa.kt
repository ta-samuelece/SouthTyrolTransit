package org.southtyrol.transit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.southtyrol.transit.model.AlertDataSource
import org.southtyrol.transit.model.AlertSource
import org.southtyrol.transit.model.DepartureBoardDataSource
import org.southtyrol.transit.model.Departure
import org.southtyrol.transit.model.FareInformation
import org.southtyrol.transit.model.FareKind
import org.southtyrol.transit.model.FareTicket
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.GeocodingLocationDataSource
import org.southtyrol.transit.model.IntermediateStop
import org.southtyrol.transit.model.Journey
import org.southtyrol.transit.model.JourneyPlannerDataSource
import org.southtyrol.transit.model.Languages
import org.southtyrol.transit.model.Leg
import org.southtyrol.transit.model.Place
import org.southtyrol.transit.model.PlaceType
import org.southtyrol.transit.model.Point
import org.southtyrol.transit.model.RoutePreference
import org.southtyrol.transit.model.SearchOptions
import org.southtyrol.transit.model.ServiceAlert
import org.southtyrol.transit.model.ServiceState
import org.southtyrol.transit.model.TextNormalizer
import org.southtyrol.transit.model.TripLiveDataSource
import org.southtyrol.transit.model.LiveStopTime
import org.southtyrol.transit.model.TransitZone
import org.southtyrol.transit.model.TransportMode
import org.southtyrol.transit.model.WalkingSpeed
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** `yyyyMMdd`: note BASIC_ISO_DATE would append the UTC offset for zoned values, which EFA ignores. */
private val EFA_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

/** EFA reported an error that prevents a result (e.g. origin not identified). */
class EfaFailure(val code: String) : IOException("EFA error $code")

object EfaCodes {
    const val ORIGIN_NOT_FOUND = "origin"
    const val DESTINATION_NOT_FOUND = "destination"
    const val SAME_PLACE = "same"
    const val NO_ROUTE = "noroute"
}

/**
 * Client for the public STA EFA XML interface (CC0, https://efa.sta.bz.it/apb/). It is the
 * journey planner, place search and network departure board. Isolated behind the data-source
 * interfaces so another routing engine can replace it.
 */
class EfaClient(
    private val http: TransitHttp,
    private val base: String = "https://efa.sta.bz.it/apb/",
) : JourneyPlannerDataSource, GeocodingLocationDataSource, DepartureBoardDataSource, AlertDataSource, TripLiveDataSource {

    private val common = mapOf("outputFormat" to "XML", "coordOutputFormat" to "WGS84[DD.DDDDD]", "locationServerActive" to "1")

    private suspend fun get(path: String, params: Map<String, String>): ByteArray = http.bytes(endpoint(base, path, common + params), "application/xml, text/xml")

    /**
     * EFA matches names per request language: "Bozen Bahnhof" is only found in German and
     * "Bolzano stazione" only in Italian. Both are queried in parallel and merged by match quality;
     * the app language's spelling wins when both find the same place.
     */
    override suspend fun search(query: String, language: String): List<Place> = guarded {
        if (query.isBlank()) return@guarded emptyList()
        val primary = Languages.efaStopFinder(language)
        val languages = listOf(primary, if (primary == "de") "it" else "de")
        val results = coroutineScope {
            languages.map { lang ->
                async {
                    val bytes = get("XML_STOPFINDER_REQUEST", mapOf("language" to lang, "type_sf" to "any", "name_sf" to query, "anyObjFilter_sf" to "0", "anyMaxSizeHitList" to "20"))
                    withContext(Dispatchers.Default) { EfaXml.rankedPlaces(bytes) }
                }
            }.awaitAll()
        }
        EfaXml.mergeRanked(results)
    }

    override suspend fun reverse(point: Point, language: String): Place? = guarded {
        val bytes = get(
            "XML_STOPFINDER_REQUEST",
            mapOf("language" to Languages.efaStopFinder(language), "type_sf" to "coord", "name_sf" to coordinate(point), "anyObjFilter_sf" to "0"),
        )
        withContext(Dispatchers.Default) { EfaXml.places(bytes).firstOrNull() }?.copy(point = point)
    }

    suspend fun nearbyStops(point: Point, radiusMeters: Int, language: String, max: Int = 30): List<Place> = guarded {
        val bytes = get(
            "XML_COORD_REQUEST",
            mapOf("language" to Languages.efa(language), "coord" to coordinate(point), "inclFilter" to "1", "type_1" to "STOP", "radius_1" to radiusMeters.toString(), "max" to max.toString()),
        )
        withContext(Dispatchers.Default) { EfaXml.coordStops(bytes) }
    }

    override suspend fun plan(from: Place, to: Place, options: SearchOptions, language: String): List<Journey> = guarded {
        val bytes = get("XML_TRIP_REQUEST2", tripParams(from, to, options, language))
        val journeys = withContext(Dispatchers.Default) { EfaXml.journeys(bytes) }
        when (options.preference) {
            RoutePreference.FASTEST -> journeys.sortedWith(compareBy<Journey> { if (options.arriveBy) -it.bestDeparture.epochSecond else it.bestArrival.epochSecond }.thenBy { it.duration })
            RoutePreference.FEWEST_CHANGES -> journeys.sortedWith(compareBy<Journey> { it.changes }.thenBy { it.bestArrival })
            RoutePreference.LEAST_WALKING -> journeys.sortedWith(compareBy<Journey> { it.walkingDuration }.thenBy { it.bestArrival })
        }
    }

    override suspend fun departures(stopGlobalId: String, at: Instant, arrivals: Boolean, language: String, limit: Int): List<Departure> = guarded {
        val time = at.atZone(TransitZone)
        val bytes = get(
            "XML_DM_REQUEST",
            mapOf(
                "language" to Languages.efa(language), "type_dm" to "any", "name_dm" to stopGlobalId, "mode" to "direct", "useRealtime" to "1",
                "limit" to limit.toString(), "itdDateTimeDepArr" to if (arrivals) "arr" else "dep", "useAllStops" to "1",
                "itdDate" to time.format(EFA_DATE), "itdTime" to time.format(DateTimeFormatter.ofPattern("HHmm")),
            ),
        )
        withContext(Dispatchers.Default) { EfaXml.departures(bytes, Instant.now()) }
    }

    /** Live stop-by-stop times of one run: [ref] is a departure's [Departure.liveRef]. */
    override suspend fun liveTrip(ref: String): List<LiveStopTime> = guarded {
        val parts = ref.split('|')
        if (parts.size != 5 || parts.any { it.isBlank() }) return@guarded emptyList()
        val (line, stop, tripCode, date, time) = parts
        val bytes = get(
            "XML_STOPSEQCOORD_REQUEST",
            mapOf("line" to line, "stop" to stop, "tripCode" to tripCode, "date" to date, "time" to time, "useRealtime" to "1", "tStOTType" to "all"),
        )
        withContext(Dispatchers.Default) { EfaXml.stopSequence(bytes) }
    }

    override suspend fun alerts(language: String): List<ServiceAlert> = guarded {
        val bytes = get("XML_ADDINFO_REQUEST", mapOf("language" to Languages.efa(language), "filterPublished" to "1", "filterShowLineList" to "1", "filterShowStopList" to "1"))
        withContext(Dispatchers.Default) { EfaXml.alerts(bytes, Instant.now()) }
    }

    companion object {
        fun coordinate(p: Point) = "%.6f:%.6f:WGS84[DD.DDDDD]".format(java.util.Locale.ROOT, p.longitude, p.latitude)

        /** EFA motType ids used by excludedMeans, grouped by the app's modes. */
        fun efaMeans(mode: TransportMode): List<Int> = when (mode) {
            TransportMode.TRAIN -> listOf(0, 1, 13, 14, 15, 16, 18)
            TransportMode.CITY_BUS -> listOf(5)
            TransportMode.BUS -> listOf(6, 7, 17, 19, 21)
            TransportMode.CABLE_CAR, TransportMode.FUNICULAR -> listOf(8)
            TransportMode.TRAM -> listOf(2, 3, 4)
            TransportMode.ON_DEMAND -> listOf(10, 20)
            else -> emptyList()
        }

        fun tripParams(from: Place, to: Place, options: SearchOptions, language: String): Map<String, String> {
            val at = options.at.withZoneSameInstant(TransitZone)
            val params = linkedMapOf(
                "language" to Languages.efa(language), "stateless" to "1", "useRealtime" to "1", "calcNumberOfTrips" to "6",
                "coordListOutputFormat" to "STRING", "itdDate" to at.format(EFA_DATE), "itdTime" to at.format(DateTimeFormatter.ofPattern("HHmm")),
                "itdTripDateTimeDepArr" to if (options.arriveBy) "arr" else "dep", "ptOptionsActive" to "1", "itOptionsActive" to "1",
                "routeType" to when (options.preference) { RoutePreference.FASTEST -> "LEASTTIME"; RoutePreference.FEWEST_CHANGES -> "LEASTINTERCHANGE"; RoutePreference.LEAST_WALKING -> "LEASTWALKING" },
                "changeSpeed" to when (options.walkingSpeed) { WalkingSpeed.SLOW -> "slow"; WalkingSpeed.NORMAL -> "normal"; WalkingSpeed.FAST -> "fast" },
            )
            for ((key, place) in listOf("origin" to from, "destination" to to)) {
                val point = place.point
                when {
                    place.type == PlaceType.COORDINATE && point != null -> { params["type_$key"] = "coord"; params["name_$key"] = coordinate(point) }
                    place.type == PlaceType.STOP && place.id.isNotBlank() -> { params["type_$key"] = "stop"; params["name_$key"] = place.id }
                    place.id.isNotBlank() -> { params["type_$key"] = "any"; params["name_$key"] = place.id }
                    point != null -> { params["type_$key"] = "coord"; params["name_$key"] = coordinate(point) }
                    else -> { params["type_$key"] = "any"; params["name_$key"] = place.name }
                }
            }
            if (options.wheelchair) {
                params["imparedOptionsActive"] = "1"; params["wheelchair"] = "on"; params["lowPlatformVhcl"] = "on"; params["noSolidStairs"] = "on"
            }
            val excluded = options.excludedModes.flatMap(::efaMeans)
            if (excluded.isNotEmpty()) {
                params["excludedMeans"] = "checkbox"
                excluded.forEach { params["exclMOT_$it"] = "1" }
            }
            return params
        }
    }
}

/** Secure DOM parsing of EFA XML documents. */
object EfaXml {
    private fun root(bytes: ByteArray): Element {
        require(bytes.isNotEmpty()) { "Empty EFA response" }
        require(bytes.size <= 15_000_000) { "EFA response too large" }
        // Reject any DTD before parsing: the interface never uses one and it is the XXE vector.
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        require(!head.contains("<!DOCTYPE", ignoreCase = true) && !head.contains("<!ENTITY", ignoreCase = true)) { "DTD forbidden" }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            isValidating = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
        require(root.tagName == "itdRequest") { "Unexpected EFA root ${root.tagName}" }
        return root
    }

    internal fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { nodes -> List(nodes.length) { nodes.item(it) as Element } }
    internal fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) { if (n is Element && n.tagName == tag) out += n; n = n.nextSibling }
        return out
    }
    internal fun Element.child(tag: String): Element? = children(tag).firstOrNull()
    private fun Element.a(name: String): String = getAttribute(name)
    private fun Element.text(tag: String): String = child(tag)?.textContent?.trim().orEmpty()

    private fun point(x: String, y: String): Point? {
        val p = Point(y.toDoubleOrNull() ?: return null, x.toDoubleOrNull() ?: return null)
        return p.takeIf { it.isValid }
    }

    private fun placeType(anyType: String) = when (anyType.lowercase()) {
        "stop" -> PlaceType.STOP
        "street" -> PlaceType.STREET
        "address", "singlehouse" -> PlaceType.ADDRESS
        "poi" -> PlaceType.POI
        "loc", "locality", "suburb", "postcode" -> PlaceType.LOCALITY
        "coord" -> PlaceType.COORDINATE
        else -> PlaceType.UNKNOWN
    }

    fun places(bytes: ByteArray): List<Place> = rankedPlaces(bytes).map { it.first }

    /** Merges per-language result lists (first list = preferred spelling) by EFA match quality. */
    fun mergeRanked(lists: List<List<Pair<Place, Int>>>): List<Place> {
        val best = LinkedHashMap<String, Pair<Place, Int>>()
        for (list in lists) for ((place, quality) in list) {
            val existing = best[place.id]
            best[place.id] = if (existing == null) place to quality else existing.first to maxOf(existing.second, quality)
        }
        return best.values.sortedByDescending { it.second }.map { it.first }
    }

    /** Places with EFA's matchQuality (a unique identification scores highest). */
    fun rankedPlaces(bytes: ByteArray): List<Pair<Place, Int>> {
        val root = root(bytes)
        val odv = root.all("itdOdv").firstOrNull { it.a("usage") == "sf" } ?: root
        val name = odv.all("itdOdvName").firstOrNull() ?: return emptyList()
        if (name.a("state") !in setOf("identified", "list")) return emptyList()
        return name.children("odvNameElem").mapNotNull { e ->
            val id = e.a("stateless").ifBlank { e.a("id") }
            if (id.isBlank()) return@mapNotNull null
            val type = placeType(e.a("anyType"))
            val objectName = e.a("objectName").ifBlank { e.textContent.trim() }
            val locality = e.a("locality").ifBlank { e.a("mainLocality") }
            val display = when {
                type == PlaceType.LOCALITY -> objectName.ifBlank { locality }
                locality.isNotBlank() && !objectName.contains(locality, ignoreCase = true) -> "$objectName, $locality"
                else -> objectName
            }
            Place(id, display, type, point(e.a("x"), e.a("y")), locality, e.a("gid")) to (e.a("matchQuality").toIntOrNull() ?: 0)
        }.distinctBy { it.first.id }.sortedByDescending { it.second }
    }

    fun coordStops(bytes: ByteArray): List<Place> = root(bytes).all("coordInfoItem").filter { it.a("type") == "STOP" }.mapNotNull { e ->
        val coord = e.all("itdCoordinateBaseElem").firstOrNull()
        val p = coord?.let { point(it.text("x"), it.text("y")) }
        val gid = e.all("genAttrElem").firstOrNull { it.text("name") == "STOP_GLOBAL_ID" }?.text("value").orEmpty()
        val locality = e.a("locality")
        Place(e.a("stateless").ifBlank { e.a("id") }, listOf(locality, e.a("name")).filter { it.isNotBlank() }.joinToString(", "), PlaceType.STOP, p, locality, gid)
    }

    private fun dateTime(e: Element?): Instant? {
        e ?: return null
        val date = e.child("itdDate") ?: return null
        val time = e.child("itdTime") ?: return null
        val year = date.a("year").toIntOrNull() ?: return null
        if (year <= 0) return null
        val hour = time.a("hour").toIntOrNull() ?: return null
        val minute = time.a("minute").toIntOrNull() ?: return null
        if (hour < 0 || minute < 0) return null
        return LocalDate.of(year, date.a("month").toInt(), date.a("day").toInt()).atTime(hour, minute).atZone(TransitZone).toInstant()
    }

    private fun Element.place(): Place {
        val gid = a("gid")
        val stopId = a("stopID")
        val isStop = gid.isNotBlank() || (stopId.isNotBlank() && !stopId.startsWith("9999999"))
        return Place(
            id = if (isStop) stopId else "",
            name = a("name").ifBlank { a("nameWO") },
            type = if (isStop) PlaceType.STOP else PlaceType.COORDINATE,
            point = point(a("x"), a("y")),
            locality = a("locality").ifBlank { a("place") },
            stopGlobalId = gid,
        )
    }

    private fun lineLabel(m: Element?): Pair<String, String> {
        m ?: return "" to ""
        val mot = m.a("motType").toIntOrNull()
        val trainType = m.a("trainType"); val trainNum = m.a("trainNum")
        val name = m.a("name").ifBlank { m.a("number") }
        val short = when {
            TransportMode.fromEfa(mot) == TransportMode.TRAIN && trainType.isNotBlank() -> trainType
            m.a("shortname").isNotBlank() && TransportMode.fromEfa(mot) != TransportMode.TRAIN -> m.a("shortname")
            m.a("number").isNotBlank() && m.a("number").length <= 6 -> m.a("number")
            else -> m.a("symbol").ifBlank { m.a("shortname") }
        }
        val long = if (trainNum.isNotBlank() && TransportMode.fromEfa(mot) == TransportMode.TRAIN) listOf(trainType, trainNum).filter { it.isNotBlank() }.joinToString(" ") else name
        return short to long
    }

    private fun Element.isCancelled(): Boolean =
        listOf("realtimeStatus", "realtimeTripStatus").any { a(it).contains("CANCEL", ignoreCase = true) }

    private fun notices(part: Element, language: String?): List<String> = part.children("infoLink").mapNotNull { link ->
        val lang = link.a("language")
        if (language != null && lang.isNotBlank() && lang != language) return@mapNotNull null
        val info = link.child("infoText")
        listOf(info?.text("subtitle"), link.text("infoLinkText"), info?.text("subject"))
            .firstOrNull { !it.isNullOrBlank() && !it.equals("Information", ignoreCase = true) }
    }.distinct()

    fun journeys(bytes: ByteArray): List<Journey> {
        val root = root(bytes)
        val request = root.all("itdTripRequest").firstOrNull() ?: return emptyList()
        val routes = request.all("itdRoute")
        if (routes.isEmpty()) {
            for (odv in request.children("itdOdv")) {
                val state = odv.child("itdOdvName")?.a("state")
                if (state == "notidentified" || state == "empty") when (odv.a("usage")) {
                    "origin" -> throw EfaFailure(EfaCodes.ORIGIN_NOT_FOUND)
                    "destination" -> throw EfaFailure(EfaCodes.DESTINATION_NOT_FOUND)
                }
            }
            return emptyList()
        }
        val language = root.a("language").ifBlank { null }
        return routes.mapNotNull { route ->
            val legs = route.all("itdPartialRoute").mapNotNull { part -> leg(part, language) }
            if (legs.isEmpty()) return@mapNotNull null
            val transit = legs.count { it.mode.isTransit }
            Journey(
                id = legs.first().departure.epochSecond.toString() + ":" + legs.last().arrival.epochSecond + ":" + legs.joinToString("-") { it.line.ifBlank { it.mode.name } },
                legs = legs,
                changes = route.a("changes").toIntOrNull() ?: (transit - 1).coerceAtLeast(0),
                fare = fare(route.child("itdFare")),
            )
        }.distinctBy { it.id }
    }

    private fun leg(part: Element, language: String?): Leg? {
        val points = part.children("itdPoint")
        val from = points.firstOrNull { it.a("usage") == "departure" } ?: return null
        val to = points.firstOrNull { it.a("usage") == "arrival" } ?: return null
        val departure = dateTime(from.child("itdDateTimeTarget")) ?: dateTime(from.child("itdDateTime")) ?: return null
        val arrival = dateTime(to.child("itdDateTimeTarget")) ?: dateTime(to.child("itdDateTime")) ?: return null
        val estimatedDeparture = dateTime(from.child("itdDateTime"))
        val estimatedArrival = dateTime(to.child("itdDateTime"))
        val means = part.child("itdMeansOfTransport")
        val meansType = means?.a("type")?.toIntOrNull()
        val mode = if (part.a("type") == "IT" || meansType == 99 || meansType == 100) TransportMode.WALK else TransportMode.fromEfa(means?.a("motType")?.toIntOrNull())
        val (line, lineName) = lineLabel(means)
        val realtime = part.a("realtimeStatus").contains("MONITORED") || (estimatedDeparture != null && estimatedDeparture != departure) || (estimatedArrival != null && estimatedArrival != arrival)
        val intermediate = part.child("itdStopSeq")?.children("itdPoint")?.drop(1)?.dropLast(1)?.map { p ->
            val times = p.children("itdDateTime")
            val arr = dateTime(times.getOrNull(0)); val dep = dateTime(times.getOrNull(1)) ?: arr
            val arrDelay = p.a("arrDelay").toLongOrNull()?.takeIf { it in -120..600 && realtime }
            val depDelay = p.a("depDelay").toLongOrNull()?.takeIf { it in -120..600 && realtime }
            IntermediateStop(p.place(), arr, dep, arr?.let { a -> arrDelay?.let { a.plusSeconds(it * 60) } }, dep?.let { d -> depDelay?.let { d.plusSeconds(it * 60) } }, p.a("platformName"))
        }.orEmpty()
        val geometry = part.child("itdPathCoordinates")?.let { coordinates(it) }.orEmpty()
        val operator = means?.child("itdOperator")?.text("name").orEmpty()
        val distance = part.a("distance").toIntOrNull()
            ?: part.all("itdFootPathElem").firstOrNull()?.child("attributes")?.a("distance")?.toIntOrNull()
        return Leg(
            mode = mode, line = if (mode == TransportMode.WALK) "" else line, destination = means?.a("destination").orEmpty(),
            from = from.place(), to = to.place(), departure = departure, arrival = arrival,
            predictedDeparture = estimatedDeparture.takeIf { realtime }, predictedArrival = estimatedArrival.takeIf { realtime },
            intermediate = intermediate, geometry = geometry, operator = operator,
            departurePlatform = from.a("platformName"), arrivalPlatform = to.a("platformName"),
            realtime = realtime && mode.isTransit, cancelled = part.isCancelled() || (means?.isCancelled() ?: false),
            notices = notices(part, language), distanceMeters = distance, lineName = if (mode == TransportMode.WALK) "" else lineName,
        )
    }

    private fun coordinates(path: Element): List<Point> {
        path.child("itdCoordinateString")?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { s ->
            return s.split(' ').mapNotNull { pair -> pair.split(',').takeIf { it.size == 2 }?.let { point(it[0], it[1]) } }
        }
        return path.all("itdCoordinateBaseElem").mapNotNull { point(it.text("x"), it.text("y")) }
    }

    private fun fare(e: Element?): FareInformation? {
        e ?: return null
        // Tickets are listed per span of transit legs (fromPR..toPR); the journey fare spans all of them.
        val all = e.children("itdUnifiedTicket")
        val lastLeg = all.maxOfOrNull { it.a("toPR").toIntOrNull() ?: 0 } ?: 0
        val tickets = all.filter { (it.a("person") == "ADULT" || it.a("person").isBlank()) && (it.a("fromPR").toIntOrNull() ?: 0) == 0 && (it.a("toPR").toIntOrNull() ?: 0) == lastLeg }.mapNotNull { t ->
            val price = t.a("priceBrutto").toBigDecimalOrNull() ?: return@mapNotNull null
            if (price <= BigDecimal.ZERO) return@mapNotNull null
            val kind = when (t.a("id")) {
                "STANDARD" -> FareKind.SINGLE
                "VALUECARD" -> FareKind.VALUE_CARD
                "SUBSCRIPTION" -> FareKind.SUBSCRIPTION_LEVEL
                else -> FareKind.OTHER
            }
            FareTicket(t.a("name"), kind, price, t.a("currency").ifBlank { "EUR" }, t.a("person"))
        }.distinctBy { it.kind to it.name }
        return tickets.takeIf { it.isNotEmpty() }?.let { FareInformation(it, "EFA") }
    }

    fun departures(bytes: ByteArray, now: Instant): List<Departure> {
        val root = root(bytes)
        return root.all("itdDeparture").mapNotNull { d ->
            val scheduled = dateTime(d.child("itdDateTime")) ?: return@mapNotNull null
            val rt = dateTime(d.child("itdRTDateTime"))
            val line = d.child("itdServingLine")
            val (short, _) = lineLabel(line)
            val delay = line?.child("itdNoTrain")?.a("delay")?.toIntOrNull()
            val cancelled = d.isCancelled() || delay == -9999
            val realtime = line?.a("realtime") == "1"
            val mode = TransportMode.fromEfa(line?.a("motType")?.toIntOrNull())
            val tripCode = d.child("itdServingTrip")?.a("tripCode").orEmpty()
            val stateless = line?.a("stateless").orEmpty()
            val stopNumber = d.a("stopID")
            val planned = (dateTime(d.child("itdDateTimeBaseTimetable")) ?: scheduled).atZone(TransitZone)
            val liveRef = if (stateless.isBlank() || stopNumber.isBlank() || tripCode.isBlank()) "" else
                listOf(stateless, stopNumber, tripCode, planned.format(EFA_DATE), planned.format(DateTimeFormatter.ofPattern("HHmm"))).joinToString("|")
            Departure(
                tripId = "efa:" + line?.a("stateless").orEmpty() + ":" + tripCode + ":" + scheduled.epochSecond,
                stopId = d.a("gid").ifBlank { d.a("stopID") }, sequence = 0, routeId = line?.a("stateless").orEmpty(),
                line = short, mode = mode, destination = line?.a("direction").orEmpty(), scheduled = scheduled,
                predicted = (rt ?: delay?.takeIf { it in 0..600 && realtime }?.let { scheduled.plusSeconds(it * 60L) }).takeIf { realtime && !cancelled }, state = if (cancelled) ServiceState.CANCELLED else ServiceState.NORMAL,
                freshness = if (realtime) Freshness.LIVE else Freshness.SCHEDULED, platform = d.a("platformName"),
                observedAt = if (realtime) now else null, operator = line?.child("itdOperator")?.text("name").orEmpty(), tripLinked = false,
                liveRef = liveRef,
            )
        }
    }

    /** Calls of one run from XML_STOPSEQCOORD_REQUEST, with live arrival and departure delays. */
    fun stopSequence(bytes: ByteArray): List<LiveStopTime> {
        val root = root(bytes)
        val seq = root.all("stopSeq").firstOrNull() ?: return emptyList()
        return seq.children("itdPoint").mapNotNull { p ->
            val key = p.a("gid").ifBlank { return@mapNotNull null }
            val times = p.children("itdDateTime").map(::dateTime)
            val arrValid = p.a("arrValid") != "0"
            val depValid = p.a("depValid") != "0"
            val (arrival, departure) = when (times.size) {
                0 -> null to null
                1 -> times[0] to times[0]
                else -> times[0] to times[1]
            }
            fun delay(name: String) = p.a(name).toIntOrNull()?.takeIf { it in -60..600 }
            LiveStopTime(
                stationKey = key, name = p.a("name"),
                scheduledArrival = arrival.takeIf { arrValid }, scheduledDeparture = departure.takeIf { depValid },
                arrivalDelayMinutes = delay("arrDelay").takeIf { arrValid }, departureDelayMinutes = delay("depDelay").takeIf { depValid },
            )
        }
    }

    fun alerts(bytes: ByteArray, now: Instant): List<ServiceAlert> {
        val root = root(bytes)
        return root.all("itdAdditionalTravelInformation").mapNotNull { info ->
            if (info.a("publish") == "0" || info.a("deactivated") == "true") return@mapNotNull null
            val validity = info.child("validityPeriod")?.children("itdDateTime").orEmpty()
            val start = dateTime(validity.getOrNull(0)); val end = dateTime(validity.getOrNull(1))
            if (end != null && end.isBefore(now)) return@mapNotNull null
            val headers = LinkedHashMap<String, String>(); val descriptions = LinkedHashMap<String, String>()
            for (link in info.children("infoLink")) {
                val lang = Languages.normalize(link.a("language"))
                val text = link.child("infoText")
                val header = listOf(text?.text("subtitle"), link.text("infoLinkText")).firstOrNull { !it.isNullOrBlank() && !it.equals("Information", true) }
                if (header != null) headers[lang] = TextNormalizer.stripHtml(header)
                val body = listOfNotNull(text?.text("subject")?.takeIf { it.isNotBlank() }, text?.text("content")?.let(TextNormalizer::stripHtml)?.takeIf { it.isNotBlank() }).joinToString("\n\n")
                if (body.isNotBlank()) descriptions[lang] = body
            }
            if (headers.isEmpty() && descriptions.isEmpty()) return@mapNotNull null
            val lines = info.child("concernedLines")?.children("line").orEmpty()
            val stops = info.child("concernedStops")?.children("stop").orEmpty()
            val type = info.a("type")
            ServiceAlert(
                id = "efa:" + info.a("infoID"),
                headers = headers, descriptions = descriptions,
                lineNames = lines.map { it.a("number") }.filter { it.isNotBlank() }.toSet(),
                stops = stops.map { it.a("globalID") }.filter { it.isNotBlank() }.toSet(),
                periods = if (start == null && end == null) emptyList() else listOf(start to end),
                observedAt = now,
                effect = when (type) { "stopBlocking", "lineBlocking" -> "NO_SERVICE"; else -> "OTHER_EFFECT" },
                severity = when (info.a("priority")) { "veryHigh", "high" -> "SEVERE"; "normal" -> "WARNING"; else -> "INFO" },
                source = AlertSource.EFA,
            )
        }
    }
}
