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

        val surface = Ui.surface(a)
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
            background = round(a, Ui.surfaceVariant(a), 22f)
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

        // ---- pages, one under another: scroll to move between them
        val stage = FrameLayout(a).apply { setBackgroundColor(Color.rgb(38, 38, 42)) }
        val list = androidx.recyclerview.widget.RecyclerView(a).apply {
            clipToPadding = false
            setPadding(0, dp(8f), 0, dp(8f))
            itemAnimator = null
        }
        val lm = androidx.recyclerview.widget.LinearLayoutManager(a)
        list.layoutManager = lm
        stage.addView(list, FrameLayout.LayoutParams(-1, -1))
        val badge = TextView(a).apply {
            textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
            background = round(a, Color.argb(175, 0, 0, 0), 50f)
            visibility = View.GONE
        }
        stage.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12f) })
        val hint = TextView(a).apply {
            text = "Hold a page to see the original · tap to zoom"; textSize = 11.5f; setTextColor(Color.WHITE)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            background = round(a, Color.argb(140, 0, 0, 0), 50f)
            visibility = View.GONE
        }
        stage.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12f) })
        val busyBox = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22f), dp(18f), dp(22f), dp(18f))
            background = round(a, Color.argb(225, 22, 22, 26), 20f)
            visibility = View.GONE
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
        val compare = tool("Original", R.drawable.ic_compare)
        val textBtn = tool("Text", R.drawable.ic_text)
        val shareBtn = tool("Share", R.drawable.ic_share)
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
        val cache = LinkedHashMap<Int, Result>()   // full results of the last couple of pages
        var showOriginal = false
        var job: Job? = null          // saving
        var worker: Job? = null       // translating the pages being looked at
        var saving = false
        var declined = false          // "Not now" to a download: stop asking until Download is tapped
        val session = Translator.Session()

        /** A finished page: translated and original pictures kept on disk, so scrolling back is instant. */
        class Done(val from: String?, val note: String?, val tFile: File?, val oFile: File?, val wPt: Float, val hPt: Float)
        val done = HashMap<Int, Done>()
        val working = HashMap<Int, String>()      // page → what is happening now
        val liveDir = File(a.cacheDir, "translated/live").apply { deleteRecursively(); mkdirs() }
        var gen = 0
        val views = object : android.util.LruCache<String, Bitmap>(
            min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).toInt()
        ) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

        fun posOf(page: Int) = pages.indexOf(page)

        fun labels() {
            val det = done[pages[idx]]?.from
            fromName.text = if (from == AUTO) (det?.let { "${Translator.name(it)} (auto)" } ?: "Detect language") else Translator.name(from)
            toName.text = Translator.name(to)
            pagePill.text = if (pages.size > 1) "${idx + 1} / ${pages.size}" else "Page ${pages[idx] + 1}"
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

        /** Updates the tools and the badge after pages finish or the view mode changes. */
        fun tools() {
            val any = done.values.any { it.tFile != null }
            setLabel(compare, if (showOriginal) "Translated" else "Original")
            enable(compare, any)
            badge.visibility = if (any) View.VISIBLE else View.GONE
            badge.text = if (showOriginal) "Original" else "Translated to ${Translator.name(to)}"
            if (any && !showOriginal && hint.tag == null) {
                hint.tag = true; hint.visibility = View.VISIBLE
                hint.postDelayed({ hint.visibility = View.GONE }, 4500)
            }
            labels()
        }

        fun decode(f: File, maxW: Int): Bitmap? = try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (maxW > 0 && o.outWidth / (s * 2) >= maxW) s *= 2
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }

        var retryDownloads: (() -> Unit)? = null

        // ---- one row per page
        class VH(val card: FrameLayout, val pic: ImageView, val wait: LinearLayout, val waitText: TextView,
                 val num: TextView, val noteBox: LinearLayout, val noteText: TextView, val noteAction: MaterialButton) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(card)

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<VH>() {
            override fun getItemCount() = pages.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val card = FrameLayout(a).apply {
                    setBackgroundColor(Color.WHITE)
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2).apply {
                        setMargins(dp(10f), dp(6f), dp(10f), dp(6f))
                    }
                    elevation = dp(2f).toFloat()
                }
                val pic = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; adjustViewBounds = false }
                card.addView(pic, FrameLayout.LayoutParams(-1, -1))
                val wait = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(20f), dp(16f), dp(20f), dp(16f))
                    background = round(a, Color.argb(225, 22, 22, 26), 18f)
                }
                wait.addView(ProgressBar(a), LinearLayout.LayoutParams(dp(30f), dp(30f)))
                val waitText = TextView(a).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, dp(8f), 0, 0) }
                wait.addView(waitText, LinearLayout.LayoutParams(-2, -2))
                card.addView(wait, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply { marginStart = dp(24f); marginEnd = dp(24f) })
                val num = TextView(a).apply {
                    textSize = 11.5f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(9f), dp(3f), dp(9f), dp(3f))
                    background = round(a, Color.argb(150, 0, 0, 0), 50f)
                }
                card.addView(num, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = dp(8f); marginStart = dp(8f) })
                val noteBox = LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
                    background = round(a, Color.argb(230, 22, 22, 26), 16f)
                }
                val noteText = TextView(a).apply { textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
                noteBox.addView(noteText, LinearLayout.LayoutParams(-2, -2))
                val noteAction = MaterialButton(a).apply { text = "Download" }
                noteBox.addView(noteAction, LinearLayout.LayoutParams(-2, dp(44f)).apply { topMargin = dp(8f) })
                card.addView(noteBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(14f); marginStart = dp(16f); marginEnd = dp(16f)
                })
                return VH(card, pic, wait, waitText, num, noteBox, noteText, noteAction)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {
                val page = pages[position]
                val d = done[page]
                val w = (if (list.width > 0) list.width else a.resources.displayMetrics.widthPixels) - dp(20f)
                val ratio = if (d != null && d.wPt > 0f) d.hPt / d.wPt else 1.414f
                holder.card.layoutParams = holder.card.layoutParams.apply { height = (w * ratio).toInt() }
                holder.num.text = "${page + 1}"
                holder.num.visibility = if (pages.size > 1) View.VISIBLE else View.GONE
                if (d == null) {
                    holder.pic.tag = null
                    holder.pic.setImageDrawable(null)
                    holder.wait.visibility = View.VISIBLE
                    holder.waitText.text = working[page] ?: "Waiting to translate…"
                    holder.noteBox.visibility = View.GONE
                    return
                }
                holder.wait.visibility = View.GONE
                if (d.note != null) {
                    holder.noteBox.visibility = View.VISIBLE
                    holder.noteText.text = d.note
                    val dl = d.note.contains("isn't downloaded")
                    holder.noteAction.visibility = if (dl) View.VISIBLE else View.GONE
                    holder.noteAction.setOnClickListener { retryDownloads?.invoke() }
                } else holder.noteBox.visibility = View.GONE
                val f = if (showOriginal || d.tFile == null) d.oFile else d.tFile
                if (f == null) { holder.pic.setImageDrawable(null); return }
                val key = f.absolutePath
                holder.pic.tag = key
                val hit = views.get(key)
                if (hit != null) { holder.pic.setImageBitmap(hit); return }
                holder.pic.setImageDrawable(null)
                a.lifecycleScope.launch {
                    val b = withContext(Dispatchers.IO) { decode(f, w) } ?: return@launch
                    views.put(key, b)
                    if (holder.pic.tag == key) holder.pic.setImageBitmap(b)
                }
            }
        }
        list.adapter = adapter

        fun refresh(page: Int) { val p = posOf(page); if (p >= 0) adapter.notifyItemChanged(p) }

        /** Asks before a one-time language download. */
        var asking = false
        suspend fun askDownload(missing: List<String>): Boolean = suspendCancellableCoroutine { cont ->
            asking = true
            cont.invokeOnCancellation { asking = false }
            val names = missing.joinToString(" and ") { Translator.name(it) }
            MaterialAlertDialogBuilder(a)
                .setTitle("Download $names?")
                .setMessage("One-time download of about ${Translator.MODEL_MB * missing.size} MB. After that, translation works offline on your phone.")
                .setPositiveButton("Download") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Not now") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .setOnDismissListener { asking = false }
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
                        TranslateSheet.showProblem(a, "Download didn't finish", problem)
                        return Result(src, null, lang, "${missing.joinToString(" and ") { Translator.name(it) }} isn't downloaded yet.")
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
                while (cache.size > 2) cache.remove(cache.keys.first())
            }
            return r
        }

        /** Keeps a finished page on disk and shows it in the list. */
        suspend fun record(page: Int, r: Result, g: Int) {
            val src = r.src
            val saved = withContext(Dispatchers.IO) {
                try {
                    val o = src?.let { s -> File(liveDir, "g$g-p$page-o.jpg").also { f -> f.outputStream().use { s.bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) } } }
                    val t = r.translated?.let { b -> File(liveDir, "g$g-p$page-t.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } } }
                    Pair(o, t)
                } catch (_: Throwable) { Pair(null, null) }
            }
            if (g != gen) return
            done[page] = Done(r.from, r.note, saved.second, saved.first, src?.wPt ?: 0f, src?.hPt ?: 0f)
            working.remove(page)
            refresh(page)
            tools()
        }

        /** The page shown in the middle of the screen becomes the current page. */
        fun track() {
            val f = lm.findFirstVisibleItemPosition()
            val l = lm.findLastVisibleItemPosition()
            if (f < 0) return
            var best = f
            if (l > f) {
                val mid = list.height / 2
                for (p in f..l) {
                    val v = lm.findViewByPosition(p) ?: continue
                    if (v.top <= mid && v.bottom >= mid) { best = p; break }
                }
            }
            if (best != idx) { idx = best; labels() }
        }

        /** Next page to translate: the ones on screen first, then the one just below. */
        fun nextPos(): Int? {
            var f = lm.findFirstVisibleItemPosition()
            var l = lm.findLastVisibleItemPosition()
            if (f < 0) { f = idx; l = idx }
            val order = (listOf(idx) + (f..l) + listOf(l + 1)).filter { it in pages.indices }.distinct()
            return order.firstOrNull { !done.containsKey(pages[it]) }
        }

        /** Translates the pages being looked at, one at a time, as the user scrolls. */
        fun kick() {
            if (saving || worker?.isActive == true) return
            val g = gen
            worker = a.lifecycleScope.launch {
                while (isActive && g == gen) {
                    val pos = nextPos() ?: break
                    val page = pages[pos]
                    val f = lm.findFirstVisibleItemPosition(); val l = lm.findLastVisibleItemPosition()
                    val onScreen = f < 0 || pos in f..l
                    working[page] = "Reading the page…"; refresh(page)
                    val r0 = try {
                        translate(page, onScreen && !declined) { s -> if (g == gen && working.containsKey(page)) { working[page] = s; refresh(page) } }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) { working.remove(page); throw e }
                        Result(null, null, null, "Couldn't translate this page. Please try again.")
                    }
                    // reading ahead never asks to download: tell the user on the page instead
                    val r = if (r0.src != null && r0.translated == null && r0.note == null)
                        Result(r0.src, null, r0.from, "The language pack isn't downloaded yet.") else r0
                    if (r.note?.contains("isn't downloaded") == true) declined = true
                    record(page, r, g)
                }
            }
        }

        retryDownloads = {
            declined = false
            done.entries.removeAll { it.value.note?.contains("isn't downloaded") == true }
            adapter.notifyDataSetChanged()
            worker?.cancel(); worker = null
            kick()
        }

        fun changed() {
            gen++
            worker?.cancel(); worker = null
            synchronized(cache) { cache.clear() }
            done.clear(); working.clear(); views.evictAll(); declined = false
            Thread { liveDir.listFiles()?.forEach { it.delete() } }.start()
            showOriginal = false
            adapter.notifyDataSetChanged()
            tools()
            kick()
        }

        list.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) { track() }
            override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    track()
                    // jumped far away: start with the pages now on screen
                    val p = pages[idx]
                    if (!asking && !done.containsKey(p) && !working.containsKey(p)) { worker?.cancel(); worker = null }
                    kick()
                }
            }
        })

        /** Full screen view of one page with pinch zoom; hold to see the original. */
        fun zoom(page: Int) {
            val d = done[page] ?: return
            val z = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val box = FrameLayout(a).apply { setBackgroundColor(Color.rgb(20, 20, 22)) }
            val img = ZoomImage(a)
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            val x = ImageButton(a).apply {
                setImageResource(R.drawable.ic_close); setColorFilter(Color.WHITE); contentDescription = "Close"
                background = round(a, Color.argb(150, 0, 0, 0), 24f)
                setOnClickListener { z.dismiss() }
            }
            box.addView(x, FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.TOP or Gravity.END).apply { topMargin = dp(16f); marginEnd = dp(16f) })
            val cap = TextView(a).apply {
                textSize = 12f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12f), dp(5f), dp(12f), dp(5f))
                background = round(a, Color.argb(160, 0, 0, 0), 50f)
            }
            box.addView(cap, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(24f) })
            z.setContentView(box)
            z.show()
            a.lifecycleScope.launch {
                val t = d.tFile?.let { f -> withContext(Dispatchers.IO) { decode(f, 0) } }
                val o = d.oFile?.let { f -> withContext(Dispatchers.IO) { decode(f, 0) } }
                val first = (if (showOriginal) o else t) ?: o ?: t ?: return@launch
                img.show(first)
                cap.text = if (first === t) "Page ${page + 1} · hold to see the original" else "Page ${page + 1} · original"
                img.onHold = { hold ->
                    if (t != null && o != null) {
                        val b = if (hold != showOriginal) o else t
                        img.show(b)
                        cap.text = if (b === o) "Page ${page + 1} · original" else "Page ${page + 1} · translated"
                    }
                }
            }
        }

        // tap a page to zoom; hold it to see the original
        list.addOnItemTouchListener(object : androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
            var held: VH? = null
            val gd = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val v = list.findChildViewUnder(e.x, e.y) ?: return false
                    val p = list.getChildAdapterPosition(v)
                    if (p in pages.indices) zoom(pages[p])
                    return true
                }
                override fun onLongPress(e: MotionEvent) {
                    val v = list.findChildViewUnder(e.x, e.y) ?: return
                    val vh = list.getChildViewHolder(v) as? VH ?: return
                    val p = list.getChildAdapterPosition(v)
                    val d = if (p in pages.indices) done[pages[p]] else null
                    val other = (if (showOriginal) d?.tFile else d?.oFile) ?: return
                    if (d?.tFile == null) return
                    held = vh
                    val w = vh.pic.width
                    a.lifecycleScope.launch {
                        val b = views.get(other.absolutePath) ?: withContext(Dispatchers.IO) { decode(other, w) }?.also { views.put(other.absolutePath, it) }
                        if (b != null && held === vh) { vh.pic.tag = other.absolutePath; vh.pic.setImageBitmap(b) }
                    }
                }
            })
            override fun onInterceptTouchEvent(rv: androidx.recyclerview.widget.RecyclerView, e: MotionEvent): Boolean {
                gd.onTouchEvent(e)
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    held?.let { vh -> held = null; val p = vh.bindingAdapterPosition; if (p in pages.indices) adapter.notifyItemChanged(p) }
                }
                return false
            }
        })

        fun pick(title: String, allowAuto: Boolean, current: String, onPick: (String) -> Unit) =
            TranslateSheet.pickLanguage(a, title, allowAuto, current, have, onPick)

        fromBtn.setOnClickListener { pick("Translate from", true, from) { from = it; changed() } }
        toBtn.setOnClickListener { pick("Translate to", false, to) { to = it; Translator.rememberTarget(a, it); changed() } }
        swap.setOnClickListener {
            val src = if (from == AUTO) done[pages[idx]]?.from else from
            if (src != null && src != to) { from = to; to = src; Translator.rememberTarget(a, to); changed() }
        }
        compare.setOnClickListener { showOriginal = !showOriginal; adapter.notifyDataSetChanged(); tools() }
        textBtn.setOnClickListener { val page = pages[idx]; TranslateSheet.show(a, "Page ${page + 1}") { pageText(page) } }
        shareBtn.setOnClickListener {
            val page = pages[idx]
            val d = done[page] ?: return@setOnClickListener
            val pic = d.tFile ?: d.oFile ?: return@setOnClickListener
            a.lifecycleScope.launch {
                val f = withContext(Dispatchers.IO) {
                    try {
                        val dir = File(a.cacheDir, "translated").apply { mkdirs() }
                        File(dir, "page-${page + 1}-${to}.jpg").also { f -> pic.copyTo(f, overwrite = true) }
                    } catch (_: Throwable) { null }
                } ?: return@launch
                a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, Storage.uriFor(a, f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share translated page"))
            }
        }

        val saveLabel = saveBtn.text
        saveBtn.setOnClickListener {
            if (saving) { job?.cancel(); return@setOnClickListener }   // "Stop"
            job?.cancel()
            worker?.cancel(); worker = null
            working.clear(); adapter.notifyDataSetChanged()
            val g = gen
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
                            // pages already translated while scrolling are reused
                            val d = done[page]?.takeIf { it.wPt > 0f && (it.tFile != null || it.note?.contains("isn't downloaded") != true) }
                            val wPt: Float; val hPt: Float; val pic: Bitmap
                            if (d != null) {
                                pic = withContext(Dispatchers.IO) { (d.tFile ?: d.oFile)?.let { decode(it, 0) } } ?: continue
                                wPt = d.wPt; hPt = d.hPt
                                if (d.tFile != null) translatedAny = true
                            } else {
                                val r = translate(page, true) { busy(tag + it) }
                                if (r.note != null && r.note.contains("isn't downloaded")) { busy(null); note(r.note); return@launch }
                                val src = r.src ?: continue
                                pic = r.translated ?: src.bitmap
                                wPt = src.wPt; hPt = src.hPt
                                if (r.translated != null) translatedAny = true
                                record(page, r, g)
                            }
                            withContext(Dispatchers.IO) {
                                val pg = PDPage(PDRectangle(wPt, hPt))
                                pd.addPage(pg)
                                val img = JPEGFactory.createFromImage(pd, pic, 0.86f)
                                PDPageContentStream(pd, pg).use { it.drawImage(img, 0f, 0f, wPt, hPt) }
                            }
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
                    if (e is kotlinx.coroutines.CancellationException) { busy(null); note("Stopped. Nothing was saved."); busyBox.postDelayed({ if (!saving) busy(null) }, 2500); throw e }
                    busy(null)
                    note("Couldn't save the PDF. Please try again.")
                } finally {
                    saving = false
                    saveBtn.text = saveLabel
                    saveBtn.setIconResource(R.drawable.ic_save_as)
                    dlg.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (dlg.isShowing) list.post { kick() }
                }
            }
        }

        dlg.setOnDismissListener {
            job?.cancel(); worker?.cancel()
            views.evictAll()
            Thread { session.close(); liveDir.deleteRecursively() }.start()
        }
        dlg.show()
        labels()
        tools()
        if (idx > 0) lm.scrollToPositionWithOffset(idx, 0)
        list.post { kick() }
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
