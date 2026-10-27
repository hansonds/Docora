package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Removes the paper background from a cropped signature photo and saves it as a transparent PNG. */
class SignatureEditActivity : BaseActivity() {

    companion object {
        const val EXTRA_SRC = "src"
        const val EXTRA_OUT = "out"
        private const val MIN_T = 80

        fun intent(ctx: Context, src: File) =
            Intent(ctx, SignatureEditActivity::class.java).putExtra(EXTRA_SRC, src.absolutePath)

        /** Grey/white checkerboard so transparency is visible. */
        fun checkerboard(ctx: Context): Drawable {
            val cell = (8 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(6)
            val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val p = Paint().apply { color = Color.rgb(226, 229, 233) }
            c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
            c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
            return BitmapDrawable(ctx.resources, bmp).apply {
                setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
        }
    }

    private lateinit var preview: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var sensitivity: SeekBar
    private lateinit var boldness: SeekBar
    private lateinit var sensLabel: TextView

    private var processor: SignatureProcessor? = null
    private var ink = InkColor.ORIGINAL
    private var result: Bitmap? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signature_edit)
        setSupportActionBar(findViewById(R.id.toolbar))
        title = "Clean up signature"
        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.progress)
        sensitivity = findViewById(R.id.sensitivity)
        boldness = findViewById(R.id.boldness)
        sensLabel = findViewById(R.id.sensLabel)
        findViewById<View>(R.id.previewFrame).background = checkerboard(this)

        val inkGroup = findViewById<ChipGroup>(R.id.inkGroup)
        for (c in InkColor.values()) {
            val chip = Chip(this).apply {
                id = View.generateViewId()
                text = c.label
                isCheckable = true
                tag = c
            }
            inkGroup.addView(chip)
            if (c == ink) chip.isChecked = true
        }
        inkGroup.isSingleSelection = true
        inkGroup.isSelectionRequired = true
        inkGroup.setOnCheckedStateChangeListener { group, ids ->
            val chip = ids.firstOrNull()?.let { group.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            ink = chip.tag as InkColor
            render()
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) render() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sensitivity.setOnSeekBarChangeListener(listener)
        boldness.setOnSeekBarChangeListener(listener)

        findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED); finish()
        }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }

        val path = intent.getStringExtra(EXTRA_SRC)
        if (path == null) { finish(); return }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val p = withContext(Dispatchers.Default) {
                val bmp = ImageUtils.load(File(path), 1600) ?: return@withContext null
                SignatureProcessor(bmp).also { bmp.recycle() }
            }
            if (p == null) {
                Toast.makeText(this@SignatureEditActivity, "Could not open the image", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            processor = p
            sensitivity.progress = (p.autoThreshold - MIN_T).coerceIn(0, sensitivity.max)
            render()
        }
    }

    private fun render() {
        val p = processor ?: return
        val t = MIN_T + sensitivity.progress
        val bold = boldness.progress / 100f
        val color = ink
        sensLabel.text = "Background removal (${sensitivity.progress * 100 / sensitivity.max}%)"
        job?.cancel()
        job = lifecycleScope.launch {
            delay(60)
            progress.visibility = View.VISIBLE
            val bmp = withContext(Dispatchers.Default) { p.render(t, color, bold) }
            result = bmp
            preview.setImageBitmap(bmp)
            progress.visibility = View.GONE
        }
    }

    private fun save() {
        val bmp = result ?: return
        if (bmp.width <= 4 && bmp.height <= 4) {
            Toast.makeText(this, "No ink found — move the slider right", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val out = File(Storage.signatureDir(this@SignatureEditActivity), "sig_${System.currentTimeMillis()}.png")
            withContext(Dispatchers.IO) { ImageUtils.savePng(bmp, out) }
            Toast.makeText(this@SignatureEditActivity, "Signature saved", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
            finish()
        }
    }
}
