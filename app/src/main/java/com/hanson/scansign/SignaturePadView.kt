package com.hanson.scansign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Finger-drawn signature pad. Exports a tightly-cropped transparent PNG. */
class SignaturePadView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private class Stroke(val pts: MutableList<PointF>, val color: Int, val width: Float)

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    private val density = resources.displayMetrics.density

    var inkColor = Color.rgb(16, 32, 120)
    var inkWidthDp = 3f
    var onChanged: (() -> Unit)? = null

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 205, 215); strokeWidth = 1.5f * density }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170, 176, 190); textSize = 15 * density; textAlign = Paint.Align.CENTER }

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() { strokes.clear(); current = null; invalidate(); onChanged?.invoke() }

    fun undo() { if (strokes.isNotEmpty()) { strokes.removeAt(strokes.size - 1); invalidate(); onChanged?.invoke() } }

    private fun paint(s: Stroke, scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = s.color; strokeWidth = s.width * scale
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun path(s: Stroke, scale: Float, dx: Float, dy: Float): Path {
        val p = Path()
        val pts = s.pts
        if (pts.isEmpty()) return p
        fun x(i: Int) = (pts[i].x - dx) * scale
        fun y(i: Int) = (pts[i].y - dy) * scale
        p.moveTo(x(0), y(0))
        if (pts.size == 1) { p.lineTo(x(0) + 0.1f, y(0)); return p }
        for (i in 1 until pts.size) {
            p.quadTo(x(i - 1), y(i - 1), (x(i - 1) + x(i)) / 2, (y(i - 1) + y(i)) / 2)
        }
        p.lineTo(x(pts.size - 1), y(pts.size - 1))
        return p
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val baseY = height * 0.72f
        canvas.drawLine(width * 0.08f, baseY, width * 0.92f, baseY, guide)
        if (strokes.isEmpty() && current == null) canvas.drawText("Sign here with your finger", width / 2f, baseY - 24 * density, hint)
        for (s in strokes) canvas.drawPath(path(s, 1f, 0f, 0f), paint(s, 1f))
        current?.let { canvas.drawPath(path(it, 1f, 0f, 0f), paint(it, 1f)) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current = Stroke(mutableListOf(PointF(e.x, e.y)), inkColor, inkWidthDp * density)
            }
            MotionEvent.ACTION_MOVE -> {
                val c = current ?: return true
                for (h in 0 until e.historySize) c.pts.add(PointF(e.getHistoricalX(h), e.getHistoricalY(h)))
                c.pts.add(PointF(e.x, e.y))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current?.let { strokes.add(it) }
                current = null
                onChanged?.invoke()
            }
        }
        invalidate()
        return true
    }

    /** Renders the signature at 3x resolution on a transparent background, cropped to the ink. */
    fun export(): Bitmap? {
        if (strokes.isEmpty()) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        var maxW = 0f
        for (s in strokes) {
            maxW = max(maxW, s.width)
            for (p in s.pts) { l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y) }
        }
        val pad = maxW * 2
        l -= pad; t -= pad; r += pad; b += pad
        val scale = 3f
        val w = ((r - l) * scale).toInt().coerceIn(8, 4000)
        val h = ((b - t) * scale).toInt().coerceIn(8, 4000)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        for (s in strokes) c.drawPath(path(s, scale, l, t), paint(s, scale))
        return bmp
    }
}
