package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.Collections

/** Page organiser: reorder (drag), rotate, delete, duplicate, insert blank pages, extract a selection. */
class OrganizeActivity : BaseActivity() {

    companion object {
        const val EXTRA_SRC = "src"
        const val EXTRA_NAME = "name"
        const val EXTRA_OUT = "out"

        fun intent(ctx: Context, src: File, name: String) =
            Intent(ctx, OrganizeActivity::class.java).putExtra(EXTRA_SRC, src.absolutePath).putExtra(EXTRA_NAME, name)
    }

    private lateinit var src: File
    private lateinit var name: String
    private lateinit var grid: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private var renderer: PageRenderer? = null
    private val ops = ArrayList<PageOp>()
    private val selected = HashSet<Long>()
    private val thumbs = HashMap<Int, Bitmap>()
    private val pending = HashSet<Int>()
    private var changed = false
    private val adapter = PageAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_organize)
        src = File(intent.getStringExtra(EXTRA_SRC) ?: run { finish(); return })
        name = (intent.getStringExtra(EXTRA_NAME) ?: src.name).removeSuffix(".pdf").removeSuffix(".PDF")
        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { confirmLeave() }
        toolbar.menu.add(0, 1, 0, "Save").setIcon(R.drawable.ic_save).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        toolbar.setOnMenuItemClickListener { save(); true }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, Ui.columns(this, 125, 3))
        grid.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder): Boolean {
                val from = a.bindingAdapterPosition; val to = b.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(ops, from, to)
                adapter.notifyItemMoved(from, to)
                changed = true
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.notifyDataSetChanged() // refresh page numbers
            }
        }).attachToRecyclerView(grid)

        val bar = findViewById<LinearLayout>(R.id.actions)
        action(bar, "Rotate left", R.drawable.ic_rotate) { rotate(-90) }
        action(bar, "Rotate right", R.drawable.ic_rotate_right) { rotate(90) }
        action(bar, "Delete", R.drawable.ic_delete) { delete() }
        action(bar, "Duplicate", R.drawable.ic_pages) { duplicate() }
        action(bar, "Blank page", R.drawable.ic_add) { addBlank() }
        action(bar, "Extract", R.drawable.ic_split) { extract() }
        action(bar, "Select all", R.drawable.ic_check) { selectAll() }

        try {
            val r = PageRenderer(src)
            renderer = r
            for (i in 0 until r.pageCount) ops.add(PageOp(i))
            findViewById<View>(R.id.progress).visibility = View.GONE
            adapter.notifyDataSetChanged()
            updateTitle()
        } catch (e: Throwable) {
            MaterialAlertDialogBuilder(this).setTitle("Can't open")
                .setMessage("This PDF can't be opened here. If it has a password, remove it first with the Remove password tool.")
                .setPositiveButton("OK") { _, _ -> finish() }.setCancelable(false).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renderer?.close()
    }

    private fun action(parent: LinearLayout, label: String, icon: Int, onClick: () -> Unit) {
        val b = LayoutInflater.from(this).inflate(R.layout.view_tool_button, parent, false) as MaterialButton
        b.text = label
        b.setIconResource(icon)
        b.setOnClickListener { onClick() }
        parent.addView(b)
    }

    private fun updateTitle() {
        toolbar.title = if (selected.isEmpty()) "Organize · ${ops.size} pages" else "${selected.size} selected"
    }

    private fun targets(): List<PageOp> {
        val sel = ops.filter { it.id in selected }
        return sel
    }

    private fun needSelection(): Boolean {
        if (selected.isEmpty()) { Ui.toast(this, "Tap pages to select them first"); return false }
        return true
    }

    private fun rotate(deg: Int) {
        if (!needSelection()) return
        targets().forEach { it.rot = (((it.rot + deg) % 360) + 360) % 360 }
        changed = true
        adapter.notifyDataSetChanged()
    }

    private fun delete() {
        if (!needSelection()) return
        if (selected.size == ops.size) { Ui.toast(this, "A PDF needs at least one page"); return }
        ops.removeAll { it.id in selected }
        selected.clear()
        changed = true
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun duplicate() {
        if (!needSelection()) return
        val copy = ArrayList<PageOp>()
        for (op in ops) {
            copy.add(op)
            if (op.id in selected) copy.add(PageOp(op.src, op.rot))
        }
        ops.clear(); ops.addAll(copy)
        changed = true
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun addBlank() {
        val lastSel = ops.indexOfLast { it.id in selected }
        val at = if (lastSel >= 0) lastSel + 1 else ops.size
        ops.add(at, PageOp(-1))
        changed = true
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(at)
        updateTitle()
        Ui.toast(this, "Blank page added at position ${at + 1}")
    }

    private fun selectAll() {
        if (selected.size == ops.size) selected.clear() else { selected.clear(); ops.forEach { selected.add(it.id) } }
        adapter.notifyDataSetChanged()
        updateTitle()
    }

    private fun extract() {
        if (!needSelection()) return
        val pick = targets().map { PageOp(it.src, it.rot) }
        Ui.runJob(this, "Extracting", {
            val out = Storage.uniquePdf(this, "${name}_extract")
            PdfTools.organize(src, pick, out)
            Pair(out, Storage.exportToDownloads(this, out))
        }) { (out, exported) -> Ui.showSaved(this, out, exported) }
    }

    private fun save() {
        if (!changed) { Ui.toast(this, "No changes to save"); return }
        Ui.runJob(this, "Saving", {
            val base = if (name.endsWith("_organized")) name else "${name}_organized"
            val out = Storage.uniquePdf(this, base)
            PdfTools.organize(src, ops.map { PageOp(it.src, it.rot) }, out)
            Pair(out, Storage.exportToDownloads(this, out))
        }) { (out, exported) ->
            changed = false
            if (callingActivity != null) {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
                finish()
            } else {
                Ui.showSaved(this, out, exported)
            }
        }
    }

    private fun confirmLeave() {
        if (!changed) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save changes?")
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun thumb(op: PageOp, iv: ImageView) {
        iv.setImageDrawable(null)
        iv.rotation = 0f
        if (op.src < 0) return
        val cached = thumbs[op.src]
        if (cached != null) {
            iv.setImageBitmap(if (op.rot == 0) cached else ImageUtils.rotate(cached, op.rot.toFloat(), recycleSrc = false))
            return
        }
        val r = renderer ?: return
        if (!pending.add(op.src)) return
        val idx = op.src
        r.renderPage(idx, 260, stillWanted = { !isFinishing }) { bmp ->
            pending.remove(idx)
            if (bmp != null) {
                thumbs[idx] = bmp
                adapter.notifyDataSetChanged()
            }
        }
    }

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.card)
            val thumb: ImageView = v.findViewById(R.id.thumb)
            val check: ImageView = v.findViewById(R.id.check)
            val label: TextView = v.findViewById(R.id.label)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_organize, parent, false))

        override fun getItemCount() = ops.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val op = ops[position]
            val sel = op.id in selected
            h.label.text = when {
                op.src < 0 -> "${position + 1} · blank"
                op.rot != 0 -> "${position + 1} · ${op.rot}°"
                else -> "${position + 1}"
            }
            h.check.visibility = if (sel) View.VISIBLE else View.GONE
            h.card.strokeWidth = if (sel) (3 * resources.displayMetrics.density).toInt() else 0
            thumb(op, h.thumb)
            h.itemView.setOnClickListener {
                if (op.id in selected) selected.remove(op.id) else selected.add(op.id)
                notifyItemChanged(h.bindingAdapterPosition)
                updateTitle()
            }
        }
    }
}
