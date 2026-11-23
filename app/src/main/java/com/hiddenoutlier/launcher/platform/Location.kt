package com.hiddenoutlier.launcher.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Approximate location for the weather only (ACCESS_COARSE_LOCATION, asked for when first needed). Coordinates
 * are rounded to 2 decimals (about 1 km) before they go anywhere. No Google Play services: Android's own
 * LocationManager.
 */
object Location {
    fun granted(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Rounded (lat, lon), or null if unavailable within a few seconds. */
    suspend fun approximate(context: Context): Pair<Double, Double>? {
        if (!granted(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER); add(LocationManager.PASSIVE_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = if (Build.VERSION.SDK_INT >= 30) providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }?.let { p ->
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<android.location.Location?> { cont ->
                    try {
                        lm.getCurrentLocation(p, null, context.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                    } catch (e: SecurityException) { cont.resume(null) }
                }
            }
        } else null
        val loc = fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return null
        fun r(v: Double) = (v * 100).roundToInt() / 100.0
        return r(loc.latitude) to r(loc.longitude)
    }
}
