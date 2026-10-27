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
            Log.e("ScanSign", "OpenCV failed to load", t)
            false
        }
    }

    companion object {
        var openCvReady = false
            private set
    }
}
