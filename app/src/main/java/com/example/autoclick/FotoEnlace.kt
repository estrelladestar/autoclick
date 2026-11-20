package com.example.autoclick

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Mini foto de la publicación de un enlace (la imagen que Instagram enseña al compartirlo),
 * para verla junto al enlace en la pantalla principal. Se guarda en el móvil para no volver a bajarla.
 */
object FotoEnlace {
    private val main = Handler(Looper.getMainLooper())
    private val memoria = ConcurrentHashMap<String, Bitmap>()
    private const val UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    /** Busca la foto del enlace y la devuelve en el hilo principal (null si no se encuentra). */
    fun cargar(ctx: Context, url: String, alVer: (Bitmap?) -> Unit) {
        val u = url.trim()
        if (!u.startsWith("http")) {
            alVer(null)
            return
        }
        memoria[u]?.let {
            alVer(it)
            return
        }
        val app = ctx.applicationContext
        Thread {
            val bmp = try { buscar(app, u) } catch (e: Exception) { null }
            if (bmp != null) memoria[u] = bmp
            main.post { alVer(bmp) }
        }.start()
    }

    private fun buscar(ctx: Context, url: String): Bitmap? {
        val f = File(ctx.filesDir, "fotos_enlaces/${url.hashCode()}.jpg")
        if (f.exists() && f.length() > 0) return decodificar(f.readBytes())
        val html = bajar(url, 400_000)?.let { String(it, Charsets.UTF_8) } ?: return null
        val imagen = ogImagen(html) ?: return null
        val bytes = bajar(imagen, 3_000_000) ?: return null
        val bmp = decodificar(bytes) ?: return null
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return bmp
    }

    /** La imagen de la etiqueta «og:image» de la página (la foto de la publicación). */
    private fun ogImagen(html: String): String? {
        val antes = Regex("""<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val despues = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE)
        val m = antes.find(html) ?: despues.find(html) ?: return null
        return m.groupValues[1].replace("&amp;", "&")
    }

    /** Descarga hasta «max» bytes de una dirección. */
    private fun bajar(url: String, max: Int): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
            if (c.responseCode !in 200..299) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { ins ->
                val buf = ByteArray(16384)
                while (out.size() < max) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** Lo reduce al tamaño de una miniatura para no gastar memoria. */
    private fun decodificar(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var muestra = 1
        while (o.outWidth / (muestra * 2) >= 160 && o.outHeight / (muestra * 2) >= 160) muestra *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = muestra })
    }
}
