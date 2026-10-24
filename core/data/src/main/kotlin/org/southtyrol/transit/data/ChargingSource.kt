package org.southtyrol.transit.data

import kotlinx.coroutines.CancellationException
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
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.Charging
import org.southtyrol.transit.model.ChargingConnector
import org.southtyrol.transit.model.ChargingDetails
import org.southtyrol.transit.model.ChargingPoint
import org.southtyrol.transit.model.ChargingStatus
import org.southtyrol.transit.model.ConnectorType
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.MobilityDataSource
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.Point
import java.time.Duration
import java.time.Instant

/**
 * Charging stations from the Open Data Hub Mobility API v2: the stations (EChargingStation), their points
 * (EChargingPlug, linked by the plug's `pcode`) and each point's latest status, fetched together and joined
 * here. The status call is best effort: without it the stations still show, with no live count.
 */
class OdhChargingSource(
    private val http: TransitHttp,
    private val base: String = "https://mobility.api.opendatahub.com/v2/flat,node/",
) : MobilityDataSource {
    override val kind = MobilityKind.CHARGING
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun points(box: BoundingBox, now: Instant): List<MobilityPoint> = guarded {
        val where = "sactive.eq.true,scoordinate.bbi.(${box.west},${box.south},${box.east},${box.north},4326)"
        coroutineScope {
            val stations = async { get("EChargingStation", where, "scode,sname,scoordinate,sorigin,smetadata", 500) }
            val plugs = async { get("EChargingPlug", where, "scode,pcode,smetadata", 3000) }
            val statuses = async {
                try {
                    get("EChargingPlug/$STATUS_TYPES/latest", where, "scode,mvalue,mvalidtime", 3000)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            val (s, p, st) = Triple(stations.await(), plugs.await(), statuses.await())
            withContext(Dispatchers.Default) { parse(s, p, st, now) }
        }
    }

    private suspend fun get(path: String, where: String, select: String, limit: Int): String =
        http.bytes(endpoint(base, path, mapOf("limit" to "$limit", "where" to where, "select" to select)), "application/json", maxBytes = 5L * 1024 * 1024).decodeToString()

    fun parse(stationsBody: String, plugsBody: String, statusesBody: String?, now: Instant): List<MobilityPoint> {
        val statuses = data(statusesBody).associate { o ->
            o.string("scode") to (o.string("mvalue") to o.string("mvalidtime")?.let(OdhMobilitySource::parseTime))
        }
        val plugsByStation = data(plugsBody).groupBy { it.string("pcode") }
        return data(stationsBody).mapNotNull { o ->
            val code = o.string("scode") ?: return@mapNotNull null
            val coordinate = o["scoordinate"] as? JsonObject ?: return@mapNotNull null
            val point = Point(coordinate.double("y") ?: return@mapNotNull null, coordinate.double("x") ?: return@mapNotNull null)
            if (!point.isValid) return@mapNotNull null
            val meta = o["smetadata"] as? JsonObject ?: JsonObject(emptyMap())
            if (meta.string("state").equals("PLANNED", ignoreCase = true)) return@mapNotNull null
            val described = plugsByStation[code].orEmpty().map { it to describe(it["smetadata"] as? JsonObject) }
            if (described.isNotEmpty() && described.all { it.second.hydrogen }) return@mapNotNull null

            var updatedAt: Instant? = null
            val points = described.filterNot { it.second.hydrogen }.mapNotNull { (plug, info) ->
                val id = plug.string("scode") ?: return@mapNotNull null
                val (raw, time) = statuses[id] ?: (null to null)
                val current = time != null && Duration.between(time, now).seconds <= FreshnessPolicy.CHARGING_MAX_AGE
                val status = if (raw == null || !current) ChargingStatus.UNKNOWN else Charging.status(raw) ?: return@mapNotNull null
                if (current && (updatedAt == null || time!! > updatedAt)) updatedAt = time
                ChargingPoint(id, info.connector, status)
            }.ifEmpty {
                // No point listed: the station's own capacity, if any, as points nobody describes.
                List(meta.int("capacity") ?: 0) { ChargingPoint("$code#$it", null, ChargingStatus.UNKNOWN) }
            }
            val address = meta.string("address")?.trim()?.takeIf { it.isNotEmpty() }
            val details = ChargingDetails(
                address = address,
                city = Charging.cityName(meta.string("city") ?: meta.string("municipality")),
                openAllDay = (meta["opening_times"] as? JsonObject)?.let { (it["twentyfourseven"] as? JsonPrimitive)?.booleanOrNull } == true ||
                    meta.string("accessInfo")?.trim() == "24/7",
                privateWithPublicAccess = meta.string("accessType") == "PRIVATE_WITHPUBLICACCESS",
                rfid = described.any { it.second.rfid },
                paymentUrl = meta.string("paymentInfo")?.trim()?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                points = points,
            )
            MobilityPoint(
                id = "${MobilityKind.CHARGING.name}:$code",
                kind = MobilityKind.CHARGING,
                name = Charging.title(o.string("sname").orEmpty(), address).orEmpty(),
                point = point,
                available = if (details.hasStatus) details.free else null,
                capacity = points.size.takeIf { it > 0 },
                updatedAt = updatedAt,
                provider = meta.string("provider") ?: o.string("sorigin").orEmpty().substringBefore('-'),
                freshness = FreshnessPolicy.charging(updatedAt, now),
                charging = details,
            )
        }.distinctBy { it.id }
    }

    private class PlugInfo(val connector: ChargingConnector?, val hydrogen: Boolean, val rfid: Boolean)

    /** OCPI plugs list `connectors`; older feeds list `outlets`. A point with several takes its most powerful. */
    private fun describe(meta: JsonObject?): PlugInfo {
        meta ?: return PlugInfo(null, hydrogen = false, rfid = false)
        val rfid = (meta["capabilities"] as? JsonArray)?.any { (it as? JsonPrimitive)?.content == "RFID_READER" } == true
        val connectors = (meta["connectors"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().map { c ->
            ChargingConnector(
                type = Charging.connectorType(c.string("standard")),
                powerKw = c.double("max_electric_power")?.takeIf { it > 0 }?.div(1000),
                dc = c.string("power_type")?.startsWith("DC") == true,
                cable = when (c.string("format")) { "CABLE" -> true; "SOCKET" -> false; else -> null },
            )
        }
        val outlets = (meta["outlets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val hydrogen = outlets.isNotEmpty() && outlets.all { Charging.isHydrogen(it.string("outletTypeCode")) }
        val fromOutlets = outlets.filterNot { Charging.isHydrogen(it.string("outletTypeCode")) }.map { c ->
            val type = Charging.connectorType(c.string("outletTypeCode"))
            ChargingConnector(
                type = type,
                powerKw = c.double("maxPower")?.takeIf { it > 0 },
                dc = type == ConnectorType.CCS || type == ConnectorType.CHADEMO,
                cable = (c["hasFixedCable"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
        return PlugInfo((connectors + fromOutlets).maxByOrNull { it.powerKw ?: 0.0 }, hydrogen, rfid)
    }

    private fun data(body: String?): List<JsonObject> =
        body?.let { (json.parseToJsonElement(it).jsonObject["data"] as? JsonArray)?.mapNotNull { e -> e as? JsonObject } }.orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        /** Each operator family reports its points under its own data type. */
        const val STATUS_TYPES = "echarging-plug-status,echarging-plug-status-ocpi,echarging-plug-status-oicp"
    }
}
