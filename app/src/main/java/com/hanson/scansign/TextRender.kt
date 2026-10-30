package com.hanson.scansign

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Shared text layout so the on-screen preview and the saved PDF line up.
 * Line k's baseline sits at top + size * (ASCENT + LINE * k); box height = lines * LINE * size.
 */
object TextRender {
    const val ASCENT = 0.95f
    const val LINE = 1.2f
    /** Padding inside a stamp frame, as a fraction of the font size. */
    const val PAD = 0.4f
    /** Stamp frame line width, as a fraction of the font size. */
    const val FRAME = 0.12f

    // Docora carries its own fonts (Liberation Sans / Serif) so bold and italic look the same on every
    // phone — some phones (custom system fonts) ignore bold / italic requests — and the saved PDF
    // uses exactly the same letters as the screen.
    private var assets: android.content.res.AssetManager? = null
    private val faces = HashMap<String, Typeface>()

    private var cacheDir: java.io.File? = null

    fun init(ctx: android.content.Context) { assets = ctx.applicationContext.assets; cacheDir = ctx.applicationContext.cacheDir }

    /** The PDF's own fonts (for replaced text), by PDF font name, with their style. */
    private class PdfFace(val face: Typeface, val bold: Boolean, val italic: Boolean)
    private val pdfFaces = HashMap<String, PdfFace>()

    fun hasPdfFace(name: String) = synchronized(pdfFaces) { pdfFaces.containsKey(name) }

    fun registerPdfFace(name: String, bytes: ByteArray, bold: Boolean, italic: Boolean) {
        val dir = java.io.File(cacheDir ?: return, "pdffonts").apply { mkdirs() }
        val file = java.io.File(dir, "f_" + Integer.toHexString(name.hashCode()) + ".ttf")
        try {
            if (!file.exists()) file.writeBytes(bytes)
            val face = Typeface.createFromFile(file)
            synchronized(pdfFaces) { pdfFaces[name] = PdfFace(face, bold, italic) }
        } catch (_: Throwable) {}
    }

    /**
     * Font for drawing [item]: for text that replaced original words, the PDF's own font (or its
     * bold / italic sibling, else the original made bold / slanted); otherwise Docora's fonts.
     * Returns the typeface and whether bold / italic must be added on top.
     */
    /** Typeface to draw with, bold / italic to add on top, and whether it is the PDF's own font. */
    class Face(val face: Typeface, val fakeBold: Boolean, val fakeItalic: Boolean, val fromPdf: Boolean)

    fun faceFor(item: TextItem): Face = faceFor(item, item.bold, item.italic)

    fun faceFor(item: TextItem, bold: Boolean, italic: Boolean): Face {
        val orig = (item as? ReplacedText)?.origFont
        if (orig != null) {
            synchronized(pdfFaces) {
                val fam = BetaEdit.familyKey(orig)
                val same = pdfFaces.entries.filter { BetaEdit.familyKey(it.key) == fam }
                same.firstOrNull { it.value.bold == bold && it.value.italic == italic }?.let { return Face(it.value.face, false, false, true) }
                val o = pdfFaces[orig]
                if (o != null && (!o.bold || bold) && (!o.italic || italic))
                    return Face(o.face, bold && !o.bold, italic && !o.italic, true)
                same.firstOrNull { !it.value.bold && !it.value.italic }?.let { return Face(it.value.face, bold, italic, true) }
            }
        }
        val fake = faked(item.font)
        return Face(typeface(item.font, bold, italic), fake && bold, fake && italic, false)
    }

    /** The style of the item as a whole, as [Style] bits. */
    fun baseFlags(item: TextItem) = (if (item.bold) Style.BOLD else 0) or (if (item.italic) Style.ITALIC else 0)

    /** Style bits for every character of [text] (the whole item's style, with marked parts on top). */
    fun flagsOf(item: TextItem, text: String = item.text): IntArray {
        val f = IntArray(text.length) { baseFlags(item) }
        for (m in (item as? ReplacedText)?.marks ?: emptyList()) {
            for (i in m.start.coerceAtLeast(0) until m.end.coerceAtMost(text.length)) f[i] = m.flags
        }
        return f
    }

    /** Letter size factor: a stand-in font is resized so its capitals match the original's height. */
    fun glyphScale(item: TextItem): Float {
        val r = item as? ReplacedText ?: return 1f
        if (r.glyphScale == 1f) return 1f
        return if (faceFor(item).fromPdf) 1f else r.glyphScale
    }

    /** Capital height of Docora's fonts (fraction of the font size). */
    fun standInCapHeight(font: TextFont) = when (font) { TextFont.SANS -> 0.688f; TextFont.SERIF -> 0.655f; TextFont.MONO -> 0.62f }

    /** The bundled font file for this style (null = Mono, which uses the phone's font). */
    fun fontFile(font: TextFont, bold: Boolean, italic: Boolean): String? {
        val fam = when (font) { TextFont.SANS -> "Sans"; TextFont.SERIF -> "Serif"; TextFont.MONO -> return null }
        val st = when { bold && italic -> "BoldItalic"; bold -> "Bold"; italic -> "Italic"; else -> "Regular" }
        return "fonts/Liberation$fam-$st.ttf"
    }

