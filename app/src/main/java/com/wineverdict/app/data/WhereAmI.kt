package com.wineverdict.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One fix, when the user asks for one.
 *
 * No fused-location dependency and no background watching: the app needs a position at the moment a
 * night's Where box is tapped, not a trail of them. Coarse location is not asked for on its own
 * because a circle of forty-five metres drawn from a cell-tower fix would claim half a town — fine
 * location is the permission, and a refusal simply leaves the box to be typed in.
 */
object WhereAmI {

    fun allowed(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The phone's best answer about where it is *now*, or null when it has none in [timeoutMs].
     *
     * A fix from the last ten minutes is used as it stands: a night is written in the room it
     * happened in, and asking the GPS again in that room costs seconds to say the same thing. Older
     * than that is not a starting point but a wrong answer — an emulator handed this code a fix from
     * two days and two hundred miles away, and tagging a night with whatever place used to contain
     * that pin is worse than saying nothing at all.
     */
    suspend fun fix(context: Context, timeoutMs: Long = 6_000): LatLng? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) {
            return LatLng(last.latitude, last.longitude)
        }
        val provider = when {
            enabled(manager, LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            enabled(manager, LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        // Nothing older stands in for a fix that did not arrive: [last] may be days old by now.
        val fresh = withTimeoutOrNull(timeoutMs) { firstFix(manager, provider) }
        return fresh?.let { LatLng(it.latitude, it.longitude) }
    }

    private fun enabled(manager: LocationManager, provider: String): Boolean =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    private suspend fun firstFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(location)
                }

                // Deprecated on newer platforms but still called on the oldest ones the app runs on.
                @Deprecated("Required by LocationListener before API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) {
                    runCatching { manager.removeUpdates(this) }
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
            continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
        }
}
