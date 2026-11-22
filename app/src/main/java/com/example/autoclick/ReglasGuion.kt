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
}
