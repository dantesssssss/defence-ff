package com.da4a.smartcity.estimation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.roundToInt

class ProximityTrackerTest {

    private var now = 0L
    private val tracker = ProximityTracker { now }
    private val proximity get() = tracker.states.getValue(PEER)

    /** RSSI that the default calibration (−59 dBm at 1 m, exponent 3) reads as [distanceM]. */
    private fun rssiAt(distanceM: Float) = (-59 - 30 * log10(distanceM)).roundToInt()

    /** An advertisement every 50 ms for [ms]; [after] runs after each one, as in BeaconEngine. */
    private fun advertise(ms: Long, rssi: Int, after: () -> Unit = {}) {
        val end = now + ms
        while (now < end) {
            now += 50
            tracker.onBle(PEER, rssi)
            after()
        }
    }

    /** This phone's own Wi-Fi ranging, one result every 1.2 s. */
    private var nextRangeMs = 0L
    private fun rangeEvery1200Ms(distanceM: Float) = {
        if (now >= nextRangeMs) {
            tracker.onRange(PEER, distanceM)
            nextRangeMs += 1_200
        }
    }

    @Test
    fun aDistanceFromTheOtherPhoneCountsOnceNotOncePerAdvertisement() {
        // The other phone repeats its last Wi-Fi distance in every advertisement for seconds.
        val shared = { tracker.onRange(PEER, 10f, raw = false) }
        advertise(5_000, rssiAt(10f), shared)
        // Walking closer: the signal now says 5 m while the repeated distance still says 10 m.
        advertise(1_000, rssiAt(5f), shared)
        assertTrue("distance ${proximity.distanceM}", proximity.distanceM < 8f)
    }

    @Test
    fun trendFollowsTheSignalWhileAnOlderWifiDistanceIsBlendedIn() {
        advertise(10_000, rssiAt(10f), rangeEvery1200Ms(10f))
        // 6 dB stronger. The next ranges are 6.3 m, but their median still says 10 m.
        advertise(1_500, rssiAt(6.3f), rangeEvery1200Ms(6.3f))
        assertEquals(Trend.CLOSER, proximity.trend)
    }

    @Test
    fun calibrationDoesNotLearnFromALaggingWifiDistanceWhileMoving() {
        advertise(10_000, rssiAt(10f), rangeEvery1200Ms(10f))
        // Step to 5 m. The ranges' median keeps saying 10 m for two more results.
        advertise(5_000, rssiAt(5f), rangeEvery1200Ms(5f))
        // Ranging stops; once the last range has expired, the signal alone gives the distance.
        advertise(9_000, rssiAt(5f))
        assertEquals(5f, proximity.distanceM, 0.4f)
    }

    private companion object {
        const val PEER = 1L
    }
}
