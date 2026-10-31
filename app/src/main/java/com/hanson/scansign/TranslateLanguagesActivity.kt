package com.hanson.scansign

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Translation languages: download the ones you need (about 30 MB each), delete them to free space. */
class TranslateLanguagesActivity : BaseActivity() {

    private class Row(val lang: Translator.Lang, val button: MaterialButton, val spinner: ProgressBar, val status: TextView) {
        val card: View get() = (button.parent as View).parent as View
    }

    private val rows = ArrayList<Row>()
    private lateinit var list: LinearLayout
    private lateinit var headReady: TextView
    private lateinit var headMore: TextView
    private var have: Set<String> = emptySet()
    private val busy = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            title = "Translation languages"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56f)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(24f)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Ui.limitWidth(list, 680)

        // intro card
        val intro = MaterialCardView(this).apply { radius = 18 * d }
        val introBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(14f), dp(16f), dp(10f)) }
        introBox.addView(TextView(this).apply {
            text = "Translate on your phone"
            textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setCompoundDrawablesRelativeWithIntrinsicBounds(IconTiles.badge(this@TranslateLanguagesActivity, R.drawable.ic_web, IconTiles.Hue.CYAN), null, null, null)
            compoundDrawablePadding = dp(12f)
            gravity = Gravity.CENTER_VERTICAL
        })
        introBox.addView(TextView(this).apply {
            text = "Download the languages you need — about ${Translator.MODEL_MB} MB each, once. After that, translation works offline and your text never leaves the phone. English is always included."
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(this@TranslateLanguagesActivity, R.color.md_on_surface_variant))
            setPadding(0, dp(8f), 0, 0)
        })
        introBox.addView(MaterialSwitch(this).apply {
            text = "Download only on Wi-Fi"
            isChecked = Translator.wifiOnly(this@TranslateLanguagesActivity)
            setOnCheckedChangeListener { _, on -> Translator.setWifiOnly(this@TranslateLanguagesActivity, on) }
        })
        intro.addView(introBox)
        list.addView(intro, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14f) })

        fun header(s: String) = TextView(this).apply {
            text = s; textSize = 14f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@TranslateLanguagesActivity, Themes.accent(this@TranslateLanguagesActivity).primary))
            setPadding(dp(4f), dp(10f), 0, dp(8f))
        }
        headReady = header("On this phone")
        headMore = header("Available to download")
        for (lang in Translator.all) {
            val card = MaterialCardView(this).apply { radius = 14 * d; cardElevation = 0f; strokeWidth = dp(1f)
                strokeColor = ContextCompat.getColor(this@TranslateLanguagesActivity, R.color.md_outline) }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(8f), dp(8f), dp(8f)); minimumHeight = dp(60f)
            }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply { text = lang.name; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
            val status = TextView(this).apply {
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(this@TranslateLanguagesActivity, R.color.md_on_surface_variant))
            }
            texts.addView(status)
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            val spinner = ProgressBar(this).apply { visibility = View.GONE }
            row.addView(spinner, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply { marginEnd = dp(10f) })
            val button = MaterialButton(this).apply { minWidth = dp(112f) }
            row.addView(button, LinearLayout.LayoutParams(-2, dp(44f)))
            card.addView(row)
            card.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) }
            val r = Row(lang, button, spinner, status)
            rows.add(r)
            button.setOnClickListener { act(r) }
        }
        arrange()
        refreshList()
    }

    private fun refreshList() {
        lifecycleScope.launch {
            have = withContext(Dispatchers.IO) { try { Translator.downloaded() } catch (_: Throwable) { setOf("en") } }
            for (r in rows) show(r)
            arrange()
        }
    }

    /** Downloaded languages first ("On this phone"), then the rest. */
    private fun arrange() {
        while (list.childCount > 1) list.removeViewAt(1)   // keep the intro card
        val (ready, more) = rows.partition { it.lang.code in have || it.lang.code in busy }
        list.addView(headReady)
        ready.forEach { list.addView(it.card) }
        if (more.isNotEmpty()) {
            list.addView(headMore)
            more.forEach { list.addView(it.card) }
        }
    }

    /** Live download state per language ("12.3 of 30.1 MB", "Waiting for Wi-Fi…"). */
    private val progress = HashMap<String, String>()

    private fun show(r: Row) {
        val code = r.lang.code
        val loading = code in busy
        r.spinner.visibility = if (loading) View.VISIBLE else View.GONE
        r.button.isEnabled = !Translator.builtIn(code)
        when {
            Translator.builtIn(code) -> { r.status.text = "Always included"; r.button.text = "Included" }
            loading -> { r.status.text = progress[code] ?: "Downloading…"; r.button.text = "Cancel" }
            code in have -> { r.status.text = "✓ Downloaded · works offline"; r.button.text = "Delete" }
            else -> { r.status.text = "About ${Translator.MODEL_MB} MB"; r.button.text = "Download" }
        }
    }

    private fun act(r: Row) {
        val code = r.lang.code
        when {
            code in busy -> {
                // cancel: stop waiting and clear the phone's queued download
                cancelled.add(code)
                lifecycleScope.launch(Dispatchers.IO) { Translator.cancelStuck(this@TranslateLanguagesActivity) }
            }
            code in have -> MaterialAlertDialogBuilder(this)
                .setTitle("Delete ${r.lang.name}?")
                .setMessage("Frees about ${Translator.MODEL_MB} MB. You can download it again any time.")
                .setPositiveButton("Delete") { _, _ -> run(r) { Translator.delete(code) } }
                .setNegativeButton("Cancel", null)
                .show()
            else -> run(r) {
                Translator.download(this, code,
                    status = { s -> runOnUiThread { progress[code] = s; show(r) } },
                    keepGoing = { code !in cancelled && !isDestroyed })
            }
        }
    }

    private val cancelled = HashSet<String>()

    private fun run(r: Row, work: () -> Unit) {
        val code = r.lang.code
        busy.add(code)
        cancelled.remove(code)
        progress.remove(code)
        show(r)
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { try { work(); null } catch (e: Throwable) { e } }
            busy.remove(code)
            progress.remove(code)
            val wasCancelled = cancelled.remove(code)
            if (problem != null && !wasCancelled) explain(r, problem)
            refreshList()
        }
    }

    /** Says why a download failed, with a way to fix it. */
    private fun explain(r: Row, e: Throwable) {
        val p = e as? Translator.DownloadProblem
        val b = MaterialAlertDialogBuilder(this)
            .setTitle("${r.lang.name} didn't download")
            .setMessage(p?.message ?: "Couldn't finish — check your internet connection and try again.")
            .setPositiveButton("Try again") { _, _ -> act(r) }
            .setNegativeButton("Close", null)
        if (p?.openDownloadManager == true) b.setNeutralButton("Open settings") { _, _ -> Translator.openDownloadManagerSettings(this) }
        b.show()
    }
}
