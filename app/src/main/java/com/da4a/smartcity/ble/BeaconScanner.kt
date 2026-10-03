package com.da4a.smartcity.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
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
)

@SuppressLint("MissingPermission")
class BeaconScanner(private val adapter: BluetoothAdapter?) {

    val peers = mutableStateMapOf<Long, Peer>()

    var state by mutableStateOf("off")
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = BeaconPayload.decode(
                result.scanRecord?.getManufacturerSpecificData(BleConstants.COMPANY_ID)
            )
            val id = payload?.deviceId ?: result.device.address.hashCode().toLong()
            // Keep the last payload if this result arrived without the scan response.
            peers[id] = Peer(id, result.rssi, payload ?: peers[id]?.payload, SystemClock.elapsedRealtime())
        }

        override fun onScanFailed(errorCode: Int) {
            state = "failed ($errorCode)"
        }
    }

    fun start() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            state = "not available"
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            state = "active"
        } catch (e: Exception) {
            state = "failed (${e.message})"
        }
    }

    fun stop() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {
        }
        state = "off"
    }
}
