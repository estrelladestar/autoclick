package com.example.autoclick

import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Asistente que convierte una frase en español en un guion de la app.
 *
 * 1) interpret(): intérprete sin IA. Gratis y sin internet. Entiende frases sencillas como
 *    «abre el enlace, pulsa la imagen 1, luego la imagen 2, escribe un comentario copiado con 2 menciones,
 *     pulsa otra imagen y repite 5 veces».
 * 2) askAI(): IA real (API de Anthropic). Solo se usa si la persona ha puesto su propia clave API en la app
 *    (la clave nunca va en el código). Solo se envía la frase escrita, nunca los comentarios, menciones ni enlaces.
 *    Si falla (sin internet, sin saldo…), la pantalla vuelve a usar el intérprete sin IA.
 */
object Assistant {

    class Result(val script: String?, val unknown: List<String>, val notes: List<String> = emptyList())

    private val WORD_NUMS = mapOf(
        "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7,
        "ocho" to 8, "nueve" to 9, "diez" to 10,
        "primer" to 1, "primera" to 1, "primero" to 1,
        "segunda" to 2, "segundo" to 2,
        "tercer" to 3, "tercera" to 3, "tercero" to 3,
        "cuarta" to 4, "cuarto" to 4,
        "quinta" to 5, "quinto" to 5
    )

    private class Clause(val n: String, val o: String)

    private sealed class Step {
        class Link(val num: Int?) : Step()
        class Image(val num: Int?) : Step()
        class Field : Step()
        class Wait(val text: String) : Step()
        class Tap(val text: String) : Step()
        class Literal(val text: String) : Step()
        class Text(val copied: Boolean, val mentions: Boolean, val count: Int?) : Step()
        /** Toque en una zona de la pantalla, en porcentaje del ancho (x) y del alto (y). */
        class Pos(val x: Int, val y: Int) : Step()
        /** Cambiar de cuenta: pulsación larga en el perfil y, si se dice, elegir la cuenta por su nombre. */
        class Account(val name: String?) : Step()
    }

    private fun stripAccent(c: Char): Char {
        val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        return if (d.isNotEmpty()) d[0] else c
    }

    /** Número que está pegado a una palabra: «imagen 2», «2 imagen», «segunda imagen», «2 menciones». */
    private fun numberNear(n: String, stem: String, allowArticle: Boolean = false): Int? {
        Regex("$stem\\w*\\s*(?:n[o°º]?\\s*)?(\\d+)").find(n)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("(\\d+)\\s*(?:a|o|º|ª|°)?\\s*$stem").find(n)?.let { return it.groupValues[1].toIntOrNull() }
        for ((w, v) in WORD_NUMS) {
            if (Regex("\\b$w\\s+(?:de\\s+)?(?:los\\s+|las\\s+)?$stem").containsMatchIn(n)) return v
            if (Regex("$stem\\w*\\s+$w\\b").containsMatchIn(n)) return v
        }
        if (allowArticle && Regex("\\b(?:un|una|uno)\\s+$stem").containsMatchIn(n)) return 1
        return null
    }

    /** «30» → «30»; «entre 30 y 60», «30-60», «de 30 a 60» → «30-60». */
    private fun waitText(n: String): String? {
        Regex("(\\d+(?:[.,]\\d+)?)\\s*-\\s*(\\d+(?:[.,]\\d+)?)").find(n)?.let {
            return it.groupValues[1].replace(',', '.') + "-" + it.groupValues[2].replace(',', '.')
        }
        Regex("(\\d+(?:[.,]\\d+)?)").find(n)?.let {
            var v = it.groupValues[1].replace(',', '.')
            if (n.contains("minuto")) {
                val m = v.toDoubleOrNull() ?: return v
                v = (m * 60).toInt().toString()
            }
            return v
        }
        for ((w, v) in WORD_NUMS) if (Regex("\\b$w\\b").containsMatchIn(n)) return v.toString()
        return null
    }

