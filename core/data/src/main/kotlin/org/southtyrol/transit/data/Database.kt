package org.southtyrol.transit.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------------------------
// Static schedule database. One SQLite file per imported feed version; see ScheduleStore.
// Integer surrogate keys keep the ~1M stop_times rows compact.
// ---------------------------------------------------------------------------------------------

@Entity(tableName = "agency")
data class AgencyRow(@PrimaryKey val id: String, val name: String, val url: String, val phone: String)

@Entity(
    tableName = "stops",
    indices = [Index(value = ["id"], unique = true), Index(value = ["stationKey"]), Index(value = ["lat", "lon"])],
)
data class StopRow(
    @PrimaryKey val idx: Int,
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val code: String,
    val stationKey: String,
    val platform: String,
    val wheelchair: Int,
    val locationType: Int,
)

data class NextStopRow(val platformId: String, val nextId: String, val nextName: String, val calls: Int)

/** Full-text index over every stop name variant (all languages, accent-folded). */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["stationKey"])
@Entity(tableName = "stop_search")
data class StopSearchRow(val stationKey: String, val text: String)

@Entity(tableName = "routes", indices = [Index(value = ["lineKey"])])
data class RouteRow(
    @PrimaryKey val id: String,
    val shortName: String,
    val longName: String,
    val type: Int,
    val agencyId: String,
    val color: String,
    val textColor: String,
    val lineKey: String,
)

@Entity(
    tableName = "trips",
    indices = [Index(value = ["id"], unique = true), Index(value = ["routeId", "direction"]), Index(value = ["serviceId"])],
)
data class TripRow(
    @PrimaryKey val idx: Int,
    val id: String,
    val routeId: String,
    val serviceId: String,
    val headsign: String,
    val direction: Int,
    val shapeId: String,
    val wheelchair: Int,
)

@Entity(tableName = "stop_times", primaryKeys = ["tripIdx", "sequence"], indices = [Index(value = ["stopIdx", "departure"])])
data class StopTimeRow(
    val tripIdx: Int,
    val sequence: Int,
    val stopIdx: Int,
    val arrival: Int,
    val departure: Int,
    /** pickup_type in the low nibble, drop_off_type in the next. */
    val flags: Int,
)

@Entity(tableName = "calendar")
data class CalendarRow(@PrimaryKey val serviceId: String, val start: Int, val end: Int, val weekdays: Int)

@Entity(tableName = "calendar_dates", primaryKeys = ["serviceId", "date"], indices = [Index(value = ["date"])])
data class CalendarDateRow(val serviceId: String, val date: Int, val added: Boolean)

@Entity(tableName = "shapes", primaryKeys = ["id", "part"])
data class ShapeRow(val id: String, val part: Int, val polyline: String)

@Entity(tableName = "translations", primaryKeys = ["tableName", "recordId", "language"])
data class TranslationRow(val tableName: String, val recordId: String, val language: String, val text: String)

@Entity(tableName = "meta")
data class MetaRow(@PrimaryKey val key: String, val value: String)

data class BoardRow(
    val tripId: String,
    val stopId: String,
    val sequence: Int,
    val arrival: Int,
    val departure: Int,
    val flags: Int,
    val serviceId: String,
    val headsign: String,
    val routeId: String,
    val shortName: String,
    val longName: String,
    val type: Int,
    val color: String,
    val textColor: String,
    val agencyId: String,
    val platform: String,
    val lastSequence: Int,
)

data class TripStopRow(
    @ColumnInfo(name = "sequence") val sequence: Int,
    val arrival: Int,
    val departure: Int,
    val flags: Int,
    @ColumnInfo(name = "stopId") val stopId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val code: String,
    val stationKey: String,
    val platform: String,
    val wheelchair: Int,
)

data class LineRow(val lineKey: String, val shortName: String, val longName: String, val type: Int, val color: String, val textColor: String, val routeIds: String)

