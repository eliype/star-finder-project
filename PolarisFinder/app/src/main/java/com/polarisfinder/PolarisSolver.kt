package com.polarisfinder

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.util.function.Consumer

/** Result of polaris_offset() in polaris_solver.py. Image frame: x right, y up (FITS). */
data class PolarisOffset(
    val sepDeg: Double,
    val thetaDeg: Double,
    val inside: Boolean,
    val pixelX: Double,
    val pixelY: Double,
    val width: Int,
    val height: Int,
    val circleRadiusPx: Double,
)

/** Thin Kotlin wrapper around the Chaquopy-hosted Python module. Call from a background thread. */
class PolarisSolver(context: Context) {
    init {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
    }

    private val module get() = Python.getInstance().getModule("polaris_solver")

    /** Uploads [imagePath] to astrometry.net and writes the solved FITS to [outFitsPath]. */
    fun solve(imagePath: String, apiKey: String, outFitsPath: String, onProgress: (String) -> Unit) {
        module.callAttr("solve", imagePath, apiKey, outFitsPath, 600, Consumer<String> { onProgress(it) })
    }

    fun offset(fitsPath: String): PolarisOffset {
        val j = JSONObject(module.callAttr("polaris_offset_json", fitsPath).toString())
        return PolarisOffset(
            sepDeg = j.getDouble("sep_deg"),
            thetaDeg = j.getDouble("theta_deg"),
            inside = j.getBoolean("inside"),
            pixelX = j.getDouble("pixel_x"),
            pixelY = j.getDouble("pixel_y"),
            width = j.getInt("width"),
            height = j.getInt("height"),
            circleRadiusPx = j.getDouble("circle_radius_px"),
        )
    }
}
