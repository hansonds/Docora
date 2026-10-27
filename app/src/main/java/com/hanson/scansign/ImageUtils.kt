package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImageUtils {

    /** Loads an image, applies its EXIF rotation, and keeps the longest side under [maxSide]. */
    fun load(file: File, maxSide: Int = 3200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null

        val rotation = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (rotation != 0f) bmp = rotate(bmp, rotation)

        val longest = max(bmp.width, bmp.height)
        if (longest > maxSide) {
            val scale = maxSide.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(
                bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
            )
            if (scaled != bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    fun rotate(src: Bitmap, degrees: Float, recycleSrc: Boolean = true): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (recycleSrc && out != src) src.recycle()
        return out
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92) {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun savePng(bmp: Bitmap, file: File) {
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Small thumbnail for lists. */
    fun thumb(file: File, size: Int = 360): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
