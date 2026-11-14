package com.example.autoclick

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/**
 * Lector gratis de comentarios: abre la publicación en Instagram, va bajando por los comentarios
 * y apunta quién ha escrito cada uno (sin Apify). Lo usa el Comprobador de comentarios.
 */
object Lector {
    private val main = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private lateinit var status: TextView
    private lateinit var mainBtn: Button
    private var started = false
    private var rounds = 0
    private var sameRounds = 0
    private var accounts: Set<String> = emptySet()
    /** Comentarios leídos: clave autor|texto → (autor, texto). */
    private val seen = LinkedHashMap<String, Pair<String, String>>()

    /** Resultado de la última lectura: [[usuario, comentario], ...] (lo recoge ComprobarActivity). */
    var result: JSONArray? = null

    private val handleRe = Regex("^@?([A-Za-z0-9._]{2,30})$")
    private val timeRe = Regex("^(\\d+\\s*(s|min|h|d|sem|w|m|a|y)|hace .*|ahora|now|editado|edited)$", RegexOption.IGNORE_CASE)
    private val uiRe = Regex(
        "^(responder|reply|ver traducción|see translation|me gusta|like|\\d+[.,]?\\d*\\s*(me gusta|likes?)|ocultar respuestas|hide replies|" +
            "ver \\d+ respuestas?.*|view \\d+ (more )?repl.*|—+.*|autor|author|fijado|pinned|seguir|follow|verificado|verified)$",
        RegexOption.IGNORE_CASE
    )
    private val moreRe = Regex("(ver más comentarios|cargar más comentarios|view more comments|load more comments)", RegexOption.IGNORE_CASE)

