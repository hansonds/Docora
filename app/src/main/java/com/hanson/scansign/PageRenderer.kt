package com.hanson.scansign

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Renders PDF pages on one background thread (PdfRenderer is not thread-safe).
 * Only one page can be open at a time, so every request runs in order on this thread.
 */
class PageRenderer(file: File) {

    private val thread = HandlerThread("pdf-render").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    @Volatile private var closed = false

    val pageCount: Int = renderer.pageCount

    /**
     * Renders a whole page to [widthPx] wide. [stillWanted] is checked on the worker thread
     * just before rendering so stale requests (page scrolled away) are skipped cheaply.
     */
    fun renderPage(index: Int, widthPx: Int, stillWanted: () -> Boolean, done: (Bitmap?) -> Unit) {
        worker.post {
            if (closed || !stillWanted()) { main.post { done(null) }; return@post }
            val bmp = try {
                val page = renderer.openPage(index)
                try {
                    val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceIn(1, 8000)
                    val b = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    page.close()
                }
            } catch (e: Throwable) {
                null
            }
            main.post { done(bmp) }
        }
    }

    /**
     * Renders only part of a page at high resolution (for zoom).
     * [scale] is bitmap pixels per PDF point; (left, top) is the region's corner in those pixels.
     */
    fun renderRegion(
        index: Int, scale: Float, left: Int, top: Int, w: Int, h: Int,
        stillWanted: () -> Boolean, done: (Bitmap?) -> Unit
    ) {
        worker.post {
            if (closed || !stillWanted() || w <= 0 || h <= 0) { main.post { done(null) }; return@post }
            val bmp = try {
                val page = renderer.openPage(index)
                try {
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    val m = Matrix()
                    m.setScale(scale, scale)
                    m.postTranslate(-left.toFloat(), -top.toFloat())
                    page.render(b, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                } finally {
                    page.close()
                }
            } catch (e: Throwable) {
                null
            }
            main.post { done(bmp) }
        }
    }

    fun close() {
        closed = true
        worker.post {
            try { renderer.close() } catch (_: Throwable) {}
            try { fd.close() } catch (_: Throwable) {}
            thread.quitSafely()
        }
    }
}
