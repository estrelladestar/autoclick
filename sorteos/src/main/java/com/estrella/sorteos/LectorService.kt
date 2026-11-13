package com.estrella.sorteos

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Lee los comentarios desde la app de Instagram o de TikTok (no desde la web).
 * Abre la publicación o el directo en la app, pone un panel flotante encima con
 * «Empezar» / «Terminar», y va leyendo el texto que hay en la pantalla.
 * En una publicación además baja sola por la lista de comentarios.
 * Solo mira las apps de Instagram y TikTok (ver res/xml/lector_config.xml).
 */
/** Lo recogido, para que MainActivity se lo pase a la app al volver. */
object LectorResult {
    var ready = false
    var live = false
    val snaps = ArrayList<Pair<String, String>>()   // (modo de lectura, texto de una pantalla) — publicaciones
    val rows = ArrayList<Pair<String, String>>()    // (usuario, comentario) — directo
    var lastDump = ""                                // lo último leído, para ayudar a ajustar la app
    fun clear() { ready = false; snaps.clear(); rows.clear() }
}

class LectorService : AccessibilityService() {

    companion object {
        var instance: LectorService? = null
        val PKGS = setOf("com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme")

        fun enabledInSettings(ctx: Context): Boolean {
            val list = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private lateinit var status: TextView
    private lateinit var mainBtn: Button
    private lateinit var closeBtn: Button

    private var liveMode = false
    private var started = false
    private var cancelArmed = false
    private var rounds = 0
    private var sameRounds = 0
    private var noTextRounds = 0
    private val seenSnaps = HashSet<String>()
    private val replyKeys = HashSet<String>()           // para contar comentarios (cada uno tiene su «Responder»)
    private val liveBase = HashSet<String>()
    private val liveSeen = HashSet<String>()
    private val livePeople = HashSet<String>()

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { stop(); instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { stop(); if (instance === this) instance = null; super.onDestroy() }

    // ---------- empezar ----------
    fun begin(url: String, live: Boolean) {
        stop()
        LectorResult.clear(); LectorResult.live = live
        liveMode = live; started = false; rounds = 0; sameRounds = 0; noTextRounds = 0
        seenSnaps.clear(); replyKeys.clear(); liveBase.clear(); liveSeen.clear(); livePeople.clear()
        openInApp(url)
        showPanel()
        if (live) {
            say("🔴 Entra en el directo en la app. Cuando quieras empezar, pulsa «Empezar a contar».")
            mainBtn.text = "▶️ Empezar a contar"
        } else {
            say("1️⃣ Abre los comentarios de la publicación (el bocadillo 💬).\n2️⃣ Pulsa «Empezar»: la app los lee y baja sola.")
            mainBtn.text = "▶️ Empezar"
        }
        main.postDelayed(tick, 1500)
    }

    private fun openInApp(url: String) {
        val uri = Uri.parse(url)
        val tt = url.contains("tiktok", true)
        val pkgs = if (tt) listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme") else listOf("com.instagram.android")
        for (p in pkgs) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            toast("No encuentro la app de ${if (tt) "TikTok" else "Instagram"} instalada")
        } catch (e: Exception) { toast("No se pudo abrir el enlace") }
    }

    // ---------- bucle ----------
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (panel == null) return
            try { if (liveMode) liveRound() else postRound() } catch (e: Exception) {}
            main.postDelayed(this, if (liveMode) 1000L else 1300L)
        }
    }

