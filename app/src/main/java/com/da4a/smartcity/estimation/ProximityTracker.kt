package com.da4a.smartcity.estimation

import android.os.SystemClock
import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

enum class Trend { CLOSER, FARTHER, STEADY }

data class Proximity(
    val distanceM: Float,
    /** True while a recent Wi-Fi ranging result is part of the estimate. */
    val usesWifi: Boolean,
    val smoothedRssi: Float?,
    val trend: Trend,
    val updatedMs: Long,
)

/** Median of the last few samples, then exponential smoothing. */
private class RssiFilter {
    private val window = ArrayDeque<Int>()
    private var smoothed: Float? = null

    fun add(rssi: Int): Float {
        window.addLast(rssi)
        if (window.size > MEDIAN_WINDOW) window.removeFirst()
        val median = window.sorted()[window.size / 2].toFloat()
        return (smoothed?.let { it + ALPHA * (median - it) } ?: median).also { smoothed = it }
    }

    private companion object {
        const val MEDIAN_WINDOW = 5
        const val ALPHA = 0.3f
    }
}

/**
 * Fuses Bluetooth signal strength and Wi-Fi ranging into one continuous closeness estimate.
 *
 * Bluetooth RSSI arrives many times a second and drives the estimate. Wi-Fi ranging is slow
 * and often fails, so it never replaces the Bluetooth value; instead each successful range
 * re-calibrates the RSSI-to-distance mapping and is blended in while it is fresh. The output
 * therefore never jumps between two differently scaled sources.
 */
class ProximityTracker {

    val states = mutableStateMapOf<Long, Proximity>()

    private class Track {
        val filter = RssiFilter()
        var rssi: Float? = null
        var advRssi: Float? = null
        var advTimeMs = 0L
        var connTimeMs = 0L
        /** How much stronger the connection reads than advertisements (it transmits louder). */
        var connOffsetDb: Float? = null
        /** RSSI expected at 1 m; learned from Wi-Fi ranges because it differs per phone pair. */
        var p1mDbm = DEFAULT_P1M_DBM
        var rangeM: Float? = null
        var rangeTimeMs = 0L
        val rangeWindow = ArrayDeque<Float>()
        /** (time, closeness level in dB): higher is closer. */
        val history = ArrayDeque<Pair<Long, Float>>()
        var trend = Trend.STEADY
    }

    private val tracks = HashMap<Long, Track>()

    /** RSSI of a received advertisement. */
    fun onBle(peerId: Long, rssi: Int) {
        val track = tracks.getOrPut(peerId) { Track() }
        val now = SystemClock.elapsedRealtime()
        track.advRssi = track.advRssi?.let { it + ADV_ALPHA * (rssi - it) } ?: rssi.toFloat()
        track.advTimeMs = now
        // While the connection delivers readings it is the only source, so the estimate does
        // not wobble between two differently powered signals.
        if (now - track.connTimeMs < CONN_FRESH_MS) return
        track.rssi = track.filter.add(rssi)
        publish(peerId, track)
    }

    /** RSSI of the Bluetooth connection, shifted onto the same scale as advertisements. */
    fun onConnRssi(peerId: Long, rssi: Int) {
        val track = tracks.getOrPut(peerId) { Track() }
        val now = SystemClock.elapsedRealtime()
        track.connTimeMs = now
        val adv = track.advRssi
        if (adv != null && now - track.advTimeMs < ADV_FRESH_MS) {
            val offset = rssi - adv
            track.connOffsetDb = track.connOffsetDb?.let { it + OFFSET_ALPHA * (offset - it) } ?: offset
        }
        track.rssi = track.filter.add((rssi - (track.connOffsetDb ?: 0f)).toInt())
        publish(peerId, track)
    }

