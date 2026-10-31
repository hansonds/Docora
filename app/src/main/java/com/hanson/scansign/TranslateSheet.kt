package com.hanson.scansign

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translate sheet: from (auto-detected) → to, the translation, Copy / Share, and a link to the
 * language downloads. [loadText] supplies the text (selected words, or a whole page).
 */
object TranslateSheet {

    private const val AUTO = "auto"

    private fun accent(a: Context) = ContextCompat.getColor(a, Themes.accent(a).primary)

    /** Button with an outline (no fill). */
    fun outlined(a: Context) = MaterialButton(a).apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        strokeWidth = (1.5f * a.resources.displayMetrics.density).toInt()
        strokeColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(a, R.color.md_outline))
        setTextColor(accent(a))
        iconTint = android.content.res.ColorStateList.valueOf(accent(a))
    }

    /** Text-only button. */
    fun plain(a: Context) = MaterialButton(a).apply {
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(accent(a))
        elevation = 0f
        stateListAnimator = null
    }

    /** Shown every time before translating: what machine translation can and can't do. */
    fun disclaimer(a: AppCompatActivity, go: () -> Unit) {
        val d = a.resources.displayMetrics.density
        val muted = ContextCompat.getColor(a, R.color.md_on_surface_variant)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0) }
        val points = listOf(
            "Machine translation can be wrong or miss words. For legal, medical or official papers, have a person check it.",
            "Names, numbers, codes and some special terms are kept as they are.",
            "The translated page is a picture: fonts, colours and layout are matched as closely as possible, not exactly.",
            "Text inside photos, logos, stamps or handwriting may not be translated. Scanned pages depend on how clearly the text is read.",
            "Each language is a one-time download (about ${Translator.MODEL_MB} MB). After that it works offline — your text never leaves your phone.",
            "Saving makes a new PDF (documents up to ${PageTranslate.MAX_PAGES} pages). Your original file is never changed."
        )
        for (p in points) box.addView(TextView(a).apply {
            text = "•  $p"; textSize = 14f; setTextColor(muted); setLineSpacing(0f, 1.15f)
            setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt())
        })
        MaterialAlertDialogBuilder(a)
            .setTitle("Before you translate")
            .setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton("Translate") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Language list: "Detect automatically", then the downloaded languages (✓, ready offline),
     * then the rest with their download size.
     */
    fun pickLanguage(a: Context, title: String, allowAuto: Boolean, current: String, have: Set<String>, done: (String) -> Unit) {
        val (ready, rest) = Translator.all.partition { it.code in have }
        val names = ArrayList<CharSequence>()
        val codes = ArrayList<String>()
        if (allowAuto) { names.add("Detect automatically"); codes.add(AUTO) }
        for (l in ready) { names.add("✓  ${l.name}"); codes.add(l.code) }
        for (l in rest) { names.add("${l.name}   · ${Translator.MODEL_MB} MB"); codes.add(l.code) }
        MaterialAlertDialogBuilder(a)
            .setTitle(title)
            .setSingleChoiceItems(names.toTypedArray(), codes.indexOf(current)) { dlg, i -> dlg.dismiss(); done(codes[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun show(a: AppCompatActivity, what: String, loadText: suspend () -> String) {
        val d = a.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val muted = ContextCompat.getColor(a, R.color.md_on_surface_variant)
        val sheet = BottomSheetDialog(a)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(6f), dp(20f), dp(20f)) }

        // title
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(a).apply {
            text = "Translate"; textSize = 22f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setCompoundDrawablesRelativeWithIntrinsicBounds(IconTiles.badge(a, R.drawable.ic_web, IconTiles.Hue.CYAN, 34f), null, null, null)
            compoundDrawablePadding = dp(12f); gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head)
        root.addView(TextView(a).apply { text = "$what · on your phone, offline"; textSize = 13f; setTextColor(muted); setPadding(0, dp(2f), 0, dp(12f)) })

        // from ⇄ to
        var from = AUTO
        var detected: String? = null
        var sure = 1f
        var to = Translator.defaultTarget(a)
        val langs = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val fromBtn = outlined(a)
        val swap = plain(a).apply { text = "⇄"; textSize = 20f }
        val toBtn = MaterialButton(a)
        langs.addView(fromBtn, LinearLayout.LayoutParams(0, dp(52f), 1f))
        langs.addView(swap, LinearLayout.LayoutParams(dp(56f), dp(52f)))
        langs.addView(toBtn, LinearLayout.LayoutParams(0, dp(52f), 1f))
        root.addView(langs)

        val progress = LinearProgressIndicator(a).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10f) })
        val note = TextView(a).apply { textSize = 13f; setTextColor(muted); setPadding(0, dp(8f), 0, 0); visibility = View.GONE }
        root.addView(note)
        val downloadBtn = MaterialButton(a).apply { visibility = View.GONE }
        root.addView(downloadBtn, LinearLayout.LayoutParams(-1, dp(50f)).apply { topMargin = dp(6f) })

        // result
        val result = TextView(a).apply {
            textSize = 16f; setTextIsSelectable(true); setLineSpacing(0f, 1.2f)
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14 * d; setColor(ContextCompat.getColor(a, R.color.md_surface_variant))
            }
        }
        val scroll = ScrollView(a).apply { addView(result); visibility = View.GONE }
        root.addView(scroll, LinearLayout.LayoutParams(-1, (a.resources.displayMetrics.heightPixels * 0.42f).toInt()).apply { topMargin = dp(12f) })

        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, 0); visibility = View.GONE }
        val copyBtn = outlined(a).apply { text = "Copy"; setIconResource(R.drawable.ic_check) }
        val shareBtn = MaterialButton(a).apply { text = "Share"; setIconResource(R.drawable.ic_share) }
        actions.addView(copyBtn, LinearLayout.LayoutParams(0, dp(50f), 1f).apply { marginEnd = dp(6f) })
        actions.addView(shareBtn, LinearLayout.LayoutParams(0, dp(50f), 1f).apply { marginStart = dp(6f) })
        root.addView(actions)
        root.addView(plain(a).apply {
            text = "Manage languages"
            setOnClickListener { a.startActivity(Intent(a, TranslateLanguagesActivity::class.java)) }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4f) })

        sheet.setContentView(root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        var source = ""
        var job: Job? = null
        var have: Set<String> = setOf("en")

        fun labels() {
            fromBtn.text = if (from == AUTO) (detected?.let { "${Translator.name(it)} (auto${if (sure < 0.45f) "?" else ""})" } ?: "Detect language") else Translator.name(from)
            toBtn.text = Translator.name(to)
        }

        fun run() {
            job?.cancel()
            labels()
            val src = if (from == AUTO) detected else from
            if (source.isBlank()) { note.visibility = View.VISIBLE; note.text = "No text found here."; return }
            if (src == null) {
                note.visibility = View.VISIBLE
                note.text = Translator.unsupportedScript(source)?.let { "$it text can't be translated on the phone yet — Google's offline translator doesn't support it." }
                    ?: "Couldn't tell which language this is — tap the left button to choose it."
                scroll.visibility = View.GONE; actions.visibility = View.GONE; downloadBtn.visibility = View.GONE
                return
            }
            if (src == to) {
                note.visibility = View.VISIBLE; note.text = "The text is already in ${Translator.name(to)}."
                result.text = source; scroll.visibility = View.VISIBLE; actions.visibility = View.VISIBLE; downloadBtn.visibility = View.GONE
                return
            }
            val missing = listOf(src, to).filter { it !in have }
            if (missing.isNotEmpty()) {
                // ask before downloading anything
                note.visibility = View.VISIBLE
                note.text = missing.joinToString(" and ") { Translator.name(it) } +
                    " needs a one-time download (about ${Translator.MODEL_MB * missing.size} MB). After that it works offline."
                downloadBtn.visibility = View.VISIBLE
                downloadBtn.text = "Download & translate"
                scroll.visibility = View.GONE; actions.visibility = View.GONE
                downloadBtn.setOnClickListener {
                    downloadBtn.isEnabled = false
                    progress.visibility = View.VISIBLE
                    note.text = "Downloading ${missing.joinToString(" and ") { Translator.name(it) }}…"
                    job = a.lifecycleScope.launch {
                        val problem = withContext(Dispatchers.IO) {
                            try {
                                missing.forEach { code ->
                                    Translator.download(a, code,
                                        status = { s -> a.runOnUiThread { note.text = "Downloading ${Translator.name(code)} · $s" } },
                                        keepGoing = { isActive && sheet.isShowing })
                                }
                                null
                            } catch (e: Throwable) { e }
                        }
                        progress.visibility = View.GONE
                        downloadBtn.isEnabled = true
                        if (problem != null) {
                            note.text = (problem as? Translator.DownloadProblem)?.message
                                ?: "Couldn't download — check your internet connection and try again."
                            downloadBtn.text = "Try again"
                            if ((problem as? Translator.DownloadProblem)?.openDownloadManager == true)
                                Translator.openDownloadManagerSettings(a)
                            return@launch
                        }
                        have = have + missing
                        run()
                    }
                }
                return
            }
            downloadBtn.visibility = View.GONE
            note.visibility = View.GONE
            progress.visibility = View.VISIBLE
            job = a.lifecycleScope.launch {
                val out = withContext(Dispatchers.IO) {
                    try { Translator.translate(source, src, to) } catch (e: Throwable) { null }
                }
                progress.visibility = View.GONE
                if (out == null) { note.visibility = View.VISIBLE; note.text = "Couldn't translate this text. Please try again."; return@launch }
                result.text = out
                scroll.visibility = View.VISIBLE
                actions.visibility = View.VISIBLE
            }
        }

        fun pick(title: String, allowAuto: Boolean, current: String, done: (String) -> Unit) =
            pickLanguage(a, title, allowAuto, current, have, done)

        fromBtn.setOnClickListener { pick("Translate from", true, from) { from = it; run() } }
        toBtn.setOnClickListener { pick("Translate to", false, to) { to = it; Translator.rememberTarget(a, it); run() } }
        swap.setOnClickListener {
            val src = if (from == AUTO) detected else from
            if (src != null) { from = to; to = src; Translator.rememberTarget(a, to); run() }
        }
        copyBtn.setOnClickListener {
            (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("translation", result.text))
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
        }
        shareBtn.setOnClickListener {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, result.text.toString())
            }, "Share translation"))
        }
        sheet.setOnDismissListener { job?.cancel() }

        labels()
        progress.visibility = View.VISIBLE
        note.visibility = View.VISIBLE
        note.text = "Reading the text…"
        sheet.show()
        job = a.lifecycleScope.launch {
            source = try { Translator.reflow(loadText()).trim() } catch (_: Throwable) { "" }
            val (det, models) = withContext(Dispatchers.IO) {
                Pair(if (source.isBlank()) null else Translator.detectPage(source.split('\n')).also { sure = it.confidence }.code,
                    try { Translator.downloaded() } catch (_: Throwable) { setOf("en") })
            }
            detected = det
            have = models
            // translating into the text's own language makes no sense: go to English instead
            if (det != null && det == to) to = if (det == "en") Translator.defaultTarget(a).takeIf { it != "en" } ?: "hi" else "en"
            progress.visibility = View.GONE
            note.visibility = View.GONE
            run()
        }
    }
}
