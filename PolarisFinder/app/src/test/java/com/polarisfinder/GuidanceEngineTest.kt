package com.polarisfinder

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GuidanceEngineTest {

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** Rotation of [deg] about the device y axis (yaw). */
    private fun yaw(deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        return floatArrayOf(
            cos(a).toFloat(), 0f, sin(a).toFloat(),
            0f, 1f, 0f,
            (-sin(a)).toFloat(), 0f, cos(a).toFloat(),
        )
    }

    @Test
    fun noMotion_remainingEqualsSeparation() {
        val g = GuidanceEngine(identity, 30.0, 90.0).update(identity)
        assertEquals(30.0, g.remainingDeg, 1e-3)
        assertEquals(90.0, g.arrowDeg, 1e-3) // Polaris to the right of image-up
    }

    @Test
    fun straightAhead_hasZeroRemaining() {
        val g = GuidanceEngine(identity, 0.0, 0.0).update(identity)
        assertEquals(0.0, g.remainingDeg, 1e-3)
    }

    @Test
    fun turningTowardTarget_reducesRemaining() {
        val engine = GuidanceEngine(identity, 30.0, 90.0)
        val before = engine.update(identity).remainingDeg
        // One of the two yaw directions moves the boresight toward the target.
        val a = engine.update(yaw(10.0)).remainingDeg
        val b = engine.update(yaw(-10.0)).remainingDeg
        assertEquals(20.0, minOf(a, b), 1e-3)
        assertEquals(40.0, maxOf(a, b), 1e-3)
        assertEquals(30.0, before, 1e-3)
    }
}
