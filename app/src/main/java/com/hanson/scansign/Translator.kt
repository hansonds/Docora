package com.hanson.scansign

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
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

    /** A detected language ([code] = null when unsure) and how sure the detector is (0..1). */
    class Guess(val code: String?, val confidence: Float)

    /** Which supported language [text] is written in, or null if unsure / not translatable. */
    fun detect(text: String): String? = detectPage(text.split('\n')).code

    /** Language of each text (null = unsure). */
    fun detectAll(texts: List<String?>): List<String?> = guessAll(texts).map { it.code }

    /**
     * Language of each text, from the detector's candidates checked against the writing system
     * actually used (so Devanagari is never taken for English, Cyrillic for Spanish, …).
     */
    fun guessAll(texts: List<String?>): List<Guess> {
        val client = LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.05f).build())
        try {
            return texts.map { t ->
                if (t.isNullOrBlank() || t.count { it.isLetter() } < 2) return@map Guess(null, 0f)
                val script = Scripts.of(t)
                val cands = try {
                    Tasks.await(client.identifyPossibleLanguages(t.take(2000)))
                        .mapNotNull { c ->
                            val tag = c.languageTag
                            if (tag == "und" || tag.endsWith("-Latn")) null   // romanised Hindi etc. can't be translated
                            else TranslateLanguage.fromLanguageTag(tag)?.let { Guess(it, c.confidence) }
                        }
                } catch (_: Throwable) { emptyList() }
                val fits = cands.filter { Scripts.fits(it.code!!, script) }
                fits.maxByOrNull { it.confidence }
                    ?: Scripts.defaultFor(script)?.let { Guess(it, 0.6f) }
                    ?: Guess(null, 0f)
            }
        } finally {
            client.close()
        }
    }

    /**
     * Language of a whole page or selection: every paragraph votes, weighted by its length and how
     * sure the detector is (better than detecting one long mixed text).
     */
    fun detectPage(texts: List<String?>): Guess {
        val guesses = guessAll(texts)
        val votes = HashMap<String, Float>()
        var total = 0f
        texts.forEachIndexed { i, t ->
            val g = guesses[i]
            val w = (t?.count { it.isLetter() } ?: 0).toFloat()
            total += w
            if (g.code != null) votes[g.code] = (votes[g.code] ?: 0f) + w * g.confidence
        }
        val best = votes.maxByOrNull { it.value } ?: return Guess(null, 0f)
        return Guess(best.key, if (total > 0) (best.value / total).coerceIn(0f, 1f) else 0f)
    }

    /** Name of the language of [text] when its writing system can't be translated on the phone (e.g. Malayalam), else null. */
    fun unsupportedScript(text: String): String? = Scripts.unsupportedName(Scripts.of(text))

    /** Writing systems: which languages can be written in each. */
    private object Scripts {
        private val sets: Map<Character.UnicodeScript, List<String>> = mapOf(
            Character.UnicodeScript.DEVANAGARI to listOf("hi", "mr"),
            Character.UnicodeScript.BENGALI to listOf("bn"),
            Character.UnicodeScript.GUJARATI to listOf("gu"),
            Character.UnicodeScript.TAMIL to listOf("ta"),
            Character.UnicodeScript.TELUGU to listOf("te"),
            Character.UnicodeScript.KANNADA to listOf("kn"),
            Character.UnicodeScript.ARABIC to listOf("ar", "fa", "ur"),
            Character.UnicodeScript.HEBREW to listOf("he"),
            Character.UnicodeScript.CYRILLIC to listOf("ru", "uk", "be", "bg", "mk"),
            Character.UnicodeScript.GREEK to listOf("el"),
            Character.UnicodeScript.THAI to listOf("th"),
            Character.UnicodeScript.HANGUL to listOf("ko"),
            Character.UnicodeScript.HIRAGANA to listOf("ja"),
            Character.UnicodeScript.KATAKANA to listOf("ja"),
            Character.UnicodeScript.HAN to listOf("zh", "ja"),
            Character.UnicodeScript.GEORGIAN to listOf("ka")
        )
        private val unsupported = mapOf(
            Character.UnicodeScript.MALAYALAM to "Malayalam",
            Character.UnicodeScript.GURMUKHI to "Punjabi",
            Character.UnicodeScript.ORIYA to "Odia",
            Character.UnicodeScript.SINHALA to "Sinhala",
            Character.UnicodeScript.MYANMAR to "Burmese",
            Character.UnicodeScript.KHMER to "Khmer",
            Character.UnicodeScript.ETHIOPIC to "Amharic",
            Character.UnicodeScript.LAO to "Lao",
            Character.UnicodeScript.ARMENIAN to "Armenian",
            Character.UnicodeScript.TIBETAN to "Tibetan"
        )
        private val nonLatin: Set<String> = sets.values.flatten().toSet()

        /** The main writing system of the text's letters. Kana anywhere means Japanese. */
        fun of(t: String): Character.UnicodeScript {
            val count = HashMap<Character.UnicodeScript, Int>()
            var i = 0
            while (i < t.length) {
                val cp = t.codePointAt(i)
                if (Character.isLetter(cp)) {
                    val sc = try { Character.UnicodeScript.of(cp) } catch (_: Throwable) { Character.UnicodeScript.UNKNOWN }
                    count[sc] = (count[sc] ?: 0) + 1
                }
                i += Character.charCount(cp)
            }
            val kana = (count[Character.UnicodeScript.HIRAGANA] ?: 0) + (count[Character.UnicodeScript.KATAKANA] ?: 0)
            if (kana > 0 && kana * 10 >= count.values.sum()) return Character.UnicodeScript.HIRAGANA
            return count.maxByOrNull { it.value }?.key ?: Character.UnicodeScript.UNKNOWN
        }

        fun fits(code: String, sc: Character.UnicodeScript): Boolean = when (sc) {
            Character.UnicodeScript.LATIN -> code !in nonLatin
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> code == "ja"
            in sets -> code in sets.getValue(sc)
            else -> true
        }

        fun defaultFor(sc: Character.UnicodeScript): String? = when (sc) {
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> "ja"
            else -> sets[sc]?.firstOrNull()
        }?.let { TranslateLanguage.fromLanguageTag(it) }

        fun unsupportedName(sc: Character.UnicodeScript): String? = unsupported[sc]
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
                    // keep bullets and list numbers ("•", "1.", "(a)") exactly as they are
                    val body = p.trim()
                    val mark = BULLET.find(body)?.value.orEmpty()
                    val rest = body.substring(mark.length)
                    lead + mark + (if (rest.none { it.isLetter() }) rest else Tasks.await(c.translate(rest))) + trail
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

    private val BULLET = Regex("^(?:[•●○◦▪▫■□‣⁃∙·*\\-–—]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)]|[ivxIVX]{1,4}[.)])\\s+")

    /**
     * Joins the line breaks that only come from the page's width (copied or extracted PDF text)
     * back into whole sentences, which translate much better. Paragraphs, list items and short
     * lines (headings, addresses) keep their own lines.
     */
    fun reflow(text: String): String {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (lines.size < 2) return text
        val width = lines.map { it.trim().length }.sorted().let { it[(it.size * 3) / 4] }
        val sb = StringBuilder()
        var prev = ""
        for (raw in lines) {
            val line = raw.trim()
            when {
                sb.isEmpty() -> sb.append(line)
                line.isEmpty() || prev.isEmpty() -> sb.append('\n').append(line)
                BULLET.containsMatchIn(line) -> sb.append('\n').append(line)
                prev.endsWith("-") && line[0].isLowerCase() -> { sb.setLength(sb.length - 1); sb.append(line) }
                prev.length < width * 0.6f || prev.last() in ".!?:;।。" -> sb.append('\n').append(line)
                else -> sb.append(' ').append(line)
            }
            prev = line
        }
        return sb.toString()
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
