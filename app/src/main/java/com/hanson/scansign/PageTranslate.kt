package com.hanson.scansign

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Translated page view (like a camera translator): the page picture with every paragraph
 * replaced by its translation, in the same place, colour and size. Documents of up to
 * [MAX_PAGES] pages can be paged through and saved as a new translated PDF; longer
 * documents translate the page being read.
 */
object PageTranslate {

    const val MAX_PAGES = 10
    private const val AUTO = "auto"

    /** A page to translate: its picture, its text layout and its size in points. */
    class Source(val bitmap: Bitmap, val layout: PageLayout?, val wPt: Float, val hPt: Float)

    /** Result for one page: the translated picture (or null) and a note for the user. */
    private class Result(val src: Source?, val translated: Bitmap?, val from: String?, val note: String?)

    fun show(
        a: AppCompatActivity,
        docName: String,
        pageCount: Int,
        current: Int,
        load: suspend (Int) -> Source?,
        pageText: suspend (Int) -> String,
        openPdf: (File) -> Unit
    ) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val pages: List<Int> = if (pageCount <= MAX_PAGES) (0 until pageCount).toList() else listOf(current)
        var idx = pages.indexOf(current).coerceAtLeast(0)

        val surface = ContextCompat.getColor(a, R.color.md_surface)
        val onSurface = ContextCompat.getColor(a, R.color.md_on_surface)
        val muted = ContextCompat.getColor(a, R.color.md_on_surface_variant)
        val accent = ContextCompat.getColor(a, Themes.accent(a).primary)

