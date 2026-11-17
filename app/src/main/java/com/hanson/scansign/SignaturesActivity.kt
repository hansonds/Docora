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
import android.widget.LinearLayout
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
        const val EXTRA_INK = "ink"

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
            val ready = if (f == null) null else withContext(Dispatchers.IO) { addTransparent(f) }
            progress.visibility = View.GONE
            if (ready != null) {
                // already a clean signature (e.g. a PNG saved from Docora): keep it exactly as it is
                reload()
                Toast.makeText(this@SignaturesActivity, "Added as it is — it already has a transparent background", Toast.LENGTH_LONG).show()
                if (pickMode) returnSignature(ready)
            } else if (f != null) crop(f)
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

        // colourful add buttons, info card icon
        findViewById<TileButton>(R.id.btnCamera).colorful(IconTiles.Hue.TEAL)
        findViewById<TileButton>(R.id.btnGallery).colorful(IconTiles.Hue.BLUE)
        findViewById<TileButton>(R.id.btnDraw).colorful(IconTiles.Hue.VIOLET)
        findViewById<TextView>(R.id.tip).setCompoundDrawablesRelativeWithIntrinsicBounds(
            IconTiles.badge(this, R.drawable.ic_sign, IconTiles.Hue.PINK, 34f), null, null, null)
        findViewById<View>(R.id.btnCamera).setOnClickListener {
            cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_SIG))
        }
        findViewById<View>(R.id.btnDraw).setOnClickListener {
            drawLauncher.launch(Intent(this, DrawSignatureActivity::class.java))
        }
        findViewById<View>(R.id.btnGallery).setOnClickListener {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        if (pickMode) findViewById<TextView>(R.id.tip).text = "Tap a signature, choose its ink colour, and it's placed on your page."
        buildEmptyState()
        reload()
    }

    private val prefs by lazy { getSharedPreferences("signatures", MODE_PRIVATE) }
    private lateinit var emptyPanel: View

    /** Friendly start screen when there are no signatures yet: three clear ways to add one. */
    private fun buildEmptyState() {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val onSurface = androidx.core.content.ContextCompat.getColor(this, R.color.md_on_surface)
        val muted = androidx.core.content.ContextCompat.getColor(this, R.color.md_on_surface_variant)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f), dp(12f), dp(20f), dp(20f))
        }
        box.addView(android.widget.ImageView(this).apply {
            setImageDrawable(IconTiles.badge(this@SignaturesActivity, R.drawable.ic_sign, IconTiles.Hue.PINK, 84f))
        })
        box.addView(TextView(this).apply {
            text = "Add your first signature"; textSize = 23f; setTextColor(onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(6f))
        })
        box.addView(TextView(this).apply {
            text = "Make it once, then sign any PDF with a tap. Your signature stays on this phone."
            textSize = 14.5f; setTextColor(muted); gravity = android.view.Gravity.CENTER; setLineSpacing(0f, 1.15f)
            setPadding(dp(8f), 0, dp(8f), dp(20f))
        })
        fun option(icon: Int, hue: IconTiles.Hue, title: String, sub: String, go: () -> Unit) {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 20 * d; isClickable = true; isFocusable = true
                setOnClickListener { go() }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            }
            row.addView(android.widget.ImageView(this).apply { setImageDrawable(IconTiles.badge(this@SignaturesActivity, icon, hue, 46f)) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, dp(6f), 0) }
            texts.addView(TextView(this).apply { text = title; textSize = 16.5f; setTextColor(onSurface); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(muted); setPadding(0, dp(2f), 0, 0) })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_back); rotation = 180f; setColorFilter(muted); alpha = 0.7f
            }, LinearLayout.LayoutParams(dp(20f), dp(20f)))
            card.addView(row)
            box.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10f) })
        }
        option(R.drawable.ic_camera, IconTiles.Hue.TEAL, "Take a photo", "Sign on white paper. The paper is removed automatically.") {
            cameraLauncher.launch(CameraActivity.intent(this, CameraActivity.MODE_SIG))
        }
        option(R.drawable.ic_image, IconTiles.Hue.BLUE, "Choose from gallery", "Use a picture of your signature you already have.") {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        option(R.drawable.ic_draw, IconTiles.Hue.VIOLET, "Draw with your finger", "Sign right on the screen in a smooth pen stroke.") {
            drawLauncher.launch(Intent(this, DrawSignatureActivity::class.java))
        }
        emptyPanel = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(box, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER))
        }
        Ui.limitWidth(box, 560)
        (empty.parent as ViewGroup).addView(emptyPanel, android.widget.FrameLayout.LayoutParams(-1, -1))
        empty.visibility = View.GONE
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /**
     * If [f] is a picture with a see-through background (a signature PNG), saves it straight into
     * My signatures, trimmed to the ink, and returns it. Photos (no transparency) return null and
     * go through crop + paper removal as usual.
     */
    private fun addTransparent(f: File): File? {
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            var b = android.graphics.BitmapFactory.decodeFile(f.absolutePath, o) ?: return null
            if (!b.hasAlpha()) { b.recycle(); return null }
            val w = b.width; val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            var clear = 0
            var l = w; var t = h; var r = -1; var btm = -1
            for (y in 0 until h) for (x in 0 until w) {
                val a = px[y * w + x] ushr 24
                if (a < 20) { clear++; continue }
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y
            }
            // mostly see-through and some ink: it's a transparent signature
            if (clear < px.size * 0.3 || r < 0) { b.recycle(); return null }
            val m = (maxOf(r - l, btm - t) * 0.04f).toInt() + 2
            l = maxOf(0, l - m); t = maxOf(0, t - m); r = minOf(w - 1, r + m); btm = minOf(h - 1, btm + m)
            var out = android.graphics.Bitmap.createBitmap(b, l, t, r - l + 1, btm - t + 1)
            val big = maxOf(out.width, out.height)
            if (big > 2000) out = android.graphics.Bitmap.createScaledBitmap(out, out.width * 2000 / big, out.height * 2000 / big, true)
            val dest = File(Storage.signatureDir(this), "sig_${System.currentTimeMillis()}.png")
            ImageUtils.savePng(out, dest)
            b.recycle()
            dest
        } catch (_: Throwable) { null }
    }

    private fun crop(f: File) {
        cropLauncher.launch(CropActivity.intent(this, f, CropActivity.MODE_SIG))
    }

    private fun reload() {
        items = Storage.listSignatures(this)
        adapter.notifyDataSetChanged()
        empty.visibility = View.GONE
        if (::emptyPanel.isInitialized) emptyPanel.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tipCard).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<View>(R.id.listTitle).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.listTitle).text = if (items.size == 1) "Your signature" else "Your signatures · ${items.size}"
        // the empty screen already offers the three ways to add one
        findViewById<View>(R.id.addCard).visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Picking: after a signature is chosen, its ink colour is chosen (with a live preview), then it's placed. */
    private fun returnSignature(f: File) {
        SigColors.pick(this, "Choose ink colour", f, InkColor.ORIGINAL, action = "Place") { ink ->
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_SIG, f.absolutePath).putExtra(EXTRA_INK, ink.name))
            finish()
        }
    }

    /** Options for a saved signature, in a bottom panel with its preview. */
    private fun options(f: File) {
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val onSurface = androidx.core.content.ContextCompat.getColor(this, R.color.md_on_surface)
        val muted = androidx.core.content.ContextCompat.getColor(this, R.color.md_on_surface_variant)
        val danger = android.graphics.Color.rgb(220, 53, 69)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(18f)) }

        // preview on a see-through-style background
        val frame = android.widget.FrameLayout(this).apply {
            background = SignatureEditActivity.checkerboard(this@SignaturesActivity)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18 * d) }
            }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(16f), dp(12f), dp(16f), dp(12f)) }
        frame.addView(img, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, dp(130f)))
        lifecycleScope.launch { img.setImageBitmap(withContext(Dispatchers.IO) { ImageUtils.thumb(f, 700) }) }
        root.addView(TextView(this).apply {
            text = "Saved " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(f.lastModified())) +
                " · transparent PNG"
            textSize = 12.5f; setTextColor(muted); setPadding(dp(4f), dp(8f), 0, dp(6f))
        })

        fun row(icon: Int, hue: IconTiles.Hue, title: String, sub: String, red: Boolean = false, go: () -> Unit) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(10f), dp(8f), dp(10f))
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(36, 128, 128, 128)), null,
                    android.graphics.drawable.GradientDrawable().apply { cornerRadius = 16 * d; setColor(android.graphics.Color.WHITE) })
                setOnClickListener { sheet.dismiss(); go() }
            }
            r.addView(ImageView(this).apply { setImageDrawable(IconTiles.badge(this@SignaturesActivity, icon, hue, 42f)) })
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
            texts.addView(TextView(this).apply {
                text = title; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (red) danger else onSurface)
            })
            texts.addView(TextView(this).apply { text = sub; textSize = 12.5f; setTextColor(muted) })
            r.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        if (pickMode) row(R.drawable.ic_check, IconTiles.Hue.BLUE, "Use this signature", "Choose the ink colour and place it") { returnSignature(f) }
        row(R.drawable.ic_palette, IconTiles.Hue.PINK, "Change colour", "Black, blue, red, green or original") { changeColour(f) }
        row(R.drawable.ic_share, IconTiles.Hue.TEAL, "Share", "As a PNG with a transparent background") { shareGuide(f) }
        row(R.drawable.ic_save_as, IconTiles.Hue.INDIGO, "Save to Downloads", "Downloads › Docora") { savePng(f) }
        root.addView(View(this).apply { setBackgroundColor(android.graphics.Color.argb(40, 128, 128, 128)) },
            LinearLayout.LayoutParams(-1, dp(1f)).apply { topMargin = dp(4f); bottomMargin = dp(4f) })
        row(R.drawable.ic_delete, IconTiles.Hue.RED, "Delete", "Remove it from this phone", red = true) { confirmDelete(f) }

        sheet.setContentView(android.widget.ScrollView(this).apply { addView(root) })
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    /** Recolours a saved signature (black, blue, red, green, or back to its original colours). */
    private fun changeColour(f: File) {
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { SigColors.currentInk(this@SignaturesActivity, f) }
            SigColors.pick(this@SignaturesActivity, "Signature colour", f, now) { ink ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { SigColors.recolorSaved(this@SignaturesActivity, f, ink) }
                    if (!ok) Toast.makeText(this@SignaturesActivity, "Couldn't change the colour", Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }
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
            .setPositiveButton("Delete") { _, _ -> f.delete(); java.io.File(java.io.File(Storage.signatureDir(this), ".orig"), f.name).delete(); reload() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class SigAdapter : RecyclerView.Adapter<SigAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.image)
            val caption: TextView = v.findViewById(R.id.caption)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_signature, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = items[position]
            holder.image.setImageDrawable(null)
            holder.image.tag = f.absolutePath
            val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))
            holder.caption.text = if (pickMode) "Tap to use · $date" else "Saved $date  ·  tap for options"
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
