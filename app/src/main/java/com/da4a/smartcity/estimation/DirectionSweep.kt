package com.da4a.smartcity.estimation

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.da4a.smartcity.sensors.HeadingSource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class SweepResult(
    /** Bearing to the other phone, in the frame of [HeadingSource.relativeDeg]. */
    val bearingDeg: Float,
    /** Strongest minus weakest heading, in dB. Small spread means the bearing is a guess. */
    val spreadDb: Float,
    val timeMs: Long,
)

/**
 * Finds direction from signal strength alone: the user turns a full circle with the phone
 * held against the chest. The body blocks the signal when facing away from the other phone,
 * so the heading with the strongest signal points towards it.
 */
class DirectionSweep {

    var targetId by mutableStateOf<Long?>(null)
        private set

    /** Number of heading bins that have enough samples, out of [BINS]. */
    var coveredBins by mutableIntStateOf(0)
        private set

    val results = mutableStateMapOf<Long, SweepResult>()

    private val sums = FloatArray(BINS)
    private val counts = IntArray(BINS)

    fun start(peerId: Long) {
        sums.fill(0f)
        counts.fill(0)
        coveredBins = 0
        targetId = peerId
    }

    fun cancel() {
        targetId = null
    }

    fun onSample(peerId: Long, rssi: Int, headingDeg: Float?) {
        if (peerId != targetId || headingDeg == null) return
        val bin = (headingDeg / BIN_DEG).toInt().coerceIn(0, BINS - 1)
        sums[bin] += rssi
        counts[bin]++
        coveredBins = counts.count { it >= MIN_SAMPLES }
        if (coveredBins == BINS) finish()
    }

    /** Ends the sweep with whatever has been collected so far. */
    fun finish() {
        val id = targetId ?: return
        targetId = null
        if (counts.count { it > 0 } < MIN_BINS_FOR_RESULT) return

        val filled = (0 until BINS).filter { counts[it] > 0 }.map { sums[it] / counts[it] }
        val floor = filled.min()
        // Headings never visited count as the weakest, so they cannot win.
        val means = FloatArray(BINS) { if (counts[it] > 0) sums[it] / counts[it] else floor }
        val smooth = FloatArray(BINS) {
            0.25f * means[(it + BINS - 1) % BINS] + 0.5f * means[it] + 0.25f * means[(it + 1) % BINS]
        }
        val min = smooth.min()
        var x = 0.0
        var y = 0.0
        for (i in 0 until BINS) {
            val weight = (smooth[i] - min).let { it * it }
            val angle = Math.toRadians((i + 0.5) * BIN_DEG)
            x += weight * sin(angle)
            y += weight * cos(angle)
        }
        results[id] = SweepResult(
            bearingDeg = HeadingSource.normalizeDeg(Math.toDegrees(atan2(x, y)).toFloat()),
            spreadDb = smooth.max() - min,
            timeMs = SystemClock.elapsedRealtime(),
        )
    }

    companion object {
        const val BINS = 12
        const val MIN_BINS_FOR_RESULT = 8
        /** Below this spread the body shadow is not visible in the data. */
        const val MIN_SPREAD_DB = 6f
        private const val BIN_DEG = 360f / BINS
        private const val MIN_SAMPLES = 3
    }
}
