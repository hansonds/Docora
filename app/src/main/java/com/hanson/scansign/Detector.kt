package com.hanson.scansign

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Document edge detector.
 *
 * 1. Many candidate quadrilaterals are generated: contours of several edge maps (fine, texture-blurred,
 *    "white paper" colour mask, saturation edges) plus combinations of 4 straight lines (Hough).
 * 2. Each candidate is scored mainly by whether, along every side, the inside is consistently brighter
 *    or less colourful than the outside (true paper borders), which rejects busy backgrounds like
 *    striped bedsheets, plus size, shape and paper whiteness.
 * 3. Below a confidence threshold nothing is returned (better no outline than a wrong one).
 *
 * Tuned on real phone photos and a 63-image benchmark (striped fabric, wood, desks, plain backgrounds).
 */
object Detector {

    private const val MIN_AREA = 0.08
    private const val MIN_SCORE = 1.4

    /** Quad as x0,y0,x1,y1,x2,y2,x3,y3 in TL, TR, BR, BL order. */
    private class Cand(val q: DoubleArray, val pre: Double, val area: Double, val maxCos: Double, val edge: Double, val frame: Boolean)

    /**
     * @param gray 8-bit grayscale, ideally 400-800 px on the long side
     * @param sat  8-bit saturation (or chroma) of the same size
     * @return corners in pixel coordinates (TL, TR, BR, BL) or null
     */
    fun detect(gray: Mat, sat: Mat, perGroup: Int = 12): DoubleArray? {
        val w = gray.cols()
        val h = gray.rows()
        val area = w.toDouble() * h
        val mats = ArrayList<Mat>()
        fun m(): Mat = Mat().also { mats.add(it) }
        fun k(n: Int) = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(n.toDouble(), n.toDouble())).also { mats.add(it) }

        try {
            val gs = m(); Imgproc.medianBlur(gray, gs, 5)
            val ss = m(); Imgproc.medianBlur(sat, ss, 5)
            val cands = ArrayList<DoubleArray>()

            // A: fine edges
            val b1 = m(); Imgproc.GaussianBlur(gray, b1, Size(5.0, 5.0), 0.0)
            val e1 = m(); Imgproc.Canny(b1, e1, 30.0, 100.0)
            Imgproc.dilate(e1, e1, k(3))
            contourCands(e1, area, cands)

            // B: texture removed (fabric, wood grain, text)
            for (kk in intArrayOf(15, 25)) {
                val mb = m(); Imgproc.medianBlur(gray, mb, kk)
                val e = m(); Imgproc.Canny(mb, e, 20.0, 60.0)
                Imgproc.morphologyEx(e, e, Imgproc.MORPH_CLOSE, k(5))
                contourCands(e, area, cands)
            }

            // C: white paper colour mask (bright and not colourful)
            val vm = m(); Imgproc.medianBlur(gray, vm, 15)
            val sm = m(); Imgproc.medianBlur(sat, sm, 15)
            val tmp = m()
            val otsu = Imgproc.threshold(vm, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val bright = m(); Imgproc.threshold(vm, bright, max(otsu, 90.0) - 1.0, 255.0, Imgproc.THRESH_BINARY)
            for (st in intArrayOf(50, 80)) {
                val low = m(); Imgproc.threshold(sm, low, st - 1.0, 255.0, Imgproc.THRESH_BINARY_INV)
                val mask = m(); Core.bitwise_and(bright, low, mask)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, k(9))
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, k(15))
                contourCands(mask, area, cands)
            }

            // D: saturation edges (white paper on coloured cloth)
            val es = m(); Imgproc.Canny(sm, es, 20.0, 60.0)
            Imgproc.morphologyEx(es, es, Imgproc.MORPH_CLOSE, k(5))
            contourCands(es, area, cands)

            // E: straight-line combinations
            for (kk in intArrayOf(5, 15)) {
                val a = m(); Imgproc.medianBlur(gs, a, kk)
                val b = m(); Imgproc.medianBlur(ss, b, kk)
                val ea = m(); Imgproc.Canny(a, ea, 15.0, 45.0)
                val eb = m(); Imgproc.Canny(b, eb, 15.0, 45.0)
                Core.bitwise_or(ea, eb, ea)
                houghCands(ea, w, h, perGroup, cands)
            }

