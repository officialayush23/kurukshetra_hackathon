package com.bitchat.android.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This phone's last known position, for places that need "where am I" without a UI:
 * tagging VLM briefs and ungeotagged reports before they go to the command centre, and
 * precomputing routes to the nearest centres while there is still signal.
 */
object LastFix {
    @Volatile var cached: Pair<Double, Double>? = null
        private set

    fun remember(lat: Double, lon: Double) {
        cached = lat to lon
    }

    suspend fun get(context: Context): Pair<Double, Double>? {
        val ctx = context.applicationContext
        val allowed = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!allowed) return cached
        val result = CompletableDeferred<Pair<Double, Double>?>()
        try {
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation
                .addOnSuccessListener { loc -> result.complete(loc?.let { it.latitude to it.longitude }) }
                .addOnFailureListener { result.complete(null) }
        } catch (_: SecurityException) {
            result.complete(null)
        } catch (_: Exception) {
            result.complete(null)
        }
        val fix = withTimeoutOrNull(4_000) { result.await() }
        if (fix != null) cached = fix
        return fix ?: cached
    }
}
