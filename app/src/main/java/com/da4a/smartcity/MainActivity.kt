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
import android.os.SystemClock
import android.util.Log
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
import com.da4a.smartcity.ble.BeaconLink
import com.da4a.smartcity.ble.BeaconPayload
import com.da4a.smartcity.ble.BeaconScanner
import com.da4a.smartcity.estimation.ProximityTracker
import com.da4a.smartcity.sensors.BatterySource
import com.da4a.smartcity.sensors.LocationSource
import com.da4a.smartcity.sensors.PressureSource
import com.da4a.smartcity.ui.BeaconScreen
import com.da4a.smartcity.ui.theme.SmartCityTheme
import com.da4a.smartcity.wifi.AwareRanger
import kotlin.math.roundToInt
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private val deviceId = Random.nextInt().toLong() and 0xFFFFFFFFL
    private val handler = Handler(Looper.getMainLooper())

    private var adapter: BluetoothAdapter? = null
    private lateinit var pressure: PressureSource
    private lateinit var location: LocationSource
    private lateinit var ranger: AwareRanger
    private val proximity = ProximityTracker()
    private lateinit var advertiser: BeaconAdvertiser
    private lateinit var scanner: BeaconScanner
    private lateinit var link: BeaconLink

    private var bluetoothOn by mutableStateOf(false)
    private var running by mutableStateOf(false)
    private var batteryPct by mutableIntStateOf(0)
    private var seq = 0
    private var rangingEnabled by mutableStateOf(true)

    // Latest Wi-Fi distance measured by this phone, broadcast so the other phone can use it
    // even when its own ranging attempts fail.
    private var rangedPeerId = 0L
    private var rangedDistanceM = 0f
    private var rangedTimeMs = 0L

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

    private val wifiPermissions =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            emptyList()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { startBeacon() }

    private val refreshPayload = object : Runnable {
        override fun run() {
            advertiser.update(buildPayload())
            logStatus()
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
        ranger = AwareRanger(this, deviceId)
        link = BeaconLink(this, adapter, deviceId)
        link.onRssi = proximity::onConnRssi
        scanner.onConnectable = { device, payload -> link.onConnectable(device, payload.deviceId, payload.cannotMeasureRssi) }
        ranger.peerInRange = { (proximity.recentRssi(it) ?: -127f) > RANGING_MIN_RSSI_DBM }
        ranger.peerCannotRange = { scanner.peers[it]?.payload?.cannotRange == true }
        scanner.onSighting = { peer ->
            proximity.onBle(peer.id, peer.rssi)
            val cm = peer.payload?.rangedDistanceCm
            if (cm != null && peer.payload.rangedPeerId == deviceId) {
                proximity.onRange(peer.id, cm / 100f, raw = false)
            }
        }
        ranger.onDistance = { id, meters ->
            proximity.onRange(id, meters)
            rangedPeerId = id
            rangedDistanceM = proximity.rangeTo(id) ?: meters
            rangedTimeMs = SystemClock.elapsedRealtime()
        }

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
                        rangingState = ranger.state,
                        linkState = link.state,
                        rangingEnabled = rangingEnabled,
                        onRangingEnabled = ::setRanging,
                        proximity = proximity.states,
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
            link.stop()
        }
        location.stop()
        pressure.stop()
        ranger.stop()
        super.onDestroy()
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            (bluetoothPermissions.toList() + LOCATION_PERMISSIONS + wifiPermissions).distinct().toTypedArray()
        )
    }

    private fun onPermissionsResult() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            location.stop()
            location.start()
        }
        // Wi-Fi ranging is optional: Bluetooth proximity keeps working without it.
        if (rangingEnabled) startRangingIfPermitted()
        if (!bluetoothPermissions.all(::granted)) return
        if (adapter?.isEnabled == true) {
            startBeacon()
        } else if (adapter != null) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    private fun startRangingIfPermitted() {
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && wifiPermissions.all(::granted)) ranger.start()
    }

    private fun setRanging(enabled: Boolean) {
        rangingEnabled = enabled
        if (enabled) startRangingIfPermitted() else ranger.stop()
    }

    private fun startBeacon() {
        bluetoothOn = adapter?.isEnabled == true
        if (running || !bluetoothOn) return
        running = true
        advertiser.start(buildPayload())
        scanner.start()
        link.start()
        handler.postDelayed(refreshPayload, PAYLOAD_REFRESH_MS)
    }

    private fun buildPayload(): BeaconPayload {
        batteryPct = BatterySource.percent(this)
        val fix = location.fix
        val rangeFresh = rangedTimeMs != 0L && SystemClock.elapsedRealtime() - rangedTimeMs < RANGE_SHARE_MS
        return BeaconPayload(
            deviceId = deviceId,
            batteryPct = batteryPct,
            pressurePa = pressure.pressurePa?.roundToInt(),
            lat = fix?.latitude,
            lon = fix?.longitude,
            fixAgeMin = fix?.let { (LocationSource.ageSeconds(it) / 60).toInt() } ?: 0,
            seq = seq++,
            rangedPeerId = rangedPeerId.takeIf { rangeFresh },
            rangedDistanceCm = (rangedDistanceM * 100).roundToInt().takeIf { rangeFresh },
            cannotMeasureRssi = !link.canMeasure,
            cannotRange = ranger.cannotRange,
        )
    }

    /** One line per second and peer, for analysing dropouts from logcat after a walk test. */
    private fun logStatus() {
        val now = SystemClock.elapsedRealtime()
        if (scanner.peers.isEmpty()) Log.i(TAG, "no peers | adv=${advertiser.state} scan=${scanner.state}")
        for (peer in scanner.peers.values) {
            val p = proximity.states[peer.id]
            Log.i(
                TAG,
                "peer=%08X est=%dms silent=%dms rate=%.1f/s maxGap=%dms rssi=%d smooth=%.1f dist=%.1f wifi=%s trend=%s | adv=%s scan=%s link=%s aware=%s"
                    .format(
                        peer.id, p?.let { now - it.updatedMs } ?: -1, now - peer.lastSeenMs, peer.ratePerS, peer.maxGapMs, peer.rssi,
                        p?.smoothedRssi ?: 0f, p?.distanceM ?: -1f, p?.usesWifi, p?.trend,
                        advertiser.state, scanner.state, link.state, ranger.state,
                    ),
            )
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val PAYLOAD_REFRESH_MS = 1000L
        const val TAG = "RescueLog"
        const val RANGE_SHARE_MS = 3000L
        const val RANGING_MIN_RSSI_DBM = -72f
        val LOCATION_PERMISSIONS = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
