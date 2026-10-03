package com.da4a.smartcity.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Barometer reading in Pa, averaged over the last second. */
class PressureSource(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val samples = ArrayDeque<Pair<Long, Float>>()

    val available: Boolean get() = sensor != null

    var pressurePa by mutableStateOf<Float?>(null)
        private set

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        samples.addLast(event.timestamp to event.values[0] * 100f) // hPa -> Pa
        while (event.timestamp - samples.first().first > WINDOW_NS) samples.removeFirst()
        pressurePa = samples.map { it.second }.average().toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val WINDOW_NS = 1_000_000_000L
    }
}
