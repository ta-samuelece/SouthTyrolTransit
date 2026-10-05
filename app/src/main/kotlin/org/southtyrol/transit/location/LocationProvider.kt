package org.southtyrol.transit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.southtyrol.transit.model.Point
import kotlin.coroutines.resume

sealed class LocationResult {
    data class Found(val point: Point, val accuracyMeters: Float?) : LocationResult()
    data object PermissionDenied : LocationResult()
    data object Unavailable : LocationResult()
}

/** One-shot foreground location. Never tracks in the background and never logs positions. */
interface LocationProvider {
    fun hasPermission(): Boolean
    suspend fun current(): LocationResult
}

/** Framework LocationManager implementation (no Google Play services dependency). */
class AndroidLocationProvider(private val context: Context) : LocationProvider {
    private val manager = context.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override suspend fun current(): LocationResult {
        if (!hasPermission()) return LocationResult.PermissionDenied
        val manager = manager ?: return LocationResult.Unavailable
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
        val providers = (fused + listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val recent = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
            .minByOrNull { it.accuracy }
        if (recent != null) return recent.toResult()
        val provider = providers.firstOrNull() ?: return LocationResult.Unavailable
        val fresh = withTimeoutOrNull(12_000) { request(manager, provider) }
        return fresh?.toResult()
            ?: providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.toResult()
            ?: LocationResult.Unavailable
    }

    @SuppressLint("MissingPermission")
    private suspend fun request(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location -> if (cont.isActive) cont.resume(location) }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    manager.removeUpdates(this)
                    if (cont.isActive) cont.resume(location)
                }
            }
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    private fun Location.toResult() = LocationResult.Found(Point(latitude, longitude), if (hasAccuracy()) accuracy else null)
}
