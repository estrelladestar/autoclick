package com.example.autoclick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Ejecuta el guion de la pantalla "Comentarios automáticos".
 *
 * Instrucciones (una por línea):
 *   esperar 5          espera 5 segundos
 *   tocar Texto        toca el botón o texto que diga así
 *   tocar 500,1200     toca esa posición de la pantalla
 *   escribir lista     escribe el siguiente comentario de la lista
 *   escribir azar      escribe un comentario de la lista elegido al azar
 *   escribir menciones     escribe @amigos al azar de tu cuadro de menciones (cuántos: el selector 1 a 5)
 *   escribir menciones 3   igual, pero con 3 menciones (de 1 a 5)
 *   escribir copiados menciones 2   un comentario copiado al azar + 2 menciones (de 1 a 5)
 *   enlace 2           abre el enlace 2 de la app (sin número: abre el siguiente enlace guardado)
 *   imagen 1           busca en pantalla la imagen 1 de la lista de pasos y la pulsa
 *   escribir Hola      escribe ese texto fijo
 *   repetir 10         repite todo el guion 10 veces
 *   # nota             se ignora
 * Al final de cualquier línea, "| 30" añade 30 segundos de espera, y "| 30-60" añade un tiempo
 * al azar entre 30 y 60 segundos (también vale "esperar 5-10").
 * El guion se para solo, con vibración, si la pantalla muestra un aviso de bloqueo de Instagram.
 *
 * Límites: máximo 50 comentarios por tanda y al menos 10 segundos entre uno y el siguiente.
 */
object Macro {
    @Volatile
    var running = false

    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    private var svc: ClickService? = null

    private const val MAX_COMMENTS = 50
    private const val MIN_GAP_MS = 10_000L
    private const val MAX_TRIES = 25

    class Cmd(val op: String, val arg: String, val extraMs: Long, val extraMaxMs: Long = extraMs) {
        /** Espera extra de la línea; si es un rango («30-60»), un valor al azar dentro del rango. */
        fun extra(): Long =
            if (extraMaxMs > extraMs) extraMs + (Math.random() * (extraMaxMs - extraMs)).toLong() else extraMs
    }

    /** «30» → (30000, 30000); «30-60» → (30000, 60000). Devuelve milisegundos. */
    fun parseSecs(text: String): Pair<Long, Long> {
        val t = text.trim().replace(',', '.').replace('–', '-')
        val parts = t.split("-").map { it.trim() }
        val a = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val b = if (parts.size > 1) (parts[1].toDoubleOrNull() ?: a) else a
        val lo = (minOf(a, b) * 1000).toLong()
        val hi = (maxOf(a, b) * 1000).toLong()
        return Pair(lo, hi)
    }

    private val BLOCK_TEXTS = listOf(
        "Acción bloqueada", "Action Blocked", "Limitamos la frecuencia",
        "We limit how often", "Inténtalo de nuevo más tarde", "Try again later"
    )

    fun start(service: ClickService, script: String, comments: List<String>) {
        if (running) {
            toast(service, "Ya hay un guion en marcha")
            return
        }
        val cmds = ArrayList<Cmd>()
        var repeats = 1
        for (raw in script.lines()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            var extra = 0L
            var extraMax = 0L
            val bar = line.lastIndexOf('|')
            if (bar >= 0) {
                val r = parseSecs(line.substring(bar + 1))
                extra = r.first
                extraMax = r.second
                line = line.substring(0, bar).trim()
            }
            val sp = line.indexOf(' ')
            val op = (if (sp < 0) line else line.substring(0, sp)).lowercase()
            val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
            when (op) {
                "repetir" -> repeats = (arg.toIntOrNull() ?: 1).coerceIn(1, 1000)
                "esperar", "tocar", "escribir", "campo", "enlace", "imagen", "mantener" -> cmds.add(Cmd(op, arg, extra, extraMax))
                else -> {
                    toast(service, "No entiendo esta línea: $raw")
                    return
                }
            }
        }
        if (cmds.isEmpty()) {
            toast(service, "El guion está vacío")
            return
        }
        svc = service
        running = true
        token++
        service.showOverlay()
        service.setOverlayText("■")
        toast(service, "Guion iniciado. Toca ■ en el botón flotante para parar.")
        Runner(service, cmds, repeats, comments, token).step()
    }

    fun stop() {
        finish("Guion detenido", false)
    }

