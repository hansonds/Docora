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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Opens any PDF: view with zoom, fill forms, add signatures and text, save a copy. */
class PdfEditorActivity : BaseActivity(), PdfDocView.Listener {

    companion object {
        const val EXTRA_PDF = "pdf"
        const val EXTRA_NAME = "name"
        private val COLORS = intArrayOf(
            Color.rgb(17, 17, 17), Color.rgb(16, 48, 160), Color.rgb(36, 87, 245),
            Color.rgb(200, 30, 45), Color.rgb(0, 128, 72), Color.rgb(120, 60, 200),
            Color.rgb(230, 120, 0), Color.rgb(110, 110, 110)
        )

        const val EXTRA_URI = "uri"

        /** [sourceUri] = where the PDF came from, so "Replace original" can write back to it. */
        fun intent(ctx: Context, pdf: File, displayName: String? = null, sourceUri: android.net.Uri? = null) =
            Intent(ctx, PdfEditorActivity::class.java)
                .putExtra(EXTRA_PDF, pdf.absolutePath)
                .putExtra(EXTRA_NAME, displayName ?: pdf.name)
                .putExtra(EXTRA_URI, sourceUri)
    }

    override val recreateOnThemeChange = false
    private lateinit var toolbar: MaterialToolbar
    private lateinit var doc: PdfDocView
    private lateinit var pagePill: TextView
    private lateinit var formBanner: TextView
    private lateinit var loading: View
    private lateinit var loadingText: TextView
    private lateinit var zoomBox: View
    private lateinit var undoBox: View
    private lateinit var topBar: View
    private lateinit var dock: View
    private lateinit var editorRoot: View
    private lateinit var btnExitFull: View
    private var fullscreen = false
    /** Full screen is applied automatically only when the file is first opened. */
    private var autoFullscreenDone = false
    private var recordedOpen = false
    private val hideExit = Runnable { fade(btnExitFull, false) }
    private val hidePill = Runnable { if (fullscreen) fade(pagePill, false) }
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
            else {
                val target = pendingImageRect
                pendingImageRect = null
                if (target != null) placeImageIn(f, target.first, target.second) else addSignature(f, width = 0.45f)
            }
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
        undoBox = findViewById(R.id.undoBox)
        topBar = findViewById(R.id.topBar)
        dock = findViewById(R.id.dock)
        editorRoot = findViewById(R.id.editorRoot)
        btnExitFull = findViewById(R.id.btnExitFull)
        fieldEditor = findViewById(R.id.fieldEditor)
        fieldEditor.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) { nextField(); true }
            else if (action == EditorInfo.IME_ACTION_DONE) { commitEdit(); true }
            else false
        }

        val path = intent.getStringExtra(EXTRA_PDF)
        sourceUri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_URI, android.net.Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_URI)
        // opened from another app: only the link is known, the copy is made below
        if (path == null && sourceUri == null) { finish(); return }
        source = if (path != null) File(path) else File(cacheDir, "opening.pdf")
        displayName = intent.getStringExtra(EXTRA_NAME) ?: if (path != null) source.name else "document.pdf"
        toolbar.title = displayName
        findViewById<View>(R.id.btnBack).setOnClickListener { confirmLeave() }
        doc.listener = this
        doc.nightMode = getSharedPreferences("view", MODE_PRIVATE).getBoolean("night", false)

        findViewById<View>(R.id.btnZoomIn).setOnClickListener { doc.zoomIn() }
        findViewById<View>(R.id.btnZoomOut).setOnClickListener { doc.zoomOut() }
        findViewById<View>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<View>(R.id.btnRedo).setOnClickListener { redo() }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { setFullscreen(true) }
        btnExitFull.setOnClickListener { setFullscreen(false) }
        setupFind()
        // keep pages clear of the floating bars and float the controls just above the dock
        val relayoutChrome = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> editorRoot.post { updateChrome() } }
        editorRoot.addOnLayoutChangeListener(relayoutChrome)
        dock.addOnLayoutChangeListener(relayoutChrome)
        topBar.addOnLayoutChangeListener(relayoutChrome)
        pagePill.setOnClickListener { pagesSheet() }
        textBar = findViewById(R.id.textBar)
        findViewById<MaterialButton>(R.id.selCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("text", selText))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            doc.clearTextSelection()
        }
        findViewById<MaterialButton>(R.id.selAll).setOnClickListener { doc.selectAllText() }
        findViewById<MaterialButton>(R.id.selTranslate).setOnClickListener {
            val text = selText
            doc.clearTextSelection()
            TranslateSheet.show(this, "Selected text") { text }
        }
        findViewById<MaterialButton>(R.id.selShare).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, selText)
            }, "Share text"))
        }
        setupToolbarMenu()
        setupTools()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = when {
                typing != null -> finishTyping(true)
                searching -> closeFind()
                betaMode -> exitBeta()
                fullscreen -> setFullscreen(false)
                else -> confirmLeave()
            }
        })

        if (path != null) open(null) else openFromLink(sourceUri!!)
    }

    /** Copies a PDF shared or opened from another app (WhatsApp, Files, Gmail…) and opens it. */
    private fun openFromLink(uri: android.net.Uri) {
        setLoading(true, "Opening…")
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { Storage.displayName(this@PdfEditorActivity, uri) } ?: "document.pdf"
            val f = withContext(Dispatchers.IO) { Storage.copyUriToWork(this@PdfEditorActivity, uri, "pdf") }
            if (f == null) {
                setLoading(false, null)
                Toast.makeText(this@PdfEditorActivity, "Could not open that PDF", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            source = f
            displayName = name
            toolbar.title = name
            open(null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        findJob?.cancel()
        closeSearchDoc()
        doc.release()
        renderer?.close()
    }

    // ---------------- opening ----------------

    /** A long document shown for reading only: its editing / form data hasn't been read yet. */
    private var needsFull = false
    /** That data is being read right now. */
    private var preparing = false
    /** The editing action to continue with once it's ready. */
    private var afterPrepare: (() -> Unit)? = null

    /** Reads a long document's editing data (only when an editing tool is used), then runs [then]. */
    private fun prepareEditing(then: (() -> Unit)?) {
        if (then != null) afterPrepare = then
        if (preparing) return
        preparing = true
        setLoading(true, "Getting editing ready…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { PdfOpener.open(this@PdfEditorActivity, source, null) }
            preparing = false
            needsFull = false
            setLoading(false, null)
            val next = afterPrepare
            afterPrepare = null
            if (result is OpenResult.Ok) {
                upgrade(result.pdf)
                if (result.pdf.editable) next?.invoke()
            } else {
                Toast.makeText(this@PdfEditorActivity, "This PDF can be read, but it can't be edited", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runs an editing [action] now, or after the editing data of a long document has been read. */
    private fun editing(action: () -> Unit) {
        if (requireEditable(action)) action()
    }

    private fun open(password: String?) {
        setLoading(true, "Opening…")
        lifecycleScope.launch {
            // long documents: show the pages straight away, read the rest in the background
            val quick = if (password == null) withContext(Dispatchers.IO) { PdfOpener.quickOpen(source) } else null
            if (quick != null) {
                // just reading needs nothing more; editing data is read when an editing tool is first used
                needsFull = true
                show(quick)
                return@launch
            }
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
        resetSearch()
        if (betaMode) {
            // the file changed (e.g. after Save): read the pages again
            doc.pickMode = false
            doc.pickMode = true
            doc.post { if (betaMode) analyzeAround(doc.currentPage()) }
        }
        doc.setDocument(p.geoms, r, p.widgets, items)
        if (restorePage > 0) { val rp = restorePage; doc.post { doc.goToPage(rp) } }
        restorePage = -1
        record()
        savedIndex = hIndex
        doc.drawAllValues = !p.valuesBaked && p.fields.isNotEmpty()
        setLoading(false, null)
        toolbar.subtitle = "${p.geoms.size} page${if (p.geoms.size == 1) "" else "s"}" +
            (if (p.fields.isNotEmpty()) " · ${p.fields.size} form fields" else "")
        pagePill.visibility = View.VISIBLE
        zoomBox.visibility = View.VISIBLE
        undoBox.visibility = View.VISIBLE
        pagePill.text = "1 / ${p.geoms.size}"
        if (p.fields.isNotEmpty()) {
            formBanner.visibility = View.VISIBLE
            formBanner.postDelayed({ formBanner.visibility = View.GONE }, 5000)
        }
        if (!p.editable && !p.preliminary) {
            Toast.makeText(this, "This PDF can be viewed, but it can't be edited", Toast.LENGTH_LONG).show()
        }
        // open straight into full-screen reading (forms stay in normal mode so they're easy to fill)
        if (!recordedOpen) {
            // remember it under "Recently opened" on the home screen
            recordedOpen = true
            val src = source; val name = displayName; val uri = sourceUri; val lib = inLibrary()
            lifecycleScope.launch(Dispatchers.IO) {
                try { Recents.record(applicationContext, src, name, uri, lib) } catch (_: Throwable) {}
            }
        }
        if (!autoFullscreenDone) {
            autoFullscreenDone = true
            if (Settings.openFullscreen(this) && p.fields.isEmpty()) setFullscreen(true)
        }
    }

    /** Swaps the instant preview for the fully read document, staying on the same page. */
    private fun upgrade(full: OpenedPdf) {
        val old = pdf ?: return
        val keep = doc.currentPage()
        val sameFile = full.displayFile.absolutePath == old.displayFile.absolutePath
        val sameSizes = full.geoms.size == old.geoms.size && full.geoms.indices.all {
            kotlin.math.abs(full.geoms[it].dispW - old.geoms[it].dispW) < 0.5f && kotlin.math.abs(full.geoms[it].dispH - old.geoms[it].dispH) < 0.5f
        }
        when {
            !sameFile -> {
                val r = try { PageRenderer(full.displayFile) } catch (e: Throwable) { return }
                renderer?.close()
                renderer = r
                pdf = full
                resetSearch()
                doc.setDocument(full.geoms, r, full.widgets, items)
                doc.post { doc.goToPage(keep) }
            }
            !sameSizes -> {
                pdf = full
                doc.setDocument(full.geoms, renderer ?: return, full.widgets, items)
                doc.post { doc.goToPage(keep) }
            }
            else -> {
                pdf = full
                doc.updateForm(full.geoms, full.widgets)
            }
        }
        doc.drawAllValues = !full.valuesBaked && full.fields.isNotEmpty()
        toolbar.subtitle = "${full.geoms.size} page${if (full.geoms.size == 1) "" else "s"}" +
            if (full.fields.isNotEmpty()) " · ${full.fields.size} form fields" else ""
        if (!full.editable) Toast.makeText(this, "This PDF can be viewed, but it can't be edited", Toast.LENGTH_LONG).show()
    }

    private fun requireEditable(retry: (() -> Unit)? = null): Boolean {
        if (needsFull || preparing) { prepareEditing(retry); return false }
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
        if (betaMode) analyzeAround(page)
        pagePill.text = "${page + 1} / ${doc.pageCount}"
        if (fullscreen && pdf != null) {
            fade(pagePill, true)
            pagePill.removeCallbacks(hidePill)
            pagePill.postDelayed(hidePill, 1400)
        }
    }

    override fun onItemTapped(item: EditItem) {
        if (item is ReplacedText) {
            // text changed in Edit PDF always reopens there (switching back into Edit PDF if needed)
            if (!betaMode) enterBeta()
            if (betaMode) startTyping(item)
        } else if (item is TextItem) textDialog(item)
    }

    override fun onItemsChanged() {
        record()
    }

    override fun onItemAdded(item: EditItem) {
        record()
    }

    override fun onSelectionChanged(item: EditItem?) {}

    override fun onViewportChanged() {
        positionTyping()
        positionEditor()
        positionTextBar()
    }

    // ---------------- selecting text on the page ----------------

    private lateinit var textBar: View
    private var selText = ""
    private val textJobs = HashSet<Int>()

    override fun onNeedPageText(page: Int) {
        val r = renderer ?: return
        if (!textJobs.add(page)) return
        Toast.makeText(this, "Finding text on this page…", Toast.LENGTH_SHORT).show()
        r.renderPage(page, 2200, stillWanted = { true }) { bmp ->
            if (bmp == null) { textJobs.remove(page); doc.setPageWords(page, emptyList()); return@renderPage }
            lifecycleScope.launch {
                val words = withContext(Dispatchers.IO) {
                    try {
                        Ocr.words(this@PdfEditorActivity, bmp).map { w ->
                            PdfDocView.Word(w.text, android.graphics.RectF(
                                w.box.left.toFloat() / bmp.width, w.box.top.toFloat() / bmp.height,
                                w.box.right.toFloat() / bmp.width, w.box.bottom.toFloat() / bmp.height
                            ), w.lineEnd)
                        }
                    } catch (e: Throwable) { emptyList() }
                }
                bmp.recycle()
                textJobs.remove(page)
                doc.setPageWords(page, words)
            }
        }
    }

    override fun onTextSelected(text: String?, anchor: android.graphics.RectF?) {
        if (text == null) { textBar.visibility = View.GONE; return }
        if (text.isEmpty() || anchor == null) {
            textBar.visibility = View.GONE
            Toast.makeText(this, "No text found here", Toast.LENGTH_SHORT).show()
            return
        }
        selText = text
        textBar.visibility = View.VISIBLE
        hintOnce("sel_hint", "Drag the blue handles to select more")
        textBar.post { positionTextBar() }
    }

    private fun positionTextBar() {
        if (textBar.visibility != View.VISIBLE) return
        val r = doc.selectionScreenRect() ?: return
        val d = resources.displayMetrics.density
        val w = textBar.width.takeIf { it > 0 } ?: (260 * d).toInt()
        val h = textBar.height.takeIf { it > 0 } ?: (44 * d).toInt()
        var top = r.top - h - 14 * d
        if (top < 8 * d) top = r.bottom + 28 * d
        val left = (r.centerX() - w / 2).coerceIn(8 * d, (doc.width - w - 8 * d).coerceAtLeast(8 * d))
        val lp = textBar.layoutParams as FrameLayout.LayoutParams
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        lp.leftMargin = left.toInt()
        lp.topMargin = top.toInt().coerceIn(0, (doc.height - h).coerceAtLeast(0))
        textBar.layoutParams = lp
    }

    override fun onAnyTap() {
        if (typing != null) finishTyping(true)
        if (editingWidget != null) commitEdit()
        if (fullscreen) flashExit()
    }

    // ---------------- Edit PDF (beta) ----------------

    private var betaMode = false
    private val betaHint: TextView by lazy { findViewById(R.id.betaHint) }
    private val layouts = HashMap<Int, PageLayout>()
    private val layoutJobs = HashMap<Int, kotlinx.coroutines.Deferred<PageLayout?>>()
    private val pageBitmaps = LinkedHashMap<String, android.graphics.Bitmap>()
    /** Where a "replace picture" should go once a photo is picked. */
    private var pendingImageRect: Pair<Int, android.graphics.RectF>? = null

    private fun enterBeta() {
        if (!requireEditable { enterBeta() }) return
        if (fullscreen) setFullscreen(false)
        if (searching) closeFind()
        commitEdit()
        doc.clearTextSelection()
        doc.select(null)
        betaMode = true
        findViewById<View>(R.id.betaHeader).visibility = View.VISIBLE
        buildBetaTools()
        setTool(ActiveTool.BETA_TEXT)
        analyzeAround(doc.currentPage())
        if (!getSharedPreferences("hints", MODE_PRIVATE).getBoolean("beta_intro", false)) {
            getSharedPreferences("hints", MODE_PRIVATE).edit().putBoolean("beta_intro", true).apply()
            MaterialAlertDialogBuilder(this)
                .setTitle("Edit PDF (beta)")
                .setMessage("Change the PDF itself:\n\n" +
                    "• Tap any text to rewrite it — the old words are covered and the new ones are written in a matching style\n" +
                    "• Tap a picture to move, resize, replace or delete it\n" +
                    "• Find & replace words across the whole document\n" +
                    "• Black out private details\n\n" +
                    "Works on scanned pages too. Nothing changes until you tap Save, and Undo works for everything.\n\n" +
                    "This is a beta: the new text uses the closest available font, so check the result before sending.")
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    private fun exitBeta() {
        if (!betaMode) return
        finishTyping(true)
        betaMode = false
        doc.pickMode = false
        doc.pickFocus = null
        findViewById<View>(R.id.betaHeader).visibility = View.GONE
        buildNormalTools()
        setTool(ActiveTool.NONE)
        markActiveTool()
    }

    private fun buildBetaTools() {
        val bar = findViewById<LinearLayout>(R.id.tools)
        bar.removeAllViews()
        toolViews.clear()
        toolButton(bar, "Edit text", R.drawable.ic_edit_doc, ActiveTool.BETA_TEXT) { setTool(ActiveTool.BETA_TEXT) }
        toolButton(bar, "Pictures", R.drawable.ic_image, ActiveTool.BETA_IMAGE) { setTool(ActiveTool.BETA_IMAGE) }
        toolButton(bar, "Replace", R.drawable.ic_find_replace) { setTool(ActiveTool.BETA_TEXT); findReplaceDialog() }
        toolButton(bar, "Add text", R.drawable.ic_text) { setTool(ActiveTool.BETA_TEXT); textDialog(null) }
        toolButton(bar, "Quick fill", R.drawable.ic_idcard) { setTool(ActiveTool.BETA_TEXT); quickFillSheet() }
        toolButton(bar, "Add image", R.drawable.ic_photo) {
            setTool(ActiveTool.BETA_TEXT)
            pendingImageRect = null
            pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        toolButton(bar, "Black out", R.drawable.ic_redact, ActiveTool.BLACKOUT) { setTool(ActiveTool.BLACKOUT) }
        toolButton(bar, "Erase", R.drawable.ic_eraser, ActiveTool.WHITEOUT) { setTool(ActiveTool.WHITEOUT) }
        dockDivider(bar)
        toolButton(bar, "Sign", R.drawable.ic_sign) {
            setTool(ActiveTool.BETA_TEXT); pendingSigWidget = null; pickSignature.launch(SignaturesActivity.intent(this, pick = true))
        }
        toolButton(bar, "Draw", R.drawable.ic_draw, ActiveTool.PEN) { setTool(ActiveTool.PEN) }
        toolButton(bar, "Highlight", R.drawable.ic_highlight, ActiveTool.HIGHLIGHT) { setTool(ActiveTool.HIGHLIGHT) }
        toolButton(bar, "Pages", R.drawable.ic_pages) { openPages() }
        markActiveTool()
    }

    /** Reads the text and pictures on the page being viewed (and its neighbours) in the background. */
    private fun analyzeAround(page: Int) {
        val p = pdf ?: return
        for (i in (page - 1)..(page + 1)) {
            if (i !in p.geoms.indices) continue
            lifecycleScope.launch {
                val lay = layoutFor(i) ?: return@launch
                if (betaMode) doc.setPickOutlines(i, lay.lines.map { it.rect }, lay.images.map { it.rect })
            }
        }
    }

    /** Text lines, paragraphs and pictures of a page (PDF text, or OCR for scanned pages). */
    private suspend fun layoutFor(page: Int): PageLayout? {
        layouts[page]?.let { return it }
        val p = pdf ?: return null
        val job = layoutJobs.getOrPut(page) {
            lifecycleScope.async {
                var lay = withContext(Dispatchers.IO) {
                    searchLock.withLock {
                        try {
                            val d = searchDoc ?: com.tom_roush.pdfbox.pdmodel.PDDocument.load(p.displayFile, "", PdfOpener.memory())
                                .also { searchDoc = it }
                            BetaEdit.pdfLayout(d, page, p.geoms[page])
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            null
                        }
                    }
                }
                val chars = lay?.lines?.sumOf { l -> l.text.count { !it.isWhitespace() } } ?: 0
                if (chars < 12) {
                    // a scanned page: read the words with on-device OCR
                    val words = doc.pageWords(page) ?: ocrPage(page)
                    if (!words.isNullOrEmpty()) {
                        val ocr = BetaEdit.ocrLayout(page, words, p.geoms[page])
                        lay = PageLayout(page, ocr.lines, ocr.blocks, lay?.images ?: emptyList(), scanned = true)
                    }
                }
                lay?.also { layouts[page] = it }
            }
        }
        return try { job.await() } finally { layoutJobs.remove(page) }
    }

    /** The page as a picture (for matching colours and copying images). */
    private suspend fun pageBitmap(page: Int, width: Int): android.graphics.Bitmap? {
        val key = "$page@$width"
        pageBitmaps[key]?.let { return it }
        val r = renderer ?: return null
        val bmp = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            r.renderPage(page, width, stillWanted = { cont.isActive }) { b -> if (cont.isActive) cont.resume(b) else b?.recycle() }
        } ?: return null
        pageBitmaps[key] = bmp
        while (pageBitmaps.size > 3) {
            val first = pageBitmaps.keys.first()
            pageBitmaps.remove(first)
        }
        return bmp
    }

    private fun clearBetaCaches() {
        pageStrokes.clear()
        layouts.clear()
        layoutJobs.clear()
        pageBitmaps.clear()
    }

    override fun onPagePick(page: Int, fx: Float, fy: Float) {
        if (!betaMode) return
        val p = pdf ?: return
        lifecycleScope.launch {
            val lay = layoutFor(page)
            if (lay == null) { Toast.makeText(this@PdfEditorActivity, "Couldn't read this page", Toast.LENGTH_SHORT).show(); return@launch }
            val slopX = 10 * resources.displayMetrics.density / doc.pageScreenWidth(page)
            val slopY = slopX * p.geoms[page].dispW / p.geoms[page].dispH
            val line = lay.lineAt(fx, fy, slopX, slopY)
            val img = lay.imageAt(fx, fy)
            when {
                active == ActiveTool.BETA_IMAGE && img != null -> imageMenu(img)
                active == ActiveTool.BETA_IMAGE -> Toast.makeText(this@PdfEditorActivity, "No picture here — pictures have orange dashed boxes", Toast.LENGTH_SHORT).show()
                line != null -> startTyping(lay, line)
                img != null -> imageMenu(img)
                else -> Toast.makeText(this@PdfEditorActivity,
                    if (lay.lines.isEmpty()) "No text found on this page" else "No text here — use Add text to write something new",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---- typing straight onto the page ----

    private var typing: ReplacedText? = null
    private var typingIsNew = false
    private var typingBackup: ReplacedText? = null
    private var typingLay: PageLayout? = null
    private var typingLine: TextLine? = null
    private var typingWhole = false
    private var typingOriginal = ""
    private var typingInk = Color.BLACK
    private val typeBox: EditText by lazy {
        findViewById<EditText>(R.id.typeBox).also { box ->
            // Bold / Italic / Underline also appear in the menu shown when words are selected
            box.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
                    menu.add(0, 9101, 0, "Bold"); menu.add(0, 9102, 1, "Italic"); menu.add(0, 9103, 2, "Underline")
                    return true
                }
                override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu) = false
                override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem): Boolean {
                    val bit = when (item.itemId) { 9101 -> Style.BOLD; 9102 -> Style.ITALIC; 9103 -> Style.UNDERLINE; else -> return false }
                    toggleStyle(bit)
                    return true
                }
                override fun onDestroyActionMode(mode: android.view.ActionMode) {}
            }
        }
    }
    /** Moves the typing box so its first baseline matches the saved text's baseline. */
    private var typeBaselineShift = 0f

    /** A replacement for [lines] that looks like the original: same place, size, ink, spacing and letter width. */
    private suspend fun buildReplacement(
        page: Int, lines: List<TextLine>, text: String, font: TextFont, bold: Boolean,
        italic: Boolean = lines.count { it.italic } * 2 > lines.size,
        align: TextAlign = TextAlign.LEFT,
        /** Other text on the page (to know how far a single line may grow). */
        context: List<TextLine> = emptyList()
    ): ReplacedText {
        val g = pdf!!.geoms[page]
        val bmp = pageBitmap(page, 1400)
        val area = android.graphics.RectF(lines[0].rect).also { r -> lines.forEach { r.union(it.rect) } }
        val size = lines.map { it.sizePt }.sorted()[lines.size / 2]
        val cover = BetaEdit.coverFor(area, size, g)
        val bg = bmp?.let { BetaEdit.backgroundAround(it, cover) } ?: Color.WHITE
        val ink = bmp?.let { BetaEdit.inkInside(it, area, bg) } ?: Color.BLACK
        val gap = if (lines.size > 1) ((lines.last().baseline - lines[0].baseline) / (lines.size - 1) * g.dispH / size).coerceIn(0.9f, 3f)
        else TextRender.LINE
        // letter height: a stand-in font is resized so its capitals are as tall as the original's
        val caps = lines.map { it.capHeight }.filter { it > 0f }.sorted()
        val glyphScale = if (caps.isEmpty()) 1f else (caps[caps.size / 2] / TextRender.standInCapHeight(font)).coerceIn(0.8f, 1.2f)
        // letter width: make the stand-in font as wide as the original words
        val probe = TextItem(page, "", ink, size, bold, font, 0f, 0f, italic = italic)
        val p = TextRender.paint(probe, 1f)
        var natural = 0f; var actual = 0f
        // justified lines are stretched, so only their last line shows the natural width
        val measured = if (align == TextAlign.JUSTIFY || align == TextAlign.JUSTIFY_ALL) listOf(lines.last()) else lines
        for (l in measured.ifEmpty { lines }) {
            if (l.text.length < 3) continue
            natural += p.measureText(l.text) * glyphScale
            actual += ((l.xe.last() - l.xs.first()) * g.dispW)
        }
        val scale = if (natural > 0f) (actual / natural).coerceIn(0.75f, 1.25f) else 1f
        // first-line indent of a paragraph
        val minLeft = area.left
        val indentPt = if (lines.size > 1) ((lines[0].rect.left - lines.drop(1).minOf { it.rect.left }) * g.dispW)
            .takeIf { it > size * 0.5f } ?: 0f else 0f
        val columnPt = area.width() * g.dispW
        val wrapPt: Float
        var breakPt = 0f
        if (lines.size > 1) {
            // a paragraph keeps its column; lines break at the widest original line, so untouched lines stay put
            wrapPt = columnPt
            val sized = TextItem(page, "", ink, size, bold, font, 0f, 0f, scaleX = scale, italic = italic)
            val sp = TextRender.paint(sized, 1f)
            breakPt = lines.dropLast(1).mapIndexed { k, l -> sp.measureText(l.text.trim()) * glyphScale + if (k == 0) indentPt else 0f }
                .maxOrNull()?.let { it * 1.004f + 0.3f }?.coerceAtMost(columnPt) ?: 0f
        } else if (align == TextAlign.LEFT) {
            // a single line may grow up to the next text on the same line, or the page's text margin
            val line = lines[0]
            val nextOnLine = context.filter {
                it !== line && kotlin.math.abs(it.baseline - line.baseline) * g.dispH < size * 0.4f && it.rect.left > line.rect.right
            }.minOfOrNull { it.rect.left }
            val pageRight = context.maxOfOrNull { it.rect.right }?.takeIf { it > line.rect.right } ?: (line.rect.right + 0.05f).coerceAtMost(0.96f)
            val limit = nextOnLine?.let { it - size * 0.6f / g.dispW } ?: pageRight
            wrapPt = (kotlin.math.max(limit, line.rect.right) - minLeft) * g.dispW + size * 0.2f
        } else wrapPt = columnPt
        val origFont = lines.mapNotNull { it.fontName }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return ReplacedText(page, text, ink, size, bold, font, minLeft, lines[0].baseline - size * TextRender.ASCENT / g.dispH,
            gap, cover, bg, scale, wrapPt, text, origFont).also {
            it.italic = italic; it.align = align
            it.glyphScale = glyphScale; it.breakPt = breakPt; it.indentPt = indentPt
        }
    }

    /** Paragraph lines joined into running text (a hyphen at a line end joins the word). */
    private fun joinLines(lines: List<TextLine>): String = joinLinesStyled(lines).first

    /** Joined text plus the style of each character (null if unknown). */
    private fun joinLinesStyled(lines: List<TextLine>): Pair<String, IntArray?> {
        val sb = StringBuilder()
        val fl = ArrayList<Int>()
        val known = lines.all { it.charFlags != null && it.charFlags.size == it.text.length }
        for (l in lines) {
            val lead = l.text.length - l.text.trimStart().length
            val s = l.text.trim()
            val f = if (known) l.charFlags!!.copyOfRange(lead, lead + s.length).toList() else List(s.length) { 0 }
            if (sb.isEmpty()) { sb.append(s); fl.addAll(f) }
            else if (sb.endsWith("-")) { sb.setLength(sb.length - 1); fl.removeAt(fl.size - 1); sb.append(s); fl.addAll(f) }
            else { sb.append(' ').append(s); fl.add(fl.lastOrNull() ?: 0); fl.addAll(f) }
        }
        return Pair(sb.toString(), if (known) fl.toIntArray() else null)
    }

    /** Style marks for the characters whose style differs from the text as a whole. */
    private fun marksFrom(flags: IntArray, base: Int): List<StyleMark> {
        val out = ArrayList<StyleMark>()
        var i = 0
        while (i < flags.size) {
            if (flags[i] == base) { i++; continue }
            var j = i + 1
            while (j < flags.size && flags[j] == flags[i]) j++
            out.add(StyleMark(i, j, flags[i]))
            i = j
        }
        return out
    }

    /** Keeps a line's own mix of styles (e.g. one bold word) when it isn't all the same. */
    private fun originalMarks(item: ReplacedText, flags: IntArray?): List<StyleMark> {
        if (flags == null || flags.isEmpty() || flags.all { it == flags[0] }) return emptyList()
        return marksFrom(flags, TextRender.baseFlags(item))
    }

    /** Tap on original text: type over it right there on the page. */
    /** Typical stroke thickness of body text per page (for spotting bold lines). */
    private val pageStrokes = HashMap<Int, Float?>()

    /** Bold / italic of a line: from its font, or from how its letters look on the page. */
    private suspend fun detectStyle(lay: PageLayout, line: TextLine): Pair<Boolean, Boolean> {
        val g = pdf?.geoms?.getOrNull(lay.page) ?: return Pair(line.bold, line.italic)
        // sharp enough that the letters are at least ~24 px tall when measured
        val width = (g.dispW * 24f / line.sizePt.coerceAtLeast(1f)).toInt().coerceIn(1400, 2400)
        val bmp = pageBitmap(lay.page, width) ?: return Pair(line.bold, line.italic)
        return withContext(Dispatchers.Default) {
            fun colours(l: TextLine): Pair<Int, Int> {
                val bg = BetaEdit.backgroundAround(bmp, BetaEdit.coverFor(l.rect, l.sizePt, g))
                return Pair(bg, BetaEdit.inkInside(bmp, l.rect, bg))
            }
            val (bg, ink) = colours(line)
            val st = BetaEdit.inkStyle(bmp, line.rect, bg, ink)
            val body = if (pageStrokes.containsKey(lay.page)) pageStrokes[lay.page]
            else BetaEdit.pageStroke(bmp, lay.lines) { colours(it) }.also { pageStrokes[lay.page] = it }
            // bold = clearly thicker strokes than the page's normal text (tested: bold is 1.5–2× regular)
            val inkBold = st != null && (if (body != null) st.stroke > body * 1.3f else st.stroke > 0.095f)
            Pair(line.bold || inkBold, line.italic || st?.italic == true)
        }
    }

    private var typingBold = false
    private var typingItalic = false

    private fun startTyping(lay: PageLayout, line: TextLine) {
        commitEdit()
        lifecycleScope.launch {
            val (bold, italic) = detectStyle(lay, line)
            typingBold = bold; typingItalic = italic
            val item = buildReplacement(lay.page, listOf(line), line.text, line.font, bold, italic,
                align = BetaEdit.lineAlign(lay, line, pdf!!.geoms[lay.page]), context = lay.lines)
            item.marks = originalMarks(item, line.charFlags)
            typingMarks = item.marks.joinToString { "${it.start}-${it.end}-${it.flags}" }
            items.add(item)
            typingIsNew = true
            typingBackup = null
            typingLay = lay; typingLine = line; typingWhole = false
            typingOriginal = line.text
            typingInk = item.color
            showTyping(item)
        }
    }

    /** Tap on text you already changed: keep editing it. */
    private fun startTyping(item: ReplacedText) {
        commitEdit()
        doc.select(null)
        typingIsNew = false
        typingBackup = item.copy() as ReplacedText
        typingLay = null; typingLine = null; typingWhole = false
        typingOriginal = item.plain
        typingInk = item.color
        typingMarks = item.marks.joinToString { "${it.start}-${it.end}-${it.flags}" }
        showTyping(item)
    }

    private fun showTyping(item: ReplacedText) {
        typing = item
        doc.select(null)
        doc.pickFocus = null
        doc.typingItem = item
        findViewById<View>(R.id.betaHeader).visibility = View.GONE
        findViewById<View>(R.id.toolsScroll).visibility = View.GONE
        toolOptions.visibility = View.GONE
        findViewById<View>(R.id.typeBar).visibility = View.VISIBLE
        undoBox.visibility = View.INVISIBLE
        buildTypeTools()
        typeBox.setText(item.plain)
        applyMarks(item)
        styleTypeBox()
        typeBox.visibility = View.VISIBLE
        typeHandle.visibility = View.VISIBLE
        // small text: zoom in so it's comfortable to type
        doc.fracToScreen(item.page, item.cover)?.let { doc.zoomToFit(it, 30 * resources.displayMetrics.density) }
        positionTyping()
        typeBox.requestFocus()
        typeBox.setSelection(typeBox.text.length)
        typeBox.post {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(typeBox, InputMethodManager.SHOW_IMPLICIT)
            typeBox.postDelayed({ typingScreenRect()?.let { doc.ensureVisible(it) } }, 350)
        }
        hintOnce("beta_type_hint", "Type the new words · the box keeps the original width · tap ✓ when done")
    }

    private fun typingScreenRect(): android.graphics.RectF? {
        val item = typing ?: return null
        val r = doc.fracToScreen(item.page, item.cover) ?: return null
        r.bottom = kotlin.math.max(r.bottom, r.top + typeBox.height)
        return r
    }

    /** Makes the typing box look exactly like the text will (font, size, ink, spacing). */
    private fun styleTypeBox() {
        val item = typing ?: return
        val px = doc.pxPerPtOn(item.page)
        TextRender.applyTo(typeBox, item)
        typeBox.textScaleX = item.scaleX
        typeBox.setTextColor(item.color)
        typeBox.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, item.sizePt * px * TextRender.glyphScale(item))
        // put the first line's baseline exactly where the saved text's baseline will be
        typeBaselineShift = TextRender.ASCENT * item.sizePt * px + typeBox.paint.fontMetrics.ascent
        // paragraph indent on the first line
        val ed = typeBox.text
        ed.getSpans(0, ed.length, android.text.style.LeadingMarginSpan.Standard::class.java).forEach { ed.removeSpan(it) }
        if (item.indentPt > 0f) ed.setSpan(android.text.style.LeadingMarginSpan.Standard((item.indentPt * px).toInt(), 0),
            0, ed.length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        if (android.os.Build.VERSION.SDK_INT >= 28) typeBox.lineHeight = (item.lineGap * item.sizePt * px).toInt().coerceAtLeast(1)
        else typeBox.setLineSpacing(0f, item.lineGap / 1.17f)
        typeBox.gravity = android.view.Gravity.TOP or when (item.align) {
            TextAlign.CENTER -> android.view.Gravity.CENTER_HORIZONTAL
            TextAlign.RIGHT -> android.view.Gravity.END
            else -> android.view.Gravity.START
        }
        typeBox.justificationMode = if (item.align == TextAlign.JUSTIFY || item.align == TextAlign.JUSTIFY_ALL)
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE
    }

    private val typeHandle: View by lazy {
        findViewById<View>(R.id.typeHandle).also { h ->
            var downX = 0f; var startW = 0f; var startFx = 0f
            h.setOnTouchListener { v, e ->
                val item = typing ?: return@setOnTouchListener false
                val g = pdf?.geoms?.getOrNull(item.page) ?: return@setOnTouchListener false
                val px = doc.pxPerPtOn(item.page)
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; startW = item.wrapPt; startFx = item.fx
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val centre = item.align == TextAlign.CENTER
                        // centred text grows on both sides, everything else to the right
                        val dPt = (e.rawX - downX) / px * (if (centre) 2f else 1f)
                        val maxW = if (centre) g.dispW else (1f - startFx) * g.dispW
                        val w = (startW + dPt).coerceIn(item.sizePt * 1.5f, maxW)
                        item.wrapPt = w
                        item.breakPt = 0f
                        if (centre) item.fx = (startFx + (startW - w) / 2f / g.dispW).coerceIn(0f, 1f)
                        positionTyping()
                    }
                }
                true
            }
        }
    }

    private fun positionTyping() {
        val item = typing ?: return
        if (typeBox.visibility != View.VISIBLE) return
        val px = doc.pxPerPtOn(item.page)
        val tl = doc.fracToScreen(item.page, android.graphics.RectF(item.fx, item.fy, item.fx, item.fy)) ?: return
        val lp = typeBox.layoutParams as FrameLayout.LayoutParams
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        // the box wraps where the saved text will wrap
        val breakW = if (item.breakPt > 0f && item.align != TextAlign.CENTER && item.align != TextAlign.RIGHT) item.breakPt else item.wrapPt
        lp.width = (breakW * px + 3f).toInt().coerceAtLeast((48 * resources.displayMetrics.density).toInt())
        lp.leftMargin = tl.left.toInt()
        lp.topMargin = (tl.top + typeBaselineShift).toInt()
        typeBox.layoutParams = lp
        // width handle on the right edge, beside the first line
        val hl = typeHandle.layoutParams as FrameLayout.LayoutParams
        val hs = (30 * resources.displayMetrics.density).toInt()
        hl.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        hl.leftMargin = lp.leftMargin + lp.width - hs / 2
        hl.topMargin = (lp.topMargin + item.sizePt * px * 0.6f - hs / 2f).toInt()
        typeHandle.layoutParams = hl
        // keep the text size in step with zoom
        val want = item.sizePt * px * TextRender.glyphScale(item)
        if (kotlin.math.abs(typeBox.textSize - want) > 0.5f) styleTypeBox()
    }

    /** Style buttons shown above the keyboard while typing. */
    private fun buildTypeTools() {
        val item = typing ?: return
        val bar = findViewById<LinearLayout>(R.id.typeTools)
        bar.removeAllViews()
        fun chip(label: String, checked: Boolean? = null, onClick: (Chip) -> Unit) {
            bar.addView(Chip(this).apply {
                text = label
                if (checked != null) { isCheckable = true; isChecked = checked }
                setOnClickListener { onClick(this) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (6 * resources.displayMetrics.density).toInt() }
            })
        }
        val lay = typingLay
        val line = typingLine
        if (typingIsNew && lay != null && line != null && lay.blockOf(line).lines.size > 1) {
            chip("¶ Paragraph", typingWhole) { setTypingWhole(!typingWhole) }
        }
        chip("Aa ${item.font.label}") { c ->
            item.font = TextFont.values()[(item.font.ordinal + 1) % TextFont.values().size]
            item.origFont = null   // the user chose a style: don't switch back to the PDF's font when saving
            c.text = "Aa ${item.font.label}"; styleTypeBox()
        }
        // bold / italic keep the PDF's own font (its bold / italic version, or the same letters made bold / slanted)
        // with words selected these style just them; with nothing selected, the whole text
        chip("B  Bold") { toggleStyle(Style.BOLD) }
        chip("I  Italic") { toggleStyle(Style.ITALIC) }
        chip("U  Underline") { toggleStyle(Style.UNDERLINE) }
        chip("≡ ${item.align.label}") { c ->
            item.align = when (item.align) {
                TextAlign.LEFT -> TextAlign.CENTER
                TextAlign.CENTER -> TextAlign.RIGHT
                TextAlign.RIGHT -> TextAlign.JUSTIFY
                else -> TextAlign.LEFT
            }
            c.text = "≡ ${item.align.label}"; styleTypeBox()
        }
        chip("A−") { item.sizePt = (item.sizePt * 0.92f).coerceAtLeast(3f); styleTypeBox() }
        chip("A+") { item.sizePt = (item.sizePt * 1.08f).coerceAtMost(200f); styleTypeBox() }
        // text colour: the original ink found on the page, or black / blue / red
        val inks = intArrayOf(typingInk, Color.BLACK, Color.rgb(16, 48, 160), Color.rgb(200, 30, 45))
        val inkNames = arrayOf("Original colour", "Black", "Blue", "Red")
        fun inkLabel() = inkNames[inks.indexOf(item.color).coerceAtLeast(0)]
        fun dot(c: Int) = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(c); setStroke(2, Color.GRAY)
        }
        chip(inkLabel()) { c ->
            item.color = inks[(inks.indexOf(item.color).coerceAtLeast(0) + 1) % inks.size]
            c.text = inkLabel(); c.chipIcon = dot(item.color); styleTypeBox()
        }
        (bar.getChildAt(bar.childCount - 1) as Chip).apply { chipIcon = dot(item.color); isChipIconVisible = true }
        chip("Delete") { typeBox.setText(""); finishTyping(true) }
    }

    /** Switches between rewriting one line and the whole paragraph. */
    private fun setTypingWhole(whole: Boolean) {
        val lay = typingLay ?: return
        val line = typingLine ?: return
        val old = typing ?: return
        lifecycleScope.launch {
            val lines = if (whole) lay.blockOf(line).lines else listOf(line)
            val (text, flags) = if (whole) joinLinesStyled(lines) else Pair(line.text, line.charFlags)
            val g = pdf!!.geoms[lay.page]
            val item = buildReplacement(lay.page, lines, text, old.font, old.bold, old.italic,
                if (whole) BetaEdit.blockAlign(lines, g) else BetaEdit.lineAlign(lay, line, g), lay.lines)
            item.marks = originalMarks(item, flags)
            typingMarks = item.marks.joinToString { "${it.start}-${it.end}-${it.flags}" }
            val i = items.indexOf(old)
            if (i >= 0) items[i] = item else items.add(item)
            typingWhole = whole
            typingOriginal = text
            typing = item
            doc.typingItem = item
            typeBox.setText(text)
            applyMarks(item)
            typeBox.setSelection(typeBox.text.length)
            styleTypeBox()
            positionTyping()
            buildTypeTools()
        }
    }

    /** The marks the text started with (to tell whether anything changed). */
    private var typingMarks = ""

    /** Shows bold / italic / underline of part of the text in the typing box, in the real fonts. */
    private inner class PartStyle(val flags: Int) : android.text.style.MetricAffectingSpan() {
        override fun updateMeasureState(tp: android.text.TextPaint) = apply(tp)
        override fun updateDrawState(tp: android.text.TextPaint) = apply(tp)
        private fun apply(tp: android.text.TextPaint) {
            val item = typing ?: return
            val f = TextRender.faceFor(item, flags and Style.BOLD != 0, flags and Style.ITALIC != 0)
            tp.typeface = f.face
            tp.isFakeBoldText = f.fakeBold
            tp.textSkewX = if (f.fakeItalic) -0.22f else 0f
            tp.textSize = item.sizePt * doc.pxPerPtOn(item.page) * (if (f.fromPdf) 1f else item.glyphScale)
            tp.isUnderlineText = flags and Style.UNDERLINE != 0
        }
    }

    /** Style of every character in the typing box right now. */
    private fun currentFlags(item: ReplacedText): IntArray {
        val ed = typeBox.text
        val f = IntArray(ed.length) { TextRender.baseFlags(item) }
        for (s in ed.getSpans(0, ed.length, PartStyle::class.java)) {
            for (i in ed.getSpanStart(s).coerceAtLeast(0) until ed.getSpanEnd(s).coerceAtMost(ed.length)) f[i] = s.flags
        }
        return f
    }

    /** Puts per-character styles on the typing box (only where they differ from the whole text). */
    private fun setFlags(item: ReplacedText, flags: IntArray) {
        val ed = typeBox.text
        ed.getSpans(0, ed.length, PartStyle::class.java).forEach { ed.removeSpan(it) }
        for (m in marksFrom(flags, TextRender.baseFlags(item))) {
            // typing at the end of a styled word continues that style
            ed.setSpan(PartStyle(m.flags), m.start, m.end.coerceAtMost(ed.length), android.text.Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        typeBox.requestLayout()
        typeBox.invalidate()
    }

    private fun applyMarks(item: ReplacedText) = setFlags(item, TextRender.flagsOf(item, typeBox.text.toString()))

    /** Bold / Italic / Underline: for the selected words, or the whole text when nothing is selected. */
    private fun toggleStyle(bit: Int) {
        val item = typing ?: return
        val len = typeBox.text.length
        val s = kotlin.math.min(typeBox.selectionStart, typeBox.selectionEnd).coerceIn(0, len)
        val e = kotlin.math.max(typeBox.selectionStart, typeBox.selectionEnd).coerceIn(0, len)
        val flags = currentFlags(item)
        val range = if (s < e) s until e else 0 until len
        val on = !range.all { flags[it] and bit != 0 }
        for (i in range) flags[i] = if (on) flags[i] or bit else flags[i] and bit.inv()
        if (s >= e) {
            // whole text: bold / italic become the text's own style
            if (bit == Style.BOLD) item.bold = on
            if (bit == Style.ITALIC) item.italic = on
        }
        setFlags(item, flags)
        styleTypeBox()
        if (s < e) typeBox.setSelection(s, e)
        else hintOnce("beta_part_style", "Tip: select words first to make just them bold, italic or underlined")
    }

    /** Ends typing: keeps the new words ([apply]) or puts things back. */
    private fun finishTyping(apply: Boolean) {
        val item = typing ?: return
        typing = null
        doc.typingItem = null
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(typeBox.windowToken, 0)
        typeBox.clearFocus()
        typeBox.visibility = View.GONE
        typeHandle.visibility = View.GONE
        findViewById<View>(R.id.typeBar).visibility = View.GONE
        findViewById<View>(R.id.toolsScroll).visibility = View.VISIBLE
        if (betaMode) findViewById<View>(R.id.betaHeader).visibility = View.VISIBLE
        undoBox.visibility = View.VISIBLE
        setTool(active)
        val raw = typeBox.text.toString().trimEnd()
        val newMarks = marksFrom(currentFlags(item).copyOf(raw.length), TextRender.baseFlags(item))
        val backup = typingBackup
        when {
            !apply -> {
                if (typingIsNew) items.remove(item)
                else if (backup != null) { val i = items.indexOf(item); if (i >= 0) items[i] = backup }
            }
            raw.isEmpty() -> {
                // delete: just the cover remains
                val i = items.indexOf(item)
                val c = item.cover
                val cover = ShapeItem(item.page, ShapeKind.FILL, c.left, c.top, c.width(), c.height(), item.coverColor, 1f)
                if (i >= 0) items[i] = cover else items.add(cover)
                record()
            }
            typingIsNew && raw == typingOriginal && backup == null && item.font == typingLine?.font &&
                item.bold == typingBold && item.italic == typingItalic && item.color == typingInk && !typingWhole &&
                newMarks.joinToString { "${it.start}-${it.end}-${it.flags}" } == typingMarks -> items.remove(item)
            else -> {
                item.marks = newMarks
                item.plain = raw
                item.text = TextRender.wrapItem(item, raw)
                record()
            }
        }
        typingBackup = null
        doc.refresh()
    }

    /** Move / resize, replace or delete a picture that is part of the PDF. */
    private fun imageMenu(img: ImageBox) {
        val p = pdf ?: return
        doc.pickFocus = Pair(img.page, img.rect)
        val opts = arrayOf<CharSequence>("Move or resize", "Replace with another picture", "Delete picture")
        MaterialAlertDialogBuilder(this)
            .setTitle("Picture")
            .setItems(opts) { _, which ->
                lifecycleScope.launch {
                    val g = p.geoms[img.page]
                    val bmp = pageBitmap(img.page, 2000)
                    val r = android.graphics.RectF(img.rect)
                    val pad = 1.5f / g.dispW
                    val cover = android.graphics.RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
                    val bg = bmp?.let { BetaEdit.backgroundAround(it, cover) } ?: Color.WHITE
                    val coverItem = ShapeItem(img.page, ShapeKind.FILL, cover.left, cover.top, cover.width(), cover.height(), bg, 1f)
                    when (which) {
                        0 -> {
                            val piece = bmp?.let { BetaEdit.crop(it, r) }
                            if (piece == null) { Toast.makeText(this@PdfEditorActivity, "Couldn't copy this picture", Toast.LENGTH_SHORT).show(); return@launch }
                            val file = withContext(Dispatchers.IO) {
                                Storage.newWorkFile(this@PdfEditorActivity, "png").also { ImageUtils.savePng(piece, it) }
                            }
                            items.add(coverItem)
                            val item = SignatureItem(img.page, file, r.left, r.top, r.width(), r.height())
                            items.add(item)
                            record()
                            doc.pickFocus = null
                            doc.select(item)
                            hintOnce("beta_img_hint", "Drag to move · pinch or the blue dot to resize · rotate with the round handle")
                        }
                        1 -> {
                            items.add(coverItem)
                            record()
                            doc.pickFocus = null
                            pendingImageRect = Pair(img.page, r)
                            pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        else -> {
                            items.add(coverItem)
                            record()
                            doc.pickFocus = null
                            doc.refresh()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ -> doc.pickFocus = null }
            .setOnCancelListener { doc.pickFocus = null }
            .show()
    }

    /** A new picture fitted into the place of an old one. */
    private fun placeImageIn(file: File, page: Int, r: android.graphics.RectF) {
        val size = doc.pageSizePt(page)
        val aspect = sigAspect(file) * size.x / size.y          // height / width in page fractions
        var fw = r.width(); var fh = fw * aspect
        if (fh > r.height()) { fh = r.height(); fw = fh / aspect }
        val item = SignatureItem(page, file, r.centerX() - fw / 2, r.centerY() - fh / 2, fw, fh)
        items.add(item)
        record()
        doc.select(item)
    }

    /** Replaces a word or phrase everywhere in the document. */
    private fun findReplaceDialog() {
        val p = pdf ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val find = EditText(this).apply { hint = "Find"; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val repl = EditText(this).apply { hint = "Replace with (empty = delete)"; maxLines = 1; inputType = android.text.InputType.TYPE_CLASS_TEXT }
        val matchCase = android.widget.CheckBox(this).apply { text = "Match case" }
        box.addView(find); box.addView(repl); box.addView(matchCase)
        MaterialAlertDialogBuilder(this)
            .setTitle("Find & replace")
            .setView(padded(box))
            .setPositiveButton("Replace all") { _, _ ->
                val q = find.text.toString()
                if (q.isBlank()) return@setPositiveButton
                runReplaceAll(p, q, repl.text.toString(), matchCase.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
        find.requestFocus()
    }

    private fun runReplaceAll(p: OpenedPdf, query: String, replacement: String, matchCase: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null)
        val text = view.findViewById<TextView>(R.id.progressText)
        val bar = view.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressBar)
        var cancelled = false
        val dlg = MaterialAlertDialogBuilder(this).setTitle("Replacing…").setView(view)
            .setNegativeButton("Stop") { _, _ -> cancelled = true }.setCancelable(false).show()
        lifecycleScope.launch {
            val n = p.geoms.size
            bar.isIndeterminate = false
            bar.max = n
            var count = 0
            val pages = HashSet<Int>()
            val added = ArrayList<EditItem>()
            for (page in 0 until n) {
                if (cancelled) break
                text.text = "Page ${page + 1} of $n · $count found"
                bar.setProgressCompat(page, true)
                val lay = layoutFor(page) ?: continue
                val g = p.geoms[page]
                var bmp: android.graphics.Bitmap? = null
                for (line in lay.lines) {
                    var from = 0
                    while (true) {
                        val i = line.text.indexOf(query, from, ignoreCase = !matchCase)
                        if (i < 0) break
                        val j = i + query.length - 1
                        from = i + query.length
                        if (bmp == null) bmp = pageBitmap(page, 1400)
                        val area = android.graphics.RectF(line.xs[i], line.top, line.xe[j], line.bottom)
                        val cover = BetaEdit.coverFor(area, line.sizePt, g)
                        val bg = bmp?.let { BetaEdit.backgroundAround(it, cover) } ?: Color.WHITE
                        val ink = bmp?.let { BetaEdit.inkInside(it, area, bg) } ?: Color.BLACK
                        added.add(
                            if (replacement.isEmpty()) ShapeItem(page, ShapeKind.FILL, cover.left, cover.top, cover.width(), cover.height(), bg, 1f)
                            else ReplacedText(page, replacement, ink, line.sizePt, line.bold, line.font, area.left,
                                line.baseline - line.sizePt * TextRender.ASCENT / g.dispH, TextRender.LINE, cover, bg,
                                origFont = line.fontName).also { it.italic = line.italic }
                        )
                        count++
                        pages.add(page)
                    }
                }
            }
            try { dlg.dismiss() } catch (_: Throwable) {}
            if (added.isNotEmpty()) {
                items.addAll(added)
                record()
                doc.refresh()
            }
            val msg = when (count) {
                0 -> "\"$query\" wasn't found"
                1 -> "Replaced 1 time"
                else -> "Replaced $count times on ${pages.size} page${if (pages.size == 1) "" else "s"}"
            }
            MaterialAlertDialogBuilder(this@PdfEditorActivity).setTitle("Find & replace").setMessage(
                msg + if (count > 0) "\n\nCheck the pages, then tap Save. Undo reverses it." else ""
            ).setPositiveButton("OK", null).show()
        }
    }

    // ---------------- translate ----------------

    /** Translates the page being read (its own text, or text read from a scan). */
    private fun translatePage() {
        val page = doc.currentPage()
        TranslateSheet.show(this, "Page ${page + 1}") { readPageText(page) }
    }

    /** The words on a page in reading order: the PDF's text, or on-device OCR for scanned pages. */
    private suspend fun readPageText(page: Int): String {
        val p = pdf ?: return ""
        val text = withContext(Dispatchers.IO) {
            searchLock.withLock {
                try {
                    val d = searchDoc ?: com.tom_roush.pdfbox.pdmodel.PDDocument.load(p.displayFile, "", PdfOpener.memory())
                        .also { searchDoc = it }
                    com.tom_roush.pdfbox.text.PDFTextStripper().apply {
                        sortByPosition = true; startPage = page + 1; endPage = page + 1
                    }.getText(d)
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    ""
                }
            }
        }
        if (text.count { !it.isWhitespace() } >= 12) return text
        // a scanned page: read it with OCR
        val bmp = pageBitmap(page, 1800) ?: return text
        return withContext(Dispatchers.IO) {
            try { Ocr.recognize(this@PdfEditorActivity, bmp).first } catch (_: Throwable) { text }
        }
    }

    // ---------------- find in document ----------------

    private lateinit var findBar: View
    private lateinit var findText: EditText
    private lateinit var findCount: TextView
    private lateinit var findProgress: View
    private var searching = false
    private var findJob: kotlinx.coroutines.Job? = null
    private val pageTexts = HashMap<Int, PageText>()
    private var searchDoc: com.tom_roush.pdfbox.pdmodel.PDDocument? = null
    private val searchLock = kotlinx.coroutines.sync.Mutex()
    private val hits = ArrayList<SearchHit>()
    private var currentHit: SearchHit? = null
    private var lastQuery = ""
    private var findDone = false
    private val findDebounce = Runnable { runSearch(findText.text.toString()) }

    private fun setupFind() {
        findBar = findViewById(R.id.findBar)
        findText = findViewById(R.id.findText)
        findCount = findViewById(R.id.findCount)
        findProgress = findViewById(R.id.findProgress)
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
        findViewById<View>(R.id.findNext).setOnClickListener { stepHit(1) }
        findViewById<View>(R.id.findPrev).setOnClickListener { stepHit(-1) }
        findText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!searching) return
                findText.removeCallbacks(findDebounce)
                findText.postDelayed(findDebounce, 450)
            }
        })
        findText.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                findText.removeCallbacks(findDebounce)
                // Enter on the same words jumps to the next match
                if (PdfSearch.normalise(findText.text.toString()) == lastQuery && hits.isNotEmpty()) stepHit(1)
                else runSearch(findText.text.toString())
                true
            } else false
        }
    }

    private fun keyboard(show: Boolean) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        if (show) imm.showSoftInput(findText, 0) else imm.hideSoftInputFromWindow(findText.windowToken, 0)
    }

    private fun openFind() {
        if (pdf == null || searching) return
        if (betaMode) exitBeta()
        if (fullscreen) setFullscreen(false)
        commitEdit()
        setTool(ActiveTool.NONE)
        doc.clearTextSelection()
        searching = true
        topBar.visibility = View.INVISIBLE
        dock.visibility = View.INVISIBLE
        undoBox.visibility = View.INVISIBLE
        findBar.visibility = View.VISIBLE
        findBar.alpha = 0f
        findBar.animate().alpha(1f).setDuration(160).start()
        findText.requestFocus()
        findText.post { keyboard(true) }
        updateChrome()
        // searching again opens with the previous words still highlighted
        if (hits.isNotEmpty()) doc.setSearchHits(hits, currentHit)
    }

    private fun closeFind() {
        if (!searching) return
        searching = false
        findText.removeCallbacks(findDebounce)
        keyboard(false)
        findText.clearFocus()
        findJob?.cancel()
        findProgress.visibility = View.GONE
        doc.setSearchHits(emptyList(), null)
        findBar.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        dock.visibility = View.VISIBLE
        undoBox.visibility = View.VISIBLE
        updateChrome()
    }

    private fun closeSearchDoc() {
        try { searchDoc?.close() } catch (_: Throwable) {}
        searchDoc = null
    }

    /** A new or re-saved file: cached page text no longer applies. */
    private fun resetSearch() {
        findJob?.cancel()
        pageTexts.clear()
        clearBetaCaches()
        hits.clear()
        currentHit = null
        lastQuery = ""
        val old = searchDoc
        searchDoc = null
        if (old != null) lifecycleScope.launch(Dispatchers.IO) { searchLock.withLock { try { old.close() } catch (_: Throwable) {} } }
        if (::findBar.isInitialized && searching) closeFind()
    }

    private fun runSearch(raw: String) {
        val q = PdfSearch.normalise(raw)
        if (q == lastQuery && (findJob?.isActive == true || findDone)) return
        val p = pdf ?: return
        lastQuery = q
        findJob?.cancel()
        hits.clear()
        currentHit = null
        findDone = false
        doc.setSearchHits(emptyList(), null)
        if (q.isEmpty()) { findCount.text = ""; findProgress.visibility = View.GONE; return }
        val n = p.geoms.size
        val start = doc.currentPage().coerceIn(0, n - 1)
        findProgress.visibility = View.VISIBLE
        findCount.text = ""
        findJob = lifecycleScope.launch {
            // start on the page being read, then wrap around
            for (k in 0 until n) {
                val page = (start + k) % n
                val pt = pageText(p, page)
                val found = PdfSearch.find(page, pt, q)
                if (found.isNotEmpty()) {
                    hits.addAll(found)
                    hits.sortBy { it.page }
                    if (currentHit == null) { currentHit = found.first(); doc.reveal(found.first()) }
                    doc.setSearchHits(hits, currentHit)
                }
                showCount(stillSearching = true)
            }
            findDone = true
            findProgress.visibility = View.GONE
            showCount(stillSearching = false)
        }
    }

    private fun showCount(stillSearching: Boolean) {
        val i = hits.indexOf(currentHit)
        findCount.text = when {
            hits.isEmpty() && stillSearching -> ""
            hits.isEmpty() -> "None"
            else -> "${i + 1}/${hits.size}" + if (stillSearching) "+" else ""
        }
    }

    private fun stepHit(dir: Int) {
        if (hits.isEmpty()) return
        val i = hits.indexOf(currentHit)
        val next = hits[((if (i < 0) 0 else i + dir) % hits.size + hits.size) % hits.size]
        currentHit = next
        doc.setSearchHits(hits, next)
        doc.reveal(next)
        showCount(stillSearching = findJob?.isActive == true)
    }

    /** Text of a page for searching: the PDF's own text, or recognised text for scanned pages. */
    private suspend fun pageText(p: OpenedPdf, page: Int): PageText {
        pageTexts[page]?.let { return it }
        var t = withContext(Dispatchers.IO) {
            searchLock.withLock {
                try {
                    val d = searchDoc ?: com.tom_roush.pdfbox.pdmodel.PDDocument.load(p.displayFile, "", PdfOpener.memory())
                        .also { searchDoc = it }
                    PdfSearch.pdfText(d, page, p.geoms[page])
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    PageText("", emptyList())
                }
            }
        }
        // hardly any real text: it's a scanned page, so read it with on-device OCR
        if (t.text.count { !it.isWhitespace() } < 12) {
            val words = doc.pageWords(page) ?: ocrPage(page)
            if (words != null) {
                val ocr = PdfSearch.ocrText(words)
                if (ocr.text.length > t.text.length) t = ocr
            }
        }
        pageTexts[page] = t
        return t
    }

    private suspend fun ocrPage(page: Int): List<PdfDocView.Word>? {
        val r = renderer ?: return null
        val bmp = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            r.renderPage(page, 1800, stillWanted = { cont.isActive }) { b ->
                if (cont.isActive) cont.resume(b) else b?.recycle()
            }
        } ?: return null
        val words = withContext(Dispatchers.IO) {
            try { ocrWords(bmp) } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; emptyList() }
            finally { bmp.recycle() }
        }
        doc.setPageWords(page, words)
        return words
    }

    private fun ocrWords(bmp: android.graphics.Bitmap) = Ocr.words(this, bmp).map { w ->
        PdfDocView.Word(w.text, android.graphics.RectF(
            w.box.left.toFloat() / bmp.width, w.box.top.toFloat() / bmp.height,
            w.box.right.toFloat() / bmp.width, w.box.bottom.toFloat() / bmp.height
        ), w.lineEnd)
    }

    // ---------------- floating bars + full screen ----------------

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Tells the page view how much the bars cover, and floats the side controls above the dock. */
    private fun updateChrome() {
        val h = editorRoot.height
        if (h == 0) return
        val dockCover = (h - dock.top).toFloat()
        when {
            fullscreen -> doc.setInsets(dp(4f), dp(8f))
            searching -> doc.setInsets(topBar.bottom + dp(6f), dp(8f))
            else -> doc.setInsets(topBar.bottom + dp(6f), dockCover + dp(6f))
        }
        val bottom = (if (fullscreen || searching) dp(20f) else dockCover + dp(12f)).toInt()
        for (v in listOf(zoomBox, undoBox, pagePill)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; v.layoutParams = lp }
        }
    }

    private fun fade(v: View, show: Boolean) {
        v.animate().cancel()
        if (show) {
            if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE }
            v.animate().alpha(1f).setDuration(180).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(180).withEndAction { v.visibility = View.INVISIBLE }.start()
        }
    }

    /** True for the 3-button (or 2-button) navigation bar; false for gesture navigation. */
    private fun usesNavButtons(): Boolean {
        val ins = window.decorView.rootWindowInsets ?: return true
        val t = when {
            android.os.Build.VERSION.SDK_INT >= 30 ->
                ins.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.tappableElement())
            android.os.Build.VERSION.SDK_INT >= 29 -> ins.tappableElementInsets
            else -> return true
        }
        // the gesture handle isn't tappable; a button bar is (bottom, or the side in landscape)
        return t.bottom > 0 || t.left > 0 || t.right > 0
    }

    private fun flashExit() {
        fade(btnExitFull, true)
        btnExitFull.removeCallbacks(hideExit)
        btnExitFull.postDelayed(hideExit, 2600)
    }

    /** Full-screen reading: hides the bars, controls and the phone's status/navigation bars. */
    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        if (on && betaMode) exitBeta()
        if (on) { commitEdit(); setTool(ActiveTool.NONE); doc.clearTextSelection() }
        fullscreen = on
        val ctl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val all = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        if (on) {
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // with Back / Home / Recents buttons, keep them on screen and hide only the status bar
            ctl.hide(if (usesNavButtons()) androidx.core.view.WindowInsetsCompat.Type.statusBars() else all)
        } else ctl.show(all)

        val slideUp = -(topBar.bottom + dp(12f))
        val slideDown = (editorRoot.height - dock.top) + dp(12f)
        for ((v, dy) in listOf(topBar to slideUp, dock to slideDown)) {
            v.animate().cancel()
            if (on) {
                v.animate().translationY(dy).alpha(0f).setDuration(240)
                    .withEndAction { v.visibility = View.INVISIBLE }.start()
            } else {
                v.visibility = View.VISIBLE
                v.animate().translationY(0f).alpha(1f).setDuration(240).start()
            }
        }
        val ready = pdf != null
        fade(zoomBox, !on && ready)
        fade(undoBox, !on && ready)
        pagePill.removeCallbacks(hidePill)
        fade(pagePill, !on && ready)
        if (on) {
            flashExit()
            hintOnce("fs_hint2", "Full screen — tap the page, then ⛶ to show the editing tools")
        } else {
            btnExitFull.removeCallbacks(hideExit)
            btnExitFull.animate().cancel()
            btnExitFull.visibility = View.GONE
        }
        updateChrome()
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
        finishTyping(true)
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
        val italic = view.findViewById<MaterialSwitch>(R.id.italic)
        italic.isChecked = existing?.italic ?: prefs.getBoolean("italic", false)

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
                // an untouched slider keeps the exact size (detected sizes aren't whole numbers)
                val sizePt = if (existing != null && slider.value == size.coerceIn(6f, 72f).toInt().toFloat()) existing.sizePt else slider.value
                if (existing !is ReplacedText) prefs.edit().putInt("color", color).putFloat("size", sizePt)
                    .putBoolean("bold", bold.isChecked).putBoolean("italic", italic.isChecked).putString("font", font.name).apply()
                if (existing != null) {
                    existing.text = text
                    existing.color = color
                    existing.sizePt = sizePt
                    existing.bold = bold.isChecked
                    existing.italic = italic.isChecked
                    existing.font = font
                    record()
                    doc.refresh()
                } else {
                    addText(text, color, sizePt, bold.isChecked, font, italic = italic.isChecked)
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) {
            builder.setNeutralButton(if (existing is ReplacedText) "Restore original" else "Delete") { _, _ ->
                items.remove(existing)
                doc.select(null)
                record()
            }
        }
        builder.show()
        input.requestFocus()
    }

    private fun addText(text: String, color: Int, sizePt: Float, bold: Boolean, font: TextFont, boxed: Boolean = false, italic: Boolean = false) {
        val page = doc.currentPage()
        val size = doc.pageSizePt(page)
        val c = doc.visibleCenterOn(page)
        val item = TextItem(page, text, color, sizePt, bold, font, 0f, 0f, boxed, italic = italic)
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
        val canUndo = hIndex > 0
        val canRedo = hIndex < history.size - 1
        findViewById<View>(R.id.btnUndo)?.let { it.isEnabled = canUndo; it.alpha = if (canUndo) 1f else 0.35f }
        findViewById<View>(R.id.btnRedo)?.let { it.isEnabled = canRedo; it.alpha = if (canRedo) 1f else 0.35f }
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
        val always = android.view.MenuItem.SHOW_AS_ACTION_ALWAYS
        m.add(0, 12, 0, "Find").setIcon(R.drawable.ic_search).setShowAsAction(always)
        m.add(0, 10, 0, "Compress").setIcon(R.drawable.ic_compress).setShowAsAction(always)
        m.add(0, 3, 1, "Save").setIcon(R.drawable.bg_save_fab).setShowAsAction(always)
        m.add(0, 11, 2, "Full screen").setIcon(R.drawable.ic_fullscreen)
        m.add(0, 5, 3, "Share…")
        m.add(0, 8, 4, "Print")
        m.add(0, 9, 5, "Night reading").apply { isCheckable = true; isChecked = getSharedPreferences("view", MODE_PRIVATE).getBoolean("night", false) }
        m.add(0, 4, 6, "Go to page")
        m.add(0, 13, 7, "Translate page")
        m.add(0, 6, 8, "More PDF tools")
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                11 -> setFullscreen(true)
                12 -> openFind()
                3 -> editing { quickSave() }
                10 -> compressCurrent()
                4 -> pagesSheet()
                5 -> shareCurrent()
                8 -> printCurrent()
                9 -> {
                    item.isChecked = !item.isChecked
                    getSharedPreferences("view", MODE_PRIVATE).edit().putBoolean("night", item.isChecked).apply()
                    doc.nightMode = item.isChecked
                }
                6 -> startActivity(Intent(this, ToolsActivity::class.java))
                13 -> translatePage()
            }
            true
        }
        updateUndoButtons()
    }

    private fun shareCurrent() {
        commitEdit()
        if (dirty) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Share with your changes?")
                .setMessage("Your edits are saved first, then shared.")
                .setPositiveButton("Save & share") { _, _ -> quickSave(thenShare = true) }
                .setNegativeButton("Share original") { _, _ -> Ui.share(this, source) }
                .show()
        } else Ui.share(this, source)
    }

    private fun printCurrent() {
        val p = pdf ?: return
        if (dirty) Toast.makeText(this, "Printing the last saved version — save to include your edits", Toast.LENGTH_LONG).show()
        Ui.print(this, p.displayFile, displayName)
    }

    private lateinit var toolOptions: View
    private lateinit var toolTitle: TextView
    private lateinit var shapeKinds: ChipGroup
    private lateinit var toolColors: LinearLayout
    private lateinit var toolWidth: Slider
    private lateinit var widthLabel: TextView
    private var toolColor = Color.rgb(16, 48, 160)
    private var shapeKind = ShapeKind.RECT
    private enum class ActiveTool { NONE, PEN, HIGHLIGHT, SHAPE, WHITEOUT, BETA_TEXT, BETA_IMAGE, BLACKOUT }
    private var active = ActiveTool.NONE

    private val toolViews = HashMap<ActiveTool, View>()

    private fun toolButton(parent: LinearLayout, label: String, icon: Int, tool: ActiveTool? = null, beta: Boolean = false, onClick: () -> Unit) {
        val b = LayoutInflater.from(this).inflate(R.layout.view_dock_tool, parent, false)
        if (beta) b.findViewById<View>(R.id.toolBadge).visibility = View.VISIBLE
        b.findViewById<android.widget.ImageView>(R.id.toolIcon).setImageResource(icon)
        b.findViewById<TextView>(R.id.toolLabel).text = label
        b.contentDescription = label
        b.setOnClickListener {
            // tapping the active drawing tool again switches it off
            if (tool != null && tool == active) setTool(ActiveTool.NONE) else onClick()
        }
        parent.addView(b)
        if (tool != null) toolViews[tool] = b
    }

    private fun dockDivider(parent: LinearLayout) {
        val d = resources.displayMetrics.density
        parent.addView(View(this).apply {
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@PdfEditorActivity, R.color.glass_stroke))
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt().coerceAtLeast(1), (36 * d).toInt()).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
            }
        })
    }

    /** Lights up the dock tile of the active drawing tool. */
    private fun markActiveTool() {
        val accent = androidx.core.content.ContextCompat.getColor(this, Themes.accent(this).primary)
        val idleIcon = androidx.core.content.ContextCompat.getColor(this, R.color.dock_icon)
        val idleText = androidx.core.content.ContextCompat.getColor(this, R.color.md_on_surface_variant)
        for ((t, v) in toolViews) {
            val on = t == active
            v.isSelected = on
            v.findViewById<android.widget.ImageView>(R.id.toolIcon).imageTintList =
                android.content.res.ColorStateList.valueOf(if (on) Color.WHITE else idleIcon)
            v.findViewById<TextView>(R.id.toolLabel).apply {
                setTextColor(if (on) accent else idleText)
                setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
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

        findViewById<View>(R.id.betaDone).setOnClickListener { exitBeta() }
        findViewById<View>(R.id.typeDone).setOnClickListener { finishTyping(true) }
        findViewById<View>(R.id.typeCancel).setOnClickListener { finishTyping(false) }
        findViewById<View>(R.id.betaSave).setOnClickListener { editing { quickSave() } }
        buildNormalTools()
    }

    private fun buildNormalTools() {
        val bar = findViewById<LinearLayout>(R.id.tools)
        bar.removeAllViews()
        toolViews.clear()
        toolButton(bar, "Sign", R.drawable.ic_sign) {
            editing { setTool(ActiveTool.NONE); pendingSigWidget = null; pickSignature.launch(SignaturesActivity.intent(this, pick = true)) }
        }
        toolButton(bar, "Edit PDF", R.drawable.ic_edit_doc, beta = true) { enterBeta() }
        toolButton(bar, "Quick fill", R.drawable.ic_idcard) { editing { setTool(ActiveTool.NONE); quickFillSheet() } }
        toolButton(bar, "Text", R.drawable.ic_text) { editing { setTool(ActiveTool.NONE); textDialog(null) } }
        toolButton(bar, "Draw", R.drawable.ic_draw, ActiveTool.PEN) { editing { setTool(ActiveTool.PEN) } }
        toolButton(bar, "Highlight", R.drawable.ic_highlight, ActiveTool.HIGHLIGHT) { editing { setTool(ActiveTool.HIGHLIGHT) } }
        toolButton(bar, "Shapes", R.drawable.ic_shapes, ActiveTool.SHAPE) { editing { setTool(ActiveTool.SHAPE) } }
        toolButton(bar, "Whiteout", R.drawable.ic_eraser, ActiveTool.WHITEOUT) { editing { setTool(ActiveTool.WHITEOUT) } }
        toolButton(bar, "Image", R.drawable.ic_image) {
            editing { setTool(ActiveTool.NONE); pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        }
        toolButton(bar, "Stamp", R.drawable.ic_stamp) { editing { setTool(ActiveTool.NONE); stampDialog() } }
        dockDivider(bar)
        toolButton(bar, "Pages", R.drawable.ic_pages) { setTool(ActiveTool.NONE); openPages() }
        toolButton(bar, "Get text", R.drawable.ic_ocr) { setTool(ActiveTool.NONE); extractText() }
        toolButton(bar, "Translate", R.drawable.ic_web) { setTool(ActiveTool.NONE); translatePage() }
        toolButton(bar, "Go to", R.drawable.ic_pdf) { pagesSheet() }
        toolButton(bar, "Full screen", R.drawable.ic_fullscreen) { setFullscreen(true) }
        toolButton(bar, "Share", R.drawable.ic_share) { setTool(ActiveTool.NONE); shareCurrent() }
        toolButton(bar, "Print", R.drawable.ic_print) { setTool(ActiveTool.NONE); printCurrent() }
        markActiveTool()
    }

    private fun setTool(tool: ActiveTool) {
        commitEdit()
        // in the beta editor "no tool" means tapping text to edit it
        val t = if (betaMode && tool == ActiveTool.NONE) ActiveTool.BETA_TEXT else tool
        active = t
        val prefs = getSharedPreferences("tools", MODE_PRIVATE)
        val picking = t == ActiveTool.BETA_TEXT || t == ActiveTool.BETA_IMAGE
        doc.pickMode = betaMode
        if (!picking) doc.pickFocus = null
        if (betaMode) betaHint.text = when (t) {
            ActiveTool.BETA_IMAGE -> "Tap a picture to move, resize, replace or delete it"
            ActiveTool.BLACKOUT -> "Drag over private details · on Save they're permanently removed (that page is saved as an image)"
            ActiveTool.WHITEOUT -> "Drag to erase an area"
            else -> "Tap any text to change it · dashed boxes show what can be edited"
        }
        toolOptions.visibility = if (t == ActiveTool.NONE || picking) View.GONE else View.VISIBLE
        findViewById<View>(R.id.shapeScroll).visibility = if (t == ActiveTool.SHAPE) View.VISIBLE else View.GONE
        val showStyle = t != ActiveTool.WHITEOUT && t != ActiveTool.BLACKOUT
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
            ActiveTool.BLACKOUT -> toolTitle.text = "Black out — permanently removed when you save"
            ActiveTool.NONE, ActiveTool.BETA_TEXT, ActiveTool.BETA_IMAGE -> {}
        }
        widthLabel.text = "Size ${"%.1f".format(toolWidth.value)}"
        applyTool()
        markActiveTool()
    }

    private fun applyTool() {
        val w = toolWidth.value
        val prefs = getSharedPreferences("tools", MODE_PRIVATE).edit()
        doc.tool = when (active) {
            ActiveTool.NONE, ActiveTool.BETA_TEXT, ActiveTool.BETA_IMAGE -> PdfDocView.Tool.None
            ActiveTool.BLACKOUT -> PdfDocView.Tool.Shape(ShapeKind.REDACT, Color.BLACK, 1f)
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

    /** Fill & sign: tap a saved detail, today's date or a mark to place it on the page. */
    private fun quickFillSheet() {
        val d = resources.displayMetrics.density
        val details = MyDetails.load(this)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (20 * d).toInt()) }
        fun title(s: String) = TextView(this).apply {
            text = s; textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(androidx.core.content.ContextCompat.getColor(this@PdfEditorActivity, R.color.md_on_surface_variant))
            setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
        }
        fun group(values: List<Pair<String, String>>): View {
            val g = com.google.android.material.chip.ChipGroup(this)
            for ((label, value) in values) g.addView(Chip(this).apply {
                text = label
                setOnClickListener { sheet.dismiss(); placeDetail(value) }
            })
            return g
        }
        root.addView(TextView(this).apply { text = "Quick fill"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        root.addView(TextView(this).apply {
            text = "Tap to place it on the page, then drag it into position."
            textSize = 13f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@PdfEditorActivity, R.color.md_on_surface_variant))
        })
        val mine = MyDetails.FIELDS.mapNotNull { (k, label) -> details[k]?.takeIf { it.isNotBlank() }?.let { v -> Pair(if (k == "initials") v else v.lines().first().take(28), v) } }
        root.addView(title("MY DETAILS"))
        if (mine.isEmpty()) root.addView(TextView(this).apply { text = "Nothing saved yet — add your name, initials, phone… once and use them on every PDF."; textSize = 13f })
        else root.addView(group(mine))
        root.addView(MaterialButton(this).apply {
            text = if (mine.isEmpty()) "Add my details" else "Edit my details"
            setIconResource(R.drawable.ic_draw)
            setOnClickListener { sheet.dismiss(); editMyDetails() }
        })
        val now = Date()
        root.addView(title("DATE"))
        root.addView(group(listOf("dd.MM.yyyy", "dd/MM/yyyy", "dd MMM yyyy", "MMMM d, yyyy").map { f ->
            SimpleDateFormat(f, Locale.getDefault()).format(now).let { Pair(it, it) }
        }))
        root.addView(title("MARKS"))
        root.addView(group(listOf("✓" to "✓", "✗" to "✗", "●" to "●", "N/A" to "N/A")))
        sheet.setContentView(android.widget.ScrollView(this).apply { addView(root) })
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Places a detail as text in the user's usual text style. */
    private fun placeDetail(value: String) {
        val prefs = getSharedPreferences("text", MODE_PRIVATE)
        val font = runCatching { TextFont.valueOf(prefs.getString("font", "SANS")!!) }.getOrDefault(TextFont.SANS)
        val size = if (value.length <= 2) prefs.getFloat("size", 14f) * 1.3f else prefs.getFloat("size", 14f)
        addText(value, prefs.getInt("color", COLORS[1]), size, prefs.getBoolean("bold", false), font)
    }

    private fun editMyDetails() {
        val d = resources.displayMetrics.density
        val current = MyDetails.load(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inputs = LinkedHashMap<String, EditText>()
        for ((k, label) in MyDetails.FIELDS) {
            box.addView(TextView(this).apply {
                text = label; textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@PdfEditorActivity, R.color.md_on_surface_variant))
                setPadding(0, (10 * d).toInt(), 0, 0)
            })
            val e = EditText(this).apply {
                setText(current[k] ?: "")
                hint = label
                inputType = when (k) {
                    "email" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    "phone" -> android.text.InputType.TYPE_CLASS_PHONE
                    "address", "other" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    "initials" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                    else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                }
            }
            box.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            inputs[k] = e
        }
        box.addView(TextView(this).apply {
            text = "Saved only on this phone."
            textSize = 12f
            setPadding(0, (8 * d).toInt(), 0, 0)
        })
        MaterialAlertDialogBuilder(this)
            .setTitle("My details")
            .setView(android.widget.ScrollView(this).apply { addView(padded(box)) })
            .setPositiveButton("Save") { _, _ ->
                MyDetails.save(this, inputs.mapValues { it.value.text.toString() })
                quickFillSheet()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Grid of page previews: tap one to jump there. */
    private fun pagesSheet() {
        val p = pdf ?: return
        val r = renderer ?: return
        val n = p.geoms.size
        val d = resources.displayMetrics.density
        val current = doc.currentPage()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = "$n page${if (n == 1) "" else "s"}"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(MaterialButton(this).apply {
            text = "Go to number"
            setOnClickListener { sheet.dismiss(); goToPageDialog() }
        })
        root.addView(head)

        val cols = Ui.columns(this, 112, 3)
        val thumbs = android.util.LruCache<Int, android.graphics.Bitmap>(60)
        val thumbW = (((resources.displayMetrics.widthPixels - 24 * d) / cols) - 16 * d).toInt().coerceAtLeast(80)
        val accent = androidx.core.content.ContextCompat.getColor(this, Themes.accent(this).primary)
        val list = androidx.recyclerview.widget.RecyclerView(this)
        list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, cols)
        class VH(v: View, val card: com.google.android.material.card.MaterialCardView, val img: android.widget.ImageView, val num: TextView) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
        list.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<VH>() {
            override fun getItemCount() = n
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                val box = LinearLayout(this@PdfEditorActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                }
                val card = com.google.android.material.card.MaterialCardView(this@PdfEditorActivity).apply {
                    radius = 8 * d; cardElevation = 2 * d; strokeWidth = 0
                }
                val img = android.widget.ImageView(this@PdfEditorActivity).apply {
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE)
                }
                card.addView(img, android.widget.FrameLayout.LayoutParams(thumbW, (thumbW * 1.41f).toInt()))
                box.addView(card)
                val num = TextView(this@PdfEditorActivity).apply { textSize = 12f; gravity = android.view.Gravity.CENTER; setPadding(0, (4 * d).toInt(), 0, 0) }
                box.addView(num)
                return VH(box, card, img, num)
            }
            override fun onBindViewHolder(h: VH, pos: Int) {
                val g = p.geoms[pos]
                h.img.layoutParams = h.img.layoutParams.apply { height = (thumbW * g.dispH / g.dispW).toInt().coerceIn(40, thumbW * 2) }
                h.num.text = "${pos + 1}"
                val isCur = pos == current
                h.card.strokeWidth = if (isCur) (2.5f * d).toInt() else 0
                h.card.strokeColor = accent
                h.num.setTextColor(if (isCur) accent else androidx.core.content.ContextCompat.getColor(this@PdfEditorActivity, R.color.md_on_surface_variant))
                h.num.setTypeface(null, if (isCur) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                h.img.tag = pos
                val cached = thumbs.get(pos)
                if (cached != null) h.img.setImageBitmap(cached) else {
                    h.img.setImageDrawable(null)
                    r.renderPage(pos, thumbW.coerceAtMost(360), stillWanted = { h.img.tag == pos && sheet.isShowing }) { bmp ->
                        if (bmp == null) return@renderPage
                        thumbs.put(pos, bmp)
                        if (h.img.tag == pos) h.img.setImageBitmap(bmp)
                    }
                }
                h.itemView.setOnClickListener { sheet.dismiss(); doc.goToPage(pos) }
            }
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.7f).toInt()))
        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
        list.scrollToPosition(current)
    }

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
        if (typing != null) { finishTyping(true); return }
        if (editingWidget != null) { commitEdit(); return }
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save changes?")
            .setMessage("You have unsaved changes to this PDF.")
            .setPositiveButton("Save") { _, _ -> quickSave(thenFinish = true) }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    // ---------------- saving ----------------

    private var organizeAfterSave = false
    private var shareAfterSave = false
    private var finishAfterSave = false
    /** Original location of a PDF opened from another app / the file picker. */
    private var sourceUri: android.net.Uri? = null
    private var pendingCopy: Pair<String, Boolean>? = null
    private var pendingPrepared: File? = null

    /**
     * Compress: (includes unsaved edits) -> pick a size with real file sizes -> Replace original or Save as new copy.
     */
    private fun compressCurrent() {
        commitEdit()
        val p = pdf ?: return
        if (!p.editable) { Toast.makeText(this, "This PDF can't be edited", Toast.LENGTH_SHORT).show(); return }
        doc.select(null)
        setLoading(true, if (dirty) "Applying your edits…" else "Preparing…")
        val snapshot = items.map { it.copy() }
        val includeEdits = dirty
        lifecycleScope.launch {
            val base = withContext(Dispatchers.IO) {
                try {
                    if (includeEdits) Storage.newWorkFile(this@PdfEditorActivity, "pdf").also { PdfSaver.save(p, it, snapshot, false) }
                    else p.file
                } catch (e: Throwable) { null }
            }
            setLoading(false, null)
            if (base == null) { Toast.makeText(this@PdfEditorActivity, "Could not prepare the PDF", Toast.LENGTH_LONG).show(); return@launch }
            ShareSheet.compress(this@PdfEditorActivity, base, displayName) { compressed ->
                lifecycleScope.launch {
                    val writable = inLibrary() || withContext(Dispatchers.IO) { canWriteBack() }
                    showSaveSheet(writable, thenOrganize = false, thenShare = false, thenFinish = false, prepared = compressed)
                }
            }
        }
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val pc = pendingCopy ?: return@registerForActivityResult
        pendingCopy = null
        if (uri == null) return@registerForActivityResult
        val prep = pendingPrepared
        pendingPrepared = null
        performSave(Storage.uniquePdf(this, pc.first), overwrite = false, flatten = pc.second, export = false, copyTo = uri, showDialog = true, prepared = prep)
    }

    private fun baseName() = displayName.removeSuffix(".pdf").removeSuffix(".PDF")

    private fun inLibrary() = source.parentFile?.absolutePath == Storage.pdfDir(this).absolutePath

    /** True if we can write back to the PDF's original location. */
    private fun canWriteBack(): Boolean {
        val u = sourceUri ?: return false
        return try {
            contentResolver.openFileDescriptor(u, "rw")?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /** Asks how to save (replace the original or keep it and save a copy), then does what was requested after. */
    private fun quickSave(thenOrganize: Boolean = false, thenShare: Boolean = false, thenFinish: Boolean = false) {
        commitEdit()
        pdf ?: return
        if (!dirty) {
            when {
                thenOrganize -> pdf?.let { organize.launch(OrganizeActivity.intent(this, it.file, displayName)) }
                thenShare -> Ui.share(this, source)
                thenFinish -> finish()
                else -> Toast.makeText(this, "No changes to save", Toast.LENGTH_SHORT).show()
            }
            return
        }
        lifecycleScope.launch {
            val writable = inLibrary() || withContext(Dispatchers.IO) { canWriteBack() }
            showSaveSheet(writable, thenOrganize, thenShare, thenFinish)
        }
    }

    private fun saveDialog(thenOrganize: Boolean = false) = quickSave(thenOrganize = thenOrganize)

    private fun showSaveSheet(canReplace: Boolean, thenOrganize: Boolean, thenShare: Boolean, thenFinish: Boolean, prepared: File? = null) {
        val p = pdf ?: return
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.sheet_save, null)
        sheet.setContentView(v)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        v.findViewById<TextView>(R.id.fileName).text = displayName

        val primary = androidx.core.content.ContextCompat.getColor(this, Themes.accent(this).primary)
        fun themeColor(attr: Int, fallback: Int): Int {
            val tv = android.util.TypedValue()
            return if (theme.resolveAttribute(attr, tv, true)) {
                if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(this, tv.resourceId) else tv.data
            } else fallback
        }
        val outline = themeColor(com.google.android.material.R.attr.colorOutline, Color.GRAY)
        val surface = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val selectedBg = themeColor(com.google.android.material.R.attr.colorPrimaryContainer, Color.rgb(220, 228, 255))

        val replace = v.findViewById<com.google.android.material.card.MaterialCardView>(R.id.optReplace)
        val copy = v.findViewById<com.google.android.material.card.MaterialCardView>(R.id.optCopy)
        val copyBox = v.findViewById<View>(R.id.copyBox)
        val nameField = v.findViewById<EditText>(R.id.name)
        val where = v.findViewById<ChipGroup>(R.id.where)
        val flatten = v.findViewById<MaterialSwitch>(R.id.flatten)
        val btn = v.findViewById<MaterialButton>(R.id.btnSave)
        flatten.visibility = if (p.fields.isNotEmpty() && prepared == null) View.VISIBLE else View.GONE
        if (prepared != null) {
            v.findViewById<TextView>(R.id.fileName).text =
                "Compressed: ${android.text.format.Formatter.formatShortFileSize(this, prepared.length())}  ·  $displayName"
        }

        fun setup(card: View, title: String, sub: String, icon: Int, badge: Boolean) {
            card.findViewById<TextView>(R.id.title).text = title
            card.findViewById<TextView>(R.id.subtitle).text = sub
            card.findViewById<View>(R.id.badge).visibility = if (badge) View.VISIBLE else View.GONE
            card.findViewById<View>(R.id.size).visibility = View.GONE
            card.findViewById<View>(R.id.saving).visibility = View.GONE
            card.findViewById<View>(R.id.iconBox).backgroundTintList = android.content.res.ColorStateList.valueOf(selectedBg)
            card.findViewById<android.widget.ImageView>(R.id.icon).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(primary)
            }
        }
        setup(replace, "Replace original",
            if (canReplace) "Overwrite $displayName" else "Not possible — this file is read-only", R.drawable.ic_save, false)
        setup(copy, "Save as new copy", "Keep the original unchanged", R.drawable.ic_save_as, false)
        replace.alpha = if (canReplace) 1f else 0.5f

        val prefs = getSharedPreferences("save", MODE_PRIVATE)
        var useReplace = canReplace && prefs.getBoolean("replace", true)
        val suffix = if (prepared != null) "_compressed" else "_signed"
        nameField.setText(baseName().let { if (it.endsWith(suffix)) it else it + suffix })

        fun refresh() {
            for ((card, sel) in listOf(replace to useReplace, copy to !useReplace)) {
                card.strokeWidth = ((if (sel) 2.5f else 1f) * resources.displayMetrics.density).toInt()
                card.strokeColor = if (sel) primary else outline
                card.setCardBackgroundColor(if (sel) selectedBg else surface)
            }
            copyBox.visibility = if (useReplace) View.GONE else View.VISIBLE
            btn.text = if (useReplace) "Replace original" else "Save copy"
        }
        replace.setOnClickListener {
            if (canReplace) { useReplace = true; refresh() }
            else Toast.makeText(this, "This file can't be changed — save a copy instead", Toast.LENGTH_SHORT).show()
        }
        copy.setOnClickListener { useReplace = false; refresh() }
        refresh()

        btn.setOnClickListener {
            prefs.edit().putBoolean("replace", useReplace).apply()
            organizeAfterSave = thenOrganize
            shareAfterSave = thenShare
            finishAfterSave = thenFinish
            sheet.dismiss()
            val lock = flatten.isChecked
            if (useReplace) {
                if (inLibrary()) performSave(source, overwrite = true, flatten = lock, export = false, copyTo = null, showDialog = false, prepared = prepared)
                else performSave(source, overwrite = true, flatten = lock, export = false, copyTo = sourceUri, showDialog = false, prepared = prepared)
            } else {
                val n = Storage.cleanName(nameField.text.toString())
                if (where.checkedChipId == R.id.wherePick) {
                    pendingCopy = Pair(n, lock)
                    pendingPrepared = prepared
                    try { createDoc.launch("$n.pdf") } catch (e: Exception) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show() }
                } else {
                    performSave(Storage.uniquePdf(this, n), overwrite = false, flatten = lock, export = true, copyTo = null, showDialog = !thenFinish && !thenShare && !thenOrganize, prepared = prepared)
                }
            }
        }
        sheet.show()
    }

    private fun performSave(target: File, overwrite: Boolean, flatten: Boolean, export: Boolean, copyTo: android.net.Uri?, showDialog: Boolean, prepared: File? = null) {
        val p = pdf ?: return
        doc.select(null)
        setLoading(true, "Saving…")
        val snapshot = items.map { it.copy() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val tmp = if (overwrite) Storage.newWorkFile(this@PdfEditorActivity, "pdf") else target
                    if (prepared != null) prepared.copyTo(tmp, overwrite = true)
                    else PdfSaver.save(p, tmp, snapshot, flatten)
                    if (overwrite) {
                        // atomic replace; the viewer keeps reading the old file until we reload
                        if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                    }
                    val exported = export && Storage.exportToDownloads(this@PdfEditorActivity, target)
                    val copied = copyTo?.let { uri ->
                        contentResolver.openOutputStream(uri, "wt")?.use { out -> target.inputStream().use { it.copyTo(out) } } != null
                    } ?: false
                    Triple(target, exported, copied)
                } catch (e: Throwable) {
                    null
                }
            }
            setLoading(false, null)
            if (result == null) {
                Toast.makeText(this@PdfEditorActivity, "Could not save the PDF", Toast.LENGTH_LONG).show()
                return@launch
            }
            val (file, exported, copied) = result
            if (copyTo != null && !copied) {
                MaterialAlertDialogBuilder(this@PdfEditorActivity)
                    .setTitle("Couldn't write to the original file")
                    .setMessage("Your changes are kept in the app as ${file.name}. Use Save → Save as new copy to put it somewhere else.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            if (finishAfterSave) {
                Toast.makeText(this@PdfEditorActivity, "Saved", Toast.LENGTH_SHORT).show()
                dirty = false
                finish()
                return@launch
            }
            reloadFrom(file)
            when {
                organizeAfterSave -> { organizeAfterSave = false; organize.launch(OrganizeActivity.intent(this@PdfEditorActivity, file, file.name)) }
                shareAfterSave -> { shareAfterSave = false; Ui.share(this@PdfEditorActivity, file) }
                showDialog -> MaterialAlertDialogBuilder(this@PdfEditorActivity)
                    .setTitle("Saved")
                    .setMessage(file.name +
                        (if (copied) "\n\nSaved to the folder you chose." else "") +
                        (if (exported) "\n\nA copy is in Downloads › Docora." else ""))
                    .setPositiveButton("Share") { _, _ -> Ui.share(this@PdfEditorActivity, file) }
                    .setNeutralButton("Print") { _, _ -> Ui.print(this@PdfEditorActivity, file) }
                    .setNegativeButton("Keep editing", null)
                    .show()
                else -> Toast.makeText(this@PdfEditorActivity,
                    (if (prepared != null) "Compressed & saved · ${android.text.format.Formatter.formatShortFileSize(this@PdfEditorActivity, file.length())}" else "Saved") +
                        (if (exported) " · copy in Downloads › Docora" else ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** After saving, continue editing the saved file (changes are now part of the PDF). */
    private fun reloadFrom(file: File) {
        val page = doc.currentPage()
        items.clear()
        history.clear()
        hIndex = -1
        savedIndex = 0
        dirty = false
        doc.select(null)
        doc.clearTextSelection()
        renderer?.close()
        renderer = null
        source = file
        displayName = file.name
        toolbar.title = displayName
        restorePage = page
        open(null)
    }

    private var restorePage = -1

    private fun padded(v: View): View {
        val pad = (22 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
