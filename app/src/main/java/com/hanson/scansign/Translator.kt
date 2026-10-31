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
    fun detect(text: String): String? = detectAll(listOf(text)).firstOrNull()

    /** Language of each text (null = unsure). One detector for all. */
    fun detectAll(texts: List<String?>): List<String?> {
        val client = LanguageIdentification.getClient()
        try {
            return texts.map { t ->
                if (t.isNullOrBlank()) null
                else try {
                    val tag = Tasks.await(client.identifyLanguage(t.take(1500)))
                    if (tag == "und") null else TranslateLanguage.fromLanguageTag(tag)
                } catch (_: Throwable) { null }
            }
        } finally {
            client.close()
        }
    }

    /**
     * Translates [text] from [from] to [to], sentence by sentence (short pieces are translated
     * more completely). The languages must already be downloaded.
     */
    fun translate(text: String, from: String, to: String, progress: ((Int, Int) -> Unit)? = null): String =
        Session().use { it.translate(text, from, to) }

    /** Translates many texts (null entries stay null). */
    fun translateAll(texts: List<String?>, from: String, to: String): List<String?> =
        Session().use { s -> texts.map { t -> if (t.isNullOrBlank()) null else s.translate(t, from, to) } }

    /**
     * Keeps translators open between calls and remembers what was already translated, so paging
     * through a document is fast. Close it when done.
     */
    class Session : java.io.Closeable {
        private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
        private val memo = HashMap<String, String>()

        @Synchronized
        private fun client(from: String, to: String) = clients.getOrPut("$from>$to") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }

        fun translate(text: String, from: String, to: String): String {
            if (from == to || text.isBlank()) return text
            val key = "$from>$to>$text"
            synchronized(memo) { memo[key] }?.let { return it }
            val c = client(from, to)
            val prep = Case.prepare(text)
            fun run(src: String) = pieces(src).joinToString("") { p ->
                if (p.isBlank() || p.none { it.isLetter() }) p
                else {
                    val lead = p.takeWhile { it.isWhitespace() }
                    val trail = p.takeLastWhile { it.isWhitespace() }
                    lead + Tasks.await(c.translate(p.trim())) + trail
                }
            }
            var out = run(prep.text)
            // came back unchanged (often names-like words): try again in lower case
            if (out.trim().equals(prep.text.trim(), ignoreCase = true) && prep.text.any { it.isUpperCase() }) {
                val again = run(prep.text.lowercase())
                if (!again.trim().equals(prep.text.trim(), ignoreCase = true)) out = again
            }
            out = prep.restore(out)
            synchronized(memo) { if (memo.size > 4000) memo.clear(); memo[key] = out }
            return out
        }

        @Synchronized
        override fun close() {
            for (c in clients.values) try { c.close() } catch (_: Throwable) {}
            clients.clear()
        }
    }

    /**
     * The translator leaves ALL-CAPS and Title-Case words alone (it takes them for names), so
     * such text is translated in sentence case and turned back into capitals afterwards.
     */
    private object Case {
        class Prepared(val text: String, private val upper: Boolean) {
            fun restore(s: String) = if (upper) s.uppercase() else s
        }

        fun prepare(t: String): Prepared {
            val cased = t.filter { it.isUpperCase() || it.isLowerCase() }
            if (cased.length < 4) return Prepared(t, false)
            val ups = cased.count { it.isUpperCase() }
            if (ups >= cased.length * 0.8f) return Prepared(sentence(t.lowercase()), true)
            val words = t.split(Regex("\\s+")).filter { it.length >= 3 && it[0].isLetter() }
            if (words.size >= 3 && words.count { it[0].isUpperCase() } >= words.size * 0.75f && ups < cased.length * 0.5f)
                return Prepared(sentence(t.lowercase()), false)
            return Prepared(t, false)
        }

        private fun sentence(s: String): String {
            val sb = StringBuilder(s)
            var cap = true
            for (i in sb.indices) {
                val ch = sb[i]
                if (cap && ch.isLetter()) { sb.setCharAt(i, ch.uppercaseChar()); cap = false }
                else if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') cap = true
            }
            return sb.toString()
        }
    }

    /** Splits text into sentences (at most ~400 characters each), keeping all spaces and line breaks. */
    private fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            sb.append(ch)
            val end = (ch == '.' || ch == '!' || ch == '?' || ch == '\u0964' || ch == '\u3002' || ch == ';') &&
                (i + 1 >= text.length || text[i + 1].isWhitespace())
            if (ch == '\n' || (end && sb.length >= 25) || (sb.length >= 400 && ch.isWhitespace())) {
                out.add(sb.toString()); sb.setLength(0)
            }
            i++
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }
}
