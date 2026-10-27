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
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
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

/** Pages of the document being scanned. Lives as long as the app process. */
object ScanSession {
    val pages = ArrayList<ScanPage>()
}

class ScanActivity : AppCompatActivity() {

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
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<MaterialButton>(R.id.btnPhotos).setOnClickListener { openGallery() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { askSave() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmLeave()
        })

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
                ScanSession.pages.clear()
                finish()
            }
            .setNeutralButton("Stay", null)
            .show()
    }

    private fun openCamera() {
        cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_DOC, ScanSession.pages.size))
    }

    private fun openGallery() {
        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { Storage.copyUriToWork(this@ScanActivity, it, "jpg") }
            }
            progress.visibility = View.GONE
            queue.addAll(files)
            if (files.size > 1) {
                Toast.makeText(this@ScanActivity, "${files.size} photos — adjust each one", Toast.LENGTH_SHORT).show()
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

    private fun refreshEmpty() {
        empty.visibility = if (ScanSession.pages.isEmpty()) View.VISIBLE else View.GONE
        title = if (ScanSession.pages.isEmpty()) "New scan" else "New scan · ${ScanSession.pages.size} page(s)"
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
                    adapter.notifyDataSetChanged()
                }
                4 -> if (index < pages.size - 1) {
                    java.util.Collections.swap(pages, index, index + 1)
                    adapter.notifyDataSetChanged()
                }
                5 -> {
                    pages.removeAt(index)
                    adapter.notifyDataSetChanged()
                    refreshEmpty()
                }
            }
            true
        }
        menu.show()
    }

    private fun rotatePage(index: Int) {
        val page = ScanSession.pages[index]
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val out = withContext(Dispatchers.Default) {
                val bmp = ImageUtils.load(page.page) ?: return@withContext null
                val r = ImageUtils.rotate(bmp, 90f)
                val f = Storage.newWorkFile(this@ScanActivity, "jpg")
                ImageUtils.saveJpeg(r, f, 92)
                r.recycle()
                f
            }
            progress.visibility = View.GONE
            if (out != null) {
                page.page = out
                adapter.notifyItemChanged(index)
            }
        }
    }

    private fun askSave() {
        if (ScanSession.pages.isEmpty()) {
            Toast.makeText(this, "Add at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_save_pdf, null)
        val name = view.findViewById<EditText>(R.id.name)
        val sizeGroup = view.findViewById<RadioGroup>(R.id.size)
        val qualityGroup = view.findViewById<RadioGroup>(R.id.quality)
        val margin = view.findViewById<CheckBox>(R.id.margin)
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
                savePdf(name.text.toString(), size, quality, margin.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun savePdf(name: String, size: PageSize, quality: Quality, margin: Boolean) {
        progress.visibility = View.VISIBLE
        val images = ScanSession.pages.map { it.page }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val out = Storage.uniquePdf(this@ScanActivity, name)
                    PdfTools.imagesToPdf(images, out, size, quality, margin)
                    val exported = Storage.exportToDownloads(this@ScanActivity, out)
                    Pair(out, exported)
                } catch (e: Throwable) {
                    null
                }
            }
            progress.visibility = View.GONE
            if (result == null) {
                Toast.makeText(this@ScanActivity, "Could not create the PDF", Toast.LENGTH_LONG).show()
                return@launch
            }
            val (file, exported) = result
            ScanSession.pages.clear()
            adapter.notifyDataSetChanged()
            refreshEmpty()
            MaterialAlertDialogBuilder(this@ScanActivity)
                .setTitle("PDF saved")
                .setMessage(
                    file.name + if (exported) "\n\nA copy is in Downloads › PDF ScanSign." else ""
                )
                .setPositiveButton("Open & sign") { _, _ ->
                    startActivity(PdfEditorActivity.intent(this@ScanActivity, file))
                    finish()
                }
                .setNeutralButton("Share") { _, _ ->
                    Storage.sharePdf(this@ScanActivity, file)
                    finish()
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

        override fun onBindViewHolder(holder: VH, position: Int) {
            val page = ScanSession.pages[position]
            holder.label.text = "Page ${position + 1}"
            holder.thumb.setImageDrawable(null)
            holder.thumb.tag = page.page.absolutePath
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.thumb(page.page) }
                if (holder.thumb.tag == page.page.absolutePath) holder.thumb.setImageBitmap(bmp)
            }
            holder.itemView.setOnClickListener { pageMenu(it, holder.bindingAdapterPosition) }
        }
    }
}
