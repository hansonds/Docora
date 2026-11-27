package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.LinearLayout
import android.graphics.Color
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One scanned page: the original photo (for re-cropping) and the processed page image. */
data class ScanPage(val original: File, var page: File)

/**
 * Pages of the document being scanned. Kept on disk too, so "Keep for later" still works after
 * the phone closes Docora in the background.
 */
object ScanSession {
    val pages = ArrayList<ScanPage>()
    /** The look chosen on the first page; every page of this document uses it (null = not chosen yet). */
    var filter: ScanFilter? = null
    private var restored = false

    private fun prefs(c: Context) = c.getSharedPreferences("scan_session", Context.MODE_PRIVATE)

    /** Starts a new document. */
    fun reset(c: Context? = null) { pages.clear(); filter = null; c?.let { save(it) } }

    /** Remembers the pages (paths only) and the chosen look. */
    fun save(c: Context) {
        val arr = org.json.JSONArray()
        for (p in pages) arr.put(org.json.JSONArray().put(p.original.absolutePath).put(p.page.absolutePath))
        prefs(c).edit().putString("pages", arr.toString()).putString("filter", filter?.name).apply()
    }

    /** Brings back kept pages after Docora was closed. Returns how many pages had gone missing. */
    fun restore(c: Context): Int {
        if (restored || pages.isNotEmpty()) { restored = true; return 0 }
        restored = true
        var missing = 0
        try {
            val arr = org.json.JSONArray(prefs(c).getString("pages", "[]"))
            for (i in 0 until arr.length()) {
                val e = arr.getJSONArray(i)
                val o = File(e.getString(0)); val p = File(e.getString(1))
                if (p.exists()) pages.add(ScanPage(if (o.exists()) o else p, p)) else missing++
            }
            filter = prefs(c).getString("filter", null)?.let { n -> ScanFilter.values().firstOrNull { it.name == n } }
        } catch (_: Throwable) {}
        return missing
    }

    /** Every file the kept pages use (so cleaning temporary files never removes them). */
    fun files(c: Context): Set<String> {
        val out = HashSet<String>()
        for (p in pages) { out.add(p.original.absolutePath); out.add(p.page.absolutePath) }
        try {
            val arr = org.json.JSONArray(prefs(c).getString("pages", "[]"))
            for (i in 0 until arr.length()) { val e = arr.getJSONArray(i); out.add(e.getString(0)); out.add(e.getString(1)) }
        } catch (_: Throwable) {}
        return out
    }
}

class ScanActivity : BaseActivity() {

    companion object {
        const val EXTRA_START = "start"
        const val START_CAMERA = "camera"
        const val START_GALLERY = "gallery"
        private const val STATE_EDIT_INDEX = "editIndex"

        fun intent(ctx: Context, start: String?) =
            Intent(ctx, ScanActivity::class.java).putExtra(EXTRA_START, start)
    }

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private val adapter = PageAdapter()

