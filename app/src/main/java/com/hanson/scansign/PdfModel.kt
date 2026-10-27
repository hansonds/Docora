package com.hanson.scansign

import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Page box + rotation; converts between displayed coordinates (top-left origin) and PDF user space. */
data class PageGeom(val llx: Float, val lly: Float, val w: Float, val h: Float, val rot: Int) {
    val dispW: Float get() = if (rot == 90 || rot == 270) h else w
    val dispH: Float get() = if (rot == 90 || rot == 270) w else h

    /** Displayed (u, v; v downward, in points) -> PDF user space (x, y; y upward). */
    fun toUser(u: Float, v: Float): PointF = when (rot) {
        90 -> PointF(llx + v, lly + u)
        180 -> PointF(llx + w - u, lly + v)
        270 -> PointF(llx + w - v, lly + h - u)
        else -> PointF(llx + u, lly + h - v)
    }

    /** PDF user space -> displayed coordinates. */
    fun toDisp(x: Float, y: Float): PointF = when (rot) {
        90 -> PointF(y - lly, x - llx)
        180 -> PointF(llx + w - x, y - lly)
        270 -> PointF(lly + h - y, llx + w - x)
        else -> PointF(x - llx, lly + h - y)
    }

    /** A user-space rectangle as fractions (0..1) of the displayed page. */
    fun userRectToFrac(x1: Float, y1: Float, x2: Float, y2: Float): RectF {
        val a = toDisp(x1, y1)
        val b = toDisp(x2, y2)
        return RectF(
            min(a.x, b.x) / dispW, min(a.y, b.y) / dispH,
            max(a.x, b.x) / dispW, max(a.y, b.y) / dispH
        )
    }
}

enum class TextFont(val label: String) { SANS("Sans"), SERIF("Serif"), MONO("Mono") }

/** Something the user added on top of a page. Positions are fractions of the displayed page (top-left origin). */
sealed class EditItem {
    abstract val page: Int
    abstract var fx: Float
    abstract var fy: Float
    abstract fun copy(): EditItem
}

/** Rotation of an item (only signatures / images and text / stamps can rotate). */
var EditItem.rotationDeg: Float
    get() = when (this) { is SignatureItem -> rotation; is TextItem -> rotation; else -> 0f }
    set(v) {
        val n = ((v % 360f) + 360f) % 360f
        when (this) { is SignatureItem -> rotation = n; is TextItem -> rotation = n; else -> {} }
    }

val EditItem.canRotate: Boolean get() = this is SignatureItem || this is TextItem

/** A picture on the page: a signature (transparent PNG) or any photo / image. */
class SignatureItem(
    override val page: Int,
    val file: File,
    override var fx: Float,
    override var fy: Float,
    var fw: Float,
    var fh: Float,
    /** Clockwise rotation in degrees, around the item's centre. */
    var rotation: Float = 0f
) : EditItem() {
    override fun copy() = SignatureItem(page, file, fx, fy, fw, fh, rotation)
}

class TextItem(
    override val page: Int,
    var text: String,
    var color: Int,
    var sizePt: Float,
    var bold: Boolean,
    var font: TextFont,
    override var fx: Float,
    override var fy: Float,
    /** Draw a rounded frame around the text (stamps). */
    var boxed: Boolean = false,
    /** Clockwise rotation in degrees, around the item's centre. */
    var rotation: Float = 0f
) : EditItem() {
    override fun copy() = TextItem(page, text, color, sizePt, bold, font, fx, fy, boxed, rotation)
}

/** One freehand pen / highlighter stroke. Points are page fractions. */
class InkItem(
    override val page: Int,
    val points: MutableList<PointF>,
    var color: Int,
    var widthPt: Float,
    /** 1 = pen, < 1 = highlighter. */
    var alpha: Float
) : EditItem() {
    override var fx: Float
        get() = points.minOfOrNull { it.x } ?: 0f
        set(v) { val d = v - fx; points.forEach { it.x += d } }
    override var fy: Float
        get() = points.minOfOrNull { it.y } ?: 0f
        set(v) { val d = v - fy; points.forEach { it.y += d } }
    override fun copy() = InkItem(page, points.map { PointF(it.x, it.y) }.toMutableList(), color, widthPt, alpha)
}

