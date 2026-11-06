package cc.identified.sdk.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Called only after the user chooses location and the UI requests permission. */
internal suspend fun currentLocation(context: Context): Pair<Double, Double> =
    withContext(Dispatchers.Main.immediate) {
        val manager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: throw IllegalStateException(
                    "This device cannot share its location. Type your digital address instead."
                )
        val fine =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        check(fine || coarse) {
            "Location permission was denied. Type your digital address instead."
        }
        val providers =
            manager.getProviders(true).filter {
                (it == LocationManager.GPS_PROVIDER && fine) ||
                    it == LocationManager.NETWORK_PROVIDER
            }
        check(providers.isNotEmpty()) {
            "Location is unavailable. Turn on location services or type your digital address."
        }
        withTimeout(15_000) {
            suspendCancellableCoroutine { continuation ->
                val listener =
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (continuation.isActive) {
                                manager.removeUpdates(this)
                                continuation.resume(location.latitude to location.longitude)
                            }
                        }

                        @Deprecated("Platform callback")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: Bundle?,
                        ) = Unit

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) = Unit
                    }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                try {
                    providers.forEach {
                        manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper())
                    }
                } catch (error: Exception) {
                    manager.removeUpdates(listener)
                    if (continuation.isActive) continuation.cancel(error)
                }
            }
        }
    }