    /**
     * [raw] is true for this phone's own measurements, which jump by a metre or so and are
     * median-filtered here; distances received from the other phone are already filtered.
     */
    fun onRange(peerId: Long, distanceM: Float, raw: Boolean = true) {
        val track = tracks.getOrPut(peerId) { Track() }
        val range = if (raw) {
            track.rangeWindow.addLast(distanceM.coerceAtLeast(0f))
            if (track.rangeWindow.size > RANGE_MEDIAN_WINDOW) track.rangeWindow.removeFirst()
            track.rangeWindow.sorted()[track.rangeWindow.size / 2]
        } else {
            distanceM
        }
        track.rangeM = range
        track.rangeTimeMs = SystemClock.elapsedRealtime()

        // Below this the ranging error is as large as the distance itself and would only
        // teach the calibration noise.
        val rssi = track.rssi
        if (rssi != null && range >= LEARN_MIN_RANGE_M) {
            val observedP1m = rssi + 10f * PATH_LOSS_EXPONENT * log10(range)
            track.p1mDbm = (track.p1mDbm + LEARN_RATE * (observedP1m - track.p1mDbm)).coerceIn(P1M_MIN, P1M_MAX)
        }
        publish(peerId, track)
    }

    /** Smoothed signal strength of [peerId] on the advertisement scale, if it was heard recently. */
    fun recentRssi(peerId: Long): Float? =
        states[peerId]?.takeIf { SystemClock.elapsedRealtime() - it.updatedMs < 2_000L }?.smoothedRssi

    /** This phone's filtered Wi-Fi range to [peerId], for sharing with that phone. */
    fun rangeTo(peerId: Long): Float? = tracks[peerId]?.rangeM

    private fun publish(peerId: Long, track: Track) {
        val now = SystemClock.elapsedRealtime()
        val rangeAge = now - track.rangeTimeMs
        val range = track.rangeM?.takeIf { rangeAge < RANGE_FRESH_MS }?.coerceAtLeast(MIN_DISTANCE_M)
        val ble = track.rssi?.let { 10f.pow((track.p1mDbm - it) / (10f * PATH_LOSS_EXPONENT)) }
        val distance = when {
            ble != null && range != null -> {
                // The range counts for less the older it gets, so it fades out instead of
                // dropping away in one step.
                val weight = RANGE_MAX_WEIGHT * (1f - rangeAge.toFloat() / RANGE_FRESH_MS)
                10f.pow((1f - weight) * log10(ble) + weight * log10(range))
            }
            else -> ble ?: range ?: return
        }

        val level = -10f * PATH_LOSS_EXPONENT * log10(distance.coerceAtLeast(MIN_DISTANCE_M))
        val history = track.history
        history.addLast(now to level)
        while (now - history.first().first > TREND_HISTORY_MS) history.removeFirst()
        val recent = history.filter { now - it.first <= TREND_RECENT_MS }.map { it.second }
        val older = history.filter { now - it.first >= TREND_OLDER_MS }.map { it.second }
        if (older.isNotEmpty()) {
            val change = recent.average() - older.average()
            // Hysteresis: a trend starts at a clear change and only ends once it has nearly
            // vanished, so it does not flicker around the threshold.
            track.trend = when {
                change > TREND_START_DB -> Trend.CLOSER
                change < -TREND_START_DB -> Trend.FARTHER
                abs(change) < TREND_END_DB -> Trend.STEADY
                else -> track.trend
            }
        }
        states[peerId] = Proximity(distance, usesWifi = range != null, track.rssi, track.trend, now)
    }

    private companion object {
        const val DEFAULT_P1M_DBM = -59f
        const val P1M_MIN = -80f
        const val P1M_MAX = -40f
        const val PATH_LOSS_EXPONENT = 3.0f
        const val MIN_DISTANCE_M = 0.1f

        const val RANGE_FRESH_MS = 8_000L
        const val RANGE_MAX_WEIGHT = 0.6f
        const val RANGE_MEDIAN_WINDOW = 5
        const val LEARN_MIN_RANGE_M = 1.5f
        const val LEARN_RATE = 0.2f

        const val ADV_ALPHA = 0.3f
        const val ADV_FRESH_MS = 1_000L
        const val CONN_FRESH_MS = 1_500L
        const val OFFSET_ALPHA = 0.1f

        const val TREND_HISTORY_MS = 6_000L
        const val TREND_RECENT_MS = 1_500L
        const val TREND_OLDER_MS = 3_000L
        const val TREND_START_DB = 3f
        const val TREND_END_DB = 1.5f
    }
}
