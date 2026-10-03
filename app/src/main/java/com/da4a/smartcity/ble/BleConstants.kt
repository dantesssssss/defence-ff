package com.da4a.smartcity.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("7e5c0001-9a1b-4c3d-8e2f-1a2b3c4d5e6f")
    val RSSI_CHARACTERISTIC_UUID: UUID = UUID.fromString("7e5c0002-9a1b-4c3d-8e2f-1a2b3c4d5e6f")
    const val COMPANY_ID = 0xFFFF

    /**
     * Prefix of our manufacturer data. 0xFFFF is the shared test company ID, so this is what
     * actually identifies a beacon; scans filter on it.
     */
    val MAGIC = byteArrayOf(0x52, 0x42)
}
