package com.hanson.scansign

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Shows text recognised (on the phone) from PDF pages or scanned images. Copy / share / save. */
class TextResultActivity : BaseActivity() {

    companion object {
        private const val EXTRA_PDF = "pdf"
        private const val EXTRA_PAGES = "pages"
        private const val EXTRA_IMAGES = "images"
        private const val EXTRA_NAME = "name"

        fun intent(ctx: Context, pdf: File, pages: List<Int>, name: String) =
            Intent(ctx, TextResultActivity::class.java)
                .putExtra(EXTRA_PDF, pdf.absolutePath).putExtra(EXTRA_PAGES, pages.toIntArray()).putExtra(EXTRA_NAME, name)

        fun forImages(ctx: Context, images: List<File>, name: String) =
            Intent(ctx, TextResultActivity::class.java)
                .putExtra(EXTRA_IMAGES, images.map { it.absolutePath }.toTypedArray()).putExtra(EXTRA_NAME, name)
    }

    private lateinit var text: EditText
    private var name = "text"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_result)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        text = findViewById(R.id.text)
        name = (intent.getStringExtra(EXTRA_NAME) ?: "text").removeSuffix(".pdf")
        findViewById<MaterialToolbar>(R.id.toolbar).subtitle = Ocr.script(this).label
        val bar = findViewById<LinearProgressIndicator>(R.id.progressBar)
        val ptext = findViewById<TextView>(R.id.progressText)

        findViewById<MaterialButton>(R.id.btnCopy).setOnClickListener {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(name, text.text))
            Ui.toast(this, "Copied")
        }
        findViewById<MaterialButton>(R.id.btnShare).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text.text.toString())
            }, "Share text"))
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveTxt() }

        val pdf = intent.getStringExtra(EXTRA_PDF)
        val pages = intent.getIntArrayExtra(EXTRA_PAGES)
        val images = intent.getStringArrayExtra(EXTRA_IMAGES)
        lifecycleScope.launch {
            val total = images?.size ?: pages?.size ?: 0
            val sb = StringBuilder()
            withContext(Dispatchers.IO) {
                fun add(label: String, bmp: Bitmap?) {
                    val t = if (bmp == null) "" else try { Ocr.recognize(this@TextResultActivity, bmp).first } catch (e: Throwable) { "" }
                    if (total > 1) sb.append("──── ").append(label).append(" ────\n")
                    sb.append(t.trim()).append("\n\n")
                }
                if (images != null) {
                    images.forEachIndexed { i, p ->
                        val b = ImageUtils.load(File(p), 2600)
                        add("Page ${i + 1}", b)
                        b?.recycle()
                        progress(bar, ptext, i + 1, total)
                    }
                } else if (pdf != null && pages != null) {
                    try {
                        ParcelFileDescriptor.open(File(pdf), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { r ->
                                pages.forEachIndexed { k, i ->
                                    if (i !in 0 until r.pageCount) return@forEachIndexed
                                    val p = r.openPage(i)
                                    val scale = (2400f / max(p.width, p.height)).coerceAtMost(3.5f)
                                    val b = Bitmap.createBitmap((p.width * scale).toInt().coerceAtLeast(1), (p.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    b.eraseColor(Color.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    p.close()
                                    add("Page ${i + 1}", b)
                                    b.recycle()
                                    progress(bar, ptext, k + 1, total)
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        sb.append("Could not read this PDF.")
                    }
                }
            }
            findViewById<View>(R.id.progressBox).visibility = View.GONE
            val result = sb.toString().trim()
            text.setText(if (result.isBlank() || result.replace(Regex("─+ Page \\d+ ─+"), "").isBlank())
                "No text was found. Try a clearer scan, or change the language in Settings." else result)
        }
    }

    private fun progress(bar: LinearProgressIndicator, t: TextView, d: Int, total: Int) = runOnUiThread {
        bar.isIndeterminate = false
        bar.max = total
        bar.setProgressCompat(d, true)
        t.text = "Recognising text… page $d of $total"
    }

    private fun saveTxt() {
        val content = text.text.toString()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "$name.txt")
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Docora")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                        contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                        true
                    } else false
                } catch (e: Throwable) { false }
            }
            Ui.toast(this@TextResultActivity, if (ok) "Saved to Downloads › Docora › $name.txt" else "Could not save the file")
        }
    }
}
