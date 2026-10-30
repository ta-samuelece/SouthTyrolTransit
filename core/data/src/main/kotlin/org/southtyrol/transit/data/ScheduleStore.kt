package org.southtyrol.transit.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/** Metadata about the schedule currently in use. */
data class ScheduleInfo(
    val importedAt: Instant,
    val source: String,
    val hash: String,
    val firstDate: Int,
    val lastDate: Int,
    val stops: Int,
    val trips: Int,
    val hasShapes: Boolean,
    /** [GtfsImporter.VERSION] that built this database (0 before versions were recorded). */
    val importerVersion: Int = 0,
)

sealed class ScheduleStatus {
    data object Idle : ScheduleStatus()
    data class Downloading(val bytes: Long, val total: Long) : ScheduleStatus()
    data class Importing(val file: String, val rows: Long) : ScheduleStatus()
    data class Failed(val error: DataError) : ScheduleStatus()
    data object UpToDate : ScheduleStatus()
}

/** Why the offline timetable needs the user's attention, as checked when the app opens. */
enum class TimetableNeed { MISSING, EXPIRED, UPDATE_AVAILABLE }

object TimetableCheck {
    /**
     * [info] is null both when nothing was downloaded and when the timetable can no longer be read:
     * either way it is missing. [updateAvailable] is [ScheduleStore.updateAvailable]: an expired
     * timetable is reported unless the server confirmed there is nothing newer, since downloading again
     * would not help then. [today] is a `GtfsTime.dateKey`.
     */
    fun need(info: ScheduleInfo?, today: Int, updateAvailable: Boolean?): TimetableNeed? = when {
        info == null -> TimetableNeed.MISSING
        info.lastDate in 1 until today && updateAvailable != false -> TimetableNeed.EXPIRED
        updateAvailable == true -> TimetableNeed.UPDATE_AVAILABLE
        else -> null
    }
}

/** A static GTFS source. HTTPS sources are preferred; see docs/API_DISCOVERY.md. */
data class GtfsSource(val name: String, val url: String)

object GtfsSources {
    /** Open Data Hub GTFS API (HTTPS, CC0). */
    val OpenDataHub = GtfsSource("opendatahub", "https://gtfs.api.opendatahub.com/v1/dataset/sta-time-tables/raw")

    /**
     * STA's own publication point, which Open Data Hub mirrors. Anonymous FTP has no transport
     * security, so it is only used as a fallback and the archive is fully validated before use.
     */
    val StaFtp = GtfsSource("sta-ftp", "ftp://ftp.sta.bz.it/gtfs/google_transit_shp.zip")

    val default = listOf(OpenDataHub, StaFtp)
}

/**
 * Owns the on-disk schedule databases. Each import writes a new SQLite file; activation is an
 * atomic pointer rename, so interrupted downloads/imports never destroy the working schedule.
 */
