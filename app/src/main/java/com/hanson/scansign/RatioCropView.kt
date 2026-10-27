package com.hanson.scansign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Rectangle crop with a fixed aspect ratio (default 4:3, width:height). Move it or drag a corner to resize. */
class RatioCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var aspect = 4f / 3f

    private var bitmap: Bitmap? = null
    /** Crop rectangle in bitmap coordinates. */
    private val crop = RectF()
    private val imgRect = RectF()
    private var scale = 1f
    private val density = resources.displayMetrics.density

    private val shade = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE
    }
    private val grid = Paint().apply { color = Color.argb(110, 255, 255, 255); strokeWidth = 1f * density }
    private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f * density; color = Color.rgb(0, 224, 168); strokeCap = Paint.Cap.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * density; isFakeBoldText = true
    }

    /** -1 none, 0..3 corners (TL, TR, BR, BL), 4 move. */
    private var active = -1
    private var lastX = 0f
    private var lastY = 0f

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        resetCrop()
        computeRect()
        invalidate()
    }

    fun resetCrop() {
        val b = bitmap ?: return
        var w = min(b.width.toFloat(), b.height * aspect) * 0.86f
        var h = w / aspect
        if (h > b.height) { h = b.height * 0.86f; w = h * aspect }
        crop.set((b.width - w) / 2f, (b.height - h) / 2f, (b.width + w) / 2f, (b.height + h) / 2f)
        invalidate()
    }

    fun getCrop(): RectF = RectF(crop)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRect()
    }

    private fun computeRect() {
        val b = bitmap ?: return
        val pad = 20f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return
        scale = min(aw / b.width, ah / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        imgRect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    private fun toView(r: RectF) = RectF(
        imgRect.left + r.left * scale, imgRect.top + r.top * scale,
        imgRect.left + r.right * scale, imgRect.top + r.bottom * scale
    )

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (imgRect.isEmpty) computeRect()
        canvas.drawBitmap(b, null, imgRect, null)
        val r = toView(crop)
        val outside = Path().apply {
            addRect(imgRect, Path.Direction.CW)
            addRect(r, Path.Direction.CCW)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.drawPath(outside, shade)
        for (k in 1..2) {
            val x = r.left + r.width() * k / 3f
            val y = r.top + r.height() * k / 3f
            canvas.drawLine(x, r.top, x, r.bottom, grid)
            canvas.drawLine(r.left, y, r.right, y, grid)
        }
        canvas.drawRect(r, frame)
        val L = 18 * density
        // corner brackets
        canvas.drawLine(r.left, r.top, r.left + L, r.top, corner); canvas.drawLine(r.left, r.top, r.left, r.top + L, corner)
        canvas.drawLine(r.right, r.top, r.right - L, r.top, corner); canvas.drawLine(r.right, r.top, r.right, r.top + L, corner)
        canvas.drawLine(r.right, r.bottom, r.right - L, r.bottom, corner); canvas.drawLine(r.right, r.bottom, r.right, r.bottom - L, corner)
        canvas.drawLine(r.left, r.bottom, r.left + L, r.bottom, corner); canvas.drawLine(r.left, r.bottom, r.left, r.bottom - L, corner)
        canvas.drawText("4:3", r.left + 8 * density, r.top + 18 * density, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val r = toView(crop)
                val pts = listOf(r.left to r.top, r.right to r.top, r.right to r.bottom, r.left to r.bottom)
                active = -1
                var best = 40 * density
                pts.forEachIndexed { i, (x, y) ->
                    val d = hypot(e.x - x, e.y - y)
                    if (d < best) { best = d; active = i }
                }
                if (active < 0 && r.contains(e.x, e.y)) active = 4
                lastX = e.x; lastY = e.y
                if (active >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                return active >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (active < 0) return false
                val dx = (e.x - lastX) / scale
                val dy = (e.y - lastY) / scale
                lastX = e.x; lastY = e.y
                if (active == 4) {
                    val ox = dx.coerceIn(-crop.left, b.width - crop.right)
                    val oy = dy.coerceIn(-crop.top, b.height - crop.bottom)
                    crop.offset(ox, oy)
                } else {
                    resizeFromCorner(active, dx, dy, b)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                active = -1
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    /** Resizes keeping the opposite corner fixed and the aspect ratio locked. */
    private fun resizeFromCorner(c: Int, dx: Float, dy: Float, b: Bitmap) {
        val minW = max(40f, b.width * 0.08f)
        // use the larger of the two movements, projected on the diagonal
        val sx = if (c == 1 || c == 2) 1f else -1f
        val sy = if (c == 2 || c == 3) 1f else -1f
        val grow = max(dx * sx, dy * sy * aspect)
        var w = crop.width() + grow
        // anchor
        val ax = if (sx > 0) crop.left else crop.right
        val ay = if (sy > 0) crop.top else crop.bottom
        val maxW = if (sx > 0) b.width - ax else ax
        val maxH = if (sy > 0) b.height - ay else ay
        w = w.coerceIn(minW, min(maxW, maxH * aspect))
        val h = w / aspect
        crop.set(
            if (sx > 0) ax else ax - w,
            if (sy > 0) ay else ay - h,
            if (sx > 0) ax + w else ax,
            if (sy > 0) ay + h else ay
        )
    }
}
