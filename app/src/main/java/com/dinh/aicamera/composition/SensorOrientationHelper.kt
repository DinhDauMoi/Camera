package com.dinh.aicamera.composition

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

class SensorOrientationHelper(
    context: Context,
    private val onOrientationChanged: (roll: Float, pitch: Float, isSteady: Boolean) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationVectorSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private var smoothedRoll = 0f
    private var smoothedPitch = 0f
    private val smoothingFactor = 0.2f

    private var lastRoll = 0f
    private var lastPitch = 0f
    private var isSteady = true

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

            val pitchDeg = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val rollDeg = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            // Kiểm tra tốc độ lia máy để tránh zoom giật hình
            val delta = abs(rollDeg - lastRoll) + abs(pitchDeg - lastPitch)
            isSteady = delta < 1.8f
            lastRoll = rollDeg
            lastPitch = pitchDeg

            // Lọc làm mượt (EMA)
            smoothedRoll += smoothingFactor * (rollDeg - smoothedRoll)
            smoothedPitch += smoothingFactor * (pitchDeg - smoothedPitch)

            onOrientationChanged(smoothedRoll, smoothedPitch, isSteady)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
