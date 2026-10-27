package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
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
            for ((pageIndex, pageItems) in items.groupBy { it.page }) {
                if (pageIndex !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(pageIndex)
                val g = pdf.geoms.getOrNull(pageIndex) ?: continue
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (item in pageItems) {
                        when (item) {
                            is SignatureItem -> {
                                val img = images.getOrPut(item.file.absolutePath) {
                                    LosslessFactory.createFromImage(doc, BitmapFactory.decodeFile(item.file.absolutePath))
                                }
                                cs.drawImage(img, boxMatrix(g, item.fx, item.fy, item.fw, item.fh))
                            }
                            is TextItem -> drawText(doc, cs, g, item)
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

    /** Matrix that maps the unit square onto a box given in displayed-page fractions. */
    private fun boxMatrix(g: PageGeom, fx: Float, fy: Float, fw: Float, fh: Float): Matrix {
        val u0 = fx * g.dispW
        val v0 = fy * g.dispH
        val w = fw * g.dispW
        val h = fh * g.dispH
        val o = g.toUser(u0, v0 + h)
        val sx = g.toUser(u0 + w, v0 + h)
        val ty = g.toUser(u0, v0)
        return Matrix(sx.x - o.x, sx.y - o.y, ty.x - o.x, ty.y - o.y, o.x, o.y)
    }

    private fun pdfFont(item: TextItem): PDFont = when (item.font) {
        TextFont.SANS -> if (item.bold) PDType1Font.HELVETICA_BOLD else PDType1Font.HELVETICA
        TextFont.SERIF -> if (item.bold) PDType1Font.TIMES_BOLD else PDType1Font.TIMES_ROMAN
        TextFont.MONO -> if (item.bold) PDType1Font.COURIER_BOLD else PDType1Font.COURIER
    }

    private fun drawText(doc: PDDocument, cs: PDPageContentStream, g: PageGeom, item: TextItem) {
        val font = pdfFont(item)
        val lines = TextRender.lines(item.text)
        val encodable = try {
            lines.forEach { font.encode(it) }
            true
        } catch (e: Throwable) {
            false
        }
        if (!encodable) {
            // non-Latin text (e.g. Malayalam, Hindi): draw it as a sharp image instead
            drawTextAsImage(doc, cs, g, item)
            return
        }
        val r = Color.red(item.color) / 255f
        val gr = Color.green(item.color) / 255f
        val b = Color.blue(item.color) / 255f
        val size = item.sizePt
        val left = item.fx * g.dispW
        val top = item.fy * g.dispH
        lines.forEachIndexed { k, line ->
            if (line.isEmpty()) return@forEachIndexed
            val baseV = top + size * (TextRender.ASCENT + TextRender.LINE * k)
            val o = g.toUser(left, baseV)
            val ux = g.toUser(left + 1f, baseV)
            val up = g.toUser(left, baseV - 1f)
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(r, gr, b)
            cs.setTextMatrix(Matrix(ux.x - o.x, ux.y - o.y, up.x - o.x, up.y - o.y, o.x, o.y))
            cs.showText(line)
            cs.endText()
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
        cs.drawImage(img, boxMatrix(g, item.fx, item.fy, wPt / g.dispW, hPt / g.dispH))
    }
}
