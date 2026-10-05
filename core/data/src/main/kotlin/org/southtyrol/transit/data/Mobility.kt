package org.southtyrol.transit.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.southtyrol.transit.model.BoundingBox
import org.southtyrol.transit.model.FreshnessPolicy
import org.southtyrol.transit.model.MobilityDataSource
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.Point
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Open Data Hub Mobility API v2 (public, no key). One data type per station type; values older than
 * a week are treated as dead sensors and dropped, newer-but-old values are marked stale.
 */
class OdhMobilitySource(
    private val http: TransitHttp,
    override val kind: MobilityKind,
    private val base: String = "https://mobility.api.opendatahub.com/v2/flat,node/",
) : MobilityDataSource {
    private val json = Json { ignoreUnknownKeys = true }

    private val stationType = when (kind) {
        MobilityKind.PARKING -> "ParkingStation"
        MobilityKind.BIKE_SHARING -> "BikesharingStation"
        MobilityKind.CAR_SHARING -> "CarsharingStation"
    }
    private val dataType = when (kind) {
        MobilityKind.PARKING -> "free"
        else -> "number-available"
    }

    override suspend fun points(box: BoundingBox, now: Instant): List<MobilityPoint> = guarded {
        val url = endpoint(
            base, "$stationType/$dataType/latest",
            mapOf(
                "limit" to "500",
                "where" to "sactive.eq.true,scoordinate.bbi.(${box.west},${box.south},${box.east},${box.north},4326)",
                "select" to "scode,sname,scoordinate,sorigin,mvalue,mvalidtime,smetadata.capacity",
            ),
        )
        val bytes = http.bytes(url, "application/json", maxBytes = 5L * 1024 * 1024)
        withContext(Dispatchers.Default) { parse(bytes.decodeToString(), kind, now) }
    }

    fun parse(body: String, kind: MobilityKind, now: Instant): List<MobilityPoint> {
        val data = json.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val coordinate = o["scoordinate"] as? JsonObject ?: return@mapNotNull null
            val point = Point(coordinate["y"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null, coordinate["x"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null)
            if (!point.isValid) return@mapNotNull null
            val time = (o["mvalidtime"] as? JsonPrimitive)?.content?.let(::parseTime)
            if (time != null && Duration.between(time, now) > Duration.ofDays(7)) return@mapNotNull null
            MobilityPoint(
                id = "${kind.name}:${o["scode"]?.jsonPrimitive?.content}",
                kind = kind,
                name = o["sname"]?.jsonPrimitive?.content.orEmpty(),
                point = point,
                available = (o["mvalue"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
                capacity = (o["smetadata.capacity"] as? JsonPrimitive)?.intOrNull,
                updatedAt = time,
                provider = o["sorigin"]?.jsonPrimitive?.content.orEmpty(),
                freshness = FreshnessPolicy.mobility(time, now),
            )
        }.distinctBy { it.id }
    }

    companion object {
        private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSZ")
        fun parseTime(value: String): Instant? = runCatching { OffsetDateTime.parse(value, formatter).toInstant() }.getOrNull()
    }
}

data class MobilityResult(val points: List<MobilityPoint>, val failed: Set<MobilityKind>)

/** Aggregates optional layers; one failing provider never affects the others or core transit. */
class MobilityRepository(private val sources: List<MobilityDataSource>) {
    suspend fun points(box: BoundingBox, enabled: Set<MobilityKind>, now: Instant = Instant.now()): MobilityResult = coroutineScope {
        val results = sources.filter { it.kind in enabled }.map { source ->
            async {
                try {
                    source.kind to source.points(box, now)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    source.kind to null
                }
            }
        }.awaitAll()
        MobilityResult(results.flatMap { it.second.orEmpty() }, results.filter { it.second == null }.map { it.first }.toSet())
    }
}
