package com.hanson.scansign

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        Themes.init(this)
        TextRender.init(this)
        // temporary files from earlier sessions (kept half a day so an interrupted scan can still finish)
        val app = this
        Thread { try { Storage.cleanTemp(app, 12L * 60 * 60 * 1000) } catch (_: Throwable) {} }.apply { priority = Thread.MIN_PRIORITY }.start()
        PDFBoxResourceLoader.init(applicationContext)
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.e("Docora", "OpenCV initLocal failed", t)
            false
        }
        if (!openCvReady) {
            // fallback: load the native library directly
            openCvReady = try {
                System.loadLibrary("opencv_java4")
                true
            } catch (t: Throwable) {
                Log.e("Docora", "OpenCV native library missing", t)
                false
            }
        }
    }

    companion object {
        var openCvReady = false
            private set
    }
}
