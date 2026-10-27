package com.hanson.scansign

import android.content.Intent
import android.content.res.ColorStateList
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
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var count: TextView
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

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.menu.add(0, 1, 0, "About").apply {
            setIcon(R.drawable.ic_info)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        toolbar.setOnMenuItemClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        count = findViewById(R.id.count)
        btnResume = findViewById(R.id.btnResume)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnScan).setOnClickListener {
            startActivity(ScanActivity.intent(this, ScanActivity.START_CAMERA))
        }
        tile(R.id.tileOpen, R.drawable.ic_folder, R.color.icon_tile_1, R.color.brand, "Open PDF", "View, zoom & sign") {
            openPdf.launch(arrayOf("application/pdf"))
        }
        tile(R.id.tileImport, R.drawable.ic_photo, R.color.icon_tile_2, R.color.md_secondary, "From photos", "Turn images into PDF") {
            startActivity(ScanActivity.intent(this, ScanActivity.START_GALLERY))
        }
        tile(R.id.tileForm, R.drawable.ic_form, R.color.icon_tile_3, R.color.danger, "Fill a form", "Type into PDF forms") {
            openPdf.launch(arrayOf("application/pdf"))
        }
        tile(R.id.tileSigs, R.drawable.ic_sign, R.color.icon_tile_4, R.color.brand_purple, "Signatures", "Create & manage") {
            startActivity(SignaturesActivity.intent(this, pick = false))
        }
        btnResume.setOnClickListener { startActivity(ScanActivity.intent(this, null)) }

        if (!App.openCvReady) {
            Toast.makeText(this, "Edge detection engine failed to load — manual cropping still works", Toast.LENGTH_LONG).show()
        }
        if (savedInstanceState == null) handleIncoming(intent)
    }

    private fun tile(id: Int, icon: Int, bg: Int, fg: Int, title: String, sub: String, onClick: () -> Unit) {
        val t = findViewById<View>(id)
        t.findViewById<View>(R.id.iconBox).backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
        t.findViewById<ImageView>(R.id.icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, fg))
        }
        t.findViewById<TextView>(R.id.title).text = title
        t.findViewById<TextView>(R.id.subtitle).text = sub
        t.setOnClickListener { onClick() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    /** PDFs opened or shared from other apps go straight to the editor. */
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
            startActivity(PdfEditorActivity.intent(this@MainActivity, f, name))
        }
    }

    private fun reload() {
        files = Storage.listPdfs(this)
        adapter.notifyDataSetChanged()
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        count.text = if (files.isEmpty()) "" else "${files.size}"
        val n = ScanSession.pages.size
        btnResume.visibility = if (n > 0) View.VISIBLE else View.GONE
        btnResume.text = "Continue unfinished scan ($n page${if (n == 1) "" else "s"})"
    }

    private fun options(anchor: View, f: File) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Open & edit")
        menu.menu.add(0, 2, 1, "Share")
        menu.menu.add(0, 3, 2, "Open in another app")
        menu.menu.add(0, 4, 3, "Rename")
        menu.menu.add(0, 5, 4, "Delete")
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(PdfEditorActivity.intent(this, f))
                2 -> Storage.sharePdf(this, f)
                3 -> try {
                    Storage.openPdfExternally(this, f)
                } catch (e: Exception) {
                    Toast.makeText(this, "No other PDF app installed", Toast.LENGTH_SHORT).show()
                }
                4 -> rename(f)
                5 -> MaterialAlertDialogBuilder(this)
                    .setTitle("Delete ${f.name}?")
                    .setMessage("The copy in Downloads (if any) is not affected.")
                    .setPositiveButton("Delete") { _, _ ->
                        f.delete(); thumbCache.remove(f.absolutePath); reload()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }
        menu.show()
    }

    private fun rename(f: File) {
        val input = EditText(this).apply {
            setText(f.name.removeSuffix(".pdf"))
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            val pad = (22 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, 0)
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

    private fun pageCountAndThumb(f: File): Pair<Int, Bitmap?> = try {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val r = PdfRenderer(fd)
            try {
                val n = r.pageCount
                if (n == 0) Pair(0, null) else {
                    val page = r.openPage(0)
                    try {
                        val w = 180
                        val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Pair(n, b)
                    } finally {
                        page.close()
                    }
                }
            } finally {
                r.close()
            }
        }
    } catch (e: Exception) {
        Pair(0, null)
    }

    private val pageCounts = HashMap<String, Int>()

    inner class PdfAdapter : RecyclerView.Adapter<PdfAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val more: View = v.findViewById(R.id.more)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_pdf, parent, false))

        override fun getItemCount() = files.size

        private fun meta(f: File): String {
            val pages = pageCounts[f.absolutePath]
            return DateFormat.format("dd MMM yyyy, hh:mm a", f.lastModified()).toString() +
                "  ·  " + Formatter.formatShortFileSize(this@MainActivity, f.length()) +
                (if (pages != null && pages > 0) "  ·  $pages pg" else "")
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = files[position]
            holder.name.text = f.name.removeSuffix(".pdf")
            holder.meta.text = meta(f)
            holder.itemView.setOnClickListener { startActivity(PdfEditorActivity.intent(this@MainActivity, f)) }
            holder.more.setOnClickListener { options(it, f) }
            val key = f.absolutePath
            holder.thumb.tag = key
            val cached = thumbCache[key]
            if (cached != null) {
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setImageDrawable(null)
                lifecycleScope.launch {
                    val (n, bmp) = withContext(Dispatchers.IO) { pageCountAndThumb(f) }
                    pageCounts[key] = n
                    if (bmp != null) thumbCache[key] = bmp
                    if (holder.thumb.tag == key) {
                        holder.thumb.setImageBitmap(bmp)
                        holder.meta.text = meta(f)
                    }
                }
            }
        }
    }
}
