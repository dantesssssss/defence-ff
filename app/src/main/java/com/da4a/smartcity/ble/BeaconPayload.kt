package com.da4a.smartcity.ble

import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 18-byte big-endian payload carried in the scan response (manufacturer data).
 *
 * 0–3 device ID, 4 flags (bit1 has barometer, bit2 has GPS fix), 5 battery %,
 * 6–7 pressure (Pa − 80000, 0xFFFF = none), 8–11 lat × 1e7, 12–15 lon × 1e7
 * (0x7FFFFFFF = none), 16 fix age in minutes, 17 sequence.
 */
data class BeaconPayload(
    val deviceId: Long,
    val batteryPct: Int,
    val pressurePa: Int?,
    val lat: Double?,
    val lon: Double?,
    val fixAgeMin: Int,
    val seq: Int,
) {
    fun encode(): ByteArray {
        val hasFix = lat != null && lon != null
        var flags = 0
        if (pressurePa != null) flags = flags or FLAG_BAROMETER
        if (hasFix) flags = flags or FLAG_GPS_FIX
        return ByteBuffer.allocate(SIZE)
            .putInt(deviceId.toInt())
            .put(flags.toByte())
            .put(batteryPct.coerceIn(0, 255).toByte())
            .putShort((pressurePa?.let { (it - PRESSURE_BASE).coerceIn(0, 0xFFFE) } ?: 0xFFFF).toShort())
            .putInt(if (hasFix) (lat!! * 1e7).roundToInt() else NO_COORD)
            .putInt(if (hasFix) (lon!! * 1e7).roundToInt() else NO_COORD)
            .put(fixAgeMin.coerceIn(0, 255).toByte())
            .put(seq.toByte())
            .array()
    }

    companion object {
        const val SIZE = 18
        private const val FLAG_BAROMETER = 1 shl 1
        private const val FLAG_GPS_FIX = 1 shl 2
        private const val PRESSURE_BASE = 80000
        private const val NO_COORD = 0x7FFFFFFF

        fun decode(bytes: ByteArray?): BeaconPayload? {
            if (bytes == null || bytes.size < SIZE) return null
            val buf = ByteBuffer.wrap(bytes)
            val deviceId = buf.int.toLong() and 0xFFFFFFFFL
            buf.get() // flags are implied by the sentinel values below
            val battery = buf.get().toInt() and 0xFF
            val pressure = buf.short.toInt() and 0xFFFF
            val lat = buf.int
            val lon = buf.int
            val fixAge = buf.get().toInt() and 0xFF
            val seq = buf.get().toInt() and 0xFF
            val hasFix = lat != NO_COORD && lon != NO_COORD
            return BeaconPayload(
                deviceId = deviceId,
                batteryPct = battery,
                pressurePa = if (pressure == 0xFFFF) null else pressure + PRESSURE_BASE,
                lat = if (hasFix) lat / 1e7 else null,
                lon = if (hasFix) lon / 1e7 else null,
                fixAgeMin = fixAge,
                seq = seq,
            )
        }
    }
}
