package com.hanson.scansign

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Draw a signature with a finger; saved as a transparent PNG like photographed signatures. */
class DrawSignatureActivity : BaseActivity() {
    companion object { const val EXTRA_OUT = "out" }

    private val inks = intArrayOf(Color.rgb(16, 32, 120), Color.rgb(15, 15, 20), Color.rgb(36, 87, 245), Color.rgb(190, 20, 40))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_draw_signature)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        val pad = findViewById<SignaturePadView>(R.id.pad)
        val width = findViewById<Slider>(R.id.width)
        width.addOnChangeListener { _, v, _ -> pad.inkWidthDp = v }

        val box = findViewById<LinearLayout>(R.id.colors)
        val d = resources.displayMetrics.density
        val dots = ArrayList<View>()
        fun refresh() = dots.forEachIndexed { i, v ->
            (v.background as GradientDrawable).setStroke(if (inks[i] == pad.inkColor) (3 * d).toInt() else 0, Color.rgb(0, 194, 168))
        }
        for (c in inks) {
            val v = View(this)
            v.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
            v.layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply { marginEnd = (10 * d).toInt(); topMargin = (4 * d).toInt() }
            v.setOnClickListener { pad.inkColor = c; refresh() }
            box.addView(v); dots.add(v)
        }
        refresh()

        findViewById<MaterialButton>(R.id.btnClear).setOnClickListener { pad.clear() }
        findViewById<MaterialButton>(R.id.btnUndo).setOnClickListener { pad.undo() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val bmp = pad.export()
            if (bmp == null) { Ui.toast(this, "Draw your signature first"); return@setOnClickListener }
            lifecycleScope.launch {
                val out = File(Storage.signatureDir(this@DrawSignatureActivity), "sig_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { ImageUtils.savePng(bmp, out) }
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUT, out.absolutePath))
                finish()
            }
        }
    }
}