    fun begin(ctx: Context, url: String, accs: Collection<String>) {
        val s = ClickService.instance ?: return
        stop()
        accounts = accs.map { it.lowercase().removePrefix("@") }.toSet()
        seen.clear(); started = false; rounds = 0; sameRounds = 0; result = null
        try {
            s.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage("com.instagram.android").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            try { s.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) {}
        }
        showPanel(s)
        say("1️⃣ Abre los comentarios de la publicación (el bocadillo 💬).\n2️⃣ Pulsa «Empezar»: la app los lee y baja sola.")
        main.postDelayed(tick, 1500)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (panel == null) return
            try { if (started) round() } catch (e: Exception) {}
            main.postDelayed(this, 1400L)
        }
    }

    private fun igRoot(s: ClickService): AccessibilityNodeInfo? {
        try {
            for (w in s.windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() == "com.instagram.android") return r
            }
        } catch (e: Exception) {}
        val r = s.rootInActiveWindow
        return if (r?.packageName?.toString() == "com.instagram.android") r else null
    }

    private fun textsOf(n: AccessibilityNodeInfo?, out: ArrayList<String>, d: Int = 0) {
        if (n == null || d > 25) return
        if (n.isVisibleToUser) {
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t.replace('\n', ' '))
            else {
                val ds = n.contentDescription?.toString()?.trim()
                val cls = n.className?.toString() ?: ""
                if (!ds.isNullOrEmpty() && !cls.contains("Image") && ds.length < 300) out.add(ds.replace('\n', ' '))
            }
        }
        for (i in 0 until n.childCount) textsOf(n.getChild(i), out, d + 1)
    }

    /** (autor, comentario) de una fila de la lista de comentarios, o null si no lo es. */
    private fun rowOf(texts0: List<String>): Pair<String, String>? {
        val texts = texts0.map { it.trim() }.filter { it.isNotEmpty() && !timeRe.matches(it) && !uiRe.matches(it) }
        if (texts.isEmpty()) return null
        val first = texts[0]
        val h = handleRe.find(first)
        return if (h != null) {
            val body = texts.drop(1).joinToString(" ").trim()
            if (body.isEmpty()) null else h.groupValues[1].lowercase() to body
        } else {
            // A veces el nombre y el comentario van juntos: «cory_gm0 hola @ana»
            val sp = first.indexOf(' ')
            if (sp <= 1) return null
            val u = handleRe.find(first.substring(0, sp)) ?: return null
            val body = (first.substring(sp + 1) + " " + texts.drop(1).joinToString(" ")).trim()
            u.groupValues[1].lowercase() to body
        }
    }

    private fun lists(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            val cls = n.className?.toString() ?: ""
            if (n.isVisibleToUser && n.childCount > 0 && (n.isScrollable || cls.contains("RecyclerView") || cls.contains("ListView"))) out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return out
    }

    private fun round() {
        val s = ClickService.instance ?: return
        val root = igRoot(s)
        if (root == null) { say("Vuelve a Instagram para seguir leyendo"); return }
        rounds++
        // La lista que más comentarios da es la de los comentarios
        var best: List<Pair<String, String>> = emptyList()
        var bestList: AccessibilityNodeInfo? = null
        for (l in lists(root)) {
            val rows = ArrayList<Pair<String, String>>()
            for (i in 0 until l.childCount) {
                val t = ArrayList<String>(); textsOf(l.getChild(i), t)
                rowOf(t)?.let { rows.add(it) }
            }
            if (rows.size > best.size) { best = rows; bestList = l }
        }
        val before = seen.size
        for ((u, t) in best) seen.putIfAbsent("$u|$t", u to t)
        if (seen.size == before) sameRounds++ else sameRounds = 0

        val mine = seen.values.count { it.first in accounts }
        when {
            seen.isEmpty() && rounds > 6 -> say("No veo comentarios. ¿Has abierto los comentarios de la publicación?")
            sameRounds >= 10 -> { say("✅ Ya están todos: ${seen.size} comentarios, $mine de tus cuentas."); finish(); return }
            sameRounds >= 4 -> say("💬 ${seen.size} leídos · $mine de tus cuentas\nCreo que ya casi están todos…")
            else -> say("💬 ${seen.size} leídos · $mine de tus cuentas\nBajando… (pulsa «Terminar» cuando quieras)")
        }
        if (!clickMore(root)) scrollDown(s, root, bestList)
    }

    /** Pulsa «Ver más comentarios» si aparece. */
    private fun clickMore(root: AccessibilityNodeInfo): Boolean {
        var hit: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60 || hit != null) return
            val t = (n.text ?: n.contentDescription)?.toString() ?: ""
            if (n.isVisibleToUser && moreRe.containsMatchIn(t)) { hit = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        var n = hit ?: return false
        var k = 0
        while (!n.isClickable && k < 4) { n = n.parent ?: break; k++ }
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Baja un poco (por el lado derecho, para no tocar nombres ni enlaces). */
    private fun scrollDown(s: ClickService, root: AccessibilityNodeInfo, list: AccessibilityNodeInfo?) {
        val box = Rect()
        (list ?: root).getBoundsInScreen(box)
        val x = box.left + box.width() * 0.9f
        val y1 = box.top + box.height() * 0.75f
        val y2 = box.top + box.height() * 0.30f
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val ok = try {
            s.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 700)).build(), null, null)
        } catch (e: Exception) { false }
        if (!ok) list?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun finish() {
        val s = ClickService.instance
        val arr = JSONArray()
        for ((u, t) in seen.values) arr.put(JSONArray().put(u).put(t))
        result = arr
        try {
            (s?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)
                ?.vibrate(android.os.VibrationEffect.createOneShot(400, 255))
        } catch (e: Exception) {}
        stop()
        if (s != null) {
            try {
                s.startActivity(Intent(s, ComprobarActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("lectura", true))
            } catch (e: Exception) {}
        }
    }

    fun stop() {
        main.removeCallbacks(tick)
        val p = panel ?: return
        panel = null
        val s = ClickService.instance ?: return
        try { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(p) } catch (e: Exception) {}
    }

    private fun say(t: String) { if (panel != null) status.text = t }

    private fun showPanel(s: ClickService) {
        val d = s.resources.displayMetrics.density
        val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0xEE2D1B69.toInt()) }
        }
        status = TextView(s).apply { setTextColor(Color.WHITE); textSize = 14f }
        box.addView(status, LinearLayout.LayoutParams((260 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, color: Int, f: () -> Unit) = Button(s).apply {
            text = t; setTextColor(Color.WHITE); textSize = 13f; isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(color) }
            setOnClickListener { f() }
        }
        mainBtn = btn("▶️ Empezar", 0xFF16A34A.toInt()) {
            if (!started) { started = true; mainBtn.text = "■ Terminar"; say("Leyendo…") } else finish()
        }
        val cancel = btn("✕", 0xFFDC2626.toInt()) { stop() }
        row.addView(mainBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = (8 * d).toInt() })
        row.addView(cancel, LinearLayout.LayoutParams((48 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt(); leftMargin = (8 * d).toInt() })
        box.addView(row)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * d).toInt(); y = (40 * d).toInt() }
        // Se puede arrastrar desde el texto
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    try { wm.updateViewLayout(box, lp) } catch (ex: Exception) {}
                }
            }
            true
        }
        wm.addView(box, lp)
        panel = box
    }
}
