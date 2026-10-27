package com.hanson.scansign

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val prefs = Settings.prefs(this)

        val scanner = findViewById<RadioGroup>(R.id.scanner)
        scanner.check(if (Settings.useAiScanner(this)) R.id.scannerAi else R.id.scannerBuiltin)
        scanner.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Settings.SCANNER, if (id == R.id.scannerAi) "ai" else "builtin").apply()
        }

        val ocr = findViewById<RadioGroup>(R.id.ocr)
        ocr.check(if (Ocr.script(this) == Ocr.Script.DEVANAGARI) R.id.ocrDeva else R.id.ocrLatin)
        ocr.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString(Settings.OCR_SCRIPT, if (id == R.id.ocrDeva) Ocr.Script.DEVANAGARI.name else Ocr.Script.LATIN.name).apply()
        }

        val searchable = findViewById<MaterialSwitch>(R.id.searchable)
        searchable.isChecked = Settings.searchableDefault(this)
        searchable.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Settings.SEARCHABLE, on).apply() }
    }
}
