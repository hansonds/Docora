package com.hanson.scansign

import android.graphics.BitmapFactory
import android.graphics.PointF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.min

enum class PageSize(val label: String) { A4("A4"), LETTER("Letter"), FIT("Fit to image") }

enum class Quality(val label: String, val jpeg: Float, val maxSide: Int) {
    HIGH("High", 0.9f, 3000),
    MEDIUM("Medium", 0.78f, 2200),
    LOW("Small file", 0.62f, 1500)
}

/** A signature placed on a page, in fractions (0..1) of the displayed page, origin top-left. */
data class Placement(
    val page: Int,
    val signature: File,
    var fx: Float,
    var fy: Float,
    var fw: Float,
    var fh: Float
)

object PdfTools {

    /** Builds a PDF from page images (JPEG files). */
    fun imagesToPdf(images: List<File>, out: File, size: PageSize, quality: Quality, margin: Boolean) {
        PDDocument().use { doc ->
            for (imgFile in images) {
                var bmp = ImageUtils.load(imgFile, quality.maxSide) ?: continue
                val iw = bmp.width.toFloat()
                val ih = bmp.height.toFloat()
                val landscape = iw > ih
                val pageRect = when (size) {
                    PageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                    PageSize.LETTER -> if (landscape) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
                    PageSize.FIT -> {
                        // 595pt wide (A4 width) keeps the physical size sensible
                        val w = if (landscape) 842f else 595f
                        PDRectangle(w, w * ih / iw)
                    }
                }
                val page = PDPage(pageRect)
                doc.addPage(page)
                val m = if (margin && size != PageSize.FIT) 18f else 0f
                val availW = pageRect.width - 2 * m
                val availH = pageRect.height - 2 * m
                val s = min(availW / iw, availH / ih)
                val dw = iw * s
                val dh = ih * s
                val x = (pageRect.width - dw) / 2f
                val y = (pageRect.height - dh) / 2f
                val pdImg = JPEGFactory.createFromImage(doc, bmp, quality.jpeg)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(pdImg, x, y, dw, dh)
                }
                bmp.recycle()
            }
            doc.save(out)
        }
    }

    /** Page sizes as displayed (after /Rotate), in points. */
    fun displayedSizes(file: File): List<PointF> {
        PDDocument.load(file).use { doc ->
            return (0 until doc.numberOfPages).map { i ->
                val p = doc.getPage(i)
                val box = p.cropBox
                val rot = ((p.rotation % 360) + 360) % 360
                if (rot == 90 || rot == 270) PointF(box.height, box.width) else PointF(box.width, box.height)
            }
        }
    }

    /** Stamps signature images onto an existing PDF and writes the result to [out]. */
    fun addSignatures(input: File, out: File, placements: List<Placement>) {
        PDDocument.load(input).use { doc ->
            val imageCache = HashMap<String, com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject>()
            for (pl in placements) {
                if (pl.page !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(pl.page)
                val img = imageCache.getOrPut(pl.signature.absolutePath) {
                    val bmp = BitmapFactory.decodeFile(pl.signature.absolutePath)
                    LosslessFactory.createFromImage(doc, bmp)
                }
                val box = page.cropBox
                val llx = box.lowerLeftX
                val lly = box.lowerLeftY
                val W = box.width
                val H = box.height
                val rot = ((page.rotation % 360) + 360) % 360
                val dispW = if (rot == 90 || rot == 270) H else W
                val dispH = if (rot == 90 || rot == 270) W else H

                // displayed (u, v; v downward) -> PDF user space (x, y; y upward)
                fun map(u: Float, v: Float): PointF = when (rot) {
                    90 -> PointF(llx + v, lly + u)
                    180 -> PointF(llx + W - u, lly + v)
                    270 -> PointF(llx + W - v, lly + H - u)
                    else -> PointF(llx + u, lly + H - v)
                }

                val u0 = pl.fx * dispW
                val v0 = pl.fy * dispH
                val w = pl.fw * dispW
                val h = pl.fh * dispH
                val o = map(u0, v0 + h)      // image bottom-left
                val sx = map(u0 + w, v0 + h) // image bottom-right
                val ty = map(u0, v0)         // image top-left
                val matrix = Matrix(
                    sx.x - o.x, sx.y - o.y,
                    ty.x - o.x, ty.y - o.y,
                    o.x, o.y
                )
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.drawImage(img, matrix)
                }
            }
            doc.save(out)
        }
    }
}
