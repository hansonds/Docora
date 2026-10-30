package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * "Edit PDF (beta)": finds the text lines, paragraphs and pictures on a page so they can be changed.
 * Changing text = covering the original with its background colour and writing the new words in a
 * matching style on top (how mobile PDF editors work). All positions are fractions of the displayed page.
 */

/** One line (or one cell / column piece of a line) of text. */
class TextLine(
    val page: Int,
    val text: String,
    /** Left / right edge of every character. */
    val xs: FloatArray,
    val xe: FloatArray,
    val top: Float,
    val bottom: Float,
    /** Baseline (fraction of page height). */
    val baseline: Float,
    val sizePt: Float,
    val bold: Boolean,
    val font: TextFont,
    /** Name of the PDF's own font (null for scanned text). */
    val fontName: String? = null,
    val italic: Boolean = false
) {
    val rect get() = RectF(xs.firstOrNull() ?: 0f, top, xe.lastOrNull() ?: 0f, bottom)
}

/** Consecutive lines that belong together. */
class TextBlock(val lines: List<TextLine>) {
    val rect: RectF get() = RectF(lines[0].rect).also { r -> lines.forEach { r.union(it.rect) } }
}

class ImageBox(val page: Int, val rect: RectF)

class PageLayout(val page: Int, val lines: List<TextLine>, val blocks: List<TextBlock>, val images: List<ImageBox>, val scanned: Boolean) {
    fun lineAt(x: Float, y: Float, slopX: Float, slopY: Float): TextLine? = lines
        .filter { val r = it.rect; r.inset(-slopX, -slopY); r.contains(x, y) }
        .minByOrNull { abs(it.rect.centerY() - y) }

    fun blockOf(line: TextLine): TextBlock = blocks.firstOrNull { line in it.lines } ?: TextBlock(listOf(line))

    fun imageAt(x: Float, y: Float): ImageBox? = images.filter { it.rect.contains(x, y) }
        .minByOrNull { it.rect.width() * it.rect.height() }
}

object BetaEdit {

    // ------------------------------------------------------------------ text from the PDF itself

    private class Glyph(val ch: Char, val l: Float, val r: Float, val base: Float, val size: Float, val font: PDFont?, val slanted: Boolean = false)