    /** La ventana de Instagram/TikTok que se ve ahora. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val r = w.root ?: continue
                if (r.packageName?.toString() in PKGS) return r
            }
        } catch (e: Exception) {}
        val r = rootInActiveWindow
        return if (r != null && r.packageName?.toString() in PKGS) r else null
    }

    private fun isTikTok(root: AccessibilityNodeInfo) = !root.packageName.toString().contains("instagram")

    /** Todo el texto visible, en orden, una línea por elemento. */
    private fun dump(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (n.isVisibleToUser) {
                val cls = n.className?.toString() ?: ""
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty()) out.add(t.replace('\n', ' '))
                else if (!cls.contains("Image")) {
                    val d = n.contentDescription?.toString()?.trim()
                    if (!d.isNullOrEmpty() && d.length < 200) out.add(d.replace('\n', ' '))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private val replyRe = Regex("^(responder|reply)$", RegexOption.IGNORE_CASE)

    private fun postRound() {
        val root = targetRoot()
        if (root == null) { if (started) say("Vuelve a la app de Instagram/TikTok para seguir leyendo"); return }
        val lines = dump(root)
        LectorResult.lastDump = "[${root.packageName}]\n" + lines.joinToString("\n") + "\n---\n" + treeDump(root)
        if (!started) return
        rounds++
        val mode = if (isTikTok(root)) "app-tt" else "app-ig"
        val text = lines.joinToString("\n")
        if (seenSnaps.add(text)) LectorResult.snaps.add(mode to text)
        // Contar: cada comentario lleva su «Responder»; la clave son las dos líneas de antes
        val before = replyKeys.size
        for (i in lines.indices) {
            if (replyRe.matches(lines[i]) || lines[i].endsWith(" Responder") || lines[i].endsWith(" Reply")) {
                replyKeys.add(lines.getOrNull(i - 2).orEmpty() + "|" + lines.getOrNull(i - 1).orEmpty())
            }
        }
        if (replyKeys.size == before) sameRounds++ else sameRounds = 0
        val n = replyKeys.size
        when {
            n == 0 && rounds > 6 -> say("No veo comentarios. ¿Has abierto los comentarios de la publicación?")
            sameRounds == 8 -> { say("💬 $n comentarios · parece que ya están todos ✅\nPulsa «Terminar»."); vibrate() }
            sameRounds > 8 -> say("💬 $n comentarios · parece que ya están todos ✅\nPulsa «Terminar».")
            else -> say("💬 $n comentarios leídos… bajando")
        }
        scrollDown(root)
    }

    /** Baja por la lista de comentarios: primero con la acción de la lista; si no, con un deslizamiento. */
    private fun scrollDown(root: AccessibilityNodeInfo) {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 60) return
            if (n.isScrollable && n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                val a = r.width() * r.height()
                if (a > bestArea) { bestArea = a; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        val b = best
        if (b != null && b.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
        val box = Rect()
        if (b != null) b.getBoundsInScreen(box) else root.getBoundsInScreen(box)
        val x = box.exactCenterX()
        val y1 = box.top + box.height() * 0.78f
        val y2 = box.top + box.height() * 0.30f
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        try { dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null) } catch (e: Exception) {}
    }

    // ---------- directo ----------
    private val badRe = Regex("(se ha unido|se uni[oó]|joined|ha empezado a seguir|started following|followed|le ha dado|liked|ha enviado|sent |compartid|shared|está viendo|watching|dio me gusta|bienvenid|welcome)", RegexOption.IGNORE_CASE)
    private val handleRe = Regex("^@?([A-Za-z0-9._]{2,30})$")

    /** Textos de la pantalla del directo que NO son comentarios. */
    private val uiWords = setOf("live", "en directo", "directo", "seguir", "follow", "siguiendo", "following", "compartir", "share",
        "regalo", "regalos", "gift", "gifts", "añade un comentario...", "añade un comentario", "add comment...", "add comment",
        "comentar", "comment", "di algo...", "say something...", "espectadores", "viewers", "top", "ranking", "invitar", "invite",
        "más", "more", "x", "multi-guest", "invitado", "invitados", "suscribirse", "subscribe", "tienda", "shop")

    private fun textsOf(n: AccessibilityNodeInfo?, out: ArrayList<String>, d: Int = 0) {
        if (n == null || d > 25) return
        if (n.isVisibleToUser) {
            val t = n.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) out.add(t.replace('\n', ' '))
            else {
                val ds = n.contentDescription?.toString()?.trim()
                val cls = n.className?.toString() ?: ""
                if (!ds.isNullOrEmpty() && !cls.contains("Image") && ds.length < 200) out.add(ds.replace('\n', ' '))
            }
        }
        for (i in 0 until n.childCount) textsOf(n.getChild(i), out, d + 1)
    }

    /** Las listas que se pueden desplazar (el chat del directo es una de ellas). */
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

    /** Convierte los textos de un mensaje del chat en (nombre, comentario). */
    private fun rowOf(texts0: List<String>, ig: Boolean): Pair<String, String>? {
        val texts = texts0.map { it.trim() }.filter { it.isNotEmpty() && !it.matches(Regex("^[\\d.,kKmM+]{1,6}$")) && it.lowercase() !in uiWords }
        if (texts.isEmpty()) return null
        var u: String; var t: String
        if (texts.size >= 2) { u = texts[0]; t = texts.drop(1).joinToString(" ") }
        else {
            val s = texts[0]
            val m = Regex("^(.{1,30}?)\\s*[:：]\\s+(.+)$").find(s)
            if (m != null) { u = m.groupValues[1]; t = m.groupValues[2] }
            else if (ig) { val sp = s.indexOf(' '); if (sp <= 0) return null; u = s.substring(0, sp); t = s.substring(sp + 1) }
            else return null      // TikTok: sin «:» no se sabe dónde acaba el nombre
        }
        u = u.trim().trimEnd(':', '：').trim(); t = t.trim()
        if (u.isEmpty() || u.length > 40 || t.isEmpty() || t.length > 300) return null
        if (u.lowercase() in uiWords) return null
        if (badRe.containsMatchIn(t) && t.length < 70) return null
        if (ig) { val h = handleRe.find(u) ?: return null; u = "@" + h.groupValues[1] }
        else if (!(u.startsWith("@") && handleRe.matches(u))) u = u.removePrefix("@")
        return u to t
    }

    /**
     * Mensajes del chat: cada elemento de una lista es un mensaje (nombre + comentario).
     * Se usa la lista que más mensajes válidos da (la del chat), así no entran los textos sueltos de la pantalla.
     */
    private fun liveRows(root: AccessibilityNodeInfo): List<Pair<String, String>> {
        val ig = !isTikTok(root)
        var best: List<Pair<String, String>> = emptyList()
        for (l in lists(root)) {
            val rows = ArrayList<Pair<String, String>>()
            for (i in 0 until l.childCount) {
                val texts = ArrayList<String>(); textsOf(l.getChild(i), texts)
                rowOf(texts, ig)?.let { rows.add(it) }
            }
            if (rows.size > best.size) best = rows
        }
        return best
    }

    /** Para ajustar la app: el árbol de la pantalla con tipo, id y texto de cada elemento. */
    private fun treeDump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, d: Int) {
            if (n == null || d > 40 || sb.length > 30000) return
            if (n.isVisibleToUser) {
                val cls = (n.className?.toString() ?: "").substringAfterLast('.')
                val id = (n.viewIdResourceName ?: "").substringAfterLast('/')
                val t = n.text?.toString()?.replace('\n', ' ') ?: ""
                val ds = n.contentDescription?.toString()?.replace('\n', ' ') ?: ""
                sb.append("  ".repeat(d.coerceAtMost(20))).append(cls)
                if (id.isNotEmpty()) sb.append(" #").append(id)
                if (n.isScrollable) sb.append(" [lista]")
                if (t.isNotEmpty()) sb.append(" «").append(t.take(80)).append("»")
                if (ds.isNotEmpty()) sb.append(" {").append(ds.take(80)).append("}")
                sb.append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun liveRound() {
        val root = targetRoot()
        if (root == null) { say(if (started) "⚠️ Vuelve al directo en la app para seguir contando · 👥 ${livePeople.size}" else "🔴 Entra en el directo en la app…"); return }
        val rows = liveRows(root)
        LectorResult.lastDump = "[${root.packageName} directo]\n" + rows.joinToString("\n") { it.first + " → " + it.second } + "\n---\n" + treeDump(root)
        if (rows.isEmpty()) noTextRounds++ else noTextRounds = 0
        if (!started) {
            liveBase.clear(); rows.forEach { liveBase.add(it.first.lowercase() + "|" + it.second) }
            say(if (rows.isEmpty()) "🔴 Entra en el directo. Todavía no veo mensajes en el chat." else "🔴 Veo el chat ✅ Pulsa «Empezar a contar» cuando quieras.")
            return
        }
        for ((u, t) in rows) {
            val k = u.lowercase() + "|" + t
            if (k in liveBase || !liveSeen.add(k)) continue
            LectorResult.rows.add(u to t); livePeople.add(u.lowercase())
        }
        say(if (noTextRounds > 8) "⚠️ No veo el chat (¿ha terminado el directo?) · 👥 ${livePeople.size} personas"
            else "🔴 Contando… 👥 ${livePeople.size} personas · 💬 ${liveSeen.size} comentarios")
    }

    // ---------- botones ----------
    private fun onMain() {
        if (!started) {
            started = true
            mainBtn.text = if (liveMode) "⏹️ Parar y sortear" else "✅ Terminar"
            say(if (liveMode) "🔴 Contando… 👥 0 personas" else "Leyendo comentarios…")
            vibrate()
            return
        }
        finish(true)
    }

    private fun onClose() {
        if (started && !cancelArmed) {
            cancelArmed = true
            closeBtn.text = "¿Seguro? ✕"
            main.postDelayed({ cancelArmed = false; if (panel != null) closeBtn.text = "✕" }, 3000)
            return
        }
        finish(false)
    }

    private fun finish(use: Boolean) {
        val wasStarted = started
        stop()
        if (use && wasStarted) {
            LectorResult.ready = true
            LectorResult.live = liveMode
        } else LectorResult.clear()
        // Volver a Sorteos Star
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("fromLector", true))
        } catch (e: Exception) {}
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        started = false; cancelArmed = false
        panel?.let { try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) {} }
        panel = null
    }

