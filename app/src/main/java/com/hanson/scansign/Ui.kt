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

    /** A colour from the current theme (so light, dark and pure-black modes all match). */
    fun themeColor(ctx: android.content.Context, attr: Int, fallbackRes: Int): Int {
        val tv = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId) else tv.data
        } else androidx.core.content.ContextCompat.getColor(ctx, fallbackRes)
    }

    fun surface(ctx: android.content.Context) = themeColor(ctx, com.google.android.material.R.attr.colorSurface, R.color.md_surface)
    fun surfaceVariant(ctx: android.content.Context) = themeColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant, R.color.md_surface_variant)

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
            .setMessage("${file.name}  ·  $sizeTxt" + extra + if (exported) "\n\nA copy is in Downloads › Docora." else "")
            .setPositiveButton("Open") { _, _ -> a.startActivity(PdfEditorActivity.intent(a, file)) }
            .setNeutralButton("Share") { _, _ -> share(a, file) }
            .setNegativeButton("Close", null)
            .setOnDismissListener { RateApp.goodMoment(a) }
            .show()
    }

    fun toast(a: AppCompatActivity, msg: String) = note(a, msg, long = true)

    private var lastNote: Toast? = null

    /**
     * Docora's short message: a dark rounded pill with the app's little logo, near the bottom of the
     * screen (instead of the plain grey system message). A new message replaces the one showing.
     */
    @Suppress("DEPRECATION")
    fun note(ctx: android.content.Context, msg: CharSequence, long: Boolean = false) {
        val c = ctx.applicationContext ?: ctx
        val d = c.resources.displayMetrics.density
        try {
            val row = android.widget.LinearLayout(c).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((14 * d).toInt(), (11 * d).toInt(), (18 * d).toInt(), (11 * d).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 22 * d
                    setColor(android.graphics.Color.argb(242, 28, 32, 44))
                    setStroke(d.toInt().coerceAtLeast(1), android.graphics.Color.argb(46, 255, 255, 255))
                }
            }
            val icon = android.widget.ImageView(c).apply {
                setImageResource(R.drawable.app_logo)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 6 * d)
                }
                clipToOutline = true
            }
            row.addView(icon, android.widget.LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt()).apply { marginEnd = (11 * d).toInt() })
            row.addView(TextView(c).apply {
                text = msg
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                maxLines = 4
                maxWidth = (290 * d).toInt()
                setLineSpacing(0f, 1.1f)
            })
            lastNote?.cancel()
            val t = Toast(c)
            t.view = row
            t.duration = if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            t.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL, 0, (110 * d).toInt())
            t.show()
            lastNote = t
        } catch (_: Throwable) {
            Toast.makeText(c, msg, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    /** Share a PDF, with clear options to make it smaller first (good for WhatsApp / email limits). */
    fun share(a: AppCompatActivity, file: File) = ShareSheet.show(a, file)

    /** Sends a PDF to the Android print system (printers, Save as PDF, etc.). */
    fun print(a: AppCompatActivity, file: File, name: String = file.name) {
        val pm = a.getSystemService(android.content.Context.PRINT_SERVICE) as android.print.PrintManager
        pm.print(name, object : android.print.PrintDocumentAdapter() {
            override fun onLayout(
                old: android.print.PrintAttributes?, new: android.print.PrintAttributes?,
                cancel: android.os.CancellationSignal?, cb: LayoutResultCallback, extras: android.os.Bundle?
            ) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(
                    android.print.PrintDocumentInfo.Builder(name)
                        .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true
                )
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor,
                cancel: android.os.CancellationSignal?, cb: WriteResultCallback
            ) {
                try {
                    file.inputStream().use { input ->
                        java.io.FileOutputStream(dest.fileDescriptor).use { input.copyTo(it) }
                    }
                    cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    cb.onWriteFailed(e.message)
                }
            }
        }, null)
    }

    /** Screen width in dp (phones ~360-430, tablets 600+). */
    fun widthDp(a: android.app.Activity) = a.resources.configuration.screenWidthDp

    fun isTablet(a: android.app.Activity) = a.resources.configuration.smallestScreenWidthDp >= 600

    /** On wide screens, keep content at a comfortable reading width, centred. */
    fun limitWidth(v: View, maxDp: Int) {
        val d = v.resources.displayMetrics.density
        val max = (maxDp * d).toInt()
        if (v.resources.displayMetrics.widthPixels > max) {
            val lp = v.layoutParams
            lp.width = max
            v.layoutParams = lp
        }
    }

    /** Grid columns for cells at least [cellDp] wide. */
    fun columns(a: android.app.Activity, cellDp: Int, min: Int) = kotlin.math.max(min, widthDp(a) / cellDp)
}
