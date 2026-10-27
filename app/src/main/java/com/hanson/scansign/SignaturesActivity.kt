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
class SignaturesActivity : AppCompatActivity() {

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
        list.layoutManager = GridLayoutManager(this, 2)
        list.adapter = adapter

        findViewById<MaterialButton>(R.id.btnCamera).setOnClickListener {
            cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_SIG))
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
                if (pickMode) returnSignature(f) else confirmDelete(f)
            }
            holder.itemView.setOnLongClickListener { confirmDelete(f); true }
        }
    }
}