enum class ShapeKind(val label: String) {
    RECT("Box"), ELLIPSE("Circle"), LINE("Line"), ARROW("Arrow"), WHITEOUT("Whiteout"), HIGHLIGHT("Highlight box")
}

/**
 * A shape. For RECT / ELLIPSE / WHITEOUT / HIGHLIGHT the box is (fx, fy, fw, fh).
 * For LINE / ARROW it runs from (fx, fy) to (fx + fw, fy + fh) (fw / fh may be negative).
 */
class ShapeItem(
    override val page: Int,
    val kind: ShapeKind,
    override var fx: Float,
    override var fy: Float,
    var fw: Float,
    var fh: Float,
    var color: Int,
    var widthPt: Float
) : EditItem() {
    override fun copy() = ShapeItem(page, kind, fx, fy, fw, fh, color, widthPt)
    val isLine: Boolean get() = kind == ShapeKind.LINE || kind == ShapeKind.ARROW
}

enum class FieldType { TEXT, CHECKBOX, RADIO, CHOICE, SIGNATURE }

/** One fillable field of the PDF form. */
class FormField(
    val name: String,
    val type: FieldType,
    val label: String,
    val multiline: Boolean,
    val readOnly: Boolean,
    val optionsDisplay: List<String>,
    val optionsExport: List<String>,
    val editableChoice: Boolean,
    val original: String,
    var value: String
) {
    val changed: Boolean get() = value != original
}

/** Where a field appears on a page. [onValue] is the "checked" state name for checkboxes / radio buttons. */
class FormWidget(val field: FormField, val page: Int, val rect: RectF, val onValue: String?)

class OpenedPdf(
    /** File that PDFBox edits and saves from (decrypted / repaired copy if needed). */
    val file: File,
    /** File shown on screen: same as [file], or a copy with form values and annotations drawn into the pages. */
    val displayFile: File,
    /** True when existing form values are already visible in [displayFile]. */
    val valuesBaked: Boolean,
    val geoms: List<PageGeom>,
    val fields: List<FormField>,
    val widgets: List<FormWidget>,
    val editable: Boolean
)

sealed class OpenResult {
    class Ok(val pdf: OpenedPdf) : OpenResult()
    object NeedPassword : OpenResult()
    object WrongPassword : OpenResult()
    class Error(val message: String) : OpenResult()
}

object PdfOpener {

    fun memory(): MemoryUsageSetting = MemoryUsageSetting.setupMixed(48L * 1024 * 1024)

    /** Opens any PDF: handles passwords, encryption and damaged files. Call off the main thread. */
    fun open(ctx: Context, src: File, password: String?): OpenResult {
        var renderFile = src
        var geoms: List<PageGeom>? = null
        var fields: List<FormField> = emptyList()
        var widgets: List<FormWidget> = emptyList()
        var editable = true
        var displayFile: File? = null
        var valuesBaked = false

        try {
            PDDocument.load(src, password ?: "", memory()).use { doc ->
                if (doc.isEncrypted) {
                    // remove the security so it can be displayed and edited
                    doc.setAllSecurityToBeRemoved(true)
                    val dec = Storage.newWorkFile(ctx, "pdf")
                    doc.save(dec)
                    renderFile = dec
                }
                geoms = readGeoms(doc)
                val (f, w) = readForm(doc)
                fields = f
                widgets = w
                // Android's renderer doesn't draw form values or annotations: bake them into a display copy
                if (fields.isNotEmpty() || hasVisibleAnnotations(doc)) {
                    try {
                        valuesBaked = bake(doc)
                        val disp = Storage.newWorkFile(ctx, "pdf")
                        doc.save(disp)
                        displayFile = disp
                    } catch (e: Throwable) {
                        displayFile = null
                        valuesBaked = false
                    }
                }
            }
        } catch (e: InvalidPasswordException) {
            return if (password == null) OpenResult.NeedPassword else OpenResult.WrongPassword
        } catch (e: Throwable) {
            // PDFBox could not parse it; maybe the viewer still can
            editable = false
        }

        // make sure the system renderer can display it; if not, let PDFBox rewrite (repair) it
        if (!canRender(renderFile)) {
            if (!editable) return OpenResult.Error("This file is damaged or is not a PDF.")
            try {
                val repaired = Storage.newWorkFile(ctx, "pdf")
                PDDocument.load(renderFile, password ?: "", memory()).use { doc ->
                    doc.setAllSecurityToBeRemoved(true)
                    doc.save(repaired)
                }
                if (!canRender(repaired)) return OpenResult.Error("This PDF can't be displayed.")
                renderFile = repaired
            } catch (e: Throwable) {
                return OpenResult.Error("This PDF can't be displayed.")
            }
        }

        val g = geoms ?: rendererGeoms(renderFile) ?: return OpenResult.Error("This PDF has no pages.")
        if (g.isEmpty()) return OpenResult.Error("This PDF has no pages.")
        var disp = displayFile ?: renderFile
        if (disp != renderFile && !canRender(disp)) { disp = renderFile; valuesBaked = false }
        return OpenResult.Ok(OpenedPdf(renderFile, disp, valuesBaked, g, fields, widgets, editable))
    }