    fun openFont(file: String): java.io.InputStream? = try { assets?.open(file) } catch (_: Throwable) { null }

    /** True when bold / italic have to be drawn by thickening / slanting (no real font for it). */
    private fun faked(font: TextFont) = fontFile(font, false, false) == null || assets == null

    private fun systemFace(font: TextFont) = Typeface.create(when (font) {
        TextFont.SANS -> Typeface.SANS_SERIF
        TextFont.SERIF -> Typeface.SERIF
        TextFont.MONO -> Typeface.MONOSPACE
    }, Typeface.NORMAL)

    fun typeface(font: TextFont, bold: Boolean, italic: Boolean = false): Typeface {
        val file = fontFile(font, bold, italic)
        val a = assets
        if (file == null || a == null) return systemFace(font)
        return faces.getOrPut(file) { try { Typeface.createFromAsset(a, file) } catch (_: Throwable) { systemFace(font) } }
    }

    /** Puts the item's font on a text field (the typing box), including added bold / italic. */
    fun applyTo(tv: android.widget.TextView, item: TextItem) {
        val f = faceFor(item)
        tv.typeface = f.face
        tv.paint.isFakeBoldText = f.fakeBold
        tv.paint.textSkewX = if (f.fakeItalic) -0.22f else 0f
        tv.invalidate()
    }

    fun lines(text: String): List<String> = text.split('\n').ifEmpty { listOf("") }

    fun paint(item: TextItem, pxPerPt: Float): Paint = paint(item, pxPerPt, baseFlags(item))

    /** Paint for one style of an item (bold / italic from [flags]). */
    fun paint(item: TextItem, pxPerPt: Float, flags: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        val f = faceFor(item, flags and Style.BOLD != 0, flags and Style.ITALIC != 0)
        typeface = f.face
        isFakeBoldText = f.fakeBold
        textSkewX = if (f.fakeItalic) -0.22f else 0f
        textSize = item.sizePt * pxPerPt * (if (f.fromPdf) 1f else ((item as? ReplacedText)?.glyphScale ?: 1f))
        color = item.color
        textScaleX = item.scaleX
    }

    /** Measures text whose characters may have different styles (in points). */
    private class Measurer(val item: TextItem) {
        private val paints = HashMap<Int, Paint>()
        fun paint(flags: Int) = paints.getOrPut(flags and (Style.BOLD or Style.ITALIC)) { paint(item, 1f, flags) }
        fun width(text: String, flags: IntArray, from: Int, to: Int): Float {
            var w = 0f
            var i = from
            while (i < to) {
                var j = i + 1
                while (j < to && flags[j] and 3 == flags[i] and 3) j++
                w += paint(flags[i]).measureText(text, i, j)
                i = j
            }
            return w
        }
    }

    /**
     * Breaks [text] into lines no wider than [widthPt] by turning spaces into line breaks. Every
     * character stays in place (so styled parts stay on the right words); a word longer than a
     * whole line is not split.
     */
    fun wrapKeep(item: TextItem, text: String, widthPt: Float, firstIndent: Float = 0f, flags: IntArray = flagsOf(item, text)): String {
        if (widthPt <= 0f) return text
        val m = Measurer(item)
        val limit = widthPt * 1.002f + 0.3f
        val out = text.toCharArray()
        var lineStart = 0
        var lastSpace = -1
        var firstLine = true
        var i = 0
        while (i <= text.length) {
            val end = i == text.length || text[i] == '\n'
            if (end || text[i] == ' ') {
                val avail = limit - if (firstLine) firstIndent else 0f
                // the line so far (up to this space) is too wide: break at the previous space
                if (m.width(text, flags, lineStart, i) > avail && lastSpace > lineStart) {
                    out[lastSpace] = '\n'
                    lineStart = lastSpace + 1
                    firstLine = false
                    lastSpace = -1
                    continue   // measure the same word again on the new line
                }
                if (end) { lineStart = i + 1; lastSpace = -1; firstLine = false } else lastSpace = i
            }
            i++
        }
        return String(out)
    }

    /** Wraps replaced text at its line-break width, leaving room for the first-line indent. */
    fun wrapItem(item: ReplacedText, text: String): String {
        val w = if (item.breakPt > 0f) item.breakPt else item.wrapPt
        return wrapKeep(item, text, w, item.indentPt.coerceAtMost(w * 0.6f))
    }

    /** Older word-by-word wrapping, for text without styled parts (e.g. normal text boxes). */
    fun wrap(item: TextItem, text: String, widthPt: Float): String = wrapKeep(item, text, widthPt)

    /** A piece of text placed on a line: [x] and [w] in points from the left of the text box. */
    class Run(val text: String, val x: Float, val w: Float, val flags: Int = 0)

    /** Offsets where each line of [text] starts. */
    private fun lineStarts(text: String): List<Int> {
        val s = arrayListOf(0)
        text.forEachIndexed { i, c -> if (c == '\n') s.add(i + 1) }
        return s
    }

