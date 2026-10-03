package com.da4a.smartcity.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Broadcasts the payload on several advertising sets at once. In a crowded 2.4 GHz
 * environment most single packets are lost to collisions, so the same payload is sent by
 * [legacySets] independent legacy sets (each every 100 ms, the platform minimum) and, when
 * [coded] is set and the phone supports it, by one long-range (LE Coded PHY) set that still
 * decodes when the signal is well below what the normal PHY needs. The long-range set is
 * connectable: it is what [BeaconLink] connects to.
 */
@SuppressLint("MissingPermission")
class BeaconAdvertiser(
    private val adapter: BluetoothAdapter?,
    private val legacySets: Int = 3,
    private val coded: Boolean = true,
) {

    var state by mutableStateOf("off")
        private set

    private inner class SetCallback(val label: String, val params: AdvertisingSetParameters) : AdvertisingSetCallback() {
        var set: AdvertisingSet? = null
        var failed: Int? = null
        var enabled = true

        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == ADVERTISE_SUCCESS) set = advertisingSet else failed = status
            report()
        }

        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            set = null
            report()
        }

        override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) {
            // A connectable set is switched off by the controller once someone connects.
            // update() keeps trying to switch it back on, so the phone becomes connectable
            // again as soon as the radio allows it.
            enabled = enable && status == ADVERTISE_SUCCESS
        }
    }

    private val callbacks = ArrayList<SetCallback>()

    fun start(payload: BeaconPayload) {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null || !adapter.isMultipleAdvertisementSupported) {
            state = "not supported"
            return
        }
        state = "starting"
        val data = advertiseData(payload)
        repeat(legacySets) {
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(false)
                .setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            startSet(advertiser, params, data, "1M")
        }
        if (coded && adapter.isLeCodedPhySupported && adapter.isLeExtendedAdvertisingSupported) {
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(true)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            startSet(advertiser, params, data, "coded")
        }
    }

    private fun startSet(
        advertiser: android.bluetooth.le.BluetoothLeAdvertiser,
        params: AdvertisingSetParameters,
        data: AdvertiseData,
        label: String,
    ) {
        val callback = SetCallback(label, params)
        callbacks += callback
        try {
            advertiser.startAdvertisingSet(params, data, null, null, null, callback)
        } catch (e: Exception) {
            callback.failed = -1
            report()
        }
    }

    /** Replaces the advertised payload without restarting advertising. */
    fun update(payload: BeaconPayload) {
        val data = advertiseData(payload)
        // A set can fail to start right after an app restart, while the previous process's
        // sets are still being released; keep retrying until it comes up.
        val advertiser = adapter?.bluetoothLeAdvertiser
        for (failed in callbacks.filter { it.failed != null }) {
            callbacks -= failed
            if (advertiser != null) startSet(advertiser, failed.params, data, failed.label)
        }
        for (callback in callbacks) {
            try {
                callback.set?.setAdvertisingData(data)
                if (!callback.enabled) callback.set?.enableAdvertising(true, 0, 0)
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        for (callback in callbacks) {
            try {
                advertiser?.stopAdvertisingSet(callback)
            } catch (_: Exception) {
            }
        }
        callbacks.clear()
        state = "off"
    }

    private fun report() {
        val active = callbacks.filter { it.set != null }
        val failed = callbacks.filter { it.failed != null }
        state = when {
            active.isEmpty() && failed.isEmpty() -> "starting"
            active.isEmpty() -> "failed (${failed.first().failed})"
            else -> "active (" + active.groupingBy { it.label }.eachCount()
                .entries.joinToString(" + ") { "${it.value}× ${it.key}" } +
                (if (failed.isEmpty()) "" else ", ${failed.size} failed") + ")"
        }
    }

    // Everything rides in the advertisement itself (30 of the 31 available bytes). A scan
    // response would need a request/response exchange per packet, and most of those were lost.
    private fun advertiseData(payload: BeaconPayload) = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .addManufacturerData(BleConstants.COMPANY_ID, BleConstants.MAGIC + payload.encode())
        .build()
}
