package com.example.autoclick

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors

/**
 * Un paso de la secuencia.
 * type: "image" (value = nombre del archivo), "word" (value = palabra o palabras separadas por comas)
 *       o "point" (x, y = posición fija en la pantalla).
 * times: cuántas veces se pulsa este paso antes de pasar al siguiente.
 */
data class Step(
    val id: Long,
    val type: String,
    val value: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    var times: Int = 1
)

object StepStore {
    fun load(ctx: Context): MutableList<Step> {
        val raw = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("steps", "[]") ?: "[]"
        val out = mutableListOf<Step>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Step(
                        o.getLong("id"),
                        o.getString("type"),
                        o.optString("value", ""),
                        o.optDouble("x", 0.0).toFloat(),
                        o.optDouble("y", 0.0).toFloat(),
                        o.optInt("times", 1)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return out
    }

    fun save(ctx: Context, steps: List<Step>) {
        val arr = JSONArray()
        for (s in steps) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("type", s.type)
            o.put("value", s.value)
            o.put("x", s.x.toDouble())
            o.put("y", s.y.toDouble())
            o.put("times", s.times)
            arr.put(o)
        }
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            .edit().putString("steps", arr.toString()).apply()
    }
}

class ClickService : AccessibilityService() {
    companion object { var instance: ClickService? = null }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private var running = false
    private var runId = 0
    private var steps = listOf<Step>()
    private val templates = HashMap<Long, Mat>()
    private var threshold = 0.8
    private var delayMs = 500L
    private var repeatTotal = 1
    private var timeoutMs = 0L

    private var idx = 0
    private var clicksDone = 0
    private var loopsDone = 0
    private var stepStart = 0L

    private var overlay: Button? = null
    private var pickView: View? = null

    override fun onServiceConnected() {
        instance = this
        OpenCVLoader.initLocal()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }

    override fun onUnbind(intent: Intent?): Boolean {
        stopClicking(); hideOverlay(); closePicker(); instance = null
        return super.onUnbind(intent)
    }

