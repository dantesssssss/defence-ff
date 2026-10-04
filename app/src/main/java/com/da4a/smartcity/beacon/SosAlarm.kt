package com.da4a.smartcity.beacon

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.da4a.smartcity.R

/**
 * Makes a victim's phone findable by eye and ear, also by rescuers without the app: the torch
 * blinks and a siren loops at full alarm volume, which plays through silent mode. Both are
 * best effort; a phone without a torch, or whose camera another app holds, only sounds the
 * siren.
 */
class SosAlarm(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val torchId: String? by lazy { findTorch() }

    // Without it the CPU sleeps soon after the screen goes off, and the torch stops blinking.
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartCity:sos")

    private var active = false
    private var torchOn = false
    private var player: MediaPlayer? = null
    private var volumeBefore: Int? = null

    private val blink = object : Runnable {
        override fun run() {
            setTorch(!torchOn)
            handler.postDelayed(this, BLINK_MS)
        }
    }

    // Held for exactly as long as the emergency; stop() releases it.
    @SuppressLint("WakelockTimeout")
    fun start() {
        if (active) return
        active = true
        wakeLock.acquire()
        handler.post(blink)
        startSiren()
    }

    fun stop() {
        if (!active) return
        active = false
        handler.removeCallbacks(blink)
        setTorch(false)
        player?.release()
        player = null
        volumeBefore?.let(::setAlarmVolume)
        volumeBefore = null
        wakeLock.release()
    }

    private fun startSiren() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        player = MediaPlayer.create(context, R.raw.siren, attributes, audio.generateAudioSessionId())?.apply {
            isLooping = true
            if (QUIET_FOR_TESTING) setVolume(QUIET_VOLUME, QUIET_VOLUME)
            start()
        }
        if (QUIET_FOR_TESTING) return
        volumeBefore = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        setAlarmVolume(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
    }

    private fun setAlarmVolume(volume: Int) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0)
        } catch (_: SecurityException) {
            // Some Do Not Disturb setups refuse volume changes; the siren then plays as loud as allowed.
        }
    }

    private fun setTorch(on: Boolean) {
        val id = torchId ?: return
        torchOn = on
        try {
            camera.setTorchMode(id, on)
        } catch (_: Exception) {
            // The camera is in use; keep trying on the next blink.
        }
    }

    private fun findTorch(): String? = try {
        camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        // Once a second: easy to spot, and well below the rates that can trigger seizures.
        const val BLINK_MS = 500L

        // While testing, the siren is barely audible and the alarm volume is left alone.
        // Set to false for real use: full alarm volume.
        const val QUIET_FOR_TESTING = true
        const val QUIET_VOLUME = 0.03f
    }
}