    // ---------- panel flotante ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showPanel() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#EE7B2FF7"), Color.parseColor("#EEF72585"))).apply { cornerRadius = dp(18).toFloat() }
            elevation = dp(8).toFloat()
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        mainBtn = btn("▶️ Empezar", Color.WHITE, Color.parseColor("#7B2FF7")) { onMain() }
        closeBtn = btn("✕", Color.parseColor("#40FFFFFF"), Color.WHITE) { onClose() }
        row.addView(mainBtn, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) })
        row.addView(closeBtn, LinearLayout.LayoutParams(-2, dp(42)))
        p.addView(status); p.addView(row)

        val lp = WindowManager.LayoutParams(
            dp(300), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(36) }

        // Arrastrar el panel tocando el texto
        var sy = 0f; var oy = 0; var sx = 0f; var ox = 0
        status.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sy = e.rawY; oy = lp.y; sx = e.rawX; ox = lp.x; true }
                MotionEvent.ACTION_MOVE -> { lp.y = (oy + (e.rawY - sy)).toInt().coerceAtLeast(0); lp.x = (ox + (e.rawX - sx)).toInt(); try { wm.updateViewLayout(p, lp) } catch (x: Exception) {}; true }
                else -> true
            }
        }
        try { wm.addView(p, lp); panel = p } catch (e: Exception) { toast("No se pudo mostrar el panel") }
    }

    private fun btn(t: String, bg: Int, fg: Int, onTap: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(fg); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(bg) }
        setOnClickListener { onTap() }
    }

    private fun say(t: String) { if (panel != null) status.text = t }
    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }
    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        } catch (e: Exception) {}
    }
}
