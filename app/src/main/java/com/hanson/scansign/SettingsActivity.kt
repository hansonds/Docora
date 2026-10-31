package com.hanson.scansign

import android.os.Bundle
import android.widget.TextView
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        Ui.limitWidth(findViewById(R.id.content), 680)
        val prefs = Settings.prefs(this)

        val scanner = findViewById<RadioGroup>(R.id.scanner)
        scanner.check(if (Settings.useAiScanner(this)) R.id.scannerAi else R.id.scannerBuiltin)
        scanner.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Settings.SCANNER, if (id == R.id.scannerAi) "ai" else "builtin").apply()
        }

        // default scan filter
        val filterGroup = findViewById<com.google.android.material.chip.ChipGroup>(R.id.filterGroup)
        val filterHint = findViewById<android.widget.TextView>(R.id.filterHint)
        fun hint(f: ScanFilter) = when (f) {
            ScanFilter.AUTO -> "Removes shadows, keeps colour only when the page has colour (recommended)"
            ScanFilter.ORIGINAL -> "Keeps the photo exactly as captured"
            ScanFilter.MAGIC -> "Removes shadows and brightens, always in colour"
            ScanFilter.GRAY -> "Greyscale, no shadow removal"
            ScanFilter.BW -> "Pure black & white, sharpest for printed text"
        }
        val current = Settings.defaultFilter(this)
        for (f in ScanFilter.values()) {
            val chip = com.google.android.material.chip.Chip(this).apply {
                id = android.view.View.generateViewId()
                text = if (f == ScanFilter.AUTO) "Auto ✦" else f.label
                isCheckable = true
                tag = f
            }
            filterGroup.addView(chip)
            if (f == current) chip.isChecked = true
        }
        filterHint.text = hint(current)
        filterGroup.setOnCheckedStateChangeListener { g, ids ->
            val f = ids.firstOrNull()?.let { g.findViewById<com.google.android.material.chip.Chip>(it) }?.tag as? ScanFilter ?: return@setOnCheckedStateChangeListener
            prefs.edit().putString(Settings.DEFAULT_FILTER, f.name).apply()
            filterHint.text = hint(f)
        }

        val ocr = findViewById<RadioGroup>(R.id.ocr)
        ocr.check(if (Ocr.script(this) == Ocr.Script.DEVANAGARI) R.id.ocrDeva else R.id.ocrLatin)
        ocr.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Settings.OCR_SCRIPT, if (id == R.id.ocrDeva) Ocr.Script.DEVANAGARI.name else Ocr.Script.LATIN.name).apply()
        }

        // appearance
        val modeGroup = findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.modeGroup)
        modeGroup.check(when (Themes.mode(this)) {
            Themes.Mode.LIGHT -> R.id.modeLight
            Themes.Mode.DARK -> R.id.modeDark
            else -> R.id.modeSystem
        })
        modeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val m = when (id) { R.id.modeLight -> Themes.Mode.LIGHT; R.id.modeDark -> Themes.Mode.DARK; else -> Themes.Mode.SYSTEM }
            if (m != Themes.mode(this)) Themes.setMode(this, m)
        }
        buildAccents()
        val amoled = findViewById<MaterialSwitch>(R.id.amoled)
        amoled.isChecked = Themes.amoled(this)
        amoled.setOnCheckedChangeListener { _, on -> Themes.setAmoled(this, on); recreate() }

        val fullscreen = findViewById<MaterialSwitch>(R.id.openFullscreen)
        fullscreen.isChecked = Settings.openFullscreen(this)
        fullscreen.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Settings.OPEN_FULLSCREEN, on).apply() }

        for ((id, icon, hue) in listOf(
            Triple(R.id.secAppearance, R.drawable.ic_night, IconTiles.Hue.VIOLET),
            Triple(R.id.secViewer, R.drawable.ic_pdf, IconTiles.Hue.BLUE),
            Triple(R.id.secScanner, R.drawable.ic_camera, IconTiles.Hue.TEAL),
            Triple(R.id.secOcr, R.drawable.ic_ocr, IconTiles.Hue.ORANGE),
            Triple(R.id.secTranslate, R.drawable.ic_web, IconTiles.Hue.CYAN)
        )) findViewById<TextView>(id).setCompoundDrawablesRelativeWithIntrinsicBounds(IconTiles.badge(this, icon, hue), null, null, null)

        findViewById<android.view.View>(R.id.btnLanguages).setOnClickListener {
            startActivity(android.content.Intent(this, TranslateLanguagesActivity::class.java))
        }
        val searchable = findViewById<MaterialSwitch>(R.id.searchable)
        searchable.isChecked = Settings.searchableDefault(this)
        searchable.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Settings.SEARCHABLE, on).apply() }
    }

    private fun buildAccents() {
        val box = findViewById<android.widget.LinearLayout>(R.id.accents)
        val d = resources.displayMetrics.density
        val current = Themes.accent(this)
        // swatches in rows of 4 (fits phones and tablets)
        for (row in Themes.Accent.values().toList().chunked(4)) {
            val line = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            box.addView(line)
            for (a in row) {
                val cell = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
                    isClickable = true
                    setOnClickListener { Themes.setAccent(this@SettingsActivity, a); recreate() }
                }
                val dot = android.view.View(this).apply {
                    background = android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                        intArrayOf(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, a.hero1),
                            androidx.core.content.ContextCompat.getColor(this@SettingsActivity, a.hero2))
                    ).apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        if (a == current) setStroke((3 * d).toInt(), androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.accent))
                    }
                }
                cell.addView(dot, android.widget.LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                cell.addView(android.widget.TextView(this).apply {
                    text = a.label
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    if (a == current) setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, (4 * d).toInt(), 0, 0)
                }, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
                line.addView(cell, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            // keep cells the same width on the last row
            repeat(4 - row.size) { line.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(0, 1, 1f)) }
        }
    }
}
