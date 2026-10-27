package com.hanson.scansign

import android.content.res.ColorStateList
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Share sheet with three size options. The compressed versions are prepared in the background
 * as soon as the sheet opens, so the real file sizes and savings are shown before choosing.
 */
object ShareSheet {

    private class Option(
        val title: String, val sub: String, val icon: Int, val level: CompressLevel?, val recommended: Boolean
    ) {
        var file: File? = null
        var ready = false
        lateinit var card: MaterialCardView
        lateinit var size: TextView
        lateinit var saving: TextView
        lateinit var spinner: ProgressBar
    }

    fun show(a: AppCompatActivity, file: File) {
        val sheet = BottomSheetDialog(a)
        val view = LayoutInflater.from(a).inflate(R.layout.sheet_share, null)
        sheet.setContentView(view)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        view.findViewById<TextView>(R.id.fileName).text = file.name

        val original = file.length()
        val opts = listOf(
            Option("Original", "Best quality, unchanged", R.drawable.ic_file, null, false),
            Option("Compressed", "Great quality · WhatsApp & email", R.drawable.ic_compress, CompressLevel.MEDIUM, true),
            Option("Smallest", "Smallest file · fast to send", R.drawable.ic_compress, CompressLevel.STRONG, false)
        )
        val primary = ContextCompat.getColor(a, Themes.accent(a).primary)
        val outline = MaterialColors(a)
        var chosen = opts[1]
        val box = view.findViewById<LinearLayout>(R.id.options)
        val shareBtn = view.findViewById<MaterialButton>(R.id.btnShare)

        fun fmt(b: Long) = Formatter.formatShortFileSize(a, b)

        fun refresh() {
            for (o in opts) {
                val sel = o === chosen
                o.card.strokeWidth = ((if (sel) 2.5f else 1f) * a.resources.displayMetrics.density).toInt()
                o.card.strokeColor = if (sel) primary else outline.outline
                o.card.setCardBackgroundColor(if (sel) outline.selected else outline.surface)
            }
            shareBtn.text = when {
                !chosen.ready -> "Preparing…"
                else -> "Share  ·  ${fmt(chosen.file?.length() ?: original)}"
            }
            shareBtn.isEnabled = chosen.ready
        }

        for (o in opts) {
            val row = LayoutInflater.from(a).inflate(R.layout.item_share_option, box, false)
            o.card = row.findViewById(R.id.card)
            o.size = row.findViewById(R.id.size)
            o.saving = row.findViewById(R.id.saving)
            o.spinner = row.findViewById(R.id.spinner)
            row.findViewById<TextView>(R.id.title).text = o.title
            row.findViewById<TextView>(R.id.subtitle).text = o.sub
            row.findViewById<View>(R.id.badge).visibility = if (o.recommended) View.VISIBLE else View.GONE
            row.findViewById<View>(R.id.iconBox).backgroundTintList = ColorStateList.valueOf(outline.selected)
            row.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(o.icon)
                imageTintList = ColorStateList.valueOf(primary)
                if (o.level == CompressLevel.STRONG) { scaleX = 0.8f; scaleY = 0.8f }
            }
            if (o.level == null) {
                o.file = file; o.ready = true
                o.size.text = fmt(original)
                o.saving.text = ""
            } else {
                o.spinner.visibility = View.VISIBLE
                o.size.text = ""
                o.saving.text = ""
            }
            o.card.setOnClickListener { chosen = o; refresh() }
            box.addView(row)
        }
        refresh()

        // prepare compressed versions one after another
        var job: Job? = null
        job = a.lifecycleScope.launch {
            val dir = File(a.cacheDir, "share").apply { mkdirs() }
            for (o in opts.filter { it.level != null }) {
                val out = File(dir, "${o.level!!.name.lowercase()}_${file.name}")
                val ok = withContext(Dispatchers.IO) {
                    try { PdfTools.compress(file, out, o.level, null) } catch (e: Throwable) { false }
                }
                if (job?.isCancelled == true) return@launch
                o.spinner.visibility = View.GONE
                o.ready = true
                if (ok && out.length() > 0 && out.length() < original) {
                    o.file = out
                    o.size.text = fmt(out.length())
                    val pct = ((1 - out.length().toDouble() / original) * 100).toInt().coerceIn(1, 99)
                    o.saving.text = "−$pct%"
                } else {
                    o.file = file
                    o.size.text = fmt(original)
                    o.saving.text = "already small"
                    o.saving.setTextColor(ContextCompat.getColor(a, R.color.md_on_surface_variant))
                }
                refresh()
            }
        }
        sheet.setOnDismissListener { job.cancel() }

        shareBtn.setOnClickListener {
            val f = chosen.file ?: return@setOnClickListener
            sheet.dismiss()
            Storage.sharePdf(a, f)
        }
        sheet.show()
    }

    /** Theme colours used by the cards. */
    private class MaterialColors(private val a: AppCompatActivity) {
        private fun attr(id: Int, fallback: Int): Int {
            val tv = android.util.TypedValue()
            return if (a.theme.resolveAttribute(id, tv, true)) {
                if (tv.resourceId != 0) ContextCompat.getColor(a, tv.resourceId) else tv.data
            } else fallback
        }
        val outline = attr(com.google.android.material.R.attr.colorOutline, 0xFFC3C8D6.toInt())
        val surface = attr(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val selected = attr(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFDCE4FF.toInt())
    }
}