    private fun skipAnnot(a: PDAnnotation): Boolean =
        a is PDAnnotationWidget || a is PDAnnotationLink || a is PDAnnotationPopup || a.isHidden || a.isNoView

    private fun hasVisibleAnnotations(doc: PDDocument): Boolean {
        for (page in doc.pages) {
            val annots = try { page.annotations } catch (e: Throwable) { continue }
            if (annots.any { !skipAnnot(it) && it.normalAppearanceStream != null }) return true
        }
        return false
    }

    /**
     * Draws form fields (with their current values) and other annotations into the page content.
     * Only used for the on-screen copy. Returns true if form values were baked in.
     */
    private fun bake(doc: PDDocument): Boolean {
        var formOk = false
        val form = doc.documentCatalog.acroForm
        if (form != null) {
            try { form.refreshAppearances() } catch (_: Throwable) {}
            formOk = try {
                form.flatten(form.fieldTree.toList(), true)
                true
            } catch (e: Throwable) {
                false
            }
        }
        for (page in doc.pages) {
            val annots = try { page.annotations } catch (e: Throwable) { continue }
            val draw = annots.filter { !skipAnnot(it) && it.normalAppearanceStream != null }
            if (draw.isEmpty()) continue
            try {
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in draw) drawAppearance(cs, a)
                }
            } catch (_: Throwable) {}
        }
        return formOk
    }

    /** Places an annotation's appearance stream into its rectangle (same maths viewers use). */
    private fun drawAppearance(cs: PDPageContentStream, a: PDAnnotation) {
        val ap = a.normalAppearanceStream ?: return
        val rect = a.rectangle ?: return
        val bbox = ap.bBox ?: return
        val m = ap.matrix
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val corners = arrayOf(
            floatArrayOf(bbox.lowerLeftX, bbox.lowerLeftY), floatArrayOf(bbox.upperRightX, bbox.lowerLeftY),
            floatArrayOf(bbox.upperRightX, bbox.upperRightY), floatArrayOf(bbox.lowerLeftX, bbox.upperRightY)
        )
        for (i in 0..3) {
            val p = m.transformPoint(corners[i][0], corners[i][1])
            xs[i] = p.x; ys[i] = p.y
        }
        val bx = xs.min(); val by = ys.min()
        val bw = xs.max() - bx; val bh = ys.max() - by
        if (bw <= 0f || bh <= 0f) return
        val sx = rect.width / bw
        val sy = rect.height / bh
        cs.saveGraphicsState()
        cs.transform(com.tom_roush.pdfbox.util.Matrix(sx, 0f, 0f, sy, rect.lowerLeftX - bx * sx, rect.lowerLeftY - by * sy))
        cs.drawForm(ap)
        cs.restoreGraphicsState()
    }

    private fun canRender(f: File): Boolean = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount > 0 }
        }
    } catch (e: Throwable) {
        false
    }

    private fun rendererGeoms(f: File): List<PageGeom>? = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until r.pageCount).map { i ->
                    val p = r.openPage(i)
                    try { PageGeom(0f, 0f, p.width.toFloat(), p.height.toFloat(), 0) } finally { p.close() }
                }
            }
        }
    } catch (e: Throwable) {
        null
    }

    private fun readGeoms(doc: PDDocument): List<PageGeom> {
        val out = ArrayList<PageGeom>(doc.numberOfPages)
        for (page in doc.pages) {
            val box = page.cropBox
            val rot = ((page.rotation % 360) + 360) % 360
            out.add(PageGeom(box.lowerLeftX, box.lowerLeftY, box.width.coerceAtLeast(1f), box.height.coerceAtLeast(1f), rot))
        }
        return out
    }

    private fun readForm(doc: PDDocument): Pair<List<FormField>, List<FormWidget>> {
        val form = doc.documentCatalog.acroForm ?: return Pair(emptyList(), emptyList())
        val fields = ArrayList<FormField>()
        val widgets = ArrayList<FormWidget>()
        var annotPageIndex: HashMap<COSDictionary, Int>? = null

        fun pageOf(w: PDAnnotationWidget): Int {
            val p = w.page
            if (p != null) {
                val i = doc.pages.indexOf(p)
                if (i >= 0) return i
            }
            // widget without /P: build an index once
            val idx = annotPageIndex ?: HashMap<COSDictionary, Int>().also { map ->
                var i = 0
                for (pg in doc.pages) {
                    try { pg.annotations.forEach { a -> map[a.cosObject] = i } } catch (_: Exception) {}
                    i++
                }
                annotPageIndex = map
            }
            return idx[w.cosObject] ?: -1
        }

        val geoms = readGeoms(doc)
        for (f in form.fieldTree) {
            try {
                val type = when (f) {
                    is PDTextField -> FieldType.TEXT
                    is PDCheckBox -> FieldType.CHECKBOX
                    is PDRadioButton -> FieldType.RADIO
                    is PDChoice -> FieldType.CHOICE
                    is PDSignatureField -> FieldType.SIGNATURE
                    else -> null
                } ?: continue
                val ws = f.widgets ?: continue
                if (ws.isEmpty()) continue
                val value = when (f) {
                    is PDCheckBox -> f.value ?: "Off"
                    is PDRadioButton -> f.value ?: "Off"
                    is PDChoice -> f.value?.firstOrNull() ?: ""
                    is PDSignatureField -> ""
                    else -> f.valueAsString ?: ""
                }
                val display = if (f is PDChoice) safe { f.optionsDisplayValues } ?: emptyList() else emptyList()
                val export = if (f is PDChoice) safe { f.optionsExportValues } ?: emptyList() else emptyList()
                val field = FormField(
                    name = f.fullyQualifiedName,
                    type = type,
                    label = (f.alternateFieldName ?: f.partialName ?: f.fullyQualifiedName).trim(),
                    multiline = f is PDTextField && f.isMultiline,
                    readOnly = f.isReadOnly,
                    optionsDisplay = display,
                    optionsExport = export,
                    editableChoice = f is PDComboBox && f.isEdit,
                    original = value,
                    value = value
                )
                var added = false
                for (w in ws) {
                    val pi = pageOf(w)
                    if (pi !in geoms.indices) continue
                    val r = w.rectangle ?: continue
                    val frac = geoms[pi].userRectToFrac(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
                    if (frac.width() <= 0f || frac.height() <= 0f) continue
                    val on = if (type == FieldType.CHECKBOX || type == FieldType.RADIO) onStateOf(w) else null
                    widgets.add(FormWidget(field, pi, frac, on))
                    added = true
                }
                if (added) fields.add(field)
            } catch (_: Throwable) {
                // skip fields we can't understand
            }
        }
        return Pair(fields, widgets)
    }

    /** The appearance state name that means "on" for a checkbox / radio widget. */
    private fun onStateOf(w: PDAnnotationWidget): String? = try {
        val n = w.appearance?.normalAppearance
        if (n != null && n.isSubDictionary) {
            n.subDictionary.keys.map { it.name }.firstOrNull { it != "Off" }
        } else null
    } catch (e: Throwable) {
        null
    } ?: "Yes"

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (e: Throwable) { null }
}
