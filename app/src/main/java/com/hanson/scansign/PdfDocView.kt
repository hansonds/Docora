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
        /** Scroll or zoom changed (used to keep the in-place field editor attached). */
        fun onViewportChanged() {}
        /** Any tap on the document (before it is handled). */
        fun onAnyTap() {}
        /** A pen stroke or shape was just drawn. */
        fun onItemAdded(item: EditItem) {}
        /** Long-press needs the words on [page] (call [setPageWords] when ready). */
        fun onNeedPageText(page: Int) {}
        /** Text selection changed; null = cleared. [anchor] is the selection's screen bounds. */
        fun onTextSelected(text: String?, anchor: RectF?) {}
        /** Edit PDF (beta): a tap on the page that didn't hit an item. Position in page fractions. */
        fun onPagePick(page: Int, fx: Float, fy: Float) {}
    }

    /** A recognised word: text and position as page fractions; [lineEnd] = last word of a line. */
    class Word(val text: String, val r: RectF, val lineEnd: Boolean)

    private val pageWords = HashMap<Int, List<Word>>()
    private var selPage = -1
    private var selA = -1
    private var selB = -1
    private var pendingSel: Pair<Int, PointF>? = null
    private val selFill = Paint().apply { color = Color.argb(70, 36, 87, 245) }
    private val selHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 87, 245) }

    fun hasPageWords(page: Int) = pageWords.containsKey(page)
    fun pageWords(page: Int): List<Word>? = pageWords[page]

    // ---- Edit PDF (beta) ----
    /** Taps that miss the user's items go to [Listener.onPagePick] (instead of form fields). */
    var pickMode = false
        set(v) { field = v; if (!v) { pickOutlines.clear(); pickFocus = null }; invalidate() }
    private val pickOutlines = HashMap<Int, Pair<List<RectF>, List<RectF>>>()
    /** The text / picture being edited right now. */
    var pickFocus: Pair<Int, RectF>? = null
        set(v) { field = v; invalidate() }
    private val outlineText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(110, 36, 87, 245)
        strokeWidth = 1f * context.resources.displayMetrics.density
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * context.resources.displayMetrics.density, 4f * context.resources.displayMetrics.density), 0f)
    }
    private val outlineImage = Paint(outlineText).apply { color = Color.argb(150, 255, 120, 0) }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(36, 87, 245); strokeWidth = 2.5f * context.resources.displayMetrics.density
    }
    private val focusFill = Paint().apply { color = Color.argb(30, 36, 87, 245) }

    /** Shows what can be edited on [page]: text lines (blue dashes) and pictures (orange dashes). */
    fun setPickOutlines(page: Int, text: List<RectF>, images: List<RectF>) {
        pickOutlines[page] = Pair(text, images)
        invalidate()
    }

    private fun drawPickOutlines(canvas: Canvas, page: Int, pr: RectF) {
        if (!pickMode) return
        fun s(r: RectF) = RectF(pr.left + r.left * pr.width(), pr.top + r.top * pr.height(), pr.left + r.right * pr.width(), pr.top + r.bottom * pr.height())
        pickOutlines[page]?.let { (text, images) ->
            for (r in images) canvas.drawRect(s(r), outlineImage)
            for (r in text) canvas.drawRoundRect(s(r), 2 * density, 2 * density, outlineText)
        }
        pickFocus?.let { (p, r) ->
            if (p == page) { val sr = s(r); sr.inset(-2 * density, -2 * density); canvas.drawRect(sr, focusFill); canvas.drawRect(sr, focusPaint) }
        }
    }

    // ---- find in document ----
    private var searchHits: Map<Int, List<SearchHit>> = emptyMap()
    private var currentHit: SearchHit? = null
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 255, 214, 0) }
    private val hitNowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 255, 120, 0) }
    private val hitNowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(255, 110, 0); strokeWidth = 2f * context.resources.displayMetrics.density
    }

    /** Highlights search matches; [current] is drawn stronger. */
    fun setSearchHits(hits: List<SearchHit>, current: SearchHit?) {
        searchHits = hits.groupBy { it.page }
        currentHit = current
        invalidate()
    }

    /** Scrolls so [hit] is in the middle of the visible area (between the floating bars). */
    fun reveal(hit: SearchHit) {
        if (hit.page !in geoms.indices || pageTop.isEmpty()) return
        scroller.forceFinished(true)
        val pr = pageScreenRect(hit.page)
        val r = hit.rects.first()
        val cx = pr.left + r.centerX() * pr.width()
        val cy = pr.top + r.centerY() * pr.height()
        val midY = insetTop + (height - insetTop - insetBottom) / 2f
        scrollTo(scrollXf + (cx - width / 2f), scrollYf + (cy - midY))
    }

    private fun drawSearchHits(canvas: Canvas, page: Int, pr: RectF) {
        val hits = searchHits[page] ?: return
        val pad = 1.5f * density
        for (h in hits) {
            val now = h === currentHit
            for (r in h.rects) {
                val s = RectF(
                    pr.left + r.left * pr.width() - pad, pr.top + r.top * pr.height() - pad,
                    pr.left + r.right * pr.width() + pad, pr.top + r.bottom * pr.height() + pad
                )
                canvas.drawRoundRect(s, 3 * density, 3 * density, if (now) hitNowPaint else hitPaint)
                if (now) canvas.drawRoundRect(s, 3 * density, 3 * density, hitNowStroke)
            }
        }
    }

    fun setPageWords(page: Int, words: List<Word>) {
        pageWords[page] = words
        val p = pendingSel
        if (p != null && p.first == page) {
            pendingSel = null
            if (words.isEmpty()) listener?.onTextSelected("", null) else selectWordAt(page, p.second)
        }
    }

    fun clearTextSelection() {
        if (selPage < 0 && pendingSel == null) return
        selPage = -1; selA = -1; selB = -1; pendingSel = null
        listener?.onTextSelected(null, null)
        invalidate()
    }

    /** Selects every word on the page that holds the current selection. */
    fun selectAllText() {
        val words = pageWords[selPage] ?: return
        if (words.isEmpty()) return
        selA = 0; selB = words.size - 1
        notifySelection()
        invalidate()
    }

    private fun startTextSelection(x: Float, y: Float) {
        val page = pageAt(x, y) ?: return
        val f = toFrac(page, x, y)
        select(null)
        if (pageWords.containsKey(page)) selectWordAt(page, f)
        else {
            pendingSel = Pair(page, f)
            listener?.onNeedPageText(page)
        }
    }

    private fun nearestWord(page: Int, f: PointF): Int {
        val words = pageWords[page] ?: return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        val g = geoms[page]
        for ((i, w) in words.withIndex()) {
            // distance in points, vertical distance weighted more (stay on the line)
            val dx = when { f.x < w.r.left -> w.r.left - f.x; f.x > w.r.right -> f.x - w.r.right; else -> 0f } * g.dispW
            val dy = when { f.y < w.r.top -> w.r.top - f.y; f.y > w.r.bottom -> f.y - w.r.bottom; else -> 0f } * g.dispH
            val d = dx + dy * 3
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun selectWordAt(page: Int, f: PointF) {
        val i = nearestWord(page, f)
        if (i < 0) { listener?.onTextSelected("", null); return }
        selPage = page; selA = i; selB = i
        notifySelection()
        invalidate()
    }

    private fun selRange(): IntRange? = if (selPage < 0 || selA < 0) null else minOf(selA, selB)..maxOf(selA, selB)

    private fun wordScreen(page: Int, w: Word): RectF {
        val pr = pageScreenRect(page)
        return RectF(pr.left + w.r.left * pr.width(), pr.top + w.r.top * pr.height(),
            pr.left + w.r.right * pr.width(), pr.top + w.r.bottom * pr.height())
    }

    private fun selectionHandles(): Pair<PointF, PointF>? {
        val range = selRange() ?: return null
        val words = pageWords[selPage] ?: return null
        val a = wordScreen(selPage, words[range.first]); val b = wordScreen(selPage, words[range.last])
        return Pair(PointF(a.left, a.bottom + 10 * density), PointF(b.right, b.bottom + 10 * density))
    }

    fun selectionScreenRect(): RectF? {
        val range = selRange() ?: return null
        val words = pageWords[selPage] ?: return null
        val r = RectF(wordScreen(selPage, words[range.first]))
        for (i in range) r.union(wordScreen(selPage, words[i]))
        return r
    }

    private fun selectedText(): String {
        val range = selRange() ?: return ""
        val words = pageWords[selPage] ?: return ""
        val sb = StringBuilder()
        for (i in range) {
            sb.append(words[i].text)
            if (i != range.last) sb.append(if (words[i].lineEnd) "\n" else " ")
        }
        return sb.toString()
    }

    private fun notifySelection() = listener?.onTextSelected(selectedText(), selectionScreenRect())

    private fun dragSelectionHandle(x: Float, y: Float) {
        if (selPage < 0) return
        val i = nearestWord(selPage, toFrac(selPage, x, y - 10 * density))
        if (i < 0) return
        if (mode == Mode.SEL_START) selA = i else selB = i
        invalidate()
    }

    private fun drawTextSelection(canvas: Canvas) {
        val range = selRange() ?: return
        val words = pageWords[selPage] ?: return
        for (i in range) canvas.drawRect(wordScreen(selPage, words[i]), selFill)
        val (a, b) = selectionHandles() ?: return
        for (p in listOf(a, b)) {
            canvas.drawLine(p.x, p.y - 10 * density, p.x, p.y, selHandle.apply { strokeWidth = 2 * density })
            canvas.drawCircle(p.x, p.y + 4 * density, 8 * density, selHandle)
        }
    }

    /** Current drawing tool. [None] = select / move / pan. */
    sealed class Tool {
        object None : Tool()
        class Pen(val color: Int, val widthPt: Float, val alpha: Float) : Tool()
        class Shape(val kind: ShapeKind, val color: Int, val widthPt: Float) : Tool()
    }

    var tool: Tool = Tool.None
        set(v) { field = v; if (v != Tool.None) select(null) }
    private var drawing: EditItem? = null

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
    /** Draw every field value ourselves (when the display copy couldn't bake them in). */
    var drawAllValues = false
        set(v) { field = v; invalidate() }
    /** Field currently being typed into: drawn without its old value underneath. */
    var editingField: FormField? = null
        set(v) { field = v; invalidate() }

    // ---- layout (document coordinates at zoom 1) ----
    private val density = resources.displayMetrics.density
    private val hMargin = 10 * density
    private val gap = 10 * density
    private val topPad = 10 * density
    private val bottomPad = 24 * density
    /** Space taken by the floating bars (screen px); pages scroll underneath them. */
    private var insetTop = 0f
    private var insetBottom = 72 * density
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
    private val bgTop = ContextCompat.getColor(context, R.color.viewer_bg_top)
    private val bgBottom = ContextCompat.getColor(context, R.color.viewer_bg_bottom)
    private val bgPaint = Paint()
    private val pageShadow = Paint().apply { color = Color.argb(28, 0, 0, 0) }
    /** Soft floating shadow under each page (blurred shadows need Android 9+ with hardware drawing). */
    private val softShadow = android.os.Build.VERSION.SDK_INT >= 28
    private val pageGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(14 * resources.displayMetrics.density, 0f, 4 * resources.displayMetrics.density, Color.argb(60, 10, 20, 50))
    }
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
    private enum class Mode { NONE, PAN, ITEM_MOVE, ITEM_RESIZE, ITEM_ROTATE, SCROLLBAR, DRAW, SEL_START, SEL_END }
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
            val fitsY = totalH * zoom < height
            if (fitsY && maxScrollX() <= 0f) return true
            scroller.fling(
                scrollXf.roundToInt(), scrollYf.roundToInt(), (-vx).roundToInt(), if (fitsY) 0 else (-vy).roundToInt(),
                0, maxScrollX().roundToInt(), scrollYf.roundToInt().coerceAtMost(0), maxScrollY().roundToInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (tool == Tool.None) handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (tool != Tool.None || mode != Mode.PAN) return
            // long-press on an item selects it; elsewhere it starts text selection
            if (items.asReversed().any { hits(it, e.x, e.y) }) return
            startTextSelection(e.x, e.y)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != Tool.None) return false
            animateZoom(if (zoom < 1.8f) 2.5f else 1f, e.x, e.y)
            return true
        }
    })

    // ======================= public API =======================

    /** Night reading: pages shown inverted (white text on dark). Saved PDFs are not affected. */
    var nightMode = false
        set(v) {
            field = v
            bmpPaint.colorFilter = if (v) android.graphics.ColorMatrixColorFilter(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )) else null
            pageFill.color = if (v) Color.BLACK else Color.WHITE
            invalidate()
        }

    fun setDocument(geoms: List<PageGeom>, renderer: PageRenderer, widgets: List<FormWidget>, items: MutableList<EditItem>) {
        pageWords.clear()
        selPage = -1; selA = -1; selB = -1; pendingSel = null
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
        scrollTo(scrollXf, (pageTop[i] - topPad) * zoom - insetTop)
    }

    /**
     * Space covered by floating bars at the top and bottom (screen pixels). The first page starts
     * below the top bar and the last page can scroll above the bottom bar; what's on screen
     * doesn't jump when the bars show or hide.
     */
    fun setInsets(top: Float, bottom: Float) {
        if (top == insetTop && bottom == insetBottom) return
        val dTop = top - insetTop
        insetTop = top
        insetBottom = bottom
        relayout()
        scrollYf += dTop * zoom
        clampScroll()
        onViewportChanged()
        invalidate()
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

    /** Width of [page] on screen right now, in pixels. */
    fun pageScreenWidth(page: Int): Float = if (page in geoms.indices && pageTop.isNotEmpty()) pageScreenRect(page).width() else width.toFloat()

    fun pageSizePt(page: Int): PointF = geoms.getOrNull(page)?.let { PointF(it.dispW, it.dispH) } ?: PointF(595f, 842f)

    fun release() {
        handler.removeCallbacksAndMessages(null)
        baseCache.evictAll()
        tiles.clear()
    }

    // ======================= layout =======================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bgPaint.shader = null
        if (w == oldw && oldw != 0) {
            // only the height changed (keyboard opened/closed): keep position, layout is width-based
            clampScroll()
            onViewportChanged()
            return
        }
        val keepPage = currentPage
        relayout()
        if (keepPage > 0) goToPage(keepPage)
        baseCache.evictAll()
        tiles.clear()
    }

    /** Where a form field is on screen right now. */
    fun widgetScreenRect(w: FormWidget): RectF? {
        if (w.page !in geoms.indices || pageTop.isEmpty()) return null
        return widgetRect(w, pageScreenRect(w.page))
    }

    /** Scrolls so that [r] (screen coordinates) is fully visible with a margin. */
    fun ensureVisible(r: RectF) {
        val margin = 24 * density
        var dy = 0f
        if (r.bottom > height - insetBottom - margin) dy = r.bottom - (height - insetBottom - margin)
        if (r.top - dy < insetTop + margin) dy = r.top - insetTop - margin
        var dx = 0f
        if (r.right > width - margin) dx = r.right - (width - margin)
        if (r.left - dx < margin) dx = r.left - margin
        if (dx != 0f || dy != 0f) scrollTo(scrollXf + dx, scrollYf + dy)
    }

    private fun relayout() {
        if (width == 0 || geoms.isEmpty()) return
        contentW = width - 2 * hMargin
        pageTop = FloatArray(geoms.size)
        pageH = FloatArray(geoms.size)
        var y = topPad + insetTop
        for (i in geoms.indices) {
            val g = geoms[i]
            pageTop[i] = y
            pageH[i] = contentW * g.dispH / g.dispW
            y += pageH[i] + gap
        }
        totalH = y - gap + bottomPad + insetBottom
        clampScroll()
    }

    private fun maxScrollX() = max(0f, width * zoom - width)
    private fun maxScrollY() = max(0f, totalH * zoom - height)

    private fun clampScroll() {
        scrollXf = scrollXf.coerceIn(0f, maxScrollX())
        val content = totalH * zoom
        // everything fits (e.g. a single page): centre it vertically instead of sticking to the top
        scrollYf = if (content < height && geoms.isNotEmpty()) -(height - content) / 2f
        else scrollYf.coerceIn(0f, maxScrollY())
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
        listener?.onViewportChanged()
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
        if (bgPaint.shader == null && height > 0) {
            bgPaint.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(), bgTop, bgBottom, android.graphics.Shader.TileMode.CLAMP)
        }
        if (bgPaint.shader != null) canvas.drawPaint(bgPaint) else canvas.drawColor(bgColor)
        if (geoms.isEmpty() || pageTop.isEmpty()) return
        val range = visibleRange()
        if (range.isEmpty()) return
        wantFirst = range.first - 1
        wantLast = range.last + 1

        for (i in range) {
            val r = pageScreenRect(i)
            if (softShadow) canvas.drawRect(r, pageGlow)
            else canvas.drawRect(r.left + 2 * density, r.top + 3 * density, r.right + 2 * density, r.bottom + 3 * density, pageShadow)
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
            drawPickOutlines(canvas, i, r)
            drawWidgets(canvas, i, r)
            drawItems(canvas, i, r)
            drawSearchHits(canvas, i, r)
        }
        // prefetch neighbours
        if (range.last + 1 < geoms.size) requestBase(range.last + 1)
        if (range.first - 1 >= 0) requestBase(range.first - 1)
        // drop tiles that scrolled away
        tiles.keys.retainAll { it in range }

        drawSelection(canvas)
        drawTextSelection(canvas)
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
            if (f === editingField) {
                canvas.drawRect(r, fieldCover)
            } else if (f.changed || (drawAllValues && f.value.isNotEmpty() && f.value != "Off")) {
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
        val g = geoms[item.page]
        val f = ItemRender.bounds(item, g.dispW, g.dispH)
        return RectF(pr.left + f.left * pr.width(), pr.top + f.top * pr.height(),
            pr.left + f.right * pr.width(), pr.top + f.bottom * pr.height())
    }

    /** Replaced text being typed over on the page: only its cover is drawn (the typing box shows the words). */
    var typingItem: ReplacedText? = null
        set(v) { field = v; invalidate() }
    private val coverPaint = Paint()

    private fun drawItems(canvas: Canvas, page: Int, pr: RectF) {
        val s = pxPerPt(page)
        for (item in items) {
            if (item.page != page) continue
            if (item === typingItem) {
                val c = (item as ReplacedText).cover
                coverPaint.color = item.coverColor
                canvas.drawRect(pr.left + c.left * pr.width(), pr.top + c.top * pr.height(),
                    pr.left + c.right * pr.width(), pr.top + c.bottom * pr.height(), coverPaint)
                continue
            }
            ItemRender.draw(canvas, item, pr, s, { sigBitmap(it) }, item === selected)
        }
    }

    /** A page-fraction rectangle on screen right now. */
    fun fracToScreen(page: Int, r: RectF): RectF? {
        if (page !in geoms.indices || pageTop.isEmpty()) return null
        val pr = pageScreenRect(page)
        return RectF(pr.left + r.left * pr.width(), pr.top + r.top * pr.height(), pr.left + r.right * pr.width(), pr.top + r.bottom * pr.height())
    }

    /** Screen pixels per PDF point on [page] at the current zoom. */
    fun pxPerPtOn(page: Int): Float = if (page in geoms.indices && pageTop.isNotEmpty()) pxPerPt(page) else 1f

    /** Precise hit test: thin items (pen strokes, lines) only respond near the stroke. */
    private fun hits(item: EditItem, xIn: Float, yIn: Float, extra: Float = 0f): Boolean {
        val r = itemRect(item) ?: return false
        // undo the item's rotation so we can test against its unrotated box
        val local = rot(xIn, yIn, r.centerX(), r.centerY(), -item.rotationDeg)
        val x = local.x; val y = local.y
        val slop = 14 * density + extra
        val grow = RectF(r)
        grow.inset(-slop / 2, -slop / 2)
        if (!grow.contains(x, y)) return false
        val pr = pageScreenRect(item.page)
        fun sp(p: PointF) = PointF(pr.left + p.x * pr.width(), pr.top + p.y * pr.height())
        val touch = PointF(x, y)
        return when (item) {
            is InkItem -> {
                if (item.points.size < 2) true
                else (1 until item.points.size).any {
                    ItemRender.distToSegment(touch, sp(item.points[it - 1]), sp(item.points[it])) < slop
                }
            }
            is ShapeItem -> if (item.isLine) {
                ItemRender.distToSegment(touch, sp(PointF(item.fx, item.fy)), sp(PointF(item.fx + item.fw, item.fy + item.fh))) < slop
            } else true
            else -> true
        }
    }

    /** Rotates screen point (x, y) around (cx, cy) by [deg] clockwise. */
    private fun rot(x: Float, y: Float, cx: Float, cy: Float, deg: Float): PointF {
        if (deg == 0f) return PointF(x, y)
        val a = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
        val dx = x - cx; val dy = y - cy
        return PointF(cx + dx * c - dy * sn, cy + dx * sn + dy * c)
    }

    /** Positions of the selection handles (delete, resize, rotate) for the selected item. */
    private class Handles(val box: RectF, val deg: Float, val del: PointF, val resize: PointF, val rotate: PointF?, val stemFrom: PointF?)

    private fun handles(s: EditItem): Handles? {
        val r = itemRect(s) ?: return null
        val pad = 3 * density
        val box = RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        val deg = s.rotationDeg
        val cx = box.centerX(); val cy = box.centerY()
        val del = rot(box.right, box.top, cx, cy, deg)
        val res = rot(box.right, box.bottom, cx, cy, deg)
        val rotH = if (s.canRotate) rot(cx, box.top - 34 * density, cx, cy, deg) else null
        val stem = if (s.canRotate) rot(cx, box.top, cx, cy, deg) else null
        return Handles(box, deg, del, res, rotH, stem)
    }

    private fun drawSelection(canvas: Canvas) {
        val s = selected ?: return
        val h = handles(s) ?: return
        canvas.save()
        canvas.rotate(h.deg, h.box.centerX(), h.box.centerY())
        canvas.drawRect(h.box, selBorder)
        canvas.restore()
        val k = handleR * 0.38f
        // rotate handle with stem
        if (h.rotate != null && h.stemFrom != null) {
            canvas.drawLine(h.stemFrom.x, h.stemFrom.y, h.rotate.x, h.rotate.y, selBorder)
            canvas.drawCircle(h.rotate.x, h.rotate.y, handleR, resizePaint)
            canvas.drawCircle(h.rotate.x, h.rotate.y, handleR, handleRing)
            val arc = RectF(h.rotate.x - k * 1.3f, h.rotate.y - k * 1.3f, h.rotate.x + k * 1.3f, h.rotate.y + k * 1.3f)
            canvas.drawArc(arc, -60f, 270f, false, handleIcon)
        }
        // delete
        canvas.drawCircle(h.del.x, h.del.y, handleR, delPaint)
        canvas.drawCircle(h.del.x, h.del.y, handleR, handleRing)
        canvas.drawLine(h.del.x - k, h.del.y - k, h.del.x + k, h.del.y + k, handleIcon)
        canvas.drawLine(h.del.x - k, h.del.y + k, h.del.x + k, h.del.y - k, handleIcon)
        // resize
        canvas.drawCircle(h.resize.x, h.resize.y, handleR, resizePaint)
        canvas.drawCircle(h.resize.x, h.resize.y, handleR, handleRing)
        canvas.drawLine(h.resize.x - k, h.resize.y + k, h.resize.x + k, h.resize.y - k, handleIcon)
        if (mode == Mode.ITEM_ROTATE) {
            val label = "${s.rotationDeg.roundToInt()}°"
            canvas.drawText(label, h.box.centerX(), h.box.centerY() + bubbleText.textSize * 0.35f, bubbleText.apply { color = Color.rgb(36, 87, 245) })
            bubbleText.color = Color.WHITE
        }
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
        val track = height - thumbH - 2 * topPad - insetTop - insetBottom
        val m = maxScrollY()
        return topPad + insetTop + if (m > 0) scrollYf / m * track else 0f
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
            if (mode == Mode.PAN && tool != Tool.None && startDrawing(e.x, e.y)) mode = Mode.DRAW
            if (mode != Mode.PAN) parent?.requestDisallowInterceptTouchEvent(true)
        }
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && mode == Mode.DRAW) {
            // second finger: cancel the stroke and pan / zoom instead
            drawing?.let { items.remove(it) }
            drawing = null
            mode = Mode.PAN
            invalidate()
        }

        scaleDetector.onTouchEvent(e)
        gestureDetector.onTouchEvent(e)

        when (mode) {
            Mode.SCROLLBAR -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) {
                    val track = height - thumbH - 2 * topPad - insetTop - insetBottom
                    val t = ((e.y - thumbH / 2 - topPad - insetTop) / track).coerceIn(0f, 1f)
                    scrollTo(scrollXf, t * maxScrollY())
                }
            }
            Mode.ITEM_MOVE, Mode.ITEM_RESIZE, Mode.ITEM_ROTATE -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount == 1 && !scaleDetector.isInProgress) {
                    dragSelected(e.x - lastX, e.y - lastY)
                } else if (e.actionMasked == MotionEvent.ACTION_MOVE && e.pointerCount >= 2) {
                    twist(e)
                }
                if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) twistAngle = Float.NaN
            }
            Mode.SEL_START, Mode.SEL_END -> if (e.actionMasked == MotionEvent.ACTION_MOVE) dragSelectionHandle(e.x, e.y)
            Mode.DRAW -> if (e.actionMasked == MotionEvent.ACTION_MOVE) continueDrawing(e)
            Mode.PAN, Mode.NONE -> {}
        }
        lastX = e.x; lastY = e.y

        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (mode == Mode.ITEM_MOVE || mode == Mode.ITEM_RESIZE || mode == Mode.ITEM_ROTATE) listener?.onItemsChanged()
            twistAngle = Float.NaN
            if (mode == Mode.SEL_START || mode == Mode.SEL_END) notifySelection()
            if (mode == Mode.DRAW) finishDrawing()
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
        if (selPage >= 0) {
            val (ha, hb) = selectionHandles() ?: Pair(null, null)
            val hit = 26 * density
            if (ha != null && hypot(x - ha.x, y - ha.y) < hit) return Mode.SEL_START
            if (hb != null && hypot(x - hb.x, y - hb.y) < hit) return Mode.SEL_END
        }
        val s = selected
        if (s != null) {
            val h = handles(s)
            if (h != null) {
                val hit = handleR * 1.9f
                if (hypot(x - h.del.x, y - h.del.y) < hit) {
                    items.remove(s)
                    select(null)
                    listener?.onItemsChanged()
                    return Mode.NONE
                }
                if (h.rotate != null && hypot(x - h.rotate.x, y - h.rotate.y) < hit) return Mode.ITEM_ROTATE
                if (hypot(x - h.resize.x, y - h.resize.y) < hit) return Mode.ITEM_RESIZE
                if (hits(s, x, y, extra = 10 * density)) return Mode.ITEM_MOVE
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
                // keep at least half of the item on the page
                val l = (r.left - pr.left) / pr.width(); val t = (r.top - pr.top) / pr.height()
                val fw = r.width() / pr.width(); val fh = r.height() / pr.height()
                val ddx = (dx / pr.width()).coerceIn(-fw * 0.5f - l, 1f - fw * 0.5f - l)
                val ddy = (dy / pr.height()).coerceIn(-fh * 0.5f - t, 1f - fh * 0.5f - t)
                ItemRender.moveBy(s, ddx, ddy)
            }
            Mode.ITEM_RESIZE -> {
                val r = itemRect(s) ?: return
                val cx = r.centerX(); val cy = r.centerY()
                val before = hypot(lastX - cx, lastY - cy)
                val after = hypot(lastX + dx - cx, lastY + dy - cy)
                if (before > 4 * density) scaleItem(s, (after / before).coerceIn(0.8f, 1.25f))
            }
            Mode.ITEM_ROTATE -> {
                val r = itemRect(s) ?: return
                val ang = Math.toDegrees(kotlin.math.atan2((lastY + dy - r.centerY()).toDouble(), (lastX + dx - r.centerX()).toDouble())).toFloat() + 90f
                s.rotationDeg = snap(ang)
            }
            else -> {}
        }
        invalidate()
    }

    private fun scaleItem(s: EditItem, factor: Float) {
        // keep the centre in place so rotated items don't drift
        val before = itemRect(s)
        ItemRender.scaleBy(s, factor)
        val after = itemRect(s)
        if (before != null && after != null && (s is SignatureItem || s is TextItem)) {
            val pr = pageScreenRect(s.page)
            ItemRender.moveBy(s, (before.centerX() - after.centerX()) / pr.width(), (before.centerY() - after.centerY()) / pr.height())
        }
        invalidate()
    }

    /** Snaps to 0 / 90 / 180 / 270 when within 6 degrees. */
    private fun snap(deg: Float): Float {
        val n = ((deg % 360f) + 360f) % 360f
        for (t in floatArrayOf(0f, 90f, 180f, 270f, 360f)) if (kotlin.math.abs(n - t) < 6f) return t % 360f
        return n
    }

    // two-finger twist on a selected item
    private var twistAngle = Float.NaN

    private fun twist(e: MotionEvent) {
        val s = selected ?: return
        if (!s.canRotate || e.pointerCount < 2) { twistAngle = Float.NaN; return }
        val a = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
        if (!twistAngle.isNaN()) {
            var d = a - twistAngle
            if (d > 180) d -= 360
            if (d < -180) d += 360
            s.rotationDeg = s.rotationDeg + d
            invalidate()
        }
        twistAngle = a
    }

    // ---- drawing tools ----

    private fun pageAt(x: Float, y: Float): Int? {
        for (i in visibleRange()) if (pageScreenRect(i).contains(x, y)) return i
        return null
    }

    private fun toFrac(page: Int, x: Float, y: Float): PointF {
        val pr = pageScreenRect(page)
        return PointF(((x - pr.left) / pr.width()).coerceIn(0f, 1f), ((y - pr.top) / pr.height()).coerceIn(0f, 1f))
    }

    private fun startDrawing(x: Float, y: Float): Boolean {
        val page = pageAt(x, y) ?: return false
        val p = toFrac(page, x, y)
        val item: EditItem = when (val t = tool) {
            is Tool.Pen -> InkItem(page, mutableListOf(p), t.color, t.widthPt, t.alpha)
            is Tool.Shape -> ShapeItem(page, t.kind, p.x, p.y, 0f, 0f, t.color, t.widthPt)
            Tool.None -> return false
        }
        drawing = item
        items.add(item)
        invalidate()
        return true
    }

    private fun continueDrawing(e: MotionEvent) {
        val d = drawing ?: return
        val p = toFrac(d.page, e.x, e.y)
        when (d) {
            is InkItem -> {
                val last = d.points.last()
                val pr = pageScreenRect(d.page)
                if (hypot((p.x - last.x) * pr.width(), (p.y - last.y) * pr.height()) > 1.5f * density) d.points.add(p)
            }
            is ShapeItem -> { d.fw = p.x - d.fx; d.fh = p.y - d.fy }
            else -> {}
        }
        invalidate()
    }

    private fun finishDrawing() {
        val d = drawing ?: return
        drawing = null
        if (d is ShapeItem && !d.isLine) {
            // normalise to a positive box
            if (d.fw < 0) { d.fx += d.fw; d.fw = -d.fw }
            if (d.fh < 0) { d.fy += d.fh; d.fh = -d.fh }
        }
        val pr = pageScreenRect(d.page)
        val tiny = when (d) {
            is ShapeItem -> hypot(d.fw * pr.width(), d.fh * pr.height()) < 8 * density
            else -> false
        }
        if (tiny) { items.remove(d); invalidate(); return }
        listener?.onItemAdded(d)
        invalidate()
    }

    /** Zooms in (if needed) so a field shown at [r] becomes at least [minHeightPx] tall. */
    fun zoomToFit(r: RectF, minHeightPx: Float) {
        if (r.height() >= minHeightPx) return
        val target = (zoom * minHeightPx / max(1f, r.height())).coerceAtMost(maxZoom)
        zoomAround(target, r.centerX(), r.centerY())
    }

    private fun handleTap(x: Float, y: Float) {
        listener?.onAnyTap()
        if (selPage >= 0 || pendingSel != null) { clearTextSelection(); return }
        // items first (topmost last in list)
        for (item in items.asReversed()) {
            if (hits(item, x, y)) {
                if (item === selected) listener?.onItemTapped(item) else select(item)
                return
            }
        }
        if (selected != null) {
            select(null)
            return
        }
        if (pickMode) {
            for (i in visibleRange()) {
                val pr = pageScreenRect(i)
                if (pr.contains(x, y)) { listener?.onPagePick(i, (x - pr.left) / pr.width(), (y - pr.top) / pr.height()); return }
            }
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