            // ---- scoring ----
            val g = ByteArray(w * h); gs.get(0, 0, g)
            val s = ByteArray(w * h); ss.get(0, 0, s)
            val pre = ArrayList<Cand>()
            for (raw in cands) {
                val q = order(raw)
                if (!convex(q)) continue
                val a = polyArea(q) / area
                if (a < MIN_AREA || a > 0.98) continue
                val mc = maxCos(q)
                if (mc > 0.5) continue
                val e = edgeScore(sideSteps(q, g, s, w, h)) ?: continue
                val fl = frameLike(q, w, h)
                val p = e * 1.6 + a * 0.5 - 0.15 * mc - (if (fl) 0.3 else 0.0)
                pre.add(Cand(q, p, a, mc, e, fl))
            }
            pre.sortByDescending { it.pre }
            var best: DoubleArray? = null
            var bestScore = MIN_SCORE
            for (c in pre.take(8)) {
                val (gi, si) = interior(c.q, g, s, w, h)
                val con = outsideContrast(c.q, g, w, h)
                val whiteness = gi / 255.0 - si / 255.0
                val sc = c.edge * 1.6 + c.area * 0.5 + max(0.0, con) * 0.6 + whiteness * 0.3 -
                    0.15 * c.maxCos - (if (c.frame) 0.3 else 0.0)
                if (sc > bestScore) { bestScore = sc; best = c.q }
            }
            return best
        } catch (t: Throwable) {
            return null
        } finally {
            mats.forEach { it.release() }
        }
    }

    // ---------------- candidates ----------------

    private fun contourCands(mask: Mat, area: Double, out: MutableList<DoubleArray>) {
        for (mode in intArrayOf(Imgproc.RETR_EXTERNAL, Imgproc.RETR_LIST)) {
            val contours = ArrayList<MatOfPoint>()
            val hier = Mat()
            val copy = mask.clone()
            Imgproc.findContours(copy, contours, hier, mode, Imgproc.CHAIN_APPROX_SIMPLE)
            copy.release(); hier.release()
            val top = contours.map { Pair(it, Imgproc.contourArea(it)) }.sortedByDescending { it.second }.take(5)
            for ((c, a) in top) quadFromContour(c, a, area)?.let { out.add(it) }
            contours.forEach { it.release() }
        }
    }

    private fun toArr(pts: Array<Point>): DoubleArray =
        DoubleArray(8) { i -> if (i % 2 == 0) pts[i / 2].x else pts[i / 2].y }

    private fun quadFromContour(c: MatOfPoint, a: Double, areaImg: Double): DoubleArray? {
        if (a < areaImg * MIN_AREA) return null
        val c2f = MatOfPoint2f(*c.toArray())
        val peri = Imgproc.arcLength(c2f, true)
        for (eps in doubleArrayOf(0.02, 0.03, 0.04, 0.05, 0.06, 0.08)) {
            val ap = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, ap, eps * peri, true)
            val n = ap.total()
            if (n == 4L && Imgproc.isContourConvex(MatOfPoint(*ap.toArray()))) return toArr(ap.toArray())
            if (n < 4L) break
        }
        val hullIdx = MatOfInt()
        Imgproc.convexHull(c, hullIdx)
        val pts = c.toArray()
        val hull = hullIdx.toArray().map { pts[it] }.toTypedArray()
        if (hull.size >= 4) {
            val h2f = MatOfPoint2f(*hull)
            val hp = Imgproc.arcLength(h2f, true)
            for (eps in doubleArrayOf(0.02, 0.03, 0.04, 0.05, 0.06, 0.08, 0.1)) {
                val ap = MatOfPoint2f()
                Imgproc.approxPolyDP(h2f, ap, eps * hp, true)
                val n = ap.total()
                if (n == 4L) return toArr(ap.toArray())
                if (n < 4L) break
            }
        }
        val rr: RotatedRect = Imgproc.minAreaRect(c2f)
        if (a / max(1.0, rr.size.width * rr.size.height) > 0.85) {
            val box = Array(4) { Point() }
            rr.points(box)
            return toArr(box)
        }
        return null
    }

    private fun houghCands(edges: Mat, w: Int, h: Int, perGroup: Int, out: MutableList<DoubleArray>) {
        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, PI / 180, (min(w, h) * 0.15).toInt())
        val keep = ArrayList<DoubleArray>()
        val n = min(lines.rows(), 150)
        for (i in 0 until n) {
            val l = lines.get(i, 0) ?: continue
            val rho = l[0]
            val th = l[1]
            val dup = keep.any { abs(rho - it[0]) < 12 && min(abs(th - it[1]), PI - abs(th - it[1])) < 4 * PI / 180 }
            if (!dup) keep.add(doubleArrayOf(rho, th))
        }
        lines.release()
        val hl = keep.filter { abs(sin(it[1])) > abs(cos(it[1])) }.take(perGroup)
        val vl = keep.filter { abs(sin(it[1])) <= abs(cos(it[1])) }.take(perGroup)

        fun inter(a: DoubleArray, b: DoubleArray): DoubleArray? {
            val c1 = cos(a[1]); val s1 = sin(a[1]); val c2 = cos(b[1]); val s2 = sin(b[1])
            val det = c1 * s2 - s1 * c2
            if (abs(det) < 1e-6) return null
            return doubleArrayOf((a[0] * s2 - b[0] * s1) / det, (c1 * b[0] - c2 * a[0]) / det)
        }
        for (i in hl.indices) for (j in i + 1 until hl.size) for (k in vl.indices) for (mm in k + 1 until vl.size) {
            val pts = arrayOf(inter(hl[i], vl[k]), inter(hl[i], vl[mm]), inter(hl[j], vl[mm]), inter(hl[j], vl[k]))
            if (pts.any { it == null }) continue
            var ok = true
            val q = DoubleArray(8)
            for (p in 0..3) {
                val x = pts[p]!![0]; val y = pts[p]!![1]
                if (x < -0.05 * w || x > 1.05 * w || y < -0.05 * h || y > 1.05 * h) { ok = false; break }
                q[p * 2] = x.coerceIn(0.0, w - 1.0)
                q[p * 2 + 1] = y.coerceIn(0.0, h - 1.0)
            }
            if (ok) out.add(q)
        }
    }

    // ---------------- geometry ----------------

    /** TL, TR, BR, BL by sum / difference of coordinates. */
    private fun order(q: DoubleArray): DoubleArray {
        var tl = 0; var tr = 0; var br = 0; var bl = 0
        for (i in 1..3) {
            val sI = q[i * 2] + q[i * 2 + 1]; val dI = q[i * 2] - q[i * 2 + 1]
            if (sI < q[tl * 2] + q[tl * 2 + 1]) tl = i
            if (sI > q[br * 2] + q[br * 2 + 1]) br = i
            if (dI > q[tr * 2] - q[tr * 2 + 1]) tr = i
            if (dI < q[bl * 2] - q[bl * 2 + 1]) bl = i
        }
        return doubleArrayOf(q[tl * 2], q[tl * 2 + 1], q[tr * 2], q[tr * 2 + 1], q[br * 2], q[br * 2 + 1], q[bl * 2], q[bl * 2 + 1])
    }

    private fun polyArea(q: DoubleArray): Double {
        var a = 0.0
        for (i in 0..3) {
            val n = (i + 1) % 4
            a += q[i * 2] * q[n * 2 + 1] - q[n * 2] * q[i * 2 + 1]
        }
        return abs(a) / 2
    }

    private fun convex(q: DoubleArray): Boolean {
        var sgn = 0
        for (i in 0..3) {
            val a = i; val b = (i + 1) % 4; val c = (i + 2) % 4
            val cr = (q[b * 2] - q[a * 2]) * (q[c * 2 + 1] - q[b * 2 + 1]) - (q[b * 2 + 1] - q[a * 2 + 1]) * (q[c * 2] - q[b * 2])
            if (abs(cr) < 1e-6) return false
            val sg = if (cr > 0) 1 else -1
            if (sgn == 0) sgn = sg else if (sg != sgn) return false
        }
        return true
    }

    private fun maxCos(q: DoubleArray): Double {
        var m = 0.0
        for (i in 0..3) {
            val p0 = (i + 3) % 4; val p2 = (i + 1) % 4
            val v1x = q[p0 * 2] - q[i * 2]; val v1y = q[p0 * 2 + 1] - q[i * 2 + 1]
            val v2x = q[p2 * 2] - q[i * 2]; val v2y = q[p2 * 2 + 1] - q[i * 2 + 1]
            val c = abs((v1x * v2x + v1y * v2y) / (hypot(v1x, v1y) * hypot(v2x, v2y) + 1e-6))
            m = max(m, c)
        }
        return m
    }

    private fun frameLike(q: DoubleArray, w: Int, h: Int): Boolean {
        var n = 0
        for (i in 0..3) {
            val x = q[i * 2]; val y = q[i * 2 + 1]
            if (x < w * 0.04 || x > w * 0.96 || y < h * 0.04 || y > h * 0.96) n++
        }
        return n >= 3
    }

    // ---------------- scoring ----------------

    private fun px(a: ByteArray, w: Int, x: Int, y: Int): Int = a[y * w + x].toInt() and 0xFF

    /** Inward normal of side i (from corner i to corner i+1). */
    private fun normal(q: DoubleArray, i: Int): DoubleArray {
        val n = (i + 1) % 4
        val px0 = q[i * 2]; val py0 = q[i * 2 + 1]
        val dx = q[n * 2] - px0; val dy = q[n * 2 + 1] - py0
        val len = hypot(dx, dy) + 1e-6
        var nx = -dy / len; var ny = dx / len
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
        val mx = px0 + dx / 2; val my = py0 + dy / 2
        if ((cx - mx) * nx + (cy - my) * ny < 0) { nx = -nx; ny = -ny }
        return doubleArrayOf(px0, py0, dx, dy, nx, ny)
    }

    /** Per side: how consistently the inside is brighter / less colourful than the outside (-1 = unknown). */
    private fun sideSteps(q: DoubleArray, g: ByteArray, s: ByteArray, w: Int, h: Int): DoubleArray {
        val res = DoubleArray(4)
        val steps = IntArray(48)
        for (i in 0..3) {
            val nm = normal(q, i)
            val px0 = nm[0]; val py0 = nm[1]; val dx = nm[2]; val dy = nm[3]; val nx = nm[4]; val ny = nm[5]
            var best = -1.0
            for (off in intArrayOf(5, 9)) {
                var cnt = 0
                for (k in 0 until 48) {
                    val t = 0.08 + 0.84 * k / 47
                    val bx = px0 + t * dx; val by = py0 + t * dy
                    val ix = (bx + nx * off).toInt(); val iy = (by + ny * off).toInt()
                    val ox = (bx - nx * off).toInt(); val oy = (by - ny * off).toInt()
                    if (ix in 0 until w && iy in 0 until h && ox in 0 until w && oy in 0 until h) {
                        val db = px(g, w, ix, iy) - px(g, w, ox, oy)
                        val ds = px(s, w, ox, oy) - px(s, w, ix, iy)
                        steps[cnt++] = max(db, ds)
                    }
                }
                if (cnt < 8) continue
                val sorted = steps.copyOf(cnt).also { it.sort() }
                val med = sorted[cnt / 2]
                val strong = sorted.count { it > 12 }
                val v = med / 255.0 + 0.5 * strong / cnt
                best = max(best, v)
            }
            res[i] = best
        }
        return res
    }

    private fun edgeScore(st: DoubleArray): Double? {
        val known = st.count { it > -1 }
        if (known < 2) return null
        val vals = st.map { if (it > -1) it else 0.3 }
        return 0.6 * vals.sum() / 4 + 0.8 * vals.min()
    }

    private fun interior(q: DoubleArray, g: ByteArray, s: ByteArray, w: Int, h: Int): Pair<Double, Double> {
        var gb = 0.0; var sb = 0.0; var n = 0
        for (a in 0 until 12) for (b in 0 until 12) {
            val u = 0.1 + 0.8 * a / 11; val v = 0.1 + 0.8 * b / 11
            val tx = q[0] + (q[2] - q[0]) * u; val ty = q[1] + (q[3] - q[1]) * u
            val bx = q[6] + (q[4] - q[6]) * u; val by = q[7] + (q[5] - q[7]) * u
            val x = (tx + (bx - tx) * v).toInt(); val y = (ty + (by - ty) * v).toInt()
            if (x in 0 until w && y in 0 until h) { gb += px(g, w, x, y); sb += px(s, w, x, y); n++ }
        }
        return if (n == 0) Pair(0.0, 0.0) else Pair(gb / n, sb / n)
    }

    private fun outsideContrast(q: DoubleArray, g: ByteArray, w: Int, h: Int): Double {
        var tot = 0.0; var n = 0
        for (i in 0..3) {
            val nm = normal(q, i)
            for (k in 0 until 24) {
                val t = 0.1 + 0.8 * k / 23
                val bx = nm[0] + t * nm[2]; val by = nm[1] + t * nm[3]
                val ix = (bx + nm[4] * 14).toInt(); val iy = (by + nm[5] * 14).toInt()
                val ox = (bx - nm[4] * 14).toInt(); val oy = (by - nm[5] * 14).toInt()
                if (ix in 0 until w && iy in 0 until h && ox in 0 until w && oy in 0 until h) {
                    tot += px(g, w, ix, iy) - px(g, w, ox, oy); n++
                }
            }
        }
        return if (n == 0) 0.0 else tot / n / 255.0
    }
}
