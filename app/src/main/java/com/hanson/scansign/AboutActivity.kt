package com.hanson.scansign

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutActivity : BaseActivity() {

    /** One group of features: colour, icon, title and what it does. */
    private class Section(val hue: IconTiles.Hue, val icon: Int, val title: String, val points: List<String>, val beta: Boolean = false)

    private val sections = listOf(
        Section(IconTiles.Hue.VIOLET, R.drawable.ic_edit_doc, "Edit PDF", listOf(
            "Tap any text and type — it keeps the original font, size, colour and spacing",
            "Bold, italic, alignment (left, centre, right, justified) and a widen handle",
            "Move, resize, replace or delete pictures and logos",
            "Find & replace words in the whole document",
            "Black out private details — removed for good when you save",
            "Works on scanned pages too"
        ), beta = true),
        Section(IconTiles.Hue.TEAL, R.drawable.ic_camera, "Scan", listOf(
            "AI scanner with edge detection and auto capture",
            "Removes shadows, stains and fingers",
            "Pick the look on the first page — the whole document uses it",
            "Adjust corners by hand with a magnifier",
            "ID cards (both sides on one page), A4 / Letter",
            "Searchable scans: the text can be found and copied"
        )),
        Section(IconTiles.Hue.PINK, R.drawable.ic_sign, "Sign & fill", listOf(
            "Signatures from a photo (background removed) or drawn by finger",
            "Save signatures as transparent PNG",
            "Fill PDF forms right on the page",
            "Quick fill: your name, initials, address and more in one tap",
            "Text with fonts, bold, italic and colours · stamps · dates"
        )),
        Section(IconTiles.Hue.ORANGE, R.drawable.ic_draw, "Mark up", listOf(
            "Draw, highlight, shapes, arrows and whiteout",
            "Move, resize and rotate everything you add",
            "Undo and redo"
        )),
        Section(IconTiles.Hue.BLUE, R.drawable.ic_pdf, "Read", listOf(
            "Opens any PDF — books of thousands of pages open almost instantly",
            "Full-screen reading, smooth zoom and fast scrolling",
            "Find text with highlights, even in scans",
            "Page thumbnails, night reading, select and copy text"
        )),
        Section(IconTiles.Hue.CYAN, R.drawable.ic_web, "Translate", listOf(
            "Translate selected text or a whole page into 59 languages",
            "See the page translated in place — same layout, colours and size",
            "Save the translation as a new PDF (documents up to 10 pages)",
            "Detects the language by itself — works on scanned pages too",
            "Download only the languages you need (about 30 MB each), then it works offline",
            "Copy or share the translation"
        )),
        Section(IconTiles.Hue.GREEN, R.drawable.ic_tools, "PDF tools", listOf(
            "Merge, split, extract and organize pages",
            "Compress — see the new size before you share",
            "PDF to JPG and images to PDF",
            "Text recognition (OCR): English, German and more, plus Hindi",
            "Password lock and unlock, watermarks, page numbers, print"
        )),
        Section(IconTiles.Hue.AMBER, R.drawable.ic_folder, "Your files", listOf(
            "Recently opened and recently edited, in separate tabs",
            "PDFs from WhatsApp, Gmail or Files stay available later",
            "Save over the original or as a new copy",
            "Share with size options for WhatsApp and email"
        )),
        Section(IconTiles.Hue.SLATE, R.drawable.ic_lock, "Private by design", listOf(
            "Everything happens on your phone — nothing is uploaded",
            "No account, no ads, no tracking, no cloud backup of your documents",
            "Light, dark or system theme, 7 colours, pure black mode"
        ))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        Ui.limitWidth(findViewById(R.id.content), 680)
        val d = resources.displayMetrics.density

        // header in the theme colours
        findViewById<View>(R.id.hero).background = Themes.heroBackground(this)
        findViewById<FrameLayout>(R.id.logoBox).apply {
            background = GradientDrawable().apply { cornerRadius = 24 * d; setColor(Color.WHITE) }
            clipToOutline = true
            setPadding((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
        }
        findViewById<TextView>(R.id.version).apply {
            text = "Version ${BuildConfig.VERSION_NAME}"
            background = GradientDrawable().apply { cornerRadius = 50 * d; setColor(Color.argb(55, 255, 255, 255)) }
        }
        val badges = findViewById<ChipGroup>(R.id.badges)
        for (b in listOf("✓ 100% free", "✓ No ads", "✓ No account", "✓ Works offline")) {
            badges.addView(Chip(this).apply {
                text = b
                isClickable = false
                setTextColor(Color.WHITE)
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(Color.argb(45, 255, 255, 255))
                chipStrokeWidth = 0f
                textSize = 12f
            })
        }

        // feature cards
        val box = findViewById<LinearLayout>(R.id.features)
        for (s in sections) box.addView(card(s), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (12 * d).toInt() })

        // developer
        findViewById<TextView>(R.id.devAvatar).background = IconTiles.gradient(this, IconTiles.Hue.VIOLET.top, IconTiles.Hue.INDIGO.bottom, 26f)
        findViewById<View>(R.id.btnWeb).setOnClickListener { open("https://phoenicx.org") }
        findViewById<View>(R.id.btnPrivacy).setOnClickListener { open("https://hansonds.github.io/Docora/privacy/") }

        // engines + credits
        findViewById<TextView>(R.id.secStatus).setCompoundDrawablesRelativeWithIntrinsicBounds(
            IconTiles.badge(this, R.drawable.ic_settings, IconTiles.Hue.CYAN), null, null, null)
        findViewById<TextView>(R.id.engine).text = buildString {
            append(if (App.openCvReady) "✓  Edge detection — ready" else "✗  Edge detection — not loaded (manual crop still works)")
            append("\n✓  PDF engine — ready")
            append("\n✓  Text recognition — on-device")
        }
        findViewById<TextView>(R.id.credits).text =
            "OpenCV · PdfBox-Android · AndroidX & Material Components (Apache 2.0)\n" +
            "Google ML Kit document scanner, text recognition & translation\n" +
            "Liberation Sans & Serif fonts (SIL Open Font License 1.1)"
        findViewById<View>(R.id.btnFontLicence).setOnClickListener {
            val text = try { assets.open("fonts/LICENSE-Liberation.txt").bufferedReader().use { it.readText() } } catch (_: Throwable) { "" }
            MaterialAlertDialogBuilder(this).setTitle("Font licence").setMessage(text).setPositiveButton("OK", null).show()
        }
    }

    /** A feature card: gradient icon tile, title (with BETA badge) and a tidy list. */
    private fun card(s: Section): View {
        val d = resources.displayMetrics.density
        val card = MaterialCardView(this).apply { radius = 20 * d }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tile = FrameLayout(this)
        val icon = ImageView(this)
        tile.addView(icon, FrameLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt(), Gravity.CENTER))
        IconTiles.feature(tile, icon, s.icon, s.hue)
        head.addView(tile, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
        head.addView(TextView(this).apply {
            text = s.title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding((12 * d).toInt(), 0, 0, 0)
        })
        if (s.beta) head.addView(TextView(this).apply {
            text = "BETA"
            textSize = 9f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = ContextCompat.getDrawable(this@AboutActivity, R.drawable.bg_beta_badge)
            setPadding((5 * d).toInt(), (1 * d).toInt(), (5 * d).toInt(), (1 * d).toInt())
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * d).toInt() })
        inner.addView(head)
        val dotColor = s.hue.bottom
        for (p in s.points) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (8 * d).toInt(), 0, 0) }
            row.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
            }, LinearLayout.LayoutParams((6 * d).toInt(), (6 * d).toInt()).apply {
                topMargin = (7 * d).toInt(); marginStart = (17 * d).toInt(); marginEnd = (12 * d).toInt()
            })
            row.addView(TextView(this).apply {
                text = p
                textSize = 14f
                setLineSpacing(0f, 1.15f)
                setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.md_on_surface_variant))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            inner.addView(row)
        }
        card.addView(inner)
        return card
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }
}
