package com.da4a.smartcity.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Temperature around the phone in °C. Few phones have an air thermometer; the rest report
 * their battery's, which follows the surroundings within minutes but runs warmer from the
 * phone's own heat.
 */
class TemperatureSource(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private var airC: Float? = null

    /** Latest reading, or null when neither the air sensor nor the battery reports one. */
    val celsius: Float? get() = airC ?: BatterySource.temperatureC(context)

    /** True while [celsius] is the air itself rather than the battery. */
    val isAir: Boolean get() = airC != null

    fun start() {
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        airC = event.values[0]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
