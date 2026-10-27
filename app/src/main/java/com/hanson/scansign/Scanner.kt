package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class ScanFilter(val label: String) {
    AUTO("Auto"),
    ORIGINAL("Original"),
    MAGIC("Magic color"),
    GRAY("Grayscale"),
    BW("Black & white")
}

/** Edge detection, perspective correction and page filters (OpenCV). */
object Scanner {

    /** Default crop when nothing is detected: 4% inset. Order TL, TR, BR, BL. */
    fun defaultQuad(w: Int, h: Int): List<PointF> {
        val dx = w * 0.04f
        val dy = h * 0.04f
        return listOf(
            PointF(dx, dy), PointF(w - dx, dy), PointF(w - dx, h - dy), PointF(dx, h - dy)
        )
    }

    fun fullQuad(w: Int, h: Int): List<PointF> =
        listOf(PointF(0f, 0f), PointF(w.toFloat(), 0f), PointF(w.toFloat(), h.toFloat()), PointF(0f, h.toFloat()))

    /** Finds the document's 4 corners in [bmp] coordinates, or null if nothing convincing is found. */
    fun detect(bmp: Bitmap): List<PointF>? {
        if (!App.openCvReady) return null
        val maxSide = 720.0
        val scale = maxSide / max(bmp.width, bmp.height)
        val small = if (scale < 1.0) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt(), (bmp.height * scale).roundToInt(), true)
        } else bmp
        val ratio = if (scale < 1.0) 1.0 / scale else 1.0
        val rgba = Mat()
        val gray = Mat()
        val rgb = Mat()
        val hsv = Mat()
        val sat = Mat()
        try {
            Utils.bitmapToMat(small, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
            Core.extractChannel(hsv, sat, 1)
            val q = Detector.detect(gray, sat) ?: return null
            val found = List(4) { PointF(q[it * 2].toFloat(), q[it * 2 + 1].toFloat()) }
            return orderPoints(found.map { PointF((it.x * ratio).toFloat(), (it.y * ratio).toFloat()) })
                .map { PointF(it.x.coerceIn(0f, bmp.width.toFloat()), it.y.coerceIn(0f, bmp.height.toFloat())) }
        } catch (e: Throwable) {
            return null
        } finally {
            rgba.release(); gray.release(); rgb.release(); hsv.release(); sat.release()
            if (small != bmp) small.recycle()
        }
    }

    /** Orders 4 points as top-left, top-right, bottom-right, bottom-left. */
    fun orderPoints(p: List<PointF>): List<PointF> {
        val tl = p.minByOrNull { it.x + it.y }!!
        val br = p.maxByOrNull { it.x + it.y }!!
        val tr = p.maxByOrNull { it.x - it.y }!!
        val bl = p.minByOrNull { it.x - it.y }!!
        val set = setOf(tl, tr, br, bl)
        if (set.size == 4) return listOf(tl, tr, br, bl)
        // degenerate case: sort by angle around the centre
        val cx = p.map { it.x }.average().toFloat()
        val cy = p.map { it.y }.average().toFloat()
        val byAngle = p.sortedBy { Math.atan2((it.y - cy).toDouble(), (it.x - cx).toDouble()) }
        // atan2 order starts at the left (-pi); rotate so the top-left-most comes first
        val start = byAngle.indices.minByOrNull { byAngle[it].x + byAngle[it].y } ?: 0
        return List(4) { byAngle[(start + it) % 4] }
    }

    /** Perspective-corrects [src] using the quad (TL, TR, BR, BL) in bitmap coordinates. */
    fun warp(src: Bitmap, quad: List<PointF>): Bitmap {
        val q = orderPoints(quad)
        val (tl, tr, br, bl) = q
        val wTop = hypot((tr.x - tl.x).toDouble(), (tr.y - tl.y).toDouble())
        val wBot = hypot((br.x - bl.x).toDouble(), (br.y - bl.y).toDouble())
        val hL = hypot((bl.x - tl.x).toDouble(), (bl.y - tl.y).toDouble())
        val hR = hypot((br.x - tr.x).toDouble(), (br.y - tr.y).toDouble())
        val w = max(wTop, wBot).roundToInt().coerceAtLeast(10)
        val h = max(hL, hR).roundToInt().coerceAtLeast(10)

        if (!App.openCvReady) {
            // no OpenCV: simple rectangular crop of the bounding box
            val l = q.minOf { it.x }.toInt().coerceIn(0, src.width - 1)
            val t = q.minOf { it.y }.toInt().coerceIn(0, src.height - 1)
            val r = q.maxOf { it.x }.toInt().coerceIn(l + 1, src.width)
            val b = q.maxOf { it.y }.toInt().coerceIn(t + 1, src.height)
            return Bitmap.createBitmap(src, l, t, r - l, b - t)
        }

        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)
        val from = MatOfPoint2f(
            Point(tl.x.toDouble(), tl.y.toDouble()), Point(tr.x.toDouble(), tr.y.toDouble()),
            Point(br.x.toDouble(), br.y.toDouble()), Point(bl.x.toDouble(), bl.y.toDouble())
        )
        val to = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0), Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(srcMat, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(out, bmp)
        srcMat.release(); out.release(); m.release()
        return bmp
    }

    /** Applies a page filter and returns a new bitmap. */
    fun applyFilter(src: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.ORIGINAL || !App.openCvReady) return src.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val result = Mat()
        when (filter) {
            ScanFilter.GRAY -> {
                val g = Mat()
                Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                // mild contrast stretch
                Core.normalize(g, g, 0.0, 255.0, Core.NORM_MINMAX)
                Imgproc.cvtColor(g, result, Imgproc.COLOR_GRAY2RGBA)
                g.release()
            }
            ScanFilter.BW -> {
                val g = Mat()
                Imgproc.cvtColor(rgba, g, Imgproc.COLOR_RGBA2GRAY)
                val flat = flattenLighting(g)
                val bw = Mat()
                val block = (max(src.width, src.height) / 40).let { if (it % 2 == 0) it + 1 else it }.coerceIn(15, 71)
                Imgproc.adaptiveThreshold(
                    flat, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 12.0
                )
                Imgproc.medianBlur(bw, bw, 3)
                Imgproc.cvtColor(bw, result, Imgproc.COLOR_GRAY2RGBA)
                g.release(); flat.release(); bw.release()
            }
            ScanFilter.AUTO -> {
                // shadows removed + contrast, then colour kept only if the page really has colour
                val magic = magicColor(rgba)
                if (isColourful(rgba)) {
                    Imgproc.cvtColor(magic, result, Imgproc.COLOR_RGB2RGBA)
                } else {
                    val g = Mat()
                    Imgproc.cvtColor(magic, g, Imgproc.COLOR_RGB2GRAY)
                    // gentle sharpening for crisp text
                    val blur = Mat()
                    Imgproc.GaussianBlur(g, blur, Size(0.0, 0.0), 1.2)
                    Core.addWeighted(g, 1.5, blur, -0.5, 0.0, g)
                    Imgproc.cvtColor(g, result, Imgproc.COLOR_GRAY2RGBA)
                    g.release(); blur.release()
                }
                magic.release()
            }
            ScanFilter.MAGIC -> {
                val rgb = Mat()
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                val channels = ArrayList<Mat>()
                Core.split(rgb, channels)
                val outCh = ArrayList<Mat>()
                for (ch in channels) {
                    outCh.add(flattenLighting(ch))
                    ch.release()
                }
                val merged = Mat()
                Core.merge(outCh, merged)
                // a touch more contrast / saturation
                merged.convertTo(merged, -1, 1.15, -18.0)
                Imgproc.cvtColor(merged, result, Imgproc.COLOR_RGB2RGBA)
                outCh.forEach { it.release() }
                rgb.release(); merged.release()
            }
            ScanFilter.ORIGINAL -> rgba.copyTo(result)
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, out)
        rgba.release(); result.release()
        return out
    }

    /** Magic colour: lighting flattened per channel plus a little contrast. Returns an RGB Mat. */
    private fun magicColor(rgba: Mat): Mat {
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        val channels = ArrayList<Mat>()
        Core.split(rgb, channels)
        val outCh = channels.map { ch -> flattenLighting(ch).also { ch.release() } }
        val merged = Mat()
        Core.merge(outCh, merged)
        merged.convertTo(merged, -1, 1.15, -18.0)
        outCh.forEach { it.release() }
        rgb.release()
        return merged
    }

    /** True if more than ~1.2% of the page has noticeable colour (stamps, photos, coloured ink, logos). */
    private fun isColourful(rgba: Mat): Boolean {
        val small = Mat()
        val scale = 400.0 / max(rgba.cols(), rgba.rows())
        Imgproc.resize(rgba, small, Size(rgba.cols() * scale, rgba.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = Mat(); Imgproc.cvtColor(small, rgb, Imgproc.COLOR_RGBA2RGB)
        val hsv = Mat(); Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = ArrayList<Mat>(); Core.split(hsv, ch)
        val satMask = Mat(); Imgproc.threshold(ch[1], satMask, 70.0, 255.0, Imgproc.THRESH_BINARY)
        val valMask = Mat(); Imgproc.threshold(ch[2], valMask, 60.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_and(satMask, valMask, satMask)
        val frac = Core.countNonZero(satMask).toDouble() / (satMask.rows() * satMask.cols()).coerceAtLeast(1)
        listOf(small, rgb, hsv, satMask, valMask).forEach { it.release() }
        ch.forEach { it.release() }
        return frac > 0.012
    }

    /**
     * Removes shadows / uneven lighting from a single-channel image:
     * estimates the paper background and divides it out so paper becomes white.
     */
    fun flattenLighting(gray: Mat): Mat {
        val k = (max(gray.cols(), gray.rows()) / 60).coerceIn(7, 41)
        val bg = Mat()
        Imgproc.dilate(gray, bg, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble())))
        val med = if (k % 2 == 0) k + 1 else k
        Imgproc.medianBlur(bg, bg, med.coerceAtMost(255))
        val g32 = Mat()
        val b32 = Mat()
        gray.convertTo(g32, CvType.CV_32F)
        bg.convertTo(b32, CvType.CV_32F)
        Core.max(b32, org.opencv.core.Scalar(1.0), b32)
        val div = Mat()
        Core.divide(g32, b32, div, 255.0)
        val out = Mat()
        div.convertTo(out, CvType.CV_8U)
        bg.release(); g32.release(); b32.release(); div.release()
        return out
    }
}