    /** Width of the box lines are aligned in (the original column for replaced text). */
    fun boxWidthPt(item: TextItem): Float {
        val m = Measurer(item)
        val text = item.text
        val flags = flagsOf(item)
        val indent = (item as? ReplacedText)?.indentPt ?: 0f
        val ls = lines(text)
        val starts = lineStarts(text)
        val widest = ls.indices.maxOfOrNull { k -> m.width(text, flags, starts[k], starts[k] + ls[k].length) + if (k == 0) indent else 0f } ?: 0f
        val wrap = (item as? ReplacedText)?.wrapPt ?: 0f
        return if (item.align != TextAlign.LEFT && wrap > 0f) kotlin.math.max(wrap, widest) else widest
    }

    /**
     * Where every piece of text goes: each line is split into parts of one style (and, for
     * justified text, into words). Used for both the screen and the saved PDF, so they match.
     */
    fun layout(item: TextItem): List<List<Run>> {
        val m = Measurer(item)
        val text = item.text
        val flags = flagsOf(item)
        val ls = lines(text)
        val starts = lineStarts(text)
        val fullBox = boxWidthPt(item)
        val indent = (item as? ReplacedText)?.indentPt ?: 0f

        /** Pieces of one style between [from] and [to], starting at x. */
        fun pieces(from: Int, to: Int, x0: Float): List<Run> {
            val out = ArrayList<Run>()
            var x = x0
            var i = from
            while (i < to) {
                var j = i + 1
                while (j < to && flags[j] == flags[i]) j++
                val w = m.width(text, flags, i, j)
                out.add(Run(text.substring(i, j), x, w, flags[i]))
                x += w
                i = j
            }
            return out
        }

        return ls.indices.map { k ->
            val from = starts[k]
            val to = from + ls[k].length
            val line = ls[k]
            val w = m.width(text, flags, from, to)
            val ind = if (k == 0 && (item.align == TextAlign.LEFT || item.align == TextAlign.JUSTIFY || item.align == TextAlign.JUSTIFY_ALL)) indent else 0f
            val box = fullBox - ind
            val justify = (item.align == TextAlign.JUSTIFY && k < ls.size - 1) || item.align == TextAlign.JUSTIFY_ALL
            // words of the line (start, end) for justified text
            val words = ArrayList<Pair<Int, Int>>()
            var ws = -1
            for (i in from..to) {
                val sp = i == to || text[i] == ' '
                if (!sp && ws < 0) ws = i
                if (sp && ws >= 0) { words.add(Pair(ws, i)); ws = -1 }
            }
            when {
                line.isEmpty() -> emptyList()
                justify && words.size > 1 && box > w -> {
                    val widths = words.map { (a, b) -> m.width(text, flags, a, b) }
                    val gap = (box - widths.sum()) / (words.size - 1)
                    var x = ind
                    words.flatMapIndexed { i, (a, b) -> pieces(a, b, x).also { x += widths[i] + gap } }
                }
                item.align == TextAlign.CENTER -> pieces(from, to, (fullBox - w) / 2f)
                item.align == TextAlign.RIGHT -> pieces(from, to, fullBox - w)
                else -> pieces(from, to, ind)
            }
        }
    }

    /** Width and height of the text box in points. */
    fun sizePt(item: TextItem): Pair<Float, Float> {
        val ls = lines(item.text)
        val w = boxWidthPt(item).coerceAtLeast(item.sizePt * 0.6f)
        val h = ((ls.size - 1) * item.lineGap + LINE) * item.sizePt
        if (item.boxed) {
            val pad = 2 * PAD * item.sizePt
            return Pair(w + pad, h + pad)
        }
        return Pair(w, h)
    }

    /** Underline position and thickness, as fractions of the font size below the baseline. */
    const val UNDER_POS = 0.12f
    const val UNDER_THICK = 0.06f

    /** Draws the text with its top-left at (left, top) in a canvas scaled by [pxPerPt]. */
    fun draw(canvas: Canvas, item: TextItem, left: Float, top: Float, pxPerPt: Float) {
        val size = item.sizePt * pxPerPt
        var l = left
        var t = top
        if (item.boxed) {
            val (wPt, hPt) = sizePt(item)
            val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = item.color; strokeWidth = FRAME * size
            }
            val half = frame.strokeWidth / 2
            canvas.drawRoundRect(
                left + half, top + half, left + wPt * pxPerPt - half, top + hPt * pxPerPt - half,
                size * 0.3f, size * 0.3f, frame
            )
            l += PAD * size
            t += PAD * size
        }
        val paints = HashMap<Int, Paint>()
        val under = Paint().apply { color = item.color }
        layout(item).forEachIndexed { k, runs ->
            val base = t + size * (ASCENT + item.lineGap * k)
            for (r in runs) {
                val p = paints.getOrPut(r.flags and 3) { paint(item, pxPerPt, r.flags) }
                val x = l + r.x * pxPerPt
                canvas.drawText(r.text, x, base, p)
                if (r.flags and Style.UNDERLINE != 0) {
                    canvas.drawRect(x, base + size * UNDER_POS, x + r.w * pxPerPt, base + size * (UNDER_POS + UNDER_THICK), under)
                }
            }
        }
    }
}
