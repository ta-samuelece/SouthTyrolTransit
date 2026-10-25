package org.southtyrol.transit.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import org.southtyrol.transit.model.Freshness
import org.southtyrol.transit.model.MobilityKind
import org.southtyrol.transit.model.MobilityPoint
import org.southtyrol.transit.model.ParkingForecast
import org.southtyrol.transit.model.ParkingForecastSource
import org.southtyrol.transit.model.ParkingForecasts
import org.southtyrol.transit.model.RawParkingForecast
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * One car park's forecast from the Open Data Hub Mobility API v2: its latest `parking-forecast-*` values
 * and its `free` history for the chart's past bars, fetched together when the car park is opened. The
 * history is best effort: without it the chart simply has no past bars.
 */
class OdhParkingForecastSource(
    private val http: TransitHttp,
    private val base: String = "https://mobility.api.opendatahub.com/v2/flat,node/",
) : ParkingForecastSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun forecast(point: MobilityPoint, now: Instant): ParkingForecast? {
        if (point.kind != MobilityKind.PARKING || point.freshness != Freshness.LIVE) return null
        val code = point.id.substringAfter(':').replace("\"", "")
        return guarded {
            coroutineScope {
                val raw = async { get("ParkingStation/*/latest", "scode.eq.\"$code\",tname.ire.forecast", "tname,mvalue,mvalidtime") }
                val history = async {
                    val from = PATH_TIME.format(now.minus(ParkingForecasts.HISTORY).minus(Duration.ofMinutes(30)))
                    try {
                        get("ParkingStation/free/$from/${PATH_TIME.format(now)}", "scode.eq.\"$code\"", "mvalue,mvalidtime")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                val (r, h) = raw.await() to history.await()
                withContext(Dispatchers.Default) {
                    ParkingForecasts.build(point.capacity, point.available, h?.let(::parseHistory).orEmpty(), parseForecast(r), now)
                }
            }
        }
    }

    private suspend fun get(path: String, where: String, select: String): String =
        http.bytes(endpoint(base, path, mapOf("limit" to "-1", "where" to where, "select" to select)), "application/json", maxBytes = 2L * 1024 * 1024).decodeToString()

    /** `parking-forecast-60`, `-low-60`, `-high-60`; the `-rmse-` values (a year old in the feed) are ignored. */
    fun parseForecast(body: String): List<RawParkingForecast> = data(body).mapNotNull { o ->
        val match = FORECAST.matchEntire((o["tname"] as? JsonPrimitive)?.content.orEmpty()) ?: return@mapNotNull null
        val band = when (match.groupValues[1]) {
            "low" -> RawParkingForecast.Band.LOW
            "high" -> RawParkingForecast.Band.HIGH
            else -> RawParkingForecast.Band.EXPECTED
        }
        val value = (o["mvalue"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val at = (o["mvalidtime"] as? JsonPrimitive)?.content?.let(OdhMobilitySource::parseTime) ?: return@mapNotNull null
        RawParkingForecast(match.groupValues[2].toInt(), band, value, at)
    }

    fun parseHistory(body: String): List<Pair<Instant, Int>> = data(body).mapNotNull { o ->
        val value = (o["mvalue"] as? JsonPrimitive)?.doubleOrNull?.roundToInt() ?: return@mapNotNull null
        val at = (o["mvalidtime"] as? JsonPrimitive)?.content?.let(OdhMobilitySource::parseTime) ?: return@mapNotNull null
        at to value
    }

    private fun data(body: String): List<JsonObject> =
        (json.parseToJsonElement(body).jsonObject["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private companion object {
        val FORECAST = Regex("""parking-forecast-(?:(low|high)-)?(\d+)""")
        /** The API's path time, read as UTC. */
        val PATH_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm").withZone(ZoneOffset.UTC)
    }
}

/**
 * Forecasts for the car park sheet, kept for five minutes per car park: the source is updated hourly and
 * reopening a sheet should not download it again. A failure shows no chart and is not kept.
 */
class ParkingForecastRepository(private val source: ParkingForecastSource) {
    private val mutex = Mutex()
    private val cache = HashMap<String, Pair<Instant, ParkingForecast?>>()

    suspend fun forecast(point: MobilityPoint, now: Instant = Instant.now()): ParkingForecast? {
        mutex.withLock { cache[point.id]?.takeIf { Duration.between(it.first, now) < KEEP }?.let { return it.second } }
        val result = try {
            source.forecast(point, now)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        mutex.withLock { cache[point.id] = now to result }
        return result
    }

    private companion object {
        val KEEP: Duration = Duration.ofMinutes(5)
    }
}