    fun pdfLayout(doc: PDDocument, page: Int, g: PageGeom): PageLayout {
        val w = g.dispW.coerceAtLeast(1f)
        val h = g.dispH.coerceAtLeast(1f)
        val rawLines = ArrayList<MutableList<Glyph?>>()   // null = word gap
        var cur = ArrayList<Glyph?>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (tp in textPositions) {
                    val u = tp.unicode ?: continue
                    val n = u.length.coerceAtLeast(1)
                    val cw = max(tp.width, 0.5f) / n
                    u.forEachIndexed { i, ch ->
                        if (ch.isWhitespace()) cur.add(null)
                        else cur.add(Glyph(ch, tp.x + cw * i, tp.x + cw * (i + 1), tp.y, sizeOf(tp), tp.font, slanted(tp)))
                    }
                }
            }
            override fun writeWordSeparator() { cur.add(null) }
            override fun writeLineSeparator() { if (cur.isNotEmpty()) rawLines.add(cur); cur = ArrayList() }
        }
        stripper.sortByPosition = true
        stripper.startPage = page + 1
        stripper.endPage = page + 1
        stripper.getText(doc)
        if (cur.isNotEmpty()) rawLines.add(cur)

        val lines = ArrayList<TextLine>()
        for (raw in rawLines) {
            // split a line where there is a wide gap (table cells, columns)
            var seg = ArrayList<Glyph?>()
            var prev: Glyph? = null
            for (gl in raw) {
                if (gl != null && prev != null && gl.l - prev.r > max(prev.size, gl.size) * 1.6f) {
                    makeLine(page, seg, w, h)?.let { lines.add(it) }
                    seg = ArrayList()
                }
                seg.add(gl)
                if (gl != null) prev = gl
            }
            makeLine(page, seg, w, h)?.let { lines.add(it) }
        }
        val images = try { imageBoxes(doc, page, g) } catch (_: Throwable) { emptyList() }
        return PageLayout(page, lines, blocks(lines), images, scanned = false)
    }

    /** Real size of the text on the page in points (font size × text and page scaling). */
    private fun sizeOf(tp: TextPosition): Float {
        val s = try { abs(tp.textMatrix.scalingFactorY) } catch (_: Throwable) { 0f }
        return if (s in 1f..400f) s else tp.fontSizeInPt.toFloat().coerceAtLeast(tp.heightDir)
    }

    private fun makeLine(page: Int, seg: List<Glyph?>, w: Float, h: Float): TextLine? {
        val glyphs = seg.filterNotNull()
        if (glyphs.isEmpty()) return null
        val sb = StringBuilder()
        val xs = ArrayList<Float>(); val xe = ArrayList<Float>()
        var lastRight = glyphs[0].l
        var pendingSpace = false
        for (gl in seg) {
            if (gl == null) { if (sb.isNotEmpty()) pendingSpace = true; continue }
            if (pendingSpace) { sb.append(' '); xs.add(lastRight / w); xe.add(gl.l / w); pendingSpace = false }
            sb.append(gl.ch); xs.add(gl.l / w); xe.add(gl.r / w)
            lastRight = gl.r
        }
        val size = median(glyphs.map { it.size }).coerceIn(3f, 300f)
        val base = median(glyphs.map { it.base })
        val font = glyphs.groupingBy { it.font }.eachCount().maxByOrNull { it.value }?.key
        return TextLine(
            page, sb.toString(), xs.toFloatArray(), xe.toFloatArray(),
            (base - size * 0.92f) / h, (base + size * 0.26f) / h, base / h,
            size,
            // the style most of the line's letters use
            glyphs.count { isBold(it.font) } * 2 > glyphs.size, family(font), try { font?.name } catch (_: Throwable) { null },
            glyphs.count { isItalic(it.font) || it.slanted } * 2 > glyphs.size
        )
    }

    private fun fontName(f: PDFont?) = (try { f?.name } catch (_: Throwable) { null } ?: "").lowercase()

    private val boldCache = HashMap<PDFont, Boolean>()
    private val italicCache = HashMap<PDFont, Boolean>()

    private fun isBold(f: PDFont?): Boolean {
        f ?: return false
        return boldCache.getOrPut(f) {
            val n = fontName(f).substringAfter('+')
            val style = n.substringAfterLast('-', "").substringAfterLast(',', n.substringAfterLast('-', ""))
            if (listOf("bold", "black", "heavy", "semibold", "demibold", "demi", "extrabold", "ultrabold").any { it in n }) return@getOrPut true
            // short style codes such as "Arial-BdMT", "Futura-Bd", "...,Bd"
            if (Regex("^(bd|bdit|bdi|blk|hv|sb|xb)").containsMatchIn(style)) return@getOrPut true
            val d = try { f.fontDescriptor } catch (_: Throwable) { null } ?: return@getOrPut false
            d.isForceBold || d.fontWeight >= 600f || (d.stemV >= 120f && d.fontWeight == 0f)
        }
    }

    private fun isItalic(f: PDFont?): Boolean {
        f ?: return false
        return italicCache.getOrPut(f) {
            val n = fontName(f).substringAfter('+')
            if (listOf("italic", "oblique", "inclined", "kursiv", "slanted").any { it in n }) return@getOrPut true
            val style = n.substringAfterLast('-', "").substringAfterLast(',', n.substringAfterLast('-', ""))
            if (Regex("^(it|bdit|bi|bolditalic|i$)").containsMatchIn(style) || style.endsWith("it")) return@getOrPut true
            val d = try { f.fontDescriptor } catch (_: Throwable) { null } ?: return@getOrPut false
            d.isItalic || kotlin.math.abs(d.italicAngle) > 4f
        }
    }

    /** Letters made italic by slanting (no italic font, just a skewed text matrix). */
    private fun slanted(tp: TextPosition): Boolean = try {
        val m = tp.textMatrix
        val a = m.getValue(0, 0); val b = m.getValue(0, 1); val c = m.getValue(1, 0); val d = m.getValue(1, 1)
        // upright text with a sideways lean of the vertical axis
        abs(b) < abs(a) * 0.05f && abs(c) > abs(d) * 0.12f && abs(c) < abs(d) * 0.6f
    } catch (_: Throwable) { false }

    private fun family(f: PDFont?): TextFont {
        val n = fontName(f)
        if (listOf("courier", "mono", "consol", "typewriter").any { it in n }) return TextFont.MONO
        if (listOf("times", "serif", "roman", "georgia", "garamond", "cambria", "book", "minion", "palatino", "baskerville")
                .any { it in n } && "sans" !in n) return TextFont.SERIF
        val d = try { f?.fontDescriptor } catch (_: Throwable) { null }
        if (d != null) {
            if (d.isFixedPitch) return TextFont.MONO
            if (d.isSerif && "sans" !in n) return TextFont.SERIF
        }
        return TextFont.SANS
    }

    private fun median(v: List<Float>): Float {
        if (v.isEmpty()) return 0f
        val s = v.sorted()
        return s[s.size / 2]
    }

    // ------------------------------------------------------------------ text read from a scan (OCR)

    fun ocrLayout(page: Int, words: List<PdfDocView.Word>, g: PageGeom): PageLayout {
        val lines = ArrayList<TextLine>()
        var cur = ArrayList<PdfDocView.Word>()
        fun flush() {
            if (cur.isEmpty()) return
            val top = cur.minOf { it.r.top }
            val bottom = cur.maxOf { it.r.bottom }
            val sizePt = ((bottom - top) * g.dispH / 1.22f).coerceIn(3f, 300f)
            val sb = StringBuilder()
            val xs = ArrayList<Float>(); val xe = ArrayList<Float>()
            cur.forEachIndexed { wi, wd ->
                if (wi > 0) { sb.append(' '); xs.add(cur[wi - 1].r.right); xe.add(wd.r.left) }
                val n = wd.text.length.coerceAtLeast(1)
                wd.text.forEachIndexed { i, ch ->
                    sb.append(ch)
                    xs.add(wd.r.left + wd.r.width() * i / n); xe.add(wd.r.left + wd.r.width() * (i + 1) / n)
                }
            }
            val baseline = top + sizePt * TextRender.ASCENT / g.dispH
            lines.add(TextLine(page, sb.toString(), xs.toFloatArray(), xe.toFloatArray(), top, bottom, baseline, sizePt, false, TextFont.SANS))
            cur = ArrayList()
        }
        for (wd in words) {
            // a big jump to the right on the same line starts a new piece (columns, tables)
            val last = cur.lastOrNull()
            if (last != null && wd.r.left - last.r.right > (last.r.height()) * 2.2f) flush()
            cur.add(wd)
            if (wd.lineEnd) flush()
        }
        flush()
        return PageLayout(page, lines, blocks(lines), emptyList(), scanned = true)
    }

    // ------------------------------------------------------------------ paragraphs

    /** Groups lines into paragraphs: same column, similar size, normal line spacing. */
    private fun blocks(lines: List<TextLine>): List<TextBlock> {
        val sorted = lines.sortedWith(compareBy({ it.baseline }, { it.xs.firstOrNull() ?: 0f }))
        val used = HashSet<TextLine>()
        val out = ArrayList<TextBlock>()
        for (start in sorted) {
            if (start in used) continue
            val block = arrayListOf(start)
            used.add(start)
            var last = start
            while (true) {
                val next = sorted.firstOrNull { c ->
                    c !in used && c.baseline > last.baseline &&
                        sameColumn(last, c) &&
                        abs(c.sizePt - last.sizePt) <= last.sizePt * 0.15f &&
                        (c.baseline - last.baseline) < (last.bottom - last.top) * 1.9f
                } ?: break
                block.add(next); used.add(next); last = next
            }
            out.add(TextBlock(block))
        }
        return out
    }

    private fun sameColumn(a: TextLine, b: TextLine): Boolean {
        val ra = a.rect; val rb = b.rect
        val overlap = min(ra.right, rb.right) - max(ra.left, rb.left)
        return overlap > min(ra.width(), rb.width()) * 0.3f && abs(ra.left - rb.left) < max(ra.width(), rb.width()) * 0.6f
    }

    // ------------------------------------------------------------------ alignment

    /** Alignment of a paragraph, from where its lines start and end. */
    fun blockAlign(lines: List<TextLine>, g: PageGeom): TextAlign {
        if (lines.size < 2) return singleAlign(lines.firstOrNull() ?: return TextAlign.LEFT)
        val size = lines.map { it.sizePt }.sorted()[lines.size / 2]
        val tol = size * 0.7f / g.dispW
        fun spread(v: List<Float>) = (v.maxOrNull() ?: 0f) - (v.minOrNull() ?: 0f)
        val lefts = lines.map { it.rect.left }
        val rights = lines.map { it.rect.right }
        val centers = lines.map { it.rect.centerX() }
        val leftOk = spread(lefts) < tol
        val bodyRights = rights.dropLast(1)
        return when {
            leftOk && bodyRights.size >= 2 && spread(bodyRights) < tol -> TextAlign.JUSTIFY
            !leftOk && spread(centers) < tol -> TextAlign.CENTER
            !leftOk && spread(rights) < tol -> TextAlign.RIGHT
            else -> TextAlign.LEFT
        }
    }

    /** A line on its own: centred headings sit in the middle of the page. */
    private fun singleAlign(line: TextLine): TextAlign {
        val r = line.rect
        return if (kotlin.math.abs(r.centerX() - 0.5f) < 0.015f && r.left > 0.12f) TextAlign.CENTER else TextAlign.LEFT
    }

    /** Alignment for editing just [line]: follows its paragraph (a justified line stays full width). */
    fun lineAlign(lay: PageLayout, line: TextLine, g: PageGeom): TextAlign {
        val block = lay.blockOf(line)
        if (block.lines.size < 2) return singleAlign(line)
        return when (blockAlign(block.lines, g)) {
            TextAlign.JUSTIFY -> if (line === block.lines.last()) TextAlign.LEFT else TextAlign.JUSTIFY_ALL
            TextAlign.CENTER -> TextAlign.CENTER
            TextAlign.RIGHT -> TextAlign.RIGHT
            else -> TextAlign.LEFT
        }
    }

    // ------------------------------------------------------------------ pictures

    /** Where each picture is drawn on the page. */
    private fun imageBoxes(doc: PDDocument, page: Int, g: PageGeom): List<ImageBox> {
        val out = ArrayList<ImageBox>()
        val engine = object : PDFStreamEngine() {
            init {
                addOperator(Concatenate())
                addOperator(DrawObject())
                addOperator(SetGraphicsStateParameters())
                addOperator(Save())
                addOperator(Restore())
                addOperator(SetMatrix())
            }

            override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
                if (operator.name == "Do") {
                    val name = operands.firstOrNull() as? COSName
                    if (name != null && resources?.isImageXObject(name) == true) {
                        val m = graphicsState.currentTransformationMatrix
                        val pts = listOf(
                            m.transformPoint(0f, 0f), m.transformPoint(1f, 0f),
                            m.transformPoint(0f, 1f), m.transformPoint(1f, 1f)
                        ).map { PointF(it.x, it.y) }
                        val r = g.userRectToFrac(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
                        r.intersect(0f, 0f, 1f, 1f)
                        // skip tiny dots and full-page backgrounds (e.g. a scanned page)
                        if (r.width() > 0.02f && r.height() > 0.01f && r.width() * r.height() < 0.85f) out.add(ImageBox(page, r))
                        return
                    }
                }
                super.processOperator(operator, operands)
            }
        }
        engine.processPage(doc.getPage(page))
        return out
    }

    // ------------------------------------------------------------------ colours

    /** The page colour just around [r] (most common colour on a thin ring outside it). */
    fun backgroundAround(bmp: Bitmap, r: RectF): Int {
        val w = bmp.width; val h = bmp.height
        val pad = max(2, (min(r.width() * w, r.height() * h) * 0.18f).toInt())
        val l = (r.left * w).toInt() - pad; val t = (r.top * h).toInt() - pad
        val rr = (r.right * w).toInt() + pad; val b = (r.bottom * h).toInt() + pad
        val counts = HashMap<Int, Int>()
        val samples = HashMap<Int, IntArray>()
        fun take(x: Int, y: Int) {
            if (x !in 0 until w || y !in 0 until h) return
            val c = bmp.getPixel(x, y)
            val q = (Color.red(c) / 16 shl 8) or (Color.green(c) / 16 shl 4) or (Color.blue(c) / 16)
            counts[q] = (counts[q] ?: 0) + 1
            val s = samples.getOrPut(q) { IntArray(4) }
            s[0] += Color.red(c); s[1] += Color.green(c); s[2] += Color.blue(c); s[3]++
        }
        val stepX = max(1, (rr - l) / 60); val stepY = max(1, (b - t) / 30)
        var x = l
        while (x <= rr) { take(x, t); take(x, b); x += stepX }
        var y = t
        while (y <= b) { take(l, y); take(rr, y); y += stepY }
        val best = counts.maxByOrNull { it.value }?.key ?: return Color.WHITE
        val s = samples[best]!!
        return Color.rgb(s[0] / s[3], s[1] / s[3], s[2] / s[3])
    }

    /** The ink colour of the text inside [r] (pixels furthest from the background). */
    fun inkInside(bmp: Bitmap, r: RectF, bg: Int): Int {
        val w = bmp.width; val h = bmp.height
        val l = (r.left * w).toInt().coerceIn(0, w - 1); val t = (r.top * h).toInt().coerceIn(0, h - 1)
        val rr = (r.right * w).toInt().coerceIn(0, w - 1); val b = (r.bottom * h).toInt().coerceIn(0, h - 1)
        val px = ArrayList<Pair<Int, Int>>()
        val step = max(1, ((rr - l) * (b - t) / 4000))
        var i = 0
        for (yy in t..b) for (xx in l..rr) {
            if (i++ % step != 0) continue
            val c = bmp.getPixel(xx, yy)
            val d = abs(Color.red(c) - Color.red(bg)) + abs(Color.green(c) - Color.green(bg)) + abs(Color.blue(c) - Color.blue(bg))
            if (d > 90) px.add(Pair(d, c))
        }
        if (px.isEmpty()) return Color.BLACK
        val top = px.sortedByDescending { it.first }.take(max(1, px.size / 3))
        var sr = 0; var sg = 0; var sb = 0
        top.forEach { sr += Color.red(it.second); sg += Color.green(it.second); sb += Color.blue(it.second) }
        val n = top.size
        return Color.rgb(sr / n, sg / n, sb / n)
    }

    // ------------------------------------------------------------------ bold / italic from the letters themselves

    /** What the ink of one line looks like: stroke thickness (relative to the text size) and slant. */
    class InkStyle(val stroke: Float, val italic: Boolean)

    /**
     * Measures a line on the rendered page: how thick the strokes are (bold) and whether the letters
     * lean (italic). Works for scans and for PDFs whose fonts don't say what they are.
     */
    fun inkStyle(bmp: Bitmap, r: RectF, bg: Int, ink: Int): InkStyle? {
        val w = bmp.width; val h = bmp.height
        val l = (r.left * w).toInt().coerceIn(0, w - 1); val t = (r.top * h).toInt().coerceIn(0, h - 1)
        val rr = (r.right * w).toInt().coerceIn(l + 1, w); val b = (r.bottom * h).toInt().coerceIn(t + 1, h)
        val cw = rr - l; val ch = b - t
        if (cw < 8 || ch < 6) return null
        val px = IntArray(cw * ch)
        bmp.getPixels(px, 0, cw, l, t, cw, ch)
        fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        val mid = (lum(bg) + lum(ink)) / 2
        val darkInk = lum(ink) < lum(bg)
        val on = BooleanArray(px.size) { val v = lum(px[it]); if (darkInk) v < mid else v > mid }
        // stroke thickness: typical length of the short horizontal ink runs (the stems of letters)
        val runs = ArrayList<Int>()
        for (y in 0 until ch) {
            var run = 0
            for (x in 0 until cw) {
                if (on[y * cw + x]) run++ else { if (run > 0) runs.add(run); run = 0 }
            }
            if (run > 0) runs.add(run)
        }
        if (runs.size < 12) return null
        runs.sort()
        val stem = runs[runs.size * 2 / 5].toFloat()           // below the median: skips horizontal bars
        val stroke = stem / ch
        // slant: undo a lean of 0°…16° and see which makes the letters stand up straightest
        val cy = ch / 2f
        fun sharpness(s: Float): Double {
            val cols = IntArray(cw + ch)
            for (y in 0 until ch) {
                val shift = Math.round(s * (y - cy)) + ch / 2
                for (x in 0 until cw) if (on[y * cw + x]) { val c = x + shift; if (c in cols.indices) cols[c]++ }
            }
            var sum = 0.0
            for (c in cols) sum += c.toDouble() * c
            return sum
        }
        val upright = sharpness(0f)
        val best = listOf(0.14f, 0.2f, 0.27f, 0.34f).maxOf { sharpness(it) }
        val back = listOf(-0.14f, -0.2f).maxOf { sharpness(it) }
        val italic = best > upright * 1.06 && best > back * 1.04
        return InkStyle(stroke, italic)
    }

    /** Typical stroke thickness of the body text on a page (to tell bold lines apart). */
    fun pageStroke(bmp: Bitmap, lines: List<TextLine>, inkOf: (TextLine) -> Pair<Int, Int>): Float? {
        val sample = lines.filter { it.text.length >= 6 }.let { l -> if (l.size > 30) l.shuffled(java.util.Random(7)).take(30) else l }
        val strokes = sample.mapNotNull { line -> val (bg, ink) = inkOf(line); inkStyle(bmp, line.rect, bg, ink)?.stroke }
        if (strokes.size < 3) return null
        return strokes.sorted()[strokes.size / 2]
    }

    /** A piece of the rendered page as its own picture (to move or resize an existing image). */
    fun crop(bmp: Bitmap, r: RectF): Bitmap? {
        val l = (r.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val t = (r.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = (r.width() * bmp.width).toInt().coerceIn(1, bmp.width - l)
        val h = (r.height() * bmp.height).toInt().coerceIn(1, bmp.height - t)
        return try { Bitmap.createBitmap(bmp, l, t, w, h) } catch (_: Throwable) { null }
    }

    /** A cover rectangle a little larger than the text, so no edges of the old letters show. */
    fun coverFor(r: RectF, sizePt: Float, g: PageGeom): RectF {
        val px = sizePt * 0.12f / g.dispW
        val py = sizePt * 0.08f / g.dispH
        return RectF(r.left - px, r.top - py, r.right + px, r.bottom + py)
    }
}
