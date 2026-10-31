package com.hanson.scansign

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * On-device translation (Google ML Kit). Each language is a separate download of about 30 MB;
 * after that, translating works offline and the text never leaves the phone.
 * Every function that waits is blocking: call it from a background thread.
 */
object Translator {

    class Lang(val code: String, val name: String)

    /** Rough download size of one language (shown to the user). */
    const val MODEL_MB = 30

    /** All languages that can be translated, sorted by name. */
    val all: List<Lang> by lazy {
        TranslateLanguage.getAllLanguages().map { Lang(it, name(it)) }.sortedBy { it.name }
    }

    /** Language name in the phone's language, e.g. "Hindi", "German". */
    fun name(code: String): String {
        val n = Locale(code).getDisplayLanguage(Locale.getDefault())
        return if (n.isBlank() || n == code) code.uppercase() else n.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    /** English is built into every translation, so it never needs downloading. */
    fun builtIn(code: String) = code == TranslateLanguage.ENGLISH

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("translate", Context.MODE_PRIVATE)

    /** Download only on Wi-Fi (user setting). */
    fun wifiOnly(ctx: Context) = prefs(ctx).getBoolean("wifi_only", false)
    fun setWifiOnly(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wifi_only", on).apply()

    /** The language people usually translate into (remembered), else the phone's language, else English. */
    fun defaultTarget(ctx: Context): String {
        prefs(ctx).getString("to", null)?.let { return it }
        val phone = TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
        return phone ?: TranslateLanguage.ENGLISH
    }
    fun rememberTarget(ctx: Context, code: String) = prefs(ctx).edit().putString("to", code).apply()

    private fun conditions(ctx: Context) = DownloadConditions.Builder().apply { if (wifiOnly(ctx)) requireWifi() }.build()

    /** Languages already on the phone (English always counts). */
    fun downloaded(): Set<String> {
        val models = Tasks.await(RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java))
        return models.map { it.language }.toSet() + TranslateLanguage.ENGLISH
    }

    fun download(ctx: Context, code: String) {
        if (builtIn(code)) return
        Tasks.await(RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(code).build(), conditions(ctx)))
    }

    fun delete(code: String) {
        if (builtIn(code)) return
        Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()))
    }

    /** Which supported language [text] is written in, or null if unsure / not translatable. */
    fun detect(text: String): String? {
        val client = LanguageIdentification.getClient()
        return try {
            val tag = Tasks.await(client.identifyLanguage(text.take(1500)))
            if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
        } catch (_: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    /**
     * Translates [text] from [from] to [to], paragraph by paragraph (long texts are split so each
     * piece stays small). The languages must already be downloaded.
     */
    fun translate(text: String, from: String, to: String, progress: ((Int, Int) -> Unit)? = null): String {
        if (from == to) return text
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            val chunks = chunks(text)
            val out = StringBuilder()
            chunks.forEachIndexed { i, c ->
                if (c.isBlank()) out.append(c)
                else out.append(Tasks.await(client.translate(c)))
                progress?.invoke(i + 1, chunks.size)
            }
            return out.toString()
        } finally {
            client.close()
        }
    }

    /** Translates many short texts with one client (null entries are skipped and stay null). */
    fun translateAll(texts: List<String?>, from: String, to: String): List<String?> {
        if (from == to) return texts
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        try {
            return texts.map { t ->
                if (t.isNullOrBlank()) null
                else chunks(t).joinToString("") { c -> if (c.isBlank()) c else Tasks.await(client.translate(c)) }
            }
        } finally {
            client.close()
        }
    }

    /** Splits text into pieces of at most ~1500 characters at paragraph / sentence ends, keeping line breaks. */
    private fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        for (para in text.split(Regex("(?<=\n)"))) {
            if (para.length <= 1500) { out.add(para); continue }
            var rest = para
            while (rest.length > 1500) {
                val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', ';', ','), 1500).takeIf { it > 300 } ?: 1500
                out.add(rest.substring(0, cut + 1))
                rest = rest.substring(cut + 1)
            }
            if (rest.isNotEmpty()) out.add(rest)
        }
        // join small neighbours so there are fewer calls
        val merged = ArrayList<String>()
        for (c in out) {
            if (merged.isNotEmpty() && merged.last().length + c.length <= 1500) merged[merged.size - 1] = merged.last() + c
            else merged.add(c)
        }
        return merged
    }
}
