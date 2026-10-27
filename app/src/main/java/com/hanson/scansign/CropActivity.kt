package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/**
 * Step 1: adjust the crop (auto-detected or manual).
 * Step 2 (documents only): pick a filter, then save the page.
 * Returns the output image path in [EXTRA_OUT].
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SRC = "src"
        const val EXTRA_MODE = "mode"
        const val EXTRA_OUT = "out"
        const val MODE_DOC = "doc"
        const val MODE_SIG = "sig"
        private const val PREFS = "scan"
        private const val KEY_FILTER = "filter"

        fun intent(ctx: Context, src: File, mode: String) =
            Intent(ctx, CropActivity::class.java)
                .putExtra(EXTRA_SRC, src.absolutePath)
                .putExtra(EXTRA_MODE, mode)
    }

    private lateinit var cropView: QuadCropView
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
    private var filter = ScanFilter.ORIGINAL
    private var mode = MODE_DOC
    private var inFilterStep = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        cropView = findViewById(R.id.cropView)
        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.progress)
        hint = findViewById(R.id.hint)
        filterScroll = findViewById(R.id.filterScroll)
        filterGroup = findViewById(R.id.filterGroup)
        cropTools = findViewById(R.id.cropTools)
        btnBack = findViewById(R.id.btnBack)
        btnNext = findViewById(R.id.btnNext)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DOC
        title = if (mode == MODE_SIG) "Crop signature" else "Adjust corners"
        filter = runCatching {
            ScanFilter.valueOf(getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FILTER, "ORIGINAL")!!)
        }.getOrDefault(ScanFilter.ORIGINAL)

        hint.text = if (mode == MODE_SIG)
            "Drag the corners tightly around the signature"
        else
            "Drag corners or edges to fit the page. A magnifier helps you place them."
        btnNext.text = if (mode == MODE_SIG) "Next" else "Crop"

        setupFilters()

        findViewById<MaterialButton>(R.id.btnAuto).setOnClickListener { autoDetect(showToast = true) }
        findViewById<MaterialButton>(R.id.btnFull).setOnClickListener {
            source?.let { cropView.setQuad(Scanner.fullQuad(it.width, it.height)) }
        }
        findViewById<MaterialButton>(R.id.btnRotate).setOnClickListener { rotate() }
        btnBack.setOnClickListener { goBack() }
        btnNext.setOnClickListener { next() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val path = intent.getStringExtra(EXTRA_SRC)
        if (path == null) { finish(); return }
        setBusy(true)
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { ImageUtils.load(File(path)) }
            if (bmp == null) {
                Toast.makeText(this@CropActivity, "Could not open this image", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            source = bmp
            val quad = withContext(Dispatchers.Default) { Scanner.detect(bmp) }
            cropView.setImage(bmp, quad ?: Scanner.defaultQuad(bmp.width, bmp.height))
            if (quad == null && mode == MODE_DOC) {
                hint.text = "Page edges not found automatically — drag the corners to fit"
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FILTER, filter.name).apply()
            refreshPreview()
        }
    }

    private fun autoDetect(showToast: Boolean) {
        val bmp = source ?: return
        setBusy(true)
        lifecycleScope.launch {
            val quad = withContext(Dispatchers.Default) { Scanner.detect(bmp) }
            if (quad != null) cropView.setQuad(quad)
            else if (showToast) Toast.makeText(this@CropActivity, "No clear edges found — adjust manually", Toast.LENGTH_SHORT).show()
            setBusy(false)
        }
    }

    private fun rotate() {
        val bmp = source ?: return
        setBusy(true)
        lifecycleScope.launch {
            val rotated = withContext(Dispatchers.Default) { ImageUtils.rotate(bmp, 90f, recycleSrc = false) }
            source = rotated
            val quad = withContext(Dispatchers.Default) { Scanner.detect(rotated) }
            cropView.setImage(rotated, quad ?: Scanner.defaultQuad(rotated.width, rotated.height))
            setBusy(false)
        }
    }

    private fun next() {
        if (busy) return
        val bmp = source ?: return
        if (inFilterStep) { savePage(); return }
        val quad = cropView.getQuad()
        setBusy(true)
        lifecycleScope.launch {
            val w = withContext(Dispatchers.Default) { Scanner.warp(bmp, quad) }
            warped = w
            if (mode == MODE_SIG) {
                val out = Storage.newWorkFile(this@CropActivity, "png")
                withContext(Dispatchers.IO) { ImageUtils.savePng(w, out) }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
                finish()
                return@launch
            }
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
        cropView.visibility = if (show) View.GONE else View.VISIBLE
        preview.visibility = if (show) View.VISIBLE else View.GONE
        cropTools.visibility = if (show) View.GONE else View.VISIBLE
        filterScroll.visibility = if (show) View.VISIBLE else View.GONE
        btnBack.text = if (show) "Re-crop" else "Cancel"
        btnNext.text = if (show) "Save page" else "Crop"
        title = if (show) "Choose a look" else "Adjust corners"
        hint.text = if (show) "Magic color removes shadows; Black & white is best for text"
        else "Drag corners or edges to fit the page"
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
