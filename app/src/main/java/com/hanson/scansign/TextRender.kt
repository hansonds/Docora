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

    fun typeface(font: TextFont, bold: Boolean): Typeface {
        val family = when (font) {
            TextFont.SANS -> Typeface.SANS_SERIF
            TextFont.SERIF -> Typeface.SERIF
            TextFont.MONO -> Typeface.MONOSPACE
        }
        return Typeface.create(family, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun lines(text: String): List<String> = text.split('\n').ifEmpty { listOf("") }

    fun paint(item: TextItem, pxPerPt: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = typeface(item.font, item.bold)
        textSize = item.sizePt * pxPerPt
        color = item.color
    }

    /** Width and height of the text box in points. */
    fun sizePt(item: TextItem): Pair<Float, Float> {
        val p = paint(item, 1f)
        val ls = lines(item.text)
        val w = ls.maxOf { p.measureText(it) }.coerceAtLeast(item.sizePt * 0.6f)
        val h = ls.size * LINE * item.sizePt
        return Pair(w, h)
    }

    /** Draws the text with its top-left at (left, top) in a canvas scaled by [pxPerPt]. */
    fun draw(canvas: Canvas, item: TextItem, left: Float, top: Float, pxPerPt: Float) {
        val p = paint(item, pxPerPt)
        val size = item.sizePt * pxPerPt
        lines(item.text).forEachIndexed { k, line ->
            canvas.drawText(line, left, top + size * (ASCENT + LINE * k), p)
        }
    }
}