class ScheduleStore(
    private val context: Context,
    private val http: TransitHttp,
    private val sources: List<GtfsSource> = GtfsSources.default,
    private val importer: GtfsImporter = GtfsImporter(),
    private val ftpEnabled: Boolean = true,
) {
    private val dir = File(context.filesDir, "schedule").apply { mkdirs() }
    private val downloads = TransitHttp.forLargeDownloads(http)
    private val pointer = File(dir, "active")
    private val lock = Mutex()
    private val openLock = Any()

    /** An open schedule database and how many callers are using it right now. */
    private class OpenDatabase(val name: String, val db: ScheduleDatabase) { var users = 0 }
    private var current: OpenDatabase? = null
    /** Replaced databases still in use; closed and deleted when their last user finishes. */
    private val retired = mutableListOf<OpenDatabase>()

    private val _status = MutableStateFlow<ScheduleStatus>(ScheduleStatus.Idle)
    val status: StateFlow<ScheduleStatus> = _status.asStateFlow()

    private val _info = MutableStateFlow<ScheduleInfo?>(null)
    val info: StateFlow<ScheduleInfo?> = _info.asStateFlow()

    /** Bumped whenever a new schedule is activated so caches keyed on it can be invalidated. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile private var activeCache: String? = null
    @Volatile private var activeLoaded = false

    fun activeName(): String? {
        if (!activeLoaded) {
            activeCache = pointer.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() && File(dir, it).exists() }
            activeLoaded = true
        }
        return activeCache
    }

    /**
     * Runs [block] on the active database; throws [DataError.NoSchedule] when none has been imported.
     * The database stays open until [block] returns, even when a new schedule is activated meanwhile:
     * closing it under a running query would fail that query.
     */
    suspend fun <T> withDatabase(block: suspend (ScheduleDatabase) -> T): T {
        val open = acquire() ?: throw DataException(DataError.NoSchedule)
        try {
            return block(open.db)
        } finally {
            release(open)
        }
    }

    private fun acquire(): OpenDatabase? = synchronized(openLock) {
        val name = activeName() ?: return null
        val open = current?.takeIf { it.name == name } ?: OpenDatabase(name, open(File(dir, name))).also { fresh ->
            current?.let(::retire)
            current = fresh
        }
        open.users++
        open
    }

    private fun release(open: OpenDatabase) = synchronized(openLock) {
        open.users--
        if (open.users == 0 && retired.remove(open)) dispose(open)
    }

    /** Called with [openLock] held. */
    private fun retire(open: OpenDatabase) {
        if (open.users == 0) dispose(open) else retired += open
    }

    private fun dispose(open: OpenDatabase) {
        open.db.close()
        if (open.name != activeCache) deleteDatabase(File(dir, open.name))
    }

    suspend fun loadInfo(): ScheduleInfo? = withContext(Dispatchers.IO) {
        if (activeName() == null) return@withContext null
        val info = runCatching {
            withDatabase { db ->
                val dao = db.schedule()
                ScheduleInfo(
                    importedAt = Instant.ofEpochMilli(dao.meta("importedAt")?.toLongOrNull() ?: 0),
                    source = dao.meta("source").orEmpty(),
                    hash = dao.meta("hash").orEmpty(),
                    firstDate = dao.meta("firstDate")?.toIntOrNull() ?: 0,
                    lastDate = dao.meta("lastDate")?.toIntOrNull() ?: 0,
                    stops = dao.meta("stops")?.toIntOrNull() ?: 0,
                    trips = dao.meta("trips")?.toIntOrNull() ?: 0,
                    hasShapes = dao.meta("hasShapes") == "true",
                    importerVersion = dao.meta("importerVersion")?.toIntOrNull() ?: 0,
                )
            }
        }.getOrNull()
        _info.value = info
        info
    }

    private fun open(file: File): ScheduleDatabase =
        Room.databaseBuilder(context, ScheduleDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(ScheduleDatabase.MIGRATION_1_2)
            .build()

    /**
     * Asks the preferred HTTPS source whether a newer feed exists, reading only the response headers:
     * true when one does (or the importer changed since the active feed was built), false when the
     * active feed is current, null when it cannot tell - no timetable, a feed that came from FTP, no
     * validators to compare, or no connection.
     */
    suspend fun updateAvailable(): Boolean? = withContext(Dispatchers.IO) {
        val info = loadInfo() ?: return@withContext null
        if (info.importerVersion < GtfsImporter.VERSION) return@withContext true
        val source = sources.firstOrNull { !it.url.startsWith("ftp:") }?.takeIf { it.name == info.source } ?: return@withContext null
        val (etag, modified) = runCatching { withDatabase { db -> db.schedule().let { it.meta("etag").orEmpty() to it.meta("lastModified").orEmpty() } } }
            .getOrNull() ?: return@withContext null
        if (etag.isBlank() && modified.isBlank()) return@withContext null
        val headers = buildMap {
            if (etag.isNotBlank()) put("If-None-Match", etag)
            if (modified.isNotBlank()) put("If-Modified-Since", modified)
        }
        try {
            // The body is never read: closing the response drops the connection instead.
            http.response(source.url, "application/zip, application/octet-stream", headers).use { response ->
                val newEtag = response.header("ETag").orEmpty()
                val newModified = response.header("Last-Modified").orEmpty()
                when {
                    response.code == 304 -> false
                    // A server that ignores the conditions still names the feed it would send.
                    etag.isNotBlank() && newEtag.isNotBlank() -> newEtag != etag
                    modified.isNotBlank() && newModified.isNotBlank() -> newModified != modified
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads the static feed if it changed and imports it. Returns true when a new schedule was
     * activated. Never throws for "not modified".
     */
    suspend fun refresh(allowFtp: Boolean = ftpEnabled): Boolean = lock.withLock {
        val previous = loadInfo()
        val download = File(context.cacheDir, "gtfs-download.zip")
        try {
            // Report the first (preferred) source's failure: it is the most meaningful to the user.
            var firstError: Throwable? = null
            for (source in sources) {
                if (source.url.startsWith("ftp:") && !allowFtp) continue
                try {
                    val meta = withContext(Dispatchers.IO) { fetch(source, previous, download) } ?: run {
                        _status.value = ScheduleStatus.UpToDate
                        return@withLock false
                    }
                    return@withLock importFile(download, meta + ("source" to source.name))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("ScheduleStore", "Timetable source ${source.name} failed: $e")
                    if (firstError == null) firstError = e
                }
            }
            throw firstError ?: TransferFailedException("No schedule source available")
        } catch (e: CancellationException) {
            _status.value = ScheduleStatus.Idle
            throw e
        } catch (e: Exception) {
            val error = e.toDataError()
            _status.value = ScheduleStatus.Failed(error)
            throw DataException(error, e)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { download.delete() }
        }
    }

    /** Imports a local GTFS archive (also used for tests and manual imports). */
    suspend fun importFile(zip: File, meta: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val hash = GtfsFiles.sha256(zip)
        // The same feed is imported again only when the importer itself has changed since.
        val previous = loadInfo()
        if (previous?.hash == hash && previous.importerVersion >= GtfsImporter.VERSION) { _status.value = ScheduleStatus.UpToDate; return@withContext false }
        val name = "schedule-${UUID.randomUUID()}.db"
        val target = File(dir, name)
        try {
            val db = open(target)
            try {
                val sql = db.openHelper.writableDatabase
                importer.import(zip, sql, meta + mapOf("hash" to hash, "importedAt" to System.currentTimeMillis().toString(), "importerVersion" to GtfsImporter.VERSION.toString())) {
                    _status.value = ScheduleStatus.Importing(it.file, it.rows)
                }
            } finally {
                db.close()
            }
            activate(name)
            loadInfo()
            _status.value = ScheduleStatus.Idle
            _version.value++
            true
        } catch (e: Throwable) {
            withContext(NonCancellable) { deleteDatabase(target) }
            throw e
        }
    }

    private fun activate(name: String) {
        val tmp = File(dir, "active.tmp")
        tmp.writeText(name)
        if (!tmp.renameTo(pointer)) {
            pointer.delete()
            if (!tmp.renameTo(pointer)) throw TransferFailedException("Could not activate schedule")
        }
        val keep = synchronized(openLock) {
            activeCache = name
            activeLoaded = true
            current?.let(::retire)
            current = null
            retired.map { it.name } + name
        }
        // Databases still in use are deleted when their last user finishes (see [release]).
        dir.listFiles()?.filter { f -> f.name.startsWith("schedule-") && keep.none { f.name.startsWith(it) } }?.forEach { it.delete() }
    }

    private fun deleteDatabase(file: File) {
        listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
    }

    /** Downloads [source] into [target]. Returns null when the server reports no change. */
    private suspend fun fetch(source: GtfsSource, previous: ScheduleInfo?, target: File): Map<String, String>? {
        val maxBytes = 400L * 1024 * 1024
        if (source.url.startsWith("ftp:")) {
            // FTP data connections can drop mid-transfer; resume with REST up to a few times.
            target.delete()
            var attempts = 0
            var modified = ""
            while (true) {
                val ftp = FtpDownload(source.url)
                var size = -1L
                try {
                    ftp.open(target.length()).use { input ->
                        size = ftp.size
                        modified = ftp.lastModified
                        copy(input, target, size, maxBytes, append = true)
                    }
                } catch (e: IOException) {
                    if (++attempts >= 5) throw e
                    continue
                } finally {
                    ftp.close()
                }
                if (size <= 0 || target.length() >= size) break
                if (++attempts >= 5) throw TransferFailedException("Incomplete FTP download")
            }
            return mapOf("lastModified" to modified)
        }
        val headers = buildMap {
            // A conditional request would answer "not modified" and keep a feed the current importer
            // has never processed; ask unconditionally after an importer change.
            if (previous?.source == source.name && previous.importerVersion >= GtfsImporter.VERSION) {
                val (etag, modified) = withDatabase { db -> db.schedule().let { it.meta("etag") to it.meta("lastModified") } }
                etag?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
                modified?.takeIf { it.isNotBlank() }?.let { put("If-Modified-Since", it) }
            }
        }
        downloads.response(source.url, "application/zip, application/octet-stream", headers).use { response ->
            if (response.code == 304) return null
            val body = response.body
            body.byteStream().use { input -> copy(input, target, body.contentLength(), maxBytes) }
            return mapOf("etag" to response.header("ETag").orEmpty(), "lastModified" to response.header("Last-Modified").orEmpty())
        }
    }

    private suspend fun copy(input: java.io.InputStream, target: File, total: Long, maxBytes: Long, append: Boolean = false) {
        if (total > maxBytes) throw BadDataException("Download too large")
        var written = if (append) target.length() else 0L
        java.io.FileOutputStream(target, append).buffered(1 shl 16).use { out ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > maxBytes) throw BadDataException("Download too large")
                out.write(buffer, 0, n)
                _status.value = ScheduleStatus.Downloading(written, total)
            }
        }
    }
}
