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
)

sealed class ScheduleStatus {
    data object Idle : ScheduleStatus()
    data class Downloading(val bytes: Long, val total: Long) : ScheduleStatus()
    data class Importing(val file: String, val rows: Long) : ScheduleStatus()
    data class Failed(val error: DataError) : ScheduleStatus()
    data object UpToDate : ScheduleStatus()
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
    private val pointer = File(dir, "active")
    private val lock = Mutex()
    private val openLock = Any()
    private var current: ScheduleDatabase? = null
    private var currentName: String? = null

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

    /** Returns the active database or null when no schedule has been imported yet. */
    fun database(): ScheduleDatabase? = synchronized(openLock) {
        val name = activeName() ?: return null
        if (current != null && currentName == name) return current
        current?.close()
        current = open(File(dir, name))
        currentName = name
        current
    }

    suspend fun loadInfo(): ScheduleInfo? = withContext(Dispatchers.IO) {
        val dao = database()?.schedule() ?: return@withContext null
        val info = runCatching {
            ScheduleInfo(
                importedAt = Instant.ofEpochMilli(dao.meta("importedAt")?.toLongOrNull() ?: 0),
                source = dao.meta("source").orEmpty(),
                hash = dao.meta("hash").orEmpty(),
                firstDate = dao.meta("firstDate")?.toIntOrNull() ?: 0,
                lastDate = dao.meta("lastDate")?.toIntOrNull() ?: 0,
                stops = dao.meta("stops")?.toIntOrNull() ?: 0,
                trips = dao.meta("trips")?.toIntOrNull() ?: 0,
                hasShapes = dao.meta("hasShapes") == "true",
            )
        }.getOrNull()
        _info.value = info
        info
    }

    private fun open(file: File): ScheduleDatabase =
        Room.databaseBuilder(context, ScheduleDatabase::class.java, file.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()

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
            throw firstError ?: IOException("No schedule source available")
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
        if (loadInfo()?.hash == hash) { _status.value = ScheduleStatus.UpToDate; return@withContext false }
        val name = "schedule-${UUID.randomUUID()}.db"
        val target = File(dir, name)
        try {
            val db = open(target)
            try {
                val sql = db.openHelper.writableDatabase
                importer.import(zip, sql, meta + mapOf("hash" to hash, "importedAt" to System.currentTimeMillis().toString())) {
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
            if (!tmp.renameTo(pointer)) throw IOException("Could not activate schedule")
        }
        synchronized(openLock) {
            activeCache = name
            activeLoaded = true
            current?.close()
            current = null
            currentName = null
        }
        dir.listFiles()?.filter { it.name.startsWith("schedule-") && !it.name.startsWith(name) }?.forEach { it.delete() }
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
                if (++attempts >= 5) throw IOException("Incomplete FTP download")
            }
            return mapOf("lastModified" to modified)
        }
        val dao = database()?.schedule()
        val headers = buildMap {
            if (previous?.source == source.name) {
                dao?.meta("etag")?.takeIf { it.isNotBlank() }?.let { put("If-None-Match", it) }
                dao?.meta("lastModified")?.takeIf { it.isNotBlank() }?.let { put("If-Modified-Since", it) }
            }
        }
        http.response(source.url, "application/zip, application/octet-stream", headers).use { response ->
            if (response.code == 304) return null
            val body = response.body
            body.byteStream().use { input -> copy(input, target, body.contentLength(), maxBytes) }
            return mapOf("etag" to response.header("ETag").orEmpty(), "lastModified" to response.header("Last-Modified").orEmpty())
        }
    }

    private suspend fun copy(input: java.io.InputStream, target: File, total: Long, maxBytes: Long, append: Boolean = false) {
        if (total > maxBytes) throw IOException("Download too large")
        var written = if (append) target.length() else 0L
        java.io.FileOutputStream(target, append).buffered(1 shl 16).use { out ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > maxBytes) throw IOException("Download too large")
                out.write(buffer, 0, n)
                _status.value = ScheduleStatus.Downloading(written, total)
            }
        }
    }
}