    private fun finish(msg: String, alert: Boolean = true) {
        if (!running) return
        running = false
        token++
        main.removeCallbacksAndMessages(null)
        val s = svc
        if (s != null) {
            s.setOverlayText("▶")
            toast(s, msg)
            if (alert) buzz(s)
        }
    }

    /** Vibra tres veces para avisar de que el guion se ha parado solo. */
    private fun buzz(s: ClickService) {
        try {
            val v = s.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
        } catch (e: Exception) {
        }
    }

    private fun toast(s: ClickService, m: String) {
        Toast.makeText(s, m, Toast.LENGTH_LONG).show()
    }

    class Runner(
        private val s: ClickService,
        private val cmds: List<Cmd>,
        private val repeats: Int,
        private val comments: List<String>,
        private val id: Int
    ) {
        private var i = 0
        private var loop = 0
        private var tries = 0
        /** Paso (vuelta y línea) en el que ya se pulsó la caja de comentarios; así se espera a que se abra antes de escribir. */
        private var openedAt = -1
        private var commentIdx = 0
        private var used = 0
        private var last = 0L
        private var linkTurn = 0
        private val usedCombos = HashSet<String>()
        private val pool = ArrayList<String>()

        private fun later(ms: Long) {
            main.postDelayed({ step() }, ms)
        }

        private fun advance(ms: Long) {
            tries = 0
            openedAt = -1
            i++
            later(maxOf(ms, 400L))
        }

        private fun retry(what: String) {
            tries++
            if (tries > MAX_TRIES) finish("No encontré $what. Guion detenido.") else later(800L)
        }

        fun step() {
            if (id != token || !running) return
            if (i >= cmds.size) {
                loop++
                if (loop >= repeats) {
                    finish("Guion terminado: $repeats vuelta(s)" + (if (used > 0) ", $used comentario(s) escritos" else ""))
                    return
                }
                i = 0
                later(500L)
                return
            }
            if (blocked()) {
                finish("Instagram muestra un aviso de bloqueo o límite. Guion detenido para proteger tu cuenta.")
                return
            }
            showProgress()
            val c = cmds[i]
            when (c.op) {
                "esperar" -> {
                    val r = parseSecs(c.arg)
                    val wait = r.first + (Math.random() * (r.second - r.first)).toLong()
                    advance(wait + c.extra())
                }
                "tocar" -> {
                    if (tapTarget(c.arg)) advance(c.extra()) else retry("«" + c.arg + "»")
                }
                "campo" -> {
                    val f = findField()
                    if (f == null) {
                        retry("la caja de texto")
                    } else {
                        f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        advance(c.extra())
                    }
                }
                "escribir" -> write(c)
                "enlace" -> openLink(c)
                "imagen" -> tapImage(c)
                "mantener" -> {
                    val p = parsePoint(c.arg)
                    if (p == null) {
                        finish("No entiendo la posición «${c.arg}» de «mantener». Usa por ejemplo mantener 90%,95%. Guion detenido.")
                        return
                    }
                    s.longPressAt(p.first, p.second)
                    advance(maxOf(c.extra(), 1500L))
                }
                else -> advance(0L)
            }
        }

        /** Muestra en el botón flotante el paso en el que va, la vuelta y los comentarios escritos. */
        private fun showProgress() {
            val c = cmds.getOrNull(i) ?: return
            val what = (c.op + " " + c.arg).trim().take(14)
            var t = "■ ${i + 1}/${cmds.size} $what"
            val extra = ArrayList<String>()
            if (repeats > 1) extra.add("vuelta ${loop + 1}/$repeats")
            if (used > 0) extra.add("$used/$MAX_COMMENTS")
            if (extra.isNotEmpty()) t += "\n" + extra.joinToString(" · ")
            s.setOverlayText(t)
        }

        private fun blocked(): Boolean {
            val root = s.rootInActiveWindow ?: return false
            for (t in BLOCK_TEXTS) {
                val found = root.findAccessibilityNodeInfosByText(t)
                if (found != null && found.isNotEmpty()) return true
            }
            return false
        }

        private fun openLink(c: Cmd) {
            val n = c.arg.trim().toIntOrNull()
            val ok = if (n != null) {
                s.openSavedLink(n - 1)
            } else {
                val marked = s.selectedLinkUrl()
                if (marked.isNotEmpty()) {
                    // El enlace marcado con el círculo en el Etiquetador
                    s.openUrl(marked)
                    true
                } else {
                    val all = s.savedLinks()
                    if (all.isEmpty()) false else {
                        val idx = all[linkTurn % all.size]
                        linkTurn++
                        s.openSavedLink(idx)
                    }
                }
            }
            if (!ok) {
                finish("No hay ningún enlace. Ponlo en «Enlaces del sorteo» del Etiquetador o en «Enlace 1» de la pantalla principal. Guion detenido.")
                return
            }
            advance(maxOf(c.extra(), 3000L))
        }

        private fun tapImage(c: Cmd) {
            val n = c.arg.trim().toIntOrNull() ?: 1
            val myTry = id
            s.locateSavedImage(n) { pos, missing ->
                if (myTry != token || !running) return@locateSavedImage
                when {
                    missing -> finish("No encuentro la imagen $n. Añádela en la app. Guion detenido.")
                    pos != null -> {
                        s.tapAt(pos.first, pos.second)
                        advance(c.extra())
                    }
                    else -> retry("la imagen $n en la pantalla")
                }
            }
        }

        private fun writeMentions(c: Cmd) {
            val prefs = s.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
            val friends = (prefs.getString("friends", "") ?: "").lines()
                .map { it.trim() }.filter { it.isNotEmpty() }
                .map { if (it.startsWith("@")) it else "@$it" }.distinct()
            if (friends.isEmpty()) {
                finish("No hay menciones. Pon tus amigos en el cuadro de menciones. Guion detenido.")
                return
            }
            val tokens = c.arg.trim().split(" ").filter { it.isNotEmpty() }
            val withCopied = tokens.firstOrNull()?.lowercase()?.startsWith("copiado") == true
            if (withCopied && comments.isEmpty()) {
                finish("No hay comentarios copiados. Pégalos en «TUS COMENTARIOS». Guion detenido.")
                return
            }
            val asked = tokens.lastOrNull()?.toIntOrNull()
            val n = (asked ?: prefs.getInt("mentionsN", 2)).coerceIn(1, 5).coerceAtMost(friends.size)
            if (used >= MAX_COMMENTS) {
                finish("Límite de $MAX_COMMENTS comentarios por tanda alcanzado")
                return
            }
            if (last != 0L) {
                val wait = last + MIN_GAP_MS - System.currentTimeMillis()
                if (wait > 0) {
                    later(wait)
                    return
                }
            }
            // Combinación al azar que no se haya usado ya en esta tanda
            var pick: List<String> = emptyList()
            var key = ""
            var found = false
            for (attempt in 0 until 200) {
                pick = friends.shuffled().take(n)
                key = pick.sorted().joinToString(",")
                if (key !in usedCombos) { found = true; break }
            }
            if (!found) {
                // Sin límite: si se gastaron todas las combinaciones, se vuelve a empezar
                usedCombos.clear()
                pick = friends.shuffled().take(n)
                key = pick.sorted().joinToString(",")
            }
            val f = findField()
            if (f == null) {
                retry("la caja de texto")
                return
            }
            // «escribir» ya abre la caja de comentarios por sí solo (no hace falta poner «campo»):
            // la pulsa, espera 1,5 s a que se abra y vuelve a este paso para escribir
            val here = loop * 100000 + i
            if (openedAt != here) {
                openedAt = here
                f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                later(1500L)
                return
            }
            var text = pick.joinToString(" ")
            val phrases = if (withCopied) {
                // Un comentario copiado al azar, sin repetir hasta haber usado todos los de la lista
                if (pool.isEmpty()) pool.addAll(comments)
                listOf(pool.removeAt((Math.random() * pool.size).toInt().coerceAtMost(pool.size - 1)))
            } else {
                (prefs.getString("phrases", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
            }
            val chance = if (withCopied) 100 else prefs.getInt("phraseChance", 100).coerceIn(0, 100)
            if (phrases.isNotEmpty() && Math.random() * 100 < chance) {
                val phrase = phrases[(Math.random() * phrases.size).toInt().coerceAtMost(phrases.size - 1)]
                val pos = prefs.getInt("phrasePos", 0)
                val before = if (pos == 2) Math.random() < 0.5 else pos == 0
                text = if (before) "$phrase $text" else "$text $phrase"
            }
            if (!putText(f, text)) {
                retry("dónde escribir (la caja no deja escribir)")
                return
            }
            usedCombos.add(key)
            used++
            last = System.currentTimeMillis()
            showProgress()
            advance(c.extra())
        }

        private fun write(c: Cmd) {
            val low = c.arg.lowercase()
            if (low.startsWith("menciones") || (low.startsWith("copiado") && low.contains("mencion"))) {
                writeMentions(c)
                return
            }
            val word = if (low.startsWith("copiado")) "azar" else low
            val isRandom = word == "azar"
            val isList = word == "lista" || isRandom
            // Primero abre la caja de comentarios (antes de elegir el comentario, para no gastar ninguno)
            val box = findField()
            if (box == null) {
                retry("la caja de texto")
                return
            }
            // «escribir» ya abre la caja de comentarios por sí solo (no hace falta poner «campo»):
            // la pulsa, espera 1,5 s a que se abra y vuelve a este paso para escribir
            val here = loop * 100000 + i
            if (openedAt != here) {
                openedAt = here
                box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                later(1500L)
                return
            }
            var text = c.arg
            if (isList) {
                if (comments.isEmpty() || (!isRandom && commentIdx >= comments.size)) {
                    finish("Se acabaron los comentarios de tu lista")
                    return
                }
                if (used >= MAX_COMMENTS) {
                    finish("Límite de $MAX_COMMENTS comentarios por tanda alcanzado")
                    return
                }
                if (last != 0L) {
                    val wait = last + MIN_GAP_MS - System.currentTimeMillis()
                    if (wait > 0) {
                        later(wait)
                        return
                    }
                }
                if (isRandom) {
                    // Al azar, sin repetir hasta haber usado todos los de la lista
                    if (pool.isEmpty()) pool.addAll(comments)
                    text = pool.removeAt((Math.random() * pool.size).toInt().coerceAtMost(pool.size - 1))
                } else {
                    text = comments[commentIdx]
                }
            }
            val f = findField()
            if (f == null) {
                retry("la caja de texto")
                return
            }
            if (!putText(f, text)) {
                retry("dónde escribir (la caja no deja escribir)")
                return
            }
            if (isList) {
                commentIdx++
                used++
                last = System.currentTimeMillis()
                showProgress()
            }
            advance(c.extra())
        }

        /** «500,1200» (píxeles) o «90%,95%» (porcentaje del ancho y del alto de la pantalla). Null si no es una posición. */
        private fun parsePoint(arg: String): Pair<Float, Float>? {
            val xy = arg.split(",")
            if (xy.size != 2) return null
            fun one(text: String, total: Int): Float? {
                val v = text.trim()
                return if (v.endsWith("%")) {
                    v.dropLast(1).trim().toFloatOrNull()?.let { it.coerceIn(0f, 100f) / 100f * total }
                } else {
                    v.toFloatOrNull()
                }
            }
            val size = s.screenSize()
            val x = one(xy[0], size.first) ?: return null
            val y = one(xy[1], size.second) ?: return null
            return Pair(x, y)
        }

        private fun tapTarget(arg: String): Boolean {
            val p = parsePoint(arg)
            if (p != null) {
                s.tapAt(p.first, p.second)
                return true
            }
            val root = s.rootInActiveWindow ?: return false
            val node = root.findAccessibilityNodeInfosByText(arg)
                ?.firstOrNull { it.isVisibleToUser } ?: return false
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val r = Rect()
            node.getBoundsInScreen(r)
            s.tapAt(r.exactCenterX(), r.exactCenterY())
            return true
        }

        /**
         * Escribe el texto en la caja y comprueba que de verdad se ha escrito.
         * Si Instagram no lo acepta, lo pega como desde el portapapeles; y si aun así no, prueba en las demás ventanas.
         */
        private fun putText(first: AccessibilityNodeInfo, text: String): Boolean {
            val want = text.trim()
            fun has(n: AccessibilityNodeInfo): Boolean {
                n.refresh()
                return (n.text?.toString() ?: "").contains(want)
            }
            fun tryOn(n: AccessibilityNodeInfo): Boolean {
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val b = Bundle()
                b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
                if (has(n)) return true
                try {
                    val cm = s.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("comentario", text))
                    n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                } catch (e: Exception) {
                }
                return has(n)
            }
            val f = findField() ?: first
            if (tryOn(f)) return true
            try {
                for (w in s.windows) {
                    val r = w.root ?: continue
                    val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val e = if (focused != null && focused.isEditable) focused else findEditable(r)
                    if (e != null && e != f && tryOn(e)) return true
                }
            } catch (e: Exception) {
            }
            return false
        }

        private fun findField(): AccessibilityNodeInfo? {
            val root = s.rootInActiveWindow ?: return null
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
            return findEditable(root)
        }

        private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isEditable && n.isVisibleToUser) return n
            for (k in 0 until n.childCount) {
                val f = findEditable(n.getChild(k))
                if (f != null) return f
            }
            return null
        }
    }
}
