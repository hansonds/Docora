package com.hanson.scansign

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws on top of the camera preview: the live detected page outline (document mode)
 * or a 4:3 guide box (signature mode), plus the auto-capture progress.
 */
class CameraOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Width / height of the camera image as shown (portrait, e.g. 3/4). */
    var imageAspect = 3f / 4f
        set(v) { field = v; invalidate() }

    var signatureMode = false
        set(v) { field = v; invalidate() }

    /** Corners as fractions of the image, TL, TR, BR, BL. */
    private var quad: List<PointF>? = null
    private var progress = 0f
    private val density = resources.displayMetrics.density

    private val fill = Paint().apply { color = Color.argb(36, 0, 224, 168) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density; color = Color.rgb(0, 224, 168)
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shade = Paint().apply { color = Color.argb(120, 0, 0, 0) }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = Color.WHITE
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * density; color = Color.rgb(0, 224, 168)
        strokeCap = Paint.Cap.ROUND
    }

    fun setQuad(q: List<PointF>?, captureProgress: Float) {
        quad = q
        progress = captureProgress
        invalidate()
    }

    /** Area the camera image occupies in this view (preview is FIT_CENTER). */
    fun contentRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        return if (vw / vh > imageAspect) {
            val w = vh * imageAspect
            RectF((vw - w) / 2, 0f, (vw + w) / 2, vh)
        } else {
            val h = vw / imageAspect
            RectF(0f, (vh - h) / 2, vw, (vh + h) / 2)
        }
    }

    /** The 4:3 signature guide box in view coordinates. */
    fun guideRect(): RectF {
        val c = contentRect()
        val w = c.width() * 0.86f
        val h = w * 3f / 4f
        return RectF(c.centerX() - w / 2, c.centerY() - h / 2, c.centerX() + w / 2, c.centerY() + h / 2)
    }

    override fun onDraw(canvas: Canvas) {
        val c = contentRect()
        if (signatureMode) {
            val g = guideRect()
            val p = Path().apply {
                addRect(c, Path.Direction.CW)
                addRect(g, Path.Direction.CCW)
                fillType = Path.FillType.EVEN_ODD
            }
            canvas.drawPath(p, shade)
            canvas.drawRoundRect(g, 12 * density, 12 * density, guide)
            return
        }
        val q = quad ?: return
        val pts = q.map { PointF(c.left + it.x * c.width(), c.top + it.y * c.height()) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) lineTo(pts[i].x, pts[i].y)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
        for (p in pts) canvas.drawCircle(p.x, p.y, 6 * density, dot)
        if (progress > 0f) {
            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat()
            val r = min(28 * density, min(c.width(), c.height()) / 8)
            canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * progress, false, ring)
        }
    }
}
