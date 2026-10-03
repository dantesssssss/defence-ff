package com.da4a.smartcity.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.atan2

/**
 * Direction the user is facing, in degrees clockwise (0..360).
 *
 * [magneticDeg] is relative to magnetic north. [relativeDeg] comes from the gyro-based game
 * rotation vector: its zero is arbitrary, but it is not disturbed by steel and wiring indoors,
 * so it is the one to use for comparing headings within a short time.
 */
class HeadingSource(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val magnetic: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val game: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotation = FloatArray(9)

    var magneticDeg by mutableStateOf<Float?>(null)
        private set

    var relativeDeg by mutableStateOf<Float?>(null)
        private set

    fun start() {
        magnetic?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        game?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Forward = top edge of the phone plus its back, projected on the ground. This stays
        // stable whether the phone lies flat in the hand or is held upright against the chest.
        val east = rotation[1] - rotation[2]
        val north = rotation[4] - rotation[5]
        val deg = normalizeDeg(Math.toDegrees(atan2(east, north).toDouble()).toFloat())
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            magneticDeg = deg
            if (game == null) relativeDeg = deg
        } else {
            relativeDeg = deg
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        fun normalizeDeg(deg: Float): Float = ((deg % 360f) + 360f) % 360f
    }
}
