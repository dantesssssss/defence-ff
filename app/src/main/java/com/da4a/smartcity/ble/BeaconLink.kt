package com.da4a.smartcity.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer

/**
 * A real Bluetooth connection between two phones, used only as a signal-strength probe.
 *
 * Advertisements are sent at low power on three crowded channels and are lost a few metres
 * away. A connection transmits at the radio's full power, hops over 37 channels, retransmits
 * lost packets and can run on the long-range coded PHY, so it keeps delivering RSSI readings
 * where advertisements no longer get through.
 *
 * Every phone runs the server side. For each pair, the phone with the higher ID connects,
 * reads the connection RSSI ten times a second and writes it to the other phone, so both
 * ends see the same reading. Some phones never get an answer to the RSSI read (seen on a
 * Pixel 7 running an Android beta); such a phone sets [canMeasure] to false, advertises that,
 * and the other phone takes over the measuring role.
 */
@SuppressLint("MissingPermission")
class BeaconLink(
    private val context: Context,
    private val adapter: BluetoothAdapter?,
    private val deviceId: Long,
) {

    var state by mutableStateOf("off")
        private set

    /** Peer device ID and the connection RSSI in dBm. */
    var onRssi: ((Long, Int) -> Unit)? = null

    var canMeasure by mutableStateOf(true)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private var serverClients = 0

    private var gatt: BluetoothGatt? = null
    private var gattPeerId = 0L
    private var characteristic: BluetoothGattCharacteristic? = null
    private var connected = false
    private var connectStartedMs = 0L
    private var phy = "1M"
    private var rssiReads = 0
    private var servicesReady = false
    private var servicesReadyMs = 0L
    private var rssiPending = false
    private var rssiRequestedMs = 0L

    private val poll = object : Runnable {
        override fun run() {
            val g = gatt ?: return
            val now = SystemClock.elapsedRealtime()
            if (connected) {
                // One request at a time: the stack silently drops a read issued while another
                // is pending, and can then stop answering altogether.
                if (servicesReady && rssiReads == 0 && now - servicesReadyMs > NO_RSSI_GIVE_UP_MS) {
                    canMeasure = false
                    closeClient("no RSSI answers, handing the measuring role to the other phone")
                    return
                }
                if (servicesReady && (!rssiPending || now - rssiRequestedMs > RSSI_TIMEOUT_MS)) {
                    rssiPending = g.readRemoteRssi()
                    rssiRequestedMs = now
                }
            } else if (SystemClock.elapsedRealtime() - connectStartedMs > CONNECT_TIMEOUT_MS) {
                closeClient("connect timed out")
                return
            }
            handler.postDelayed(this, RSSI_INTERVAL_MS)
        }
    }

    fun start() {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        try {
            server = manager.openGattServer(context, serverCallback)?.apply {
                val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        BleConstants.RSSI_CHARACTERISTIC_UUID,
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_WRITE,
                    )
                )
                addService(service)
            }
            report()
        } catch (e: Exception) {
            state = "failed (${e.message})"
        }
    }

    fun stop() {
        closeClient(null)
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        state = "off"
    }

    /** Call for every connectable advertisement received from [peerId]. */
    fun onConnectable(device: BluetoothDevice, peerId: Long, peerCannotMeasure: Boolean) {
        if (server == null || gatt != null || !canMeasure) return
        if (!peerCannotMeasure && peerId >= deviceId) return
        gattPeerId = peerId
        connected = false
        connectStartedMs = SystemClock.elapsedRealtime()
        try {
            gatt = device.connectGatt(
                context, false, clientCallback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK, handler,
            )
            handler.postDelayed(poll, RSSI_INTERVAL_MS)
            report()
        } catch (e: Exception) {
            gatt = null
            state = "failed (${e.message})"
        }
    }

    private fun closeClient(reason: String?) {
        handler.removeCallbacks(poll)
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        characteristic = null
        connected = false
        servicesReady = false
        rssiPending = false
        if (reason != null) Log.i(TAG, "link closed: $reason")
        report()
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.setPreferredPhy(
                    BluetoothDevice.PHY_LE_CODED_MASK, BluetoothDevice.PHY_LE_CODED_MASK,
                    BluetoothDevice.PHY_OPTION_S8,
                )
                g.discoverServices()
                report()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // The next connectable advertisement from the peer starts a new connection.
                closeClient("disconnected, status $status")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            characteristic = g.getService(BleConstants.SERVICE_UUID)
                ?.getCharacteristic(BleConstants.RSSI_CHARACTERISTIC_UUID)
            servicesReady = true
            servicesReadyMs = SystemClock.elapsedRealtime()
        }

        override fun onPhyUpdate(g: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            phy = if (rxPhy == BluetoothDevice.PHY_LE_CODED) "long range" else "${rxPhy}M"
            report()
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            rssiPending = false
            rssiReads++
            if (status != BluetoothGatt.GATT_SUCCESS) return
            onRssi?.invoke(gattPeerId, rssi)
            val ch = characteristic ?: return
            val value = ByteBuffer.allocate(5).putInt(deviceId.toInt()).put(rssi.toByte()).array()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = value
                    g.writeCharacteristic(ch)
                }
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) serverClients++
                else if (newState == BluetoothProfile.STATE_DISCONNECTED && serverClients > 0) serverClients--
                report()
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (value == null || value.size < 5) return
            val buf = ByteBuffer.wrap(value)
            val peerId = buf.int.toLong() and 0xFFFFFFFFL
            val rssi = buf.get().toInt()
            handler.post { onRssi?.invoke(peerId, rssi) }
        }
    }

    private fun report() {
        state = when {
            gatt != null && connected -> "connected ($phy), measuring"
            gatt != null -> "connecting"
            serverClients > 0 && !canMeasure -> "connected, answering (cannot measure on this phone)"
            serverClients > 0 -> "connected, answering"
            else -> "waiting for the other phone"
        }
    }

    private companion object {
        const val RSSI_INTERVAL_MS = 100L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val RSSI_TIMEOUT_MS = 2_000L
        const val NO_RSSI_GIVE_UP_MS = 4_000L
        const val TAG = "RescueLog"
    }
}
