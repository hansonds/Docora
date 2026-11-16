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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    /** Same shape and soft edges, new ink colour (the transparency is kept). */
    fun recolor(src: Bitmap, rgb: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width; val h = out.height
        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        val c = rgb and 0x00FFFFFF
        for (i in px.indices) {
            val a = px[i] ushr 24
            px[i] = if (a == 0) 0 else (a shl 24) or c
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun write(b: Bitmap, f: File) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Recolours a saved signature in place. The original is kept once, so "Original" restores it. */
    fun recolorSaved(ctx: Context, f: File, ink: InkColor): Boolean = try {
        val orig = originalOf(ctx, f)
        if (ink.rgb == null) {
            if (orig.exists()) { orig.copyTo(f, overwrite = true); orig.delete() }
            true
        } else {
            if (!orig.exists()) { orig.parentFile?.mkdirs(); f.copyTo(orig, overwrite = true) }
            val src = BitmapFactory.decodeFile(orig.absolutePath) ?: return false
            write(recolor(src, ink.rgb), f)
            src.recycle()
            true
        }
    } catch (_: Throwable) { false }

    /** The colour a saved signature has now (Original if it was never recoloured). */
    fun currentInk(ctx: Context, f: File): InkColor {
        if (!originalOf(ctx, f).exists()) return InkColor.ORIGINAL
        return try {
            val b = BitmapFactory.decodeFile(f.absolutePath) ?: return InkColor.ORIGINAL
            var found = InkColor.ORIGINAL
            loop@ for (y in 0 until b.height step 4) for (x in 0 until b.width step 4) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) > 200) { found = InkColor.values().firstOrNull { it.rgb != null && (it.rgb and 0xFFFFFF) == (p and 0xFFFFFF) } ?: InkColor.ORIGINAL; break@loop }
            }
            b.recycle(); found
        } catch (_: Throwable) { InkColor.ORIGINAL }
    }

    /** A copy of [original] in [ink] for placing on a page ([original] itself for Original). */
    fun colouredCopy(ctx: Context, original: File, ink: InkColor): File {
        val rgb = ink.rgb ?: return original
        val out = File(colouredDir(ctx), "${original.nameWithoutExtension}_${original.lastModified()}_${ink.name.lowercase()}.png")
        if (out.exists() && out.length() > 0) return out
        val src = BitmapFactory.decodeFile(original.absolutePath) ?: return original
        write(recolor(src, rgb), out)
        src.recycle()
        return out
    }

    /** A row of round colour swatches with names; the chosen one gets a ring. */
    fun swatchRow(ctx: Context, selected: InkColor, onPick: (InkColor) -> Unit): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        val accent = ContextCompat.getColor(ctx, Themes.accent(ctx).primary)
        val muted = ContextCompat.getColor(ctx, R.color.md_on_surface_variant)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        var current = selected
        val dots = ArrayList<Pair<InkColor, View>>()
        fun paint() {
            for ((ink, v) in dots) {
                val fill = ink.rgb
                v.background = if (fill == null) GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(20, 20, 30), Color.rgb(16, 48, 160), Color.rgb(200, 30, 40))).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(((if (ink == current) 4 else 1) * d).toInt(), if (ink == current) accent else Color.argb(60, 128, 128, 128))
                } else GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(fill)
                    setStroke(((if (ink == current) 4 else 1) * d).toInt(), if (ink == current) accent else Color.argb(60, 128, 128, 128))
                }
            }
        }
        for (ink in InkColor.values()) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                isClickable = true; isFocusable = true
                contentDescription = ink.label
            }
            val dot = View(ctx)
            cell.addView(dot, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
            cell.addView(TextView(ctx).apply {
                text = ink.label; textSize = 11.5f; setTextColor(muted); gravity = Gravity.CENTER
                setPadding(0, (4 * d).toInt(), 0, 0)
            })
            cell.setOnClickListener { current = ink; paint(); onPick(ink) }
            row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            dots.add(ink to dot)
        }
        paint()
        return row
    }

    /** Dialog to choose an ink colour. */
    fun pick(ctx: Context, title: String, selected: InkColor, onPick: (InkColor) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        var chosen = selected
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), 0) }
        box.addView(swatchRow(ctx, selected) { chosen = it })
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("Apply") { _, _ -> onPick(chosen) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
