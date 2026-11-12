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
    /** Textos que solo salen en la lista de cuentas de Instagram (para saber que está abierta). */
    private val LIST_MARKS = listOf("usar otro perfil", "crear cuenta nueva", "añadir cuenta", "agregar cuenta",
        "centro de cuentas", "cambiar de cuenta", "iniciar sesión en una cuenta existente",
        "use another profile", "create new account", "add account", "add instagram account", "accounts center",
        "switch account", "log into an existing account")

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

    // ---------- Informe de lo que va haciendo el guion (para poder encontrar fallos) ----------
    private val traceLines = ArrayDeque<String>()
    private var traceCtx: Context? = null

    /** Apunta una línea en el informe del guion (se guardan las últimas 400). */
    fun trace(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(traceLines) {
            traceLines.addLast("$t $msg")
            while (traceLines.size > 400) traceLines.removeFirst()
        }
    }

    /**
     * Apunta al momento (en disco) cuándo arranca, se para o carga algo el servicio de accesibilidad.
     * Así, si la app se cierra de golpe (por ejemplo al cargar una librería), se sabe qué estaba haciendo.
     */
    fun mark(ctx: Context, msg: String) {
        trace(msg)
        try {
            val p = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val t = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val old = (p.getString("servicio_eventos", "") ?: "").lines().filter { it.isNotBlank() }
            p.edit().putString("servicio_eventos", (old + "$t $msg").takeLast(15).joinToString("\n")).commit()
        } catch (e: Exception) {
        }
    }

    /** Los últimos arranques y paradas del servicio (lo que apuntó mark). */
    fun marks(ctx: Context): String =
        ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("servicio_eventos", "") ?: ""

    /** Lo importante del informe (fallo de la app y arranques del servicio), que va siempre arriba. */
    fun reportHead(ctx: Context): String {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val crash = p.getString("ultimo_fallo", "") ?: ""
        val ev = marks(ctx)
        val sb = StringBuilder()
        if (crash.isNotEmpty()) sb.append("⚠ ÚLTIMO FALLO DE LA APP:\n").append(crash).append("\n\n")
        sb.append("SERVICIO (arranques y paradas):\n")
            .append(if (ev.isBlank()) "nunca ha arrancado en este móvil" else ev).append("\n\n")
        return sb.toString()
    }

    /** El informe del último guion (o del que está en marcha), para copiarlo y enviarlo. */
    fun traceText(ctx: Context): String {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val live = synchronized(traceLines) { traceLines.joinToString("\n") }
        val body = if (live.isNotEmpty()) live else (p.getString("informe", "") ?: "")
        return reportHead(ctx) + body
    }

    /** Guarda el motivo si la app (o su servicio) se cierra por un fallo, para verlo en el informe. */
    fun installCrashLogger(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(app, prev))
    }

    private class CrashLogger(val app: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val when_ = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
                val ver = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (x: Exception) { "?" }
                app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                    .putString("ultimo_fallo", "$when_ (versión $ver)\n" + sw.toString().take(3000)).commit()
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun saveTrace() {
        val c = traceCtx ?: return
        val txt = synchronized(traceLines) { traceLines.joinToString("\n") }
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("informe", txt).apply()
    }

    private val BLOCK_TEXTS = listOf(
        "Acción bloqueada", "Action Blocked", "Limitamos la frecuencia",
        "We limit how often", "Inténtalo de nuevo más tarde", "Try again later"
    )

    /** Avisos (en rojo) de Instagram cuando un comentario no se publica. En minúsculas. */
    private val NOT_POSTED_TEXTS = listOf(
        "no se ha podido publicar", "no se pudo publicar", "no se ha publicado", "no se publicó",
        "toca para reintentar", "no se puede comentar", "no se le puede", "no puedes comentar",
        "no se puede mencionar", "no puedes mencionar", "no permite", "no se ha podido compartir",
        "couldn't post", "could not post", "tap to retry", "can't comment", "can't be mentioned",
        "can't mention", "doesn't allow", "does not allow", "not posted"
    )
    /** De esos, los que dicen que es por una persona mencionada. */
    private val MENTION_ERROR_HINTS = listOf("mencion", "mention", "no permite", "no se le puede", "allow")

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
                    cmds.add(Cmd("esperar", "${r.first / 1000.0}-${r.second / 1000.0}", 0L, 0L, "descanso"))
                }
                "hora" -> {
                    if (!Regex("\\d{1,2}[:.]\\d{2}").matches(arg)) {
                        toast(service, "Pon la hora así: «hora 21:30»")
                        return
                    }
                    cmds.add(Cmd("hora", arg.replace('.', ':'), 0L, 0L))
                }
                "esperar", "tocar", "escribir", "campo", "enlace", "imagen", "mantener", "mirar", "inicio", "historias" -> cmds.add(Cmd(op, arg, extra, extraMax))
                "atras", "atrás" -> cmds.add(Cmd("atras", arg, extra, extraMax))
                "clic", "click", "pulsar" -> cmds.add(Cmd("tocar", arg, extra, extraMax))
                "cuenta" -> {
                    // «cuenta nombre»: cierra teclado y comentarios, abre la lista de cuentas
                    // (pulsación larga en el perfil, abajo a la derecha) y pulsa esa cuenta
                    if (arg.isEmpty()) {
                        toast(service, "Pon el nombre de la cuenta: por ejemplo «cuenta del_mar190»")
                        return
                    }
                    if (arg.lowercase() == "doble") {
                        // «cuenta doble»: doble toque en la foto de perfil de abajo a la derecha.
                        // Ojo: Instagram así solo salta entre las dos últimas cuentas usadas.
                        cmds.add(Cmd("irperfil", "", 800L))
                        cmds.add(Cmd("dobleperfil", "", maxOf(extra, 4000L), maxOf(extraMax, 4000L)))
                        continue
                    }
                    cmds.add(Cmd("irperfil", "", 800L))
                    cmds.add(Cmd("abrirlista", "", 0L))
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
        traceCtx = service.applicationContext
        synchronized(traceLines) { traceLines.clear() }
        trace("INICIO: ${cmds.size} pasos, repetir $repeats, cuentas=${if (accounts.isEmpty()) "todas las de Instagram" else accounts.joinToString(",")}, límite=$dailyLimit")
        val ver = try { service.packageManager.getPackageInfo(service.packageName, 0).versionName } catch (e: Exception) { "?" }
        trace("Star App versión $ver · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · pantalla ${service.screenSize().first}x${service.screenSize().second}")
        running = true
        token++
        service.showOverlay()
        service.setOverlayText("■")
        toast(service, "Guion iniciado. Toca ■ en el botón flotante para parar.")
        val r = Runner(service, cmds, repeats, comments, token, accounts.toList(), dailyLimit)
        val myToken = token
        val dog = object : Runnable {
            override fun run() {
                if (!running || token != myToken) return
                r.watchdog()
                main.postDelayed(this, 15_000L)
            }
        }
        main.postDelayed(dog, 15_000L)
        r.step()
    }

    fun stop() {
        finish("Guion detenido", false)
    }

    private fun finish(msg: String, alert: Boolean = true) {
        if (!running) return
        trace("FIN: $msg")
        saveTrace()
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

    /** Menciones hechas: {"2026-11-11": {"cuentas": {"cory_gm0": 10}, "personas": {"@ana": 3}}} (últimos 30 días). */
    fun loadMentions(ctx: android.content.Context): org.json.JSONObject = try {
        org.json.JSONObject(ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).getString("registro_menciones", "{}") ?: "{}")
    } catch (e: Exception) {
        org.json.JSONObject()
    }

    fun addMentions(ctx: android.content.Context, account: String?, handles: List<String>, extraForAccount: Int = 0) {
        if (handles.isEmpty() && extraForAccount == 0) return
        val log = loadMentions(ctx)
        val d = today()
        val day = log.optJSONObject(d) ?: org.json.JSONObject().also { log.put(d, it) }
        val accts = day.optJSONObject("cuentas") ?: org.json.JSONObject().also { day.put("cuentas", it) }
        val people = day.optJSONObject("personas") ?: org.json.JSONObject().also { day.put("personas", it) }
        if (account != null) {
            val k = account.lowercase()
            accts.put(k, accts.optInt(k, 0) + handles.size + extraForAccount)
        } else {
            accts.put("(sin saber la cuenta)", accts.optInt("(sin saber la cuenta)", 0) + handles.size)
        }
        for (h in handles) {
            val k = h.lowercase()
            people.put(k, people.optInt(k, 0) + 1)
        }
        val days = log.keys().asSequence().toList().sorted()
        if (days.size > 30) days.take(days.size - 30).forEach { log.remove(it) }
        ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit().putString("registro_menciones", log.toString()).apply()
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
        /** Menciones escritas en este guion (todas las cuentas). */
        private var mentionsRun = 0
        private var i = 0
        private var loop = 0
        private var tries = 0
        /** Para «mirar»: los deslizamientos que quedan por hacer (true = bajar por los comentarios). */
        private var swipes: ArrayDeque<Boolean>? = null
        /** Para «inicio»: 0 = empezar, 1 = volviendo a la pantalla principal, 2 = mirando publicaciones. */
        private var feedPhase = 0
        /** Para esperas largas: cuándo acaba (0 = no hay espera en marcha). */
        private var waitEnd = 0L
        /** Para «abrirlista»: en qué parte va (0 = mantener pulsado, 2 = por el perfil, …). */
        private var listPhase = 0
        /** Veces que se ha vuelto a intentar abrir la lista de cuentas desde el paso de pulsar la cuenta. */
        private var reopens = 0
        /** Veces que se ha esperado un poco más a que se abra la lista de cuentas. */
        private var listWaits = 0
        /** Para «historias»: en qué parte va y cuántas quedan por ver. */
        private var storyPhase = 0
        private var storiesLeft = 0
        /** Cuenta que se acaba de tocar en la lista y dónde (para comprobar que de verdad se cambió). */
        private var pendingSwitch: Triple<String, Float, Float>? = null
        private var switchChecks = 0
        /** Último paso apuntado en el informe (para no repetirlo en cada reintento). */
        private var tracedAt = -1
        /** Cambia en cada llamada a step(): las respuestas que llegan tarde de un intento anterior se ignoran. */
        private var stepGen = 0
        /** Cuándo debería volver a moverse el guion; si pasa mucho más, la alarma de atasco lo reintenta. */
        @Volatile var nextDue = System.currentTimeMillis()
        /** Paso (vuelta y línea) en el que ya se pulsó la caja de comentarios; así se espera a que se abra antes de escribir. */
        private var openedAt = -1
        private var commentIdx = 0
        private var used = 0
        private var last = 0L
        private var linkTurn = 0
        private val usedCombos = HashSet<String>()
        /** Menciones del último comentario escrito: al tocar «publicar» se comprueba que ha salido bien. */
        private var checkMention: List<String>? = null
        private val pool = ArrayList<String>()

        private fun later(ms: Long) {
            nextDue = System.currentTimeMillis() + ms
            main.postDelayed({ step() }, ms)
        }

        /** Alarma de atasco: el paso lleva más de un minuto sin responder (por ejemplo, una captura que no llegó). */
        fun watchdog() {
            if (id != token || !running) return
            val late = System.currentTimeMillis() - nextDue
            if (late > 60_000L) {
                val c = cmds.getOrNull(i)
                trace("⚠ atasco: el paso ${i + 1} (${c?.op}) lleva ${late / 1000} s sin responder; lo vuelvo a intentar")
                retry("respuesta en el paso ${i + 1}")
            }
        }

        private fun advance(ms: Long) {
            tries = 0
            swipes = null
            feedPhase = 0
            waitEnd = 0L
            listPhase = 0
            listWaits = 0
            pendingSwitch = null
            switchChecks = 0
            storyPhase = 0
            storiesLeft = 0
            openedAt = -1
            i++
            later(maxOf(ms, 400L))
        }

        private fun retry(what: String) {
            trace("  reintento ${tries + 1}/$MAX_TRIES: no encuentro $what")
            tries++
            if (tries > MAX_TRIES) recover(what) else later(800L)
        }

        /** Fallos seguidos sin avanzar (se pone a 0 al escribir un comentario o al cambiar de cuenta). */
        private var recoveries = 0

        /**
         * En vez de parar cuando no encuentra algo: vuelve a la pantalla principal de Instagram y lo intenta de nuevo.
         * Si estaba cambiando de cuenta, repite el cambio de cuenta; si no, empieza la vuelta desde el principio.
         * Con muchos fallos seguidos espera más entre intento e intento (hasta 5 minutos), pero no se para.
         */
        private fun recover(what: String) {
            recoveries++
            val switchOps = setOf("irperfil", "abrirlista", "tocarcuenta", "perfil", "dobleperfil")
            val inSwitch = cmds.getOrNull(i)?.op in switchOps
            trace("⟲ NO ENCUENTRO $what: vuelvo a Instagram y lo intento de nuevo (vez $recoveries)")
            toast(s, if (inSwitch) "No encuentro $what: vuelvo a Instagram y repito el cambio de cuenta"
                     else "No encuentro $what: vuelvo a Instagram y empiezo la vuelta de nuevo")
            tries = 0
            swipes = null
            feedPhase = 0
            waitEnd = 0L
            listPhase = 0
            listWaits = 0
            pendingSwitch = null
            switchChecks = 0
            storyPhase = 0
            storiesLeft = 0
            openedAt = -1
            reopens = 0
            scrolls = 0
            if (inSwitch) {
                // Al principio del bloque de cambio de cuenta
                while (i > 0 && cmds[i - 1].op in switchOps) i--
            } else {
                i = 0
            }
            tracedAt = -1
            openInstagramHome()
            val pause = if (recoveries <= 3) 5000L else minOf(recoveries * 60_000L, 300_000L)
            if (recoveries > 3) trace("  muchos fallos seguidos: espero ${pause / 1000} s antes de seguir")
            later(pause)
        }

        fun step() {
            if (id != token || !running) return
            stepGen++
            nextDue = System.currentTimeMillis()
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
            val here = loop * 100000 + i
            if (here != tracedAt) {
                tracedAt = here
                trace("paso ${i + 1}/${cmds.size} (vuelta ${loop + 1}): ${c.op} ${if (c.arg == NEXT_ACCOUNT) "siguiente" else c.arg}".trimEnd())
            }
            when (c.op) {
                "esperar" -> {
                    val now = System.currentTimeMillis()
                    if (waitEnd == 0L) {
                        val r = parseSecs(c.arg)
                        var wait = r.first + (Math.random() * (r.second - r.first)).toLong()
                        if (c.label == "descanso") wait *= 60
                        wait += c.extra()
                        if (wait <= 10000L) {
                            advance(wait)
                            return
                        }
                        waitEnd = now + wait
                    }
                    val left = waitEnd - now
                    if (left <= 0L) {
                        advance(400L)
                        return
                    }
                    // Espera larga: el botón flotante enseña cuánto falta
                    val secs = (left + 999) / 1000
                    showProgress(String.format(java.util.Locale.US, "%s · quedan %d:%02d",
                        if (c.label.isNotEmpty()) c.label else "esperando", secs / 60, secs % 60))
                    later(minOf(left, 1000L))
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
                    if (pendingSwitch != null) {
                        verifySwitch(c)
                        return
                    }
                    showProgress("leyendo la lista de cuentas")
                    // En la vuelta 1 se comenta con la primera cuenta de «cuentas» y se pasa a la segunda, etc.
                    if (c.arg == NEXT_ACCOUNT && accounts.isEmpty()) {
                        autoAccount(c)
                    } else {
                        val name = if (c.arg == NEXT_ACCOUNT) accounts[(loop + 1) % accounts.size] else c.arg
                        tapAccount(c, name)
                    }
                }
                "mirar" -> look(c)
                "inicio" -> feed(c)
                "historias" -> stories(c)
                "irperfil" -> {
                    // Ir a la pantalla principal de Instagram (con la barra de abajo y tu icono de perfil).
                    // Primero cierra el teclado; luego abre Instagram desde cero en su pantalla principal,
                    // porque ir hacia atrás a veces vuelve a la publicación con los comentarios.
                    val icon = profileIcon()
                    if (icon != null || tries >= 7) {
                        trace(if (icon != null) "  ya se ve la barra de Instagram (perfil en ${icon.first.toInt()},${icon.second.toInt()})"
                              else "  no veo la barra de Instagram, sigo igualmente. " + describeBottom())
                        advance(c.extra())
                        return
                    }
                    if (tries == 0) {
                        tries++
                        trace("  atrás (cerrar teclado)")
                        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        later(1000L)
                    } else {
                        tries++
                        trace("  no veo la barra de Instagram (intento $tries)")
                        openInstagramHome()
                        later(4000L)
                    }
                }
                "abrirlista" -> openAccountList(c)
                "dobleperfil" -> {
                    val p = profileIcon()
                    if (p == null) {
                        if (tries < 3) {
                            tries++
                            openInstagramHome()
                            later(4500L)
                            return
                        }
                        retry("tu foto de perfil abajo a la derecha")
                        return
                    }
                    trace("  doble toque en el perfil en ${p.first.toInt()},${p.second.toInt()}")
                    s.doubleTapAt(p.first, p.second)
                    current = null
                    used = 0
                    toast(s, "Doble toque en tu perfil: cambio de cuenta")
                    advance(c.extra())
                }
                "perfil" -> {
                    // Pulsación larga en el icono de perfil de abajo (lo busca por su nombre; si no, abajo a la derecha)
                    val p = profileIcon() ?: run {
                        if (tries < 3) {
                            tries++
                            openInstagramHome()
                            later(4500L)
                            return
                        }
                        recover("tu foto de perfil abajo a la derecha de Instagram")
                        return
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

        /**
         * «abrirlista»: abre la lista de cuentas de Instagram y comprueba que de verdad está abierta.
         * 1) Mantiene pulsada la foto de perfil de abajo a la derecha.
         * 2) Si no se abre, va a tu perfil y pulsa tu nombre de arriba (la otra forma que tiene Instagram).
         * Lo intenta hasta 3 veces; si no hay manera, para el guion y avisa.
         */
        private fun openAccountList(c: Cmd) {
            val myTry = stepGen
            when (listPhase) {
                0 -> {
                    val p = profileIcon()
                    if (p == null) {
                        if (tries < 6) {
                            tries++
                            trace("  no veo tu foto de perfil abajo (intento $tries)")
                            openInstagramHome()
                            later(4000L)
                        } else {
                            trace("  " + describeBottom())
                            recover("la barra de abajo de Instagram")
                        }
                        return
                    }
                    trace("  mantengo pulsada tu foto de perfil en ${p.first.toInt()},${p.second.toInt()}")
                    showProgress("abriendo la lista de cuentas")
                    s.longPressAt(p.first, p.second)
                    listPhase = 1
                    listWaits = 0
                    later(2800L)
                }
                1, 4 -> {
                    showProgress("esperando la lista de cuentas")
                    checkListOpen { open ->
                        if (myTry != stepGen || id != token || !running) return@checkListOpen
                        if (open) {
                            trace("  la lista de cuentas está abierta")
                            advance(maxOf(c.extra(), 600L))
                        } else if (listWaits < 3) {
                            // En algunos móviles la lista tarda en salir: se espera un poco más antes de probar otra cosa
                            listWaits++
                            trace("  la lista aún no ha salido: espero un poco más ($listWaits/3)")
                            later(1800L)
                        } else if (listPhase == 1) {
                            trace("  la lista no se abrió manteniendo pulsado: pruebo por tu perfil")
                            listPhase = 2
                            later(300L)
                        } else if (tries < 3) {
                            tries++
                            trace("  tampoco se abrió por el perfil: vuelvo a empezar (intento $tries)")
                            listPhase = 0
                            listWaits = 0
                            openInstagramHome()
                            later(4500L)
                        } else {
                            recover("la lista de cuentas")
                        }
                    }
                }
                2 -> {
                    // Ir a tu perfil (toque normal en la foto de abajo)
                    val p = profileIcon()
                    if (p == null) {
                        trace("  no veo tu foto de perfil: abro Instagram")
                        openInstagramHome()
                        listPhase = 0
                        tries++
                        if (tries > 3) recover("la barra de abajo de Instagram") else later(4500L)
                        return
                    }
                    trace("  toco tu foto de perfil para ir a tu perfil")
                    showProgress("probando por tu perfil")
                    s.tapAt(p.first, p.second)
                    listPhase = 3
                    listWaits = 0
                    later(3000L)
                }
                3 -> {
                    // Pulsar tu nombre de usuario de arriba del todo
                    val t = profileTitle()
                    if (t != null) {
                        trace("  toco tu nombre de arriba en ${t.first.toInt()},${t.second.toInt()}")
                        s.tapAt(t.first, t.second)
                        listPhase = 4
                        listWaits = 0
                        later(2500L)
                        return
                    }
                    s.readScreen { lines ->
                        if (myTry != stepGen || id != token || !running) return@readScreen
                        val (w, h) = s.screenSize()
                        val top = lines?.filter { it.second.centerY() > h * 0.035f && it.second.centerY() < h * 0.16f }
                            ?.sortedBy { it.second.top }
                        trace("  arriba leo: " + (top?.joinToString(" | ") { it.first } ?: "nada"))
                        // Tu nombre (aunque salga cortado con «…»), el más cerca del centro
                        val title = top?.filter { looksLikeTitle(it.first) && it.second.centerX() < w * 0.85f }
                            ?.minByOrNull { Math.abs(it.second.centerX() - w / 2) }
                        if (title != null) {
                            trace("  toco «${title.first}» (tu nombre de arriba)")
                            s.tapAt(title.second.exactCenterX(), title.second.exactCenterY())
                        } else {
                            trace("  no encuentro tu nombre de arriba")
                        }
                        listPhase = 4
                        listWaits = 0
                        later(2500L)
                    }
                }
            }
        }

        /**
         * Reconoce la lista de cuentas por su forma: varios nombres de cuenta uno debajo de otro,
         * alineados por la izquierda y a la misma distancia (como sale en la ventana que sube desde abajo).
         * Devuelve esos nombres de arriba abajo, o null si en la pantalla no hay nada así.
         */
        private fun accountColumn(items: List<Pair<String, Rect>>): List<Pair<String, Rect>>? {
            val (w, h) = s.screenSize()
            val userRx = Regex("^@?[A-Za-z0-9._]{2,30}$")
            val skip = setOf("instagram", "meta", "facebook", "threads", "home", "inicio")
            val names = items
                .map { Pair(it.first.trim().removePrefix("@"), it.second) }
                .filter { userRx.matches(it.first) && it.first.any { ch -> ch.isLetter() } && it.first.lowercase() !in skip }
                .filter { it.second.centerY() > h * 0.12f }
                .distinctBy { it.first.lowercase() }
            val hint = items.any { l ->
                val low = l.first.lowercase()
                LIST_MARKS.any { low.contains(it) } || low.contains("insignias") || low.contains("chats y") || low.contains("notificaciones")
            }
            var best: List<Pair<String, Rect>> = emptyList()
            for (a in names) {
                val col = names.filter { Math.abs(it.second.left - a.second.left) < w * 0.04f }.sortedBy { it.second.top }
                var run = ArrayList<Pair<String, Rect>>()
                var bestRun: List<Pair<String, Rect>> = emptyList()
                for (it in col) {
                    if (run.isEmpty()) {
                        run.add(it)
                    } else {
                        val gap = it.second.top - run.last().second.top
                        if (gap > h * 0.035f && gap < h * 0.17f) {
                            run.add(it)
                        } else {
                            if (run.size > bestRun.size) bestRun = run
                            run = arrayListOf(it)
                        }
                    }
                }
                if (run.size > bestRun.size) bestRun = run
                if (bestRun.size > best.size) best = bestRun
            }
            return if (best.size >= 3 || (best.size >= 2 && hint)) best else null
        }

        /** Tu nombre de usuario arriba del todo en tu perfil (el que abre la lista de cuentas), o null. */
        private fun profileTitle(): Pair<Float, Float>? {
            for (id in listOf("action_bar_large_title_auto_size", "action_bar_large_title", "action_bar_title_chevron",
                "action_bar_title", "action_bar_textview_title", "title_chevron")) {
                byInstagramId(id)?.let { return it }
            }
            // Por lo que pone: tu nombre (aunque salga cortado «enamorada_d…») o «Cambiar de cuenta», arriba del todo
            val (w, h) = s.screenSize()
            var best: Rect? = null
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                    if (n == null || depth > 60) return
                    if (n.isVisibleToUser) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.centerY() > h * 0.03f && r.centerY() < h * 0.16f && r.width() > 0) {
                            val t = ((n.text ?: n.contentDescription)?.toString() ?: "")
                            val low = t.lowercase()
                            val ok = looksLikeTitle(t) || low.contains("cambiar de cuenta") || low.contains("switch account")
                            if (ok && (best == null || Math.abs(r.centerX() - w / 2) < Math.abs(best!!.centerX() - w / 2))) best = r
                        }
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
                }
                walk(root, 0)
                if (best != null) break
            }
            return best?.let { Pair(it.exactCenterX(), it.exactCenterY()) }
        }

        /**
         * ¿Parece tu nombre de usuario de arriba? Acepta que salga cortado («enamorada_d…» / «enamorada_d...»)
         * y con la flechita ⌄ al lado.
         */
        private fun looksLikeTitle(t: String): Boolean {
            var x = t.trim().removePrefix("@")
            x = x.replace("…", "").replace("...", "")
            x = x.trimEnd(' ', '⌄', '˅', '∨', '▾', '▼', '~', '.', '·', '•')
            if (x.endsWith(" v") || x.endsWith(" V")) x = x.dropLast(2).trimEnd()
            return Regex("^[A-Za-z0-9._]{2,30}$").matches(x) && x.any { it.isLetter() } &&
                x.lowercase() !in setOf("instagram", "threads", "editar", "compartir")
        }

        /** ¿Está abierta la lista de cuentas? Primero por accesibilidad; si no, leyendo la pantalla. */
        private fun checkListOpen(done: (Boolean) -> Unit) {
            val list = accountListNames()
            if (list != null) {
                trace("  lista (accesibilidad): " + list.joinToString(", ") { it.first })
                done(true)
                return
            }
            s.readScreen { lines ->
                val col = if (lines != null) accountColumn(lines) else null
                trace("  leo en pantalla: " + (lines?.take(16)?.joinToString(" | ") { it.first } ?: "no puedo leer la pantalla"))
                if (col != null) trace("  lista (leída de la pantalla): " + col.joinToString(", ") { it.first })
                done(col != null)
            }
        }

        /** Muestra en el botón flotante el paso en el que va, la vuelta y los comentarios escritos. */
        private fun showProgress(override: String? = null) {
            val c = cmds.getOrNull(i) ?: return
            val what = override ?: when {
                c.label.isNotEmpty() -> c.label + " " + c.arg + " min"
                c.op == "tocarcuenta" || c.op == "irperfil" || c.op == "perfil" || c.op == "dobleperfil" || c.op == "abrirlista" -> "cambiando cuenta"
                c.op == "inicio" -> "paseo por inicio"
                c.op == "historias" -> "viendo historias"
                else -> (c.op + " " + c.arg).trim().take(14)
            }
            var t = "■ ${i + 1}/${cmds.size} $what"
            if (tries > 0) t += " · intento $tries/$MAX_TRIES"
            val extra = ArrayList<String>()
            if (repeats > 1) extra.add("vuelta ${loop + 1}/$repeats")
            if (used > 0) extra.add("$used escritos")
            if (mentionsRun > 0) extra.add("$mentionsRun menciones")
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
            if (android.os.Build.VERSION.SDK_INT < 30 && ScreenCaptureService.instance == null) {
                finish("Falta el permiso de captura de pantalla. Abre Star App y pulsa ▶ Iniciar: te lo pedirá.")
                return
            }
            val n = c.arg.trim().toIntOrNull() ?: 1
            val myTry = stepGen
            s.locateSavedImage(n) { pos, missing ->
                if (myTry != stepGen || id != token || !running) return@locateSavedImage
                when {
                    missing -> finish("No encuentro la imagen $n. Añádela en la app. Guion detenido.")
                    pos != null -> {
                        trace("  imagen $n encontrada: toco ${pos.first.toInt()},${pos.second.toInt()}")
                        val pick = checkMention
                        // Lo que ya se veía antes de publicar, para no confundirlo con un aviso nuevo
                        val before = if (pick != null) screenTexts().toSet() else emptySet()
                        s.tapAt(pos.first, pos.second)
                        if (pick != null) {
                            // Era «publicar» después de escribir menciones: mirar si Instagram lo ha publicado
                            checkMention = null
                            nextDue = System.currentTimeMillis() + 4000L
                            main.postDelayed({
                                if (myTry == stepGen && id == token && running) checkPosted(pick, before, c)
                            }, 3500L)
                        } else {
                            advance(c.extra())
                        }
                    }
                    else -> {
                        nudgeToFind(n)
                        retry("la imagen $n en la pantalla")
                    }
                }
            }
        }

        /**
         * Si no se ve la imagen, a veces está un poco más abajo (por ejemplo al abrir el enlace):
         * baja la pantalla poco a poco unas veces y, si tampoco, vuelve a subir.
         * Con el teclado abierto no se desliza, para no escribir nada sin querer.
         */
        private fun nudgeToFind(n: Int) {
            val attempt = tries + 1
            val down = attempt == 2 || attempt == 4 || attempt == 6
            val up = attempt == 9 || attempt == 11 || attempt == 13 || attempt == 15
            if (!down && !up) return
            val keyboard = try {
                s.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            } catch (e: Exception) { false }
            if (keyboard) return
            val (w, h) = s.screenSize()
            val x = w * 0.5f
            if (down) {
                trace("  no veo la imagen $n: bajo un poco la pantalla para buscarla")
                s.swipe(x, h * 0.68f, x, h * 0.46f, 550L)
            } else {
                trace("  sigo sin ver la imagen $n: vuelvo a subir un poco")
                s.swipe(x, h * 0.46f, x, h * 0.68f, 550L)
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
            trace("  escrito: $text")
            usedCombos.add(key)
            checkMention = pick
            countComment(text)
            used++
            last = System.currentTimeMillis()
            showProgress()
            advance(c.extra())
        }

        /** Todos los textos que se ven en pantalla (menos lo que hay escrito en la caja). */
        private fun screenTexts(): List<String> {
            val out = ArrayList<String>()
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 40) return
                if (!n.isEditable) {
                    n.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                    n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
                }
                for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
            }
            try {
                for (w in s.windows) {
                    if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                    walk(w.root, 0)
                }
            } catch (e: Exception) {
                walk(s.rootInActiveWindow, 0)
            }
            return out
        }

        /**
         * Después de tocar «publicar»: si sale cualquier aviso o línea en rojo que antes no estaba,
         * quita de la lista a la persona que no deja que la mencionen (si se sabe cuál) y pasa a la siguiente cuenta.
         * Si es un aviso de bloqueo, se para como siempre.
         */
        private fun checkPosted(pick: List<String>, before: Set<String>, c: Cmd) {
            if (blocked()) {
                finish("Instagram muestra un aviso de bloqueo o límite. Guion detenido para proteger tu cuenta.")
                return
            }
            val texts = screenTexts()
            val err = texts.firstOrNull { t -> t !in before && t.lowercase().let { l -> NOT_POSTED_TEXTS.any { l.contains(it) } } }
            if (err != null) {
                notPosted(pick, err, c)
                return
            }
            // Sin aviso conocido: mirar si ha aparecido cualquier línea en rojo que antes no estaba
            if (s.redBefore) {
                advance(c.extra())
                return
            }
            val gen = stepGen
            nextDue = System.currentTimeMillis() + 10_000L
            s.checkRedText { red ->
                if (gen != stepGen || id != token || !running) return@checkRedText
                if (red) notPosted(pick, null, c) else advance(c.extra())
            }
        }

        /** Ha salido algo en rojo al publicar: quitar la mención culpable (si se sabe) y pasar a la siguiente cuenta. */
        private fun notPosted(pick: List<String>, err: String?, c: Cmd) {
            trace("  ⚠ ha salido un aviso en rojo al publicar" + (if (err != null) ": «${err.take(150)}»" else ""))
            if (err == null) {
                findField()?.let { f -> if ((f.text?.length ?: 0) > 0) putText(f, "") }
                switchNow(c)
                return
            }
            // ¿Por culpa de quién? La persona que sale en el aviso o, si solo había una mención y el aviso habla de menciones, esa.
            val low = err.lowercase()
            val aboutMention = MENTION_ERROR_HINTS.any { low.contains(it) }
            var culprit = if (aboutMention) pick.filter { low.contains(it.removePrefix("@").lowercase()) } else emptyList()
            if (culprit.isEmpty() && pick.size == 1 && aboutMention) culprit = pick
            if (culprit.isNotEmpty()) removeMentions(culprit)
            // Borrar lo que quede escrito y cambiar de cuenta
            findField()?.let { f -> if ((f.text?.length ?: 0) > 0) putText(f, "") }
            switchNow(c)
        }

        /** Salta al cambio de cuenta del guion (si no hay, sigue con el paso siguiente). */
        private fun switchNow(c: Cmd) {
            val j = (i + 1 until cmds.size).firstOrNull { cmds[it].op == "irperfil" }
            if (j == null) {
                advance(c.extra())
                return
            }
            toast(s, "Ha salido un aviso en rojo: paso a la siguiente cuenta")
            trace("  paso a la siguiente cuenta")
            tries = 0
            swipes = null
            i = j
            later(1500L)
        }

        /** Quita personas de la lista de menciones (y las apunta en «no_mencionar»). */
        private fun removeMentions(handles: List<String>) {
            val prefs = s.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
            val bad = handles.map { it.removePrefix("@").lowercase() }.toSet()
            val keep = (prefs.getString("friends", "") ?: "").lines()
                .filter { it.trim().removePrefix("@").lowercase() !in bad }
            val old = (prefs.getString("no_mencionar", "") ?: "").lines().filter { it.isNotBlank() }
            prefs.edit()
                .putString("friends", keep.joinToString("\n"))
                .putString("no_mencionar", (old + handles).distinct().joinToString("\n"))
                .apply()
            toast(s, "Quito de tus menciones: ${handles.joinToString(" ")} (no deja que la mencionen)")
            trace("  quito de la lista de menciones: ${handles.joinToString(" ")}")
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

        private fun countComment(text: String) {
            skipsInARow = 0
            recoveries = 0
            val who = current
            if (who == null) pending++ else addCount(s, who)
            // Menciones de este comentario (@nombre)
            val handles = Regex("@[A-Za-z0-9._]+").findAll(text).map { it.value.trimEnd('.') }.toList()
            mentionsRun += handles.size
            addMentions(s, who, handles)
        }

        /** Toca la cuenta [name] de la lista como lo haría un dedo y luego comprueba que Instagram ha cambiado. */
        private fun startSwitch(name: String, x: Float, y: Float) {
            trace("  toco el nombre de la cuenta $name en ${x.toInt()},${y.toInt()}")
            showProgress("cambiando a $name")
            s.fingerTap(x, y)
            pendingSwitch = Triple(name, x, y)
            switchChecks = 0
            later(2600L)
        }

        /** Después de tocar una cuenta: si la lista se ha cerrado, el cambio está hecho; si no, se vuelve a tocar. */
        private fun verifySwitch(c: Cmd) {
            val (name, x, y) = pendingSwitch ?: return
            val g = stepGen
            checkListOpen { open ->
                if (g != stepGen || id != token || !running) return@checkListOpen
                if (!open) {
                    trace("  la lista se ha cerrado: cambio hecho")
                    pendingSwitch = null
                    switchedTo(name)
                    advance(c.extra())
                } else if (switchChecks < 2) {
                    switchChecks++
                    // Siempre en el NOMBRE de la cuenta (nunca en su foto): la segunda vez un poco más a la derecha
                    val (w, _) = s.screenSize()
                    val tx = if (switchChecks == 1) x else minOf(x + w * 0.10f, w * 0.85f)
                    trace("  la lista sigue abierta: vuelvo a tocar el nombre $name (vez ${switchChecks + 1}) en ${tx.toInt()},${y.toInt()}")
                    s.fingerTap(tx, y, 220L)
                    later(3000L)
                } else {
                    trace("  no consigo cambiar a $name: pruebo con otra cuenta")
                    pendingSwitch = null
                    visited.add(name)
                    retry("cómo cambiar a la cuenta $name")
                }
            }
        }

        /** Ya estamos en la cuenta [name]. */
        private fun switchedTo(name: String) {
            recoveries = 0
            trace("  CAMBIO A LA CUENTA $name")
            current = name
            used = 0
            reopens = 0
        }

        /** La lista de cuentas no está: se vuelve al paso de abrirla (como mucho 2 veces seguidas). */
        private fun reopenList() {
            if (reopens < 2 && i > 0 && cmds[i - 1].op == "abrirlista") {
                reopens++
                trace("  vuelvo a abrir la lista de cuentas (vez $reopens)")
                i -= 1
                tries = 0
                listPhase = 0
                tracedAt = -1
                later(500L)
            } else {
                recover("la lista de cuentas")
            }
        }

        /**
         * «cuenta siguiente» sin línea «cuentas»: lee los nombres de la lista de cuentas de Instagram
         * y pulsa el primero que no se haya usado en esta ronda. Cuando se han usado todas, empieza otra ronda.
         */
        private fun autoAccount(c: Cmd) {
            val list = accountListNames()
            if (list == null) {
                // Este móvil no da los nombres por accesibilidad: se lee la pantalla como una foto
                val myTry = stepGen
                s.readScreen { lines ->
                    if (myTry != stepGen || id != token || !running) return@readScreen
                    if (lines != null && autoAccountOcr(c, lines)) return@readScreen
                    trace("  no veo la lista de cuentas. Leo: " + (lines?.take(14)?.joinToString(" | ") { it.first } ?: "no puedo leer la pantalla"))
                    // La lista no está abierta (o se ha cerrado): se vuelve a abrir
                    if (tries >= 5) reopenList() else retry("la lista de cuentas de Instagram")
                }
                return
            }
            trace("  lista (accesibilidad): " + list.joinToString(", ") { it.first })
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
            val spot = Rect().also { list.last().second.getBoundsInScreen(it) }
            if (target == null && scrolls < 3) {
                // Puede que haya más cuentas más abajo
                scrolls++
                trace("  ya usé todas las cuentas que se ven: bajo por la lista")
                scrollList(true, spot)
                later(1200L)
                return
            }
            if (target == null) {
                // Ronda terminada: se vuelve a empezar por las demás
                visited.clear()
                current?.let { visited.add(it) }
                if (scrolls > 0) {
                    scrolls = 0
                    trace("  ronda terminada: vuelvo arriba de la lista")
                    scrollList(false, spot); scrollList(false, spot); scrollList(false, spot)
                    later(1200L)
                    return
                }
                target = list.map { it.first }.firstOrNull { it !in visited }
            }
            if (target == null) {
                recover("otra cuenta en la lista de cuentas")
                return
            }
            scrolls = 0
            val node = list.first { it.first == target }.second
            val r = Rect()
            node.getBoundsInScreen(r)
            visited.add(target)
            toast(s, "Cambio a la cuenta $target")
            startSwitch(target, r.exactCenterX(), r.exactCenterY())
        }

        /** Igual que [autoAccount] pero con el texto leído de la pantalla. Devuelve false si la lista no está abierta. */
        private fun autoAccountOcr(c: Cmd, lines: List<Pair<String, Rect>>): Boolean {
            val names = accountColumn(lines) ?: return false
            trace("  lista (leída de la pantalla): " + names.joinToString(", ") { it.first })
            if (current == null) {
                current = names.first().first
                if (pending > 0) repeat(pending) { addCount(s, names.first().first) }
                pending = 0
            }
            current?.let { visited.add(it) }
            fun seen(n: String) = visited.any { it.equals(n, ignoreCase = true) }
            var target = names.firstOrNull { !seen(it.first) }
            val spot = names.last().second
            if (target == null && scrolls < 3) {
                scrolls++
                trace("  ya usé todas las cuentas que se ven: bajo por la lista")
                scrollList(true, spot)
                later(1200L)
                return true
            }
            if (target == null) {
                visited.clear()
                current?.let { visited.add(it) }
                if (scrolls > 0) {
                    scrolls = 0
                    trace("  ronda terminada: vuelvo arriba de la lista")
                    scrollList(false, spot); scrollList(false, spot); scrollList(false, spot)
                    later(1200L)
                    return true
                }
                target = names.firstOrNull { !seen(it.first) }
            }
            if (target == null) {
                recover("otra cuenta en la lista de cuentas")
                return true
            }
            scrolls = 0
            visited.add(target.first)
            toast(s, "Cambio a la cuenta ${target.first}")
            startSwitch(target.first, target.second.exactCenterX(), target.second.exactCenterY())
            return true
        }

        /**
         * Si la lista de cuentas de Instagram está abierta, los nombres que se ven (de arriba abajo) con su texto;
         * si no está abierta, null.
         */
        private fun accountListNames(): List<Pair<String, AccessibilityNodeInfo>>? {
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                val items = ArrayList<Triple<String, Rect, AccessibilityNodeInfo>>()
                fun walk(n: AccessibilityNodeInfo?) {
                    if (n == null) return
                    val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""
                    if (t.isNotEmpty() && n.isVisibleToUser) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() > 0) items.add(Triple(t, r, n))
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k))
                }
                walk(root)
                val col = accountColumn(items.map { Pair(it.first, it.second) }) ?: continue
                return col.mapNotNull { c ->
                    items.firstOrNull { it.second == c.second && it.first.trim().removePrefix("@") == c.first }
                        ?.let { Pair(c.first, it.third) }
                }
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
            trace("  escrito: $text")
            countComment(text)
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

        /**
         * «inicio»: sale a la pantalla principal de Instagram, baja y sube por las publicaciones un rato
         * (con pausas al azar) y sigue. «inicio azar» = solo la mitad de las veces.
         * Después en el guion conviene poner «enlace» para volver a la publicación del sorteo.
         */
        private fun feed(c: Cmd) {
            when (feedPhase) {
                0 -> {
                    if (c.arg.trim().lowercase().startsWith("azar") && Math.random() < 0.5) {
                        advance(c.extra())
                        return
                    }
                    feedPhase = 1
                    tries = 0
                    later(200L)
                }
                1 -> {
                    // Atrás hasta ver la barra de abajo de Instagram (si se sale de Instagram, lo vuelve a abrir)
                    if (profileIcon() != null || tries >= 4) {
                        val home = homeIcon()
                        trace(if (home != null) "  en Inicio: toco la casita y bajo por las publicaciones" else "  no veo la casita; bajo por lo que haya")
                        home?.let { s.tapAt(it.first, it.second) }
                        val plan = ArrayDeque<Boolean>()
                        repeat(3 + (Math.random() * 4).toInt()) { plan.add(true) }
                        repeat(1 + (Math.random() * 3).toInt()) { plan.add(false) }
                        swipes = plan
                        feedPhase = 2
                        later(2000L)
                        return
                    }
                    tries++
                    if (tries == 1) {
                        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        later(1000L)
                    } else {
                        openInstagramHome()
                        later(4500L)
                    }
                }
                else -> {
                    val down = swipes?.removeFirstOrNull()
                    if (down == null) {
                        advance(c.extra())
                        return
                    }
                    val (w, h) = s.screenSize()
                    val x = w * (0.40f + (Math.random() * 0.20).toFloat())
                    val ms = 280L + (Math.random() * 320).toLong()
                    if (down) s.swipe(x, h * 0.74f, x, h * (0.26f + (Math.random() * 0.08).toFloat()), ms)
                    else s.swipe(x, h * 0.30f, x, h * (0.68f + (Math.random() * 0.06).toFloat()), ms)
                    // Se para un poco en cada publicación, como si la mirara
                    later(1500L + (Math.random() * 3500).toLong())
                }
            }
        }

        /** Centro de una vista de Instagram por su nombre interno (por ejemplo «profile_tab»), si se ve. */
        private fun byInstagramId(name: String): Pair<Float, Float>? {
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                val found = try { root.findAccessibilityNodeInfosByViewId("$INSTAGRAM:id/$name") } catch (e: Exception) { null } ?: continue
                for (n in found) {
                    if (!n.isVisibleToUser) continue
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) return Pair(r.exactCenterX(), r.exactCenterY())
                }
            }
            return null
        }

        /** Abre Instagram desde cero en su pantalla principal (Inicio, con la barra de abajo). */
        /** Cuántas veces se ha intentado ir a Inicio (para ir probando una forma distinta cada vez). */
        private var homeTries = 0

        /**
         * Ir a la pantalla principal de Instagram (Inicio, con la barra de abajo). Cada vez prueba una forma distinta,
         * porque según el móvil una funciona y otra no:
         * 1) abrir instagram.com (Instagram lo abre en su Inicio),
         * 2) el botón atrás (sale de la publicación que abrió el enlace),
         * 3) abrir Instagram desde cero.
         */
        private fun openInstagramHome() {
            val method = homeTries % 3
            homeTries++
            when (method) {
                0 -> {
                    trace("  voy a Inicio de Instagram abriendo instagram.com")
                    val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.instagram.com/"))
                        .setPackage(INSTAGRAM)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { s.startActivity(i) } catch (e: Exception) { launchInstagramFresh() }
                }
                1 -> {
                    trace("  voy a Inicio de Instagram con el botón atrás")
                    s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                else -> {
                    trace("  voy a Inicio de Instagram abriéndolo desde cero")
                    launchInstagramFresh()
                }
            }
        }

        private fun launchInstagramFresh() {
            val i = s.packageManager.getLaunchIntentForPackage(INSTAGRAM) ?: return
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            try { s.startActivity(i) } catch (e: Exception) {}
        }

        /**
         * «historias 3»: va a la pantalla principal de Instagram, abre la primera historia de arriba,
         * la mira unos segundos y pasa a la siguiente hasta ver 3; luego cierra las historias y sigue.
         * «historias azar» = solo la mitad de las veces. Después en el guion conviene poner «enlace».
         */
        private fun stories(c: Cmd) {
            val a = c.arg.trim().lowercase()
            when (storyPhase) {
                0 -> {
                    if (a.contains("azar") && Math.random() < 0.5) {
                        advance(c.extra())
                        return
                    }
                    // Ir a la pantalla principal (con la barra de abajo)
                    if (profileIcon() != null) {
                        homeIcon()?.let { s.tapAt(it.first, it.second) }
                        trace("  en Inicio: busco las historias de arriba")
                        storyPhase = 1
                        tries = 0
                        later(2500L)
                        return
                    }
                    if (tries >= 4) {
                        trace("  no consigo ir a Inicio: me salto las historias")
                        advance(c.extra())
                        return
                    }
                    tries++
                    if (tries == 1) {
                        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        later(1000L)
                    } else {
                        openInstagramHome()
                        later(4500L)
                    }
                }
                1 -> {
                    // Abrir la primera historia que no sea la tuya
                    val p = firstStory()
                    if (p != null) {
                        trace("  abro la primera historia en ${p.first.toInt()},${p.second.toInt()}")
                        s.fingerTap(p.first, p.second)
                        storyPhase = 2
                        later(2500L)
                        return
                    }
                    val g = stepGen
                    s.readScreen { lines ->
                        if (g != stepGen || id != token || !running) return@readScreen
                        val (_, h) = s.screenSize()
                        val yours = lines?.firstOrNull { it.first.lowercase().contains("tu historia") || it.first.lowercase().contains("your story") }
                        // El nombre que hay debajo de la burbuja de al lado de «Tu historia» (tocarlo también abre la historia)
                        val next = if (yours == null) null else lines.filter {
                            Math.abs(it.second.centerY() - yours.second.centerY()) < h * 0.02f && it.second.left > yours.second.right
                        }.minByOrNull { it.second.left }
                        if (next != null) {
                            val x = next.second.exactCenterX()
                            val y = next.second.exactCenterY()
                            trace("  abro la primera historia tocando su nombre «${next.first}» en ${x.toInt()},${y.toInt()}")
                            s.fingerTap(x, y)
                            storyPhase = 2
                            later(2500L)
                        } else {
                            trace("  no veo las historias de arriba: me las salto")
                            advance(c.extra())
                        }
                    }
                }
                2 -> {
                    if (inStoryViewer()) {
                        // «historias 3» = 3; «historias 3-5» = entre 3 y 5 al azar
                        val m = Regex("(\\d+)\\s*-\\s*(\\d+)").find(a)
                        storiesLeft = if (m != null) {
                            val lo = m.groupValues[1].toInt()
                            val hi = maxOf(lo, m.groupValues[2].toInt())
                            lo + (Math.random() * (hi - lo + 1)).toInt()
                        } else {
                            Regex("\\d+").find(a)?.value?.toIntOrNull() ?: 3
                        }.coerceIn(1, 20)
                        trace("  viendo historias ($storiesLeft)")
                        storyPhase = 3
                        later(3000L + (Math.random() * 3000).toLong())
                    } else if (tries < 2) {
                        tries++
                        trace("  la historia no se ha abierto: lo intento otra vez")
                        storyPhase = 1
                        later(1000L)
                    } else {
                        trace("  no consigo abrir las historias: me las salto")
                        advance(c.extra())
                    }
                }
                3 -> {
                    storiesLeft--
                    if (storiesLeft <= 0 || !inStoryViewer()) {
                        storyPhase = 4
                        tries = 0
                        later(300L)
                        return
                    }
                    // Pasar a la siguiente historia (toque en la parte derecha)
                    val (w, h) = s.screenSize()
                    trace("  paso a la siguiente historia")
                    s.fingerTap(w * 0.86f, h * 0.45f)
                    later(3000L + (Math.random() * 3000).toLong())
                }
                else -> {
                    // Cerrar las historias
                    if (inStoryViewer() && tries < 3) {
                        tries++
                        trace("  cierro las historias")
                        s.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                        later(1500L)
                        return
                    }
                    advance(c.extra())
                }
            }
        }

        /** Centro de la primera historia de arriba que no es la tuya, o null. */
        private fun firstStory(): Pair<Float, Float>? {
            val (_, h) = s.screenSize()
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                // 1) Por su descripción: «Historia de …» / «…'s story» (las burbujas de arriba)
                val bubbles = ArrayList<Rect>()
                var tray: AccessibilityNodeInfo? = null
                fun walk(n: AccessibilityNodeInfo?) {
                    if (n == null) return
                    if (n.isVisibleToUser) {
                        // Solo la descripción (no el texto), para no confundirla con una frase de una publicación
                        val d = (n.contentDescription?.toString() ?: "").lowercase()
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        val isStory = (d.contains("historia de ") || d.contains("'s story") || d.contains("story by") ||
                            d.contains("historia sin ver") || d.contains("unseen story")) &&
                            !d.contains("tu historia") && !d.contains("your story")
                        if (isStory && r.width() > 0 && r.centerY() < h * 0.40f) bubbles.add(r)
                        if (tray == null && n.viewIdResourceName?.endsWith(":id/reels_tray_container") == true) tray = n
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k))
                }
                walk(root)
                // La primera burbuja de la izquierda es siempre la tuya: se coge la siguiente
                val (sw, _) = s.screenSize()
                bubbles.filter { it.centerX() > sw * 0.25f }.minByOrNull { it.left }?.let {
                    trace("  historia encontrada por su descripción")
                    return Pair(it.exactCenterX(), it.exactCenterY())
                }
                // 2) Por la fila de historias: la segunda (la primera es «Tu historia»)
                val t = tray ?: continue
                val items = (0 until t.childCount).mapNotNull { t.getChild(it) }
                    .filter { it.isVisibleToUser }
                    .map { val r = Rect(); it.getBoundsInScreen(r); r }
                    .filter { it.width() > 0 && it.height() > 0 }
                    .sortedBy { it.left }
                val r = items.getOrNull(1) ?: continue
                trace("  historia encontrada en la fila de historias")
                return Pair(r.exactCenterX(), r.exactCenterY())
            }
            return null
        }

        /** ¿Están abiertas las historias? (pantalla de historias de Instagram) */
        private fun inStoryViewer(): Boolean {
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                var found = false
                fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                    if (n == null || found || depth > 40) return
                    val vid = n.viewIdResourceName ?: ""
                    val t = ((n.text ?: n.contentDescription)?.toString() ?: "").lowercase()
                    if (vid.contains("reel_viewer") || t.startsWith("enviar mensaje") || t.startsWith("send message") ||
                        t.startsWith("responder a ")) {
                        found = true
                        return
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
                }
                walk(root, 0)
                if (found) return true
            }
            return false
        }

        /** Centro del icono «Inicio» (la casita de abajo a la izquierda de Instagram), o null. */
        private fun homeIcon(): Pair<Float, Float>? {
            byInstagramId("feed_tab")?.let { return it }
            homeByLabel()?.let { return it }
            return tabBar()?.first()?.let { Pair(it.exactCenterX(), it.exactCenterY()) }
        }

        private fun homeByLabel(): Pair<Float, Float>? {
            val (w, h) = s.screenSize()
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                for (word in listOf("Inicio", "Home")) {
                    val found = root.findAccessibilityNodeInfosByText(word) ?: continue
                    for (n in found) {
                        if (!n.isVisibleToUser) continue
                        val low = ((n.contentDescription ?: n.text)?.toString() ?: "").trim().lowercase()
                        if (!low.startsWith(word.lowercase())) continue
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.centerY() < h * 0.80f || r.centerX() > w * 0.40f) continue
                        return Pair(r.exactCenterX(), r.exactCenterY())
                    }
                }
            }
            return null
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
                val r = Rect()
                node.getBoundsInScreen(r)
                startSwitch(name, r.exactCenterX(), r.exactCenterY())
                return
            }
            val myTry = stepGen
            s.locateText(name) { pos ->
                if (myTry != stepGen || id != token || !running) return@locateText
                if (pos != null) {
                    startSwitch(name, pos.first, pos.second)
                    return@locateText
                }
                // Con muchas cuentas la lista no cabe: baja y luego sube para buscarla
                if (tries >= 8) {
                    reopenList()
                    return@locateText
                }
                scrollList(tries < 4)
                retry("la cuenta «$name» en la lista de cuentas")
            }
        }

        /**
         * Desplaza una lista hacia abajo (o hacia arriba). Si se da [near], usa la lista que está en ese sitio
         * (la de cuentas, no la de publicaciones que queda detrás); si no, la primera de la ventana de más arriba.
         */
        private fun scrollList(down: Boolean, near: Rect? = null) {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.isScrollable && n.isVisibleToUser) {
                    if (near == null) return n
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.contains(near.centerX(), near.centerY())) {
                        // Si dentro hay otra lista más ajustada a ese sitio, mejor esa
                        for (k in 0 until n.childCount) find(n.getChild(k))?.let { return it }
                        return n
                    }
                }
                for (k in 0 until n.childCount) find(n.getChild(k))?.let { return it }
                return null
            }
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            for (root in windowRoots()) {
                val list = find(root) ?: continue
                list.performAction(action)
                return
            }
            if (near != null) {
                // Sin lista desplazable a la vista: se desliza con el dedo sobre la lista de cuentas
                val (w, h) = s.screenSize()
                val x = w * 0.5f
                if (down) s.swipe(x, h * 0.80f, x, h * 0.55f, 500L) else s.swipe(x, h * 0.55f, x, h * 0.80f, 500L)
            }
        }

        /**
         * Centro del icono «Perfil» de la pestaña de abajo a la derecha de Instagram, o null si no se ve
         * (por ejemplo con los comentarios abiertos). Tiene que llamarse exactamente «Perfil» (no «foto del perfil»)
         * y estar abajo del todo y a la derecha.
         */
        /**
         * La barra de abajo de Instagram (Inicio, Reels, Mensajes, Buscar, Perfil) buscada por su forma:
         * 4 a 6 botones en fila abajo del todo, repartidos por todo el ancho. Devuelve sus posiciones de izquierda a derecha.
         * Sirve aunque cada versión de Instagram llame a los botones de otra manera.
         */
        private fun tabBar(): List<Rect>? {
            val (w, h) = s.screenSize()
            for (root in windowRoots()) {
                if (root.packageName?.toString() != INSTAGRAM) continue
                val cands = ArrayList<Rect>()
                fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                    if (n == null || depth > 60) return
                    if (n.isVisibleToUser && (n.isClickable || !n.contentDescription.isNullOrEmpty())) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.centerY() > h * 0.82f && r.height() in 1..(h * 0.12f).toInt() && r.width() in 1..(w * 0.35f).toInt()) {
                            cands.add(r)
                        }
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
                }
                walk(root, 0)
                // Agrupar por altura y quedarse con una fila de 4 a 6 botones que ocupe casi todo el ancho
                val rows = cands.groupBy { (it.centerY() / (h * 0.03f)).toInt() }.values
                for (row in rows.sortedByDescending { it.first().centerY() }) {
                    val cols = row.sortedBy { it.centerX() }
                        .fold(ArrayList<Rect>()) { acc, r ->
                            if (acc.isEmpty() || r.centerX() - acc.last().centerX() > w * 0.08f) acc.add(r)
                            acc
                        }
                    if (cols.size in 4..6 && cols.last().centerX() - cols.first().centerX() > w * 0.6f) return cols
                }
            }
            return null
        }

        /** Qué hay abajo del todo de la pantalla (para el informe cuando no se encuentra la barra de Instagram). */
        private fun describeBottom(): String {
            val (_, h) = s.screenSize()
            val out = ArrayList<String>()
            val pkg = s.rootInActiveWindow?.packageName?.toString() ?: "?"
            for (root in windowRoots()) {
                fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                    if (n == null || depth > 60 || out.size > 14) return
                    if (n.isVisibleToUser) {
                        val r = Rect()
                        n.getBoundsInScreen(r)
                        if (r.centerY() > h * 0.82f) {
                            val d = (n.contentDescription ?: n.text)?.toString()?.take(25) ?: ""
                            val vid = n.viewIdResourceName?.substringAfter(":id/") ?: ""
                            if (d.isNotEmpty() || vid.isNotEmpty()) out.add("$d[$vid]@${r.centerX()},${r.centerY()}")
                        }
                    }
                    for (k in 0 until n.childCount) walk(n.getChild(k), depth + 1)
                }
                walk(root, 0)
            }
            return "app delante: $pkg · abajo: " + (if (out.isEmpty()) "nada" else out.joinToString(" | "))
        }

        private fun profileIcon(): Pair<Float, Float>? {
            byInstagramId("profile_tab")?.let { return it }
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
            val b = best ?: return tabBar()?.last()?.let { Pair(it.exactCenterX(), it.exactCenterY()) }
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
