package com.dinh.aicamera.composition

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.sqrt

class SensorOrientationHelper(
    context: Context,
    private val onOrientationChanged: (roll: Float, pitch: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var smoothedRoll = 0f
    private var smoothedPitch = 0f
    private val smoothingFactor = 0.2f

    fun start() {
        rotationVectorSensor?.let { sensor ->
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)

            // azimuth = orientationAngles[0], pitch = orientationAngles[1], roll = orientationAngles[2]
            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            // Lọc làm mượt (EMA) để tránh rung giật
            smoothedRoll += smoothingFactor * (rollDeg - smoothedRoll)
            smoothedPitch += smoothingFactor * (pitchDeg - smoothedPitch)

            onOrientationChanged(smoothedRoll, smoothedPitch)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
