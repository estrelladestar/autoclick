package com.example.autoclick

/** Reglas puras del guion (sin Android), para poder probarlas con pruebas automáticas. */
object ReglasGuion {
    private val PUBLICACION = Regex("/(p|reel|reels|tv)/([A-Za-z0-9_-]+)")

    /** Código de la publicación o reel dentro del enlace (null si no es una publicación). */
    fun idPublicacion(url: String): String? = PUBLICACION.find(url)?.groupValues?.get(2)

    /** Texto que se muestra para lo guardado en un enlace. */
    fun describirMenciones(n: Int, frase: Boolean): String = when {
        n == 0 -> "sin menciones (solo frase)"
        n == 1 -> "1 mención " + if (frase) "con frase" else "sin frase"
        else -> "$n menciones " + if (frase) "con frase" else "sin frase"
    }

    /** Un paso del guion (una línea) dicho con palabras normales, para que se vea qué hará. */
    fun pasoEnTexto(linea: String): String {
        val l = linea.trim()
        val op = l.substringBefore(" ").lowercase()
        val arg = l.substringAfter(" ", "").trim()
        return when {
            l.isEmpty() -> ""
            op == "enlace" -> "Abrir el enlace de la publicación"
            op == "esperar" -> "Esperar $arg segundos"
            op == "seguir" && arg.lowercase().startsWith("descrip") -> "Seguir las cuentas que salen en la descripción"
            op == "seguir" -> "Seguir a la cuenta de la publicación"
            op == "megusta" -> "Dar me gusta"
            op == "guardar" -> "Guardar la publicación"
            op == "repost" -> "Volver a publicar"
            op == "historia" -> "Subir la historia etiquetando a la tienda y a las cuentas"
            op == "historias" -> "Ver historias ($arg)"
            op == "imagen" -> "Elegir la foto ${arg.substringBefore(" ")}"
            op == "escribir" && arg.startsWith("solo") -> "Escribir solo una frase"
            op == "escribir" -> "Escribir el comentario: " + arg.replace("menciones ", "").replace("sinfrase", "sin frase")
            op == "cuenta" -> "Cambiar a la siguiente cuenta"
            op == "cuentas" -> "Cuentas en orden: $arg"
            op == "repetir" -> "Repetir $arg vuelta(s)"
            else -> l
        }
    }
}
