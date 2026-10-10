package com.polarisfinder

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure math: turns "Polaris is `sepDeg` away from the image center, `thetaDeg` clockwise
 * from image-up" plus the phone orientation at capture (R0) into a live pointer.
 *
 * Conventions
 * - Rotation matrices are row-major FloatArray(9), as produced by
 *   SensorManager.getRotationMatrixFromVector: v_world = R * v_device.
 * - Device frame: x right, y toward top of screen, z out of screen. Rear camera
 *   boresight is -z. The captured image must be upright (image right = +x, up = +y).
 */
class GuidanceEngine(
    r0: FloatArray,
    sepDeg: Double,
    thetaDeg: Double,
) {
    data class Guidance(
        /** Angle between camera boresight and Polaris, degrees. */
        val remainingDeg: Double,
        /** Direction to turn, degrees clockwise from screen-up. */
        val arrowDeg: Double,
    )

    // Target direction expressed in world coordinates (fixed while the user rotates).
    private val targetWorld: DoubleArray

    init {
        val s = Math.toRadians(sepDeg)
        val t = Math.toRadians(thetaDeg)
        val dDevice = doubleArrayOf(sin(s) * sin(t), sin(s) * cos(t), -cos(s))
        targetWorld = mul(r0, dDevice)
    }

    fun update(r: FloatArray): Guidance {
        val d = mulT(r, targetWorld) // target in the current device frame
        val remaining = Math.toDegrees(acos((-d[2]).coerceIn(-1.0, 1.0)))
        val arrow = (Math.toDegrees(atan2(d[0], d[1])) + 360.0) % 360.0
        return Guidance(remaining, arrow)
    }

    private fun mul(m: FloatArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
    )

    /** Multiply by the transpose (= inverse for a rotation). */
    private fun mulT(m: FloatArray, v: DoubleArray) = doubleArrayOf(
        m[0] * v[0] + m[3] * v[1] + m[6] * v[2],
        m[1] * v[0] + m[4] * v[1] + m[7] * v[2],
        m[2] * v[0] + m[5] * v[1] + m[8] * v[2],
    )
}
