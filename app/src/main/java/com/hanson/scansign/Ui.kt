package com.hanson.scansign

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Small shared UI helpers: progress dialog for long jobs, result dialog, padded dialog content. */
object Ui {

    fun padded(a: AppCompatActivity, v: View): View {
        val pad = (22 * a.resources.displayMetrics.density).toInt()
        return FrameLayout(a).apply {
            setPadding(pad, pad / 3, pad, 0)
            addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Runs [work] on a background thread with a progress dialog. [work] receives a progress callback.
     * Errors are shown as a friendly message.
     */
    fun <T> runJob(a: AppCompatActivity, title: String, work: (Progress) -> T, done: (T) -> Unit) {
        val view = LayoutInflater.from(a).inflate(R.layout.dialog_progress, null)
        val text = view.findViewById<TextView>(R.id.progressText)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.progressBar)
        text.text = "$title…"
        val dlg = MaterialAlertDialogBuilder(a).setTitle(title).setView(view).setCancelable(false).show()
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(work { d, t ->
                        a.runOnUiThread {
                            if (t > 0) {
                                bar.isIndeterminate = false
                                bar.max = t
                                bar.setProgressCompat(d, true)
                                text.text = "$title… $d of $t"
                            }
                        }
                    })
                } catch (e: Throwable) {
                    Result.failure<T>(e)
                }
            }
            try { dlg.dismiss() } catch (_: Throwable) {}
            result.onSuccess(done).onFailure { e ->
                val msg = when (e) {
                    is InvalidPasswordException -> "This PDF is password-protected. Unlock it first with the Unlock tool (or check the password)."
                    is OutOfMemoryError -> "The file is too large to process on this phone."
                    else -> "Something went wrong: ${e.message ?: e.javaClass.simpleName}"
                }
                MaterialAlertDialogBuilder(a).setTitle("Couldn't finish").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    /** Standard "saved" dialog for a new PDF ([exported] = a copy went to Downloads). */
    fun showSaved(a: AppCompatActivity, file: File, exported: Boolean, extra: String = "") {
        val sizeTxt = android.text.format.Formatter.formatShortFileSize(a, file.length())
        MaterialAlertDialogBuilder(a)
            .setTitle("Saved")
            .setMessage("${file.name}  ·  $sizeTxt" + extra + if (exported) "\n\nA copy is in Downloads › PDF ScanSign." else "")
            .setPositiveButton("Open") { _, _ -> a.startActivity(PdfEditorActivity.intent(a, file)) }
            .setNeutralButton("Share") { _, _ -> Storage.sharePdf(a, file) }
            .setNegativeButton("Close", null)
            .show()
    }

    fun toast(a: AppCompatActivity, msg: String) = Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
}
