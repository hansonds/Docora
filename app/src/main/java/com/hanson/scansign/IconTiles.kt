package com.hanson.scansign

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Modern rounded icon tiles used across the app: vivid gradient squares with white icons. */
object IconTiles {

    enum class Hue(val top: Int, val bottom: Int) {
        BLUE(0xFF4F8BFF.toInt(), 0xFF2350E0.toInt()),
        INDIGO(0xFF7C83FF.toInt(), 0xFF4338CA.toInt()),
        VIOLET(0xFFA77BFF.toInt(), 0xFF6D28D9.toInt()),
        PINK(0xFFFF6FB5.toInt(), 0xFFD0256F.toInt()),
        RED(0xFFFF6B6B.toInt(), 0xFFD02436.toInt()),
        ORANGE(0xFFFFA14A.toInt(), 0xFFE8590C.toInt()),
        AMBER(0xFFFFC94A.toInt(), 0xFFD98B06.toInt()),
        GREEN(0xFF4ADE80.toInt(), 0xFF15A34A.toInt()),
        TEAL(0xFF2DD4BF.toInt(), 0xFF0E8C7E.toInt()),
        CYAN(0xFF38D3F5.toInt(), 0xFF0786B0.toInt()),
        SLATE(0xFF8C97AD.toInt(), 0xFF4B566B.toInt())
    }

    private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

    fun gradient(ctx: Context, top: Int, bottom: Int, radiusDp: Float = 15f) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)
    ).apply { cornerRadius = dp(ctx, radiusDp) }

    /** Paints [box] as a gradient tile with a soft coloured glow and [icon] in white. */
    fun feature(box: View, icon: ImageView, iconRes: Int, top: Int, bottom: Int) {
        val ctx = box.context
        val radius = dp(ctx, 15f)
        box.backgroundTintList = null
        box.background = gradient(ctx, top, bottom)
        box.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
        }
        box.elevation = dp(ctx, 4f)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            box.outlineSpotShadowColor = bottom
            box.outlineAmbientShadowColor = bottom
        }
        icon.setImageResource(iconRes)
        icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
    }

    fun feature(box: View, icon: ImageView, iconRes: Int, hue: Hue) = feature(box, icon, iconRes, hue.top, hue.bottom)

    /** A small gradient tile with a white icon, as a drawable (for text labels and headers). */
    fun badge(ctx: Context, iconRes: Int, hue: Hue, sizeDp: Float = 32f): android.graphics.drawable.Drawable {
        val size = dp(ctx, sizeDp).toInt()
        val inset = (size * 0.22f).toInt()
        val bg = gradient(ctx, hue.top, hue.bottom, sizeDp * 0.32f).apply { setSize(size, size) }
        val ic = ctx.getDrawable(iconRes)!!.mutate().apply { setTint(Color.WHITE) }
        return android.graphics.drawable.LayerDrawable(arrayOf(bg, ic)).apply {
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, size, size)
        }
    }

    /** Gradient from the user's accent colour (for things that follow the theme). */
    fun accentFeature(box: View, icon: ImageView, iconRes: Int) {
        val c = ContextCompat.getColor(box.context, Themes.accent(box.context).primary)
        feature(box, icon, iconRes, lighten(c, 0.22f), darken(c, 0.18f))
    }

    fun lighten(c: Int, f: Float) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
    )

    fun darken(c: Int, f: Float) = Color.rgb(
        (Color.red(c) * (1 - f)).toInt(), (Color.green(c) * (1 - f)).toInt(), (Color.blue(c) * (1 - f)).toInt()
    )
}

/**
 * Action button for bottom bars: a soft rounded icon tile with the label underneath.
 * In XML it takes android:text and app:icon like a MaterialButton; android:tag="dark" for dark screens.
 */
class TileButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    private val icon = ImageView(ctx)
    private val label = TextView(ctx)
    private val dark: Boolean
    private val tile = GradientDrawable()

    var text: CharSequence
        get() = label.text
        set(v) { label.text = v; contentDescription = v }

    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumWidth = (64 * d).toInt()
        setPadding((4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt())
        val tv = android.util.TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)) {
            setBackgroundResource(tv.resourceId)
        }
        dark = (attrs?.getAttributeValue(ANDROID_NS, "tag") ?: tag?.toString()) == "dark"

        val accent = ContextCompat.getColor(ctx, Themes.accent(ctx).primary)
        tile.cornerRadius = 14 * d
        tile.setColor(if (dark) Color.argb(40, 255, 255, 255) else Color.argb(30, Color.red(accent), Color.green(accent), Color.blue(accent)))
        icon.background = tile
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = (8 * d).toInt()
        icon.setPadding(p, p, p, p)
        icon.imageTintList = ColorStateList.valueOf(if (dark) Color.WHITE else accent)
        icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(icon, LayoutParams((52 * d).toInt(), (38 * d).toInt()))

        label.textSize = 11.5f
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        label.setTextColor(if (dark) Color.argb(230, 255, 255, 255) else ContextCompat.getColor(ctx, R.color.md_on_surface_variant))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * d).toInt() })

        if (attrs != null) {
            val iconRes = attrs.getAttributeResourceValue(APP_NS, "icon", 0)
            if (iconRes != 0) setIcon(iconRes)
            val textRes = attrs.getAttributeResourceValue(ANDROID_NS, "text", 0)
            text = if (textRes != 0) ctx.getString(textRes) else attrs.getAttributeValue(ANDROID_NS, "text") ?: ""
        }
    }

    fun setIcon(res: Int) = icon.setImageResource(res)

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.4f
    }

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val APP_NS = "http://schemas.android.com/apk/res-auto"
    }
}
