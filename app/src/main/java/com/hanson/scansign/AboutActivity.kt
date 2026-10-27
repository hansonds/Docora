package com.hanson.scansign

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<TextView>(R.id.version).text = "Version ${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.engine).text = buildString {
            append(if (App.openCvReady) "✔ Edge detection engine: ready" else "✘ Edge detection engine: not loaded (manual crop only)")
            append("\n✔ PDF engine: ready")
        }
        findViewById<MaterialButton>(R.id.btnWeb).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://phoenicx.org")))
            } catch (e: Exception) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
