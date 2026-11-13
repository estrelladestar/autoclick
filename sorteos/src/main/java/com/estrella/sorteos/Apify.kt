package com.estrella.sorteos

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Carga TODOS los comentarios de una publicación de golpe con Apify (sin abrir Instagram/TikTok).
 * Instagram: robots fetch_cat, easyapi y el oficial de Apify (se usa el que más traiga); TikTok: «clockworks/tiktok-comments-scraper».
 * La clave sale del secreto APIFY_TOKEN de GitHub (no está en el código).
 */
object Apify {
    private const val API = "https://api.apify.com/v2"
    @Volatile var cancelled = false
    @Volatile private var runId: String? = null

    fun hasToken() = BuildConfig.APIFY_TOKEN.isNotEmpty()
    private fun tk() = "token=" + URLEncoder.encode(BuildConfig.APIFY_TOKEN, "UTF-8")

    private fun http(method: String, url: String, body: String? = null): JSONObject? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 60000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw Exception(errorText(code, txt))
        return if (txt.trim().startsWith("{")) JSONObject(txt) else JSONObject().put("array", JSONArray(txt))
    }

    private fun errorText(code: Int, txt: String): String {
        val msg = try { JSONObject(txt).optJSONObject("error")?.optString("message") ?: "" } catch (e: Exception) { "" }
        return when (code) {
            401 -> "La clave de Apify no es válida"
            402 -> "No queda saldo en Apify (revisa tu cuenta o el límite de gasto)"
            403 -> "Apify no deja usar ese robot con esta cuenta: $msg"
            else -> "Error de Apify ($code) $msg".trim()
        }
    }

    /**
     * Pide los comentarios. Llamar en un hilo aparte (tarda de segundos a algunos minutos).
     * Instagram: se prueban varios robots en orden y se queda el que más comentarios traiga
     * (algunos solo devuelven unos pocos sin sesión). progress(n) = comentarios cargados.
     * Devuelve [[usuario, comentario], ...].
     */
    fun fetch(postUrl: String, max: Int, progress: (Int) -> Unit): JSONArray {
        cancelled = false
        if (postUrl.contains("tiktok", true)) {
            return runActor("clockworks~tiktok-comments-scraper",
                JSONObject().put("postURLs", JSONArray().put(postUrl)).put("commentsPerPost", max).put("maxRepliesPerComment", 0), progress)
        }
        val robots = listOf(
            "fetch_cat~instagram-comments-scraper" to JSONObject()
                .put("startUrls", JSONArray().put(JSONObject().put("url", postUrl)))
                .put("maxItems", max).put("maxLoadMoreAttempts", 5000),
            "easyapi~instagram-comments-scraper" to JSONObject()
                .put("postUrls", JSONArray().put(postUrl)).put("maxItems", max),
            "apify~instagram-comment-scraper" to JSONObject()
                .put("directUrls", JSONArray().put(postUrl)).put("resultsLimit", max)
        )
        var best = JSONArray()
        var lastError: Exception? = null
        for ((actor, input) in robots) {
            if (cancelled) throw Exception("Cancelado")
            try {
                val r = runActor(actor, input) { n -> progress(maxOf(n, best.length())) }
                if (r.length() > best.length()) best = r
                // Si ya trae casi todo lo pedido, o hay muchos, no hace falta probar otro robot
                if (best.length() >= max - 1 || best.length() >= 100) break
            } catch (e: Exception) {
                if (e.message == "Cancelado") throw e
                lastError = e
            }
        }
        if (best.length() == 0 && lastError != null) throw lastError
        return best
    }

    private fun runActor(actor: String, input: JSONObject, progress: (Int) -> Unit): JSONArray {
        val run = http("POST", "$API/acts/$actor/runs?${tk()}", input.toString())!!.getJSONObject("data")
        val id = run.getString("id"); runId = id
        val dataset = run.getString("defaultDatasetId")

        // Esperar a que termine (máx. 15 minutos)
        val t0 = System.currentTimeMillis()
        while (true) {
            if (cancelled) { abort(); throw Exception("Cancelado") }
            Thread.sleep(3000)
            val st = http("GET", "$API/actor-runs/$id?${tk()}")!!.getJSONObject("data").optString("status")
            try { progress(http("GET", "$API/datasets/$dataset?${tk()}")!!.getJSONObject("data").optInt("itemCount")) } catch (e: Exception) {}
            if (st == "SUCCEEDED") break
            if (st == "FAILED" || st == "ABORTED" || st == "TIMED-OUT") throw Exception("Apify no pudo leer la publicación ($st). ¿El enlace es correcto y la cuenta es pública?")
            if (System.currentTimeMillis() - t0 > 15 * 60_000L) { abort(); throw Exception("Apify está tardando demasiado") }
        }
        runId = null

        // Descargar los comentarios, de 1.000 en 1.000 (sirve para los distintos robots)
        val out = JSONArray()
        var offset = 0
        while (true) {
            val arr = http("GET", "$API/datasets/$dataset/items?${tk()}&clean=true&format=json&offset=$offset&limit=1000")!!.getJSONArray("array")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.has("error") && !o.has("text") && !o.has("message")) continue
                val u = firstOf(o, "ownerUsername", "uniqueId", "username", "authorUsername")
                    ?: o.optJSONObject("owner")?.optString("username")?.takeIf { it.isNotBlank() }
                    ?: o.optJSONObject("user")?.let { firstOf(it, "username", "uniqueId") } ?: continue
                val t = firstOf(o, "text", "message", "comment") ?: ""
                out.put(JSONArray().put(u).put(t))
            }
            if (arr.length() < 1000) break
            offset += 1000
            progress(out.length())
        }
        return out
    }

    private fun firstOf(o: JSONObject, vararg keys: String): String? {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotBlank() && v != "null") return v }
        return null
    }

    fun abort() {
        val id = runId ?: return
        runId = null
        try { http("POST", "$API/actor-runs/$id/abort?${tk()}") } catch (e: Exception) {}
    }
}
