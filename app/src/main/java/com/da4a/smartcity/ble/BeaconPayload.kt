package com.da4a.smartcity.ble

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 25-byte big-endian payload carried in the advertisement (manufacturer data).
 *
 * 0–3 device ID, 4 flags (bit0 cannot read connection RSSI, bit1 has barometer, bit2 has GPS
 * fix, bit3 Wi-Fi ranging from this phone keeps failing, bit4 emergency, bit5 temperature is
 * of the air rather than the battery), 5 battery %, 6–7 pressure (Pa − 80000, 0xFFFF = none),
 * 8–11 lat × 1e7, 12–15 lon × 1e7 (0x7FFFFFFF = none), 16 fix age in minutes, 17 sequence,
 * 18–21 ID of the phone this one last ranged over Wi-Fi, 22–23 that distance in cm
 * (0xFFFF = none), 24 temperature in °C (signed, −128 = none).
 */
data class BeaconPayload(
    val deviceId: Long,
    val batteryPct: Int,
    val pressurePa: Int?,
    val lat: Double?,
    val lon: Double?,
    val fixAgeMin: Int,
    val seq: Int,
    val rangedPeerId: Long? = null,
    val rangedDistanceCm: Int? = null,
    /** This phone cannot act as the measuring end of the Bluetooth link / of Wi-Fi ranging. */
    val cannotMeasureRssi: Boolean = false,
    val cannotRange: Boolean = false,
    /** The user asked for help; rescuers only look for phones with this set. */
    val emergency: Boolean = false,
    /** Temperature at the phone: of the air when [airTemperature], otherwise of its battery. */
    val temperatureC: Int? = null,
    val airTemperature: Boolean = false,
) {
    fun encode(): ByteArray {
        val hasFix = lat != null && lon != null
        var flags = 0
        if (pressurePa != null) flags = flags or FLAG_BAROMETER
        if (hasFix) flags = flags or FLAG_GPS_FIX
        if (cannotMeasureRssi) flags = flags or FLAG_NO_RSSI
        if (cannotRange) flags = flags or FLAG_NO_RANGE
        if (emergency) flags = flags or FLAG_EMERGENCY
        if (airTemperature) flags = flags or FLAG_AIR_TEMPERATURE
        return ByteBuffer.allocate(SIZE)
            .putInt(deviceId.toInt())
            .put(flags.toByte())
            .put(batteryPct.coerceIn(0, 255).toByte())
            .putShort((pressurePa?.let { (it - PRESSURE_BASE).coerceIn(0, 0xFFFE) } ?: 0xFFFF).toShort())
            .putInt(if (hasFix) (lat!! * 1e7).roundToInt() else NO_COORD)
            .putInt(if (hasFix) (lon!! * 1e7).roundToInt() else NO_COORD)
            .put(fixAgeMin.coerceIn(0, 255).toByte())
            .put(seq.toByte())
            .putInt(rangedPeerId?.toInt() ?: 0)
            .putShort((rangedDistanceCm?.coerceIn(0, 0xFFFE) ?: 0xFFFF).toShort())
            .put((temperatureC?.coerceIn(-127, 127) ?: NO_TEMPERATURE).toByte())
            .array()
    }

    companion object {
        const val SIZE = 25
        private const val SIZE_WITHOUT_RANGE = 18
        private const val SIZE_WITHOUT_TEMPERATURE = 24
        private const val FLAG_NO_RSSI = 1 shl 0
        private const val FLAG_BAROMETER = 1 shl 1
        private const val FLAG_NO_RANGE = 1 shl 3
        private const val FLAG_GPS_FIX = 1 shl 2
        private const val FLAG_EMERGENCY = 1 shl 4
        private const val FLAG_AIR_TEMPERATURE = 1 shl 5
        private const val PRESSURE_BASE = 80000
        private const val NO_COORD = 0x7FFFFFFF
        private const val NO_TEMPERATURE = -128

        fun decode(bytes: ByteArray?): BeaconPayload? {
            if (bytes == null || bytes.size < SIZE_WITHOUT_RANGE) return null
            val buf = ByteBuffer.wrap(bytes)
            val deviceId = buf.int.toLong() and 0xFFFFFFFFL
            val flags = buf.get().toInt() // barometer and GPS bits are implied by sentinels
            val battery = buf.get().toInt() and 0xFF
            val pressure = buf.short.toInt() and 0xFFFF
            val lat = buf.int
            val lon = buf.int
            val fixAge = buf.get().toInt() and 0xFF
            val seq = buf.get().toInt() and 0xFF
            val hasFix = lat != NO_COORD && lon != NO_COORD
            val rangedPeer = if (bytes.size >= SIZE_WITHOUT_TEMPERATURE) buf.int.toLong() and 0xFFFFFFFFL else null
            val rangedCm = if (bytes.size >= SIZE_WITHOUT_TEMPERATURE) buf.short.toInt() and 0xFFFF else 0xFFFF
            val temperature = if (bytes.size >= SIZE) buf.get().toInt() else NO_TEMPERATURE
            return BeaconPayload(
                deviceId = deviceId,
                batteryPct = battery,
                pressurePa = if (pressure == 0xFFFF) null else pressure + PRESSURE_BASE,
                lat = if (hasFix) lat / 1e7 else null,
                lon = if (hasFix) lon / 1e7 else null,
                fixAgeMin = fixAge,
                seq = seq,
                rangedPeerId = rangedPeer.takeIf { rangedCm != 0xFFFF },
                rangedDistanceCm = rangedCm.takeIf { it != 0xFFFF },
                cannotMeasureRssi = flags and FLAG_NO_RSSI != 0,
                cannotRange = flags and FLAG_NO_RANGE != 0,
                emergency = flags and FLAG_EMERGENCY != 0,
                temperatureC = temperature.takeIf { it != NO_TEMPERATURE },
                airTemperature = flags and FLAG_AIR_TEMPERATURE != 0,
            )
        }
    }
}
