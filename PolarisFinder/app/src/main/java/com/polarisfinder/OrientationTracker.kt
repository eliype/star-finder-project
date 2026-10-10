package com.polarisfinder

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Publishes the phone's orientation as a rotation matrix (row-major, v_world = R * v_device)
 * plus its current angular speed.
 *
 * Preferred source: TYPE_GAME_ROTATION_VECTOR (gyro + accelerometer fused, no magnetometer).
 * Fallback: integrate the raw gyroscope (drifts, but works on every phone with a gyro).
 */
class OrientationTracker(context: Context) : SensorEventListener {

    data class Sample(val rotation: FloatArray, val rateDegPerSec: Double, val tNanos: Long)

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available: Boolean get() = rotationVector != null || gyro != null

    private val _samples = MutableStateFlow<Sample?>(null)
    val samples: StateFlow<Sample?> = _samples

    private var current = identity()
    private var lastNanos = 0L

    fun start() {
        lastNanos = 0L
        _samples.value = null
        val sensor = rotationVector ?: gyro ?: return
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sm.unregisterListener(this)

    /** Latest orientation (copy), or null if no sensor data has arrived yet. */
    fun latest(): FloatArray? = _samples.value?.rotation?.copyOf()

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        val dt = if (lastNanos == 0L) 0.0 else (event.timestamp - lastNanos) / 1e9
        lastNanos = event.timestamp

        val rate: Double
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            val next = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(next, event.values)
            rate = if (dt > 0) angleBetween(current, next) / dt else 0.0
            current = next
        } else {
            val wx = event.values[0].toDouble()
            val wy = event.values[1].toDouble()
            val wz = event.values[2].toDouble()
            val w = sqrt(wx * wx + wy * wy + wz * wz)
            rate = Math.toDegrees(w)
            if (dt > 0 && w > 1e-9) current = multiply(current, axisAngle(wx / w, wy / w, wz / w, w * dt))
        }
        _samples.value = Sample(current.copyOf(), rate, event.timestamp)
    }

    private companion object {
        fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val r = FloatArray(9)
            for (i in 0 until 3) for (j in 0 until 3) {
                var s = 0f
                for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
                r[i * 3 + j] = s
            }
            return r
        }

        /** Rodrigues' rotation formula. */
        fun axisAngle(x: Double, y: Double, z: Double, angle: Double): FloatArray {
            val c = cos(angle)
            val s = sin(angle)
            val t = 1 - c
            return floatArrayOf(
                (t * x * x + c).toFloat(), (t * x * y - s * z).toFloat(), (t * x * z + s * y).toFloat(),
                (t * x * y + s * z).toFloat(), (t * y * y + c).toFloat(), (t * y * z - s * x).toFloat(),
                (t * x * z - s * y).toFloat(), (t * y * z + s * x).toFloat(), (t * z * z + c).toFloat(),
            )
        }

        /** Rotation angle (degrees) of Ra^T * Rb. */
        fun angleBetween(a: FloatArray, b: FloatArray): Double {
            var trace = 0.0
            for (i in 0 until 3) for (k in 0 until 3) trace += a[k * 3 + i] * b[k * 3 + i]
            return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
        }
    }
}
