package com.hanson.scansign

import android.content.Context
import android.content.SharedPreferences

/** App-wide settings keys. */
object Settings {
    const val SCANNER = "scanner"           // "ai" or "builtin"
    const val OCR_SCRIPT = "ocr_script"     // Ocr.Script name
    const val SEARCHABLE = "searchable"     // add OCR text layer when saving scans
    const val PAGE_SIZE = "size"            // PageSize name (shared with the save dialog)

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun useAiScanner(ctx: Context) = prefs(ctx).getString(SCANNER, "ai") == "ai"
    fun searchableDefault(ctx: Context) = prefs(ctx).getBoolean(SEARCHABLE, true)
}
