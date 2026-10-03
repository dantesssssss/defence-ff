package com.da4a.smartcity.beacon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    val BLUETOOTH: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Before Android 12, scanning needs location instead.
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val LOCATION = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    val WIFI: List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()

    private val NOTIFICATIONS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    val ALL: List<String> = (BLUETOOTH + LOCATION + WIFI + NOTIFICATIONS).distinct()

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun locationGranted(context: Context) = LOCATION.any { granted(context, it) }
}
