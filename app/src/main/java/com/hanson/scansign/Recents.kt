package com.hanson.scansign

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * "Recently opened" list. PDFs from other apps (WhatsApp, Gmail…) are kept as a private copy,
 * so they can be reopened even after the other app removes its file.
 */
object Recents {

    class Entry(
        val key: String,
        val name: String,
        val path: String,
        val uri: String?,
        /** Where it came from, e.g. "WhatsApp" (null = Docora's own files). */
        val source: String?,
        val time: Long
    ) {
        val file get() = File(path)
    }

    private const val MAX = 30

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("recents", Context.MODE_PRIVATE)
    private fun dir(ctx: Context) = File(ctx.filesDir, "recent").apply { mkdirs() }

    @Synchronized
    private fun load(ctx: Context): MutableList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(prefs(ctx).getString("list", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Entry(
                    o.getString("key"), o.getString("name"), o.getString("path"),
                    o.optString("uri").ifEmpty { null }, o.optString("source").ifEmpty { null }, o.getLong("time")
                ))
            }
        } catch (_: Exception) {}
        return out
    }

    @Synchronized
    private fun save(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().apply {
            put("key", e.key); put("name", e.name); put("path", e.path)
            put("uri", e.uri ?: ""); put("source", e.source ?: ""); put("time", e.time)
        })
        prefs(ctx).edit().putString("list", a.toString()).apply()
    }

    /** Newest first; entries whose file is gone are dropped. */
    @Synchronized
    fun list(ctx: Context): List<Entry> {
        val all = load(ctx)
        val alive = all.filter { it.file.exists() }
        if (alive.size != all.size) save(ctx, alive)
        return alive
    }

    private fun isCopy(ctx: Context, e: Entry) = e.file.parentFile?.absolutePath == dir(ctx).absolutePath

    /** Remembers an opened PDF. Call from a background thread (it may copy the file). */
    @Synchronized
    fun record(ctx: Context, file: File, name: String, uri: Uri?, inLibrary: Boolean) {
        if (!file.exists()) return
        val key = when {
            inLibrary -> file.absolutePath
            uri != null -> uri.toString()
            else -> "name:$name:${file.length()}"
        }
        val list = load(ctx)
        // reopened from this list: just move it to the top
        list.firstOrNull { it.path == file.absolutePath }?.let { e ->
            list.remove(e)
            list.add(0, Entry(e.key, e.name, e.path, e.uri, e.source, System.currentTimeMillis()))
            save(ctx, list)
            return
        }
        val old = list.firstOrNull { it.key == key }
        // very large files (books) aren't copied again: the opened copy is remembered instead
        val path = if (inLibrary || file.length() > 60L * 1024 * 1024) file.absolutePath else {
            // keep our own copy (replacing an older copy of the same document)
            val target = old?.takeIf { isCopy(ctx, it) }?.file ?: File(dir(ctx), "r_${System.currentTimeMillis()}.pdf")
            try {
                if (file.absolutePath != target.absolutePath) file.copyTo(target, overwrite = true)
            } catch (_: Exception) { return }
            target.absolutePath
        }
        list.removeAll { it.key == key }
        list.add(0, Entry(key, name, path, uri?.toString(), if (inLibrary) null else sourceName(ctx, uri), System.currentTimeMillis()))
        while (list.size > MAX) {
            val gone = list.removeAt(list.size - 1)
            if (isCopy(ctx, gone)) gone.file.delete()
        }
        save(ctx, list)
    }

    @Synchronized
    fun remove(ctx: Context, key: String) {
        val list = load(ctx)
        list.filter { it.key == key }.forEach { if (isCopy(ctx, it)) it.file.delete() }
        list.removeAll { it.key == key }
        save(ctx, list)
    }

    @Synchronized
    fun clear(ctx: Context) {
        load(ctx).forEach { if (isCopy(ctx, it)) it.file.delete() }
        save(ctx, emptyList())
    }

    /** "WhatsApp", "Gmail", "Files"… from the app that shared the link. */
    private fun sourceName(ctx: Context, uri: Uri?): String? {
        val auth = uri?.authority ?: return null
        return try {
            val pm = ctx.packageManager
            pm.resolveContentProvider(auth, 0)?.applicationInfo?.loadLabel(pm)?.toString()
        } catch (_: Exception) { null }
    }
}
