package com.hanson.scansign

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/** Lists the PDFs to merge; drag to reorder, add or remove, then merge. */
class MergeActivity : BaseActivity() {

    companion object {
        private const val EXTRA_FILES = "files"
        private const val EXTRA_NAMES = "names"

        fun intent(ctx: Context, files: List<File>, names: List<String>) =
            Intent(ctx, MergeActivity::class.java)
                .putExtra(EXTRA_FILES, files.map { it.absolutePath }.toTypedArray())
                .putExtra(EXTRA_NAMES, names.toTypedArray())
    }

    private class Entry(val file: File, val name: String) {
        var pages = -1
        var thumb: Bitmap? = null
        var error = false
    }

    private val entries = ArrayList<Entry>()
    private val adapter = Adapter()
    private lateinit var touch: ItemTouchHelper
    private lateinit var summary: TextView
    private lateinit var mergeBtn: MaterialButton

    private val addMore = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> add(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_merge)
        val tb = findViewById<MaterialToolbar>(R.id.toolbar)
        tb.setNavigationOnClickListener { finish() }
        tb.menu.add(0, 1, 0, "Reverse order")
        tb.menu.add(0, 2, 1, "Sort by name")
        tb.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> entries.reverse()
                2 -> entries.sortBy { it.name.lowercase() }
            }
            adapter.notifyDataSetChanged()
            true
        }
        summary = findViewById(R.id.summary)
        mergeBtn = findViewById(R.id.btnMerge)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        Ui.limitWidth(list, 720)
        touch = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(entries, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.notifyDataSetChanged()
            }
        })
        touch.attachToRecyclerView(list)

        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { addMore.launch(arrayOf("application/pdf")) }
        mergeBtn.setOnClickListener { askName() }

        val files = intent.getStringArrayExtra(EXTRA_FILES) ?: emptyArray()
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        files.forEachIndexed { i, p -> entries.add(Entry(File(p), names.getOrElse(i) { File(p).name })) }
        adapter.notifyDataSetChanged()
        loadDetails()
    }

    private fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    val f = Storage.copyUriToWork(this@MergeActivity, u, "pdf") ?: return@mapNotNull null
                    Entry(f, Storage.displayName(this@MergeActivity, u) ?: "document.pdf")
                }
            }
            entries.addAll(added)
            adapter.notifyDataSetChanged()
            loadDetails()
        }
    }

    private fun loadDetails() {
        updateSummary()
        lifecycleScope.launch {
            for (e in entries.filter { it.pages < 0 && !it.error }) {
                withContext(Dispatchers.IO) {
                    try {
                        ParcelFileDescriptor.open(e.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                            PdfRenderer(fd).use { r ->
                                e.pages = r.pageCount
                                if (r.pageCount > 0) {
                                    val p = r.openPage(0)
                                    val w = 120
                                    val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(Color.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    p.close()
                                    e.thumb = b
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // password-protected or damaged: PDFBox may still be able to merge it
                        e.error = true
                        e.pages = try { PdfTools.pageCount(e.file) } catch (x: Throwable) { -1 }
                    }
                }
                adapter.notifyDataSetChanged()
                updateSummary()
            }
        }
    }

    private fun updateSummary() {
        val pages = entries.sumOf { maxOf(0, it.pages) }
        val size = entries.sumOf { it.file.length() }
        summary.text = "${entries.size} files · $pages pages · ${Formatter.formatShortFileSize(this, size)}"
        mergeBtn.text = "Merge ${entries.size} files"
        mergeBtn.isEnabled = entries.size >= 2
    }

    private fun askName() {
        if (entries.size < 2) { Ui.toast(this, "Add at least two PDFs"); return }
        val et = EditText(this).apply {
            setText(entries.first().name.removeSuffix(".pdf").removeSuffix(".PDF") + "_merged")
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "File name"
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Name the merged PDF")
            .setView(Ui.padded(this, et))
            .setPositiveButton("Merge") { _, _ -> merge(Storage.cleanName(et.text.toString())) }
            .setNegativeButton("Cancel", null)
            .show()
        et.requestFocus()
    }

    private fun merge(name: String) {
        val files = entries.map { it.file }
        Ui.runJob(this, "Merging", {
            val out = Storage.uniquePdf(this, name)
            PdfTools.merge(files, out)
            Pair(out, Storage.exportToDownloads(this, out))
        }) { (out, exported) -> Ui.showSaved(this, out, exported) }
    }

    inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val handle: View = v.findViewById(R.id.handle)
            val num: TextView = v.findViewById(R.id.num)
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val name: TextView = v.findViewById(R.id.name)
            val meta: TextView = v.findViewById(R.id.meta)
            val remove: View = v.findViewById(R.id.remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_merge, parent, false))

        override fun getItemCount() = entries.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(h: VH, position: Int) {
            val e = entries[position]
            h.num.text = "${position + 1}"
            h.name.text = e.name
            val size = Formatter.formatShortFileSize(this@MergeActivity, e.file.length())
            h.meta.text = when {
                e.pages > 0 -> "${e.pages} page${if (e.pages == 1) "" else "s"} · $size"
                e.error -> "Protected or damaged · $size"
                else -> size
            }
            h.thumb.setImageBitmap(e.thumb)
            h.handle.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) touch.startDrag(h)
                false
            }
            h.remove.setOnClickListener {
                val i = h.bindingAdapterPosition
                if (i in entries.indices) {
                    entries.removeAt(i)
                    notifyDataSetChanged()
                    updateSummary()
                }
            }
        }
    }
}
