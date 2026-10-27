package com.hanson.scansign

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.io.File
import kotlin.math.hypot
import kotlin.math.min

/**
 * Shows one PDF page and the signatures placed on it.
 * Drag a signature to move it, pinch or drag the blue corner to resize, tap the red × to remove.
 */
class SignaturePlacementView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var page: Bitmap? = null
    private var items: List<Placement> = emptyList()
    var selected: Placement? = null
        private set

    var onDelete: ((Placement) -> Unit)? = null

    private val sigCache = HashMap<String, Bitmap>()
    private val pageRect = RectF()
    private val density = resources.displayMetrics.density
    private val handleR = 13f * density

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint().apply { color = Color.argb(50, 0, 0, 0) }
    private val sigPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = Color.rgb(30, 94, 255)
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 5 * density), 0f)
    }
    private val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 57, 53) }
    private val resizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 94, 255) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.2f * density; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private enum class Drag { NONE, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = selected ?: return false
            scaleItem(s, d.scaleFactor)
            invalidate()
            return true
        }
    })

    fun setPage(bmp: Bitmap?, placements: List<Placement>) {
        page = bmp
        items = placements
        if (selected != null && selected !in placements) selected = null
        computeRect()
        invalidate()
    }

    fun select(p: Placement?) {
        selected = p
        invalidate()
    }

    /** Aspect ratio of the displayed page (width / height). */
    fun pageAspect(): Float {
        val b = page ?: return 0.707f
        return b.width.toFloat() / b.height
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeRect()
    }

    private fun computeRect() {
        val b = page ?: return
        val pad = 12f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return
        val s = min(aw / b.width, ah / b.height)
        val dw = b.width * s
        val dh = b.height * s
        pageRect.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
    }

    private fun sigBitmap(f: File): Bitmap? =
        sigCache[f.absolutePath] ?: BitmapFactory.decodeFile(f.absolutePath)?.also { sigCache[f.absolutePath] = it }

    private fun rectOf(p: Placement) = RectF(
        pageRect.left + p.fx * pageRect.width(),
        pageRect.top + p.fy * pageRect.height(),
        pageRect.left + (p.fx + p.fw) * pageRect.width(),
        pageRect.top + (p.fy + p.fh) * pageRect.height()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = page ?: return
        if (pageRect.isEmpty) computeRect()
        canvas.drawRect(pageRect.left + 3 * density, pageRect.top + 3 * density,
            pageRect.right + 3 * density, pageRect.bottom + 3 * density, shadowPaint)
        canvas.drawBitmap(b, null, pageRect, pagePaint)
        for (p in items) {
            val bmp = sigBitmap(p.signature) ?: continue
            canvas.drawBitmap(bmp, null, rectOf(p), sigPaint)
        }
        val s = selected ?: return
        val r = rectOf(s)
        canvas.drawRect(r, borderPaint)
        // delete handle (top-right)
        canvas.drawCircle(r.right, r.top, handleR, delPaint)
        val k = handleR * 0.4f
        canvas.drawLine(r.right - k, r.top - k, r.right + k, r.top + k, iconPaint)
        canvas.drawLine(r.right - k, r.top + k, r.right + k, r.top - k, iconPaint)
        // resize handle (bottom-right)
        canvas.drawCircle(r.right, r.bottom, handleR, resizePaint)
        canvas.drawLine(r.right - k, r.bottom + k, r.right + k, r.bottom - k, iconPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        if (scaleDetector.isInProgress) {
            drag = Drag.NONE
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                drag = Drag.NONE
                val sel = selected
                if (sel != null) {
                    val r = rectOf(sel)
                    if (hypot(e.x - r.right, e.y - r.top) <= handleR * 1.8f) {
                        onDelete?.invoke(sel)
                        selected = null
                        invalidate()
                        return true
                    }
                    if (hypot(e.x - r.right, e.y - r.bottom) <= handleR * 1.8f) {
                        drag = Drag.RESIZE
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
                // topmost signature under the finger
                val hit = items.lastOrNull { val r = rectOf(it); r.inset(-8 * density, -8 * density); r.contains(e.x, e.y) }
                selected = hit
                if (hit != null) {
                    drag = Drag.MOVE
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val s = selected ?: return true
                if (e.pointerCount > 1) return true
                val dx = (e.x - lastX) / pageRect.width()
                val dy = (e.y - lastY) / pageRect.height()
                lastX = e.x; lastY = e.y
                when (drag) {
                    Drag.MOVE -> {
                        s.fx = (s.fx + dx).coerceIn(-s.fw * 0.5f, 1f - s.fw * 0.5f)
                        s.fy = (s.fy + dy).coerceIn(-s.fh * 0.5f, 1f - s.fh * 0.5f)
                    }
                    Drag.RESIZE -> {
                        val ratio = s.fh / s.fw
                        val newW = (s.fw + dx).coerceIn(0.04f, 1.2f)
                        s.fw = newW
                        s.fh = newW * ratio
                    }
                    Drag.NONE -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = Drag.NONE
                return true
            }
        }
        return true
    }

    private fun scaleItem(s: Placement, factor: Float) {
        val cx = s.fx + s.fw / 2f
        val cy = s.fy + s.fh / 2f
        val ratio = s.fh / s.fw
        val newW = (s.fw * factor).coerceIn(0.04f, 1.2f)
        s.fw = newW
        s.fh = newW * ratio
        s.fx = cx - s.fw / 2f
        s.fy = cy - s.fh / 2f
    }
}
