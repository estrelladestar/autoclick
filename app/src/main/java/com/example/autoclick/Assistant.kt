package com.example.autoclick

import java.text.Normalizer

/**
 * Asistente que convierte una frase en español en un guion de la app.
 *
 * Es un intérprete sin IA: gratis y sin internet. Entiende frases sencillas como
 * «abre el enlace, pulsa la imagen 1, luego la imagen 2, escribe un comentario copiado con 2 menciones,
 *  pulsa otra imagen y repite 5 veces».
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
                is Step.Field -> lines.add("campo")
                is Step.Tap -> lines.add("tocar ${s.text} | 2-3")
                is Step.Pos -> lines.add("tocar ${s.x}%,${s.y}% | 2-3")
                is Step.Account -> {
                    lines.add("mantener 90%,95% | 2")
                    if (s.name != null) lines.add("tocar ${s.name} | 2-3")
                }
                is Step.Literal -> {
                    if (lines.lastOrNull() != "campo") { lines.add("campo"); lines.add("esperar 1") }
                    lines.add("escribir ${s.text}")
                    lines.add("esperar 1.5")
                }
                is Step.Text -> {
                    if (lines.lastOrNull() != "campo" && lines.lastOrNull() != "esperar 1") {
                        lines.add("campo")
                        lines.add("esperar 1")
                    }
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
}
