package com.estrella.teclado

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import java.text.Normalizer

/** Convierte texto normal a las letras bonitas de cada estilo (á, é, ñ… se hacen con la letra bonita + su tilde). */
object Fuentes {
    fun convertir(texto: String, e: Estilo): String {
        if (e === ESTILOS[0]) return texto
        val nfd = Normalizer.normalize(texto, Normalizer.Form.NFD)
        val sb = StringBuilder()
        var i = 0
        while (i < nfd.length) {
            val cp = nfd.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in 'A'.code..'Z'.code -> sb.append(e.mayus[cp - 'A'.code]).append(e.marca)
                cp in 'a'.code..'z'.code -> sb.append(e.minus[cp - 'a'.code]).append(e.marca)
                cp in '0'.code..'9'.code -> sb.append(e.nums[cp - '0'.code]).append(e.marca)
                cp == ' '.code && e.marca.isNotEmpty() -> sb.append(' ').append(e.marca)
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }
}

/** Colores del teclado. arcoiris = cada columna de teclas de un color. */
class Tema(
    val nombre: String,
    val fondo1: Int, val fondo2: Int,
    val tecla: Int, val texto: Int,
    val especial: Int, val textoEspecial: Int,
    val acento: Int,
    val arcoiris: Boolean = false,
)

private fun c(s: String) = Color.parseColor(s)

val TEMAS: List<Tema> = listOf(
    Tema("Estrella", c("#5B2BB5"), c("#1E0A4A"), c("#7B4FD6"), c("#FFFFFF"), c("#3D1A8C"), c("#FFE58A"), c("#FFD23F")),
    Tema("Rosa", c("#FFC1DA"), c("#FF7EB3"), c("#FFFFFF"), c("#B0185C"), c("#FF9CC7"), c("#7A0E3E"), c("#FF3D8B")),
    Tema("Arcoíris", c("#2A2238"), c("#110C1A"), c("#FF5A6E"), c("#FFFFFF"), c("#3A3150"), c("#FFFFFF"), c("#FFFFFF"), arcoiris = true),
    Tema("Noche", c("#1B1F2A"), c("#0D0F14"), c("#2C3242"), c("#EAF0FF"), c("#1F2431"), c("#8FC3FF"), c("#4DA3FF")),
    Tema("Menta", c("#C9F7E4"), c("#7FDDB8"), c("#FFFFFF"), c("#0F6B4B"), c("#A8EBD0"), c("#0B4F37"), c("#13A36E")),
    Tema("Cielo", c("#BEE3FF"), c("#6FB6FF"), c("#FFFFFF"), c("#0D4E8C"), c("#A6D4FF"), c("#083763"), c("#1976D2")),
    Tema("Oro", c("#2A2010"), c("#100B03"), c("#3B2E14"), c("#FFD86B"), c("#241A08"), c("#FFC107"), c("#FFC107")),
    Tema("Fresa", c("#FF5A6E"), c("#C41E3A"), c("#FF8A98"), c("#FFFFFF"), c("#D63A50"), c("#FFF1A8"), c("#FFF1A8")),
    Tema("Lavanda", c("#E9DDFF"), c("#C3A8F5"), c("#FFFFFF"), c("#5B2BB5"), c("#D7C4FA"), c("#3A1488"), c("#8350E8")),
    Tema("Blanco", c("#EEF0F3"), c("#D9DDE3"), c("#FFFFFF"), c("#222222"), c("#C6CBD3"), c("#222222"), c("#6A32D0")),
)

/** Colores del modo arcoíris, uno por columna. */
val ARCOIRIS = intArrayOf(
    c("#FF4D6D"), c("#FF8A3D"), c("#FFC53D"), c("#7BD34F"), c("#2EC4B6"),
    c("#3A86FF"), c("#5E60CE"), c("#9D4EDD"), c("#E056FD"), c("#FF5FA2"),
)

/** Ajustes compartidos entre la app y el teclado. */
class Ajustes(ctx: Context) {
    val p: SharedPreferences = ctx.getSharedPreferences("teclado", Context.MODE_PRIVATE)
    var estilo: Int
        get() = p.getInt("estilo", 4).coerceIn(0, ESTILOS.size - 1)
        set(v) { p.edit().putInt("estilo", v).apply() }
    var tema: Int
        get() = p.getInt("tema", 0).coerceIn(0, TEMAS.size - 1)
        set(v) { p.edit().putInt("tema", v).apply() }
    /** 0 pequeño, 1 normal, 2 grande */
    var alto: Int
        get() = p.getInt("alto", 1).coerceIn(0, 2)
        set(v) { p.edit().putInt("alto", v).apply() }
    var vibrar: Boolean
        get() = p.getBoolean("vibrar", true)
        set(v) { p.edit().putBoolean("vibrar", v).apply() }
    var foto: Long
        get() = p.getLong("foto", 0L)  // 0 = sin foto; si no, la hora en que se puso (para recargarla)
        set(v) { p.edit().putLong("foto", v).apply() }
}