        val dlg = Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(surface) }

        // ---- top bar
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), dp(4f), dp(12f), dp(4f)) }
        val close = ImageButton(a).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(onSurface)
            background = null; contentDescription = "Close"
            setOnClickListener { dlg.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(a).apply { text = "Translated page"; textSize = 18f; setTextColor(onSurface); setTypeface(typeface, Typeface.BOLD) })
        val sub = TextView(a).apply { textSize = 12.5f; setTextColor(muted) }
        titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6f) })
        root.addView(top)

        // ---- languages
        val langs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(8f)) }
        val fromBtn = TranslateSheet.outlined(a)
        val swap = TranslateSheet.plain(a).apply { text = "⇄"; textSize = 20f }
        val toBtn = MaterialButton(a)
        langs.addView(fromBtn, LinearLayout.LayoutParams(0, dp(50f), 1f))
        langs.addView(swap, LinearLayout.LayoutParams(dp(54f), dp(50f)))
        langs.addView(toBtn, LinearLayout.LayoutParams(0, dp(50f), 1f))
        root.addView(langs)

        // ---- page picture
        val stage = FrameLayout(a).apply { setBackgroundColor(Color.rgb(40, 40, 44)) }
        val image = ZoomImage(a)
        stage.addView(image, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(a).apply {
            textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 50 * d; setColor(Color.argb(170, 0, 0, 0)) }
            visibility = View.GONE
        }
        stage.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10f) })
        val busyBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setColor(Color.argb(215, 20, 20, 24)) }
        }
        val spinner = ProgressBar(a)
        busyBox.addView(spinner, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        val status = TextView(a).apply { textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, 0) }
        busyBox.addView(status, LinearLayout.LayoutParams(-2, -2))
        val busyAction = MaterialButton(a).apply { visibility = View.GONE }
        busyBox.addView(busyAction, LinearLayout.LayoutParams(-2, dp(46f)).apply { topMargin = dp(10f) })
        stage.addView(busyBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { marginStart = dp(24f); marginEnd = dp(24f) })
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- bottom bar
        val bottom = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), dp(8f), dp(12f), dp(10f)) }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val prev = TranslateSheet.plain(a).apply { text = "‹"; textSize = 24f; contentDescription = "Previous page" }
        val next = TranslateSheet.plain(a).apply { text = "›"; textSize = 24f; contentDescription = "Next page" }
        val compare = TranslateSheet.outlined(a).apply { text = "Original" }
        val textBtn = TranslateSheet.outlined(a).apply { text = "Text" }
        val shareBtn = TranslateSheet.outlined(a).apply { text = "Share" }
        if (pages.size > 1) row.addView(prev, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        row.addView(compare, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = dp(3f); marginEnd = dp(3f) })
        row.addView(textBtn, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = dp(3f); marginEnd = dp(3f) })
        row.addView(shareBtn, LinearLayout.LayoutParams(0, dp(48f), 1f).apply { marginStart = dp(3f); marginEnd = dp(3f) })
        if (pages.size > 1) row.addView(next, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        bottom.addView(row)
        val saveBtn = MaterialButton(a).apply {
            text = if (pages.size > 1) "Save as new PDF (${pages.size} pages)" else "Save as new PDF"
            setIconResource(R.drawable.ic_pdf)
        }
        bottom.addView(saveBtn, LinearLayout.LayoutParams(-1, dp(52f)).apply { topMargin = dp(8f) })
        if (pageCount > MAX_PAGES) bottom.addView(TextView(a).apply {
            text = "Long document: translating the page you're reading. Whole-document translation works for PDFs of up to $MAX_PAGES pages."
            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(0, dp(6f), 0, 0)
        })
        root.addView(bottom)

        dlg.setContentView(root)
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(surface))
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }

        // ---- state
        var from = AUTO
        var to = Translator.defaultTarget(a)
        var have: Set<String> = setOf("en")
        var haveLoaded = false
        val cache = LinkedHashMap<Int, Result>()   // results for the current languages (a few pages)
        var showing: Result? = null
        var showOriginal = false
        var job: Job? = null

        fun labels(det: String?) {
            fromBtn.text = if (from == AUTO) (det?.let { "${Translator.name(it)} (auto)" } ?: "Detect language") else Translator.name(from)
            toBtn.text = Translator.name(to)
            sub.text = (if (pages.size > 1) "Page ${pages[idx] + 1} of $pageCount" else "Page ${pages[idx] + 1}") + " · offline on your phone"
            prev.isEnabled = idx > 0
            next.isEnabled = idx < pages.size - 1
        }

        fun busy(msg: String?) {
            busyAction.visibility = View.GONE
            if (msg == null) { busyBox.visibility = View.GONE; return }
            busyBox.visibility = View.VISIBLE; spinner.visibility = View.VISIBLE; status.text = msg
        }

        fun note(msg: String) {
            busyBox.visibility = View.VISIBLE; spinner.visibility = View.GONE; status.text = msg
            busyAction.visibility = View.GONE
        }

        fun display() {
            val r = showing ?: return
            val pic = if (showOriginal || r.translated == null) r.src?.bitmap else r.translated
            if (pic != null) image.show(pic)
            compare.text = if (showOriginal) "Translated" else "Original"
            compare.isEnabled = r.translated != null
            badge.visibility = if (r.translated != null) View.VISIBLE else View.GONE
            badge.text = if (showOriginal) "Original" else "Translated to ${Translator.name(to)}"
            labels(r.from)
        }

        /** Asks before a one-time language download. */
        suspend fun askDownload(missing: List<String>): Boolean = suspendCancellableCoroutine { cont ->
            val names = missing.joinToString(" and ") { Translator.name(it) }
            MaterialAlertDialogBuilder(a)
                .setTitle("Download $names?")
                .setMessage("One-time download of about ${Translator.MODEL_MB * missing.size} MB. After that, translation works offline on your phone.")
                .setPositiveButton("Download") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Not now") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
        }

        /** Translates one page and paints the translation onto its picture. */
        suspend fun translate(page: Int, progress: (String) -> Unit): Result {
            cache[page]?.let { return it }
            progress("Reading the page…")
            val src = try { load(page) } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
                ?: return Result(null, null, null, "Couldn't open this page.")
            val blocks = src.layout?.let { lay -> lay.blocks.ifEmpty { lay.lines.map { TextBlock(listOf(it)) } } }.orEmpty()
            val texts = blocks.map { blockText(it) }
            if (texts.all { it == null }) return Result(src, null, null, "No text found on this page.")
            if (!haveLoaded) {
                have = withContext(Dispatchers.IO) { try { Translator.downloaded() } catch (_: Throwable) { setOf("en") } }
                haveLoaded = true
            }
            val lang = (if (from == AUTO) withContext(Dispatchers.IO) { Translator.detect(texts.filterNotNull().joinToString("\n")) } else from)
                ?: return Result(src, null, null, "Couldn't tell which language this page is in — tap the left language button to choose it.")
            if (lang == to) return Result(src, null, lang, "This page is already in ${Translator.name(to)}.")
            val missing = listOf(lang, to).filter { it !in have }.distinct()
            if (missing.isNotEmpty()) {
                if (!askDownload(missing)) return Result(src, null, lang, "${missing.joinToString(" and ") { Translator.name(it) }} isn't downloaded yet.")
                progress("Downloading ${missing.joinToString(" and ") { Translator.name(it) }}…")
                val ok = withContext(Dispatchers.IO) { try { missing.forEach { Translator.download(a, it) }; true } catch (_: Throwable) { false } }
                if (!ok) return Result(src, null, lang,
                    if (Translator.wifiOnly(a)) "Couldn't download — connect to Wi-Fi, or turn off \"Wi-Fi only\" in Manage languages."
                    else "Couldn't download — check your internet connection and try again.")
                have = have + missing
            }
            progress("Translating ${Translator.name(lang)} → ${Translator.name(to)}…")
            val out = withContext(Dispatchers.IO) {
                try { Translator.translateAll(texts, lang, to) } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            } ?: return Result(src, null, lang, "Couldn't translate this page. Please try again.")
            val painted = withContext(Dispatchers.Default) { Painter.paint(src, blocks, out) }
            val r = Result(src, painted, lang, null)
            cache[page] = r
            while (cache.size > 3) cache.remove(cache.keys.first())
            return r
        }

        fun run() {
            job?.cancel()
            labels(null)
            showOriginal = false
            val page = pages[idx]
            job = a.lifecycleScope.launch {
                busy("Reading the page…")
                val r = translate(page) { busy(it) }
                showing = r
                busy(null)
                display()
                if (r.note != null) {
                    note(r.note)
                    if (r.note.contains("isn't downloaded")) {
                        busyAction.visibility = View.VISIBLE; busyAction.text = "Download"
                        busyAction.setOnClickListener { run() }
                    }
                }
            }
        }

        fun changed() { cache.clear(); run() }

        fun pick(title: String, allowAuto: Boolean, current: String, done: (String) -> Unit) {
            val list = Translator.all
            val names = (if (allowAuto) listOf("Detect automatically") else emptyList()) +
                list.map { it.name + if (it.code in have) "  ✓" else "" }
            val codes = (if (allowAuto) listOf(AUTO) else emptyList()) + list.map { it.code }
            MaterialAlertDialogBuilder(a)
                .setTitle(title)
                .setSingleChoiceItems(names.map { it as CharSequence }.toTypedArray(), codes.indexOf(current)) { dd, i -> dd.dismiss(); done(codes[i]) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fromBtn.setOnClickListener { pick("Translate from", true, from) { from = it; changed() } }
        toBtn.setOnClickListener { pick("Translate to  (✓ = downloaded)", false, to) { to = it; Translator.rememberTarget(a, it); changed() } }
        swap.setOnClickListener {
            val src = if (from == AUTO) showing?.from else from
            if (src != null && src != to) { from = to; to = src; Translator.rememberTarget(a, to); changed() }
        }
        prev.setOnClickListener { if (idx > 0) { idx--; run() } }
        next.setOnClickListener { if (idx < pages.size - 1) { idx++; run() } }
        compare.setOnClickListener { showOriginal = !showOriginal; display() }
        image.onHold = { hold -> if (showing?.translated != null) { showOriginal = hold; display() } }
        textBtn.setOnClickListener { val page = pages[idx]; TranslateSheet.show(a, "Page ${page + 1}") { pageText(page) } }
        shareBtn.setOnClickListener {
            val r = showing ?: return@setOnClickListener
            val pic = r.translated ?: r.src?.bitmap ?: return@setOnClickListener
            a.lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) {
                    try {
                        val dir = File(a.cacheDir, "translated").apply { mkdirs() }
                        File(dir, "page-${pages[idx] + 1}-${to}.jpg").also { f -> f.outputStream().use { pic.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                    } catch (_: Throwable) { null }
                } ?: return@launch
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, Storage.uriFor(a, f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share translated page"))
            }
        }

        saveBtn.setOnClickListener {
            job?.cancel()
            job = a.lifecycleScope.launch {
                saveBtn.isEnabled = false
                try {
                    val pd = PDDocument()
                    var translatedAny = false
                    try {
                        for ((n, page) in pages.withIndex()) {
                            val tag = if (pages.size > 1) "Page ${n + 1} of ${pages.size}: " else ""
                            busy("${tag}reading…")
                            val r = translate(page) { busy(tag + it.replaceFirstChar { c -> c.lowercase() }) }
                            if (r.note != null && r.note.contains("isn't downloaded")) { busy(null); note(r.note); return@launch }
                            val src = r.src ?: continue
                            val pic = r.translated ?: src.bitmap
                            if (r.translated != null) translatedAny = true
                            withContext(Dispatchers.IO) {
                                val pg = PDPage(PDRectangle(src.wPt, src.hPt))
                                pd.addPage(pg)
                                val img = JPEGFactory.createFromImage(pd, pic, 0.86f)
                                PDPageContentStream(pd, pg).use { it.drawImage(img, 0f, 0f, src.wPt, src.hPt) }
                            }
                            if (page == pages[idx]) { showing = r; display() }
                        }
                        if (!translatedAny) { busy(null); note("Nothing to translate in this document."); return@launch }
                        busy("Saving…")
                        val base = docName.removeSuffix(".pdf").removeSuffix(".PDF")
                        val file = withContext(Dispatchers.IO) {
                            Storage.uniquePdf(a, "$base (${Translator.name(to)})").also { pd.save(it) }
                        }
                        busy(null)
                        MaterialAlertDialogBuilder(a)
                            .setTitle("Saved")
                            .setMessage("${file.name}\n\nA new PDF with the translated pages. Your original PDF is unchanged.")
                            .setPositiveButton("Open") { _, _ -> dlg.dismiss(); openPdf(file) }
                            .setNeutralButton("Share") { _, _ -> Storage.sharePdf(a, file) }
                            .setNegativeButton("Done", null)
                            .show()
                    } finally {
                        try { pd.close() } catch (_: Throwable) {}
                    }
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    busy(null)
                    note("Couldn't save the PDF. Please try again.")
                } finally {
                    saveBtn.isEnabled = true
                }
            }
        }

        dlg.setOnDismissListener { job?.cancel() }
        dlg.show()
        run()
    }

    /** The text of a paragraph as one string (line-end hyphens joined back up). Null if it has no words. */
    private fun blockText(b: TextBlock): String? {
        val sb = StringBuilder()
        for (l in b.lines) {
            val t = l.text.trim()
            if (t.isEmpty()) continue
            if (sb.endsWith("-") && t[0].isLowerCase()) sb.setLength(sb.length - 1)
            else if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        return if (sb.count { it.isLetter() } >= 2) sb.toString() else null
    }

    /** Paints translations over the page picture. */
    private object Painter {

        fun paint(s: Source, blocks: List<TextBlock>, out: List<String?>): Bitmap {
            val src = s.bitmap
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val W = bmp.width.toFloat(); val H = bmp.height.toFloat()
            val c = Canvas(bmp)
            val fill = Paint().apply { style = Paint.Style.FILL }
            blocks.forEachIndexed { i, b ->
                val t = out.getOrNull(i)?.trim()
                if (t.isNullOrEmpty()) return@forEachIndexed
                val r = b.rect
                val box = RectF(r.left * W, r.top * H, r.right * W, r.bottom * H)
                if (box.width() < 2 || box.height() < 2) return@forEachIndexed
                val lineH = b.lines.map { (it.bottom - it.top) * H }.sorted().let { it[it.size / 2] }.coerceAtLeast(4f)
                val pad = max(2f, lineH * 0.14f)
                val cover = RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad).also {
                    it.left = max(0f, it.left); it.top = max(0f, it.top); it.right = min(W, it.right); it.bottom = min(H, it.bottom)
                }
                val bg = edgeColor(src, cover)
                val ink = inkColor(src, box, bg)

                // size, style and alignment of the original
                val sizes = b.lines.map { it.sizePt }.filter { it > 0 }.sorted()
                var base = if (sizes.isNotEmpty()) sizes[sizes.size / 2] / s.wPt * W else lineH * 0.8f
                base = base.coerceIn(lineH * 0.45f, lineH * 1.25f)
                val bold = b.lines.count { it.bold } * 2 > b.lines.size
                val italic = b.lines.count { it.italic } * 2 > b.lines.size
                val family = when (b.lines[0].font) {
                    TextFont.SERIF -> Typeface.SERIF; TextFont.MONO -> Typeface.MONOSPACE; else -> Typeface.SANS_SERIF
                }
                val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(family, style) }
                val align = alignment(b, W)
                val n = b.lines.size
                val pitch = if (n > 1) (b.lines[n - 1].baseline - b.lines[0].baseline) / (n - 1) * H else 0f
                tp.textSize = base
                val mult = if (pitch > 0) (pitch / tp.fontSpacing).coerceIn(0.9f, 1.6f) else 1f
                val width = max(box.width(), base * 2).toInt().coerceAtLeast(1)

                // shrink the text until it fits the paragraph's box
                var size = base
                var lay: StaticLayout
                while (true) {
                    tp.textSize = size
                    lay = StaticLayout.Builder.obtain(t, 0, t.length, tp, width)
                        .setAlignment(align).setLineSpacing(0f, mult).setIncludePad(false).build()
                    if (lay.height <= cover.height() || size <= base * 0.5f) break
                    size *= 0.93f
                }
                val y = if (lay.height <= box.height()) box.top + (box.height() - lay.height) / 2 else box.top - pad * 0.5f
                fill.color = bg
                c.drawRect(cover.left, cover.top, max(cover.right, box.left + width + pad), max(cover.bottom, y + lay.height + pad), fill)
                c.save()
                c.translate(box.left, y)
                lay.draw(c)
                c.restore()
            }
            return bmp
        }

        private fun alignment(b: TextBlock, W: Float): Layout.Alignment {
            val rects = b.lines.map { it.rect }
            if (rects.size == 1) {
                val r = rects[0]
                return if (abs(r.centerX() - 0.5f) < 0.03f && r.width() < 0.7f && r.left > 0.15f) Layout.Alignment.ALIGN_CENTER
                else Layout.Alignment.ALIGN_NORMAL
            }
            val lefts = rects.map { it.left }; val rights = rects.map { it.right }; val centers = rects.map { it.centerX() }
            fun spread(v: List<Float>) = (v.maxOrNull() ?: 0f) - (v.minOrNull() ?: 0f)
            val tol = 8f / W
            return when {
                spread(lefts) <= tol -> Layout.Alignment.ALIGN_NORMAL
                spread(centers) <= tol * 2 -> Layout.Alignment.ALIGN_CENTER
                spread(rights) <= tol -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
        }

        /** Background colour: the middle colour of the pixels around the box. */
        private fun edgeColor(b: Bitmap, r: RectF): Int {
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            fun take(x: Float, y: Float) {
                val px = b.getPixel(x.toInt().coerceIn(0, b.width - 1), y.toInt().coerceIn(0, b.height - 1))
                rs.add(Color.red(px)); gs.add(Color.green(px)); bs.add(Color.blue(px))
            }
            val o = 1.5f
            val steps = 40
            for (k in 0..steps) {
                val x = r.left + r.width() * k / steps
                val y = r.top + r.height() * k / steps
                take(x, r.top - o); take(x, r.bottom + o); take(r.left - o, y); take(r.right + o, y)
            }
            rs.sort(); gs.sort(); bs.sort()
            return Color.rgb(rs[rs.size / 2], gs[gs.size / 2], bs[bs.size / 2])
        }

        /** Text colour: the average of the pixels inside the box that differ most from the background. */
        private fun inkColor(b: Bitmap, r: RectF, bg: Int): Int {
            val step = max(1, sqrt((r.width() * r.height()) / 3000f).toInt())
            val br = Color.red(bg); val bgG = Color.green(bg); val bb = Color.blue(bg)
            val picks = ArrayList<Pair<Int, Int>>()
            var y = r.top.toInt().coerceAtLeast(0)
            while (y < min(b.height, r.bottom.toInt())) {
                var x = r.left.toInt().coerceAtLeast(0)
                while (x < min(b.width, r.right.toInt())) {
                    val p = b.getPixel(x, y)
                    val dist = abs(Color.red(p) - br) + abs(Color.green(p) - bgG) + abs(Color.blue(p) - bb)
                    if (dist > 90) picks.add(dist to p)
                    x += step
                }
                y += step
            }
            val lum = 0.299 * br + 0.587 * bgG + 0.114 * bb
            if (picks.size < 5) return if (lum > 128) Color.rgb(20, 20, 20) else Color.WHITE
            picks.sortByDescending { it.first }
            val top = picks.subList(0, max(5, picks.size / 4))
            return Color.rgb(top.sumOf { Color.red(it.second) } / top.size, top.sumOf { Color.green(it.second) } / top.size, top.sumOf { Color.blue(it.second) } / top.size)
        }
    }

    /** Picture view with pinch zoom, pan and double-tap; keeps the zoom when the picture is swapped. Hold = [onHold]. */
    private class ZoomImage(ctx: Context) : ImageView(ctx) {
        var onHold: ((Boolean) -> Unit)? = null
        private val m = Matrix()
        private var bw = 0; private var bh = 0
        private var holding = false
        private var fitScale = 1f

        init { scaleType = ScaleType.MATRIX }

        fun show(b: Bitmap) {
            val same = b.width == bw && b.height == bh
            bw = b.width; bh = b.height
            setImageBitmap(b)
            if (!same) fit()
            imageMatrix = m
        }

        private fun fit() {
            if (bw == 0 || width == 0) return
            fitScale = min(width.toFloat() / bw, height.toFloat() / bh)
            m.reset()
            m.postScale(fitScale, fitScale)
            m.postTranslate((width - bw * fitScale) / 2, (height - bh * fitScale) / 2)
            imageMatrix = m
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); fit() }

        private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

        private fun clamp() {
            val r = RectF(0f, 0f, bw.toFloat(), bh.toFloat()); m.mapRect(r)
            val dx = when {
                r.width() <= width -> (width - r.width()) / 2 - r.left
                r.left > 0 -> -r.left
                r.right < width -> width - r.right
                else -> 0f
            }
            val dy = when {
                r.height() <= height -> (height - r.height()) / 2 - r.top
                r.top > 0 -> -r.top
                r.bottom < height -> height - r.bottom
                else -> 0f
            }
            m.postTranslate(dx, dy)
            imageMatrix = m
        }

        private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val cur = scale()
                val f = (cur * det.scaleFactor).coerceIn(fitScale, fitScale * 6) / cur
                m.postScale(f, f, det.focusX, det.focusY); clamp(); return true
            }
        })

        private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { m.postTranslate(-dx, -dy); clamp(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale() > fitScale * 1.05f) fit() else { m.postScale(2.5f, 2.5f, e.x, e.y); clamp() }
                return true
            }
            override fun onLongPress(e: MotionEvent) { holding = true; onHold?.invoke(true) }
        })

        override fun onTouchEvent(e: MotionEvent): Boolean {
            scaler.onTouchEvent(e)
            if (!scaler.isInProgress) gestures.onTouchEvent(e)
            if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && holding) {
                holding = false; onHold?.invoke(false)
            }
            return true
        }
    }
}
