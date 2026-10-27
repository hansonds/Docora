package com.hanson.scansign

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** One recognised line of text and where it is in the image (pixels). */
class OcrLine(val text: String, val box: Rect)

/** On-device text recognition (Google ML Kit, works offline). Call from a background thread. */
object Ocr {

    enum class Script(val label: String) {
        LATIN("English & other Latin-script languages"),
        DEVANAGARI("Hindi, Marathi, Nepali (Devanagari)")
    }

    fun script(ctx: Context): Script =
        runCatching { Script.valueOf(Settings.prefs(ctx).getString(Settings.OCR_SCRIPT, Script.LATIN.name)!!) }
            .getOrDefault(Script.LATIN)

    private fun client(s: Script): TextRecognizer = when (s) {
        Script.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Script.DEVANAGARI -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    /** Recognises text; returns the full text and the individual lines with positions. */
    fun recognize(ctx: Context, bmp: Bitmap): Pair<String, List<OcrLine>> {
        val c = client(script(ctx))
        try {
            val result = Tasks.await(c.process(InputImage.fromBitmap(bmp, 0)))
            val lines = ArrayList<OcrLine>()
            for (block in result.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    if (line.text.isNotBlank()) lines.add(OcrLine(line.text, box))
                }
            }
            return Pair(result.text, lines)
        } finally {
            try { c.close() } catch (_: Throwable) {}
        }
    }
}
