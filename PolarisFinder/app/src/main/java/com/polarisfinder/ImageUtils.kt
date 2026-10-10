package com.polarisfinder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

object ImageUtils {
    private const val MAX_SIDE = 2048

    /**
     * Writes an upright, downscaled copy of the camera JPEG to [dst].
     *
     * astrometry.net reads raw pixels and ignores EXIF orientation, so we bake the rotation in.
     * With the app locked to portrait, "upright" means image-up == device +y and
     * image-right == device +x, which is what GuidanceEngine assumes.
     */
    fun makeUpright(src: File, dst: File) {
        val orientation = ExifInterface(src.path)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val raw = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not decode photo")

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val scale = MAX_SIDE.toFloat() / maxOf(raw.width, raw.height)
        if (scale < 1f) m.postScale(scale, scale)

        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        dst.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (out !== raw) raw.recycle()
        out.recycle()
    }
}
