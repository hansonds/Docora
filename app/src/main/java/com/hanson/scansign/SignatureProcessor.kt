package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

enum class InkColor(val label: String, val rgb: Int?) {
    ORIGINAL("Original", null),
    BLACK("Black", Color.rgb(15, 15, 20)),
    BLUE("Blue", Color.rgb(16, 48, 160))
}

/**
 * Turns a photo of a signature on paper into a transparent PNG:
 * flattens lighting, finds the ink, makes the paper transparent and trims the edges.
 */
class SignatureProcessor(src: Bitmap) {

    private val width = src.width
    private val height = src.height
    private val colors = IntArray(width * height)
    /** Lighting-corrected brightness 0..255 (255 = paper). */
    private val level = IntArray(width * height)
    /** Automatic threshold suggestion (Otsu). */
    val autoThreshold: Int

    init {
        src.getPixels(colors, 0, width, 0, 0, width, height)
        var otsu = 160
        if (App.openCvReady) {
            val rgba = Mat()
            Utils.bitmapToMat(src, rgba)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val flat = Scanner.flattenLighting(gray)
            val buf = ByteArray(width * height)
            flat.get(0, 0, buf)
            for (i in buf.indices) level[i] = buf[i].toInt() and 0xFF
            val tmp = Mat()
            otsu = Imgproc.threshold(flat, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU).toInt()
            rgba.release(); gray.release(); flat.release(); tmp.release()
        } else {
            for (i in colors.indices) {
                val c = colors[i]
                level[i] = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            }
        }
        // Otsu tends to sit right in the middle; nudge towards keeping lighter pen strokes
        autoThreshold = (otsu + 20).coerceIn(90, 235)
    }

    /**
     * @param threshold pixels darker than this are ink (higher = keeps more / lighter strokes)
     * @param ink colour to paint the ink, or original pen colour
     * @param boldness 0..1 makes faint ink more opaque
     */
    fun render(threshold: Int, ink: InkColor, boldness: Float = 0.5f): Bitmap {
        val out = IntArray(width * height)
        val soft = max(12f, threshold * 0.30f)
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        val gain = 1f + boldness * 2.5f
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val i = row + x
                val v = level[i]
                if (v >= threshold) continue
                var a = ((threshold - v) / soft) * gain
                if (a > 1f) a = 1f
                if (a < 0.06f) continue
                val alpha = (a * 255).toInt()
                val rgb = ink.rgb ?: darken(colors[i])
                out[i] = (alpha shl 24) or (rgb and 0x00FFFFFF)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) {
            // nothing found: return a tiny transparent bitmap
            return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }
        removeSpecks(out)
        val pad = max(4, min(width, height) / 50)
        val l = max(0, minX - pad)
        val t = max(0, minY - pad)
        val r = min(width - 1, maxX + pad)
        val b = min(height - 1, maxY + pad)
        val full = Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        val cropped = Bitmap.createBitmap(full, l, t, r - l + 1, b - t + 1)
        if (cropped != full) full.recycle()
        return cropped
    }

    /** Keeps the pen colour but pushes it darker and more saturated so it reads well on paper. */
    private fun darken(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[1] = min(1f, hsv[1] * 1.4f)
        hsv[2] = hsv[2] * 0.7f
        return Color.HSVToColor(hsv)
    }

    /** Clears isolated single pixels (paper grain / sensor noise). */
    private fun removeSpecks(px: IntArray) {
        if (width < 3 || height < 3) return
        val copy = px.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if (copy[i] == 0) continue
                var n = 0
                if (copy[i - 1] != 0) n++
                if (copy[i + 1] != 0) n++
                if (copy[i - width] != 0) n++
                if (copy[i + width] != 0) n++
                if (copy[i - width - 1] != 0) n++
                if (copy[i - width + 1] != 0) n++
                if (copy[i + width - 1] != 0) n++
                if (copy[i + width + 1] != 0) n++
                if (n <= 1) px[i] = 0
            }
        }
    }
}
