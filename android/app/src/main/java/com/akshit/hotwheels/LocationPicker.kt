package com.akshit.hotwheels

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import java.util.Locale

/**
 * Turns "where am I" into the lat/lon pair Blinkit wants.
 *
 * Uses the framework LocationManager and Geocoder rather than Play Services,
 * so the app keeps its zero-dependency build. Accuracy matters here: Blinkit
 * stock is per dark store, so a coarse fix a few kilometres out can point at
 * the wrong warehouse entirely.
 */
object LocationPicker {

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val permissions = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    /**
     * Ask the OS for a fresh fix. [onResult] gets null if location is off or
     * nothing could be obtained.
     */
    fun current(context: Context, onResult: (Location?) -> Unit) {
        if (!hasPermission(context)) { onResult(null); return }
        val lm = context.getSystemService(LocationManager::class.java)

        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        } ?: run { onResult(null); return }

        try {
            lm.getCurrentLocation(
                provider,
                CancellationSignal(),
                context.mainExecutor,
            ) { location ->
                // A cold GPS fix can come back null indoors; fall back to
                // whatever the system already had.
                onResult(location ?: lastKnown(lm))
            }
        } catch (e: SecurityException) {
            onResult(null)
        }
    }

    private fun lastKnown(lm: LocationManager): Location? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }

    /** Text search: "sushant lok phase 1 gurugram" -> candidate places. */
    @Suppress("DEPRECATION") // sync overload is fine off the main thread
    fun search(context: Context, query: String, max: Int = 5): List<Address> {
        if (!Geocoder.isPresent() || query.isBlank()) return emptyList()
        return runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocationName(query, max).orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Coordinates -> a human-readable line, so a saved location is checkable. */
    @Suppress("DEPRECATION")
    fun describe(context: Context, lat: Double, lon: Double): String {
        if (!Geocoder.isPresent()) return format(lat, lon)
        val address = runCatching {
            Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
        }.getOrNull() ?: return format(lat, lon)
        return label(address)
    }

    /** Prefer the specific parts — a locality alone is too vague for a dark store. */
    fun label(a: Address): String {
        val parts = listOfNotNull(
            a.featureName?.takeIf { it.isNotBlank() && it != a.thoroughfare },
            a.thoroughfare,
            a.subLocality,
            a.locality,
            a.postalCode,
        ).distinct()
        return parts.joinToString(", ").ifBlank {
            a.getAddressLine(0) ?: format(a.latitude, a.longitude)
        }
    }

    fun format(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.4f, %.4f", lat, lon)
}
