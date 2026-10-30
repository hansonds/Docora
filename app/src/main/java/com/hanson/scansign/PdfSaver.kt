package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import android.graphics.PointF
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.ceil

/** Writes form values, signatures and text into a copy of the PDF. */
object PdfSaver {

    fun save(pdf: OpenedPdf, out: File, items: List<EditItem>, flatten: Boolean) {
        PDDocument.load(pdf.file, PdfOpener.memory()).use { doc ->
            doc.setAllSecurityToBeRemoved(true)
            applyForm(doc, pdf.fields)

            val images = HashMap<String, PDImageXObject>()
            val fonts = HashMap<String, PDFont>()
            for ((pageIndex, pageItems) in items.groupBy { it.page }) {
                if (pageIndex !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val g = pdf.geoms.getOrNull(pageIndex) ?: continue
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (item in pageItems) {
                        when (item) {
                            is SignatureItem -> {
                                val img = images.getOrPut(item.file.absolutePath) { loadImage(doc, item.file) }
                                cs.drawImage(img, boxMatrix(g, item.fx, item.fy, item.fw, item.fh, item.rotation))
                            }
                            is TextItem -> {
                                if (item is ReplacedText) drawCover(cs, g, item.cover, item.coverColor)
                                drawText(doc, cs, g, item, page, fonts)
                            }
                            is InkItem -> drawInk(cs, g, item)
                            is ShapeItem -> drawShape(cs, g, item)
                        }
                    }
                }
            }

            if (flatten) {
                try {
                    doc.documentCatalog.acroForm?.flatten()
                } catch (_: Throwable) {
                }
            }
            doc.save(out)
        }
        val redacted = items.filter { it is ShapeItem && it.kind == ShapeKind.REDACT }.map { it.page }.toSortedSet()
        if (redacted.isNotEmpty()) flattenPages(out, redacted)
    }

