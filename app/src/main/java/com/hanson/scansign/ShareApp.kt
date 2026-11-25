package com.hanson.scansign

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.widget.Toast
import java.io.File

/**
 * "Share Docora": sends a picture card (logo, features, QR code) together with a short message
 * and the Play Store link, to WhatsApp, Messages, Telegram, e-mail and so on.
 */
object ShareApp {

    fun link(c: Context) = "https://play.google.com/store/apps/details?id=${c.packageName}"

    private fun message(c: Context) = """
        Try Docora, a free PDF editor & scanner for Android 📄

        ✅ Scan documents with AI edge detection
        ✅ Edit PDF text, sign and fill forms
        ✅ Translate pages into 59 languages, offline
        ✅ 100% free: no ads, no watermark, no account

        Get it on Google Play:
        ${link(c)}
    """.trimIndent()

    fun share(a: Activity) {
        val text = message(a)
        val card = try { makeCard(a) } catch (_: Throwable) { null }
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Docora — free PDF editor & scanner")
            if (card != null) {
                val uri = Storage.uriFor(a, card)
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Docora", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else type = "text/plain"
        }
        try {
            a.startActivity(Intent.createChooser(send, "Share Docora with friends"))
        } catch (_: Throwable) {
            Toast.makeText(a, "No app found to share with", Toast.LENGTH_SHORT).show()
        }
    }

    /** Draws the share picture (1080 × 1350) and saves it in the cache. */
    private fun makeCard(c: Context): File {
        val w = 1080; val h = 1350
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // background: brand gradient with soft circles
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(36, 87, 245), Color.rgb(106, 61, 240), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = Color.argb(28, 255, 255, 255)
        cv.drawCircle(w * 0.92f, h * 0.06f, 260f, p)
        cv.drawCircle(w * 0.04f, h * 0.55f, 200f, p)
        cv.drawCircle(w * 0.85f, h * 0.98f, 220f, p)

        // logo, name, tagline
        BitmapFactory.decodeResource(c.resources, R.drawable.app_logo)?.let { logo ->
            val s = 170f
            val left = (w - s) / 2
            p.color = Color.argb(60, 0, 0, 0)
            cv.drawRoundRect(RectF(left - 4, 66f, left + s + 4, 66f + s + 10), 44f, 44f, p)
            cv.drawBitmap(logo, null, RectF(left, 60f, left + s, 60f + s), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        t.textSize = 88f; t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        cv.drawText("Docora", w / 2f, 330f, t)
        t.textSize = 42f; t.typeface = Typeface.DEFAULT; t.color = Color.argb(225, 255, 255, 255)
        cv.drawText("PDF Editor & Scanner", w / 2f, 390f, t)

        // white card with the features
        val rowH = 80f; val pad = 34f
        val card = RectF(64f, 432f, w - 64f, 432f + pad * 2 + rowH * 6)
        p.color = Color.argb(34, 0, 0, 0)
        cv.drawRoundRect(RectF(card.left + 6, card.top + 8, card.right - 6, card.bottom + 8), 44f, 44f, p)
        p.color = Color.WHITE
        cv.drawRoundRect(card, 44f, 44f, p)
        val features = listOf(
            "Scan documents with AI edge detection" to Color.rgb(0, 150, 136),
            "Edit PDF text in the original font" to Color.rgb(36, 87, 245),
            "Sign and fill forms" to Color.rgb(233, 30, 99),
            "Translate pages into 59 languages" to Color.rgb(255, 143, 0),
            "Searchable scans with text recognition" to Color.rgb(106, 61, 240),
            "Works offline, files stay on your phone" to Color.rgb(67, 160, 71),
        )
        val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 32, 48); textSize = 38f }
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 7f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val fm = f.fontMetrics
        for ((i, pair) in features.withIndex()) {
            val (label, col) = pair
            val cx = card.left + 76f; val cy = card.top + pad + rowH * i + rowH / 2
            val y = cy - (fm.ascent + fm.descent) / 2   // text centred on the tick
            p.color = col
            cv.drawCircle(cx, cy, 28f, p)
            val path = android.graphics.Path().apply { moveTo(cx - 12f, cy + 1f); lineTo(cx - 3f, cy + 10f); lineTo(cx + 13f, cy - 9f) }
            cv.drawPath(path, tick)
            cv.drawText(label, cx + 56f, y, f)
        }

        // "100% free" ribbon
        t.textSize = 36f; t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); t.color = Color.WHITE
        val free = "100% FREE  ·  NO ADS  ·  NO WATERMARK"
        val fw = t.measureText(free) + 80f
        p.color = Color.argb(60, 255, 255, 255)
        val rTop = card.bottom + 36f
        cv.drawRoundRect(RectF((w - fw) / 2, rTop, (w + fw) / 2, rTop + 68f), 34f, 34f, p)
        cv.drawText(free, w / 2f, rTop + 34f - (t.fontMetrics.ascent + t.fontMetrics.descent) / 2, t)

        // QR code + "Get it on Google Play"
        val qr = BitmapFactory.decodeResource(c.resources, R.drawable.share_qr, BitmapFactory.Options().apply { inScaled = false })
        val qs = 180f; val qx = 96f; val qy = h - 44f - qs
        p.color = Color.WHITE
        cv.drawRoundRect(RectF(qx - 12, qy - 12, qx + qs + 12, qy + qs + 12), 22f, 22f, p)
        if (qr != null) cv.drawBitmap(qr, null, RectF(qx, qy, qx + qs, qy + qs), Paint())   // no smoothing: keeps the code sharp
        val l = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 54f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        cv.drawText("Get it on Google Play", qx + qs + 56f, qy + 78f, l)
        l.textSize = 34f; l.typeface = Typeface.DEFAULT; l.color = Color.argb(220, 255, 255, 255)
        cv.drawText("Scan the code or tap the link", qx + qs + 56f, qy + 132f, l)

        val dir = File(c.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "Docora.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return out
    }
}
