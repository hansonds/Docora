package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Opens any PDF: view with zoom, fill forms, add signatures and text, save a copy. */
class PdfEditorActivity : AppCompatActivity(), PdfDocView.Listener {

    companion object {
        const val EXTRA_PDF = "pdf"
        const val EXTRA_NAME = "name"
        private val COLORS = intArrayOf(
            Color.rgb(17, 17, 17), Color.rgb(16, 48, 160), Color.rgb(36, 87, 245),
            Color.rgb(200, 30, 45), Color.rgb(0, 128, 72), Color.rgb(120, 60, 200),
            Color.rgb(230, 120, 0), Color.rgb(110, 110, 110)
        )

        fun intent(ctx: Context, pdf: File, displayName: String? = null) =
            Intent(ctx, PdfEditorActivity::class.java)
                .putExtra(EXTRA_PDF, pdf.absolutePath)
                .putExtra(EXTRA_NAME, displayName ?: pdf.name)
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var doc: PdfDocView
    private lateinit var pagePill: TextView
    private lateinit var formBanner: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var zoomBox: View
    private lateinit var toolForm: MaterialButton

    private lateinit var source: File
    private var displayName = "document.pdf"
    private var pdf: OpenedPdf? = null
    private var renderer: PageRenderer? = null
    private val items = ArrayList<EditItem>()
    private var dirty = false
    private var busy = false
    /** Signature field waiting for a signature to be picked. */
    private var pendingSigWidget: FormWidget? = null

    private val pickSignature = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra(SignaturesActivity.EXTRA_SIG)
        val widget = pendingSigWidget
        pendingSigWidget = null
        if (res.resultCode != Activity.RESULT_OK || path == null) return@registerForActivityResult
        if (widget != null) placeSignatureInField(File(path), widget) else addSignature(File(path))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_editor)
        toolbar = findViewById(R.id.toolbar)
        doc = findViewById(R.id.doc)
        pagePill = findViewById(R.id.pagePill)
        formBanner = findViewById(R.id.formBanner)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        zoomBox = findViewById(R.id.zoomBox)
        toolForm = findViewById(R.id.toolForm)

        val path = intent.getStringExtra(EXTRA_PDF)
        if (path == null) { finish(); return }
        source = File(path)
        displayName = intent.getStringExtra(EXTRA_NAME) ?: source.name
        toolbar.title = displayName
        toolbar.setNavigationOnClickListener { confirmLeave() }
        doc.listener = this

