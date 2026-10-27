package com.hanson.scansign

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class PageSize(val label: String) { A4("A4"), LETTER("Letter"), FIT("Fit to image") }

enum class Quality(val label: String, val jpeg: Float, val maxSide: Int) {
    HIGH("High", 0.9f, 3000),
    MEDIUM("Medium", 0.78f, 2200),
    LOW("Small file", 0.62f, 1500)
}

enum class Layout(val label: String) { NORMAL("One page per scan"), ID_CARD("ID card (2 sides on one page)") }

enum class CompressLevel(val label: String, val jpeg: Float, val maxSide: Int) {
    LIGHT("Light — best quality", 0.8f, 2400),
    MEDIUM("Medium — recommended", 0.65f, 1700),
    STRONG("Strong — smallest file", 0.5f, 1200)
}

/** One page in an organised document: source page index (-1 = blank page) and extra rotation. */
data class PageOp(val src: Int, var rot: Int = 0, val id: Long = nextId()) {
    companion object {
        private var counter = 0L
        @Synchronized fun nextId() = ++counter
    }
}

typealias Progress = (done: Int, total: Int) -> Unit

/** All PDF tools. Every function is blocking; call from a background thread. */
object PdfTools {

    private fun load(f: File, password: String? = null) = PDDocument.load(f, password ?: "", PdfOpener.memory())

    // ---------------- scans -> PDF ----------------

