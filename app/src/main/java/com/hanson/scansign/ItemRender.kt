package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Geometry and on-screen drawing for every kind of [EditItem]. */
object ItemRender {

    /** Item bounds in page fractions. [pw], [ph] = page size in points. */
    fun bounds(item: EditItem, pw: Float, ph: Float): RectF = when (item) {
        is SignatureItem -> RectF(item.fx, item.fy, item.fx + item.fw, item.fy + item.fh)
        is TextItem -> {
            val (w, h) = TextRender.sizePt(item)
            RectF(item.fx, item.fy, item.fx + w / pw, item.fy + h / ph)
        }
        is InkItem -> {
            val padX = item.widthPt / 2 / pw
            val padY = item.widthPt / 2 / ph
            RectF(
                (item.points.minOfOrNull { it.x } ?: 0f) - padX, (item.points.minOfOrNull { it.y } ?: 0f) - padY,
                (item.points.maxOfOrNull { it.x } ?: 0f) + padX, (item.points.maxOfOrNull { it.y } ?: 0f) + padY
            )
        }
        is ShapeItem -> {
            val r = RectF(min(item.fx, item.fx + item.fw), min(item.fy, item.fy + item.fh),
                max(item.fx, item.fx + item.fw), max(item.fy, item.fy + item.fh))
            if (item.isLine) {
                val px = max(item.widthPt * 2, 6f) / pw
                val py = max(item.widthPt * 2, 6f) / ph
                r.inset(-px, -py)
            }
            r
        }
    }

    /** Moves an item by a fraction of the page. */
    fun moveBy(item: EditItem, dx: Float, dy: Float) {
        when (item) {
            is InkItem -> item.points.forEach { it.x += dx; it.y += dy }
            else -> { item.fx += dx; item.fy += dy }
        }
    }

    /** Scales an item about its top-left corner (pinch / resize handle). */
    fun scaleBy(item: EditItem, f: Float) {
        when (item) {
            is SignatureItem -> {
                val ratio = item.fh / item.fw
                item.fw = (item.fw * f).coerceIn(0.02f, 1.5f)
                item.fh = item.fw * ratio
            }
            is TextItem -> item.sizePt = (item.sizePt * f).coerceIn(4f, 200f)
            is InkItem -> {
                val ox = item.fx; val oy = item.fy
                item.points.forEach { it.x = ox + (it.x - ox) * f; it.y = oy + (it.y - oy) * f }
                item.widthPt = (item.widthPt * f).coerceIn(0.5f, 60f)
            }
            is ShapeItem -> {
                item.fw *= f; item.fh *= f
                if (!item.isLine && (kotlin.math.abs(item.fw) < 0.01f || kotlin.math.abs(item.fh) < 0.005f)) {
                    item.fw /= f; item.fh /= f
                }
            }
        }
    }

    /** Draws an item onto [canvas] for a page shown at [pr] with [pxPerPt]. */
    fun draw(canvas: Canvas, item: EditItem, pr: RectF, pxPerPt: Float, bitmap: (SignatureItem) -> Bitmap?, selected: Boolean) {
        fun x(f: Float) = pr.left + f * pr.width()
        fun y(f: Float) = pr.top + f * pr.height()
        when (item) {
            is SignatureItem -> bitmap(item)?.let {
                val r = RectF(x(item.fx), y(item.fy), x(item.fx + item.fw), y(item.fy + item.fh))
                canvas.save()
                if (item.rotation != 0f) canvas.rotate(item.rotation, r.centerX(), r.centerY())
                canvas.drawBitmap(it, null, r, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                canvas.restore()
            }
            is TextItem -> {
                if (item is ReplacedText) {
                    val c = item.cover
                    canvas.drawRect(x(c.left), y(c.top), x(c.right), y(c.bottom), Paint().apply { color = item.coverColor })
                }
                val (w, h) = TextRender.sizePt(item)
                val l = x(item.fx); val t = y(item.fy)
                canvas.save()
                if (item.rotation != 0f) canvas.rotate(item.rotation, l + w * pxPerPt / 2, t + h * pxPerPt / 2)
                TextRender.draw(canvas, item, l, t, pxPerPt)
                canvas.restore()
            }
            is InkItem -> {
                if (item.points.isEmpty()) return
                val p = strokePaint(item.color, item.widthPt * pxPerPt, item.alpha)
                if (item.points.size == 1) {
                    p.style = Paint.Style.FILL
                    canvas.drawCircle(x(item.points[0].x), y(item.points[0].y), item.widthPt * pxPerPt / 2, p)
                    return
                }
                val path = Path()
                path.moveTo(x(item.points[0].x), y(item.points[0].y))
                for (i in 1 until item.points.size) {
                    val a = item.points[i - 1]
                    val b = item.points[i]
                    path.quadTo(x(a.x), y(a.y), (x(a.x) + x(b.x)) / 2, (y(a.y) + y(b.y)) / 2)
                }
                val last = item.points.last()
                path.lineTo(x(last.x), y(last.y))
                canvas.drawPath(path, p)
            }
            is ShapeItem -> {
                val l = x(item.fx); val t = y(item.fy)
                val r = x(item.fx + item.fw); val b = y(item.fy + item.fh)
                val box = RectF(min(l, r), min(t, b), max(l, r), max(t, b))
                val sw = item.widthPt * pxPerPt
                when (item.kind) {
                    ShapeKind.RECT -> canvas.drawRect(box, strokePaint(item.color, sw, 1f).apply { strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER })
                    ShapeKind.ELLIPSE -> canvas.drawOval(box, strokePaint(item.color, sw, 1f))
                    ShapeKind.LINE -> canvas.drawLine(l, t, r, b, strokePaint(item.color, sw, 1f))
                    ShapeKind.ARROW -> {
                        val p = strokePaint(item.color, sw, 1f)
                        canvas.drawLine(l, t, r, b, p)
                        val head = arrowHead(l, t, r, b, max(10f * pxPerPt, sw * 4))
                        canvas.drawLine(r, b, head[0], head[1], p)
                        canvas.drawLine(r, b, head[2], head[3], p)
                    }
                    ShapeKind.WHITEOUT -> {
                        canvas.drawRect(box, Paint().apply { color = Color.WHITE })
                        if (selected) canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.LTGRAY
                        })
                    }
                    ShapeKind.FILL -> {
                        canvas.drawRect(box, Paint().apply { color = item.color })
                        if (selected) canvas.drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.GRAY
                        })
                    }
                    ShapeKind.HIGHLIGHT -> canvas.drawRect(box, Paint().apply {
                        color = item.color; alpha = 90
                    })
                }
            }
        }
    }

    fun strokePaint(color: Int, width: Float, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        strokeWidth = max(0.8f, width)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Two end points of an arrow head at (x2, y2). */
    fun arrowHead(x1: Float, y1: Float, x2: Float, y2: Float, len: Float): FloatArray {
        val a = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val spread = Math.toRadians(28.0)
        return floatArrayOf(
            (x2 - len * cos(a - spread)).toFloat(), (y2 - len * sin(a - spread)).toFloat(),
            (x2 - len * cos(a + spread)).toFloat(), (y2 - len * sin(a + spread)).toFloat()
        )
    }

    /** Distance from point to segment (for hit-testing thin items). */
    fun distToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}
