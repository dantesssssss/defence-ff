package com.da4a.smartcity.beacon

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.da4a.smartcity.ble.BeaconAdvertiser
import com.da4a.smartcity.ble.BeaconLink
import com.da4a.smartcity.ble.BeaconPayload
import com.da4a.smartcity.ble.BeaconScanner
import com.da4a.smartcity.estimation.ProximityTracker
import com.da4a.smartcity.sensors.BatterySource
import com.da4a.smartcity.sensors.LocationSource
import com.da4a.smartcity.sensors.PressureSource
import com.da4a.smartcity.wifi.AwareRanger
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Everything one phone runs to be found and to find others: sensors, Bluetooth advertising,
 * scanning and the signal-strength link, Wi-Fi ranging, and the closeness estimate. Public
 * state is Compose state, so screens read it directly.
 */
class BeaconEngine(private val context: Context) {

    private val deviceId = Random.nextInt().toLong() and 0xFFFFFFFFL
    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    val pressure = PressureSource(context)
    private val location = LocationSource(context)
    private val ranger = AwareRanger(context, deviceId)
    val proximity = ProximityTracker()
    private val advertiser = BeaconAdvertiser(adapter)
    val scanner = BeaconScanner(adapter)
    private val link = BeaconLink(context, adapter, deviceId)

    /** True once Bluetooth advertising and scanning are up. */
    var running by mutableStateOf(false)
        private set

    private var emergencyState by mutableStateOf(false)

    /** Advertised to other phones; rescuers only look for phones with this set. */
    var emergency: Boolean
        get() = emergencyState
        set(value) {
            emergencyState = value
            // Tell rescuers right away instead of at the next refresh.
            if (running) advertiser.update(buildPayload())
        }

    private var seq = 0

    // Latest Wi-Fi distance measured by this phone, broadcast so the other phone can use it
    // even when its own ranging attempts fail.
    private var rangedPeerId = 0L
    private var rangedDistanceM = 0f
    private var rangedTimeMs = 0L

    private val refreshPayload = object : Runnable {
        override fun run() {
            advertiser.update(buildPayload())
            logStatus()
            handler.postDelayed(this, PAYLOAD_REFRESH_MS)
        }
    }

    init {
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
    }

    /** Starts whatever the granted permissions and the adapter allow; safe to call again. */
    fun start() {
        pressure.start()
        if (Permissions.granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
            location.stop()
            location.start()
            // Wi-Fi ranging is optional: Bluetooth proximity keeps working without it.
            if (Permissions.WIFI.all { Permissions.granted(context, it) }) ranger.start()
        }
        if (running || !Permissions.BLUETOOTH.all { Permissions.granted(context, it) } || adapter?.isEnabled != true) return
        running = true
        advertiser.start(buildPayload())
        scanner.start()
        link.start()
        handler.postDelayed(refreshPayload, PAYLOAD_REFRESH_MS)
    }

    fun stop() {
        handler.removeCallbacks(refreshPayload)
        if (running) {
            advertiser.stop()
            scanner.stop()
            link.stop()
            running = false
        }
        location.stop()
        pressure.stop()
        ranger.stop()
    }

    private fun buildPayload(): BeaconPayload {
        val fix = location.fix
        val rangeFresh = rangedTimeMs != 0L && SystemClock.elapsedRealtime() - rangedTimeMs < RANGE_SHARE_MS
        return BeaconPayload(
            deviceId = deviceId,
            batteryPct = BatterySource.percent(context),
            pressurePa = pressure.pressurePa?.roundToInt(),
            lat = fix?.latitude,
            lon = fix?.longitude,
            fixAgeMin = fix?.let { (LocationSource.ageSeconds(it) / 60).toInt() } ?: 0,
            seq = seq++,
            rangedPeerId = rangedPeerId.takeIf { rangeFresh },
            rangedDistanceCm = (rangedDistanceM * 100).roundToInt().takeIf { rangeFresh },
            cannotMeasureRssi = !link.canMeasure,
            cannotRange = ranger.cannotRange,
            emergency = emergency,
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

    private companion object {
        const val PAYLOAD_REFRESH_MS = 1000L
        const val TAG = "RescueLog"
        const val RANGE_SHARE_MS = 3000L
        const val RANGING_MIN_RSSI_DBM = -72f
    }
}
