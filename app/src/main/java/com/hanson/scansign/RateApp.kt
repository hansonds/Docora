package com.hanson.scansign

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Asking for a Play Store rating, the way Google allows it:
 * - Google's own in-app review panel (stars + comment, without leaving Docora),
 *   shown only after a few good moments (saved / shared PDFs), never on the first days,
 *   and at most once every 60 days. Google may still decide not to show it.
 * - "Rate Docora" buttons that open the Play Store page at any time.
 * Docora never asks "do you like the app?" first and never offers anything for a rating.
 */
object RateApp {

    private const val GOOD_MOMENTS = 3
    private const val MIN_DAYS_INSTALLED = 2L
    private const val DAYS_BETWEEN = 60L
    private const val DAY = 24L * 60 * 60 * 1000

    private fun prefs(c: Context) = c.getSharedPreferences("rate", Context.MODE_PRIVATE)

    private fun installedAt(c: Context): Long = try {
        c.packageManager.getPackageInfo(c.packageName, 0).firstInstallTime
    } catch (_: Throwable) { System.currentTimeMillis() }

    /** Call after something went well (a PDF saved or shared). Shows Google's review panel when it's a good time. */
    fun goodMoment(a: Activity) {
        val p = prefs(a)
        val count = p.getInt("good", 0) + 1
        p.edit().putInt("good", count).apply()
        val now = System.currentTimeMillis()
        if (count < GOOD_MOMENTS) return
        if (now - installedAt(a) < MIN_DAYS_INSTALLED * DAY) return
        if (now - p.getLong("asked", 0L) < DAYS_BETWEEN * DAY) return
        if (a.isFinishing || a.isDestroyed) return
        p.edit().putLong("asked", now).apply()
        try {
            val manager = ReviewManagerFactory.create(a)
            manager.requestReviewFlow().addOnCompleteListener { req ->
                if (req.isSuccessful && !a.isFinishing && !a.isDestroyed) {
                    try { manager.launchReviewFlow(a, req.result) } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            // no Play Store on this phone: nothing to show
        }
    }

    /** Opens Docora's Play Store page (the Play Store app, or the website if it isn't installed). */
    fun openStore(c: Context) {
        val id = c.packageName
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
        }
    }
}
