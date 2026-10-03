package com.da4a.smartcity.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Measures the distance to other phones with Wi-Fi Aware discovery + Wi-Fi RTT ranging.
 * Needs Wi-Fi switched on, but no access point and no internet. Every phone both publishes
 * (so it can be ranged) and subscribes (so it can range the others).
 */
@SuppressLint("MissingPermission")
class AwareRanger(private val context: Context, private val deviceId: Long) {

    var state by mutableStateOf("off")
        private set

    /** Called with the other phone's device ID and the measured distance in metres. */
    var onDistance: ((Long, Float) -> Unit)? = null

    /** Whether the given peer has announced that its own ranging attempts keep failing. */
    var peerCannotRange: (Long) -> Boolean = { false }

    /**
     * Whether the given peer is close enough to be worth ranging. A request to a peer that is
     * out of range can get stuck inside Android's ranging service (seen on a Pixel 10a) and
     * then blocks every later request until the phone is rebooted.
     */
    var peerInRange: (Long) -> Boolean = { true }

    /** True once this phone's attempts have failed so often that the peer should measure instead. */
    var cannotRange by mutableStateOf(false)
        private set

    private val awareManager = context.getSystemService(WifiAwareManager::class.java)
    private val rttManager = context.getSystemService(WifiRttManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    // One handle per device: the other phone gets a new handle every time it restarts its
    // session, and ranging to the stale ones only produces timeouts.
    private val peers = HashMap<Long, PeerHandle>()
    private var session: WifiAwareSession? = null
    private var attaching = false
    private var attachFailures = 0
    private var nextAttachMs = 0L
    private var stopped = true
    private var rangingInFlight = false
    private var rangingStartedMs = 0L
    private var rangesOk = 0
    private var rangesFailed = 0
    private var lastProblem = ""

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            if (session == null) attach() else range()
            // Jittered so the two phones do not keep ranging each other at the same instant.
            handler.postDelayed(this, RANGE_INTERVAL_MS + Random.nextLong(RANGE_JITTER_MS))
        }
    }

    fun start() {
        val pm = context.packageManager
        if (awareManager == null || rttManager == null ||
            !pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
        ) {
            state = "not supported"
            return
        }
        if (!stopped) return
        stopped = false
        handler.post(tick)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacks(tick)
        session?.close()
        session = null
        peers.clear()
        state = "off"
    }

    private fun attach() {
        // Retrying too eagerly hides the failure text and can itself keep the radio busy.
        if (attaching || SystemClock.elapsedRealtime() < nextAttachMs) return
        if (awareManager?.isAvailable != true) {
            state = "unavailable (turn Wi-Fi and location on)"
            return
        }
        attaching = true
        if (attachFailures == 0) state = "starting"
        try {
            awareManager.attach(object : AttachCallback() {
                override fun onAttached(newSession: WifiAwareSession) {
                    attaching = false
                    if (stopped) {
                        newSession.close()
                        return
                    }
                    attachFailures = 0
                    session = newSession
                    discover(newSession)
                }

                override fun onAttachFailed() {
                    attaching = false
                    attachFailures++
                    nextAttachMs = SystemClock.elapsedRealtime() + ATTACH_RETRY_MS
                    state = "attach failed ×$attachFailures (hotspot or Wi-Fi Direct in use?)"
                    Log.w(TAG, "Wi-Fi Aware attach failed, attempt $attachFailures")
                }

                override fun onAwareSessionTerminated() {
                    session = null
                    peers.clear()
                    state = "session lost"
                }
            }, handler)
        } catch (e: Exception) {
            attaching = false
            state = "failed (${e.message})"
        }
    }

    private fun discover(session: WifiAwareSession) {
        try {
            val publish = PublishConfig.Builder()
                .setServiceName(SERVICE_NAME)
                .setServiceSpecificInfo(ByteBuffer.allocate(4).putInt(deviceId.toInt()).array())
                .setRangingEnabled(true)
                .build()
            session.publish(publish, object : DiscoverySessionCallback() {}, handler)

            val subscribe = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
            session.subscribe(subscribe, object : DiscoverySessionCallback() {
                override fun onServiceDiscovered(
                    peerHandle: PeerHandle,
                    serviceSpecificInfo: ByteArray?,
                    matchFilter: MutableList<ByteArray>?,
                ) {
                    if (serviceSpecificInfo == null || serviceSpecificInfo.size < 4) return
                    peers[ByteBuffer.wrap(serviceSpecificInfo).int.toLong() and 0xFFFFFFFFL] = peerHandle
                    reportRanging()
                }
            }, handler)
            state = "searching"
        } catch (e: Exception) {
            state = "failed (${e.message})"
        }
    }

    private fun range() {
        val now = SystemClock.elapsedRealtime()
        if (rangingInFlight && now - rangingStartedMs > RANGE_TIMEOUT_MS) {
            rangingInFlight = false
            rangesFailed++
            lastProblem = "no answer"
            reportRanging()
        }
        // When both phones range each other at once the requests collide and nearly all fail,
        // so for each pair only the phone with the higher ID measures; the other one gets the
        // result back over Bluetooth.
        val targets = peers.filterKeys { shouldMeasure(it) && peerInRange(it) }.values
        if (targets.isEmpty() || rangingInFlight) return
        if (rttManager?.isAvailable != true) {
            lastProblem = "RTT unavailable"
            reportRanging()
            return
        }
        try {
            rangingInFlight = true
            rangingStartedMs = now
            val request = RangingRequest.Builder()
                .apply { targets.take(RangingRequest.getMaxPeers()).forEach(::addWifiAwarePeer) }
                .build()
            rttManager.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    rangingInFlight = false
                    for (result in results) {
                        val id = peers.entries.firstOrNull { it.value == result.peerHandle }?.key
                        if (result.status != RangingResult.STATUS_SUCCESS) {
                            rangesFailed++
                            lastProblem = "result status ${result.status}"
                        } else if (id == null) {
                            rangesFailed++
                            lastProblem = "unknown peer"
                        } else {
                            rangesOk++
                            onDistance?.invoke(id, result.distanceMm / 1000f)
                        }
                    }
                    if (results.isEmpty()) lastProblem = "empty result"
                    reportRanging()
                }

                override fun onRangingFailure(code: Int) {
                    rangingInFlight = false
                    rangesFailed++
                    lastProblem = "request failed, code $code"
                    Log.w(TAG, "Ranging failed: $code")
                    reportRanging()
                }
            })
        } catch (e: Exception) {
            rangingInFlight = false
            rangesFailed++
            lastProblem = "${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Ranging threw", e)
            reportRanging()
        }
    }

    // Ranging works much better in one direction than the other for some phone pairs, so a
    // phone whose attempts mostly fail hands the role over.
    private fun shouldMeasure(peerId: Long) = !cannotRange && (peerCannotRange(peerId) || peerId < deviceId)

    private fun reportRanging() {
        val attempts = rangesOk + rangesFailed
        if (!cannotRange && attempts >= GIVE_UP_AFTER && rangesOk < attempts * GIVE_UP_SUCCESS_RATE) cannotRange = true
        val role = if (peers.keys.any(::shouldMeasure)) "measuring" else "answering"
        state = "active, ${peers.size} peer, $role · ok $rangesOk / failed $rangesFailed" +
            if (lastProblem.isEmpty()) "" else " · last problem: $lastProblem"
    }

    private companion object {
        const val SERVICE_NAME = "rescuebeacon"
        const val RANGE_INTERVAL_MS = 1000L
        const val RANGE_JITTER_MS = 400L
        const val RANGE_TIMEOUT_MS = 5000L
        const val GIVE_UP_AFTER = 20
        const val GIVE_UP_SUCCESS_RATE = 0.2f
        const val ATTACH_RETRY_MS = 5000L
        const val TAG = "AwareRanger"
    }
}
