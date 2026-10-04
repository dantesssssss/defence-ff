package com.da4a.smartcity.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPayloadTest {

    private val payload = BeaconPayload(
        deviceId = 0xCAFEBABEL, batteryPct = 82, pressurePa = 100_840, lat = 52.2297, lon = 21.0122,
        fixAgeMin = 3, seq = 7, rangedPeerId = 0x12345678L, rangedDistanceCm = 250,
        cannotMeasureRssi = true, cannotRange = false, temperatureC = 37, airTemperature = true,
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

    @Test
    fun temperatureSurvivesARoundTrip() {
        for (celsius in listOf(-20, 0, 37, 85)) {
            for (air in listOf(true, false)) {
                val sent = payload.copy(temperatureC = celsius, airTemperature = air)
                assertEquals(sent, BeaconPayload.decode(sent.encode()))
            }
        }
    }

    @Test
    fun missingTemperatureDecodesAsNone() {
        assertNull(BeaconPayload.decode(payload.copy(temperatureC = null).encode())!!.temperatureC)
    }

    @Test
    fun payloadFromAnOlderVersionHasNoTemperature() {
        val old = BeaconPayload.decode(payload.encode().copyOf(24))!!
        assertNull(old.temperatureC)
        assertEquals(payload.rangedDistanceCm, old.rangedDistanceCm)
    }

    @Test
    fun fitsInALegacyAdvertisement() {
        // Length, type and company ID come before our bytes in the 31-byte advertisement.
        val adBytes = 4 + BleConstants.MAGIC.size + payload.encode().size
        assertTrue("advertisement is $adBytes bytes", adBytes <= 31)
    }
}
