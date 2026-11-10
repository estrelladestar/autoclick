package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.speech.RecognizerIntent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File

class CommentActivity : Activity() {
    /** Guion fijo: siempre está en «Guiones guardados», no se puede borrar y es el que pone «Restaurar». */
    private val fixedName = "⭐ Mi guion (fijo)"
    private val defaultScript = """cuentas cory_gm0 nuberosa56 del_mar190 amimanera6 mama.dedylan morenina.iscar comoyoquiero1 mimi88red
enlace
esperar 8
imagen 1 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
imagen 2 | 3-8
escribir menciones 2
esperar 3-8
imagen 3 | 3-8
cuenta siguiente
repetir 8"""

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var script: EditText
    private lateinit var box: EditText
    private lateinit var friendsBox: EditText
    private lateinit var mentionsN: Spinner
    private lateinit var phrasesBox: EditText
    private lateinit var phrasePos: Spinner
    private lateinit var phraseChance: EditText
    private lateinit var savedSpinner: Spinner
    private lateinit var askBox: EditText
    private lateinit var apiKeyBox: EditText
    private lateinit var imagesBox: LinearLayout
    private lateinit var statusBox: TextView
    private lateinit var startBtn: Button
    private val voiceCode = 77
    private val pickCode = 42
    private val exportCode = 91
    private val importCode = 92

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t
            setPadding(0, 24, 0, 0)
            root.addView(this)
        }
        fun multi(minL: Int, value: String) = EditText(ctx).apply {
            minLines = minL
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(value)
            root.addView(this)
        }

        Button(ctx).apply {
            text = "❓ Cómo se usa (instrucciones)"
            setOnClickListener { Help.show(this@CommentActivity) }
            root.addView(this)
        }

        // ---------- Estado y arranque rápido ----------
        statusBox = TextView(ctx).apply {
            textSize = 15f
            setPadding(0, 24, 0, 8)
            root.addView(this)
        }
        Button(ctx).apply {
            text = "▶ Iniciar"
            setOnClickListener { startBtn.performClick() }
            root.addView(this)
        }
        val toolsBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this)
        }
        val third = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
        Button(ctx).apply {
            text = "💾 Guardar copia"
            setOnClickListener { exportBackup() }
            toolsBar.addView(this, third)
        }
        Button(ctx).apply {
            text = "📂 Cargar copia"
            setOnClickListener { importBackup() }
            toolsBar.addView(this, LinearLayout.LayoutParams(third))
        }
        Button(ctx).apply {
            text = "📊 Registro"
            setOnClickListener { showLog() }
            toolsBar.addView(this, LinearLayout.LayoutParams(third))
        }

        // ---------- Asistente ----------
        label(
            "🤖 ASISTENTE: dime con tus palabras qué quieres que haga (escribe o pulsa 🎤) y te creo el guion. Por ejemplo:\n" +
            "«Abre el enlace, pulsa la imagen 1, luego la imagen 2, escribe un comentario copiado con 2 menciones, " +
            "pulsa otra imagen y repite 5 veces»\n" +
            "Tu frase se queda guardada: cámbiala o añade lo que falte (también con 🎤) y vuelve a pulsar «Crear guion». " +
            "Antes de usar el guion también puedes corregirlo a mano en la ventana que aparece."
        )
        askBox = multi(3, prefs.getString("ask", "") ?: "")
        askBox.hint = "Escribe aquí lo que quieres que haga…"
        val askBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this)
        }
        Button(ctx).apply {
            text = "🎤 Hablar"
            setOnClickListener { startVoice() }
            askBar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        Button(ctx).apply {
            text = "Crear guion"
            setOnClickListener { createFromAsk() }
            askBar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        label(
            "Clave API de Anthropic (opcional). Con clave, el asistente usa una IA y entiende frases libres (necesita internet y se paga aparte, " +
            "muy poco por frase). Sin clave, o si no hay internet, entiende frases sencillas sin IA. Solo se envía tu frase, " +
            "nunca tus comentarios, menciones ni enlaces:"
        )
        apiKeyBox = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "sk-ant-…"
            setText(prefs.getString("apiKey", "") ?: "")
            root.addView(this)
        }

        // ---------- Imágenes ----------
        label(
            "🖼 IMÁGENES que el guion puede pulsar. En el guion son «imagen 1», «imagen 2»… en este orden. " +
            "Sube una captura recortada del botón que quieres pulsar:"
        )
        imagesBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            root.addView(this)
        }
        Button(ctx).apply {
            text = "+ Añadir imagen"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, pickCode)
            }
            root.addView(this)
        }
        refreshImages()
        label(
            "GUION (un paso por línea):\n" +
            "• esperar 5 → espera 5 segundos (esperar 5-10 = al azar entre 5 y 10)\n" +
            "• tocar Texto → toca el botón o texto que diga así\n" +
            "• tocar 500,1200 → toca esa posición de la pantalla\n" +
            "• tocar 90%,95% → toca esa zona (porcentaje del ancho y del alto; 90%,95% = abajo a la derecha)\n" +
            "• mantener 90%,95% → pulsación larga en esa zona\n" +
            "• atrás → como el botón atrás del móvil (cierra el teclado o los comentarios)\n" +
            "• cuenta NOMBRE → cambia a esa cuenta de Instagram (cierra los comentarios, abre la lista de cuentas y la pulsa)\n" +
            "• cuentas a b c → tus cuentas en orden; con «cuenta siguiente» + «repetir» cambia a la siguiente en cada vuelta\n" +
            "• mirar → baja y sube por los comentarios un rato (con «mirar azar», solo a veces)\n" +
            "• cuenta siguiente (sin línea «cuentas») → va pasando por TODAS las cuentas que tengas en Instagram\n" +
            "• descanso 10-20 → pausa al azar de 10 a 20 minutos\n" +
            "• inicio → sale a la pantalla principal y baja y sube por las publicaciones (con «inicio azar», solo a veces); después pon «enlace»\n" +
            "• limite 20 → cada cuenta hace como mucho 20 comentarios al día; luego pasa a la siguiente\n" +
            "• hora 21:30 → espera hasta esa hora para seguir (deja el móvil encendido)\n" +
            "• escribir lista → pone el siguiente comentario de tu lista\n" +
            "• escribir azar → pone un comentario de tu lista al azar\n" +
            "• escribir menciones → pone @amigos al azar de tu cuadro de menciones (cuántos: selector de abajo), con una frase tuya si quieres\n" +
            "• escribir menciones 3 → igual, con 3 menciones (de 1 a 5)\n" +
            "• escribir copiados menciones 2 → un comentario copiado al azar (de TUS COMENTARIOS) + 2 menciones\n" +
            "• enlace → abre el enlace marcado en el Etiquetador (si no hay, el siguiente de los guardados); enlace 2 = el enlace 2\n" +
            "• imagen 1 → busca en pantalla la imagen 1 de la app y la pulsa (imagen 2 = otra)\n" +
            "• escribir Hola → pone ese texto fijo\n" +
            "• Añade | 30 al final de cualquier línea para esperar 30 s después (| 30-60 = al azar entre 30 y 60)\n" +
            "• repetir 10 → repite todo el guion 10 veces\n" +
            "• # al inicio = nota (se ignora)"
        )

        // ---------- Guiones guardados ----------
        label("GUIONES GUARDADOS (por ejemplo uno para Instagram y otro para TikTok):")
        savedSpinner = Spinner(ctx)
        root.addView(savedSpinner)
        refreshSaved(null)
        val savedBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            root.addView(this)
        }
        Button(ctx).apply {
            text = "Guardar como…"
            setOnClickListener { askSaveName() }
            savedBar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        Button(ctx).apply {
            text = "Borrar este"
            setOnClickListener { deleteSaved() }
            savedBar.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        script = EditText(ctx).apply {
            minLines = 8
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("script", defaultScript))
            root.addView(this)
        }
        // El selector carga el guion elegido (se ignora la primera llamada automática)
        var first = true
        savedSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (first) { first = false; return }
                if (pos > 0) {
                    val name = savedSpinner.selectedItem.toString()
                    val text = if (name == fixedName) defaultScript else loadScripts().optString(name, "")
                    if (text.isNotEmpty()) script.setText(text)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        Button(ctx).apply {
            text = "Poner mi guion fijo"
            setOnClickListener { script.setText(defaultScript) }
            root.addView(this)
        }

        label("TUS COMENTARIOS, uno por línea (se rellenan solos al generar la lista en el Etiquetador; también puedes pegarlos aquí):")
        box = multi(6, prefs.getString("comments", "") ?: "")

        label("TUS MENCIONES, una por línea (se rellenan solas desde el cuadro «Menciones» del Etiquetador; también puedes escribirlas aquí):")
        friendsBox = multi(4, prefs.getString("friends", "") ?: "")

        label("Menciones por comentario (1 a 5), para «escribir menciones»:")
        mentionsN = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "3", "4", "5"))
            setSelection((prefs.getInt("mentionsN", 2) - 1).coerceIn(0, 4))
            root.addView(this)
        }

        label("TUS FRASES (opcional), una por línea. Con «escribir menciones» se elige una al azar y se junta con las menciones. Déjalo vacío para poner solo menciones:")
        phrasesBox = multi(3, prefs.getString("phrases", "") ?: "")
        label("Dónde va la frase:")
        phrasePos = Spinner(ctx).apply {
            adapter = ArrayAdapter(
                ctx, android.R.layout.simple_spinner_dropdown_item,
                listOf("Delante de las menciones", "Detrás de las menciones", "Al azar (a veces delante, a veces detrás)")
            )
            setSelection(prefs.getInt("phrasePos", 0).coerceIn(0, 2))
            root.addView(this)
        }
        label("Probabilidad de poner frase (0 a 100 %):")
        phraseChance = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("phraseChance", 100).toString())
            root.addView(this)
        }

        label(
            "Límite de seguridad: al menos 10 s entre un comentario y el siguiente. " +
            "Si aparece un aviso de bloqueo de Instagram, el guion se para solo y el móvil vibra."
        )

        startBtn = Button(ctx).apply {
            text = "Iniciar"
            setOnClickListener {
                val list = box.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val s = ClickService.instance
                when {
                    s == null -> toast("Primero activa el servicio de accesibilidad de Star App")
                    else -> {
                        saveAll()
                        val missing = problems(script.text.toString())
                        if (missing.isEmpty()) {
                            startScript(s, list)
                        } else {
                            AlertDialog.Builder(this@CommentActivity)
                                .setTitle("Antes de empezar, falta esto")
                                .setMessage("• " + missing.joinToString("\n\n• "))
                                .setPositiveButton("Iniciar igualmente") { _, _ -> startScript(s, list) }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        }
                    }
                }
            }
            root.addView(this)
        }
        refreshStatus()
        setContentView(ScrollView(ctx).apply { addView(root) })
        StarColors.watch(root)
    }

    override fun onPause() {
        super.onPause()
        saveAll()
    }

    override fun onResume() {
        super.onResume()
        // El Etiquetador puede haber cambiado los comentarios o las menciones mientras esta pantalla estaba en segundo plano.
        val c = prefs.getString("comments", "") ?: ""
        if (box.text.toString() != c) box.setText(c)
        val f = prefs.getString("friends", "") ?: ""
        if (friendsBox.text.toString() != f) friendsBox.setText(f)
        refreshImages()
        refreshStatus()
    }

    private fun saveAll() {
        prefs.edit()
            .putString("script", script.text.toString())
            .putString("comments", box.text.toString())
            .putString("friends", friendsBox.text.toString())
            .putInt("mentionsN", mentionsN.selectedItemPosition + 1)
            .putString("phrases", phrasesBox.text.toString())
            .putInt("phrasePos", phrasePos.selectedItemPosition)
            .putInt("phraseChance", (phraseChance.text.toString().toIntOrNull() ?: 100).coerceIn(0, 100))
            .putString("ask", askBox.text.toString())
            .putString("apiKey", apiKeyBox.text.toString().trim())
            .apply()
    }

    // ---------- Asistente ----------
    private fun startVoice() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime qué quieres que haga")
        try {
            startActivityForResult(i, voiceCode)
        } catch (e: ActivityNotFoundException) {
            toast("Tu móvil no tiene reconocimiento de voz. Escribe la frase en el cuadro.")
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == voiceCode && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            val old = askBox.text.toString().trim()
            askBox.setText(if (old.isEmpty()) said else "$old. $said")
            askBox.setSelection(askBox.text.length)
        }
        if (requestCode == pickCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            addImage(uri)
        }
        if (requestCode == exportCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            writeBackup(uri)
        }
        if (requestCode == importCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            readBackup(uri)
        }
    }

    // ---------- Copia de seguridad ----------
    /** Lo que no se copia: las imágenes (hay que hacerlas en cada móvil) y el registro. */
    private val notInBackup = setOf("steps", "registro")

    private fun exportBackup() {
        saveAll()
        val name = "StarApp-copia-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        try { startActivityForResult(i, exportCode) } catch (e: Exception) { toast("No se pudo abrir el selector de archivos") }
    }

    private fun writeBackup(uri: android.net.Uri) {
        try {
            val o = org.json.JSONObject()
            o.put("app", "StarApp")
            val data = org.json.JSONObject()
            for ((k, v) in prefs.all) {
                if (k in notInBackup || v == null) continue
                val t = when (v) {
                    is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; else -> null
                } ?: continue
                data.put(k, org.json.JSONObject().put("t", t).put("v", v))
            }
            o.put("datos", data)
            contentResolver.openOutputStream(uri)?.use { it.write(o.toString(1).toByteArray()) }
            toast("Copia guardada. Pásala al otro móvil y allí pulsa «📂 Cargar copia».")
        } catch (e: Exception) {
            toast("No se pudo guardar la copia: ${e.message}")
        }
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try { startActivityForResult(i, importCode) } catch (e: Exception) { toast("No se pudo abrir el selector de archivos") }
    }

    private fun readBackup(uri: android.net.Uri) {
        try {
            val txt = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
            val o = org.json.JSONObject(txt)
            val data = o.optJSONObject("datos")
            if (o.optString("app") != "StarApp" || data == null) {
                toast("Ese archivo no es una copia de Star App")
                return
            }
            AlertDialog.Builder(this)
                .setTitle("Cargar copia")
                .setMessage("Se cambiarán tus menciones, comentarios, guiones, enlaces y ajustes por los de la copia. Las imágenes no se tocan. ¿Seguir?")
                .setPositiveButton("Cargar") { _, _ ->
                    val e = prefs.edit()
                    for (k in data.keys()) {
                        if (k in notInBackup) continue
                        val item = data.optJSONObject(k) ?: continue
                        when (item.optString("t")) {
                            "b" -> e.putBoolean(k, item.optBoolean("v"))
                            "i" -> e.putInt(k, item.optInt("v"))
                            "l" -> e.putLong(k, item.optLong("v"))
                            "f" -> e.putFloat(k, item.optDouble("v").toFloat())
                            "s" -> e.putString(k, item.optString("v"))
                        }
                    }
                    e.commit()
                    toast("Copia cargada. Las imágenes tienes que hacerlas en este móvil.")
                    recreate()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } catch (e: Exception) {
            toast("No se pudo leer la copia: ${e.message}")
        }
    }

    // ---------- Registro ----------
    private fun showLog() {
        val log = Macro.loadLog(this)
        val days = log.keys().asSequence().toList().sortedDescending().take(7)
        val sb = StringBuilder()
        if (days.isEmpty()) sb.append("Todavía no hay comentarios registrados.")
        for (d in days) {
            val day = log.optJSONObject(d) ?: continue
            var total = 0
            val lines = ArrayList<Pair<String, Int>>()
            for (k in day.keys()) { val n = day.optInt(k); total += n; lines.add(Pair(k, n)) }
            sb.append("📅 ").append(d).append(" — ").append(total).append(" comentarios\n")
            for ((k, n) in lines.sortedByDescending { it.second }) sb.append("   • ").append(k).append(": ").append(n).append("\n")
            sb.append("\n")
        }
        AlertDialog.Builder(this)
            .setTitle("📊 Comentarios por cuenta (últimos 7 días)")
            .setMessage(sb.toString().trim())
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar registro") { _, _ ->
                prefs.edit().remove("registro").apply()
                toast("Registro borrado")
            }
            .show()
    }

    /** Resumen de lo que está conectado y listo, y de lo que falta. */
    private fun refreshStatus() {
        val svc = ClickService.instance != null
        val images = StepStore.load(this).count { it.type == "image" }
        val links = (0 until 5).count { (prefs.getString("link$it", "") ?: "").trim().isNotEmpty() }
        val marked = (prefs.getString("linkSelUrl", "") ?: "").trim().isNotEmpty()
        val comments = box.text.toString().lines().count { it.trim().isNotEmpty() }
        val friends = friendsBox.text.toString().lines().count { it.trim().isNotEmpty() }
        val steps = script.text.toString().lines().map { it.trim() }
            .count { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("repetir") }
        val blockers = ArrayList<String>()
        if (!svc) blockers.add("Activa el servicio de accesibilidad de Star App (pantalla principal, botón 1).")
        blockers.addAll(problems(script.text.toString()))
        fun mark(ok: Boolean) = if (ok) "✅" else "➖"
        val sb = StringBuilder()
        sb.append(if (blockers.isEmpty()) "✅ TODO LISTO PARA INICIAR ($steps pasos)" else "⚠ FALTA ALGO ANTES DE INICIAR")
        sb.append("\n").append(if (svc) "✅" else "❌").append(" Servicio de accesibilidad: ").append(if (svc) "activo" else "desactivado")
        sb.append("\n").append(mark(links > 0 || marked)).append(" Enlaces: ")
            .append(if (marked) "uno marcado en el Etiquetador, " else "").append(links).append(" guardado(s)")
        sb.append("\n").append(mark(images > 0)).append(" Imágenes: ").append(images)
        sb.append("\n").append(mark(comments > 0)).append(" Comentarios: ").append(comments)
        sb.append("\n").append(mark(friends > 0)).append(" Menciones: ").append(friends)
        if (blockers.isNotEmpty()) sb.append("\n\nFalta:\n• ").append(blockers.joinToString("\n• "))
        statusBox.text = sb.toString()
    }

    private fun startScript(s: ClickService, list: List<String>) {
        saveAll()
        Macro.start(s, script.text.toString(), list)
        moveTaskToBack(true)
    }

    /** Cosas que faltan para que el guion funcione (vacío si todo está listo). */
    private fun problems(scriptText: String): List<String> {
        var maxImage = 0
        var linkNo = 0
        var usesLink = false
        var usesComments = false
        var usesMentions = false
        for (raw in scriptText.lines()) {
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val bar = line.lastIndexOf('|')
            if (bar >= 0) line = line.substring(0, bar).trim()
            val sp = line.indexOf(' ')
            val op = (if (sp < 0) line else line.substring(0, sp)).lowercase()
            val arg = if (sp < 0) "" else line.substring(sp + 1).trim().lowercase()
            when (op) {
                "imagen" -> maxImage = maxOf(maxImage, arg.toIntOrNull() ?: 1)
                "enlace" -> {
                    usesLink = true
                    linkNo = maxOf(linkNo, arg.toIntOrNull() ?: 0)
                }
                "escribir" -> {
                    if (arg == "azar" || arg == "lista" || arg.startsWith("copiado")) usesComments = true
                    if (arg.startsWith("menciones") || (arg.startsWith("copiado") && arg.contains("mencion"))) usesMentions = true
                }
            }
        }
        val out = ArrayList<String>()
        val images = StepStore.load(this).count { it.type == "image" }
        if (maxImage > images) {
            out.add(
                if (images == 0) "El guion usa imágenes, pero no hay ninguna subida. Añádelas en el panel «Imágenes»."
                else "El guion usa la imagen $maxImage, pero solo tienes $images subida(s). Añade las que falten en el panel «Imágenes»."
            )
        }
        if (usesLink) {
            val links = (0 until 5).map { (prefs.getString("link$it", "") ?: "").trim() }
            val marked = (prefs.getString("linkSelUrl", "") ?: "").trim()
            if (linkNo > 0) {
                if (links.getOrNull(linkNo - 1).isNullOrEmpty()) out.add("El guion abre el enlace $linkNo, pero está vacío.")
            } else if (marked.isEmpty() && links.all { it.isEmpty() }) {
                out.add("El guion abre un enlace, pero no hay ninguno. Ponlo en «Enlaces del sorteo» del Etiquetador o en «Enlace 1» de la pantalla principal.")
            }
        }
        if (usesComments && box.text.toString().lines().none { it.trim().isNotEmpty() }) {
            out.add("El guion escribe comentarios copiados, pero «TUS COMENTARIOS» está vacío (se rellena al generar la lista en el Etiquetador).")
        }
        if (usesMentions && friendsBox.text.toString().lines().none { it.trim().isNotEmpty() }) {
            out.add("El guion escribe menciones, pero la lista de menciones está vacía (escríbelas en el Etiquetador).")
        }
        for (bad in Assistant.badLines(scriptText)) out.add("La app no entiende la línea: $bad")
        return out
    }

    // ---------- Imágenes ----------
    private fun refreshImages() {
        imagesBox.removeAllViews()
        val imgs = StepStore.load(this).filter { it.type == "image" }
        if (imgs.isEmpty()) {
            imagesBox.addView(TextView(this).apply { text = "(Aún no hay imágenes)" })
            return
        }
        imgs.forEachIndexed { n, st ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val thumb = BitmapFactory.decodeFile(File(filesDir, st.value).path, opts)
            row.addView(ImageView(this).apply {
                adjustViewBounds = true
                maxHeight = 140
                maxWidth = 280
                if (thumb != null) setImageBitmap(thumb)
            })
            row.addView(
                TextView(this).apply {
                    text = "  Imagen " + (n + 1)
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(Button(this).apply {
                text = "✕"
                setOnClickListener { confirmDeleteImage(st, n + 1) }
            })
            imagesBox.addView(row)
        }
    }

    private fun confirmDeleteImage(st: Step, number: Int) {
        AlertDialog.Builder(this)
            .setTitle("Borrar la imagen $number")
            .setMessage("Las imágenes siguientes pasarán a tener un número menos. Revisa tu guion.")
            .setPositiveButton("Borrar") { _, _ ->
                val all = StepStore.load(this)
                all.removeAll { it.id == st.id }
                File(filesDir, st.value).delete()
                StepStore.save(this, all)
                refreshImages()
                refreshStatus()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addImage(uri: android.net.Uri) {
        val id = System.currentTimeMillis()
        val name = "img_" + id + ".png"
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                File(filesDir, name).outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            toast("No se pudo leer la imagen")
            return
        }
        val all = StepStore.load(this)
        all.add(Step(id, "image", name))
        StepStore.save(this, all)
        refreshImages()
        refreshStatus()
        toast("Añadida como imagen " + all.count { it.type == "image" })
    }

    private fun createFromAsk() {
        val request = askBox.text.toString().trim()
        if (request.isEmpty()) {
            toast("Escribe o di primero lo que quieres que haga")
            return
        }
        val local = Assistant.interpret(request)
        val key = apiKeyBox.text.toString().trim()
        if (key.isEmpty()) {
            useLocal(local, "")
            return
        }
        toast("Pensando… un momento")
        Assistant.askAI(key, request) { script, error ->
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (script != null) {
                    showProposal(script, "Lo ha creado la IA. Revísalo antes de usarlo.")
                } else {
                    useLocal(local, "No he podido usar la IA (" + (error ?: "sin conexión") + "). ")
                }
            }
        }
    }

    /** Usa el intérprete sin IA. «prefix» explica por qué, si venimos de un fallo de la IA. */
    private fun useLocal(local: Assistant.Result, prefix: String) {
        if (local.script != null) {
            val extra = if (local.notes.isEmpty()) "" else "\n\n" + local.notes.joinToString("\n\n")
            showProposal(local.script, prefix + "Lo he entendido sin usar IA." + extra)
            return
        }
        val what = if (local.unknown.isEmpty()) "" else "\n\nNo he entendido esta parte: «" + local.unknown.joinToString("», «") + "»"
        AlertDialog.Builder(this)
            .setTitle("No lo he entendido del todo")
            .setMessage(
                prefix + "Prueba con frases más sencillas, una acción detrás de otra. Por ejemplo: «abre el enlace, pulsa la imagen 1, " +
                "escribe un comentario copiado con 2 menciones, pulsa la imagen 2, repite 3 veces»." + what
            )
            .setPositiveButton("Vale", null)
            .show()
    }

    private fun showProposal(text: String, note: String) {
        val missing = problems(text)
        val fullNote = if (missing.isEmpty()) note else note + "\n\nOjo, antes de iniciar:\n• " + missing.joinToString("\n• ")
        val ed = EditText(this)
        ed.setText(text)
        ed.typeface = Typeface.MONOSPACE
        ed.textSize = 14f
        ed.gravity = Gravity.TOP
        ed.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ed.setPadding(48, 24, 48, 24)
        AlertDialog.Builder(this)
            .setTitle("Guion creado (puedes cambiarlo aquí)")
            .setMessage(fullNote)
            .setView(ScrollView(this).apply { addView(ed) })
            .setPositiveButton("Usar este guion") { _, _ ->
                script.setText(ed.text.toString())
                refreshStatus()
                toast("Guion puesto. Comprueba tus comentarios y menciones y pulsa Iniciar.")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Guiones guardados ----------
    private fun loadScripts(): JSONObject =
        try { JSONObject(prefs.getString("scripts", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun refreshSaved(select: String?) {
        val names = ArrayList<String>()
        names.add("— elegir un guion guardado —")
        names.add(fixedName)
        val it = loadScripts().keys()
        while (it.hasNext()) { val n = it.next(); if (n != fixedName) names.add(n) }
        savedSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        if (select != null) savedSpinner.setSelection(names.indexOf(select).coerceAtLeast(0))
    }

    private fun askSaveName() {
        val input = EditText(this)
        input.hint = "Nombre (por ejemplo Instagram)"
        AlertDialog.Builder(this)
            .setTitle("Guardar guion")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim()
                if (name == fixedName) {
                    toast("Ese nombre es el del guion fijo. Elige otro nombre.")
                } else if (name.isNotEmpty()) {
                    val all = loadScripts()
                    all.put(name, script.text.toString())
                    prefs.edit().putString("scripts", all.toString()).apply()
                    refreshSaved(name)
                    toast("Guion «$name» guardado")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSaved() {
        if (savedSpinner.selectedItemPosition <= 0) {
            toast("Elige primero un guion guardado")
            return
        }
        val name = savedSpinner.selectedItem.toString()
        if (name == fixedName) {
            toast("El guion fijo no se puede borrar")
            return
        }
        val all = loadScripts()
        all.remove(name)
        prefs.edit().putString("scripts", all.toString()).apply()
        refreshSaved(null)
        toast("Guion «$name» borrado")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
