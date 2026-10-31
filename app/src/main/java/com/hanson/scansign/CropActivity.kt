package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Documents: adjust the 4 corners (auto-detected), then pick a filter and save the page.
 * Signatures: fixed 4:3 rectangle crop.
 * Returns the output image path in [EXTRA_OUT].
 */
class CropActivity : BaseActivity() {

    override val darkSystemBars = true

    companion object {
        const val EXTRA_SRC = "src"
        const val EXTRA_MODE = "mode"
        const val EXTRA_OUT = "out"
        const val EXTRA_HINT = "hint"
        const val MODE_DOC = "doc"
        const val MODE_SIG = "sig"

        /** [hint] = live-camera corners as fractions (x0,y0,...,x3,y3), used if detection on the photo fails. */
        fun intent(ctx: Context, src: File, mode: String, hint: FloatArray? = null) =
            Intent(ctx, CropActivity::class.java)
                .putExtra(EXTRA_SRC, src.absolutePath)
                .putExtra(EXTRA_MODE, mode)
                .putExtra(EXTRA_HINT, hint)
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var cropView: QuadCropView
    private lateinit var ratioView: RatioCropView
    private lateinit var preview: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var hint: TextView
    private lateinit var filterScroll: View
    private lateinit var filterGroup: ChipGroup
    private lateinit var cropTools: View
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton

    private var source: Bitmap? = null
    private var warped: Bitmap? = null
    private var warpedSmall: Bitmap? = null
    private var filter = ScanFilter.AUTO
    private var mode = MODE_DOC
    private var inFilterStep = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        toolbar = findViewById(R.id.toolbar)
        cropView = findViewById(R.id.cropView)
        ratioView = findViewById(R.id.ratioView)
        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        filterScroll = findViewById(R.id.filterScroll)
        filterGroup = findViewById(R.id.filterGroup)
        cropTools = findViewById(R.id.cropTools)
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DOC
        // a document keeps the look chosen on its first page
        filter = if (mode == MODE_SIG) Settings.defaultFilter(this) else ScanSession.filter ?: Settings.defaultFilter(this)

        toolbar.setNavigationOnClickListener { goBack() }
        val isSig = mode == MODE_SIG
        cropView.visibility = if (isSig) View.GONE else View.VISIBLE
        ratioView.visibility = if (isSig) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnAuto).visibility = if (isSig) View.GONE else View.VISIBLE
        findViewById<TileButton>(R.id.btnFull).text = if (isSig) "Reset" else "Full image"
        showFilterStep(false)
        setupFilters()

        findViewById<View>(R.id.btnAuto).setOnClickListener { autoDetect() }
        findViewById<View>(R.id.btnFull).setOnClickListener {
            val b = source ?: return@setOnClickListener
            if (isSig) ratioView.resetCrop() else cropView.setQuad(Scanner.fullQuad(b.width, b.height))
        }
        findViewById<View>(R.id.btnRotate).setOnClickListener { rotate() }
        btnBack.setOnClickListener { goBack() }
        btnNext.setOnClickListener { next() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val path = intent.getStringExtra(EXTRA_SRC)
        if (path == null) { finish(); return }
        val hintQuad = intent.getFloatArrayExtra(EXTRA_HINT)
        setBusy(true)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageUtils.load(File(path)) }
            if (bmp == null) {
                Toast.makeText(this@CropActivity, "Could not open this image", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            source = bmp
            if (isSig) {
                ratioView.setImage(bmp)
            } else {
                val quad = withContext(Dispatchers.Default) { Scanner.detect(bmp) }
                    ?: hintQuad?.let { h -> List(4) { PointF(h[it * 2] * bmp.width, h[it * 2 + 1] * bmp.height) } }
                cropView.setImage(bmp, quad ?: Scanner.defaultQuad(bmp.width, bmp.height))
                if (quad == null) {
                    hint.text = if (App.openCvReady) "Page edges not found — drag the corners to fit the page"
                    else "Edge detection engine not available — drag the corners to fit the page"
                }
            }
            setBusy(false)
        }
    }

