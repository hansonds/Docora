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
        lateinit var bar: com.google.android.material.progressindicator.LinearProgressIndicator
    }

    fun show(a: AppCompatActivity, file: File) = sizeSheet(a, file, file.name, "Share PDF", forCompress = false) { f ->
        Storage.sharePdf(a, f)
    }

    /**
     * Compress picker: shows Light / Medium / Strong with real sizes, then hands the chosen
     * compressed file to [onPick]. [displayName] is shown under the title.
     */
    fun compress(a: AppCompatActivity, file: File, displayName: String, onPick: (File) -> Unit) =
        sizeSheet(a, file, displayName, "Compress PDF", forCompress = true, onPick = onPick)

    private fun sizeSheet(a: AppCompatActivity, file: File, displayName: String, title: String, forCompress: Boolean, onPick: (File) -> Unit) {
        val sheet = BottomSheetDialog(a)
        val view = LayoutInflater.from(a).inflate(R.layout.sheet_share, null)
        sheet.setContentView(view)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        val original = file.length()
        view.findViewById<TextView>(R.id.fileName).text = "$displayName  ·  ${Formatter.formatShortFileSize(a, original)}"
        view.findViewById<TextView>(R.id.sheetTitle).text = title

        val opts = if (forCompress) listOf(
            Option("Light", "Best quality · a little smaller", R.drawable.ic_compress, CompressLevel.LIGHT, false),
            Option("Balanced", "Great quality · much smaller", R.drawable.ic_compress, CompressLevel.MEDIUM, true),
            Option("Small", "Good for email & WhatsApp", R.drawable.ic_compress, CompressLevel.STRONG, false),
            Option("Maximum", "Smallest possible · for strict upload limits", R.drawable.ic_compress, CompressLevel.MAX, false)
        ) else listOf(
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
            val unchanged = chosen.file == file
            shareBtn.text = when {
                !chosen.ready -> "Preparing…"
                forCompress && unchanged -> "Can't make it smaller"
                forCompress -> "Continue  ·  ${fmt(chosen.file?.length() ?: original)}"
                else -> "Share  ·  ${fmt(chosen.file?.length() ?: original)}"
            }
            shareBtn.isEnabled = chosen.ready && !(forCompress && unchanged)
        }

        for (o in opts) {
            val row = LayoutInflater.from(a).inflate(R.layout.item_share_option, box, false)
            o.card = row.findViewById(R.id.card)
            o.size = row.findViewById(R.id.size)
            o.saving = row.findViewById(R.id.saving)
            o.spinner = row.findViewById(R.id.spinner)
            o.bar = row.findViewById(R.id.sizeBar)
            row.findViewById<TextView>(R.id.title).text = o.title
            row.findViewById<TextView>(R.id.subtitle).text = o.sub
            row.findViewById<View>(R.id.badge).visibility = if (o.recommended) View.VISIBLE else View.GONE
            IconTiles.accentFeature(row.findViewById(R.id.iconBox), row.findViewById(R.id.icon), o.icon)
            row.findViewById<ImageView>(R.id.icon).apply {
                val sc = when (o.level) { CompressLevel.MEDIUM -> 0.88f; CompressLevel.STRONG -> 0.76f; CompressLevel.MAX -> 0.66f; else -> 1f }
                scaleX = sc; scaleY = sc
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
                val out = File(dir, if (forCompress) "c_${o.level!!.name.lowercase()}_${System.nanoTime()}.pdf"
                    else "${o.level!!.name.lowercase()}_${file.name}")
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
                    o.bar.visibility = View.VISIBLE
                    o.bar.setProgressCompat((100 - pct).coerceAtLeast(2), true)
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
            onPick(f)
        }
        sheet.show()
    }

    /**
     * "Compressed!" screen: how much smaller the file got, before / after bars, and what to do next.
     */
    fun compressedResult(a: AppCompatActivity, before: Long, out: File, exported: Boolean) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        fun fmt(b: Long) = Formatter.formatShortFileSize(a, b)
        val after = out.length()
        val pct = if (before > 0) ((1 - after.toDouble() / before) * 100).toInt().coerceIn(0, 99) else 0
        val accent = ContextCompat.getColor(a, Themes.accent(a).primary)
        val sheet = BottomSheetDialog(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(22f), dp(8f), dp(22f), dp(22f))
        }
        // big "−62%" circle
        val circle = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(IconTiles.Hue.GREEN.top, IconTiles.Hue.TEAL.bottom)
            ).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
            elevation = 6 * d
        }
        circle.addView(TextView(a).apply {
            text = "−$pct%"; textSize = 32f; setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        circle.addView(TextView(a).apply { text = "smaller"; textSize = 13f; setTextColor(android.graphics.Color.WHITE); alpha = 0.9f })
        root.addView(circle, LinearLayout.LayoutParams(dp(132f), dp(132f)).apply { topMargin = dp(10f) })
        root.addView(TextView(a).apply {
            text = "Compressed!"; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(14f), 0, 0)
        })
        root.addView(TextView(a).apply {
            text = "You saved ${fmt((before - after).coerceAtLeast(0))}"
            textSize = 14f; setTextColor(ContextCompat.getColor(a, R.color.md_on_surface_variant))
        })
        // before / after bars
        fun barRow(label: String, size: Long, frac: Float, color: Int) {
            val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12f), 0, 0) }
            val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(TextView(a).apply { text = label; textSize = 13f }, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(TextView(a).apply { text = fmt(size); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            row.addView(top)
            val track = android.widget.FrameLayout(a).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6 * d; setColor(ContextCompat.getColor(a, R.color.md_surface_variant))
                }
            }
            val fill = View(a).apply {
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 6 * d; setColor(color) }
            }
            track.addView(fill, android.widget.FrameLayout.LayoutParams(0, -1))
            track.post {
                fill.layoutParams = fill.layoutParams.apply { width = (track.width * frac.coerceIn(0.02f, 1f)).toInt() }
            }
            row.addView(track, LinearLayout.LayoutParams(-1, dp(12f)).apply { topMargin = dp(6f) })
            root.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        barRow("Before", before, 1f, android.graphics.Color.rgb(160, 168, 184))
        barRow("After", after, if (before > 0) after.toFloat() / before else 1f, accent)
        root.addView(TextView(a).apply {
            text = out.name + if (exported) "\nSaved in the app and in Downloads › Docora" else "\nSaved in the app"
            textSize = 12.5f; gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(a, R.color.md_on_surface_variant))
            setPadding(0, dp(16f), 0, 0)
        })
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(16f), 0, 0) }
        fun button(label: String, icon: Int, primary: Boolean, onClick: () -> Unit) {
            val b = MaterialButton(a)
            if (!primary) {
                // softer second button: tinted background, accent text
                b.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(a, R.color.md_surface_variant))
                b.setTextColor(accent)
                b.iconTint = ColorStateList.valueOf(accent)
            }
            b.text = label
            b.setIconResource(icon)
            b.setOnClickListener { sheet.dismiss(); onClick() }
            buttons.addView(b, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginStart = dp(4f); marginEnd = dp(4f) })
        }
        button("Open", R.drawable.ic_pdf, false) { a.startActivity(PdfEditorActivity.intent(a, out)) }
        button("Share", R.drawable.ic_share, true) { Storage.sharePdf(a, out) }
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        sheet.setContentView(root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
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
