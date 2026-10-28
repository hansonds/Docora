package com.hanson.scansign

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible entry point for PDFs opened or shared from other apps (WhatsApp, Files, Gmail…).
 * It hands the file to the editor in Docora's own task, so Docora shows up as itself in
 * Recents instead of inside the other app.
 */
class OpenPdfActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri == null) {
            Toast.makeText(this, "Could not open that PDF", Toast.LENGTH_LONG).show()
        } else {
            // pass on exactly the access we were given (read, and write if allowed)
            val grants = i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val open = Intent(this, PdfEditorActivity::class.java)
                .setDataAndType(uri, "application/pdf")
                .putExtra(PdfEditorActivity.EXTRA_URI, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or grants or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                startActivity(open)
            } catch (e: SecurityException) {
                // couldn't pass on write access: open read-only
                startActivity(open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        finish()
    }
}
