package com.da4a.smartcity.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@SuppressLint("MissingPermission")
class BeaconAdvertiser(private val adapter: BluetoothAdapter?) {

    var state by mutableStateOf("off")
        private set

    private var set: AdvertisingSet? = null

    private val callback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) {
                set = advertisingSet
                state = "active"
            } else {
                state = "failed ($status)"
            }
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            set = null
            state = "off"
        }
    }

    fun start(payload: BeaconPayload) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported) {
            state = "not supported"
            return
        }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(false)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        state = "starting"
        try {
            advertiser.startAdvertisingSet(params, data, scanResponse(payload), null, null, callback)
        } catch (e: Exception) {
            state = "failed (${e.message})"
        }
    }

    /** Replaces the scan response without restarting advertising. */
    fun update(payload: BeaconPayload) {
        try {
            set?.setScanResponseData(scanResponse(payload))
        } catch (e: Exception) {
            state = "failed (${e.message})"
        }
    }

    fun stop() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(callback)
        } catch (_: Exception) {
        }
        set = null
        state = "off"
    }

    private fun scanResponse(payload: BeaconPayload) = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addManufacturerData(BleConstants.COMPANY_ID, payload.encode())
        .build()
}
