package com.da4a.smartcity.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Best last fix from the platform location providers (no Play Services). */
@SuppressLint("MissingPermission")
class LocationSource(context: Context) : LocationListener {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    var fix by mutableStateOf<Location?>(null)
        private set

    fun start() {
        val providers = buildList {
            add(LocationManager.GPS_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        }.filter { it in locationManager.allProviders }
        for (provider in providers) {
            try {
                locationManager.getLastKnownLocation(provider)?.let(::onLocationChanged)
                locationManager.requestLocationUpdates(provider, 1000L, 0f, this, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        locationManager.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        val current = fix
        // Never drop a good fix for a worse one unless the good one has gone stale.
        if (current == null ||
            location.accuracy <= current.accuracy ||
            ageSeconds(current) - ageSeconds(location) > STALE_S
        ) {
            fix = location
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        private const val STALE_S = 10

        fun ageSeconds(location: Location): Long =
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000_000L
    }
}
