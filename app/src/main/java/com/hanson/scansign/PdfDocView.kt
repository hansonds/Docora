package com.hanson.scansign

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Continuous-scroll PDF viewer and editor.
 * - Pinch / double-tap zoom with sharp re-rendering of the visible area
 * - Renders only visible pages, so very long documents (1000+ pages) stay smooth
 * - Fast-scroll handle on the right edge
 * - Shows and edits signatures, text boxes and form fields
 */
class PdfDocView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(page: Int)
        fun onItemTapped(item: EditItem)
        fun onFieldTapped(widget: FormWidget)
        fun onItemsChanged()
        fun onSelectionChanged(item: EditItem?)
    }

    var listener: Listener? = null

    // ---- document ----
    private var geoms: List<PageGeom> = emptyList()
    private var renderer: PageRenderer? = null
    private var widgetsByPage: Map<Int, List<FormWidget>> = emptyMap()
    private var items: MutableList<EditItem> = ArrayList()
    var selected: EditItem? = null
        private set
    var showFields = true
        set(v) { field = v; invalidate() }

    // ---- layout (document coordinates at zoom 1) ----
    private val density = resources.displayMetrics.density
    private val hMargin = 10 * density
    private val gap = 10 * density
    private val topPad = 10 * density
    private val bottomPad = 96 * density
    private var contentW = 0f
    private var pageTop = FloatArray(0)
    private var pageH = FloatArray(0)
    private var totalH = 0f

    // ---- view state ----
    var zoom = 1f
        private set
    private var scrollXf = 0f
    private var scrollYf = 0f
    private val minZoom = 1f
    private val maxZoom = 6f
    private var currentPage = -1

    // ---- rendering ----
    private val maxBytes = (Runtime.getRuntime().maxMemory() / 5).toInt().coerceAtLeast(24 * 1024 * 1024)
    private val baseCache = object : LruCache<Int, Bitmap>(maxBytes) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val pending = HashSet<Int>()
    @Volatile private var wantFirst = 0
    @Volatile private var wantLast = 0

    private class Tile(val page: Int, val doc: RectF, val bmp: Bitmap)
    private val tiles = HashMap<Int, Tile>()
    @Volatile private var tileGen = 0
    private val handler = Handler(Looper.getMainLooper())
    private val tileRunnable = Runnable { requestTiles() }

    private val sigCache = HashMap<String, Bitmap>()

    // ---- paints ----
    private val bgColor = ContextCompat.getColor(context, R.color.viewer_bg)
    private val pageShadow = Paint().apply { color = Color.argb(28, 0, 0, 0) }
    private val pageFill = Paint().apply { color = Color.WHITE }
    private val placeholderText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(170, 176, 190); textAlign = Paint.Align.CENTER; textSize = 16 * density
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fieldFill = Paint().apply { color = Color.argb(38, 36, 87, 245) }
    private val fieldStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = Color.argb(150, 36, 87, 245)
    }
    private val fieldCover = Paint().apply { color = Color.WHITE }
    private val fieldText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 20, 60) }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.rgb(10, 20, 60)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(10, 20, 60) }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 36, 87, 245) }
    private val selBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * density; color = Color.rgb(36, 87, 245)
        pathEffect = DashPathEffect(floatArrayOf(7 * density, 5 * density), 0f)
    }
    private val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(229, 72, 77) }
    private val resizePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 87, 245) }
    private val handleIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2.2f * density; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 2f * density; style = Paint.Style.STROKE
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 40, 46, 60) }
    private val thumbGrip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 1.5f * density }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 36, 87, 245) }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * density; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val handleR = 12 * density

    // ---- gestures ----
    private enum class Mode { NONE, PAN, ITEM_MOVE, ITEM_RESIZE, SCROLLBAR }
    private var mode = Mode.NONE
    private var lastX = 0f
    private var lastY = 0f
    private val scroller = OverScroller(context)
    private var zoomAnimator: ValueAnimator? = null
    private var scrollbarVisibleUntil = 0L

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val sel = selected
            if ((mode == Mode.ITEM_MOVE || mode == Mode.ITEM_RESIZE) && sel != null) {
                scaleItem(sel, d.scaleFactor)
            } else {
                zoomAround(zoom * d.scaleFactor, d.focusX, d.focusY)
            }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode != Mode.PAN || scaleDetector.isInProgress) return false
            scrollTo(scrollXf + dx, scrollYf + dy)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode != Mode.PAN) return false
            scroller.fling(
                scrollXf.roundToInt(), scrollYf.roundToInt(), (-vx).roundToInt(), (-vy).roundToInt(),
                0, maxScrollX().roundToInt(), 0, maxScrollY().roundToInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateZoom(if (zoom < 1.8f) 2.5f else 1f, e.x, e.y)
            return true
        }
    })

    // ======================= public API =======================

    fun setDocument(geoms: List<PageGeom>, renderer: PageRenderer, widgets: List<FormWidget>, items: MutableList<EditItem>) {
        this.geoms = geoms
        this.renderer = renderer
        this.widgetsByPage = widgets.groupBy { it.page }
        this.items = items
        baseCache.evictAll()
        tiles.clear()
        relayout()
        invalidate()
    }

    val pageCount: Int get() = geoms.size

    fun goToPage(index: Int) {
        if (geoms.isEmpty()) return
        val i = index.coerceIn(0, geoms.size - 1)
        scroller.forceFinished(true)
        scrollTo(scrollXf, (pageTop[i] - topPad) * zoom)
    }

    fun zoomIn() = animateZoom(min(maxZoom, zoom * 1.6f), width / 2f, height / 2f)
    fun zoomOut() = animateZoom(max(minZoom, zoom / 1.6f), width / 2f, height / 2f)

    fun select(item: EditItem?) {
        selected = item
        listener?.onSelectionChanged(item)
        invalidate()
    }

    fun refresh() = invalidate()

    /** Page currently in the middle of the screen. */
    fun currentPage(): Int = max(0, currentPage)

    /** Centre of the visible part of [page], as fractions of that page. */
    fun visibleCenterOn(page: Int): PointF {
        val r = pageScreenRect(page)
        val cx = ((width / 2f).coerceIn(r.left, r.right) - r.left) / r.width()
        val visTop = max(r.top, 0f)
        val visBottom = min(r.bottom, height.toFloat())
        val cy = if (visBottom > visTop) (((visTop + visBottom) / 2f) - r.top) / r.height() else 0.5f
        return PointF(cx.coerceIn(0.1f, 0.9f), cy.coerceIn(0.1f, 0.9f))
    }

    fun pageSizePt(page: Int): PointF = geoms.getOrNull(page)?.let { PointF(it.dispW, it.dispH) } ?: PointF(595f, 842f)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        baseCache.evictAll()
        tiles.clear()
    }

    // ======================= layout =======================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val keepPage = currentPage
        relayout()
        if (keepPage > 0) goToPage(keepPage)
        baseCache.evictAll()
        tiles.clear()
    }

    private fun relayout() {
        if (width == 0 || geoms.isEmpty()) return
        contentW = width - 2 * hMargin
        pageTop = FloatArray(geoms.size)
        pageH = FloatArray(geoms.size)
        var y = topPad
        for (i in geoms.indices) {
            val g = geoms[i]
            pageTop[i] = y
            pageH[i] = contentW * g.dispH / g.dispW
            y += pageH[i] + gap
        }
        totalH = y - gap + bottomPad
        clampScroll()
    }

    private fun maxScrollX() = max(0f, width * zoom - width)
    private fun maxScrollY() = max(0f, totalH * zoom - height)

    private fun clampScroll() {
        scrollXf = scrollXf.coerceIn(0f, maxScrollX())
        scrollYf = scrollYf.coerceIn(0f, maxScrollY())
    }

    private fun scrollTo(x: Float, y: Float) {
        scrollXf = x
        scrollYf = y
        clampScroll()
        onViewportChanged()
    }

    private fun zoomAround(newZoom: Float, fx: Float, fy: Float) {
        val z = newZoom.coerceIn(minZoom, maxZoom)
        val docX = (fx + scrollXf) / zoom
        val docY = (fy + scrollYf) / zoom
        zoom = z
        scrollXf = docX * z - fx
        scrollYf = docY * z - fy
        clampScroll()
        onViewportChanged()
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        val start = zoom
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                zoomAround(start + (target - start) * t, fx, fy)
            }
            start()
        }
    }

    private fun onViewportChanged() {
        scrollbarVisibleUntil = System.currentTimeMillis() + 1500
        handler.removeCallbacks(tileRunnable)
        handler.postDelayed(tileRunnable, 180)
        tileGen++
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXf = scroller.currX.toFloat()
            scrollYf = scroller.currY.toFloat()
            clampScroll()
            onViewportChanged()
            postInvalidateOnAnimation()
        }
    }

    private fun pageScreenRect(i: Int): RectF {
        val left = hMargin * zoom - scrollXf
        val top = pageTop[i] * zoom - scrollYf
        return RectF(left, top, left + contentW * zoom, top + pageH[i] * zoom)
    }

    private fun pxPerPt(i: Int): Float = contentW * zoom / geoms[i].dispW

    /** First page whose bottom is below doc-y. */
    private fun pageAtDocY(docY: Float): Int {
        var lo = 0
        var hi = geoms.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (pageTop[mid] + pageH[mid] < docY) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun visibleRange(): IntRange {
        if (geoms.isEmpty() || pageTop.isEmpty()) return IntRange.EMPTY
        val first = pageAtDocY(scrollYf / zoom)
        var last = first
        val bottomDoc = (scrollYf + height) / zoom
        while (last + 1 < geoms.size && pageTop[last + 1] <= bottomDoc) last++
        return first..last
    }

    // ======================= drawing =======================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bgColor)
        if (geoms.isEmpty() || pageTop.isEmpty()) return
        val range = visibleRange()
        if (range.isEmpty()) return
        wantFirst = range.first - 1
        wantLast = range.last + 1

        for (i in range) {
            val r = pageScreenRect(i)
            canvas.drawRect(r.left + 2 * density, r.top + 3 * density, r.right + 2 * density, r.bottom + 3 * density, pageShadow)
            canvas.drawRect(r, pageFill)
            val base = baseCache.get(i)
            if (base != null) {
                canvas.drawBitmap(base, null, r, bmpPaint)
            } else {
                canvas.drawText("Page ${i + 1}", r.centerX(), r.centerY(), placeholderText)
                requestBase(i)
            }
            tiles[i]?.let { t ->
                val dst = RectF(
                    t.doc.left * zoom - scrollXf, t.doc.top * zoom - scrollYf,
                    t.doc.right * zoom - scrollXf, t.doc.bottom * zoom - scrollYf
                )
                canvas.drawBitmap(t.bmp, null, dst, bmpPaint)
            }
            drawWidgets(canvas, i, r)
            drawItems(canvas, i, r)
        }
        // prefetch neighbours
        if (range.last + 1 < geoms.size) requestBase(range.last + 1)
        if (range.first - 1 >= 0) requestBase(range.first - 1)
        // drop tiles that scrolled away
        tiles.keys.retainAll { it in range }

        drawSelection(canvas)
        drawScrollbar(canvas)

        val center = pageAtDocY((scrollYf + height / 2f) / zoom)
        if (center != currentPage) {
            currentPage = center
            listener?.onPageChanged(center)
        }
    }

    private fun requestBase(i: Int) {
        val r = renderer ?: return
        if (baseCache.get(i) != null || !pending.add(i)) return
        val w = contentW.roundToInt().coerceIn(200, 1600)
        r.renderPage(i, w, stillWanted = { i in wantFirst..wantLast }) { bmp ->
            pending.remove(i)
            if (bmp != null) {
                baseCache.put(i, bmp)
                invalidate()
            }
        }
    }

    private fun requestTiles() {
        val r = renderer ?: return
        if (zoom < 1.15f || width == 0) {
            if (tiles.isNotEmpty()) { tiles.clear(); invalidate() }
            return
        }
        val gen = tileGen
        val view = RectF(0f, 0f, width.toFloat(), height.toFloat())
        for (i in visibleRange()) {
            val pr = pageScreenRect(i)
            val vis = RectF(pr)
            if (!vis.intersect(view)) continue
            val scale = pxPerPt(i)
            val left = (vis.left - pr.left).roundToInt()
            val top = (vis.top - pr.top).roundToInt()
            val w = vis.width().roundToInt()
            val h = vis.height().roundToInt()
            val docRect = RectF(
                (vis.left + scrollXf) / zoom, (vis.top + scrollYf) / zoom,
                (vis.right + scrollXf) / zoom, (vis.bottom + scrollYf) / zoom
            )
            r.renderRegion(i, scale, left, top, w, h, stillWanted = { gen == tileGen }) { bmp ->
                if (bmp != null && gen == tileGen) {
                    tiles[i] = Tile(i, docRect, bmp)
                    invalidate()
                }
            }
        }
    }

    private fun widgetRect(w: FormWidget, pr: RectF) = RectF(
        pr.left + w.rect.left * pr.width(), pr.top + w.rect.top * pr.height(),
        pr.left + w.rect.right * pr.width(), pr.top + w.rect.bottom * pr.height()
    )

    private fun drawWidgets(canvas: Canvas, page: Int, pr: RectF) {
        val list = widgetsByPage[page] ?: return
        for (w in list) {
            val f = w.field
            val r = widgetRect(w, pr)
            if (f.changed) {
                canvas.drawRect(r, fieldCover)
                when (f.type) {
                    FieldType.TEXT, FieldType.CHOICE -> {
                        val shown = if (f.type == FieldType.CHOICE) {
                            val idx = f.optionsExport.indexOf(f.value)
                            if (idx >= 0 && idx < f.optionsDisplay.size) f.optionsDisplay[idx] else f.value
                        } else f.value
                        fieldText.textSize = min(r.height() * 0.62f, 12f * pxPerPt(page))
                        canvas.save()
                        canvas.clipRect(r)
                        if (f.multiline) {
                            var y = r.top + fieldText.textSize
                            for (line in shown.split('\n')) {
                                canvas.drawText(line, r.left + 2 * density, y, fieldText)
                                y += fieldText.textSize * 1.15f
                            }
                        } else {
                            canvas.drawText(shown, r.left + 2 * density, r.centerY() + fieldText.textSize * 0.35f, fieldText)
                        }
                        canvas.restore()
                    }
                    FieldType.CHECKBOX -> if (f.value != "Off" && f.value.isNotEmpty()) drawCheck(canvas, r)
                    FieldType.RADIO -> if (f.value == w.onValue) {
                        canvas.drawCircle(r.centerX(), r.centerY(), min(r.width(), r.height()) * 0.28f, dotPaint)
                    }
                    FieldType.SIGNATURE -> {}
                }
            }
            if (showFields && !f.readOnly) {
                canvas.drawRect(r, fieldFill)
                canvas.drawRect(r, fieldStroke)
                if (f.type == FieldType.SIGNATURE) {
                    hintText.textSize = min(r.height() * 0.4f, 14 * density)
                    canvas.drawText("✎ Sign here", r.left + 4 * density, r.centerY() + hintText.textSize * 0.35f, hintText)
                }
            }
        }
    }

    private fun drawCheck(canvas: Canvas, r: RectF) {
        val s = min(r.width(), r.height())
        checkPaint.strokeWidth = max(1.5f, s * 0.12f)
        val cx = r.centerX()
        val cy = r.centerY()
        canvas.drawLine(cx - s * 0.3f, cy, cx - s * 0.08f, cy + s * 0.24f, checkPaint)
        canvas.drawLine(cx - s * 0.08f, cy + s * 0.24f, cx + s * 0.32f, cy - s * 0.26f, checkPaint)
    }

    private fun itemRect(item: EditItem): RectF? {
        if (item.page !in geoms.indices) return null
        val pr = pageScreenRect(item.page)
        return when (item) {
            is SignatureItem -> RectF(
                pr.left + item.fx * pr.width(), pr.top + item.fy * pr.height(),
                pr.left + (item.fx + item.fw) * pr.width(), pr.top + (item.fy + item.fh) * pr.height()
            )
            is TextItem -> {
                val (wPt, hPt) = TextRender.sizePt(item)
                val s = pxPerPt(item.page)
                val l = pr.left + item.fx * pr.width()
                val t = pr.top + item.fy * pr.height()
                RectF(l, t, l + wPt * s, t + hPt * s)
            }
        }
    }

    private fun drawItems(canvas: Canvas, page: Int, pr: RectF) {
        for (item in items) {
            if (item.page != page) continue
            val r = itemRect(item) ?: continue
            when (item) {
                is SignatureItem -> sigBitmap(item)?.let { canvas.drawBitmap(it, null, r, bmpPaint) }
                is TextItem -> TextRender.draw(canvas, item, r.left, r.top, pxPerPt(page))
            }
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val s = selected ?: return
        val r = itemRect(s) ?: return
        val pad = 3 * density
        val box = RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        canvas.drawRect(box, selBorder)
        // delete (top-right)
        canvas.drawCircle(box.right, box.top, handleR, delPaint)
        canvas.drawCircle(box.right, box.top, handleR, handleRing)
        val k = handleR * 0.38f
        canvas.drawLine(box.right - k, box.top - k, box.right + k, box.top + k, handleIcon)
        canvas.drawLine(box.right - k, box.top + k, box.right + k, box.top - k, handleIcon)
        // resize (bottom-right)
        canvas.drawCircle(box.right, box.bottom, handleR, resizePaint)
        canvas.drawCircle(box.right, box.bottom, handleR, handleRing)
        canvas.drawLine(box.right - k, box.bottom + k, box.right + k, box.bottom - k, handleIcon)
        canvas.drawLine(box.right + k, box.bottom - k, box.right + k - k * 0.9f, box.bottom - k, handleIcon)
        canvas.drawLine(box.right + k, box.bottom - k, box.right + k, box.bottom - k + k * 0.9f, handleIcon)
    }

    private fun sigBitmap(item: SignatureItem): Bitmap? {
        val key = item.file.absolutePath
        sigCache[key]?.let { return it }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(key, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 1200) sample *= 2
        val b = BitmapFactory.decodeFile(key, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        sigCache[key] = b
        return b
    }

    // ---- fast scroll handle ----
    private val thumbW = 6 * density
    private val thumbH = 52 * density
    private fun scrollbarEnabled() = maxScrollY() > height * 2.5f

    private fun thumbTop(): Float {
        val track = height - thumbH - 2 * topPad
        val m = maxScrollY()
        return topPad + if (m > 0) scrollYf / m * track else 0f
    }

    private fun drawScrollbar(canvas: Canvas) {
        if (!scrollbarEnabled()) return
        val active = mode == Mode.SCROLLBAR
        if (!active && System.currentTimeMillis() > scrollbarVisibleUntil) {
            // keep a faint handle visible so people know it's there
            thumbPaint.alpha = 70
        } else {
            thumbPaint.alpha = 210
            postInvalidateDelayed(300)
        }
        val top = thumbTop()
        val right = width - 4 * density
        val w = if (active) thumbW * 4 else thumbW * 3
        canvas.drawRoundRect(RectF(right - w, top, right, top + thumbH), w / 2, w / 2, thumbPaint)
        val cx = right - w / 2
        for (k in -1..1) {
            val y = top + thumbH / 2 + k * 5 * density
            canvas.drawLine(cx - w * 0.22f, y, cx + w * 0.22f, y, thumbGrip)
        }
        if (active) {
            val label = "${currentPage() + 1} / ${geoms.size}"
            val bw = bubbleText.measureText(label) + 28 * density
            val bh = 38 * density
            val by = (top + thumbH / 2 - bh / 2).coerceIn(4 * density, height - bh - 4 * density)
            val bx = right - w - 12 * density - bw
            canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), bh / 2, bh / 2, bubblePaint)
            canvas.drawText(label, bx + bw / 2, by + bh / 2 + bubbleText.textSize * 0.35f, bubbleText)
        }
    }

    // ======================= touch =======================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (geoms.isEmpty()) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            lastX = e.x; lastY = e.y
            mode = pickMode(e.x, e.y)
            if (mode != Mode.PAN) parent?.requestDisallowInterceptTouchEvent(true)
        }

        scaleDetector.onTouchEvent(e)

        when (mode) {
            Mode.SCROLLBAR -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    val track = height - thumbH - 2 * topPad
                    val t = ((e.y - thumbH / 2 - topPad) / track).coerceIn(0f, 1f)
                    scrollTo(scrollXf, t * maxScrollY())
                }
            }
            Mode.ITEM_MOVE, Mode.ITEM_RESIZE -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    dragSelected(e.x - lastX, e.y - lastY)
                }
            }
            Mode.PAN -> gestureDetector.onTouchEvent(e)
            Mode.NONE -> {}
        }
        lastX = e.x; lastY = e.y

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode == Mode.ITEM_MOVE || mode == Mode.ITEM_RESIZE) listener?.onItemsChanged()
            if (mode == Mode.SCROLLBAR) scrollbarVisibleUntil = System.currentTimeMillis() + 1200
            mode = Mode.NONE
            invalidate()
        }
        return true
    }

    private fun pickMode(x: Float, y: Float): Mode {
        if (scrollbarEnabled() && x > width - 40 * density) {
            val t = thumbTop()
            if (y > t - 24 * density && y < t + thumbH + 24 * density) return Mode.SCROLLBAR
        }
        val s = selected
        if (s != null) {
            val r = itemRect(s)
            if (r != null) {
                val pad = 3 * density
                val hit = handleR * 1.9f
                if (hypot(x - (r.right + pad), y - (r.top - pad)) < hit) {
                    items.remove(s)
                    select(null)
                    listener?.onItemsChanged()
                    return Mode.NONE
                }
                if (hypot(x - (r.right + pad), y - (r.bottom + pad)) < hit) return Mode.ITEM_RESIZE
                val grow = RectF(r)
                grow.inset(-10 * density, -10 * density)
                if (grow.contains(x, y)) return Mode.ITEM_MOVE
            }
        }
        return Mode.PAN
    }

    private fun dragSelected(dx: Float, dy: Float) {
        val s = selected ?: return
        val pr = pageScreenRect(s.page)
        when (mode) {
            Mode.ITEM_MOVE -> {
                val r = itemRect(s) ?: return
                val fw = r.width() / pr.width()
                val fh = r.height() / pr.height()
                s.fx = (s.fx + dx / pr.width()).coerceIn(-fw * 0.5f, 1f - fw * 0.5f)
                s.fy = (s.fy + dy / pr.height()).coerceIn(-fh * 0.5f, 1f - fh * 0.5f)
            }
            Mode.ITEM_RESIZE -> {
                val r = itemRect(s) ?: return
                val newW = (r.width() + dx).coerceAtLeast(16 * density)
                scaleItem(s, newW / r.width())
            }
            else -> {}
        }
        invalidate()
    }

    private fun scaleItem(s: EditItem, factor: Float) {
        when (s) {
            is SignatureItem -> {
                val ratio = s.fh / s.fw
                val nw = (s.fw * factor).coerceIn(0.03f, 1.5f)
                s.fw = nw
                s.fh = nw * ratio
            }
            is TextItem -> s.sizePt = (s.sizePt * factor).coerceIn(4f, 200f)
        }
        invalidate()
    }

    private fun handleTap(x: Float, y: Float) {
        // items first (topmost last in list)
        for (item in items.asReversed()) {
            val r = itemRect(item) ?: continue
            val grow = RectF(r)
            grow.inset(-6 * density, -6 * density)
            if (grow.contains(x, y)) {
                if (item === selected) listener?.onItemTapped(item) else select(item)
                return
            }
        }
        if (selected != null) {
            select(null)
            return
        }
        // form fields
        for (i in visibleRange()) {
            val pr = pageScreenRect(i)
            if (!pr.contains(x, y)) continue
            val list = widgetsByPage[i] ?: continue
            val hit = list.lastOrNull { w ->
                val r = widgetRect(w, pr)
                val slop = max(0f, 14 * density - min(r.width(), r.height())) / 2
                r.inset(-slop, -slop)
                r.contains(x, y) && !w.field.readOnly
            }
            if (hit != null) {
                listener?.onFieldTapped(hit)
                return
            }
        }
    }
}
