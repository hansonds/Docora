package com.hanson.scansign

import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Hub for all PDF tools (all free). */
class ToolsActivity : BaseActivity() {

    enum class Tool(val title: String, val sub: String, val icon: Int, val bg: Int, val fg: Int, val section: String) {
        SIGN("Sign & edit", "Sign, text, draw, stamps", R.drawable.ic_sign, R.color.icon_tile_4, R.color.brand_purple, "Edit"),
        ORGANIZE("Organize pages", "Reorder, rotate, delete, add", R.drawable.ic_pages, R.color.icon_tile_1, R.color.brand, "Edit"),
        MERGE("Merge PDFs", "Combine several files", R.drawable.ic_merge, R.color.icon_tile_2, R.color.md_secondary, "Pages"),
        SPLIT("Split PDF", "Into pages or ranges", R.drawable.ic_split, R.color.icon_tile_3, R.color.danger, "Pages"),
        EXTRACT("Extract pages", "Pick pages into a new file", R.drawable.ic_pdf, R.color.icon_tile_1, R.color.brand, "Pages"),
        COMPRESS("Compress", "Make the file smaller", R.drawable.ic_compress, R.color.icon_tile_2, R.color.md_secondary, "Optimize"),
        TO_JPG("PDF to JPG", "Save pages as images", R.drawable.ic_jpg, R.color.icon_tile_3, R.color.danger, "Convert"),
        IMAGES("Images to PDF", "Photos into one PDF", R.drawable.ic_photo, R.color.icon_tile_4, R.color.brand_purple, "Convert"),
        OCR_TEXT("Extract text (OCR)", "Copy text from scans", R.drawable.ic_ocr, R.color.icon_tile_1, R.color.brand, "Text recognition"),
        SEARCHABLE("Make searchable", "Add a hidden text layer", R.drawable.ic_search, R.color.icon_tile_2, R.color.md_secondary, "Text recognition"),
        PROTECT("Lock with password", "Protect with AES encryption", R.drawable.ic_lock, R.color.icon_tile_3, R.color.danger, "Security"),
        UNLOCK("Remove password", "Unlock a protected PDF", R.drawable.ic_unlock, R.color.icon_tile_4, R.color.brand_purple, "Security"),
        WATERMARK("Watermark", "Text across every page", R.drawable.ic_watermark, R.color.icon_tile_1, R.color.brand, "Stamp pages"),
        NUMBERS("Page numbers", "Number every page", R.drawable.ic_numbers, R.color.icon_tile_2, R.color.md_secondary, "Stamp pages")
    }

    private var pending: Tool? = null

