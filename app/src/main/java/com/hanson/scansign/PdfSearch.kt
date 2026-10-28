package com.hanson.scansign

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Searchable text of one page: lower-cased text with single spaces, and for every character
 * its box as fractions of the displayed page (null for spaces).
 */
class PageText(val text: String, val boxes: List<RectF?>)

/** One match: the page and the highlight rectangles (page fractions, one per line it spans). */
class SearchHit(val page: Int, val rects: List<RectF>)

object PdfSearch {

    /** Text of [page] from the PDF itself (fast; empty for scanned pages). */
    fun pdfText(doc: PDDocument, page: Int, geom: PageGeom): PageText {
        val chars = StringBuilder()
        val boxes = ArrayList<RectF?>()
        val w = geom.dispW.coerceAtLeast(1f)
        val h = geom.dispH.coerceAtLeast(1f)
        fun space() {
            if (chars.isNotEmpty() && chars[chars.length - 1] != ' ') { chars.append(' '); boxes.add(null) }
        }
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (tp in textPositions) {
                    val u = tp.unicode ?: continue
                    // x / y follow the page rotation, origin top-left, y on the baseline
                    val gh = max(tp.heightDir, tp.fontSizeInPt * 0.7f).coerceAtLeast(2f)
                    val r = RectF(
                        tp.x / w, (tp.y - gh * 1.1f) / h,
                        (tp.x + max(tp.width, 1f)) / w, (tp.y + gh * 0.28f) / h
                    )
                    for (ch in u) {
                        if (ch.isWhitespace()) space() else { chars.append(ch.lowercaseChar()); boxes.add(r) }
                    }
                }
            }
            override fun writeWordSeparator() = space()
            override fun writeLineSeparator() = space()
        }
        stripper.sortByPosition = true
        stripper.startPage = page + 1
        stripper.endPage = page + 1
        stripper.getText(doc)
        return PageText(chars.toString(), boxes)
    }

    /** Text of a scanned page from recognised words. */
    fun ocrText(words: List<PdfDocView.Word>): PageText {
        val chars = StringBuilder()
        val boxes = ArrayList<RectF?>()
        for (wd in words) {
            if (chars.isNotEmpty()) { chars.append(' '); boxes.add(null) }
            val n = wd.text.length.coerceAtLeast(1)
            wd.text.forEachIndexed { i, ch ->
                // split the word box evenly between its letters
                val l = wd.r.left + wd.r.width() * i / n
                val r = wd.r.left + wd.r.width() * (i + 1) / n
                if (ch.isWhitespace()) { chars.append(' '); boxes.add(null) }
                else { chars.append(ch.lowercaseChar()); boxes.add(RectF(l, wd.r.top, r, wd.r.bottom)) }
            }
        }
        return PageText(chars.toString(), boxes)
    }

    fun normalise(q: String) = q.trim().lowercase().replace(Regex("\\s+"), " ")

    /** All matches of [query] (already normalised) on a page. */
    fun find(page: Int, pt: PageText, query: String): List<SearchHit> {
        if (query.isEmpty() || pt.text.length < query.length) return emptyList()
        val out = ArrayList<SearchHit>()
        var from = 0
        while (true) {
            val i = pt.text.indexOf(query, from)
            if (i < 0) break
            val rects = mergeLines(pt.boxes.subList(i, i + query.length).filterNotNull())
            if (rects.isNotEmpty()) out.add(SearchHit(page, rects))
            from = i + max(1, query.length)
        }
        return out
    }

    /** Joins letter boxes that sit on the same line into one highlight. */
    private fun mergeLines(boxes: List<RectF>): List<RectF> {
        val out = ArrayList<RectF>()
        var cur: RectF? = null
        for (b in boxes) {
            val c = cur
            if (c != null && abs(c.centerY() - b.centerY()) < max(c.height(), b.height()) * 0.6f && b.left >= c.left - c.height()) {
                c.set(min(c.left, b.left), min(c.top, b.top), max(c.right, b.right), max(c.bottom, b.bottom))
            } else {
                cur = RectF(b).also { out.add(it) }
            }
        }
        return out
    }
}
