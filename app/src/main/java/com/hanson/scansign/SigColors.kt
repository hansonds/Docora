package com.hanson.scansign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.File

/** Ink colours for signatures: recolour saved ones, or make a coloured copy to place on a page. */
object SigColors {

    /** Where the untouched original of a saved signature is kept, so "Original" can bring it back. */
    private fun originalOf(ctx: Context, f: File) = File(File(Storage.signatureDir(ctx), ".orig"), f.name)

    /** Coloured copies for placing on pages (app storage, so they're never cleaned while in use). */
    private fun colouredDir(ctx: Context) = File(ctx.filesDir, "sigcolor").apply { mkdirs() }

    /** True for signatures (transparent PNGs from My signatures), not for photos placed on a page. */
    fun isSignature(ctx: Context, f: File): Boolean {
        val p = f.absolutePath
        return p.startsWith(Storage.signatureDir(ctx).absolutePath) || p.startsWith(colouredDir(ctx).absolutePath)
    }

    /**
     * Same shape and soft edges, new ink colour. How strongly a pixel shows comes from how much ink
     * it holds (its darkness and opacity), so faint paper traces left in a photographed signature
     * stay invisible instead of turning into coloured speckles.
     */
    fun recolor(src: Bitmap, rgb: Int): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val ink = FloatArray(px.size)
        var strongest = 0f
        for (i in px.indices) {
            val p = px[i]
            val a = (p ushr 24) / 255f
            if (a == 0f) continue
            val lum = (0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)) / 255f
            val v = a * (1f - lum)
            ink[i] = v
            if (v > strongest) strongest = v
        }
        val c = rgb and 0x00FFFFFF
        if (strongest < 0.02f) {
            // an all-light signature (e.g. white ink): keep its shape as it is
            for (i in px.indices) { val a = px[i] ushr 24; px[i] = if (a == 0) 0 else (a shl 24) or c }
        } else {
            for (i in px.indices) {
                var s = ink[i] / strongest
                s = if (s < 0.10f) 0f else ((s - 0.10f) / 0.75f).coerceAtMost(1f)   // drop paper traces, solid ink stays solid
                px[i] = if (s == 0f) 0 else ((s * 255).toInt() shl 24) or c
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun write(b: Bitmap, f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Recolours a saved signature in place. The original is kept once, so "Original" restores it. */
    fun recolorSaved(ctx: Context, f: File, ink: InkColor): Boolean {
        try {
            val orig = originalOf(ctx, f)
            val rgb = ink.rgb
            if (rgb == null) {
                if (orig.exists()) { orig.copyTo(f, overwrite = true); orig.delete() }
                return true
            }
            if (!orig.exists()) { orig.parentFile?.mkdirs(); f.copyTo(orig, overwrite = true) }
            val src = BitmapFactory.decodeFile(orig.absolutePath) ?: return false
            write(recolor(src, rgb), f)
            src.recycle()
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    /** The colour a saved signature has now (Original if it was never recoloured). */
    fun currentInk(ctx: Context, f: File): InkColor {
        if (!originalOf(ctx, f).exists()) return InkColor.ORIGINAL
        try {
            val b = BitmapFactory.decodeFile(f.absolutePath) ?: return InkColor.ORIGINAL
            var found = InkColor.ORIGINAL
            loop@ for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) > 200) {
                    found = InkColor.values().firstOrNull { c -> c.rgb != null && (c.rgb and 0xFFFFFF) == (p and 0xFFFFFF) } ?: InkColor.ORIGINAL
                    break@loop
                }
            }
            b.recycle()
            return found
        } catch (_: Throwable) {
            return InkColor.ORIGINAL
        }
    }

    /** A copy of [original] in [ink] for placing on a page ([original] itself for Original). */
    fun colouredCopy(ctx: Context, original: File, ink: InkColor): File {
        val rgb = ink.rgb ?: return original
        val out = File(colouredDir(ctx), "${original.nameWithoutExtension}_${original.lastModified()}_${ink.name.lowercase()}_v2.png")
        if (out.exists() && out.length() > 0) return out
        val src = BitmapFactory.decodeFile(original.absolutePath) ?: return original
        write(recolor(src, rgb), out)
        src.recycle()
        return out
    }

    /** Paint colour shown for an ink (Original gets a soft mixed swatch). */
    private fun swatch(ctx: Context, ink: InkColor, selected: Boolean, accent: Int): android.graphics.drawable.Drawable {
        val d = ctx.resources.displayMetrics.density
        val fill = ink.rgb
        val dot = if (fill == null) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(28, 28, 36), Color.rgb(36, 72, 200), Color.rgb(196, 28, 36))).apply { shape = GradientDrawable.OVAL }
        else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        dot.setStroke((1 * d).toInt().coerceAtLeast(1), Color.argb(50, 0, 0, 0))
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke((2.5f * d).toInt(), if (selected) accent else Color.TRANSPARENT)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(ring, dot)).apply {
            val inset = (6 * d).toInt()
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    /**
     * A row of round ink swatches with names. The chosen one gets a ring in the app colour and a
     * check mark; tapping one calls [onPick].
     */
    fun swatchRow(ctx: Context, selected: InkColor, onPick: (InkColor) -> Unit): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val accent = ContextCompat.getColor(ctx, Themes.accent(ctx).primary)
        val muted = ContextCompat.getColor(ctx, R.color.md_on_surface_variant)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        var current = selected
        class Cell(val ink: InkColor, val dot: android.widget.FrameLayout, val check: android.widget.ImageView, val label: TextView)
        val cells = ArrayList<Cell>()
        fun paint() {
            for (c in cells) {
                val on = c.ink == current
                c.dot.background = swatch(ctx, c.ink, on, accent)
                c.check.visibility = if (on) View.VISIBLE else View.GONE
                c.label.setTextColor(if (on) accent else muted)
                c.label.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
        for (ink in InkColor.values()) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
                isClickable = true; isFocusable = true
                contentDescription = "${ink.label} ink"
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.argb(30, 128, 128, 128)), null,
                    GradientDrawable().apply { cornerRadius = 16 * d; setColor(Color.WHITE) })
            }
            val dot = android.widget.FrameLayout(ctx)
            val check = android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(Color.WHITE)
            }
            dot.addView(check, android.widget.FrameLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt(), Gravity.CENTER))
            cell.addView(dot, LinearLayout.LayoutParams((52 * d).toInt(), (52 * d).toInt()))
            val label = TextView(ctx).apply {
                text = ink.label; textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, (2 * d).toInt(), 0, 0)
            }
            cell.addView(label)
            cell.setOnClickListener { if (current != ink) { current = ink; paint(); onPick(ink) } }
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            cells.add(Cell(ink, dot, check, label))
        }
        paint()
        return row
    }

    /**
     * Bottom panel to choose an ink colour, with a live preview of the signature [preview]
     * on a transparent-style background.
     */
    fun pick(ctx: Context, title: String, preview: File, selected: InkColor, action: String = "Apply colour", onPick: (InkColor) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val onSurface = ContextCompat.getColor(ctx, R.color.md_on_surface)
        val muted = ContextCompat.getColor(ctx, R.color.md_on_surface_variant)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(4f), dp(20f), dp(20f)) }

        root.addView(TextView(ctx).apply {
            text = title; textSize = 20f; setTextColor(onSurface); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setCompoundDrawablesRelativeWithIntrinsicBounds(IconTiles.badge(ctx, R.drawable.ic_sign, IconTiles.Hue.PINK, 34f), null, null, null)
            compoundDrawablePadding = dp(12f); gravity = Gravity.CENTER_VERTICAL
        })
        root.addView(TextView(ctx).apply {
            text = "Only the ink changes. The shape and soft edges stay the same."
            textSize = 13f; setTextColor(muted); setPadding(0, dp(4f), 0, dp(14f))
        })

        // live preview
        var chosen = selected
        val img = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
        }
        // the real result for each ink, made once from a small copy
        var base: Bitmap? = null
        val made = HashMap<InkColor, Bitmap>()
        fun show(ink: InkColor) {
            val b = base ?: return
            img.setImageBitmap(if (ink.rgb == null) b else made.getOrPut(ink) { recolor(b, ink.rgb) })
        }
        val frame = android.widget.FrameLayout(ctx).apply {
            background = SignatureEditActivity.checkerboard(ctx)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, 18 * d) }
            }
        }
        frame.addView(img, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, dp(150f)))
        Thread {
            val b = try { ImageUtils.thumb(preview, 700) } catch (_: Throwable) { null }
            if (b != null) img.post { base = b; show(chosen) }
        }.start()

        root.addView(swatchRow(ctx, selected) { chosen = it; show(it) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16f) })

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(18f), 0, 0) }
        val cancel = TranslateSheet.outlined(ctx).apply { text = "Cancel"; setOnClickListener { sheet.dismiss() } }
        val apply = com.google.android.material.button.MaterialButton(ctx).apply {
            text = action; setIconResource(R.drawable.ic_check)
            setOnClickListener { sheet.dismiss(); onPick(chosen) }
        }
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginEnd = dp(6f) })
        buttons.addView(apply, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginStart = dp(6f) })
        root.addView(buttons)

        sheet.setContentView(root)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }
}
