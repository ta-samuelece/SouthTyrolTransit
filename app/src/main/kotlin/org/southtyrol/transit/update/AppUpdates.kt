package org.southtyrol.transit.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.southtyrol.transit.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** A published release that carries an installable APK. */
data class AppRelease(val version: String, val notes: String, val pageUrl: String, val apkUrl: String, val apkSize: Long)

enum class UpdateError { NETWORK, NO_APK, DOWNLOAD, WRONG_PACKAGE, NOT_NEWER, SIGNATURE }

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Instant) : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val bytes: Long, val total: Long) : UpdateState
    data class Ready(val release: AppRelease, val file: File) : UpdateState
    data class Failed(val error: UpdateError, val release: AppRelease?) : UpdateState
}

/** Release versions like "v1.2.0" or "1.10.0-beta.1"; numeric parts compare numerically, a suffix ranks below the plain version. */
object Versions {
    fun clean(tag: String): String = tag.trim().removePrefix("v").removePrefix("V")

    fun compare(a: String, b: String): Int {
        val (coreA, suffixA) = clean(a).split('-', '+', limit = 2).let { it[0] to it.getOrNull(1) }
        val (coreB, suffixB) = clean(b).split('-', '+', limit = 2).let { it[0] to it.getOrNull(1) }
        val partsA = coreA.split('.').map { it.toIntOrNull() ?: 0 }
        val partsB = coreB.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val c = (partsA.getOrElse(i) { 0 }).compareTo(partsB.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            suffixA == suffixB -> 0
            suffixA == null -> 1
            suffixB == null -> -1
            else -> suffixA.compareTo(suffixB)
        }
    }

    fun isNewer(candidate: String, current: String) = compare(candidate, current) > 0
}

/** Parses GitHub's "latest release" response. Drafts, pre-releases and releases without an APK are ignored. */
object ReleaseParser {
    @Serializable
    private data class Asset(val name: String, @SerialName("browser_download_url") val url: String, val size: Long = 0)

    @Serializable
    private data class Release(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        @SerialName("html_url") val pageUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when the release cannot be offered; [hasApk] distinguishes "no APK attached". */
    fun parse(body: String): Pair<AppRelease?, Boolean> {
        val r = json.decodeFromString<Release>(body)
        if (r.draft || r.prerelease) return null to true
        val apks = r.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        // Prefer the release build; never offer a debug build if a proper one exists.
        val apk = apks.firstOrNull { "release" in it.name.lowercase() } ?: apks.firstOrNull { "debug" !in it.name.lowercase() } ?: apks.firstOrNull()
            ?: return null to false
        return AppRelease(Versions.clean(r.tag), r.body.orEmpty().trim(), r.pageUrl, apk.url, apk.size) to true
    }
}

/**
 * In-app updates from GitHub Releases: checks the latest release, downloads its APK, verifies that it is
 * a newer build of this app signed with the same key, and hands it to the system installer. Android always
 * asks the user to confirm; nothing is installed silently.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    client: OkHttpClient,
) {
    private val repo = BuildConfig.UPDATE_REPO.trim()
    val enabled: Boolean = repo.isNotBlank() && repo != "none" && '/' in repo
    val currentVersion: String = BuildConfig.VERSION_NAME

    // Big downloads must not go through (or evict) the HTTP response cache.
    private val http = client.newBuilder().cache(null).readTimeout(Duration.ofSeconds(60)).build()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var lastCheck: Instant = Instant.EPOCH
    // Downloads outlive any single screen (e.g. leaving Settings mid-download).
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var downloadJob: kotlinx.coroutines.Job? = null

    fun startDownload(release: AppRelease) {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch { download(release) }
    }

    fun cancelDownload(release: AppRelease) {
        downloadJob?.cancel()
        _state.value = UpdateState.Available(release)
    }

    /** Set once the user dismissed the prompt; it is not shown again until the next app start. */
    var dismissed = false

    suspend fun check(force: Boolean = false) {
        if (!enabled) return
        val busy = _state.value is UpdateState.Checking || _state.value is UpdateState.Downloading || _state.value is UpdateState.Ready
        if (busy || (!force && Duration.between(lastCheck, Instant.now()) < Duration.ofHours(1))) return
        _state.value = UpdateState.Checking
        _state.value = try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val result = withContext(Dispatchers.IO) {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 404 -> null to true // no release published yet
                        !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                        else -> ReleaseParser.parse(response.body.string())
                    }
                }
            }
            lastCheck = Instant.now()
            val (release, hasApk) = result
            when {
                release != null && Versions.isNewer(release.version, currentVersion) -> UpdateState.Available(release)
                !hasApk -> UpdateState.Failed(UpdateError.NO_APK, null)
                else -> UpdateState.UpToDate(lastCheck)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            UpdateState.Failed(UpdateError.NETWORK, null)
        }
    }

    private suspend fun download(release: AppRelease) {
        _state.value = UpdateState.Downloading(release, 0, release.apkSize)
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "SouthTyrolTransit-${release.version}.apk")
        _state.value = try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val total = response.body.contentLength().takeIf { it > 0 } ?: release.apkSize
                    response.body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            var reported = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                bytes += n
                                if (bytes - reported > 256 * 1024) {
                                    reported = bytes
                                    _state.value = UpdateState.Downloading(release, bytes, total)
                                }
                            }
                        }
                    }
                }
            }
            verify(file)?.let { UpdateState.Failed(it, release).also { file.delete() } } ?: UpdateState.Ready(release, file)
        } catch (e: CancellationException) {
            file.delete()
            _state.value = UpdateState.Available(release)
            throw e
        } catch (_: Exception) {
            file.delete()
            UpdateState.Failed(UpdateError.DOWNLOAD, release)
        }
    }

    /** Checks package name, version code and signing certificate before Android's installer sees the file. */
    private fun verify(file: File): UpdateError? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return UpdateError.WRONG_PACKAGE
        if (archive.packageName != context.packageName) return UpdateError.WRONG_PACKAGE
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (versionCode(archive) <= versionCode(installed)) return UpdateError.NOT_NEWER
        val newCerts = certificates(archive)
        val oldCerts = certificates(installed)
        // Only block when both sides are readable and clearly differ; otherwise Android's installer decides.
        if (newCerts.isNotEmpty() && oldCerts.isNotEmpty() && newCerts.intersect(oldCerts).isEmpty()) return UpdateError.SIGNATURE
        return null
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.orEmpty().map { s -> digest.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    /** False until the user allows this app to install unknown apps (Android asks once). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
