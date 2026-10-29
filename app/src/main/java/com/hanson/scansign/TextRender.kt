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

    fun typeface(font: TextFont, bold: Boolean, italic: Boolean = false): Typeface {
        val family = when (font) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONO -> Typeface.MONOSPACE
        }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }

    fun lines(text: String): List<String> = text.split('\n').ifEmpty { listOf("") }

    fun paint(item: TextItem, pxPerPt: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(item.font, item.bold, item.italic)
        textSize = item.sizePt * pxPerPt
        color = item.color
        textScaleX = item.scaleX
    }

    /**
     * Breaks [text] into lines no wider than [widthPt] (word by word; very long words are split),
     * keeping the user's own line breaks.
     */
    fun wrap(item: TextItem, text: String, widthPt: Float): String {
        if (widthPt <= 0f) return text
        val p = paint(item, 1f)
        // tiny tolerance: text measured to exactly the original width must still fit
        @Suppress("NAME_SHADOWING") val widthPt = widthPt * 1.002f + 0.3f
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val cand = if (line.isEmpty()) word else "$line $word"
                if (p.measureText(cand) <= widthPt || line.isEmpty() && p.measureText(word) <= widthPt) { line = cand; continue }
                if (line.isNotEmpty()) out.add(line)
                // a word longer than the whole width: split it
                var w = word
                while (p.measureText(w) > widthPt && w.length > 1) {
                    var n = w.length - 1
                    while (n > 1 && p.measureText(w.substring(0, n)) > widthPt) n--
                    out.add(w.substring(0, n)); w = w.substring(n)
                }
                line = w
            }
            out.add(line)
        }
        return out.joinToString("\n")
    }

    /** A piece of text placed on a line: [x] and [w] in points from the left of the text box. */
    class Run(val text: String, val x: Float, val w: Float)

    /** Width of the box lines are aligned in (the original column for replaced text). */
    fun boxWidthPt(item: TextItem): Float {
        val p = paint(item, 1f)
        val widest = lines(item.text).maxOf { p.measureText(it) }
        val wrap = (item as? ReplacedText)?.wrapPt ?: 0f
        return if (item.align != TextAlign.LEFT && wrap > 0f) kotlin.math.max(wrap, widest) else widest
    }

    /**
     * Where every line (and, for justified text, every word) goes. Used for both the screen and
     * the saved PDF, so they match exactly.
     */
    fun layout(item: TextItem): List<List<Run>> {
        val p = paint(item, 1f)
        val ls = lines(item.text)
        val box = boxWidthPt(item)
        return ls.mapIndexed { k, line ->
            val w = p.measureText(line)
            val justify = (item.align == TextAlign.JUSTIFY && k < ls.size - 1) || item.align == TextAlign.JUSTIFY_ALL
            val words = line.split(' ').filter { it.isNotEmpty() }
            when {
                line.isEmpty() -> emptyList()
                justify && words.size > 1 && box > w -> {
                    val widths = words.map { p.measureText(it) }
                    val gap = (box - widths.sum()) / (words.size - 1)
                    var x = 0f
                    words.mapIndexed { i, word -> Run(word, x, widths[i]).also { x += widths[i] + gap } }
                }
                item.align == TextAlign.CENTER -> listOf(Run(line, (box - w) / 2f, w))
                item.align == TextAlign.RIGHT -> listOf(Run(line, box - w, w))
                else -> listOf(Run(line, 0f, w))
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

    /** Draws the text with its top-left at (left, top) in a canvas scaled by [pxPerPt]. */
    fun draw(canvas: Canvas, item: TextItem, left: Float, top: Float, pxPerPt: Float) {
        val p = paint(item, pxPerPt)
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
        layout(item).forEachIndexed { k, runs ->
            val base = t + size * (ASCENT + item.lineGap * k)
            for (r in runs) canvas.drawText(r.text, l + r.x * pxPerPt, base, p)
        }
    }
}
