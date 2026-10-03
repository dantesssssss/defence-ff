package com.da4a.smartcity.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPayloadTest {

    private val payload = BeaconPayload(
        deviceId = 0xCAFEBABEL, batteryPct = 82, pressurePa = 100_840, lat = 52.2297, lon = 21.0122,
        fixAgeMin = 3, seq = 7, rangedPeerId = 0x12345678L, rangedDistanceCm = 250,
        cannotMeasureRssi = true, cannotRange = false,
    )

    @Test
    fun emergencyFlagSurvivesARoundTrip() {
        assertTrue(BeaconPayload.decode(payload.copy(emergency = true).encode())!!.emergency)
        assertFalse(BeaconPayload.decode(payload.copy(emergency = false).encode())!!.emergency)
    }

    @Test
    fun emergencyFlagLeavesTheOtherFieldsIntact() {
        val sent = payload.copy(emergency = true)
        assertEquals(sent, BeaconPayload.decode(sent.encode()))
    }
}
