package com.example.autoclick

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
 * Límite: al menos 10 segundos entre un comentario y el siguiente.
 */
object Macro {
    @Volatile
    var running = false

    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    private var svc: ClickService? = null

    private const val MIN_GAP_MS = 10_000L
    private const val MAX_TRIES = 25
    private const val INSTAGRAM = "com.instagram.android"
    private const val NEXT_ACCOUNT = "\u0001siguiente"

    class Cmd(val op: String, val arg: String, val extraMs: Long, val extraMaxMs: Long = extraMs, val label: String = "") {
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
        var dailyLimit = 0
        val accounts = ArrayList<String>()
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
                "limite", "límite" -> dailyLimit = arg.filter { it.isDigit() }.toIntOrNull() ?: 0
                "descanso" -> {
                    // «descanso 10-20» = pausa al azar entre 10 y 20 minutos (sin número: 10-20)
                    val r = parseSecs(if (arg.isEmpty()) "10-20" else arg)
                    cmds.add(Cmd("esperar", "${r.first * 60 / 1000}-${r.second * 60 / 1000}", 0L, 0L, "descanso"))
                }
                "hora" -> {
                    if (!Regex("\\d{1,2}[:.]\\d{2}").matches(arg)) {
                        toast(service, "Pon la hora así: «hora 21:30»")
                        return
                    }
                    cmds.add(Cmd("hora", arg.replace('.', ':'), 0L, 0L))
                }
                "esperar", "tocar", "escribir", "campo", "enlace", "imagen", "mantener", "mirar" -> cmds.add(Cmd(op, arg, extra, extraMax))
                "atras", "atrás" -> cmds.add(Cmd("atras", arg, extra, extraMax))
                "clic", "click", "pulsar" -> cmds.add(Cmd("tocar", arg, extra, extraMax))
                "cuenta" -> {
                    // «cuenta nombre»: cierra teclado y comentarios, abre la lista de cuentas
                    // (pulsación larga en el perfil, abajo a la derecha) y pulsa esa cuenta
                    if (arg.isEmpty()) {
                        toast(service, "Pon el nombre de la cuenta: por ejemplo «cuenta del_mar190»")
                        return
                    }
                    cmds.add(Cmd("irperfil", "", 800L))
                    cmds.add(Cmd("perfil", "", 3000L))
                    val name = if (arg.lowercase() == "siguiente") NEXT_ACCOUNT else arg.removePrefix("@")
                    cmds.add(Cmd("tocarcuenta", name, maxOf(extra, 5000L), maxOf(extraMax, 5000L)))
                }
                "cuentas" -> {
                    // «cuentas a b c»: las cuentas por las que va pasando «cuenta siguiente», en orden
                    accounts.clear()
                    accounts.addAll(arg.split(Regex("[\\s,;]+")).map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() })
                }
                else -> {
                    toast(service, "No entiendo esta línea: $raw")
                    return
                }
            }
        }
        if (cmds.any { it.arg == NEXT_ACCOUNT } && accounts.size == 1) {
            toast(service, "En «cuentas» pon al menos 2 cuentas, o quita esa línea para que use todas las de Instagram")
            return
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
        Runner(service, cmds, repeats, comments, token, accounts.toList(), dailyLimit).step()
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

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Registro: {"2026-11-10": {"cory_gm0": 12, ...}, ...} (se guardan los últimos 30 días). */
    fun loadLog(ctx: android.content.Context): org.json.JSONObject = try {
        org.json.JSONObject(ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).getString("registro", "{}") ?: "{}")
    } catch (e: Exception) {
        org.json.JSONObject()
    }

    fun todayCount(ctx: android.content.Context, account: String): Int =
        loadLog(ctx).optJSONObject(today())?.optInt(account.lowercase(), 0) ?: 0

    fun addCount(ctx: android.content.Context, account: String) {
        val log = loadLog(ctx)
        val d = today()
        val day = log.optJSONObject(d) ?: org.json.JSONObject().also { log.put(d, it) }
        val k = account.lowercase()
        day.put(k, day.optInt(k, 0) + 1)
        val days = log.keys().asSequence().toList().sorted()
        if (days.size > 30) days.take(days.size - 30).forEach { log.remove(it) }
        ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit().putString("registro", log.toString()).apply()
    }

    private fun toast(s: ClickService, m: String) {
        Toast.makeText(s, m, Toast.LENGTH_LONG).show()
    }

    class Runner(
        private val s: ClickService,
        private val cmds: List<Cmd>,
        private val repeats: Int,
        private val comments: List<String>,
        private val id: Int,
        private val accounts: List<String> = emptyList(),
        private val dailyLimit: Int = 0
    ) {
        /** Cuenta con la que se está comentando ahora (null = aún no se sabe, en modo «todas las cuentas»). */
        private var current: String? = accounts.firstOrNull()
        /** Comentarios hechos antes de saber el nombre de la cuenta actual. */
        private var pending = 0
        /** Cuentas ya usadas en esta ronda (modo «todas las cuentas de Instagram»). */
        private val visited = LinkedHashSet<String>()
        private var scrolls = 0
        /** Veces seguidas que se ha saltado una cuenta por el límite diario. */
        private var skipsInARow = 0
        private var i = 0
        private var loop = 0
        private var tries = 0
        /** Para «mirar»: los deslizamientos que quedan por hacer (true = bajar por los comentarios). */
        private var swipes: ArrayDeque<Boolean>? = null
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
            swipes = null
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
                    var wait = r.first + (Math.random() * (r.second - r.first)).toLong()
                    if (c.label == "descanso") wait *= 60
                    advance(wait + c.extra())
                }
                "hora" -> {
                    // Espera hasta esa hora (hoy, o mañana si ya ha pasado)
                    val (hh, mm) = c.arg.split(":").map { it.trim().toInt() }
                    val now = java.util.Calendar.getInstance()
                    val at = (now.clone() as java.util.Calendar).apply {
                        set(java.util.Calendar.HOUR_OF_DAY, hh.coerceIn(0, 23))
                        set(java.util.Calendar.MINUTE, mm.coerceIn(0, 59))
                        set(java.util.Calendar.SECOND, 0)
                        if (!after(now)) add(java.util.Calendar.DAY_OF_MONTH, 1)
                    }
                    toast(s, "El guion seguirá a las ${c.arg}. Deja el móvil encendido y sin bloquear.")
                    advance(at.timeInMillis - now.timeInMillis)
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
                "atras" -> {
                    // Como el botón «atrás» del móvil: cierra el teclado, los comentarios…
                    s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    advance(maxOf(c.extra(), 1000L))
                }
                "escribir" -> write(c)
                "enlace" -> openLink(c)
                "imagen" -> tapImage(c)
                "tocarcuenta" -> {
                    // En la vuelta 1 se comenta con la primera cuenta de «cuentas» y se pasa a la segunda, etc.
                    if (c.arg == NEXT_ACCOUNT && accounts.isEmpty()) {
                        autoAccount(c)
                    } else {
                        val name = if (c.arg == NEXT_ACCOUNT) accounts[(loop + 1) % accounts.size] else c.arg
                        tapAccount(c, name)
                    }
                }
                "mirar" -> look(c)
                "irperfil" -> {
                    // Vuelve atrás hasta que se vea la barra de abajo de Instagram con tu icono de perfil.
                    // Si al volver atrás se sale de Instagram (pasa cuando el enlace lo abrió desde fuera), lo vuelve a abrir.
                    if (profileIcon() != null || tries >= 8) {
                        advance(c.extra())
                        return
                    }
                    tries++
                    val pkg = s.rootInActiveWindow?.packageName?.toString()
                    if (pkg != null && pkg != INSTAGRAM) {
                        val i = s.packageManager.getLaunchIntentForPackage(INSTAGRAM)
                        if (i != null) {
                            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { s.startActivity(i) } catch (e: Exception) {}
                        }
                        later(3500L)
                    } else {
                        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        later(1500L)
                    }
                }
                "perfil" -> {
                    // Pulsación larga en el icono de perfil de abajo (lo busca por su nombre; si no, abajo a la derecha)
                    val p = profileIcon() ?: run {
                        val size = s.screenSize()
                        Pair(size.first * 0.90f, size.second * 0.92f)
                    }
                    s.longPressAt(p.first, p.second)
                    advance(maxOf(c.extra(), 1500L))
                }
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
            val what = when {
                c.label.isNotEmpty() -> c.label + " " + c.arg + " min"
                c.op == "tocarcuenta" || c.op == "irperfil" || c.op == "perfil" -> "cambiando cuenta"
                else -> (c.op + " " + c.arg).trim().take(14)
            }
            var t = "■ ${i + 1}/${cmds.size} $what"
            val extra = ArrayList<String>()
            if (repeats > 1) extra.add("vuelta ${loop + 1}/$repeats")
            if (used > 0) extra.add("$used escritos")
            current?.let { extra.add(it) }
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
                if (tries == 0) toast(s, "Intento escribir: $text")
                retry("dónde escribir: Instagram no deja escribir en la caja")
                return
            }
            toast(s, "Escrito: $text")
            usedCombos.add(key)
            countComment()
            used++
            last = System.currentTimeMillis()
            showProgress()
            advance(c.extra())
        }

        /** Si la cuenta actual ya llegó al límite de hoy, salta al cambio de cuenta. Devuelve true si ha saltado. */
        private fun overLimit(): Boolean {
            val who = current ?: return false
            if (dailyLimit <= 0 || todayCount(s, who) < dailyLimit) return false
            val j = (i + 1 until cmds.size).firstOrNull { cmds[it].op == "irperfil" }
            skipsInARow++
            val total = if (accounts.isNotEmpty()) accounts.size else maxOf(visited.size, 2)
            if (j == null || skipsInARow > total) {
                finish("Todas las cuentas han hecho ya $dailyLimit comentarios hoy. Guion detenido.")
                return true
            }
            toast(s, "$who ya hizo $dailyLimit comentarios hoy: paso a la siguiente cuenta")
            tries = 0
            swipes = null
            i = j
            later(600L)
            return true
        }

        private fun countComment() {
            skipsInARow = 0
            val who = current
            if (who == null) pending++ else addCount(s, who)
        }

        /** Ya estamos en la cuenta [name]. */
        private fun switchedTo(name: String) {
            current = name
            used = 0
        }

        /**
         * «cuenta siguiente» sin línea «cuentas»: lee los nombres de la lista de cuentas de Instagram
         * y pulsa el primero que no se haya usado en esta ronda. Cuando se han usado todas, empieza otra ronda.
         */
        private fun autoAccount(c: Cmd) {
            val list = accountListNames()
            if (list == null) {
                // La lista aún no está abierta
                if (tries == 10) {
                    val p = profileIcon() ?: s.screenSize().let { Pair(it.first * 0.90f, it.second * 0.92f) }
                    s.longPressAt(p.first, p.second)
                }
                retry("la lista de cuentas de Instagram")
                return
            }
            if (current == null) {
                // La primera de la lista es la cuenta en la que estamos
                list.firstOrNull()?.first?.let { first ->
                    current = first
                    if (pending > 0) repeat(pending) { addCount(s, first) }
                    pending = 0
                }
            }
            current?.let { visited.add(it) }
            var target = list.map { it.first }.firstOrNull { it !in visited }
            if (target == null && scrolls < 3) {
                // Puede que haya más cuentas más abajo
                scrolls++
                scrollList(true)
                later(1200L)
                return
            }
            if (target == null) {
                // Ronda terminada: se vuelve a empezar por las demás
                visited.clear()
                current?.let { visited.add(it) }
                if (scrolls > 0) {
                    scrolls = 0
                    scrollList(false); scrollList(false); scrollList(false)
                    later(1200L)
                    return
                }
                target = list.map { it.first }.firstOrNull { it !in visited }
            }
            if (target == null) {
                finish("Solo veo una cuenta en Instagram. Añade otra cuenta en Instagram o pon la línea «cuentas».")
                return
            }
            scrolls = 0
            val node = list.first { it.first == target }.second
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n == null || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                s.tapAt(r.exactCenterX(), r.exactCenterY())
            }
            visited.add(target)
            toast(s, "Cambio a la cuenta $target")
            switchedTo(target)
            advance(c.extra())
        }

        /**
         * Si la lista de cuentas de Instagram está abierta, los nombres que se ven (de arriba abajo) con su texto;
         * si no está abierta, null.
         */
        private fun accountListNames(): List<Pair<String, AccessibilityNodeInfo>>? {
            val marks = listOf("usar otro perfil", "crear cuenta nueva", "añadir cuenta", "agregar cuenta",
                "use another profile", "create new account", "add account", "iniciar sesión en una cuenta existente")
            val nameRx = Regex("^[A-Za-z0-9._]{2,30}$")
            val skip = setOf("instagram", "meta", "facebook", "threads")
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                val texts = ArrayList<Pair<String, AccessibilityNodeInfo>>()
                var isList = false
                fun walk(n: AccessibilityNodeInfo?) {
                    if (n == null) return
                    val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""
                    if (t.isNotEmpty()) {
                        val low = t.lowercase()
                        if (marks.any { low.contains(it) }) isList = true
                        if (n.isVisibleToUser && nameRx.matches(t) && t.any { it.isLetter() } && low !in skip) {
                            texts.add(Pair(t, n))
                        }
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k))
                }
                walk(root)
                if (!isList) continue
                val r = Rect()
                return texts.distinctBy { it.first.lowercase() }
                    .sortedBy { it.second.getBoundsInScreen(r); r.top }
            }
            return null
        }

        private fun write(c: Cmd) {
            if (overLimit()) return
            val low = c.arg.lowercase()
            if (low.startsWith("menciones") || (low.startsWith("copiado") && low.contains("mencion"))) {
                writeMentions(c)
                return
            }
            val word = if (low.startsWith("copiado")) "azar" else low
            val isRandom = word == "azar"
            val isList = word == "lista" || isRandom
            // Comprueba que hay una caja donde escribir antes de elegir el comentario, para no gastar ninguno
            if (findField() == null) {
                retry("la caja de texto")
                return
            }
            var text = c.arg
            if (isList) {
                if (comments.isEmpty() || (!isRandom && commentIdx >= comments.size)) {
                    finish("Se acabaron los comentarios de tu lista")
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
                if (tries == 0) toast(s, "Intento escribir: $text")
                retry("dónde escribir: Instagram no deja escribir en la caja")
                return
            }
            toast(s, "Escrito: $text")
            countComment()
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
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val r = w.root ?: continue
                    val focused = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    val e = if (okField(focused)) focused else findEditable(r)
                    if (e != null && e != f && tryOn(e)) return true
                }
            } catch (e: Exception) {
            }
            return false
        }

        /**
         * «mirar»: baja por los comentarios unas cuantas veces y vuelve a subir, con pausas al azar, como una persona.
         * «mirar 3» = 3 deslizamientos hacia abajo; «mirar azar» = solo la mitad de las veces.
         */
        private fun look(c: Cmd) {
            if (swipes == null) {
                val a = c.arg.trim().lowercase()
                if (a.startsWith("azar") && Math.random() < 0.5) {
                    advance(c.extra())
                    return
                }
                val n = (a.filter { it.isDigit() }.toIntOrNull() ?: (2 + (Math.random() * 3).toInt())).coerceIn(1, 10)
                val plan = ArrayDeque<Boolean>()
                repeat(n) { plan.add(true) }
                repeat(n) { plan.add(false) }
                swipes = plan
                // Con el teclado abierto se cierra primero, para no deslizar sobre el teclado
                val keyboard = try {
                    s.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                } catch (e: Exception) { false }
                if (keyboard) {
                    s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    later(900L)
                    return
                }
            }
            val down = swipes?.removeFirstOrNull()
            if (down == null) {
                advance(c.extra())
                return
            }
            val (w, h) = s.screenSize()
            val x = w * (0.42f + (Math.random() * 0.16).toFloat())
            val ms = 300L + (Math.random() * 300).toLong()
            if (down) {
                // Dedo hacia arriba: se ven comentarios de más abajo
                s.swipe(x, h * 0.66f, x, h * (0.34f + (Math.random() * 0.06).toFloat()), ms)
            } else {
                // Dedo hacia abajo (un poco menos, para no cerrar los comentarios)
                s.swipe(x, h * 0.34f, x, h * (0.56f + (Math.random() * 0.04).toFloat()), ms)
            }
            later(900L + (Math.random() * 1600).toLong())
        }

        /** Las ventanas de la pantalla, de la de más arriba (por ejemplo la lista de cuentas) a la de más abajo. */
        private fun windowRoots(): List<AccessibilityNodeInfo> {
            val out = ArrayList<AccessibilityNodeInfo>()
            try {
                for (w in s.windows.sortedByDescending { it.layer }) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == s.packageName) continue
                    out.add(r)
                }
            } catch (e: Exception) {
            }
            if (out.isEmpty()) s.rootInActiveWindow?.let { out.add(it) }
            return out
        }

        /** Busca el nombre exacto de la cuenta (no otro que lo contenga) en todas las ventanas. */
        private fun findAccountNode(name: String): AccessibilityNodeInfo? {
            val low = name.lowercase()
            val exact = Regex("(^|[^a-z0-9._])" + Regex.escape(low) + "($|[^a-z0-9._])")
            for (root in windowRoots()) {
                val found = root.findAccessibilityNodeInfosByText(name) ?: continue
                for (n in found) {
                    if (!n.isVisibleToUser) continue
                    val t = ((n.text ?: n.contentDescription)?.toString() ?: "").lowercase()
                    if (exact.containsMatchIn(t)) return n
                }
            }
            return null
        }

        /** Pulsa la cuenta en la lista de cuentas: la busca por su nombre; si no, leyendo la pantalla; si no, baja por la lista. */
        private fun tapAccount(c: Cmd, name: String) {
            val node = findAccountNode(name)
            if (node != null) {
                var n: AccessibilityNodeInfo? = node
                while (n != null && !n.isClickable) n = n.parent
                if (n == null || !n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    s.tapAt(r.exactCenterX(), r.exactCenterY())
                }
                switchedTo(name)
                advance(c.extra())
                return
            }
            val myTry = id
            s.locateText(name) { pos ->
                if (myTry != token || !running) return@locateText
                if (pos != null) {
                    s.tapAt(pos.first, pos.second)
                    switchedTo(name)
                    advance(c.extra())
                    return@locateText
                }
                // Si a la mitad no ha salido, a lo mejor no se abrió la lista: vuelve a mantener pulsado el perfil
                if (tries == 10) {
                    val p = profileIcon() ?: s.screenSize().let { Pair(it.first * 0.90f, it.second * 0.92f) }
                    s.longPressAt(p.first, p.second)
                } else {
                    // Con muchas cuentas la lista no cabe: baja y luego sube para buscarla
                    scrollList(tries % 10 < 5)
                }
                retry("la cuenta «$name» en la lista de cuentas")
            }
        }

        /** Desplaza la lista de la ventana de más arriba hacia abajo (o hacia arriba). */
        private fun scrollList(down: Boolean) {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.isScrollable && n.isVisibleToUser) return n
                for (k in 0 until n.childCount) find(n.getChild(k))?.let { return it }
                return null
            }
            val root = windowRoots().firstOrNull() ?: return
            find(root)?.performAction(
                if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            )
        }

        /**
         * Centro del icono «Perfil» de la pestaña de abajo a la derecha de Instagram, o null si no se ve
         * (por ejemplo con los comentarios abiertos). Tiene que llamarse exactamente «Perfil» (no «foto del perfil»)
         * y estar abajo del todo y a la derecha.
         */
        private fun profileIcon(): Pair<Float, Float>? {
            val (w, h) = s.screenSize()
            var best: Rect? = null
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                for (word in listOf("Perfil", "Profile")) {
                    val found = root.findAccessibilityNodeInfosByText(word) ?: continue
                    for (n in found) {
                        if (!n.isVisibleToUser) continue
                        val label = ((n.contentDescription ?: n.text)?.toString() ?: "").trim()
                        val low = label.lowercase()
                        if (!low.startsWith(word.lowercase()) || low.contains("foto") || low.contains("photo")) continue
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.width() <= 0 || r.height() <= 0) continue
                        if (r.centerY() < h * 0.80f || r.centerX() < w * 0.60f) continue
                        if (best == null || r.centerY() > best!!.centerY()) best = r
                    }
                }
                if (best != null) break
            }
            val b = best ?: return null
            return Pair(b.exactCenterX(), b.exactCenterY())
        }

        /** Una caja donde se puede escribir que no sea del teclado ni de esta misma app. */
        private fun okField(n: AccessibilityNodeInfo?): Boolean {
            if (n == null || !n.isEditable) return false
            if (n.packageName?.toString() == s.packageName) return false
            return try { n.window?.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD } catch (e: Exception) { true }
        }

        /**
         * Busca la caja de comentarios: primero la que tiene el cursor en cualquier ventana
         * (en Instagram la caja de comentarios a veces va en una ventana aparte), luego cualquier caja visible.
         */
        private fun findField(): AccessibilityNodeInfo? {
            try {
                val f = s.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (okField(f)) return f
            } catch (e: Exception) {
            }
            val root = s.rootInActiveWindow
            if (root != null) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (okField(focused)) return focused
                findEditable(root)?.let { return it }
            }
            try {
                for (w in s.windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    findEditable(w.root)?.let { return it }
                }
            } catch (e: Exception) {
            }
            return null
        }

        private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isVisibleToUser && okField(n)) return n
            for (k in 0 until n.childCount) {
                val f = findEditable(n.getChild(k))
                if (f != null) return f
            }
            return null
        }
    }
}