    /**
     * Real redaction: pages with a black-out are replaced by a picture of the finished page,
     * so the covered text, pictures and hidden layers no longer exist in the file.
     */
    private fun flattenPages(file: File, pages: Set<Int>) {
        val tmp = File(file.parentFile, file.name + ".redacting")
        val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(fd)
        try {
            PDDocument.load(file, PdfOpener.memory()).use { doc ->
                val originals = pages.filter { it in 0 until doc.numberOfPages }.associateWith { doc.getPage(it) }
                for ((index, old) in originals) {
                    val box = old.cropBox
                    val rot = ((old.rotation % 360) + 360) % 360
                    val w = if (rot == 90 || rot == 270) box.height else box.width
                    val h = if (rot == 90 || rot == 270) box.width else box.height
                    val page = renderer.openPage(index)
                    val scale = (2.4f).coerceAtMost(3000f / kotlin.math.max(w, h))
                    val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()
                    val img = JPEGFactory.createFromImage(doc, bmp, 0.9f)
                    bmp.recycle()
                    val flat = com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle(w, h))
                    PDPageContentStream(doc, flat).use { it.drawImage(img, 0f, 0f, w, h) }
                    doc.pages.insertBefore(flat, old)
                    doc.removePage(old)
                }
                doc.save(tmp)
            }
        } finally {
            try { renderer.close() } catch (_: Throwable) {}
            try { fd.close() } catch (_: Throwable) {}
        }
        if (tmp.exists() && tmp.length() > 0) { file.delete(); tmp.renameTo(file) }
    }

    private fun applyForm(doc: PDDocument, fields: List<FormField>) {
        val form = doc.documentCatalog.acroForm ?: return
        var needAppearances = false
        for (ff in fields) {
            if (!ff.changed || ff.readOnly) continue
            val f = form.getField(ff.name) ?: continue
            try {
                when (f) {
                    is PDTextField -> try {
                        f.setValue(ff.value)
                    } catch (e: Throwable) {
                        // font can't show these characters: store the value and let viewers draw it
                        f.cosObject.setString(com.tom_roush.pdfbox.cos.COSName.V, ff.value)
                        needAppearances = true
                    }
                    is PDCheckBox -> if (ff.value == "Off" || ff.value.isEmpty()) f.unCheck() else f.check()
                    is PDRadioButton -> f.setValue(ff.value)
                    is PDChoice -> try {
                        f.setValue(ff.value)
                    } catch (e: Throwable) {
                        f.cosObject.setString(com.tom_roush.pdfbox.cos.COSName.V, ff.value)
                        needAppearances = true
                    }
                }
            } catch (_: Throwable) {
            }
        }
        if (needAppearances) try { form.needAppearances = true } catch (_: Throwable) {}
    }

    /** Photos are stored as JPEG (small), signatures / transparent PNGs losslessly. */
    private fun loadImage(doc: PDDocument, f: File): PDImageXObject {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (kotlin.math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2400) sample *= 2
        val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        val isJpeg = f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true)
        return if (isJpeg && !bmp.hasAlpha()) JPEGFactory.createFromImage(doc, bmp, 0.88f)
        else LosslessFactory.createFromImage(doc, bmp)
    }

    private fun alphaState(alpha: Float): PDExtendedGraphicsState = PDExtendedGraphicsState().apply {
        strokingAlphaConstant = alpha
        nonStrokingAlphaConstant = alpha
    }

    private fun rgb(c: Int) = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    /** Displayed-page fraction -> PDF user space. */
    private fun up(g: PageGeom, fx: Float, fy: Float): PointF = g.toUser(fx * g.dispW, fy * g.dispH)

    private fun drawInk(cs: PDPageContentStream, g: PageGeom, item: InkItem) {
        if (item.points.isEmpty()) return
        val c = rgb(item.color)
        cs.saveGraphicsState()
        if (item.alpha < 1f) cs.setGraphicsStateParameters(alphaState(item.alpha))
        cs.setStrokingColor(c[0], c[1], c[2])
        cs.setNonStrokingColor(c[0], c[1], c[2])
        cs.setLineWidth(item.widthPt)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(1)
        val pts = item.points.map { up(g, it.x, it.y) }
        if (pts.size == 1) {
            circle(cs, pts[0].x, pts[0].y, item.widthPt / 2)
            cs.fill()
        } else {
            cs.moveTo(pts[0].x, pts[0].y)
            // smooth like the preview: quadratic through midpoints
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val mx = (a.x + b.x) / 2; val my = (a.y + b.y) / 2
                val prev = if (i == 1) pts[0] else PointF((pts[i - 2].x + a.x) / 2, (pts[i - 2].y + a.y) / 2)
                // quadratic (prev -> a -> mid) as cubic
                cs.curveTo(prev.x + 2f / 3 * (a.x - prev.x), prev.y + 2f / 3 * (a.y - prev.y),
                    mx + 2f / 3 * (a.x - mx), my + 2f / 3 * (a.y - my), mx, my)
            }
            cs.lineTo(pts.last().x, pts.last().y)
            cs.stroke()
        }
        cs.restoreGraphicsState()
    }

    /** Solid rectangle (page fractions) that hides the original text under replaced text. */
    private fun drawCover(cs: PDPageContentStream, g: PageGeom, r: android.graphics.RectF, color: Int) {
        val c = rgb(color)
        cs.saveGraphicsState()
        cs.setNonStrokingColor(c[0], c[1], c[2])
        val a = up(g, r.left, r.top); val b = up(g, r.right, r.top); val cc = up(g, r.right, r.bottom); val d = up(g, r.left, r.bottom)
        cs.moveTo(a.x, a.y); cs.lineTo(b.x, b.y); cs.lineTo(cc.x, cc.y); cs.lineTo(d.x, d.y); cs.closePath()
        cs.fill()
        cs.restoreGraphicsState()
    }

    private fun circle(cs: PDPageContentStream, cx: Float, cy: Float, r: Float) {
        val k = 0.5523f * r
        cs.moveTo(cx + r, cy)
        cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
        cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
        cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
        cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
        cs.closePath()
    }

    private fun drawShape(cs: PDPageContentStream, g: PageGeom, s: ShapeItem) {
        val c = rgb(s.color)
        cs.saveGraphicsState()
        cs.setStrokingColor(c[0], c[1], c[2])
        cs.setNonStrokingColor(c[0], c[1], c[2])
        cs.setLineWidth(s.widthPt)
        cs.setLineCapStyle(1)
        cs.setLineJoinStyle(if (s.kind == ShapeKind.RECT) 0 else 1)
        val x0 = s.fx; val y0 = s.fy; val x1 = s.fx + s.fw; val y1 = s.fy + s.fh
        fun quad() {
            val a = up(g, x0, y0); val b = up(g, x1, y0); val cc = up(g, x1, y1); val d = up(g, x0, y1)
            cs.moveTo(a.x, a.y); cs.lineTo(b.x, b.y); cs.lineTo(cc.x, cc.y); cs.lineTo(d.x, d.y); cs.closePath()
        }
        when (s.kind) {
            ShapeKind.RECT -> { quad(); cs.stroke() }
            ShapeKind.WHITEOUT -> { cs.setNonStrokingColor(1f, 1f, 1f); quad(); cs.fill() }
            ShapeKind.FILL -> { quad(); cs.fill() }
            ShapeKind.REDACT -> { cs.setNonStrokingColor(0f, 0f, 0f); quad(); cs.fill() }
            ShapeKind.HIGHLIGHT -> { cs.setGraphicsStateParameters(alphaState(0.35f)); quad(); cs.fill() }
            ShapeKind.ELLIPSE -> {
                // bezier ellipse in page fractions, each point mapped (works on rotated pages)
                val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
                val rx = (x1 - x0) / 2; val ry = (y1 - y0) / 2
                val k = 0.5523f
                fun P(x: Float, y: Float) = up(g, x, y)
                val p0 = P(cx + rx, cy)
                cs.moveTo(p0.x, p0.y)
                val segs = arrayOf(
                    floatArrayOf(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry),
                    floatArrayOf(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy),
                    floatArrayOf(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry),
                    floatArrayOf(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy)
                )
                for (sg in segs) {
                    val a = P(sg[0], sg[1]); val b = P(sg[2], sg[3]); val e = P(sg[4], sg[5])
                    cs.curveTo(a.x, a.y, b.x, b.y, e.x, e.y)
                }
                cs.closePath()
                cs.stroke()
            }
            ShapeKind.LINE, ShapeKind.ARROW -> {
                val a = up(g, x0, y0); val b = up(g, x1, y1)
                cs.moveTo(a.x, a.y); cs.lineTo(b.x, b.y); cs.stroke()
                if (s.kind == ShapeKind.ARROW) {
                    // head computed in displayed points then mapped
                    val h = ItemRender.arrowHead(x0 * g.dispW, y0 * g.dispH, x1 * g.dispW, y1 * g.dispH,
                        kotlin.math.max(10f, s.widthPt * 4))
                    val h1 = g.toUser(h[0], h[1]); val h2 = g.toUser(h[2], h[3])
                    cs.moveTo(b.x, b.y); cs.lineTo(h1.x, h1.y)
                    cs.moveTo(b.x, b.y); cs.lineTo(h2.x, h2.y)
                    cs.stroke()
                }
            }
        }
        cs.restoreGraphicsState()
    }

    /** Matrix that maps the unit square onto a box given in displayed-page fractions. */
    private fun boxMatrix(g: PageGeom, fx: Float, fy: Float, fw: Float, fh: Float, rotation: Float = 0f): Matrix {
        val u0 = fx * g.dispW
        val v0 = fy * g.dispH
        val w = fw * g.dispW
        val h = fh * g.dispH
        val map = rotated(g, u0 + w / 2, v0 + h / 2, rotation)
        val o = map(u0, v0 + h)
        val sx = map(u0 + w, v0 + h)
        val ty = map(u0, v0)
        return Matrix(sx.x - o.x, sx.y - o.y, ty.x - o.x, ty.y - o.y, o.x, o.y)
    }

    /**
     * Displayed point -> PDF user space, after rotating clockwise by [deg] around (cu, cv) in displayed points
     * (the same rotation the screen preview applies).
     */
    private fun rotated(g: PageGeom, cu: Float, cv: Float, deg: Float): (Float, Float) -> PointF {
        if (deg == 0f) return { u, v -> g.toUser(u, v) }
        val a = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
        return { u, v ->
            val du = u - cu; val dv = v - cv
            g.toUser(cu + du * c - dv * sn, cv + du * sn + dv * c)
        }
    }

    /** Docora's own font for this style (embedded, same letters as the screen); standard PDF fonts as a fallback. */
    private fun pdfFont(doc: PDDocument, item: TextItem, cache: HashMap<String, PDFont>?, bold: Boolean = item.bold, italic: Boolean = item.italic): PDFont {
        val file = TextRender.fontFile(item.font, bold, italic)
        if (file != null && cache != null) {
            cache[file]?.let { return it }
            val loaded = try {
                TextRender.openFont(file)?.use { com.tom_roush.pdfbox.pdmodel.font.PDType0Font.load(doc, it, true) }
            } catch (_: Throwable) { null }
            if (loaded != null) { cache[file] = loaded; return loaded }
        }
        val b = bold; val i = italic
        return when (item.font) {
            TextFont.SANS -> when { b && i -> PDType1Font.HELVETICA_BOLD_OBLIQUE; b -> PDType1Font.HELVETICA_BOLD; i -> PDType1Font.HELVETICA_OBLIQUE; else -> PDType1Font.HELVETICA }
            TextFont.SERIF -> when { b && i -> PDType1Font.TIMES_BOLD_ITALIC; b -> PDType1Font.TIMES_BOLD; i -> PDType1Font.TIMES_ITALIC; else -> PDType1Font.TIMES_ROMAN }
            TextFont.MONO -> when { b && i -> PDType1Font.COURIER_BOLD_OBLIQUE; b -> PDType1Font.COURIER_BOLD; i -> PDType1Font.COURIER_OBLIQUE; else -> PDType1Font.COURIER }
        }
    }

    /** A font to write with, plus bold / italic that has to be added (the font itself lacks it). */
    private class FontChoice(val font: PDFont, val fakeBold: Boolean, val fakeItalic: Boolean)

    /** True if [font] can draw every letter of [text] (embedded fonts often hold only the letters used). */
    private fun hasAll(font: PDFont, text: String): Boolean = try {
        if (font !is com.tom_roush.pdfbox.pdmodel.font.PDVectorFont) false
        else TextRender.lines(text).all { line ->
            if (line.isEmpty()) return@all true
            val input = java.io.ByteArrayInputStream(font.encode(line))
            var ok = true
            while (ok && input.available() > 0) ok = (font as com.tom_roush.pdfbox.pdmodel.font.PDVectorFont).hasGlyph(font.readCode(input))
            ok
        }
    } catch (_: Throwable) { false }

    /**
     * Replaced text keeps the PDF's own font: the same font, or its bold / italic version from the
     * same family on this page, or the original letters made bold / slanted. Null = it can't draw
     * the typed letters, use Docora's font.
     */
    private fun originalFont(
        page: com.tom_roush.pdfbox.pdmodel.PDPage, item: TextItem,
        bold: Boolean = item.bold, italic: Boolean = item.italic, text: String = item.text
    ): FontChoice? {
        val name = (item as? ReplacedText)?.origFont ?: return null
        return try {
            val res = page.resources ?: return null
            val all = res.fontNames.mapNotNull { n -> try { res.getFont(n) } catch (_: Throwable) { null } }
            val orig = all.firstOrNull { it.name == name } ?: return null
            val fam = BetaEdit.familyKey(name)
            val family = all.filter { BetaEdit.familyKey(it.name) == fam }
            val choices = ArrayList<FontChoice>()
            family.filter { BetaEdit.fontBold(it) == bold && BetaEdit.fontItalic(it) == italic }
                .sortedBy { if (it === orig) 0 else 1 }.forEach { choices.add(FontChoice(it, false, false)) }
            val ob = BetaEdit.fontBold(orig); val oi = BetaEdit.fontItalic(orig)
            if ((!ob || bold) && (!oi || italic)) choices.add(FontChoice(orig, bold && !ob, italic && !oi))
            family.filter { !BetaEdit.fontBold(it) && !BetaEdit.fontItalic(it) }.forEach { choices.add(FontChoice(it, bold, italic)) }
            choices.firstOrNull { hasAll(it.font, text) }
        } catch (_: Throwable) { null }
    }

    private fun drawText(
        doc: PDDocument, cs: PDPageContentStream, g: PageGeom, item: TextItem,
        page: com.tom_roush.pdfbox.pdmodel.PDPage? = null, fonts: HashMap<String, PDFont>? = null
    ) {
        // one font per style used in the text (bold / italic parts can use the PDF's own bold / italic font)
        class Pick(val font: PDFont, val fakeBold: Boolean, val fakeItalic: Boolean, val own: Boolean)
        val layout = TextRender.layout(item)
        val picks = HashMap<Int, Pick>()
        for ((st, runs) in layout.flatten().groupBy { it.flags and 3 }) {
            val b = st and Style.BOLD != 0; val i = st and Style.ITALIC != 0
            val txt = runs.joinToString("") { it.text }
            val own = page?.let { originalFont(it, item, b, i, txt) }
            val font = own?.font ?: pdfFont(doc, item, fonts, b, i)
            val encodable = try { font.encode(txt); true } catch (_: Throwable) { false }
            if (!encodable) {
                // non-Latin text (e.g. Malayalam, Hindi): draw it as a sharp image instead
                drawTextAsImage(doc, cs, g, item)
                return
            }
            picks[st] = Pick(font, own?.fakeBold == true, own?.fakeItalic == true, own != null)
        }
        val r = Color.red(item.color) / 255f
        val gr = Color.green(item.color) / 255f
        val b = Color.blue(item.color) / 255f
        val size = item.sizePt
        var left = item.fx * g.dispW
        var top = item.fy * g.dispH
        val (boxW, boxH) = TextRender.sizePt(item)
        val T = rotated(g, left + boxW / 2, top + boxH / 2, item.rotation)
        if (item.boxed) {
            val (wPt, hPt) = TextRender.sizePt(item)
            val lw = TextRender.FRAME * size
            val half = lw / 2
            cs.saveGraphicsState()
            cs.setStrokingColor(r, gr, b)
            cs.setLineWidth(lw)
            cs.setLineJoinStyle(1)
            val pts = listOf(
                T(left + half, top + half), T(left + wPt - half, top + half),
                T(left + wPt - half, top + hPt - half), T(left + half, top + hPt - half)
            )
            cs.moveTo(pts[0].x, pts[0].y)
            for (i in 1..3) cs.lineTo(pts[i].x, pts[i].y)
            cs.closePath()
            cs.stroke()
            cs.restoreGraphicsState()
            left += TextRender.PAD * size
            top += TextRender.PAD * size
        }
        // Docora's stand-in font is resized so its capitals match the original's height
        val standIn = size * ((item as? ReplacedText)?.glyphScale ?: 1f)
        // same line / word positions as the screen; each piece is stretched to exactly its on-screen width
        layout.forEachIndexed { k, runs ->
            val baseV = top + size * (TextRender.ASCENT + item.lineGap * k)
            for (run in runs) {
                val pick = picks[run.flags and 3] ?: continue
                val font = pick.font
                val fontSize = if (pick.own) size else standIn
                val natural = try { font.getStringWidth(run.text) / 1000f * fontSize } catch (_: Throwable) { 0f }
                val scale = if (natural > 0f) (run.w / natural * 100f).coerceIn(60f, 160f) else 100f
                val o = T(left + run.x, baseV)
                val ux = T(left + run.x + 1f, baseV)
                val up = T(left + run.x, baseV - 1f)
                // italic added by slanting the original letters
                val lean = if (pick.fakeItalic) 0.21f else 0f
                cs.saveGraphicsState()
                if (pick.fakeBold) {
                    // bold added by outlining the letters in the same colour
                    cs.setStrokingColor(r, gr, b)
                    cs.setLineWidth(size * 0.035f)
                }
                cs.beginText()
                cs.setFont(font, fontSize)
                if (pick.fakeBold) cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL_STROKE)
                if (kotlin.math.abs(scale - 100f) > 0.5f) cs.setHorizontalScaling(scale)
                cs.setNonStrokingColor(r, gr, b)
                cs.setTextMatrix(Matrix(
                    ux.x - o.x, ux.y - o.y,
                    (up.x - o.x) + lean * (ux.x - o.x), (up.y - o.y) + lean * (ux.y - o.y),
                    o.x, o.y
                ))
                cs.showText(run.text)
                if (kotlin.math.abs(scale - 100f) > 0.5f) cs.setHorizontalScaling(100f)
                if (pick.fakeBold) cs.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.FILL)
                cs.endText()
                if (run.flags and Style.UNDERLINE != 0) {
                    // underline: a thin bar under this piece
                    val y0 = baseV + size * TextRender.UNDER_POS
                    val y1 = y0 + size * TextRender.UNDER_THICK
                    val p1 = T(left + run.x, y0); val p2 = T(left + run.x + run.w, y0)
                    val p3 = T(left + run.x + run.w, y1); val p4 = T(left + run.x, y1)
                    cs.moveTo(p1.x, p1.y); cs.lineTo(p2.x, p2.y); cs.lineTo(p3.x, p3.y); cs.lineTo(p4.x, p4.y); cs.closePath()
                    cs.fill()
                }
                cs.restoreGraphicsState()
            }
        }
    }

    private fun drawTextAsImage(doc: PDDocument, cs: PDPageContentStream, g: PageGeom, item: TextItem) {
        val scale = 4f // pixels per point
        val (wPt, hPt) = TextRender.sizePt(item)
        val bw = ceil(wPt * scale).toInt().coerceIn(1, 6000)
        val bh = ceil(hPt * scale).toInt().coerceIn(1, 6000)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        TextRender.draw(Canvas(bmp), item, 0f, 0f, scale)
        val img = LosslessFactory.createFromImage(doc, bmp)
        bmp.recycle()
        cs.drawImage(img, boxMatrix(g, item.fx, item.fy, wPt / g.dispW, hPt / g.dispH, item.rotation))
    }
}
