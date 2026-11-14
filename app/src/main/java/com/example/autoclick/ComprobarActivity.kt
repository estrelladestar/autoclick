package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/**
 * Comprobador de comentarios: carga los comentarios públicos de una publicación con Apify
 * (como los ve cualquiera sin iniciar sesión) y dice cuántos se ven de cada una de tus cuentas.
 * Si una cuenta escribió comentarios pero aquí salen 0, Instagram los está ocultando.
 */
class ComprobarActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var linkBox: EditText
    private lateinit var accountsBox: EditText
    private lateinit var maxBox: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var goBtn: Button
    @Volatile private var running = false
    @Volatile private var loaded = 0

    private val defaultAccounts = "cory_gm0 nuberosa56 del_mar190 amimanera6 mama.dedylan morenina.iscar comoyoquiero1 mimi88red"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        fun label(t: String, size: Float = 15f) = TextView(ctx).apply {
            text = t; textSize = size; setPadding(0, 28, 0, 0); root.addView(this)
        }

        label("🔍 Comprobador de comentarios", 20f)
        label(
            "Carga los comentarios de la publicación tal como los ve cualquier persona (sin tu sesión) " +
                "y te dice cuántos se ven de cada cuenta tuya. Si una cuenta comentó y aquí salen 0 o muy pocos, " +
                "Instagram los está ocultando."
        )

        label("Enlace de la publicación (Instagram o TikTok):")
        val firstLink = (0 until 5).map { (prefs.getString("link$it", "") ?: "").trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        linkBox = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString("comprobar_enlace", null) ?: firstLink)
            root.addView(this)
        }
        Button(ctx).apply {
            text = "Usar un enlace de Star App"
            setOnClickListener { pickLink() }
            root.addView(this)
        }

        label("Tus cuentas (separadas por espacios):")
        accountsBox = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setText(prefs.getString("comprobar_cuentas", null) ?: accountsFromScript(prefs) ?: defaultAccounts)
            root.addView(this)
        }

        label("Máximo de comentarios a cargar (más = tarda más y gasta más saldo de Apify):")
        maxBox = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("comprobar_max", 5000).toString())
            root.addView(this)
        }

        goBtn = Button(ctx).apply {
            text = "▶ Comprobar ahora"
            setOnClickListener { if (running) cancel() else start() }
            root.addView(this)
        }
        status = label("")
        results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; root.addView(this) }

        Button(ctx).apply {
            text = "📜 Ver comprobaciones anteriores"
            setOnClickListener { showHistory() }
            root.addView(this)
        }

        setContentView(ScrollView(ctx).apply { addView(root) })
        StarColors.watch(root)
        if (!Apify.hasToken()) status.text = "⚠️ Falta la clave de Apify (secreto APIFY_TOKEN en GitHub). Sin ella no se puede comprobar."
    }

    override fun onDestroy() {
        if (running) { Apify.cancelled = true; Apify.abort() }
        super.onDestroy()
    }

    /** Las cuentas de la línea «cuentas …» del guion de comentarios, si la hay. */
    private fun accountsFromScript(prefs: android.content.SharedPreferences): String? =
        (prefs.getString("script", "") ?: "").lines().map { it.trim() }
            .firstOrNull { it.lowercase().startsWith("cuentas ") }?.substring(8)?.trim()?.takeIf { it.isNotEmpty() }

    private fun pickLink() {
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val links = (0 until 5).map { (prefs.getString("link$it", "") ?: "").trim() }.filter { it.isNotEmpty() }
        if (links.isEmpty()) { toast("No tienes enlaces guardados en Star App"); return }
        AlertDialog.Builder(this).setTitle("Elige el enlace")
            .setItems(links.toTypedArray()) { _, i -> linkBox.setText(links[i]) }
            .show()
    }

    private fun parseAccounts(): List<String> =
        accountsBox.text.toString().split(Regex("[\\s,;]+"))
            .map { it.trim().removePrefix("@").lowercase() }.filter { it.isNotEmpty() }.distinct()

    private fun start() {
        val link = linkBox.text.toString().trim()
        val accounts = parseAccounts()
        val max = maxBox.text.toString().toIntOrNull()?.coerceIn(50, 20000) ?: 5000
        if (!Apify.hasToken()) { toast("Falta la clave de Apify (secreto APIFY_TOKEN en GitHub)"); return }
        if (!link.startsWith("http")) { toast("Pega el enlace de la publicación"); return }
        if (accounts.isEmpty()) { toast("Escribe al menos una cuenta"); return }
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putString("comprobar_enlace", link)
            .putString("comprobar_cuentas", accountsBox.text.toString().trim())
            .putInt("comprobar_max", max).apply()

        running = true
        goBtn.text = "■ Parar"
        results.removeAllViews()
        val t0 = System.currentTimeMillis()
        loaded = 0
        val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val sec = (System.currentTimeMillis() - t0) / 1000
                status.text = "⏳ Apify está trabajando… ${sec / 60} min ${sec % 60} s  ·  $loaded cargados\n" +
                    "Es normal que ponga 0 un rato: Apify abre la publicación y los comentarios suelen llegar casi al final. " +
                    "Suele tardar de 1 a 5 minutos. No cierres esta pantalla."
                main.postDelayed(this, 1000)
            }
        }
        main.post(tick)
        Thread {
            try {
                val all = Apify.fetch(link, max) { n -> loaded = n }
                main.post { running = false; showResult(link, accounts, all, max) }
            } catch (e: Exception) {
                main.post { running = false; status.text = if (e.message == "Cancelado") "Parado." else "❌ ${e.message}" }
            } finally {
                main.post { running = false; goBtn.text = "▶ Comprobar ahora" }
            }
        }.start()
    }

    private fun cancel() {
        Apify.cancelled = true
        Thread { Apify.abort() }.start()
        status.text = "Parando…"
    }

    private fun showResult(link: String, accounts: List<String>, all: JSONArray, max: Int) {
        // Cuántos comentarios se ven de cada cuenta, y los textos
        val count = LinkedHashMap<String, Int>()
        val texts = HashMap<String, MutableList<String>>()
        accounts.forEach { count[it] = 0; texts[it] = mutableListOf() }
        for (i in 0 until all.length()) {
            val row = all.optJSONArray(i) ?: continue
            val u = row.optString(0).trim().lowercase().removePrefix("@")
            if (count.containsKey(u)) {
                count[u] = count[u]!! + 1
                texts[u]!!.add(row.optString(1))
            }
        }

        val previous = lastCheck(link)
        val today = Macro.loadLog(this).optJSONObject(
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        )
        saveCheck(link, all.length(), count)

        val mine = count.values.sum()
        status.text = "✅ Se han cargado ${all.length()} comentarios de la publicación. De tus cuentas se ven $mine." +
            (if (all.length() >= max) "\n(Has llegado al máximo de $max: puede que haya más. Sube el número si quieres verlos todos.)" else "") +
            (if (all.length() in 1..60) "\n⚠️ Han salido pocos comentarios: si la publicación tiene muchos más, Apify no ha podido cargarlos todos y el resultado no es fiable." else "")

        for ((acc, n) in count) {
            val written = today?.optInt(acc, 0) ?: 0
            val before = previous?.optInt(acc, -1) ?: -1
            val line = StringBuilder("@$acc: se ven $n")
            if (written > 0) line.append("  ·  hoy Star App escribió $written (en todas las publicaciones)")
            if (before >= 0 && before != n) line.append("  ·  antes se veían $before" + if (n < before) " ⬇️ (han desaparecido ${before - n})" else "")
            val v = TextView(this).apply {
                text = (if (n == 0) "🔴 " else if (before > n) "🟠 " else "🟢 ") + line
                textSize = 16f
                setPadding(0, 20, 0, 20)
                setTextColor(if (n == 0) Color.rgb(200, 30, 30) else if (before > n) Color.rgb(220, 110, 0) else Color.rgb(20, 130, 60))
                setOnClickListener {
                    val t = texts[acc]!!
                    AlertDialog.Builder(this@ComprobarActivity).setTitle("@$acc — $n visibles")
                        .setMessage(if (t.isEmpty()) "No se ve ningún comentario de esta cuenta.\n\nPuede que Instagram los esté ocultando, que la cuenta esté limitada o que no haya comentado aquí." else t.joinToString("\n\n"))
                        .setPositiveButton("Cerrar", null).show()
                }
            }
            results.addView(v)
        }
        results.addView(TextView(this).apply {
            text = "Toca una cuenta para ver sus comentarios visibles.\n🔴 = no se ve ninguno · 🟠 = se ven menos que la última vez · 🟢 = se ven"
            textSize = 13f
            setPadding(0, 16, 0, 0)
        })
    }

    // ----- Historial: {"enlace": [{"fecha": "...", "total": 900, "cuentas": {"cory_gm0": 40}}, ...]} -----

    private fun loadHistory(): JSONObject = try {
        JSONObject(getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("comprobaciones", "{}") ?: "{}")
    } catch (e: Exception) { JSONObject() }

    private fun key(link: String) = link.substringBefore("?").trimEnd('/')

    private fun lastCheck(link: String): JSONObject? {
        val arr = loadHistory().optJSONArray(key(link)) ?: return null
        return if (arr.length() == 0) null else arr.optJSONObject(arr.length() - 1)?.optJSONObject("cuentas")
    }

    private fun saveCheck(link: String, total: Int, count: Map<String, Int>) {
        val h = loadHistory()
        val arr = h.optJSONArray(key(link)) ?: JSONArray().also { h.put(key(link), it) }
        val c = JSONObject(); count.forEach { (k, v) -> c.put(k, v) }
        arr.put(JSONObject()
            .put("fecha", java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.US).format(java.util.Date()))
            .put("total", total).put("cuentas", c))
        while (arr.length() > 10) arr.remove(0)
        // Guardar como mucho 20 publicaciones
        val keys = h.keys().asSequence().toList()
        if (keys.size > 20) keys.take(keys.size - 20).forEach { h.remove(it) }
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("comprobaciones", h.toString()).apply()
    }

    private fun showHistory() {
        val h = loadHistory()
        val keys = h.keys().asSequence().toList()
        if (keys.isEmpty()) { toast("Todavía no has hecho ninguna comprobación"); return }
        val sb = StringBuilder()
        for (k in keys.reversed()) {
            sb.append("🔗 ").append(k).append("\n")
            val arr = h.optJSONArray(k) ?: continue
            for (i in arr.length() - 1 downTo 0) {
                val o = arr.optJSONObject(i) ?: continue
                val c = o.optJSONObject("cuentas") ?: JSONObject()
                val parts = c.keys().asSequence().map { "$it ${c.optInt(it)}" }.joinToString(", ")
                sb.append("  ${o.optString("fecha")} (${o.optInt("total")} en total): $parts\n")
            }
            sb.append("\n")
        }
        AlertDialog.Builder(this).setTitle("Comprobaciones anteriores")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Cerrar", null)
            .setNegativeButton("Borrar todo") { _, _ ->
                getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().remove("comprobaciones").apply()
                toast("Historial borrado")
            }.show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
