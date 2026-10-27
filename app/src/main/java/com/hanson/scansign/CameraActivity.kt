package com.hanson.scansign

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * In-app camera. Document mode shows the detected page outline live and can capture
 * automatically when the page is held steady. Signature mode shows a 4:3 guide box.
 */
class CameraActivity : BaseActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_COUNT = "count"
        const val EXTRA_FILE = "file"
        const val EXTRA_HINT = "hint"
        const val MODE_DOC = "doc"
        const val MODE_SIG = "sig"
        /** Result code when the user tapped "Gallery". */
        const val RESULT_GALLERY = Activity.RESULT_FIRST_USER + 1

        fun intent(ctx: Context, mode: String, pagesSoFar: Int = 0) =
            Intent(ctx, CameraActivity::class.java).putExtra(EXTRA_MODE, mode).putExtra(EXTRA_COUNT, pagesSoFar)

        private const val STABLE_FRAMES = 12
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: CameraOverlayView
    private lateinit var status: TextView
    private lateinit var btnFlash: MaterialButton
    private lateinit var btnAuto: MaterialButton
    private lateinit var btnShutter: ImageButton
    private lateinit var flashFx: View

    private var mode = MODE_DOC
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var analysisExecutor: ExecutorService
    private var torchOn = false
    private var autoCapture = true
    @Volatile private var capturing = false

    // detection state (analysis thread)
    private var smooth: List<PointF>? = null
    private var stableCount = 0
    private var missCount = 0
    @Volatile private var lastQuad: List<PointF>? = null

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Camera permission is needed to scan", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // phones: portrait camera; tablets can rotate freely
        if (resources.configuration.smallestScreenWidthDp < 600) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContentView(R.layout.activity_camera)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        status = findViewById(R.id.status)
        btnFlash = findViewById(R.id.btnFlash)
        btnAuto = findViewById(R.id.btnAuto)
        btnShutter = findViewById(R.id.btnShutter)
        flashFx = findViewById(R.id.flashFx)
        analysisExecutor = Executors.newSingleThreadExecutor()

        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_DOC
        val isSig = mode == MODE_SIG
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlay.signatureMode = isSig
        autoCapture = !isSig && getSharedPreferences("camera", MODE_PRIVATE).getBoolean("auto", true)

        findViewById<TextView>(R.id.title).text = if (isSig) "Photo of signature" else "Scan document"
        status.text = if (isSig) "Sign on white paper and fit it in the box" else "Point the camera at a document"
        btnAuto.visibility = if (isSig) View.GONE else View.VISIBLE
        updateAutoButton()

        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        val done = findViewById<MaterialButton>(R.id.btnDone)
        if (!isSig && count > 0) {
            done.visibility = View.VISIBLE
            done.text = "Done ($count)"
        }
        done.setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        findViewById<View>(R.id.btnGallery).visibility = if (isSig) View.GONE else View.VISIBLE
        findViewById<View>(R.id.btnGallery).setOnClickListener { setResult(RESULT_GALLERY); finish() }
        findViewById<View>(R.id.btnClose).setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        btnShutter.setOnClickListener { takePhoto() }
        btnFlash.setOnClickListener { toggleTorch() }
        btnAuto.setOnClickListener {
            autoCapture = !autoCapture
            getSharedPreferences("camera", MODE_PRIVATE).edit().putBoolean("auto", autoCapture).apply()
            updateAutoButton()
            Toast.makeText(this, if (autoCapture) "Auto capture on" else "Auto capture off", Toast.LENGTH_SHORT).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun updateAutoButton() {
        btnAuto.alpha = if (autoCapture) 1f else 0.45f
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try { future.get() } catch (e: Exception) {
                Toast.makeText(this, "Camera not available", Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }
            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
            imageCapture = capture

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            if (mode == MODE_DOC) analysis.setAnalyzer(analysisExecutor) { img -> analyze(img) }

            try {
                provider.unbindAll()
                camera = if (mode == MODE_DOC) {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
                } else {
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start the camera", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "No flash on this camera", Toast.LENGTH_SHORT).show()
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        btnFlash.setIconResource(if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    // ---------------- live detection ----------------

    private fun analyze(img: ImageProxy) {
        val mats = ArrayList<Mat>()
        fun m(): Mat = Mat().also { mats.add(it) }
        try {
            if (!App.openCvReady || capturing) return
            val w = img.width
            val h = img.height
            // luminance
            val yp = img.planes[0]
            val yBytes = ByteArray(w * h)
            copyPlane(yp.buffer, yp.rowStride, yp.pixelStride, w, h, yBytes)
            // chroma (saturation-like): distance of U,V from neutral grey, at half resolution
            val cw = w / 2
            val ch = h / 2
            val uBytes = ByteArray(cw * ch)
            val vBytes = ByteArray(cw * ch)
            copyPlane(img.planes[1].buffer, img.planes[1].rowStride, img.planes[1].pixelStride, cw, ch, uBytes)
            copyPlane(img.planes[2].buffer, img.planes[2].rowStride, img.planes[2].pixelStride, cw, ch, vBytes)
            val chroma = ByteArray(cw * ch)
            for (i in chroma.indices) {
                val du = (uBytes[i].toInt() and 0xFF) - 128
                val dv = (vBytes[i].toInt() and 0xFF) - 128
                chroma[i] = (sqrt((du * du + dv * dv).toDouble()) * 2.2).toInt().coerceAtMost(255).toByte()
            }
            val gray = m().apply { create(h, w, CvType.CV_8UC1); put(0, 0, yBytes) }
            val chromaHalf = m().apply { create(ch, cw, CvType.CV_8UC1); put(0, 0, chroma) }

            val rot = img.imageInfo.rotationDegrees
            fun rotate(src: Mat): Mat {
                val dst = m()
                when (rot) {
                    90 -> Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
                    180 -> Core.rotate(src, dst, Core.ROTATE_180)
                    270 -> Core.rotate(src, dst, Core.ROTATE_90_COUNTERCLOCKWISE)
                    else -> src.copyTo(dst)
                }
                return dst
            }
            val rg = rotate(gray)
            val rc = rotate(chromaHalf)
            val s = 480.0 / max(rg.cols(), rg.rows())
            val size = Size((rg.cols() * s).roundToInt().toDouble(), (rg.rows() * s).roundToInt().toDouble())
            val small = m(); Imgproc.resize(rg, small, size, 0.0, 0.0, Imgproc.INTER_AREA)
            val smallSat = m(); Imgproc.resize(rc, smallSat, size, 0.0, 0.0, Imgproc.INTER_LINEAR)
            val aspect = rg.cols().toFloat() / rg.rows()
            val q = Detector.detect(small, smallSat, perGroup = 8)
            val fw = small.cols().toFloat()
            val fh = small.rows().toFloat()
            val found = q?.let { d -> List(4) { PointF(d[it * 2].toFloat() / fw, d[it * 2 + 1].toFloat() / fh) } }
            onDetection(found, aspect)
        } catch (_: Throwable) {
        } finally {
            mats.forEach { it.release() }
            img.close()
        }
    }

    private fun copyPlane(buf: java.nio.ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, out: ByteArray) {
        buf.rewind()
        if (pixelStride == 1 && rowStride == w) {
            buf.get(out, 0, min(out.size, buf.remaining()))
            return
        }
        val row = ByteArray(rowStride)
        for (y in 0 until h) {
            val start = y * rowStride
            if (start >= buf.limit()) break
            buf.position(start)
            val len = min(rowStride, buf.remaining())
            buf.get(row, 0, len)
            for (x in 0 until w) {
                val idx = x * pixelStride
                out[y * w + x] = if (idx < len) row[idx] else 0
            }
        }
    }

    /** Runs on the analysis thread. */
    private fun onDetection(q: List<PointF>?, aspect: Float) {
        val valid = q != null && quadArea(q) > 0.10f
        if (!valid) {
            missCount++
            stableCount = 0
            if (missCount > 4) smooth = null
        } else {
            missCount = 0
            val prev = smooth
            if (prev != null && maxShift(prev, q!!) < 0.025f) stableCount++ else stableCount = 0
            smooth = if (prev == null) q else List(4) { i ->
                PointF(prev[i].x * 0.4f + q!![i].x * 0.6f, prev[i].y * 0.4f + q[i].y * 0.6f)
            }
        }
        val shown = smooth
        lastQuad = shown
        val progress = if (autoCapture && shown != null) (stableCount.toFloat() / STABLE_FRAMES).coerceAtMost(1f) else 0f
        val shouldCapture = autoCapture && shown != null && stableCount >= STABLE_FRAMES
        runOnUiThread {
            if (isFinishing) return@runOnUiThread
            overlay.imageAspect = aspect
            overlay.setQuad(shown, progress)
            status.text = when {
                shown == null -> "Looking for a document…"
                autoCapture -> "Hold steady…"
                else -> "Document found — tap the button"
            }
            if (shouldCapture) takePhoto()
        }
    }

    private fun quadArea(q: List<PointF>): Float {
        var a = 0f
        for (i in 0..3) {
            val p = q[i]
            val n = q[(i + 1) % 4]
            a += p.x * n.y - n.x * p.y
        }
        return abs(a) / 2f
    }

    private fun maxShift(a: List<PointF>, b: List<PointF>): Float =
        (0..3).maxOf { max(abs(a[it].x - b[it].x), abs(a[it].y - b[it].y)) }

    // ---------------- capture ----------------

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        btnShutter.isEnabled = false
        flashFx.animate().alpha(0.7f).setDuration(60).withEndAction {
            flashFx.animate().alpha(0f).setDuration(180).start()
        }.start()
        status.text = "Capturing…"
        val file = Storage.newWorkFile(this, "jpg")
        val hint = lastQuad
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val data = Intent().putExtra(EXTRA_FILE, file.absolutePath)
                    if (hint != null) data.putExtra(EXTRA_HINT, FloatArray(8) { i -> if (i % 2 == 0) hint[i / 2].x else hint[i / 2].y })
                    setResult(Activity.RESULT_OK, data)
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    capturing = false
                    btnShutter.isEnabled = true
                    stableCount = 0
                    Toast.makeText(this@CameraActivity, "Capture failed, try again", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}
