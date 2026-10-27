package com.hanson.scansign

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        openCvReady = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.e("ScanSign", "OpenCV initLocal failed", t)
            false
        }
        if (!openCvReady) {
            // fallback: load the native library directly
            openCvReady = try {
                System.loadLibrary("opencv_java4")
                true
            } catch (t: Throwable) {
                Log.e("ScanSign", "OpenCV native library missing", t)
                false
            }
        }
    }

    companion object {
        var openCvReady = false
            private set
    }
}
