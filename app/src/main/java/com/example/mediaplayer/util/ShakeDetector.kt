package com.example.mediaplayer.util

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/**
 * ShakeDetector — used as a SensorEventListener.
 * Register/unregister externally via SensorManager.
 * Usage:
 *   shakeDetector = ShakeDetector { /* on shake */ }
 *   sensorManager.registerListener(shakeDetector, accel, SENSOR_DELAY_UI)
 */
class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    private var lastShakeTime = 0L
    private var lastX = 0f; private var lastY = 0f; private var lastZ = 0f
    private var firstUpdate = true

    companion object {
        private const val SHAKE_THRESHOLD = 1200f
        private const val SHAKE_COOLDOWN  = 800L
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        if (firstUpdate) { lastX = x; lastY = y; lastZ = z; firstUpdate = false; return }

        val dX = x - lastX; val dY = y - lastY; val dZ = z - lastZ
        lastX = x; lastY = y; lastZ = z

        val magnitude = dX * dX + dY * dY + dZ * dZ
        val now = System.currentTimeMillis()
        if (magnitude > SHAKE_THRESHOLD && now - lastShakeTime > SHAKE_COOLDOWN) {
            lastShakeTime = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
