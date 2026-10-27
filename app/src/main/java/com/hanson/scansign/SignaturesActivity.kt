package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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

/** Saved signatures. In pick mode, tapping one returns it to the caller. */
class SignaturesActivity : BaseActivity() {

    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_SIG = "sig"

        fun intent(ctx: Context, pick: Boolean) =
            Intent(ctx, SignaturesActivity::class.java).putExtra(EXTRA_PICK, pick)
    }

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private var pickMode = false
    private var items: List<File> = emptyList()
    private val adapter = SigAdapter()
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val path = res.data?.getStringExtra(CameraActivity.EXTRA_FILE)
        if (res.resultCode == Activity.RESULT_OK && path != null) crop(File(path))
    }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@registerForActivityResult
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) { Storage.copyUriToWork(this@SignaturesActivity, uri, "jpg") }
            progress.visibility = View.GONE
            if (f != null) crop(f)
            else Toast.makeText(this@SignaturesActivity, "Could not open the photo", Toast.LENGTH_SHORT).show()
        }
    }

    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val out = res.data?.getStringExtra(CropActivity.EXTRA_OUT)
        if (res.resultCode == Activity.RESULT_OK && out != null) {
            editLauncher.launch(SignatureEditActivity.intent(this, File(out)))
        }
    }

    private val drawLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val out = res.data?.getStringExtra(DrawSignatureActivity.EXTRA_OUT)
        reload()
        if (res.resultCode == Activity.RESULT_OK && out != null && pickMode) returnSignature(File(out))
    }

    private val editLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val out = res.data?.getStringExtra(SignatureEditActivity.EXTRA_OUT)
        reload()
        if (res.resultCode == Activity.RESULT_OK && out != null && pickMode) {
            // a new signature made while picking is used straight away
            returnSignature(File(out))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signatures)
        pickMode = intent.getBooleanExtra(EXTRA_PICK, false)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = if (pickMode) "Choose a signature" else "My signatures"

        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        list.layoutManager = GridLayoutManager(this, Ui.columns(this, 190, 2))
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener {
            cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_SIG))
        }
        findViewById<MaterialButton>(R.id.btnDraw).setOnClickListener {
            drawLauncher.launch(Intent(this, DrawSignatureActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnGallery).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun crop(f: File) {
        cropLauncher.launch(CropActivity.intent(this, f, CropActivity.MODE_SIG))
    }

    private fun reload() {
        items = Storage.listSignatures(this)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun returnSignature(f: File) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SIG, f.absolutePath))
        finish()
    }

    /** Options for a saved signature: use, share / save as transparent PNG, delete. */
    private fun options(f: File) {
        val items = ArrayList<CharSequence>()
        val actions = ArrayList<() -> Unit>()
        if (pickMode) { items.add("Use this signature"); actions.add { returnSignature(f) } }
        items.add("Share as PNG (transparent)"); actions.add { shareGuide(f) }
        items.add("Save PNG to Downloads"); actions.add { savePng(f) }
        items.add("Delete"); actions.add { confirmDelete(f) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Signature")
            .setItems(items.toTypedArray()) { _, i -> actions[i]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pngName() = "Docora_signature_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.png"

    /** Explains how to keep the transparent background before opening the share menu. */
    private fun shareGuide(f: File) {
        val prefs = getSharedPreferences("hints", MODE_PRIVATE)
        if (prefs.getBoolean("png_share_ok", false)) { sharePng(f); return }
        val msg = "Your signature is a PNG with a transparent background, so it can be placed on any document.\n\n" +
            "📎 WhatsApp: send it as a DOCUMENT, not as a photo.\n" +
            "   1. Tap Save PNG first (it goes to Downloads › Docora)\n" +
            "   2. In WhatsApp tap 📎 → Document → pick the PNG\n" +
            "   Sending it as a photo turns the background black or white.\n\n" +
            "✉ Email, Google Drive, Telegram (as file): the transparency is kept.\n\n" +
            "If you share straight to WhatsApp from here, it may be sent as a photo."
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        box.addView(android.widget.TextView(this).apply { text = msg; setLineSpacing(0f, 1.2f) })
        val dont = android.widget.CheckBox(this).apply { text = "Don't show this again" }
        box.addView(dont)
        MaterialAlertDialogBuilder(this)
            .setTitle("Keep the transparent background")
            .setView(Ui.padded(this, box))
            .setPositiveButton("Share") { _, _ ->
                if (dont.isChecked) prefs.edit().putBoolean("png_share_ok", true).apply()
                sharePng(f)
            }
            .setNeutralButton("Save PNG") { _, _ ->
                if (dont.isChecked) prefs.edit().putBoolean("png_share_ok", true).apply()
                savePng(f)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sharePng(f: File) {
        // share a copy with a friendly name; image/png keeps the file untouched when the receiver supports files
        val out = File(File(cacheDir, "share").apply { mkdirs() }, pngName())
        f.copyTo(out, overwrite = true)
        val uri = Storage.uriFor(this, out)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "Share signature (PNG)"))
    }

    private fun savePng(f: File) {
        lifecycleScope.launch {
            val name = pngName()
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                            put(android.provider.MediaStore.Downloads.MIME_TYPE, "image/png")
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/Docora")
                        }
                        val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                        true
                    } else {
                        val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "Docora").apply { mkdirs() }
                        f.copyTo(File(dir, name), overwrite = true)
                        true
                    }
                } catch (e: Throwable) { false }
            }
            if (ok) MaterialAlertDialogBuilder(this@SignaturesActivity)
                .setTitle("Saved")
                .setMessage("$name\n\nSaved in Downloads › Docora.\n\nTo send it on WhatsApp with the transparent background: 📎 → Document → Downloads › Docora → pick this file.")
                .setPositiveButton("OK", null)
                .show()
            else Toast.makeText(this@SignaturesActivity, "Could not save the PNG", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(f: File) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete this signature?")
            .setPositiveButton("Delete") { _, _ -> f.delete(); reload() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class SigAdapter : RecyclerView.Adapter<SigAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.image)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_signature, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.image.setImageDrawable(null)
            holder.image.tag = f.absolutePath
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.thumb(f, 600) }
                if (holder.image.tag == f.absolutePath) holder.image.setImageBitmap(bmp)
            }
            holder.itemView.setOnClickListener {
                if (pickMode) returnSignature(f) else options(f)
            }
            holder.itemView.setOnLongClickListener { options(f); true }
        }
    }
}
