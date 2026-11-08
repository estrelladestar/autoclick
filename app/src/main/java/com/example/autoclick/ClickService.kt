package com.example.autoclick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors

class ClickService : AccessibilityService() {
    companion object { var instance: ClickService? = null }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var running = false
    private var template: Mat? = null
    private var words = listOf<String>()
    private var useImage = false
    private var threshold = 0.8
    private var delayMs = 500L
    private var once = false
    private var overlay: Button? = null

    override fun onServiceConnected() {
        instance = this
        OpenCVLoader.initLocal()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stopClicking(); hideOverlay(); instance = null
        return super.onUnbind(intent)
    }

    // ---------- Botón flotante ----------
    fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply {
            text = "▶"
            setOnClickListener { if (running) stopClicking() else startClicking() }
            setOnLongClickListener { hideOverlay(); true }  // mantén pulsado para ocultar
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 }
        wm.addView(b, lp)
        overlay = b
    }

    private fun hideOverlay() {
        overlay?.let { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }
        overlay = null
        stopClicking()
    }

    // ---------- Control ----------
    private fun startClicking() {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        threshold = prefs.getFloat("th", 0.8f).toDouble()
        delayMs = prefs.getLong("delay", 500L)
        once = prefs.getBoolean("once", false)
        words = (prefs.getString("word", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        useImage = prefs.getBoolean("img", false)

        if (useImage) {
            val bmp = BitmapFactory.decodeFile(File(filesDir, "target.png").path)
            if (bmp == null) { toast("Elige una imagen en la app primero"); return }
            val m = Mat(); Utils.bitmapToMat(bmp, m)
            Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
            template = m
        }
        if (words.isEmpty() && !useImage) { toast("Escribe palabras o activa la imagen en la app"); return }
        running = true
        overlay?.text = "■"
        scan()
    }

    private fun stopClicking() {
        running = false
        overlay?.text = "▶"
    }

    private fun scan() {
        if (!running) return
        // 1) Árbol de accesibilidad: rápido y exacto en apps con texto nativo
        if (words.isNotEmpty()) {
            val root = rootInActiveWindow
            for (w in words) {
                val n = root?.findAccessibilityNodeInfosByText(w)?.firstOrNull { it.isVisibleToUser }
                if (n != null) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    finish(Pair(r.exactCenterX(), r.exactCenterY()))
                    return
                }
            }
        }
        // 2) Una captura: busca la imagen y/o las palabras (OCR)
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bmp == null) { main.post { finish(null) }; return }
                if (useImage) {
                    val p = findImage(bmp)
                    if (p != null) { main.post { finish(p) }; return }
                }
                if (words.isNotEmpty()) {
                    recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener { finish(findWord(it)) }
                        .addOnFailureListener { finish(null) }
                } else main.post { finish(null) }
            }
            override fun onFailure(errorCode: Int) {
                if (running) main.postDelayed({ scan() }, maxOf(delayMs, 1000L))
            }
        })
    }

    /** Se llama en el hilo principal con la posición encontrada (o null). */
    private fun finish(pos: Pair<Float, Float>?) {
        if (pos != null) {
            tap(pos.first, pos.second)
            if (once) stopClicking()
        }
        if (running) main.postDelayed({ scan() }, delayMs)
    }

    private fun findWord(text: Text): Pair<Float, Float>? {
        for (block in text.textBlocks) for (line in block.lines) {
            val lt = line.text.lowercase()
            for (w in words) {
                val wl = w.lowercase()
                if (!lt.contains(wl)) continue
                val el = line.elements.firstOrNull { it.text.lowercase().contains(wl) }
                val box = el?.boundingBox ?: line.boundingBox ?: continue
                return Pair(box.exactCenterX(), box.exactCenterY())
            }
        }
        return null
    }

    // ---------- Buscar por imagen ----------
    private fun findImage(bmp: Bitmap): Pair<Float, Float>? {
        val t = template ?: return null
        val src = Mat(); Utils.bitmapToMat(bmp, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
        if (t.cols() > src.cols() || t.rows() > src.rows()) { src.release(); return null }
        val res = Mat()
        Imgproc.matchTemplate(src, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        src.release(); res.release()
        if (mm.maxVal < threshold) return null
        return Pair((mm.maxLoc.x + t.cols() / 2.0).toFloat(), (mm.maxLoc.y + t.rows() / 2.0).toFloat())
    }

    // ---------- Toque ----------
    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(g, null, null)
    }

    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
