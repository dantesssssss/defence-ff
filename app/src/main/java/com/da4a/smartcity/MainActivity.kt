package com.da4a.smartcity

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.da4a.smartcity.ble.BeaconAdvertiser
import com.da4a.smartcity.ble.BeaconPayload
import com.da4a.smartcity.ble.BeaconScanner
import com.da4a.smartcity.sensors.BatterySource
import com.da4a.smartcity.sensors.LocationSource
import com.da4a.smartcity.sensors.PressureSource
import com.da4a.smartcity.ui.BeaconScreen
import com.da4a.smartcity.ui.theme.SmartCityTheme
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val deviceId = Random.nextInt().toLong() and 0xFFFFFFFFL
    private val handler = Handler(Looper.getMainLooper())

    private var adapter: BluetoothAdapter? = null
    private lateinit var pressure: PressureSource
    private lateinit var location: LocationSource
    private lateinit var advertiser: BeaconAdvertiser
    private lateinit var scanner: BeaconScanner

    private var bluetoothOn by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var batteryPct by mutableIntStateOf(0)
    private var seq = 0

    private val bluetoothPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            // Before Android 12, scanning needs location instead.
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startBeacon() }

    private val refreshPayload = object : Runnable {
        override fun run() {
            advertiser.update(buildPayload())
            handler.postDelayed(this, PAYLOAD_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        pressure = PressureSource(this)
        location = LocationSource(this)
        advertiser = BeaconAdvertiser(adapter)
        scanner = BeaconScanner(adapter)

        pressure.start()
        batteryPct = BatterySource.percent(this)
        requestPermissions()

        setContent {
            SmartCityTheme(darkTheme = true, dynamicColor = false) {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BeaconScreen(
                        deviceId = deviceId,
                        bluetoothOn = bluetoothOn,
                        advertiserState = advertiser.state,
                        scannerState = scanner.state,
                        hasBarometer = pressure.available,
                        pressurePa = pressure.pressurePa,
                        fix = location.fix,
                        batteryPct = batteryPct,
                        peers = scanner.peers.values,
                        running = running,
                        onRetry = ::requestPermissions,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshPayload)
        if (running) {
            advertiser.stop()
            scanner.stop()
        }
        location.stop()
        pressure.stop()
        super.onDestroy()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            (bluetoothPermissions.toList() + LOCATION_PERMISSIONS).distinct().toTypedArray()
        )
    }

    private fun onPermissionsResult() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            location.stop()
            location.start()
        }
        if (!bluetoothPermissions.all(::granted)) return
        if (adapter?.isEnabled == true) {
            startBeacon()
        } else if (adapter != null) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun startBeacon() {
        bluetoothOn = adapter?.isEnabled == true
        if (running || !bluetoothOn) return
        running = true
        advertiser.start(buildPayload())
        scanner.start()
        handler.postDelayed(refreshPayload, PAYLOAD_REFRESH_MS)
    }

    private fun buildPayload(): BeaconPayload {
        batteryPct = BatterySource.percent(this)
        val fix = location.fix
        return BeaconPayload(
            deviceId = deviceId,
            batteryPct = batteryPct,
            pressurePa = pressure.pressurePa?.roundToInt(),
            lat = fix?.latitude,
            lon = fix?.longitude,
            fixAgeMin = fix?.let { (LocationSource.ageSeconds(it) / 60).toInt() } ?: 0,
            seq = seq++,
        )
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PAYLOAD_REFRESH_MS = 1000L
        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
