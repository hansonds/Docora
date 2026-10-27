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
        Utils.bitmapToMat(small, rgba)
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val imgArea = gray.rows().toDouble() * gray.cols()

        val edgeMaps = ArrayList<Mat>()
        try {
            // 1) soft Canny
            val b1 = Mat()
            Imgproc.GaussianBlur(gray, b1, Size(5.0, 5.0), 0.0)
            val e1 = Mat()
            Imgproc.Canny(b1, e1, 30.0, 100.0)
            Imgproc.dilate(e1, e1, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)))
            edgeMaps.add(e1)

            // 2) strong blur + hard Canny (ignores text inside the page)
            val b2 = Mat()
            Imgproc.medianBlur(gray, b2, 9)
            val e2 = Mat()
            Imgproc.Canny(b2, e2, 60.0, 180.0)
            Imgproc.morphologyEx(
                e2, e2, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            )
            edgeMaps.add(e2)

            // 3) brightness threshold (white paper on a darker table)
            val b3 = Mat()
            Imgproc.GaussianBlur(gray, b3, Size(7.0, 7.0), 0.0)
            val t3 = Mat()
            Imgproc.threshold(b3, t3, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(
                t3, t3, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            )
            edgeMaps.add(t3)
            b1.release(); b2.release(); b3.release()

            var best: Array<Point>? = null
            var bestArea = 0.0
            // edge-based maps are the most reliable; the brightness map is only a fallback
            for (edges in edgeMaps.take(2)) {
                val quad = largestQuad(edges, imgArea) ?: continue
                val area = abs(Imgproc.contourArea(MatOfPoint(*quad)))
                // a quad that is basically the whole frame is a weak signal; prefer real pages
                val penalty = if (area > imgArea * 0.97) 0.5 else 1.0
                if (area * penalty > bestArea) {
                    bestArea = area * penalty
                    best = quad
                }
            }
            if (best == null) best = largestQuad(edgeMaps[2], imgArea)
            val found = best ?: return null
            return orderPoints(found.map { PointF((it.x * ratio).toFloat(), (it.y * ratio).toFloat()) })
                .map { PointF(it.x.coerceIn(0f, bmp.width.toFloat()), it.y.coerceIn(0f, bmp.height.toFloat())) }
        } catch (e: Exception) {
            return null
        } finally {
            rgba.release(); gray.release()
            edgeMaps.forEach { it.release() }
            if (small != bmp) small.recycle()
        }
    }

    private fun largestQuad(edges: Mat, imgArea: Double): Array<Point>? {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges.clone(), contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }.take(8)
        for (c in sorted) {
            if (Imgproc.contourArea(c) < imgArea * 0.12) break
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                return approx.toArray()
            }
            // fall back to the convex hull, simplified progressively until it has 4 corners
            val hullIdx = MatOfInt()
            Imgproc.convexHull(c, hullIdx)
            val pts = c.toArray()
            val hullPts = hullIdx.toArray().map { pts[it] }.toTypedArray()
            if (hullPts.size >= 4) {
                val hull2f = MatOfPoint2f(*hullPts)
                val hp = Imgproc.arcLength(hull2f, true)
                var eps = 0.02
                while (eps <= 0.1) {
                    val a2 = MatOfPoint2f()
                    Imgproc.approxPolyDP(hull2f, a2, eps * hp, true)
                    if (a2.total() == 4L) {
                        val area = abs(Imgproc.contourArea(MatOfPoint(*a2.toArray())))
                        if (area > imgArea * 0.12) return a2.toArray()
                        break
                    }
                    if (a2.total() < 4L) break
                    eps += 0.01
                }
            }
        }
        return null
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