    /** True while the current crop came from the in-app camera (so we return to it for the next page). */
    private var fromCamera = false
    /** Originals waiting to be cropped (multi-select import). */
    private val queue = ArrayDeque<File>()
    private var currentOriginal: File? = null
    /** Index of a page being re-cropped, or -1 for a new page. */
    private var editIndex = -1

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        when (res.resultCode) {
            Activity.RESULT_OK -> {
                val path = res.data?.getStringExtra(CameraActivity.EXTRA_FILE) ?: return@registerForActivityResult
                editIndex = -1
                fromCamera = true
                currentOriginal = File(path)
                cropLauncher.launch(
                    CropActivity.intent(this, File(path), CropActivity.MODE_DOC, res.data?.getFloatArrayExtra(CameraActivity.EXTRA_HINT))
                )
            }
            CameraActivity.RESULT_GALLERY -> openGallery()
        }
    }

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50)
    ) { uris -> importUris(uris) }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val out = res.data?.getStringExtra(CropActivity.EXTRA_OUT)
        val original = currentOriginal
        if (res.resultCode == Activity.RESULT_OK && out != null && original != null) {
            if (editIndex in ScanSession.pages.indices) {
                ScanSession.pages[editIndex].page = File(out)
                adapter.notifyItemChanged(editIndex)
            } else {
                ScanSession.pages.add(ScanPage(original, File(out)))
                adapter.notifyItemInserted(ScanSession.pages.size - 1)
                list.scrollToPosition(ScanSession.pages.size - 1)
            }
        }
        val wasEdit = editIndex >= 0
        editIndex = -1
        refreshEmpty()
        if (fromCamera && !wasEdit) {
            // straight back to the camera for the next page; "Done" there returns here
            fromCamera = false
            openCamera()
            return@registerForActivityResult
        }
        fromCamera = false
        nextInQueue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "New scan"

        list = findViewById(R.id.pages)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        list.layoutManager = GridLayoutManager(this, Ui.columns(this, 170, 2))
        list.adapter = adapter

        findViewById<View>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<View>(R.id.btnPhotos).setOnClickListener { openGallery() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { askSave() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        val missing = ScanSession.restore(this)
        if (missing > 0) Ui.note(this, "$missing kept page(s) were removed by the phone's storage cleanup", long = true)
        if (savedInstanceState != null) {
            editIndex = savedInstanceState.getInt(STATE_EDIT_INDEX, -1)
        } else {
            when (intent.getStringExtra(EXTRA_START)) {
                START_CAMERA -> openCamera()
                START_GALLERY -> openGallery()
            }
        }
        refreshEmpty()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_EDIT_INDEX, editIndex)
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 7, 0, "Get text").setIcon(R.drawable.ic_ocr).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 7) {
            if (ScanSession.pages.isEmpty()) Ui.note(this, "Scan some pages first")
            else startActivity(TextResultActivity.forImages(this, ScanSession.pages.map { it.page }, "Scan"))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        confirmLeave()
        return true
    }

    private fun confirmLeave() {
        if (ScanSession.pages.isEmpty()) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Leave this scan?")
            .setMessage("Your pages stay here until you save or discard them.")
            .setPositiveButton("Keep for later") { _, _ -> finish() }
            .setNegativeButton("Discard pages") { _, _ ->
                ScanSession.reset(this)
                finish()
            }
            .setNeutralButton("Stay", null)
            .show()
    }

    private fun openCamera() {
        if (Settings.useAiScanner(this)) openAiScanner()
        else cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_DOC, ScanSession.pages.size))
    }

    // ---- Google ML Kit document scanner (on-device AI) ----

    private val aiLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data) ?: return@registerForActivityResult
        val uris = result.pages?.map { it.imageUri } ?: emptyList()
        if (uris.isEmpty()) return@registerForActivityResult
        val n = uris.size
        showBusy("Getting your pages…", if (n == 1) "1 page" else "$n pages")
        lifecycleScope.launch {
            val origs = withContext(Dispatchers.IO) { uris.mapNotNull { u -> Storage.copyUriToWork(this@ScanActivity, u, "jpg") } }
            if (origs.isEmpty()) { hideBusy(); return@launch }
            // first pages of a new document: let the user pick the look, then use it for every page.
            // The AI scanner has already cleaned the pages, so "As scanned" is the default.
            val filter = ScanSession.filter ?: (if (App.openCvReady) chooseFilter(origs[0], ScanFilter.ORIGINAL) else {
                Ui.note(this@ScanActivity, "Page looks aren't available on this phone — pages are kept as scanned", long = true)
                ScanFilter.ORIGINAL
            }).also { ScanSession.filter = it }
            val lookName = if (filter == ScanFilter.ORIGINAL) "As scanned" else filter.label
            showBusy("Processing…", "Applying $lookName" + if (origs.size > 1) " · page 1 of ${origs.size}" else "")
            var done = 0
            val pages = withContext(Dispatchers.IO) {
                origs.mapNotNull { orig ->
                    done++
                    if (origs.size > 1) runOnUiThread { busyText?.text = "Applying $lookName · page $done of ${origs.size}" }
                    if (filter == ScanFilter.ORIGINAL) return@mapNotNull ScanPage(orig, orig)
                    val bmp = ImageUtils.load(orig) ?: return@mapNotNull ScanPage(orig, orig)
                    val out = Storage.newWorkFile(this@ScanActivity, "jpg")
                    val f = Scanner.applyFilter(bmp, filter)
                    ImageUtils.saveJpeg(f, out, 92)
                    f.recycle(); bmp.recycle()
                    ScanPage(orig, out)
                }
            }
            ScanSession.pages.addAll(pages)
            adapter.notifyDataSetChanged()
            list.scrollToPosition(ScanSession.pages.size - 1)
            hideBusy()
        }
    }

    /**
     * Shows the first page in every look side by side and returns the one picked
     * (closing the picker keeps the default from Settings).
     */
    private suspend fun chooseFilter(sample: File, preferred: ScanFilter? = null): ScanFilter {
        val def = preferred ?: Settings.defaultFilter(this)
        fun name(f: ScanFilter) = if (f == ScanFilter.ORIGINAL && preferred == ScanFilter.ORIGINAL) "As scanned" else f.label
        showBusy("Preparing looks…", "Showing your page in every style")
        val previews = withContext(Dispatchers.Default) {
            val small = ImageUtils.load(sample, 520) ?: return@withContext null
            val out = ScanFilter.values().associateWith { f -> try { Scanner.applyFilter(small, f) } catch (_: Throwable) { small } }
            out
        } ?: return def
        busyCard?.visibility = View.GONE
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val d = resources.displayMetrics.density
            val cols = if (Ui.isTablet(this)) 5 else 3
            val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), 0) }
            grid.addView(TextView(this).apply {
                text = "Every page of this document will use it. You can still change single pages later."
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@ScanActivity, R.color.md_on_surface_variant))
                setPadding(0, 0, 0, (10 * d).toInt())
            })
            var dialog: androidx.appcompat.app.AlertDialog? = null
            var picked: ScanFilter? = null
            val accent = ContextCompat.getColor(this, Themes.accent(this).primary)
            for (row in ScanFilter.values().toList().chunked(cols)) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * d).toInt() })
                for (f in row) {
                    val card = com.google.android.material.card.MaterialCardView(this).apply {
                        radius = 14 * d
                        strokeWidth = if (f == def) (3 * d).toInt() else (1 * d).toInt()
                        strokeColor = if (f == def) accent else ContextCompat.getColor(this@ScanActivity, R.color.md_outline)
                        cardElevation = 0f
                        isClickable = true
                        setOnClickListener { picked = f; dialog?.dismiss() }
                    }
                    val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL; setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (8 * d).toInt()) }
                    inner.addView(android.widget.ImageView(this).apply {
                        setImageBitmap(previews[f]); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Ui.surfaceVariant(this@ScanActivity))
                    }, LinearLayout.LayoutParams(-1, (130 * d).toInt()))
                    inner.addView(TextView(this).apply {
                        text = if (f == def) "${name(f)} ★" else name(f)
                        textSize = 12.5f; gravity = android.view.Gravity.CENTER
                        setTypeface(typeface, if (f == def) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                        setPadding(0, (6 * d).toInt(), 0, 0)
                    })
                    card.addView(inner)
                    line.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = (8 * d).toInt() })
                }
                // keep the last row's tiles the same size as the others
                repeat(cols - row.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = (8 * d).toInt() }) }
            }
            dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Choose a look for this document")
                .setView(android.widget.ScrollView(this).apply { addView(grid) })
                .setNegativeButton("Use ${name(def)}") { _, _ -> picked = def }
                .setOnDismissListener { if (cont.isActive) cont.resume(picked ?: def) }
                .show()
            cont.invokeOnCancellation { try { dialog?.dismiss() } catch (_: Throwable) {} }
        }
    }

    private fun openAiScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender -> aiLauncher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                // no Google Play services / unsupported device: use the built-in camera
                Ui.note(this, "AI scanner not available on this phone — using the built-in camera", long = true)
                cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_DOC, ScanSession.pages.size))
            }
    }

    private fun openGallery() {
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        showBusy("Importing photos…", if (uris.size == 1) "1 photo" else "${uris.size} photos")
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { Storage.copyUriToWork(this@ScanActivity, it, "jpg") }
            }
            hideBusy()
            queue.addAll(files)
            if (files.size > 1) {
                Ui.note(this@ScanActivity, "${files.size} photos — adjust each one")
            }
            editIndex = -1
            nextInQueue()
        }
    }

    private fun nextInQueue() {
        val f = queue.removeFirstOrNull() ?: return
        editIndex = -1
        fromCamera = false
        startCrop(f)
    }

    private fun startCrop(original: File) {
        currentOriginal = original
        cropLauncher.launch(CropActivity.intent(this, original, CropActivity.MODE_DOC))
    }

    // ---- "Processing" card: shown while pages are being prepared, so the screen never looks empty
    private var busyCard: View? = null
    private var busyTitle: TextView? = null
    private var busyText: TextView? = null
    private val busy get() = busyCard?.visibility == View.VISIBLE

    private fun showBusy(title: String, text: String = "") {
        if (busyCard == null) {
            val d = resources.displayMetrics.density
            fun dp(v: Float) = (v * d).toInt()
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 26 * d; cardElevation = 6 * d
                setCardBackgroundColor(Ui.surfaceVariant(this@ScanActivity))
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(32f), dp(28f), dp(32f), dp(26f))
            }
            box.addView(com.google.android.material.progressindicator.CircularProgressIndicator(this).apply {
                isIndeterminate = true
                indicatorSize = dp(56f); trackThickness = dp(5f)
                setIndicatorColor(ContextCompat.getColor(this@ScanActivity, Themes.accent(this@ScanActivity).primary))
                trackCornerRadius = dp(3f)
            })
            busyTitle = TextView(this).apply {
                textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@ScanActivity, R.color.md_on_surface))
                setPadding(0, dp(18f), 0, dp(4f))
            }
            busyText = TextView(this).apply {
                textSize = 13.5f; gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@ScanActivity, R.color.md_on_surface_variant))
            }
            box.addView(busyTitle)
            box.addView(busyText)
            card.addView(box)
            (progress.parent as android.view.ViewGroup).addView(card,
                android.widget.FrameLayout.LayoutParams(dp(290f), -2, android.view.Gravity.CENTER))
            busyCard = card
        }
        busyTitle?.text = title
        busyText?.text = text
        busyText?.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        progress.visibility = View.GONE
        empty.visibility = View.GONE
        busyCard?.let { c ->
            if (c.visibility != View.VISIBLE) { c.alpha = 0f; c.visibility = View.VISIBLE; c.animate().alpha(1f).setDuration(180).start() }
        }
    }

    private fun hideBusy() {
        busyCard?.visibility = View.GONE
        progress.visibility = View.GONE
        refreshEmpty()
    }

    private fun refreshEmpty() {
        empty.visibility = if (ScanSession.pages.isEmpty() && !busy) View.VISIBLE else View.GONE
        val n = ScanSession.pages.size
        title = when (n) { 0 -> "New scan"; 1 -> "New scan · 1 page"; else -> "New scan · $n pages" }
        ScanSession.save(this)
    }

    private fun pageMenu(anchor: View, index: Int) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Adjust crop / filter")
        menu.menu.add(0, 2, 1, "Rotate")
        menu.menu.add(0, 3, 2, "Move earlier")
        menu.menu.add(0, 4, 3, "Move later")
        menu.menu.add(0, 5, 4, "Delete")
        menu.setOnMenuItemClickListener { item ->
            val pages = ScanSession.pages
            if (index !in pages.indices) return@setOnMenuItemClickListener true
            when (item.itemId) {
                1 -> {
                    fromCamera = false
                    editIndex = index
                    currentOriginal = pages[index].original
                    cropLauncher.launch(CropActivity.intent(this, pages[index].original, CropActivity.MODE_DOC))
                }
                2 -> rotatePage(index)
                3 -> if (index > 0) {
                    java.util.Collections.swap(pages, index, index - 1)
                    adapter.notifyItemMoved(index, index - 1)
                    adapter.notifyItemRangeChanged(index - 1, 2)
                    refreshEmpty()
                }
                4 -> if (index < pages.size - 1) {
                    java.util.Collections.swap(pages, index, index + 1)
                    adapter.notifyItemMoved(index, index + 1)
                    adapter.notifyItemRangeChanged(index, 2)
                    refreshEmpty()
                }
                5 -> MaterialAlertDialogBuilder(this)
                    .setTitle("Delete page ${index + 1}?")
                    .setPositiveButton("Delete") { _, _ ->
                        if (index in pages.indices) {
                            pages.removeAt(index)
                            adapter.notifyItemRemoved(index)
                            adapter.notifyItemRangeChanged(index, pages.size - index)
                            refreshEmpty()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun rotatePage(index: Int) {
        val page = ScanSession.pages[index]
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            fun turn(src: File): File? {
                val bmp = ImageUtils.load(src) ?: return null
                val r = ImageUtils.rotate(bmp, 90f)
                val f = Storage.newWorkFile(this@ScanActivity, "jpg")
                ImageUtils.saveJpeg(r, f, 92)
                r.recycle()
                return f
            }
            val turned = withContext(Dispatchers.Default) {
                val p = turn(page.page) ?: return@withContext null
                // the original turns too, so re-cropping later keeps the new orientation
                val o = if (page.original.absolutePath == page.page.absolutePath) p else (turn(page.original) ?: page.original)
                Pair(o, p)
            }
            progress.visibility = View.GONE
            if (turned != null) {
                val i = ScanSession.pages.indexOf(page)
                if (i >= 0) {
                    ScanSession.pages[i] = ScanPage(turned.first, turned.second)
                    adapter.notifyItemChanged(i)
                    refreshEmpty()
                }
            }
        }
    }

    private fun askSave() {
        if (ScanSession.pages.isEmpty()) {
            Ui.note(this, "Add at least one page first")
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_save_pdf, null)
        val name = view.findViewById<EditText>(R.id.name)
        val sizeGroup = view.findViewById<RadioGroup>(R.id.size)
        val qualityGroup = view.findViewById<RadioGroup>(R.id.quality)
        val margin = view.findViewById<CheckBox>(R.id.margin)
        val layoutGroup = view.findViewById<RadioGroup>(R.id.layout)
        val searchable = view.findViewById<CheckBox>(R.id.searchable)
        searchable.isChecked = Settings.searchableDefault(this)
        for (l in Layout.values()) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = l.label; tag = l }
            layoutGroup.addView(rb)
            if (l == Layout.NORMAL) rb.isChecked = true
        }
        name.setText(Storage.defaultName())

        val prefs = getSharedPreferences("scan", MODE_PRIVATE)
        val lastSize = prefs.getString("size", PageSize.A4.name)
        val lastQuality = prefs.getString("quality", Quality.MEDIUM.name)
        margin.isChecked = prefs.getBoolean("margin", false)

        for (s in PageSize.values()) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = s.label; tag = s }
            sizeGroup.addView(rb)
            if (s.name == lastSize) rb.isChecked = true
        }
        for (q in Quality.values()) {
            val rb = RadioButton(this).apply { id = View.generateViewId(); text = q.label; tag = q }
            qualityGroup.addView(rb)
            if (q.name == lastQuality) rb.isChecked = true
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save as PDF")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val size = sizeGroup.findViewById<RadioButton>(sizeGroup.checkedRadioButtonId)?.tag as? PageSize ?: PageSize.A4
                val quality = qualityGroup.findViewById<RadioButton>(qualityGroup.checkedRadioButtonId)?.tag as? Quality ?: Quality.MEDIUM
                prefs.edit()
                    .putString("size", size.name)
                    .putString("quality", quality.name)
                    .putBoolean("margin", margin.isChecked)
                    .apply()
                val layout = layoutGroup.findViewById<RadioButton>(layoutGroup.checkedRadioButtonId)?.tag as? Layout ?: Layout.NORMAL
                savePdf(name.text.toString(), size, quality, margin.isChecked, layout, searchable.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var saving = false

    private fun savePdf(name: String, size: PageSize, quality: Quality, margin: Boolean, layout: Layout, searchable: Boolean) {
        if (saving) return
        saving = true
        val saveBtn = findViewById<View>(R.id.btnSave)
        saveBtn.isEnabled = false
        val images = ScanSession.pages.map { it.page }
        val what = if (searchable) "Creating PDF and reading the text…" else "Creating PDF…"
        showBusy(what, if (images.size == 1) "1 page" else "page 1 of ${images.size}")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val out = Storage.uniquePdf(this@ScanActivity, name)
                    PdfTools.imagesToPdf(this@ScanActivity, images, out, size, quality, margin, layout, searchable) { done, total ->
                        if (total > 1) runOnUiThread { busyText?.text = "page ${minOf(done + 1, total)} of $total" }
                    }
                    val exported = Storage.exportToDownloads(this@ScanActivity, out)
                    Pair(out, exported)
                } catch (e: Throwable) {
                    null
                }
            }
            saving = false
            saveBtn.isEnabled = true
            hideBusy()
            if (result == null) {
                Ui.note(this@ScanActivity, "Could not create the PDF", long = true)
                return@launch
            }
            val (file, exported) = result
            ScanSession.reset(this@ScanActivity)
            adapter.notifyDataSetChanged()
            refreshEmpty()
            MaterialAlertDialogBuilder(this@ScanActivity)
                .setTitle("PDF saved")
                .setMessage(
                    file.name + if (exported) "\n\nA copy is in Downloads › Docora." else ""
                )
                .setPositiveButton("Open & sign") { _, _ ->
                    startActivity(PdfEditorActivity.intent(this@ScanActivity, file))
                    finish()
                }
                .setNeutralButton("Share") { _, _ ->
                    Ui.share(this@ScanActivity, file)
                }
                .setNegativeButton("Done") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false))

        override fun getItemCount() = ScanSession.pages.size

        private val thumbs = object : android.util.LruCache<String, android.graphics.Bitmap>(24 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val page = ScanSession.pages[position]
            holder.label.text = "Page ${position + 1}"
            val key = page.page.absolutePath
            holder.thumb.tag = key
            val cached = thumbs.get(key)
            if (cached != null) holder.thumb.setImageBitmap(cached)
            else {
                holder.thumb.setImageDrawable(null)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { ImageUtils.thumb(page.page) }
                    if (bmp != null) thumbs.put(key, bmp)
                    if (holder.thumb.tag == key) holder.thumb.setImageBitmap(bmp)
                }
            }
            holder.itemView.setOnClickListener { pageMenu(it, holder.bindingAdapterPosition) }
        }
    }
}
