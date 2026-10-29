package com.hanson.scansign

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * If Docora ever closes because of an error, the details are kept on the phone and the next start
 * offers to send them (the user chooses the app and can read everything first). Nothing is sent automatically.
 */
object CrashReport {

    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("Docora ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                    append("Time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
                    append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                    append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("Thread: ").append(thread.name).append("\n\n")
                    append(sw.toString().take(12000))
                }
                file(app).writeText(text)
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** Call from the home screen: offers to send the report of the last crash, once. */
    fun offer(a: AppCompatActivity) {
        val f = file(a)
        if (!f.exists()) return
        val text = try { f.readText() } catch (_: Throwable) { "" }
        f.delete()
        if (text.isBlank()) return
        MaterialAlertDialogBuilder(a)
            .setTitle("Docora closed unexpectedly")
            .setMessage("Sorry about that. You can send the error details so it can be fixed — you'll see the report before sending. It contains no documents or personal data.")
            .setPositiveButton("Send report") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Docora error report")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try { a.startActivity(Intent.createChooser(i, "Send report")) } catch (_: Throwable) {}
            }
            .setNegativeButton("Not now", null)
            .show()
    }
}
