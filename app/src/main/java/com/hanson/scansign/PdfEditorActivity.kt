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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
    private lateinit var fieldEditor: EditText
    /** Field being typed into in place. */
    private var editingWidget: FormWidget? = null

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

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                val raw = Storage.copyUriToWork(this@PdfEditorActivity, uri, "img") ?: return@withContext null
                // normalise orientation and size; keep PNG transparency, otherwise JPEG
                val bmp = ImageUtils.load(raw, 2400) ?: return@withContext null
                val out = if (bmp.hasAlpha()) Storage.newWorkFile(this@PdfEditorActivity, "png").also { ImageUtils.savePng(bmp, it) }
                else Storage.newWorkFile(this@PdfEditorActivity, "jpg").also { ImageUtils.saveJpeg(bmp, it, 90) }
                bmp.recycle()
                out
            }
            if (f == null) Toast.makeText(this@PdfEditorActivity, "Could not open that picture", Toast.LENGTH_SHORT).show()
            else addSignature(f, width = 0.45f)
        }
    }

    private val organize = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val out = res.data?.getStringExtra(OrganizeActivity.EXTRA_OUT) ?: return@registerForActivityResult
        // reopen the reorganised file in the editor
        dirty = false
        startActivity(intent(this, File(out)))
        finish()
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
        fieldEditor = findViewById(R.id.fieldEditor)
        fieldEditor.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) { nextField(); true }
            else if (action == EditorInfo.IME_ACTION_DONE) { commitEdit(); true }
            else false
        }

        val path = intent.getStringExtra(EXTRA_PDF)
        if (path == null) { finish(); return }
        source = File(path)
        displayName = intent.getStringExtra(EXTRA_NAME) ?: source.name
        toolbar.title = displayName
        toolbar.setNavigationOnClickListener { confirmLeave() }
        doc.listener = this

        findViewById<MaterialButton>(R.id.btnZoomIn).setOnClickListener { doc.zoomIn() }
        findViewById<MaterialButton>(R.id.btnZoomOut).setOnClickListener { doc.zoomOut() }
        pagePill.setOnClickListener { goToPageDialog() }
        setupToolbarMenu()
        setupTools()

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
            PageRenderer(p.displayFile)
        } catch (e: Throwable) {
            setLoading(false, null)
            Toast.makeText(this, "Can't display this PDF", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        pdf = p
        renderer = r
        doc.setDocument(p.geoms, r, p.widgets, items)
        record()
        savedIndex = hIndex
        doc.drawAllValues = !p.valuesBaked && p.fields.isNotEmpty()
        setLoading(false, null)
        toolbar.subtitle = "${p.geoms.size} page${if (p.geoms.size == 1) "" else "s"}" +
            if (p.fields.isNotEmpty()) " · ${p.fields.size} form fields" else ""
        pagePill.visibility = View.VISIBLE
        zoomBox.visibility = View.VISIBLE
        pagePill.text = "1 / ${p.geoms.size}"
        if (p.fields.isNotEmpty()) {
            formBanner.visibility = View.VISIBLE
            formBanner.postDelayed({ formBanner.visibility = View.GONE }, 5000)
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
        record()
    }

    override fun onItemAdded(item: EditItem) {
        record()
    }

    override fun onSelectionChanged(item: EditItem?) {}

    override fun onViewportChanged() = positionEditor()

    override fun onAnyTap() {
        if (editingWidget != null) commitEdit()
    }

    // ---------------- in-place form typing ----------------

    private fun startEdit(w: FormWidget) {
        commitEdit()
        editingWidget = w
        val f = w.field
        fieldEditor.setText(f.value)
        fieldEditor.inputType = if (f.multiline)
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        fieldEditor.imeOptions = if (nextTextWidget(w) != null) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_DONE
        fieldEditor.isSingleLine = !f.multiline
        doc.editingField = f
        fieldEditor.visibility = View.VISIBLE
        // make small fields big enough to type into comfortably
        val minH = 40 * resources.displayMetrics.density
        doc.widgetScreenRect(w)?.let { doc.zoomToFit(it, if (f.multiline) minH * 0.8f else minH) }
        doc.widgetScreenRect(w)?.let { doc.ensureVisible(it) }
        positionEditor()
        fieldEditor.requestFocus()
        fieldEditor.setSelection(fieldEditor.text.length)
        fieldEditor.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(fieldEditor, InputMethodManager.SHOW_IMPLICIT)
            fieldEditor.postDelayed({ doc.widgetScreenRect(w)?.let { doc.ensureVisible(it) } }, 350)
        }
    }

    private fun positionEditor() {
        val w = editingWidget ?: return
        val r = doc.widgetScreenRect(w) ?: return
        val minH = 36 * resources.displayMetrics.density
        val h = if (w.field.multiline) r.height().coerceAtLeast(minH) else r.height().coerceAtLeast(minH)
        val lp = fieldEditor.layoutParams as FrameLayout.LayoutParams
        lp.width = r.width().toInt().coerceAtLeast((60 * resources.displayMetrics.density).toInt())
        lp.height = h.toInt()
        lp.leftMargin = r.left.toInt()
        lp.topMargin = (r.top - (h - r.height()) / 2).toInt()
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        fieldEditor.layoutParams = lp
        fieldEditor.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, (h * 0.55f).coerceIn(28f, 120f))
    }

    private fun commitEdit() {
        val w = editingWidget ?: return
        editingWidget = null
        val value = fieldEditor.text.toString()
        if (value != w.field.value) {
            w.field.value = value
            record()
        }
        doc.editingField = null
        fieldEditor.visibility = View.GONE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(fieldEditor.windowToken, 0)
        doc.refresh()
    }

    /** Next text field in reading order (same page top-to-bottom, then following pages). */
    private fun nextTextWidget(w: FormWidget): FormWidget? {
        val list = pdf?.widgets?.filter { it.field.type == FieldType.TEXT && !it.field.readOnly } ?: return null
        val sorted = list.sortedWith(compareBy<FormWidget>({ it.page }, { (it.rect.top * 50).toInt() }, { it.rect.left }))
        val i = sorted.indexOf(w)
        return if (i >= 0 && i + 1 < sorted.size) sorted[i + 1] else null
    }

    private fun nextField() {
        val cur = editingWidget ?: return
        val next = nextTextWidget(cur)
        commitEdit()
        if (next != null) {
            if (next.page != doc.currentPage()) doc.goToPage(next.page)
            startEdit(next)
        }
    }

    override fun onFieldTapped(widget: FormWidget) {
        if (!requireEditable()) return
        val f = widget.field
        when (f.type) {
            FieldType.TEXT -> startEdit(widget)
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
        record()
        doc.refresh()
    }

    // ---------------- adding items ----------------

    private fun sigAspect(file: File): Float {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else 0.4f
    }

    private fun addSignature(file: File, width: Float = 0.3f) {
        val page = doc.currentPage()
        val size = doc.pageSizePt(page)
        val c = doc.visibleCenterOn(page)
        val fw = width
        val fh = fw * sigAspect(file) * size.x / size.y
        val item = SignatureItem(page, file, c.x - fw / 2, c.y - fh / 2, fw, fh)
        items.add(item)
        record()
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
        record()
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
                    record()
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
                record()
            }
        }
        builder.show()
        input.requestFocus()
    }

    private fun addText(text: String, color: Int, sizePt: Float, bold: Boolean, font: TextFont, boxed: Boolean = false) {
        val page = doc.currentPage()
        val size = doc.pageSizePt(page)
        val c = doc.visibleCenterOn(page)
        val item = TextItem(page, text, color, sizePt, bold, font, 0f, 0f, boxed)
        val (wPt, hPt) = TextRender.sizePt(item)
        item.fx = (c.x - wPt / size.x / 2).coerceIn(0f, 0.95f)
        item.fy = (c.y - hPt / size.y / 2).coerceIn(0f, 0.95f)
        items.add(item)
        record()
        doc.select(item)
        hintOnce("text_hint", "Drag to move · blue dot to resize · tap again to edit")
    }

    // ---------------- undo / redo ----------------

    private class Snap(val items: List<EditItem>, val values: Map<String, String>)
    private val history = ArrayList<Snap>()
    private var hIndex = -1
    private var savedIndex = 0

    private fun snapshot() = Snap(items.map { it.copy() }, pdf?.fields?.associate { it.name to it.value } ?: emptyMap())

    /** Call after every change. */
    private fun record() {
        while (history.size > hIndex + 1) history.removeAt(history.size - 1)
        history.add(snapshot())
        if (history.size > 60) { history.removeAt(0); savedIndex-- }
        hIndex = history.size - 1
        dirty = hIndex != savedIndex
        updateUndoButtons()
    }

    private fun restore(snap: Snap) {
        commitEditSilently()
        items.clear()
        items.addAll(snap.items.map { it.copy() })
        pdf?.fields?.forEach { f -> snap.values[f.name]?.let { f.value = it } }
        doc.select(null)
        doc.refresh()
        dirty = hIndex != savedIndex
        updateUndoButtons()
    }

    private fun undo() { if (hIndex > 0) { hIndex--; restore(history[hIndex]) } }
    private fun redo() { if (hIndex < history.size - 1) { hIndex++; restore(history[hIndex]) } }

    private fun updateUndoButtons() {
        toolbar.menu.findItem(1)?.let { it.isEnabled = hIndex > 0; it.icon?.alpha = if (hIndex > 0) 255 else 90 }
        toolbar.menu.findItem(2)?.let { it.isEnabled = hIndex < history.size - 1; it.icon?.alpha = if (hIndex < history.size - 1) 255 else 90 }
    }

    private fun commitEditSilently() {
        if (editingWidget == null) return
        editingWidget = null
        doc.editingField = null
        fieldEditor.visibility = View.GONE
    }

    // ---------------- toolbar + edit tools ----------------

    private fun setupToolbarMenu() {
        val m = toolbar.menu
        m.add(0, 1, 0, "Undo").setIcon(R.drawable.ic_undo).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 2, 1, "Redo").setIcon(R.drawable.ic_redo).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 3, 2, "Save").setIcon(R.drawable.ic_save).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        m.add(0, 4, 3, "Go to page")
        m.add(0, 5, 4, "Share (save a copy first)")
        m.add(0, 6, 5, "More PDF tools")
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> undo()
                2 -> redo()
                3 -> if (requireEditable()) saveDialog()
                4 -> goToPageDialog()
                5 -> if (dirty) saveDialog() else Storage.sharePdf(this, source)
                6 -> startActivity(Intent(this, ToolsActivity::class.java))
            }
            true
        }
        updateUndoButtons()
    }

    private lateinit var toolOptions: View
    private lateinit var toolTitle: TextView
    private lateinit var shapeKinds: ChipGroup
    private lateinit var toolColors: LinearLayout
    private lateinit var toolWidth: Slider
    private lateinit var widthLabel: TextView
    private var toolColor = Color.rgb(16, 48, 160)
    private var shapeKind = ShapeKind.RECT
    private enum class ActiveTool { NONE, PEN, HIGHLIGHT, SHAPE, WHITEOUT }
    private var active = ActiveTool.NONE

    private fun toolButton(parent: LinearLayout, label: String, icon: Int, onClick: () -> Unit) {
        val b = LayoutInflater.from(this).inflate(R.layout.view_tool_button, parent, false) as MaterialButton
        b.text = label
        b.setIconResource(icon)
        b.setOnClickListener { onClick() }
        parent.addView(b)
    }

    private fun setupTools() {
        toolOptions = findViewById(R.id.toolOptions)
        toolTitle = findViewById(R.id.toolTitle)
        shapeKinds = findViewById(R.id.shapeKinds)
        toolColors = findViewById(R.id.toolColors)
        toolWidth = findViewById(R.id.toolWidth)
        widthLabel = findViewById(R.id.widthLabel)
        findViewById<MaterialButton>(R.id.toolDone).setOnClickListener { setTool(ActiveTool.NONE) }
        toolWidth.addOnChangeListener { _, v, _ -> widthLabel.text = "Size ${"%.1f".format(v)}"; applyTool() }

        for (k in listOf(ShapeKind.RECT, ShapeKind.ELLIPSE, ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.HIGHLIGHT)) {
            val chip = Chip(this).apply {
                id = View.generateViewId(); text = k.label; isCheckable = true; tag = k
            }
            shapeKinds.addView(chip)
            if (k == shapeKind) chip.isChecked = true
        }
        shapeKinds.isSelectionRequired = true
        shapeKinds.setOnCheckedStateChangeListener { g, ids ->
            ids.firstOrNull()?.let { g.findViewById<Chip>(it) }?.let { shapeKind = it.tag as ShapeKind; applyTool() }
        }

        val bar = findViewById<LinearLayout>(R.id.tools)
        toolButton(bar, "Sign", R.drawable.ic_sign) {
            if (requireEditable()) { setTool(ActiveTool.NONE); pendingSigWidget = null; pickSignature.launch(SignaturesActivity.intent(this, pick = true)) }
        }
        toolButton(bar, "Text", R.drawable.ic_text) { if (requireEditable()) { setTool(ActiveTool.NONE); textDialog(null) } }
        toolButton(bar, "Draw", R.drawable.ic_draw) { if (requireEditable()) setTool(ActiveTool.PEN) }
        toolButton(bar, "Highlight", R.drawable.ic_highlight) { if (requireEditable()) setTool(ActiveTool.HIGHLIGHT) }
        toolButton(bar, "Shapes", R.drawable.ic_shapes) { if (requireEditable()) setTool(ActiveTool.SHAPE) }
        toolButton(bar, "Whiteout", R.drawable.ic_eraser) { if (requireEditable()) setTool(ActiveTool.WHITEOUT) }
        toolButton(bar, "Image", R.drawable.ic_image) {
            if (requireEditable()) { setTool(ActiveTool.NONE); pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        toolButton(bar, "Stamp", R.drawable.ic_stamp) { if (requireEditable()) { setTool(ActiveTool.NONE); stampDialog() } }
        toolButton(bar, "Pages", R.drawable.ic_pages) { setTool(ActiveTool.NONE); openPages() }
        toolButton(bar, "Get text", R.drawable.ic_ocr) { setTool(ActiveTool.NONE); extractText() }
        toolButton(bar, "Go to", R.drawable.ic_pdf) { goToPageDialog() }
        toolButton(bar, "Save", R.drawable.ic_save) { if (requireEditable()) { setTool(ActiveTool.NONE); saveDialog() } }
    }

    private fun setTool(t: ActiveTool) {
        commitEdit()
        active = t
        val prefs = getSharedPreferences("tools", MODE_PRIVATE)
        toolOptions.visibility = if (t == ActiveTool.NONE) View.GONE else View.VISIBLE
        findViewById<View>(R.id.shapeScroll).visibility = if (t == ActiveTool.SHAPE) View.VISIBLE else View.GONE
        val showStyle = t != ActiveTool.WHITEOUT
        toolColors.visibility = if (showStyle) View.VISIBLE else View.GONE
        (toolWidth.parent as View).visibility = if (showStyle) View.VISIBLE else View.GONE
        when (t) {
            ActiveTool.PEN -> {
                toolTitle.text = "Draw — one finger draws, two fingers move"
                toolColor = prefs.getInt("pen_color", Color.rgb(16, 48, 160))
                toolWidth.value = prefs.getFloat("pen_width", 2f).coerceIn(0.5f, 16f)
                buildColors(PEN_COLORS)
            }
            ActiveTool.HIGHLIGHT -> {
                toolTitle.text = "Highlight — drag over text"
                toolColor = prefs.getInt("hl_color", HL_COLORS[0])
                toolWidth.value = prefs.getFloat("hl_width", 12f).coerceIn(0.5f, 16f)
                buildColors(HL_COLORS)
            }
            ActiveTool.SHAPE -> {
                toolTitle.text = "Shapes — drag on the page"
                toolColor = prefs.getInt("shape_color", Color.rgb(200, 30, 45))
                toolWidth.value = prefs.getFloat("shape_width", 2f).coerceIn(0.5f, 16f)
                buildColors(PEN_COLORS)
            }
            ActiveTool.WHITEOUT -> toolTitle.text = "Whiteout — drag to cover text or marks"
            ActiveTool.NONE -> {}
        }
        widthLabel.text = "Size ${"%.1f".format(toolWidth.value)}"
        applyTool()
    }

    private fun applyTool() {
        val w = toolWidth.value
        val prefs = getSharedPreferences("tools", MODE_PRIVATE).edit()
        doc.tool = when (active) {
            ActiveTool.NONE -> PdfDocView.Tool.None
            ActiveTool.PEN -> { prefs.putInt("pen_color", toolColor).putFloat("pen_width", w); PdfDocView.Tool.Pen(toolColor, w, 1f) }
            ActiveTool.HIGHLIGHT -> { prefs.putInt("hl_color", toolColor).putFloat("hl_width", w); PdfDocView.Tool.Pen(toolColor, w, 0.35f) }
            ActiveTool.SHAPE -> {
                prefs.putInt("shape_color", toolColor).putFloat("shape_width", w)
                PdfDocView.Tool.Shape(shapeKind, if (shapeKind == ShapeKind.HIGHLIGHT) HL_COLORS[0].let { if (toolColor in PEN_COLORS) it else toolColor } else toolColor, w)
            }
            ActiveTool.WHITEOUT -> PdfDocView.Tool.Shape(ShapeKind.WHITEOUT, Color.WHITE, 1f)
        }
        prefs.apply()
    }

    private val PEN_COLORS = intArrayOf(
        Color.rgb(17, 17, 17), Color.rgb(16, 48, 160), Color.rgb(36, 87, 245), Color.rgb(200, 30, 45),
        Color.rgb(0, 128, 72), Color.rgb(120, 60, 200), Color.rgb(230, 120, 0)
    )
    private val HL_COLORS = intArrayOf(
        Color.rgb(255, 230, 0), Color.rgb(120, 230, 90), Color.rgb(255, 120, 190), Color.rgb(90, 200, 255), Color.rgb(255, 160, 60)
    )

    private fun buildColors(palette: IntArray) {
        toolColors.removeAllViews()
        val d = resources.displayMetrics.density
        val size = (32 * d).toInt()
        val views = ArrayList<View>()
        fun refresh() = views.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(if (palette[i] == toolColor) (3 * d).toInt() else 0, Color.rgb(0, 194, 168))
        }
        for (c in palette) {
            val v = View(this)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = (8 * d).toInt() }
            v.setOnClickListener { toolColor = c; refresh(); applyTool() }
            toolColors.addView(v)
            views.add(v)
        }
        if (toolColor !in palette) toolColor = palette[0]
        refresh()
    }

    private fun stampDialog() {
        val date = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
        data class Stamp(val label: String, val text: String, val color: Int, val boxed: Boolean, val size: Float)
        val green = Color.rgb(0, 140, 70); val red = Color.rgb(205, 30, 40); val blue = Color.rgb(20, 60, 180)
        val stamps = listOf(
            Stamp("✓  Tick", "✓", green, false, 22f), Stamp("✗  Cross", "✗", red, false, 22f),
            Stamp("●  Dot", "●", Color.rgb(17, 17, 17), false, 12f),
            Stamp("APPROVED", "APPROVED", green, true, 16f), Stamp("REJECTED", "REJECTED", red, true, 16f),
            Stamp("PAID", "PAID", green, true, 18f), Stamp("RECEIVED", "RECEIVED", blue, true, 16f),
            Stamp("VERIFIED", "VERIFIED", blue, true, 16f), Stamp("CONFIDENTIAL", "CONFIDENTIAL", red, true, 14f),
            Stamp("DRAFT", "DRAFT", Color.rgb(110, 110, 110), true, 18f), Stamp("COPY", "COPY", blue, true, 16f),
            Stamp("Date: $date", date, blue, true, 12f), Stamp("Signed on $date", "Signed on $date", Color.rgb(17, 17, 17), false, 11f)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Add a stamp")
            .setItems(stamps.map { it.label as CharSequence }.toTypedArray()) { _, i ->
                val st = stamps[i]
                addText(st.text, st.color, st.size, bold = st.boxed, font = TextFont.SANS, boxed = st.boxed)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openPages() {
        val p = pdf ?: return
        if (!p.editable) { Toast.makeText(this, "This PDF can't be edited", Toast.LENGTH_SHORT).show(); return }
        if (dirty) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Save your changes first")
                .setMessage("Page tools work on the saved file. Save now?")
                .setPositiveButton("Save") { _, _ -> saveDialog(thenOrganize = true) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        organize.launch(OrganizeActivity.intent(this, p.file, displayName))
    }

    private fun extractText() {
        val n = doc.pageCount
        if (n <= 0) return
        val cur = doc.currentPage()
        val choices = if (n == 1) arrayOf<CharSequence>("This page")
        else arrayOf<CharSequence>("This page (${cur + 1})", "All pages ($n)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Get text from the PDF")
            .setItems(choices) { _, which ->
                val p = pdf ?: return@setItems
                val pages = if (which == 0) listOf(cur) else (0 until n).toList()
                startActivity(TextResultActivity.intent(this, p.displayFile, pages, displayName))
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (editingWidget != null) { commitEdit(); return }
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

    private var organizeAfterSave = false

    private fun saveDialog(thenOrganize: Boolean = false) {
        organizeAfterSave = thenOrganize
        commitEdit()
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
            savedIndex = hIndex
            val (file, exported) = result
            if (organizeAfterSave) {
                organizeAfterSave = false
                organize.launch(OrganizeActivity.intent(this@PdfEditorActivity, file, file.name))
                return@launch
            }
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
