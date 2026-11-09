package com.example.autoclick

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
 *   campo              selecciona la caja de texto
 *   escribir lista     escribe el siguiente comentario de la lista
 *   escribir Hola      escribe ese texto fijo
 *   repetir 10         repite todo el guion 10 veces
 *   # nota             se ignora
 * Al final de cualquier línea, "| 30" añade 30 segundos de espera.
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

    class Cmd(val op: String, val arg: String, val extraMs: Long)

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
            val bar = line.lastIndexOf('|')
            if (bar >= 0) {
                val secs = line.substring(bar + 1).trim().replace(',', '.').toDoubleOrNull() ?: 0.0
                extra = (secs * 1000).toLong()
                line = line.substring(0, bar).trim()
            }
            val sp = line.indexOf(' ')
            val op = (if (sp < 0) line else line.substring(0, sp)).lowercase()
            val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
            when (op) {
                "repetir" -> repeats = (arg.toIntOrNull() ?: 1).coerceIn(1, 1000)
                "esperar", "tocar", "escribir", "campo" -> cmds.add(Cmd(op, arg, extra))
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
        finish("Guion detenido")
    }

    private fun finish(msg: String) {
        if (!running) return
        running = false
        token++
        main.removeCallbacksAndMessages(null)
        val s = svc
        if (s != null) {
            s.setOverlayText("▶")
            toast(s, msg)
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
        private var commentIdx = 0
        private var used = 0
        private var last = 0L

        private fun later(ms: Long) {
            main.postDelayed({ step() }, ms)
        }

        private fun advance(ms: Long) {
            tries = 0
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
                    finish("Guion terminado")
                    return
                }
                i = 0
                later(500L)
                return
            }
            val c = cmds[i]
            when (c.op) {
                "esperar" -> {
                    val secs = c.arg.replace(',', '.').toDoubleOrNull() ?: 0.0
                    advance((secs * 1000).toLong() + c.extraMs)
                }
                "tocar" -> {
                    if (tapTarget(c.arg)) advance(c.extraMs) else retry("«" + c.arg + "»")
                }
                "campo" -> {
                    val f = findField()
                    if (f == null) {
                        retry("la caja de texto")
                    } else {
                        f.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        f.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        advance(c.extraMs)
                    }
                }
                "escribir" -> write(c)
                else -> advance(0L)
            }
        }

        private fun write(c: Cmd) {
            val isList = c.arg.lowercase() == "lista"
            var text = c.arg
            if (isList) {
                if (commentIdx >= comments.size) {
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
                text = comments[commentIdx]
            }
            val f = findField()
            if (f == null) {
                retry("la caja de texto")
                return
            }
            val b = Bundle()
            b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
            if (isList) {
                commentIdx++
                used++
                last = System.currentTimeMillis()
            }
            advance(c.extraMs)
        }

        private fun tapTarget(arg: String): Boolean {
            val xy = arg.split(",")
            if (xy.size == 2) {
                val x = xy[0].trim().toFloatOrNull()
                val y = xy[1].trim().toFloatOrNull()
                if (x != null && y != null) {
                    s.tapAt(x, y)
                    return true
                }
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