        findViewById<MaterialButton>(R.id.btnZoomIn).setOnClickListener { doc.zoomIn() }
        findViewById<MaterialButton>(R.id.btnZoomOut).setOnClickListener { doc.zoomOut() }
        findViewById<MaterialButton>(R.id.toolSign).setOnClickListener {
            if (requireEditable()) {
                pendingSigWidget = null
                pickSignature.launch(SignaturesActivity.intent(this, pick = true))
            }
        }
        findViewById<MaterialButton>(R.id.toolText).setOnClickListener { if (requireEditable()) textDialog(null) }
        findViewById<MaterialButton>(R.id.toolPage).setOnClickListener { goToPageDialog() }
        findViewById<MaterialButton>(R.id.toolSave).setOnClickListener { if (requireEditable()) saveDialog() }
        toolForm.setOnClickListener { toggleFields() }
        pagePill.setOnClickListener { goToPageDialog() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        open(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        doc.release()
        renderer?.close()
    }

    // ---------------- opening ----------------

    private fun open(password: String?) {
        setLoading(true, "Opening…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { PdfOpener.open(this@PdfEditorActivity, source, password) }
            when (result) {
                is OpenResult.Ok -> show(result.pdf)
                OpenResult.NeedPassword -> askPassword(false)
                OpenResult.WrongPassword -> askPassword(true)
                is OpenResult.Error -> {
                    setLoading(false, null)
                    MaterialAlertDialogBuilder(this@PdfEditorActivity)
                        .setTitle("Can't open PDF")
                        .setMessage(result.message)
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }
    }

    private fun askPassword(wrong: Boolean) {
        setLoading(false, null)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(if (wrong) "Wrong password, try again" else "This PDF is password-protected")
            .setView(padded(input))
            .setPositiveButton("Open") { _, _ -> open(input.text.toString()) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun show(p: OpenedPdf) {
        val r = try {
            PageRenderer(p.file)
        } catch (e: Throwable) {
            setLoading(false, null)
            Toast.makeText(this, "Can't display this PDF", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pdf = p
        renderer = r
        doc.setDocument(p.geoms, r, p.widgets, items)
        setLoading(false, null)
        toolbar.subtitle = "${p.geoms.size} page${if (p.geoms.size == 1) "" else "s"}" +
            if (p.fields.isNotEmpty()) " · ${p.fields.size} form fields" else ""
        pagePill.visibility = View.VISIBLE
        zoomBox.visibility = View.VISIBLE
        pagePill.text = "1 / ${p.geoms.size}"
        if (p.fields.isNotEmpty()) {
            formBanner.visibility = View.VISIBLE
            formBanner.postDelayed({ formBanner.visibility = View.GONE }, 6000)
        } else {
            toolForm.visibility = View.GONE
        }
        if (!p.editable) {
            Toast.makeText(this, "This PDF can be viewed, but it can't be edited", Toast.LENGTH_LONG).show()
        }
    }

    private fun requireEditable(): Boolean {
        val p = pdf ?: return false
        if (!p.editable) {
            Toast.makeText(this, "This PDF can be viewed, but it can't be edited", Toast.LENGTH_SHORT).show()
            return false
        }
        return !busy
    }

    private fun setLoading(on: Boolean, text: String?) {
        busy = on
        loading.visibility = if (on) View.VISIBLE else View.GONE
        if (text != null) loadingText.text = text
    }

    // ---------------- viewer callbacks ----------------

    override fun onPageChanged(page: Int) {
        pagePill.text = "${page + 1} / ${doc.pageCount}"
    }

    override fun onItemTapped(item: EditItem) {
        if (item is TextItem) textDialog(item)
    }

    override fun onItemsChanged() {
        dirty = true
    }

    override fun onSelectionChanged(item: EditItem?) {}

    override fun onFieldTapped(widget: FormWidget) {
        if (!requireEditable()) return
        val f = widget.field
        when (f.type) {
            FieldType.TEXT -> {
                val input = EditText(this).apply {
                    setText(f.value)
                    setSelection(text.length)
                    inputType = if (f.multiline)
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                }
                MaterialAlertDialogBuilder(this)
                    .setTitle(f.label.ifBlank { "Fill in" })
                    .setView(padded(input))
                    .setPositiveButton("OK") { _, _ -> setField(f, input.text.toString()) }
                    .setNeutralButton("Clear") { _, _ -> setField(f, "") }
                    .setNegativeButton("Cancel", null)
                    .show()
                input.requestFocus()
            }
            FieldType.CHECKBOX -> {
                val checked = f.value != "Off" && f.value.isNotEmpty()
                setField(f, if (checked) "Off" else (widget.onValue ?: "Yes"))
            }
            FieldType.RADIO -> widget.onValue?.let { setField(f, it) }
            FieldType.CHOICE -> {
                val display = f.optionsDisplay.ifEmpty { f.optionsExport }
                if (display.isEmpty()) return
                val export = f.optionsExport.ifEmpty { display }
                val current = export.indexOf(f.value)
                MaterialAlertDialogBuilder(this)
                    .setTitle(f.label.ifBlank { "Choose" })
                    .setSingleChoiceItems(display.toTypedArray<CharSequence>(), current) { d, which ->
                        setField(f, export.getOrElse(which) { display[which] })
                        d.dismiss()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            FieldType.SIGNATURE -> {
                pendingSigWidget = widget
                pickSignature.launch(SignaturesActivity.intent(this, pick = true))
            }
        }
    }

    private fun setField(f: FormField, value: String) {
        f.value = value
        dirty = true
        doc.refresh()
    }

    private fun toggleFields() {
        doc.showFields = !doc.showFields
        Toast.makeText(
            this, if (doc.showFields) "Form fields highlighted" else "Field highlight hidden", Toast.LENGTH_SHORT
        ).show()
    }

    // ---------------- adding items ----------------

    private fun sigAspect(file: File): Float {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else 0.4f
    }

    private fun addSignature(file: File) {
        val page = doc.currentPage()
        val size = doc.pageSizePt(page)
        val c = doc.visibleCenterOn(page)
        val fw = 0.3f
        val fh = fw * sigAspect(file) * size.x / size.y
        val item = SignatureItem(page, file, c.x - fw / 2, c.y - fh / 2, fw, fh)
        items.add(item)
        dirty = true
        doc.select(item)
        hintOnce("sig_hint", "Drag to move · pinch or blue dot to resize · tap × to remove")
    }

    private fun placeSignatureInField(file: File, w: FormWidget) {
        val size = doc.pageSizePt(w.page)
        val rwPt = w.rect.width() * size.x
        val rhPt = w.rect.height() * size.y
        val a = sigAspect(file)
        val (wPt, hPt) = if (rhPt / rwPt > a) Pair(rwPt, rwPt * a) else Pair(rhPt / a, rhPt)
        val fw = wPt / size.x
        val fh = hPt / size.y
        val fx = w.rect.left + (w.rect.width() - fw) / 2
        val fy = w.rect.top + (w.rect.height() - fh) / 2
        val item = SignatureItem(w.page, file, fx, fy, fw, fh)
        items.add(item)
        dirty = true
        doc.select(item)
    }

    private fun hintOnce(key: String, msg: String) {
        val prefs = getSharedPreferences("hints", MODE_PRIVATE)
        if (prefs.getBoolean(key, false)) return
        prefs.edit().putBoolean(key, true).apply()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /** Add a new text box ([existing] == null) or edit one. */
    private fun textDialog(existing: TextItem?) {
        val prefs = getSharedPreferences("text", MODE_PRIVATE)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_text, null)
        val input = view.findViewById<EditText>(R.id.text)
        val sizeLabel = view.findViewById<TextView>(R.id.sizeLabel)
        val slider = view.findViewById<Slider>(R.id.size)
        val colors = view.findViewById<LinearLayout>(R.id.colors)
        val fonts = view.findViewById<ChipGroup>(R.id.fonts)
        val bold = view.findViewById<MaterialSwitch>(R.id.bold)

        var color = existing?.color ?: prefs.getInt("color", COLORS[0])
        var font = existing?.font ?: runCatching { TextFont.valueOf(prefs.getString("font", "SANS")!!) }.getOrDefault(TextFont.SANS)
        val size = existing?.sizePt ?: prefs.getFloat("size", 14f)
        input.setText(existing?.text ?: "")
        slider.value = size.coerceIn(6f, 72f).toInt().toFloat()
        sizeLabel.text = "Size: ${slider.value.toInt()} pt"
        slider.addOnChangeListener { _, v, _ -> sizeLabel.text = "Size: ${v.toInt()} pt" }
        bold.isChecked = existing?.bold ?: prefs.getBoolean("bold", false)

        view.findViewById<MaterialButton>(R.id.btnDate).setOnClickListener {
            val date = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            val start = input.selectionStart.coerceAtLeast(0)
            input.text.insert(start, date)
        }

        // colour swatches
        val swatchSize = (34 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        val swatches = ArrayList<View>()
        fun refreshSwatches() {
            swatches.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setStroke(
                    if (COLORS[i] == color) margin / 2 + 2 else 0, Color.rgb(0, 194, 168)
                )
            }
        }
        for (c in COLORS) {
            val v = View(this)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            v.layoutParams = LinearLayout.LayoutParams(swatchSize, swatchSize).apply { marginEnd = margin }
            v.setOnClickListener { color = c; refreshSwatches() }
            colors.addView(v)
            swatches.add(v)
        }
        refreshSwatches()

        for (f in TextFont.values()) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = f.label
                isCheckable = true
                tag = f
            }
            fonts.addView(chip)
            if (f == font) chip.isChecked = true
        }
        fonts.isSingleSelection = true
        fonts.isSelectionRequired = true
        fonts.setOnCheckedStateChangeListener { g, ids ->
            ids.firstOrNull()?.let { g.findViewById<Chip>(it) }?.let { font = it.tag as TextFont }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Add text" else "Edit text")
            .setView(view)
            .setPositiveButton(if (existing == null) "Add" else "Done") { _, _ ->
                val text = input.text.toString().trimEnd()
                if (text.isBlank()) return@setPositiveButton
                val sizePt = slider.value
                prefs.edit().putInt("color", color).putFloat("size", sizePt)
                    .putBoolean("bold", bold.isChecked).putString("font", font.name).apply()
                if (existing != null) {
                    existing.text = text
                    existing.color = color
                    existing.sizePt = sizePt
                    existing.bold = bold.isChecked
                    existing.font = font
                    dirty = true
                    doc.refresh()
                } else {
                    addText(text, color, sizePt, bold.isChecked, font)
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton("Delete") { _, _ ->
                items.remove(existing)
                doc.select(null)
                dirty = true
            }
        }
        builder.show()
        input.requestFocus()
    }

    private fun addText(text: String, color: Int, sizePt: Float, bold: Boolean, font: TextFont) {
        val page = doc.currentPage()
        val size = doc.pageSizePt(page)
        val c = doc.visibleCenterOn(page)
        val item = TextItem(page, text, color, sizePt, bold, font, 0f, 0f)
        val (wPt, hPt) = TextRender.sizePt(item)
        item.fx = (c.x - wPt / size.x / 2).coerceIn(0f, 0.95f)
        item.fy = (c.y - hPt / size.y / 2).coerceIn(0f, 0.95f)
        items.add(item)
        dirty = true
        doc.select(item)
        hintOnce("text_hint", "Drag to move · blue dot to resize · tap again to edit")
    }

    // ---------------- navigation ----------------

    private fun goToPageDialog() {
        val n = doc.pageCount
        if (n <= 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – $n"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Go to page")
            .setView(padded(input))
            .setPositiveButton("Go") { _, _ ->
                input.text.toString().toIntOrNull()?.let { doc.goToPage(it - 1) }
            }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun confirmLeave() {
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save changes?")
            .setMessage("You have unsaved changes to this PDF.")
            .setPositiveButton("Save") { _, _ -> saveDialog() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ---------------- saving ----------------

    private fun saveDialog() {
        val p = pdf ?: return
        if (!dirty) {
            Toast.makeText(this, "Nothing changed yet", Toast.LENGTH_SHORT).show()
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = EditText(this).apply {
            val base = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
            setText(if (base.endsWith("_signed")) base else base + "_signed")
            setSelectAllOnFocus(true)
            hint = "File name"
        }
        box.addView(name)
        val flatten = CheckBox(this).apply {
            text = "Lock form fields (can't be edited later)"
        }
        if (p.fields.isNotEmpty()) box.addView(flatten)
        MaterialAlertDialogBuilder(this)
            .setTitle("Save PDF")
            .setView(padded(box))
            .setPositiveButton("Save") { _, _ -> save(name.text.toString(), flatten.isChecked) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save(name: String, flatten: Boolean) {
        val p = pdf ?: return
        doc.select(null)
        setLoading(true, "Saving…")
        val snapshot = ArrayList(items)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val out = Storage.uniquePdf(this@PdfEditorActivity, name)
                    PdfSaver.save(p, out, snapshot, flatten)
                    Pair(out, Storage.exportToDownloads(this@PdfEditorActivity, out))
                } catch (e: Throwable) {
                    null
                }
            }
            setLoading(false, null)
            if (result == null) {
                Toast.makeText(this@PdfEditorActivity, "Could not save the PDF", Toast.LENGTH_LONG).show()
                return@launch
            }
            dirty = false
            val (file, exported) = result
            MaterialAlertDialogBuilder(this@PdfEditorActivity)
                .setTitle("Saved")
                .setMessage(file.name + if (exported) "\n\nA copy is in Downloads › PDF ScanSign." else "")
                .setPositiveButton("Share") { _, _ -> Storage.sharePdf(this@PdfEditorActivity, file) }
                .setNeutralButton("Open in another app") { _, _ ->
                    try { Storage.openPdfExternally(this@PdfEditorActivity, file) } catch (_: Exception) {}
                }
                .setNegativeButton("Close") { _, _ -> finish() }
                .show()
        }
    }

    private fun padded(v: View): View {
        val pad = (22 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
