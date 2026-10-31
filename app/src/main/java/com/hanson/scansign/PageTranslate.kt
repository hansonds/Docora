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
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
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

    const val MAX_PAGES = 40
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

        // ---- top bar: back · title · page pill
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4f), dp(6f), dp(14f), dp(6f)) }
        val close = ImageButton(a).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(onSurface)
            background = round(a, Color.TRANSPARENT, 24f); contentDescription = "Close"
            setOnClickListener { dlg.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(48f), dp(48f)))
        val titles = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(a).apply { text = "Translate page"; textSize = 19f; setTextColor(onSurface); setTypeface(typeface, Typeface.BOLD); maxLines = 1 })
        val sub = TextView(a).apply { text = "Offline · on your phone"; textSize = 12.5f; setTextColor(muted); maxLines = 1 }
        titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6f) })
        val pagePill = TextView(a).apply {
            textSize = 13f; setTextColor(accent); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            background = round(a, (accent and 0x00FFFFFF) or 0x22000000, 50f)
        }
        top.addView(pagePill, LinearLayout.LayoutParams(-2, -2))
        root.addView(top)

        // ---- languages: [From ▾]  ⇄  [To ▾] in one rounded bar
        val langBar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = round(a, ContextCompat.getColor(a, R.color.md_surface_variant), 22f)
        }
        fun langChip(caption: String): Pair<LinearLayout, TextView> {
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(dp(10f), dp(5f), dp(10f), dp(6f))
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22000000), round(a, surface, 16f), null)
            }
            box.addView(TextView(a).apply { text = caption; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER })
            val name = TextView(a).apply {
                textSize = 15f; setTextColor(onSurface); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, tinted(a, R.drawable.ic_chevron_down, muted, 16f), null)
                compoundDrawablePadding = dp(2f)
            }
            box.addView(name, LinearLayout.LayoutParams(-2, -2))
            return box to name
        }
        val (fromBtn, fromName) = langChip("From")
        val (toBtn, toName) = langChip("To")
        val swap = ImageButton(a).apply {
            setImageResource(R.drawable.ic_swap); setColorFilter(Color.WHITE); contentDescription = "Swap languages"
            background = round(a, accent, 22f)
        }
        langBar.addView(fromBtn, LinearLayout.LayoutParams(0, dp(54f), 1f))
        langBar.addView(swap, LinearLayout.LayoutParams(dp(44f), dp(44f)).apply { marginStart = dp(8f); marginEnd = dp(8f) })
        langBar.addView(toBtn, LinearLayout.LayoutParams(0, dp(54f), 1f))
        root.addView(langBar, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(12f); marginEnd = dp(12f); bottomMargin = dp(10f) })

        // ---- page picture
        val stage = FrameLayout(a).apply { setBackgroundColor(Color.rgb(38, 38, 42)) }
        val image = ZoomImage(a)
        stage.addView(image, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(a).apply {
            textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            background = round(a, Color.argb(175, 0, 0, 0), 50f)
            visibility = View.GONE
        }
        stage.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12f) })
        val hint = TextView(a).apply {
            text = "Hold the page to see the original"; textSize = 11.5f; setTextColor(Color.WHITE)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = round(a, Color.argb(140, 0, 0, 0), 50f)
            visibility = View.GONE
        }
        stage.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12f) })
        val busyBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(18f))
            background = round(a, Color.argb(225, 22, 22, 26), 20f)
        }
        val spinner = ProgressBar(a)
        busyBox.addView(spinner, LinearLayout.LayoutParams(dp(36f), dp(36f)))
        val status = TextView(a).apply { textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0) }
        busyBox.addView(status, LinearLayout.LayoutParams(-2, -2))
        val busyAction = MaterialButton(a).apply { visibility = View.GONE }
        busyBox.addView(busyAction, LinearLayout.LayoutParams(-2, dp(46f)).apply { topMargin = dp(12f) })
        stage.addView(busyBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { marginStart = dp(28f); marginEnd = dp(28f) })
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- bottom: evenly spaced tools (same look as the editor dock) + save
        val bottom = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), dp(6f), dp(10f), dp(12f)) }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun tool(label: String, icon: Int): View {
            val v = android.view.LayoutInflater.from(a).inflate(R.layout.view_dock_tool, row, false)
            v.findViewById<ImageView>(R.id.toolIcon).setImageResource(icon)
            v.findViewById<TextView>(R.id.toolLabel).text = label
            v.contentDescription = label
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
            return v
        }
        val prev = if (pages.size > 1) tool("Previous", R.drawable.ic_chevron_left) else null
        val compare = tool("Original", R.drawable.ic_compare)
        val textBtn = tool("Text", R.drawable.ic_text)
        val shareBtn = tool("Share", R.drawable.ic_share)
        val next = if (pages.size > 1) tool("Next", R.drawable.ic_chevron_right) else null
        fun setLabel(v: View, s: String) { v.findViewById<TextView>(R.id.toolLabel).text = s; v.contentDescription = s }
        fun enable(v: View?, on: Boolean) { v ?: return; v.isEnabled = on; v.alpha = if (on) 1f else 0.38f }
        bottom.addView(row)
        val saveBtn = MaterialButton(a).apply {
            text = if (pages.size > 1) "Save as new PDF  ·  ${pages.size} pages" else "Save as new PDF"
            setIconResource(R.drawable.ic_save_as)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            cornerRadius = dp(26f)
        }
        bottom.addView(saveBtn, LinearLayout.LayoutParams(-1, dp(52f)).apply { topMargin = dp(6f) })
        if (pageCount > MAX_PAGES) bottom.addView(TextView(a).apply {
            text = "Long document: only this page is translated. Whole documents can be translated up to $MAX_PAGES pages."
            textSize = 12f; setTextColor(muted); gravity = Gravity.CENTER; setPadding(dp(8f), dp(8f), dp(8f), 0)
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
        val ahead = HashMap<Int, kotlinx.coroutines.Deferred<Result?>>()
        val session = Translator.Session()

        fun labels(det: String?) {
            fromName.text = if (from == AUTO) (det?.let { "${Translator.name(it)} (auto)" } ?: "Detect language") else Translator.name(from)
            toName.text = Translator.name(to)
            pagePill.text = if (pages.size > 1) "${idx + 1} / ${pages.size}" else "Page ${pages[idx] + 1}"
            enable(prev, idx > 0)
            enable(next, idx < pages.size - 1)
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
            setLabel(compare, if (showOriginal) "Translated" else "Original")
            enable(compare, r.translated != null)
            badge.visibility = if (r.translated != null) View.VISIBLE else View.GONE
            if (r.translated != null && !showOriginal && hint.tag == null) {
                hint.tag = true; hint.visibility = View.VISIBLE
                hint.postDelayed({ hint.visibility = View.GONE }, 4000)
            }
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

        /**
         * Translates one page and paints the translation onto its picture. Each paragraph's own
         * language is detected, so mixed-language pages are translated completely.
         * Not [interactive] (reading ahead): never asks to download, just gives up.
         */
        suspend fun translate(page: Int, interactive: Boolean, progress: (String) -> Unit): Result {
            synchronized(cache) { cache[page] }?.let { return it }
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
            // language of the page, and of each longer paragraph
            val (pageLang, own) = withContext(Dispatchers.IO) {
                if (from != AUTO) Pair(from, texts.map { from })
                else {
                    // every paragraph votes for the page's language; longer, surer paragraphs count more
                    val page = Translator.detectPage(texts)
                    val each = Translator.guessAll(texts).mapIndexed { k, g ->
                        val letters = texts[k]?.count { c -> c.isLetter() } ?: 0
                        // short or unsure paragraphs follow the page
                        g.code?.takeIf { letters >= 15 && (g.confidence >= 0.5f || letters >= 60) }
                    }
                    Pair(page.code, each)
                }
            }
            val lang: String = pageLang ?: own.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                ?: return Result(src, null, null, Translator.unsupportedScript(texts.filterNotNull().joinToString(" "))
                    ?.let { "$it text can't be translated on the phone yet — Google's offline translator doesn't support it." }
                    ?: "Couldn't tell which language this page is in — tap the left language button to choose it.")
            // paragraphs in another language are translated from that language (when it's worth a download)
            val longOther = texts.indices.mapNotNull { k ->
                own[k]?.takeIf { it != lang && it != to && (texts[k]?.count { c -> c.isLetter() } ?: 0) >= 40 }
            }.toSet()
            val missing = (listOf(lang, to) + longOther).filter { it !in have }.distinct()
                .filter { it == lang || it == to || interactive }
            if (lang == to && longOther.isEmpty()) return Result(src, null, lang, "This page is already in ${Translator.name(to)}.")
            if (missing.isNotEmpty()) {
                if (!interactive) return Result(src, null, lang, null)
                if (!askDownload(missing)) {
                    if (lang in missing || to in missing)
                        return Result(src, null, lang, "${missing.joinToString(" and ") { Translator.name(it) }} isn't downloaded yet.")
                } else {
                    progress("Downloading ${missing.joinToString(" and ") { Translator.name(it) }}…")
                    val problem = withContext(Dispatchers.IO) {
                        try {
                            missing.forEach { code ->
                                Translator.download(a, code,
                                    status = { s -> a.runOnUiThread { progress("Downloading ${Translator.name(code)}\n$s") } },
                                    keepGoing = { isActive && dlg.isShowing })
                            }
                            null
                        } catch (e: Throwable) { e }
                    }
                    if (problem != null && (lang in missing || to in missing)) {
                        if ((problem as? Translator.DownloadProblem)?.openDownloadManager == true) Translator.openDownloadManagerSettings(a)
                        return Result(src, null, lang, "${missing.joinToString(" and ") { Translator.name(it) }} isn't downloaded yet.\n\n" +
                            ((problem as? Translator.DownloadProblem)?.message ?: "Check your internet connection and try again."))
                    }
                    if (problem == null) have = have + missing
                }
            }
            val langs = texts.indices.map { k ->
                val o = own[k]
                when {
                    texts[k] == null -> null
                    o != null && o == to && (texts[k]?.count { c -> c.isLetter() } ?: 0) >= 15 -> null   // already in the target language
                    o != null && o in have -> o
                    else -> lang
                }
            }
            progress("Translating ${Translator.name(lang)} → ${Translator.name(to)}…")
            val out = withContext(Dispatchers.IO) {
                try {
                    texts.indices.map { k ->
                        val t = texts[k]; val l = langs[k]
                        if (t == null || l == null || l == to) null else session.translate(t, l, to)
                    }
                } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            } ?: return Result(src, null, lang, "Couldn't translate this page. Please try again.")
            if (out.all { it == null }) return Result(src, null, lang, "This page is already in ${Translator.name(to)}.")
            val painted = withContext(Dispatchers.Default) { Painter.paint(src, blocks, out) }
            val r = Result(src, painted, lang, null)
            synchronized(cache) {
                cache[page] = r
                while (cache.size > 4) cache.remove(cache.keys.first())
            }
            return r
        }

        /** Reads ahead: translates the next page in the background while this one is read. */
        fun prefetch() {
            if (idx + 1 >= pages.size) return
            val page = pages[idx + 1]
            if (synchronized(cache) { cache.containsKey(page) } || ahead.containsKey(page)) return
            ahead[page] = a.lifecycleScope.async {
                try { translate(page, false) {} } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; null }
            }
        }

        fun run() {
            job?.cancel()
            labels(null)
            showOriginal = false
            val page = pages[idx]
            job = a.lifecycleScope.launch {
                busy("Reading the page…")
                val early = ahead.remove(page)?.let { d -> busy("Translating…"); try { d.await() } catch (e: Throwable) { if (e is kotlinx.coroutines.CancellationException) throw e; null } }
                val r = early?.takeIf { it.translated != null } ?: translate(page, true) { busy(it) }
                showing = r
                busy(null)
                display()
                if (r.note != null) {
                    note(r.note)
                    if (r.note.contains("isn't downloaded")) {
                        busyAction.visibility = View.VISIBLE; busyAction.text = "Download"
                        busyAction.setOnClickListener { run() }
                    }
                } else prefetch()
            }
        }

        fun changed() {
            ahead.values.forEach { it.cancel() }; ahead.clear()
            synchronized(cache) { cache.clear() }
            run()
        }

        fun pick(title: String, allowAuto: Boolean, current: String, done: (String) -> Unit) =
            TranslateSheet.pickLanguage(a, title, allowAuto, current, have, done)

        fromBtn.setOnClickListener { pick("Translate from", true, from) { from = it; changed() } }
        toBtn.setOnClickListener { pick("Translate to", false, to) { to = it; Translator.rememberTarget(a, it); changed() } }
        swap.setOnClickListener {
            val src = if (from == AUTO) showing?.from else from
            if (src != null && src != to) { from = to; to = src; Translator.rememberTarget(a, to); changed() }
        }
        prev?.setOnClickListener { if (idx > 0) { idx--; run() } }
        next?.setOnClickListener { if (idx < pages.size - 1) { idx++; run() } }
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

        var saving = false
        val saveLabel = saveBtn.text
        saveBtn.setOnClickListener {
            if (saving) { job?.cancel(); return@setOnClickListener }   // "Stop"
            job?.cancel()
            ahead.values.forEach { it.cancel() }; ahead.clear()
            job = a.lifecycleScope.launch {
                saving = true
                saveBtn.text = "Stop"
                saveBtn.setIconResource(R.drawable.ic_close)
                // keep the screen on while a long document is translated
                dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val started = System.currentTimeMillis()
                try {
                    // finished pages go to a temporary file instead of memory, so long documents fit on any phone
                    val pd = PDDocument(PdfOpener.memory())
                    var translatedAny = false
                    try {
                        for ((n, page) in pages.withIndex()) {
                            val left = if (n == 0) "" else {
                                val sec = ((System.currentTimeMillis() - started) / n * (pages.size - n) / 1000).toInt()
                                if (sec < 60) " · less than a minute left" else " · about ${(sec + 30) / 60} min left"
                            }
                            val tag = if (pages.size > 1) "Page ${n + 1} of ${pages.size}$left\n" else ""
                            busy("${tag}Reading the page…")
                            val r = translate(page, true) { busy(tag + it) }
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
                    if (e is kotlinx.coroutines.CancellationException) { busy(null); note("Stopped. Nothing was saved."); throw e }
                    busy(null)
                    note("Couldn't save the PDF. Please try again.")
                } finally {
                    saving = false
                    saveBtn.text = saveLabel
                    saveBtn.setIconResource(R.drawable.ic_save_as)
                    dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        dlg.setOnDismissListener {
            job?.cancel()
            ahead.values.forEach { it.cancel() }; ahead.clear()
            Thread { session.close() }.start()
        }
        dlg.show()
        run()
    }

    private fun round(a: Context, color: Int, radiusDp: Float) = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = radiusDp * a.resources.displayMetrics.density; setColor(color)
    }

    private fun tinted(a: Context, res: Int, color: Int, sizeDp: Float) = ContextCompat.getDrawable(a, res)?.mutate()?.apply {
        setTint(color)
        val px = (sizeDp * a.resources.displayMetrics.density).toInt()
        setBounds(0, 0, px, px)
    }

    private val ITEM = Regex("^(?:[•●○◦▪■□‣⁃∙·*\\-–—]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)])\\s")

    /**
     * The text of a paragraph: wrapped lines joined into sentences (hyphens joined back up), but
     * list items and short lines (headings, addresses, table rows) keep their own line.
     * Null if it has no words.
     */
    private fun blockText(b: TextBlock): String? {
        val sb = StringBuilder()
        val full = b.rect.width()
        var prevShort = false
        for (l in b.lines) {
            val t = l.text.trim()
            if (t.isEmpty()) continue
            when {
                sb.isEmpty() -> {}
                ITEM.containsMatchIn(t) || prevShort -> sb.append('\n')
                sb.endsWith("-") && t[0].isLowerCase() -> sb.setLength(sb.length - 1)
                else -> sb.append(' ')
            }
            sb.append(t)
            prevShort = b.lines.size > 1 && l.rect.width() < full * 0.6f
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
            val boxes = blocks.map { b -> b.rect.let { RectF(it.left * W, it.top * H, it.right * W, it.bottom * H) } }
            // page margins: where the text area of the page ends
            val textRight = boxes.maxOfOrNull { it.right } ?: W
            val textBottom = boxes.maxOfOrNull { it.bottom } ?: H

            blocks.forEachIndexed { i, b ->
                val t = out.getOrNull(i)?.trim()
                if (t.isNullOrEmpty()) return@forEachIndexed
                val box = boxes[i]
                if (box.width() < 2 || box.height() < 2) return@forEachIndexed
                val n = b.lines.size
                val lineH = b.lines.map { (it.bottom - it.top) * H }.sorted()[n / 2].coerceAtLeast(4f)
                val pad = max(2f, lineH * 0.14f)

                // free room to the right / below (up to the next paragraph) so text needn't shrink as much
                val roomRight = min(max(textRight, box.right), boxes.withIndex()
                    .filter { (j, o) -> j != i && o.left >= box.right - 1 && o.top < box.bottom && o.bottom > box.top }
                    .minOfOrNull { it.value.left - pad * 2 } ?: (W - pad * 2))
                val roomBottom = min(max(textBottom, box.bottom) + lineH, boxes.withIndex()
                    .filter { (j, o) -> j != i && o.top >= box.bottom - 1 && o.left < box.right && o.right > box.left }
                    .minOfOrNull { it.value.top - pad * 2 } ?: (H - pad * 2))

                val bg = edgeColor(src, RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad))
                val ink = inkColor(src, box, bg)

                // size and style of the original
                val sizes = b.lines.map { it.sizePt }.filter { it > 0 }.sorted()
                var base = if (sizes.isNotEmpty()) sizes[sizes.size / 2] / s.wPt * W else lineH * 0.8f
                base = base.coerceIn(lineH * 0.45f, lineH * 1.25f)
                val bold = b.lines.count { it.bold } * 2 > n
                val italic = b.lines.count { it.italic } * 2 > n
                val family = when (b.lines[0].font) {
                    TextFont.SERIF -> Typeface.SERIF; TextFont.MONO -> Typeface.MONOSPACE; else -> Typeface.SANS_SERIF
                }
                val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
                val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = Typeface.create(family, style) }

                val shape = shape(b, W)
                val pitch = if (n > 1) (b.lines[n - 1].baseline - b.lines[0].baseline) / (n - 1) * H else 0f
                val firstBase = b.lines[0].baseline * H

                // width: the paragraph's own; a single line may grow into free room on its right
                fun widthFor(size: Float): Float {
                    if (n > 1) return box.width()
                    tp.textSize = size
                    val need = tp.measureText(t) + 2
                    return when (shape.align) {
                        Layout.Alignment.ALIGN_CENTER -> min(need, box.width() + 2 * min(roomRight - box.right, box.left - pad)).coerceAtLeast(box.width())
                        Layout.Alignment.ALIGN_OPPOSITE -> box.width()
                        else -> min(need, roomRight - box.left).coerceAtLeast(box.width())
                    }
                }
                fun build(size: Float, w: Int): StaticLayout {
                    tp.textSize = size
                    val add = if (pitch > 0) (pitch * size / base - tp.fontSpacing).coerceIn(-size * 0.15f, size) else 0f
                    val text = android.text.SpannableString(t)
                    if (shape.indent > 0 && n > 1) text.setSpan(android.text.style.LeadingMarginSpan.Standard(shape.indent.toInt(), 0), 0, t.length, 0)
                    return StaticLayout.Builder.obtain(text, 0, t.length, tp, w)
                        .setAlignment(shape.align).setLineSpacing(add, 1f).setIncludePad(false)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                        .setJustificationMode(if (shape.justified) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
                        .build()
                }

                // shrink until it fits the paragraph (plus free room below); keep the original line spacing
                val maxH = max(box.height() + pad, min(roomBottom - box.top, box.height() * 1.5f + lineH))
                var size = base
                var w = widthFor(size)
                var lay = build(size, w.toInt().coerceAtLeast(1))
                while (size > base * 0.55f) {
                    val topGap = firstBase - box.top - lay.getLineBaseline(0)
                    if (lay.height + max(0f, topGap) <= maxH) break
                    size *= 0.94f
                    w = widthFor(size)
                    lay = build(size, w.toInt().coerceAtLeast(1))
                }

                // place the first line on the original first baseline
                val left = when {
                    n == 1 && shape.align == Layout.Alignment.ALIGN_CENTER -> box.centerX() - w / 2
                    n == 1 && shape.align == Layout.Alignment.ALIGN_OPPOSITE -> box.right - w
                    else -> box.left
                }
                var y = firstBase - lay.getLineBaseline(0)
                if (n == 1 && lay.lineCount > 1) y = box.centerY() - lay.height / 2f
                y = max(y, box.top - pad)

                fill.color = bg
                c.drawRect(
                    max(0f, min(box.left, left) - pad), max(0f, box.top - pad),
                    min(W, max(box.right, left + w) + pad), min(H, max(box.bottom, y + lay.height) + pad), fill)
                c.save()
                c.translate(left, y)
                lay.draw(c)
                c.restore()
            }
            return bmp
        }

        /** How a paragraph is set: alignment, justified or not, first-line indent (px). */
        private class Shape(val align: Layout.Alignment, val justified: Boolean, val indent: Float)

        private fun shape(b: TextBlock, W: Float): Shape {
            val rects = b.lines.map { it.rect }
            val tol = max(6f / W, 0.006f)
            if (rects.size == 1) {
                val r = rects[0]
                val centred = abs(r.centerX() - 0.5f) < 0.025f && r.width() < 0.7f && r.left > 0.15f
                return Shape(if (centred) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL, false, 0f)
            }
            fun spread(v: List<Float>) = (v.maxOrNull() ?: 0f) - (v.minOrNull() ?: 0f)
            val rest = rects.drop(1)
            val minLeft = rects.minOf { it.left }
            val indent = (rects[0].left - minLeft).takeIf { it > tol && spread(rest.map { it.left }) <= tol } ?: 0f
            val leftsOk = spread(rects.map { it.left }) <= tol || indent > 0
            // justified: every line but the last ends at the same right edge (and the last is shorter)
            val body = rects.dropLast(1)
            val justified = leftsOk && body.size >= 2 && spread(body.map { it.right }) <= tol && rects.last().right < body.maxOf { it.right } - tol
            val align = when {
                leftsOk -> Layout.Alignment.ALIGN_NORMAL
                spread(rects.map { it.centerX() }) <= tol * 2 -> Layout.Alignment.ALIGN_CENTER
                spread(rects.map { it.right }) <= tol -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            return Shape(align, justified, indent * W)
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