    private fun setupFilters() {
        for (f in ScanFilter.values()) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = f.label
                isCheckable = true
                tag = f
            }
            filterGroup.addView(chip)
            if (f == filter) chip.isChecked = true
        }
        filterGroup.isSingleSelection = true
        filterGroup.isSelectionRequired = true
        filterGroup.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            filter = chip.tag as ScanFilter
            refreshPreview()
        }
    }

    private fun autoDetect() {
        val bmp = source ?: return
        setBusy(true)
        lifecycleScope.launch {
            val quad = withContext(Dispatchers.Default) { Scanner.detect(bmp) }
            if (quad != null) cropView.setQuad(quad)
            else Toast.makeText(this@CropActivity, "No clear page edges found — adjust by hand", Toast.LENGTH_SHORT).show()
            setBusy(false)
        }
    }

    private fun rotate() {
        val bmp = source ?: return
        setBusy(true)
        lifecycleScope.launch {
            val rotated = withContext(Dispatchers.Default) { ImageUtils.rotate(bmp, 90f, recycleSrc = false) }
            source = rotated
            if (mode == MODE_SIG) {
                ratioView.setImage(rotated)
            } else {
                val quad = withContext(Dispatchers.Default) { Scanner.detect(rotated) }
                cropView.setImage(rotated, quad ?: Scanner.defaultQuad(rotated.width, rotated.height))
            }
            setBusy(false)
        }
    }

    private fun next() {
        if (busy) return
        val bmp = source ?: return
        if (inFilterStep) { savePage(); return }
        setBusy(true)
        if (mode == MODE_SIG) {
            val r = ratioView.getCrop()
            lifecycleScope.launch {
                val out = Storage.newWorkFile(this@CropActivity, "png")
                withContext(Dispatchers.IO) {
                    val l = r.left.roundToInt().coerceIn(0, bmp.width - 1)
                    val t = r.top.roundToInt().coerceIn(0, bmp.height - 1)
                    val w = r.width().roundToInt().coerceIn(1, bmp.width - l)
                    val h = r.height().roundToInt().coerceIn(1, bmp.height - t)
                    val c = Bitmap.createBitmap(bmp, l, t, w, h)
                    ImageUtils.savePng(c, out)
                }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
                finish()
            }
            return
        }
        val quad = cropView.getQuad()
        lifecycleScope.launch {
            val w = withContext(Dispatchers.Default) { Scanner.warp(bmp, quad) }
            warped = w
            warpedSmall = withContext(Dispatchers.Default) {
                val s = 1400f / max(w.width, w.height)
                if (s < 1f) Bitmap.createScaledBitmap(w, (w.width * s).toInt(), (w.height * s).toInt(), true) else w
            }
            showFilterStep(true)
            setBusy(false)
            refreshPreview()
        }
    }

    private fun refreshPreview() {
        val small = warpedSmall ?: return
        val f = filter
        lifecycleScope.launch {
            val shown = withContext(Dispatchers.Default) { Scanner.applyFilter(small, f) }
            preview.setImageBitmap(shown)
        }
    }

    private fun savePage() {
        val w = warped ?: return
        val f = filter
        if (mode != MODE_SIG && ScanSession.filter == null) ScanSession.filter = f
        setBusy(true)
        lifecycleScope.launch {
            val out = Storage.newWorkFile(this@CropActivity, "jpg")
            withContext(Dispatchers.Default) {
                val filtered = Scanner.applyFilter(w, f)
                ImageUtils.saveJpeg(filtered, out, 92)
                filtered.recycle()
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
            finish()
        }
    }

    private fun showFilterStep(show: Boolean) {
        inFilterStep = show
        val isSig = mode == MODE_SIG
        if (!isSig) cropView.visibility = if (show) View.GONE else View.VISIBLE
        preview.visibility = if (show) View.VISIBLE else View.GONE
        cropTools.visibility = if (show) View.GONE else View.VISIBLE
        filterScroll.visibility = if (show) View.VISIBLE else View.GONE
        btnBack.text = if (show) "Re-crop" else "Cancel"
        btnNext.text = when {
            show -> "Save page"
            isSig -> "Next"
            else -> "Crop"
        }
        toolbar.title = when {
            isSig -> "Crop signature"
            show && ScanSession.filter == null -> "Choose a look for this document"
            show -> "Page look"
            else -> "Adjust corners"
        }
        hint.text = when {
            isSig -> "Fit the signature inside the 4:3 box. Drag to move, drag a corner to resize."
            show && ScanSession.filter == null -> "Pick the look for this page — every page you add to this document will use it"
            show -> "Using this document's look (${ScanSession.filter?.label}) · tap another to change just this page"
            else -> "Drag the corners or edges to fit the page. A magnifier helps with precision."
        }
    }

    private fun goBack() {
        if (inFilterStep) showFilterStep(false)
        else { setResult(Activity.RESULT_CANCELED); finish() }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnNext.isEnabled = !b
    }
}