data class VariantRow(val routeId: String, val direction: Int, val shapeId: String, val headsign: String, val sampleTrip: String, val trips: Int)

data class VariantTripRow(val tripId: String, val serviceId: String, val departure: Int, val headsign: String, val stopId: String, val sequence: Int)

@Dao
interface ScheduleDao {
    @Query("SELECT value FROM meta WHERE `key` = :key") suspend fun meta(key: String): String?

    @Query("SELECT s.* FROM stops s WHERE s.stationKey IN (SELECT stationKey FROM stop_search WHERE stop_search MATCH :match) AND s.locationType = 0 LIMIT :limit")
    suspend fun searchStops(match: String, limit: Int): List<StopRow>

    @Query("SELECT * FROM stops WHERE locationType = 0 AND lat BETWEEN :south AND :north AND lon BETWEEN :west AND :east LIMIT :limit")
    suspend fun stopsIn(south: Double, north: Double, west: Double, east: Double, limit: Int): List<StopRow>

    @Query("SELECT * FROM stops WHERE id = :id") suspend fun stop(id: String): StopRow?
    @Query("SELECT * FROM stops WHERE stationKey = :key AND locationType = 0 ORDER BY platform, id") suspend fun station(key: String): List<StopRow>

    @Query(
        """
        SELECT t.id AS tripId, s.id AS stopId, st.sequence, st.arrival, st.departure, st.flags, t.serviceId, t.headsign,
               r.id AS routeId, r.shortName, r.longName, r.type, r.color, r.textColor, r.agencyId, s.platform,
               (SELECT MAX(x.sequence) FROM stop_times x WHERE x.tripIdx = st.tripIdx) AS lastSequence
        FROM stop_times st
        JOIN stops s ON s.idx = st.stopIdx
        JOIN trips t ON t.idx = st.tripIdx
        JOIN routes r ON r.id = t.routeId
        WHERE s.id IN (:stopIds) AND st.departure BETWEEN :low AND :high
        ORDER BY st.departure, st.tripIdx
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun departures(stopIds: Collection<String>, low: Int, high: Int, limit: Int, offset: Int = 0): List<BoardRow>

    @Query(
        """
        SELECT t.id AS tripId, s.id AS stopId, st.sequence, st.arrival, st.departure, st.flags, t.serviceId, t.headsign,
               r.id AS routeId, r.shortName, r.longName, r.type, r.color, r.textColor, r.agencyId, s.platform,
               (SELECT MAX(x.sequence) FROM stop_times x WHERE x.tripIdx = st.tripIdx) AS lastSequence
        FROM stop_times st
        JOIN stops s ON s.idx = st.stopIdx
        JOIN trips t ON t.idx = st.tripIdx
        JOIN routes r ON r.id = t.routeId
        WHERE s.id IN (:stopIds) AND st.arrival BETWEEN :low AND :high
        ORDER BY st.arrival, st.tripIdx
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun arrivals(stopIds: Collection<String>, low: Int, high: Int, limit: Int, offset: Int = 0): List<BoardRow>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM stop_times st JOIN stops s ON s.idx = st.stopIdx
            WHERE s.id IN (:stopIds) AND (
                st.arrival != st.departure OR st.flags != 0
                OR st.sequence = (SELECT MIN(x.sequence) FROM stop_times x WHERE x.tripIdx = st.tripIdx)
                OR st.sequence = (SELECT MAX(x.sequence) FROM stop_times x WHERE x.tripIdx = st.tripIdx)
            )
        )
        """,
    )
    suspend fun arrivalsDiffer(stopIds: Collection<String>): Boolean

    /** For each platform, how often each stop follows it (the next call of every trip departing there). */
    @Query(
        """
        SELECT s.id AS platformId, n.id AS nextId, n.name AS nextName, COUNT(*) AS calls
        FROM stop_times st
        JOIN stops s ON s.idx = st.stopIdx
        JOIN stop_times nst ON nst.tripIdx = st.tripIdx
            AND nst.sequence = (SELECT MIN(x.sequence) FROM stop_times x WHERE x.tripIdx = st.tripIdx AND x.sequence > st.sequence)
        JOIN stops n ON n.idx = nst.stopIdx
        WHERE s.id IN (:platformIds)
        GROUP BY s.id, n.id
        """,
    )
    suspend fun nextStops(platformIds: Collection<String>): List<NextStopRow>

    @Query("SELECT DISTINCT r.lineKey FROM stop_times st JOIN stops s ON s.idx = st.stopIdx JOIN trips t ON t.idx = st.tripIdx JOIN routes r ON r.id = t.routeId WHERE s.id IN (:stopIds)")
    suspend fun lineKeysAtStops(stopIds: Collection<String>): List<String>

    @Query(
        """
        SELECT lineKey, MIN(shortName) AS shortName, MAX(longName) AS longName, MIN(type) AS type, MAX(color) AS color, MAX(textColor) AS textColor,
               GROUP_CONCAT(id, '|') AS routeIds
        FROM routes WHERE lineKey IN (:keys) GROUP BY lineKey
        """,
    )
    suspend fun lines(keys: Collection<String>): List<LineRow>

    @Query(
        """
        SELECT lineKey, MIN(shortName) AS shortName, MAX(longName) AS longName, MIN(type) AS type, MAX(color) AS color, MAX(textColor) AS textColor,
               GROUP_CONCAT(id, '|') AS routeIds
        FROM routes WHERE shortName LIKE :query || '%' OR longName LIKE '%' || :query || '%' GROUP BY lineKey ORDER BY LENGTH(MIN(shortName)), MIN(shortName) LIMIT 200
        """,
    )
    suspend fun searchLines(query: String): List<LineRow>

    @Query(
        """
        SELECT t.routeId, t.direction, t.shapeId, MAX(t.headsign) AS headsign, MIN(t.id) AS sampleTrip, COUNT(*) AS trips
        FROM trips t WHERE t.routeId IN (:routeIds) GROUP BY t.routeId, t.direction, t.shapeId ORDER BY trips DESC LIMIT 12
        """,
    )
    suspend fun variants(routeIds: Collection<String>): List<VariantRow>

    @Query(
        """
        SELECT t.id AS tripId, t.serviceId, st.departure, t.headsign, s.id AS stopId, st.sequence
        FROM trips t JOIN stop_times st ON st.tripIdx = t.idx AND st.sequence = (SELECT MIN(x.sequence) FROM stop_times x WHERE x.tripIdx = t.idx)
        JOIN stops s ON s.idx = st.stopIdx
        WHERE t.routeId = :routeId AND t.direction = :direction AND t.shapeId = :shapeId AND st.departure BETWEEN :low AND :high
        ORDER BY st.departure
        """,
    )
    suspend fun variantTrips(routeId: String, direction: Int, shapeId: String, low: Int, high: Int): List<VariantTripRow>

    @Query("SELECT * FROM trips WHERE id = :id") suspend fun trip(id: String): TripRow?

    @Query(
        """
        SELECT st.sequence, st.arrival, st.departure, st.flags, s.id AS stopId, s.name, s.lat, s.lon, s.code, s.stationKey, s.platform, s.wheelchair
        FROM stop_times st JOIN stops s ON s.idx = st.stopIdx WHERE st.tripIdx = :tripIdx ORDER BY st.sequence
        """,
    )
    suspend fun tripStops(tripIdx: Int): List<TripStopRow>

    @Query("SELECT * FROM routes WHERE id IN (:ids)") suspend fun routes(ids: Collection<String>): List<RouteRow>
    @Query("SELECT * FROM agency") suspend fun agencies(): List<AgencyRow>
    @Query("SELECT * FROM shapes WHERE id = :id ORDER BY part") suspend fun shape(id: String): List<ShapeRow>
    @Query("SELECT * FROM calendar") suspend fun calendar(): List<CalendarRow>
    @Query("SELECT * FROM calendar_dates WHERE date = :date") suspend fun calendarDates(date: Int): List<CalendarDateRow>

    @Query("SELECT recordId, text FROM translations WHERE tableName = :table AND language = :language AND recordId IN (:ids)")
    suspend fun translations(table: String, language: String, ids: Collection<String>): List<TranslationText>
}

data class TranslationText(val recordId: String, val text: String)

@Database(
    entities = [
        AgencyRow::class, StopRow::class, StopSearchRow::class, RouteRow::class, TripRow::class, StopTimeRow::class,
        CalendarRow::class, CalendarDateRow::class, ShapeRow::class, TranslationRow::class, MetaRow::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ScheduleDatabase : RoomDatabase() {
    abstract fun schedule(): ScheduleDao
}

// ---------------------------------------------------------------------------------------------
// User database: saved items, recents and cached realtime/journey payloads. Never leaves the device.
// ---------------------------------------------------------------------------------------------

@Entity(tableName = "saved", primaryKeys = ["kind", "id"])
data class SavedRow(
    val kind: String,
    val id: String,
    val label: String,
    val subtitle: String = "",
    /** JSON payload, e.g. a serialized place or journey request. */
    val payload: String = "",
    val created: Long = System.currentTimeMillis(),
    val position: Int = 0,
)

@Entity(tableName = "recent", primaryKeys = ["kind", "id"], indices = [Index(value = ["used"])])
data class RecentRow(val kind: String, val id: String, val label: String, val subtitle: String, val payload: String, val used: Long)

@Entity(tableName = "cache")
data class CacheRow(@PrimaryKey val key: String, val payload: ByteArray, val fetchedAt: Long) {
    override fun equals(other: Any?) = other is CacheRow && other.key == key && other.fetchedAt == fetchedAt
    override fun hashCode() = key.hashCode() * 31 + fetchedAt.hashCode()
}

@Entity(tableName = "notified_alerts")
data class NotifiedAlertRow(@PrimaryKey val alertId: String, val notifiedAt: Long)

@Dao
interface UserDao {
    @Query("SELECT * FROM saved ORDER BY kind, position, created DESC") fun saved(): Flow<List<SavedRow>>
    @Query("SELECT * FROM saved WHERE kind = :kind ORDER BY position, created DESC") suspend fun saved(kind: String): List<SavedRow>
    @Query("SELECT COUNT(*) FROM saved WHERE kind = :kind AND id = :id") fun isSaved(kind: String, id: String): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(row: SavedRow)
    @Query("DELETE FROM saved WHERE kind = :kind AND id = :id") suspend fun unsave(kind: String, id: String)

    @Query("SELECT * FROM recent WHERE kind = :kind ORDER BY used DESC LIMIT :limit") fun recent(kind: String, limit: Int): Flow<List<RecentRow>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun touch(row: RecentRow)
    @Query("DELETE FROM recent WHERE kind = :kind AND id NOT IN (SELECT id FROM recent WHERE kind = :kind ORDER BY used DESC LIMIT :keep)")
    suspend fun trimRecent(kind: String, keep: Int)
    @Query("DELETE FROM recent") suspend fun clearRecent()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun cache(row: CacheRow)
    @Query("SELECT * FROM cache WHERE `key` = :key") suspend fun cache(key: String): CacheRow?
    @Query("DELETE FROM cache WHERE fetchedAt < :before") suspend fun pruneCache(before: Long)

    @Query("SELECT alertId FROM notified_alerts") suspend fun notifiedAlerts(): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markNotified(row: NotifiedAlertRow)
    @Query("DELETE FROM notified_alerts WHERE notifiedAt < :before") suspend fun pruneNotified(before: Long)
}

@Database(entities = [SavedRow::class, RecentRow::class, CacheRow::class, NotifiedAlertRow::class], version = 1, exportSchema = true)
abstract class UserDatabase : RoomDatabase() {
    abstract fun user(): UserDao
}
