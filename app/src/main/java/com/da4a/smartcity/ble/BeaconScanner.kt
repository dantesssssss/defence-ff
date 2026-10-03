package com.da4a.smartcity.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class Peer(
    val id: Long,
    val rssi: Int,
    val payload: BeaconPayload?,
    val lastSeenMs: Long,
    /** Advertisements received per second and the longest silence, over the last few seconds. */
    val ratePerS: Float = 0f,
    val maxGapMs: Long = 0,
)

@SuppressLint("MissingPermission")
class BeaconScanner(private val adapter: BluetoothAdapter?) {

    val peers = mutableStateMapOf<Long, Peer>()

    /** Called for every single advertisement received, before any smoothing. */
    var onSighting: ((Peer) -> Unit)? = null

    /** Called for advertisements of [id] that accept connections. */
    var onConnectable: ((android.bluetooth.BluetoothDevice, BeaconPayload) -> Unit)? = null

    var state by mutableStateOf("off")
        private set

    private val arrivals = HashMap<Long, ArrayDeque<Long>>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onScanFailed(errorCode: Int) {
            state = "failed ($errorCode)"
        }
    }

    // Android allows one scan per callback object, so the long-range scan needs its own.
    private val codedCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
    }

    private fun handle(result: ScanResult) {
        // 127 is the controller's "RSSI not available" marker, not a measurement.
        if (result.rssi == RSSI_UNAVAILABLE) return
        val data = result.scanRecord?.getManufacturerSpecificData(BleConstants.COMPANY_ID)
        val payload = BeaconPayload.decode(data?.drop(BleConstants.MAGIC.size)?.toByteArray())
        val id = payload?.deviceId ?: result.device.address.hashCode().toLong()
        // Keep the last payload if this result arrived without the scan response.
        val now = SystemClock.elapsedRealtime()
        val times = arrivals.getOrPut(id) { ArrayDeque() }
        times.addLast(now)
        while (now - times.first() > STATS_WINDOW_MS) times.removeFirst()
        val peer = Peer(
            id, result.rssi, payload ?: peers[id]?.payload, now,
            ratePerS = times.size * 1000f / STATS_WINDOW_MS,
            maxGapMs = times.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0,
        )
        peers[id] = peer
        onSighting?.invoke(peer)
        if (payload != null && result.isConnectable) onConnectable?.invoke(result.device, payload)
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            state = "not available"
            return
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(BleConstants.COMPANY_ID, BleConstants.MAGIC)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
        try {
            scanner.startScan(listOf(filter), settings.build(), callback)
            if (adapter.isLeCodedPhySupported) {
                // A separate scan for the long-range sets. Asking one scan for all PHYs made the
                // Pixel 10a stop reporting legacy packets as well.
                settings.setLegacy(false).setPhy(BluetoothDevice.PHY_LE_CODED)
                scanner.startScan(listOf(filter), settings.build(), codedCallback)
            }
            state = "active"
        } catch (e: Exception) {
            state = "failed (${e.message})"
        }
    }

    private companion object {
        const val STATS_WINDOW_MS = 5000L
        const val RSSI_UNAVAILABLE = 127
    }

    fun stop() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
            adapter?.bluetoothLeScanner?.stopScan(codedCallback)
        } catch (_: Exception) {
        }
        state = "off"
    }
}
