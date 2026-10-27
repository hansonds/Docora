package com.hanson.scansign

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var btnResume: MaterialButton
    private var files: List<File> = emptyList()
    private val adapter = PdfAdapter()
    private val thumbCache = HashMap<String, Bitmap>()

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openExternalPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = getString(R.string.app_name)

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        btnResume = findViewById(R.id.btnResume)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener {
            startActivity(ScanActivity.intent(this, ScanActivity.START_CAMERA))
        }
        findViewById<MaterialButton>(R.id.btnPhotos).setOnClickListener {
            startActivity(ScanActivity.intent(this, ScanActivity.START_GALLERY))
        }
        findViewById<MaterialButton>(R.id.btnSignPdf).setOnClickListener {
            openPdf.launch(arrayOf("application/pdf"))
        }
        findViewById<MaterialButton>(R.id.btnSignatures).setOnClickListener {
            startActivity(SignaturesActivity.intent(this, pick = false))
        }
        btnResume.setOnClickListener { startActivity(ScanActivity.intent(this, null)) }

        if (!App.openCvReady) {
            Toast.makeText(this, "Edge detection engine failed to load — manual cropping still works", Toast.LENGTH_LONG).show()
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** PDFs opened or shared from other apps go straight to signing. */
    private fun handleIncoming(i: Intent?) {
        i ?: return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) openExternalPdf(uri)
    }

    private fun openExternalPdf(uri: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { Storage.displayName(this@MainActivity, uri) } ?: "document.pdf"
            val f = withContext(Dispatchers.IO) { Storage.copyUriToWork(this@MainActivity, uri, "pdf") }
            if (f == null) {
                Toast.makeText(this@MainActivity, "Could not open that PDF", Toast.LENGTH_LONG).show()
                return@launch
            }
            startActivity(PdfSignActivity.intent(this@MainActivity, f, name))
        }
    }

    private fun reload() {
        files = Storage.listPdfs(this)
        adapter.notifyDataSetChanged()
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        val n = ScanSession.pages.size
        btnResume.visibility = if (n > 0) View.VISIBLE else View.GONE
        btnResume.text = "Continue unfinished scan ($n page${if (n == 1) "" else "s"})"
    }

    private fun options(f: File) {
        val actions = arrayOf<CharSequence>("Open", "Add signature", "Share", "Rename", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(f.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> try {
                        Storage.openPdfExternally(this, f)
                    } catch (e: Exception) {
                        Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
                    }
                    1 -> startActivity(PdfSignActivity.intent(this, f))
                    2 -> Storage.sharePdf(this, f)
                    3 -> rename(f)
                    4 -> MaterialAlertDialogBuilder(this)
                        .setTitle("Delete ${f.name}?")
                        .setMessage("The copy in Downloads (if any) is not affected.")
                        .setPositiveButton("Delete") { _, _ ->
                            f.delete(); thumbCache.remove(f.absolutePath); reload()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .show()
    }

    private fun rename(f: File) {
        val input = EditText(this).apply {
            setText(f.name.removeSuffix(".pdf"))
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val target = File(f.parentFile, Storage.cleanName(input.text.toString()) + ".pdf")
                if (target.exists() && target != f) {
                    Toast.makeText(this, "A file with that name already exists", Toast.LENGTH_SHORT).show()
                } else {
                    f.renameTo(target)
                    thumbCache.remove(f.absolutePath)
                    reload()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun firstPageThumb(f: File): Bitmap? = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val r = PdfRenderer(fd)
            try {
                if (r.pageCount == 0) null else {
                    val page = r.openPage(0)
                    try {
                        val w = 160
                        val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        b
                    } finally {
                        page.close()
                    }
                }
            } finally {
                r.close()
            }
        }
    } catch (e: Exception) {
        null
    }

    inner class PdfAdapter : RecyclerView.Adapter<PdfAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf, parent, false))

        override fun getItemCount() = files.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            holder.name.text = f.name
            holder.meta.text = DateFormat.format("dd MMM yyyy, hh:mm a", f.lastModified()).toString() +
                "  ·  " + Formatter.formatShortFileSize(this@MainActivity, f.length())
            holder.itemView.setOnClickListener { options(f) }
            val key = f.absolutePath
            holder.thumb.tag = key
            val cached = thumbCache[key]
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageResource(R.drawable.ic_pdf)
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) { firstPageThumb(f) } ?: return@launch
                    thumbCache[key] = bmp
                    if (holder.thumb.tag == key) holder.thumb.setImageBitmap(bmp)
                }
            }
        }
    }
}