    /** Builds a PDF from page images; optional ID-card layout and invisible OCR text (searchable PDF). */
    fun imagesToPdf(
        ctx: Context, images: List<File>, out: File, size: PageSize, quality: Quality, margin: Boolean,
        layout: Layout = Layout.NORMAL, searchable: Boolean = false, progress: Progress? = null
    ) {
        PDDocument().use { doc ->
            if (layout == Layout.ID_CARD) {
                // two cards stacked on an A4 page
                var i = 0
                while (i < images.size) {
                    val page = PDPage(PDRectangle.A4)
                    doc.addPage(page)
                    val slotH = (PDRectangle.A4.height - 3 * 36f) / 2
                    val slotW = PDRectangle.A4.width - 2 * 72f
                    for (slot in 0..1) {
                        if (i >= images.size) break
                        val bmp = ImageUtils.load(images[i], quality.maxSide) ?: run { i++; null } ?: continue
                        val s = min(slotW / bmp.width, slotH / bmp.height)
                        val dw = bmp.width * s; val dh = bmp.height * s
                        val x = (PDRectangle.A4.width - dw) / 2
                        val top = PDRectangle.A4.height - 36f - slot * (slotH + 36f)
                        val y = top - (slotH + dh) / 2
                        drawImageOnPage(ctx, doc, page, bmp, x, y, dw, dh, quality, searchable)
                        bmp.recycle()
                        i++
                        progress?.invoke(i, images.size)
                    }
                }
            } else {
                images.forEachIndexed { idx, imgFile ->
                    val bmp = ImageUtils.load(imgFile, quality.maxSide) ?: return@forEachIndexed
                    val iw = bmp.width.toFloat(); val ih = bmp.height.toFloat()
                    val landscape = iw > ih
                    val pageRect = when (size) {
                        PageSize.A4 -> if (landscape) PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width) else PDRectangle.A4
                        PageSize.LETTER -> if (landscape) PDRectangle(PDRectangle.LETTER.height, PDRectangle.LETTER.width) else PDRectangle.LETTER
                        PageSize.FIT -> { val w = if (landscape) 842f else 595f; PDRectangle(w, w * ih / iw) }
                    }
                    val page = PDPage(pageRect)
                    doc.addPage(page)
                    val m = if (margin && size != PageSize.FIT) 18f else 0f
                    val s = min((pageRect.width - 2 * m) / iw, (pageRect.height - 2 * m) / ih)
                    val dw = iw * s; val dh = ih * s
                    drawImageOnPage(ctx, doc, page, bmp, (pageRect.width - dw) / 2f, (pageRect.height - dh) / 2f, dw, dh, quality, searchable)
                    bmp.recycle()
                    progress?.invoke(idx + 1, images.size)
                }
            }
            doc.save(out)
        }
    }

    private fun drawImageOnPage(
        ctx: Context, doc: PDDocument, page: PDPage, bmp: Bitmap,
        x: Float, y: Float, dw: Float, dh: Float, quality: Quality, searchable: Boolean
    ) {
        val img = JPEGFactory.createFromImage(doc, bmp, quality.jpeg)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.drawImage(img, x, y, dw, dh)
            if (searchable) {
                val lines = try { Ocr.recognize(ctx, bmp).second } catch (e: Throwable) { emptyList() }
                val sx = dw / bmp.width; val sy = dh / bmp.height
                for (l in lines) {
                    val left = x + l.box.left * sx
                    val bottom = y + dh - l.box.bottom * sy
                    writeInvisibleLine(cs, l.text, left, bottom + l.box.height() * sy * 0.18f,
                        l.box.width() * sx, l.box.height() * sy, null)
                }
            }
        }
    }

    // ---------------- invisible text (searchable PDF) ----------------

    private val ocrFont: PDFont = PDType1Font.HELVETICA

    /** Keeps only characters the built-in font can store. */
    private fun encodable(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            try { ocrFont.encode(ch.toString()); sb.append(ch) } catch (e: Throwable) { sb.append(' ') }
        }
        return sb.toString().trim()
    }

    /**
     * Writes invisible (render mode NEITHER) text so the PDF can be searched / text selected.
     * [g] == null: coordinates are already PDF user space; else they are displayed points converted through [g].
     */
    private fun writeInvisibleLine(cs: PDPageContentStream, raw: String, left: Float, baseline: Float, w: Float, h: Float, g: PageGeom?) {
        val text = encodable(raw)
        if (text.isEmpty() || w <= 1f || h <= 1f) return
        val size = h * 0.82f
        val natural = try { ocrFont.getStringWidth(text) / 1000f * size } catch (e: Throwable) { return }
        if (natural <= 0f) return
        cs.beginText()
        cs.setRenderingMode(RenderingMode.NEITHER)
        cs.setFont(ocrFont, size)
        cs.setHorizontalScaling((100f * w / natural).coerceIn(20f, 500f))
        if (g == null) {
            cs.setTextMatrix(Matrix.getTranslateInstance(left, baseline))
        } else {
            val o = g.toUser(left, baseline); val ux = g.toUser(left + 1f, baseline); val up = g.toUser(left, baseline - 1f)
            cs.setTextMatrix(Matrix(ux.x - o.x, ux.y - o.y, up.x - o.x, up.y - o.y, o.x, o.y))
        }
        cs.showText(text)
        cs.endText()
    }

    /** Runs OCR on every page of an existing PDF and adds an invisible text layer. */
    fun makeSearchable(ctx: Context, src: File, display: File, out: File, progress: Progress?) {
        load(src).use { doc ->
            ParcelFileDescriptor.open(display, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val n = min(r.pageCount, doc.numberOfPages)
                    for (i in 0 until n) {
                        val page = doc.getPage(i)
                        val box = page.cropBox
                        val rot = ((page.rotation % 360) + 360) % 360
                        val g = PageGeom(box.lowerLeftX, box.lowerLeftY, box.width, box.height, rot)
                        val p = r.openPage(i)
                        val scale = (2200f / max(p.width, p.height)).coerceAtMost(3f)
                        val bmp = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        p.close()
                        val lines = try { Ocr.recognize(ctx, bmp).second } catch (e: Throwable) { emptyList() }
                        val sx = g.dispW / bmp.width; val sy = g.dispH / bmp.height
                        bmp.recycle()
                        if (lines.isNotEmpty()) {
                            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                for (l in lines) {
                                    val left = l.box.left * sx
                                    val base = l.box.bottom * sy - l.box.height() * sy * 0.18f
                                    writeInvisibleLine(cs, l.text, left, base, l.box.width() * sx, l.box.height() * sy, g)
                                }
                            }
                        }
                        progress?.invoke(i + 1, n)
                    }
                }
            }
            doc.save(out)
        }
    }

    // ---------------- merge / split / organise ----------------

    fun merge(inputs: List<File>, out: File) {
        val m = PDFMergerUtility()
        inputs.forEach { m.addSource(it) }
        m.destinationFileName = out.absolutePath
        m.mergeDocuments(PdfOpener.memory())
    }

    fun pageCount(f: File): Int = load(f).use { it.numberOfPages }

    /**
     * Parses "1-3, 5, 8-" (1-based, inclusive) into 0-based ranges. Returns null if invalid.
     */
    fun parseRanges(text: String, n: Int): List<IntRange>? {
        val out = ArrayList<IntRange>()
        for (part in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = Regex("^(\\d*)\\s*-\\s*(\\d*)$").find(part)
            val r = if (m != null) {
                val a = m.groupValues[1].ifEmpty { "1" }.toInt()
                val b = m.groupValues[2].ifEmpty { n.toString() }.toInt()
                a..b
            } else {
                val v = part.toIntOrNull() ?: return null
                v..v
            }
            if (r.first < 1 || r.last > n || r.first > r.last) return null
            out.add((r.first - 1)..(r.last - 1))
        }
        return out.ifEmpty { null }
    }

    /**
     * Writes a new PDF whose pages are [ops] (reordered / rotated / duplicated / blank) taken from [src].
     * Works on the original document so forms, links and bookmarks inside pages are kept.
     */
    fun organize(src: File, ops: List<PageOp>, out: File) {
        load(src).use { doc ->
            val n = doc.numberOfPages
            val orig = (0 until n).map { doc.getPage(it) }
            // make inherited attributes explicit before pages leave the tree
            for (p in orig) {
                val res = p.resources; val mb = p.mediaBox; val cb = p.cropBox; val rot = p.rotation
                p.resources = res; p.mediaBox = mb; p.cropBox = cb; p.rotation = rot
            }
            val baseRot = orig.map { it.rotation }
            val tree = doc.pages
            for (p in orig) tree.remove(p)
            val used = HashSet<PDPage>()
            var lastSize: PDRectangle = orig.firstOrNull()?.mediaBox ?: PDRectangle.A4
            for (op in ops) {
                val page: PDPage
                var rot0 = 0
                if (op.src < 0 || op.src >= n) {
                    page = PDPage(PDRectangle(lastSize.width, lastSize.height))
                } else {
                    val p = orig[op.src]
                    rot0 = baseRot[op.src]
                    page = if (used.add(p)) p else PDPage(COSDictionary(p.cosObject)).also {
                        it.cosObject.removeItem(COSName.ANNOTS) // duplicated annotations would be shared
                    }
                    lastSize = p.mediaBox
                }
                page.rotation = (((rot0 + op.rot) % 360) + 360) % 360
                tree.add(page)
            }
            doc.save(out)
        }
    }

    /** Splits into one file per chunk of [every] pages. Returns the files written. */
    fun splitEvery(ctx: Context, src: File, every: Int, baseName: String, progress: Progress?): List<File> {
        val outs = ArrayList<File>()
        load(src).use { doc ->
            val n = doc.numberOfPages
            var start = 0
            while (start < n) {
                val end = min(n, start + every)
                PDDocument().use { dst ->
                    for (i in start until end) {
                        val sp = doc.getPage(i)
                        val np = PDPage(COSDictionary(sp.cosObject))
                        np.resources = sp.resources; np.mediaBox = sp.mediaBox; np.cropBox = sp.cropBox; np.rotation = sp.rotation
                        dst.addPage(np)
                    }
                    val name = if (end - start == 1) "${baseName}_p${start + 1}" else "${baseName}_p${start + 1}-$end"
                    val f = Storage.uniquePdf(ctx, name)
                    dst.save(f)
                    outs.add(f)
                }
                start = end
                progress?.invoke(start, n)
            }
        }
        return outs
    }

    // ---------------- compress ----------------

    /** Re-encodes large images inside the PDF. Returns true if the result is smaller. */
    fun compress(src: File, out: File, level: CompressLevel, progress: Progress?): Boolean {
        load(src).use { doc ->
            val done = HashMap<COSBase, PDImageXObject?>()
            val n = doc.numberOfPages
            for (i in 0 until n) {
                compressResources(doc, doc.getPage(i).resources, done, level, 0)
                progress?.invoke(i + 1, n)
            }
            doc.save(out)
        }
        return out.length() < src.length()
    }

    private fun compressResources(doc: PDDocument, res: PDResources?, done: HashMap<COSBase, PDImageXObject?>, level: CompressLevel, depth: Int) {
        res ?: return
        for (name in res.xObjectNames.toList()) {
            val xo = try { res.getXObject(name) } catch (e: Throwable) { continue }
            when (xo) {
                is PDImageXObject -> {
                    val key = xo.cosObject
                    if (done.containsKey(key)) {
                        done[key]?.let { res.put(name, it) }
                        continue
                    }
                    done[key] = null
                    try {
                        if (xo.isStencil || xo.bitsPerComponent == 1 || xo.cosObject.containsKey(COSName.SMASK)) continue
                        if (xo.width.toLong() * xo.height < 250L * 250) continue
                        val oldLen = (xo.cosObject as COSStream).length
                        val bmp = xo.image ?: continue
                        val s = min(1f, level.maxSide.toFloat() / max(bmp.width, bmp.height))
                        val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
                        // JPEG has no transparency: flatten on white
                        val opaque = if (scaled.hasAlpha()) {
                            Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                                val c = Canvas(it); c.drawColor(Color.WHITE); c.drawBitmap(scaled, 0f, 0f, null)
                            }
                        } else scaled
                        val newImg = JPEGFactory.createFromImage(doc, opaque, level.jpeg)
                        if (newImg.cosObject.length < oldLen * 0.9) {
                            res.put(name, newImg)
                            done[key] = newImg
                        }
                    } catch (_: Throwable) {
                    }
                }
                is PDFormXObject -> if (depth < 4) compressResources(doc, xo.resources, done, level, depth + 1)
                else -> {}
            }
        }
    }

    // ---------------- security ----------------

    fun protect(src: File, out: File, password: String, srcPassword: String? = null) {
        load(src, srcPassword).use { doc ->
            if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
            val policy = StandardProtectionPolicy(password, password, AccessPermission())
            policy.encryptionKeyLength = 128
            policy.isPreferAES = true
            doc.protect(policy)
            doc.save(out)
        }
    }

    /** Throws InvalidPasswordException if the password is wrong. */
    fun unlock(src: File, out: File, password: String) {
        load(src, password).use { doc ->
            doc.setAllSecurityToBeRemoved(true)
            doc.save(out)
        }
    }

    // ---------------- stamping text on every page ----------------

    private fun pageGeom(page: PDPage): PageGeom {
        val box = page.cropBox
        return PageGeom(box.lowerLeftX, box.lowerLeftY, box.width, box.height, ((page.rotation % 360) + 360) % 360)
    }

    /**
     * Draws [text] centred at (cu, cv) in displayed page points, rotated by [angleDeg] (counter-clockwise),
     * as real text when possible, else as a sharp image (non-Latin text).
     */
    private fun drawTextAt(
        doc: PDDocument, cs: PDPageContentStream, g: PageGeom, text: String, font: PDFont, size: Float,
        color: Int, cu: Float, cv: Float, angleDeg: Float, alignRight: Boolean = false
    ) {
        val a = Math.toRadians(angleDeg.toDouble())
        // baseline direction and "up" direction in displayed coordinates (v grows downward)
        val dx = cos(a).toFloat(); val dy = (-sin(a)).toFloat()
        val nx = dy; val ny = -dx
        fun lin(u: Float, v: Float): PointF {
            val o = g.toUser(0f, 0f); val p = g.toUser(u, v); return PointF(p.x - o.x, p.y - o.y)
        }
        val encodable = try { font.encode(text); true } catch (e: Throwable) { false }
        if (encodable) {
            val w = font.getStringWidth(text) / 1000f * size
            val shift = if (alignRight) w else w / 2
            val bu = cu - dx * shift - nx * size * 0.35f
            val bv = cv - dy * shift - ny * size * 0.35f
            val o = g.toUser(bu, bv)
            val ax = lin(dx, dy); val ay = lin(nx, ny)
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f)
            cs.setTextMatrix(Matrix(ax.x, ax.y, ay.x, ay.y, o.x, o.y))
            cs.showText(text)
            cs.endText()
        } else {
            val item = TextItem(0, text, color, size, true, TextFont.SANS, 0f, 0f)
            val (wPt, hPt) = TextRender.sizePt(item)
            val scale = 4f
            val bmp = Bitmap.createBitmap(ceil(wPt * scale).toInt().coerceIn(1, 6000), ceil(hPt * scale).toInt().coerceIn(1, 6000), Bitmap.Config.ARGB_8888)
            TextRender.draw(Canvas(bmp), item, 0f, 0f, scale)
            val img = LosslessFactory.createFromImage(doc, bmp)
            bmp.recycle()
            val shift = if (alignRight) wPt else wPt / 2
            // bottom-left of the image box
            val bu = cu - dx * shift - nx * hPt / 2
            val bv = cv - dy * shift - ny * hPt / 2
            val o = g.toUser(bu, bv)
            val ax = lin(dx * wPt, dy * wPt); val ay = lin(nx * hPt, ny * hPt)
            cs.drawImage(img, Matrix(ax.x, ax.y, ay.x, ay.y, o.x, o.y))
        }
    }

    fun watermark(src: File, out: File, text: String, color: Int, opacity: Float, sizePt: Float, diagonal: Boolean, progress: Progress?) {
        load(src).use { doc ->
            val n = doc.numberOfPages
            for (i in 0 until n) {
                val page = doc.getPage(i)
                val g = pageGeom(page)
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.saveGraphicsState()
                    cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                        nonStrokingAlphaConstant = opacity; strokingAlphaConstant = opacity
                    })
                    val angle = if (diagonal) Math.toDegrees(kotlin.math.atan2(g.dispH.toDouble(), g.dispW.toDouble())).toFloat() else 0f
                    drawTextAt(doc, cs, g, text, PDType1Font.HELVETICA_BOLD, sizePt, color, g.dispW / 2, g.dispH / 2, angle)
                    cs.restoreGraphicsState()
                }
                progress?.invoke(i + 1, n)
            }
            doc.save(out)
        }
    }

    enum class NumberPos(val label: String) { BOTTOM_CENTER("Bottom centre"), BOTTOM_RIGHT("Bottom right"), TOP_RIGHT("Top right") }
    enum class NumberFormat(val label: String) { PLAIN("1"), OF("Page 1 of 10"), SLASH("1 / 10") }

    fun pageNumbers(src: File, out: File, pos: NumberPos, fmt: NumberFormat, start: Int, progress: Progress?) {
        load(src).use { doc ->
            val n = doc.numberOfPages
            for (i in 0 until n) {
                val page = doc.getPage(i)
                val g = pageGeom(page)
                val num = start + i
                val total = start + n - 1
                val label = when (fmt) {
                    NumberFormat.PLAIN -> "$num"
                    NumberFormat.OF -> "Page $num of $total"
                    NumberFormat.SLASH -> "$num / $total"
                }
                val size = 10f
                val margin = 24f
                val (u, v, right) = when (pos) {
                    NumberPos.BOTTOM_CENTER -> Triple(g.dispW / 2, g.dispH - margin, false)
                    NumberPos.BOTTOM_RIGHT -> Triple(g.dispW - margin, g.dispH - margin, true)
                    NumberPos.TOP_RIGHT -> Triple(g.dispW - margin, margin, true)
                }
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    drawTextAt(doc, cs, g, label, PDType1Font.HELVETICA, size, Color.rgb(40, 40, 40), u, v, 0f, right)
                }
                progress?.invoke(i + 1, n)
            }
            doc.save(out)
        }
    }

    // ---------------- PDF -> images ----------------

    /** Saves every page as a JPEG in Pictures/PDF ScanSign. Returns how many were saved. */
    fun toImages(ctx: Context, display: File, baseName: String, dpi: Int, progress: Progress?): Int {
        var saved = 0
        ParcelFileDescriptor.open(display, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                val n = r.pageCount
                for (i in 0 until n) {
                    val p = r.openPage(i)
                    val scale = dpi / 72f
                    var w = (p.width * scale).toInt(); var h = (p.height * scale).toInt()
                    val cap = 5000f / max(w, h)
                    if (cap < 1f) { w = (w * cap).toInt(); h = (h * cap).toInt() }
                    val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    p.close()
                    if (saveToPictures(ctx, bmp, "${baseName}_page${i + 1}.jpg")) saved++
                    bmp.recycle()
                    progress?.invoke(i + 1, n)
                }
            }
        }
        return saved
    }

    private fun saveToPictures(ctx: Context, bmp: Bitmap, name: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDF ScanSign")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                true
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PDF ScanSign").apply { mkdirs() }
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                true
            }
        } catch (e: Throwable) {
            false
        }
    }
}