    private val pickOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val t = pending ?: return@registerForActivityResult
        if (uri != null) loadInputs(t, listOf(uri))
    }

    private val pickMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val t = pending ?: return@registerForActivityResult
        if (uris.isNotEmpty()) loadInputs(t, uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val container = findViewById<LinearLayout>(R.id.container)
        Ui.limitWidth(container, 960)
        val d = resources.displayMetrics.density
        val cols = when {
            Ui.widthDp(this) >= 840 -> 4
            Ui.widthDp(this) >= 600 -> 3
            else -> 2
        }
        val gap = (12 * d).toInt()
        for ((section, tools) in Tool.values().groupBy { it.section }) {
            val header = TextView(this).apply {
                text = section
                setTextAppearance(R.style.TextAppearance_App_Section)
                setPadding(0, (18 * d).toInt(), 0, (8 * d).toInt())
            }
            container.addView(header)
            for (row in tools.chunked(cols)) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                container.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = gap
                })
                // a short last row stretches its tiles so there is no empty gap
                row.forEachIndexed { i, t ->
                    val tile = LayoutInflater.from(this).inflate(R.layout.tile_action, line, false)
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                    if (i > 0) lp.marginStart = gap
                    tile.layoutParams = lp
                    tile.findViewById<View>(R.id.iconBox).backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, t.bg))
                    tile.findViewById<ImageView>(R.id.icon).apply {
                        setImageResource(t.icon)
                        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@ToolsActivity, t.fg))
                    }
                    tile.findViewById<TextView>(R.id.title).text = t.title
                    tile.findViewById<TextView>(R.id.subtitle).text = t.sub
                    tile.setOnClickListener { start(t) }
                    line.addView(tile)
                }
            }
        }
    }

    private fun start(t: Tool) {
        pending = t
        when (t) {
            Tool.IMAGES -> startActivity(ScanActivity.intent(this, ScanActivity.START_GALLERY))
            Tool.MERGE -> pickMany.launch(arrayOf("application/pdf"))
            else -> pickOne.launch(arrayOf("application/pdf"))
        }
    }

    private class Input(val file: File, val name: String) {
        val base: String get() = name.removeSuffix(".pdf").removeSuffix(".PDF")
    }

    private fun loadInputs(t: Tool, uris: List<Uri>) {
        lifecycleScope.launch {
            val inputs = withContext(Dispatchers.IO) {
                uris.mapNotNull { u ->
                    val f = Storage.copyUriToWork(this@ToolsActivity, u, "pdf") ?: return@mapNotNull null
                    Input(f, Storage.displayName(this@ToolsActivity, u) ?: "document.pdf")
                }
            }
            if (inputs.isEmpty()) { Ui.toast(this@ToolsActivity, "Could not open the file"); return@launch }
            run(t, inputs)
        }
    }

    private fun run(t: Tool, inputs: List<Input>) {
        val first = inputs.first()
        when (t) {
            Tool.SIGN -> startActivity(PdfEditorActivity.intent(this, first.file, first.name))
            Tool.ORGANIZE -> startActivity(OrganizeActivity.intent(this, first.file, first.name))
            Tool.MERGE -> merge(inputs)
            Tool.SPLIT -> split(first)
            Tool.EXTRACT -> extract(first)
            Tool.COMPRESS -> compress(first)
            Tool.TO_JPG -> toJpg(first)
            Tool.OCR_TEXT -> ocrText(first)
            Tool.SEARCHABLE -> searchable(first)
            Tool.PROTECT -> protect(first)
            Tool.UNLOCK -> unlock(first)
            Tool.WATERMARK -> watermark(first)
            Tool.NUMBERS -> numbers(first)
            Tool.IMAGES -> {}
        }
    }

    // ---------------- tools ----------------

    private fun saveJob(title: String, outName: String, extra: (File) -> String = { "" }, work: (File, Progress) -> Unit) {
        Ui.runJob(this, title, { p ->
            val out = Storage.uniquePdf(this, outName)
            work(out, p)
            Pair(out, Storage.exportToDownloads(this, out))
        }) { (out, exported) -> Ui.showSaved(this, out, exported, extra(out)) }
    }

    private fun merge(inputs: List<Input>) {
        if (inputs.size < 2) { Ui.toast(this, "Select at least two PDFs (long-press to select several)"); return }
        val order = inputs.toMutableList()
        val names = order.joinToString("\n") { "• ${it.name}" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Merge ${order.size} PDFs")
            .setMessage("They will be joined in this order:\n\n$names")
            .setPositiveButton("Merge") { _, _ ->
                saveJob("Merging", "Merged_${Storage.defaultName("")}".trimEnd('_')) { out, _ -> PdfTools.merge(order.map { it.file }, out) }
            }
            .setNeutralButton("Reverse order") { _, _ -> merge(order.reversed()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun countPages(input: Input, then: (Int) -> Unit) {
        Ui.runJob(this, "Reading", { PdfTools.pageCount(input.file) }, then)
    }

    private fun split(input: Input) = countPages(input) { n ->
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val group = RadioGroup(this)
        val every = RadioButton(this).apply { id = View.generateViewId(); text = "Every page into its own file"; isChecked = true }
        val everyN = RadioButton(this).apply { id = View.generateViewId(); text = "Every N pages" }
        val ranges = RadioButton(this).apply { id = View.generateViewId(); text = "Custom ranges (one file per range)" }
        group.addView(every); group.addView(everyN); group.addView(ranges)
        val input2 = EditText(this).apply { hint = "N = 2   or   ranges: 1-3, 4-6, 7"; inputType = InputType.TYPE_CLASS_TEXT }
        box.addView(TextView(this).apply { text = "${input.name} has $n pages." })
        box.addView(group); box.addView(input2)
        MaterialAlertDialogBuilder(this)
            .setTitle("Split PDF")
            .setView(Ui.padded(this, box))
            .setPositiveButton("Split") { _, _ ->
                val txt = input2.text.toString()
                when (group.checkedRadioButtonId) {
                    everyN.id -> {
                        val k = txt.trim().toIntOrNull()
                        if (k == null || k < 1) { Ui.toast(this, "Enter how many pages per file, e.g. 2"); return@setPositiveButton }
                        splitEvery(input, k)
                    }
                    ranges.id -> {
                        val rs = PdfTools.parseRanges(txt, n)
                        if (rs == null) { Ui.toast(this, "Ranges look wrong. Example: 1-3, 4-6, 7"); return@setPositiveButton }
                        Ui.runJob(this, "Splitting", { p ->
                            rs.mapIndexed { i, r ->
                                val out = Storage.uniquePdf(this, "${input.base}_${r.first + 1}-${r.last + 1}")
                                PdfTools.organize(input.file, r.map { PageOp(it) }, out)
                                Storage.exportToDownloads(this, out)
                                p(i + 1, rs.size)
                                out
                            }
                        }) { files -> doneMany(files) }
                    }
                    else -> splitEvery(input, 1)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun splitEvery(input: Input, k: Int) {
        Ui.runJob(this, "Splitting", { p ->
            PdfTools.splitEvery(this, input.file, k, input.base, p).onEach { Storage.exportToDownloads(this, it) }
        }) { files -> doneMany(files) }
    }

    private fun doneMany(files: List<File>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Done")
            .setMessage("${files.size} PDF files saved.\nThey are in Recent files and in Downloads › PDF ScanSign.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .show()
    }

    private fun extract(input: Input) = countPages(input) { n ->
        val et = EditText(this).apply { hint = "e.g. 1, 3-5, 9"; inputType = InputType.TYPE_CLASS_TEXT }
        MaterialAlertDialogBuilder(this)
            .setTitle("Extract pages (1–$n)")
            .setView(Ui.padded(this, et))
            .setPositiveButton("Extract") { _, _ ->
                val rs = PdfTools.parseRanges(et.text.toString(), n)
                if (rs == null) { Ui.toast(this, "Pages look wrong. Example: 1, 3-5, 9"); return@setPositiveButton }
                val ops = rs.flatMap { r -> r.map { PageOp(it) } }
                saveJob("Extracting", "${input.base}_extract") { out, _ -> PdfTools.organize(input.file, ops, out) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun compress(input: Input) {
        val levels = CompressLevel.values()
        var choice = 1
        MaterialAlertDialogBuilder(this)
            .setTitle("Compress PDF")
            .setSingleChoiceItems(levels.map { it.label as CharSequence }.toTypedArray(), choice) { _, w -> choice = w }
            .setPositiveButton("Compress") { _, _ ->
                val before = input.file.length()
                Ui.runJob(this, "Compressing", { p ->
                    val out = Storage.uniquePdf(this, "${input.base}_compressed")
                    val smaller = PdfTools.compress(input.file, out, levels[choice], p)
                    Triple(out, smaller, Storage.exportToDownloads(this, out))
                }) { (out, smaller, exported) ->
                    val fmt = { b: Long -> android.text.format.Formatter.formatShortFileSize(this, b) }
                    val extra = if (smaller) "\n\n${fmt(before)} → ${fmt(out.length())}"
                    else "\n\nThis PDF has no large images, so it can't get much smaller."
                    Ui.showSaved(this, out, exported, extra)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toJpg(input: Input) {
        val opts = arrayOf<CharSequence>("Normal (150 dpi)", "High (300 dpi)")
        var c = 0
        MaterialAlertDialogBuilder(this)
            .setTitle("PDF to JPG")
            .setSingleChoiceItems(opts, c) { _, w -> c = w }
            .setPositiveButton("Convert") { _, _ ->
                Ui.runJob(this, "Saving images", { p ->
                    PdfTools.toImages(this, input.file, input.base, if (c == 0) 150 else 300, p)
                }) { n ->
                    MaterialAlertDialogBuilder(this).setTitle("Done")
                        .setMessage("$n images saved to Pictures › PDF ScanSign (see your Gallery).")
                        .setPositiveButton("OK", null).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ocrText(input: Input) = countPages(input) { n ->
        startActivity(TextResultActivity.intent(this, input.file, (0 until n).toList(), input.name))
    }

    private fun searchable(input: Input) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Make searchable")
            .setMessage("Text on every page is recognised on your phone and added as an invisible layer, so you can search and copy it. Language: ${Ocr.script(this).label} (change in Settings).")
            .setPositiveButton("Start") { _, _ ->
                saveJob("Recognising text", "${input.base}_searchable") { out, p ->
                    PdfTools.makeSearchable(this, input.file, input.file, out, p)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun passwordField(hint: String) = EditText(this).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun protect(input: Input) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val p1 = passwordField("Password"); val p2 = passwordField("Repeat password")
        box.addView(p1); box.addView(p2)
        box.addView(TextView(this).apply { text = "Don't forget it — a lost password can't be recovered."; alpha = 0.7f })
        MaterialAlertDialogBuilder(this)
            .setTitle("Lock with password")
            .setView(Ui.padded(this, box))
            .setPositiveButton("Lock") { _, _ ->
                val a = p1.text.toString()
                if (a.length < 4) { Ui.toast(this, "Use at least 4 characters"); return@setPositiveButton }
                if (a != p2.text.toString()) { Ui.toast(this, "The passwords don't match"); return@setPositiveButton }
                saveJob("Encrypting", "${input.base}_locked") { out, _ -> PdfTools.protect(input.file, out, a) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unlock(input: Input) {
        val p = passwordField("Current password")
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove password")
            .setView(Ui.padded(this, p))
            .setPositiveButton("Unlock") { _, _ ->
                saveJob("Unlocking", "${input.base}_unlocked") { out, _ -> PdfTools.unlock(input.file, out, p.text.toString()) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun watermark(input: Input) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val text = EditText(this).apply { setText("CONFIDENTIAL"); setSelectAllOnFocus(true) }
        val colors = listOf("Grey" to Color.rgb(120, 120, 120), "Red" to Color.rgb(205, 30, 40), "Blue" to Color.rgb(30, 70, 200))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        colors.forEachIndexed { i, (n, _) ->
            group.addView(RadioButton(this).apply { id = 1000 + i; this.text = n; isChecked = i == 0 })
        }
        val opacity = Slider(this).apply { valueFrom = 0.1f; valueTo = 0.8f; value = 0.25f; stepSize = 0.05f }
        val size = Slider(this).apply { valueFrom = 20f; valueTo = 120f; value = 60f; stepSize = 2f }
        val diag = CheckBox(this).apply { this.text = "Diagonal"; isChecked = true }
        box.addView(text); box.addView(group)
        box.addView(TextView(this).apply { this.text = "Opacity" }); box.addView(opacity)
        box.addView(TextView(this).apply { this.text = "Size" }); box.addView(size)
        box.addView(diag)
        MaterialAlertDialogBuilder(this)
            .setTitle("Watermark")
            .setView(Ui.padded(this, box))
            .setPositiveButton("Apply") { _, _ ->
                val t = text.text.toString().trim()
                if (t.isEmpty()) return@setPositiveButton
                val color = colors[(group.checkedRadioButtonId - 1000).coerceIn(0, colors.size - 1)].second
                saveJob("Adding watermark", "${input.base}_watermark") { out, p ->
                    PdfTools.watermark(input.file, out, t, color, opacity.value, size.value, diag.isChecked, p)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun numbers(input: Input) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pos = RadioGroup(this)
        PdfTools.NumberPos.values().forEachIndexed { i, p ->
            pos.addView(RadioButton(this).apply { id = 2000 + i; text = p.label; isChecked = i == 0 })
        }
        val fmt = RadioGroup(this)
        PdfTools.NumberFormat.values().forEachIndexed { i, f ->
            fmt.addView(RadioButton(this).apply { id = 3000 + i; text = f.label; isChecked = i == 1 })
        }
        val start = EditText(this).apply { setText("1"); inputType = InputType.TYPE_CLASS_NUMBER; hint = "Start at" }
        box.addView(TextView(this).apply { text = "Position" }); box.addView(pos)
        box.addView(TextView(this).apply { text = "Format" }); box.addView(fmt)
        box.addView(TextView(this).apply { text = "First number" }); box.addView(start)
        MaterialAlertDialogBuilder(this)
            .setTitle("Page numbers")
            .setView(Ui.padded(this, box))
            .setPositiveButton("Apply") { _, _ ->
                val p = PdfTools.NumberPos.values()[(pos.checkedRadioButtonId - 2000).coerceIn(0, 2)]
                val f = PdfTools.NumberFormat.values()[(fmt.checkedRadioButtonId - 3000).coerceIn(0, 2)]
                val s = start.text.toString().toIntOrNull() ?: 1
                saveJob("Numbering pages", "${input.base}_numbered") { out, pr -> PdfTools.pageNumbers(input.file, out, p, f, s, pr) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
