package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Places signatures on the pages of a PDF and saves a signed copy. */
class PdfSignActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PDF = "pdf"
        const val EXTRA_NAME = "name"

        fun intent(ctx: Context, pdf: File, displayName: String? = null) =
            Intent(ctx, PdfSignActivity::class.java)
                .putExtra(EXTRA_PDF, pdf.absolutePath)
                .putExtra(EXTRA_NAME, displayName ?: pdf.name)
    }

    private lateinit var pageView: SignaturePlacementView
    private lateinit var progress: ProgressBar
    private lateinit var pageLabel: TextView
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton

    private lateinit var pdf: File
    private var displayName = "document.pdf"
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderLock = Mutex()
    private var pageCount = 0
    private var current = 0
    private val placements = ArrayList<Placement>()
    private var saved = false
    private var currentBitmap: Bitmap? = null

    private val pickSignature = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra(SignaturesActivity.EXTRA_SIG)
        if (res.resultCode == Activity.RESULT_OK && path != null) addSignature(File(path))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_sign)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        pageView = findViewById(R.id.pageView)
        progress = findViewById(R.id.progress)
        pageLabel = findViewById(R.id.pageLabel)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)

        val path = intent.getStringExtra(EXTRA_PDF)
        if (path == null) { finish(); return }
        pdf = File(path)
        displayName = intent.getStringExtra(EXTRA_NAME) ?: pdf.name
        title = displayName

        pageView.onDelete = { p ->
            placements.remove(p)
            refreshOverlay()
        }
        btnPrev.setOnClickListener { if (current > 0) showPage(current - 1) }
        btnNext.setOnClickListener { if (current < pageCount - 1) showPage(current + 1) }
        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener {
            pickSignature.launch(SignaturesActivity.intent(this, pick = true))
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        try {
            val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            val r = PdfRenderer(fd)
            renderer = r
            pageCount = r.pageCount
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open this PDF (it may be password-protected or damaged)", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // render once the view has its size
        pageView.post { showPage(0) }
    }

    override fun onSupportNavigateUp(): Boolean {
        confirmLeave()
        return true
    }

    private fun confirmLeave() {
        if (placements.isEmpty() || saved) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Discard signatures?")
            .setMessage("You placed signatures but haven't saved the PDF.")
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Stay", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { renderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
    }

    private fun showPage(index: Int) {
        val r = renderer ?: return
        current = index.coerceIn(0, max(0, pageCount - 1))
        pageLabel.text = "Page ${current + 1} of $pageCount"
        btnPrev.isEnabled = current > 0
        btnNext.isEnabled = current < pageCount - 1
        val target = max(800, min(2000, (max(pageView.width, 1) * 1.5f).toInt()))
        val idx = current
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                renderLock.withLock {
                    try {
                        val page = r.openPage(idx)
                        try {
                            val w = target
                            val h = (target.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            b.eraseColor(Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b
                        } finally {
                            page.close()
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            progress.visibility = View.GONE
            if (idx != current) return@launch
            currentBitmap = bmp
            refreshOverlay()
        }
    }

    private fun refreshOverlay() {
        pageView.setPage(currentBitmap, placements.filter { it.page == current })
    }

    private fun addSignature(sig: File) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sig.absolutePath, o)
        if (o.outWidth <= 0) return
        val fw = 0.32f
        // keep the signature's own proportions on this page
        val fh = fw * (o.outHeight.toFloat() / o.outWidth) * pageView.pageAspect()
        val p = Placement(current, sig, 0.5f - fw / 2f, 0.72f - fh / 2f, fw, fh)
        placements.add(p)
        refreshOverlay()
        pageView.select(p)
    }

    private fun save() {
        if (placements.isEmpty()) {
            Toast.makeText(this, "Add a signature first", Toast.LENGTH_SHORT).show()
            return
        }
        progress.visibility = View.VISIBLE
        val toSave = placements.map { it.copy() }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val base = displayName.removeSuffix(".pdf").removeSuffix(".PDF")
                    val out = Storage.uniquePdf(this@PdfSignActivity, base.removeSuffix("_signed") + "_signed")
                    PdfTools.addSignatures(pdf, out, toSave)
                    Pair(out, Storage.exportToDownloads(this@PdfSignActivity, out))
                } catch (e: Throwable) {
                    null
                }
            }
            progress.visibility = View.GONE
            if (result == null) {
                Toast.makeText(this@PdfSignActivity, "Could not save the signed PDF", Toast.LENGTH_LONG).show()
                return@launch
            }
            saved = true
            val (file, exported) = result
            MaterialAlertDialogBuilder(this@PdfSignActivity)
                .setTitle("Signed PDF saved")
                .setMessage(file.name + if (exported) "\n\nA copy is in Downloads › PDF ScanSign." else "")
                .setPositiveButton("Share") { _, _ -> Storage.sharePdf(this@PdfSignActivity, file); finish() }
                .setNeutralButton("Open") { _, _ -> Storage.openPdfExternally(this@PdfSignActivity, file); finish() }
                .setNegativeButton("Done") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }
}