    /** Intérprete sin IA. */
    fun interpret(input: String): Result {
        val quotes = ArrayList<String>()
        val withHoles = Regex("[\"“«](.+?)[\"”»]").replace(Normalizer.normalize(input, Normalizer.Form.NFC)) {
            quotes.add(it.groupValues[1].trim())
            "qqq" + (quotes.size - 1) + "qqq"
        }
        val orig = withHoles.lowercase()
        // «entre 30 y 60» y «de 30 a 60» se convierten en «30-60» para que la «y» no corte la frase
        val joined = orig
            .replace(Regex("(?:entre\\s+)?(\\d+)\\s+(?:y|a)\\s+(\\d+)")) { it.groupValues[1] + "-" + it.groupValues[2] }
        val norm = joined.map { stripAccent(it) }.joinToString("")

        val splitter = Regex("\\s*(?:[,.;:]|\\by luego\\b|\\by despues\\b|\\bluego\\b|\\bdespues\\b|\\bentonces\\b|\\by\\b)\\s*")
        val clauses = ArrayList<Clause>()
        var pos = 0
        for (m in splitter.findAll(norm)) {
            if (m.range.first > pos) clauses.add(Clause(norm.substring(pos, m.range.first), joined.substring(pos, m.range.first)))
            pos = m.range.last + 1
        }
        if (pos < norm.length) clauses.add(Clause(norm.substring(pos), joined.substring(pos)))

        val steps = ArrayList<Step>()
        val unknown = ArrayList<String>()
        val notes = ArrayList<String>()
        var repeats = 1
        var lastImage = 0
        var lastTap: Step? = null

        for (c in clauses) {
            var n = c.n.trim()
            for (f in listOf("por favor", "quiero que", "quiero", "primero", "ahora", "despues", "que", "se", "me", "lo", "la", "el", "haz", "hazme")) {
                n = n.replace(Regex("\\b$f\\b"), " ")
            }
            n = n.replace(Regex("\\s+"), " ").trim()
            if (n.isEmpty()) continue

            val q = Regex("qqq(\\d+)qqq").find(n)
            when {
                n.contains("veces") || n.contains("repit") -> {
                    repeats = (Regex("(\\d+)").find(n)?.groupValues?.get(1)?.toIntOrNull()
                        ?: WORD_NUMS.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(n) }?.value
                        ?: repeats).coerceIn(1, 1000)
                }
                q != null && Regex("\\b(?:escrib\\w*|pon\\w*|pega\\w*|comenta\\w*|di|dile)\\b").containsMatchIn(n) -> {
                    steps.add(Step.Literal(quotes[q.groupValues[1].toInt()]))
                }
                n.contains("esper") || n.contains("pausa") || n.contains("aguard") -> {
                    steps.add(Step.Wait(waitText(n) ?: "3"))
                }
                Regex("enlace|link|url").containsMatchIn(n) -> {
                    steps.add(Step.Link(numberNear(n, "(?:enlace|link|url)")))
                }
                Regex("mencion|arroba|amig|etiquet").containsMatchIn(n) -> {
                    val copied = Regex("copiad|comentario|frase|lista|guardad").containsMatchIn(n)
                    steps.add(Step.Text(copied, true, numberNear(n, "(?:mencion|arroba|amig|etiquet)", true)))
                }
                Regex("copiad|comentario|frase").containsMatchIn(n) -> {
                    steps.add(Step.Text(true, false, null))
                }
                Regex("imagen|foto|captura").containsMatchIn(n) -> {
                    val explicit = numberNear(n, "(?:imagen|foto|captura)")
                    val num = when {
                        explicit != null -> explicit
                        n.contains("otra") || n.contains("siguiente") -> lastImage + 1
                        else -> lastImage + 1
                    }
                    lastImage = num
                    val st = Step.Image(num)
                    lastTap = st
                    steps.add(st)
                }
                Regex("campo|caja|cuadro de texto|barra de comentario").containsMatchIn(n) -> steps.add(Step.Field())
                Regex("cuenta").containsMatchIn(n) && Regex("cambi|entra|pasa|usa|otra").containsMatchIn(n) -> {
                    val name = if (q != null) quotes[q.groupValues[1].toInt()]
                    else Regex("cuenta\\s+(?:de\\s+|llamada\\s+)?(.+)$").find(c.o.trim())?.groupValues?.get(1)?.trim()
                        ?.takeIf { it.isNotEmpty() && !it.startsWith("otra") }
                    if (name == null) {
                        notes.add(
                            "Cambiar de cuenta: el guion mantiene pulsado el icono del perfil para abrir la lista de cuentas, " +
                            "pero no puede adivinar cuál es «la otra». Para elegirla di su nombre («cambia a la cuenta NOMBRE») " +
                            "o sube una captura de esa cuenta y añade «pulsa la imagen N»."
                        )
                    }
                    steps.add(Step.Account(name))
                }
                Regex("otra vez|de nuevo|nuevamente|vuelve|volver").containsMatchIn(n) -> {
                    val again = lastTap
                    if (again != null) steps.add(again) else unknown.add(c.o.trim())
                }
                Regex("abajo|arriba|izquierda|derecha|esquina|perfil").containsMatchIn(n) &&
                    Regex("toca|pulsa|clic|click|presiona|pincha|icono|boton|perfil").containsMatchIn(n) -> {
                    val x = when {
                        n.contains("derecha") -> 90
                        n.contains("izquierda") -> 10
                        n.contains("perfil") -> 90
                        else -> 50
                    }
                    val y = when {
                        n.contains("abajo") -> 95
                        n.contains("arriba") -> 6
                        n.contains("perfil") -> 95
                        else -> 50
                    }
                    val st = Step.Pos(x, y)
                    lastTap = st
                    steps.add(st)
                }
                else -> {
                    val t = Regex("(?:toca|pulsa|clic en|click en|clic|click|presiona|pincha en|pincha)\\s+(?:en\\s+)?(?:el\\s+|la\\s+)?(?:boton\\s+)?(.+)")
                        .find(c.o.trim())
                    if (t != null && t.groupValues[1].isNotBlank() && !t.groupValues[1].contains("qqq")) {
                        val st = Step.Tap(t.groupValues[1].trim())
                        lastTap = st
                        steps.add(st)
                    } else {
                        unknown.add(c.o.trim())
                    }
                }
            }
        }

