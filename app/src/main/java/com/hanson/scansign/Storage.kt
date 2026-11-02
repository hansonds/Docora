package com.hanson.scansign

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the app keeps its files, plus share / export helpers. */
object Storage {

    fun pdfDir(ctx: Context): File = File(ctx.filesDir, "pdfs").apply { mkdirs() }
    fun signatureDir(ctx: Context): File = File(ctx.filesDir, "signatures").apply { mkdirs() }
    fun workDir(ctx: Context): File = File(ctx.cacheDir, "work").apply { mkdirs() }

    fun newWorkFile(ctx: Context, ext: String): File =
        File(workDir(ctx), "w_${System.nanoTime()}.$ext")

    /** Temporary files an open screen still needs (the PDF being edited and its working copies). */
    private val inUse = java.util.Collections.synchronizedSet(HashSet<String>())
    fun markInUse(f: File?) { if (f != null) inUse.add(f.absolutePath) }
    fun releaseInUse(f: File?) { if (f != null) inUse.remove(f.absolutePath) }

    /** Size of Docora's temporary files (bytes). */
    fun tempSize(ctx: Context): Long = ctx.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Deletes temporary files older than [olderThanMs]: working copies of opened / shared PDFs,
     * scan photos, share copies, translated pages, leftovers from the PDF library. Keeps files an
     * open screen uses and the copies "Recent" still points to. Returns the bytes freed.
     */
    fun cleanTemp(ctx: Context, olderThanMs: Long, photosOlderThanMs: Long = olderThanMs): Long {
        val now = System.currentTimeMillis()
        val keep = HashSet<String>()
        synchronized(inUse) { keep.addAll(inUse) }
        try { Recents.list(ctx).forEach { keep.add(it.file.absolutePath) } } catch (_: Throwable) {}
        var freed = 0L
        fun sweep(dir: File, filter: (File) -> Boolean = { true }) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) { sweep(f, filter); if (f.listFiles()?.isEmpty() == true) f.delete(); return@forEach }
                // scan photos may belong to a scan still in progress: they get longer
                val age = if (f.extension.lowercase() in setOf("jpg", "jpeg", "png")) photosOlderThanMs else olderThanMs
                if (!filter(f) || f.absolutePath in keep || now - f.lastModified() < age) return@forEach
                val len = f.length()
                if (f.delete()) freed += len
            }
        }
        val cache = ctx.cacheDir
        for (name in listOf("work", "share", "translated", "pdffonts")) File(cache, name).takeIf { it.isDirectory }?.let { sweep(it) }
        // loose files at the top: the PDF being opened, the PDF library's temporary buffers
        cache.listFiles()?.filter { it.isFile && (it.name == "opening.pdf" || it.name.endsWith(".tmp") || it.name.startsWith("PDFBox")) }
            ?.forEach { f ->
                if (f.absolutePath in keep || now - f.lastModified() < olderThanMs) return@forEach
                val len = f.length()
                if (f.delete()) freed += len
            }
        return freed
    }

    fun listPdfs(ctx: Context): List<File> =
        (pdfDir(ctx).listFiles { f -> f.name.endsWith(".pdf", true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    fun listSignatures(ctx: Context): List<File> =
        (signatureDir(ctx).listFiles { f -> f.name.endsWith(".png", true) } ?: emptyArray())
            .sortedByDescending { it.lastModified() }

    fun defaultName(prefix: String = "Scan"): String =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun cleanName(name: String): String {
        val n = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").removeSuffix(".pdf")
        return n.ifBlank { defaultName() }
    }

    /** Returns a file in the PDF folder that does not exist yet. */
    fun uniquePdf(ctx: Context, baseName: String): File {
        val base = cleanName(baseName)
        var f = File(pdfDir(ctx), "$base.pdf")
        var i = 2
        while (f.exists()) {
            f = File(pdfDir(ctx), "$base ($i).pdf")
            i++
        }
        return f
    }

    fun uriFor(ctx: Context, file: File): Uri =
        FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)

    fun sharePdf(ctx: Context, file: File) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uriFor(ctx, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share PDF"))
    }

    fun openPdfExternally(ctx: Context, file: File) {
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(ctx, file), "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Open with"))
    }

    /** Copies the PDF into Downloads/Docora so it shows up in the Files app. */
    fun exportToDownloads(ctx: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Docora")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Copies any content:// URI into the work folder. */
    fun copyUriToWork(ctx: Context, uri: Uri, ext: String): File? = try {
        val out = newWorkFile(ctx, ext)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        if (out.length() > 0) out else null
    } catch (e: Exception) {
        null
    }

    fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}
