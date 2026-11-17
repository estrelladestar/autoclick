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
import android.os.Build
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


    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var ocrChecked = false

    /** Lee el texto de una captura. La primera vez apunta que carga el lector (por si cierra la app). */
    private fun ocr(bmp: Bitmap): com.google.android.gms.tasks.Task<Text> {
        val first = !ocrChecked
        if (first) {
            ocrChecked = true
            Macro.mark(this, "cargando el lector de texto…")
        }
        val task = recognizer.process(InputImage.fromBitmap(bmp, 0))
        if (first) task.addOnCompleteListener {
            Macro.mark(this, if (it.isSuccessful) "lector de texto listo" else "el lector de texto falló: ${it.exception?.message}")
        }
        return task
    }

    private var running = false
    private var runId = 0
    private var steps = listOf<Step>()
    private val templates = HashMap<Long, Mat>()
    /** Las mismas imágenes en gris, para buscarlas sin OpenCV. */
    private val grayTemplates = HashMap<Long, FastMatch.Gray>()
    private var threshold = 0.8
    private var delayMs = 500L
    private var repeatTotal = 1
    private var timeoutMs = 0L

    private var idx = 0
    private var clicksDone = 0
    private var loopsDone = 0
    private var stepStart = 0L

    private var overlay: Button? = null
    /** Lo que se añade a la pantalla: el botón flotante y su ✕ para quitarlo. */
    private var overlayRoot: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var pickView: View? = null

    /** ¿Se cargó OpenCV? Si no (móvil de 32 bits o falló), las imágenes se buscan con FastMatch. */
    private var cvOk = false

    override fun onCreate() {
        super.onCreate()
        Macro.installCrashLogger(this)
        Macro.mark(this, "Android crea el servicio")
    }

    override fun onServiceConnected() {
        instance = this
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        Macro.mark(this, "servicio de accesibilidad arrancado (versión $ver, Android ${Build.VERSION.RELEASE}, ${if (is64) "64" else "32"} bits)")
        if (is64) {
            // Que un fallo al cargar OpenCV no tumbe el servicio
            Macro.mark(this, "cargando OpenCV…")
            cvOk = try { OpenCVLoader.initLocal() } catch (e: Throwable) { Macro.trace("OpenCV: ${e.message}"); false }
            Macro.mark(this, if (cvOk) "OpenCV cargado" else "OpenCV no se pudo cargar: busco las imágenes sin él")
        } else {
            // En móviles de 32 bits OpenCV podía cerrar el servicio nada más arrancar: no se carga
            Macro.mark(this, "móvil de 32 bits: busco las imágenes sin OpenCV")
        }
    }

    override fun onDestroy() {
        Macro.mark(this, "servicio cerrado")
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        var instance: ClickService? = null

        /** ¿Está Star App encendida en Ajustes → Accesibilidad (aunque el servicio no haya arrancado)? */
        fun enabledInSettings(ctx: Context): Boolean {
            val list = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }

    override fun onUnbind(intent: Intent?): Boolean {
        Macro.mark(this, "servicio parado (apagado en Ajustes o cerrado por el móvil)")
        stopClicking(); hideOverlay(); closePicker(); instance = null
        ScreenCaptureService.instance?.stopSelf()
        return super.onUnbind(intent)
    }

    // ---------- Botón flotante (se arrastra; toque = iniciar/parar; mantener = ocultar) ----------
    fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val b = Button(this).apply { text = if (running) "■" else "▶" }
        StarColors.overlay(b)
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
        // ✕ pequeño al lado para quitar el botón flotante (y parar lo que esté en marcha)
        val d = resources.displayMetrics.density
        val close = Button(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xCC2D1B69.toInt())
            }
            elevation = 6 * d
            setOnClickListener { hideOverlay() }
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(b)
            addView(close, android.widget.LinearLayout.LayoutParams(
                (34 * d).toInt(), (34 * d).toInt()).apply { leftMargin = (6 * d).toInt() })
        }
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
                        wm.updateViewLayout(box, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        if (e.eventTime - downTime > 700) hideOverlay()
                        else if (Macro.running) Macro.stop()
                        else if (running) stopClicking()
                        else startClicking()
                    }
                    true
                }
                else -> false
            }
        }
        // Más estrecho, para que tape menos pantalla (el texto baja a otra línea)
        b.maxWidth = (screenSize().first * 0.58f).toInt()
        b.textSize = 12f
        wm.addView(box, lp)
        overlay = b
        overlayRoot = box
        overlayLp = lp
    }

    /** ¿Se ve el botón flotante? */
    fun overlayShown(): Boolean = overlayRoot != null

    /** Para que el guion de Comentarios automáticos pueda cambiar el texto del botón flotante. */
    fun setOverlayText(t: String) {
        overlay?.text = t
        overlay?.let { StarColors.overlay(it) }
    }

    /** Para que el guion pueda tocar una posición de la pantalla. */
    fun tapAt(x: Float, y: Float) {
        tap(x, y)
    }

    /**
     * Manda un gesto que empieza en (x, y). Si ese punto cae encima del botón flotante, primero aparta
     * el botón (si no, la app se tocaría a sí misma y pararía el guion).
     */
    // ---------- Toques "humanos": nunca exactamente en el mismo píxel ni con la misma duración ----------
    private val rnd = java.util.Random()

    /** Máximo que se mueve un toque desde el punto pedido (unos 6 dp: siempre cae dentro del botón). */
    private fun maxJitterPx(): Float = 6f * resources.displayMetrics.density

    /** Devuelve (x, y) movido un poquito al azar (más a menudo cerca del centro, como un dedo de verdad). */
    private fun jitter(x: Float, y: Float): Pair<Float, Float> {
        val m = maxJitterPx()
        val dx = (rnd.nextGaussian() * m / 2.5).toFloat().coerceIn(-m, m)
        val dy = (rnd.nextGaussian() * m / 2.5).toFloat().coerceIn(-m, m)
        val (w, h) = screenSize()
        return Pair((x + dx).coerceIn(1f, w - 2f), (y + dy).coerceIn(1f, h - 2f))
    }

    /** Duración al azar entre a y b milisegundos. */
    private fun rndMs(a: Long, b: Long): Long = a + (rnd.nextDouble() * (b - a)).toLong()

    private fun send(g: GestureDescription, x: Float, y: Float) {
        val v = overlayRoot
        val lp = overlayLp
        if (v != null && lp != null && v.width > 0) {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val pad = (12 * resources.displayMetrics.density).toInt()
            val r = android.graphics.Rect(loc[0] - pad, loc[1] - pad, loc[0] + v.width + pad, loc[1] + v.height + pad)
            if (r.contains(x.toInt(), y.toInt())) {
                val h = screenSize().second
                lp.y = if (y > h / 2f) (h * 0.10f).toInt() else (h * 0.62f).toInt()
                try {
                    (getSystemService(Context.WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp)
                } catch (e: Exception) {
                }
                Macro.trace("  aparto el botón flotante para no tocarlo")
                main.postDelayed({ dispatchGesture(g, null, null) }, 250L)
                return
            }
        }
        dispatchGesture(g, null, null)
    }

    /** Toque un poco más largo, como el de un dedo (para listas que no hacen caso a toques muy cortos). */
    fun fingerTap(x0: Float, y0: Float, ms: Long = 150L) {
        val (x, y) = jitter(x0, y0)
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, rndMs(ms * 8 / 10, ms * 13 / 10))).build()
        send(g, x, y)
    }

    /** Para que el guion pueda mantener pulsada una posición de la pantalla (pulsación larga). */
    /** Desliza el dedo de (x1,y1) a (x2,y2) en ms milisegundos (para mover listas como los comentarios). */
    fun swipe(ax: Float, ay: Float, bx: Float, by: Float, ms: Long) {
        val (x1, y1) = jitter(ax, ay)
        val (x2, y2) = jitter(bx, by)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, rndMs(ms * 85 / 100, ms * 115 / 100).coerceAtLeast(1L))).build()
        send(g, x1, y1)
    }

    /** Doble toque rápido (como el doble toque en la foto de perfil de Instagram, que cambia de cuenta). */
    fun doubleTapAt(x0: Float, y0: Float) {
        val (x, y) = jitter(x0, y0)
        val d = 1.5f * resources.displayMetrics.density   // el segundo toque cae casi en el mismo sitio
        val x2 = x + ((rnd.nextFloat() * 2 - 1) * d)
        val y2 = y + ((rnd.nextFloat() * 2 - 1) * d)
        val p1 = Path().apply { moveTo(x, y) }
        val p2 = Path().apply { moveTo(x2, y2) }
        val t1 = rndMs(35, 60)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, t1))
            .addStroke(GestureDescription.StrokeDescription(p2, t1 + rndMs(80, 120), rndMs(35, 60)))
            .build()
        send(g, x, y)
    }

    fun longPressAt(x0: Float, y0: Float) {
        val (x, y) = jitter(x0, y0)
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, rndMs(850, 1150))).build()
        send(g, x, y)
    }

    /** Tamaño real de la pantalla en píxeles (ancho, alto). */
    fun screenSize(): Pair<Int, Int> {
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    /** Para que el guion pueda abrir uno de los enlaces guardados en la app (0 a 4). */
    fun openSavedLink(index: Int): Boolean {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val u = (prefs.getString("link$index", "") ?: "").trim()
        if (u.isEmpty()) return false
        openLink(u)
        return true
    }

    /** Enlace marcado en el Etiquetador (vacío si no hay ninguno). */
    fun selectedLinkUrl(): String =
        (getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("linkSelUrl", "") ?: "").trim()

    /** Abre una dirección (la del enlace marcado en el Etiquetador). */
    fun openUrl(url: String) {
        openLink(url)
    }

    /** Cuántos enlaces hay guardados (los que no están vacíos). */
    fun savedLinks(): List<Int> {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        return (0 until 5).filter { (prefs.getString("link$it", "") ?: "").trim().isNotEmpty() }
    }

    /**
     * Para que el guion pueda buscar en pantalla una de las imágenes ya subidas en la app
     * (la n-ésima imagen de la lista de pasos, empezando en 1).
     * done(null) si la imagen no está en pantalla; done(false, ...) si falta la imagen.
     * El resultado se entrega en el hilo principal.
     */
    fun locateSavedImage(n: Int, done: (found: Pair<Float, Float>?, missing: Boolean) -> Unit) {
        val imgs = StepStore.load(this).filter { it.type == "image" }
        val step = imgs.getOrNull(n - 1)
        if (step == null) {
            main.post { done(null, true) }
            return
        }
        if (!prepareTemplate(step)) {
            main.post { done(null, true) }
            return
        }
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        threshold = prefs.getFloat("th", 0.8f).toDouble()
        val skip = overlayBox()
        capture({ bmp ->
            val p = if (bmp == null) null else findSaved(bmp, step.id)
            // Para saber después si al publicar ha aparecido algo en rojo que antes no estaba
            redBefore = bmp != null && hasRedText(bmp, skip)
            main.post { done(p, false) }
        }, {
            main.post { done(null, false) }
        })
    }

    /** Dónde está ahora el botón flotante (con margen), para no confundir su color rojo con un aviso. */
    private fun overlayBox(): android.graphics.Rect? {
        val v = overlayRoot ?: return null
        if (v.width <= 0) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val pad = (24 * resources.displayMetrics.density).toInt()
        return android.graphics.Rect(loc[0] - pad, loc[1] - pad, loc[0] + v.width + pad, loc[1] + v.height + pad)
    }

    /** Si en la última captura (antes de tocar una imagen) ya había una línea de letras rojas. */
    @Volatile var redBefore = false

    /**
     * ¿Hay en la pantalla una línea de letras rojas (como los avisos de error de Instagram)?
     * Busca franjas con bastantes píxeles rojos, que ocupen a lo ancho y en muchos trocitos, como las letras
     * (un icono rojo, un corazón o un punto rojo no cuentan).
     * No mira la barra de arriba ni la de abajo.
     */
    fun hasRedText(src: Bitmap, skip: android.graphics.Rect?): Boolean {
        return try {
            val b = if (src.config == Bitmap.Config.ARGB_8888) src else (src.copy(Bitmap.Config.ARGB_8888, false) ?: return false)
            val w = b.width
            val h = b.height
            val y0 = (h * 0.04f).toInt()
            val y1 = (h * 0.90f).toInt()
            val bandH = maxOf(8, h / 45)
            val bands = (y1 - y0) / bandH + 1
            val count = IntArray(bands)
            val minX = IntArray(bands) { Int.MAX_VALUE }
            val maxX = IntArray(bands) { -1 }
            // Trozos rojos separados en una misma fila: las letras dan muchos, un icono (corazón, punto) muy pocos
            val maxRuns = IntArray(bands)
            val row = IntArray(w)
            for (y in y0 until y1) {
                b.getPixels(row, 0, w, 0, y, w, 1)
                val k = (y - y0) / bandH
                val inSkip = skip != null && y >= skip.top && y <= skip.bottom
                var runs = 0
                var prevRed = false
                for (x in 0 until w) {
                    if (inSkip && x >= skip!!.left && x <= skip.right) {
                        prevRed = false
                        continue
                    }
                    val c = row[x]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val bl = c and 0xff
                    val red = r >= 170 && g <= 110 && bl <= 125 && r - g >= 100 && r - bl >= 90
                    if (red) {
                        count[k]++
                        if (x < minX[k]) minX[k] = x
                        if (x > maxX[k]) maxX[k] = x
                        if (!prevRed) runs++
                    }
                    prevRed = red
                }
                if (runs > maxRuns[k]) maxRuns[k] = runs
            }
            // Solo cuenta si parecen letras: bastante rojo, a lo ancho y en muchos trocitos (10 o más en una fila)
            (0 until bands).any { count[it] >= 100 && maxX[it] - minX[it] >= w * 0.15f && maxRuns[it] >= 10 }
        } catch (e: Throwable) {
            false
        }
    }

    /** Hace una captura y dice si hay letras rojas (false si no se puede capturar). */
    fun checkRedText(done: (Boolean) -> Unit) {
        val skip = overlayBox()
        capture({ bmp ->
            val red = bmp != null && hasRedText(bmp, skip)
            main.post { done(red) }
        }, {
            main.post { done(false) }
        })
    }

    /** Quita el botón flotante y para el guion o la secuencia que esté en marcha. */
    fun hideOverlay() {
        Macro.stop()
        stopClicking()
        val o = overlayRoot ?: overlay
        if (o != null) {
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(o)
            } catch (e: Exception) {
            }
        }
        overlay = null
        overlayRoot = null
        overlayLp = null
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            ScreenCaptureService.instance == null &&
            steps.any { it.type != "point" }
        ) {
            toast("Primero pulsa \"Permitir captura de pantalla\" en la app")
            return
        }

        templates.clear()
        grayTemplates.clear()
        for (s in steps) {
            if (s.type != "image") continue
            if (!prepareTemplate(s)) { toast("Falta una imagen: vuelve a añadirla en la app"); return }
        }

        main.removeCallbacksAndMessages(null)
        runId++
        idx = 0
        clicksDone = 0
        loopsDone = 0
        running = true
        setOverlayText("■")

        links.forEachIndexed { k, u -> main.postDelayed({ openLink(u) }, k * linkWaitMs) }
        val wait = links.size * linkWaitMs
        stepStart = System.currentTimeMillis() + wait
        main.postDelayed({ runStep() }, wait)
    }

    private fun stopClicking() {
        running = false
        runId++
        main.removeCallbacksAndMessages(null)
        // Si el guion de comentarios sigue en marcha, el botón no se toca (seguiría en rojo)
        if (!Macro.running) setOverlayText("▶")
    }

    /** El último enlace abierto (para saber qué cuentas ya subieron esa publicación a su historia). */
    @Volatile var lastUrl: String = ""

    private fun openLink(url: String) {
        val u = if (url.contains("://")) url else "https://$url"
        lastUrl = u
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

    // ---------- Captura de pantalla ----------
    /**
     * Android 11 o superior: captura con el servicio de accesibilidad.
     * Android 10 o inferior (por ejemplo EMUI 12): captura con MediaProjection
     * (ScreenCaptureService), que se permite desde un botón de la app.
     * ok() se llama en el hilo de trabajo; fail() si la captura falló y hay que reintentar.
     */
    private fun capture(ok: (Bitmap?) -> Unit, fail: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = try {
                        val hw = result.hardwareBuffer
                        val b = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hw.close()
                        b
                    } catch (e: Exception) {
                        null
                    }
                    try { ok(bmp) } catch (e: Exception) { fail() }
                }

                override fun onFailure(errorCode: Int) {
                    Macro.trace("  no se pudo capturar la pantalla (código $errorCode)")
                    fail()
                }
            })
        } else {
            val svc = ScreenCaptureService.instance
            if (svc == null) {
                main.post {
                    if (running) stopClicking()
                    toast("Falta permitir la captura de pantalla (botón «Permitir captura» en la app)")
                }
                Macro.trace("  falta el permiso de captura de pantalla")
                // Avisar al que esperaba la captura, para que no se quede colgado
                fail()
            } else {
                worker.execute {
                    val b = try { svc.grab() } catch (e: Exception) { null }
                    try { ok(b) } catch (e: Exception) { fail() }
                }
            }
        }
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
        capture({ bmp ->
            if (bmp == null) {
                main.post { if (id == runId) miss() }
            } else {
                ocr(bmp)
                    .addOnSuccessListener {
                        if (id == runId) {
                            val p = findWord(it, words)
                            if (p != null) hit(p) else miss()
                        }
                    }
                    .addOnFailureListener { if (id == runId) miss() }
            }
        }, {
            if (id == runId && running) main.postDelayed({ runStep() }, maxOf(delayMs, 1000L))
        })
    }

    /**
     * Para el guion: busca un texto en la pantalla con OCR (lee la captura) y devuelve dónde está.
     * Compara solo letras y números, sin mayúsculas, para que «del_mar190» se encuentre aunque el OCR lea mal el «_».
     * El resultado se entrega en el hilo principal.
     */
    fun locateText(word: String, done: (Pair<Float, Float>?) -> Unit) {
        val want = word.lowercase().filter { it.isLetterOrDigit() }
        if (want.isEmpty()) { main.post { done(null) }; return }
        capture({ bmp ->
            if (bmp == null) {
                main.post { done(null) }
            } else {
                ocr(bmp)
                    .addOnSuccessListener { text ->
                        var found: Pair<Float, Float>? = null
                        loop@ for (block in text.textBlocks) for (line in block.lines) {
                            for (el in line.elements) {
                                if (el.text.lowercase().filter { it.isLetterOrDigit() } == want) {
                                    val b = el.boundingBox ?: continue
                                    found = Pair(b.exactCenterX(), b.exactCenterY()); break@loop
                                }
                            }
                            if (line.text.lowercase().filter { it.isLetterOrDigit() } == want) {
                                val b = line.boundingBox ?: continue
                                found = Pair(b.exactCenterX(), b.exactCenterY()); break@loop
                            }
                        }
                        main.post { done(found) }
                    }
                    .addOnFailureListener { main.post { done(null) } }
            }
        }, {
            main.post { done(null) }
        })
    }

    /** Para el guion: lee con OCR todas las líneas de texto de la pantalla con su posición. Resultado en el hilo principal. */
    fun readScreen(done: (List<Pair<String, android.graphics.Rect>>?) -> Unit) {
        capture({ bmp ->
            if (bmp == null) {
                main.post { done(null) }
            } else {
                ocr(bmp)
                    .addOnSuccessListener { text ->
                        val out = ArrayList<Pair<String, android.graphics.Rect>>()
                        for (block in text.textBlocks) for (line in block.lines) {
                            val b = line.boundingBox ?: continue
                            out.add(Pair(line.text.trim(), b))
                        }
                        main.post { done(out) }
                    }
                    .addOnFailureListener { main.post { done(null) } }
            }
        }, {
            main.post { done(null) }
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
        if (templates[step.id] == null && grayTemplates[step.id] == null) {
            stopClicking()
            toast("Falta la imagen del paso ${idx + 1}")
            return
        }
        capture({ bmp ->
            if (bmp == null) {
                main.post { if (id == runId) miss() }
            } else {
                val p = findSaved(bmp, step.id)
                main.post {
                    if (id == runId) {
                        if (p != null) hit(p) else miss()
                    }
                }
            }
        }, {
            if (id == runId && running) main.postDelayed({ runStep() }, maxOf(delayMs, 1000L))
        })
    }

    /** Prepara la imagen guardada de un paso para buscarla (con OpenCV o sin él). false si falta el archivo. */
    private fun prepareTemplate(step: Step): Boolean {
        if (!cvOk) {
            if (grayTemplates[step.id] != null) return true
            val bmp = BitmapFactory.decodeFile(File(filesDir, step.value).path) ?: return false
            grayTemplates[step.id] = toGray(bmp) ?: return false
            return true
        }
        if (templates[step.id] != null) return true
        val bmp = BitmapFactory.decodeFile(File(filesDir, step.value).path) ?: return false
        val m = Mat()
        Utils.bitmapToMat(bmp, m)
        Imgproc.cvtColor(m, m, Imgproc.COLOR_RGBA2GRAY)
        templates[step.id] = m
        return true
    }

    /** Busca en la captura la imagen del paso. Devuelve su centro o null. */
    private fun findSaved(bmp: Bitmap, stepId: Long): Pair<Float, Float>? {
        val g = grayTemplates[stepId]
        if (g != null) {
            val s = toGray(bmp) ?: return null
            val r = FastMatch.find(s, g) ?: return null
            if (r.score < threshold) {
                Macro.trace("  (lo más parecido a la imagen da ${"%.2f".format(r.score)}; hace falta ${"%.2f".format(threshold)})")
                return null
            }
            return Pair(r.x + g.w / 2f, r.y + g.h / 2f)
        }
        val t = templates[stepId] ?: return null
        return findImage(bmp, t)
    }

    /** Pasa una imagen a gris (un número de 0 a 255 por píxel) para FastMatch. */
    private fun toGray(src: Bitmap): FastMatch.Gray? {
        val b = if (src.config == Bitmap.Config.ARGB_8888) src else (src.copy(Bitmap.Config.ARGB_8888, false) ?: return null)
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        FastMatch.toGrayInPlace(px)
        return FastMatch.Gray(px, w, h)
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
    private fun tap(x0: Float, y0: Float) {
        val (x, y) = jitter(x0, y0)
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, rndMs(45, 110))).build()
        send(g, x, y)
    }

    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
