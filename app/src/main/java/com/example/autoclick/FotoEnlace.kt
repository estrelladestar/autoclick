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

    /** El archivo de la foto de un enlace (por el código de la publicación, así da igual lo que lleve detrás). */
    private fun archivo(ctx: Context, url: String): File {
        val id = Regex("/(p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(2) ?: url.trim().hashCode().toString()
        return File(ctx.filesDir, "fotos_enlaces/$id.jpg")
    }

    /** Guarda una foto elegida por ti para ese enlace. */
    fun guardarManual(ctx: Context, url: String, bytes: ByteArray): Boolean {
        val bmp = decodificar(bytes) ?: return false
        val f = archivo(ctx, url)
        f.parentFile?.mkdirs()
        // Se guarda pequeña, en JPG
        java.io.FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        memoria[url.trim()] = bmp
        return true
    }

    private fun buscar(ctx: Context, url: String): Bitmap? {
        val f = archivo(ctx, url)
        if (f.exists() && f.length() > 0) return decodificar(f.readBytes())
        val viejo = File(ctx.filesDir, "fotos_enlaces/${url.hashCode()}.jpg")
        if (viejo.exists() && viejo.length() > 0) return decodificar(viejo.readBytes())
        val id = Regex("/(p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(2)
        // Varias formas, porque Instagram a veces pide iniciar sesión:
        // 1) la página con el «navegador» de Facebook (Instagram le da la foto), 2) la página normal,
        // 3) la versión para insertar (embed), 4) la foto directa /media/
        val intentos = ArrayList<() -> ByteArray?>()
        intentos.add { bajar(url, 400_000, UA_FB)?.let { ogImagen(String(it, Charsets.UTF_8)) }?.let { bajar(it, 3_000_000) } }
        intentos.add { bajar(url, 400_000)?.let { ogImagen(String(it, Charsets.UTF_8)) }?.let { bajar(it, 3_000_000) } }
        if (id != null) {
            intentos.add {
                bajar("https://www.instagram.com/p/$id/embed/captioned/", 600_000)?.let { embedImagen(String(it, Charsets.UTF_8)) }?.let { bajar(it, 3_000_000) }
            }
            intentos.add { bajar("https://www.instagram.com/p/$id/media/?size=m", 3_000_000) }
        }
        for (paso in intentos) {
            val bytes = try { paso() } catch (e: Exception) { null } ?: continue
            val bmp = decodificar(bytes) ?: continue
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
            return bmp
        }
        return null
    }

    private const val UA_FB = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    /** En la página «embed», la foto va en la etiqueta con class «EmbeddedMediaImage». */
    private fun embedImagen(html: String): String? {
        val m = Regex("""class=["']EmbeddedMediaImage["'][^>]*src=["']([^"']+)["']""").find(html)
            ?: Regex("""src=["']([^"']+)["'][^>]*class=["']EmbeddedMediaImage["']""").find(html)
            ?: Regex(""""display_url"\s*:\s*"([^"]+)"""").find(html)
            ?: return ogImagen(html)
        return m.groupValues[1].replace("&amp;", "&").replace("\\u0026", "&").replace("\\/", "/")
    }

    /** La imagen de la etiqueta «og:image» de la página (la foto de la publicación). */
    private fun ogImagen(html: String): String? {
        val antes = Regex("""<meta[^>]+property=["']og:image["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val despues = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]*property=["']og:image["']""", RegexOption.IGNORE_CASE)
        val m = antes.find(html) ?: despues.find(html) ?: return null
        return m.groupValues[1].replace("&amp;", "&")
    }

    /** Descarga hasta «max» bytes de una dirección. */
    private fun bajar(url: String, max: Int, ua: String = UA): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
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
