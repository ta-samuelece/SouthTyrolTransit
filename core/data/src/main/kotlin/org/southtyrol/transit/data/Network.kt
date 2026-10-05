package org.southtyrol.transit.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.southtyrol.transit.model.DataError
import org.southtyrol.transit.model.DataException
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpFailure(val status: Int, val retryAfterSeconds: Long? = null) : IOException("HTTP $status")

/**
 * Thin coroutine wrapper around OkHttp with bounded retries and exponential backoff for transient
 * failures. Honors Retry-After up to [maxRetryAfterSeconds]; longer back-offs surface as throttling.
 */
class TransitHttp(
    private val client: OkHttpClient,
    private val maxAttempts: Int = 3,
    private val baseBackoffMillis: Long = 800,
    private val maxRetryAfterSeconds: Long = 20,
) {
    suspend fun response(url: String, accept: String, headers: Map<String, String> = emptyMap()): Response {
        val request = Request.Builder().url(url)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        var lastError: IOException? = null
        for (attempt in 0 until maxAttempts) {
            val result = try {
                await(client.newCall(request))
            } catch (e: IOException) {
                lastError = e
                if (e is UnknownHostException || attempt == maxAttempts - 1) throw e
                delay(baseBackoffMillis shl attempt)
                continue
            }
            if (result.isSuccessful || result.code == 304) return result
            val code = result.code
            val retryAfter = parseRetryAfter(result.header("Retry-After"))
            result.close()
            val transient = code == 429 || code in 500..599
            if (!transient || attempt == maxAttempts - 1 || (retryAfter ?: 0) > maxRetryAfterSeconds) throw HttpFailure(code, retryAfter)
            delay(retryAfter?.times(1000)?.coerceAtLeast(baseBackoffMillis) ?: (baseBackoffMillis shl attempt))
        }
        throw lastError ?: IOException("Unavailable")
    }

    suspend fun bytes(url: String, accept: String, maxBytes: Long = 30L * 1024 * 1024): ByteArray =
        response(url, accept).use { response ->
            withContext(Dispatchers.IO) {
                val body = response.body
                val declared = body.contentLength()
                if (declared > maxBytes) throw IOException("Response too large")
                // Bounded read (InputStream.readNBytes needs API 33).
                val out = java.io.ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 64 * 1024)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > maxBytes) throw IOException("Response too large")
                    }
                }
                out.toByteArray()
            }
        }

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) { _, value, _ -> value.close() } else response.close()
            }
        })
    }

    companion object {
        const val USER_AGENT = "SouthTyrolTransit/0.1 (independent open-data client; Android)"

        fun parseRetryAfter(value: String?, now: Instant = Instant.now()): Long? {
            value ?: return null
            value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return runCatching { Duration.between(now, ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds.coerceAtLeast(0) }.getOrNull()
        }

        fun client(cacheDir: File?): OkHttpClient = OkHttpClient.Builder()
            .apply { if (cacheDir != null) cache(Cache(cacheDir, 20L * 1024 * 1024)) }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

fun endpoint(base: String, path: String, params: Map<String, String>): String =
    (base + path).toHttpUrl().newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build().toString()

/** Maps any failure to a UI-describable [DataError]. Cancellation is always rethrown. */
fun Throwable.toDataError(): DataError = when (this) {
    is CancellationException -> throw this
    is DataException -> error
    is HttpFailure -> if (status == 429) DataError.Throttled(retryAfterSeconds) else if (status == 404) DataError.NotFound else DataError.Server(status)
    is EfaFailure -> DataError.Planner(code)
    is UnknownHostException -> DataError.Offline
    is SocketTimeoutException, is InterruptedIOException -> DataError.Timeout
    is java.net.ConnectException, is java.net.NoRouteToHostException -> DataError.Offline
    is IOException -> DataError.Offline
    is IllegalArgumentException, is IllegalStateException, is org.xml.sax.SAXException, is com.google.protobuf.InvalidProtocolBufferException,
    is kotlinx.serialization.SerializationException -> DataError.Parse
    else -> DataError.Unknown
}

/** Runs [block], converting failures into [DataException]. */
suspend fun <T> guarded(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: DataException) {
    throw e
} catch (e: Exception) {
    throw DataException(e.toDataError(), e)
}
