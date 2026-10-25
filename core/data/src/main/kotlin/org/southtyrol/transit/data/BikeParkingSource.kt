package org.southtyrol.transit.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.southtyrol.transit.model.BikeParkingDetails
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.EbikeSharingDetails
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.MobilityDataSource
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.Point
import java.time.Instant

/**
 * STA's Bicibox bike parking from the Open Data Hub Mobility API v2 (BikeParking): the sites with their
 * metadata and the latest free counts, fetched together and joined here. Every site lists its places, so
 * the capacity and the places with a socket come from the metadata, not from a measurement.
 */
class OdhBikeParkingSource(
    private val http: TransitHttp,
    private val base: String = "https://mobility.api.opendatahub.com/v2/flat,node/",
) : MobilityDataSource {
    override val kind = MobilityKind.BIKE_PARKING

    override suspend fun points(box: BoundingBox, now: Instant): List<MobilityPoint> = guarded {
        val where = where(box)
        coroutineScope {
            val stations = async { odhGet(http, base, "BikeParking", where, "scode,sname,scoordinate,sorigin,smetadata", 500) }
            val values = async { odhGet(http, base, "BikeParking/$VALUE_TYPES/latest", where, "scode,tname,mvalue,mvalidtime", 2000) }
            val (s, v) = stations.await() to values.await()
            withContext(Dispatchers.Default) { parse(s, v, now) }
        }
    }

    fun parse(stationsBody: String, valuesBody: String, now: Instant): List<MobilityPoint> {
        val values = latestValues(valuesBody)
        return odhData(stationsBody).mapNotNull { o ->
            val code = o.str("scode") ?: return@mapNotNull null
            val name = o.str("sname").orEmpty()
            // The operator's test site ("Comune DEMO") is published with the real ones.
            if (name.contains("demo", ignoreCase = true)) return@mapNotNull null
            val point = o.point() ?: return@mapNotNull null
            val meta = o["smetadata"] as? JsonObject ?: JsonObject(emptyMap())
            val netex = meta["netex_parking"] as? JsonObject ?: JsonObject(emptyMap())
            val places = (meta["stationPlaces"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val v = values[code].orEmpty()
            val time = v.values.mapNotNull { it.second }.maxOrNull()
            val details = BikeParkingDetails(
                depot = meta.str("type") == "veloHub",
                addresses = (meta["addresses"] as? JsonObject ?: JsonObject(emptyMap())).mapNotNull { (k, a) ->
                    (a as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { k to it }
                }.toMap(),
                regularFree = v["freeSpotsRegularBike"]?.first?.toIntOrNull(),
                electricFree = v["freeSpotsElectricBike"]?.first?.toIntOrNull(),
                electricPlaces = places.count { p -> p.str("type").let { it != null && it != "withoutRefill" } },
                covered = netex.str("layout") == "covered",
                surveillance = (netex["surveillance"] as? JsonPrimitive)?.booleanOrNull == true,
                bookingRequired = netex.str("reservation") == "reservationRequired",
                outOfService = v["usageState"]?.first.equals("out of service", ignoreCase = true),
            )
            MobilityPoint(
                id = "${kind.name}:$code",
                kind = kind,
                // "Bressanone / Bicibox individuali": the place, then the kind of site, which the sheet says itself.
                name = name.substringBefore(" / ").trim(),
                point = point,
                available = v["free"]?.first?.toIntOrNull(),
                capacity = (meta["totalPlaces"] as? JsonPrimitive)?.intOrNull ?: places.size.takeIf { it > 0 },
                updatedAt = time,
                provider = "STA",
                freshness = FreshnessPolicy.mobility(time, now),
                bikeParking = details,
            )
        }.distinctBy { it.id }
    }

    private companion object {
        const val VALUE_TYPES = "free,freeSpotsRegularBike,freeSpotsElectricBike,usageState"
    }
}

/**
 * Ecospazio's e-bike sharing from the Open Data Hub Mobility API v2 (BIKE_CHARGER): dock stations with the
 * e-bikes waiting in them (`availableVehicles`), the empty docks (`freebay`) and the station's state, which
 * is READY only while the station is in service.
 */
class OdhEbikeSharingSource(
    private val http: TransitHttp,
    private val base: String = "https://mobility.api.opendatahub.com/v2/flat,node/",
) : MobilityDataSource {
    override val kind = MobilityKind.EBIKE_SHARING

    override suspend fun points(box: BoundingBox, now: Instant): List<MobilityPoint> = guarded {
        val where = where(box)
        coroutineScope {
            val stations = async { odhGet(http, base, "BIKE_CHARGER", where, "scode,sname,scoordinate,sorigin,smetadata", 500) }
            val values = async { odhGet(http, base, "BIKE_CHARGER/$VALUE_TYPES/latest", where, "scode,tname,mvalue,mvalidtime", 2000) }
            val (s, v) = stations.await() to values.await()
            withContext(Dispatchers.Default) { parse(s, v, now) }
        }
    }

    fun parse(stationsBody: String, valuesBody: String, now: Instant): List<MobilityPoint> {
        val values = latestValues(valuesBody)
        return odhData(stationsBody).mapNotNull { o ->
            val code = o.str("scode") ?: return@mapNotNull null
            val point = o.point() ?: return@mapNotNull null
            val meta = o["smetadata"] as? JsonObject ?: JsonObject(emptyMap())
            val v = values[code].orEmpty()
            val time = v.values.mapNotNull { it.second }.maxOrNull()
            val docks = (meta["totalBays"] as? JsonPrimitive)?.intOrNull
            val details = EbikeSharingDetails(
                bikes = v["availableVehicles"]?.first?.toIntOrNull(),
                freeDocks = v["freebay"]?.first?.toIntOrNull(),
                docks = docks,
                inService = v["state"]?.first == "READY",
            )
            MobilityPoint(
                id = "${kind.name}:$code",
                kind = kind,
                // Names come as "Italian - German" and sometimes carry a tab.
                name = o.str("sname").orEmpty().replace(Regex("\\s+"), " ").trim(),
                point = point,
                available = details.bikes,
                capacity = docks,
                updatedAt = time,
                provider = o.str("sorigin").orEmpty().lowercase().replaceFirstChar { it.uppercase() },
                freshness = FreshnessPolicy.mobility(time, now),
                ebikeSharing = details,
            )
        }.distinctBy { it.id }
    }

    private companion object {
        const val VALUE_TYPES = "availableVehicles,freebay,state"
    }
}

private val odhJson = Json { ignoreUnknownKeys = true }

private fun where(box: BoundingBox) = "sactive.eq.true,scoordinate.bbi.(${box.west},${box.south},${box.east},${box.north},4326)"

private suspend fun odhGet(http: TransitHttp, base: String, path: String, where: String, select: String, limit: Int): String =
    http.bytes(endpoint(base, path, mapOf("limit" to "$limit", "where" to where, "select" to select)), "application/json", maxBytes = 5L * 1024 * 1024).decodeToString()

private fun odhData(body: String): List<JsonObject> =
    (odhJson.parseToJsonElement(body).jsonObject["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** Latest value per station and data type: the value as text (whole numbers without ".0") and its time. */
private fun latestValues(body: String): Map<String, Map<String, Pair<String?, Instant?>>> =
    odhData(body).mapNotNull { o ->
        val code = o.str("scode") ?: return@mapNotNull null
        val type = o.str("tname") ?: return@mapNotNull null
        val value = o.str("mvalue")?.let { v -> v.toDoubleOrNull()?.toInt()?.toString() ?: v }
        Triple(code, type, value to o.str("mvalidtime")?.let(OdhMobilitySource::parseTime))
    }.groupBy { it.first }.mapValues { (_, rows) -> rows.associate { it.second to it.third } }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

private fun JsonObject.point(): Point? {
    val c = this["scoordinate"] as? JsonObject ?: return null
    val p = Point((c["y"] as? JsonPrimitive)?.doubleOrNull ?: return null, (c["x"] as? JsonPrimitive)?.doubleOrNull ?: return null)
    return p.takeIf { it.isValid }
}