        // Un comentario copiado seguido o precedido de menciones es un solo paso.
        val merged = ArrayList<Step>()
        for (s in steps) {
            val prev = merged.lastOrNull()
            if (s is Step.Text && prev is Step.Text) {
                merged[merged.size - 1] = Step.Text(prev.copied || s.copied, prev.mentions || s.mentions, s.count ?: prev.count)
            } else {
                merged.add(s)
            }
        }

        if (merged.isEmpty() || unknown.isNotEmpty()) return Result(null, unknown)

        // Construye las líneas del guion
        val lines = ArrayList<String>()
        for ((i, s) in merged.withIndex()) {
            val next = merged.getOrNull(i + 1)
            val last = i == merged.size - 1
            when (s) {
                is Step.Link -> {
                    lines.add(if (s.num != null) "enlace ${s.num}" else "enlace")
                    if (next !is Step.Wait) lines.add("esperar 8")
                }
                is Step.Wait -> lines.add("esperar ${s.text}")
                is Step.Field -> {}
                is Step.Tap -> lines.add("tocar ${s.text} | 2-3")
                is Step.Pos -> lines.add("tocar ${s.x}%,${s.y}% | 2-3")
                is Step.Account -> {
                    lines.add("mantener 90%,92% | 2")
                    if (s.name != null) lines.add("tocar ${s.name} | 2-3")
                }
                is Step.Literal -> {
                    lines.add("escribir ${s.text}")
                    lines.add("esperar 1.5")
                }
                is Step.Text -> {
                    val line = when {
                        s.copied && s.mentions -> "escribir copiados menciones" + (s.count?.let { " $it" } ?: "")
                        s.mentions -> "escribir menciones" + (s.count?.let { " $it" } ?: "")
                        else -> "escribir azar"
                    }
                    lines.add(line)
                    lines.add("esperar 1.5")
                }
                is Step.Image -> {
                    // Con varias repeticiones, el último paso espera entre 30 y 60 s antes de volver a empezar
                    val pause = if (last && repeats > 1) "30-60" else "2-4"
                    lines.add("imagen ${s.num} | $pause")
                }
            }
        }
        // Con varias repeticiones, espera entre 30 y 60 s antes de volver a empezar (como en el guion de ejemplo)
        if (repeats > 1 && merged.last() !is Step.Wait && !lines.last().contains("30-60")) lines.add("esperar 30-60")
        if (repeats > 1) lines.add("repetir $repeats")
        return Result(lines.joinToString("\n"), emptyList(), notes)
    }

    // ---------------------------------------------------------------------------------------------
    // Comprobación del guion
    // ---------------------------------------------------------------------------------------------

    private val OPS = setOf("esperar", "tocar", "escribir", "campo", "enlace", "imagen", "mantener", "repetir", "atras", "atrás", "cuenta", "cuentas", "clic", "click", "pulsar", "mirar")

    /** Líneas del guion que la app no entiende (vacío si todo está bien). */
    fun badLines(script: String): List<String> =
        script.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { it.substringBefore(' ').lowercase() !in OPS }

    // ---------------------------------------------------------------------------------------------
    // IA real (opcional)
    // ---------------------------------------------------------------------------------------------

    private const val MODEL = "claude-haiku-5-5"

    private val SYSTEM_PROMPT = """Conviertes una petición en español en un guion para una app de Android que pulsa en pantalla.
Responde SOLO con las líneas del guion, una por línea, sin explicaciones y sin bloques de código.
Instrucciones permitidas:
esperar 5            (espera 5 s; esperar 5-10 = al azar entre 5 y 10)
enlace               (abre el siguiente enlace guardado; enlace 2 = el enlace 2)
imagen 1             (busca en pantalla la imagen 1 guardada y la pulsa; imagen 2 = otra)
escribir azar        (escribe un comentario copiado al azar)
escribir lista       (escribe los comentarios copiados en orden)
escribir menciones   (escribe @amigos al azar; escribir menciones 3 = con 3 menciones, de 1 a 5)
escribir copiados menciones 2   (un comentario copiado al azar más 2 menciones)
escribir Hola        (escribe ese texto fijo)
tocar Texto          (toca el botón o texto que diga así)
tocar 500,1200       (toca esa posición en píxeles)
tocar 90%,92%        (toca esa zona: porcentaje del ancho y del alto; 90%,92% es abajo a la derecha, donde está el icono del perfil de Instagram)
mantener 90%,92%     (pulsación larga en esa zona; en Instagram, sobre el perfil abre la lista de cuentas)
atrás                (como el botón atrás del móvil: cierra el teclado o los comentarios)
cuenta NOMBRE        (cambia a la cuenta de Instagram NOMBRE: cierra los comentarios, abre la lista de cuentas y la pulsa)
repetir 10           (repite todo el guion 10 veces; va una sola vez, en cualquier línea)
# nota               (se ignora)
Al final de cualquier línea, | 30 espera 30 s más y | 30-60 espera entre 30 y 60 s al azar.
Reglas: después de «enlace» pon «esperar 8» para que cargue la página.
Si la persona dice «vuelve a pulsar» o «de nuevo», repite la línea del último toque.
Para cambiar de cuenta: si la persona dice el nombre de la cuenta, «cuenta NOMBRE». Si no dice cuál, pon «mantener 90%,92% | 2».
Si la persona no dice cuántas veces, no pongas «repetir». Si pide algo que no se puede hacer con estas instrucciones, usa solo las que sí se puedan."""

    /** Llama a la IA en un hilo aparte. onDone se llama en ese hilo: usa runOnUiThread. */
    fun askAI(apiKey: String, request: String, onDone: (script: String?, error: String?) -> Unit) {
        Thread {
            var conn: HttpURLConnection? = null
            try {
                val body = JSONObject()
                    .put("model", MODEL)
                    .put("max_tokens", 600)
                    .put("system", SYSTEM_PROMPT)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", request)))
                conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 45_000
                    doOutput = true
                    setRequestProperty("content-type", "application/json")
                    setRequestProperty("x-api-key", apiKey.trim())
                    setRequestProperty("anthropic-version", "2023-06-01")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val msg = try { JSONObject(text).getJSONObject("error").getString("message") } catch (e: Exception) { text.take(200) }
                    val hint = if (code == 401) " (revisa la clave API)" else ""
                    onDone(null, "Error $code$hint: $msg")
                    return@Thread
                }
                val content = JSONObject(text).getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val part = content.getJSONObject(i)
                    if (part.optString("type") == "text") sb.append(part.optString("text"))
                }
                val clean = sb.toString().lines()
                    .filter { !it.trim().startsWith("```") }
                    .joinToString("\n").trim()
                if (clean.isEmpty()) onDone(null, "La IA no devolvió ningún guion.") else onDone(clean, null)
            } catch (e: Exception) {
                onDone(null, "No se pudo conectar: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
