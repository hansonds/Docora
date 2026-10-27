package com.hanson.scansign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Shows a bitmap with a draggable 4-corner crop frame.
 * Drag a corner to move it; drag an edge's middle handle to move that whole edge.
 * A magnifier appears while dragging for precise placement.
 */
class QuadCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bitmap: Bitmap? = null
    /** Corners in bitmap coordinates: TL, TR, BR, BL. */
    private val corners = Array(4) { PointF() }

    private val imgRect = RectF()
    private var scale = 1f
    private val density = resources.displayMetrics.density

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.rgb(0, 224, 168)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.WHITE
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = Color.rgb(0, 224, 168)
    }
    private val shadePaint = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val loupeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = Color.WHITE
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.rgb(0, 224, 168)
    }
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var loupeShader: BitmapShader? = null

    /** 0..3 = corner, 4..7 = edge (4 = top, 5 = right, 6 = bottom, 7 = left), -1 = none. */
    private var active = -1
    private var lastTouch = PointF()
    private val touchRadius = 32f * density

    var onChanged: (() -> Unit)? = null

    fun setImage(bmp: Bitmap, quad: List<PointF>) {
        bitmap = bmp
        loupeShader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        setQuad(quad)
        requestLayout()
        invalidate()
    }

    fun setQuad(quad: List<PointF>) {
        val q = Scanner.orderPoints(quad)
        for (i in 0..3) corners[i].set(q[i].x, q[i].y)
        invalidate()
    }

    fun getQuad(): List<PointF> = corners.map { PointF(it.x, it.y) }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRect()
    }

    private fun computeRect() {
        val b = bitmap ?: return
        val pad = 24f * density
        val availW = width - 2 * pad
        val availH = height - 2 * pad
        if (availW <= 0 || availH <= 0) return
        scale = min(availW / b.width, availH / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        imgRect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    private fun toView(p: PointF) = PointF(imgRect.left + p.x * scale, imgRect.top + p.y * scale)

    private fun edgeMid(i: Int): PointF {
        val a = corners[i - 4]
        val b = corners[(i - 3) % 4]
        return PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bitmap ?: return
        if (imgRect.isEmpty) computeRect()
        canvas.drawBitmap(b, null, imgRect, null)

        val pts = corners.map { toView(it) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) lineTo(pts[i].x, pts[i].y)
            close()
        }
        // darken outside the crop
        val outside = Path().apply {
            addRect(imgRect, Path.Direction.CW)
            addPath(path)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(outside, shadePaint)
        canvas.drawPath(path, framePaint)

        val r = 11f * density
        for (p in pts) {
            canvas.drawCircle(p.x, p.y, r, handlePaint)
            canvas.drawCircle(p.x, p.y, r, handleRing)
        }
        for (i in 4..7) {
            val m = toView(edgeMid(i))
            canvas.drawCircle(m.x, m.y, r * 0.65f, handlePaint)
            canvas.drawCircle(m.x, m.y, r * 0.65f, handleRing)
        }

        if (active >= 0) drawLoupe(canvas, if (active < 4) corners[active] else edgeMid(active))
    }

    private fun drawLoupe(canvas: Canvas, focus: PointF) {
        val shader = loupeShader ?: return
        val radius = 56f * density
        val zoom = 2.2f * scale
        // put the loupe in the top corner away from the finger
        val fv = toView(focus)
        val cx = if (fv.x < width / 2f) width - radius - 16 * density else radius + 16 * density
        val cy = radius + 16 * density
        val m = Matrix()
        m.setScale(zoom, zoom)
        m.postTranslate(cx - focus.x * zoom, cy - focus.y * zoom)
        shader.setLocalMatrix(m)
        loupePaint.shader = shader
        canvas.drawCircle(cx, cy, radius, loupePaint)
        canvas.drawCircle(cx, cy, radius, loupeBorder)
        canvas.drawLine(cx - 12 * density, cy, cx + 12 * density, cy, crossPaint)
        canvas.drawLine(cx, cy - 12 * density, cx, cy + 12 * density, crossPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = -1
                var best = touchRadius * 1.6f
                for (i in 0..7) {
                    val p = toView(if (i < 4) corners[i] else edgeMid(i))
                    val d = hypot(p.x - e.x, p.y - e.y)
                    // corners win ties over edges
                    val adj = if (i < 4) d * 0.85f else d
                    if (adj < best) { best = adj; active = i }
                }
                lastTouch.set(e.x, e.y)
                if (active >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return false
                val dx = (e.x - lastTouch.x) / scale
                val dy = (e.y - lastTouch.y) / scale
                lastTouch.set(e.x, e.y)
                if (active < 4) {
                    move(corners[active], dx, dy, b)
                } else {
                    move(corners[active - 4], dx, dy, b)
                    move(corners[(active - 3) % 4], dx, dy, b)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (active >= 0) onChanged?.invoke()
                active = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun move(p: PointF, dx: Float, dy: Float, b: Bitmap) {
        p.x = (p.x + dx).coerceIn(0f, b.width.toFloat())
        p.y = (p.y + dy).coerceIn(0f, b.height.toFloat())
    }
}