    // ---------- Botón flotante (se arrastra; toque = iniciar/parar; mantener = ocultar) ----------
    fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply { text = if (running) "■" else "▶" }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 300 }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        var downTime = 0L
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    downTime = e.eventTime
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        wm.updateViewLayout(b, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - downTime > 700) hideOverlay()
                        else if (running) stopClicking()
                        else startClicking()
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(b, lp)
        overlay = b
    }

    private fun hideOverlay() {
        val o = overlay
        if (o != null) {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(o)
            } catch (e: Exception) {
            }
        }
        overlay = null
        stopClicking()
    }

    // ---------- Elegir un punto de la pantalla ----------
    fun pickPoint() {
        if (pickView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val frame = FrameLayout(this)
        frame.setBackgroundColor(0x44000000)

        val hint = TextView(this).apply {
            text = "Toca el punto exacto donde quieres que pulse.\n" +
                "Antes puedes ir a otra pantalla con Inicio o Atrás."
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 96, 32, 32)
            textSize = 16f
        }
        frame.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )

        val cancel = Button(this).apply {
            text = "Cancelar"
            setOnClickListener { closePicker() }
        }
        frame.addView(
            cancel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = 200 }
        )

        frame.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val x = e.rawX
                val y = e.rawY
                closePicker()
                val list = StepStore.load(this)
                list.add(Step(System.currentTimeMillis(), "point", "", x, y))
                StepStore.save(this, list)
                toast("Punto guardado (${x.toInt()}, ${y.toInt()}). Míralo en la lista de la app.")
            }
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(frame, lp)
        pickView = frame
    }

    private fun closePicker() {
        val v = pickView
        if (v != null) {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (e: Exception) {
            }
        }
        pickView = null
    }

    // ---------- Control ----------
    private fun startClicking() {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        threshold = prefs.getFloat("th", 0.8f).toDouble()
        delayMs = prefs.getLong("delay", 500L)
        repeatTotal = prefs.getInt("repeat", 1)
        timeoutMs = prefs.getInt("timeout", 0) * 1000L
        val links = ArrayList<String>()
        for (i in 0 until 5) {
            val u = (prefs.getString("link$i", "") ?: "").trim()
            if (u.isNotEmpty()) links.add(u)
        }
        val linkWaitMs = prefs.getInt("linkWait", 3) * 1000L

        steps = StepStore.load(this)
        if (steps.isEmpty()) { toast("Añade al menos un paso en la app"); return }

        templates.clear()
        for (s in steps) {
            if (s.type != "image") continue
            val bmp = BitmapFactory.decodeFile(File(filesDir, s.value).path)
            if (bmp == null) { toast("Falta una imagen: vuelve a añadirla en la app"); return }
            val m = Mat()
            Utils.bitmapToMat(bmp, m)
            Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
            templates[s.id] = m
        }

        main.removeCallbacksAndMessages(null)
        runId++
        idx = 0
        clicksDone = 0
        loopsDone = 0
        running = true
        overlay?.text = "■"

        links.forEachIndexed { k, u -> main.postDelayed({ openLink(u) }, k * linkWaitMs) }
        val wait = links.size * linkWaitMs
        stepStart = System.currentTimeMillis() + wait
        main.postDelayed({ runStep() }, wait)
    }

    private fun stopClicking() {
        running = false
        runId++
        main.removeCallbacksAndMessages(null)
        overlay?.text = "▶"
    }

    private fun openLink(url: String) {
        val u = if (url.contains("://")) url else "https://$url"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("No se pudo abrir el enlace")
        }
    }

    // ---------- Secuencia ----------
    private fun runStep() {
        if (!running) return

        if (idx >= steps.size) {
            loopsDone++
            if (repeatTotal > 0 && loopsDone >= repeatTotal) {
                stopClicking()
                toast("Secuencia terminada")
                return
            }
            idx = 0
            clicksDone = 0
            stepStart = System.currentTimeMillis()
            main.postDelayed({ runStep() }, delayMs)
            return
        }

        if (timeoutMs > 0 && System.currentTimeMillis() - stepStart > timeoutMs) {
            nextStep()
            main.postDelayed({ runStep() }, delayMs)
            return
        }

        val step = steps[idx]
        val id = runId
        when (step.type) {
            "point" -> hit(Pair(step.x, step.y))
            "word" -> findWordStep(step, id)
            "image" -> findImageStep(step, id)
            else -> { nextStep(); main.postDelayed({ runStep() }, delayMs) }
        }
    }

    private fun nextStep() {
        idx++
        clicksDone = 0
        stepStart = System.currentTimeMillis()
    }

    /** Se llama en el hilo principal: toca y decide si se pasa al siguiente paso. */
    private fun hit(pos: Pair<Float, Float>) {
        if (!running) return
        tap(pos.first, pos.second)
        clicksDone++
        val step = steps[idx]
        if (clicksDone >= maxOf(1, step.times)) nextStep()
        main.postDelayed({ runStep() }, delayMs)
    }

    private fun miss() {
        if (!running) return
        main.postDelayed({ runStep() }, delayMs)
    }

    // ---------- Buscar palabra ----------
    private fun findWordStep(step: Step, id: Int) {
        val words = step.value.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty()) { nextStep(); miss(); return }

        // 1) Árbol de accesibilidad: rápido y exacto en apps con texto nativo
        val root = rootInActiveWindow
        for (w in words) {
            val n = root?.findAccessibilityNodeInfosByText(w)?.firstOrNull { it.isVisibleToUser }
            if (n != null) {
                val r = Rect()
                n.getBoundsInScreen(r)
                hit(Pair(r.exactCenterX(), r.exactCenterY()))
                return
            }
        }

        // 2) Captura de pantalla + OCR
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bmp == null) { main.post { if (id == runId) miss() }; return }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener {
                        if (id == runId) {
                            val p = findWord(it, words)
                            if (p != null) hit(p) else miss()
                        }
                    }
                    .addOnFailureListener { if (id == runId) miss() }
            }

            override fun onFailure(errorCode: Int) {
                if (id == runId && running) main.postDelayed({ runStep() }, maxOf(delayMs, 1000L))
            }
        })
    }

    private fun findWord(text: Text, words: List<String>): Pair<Float, Float>? {
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

    // ---------- Buscar imagen ----------
    private fun findImageStep(step: Step, id: Int) {
        val t = templates[step.id]
        if (t == null) {
            stopClicking()
            toast("Falta la imagen del paso ${idx + 1}")
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = result.hardwareBuffer
                val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (bmp == null) { main.post { if (id == runId) miss() }; return }
                val p = findImage(bmp, t)
                main.post {
                    if (id == runId) {
                        if (p != null) hit(p) else miss()
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                if (id == runId && running) main.postDelayed({ runStep() }, maxOf(delayMs, 1000L))
            }
        })
    }

    private fun findImage(bmp: Bitmap, t: Mat): Pair<Float, Float>? {
        val src = Mat()
        Utils.bitmapToMat(bmp, src)
        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
        if (t.cols() > src.cols() || t.rows() > src.rows()) { src.release(); return null }
        val res = Mat()
        Imgproc.matchTemplate(src, t, res, Imgproc.TM_CCOEFF_NORMED)
        val mm = Core.minMaxLoc(res)
        src.release()
        res.release()
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
