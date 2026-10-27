package com.hanson.scansign

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

/** Appearance: light / dark / system, accent colour, pure-black dark mode. */
object Themes {

    enum class Mode(val label: String, val night: Int) {
        SYSTEM("Follow system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("Light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("Dark", AppCompatDelegate.MODE_NIGHT_YES)
    }

    enum class Accent(val label: String, val overlay: Int, val primary: Int, val hero1: Int, val hero2: Int) {
        BLUE("Blue", R.style.ThemeOverlay_App_Accent_Blue, R.color.acc_blue_primary, R.color.acc_blue_hero1, R.color.acc_blue_hero2),
        PURPLE("Purple", R.style.ThemeOverlay_App_Accent_Purple, R.color.acc_purple_primary, R.color.acc_purple_hero1, R.color.acc_purple_hero2),
        TEAL("Teal", R.style.ThemeOverlay_App_Accent_Teal, R.color.acc_teal_primary, R.color.acc_teal_hero1, R.color.acc_teal_hero2),
        GREEN("Green", R.style.ThemeOverlay_App_Accent_Green, R.color.acc_green_primary, R.color.acc_green_hero1, R.color.acc_green_hero2),
        ORANGE("Orange", R.style.ThemeOverlay_App_Accent_Orange, R.color.acc_orange_primary, R.color.acc_orange_hero1, R.color.acc_orange_hero2),
        ROSE("Rose", R.style.ThemeOverlay_App_Accent_Rose, R.color.acc_rose_primary, R.color.acc_rose_hero1, R.color.acc_rose_hero2),
        GRAPHITE("Graphite", R.style.ThemeOverlay_App_Accent_Graphite, R.color.acc_graphite_primary, R.color.acc_graphite_hero1, R.color.acc_graphite_hero2)
    }

    private const val MODE = "theme_mode"
    private const val ACCENT = "theme_accent"
    private const val AMOLED = "theme_amoled"

    /** Bumped whenever appearance changes, so open screens can refresh. */
    var version = 0
        private set

    fun mode(ctx: Context) = runCatching { Mode.valueOf(Settings.prefs(ctx).getString(MODE, Mode.SYSTEM.name)!!) }.getOrDefault(Mode.SYSTEM)
    fun accent(ctx: Context) = runCatching { Accent.valueOf(Settings.prefs(ctx).getString(ACCENT, Accent.BLUE.name)!!) }.getOrDefault(Accent.BLUE)
    fun amoled(ctx: Context) = Settings.prefs(ctx).getBoolean(AMOLED, false)

    fun setMode(ctx: Context, m: Mode) {
        Settings.prefs(ctx).edit().putString(MODE, m.name).apply()
        version++
        AppCompatDelegate.setDefaultNightMode(m.night)
    }

    fun setAccent(ctx: Context, a: Accent) { Settings.prefs(ctx).edit().putString(ACCENT, a.name).apply(); version++ }
    fun setAmoled(ctx: Context, on: Boolean) { Settings.prefs(ctx).edit().putBoolean(AMOLED, on).apply(); version++ }

    /** Called once at app start. */
    fun init(ctx: Context) = AppCompatDelegate.setDefaultNightMode(mode(ctx).night)

    /** Apply accent + AMOLED overlays to an activity (before setContentView). */
    fun apply(a: Activity) {
        a.theme.applyStyle(accent(a).overlay, true)
        val night = (a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night && amoled(a)) a.theme.applyStyle(R.style.ThemeOverlay_App_Amoled, true)
    }

    /** Gradient for the home banner in the current accent. */
    fun heroBackground(ctx: Context): GradientDrawable {
        val acc = accent(ctx)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(ContextCompat.getColor(ctx, acc.hero1), ContextCompat.getColor(ctx, acc.hero2))
        ).apply { cornerRadius = 22 * ctx.resources.displayMetrics.density }
    }
}

/** All screens extend this so the chosen theme applies everywhere. */
open class BaseActivity : AppCompatActivity() {
    private var appliedVersion = -1
    /** Screens holding unsaved work can opt out of refreshing when the theme changes. */
    protected open val recreateOnThemeChange = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Themes.apply(this)
        appliedVersion = Themes.version
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (recreateOnThemeChange && appliedVersion != Themes.version) recreate()
    }
}
