package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.speech.RecognizerIntent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

class CommentActivity : Activity() {
    private val defaultScript = """# Cada línea es un paso. Lo que va después de | son segundos de espera extra.
# Abre el siguiente enlace de la app, espera a que cargue,
# abre la caja de comentarios, escribe una frase y @amigos al azar
# (lo eliges abajo) y pulsa la imagen 1 (la de la lista de pasos de la app).
# | 30-60 = espera un tiempo al azar entre 30 y 60 segundos.
enlace
esperar 8
campo
esperar 1
escribir menciones
esperar 1.5
imagen 1 | 30-60
repetir 10"""

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
    private val voiceCode = 77

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
        label(
            "GUION (un paso por línea):\n" +
            "• esperar 5 → espera 5 segundos (esperar 5-10 = al azar entre 5 y 10)\n" +
            "• tocar Texto → toca el botón o texto que diga así\n" +
            "• tocar 500,1200 → toca esa posición de la pantalla\n" +
            "• tocar 90%,95% → toca esa zona (porcentaje del ancho y del alto; 90%,95% = abajo a la derecha)\n" +
            "• mantener 90%,95% → pulsación larga en esa zona\n" +
            "• escribir lista → pone el siguiente comentario de tu lista\n" +
            "• escribir azar → pone un comentario de tu lista al azar\n" +
            "• escribir menciones → pone @amigos al azar de tu cuadro de menciones (cuántos: selector de abajo), con una frase tuya si quieres\n" +
            "• escribir menciones 3 → igual, con 3 menciones (de 1 a 5)\n" +
            "• escribir copiados menciones 2 → un comentario copiado al azar (de TUS COMENTARIOS) + 2 menciones\n" +
            "• enlace → abre el enlace marcado en el Etiquetador (si no hay, el siguiente de los guardados); enlace 2 = el enlace 2\n" +
            "• imagen 1 → busca en pantalla la imagen 1 de la app y la pulsa (imagen 2 = otra)\n" +
            "• escribir Hola → pone ese texto fijo\n" +
            "• campo → selecciona la caja de texto\n" +
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
                    val text = loadScripts().optString(name, "")
                    if (text.isNotEmpty()) script.setText(text)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        Button(ctx).apply {
            text = "Restaurar guion de ejemplo"
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
            "Límites de seguridad: máximo 50 comentarios por tanda y al menos 10 s entre un comentario y el siguiente. " +
            "Si aparece un aviso de bloqueo de Instagram, el guion se para solo y el móvil vibra."
        )

        Button(ctx).apply {
            text = "Iniciar"
            setOnClickListener {
                val list = box.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val s = ClickService.instance
                when {
                    s == null -> toast("Primero activa el servicio de accesibilidad de AutoClick Imagen")
                    else -> {
                        saveAll()
                        Macro.start(s, script.text.toString(), list)
                        moveTaskToBack(true)
                    }
                }
            }
            root.addView(this)
        }
        setContentView(ScrollView(ctx).apply { addView(root) })
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
                    val bad = Assistant.badLines(script)
                    val warn = if (bad.isEmpty()) "" else
                        "\n\nOjo, la app no entiende estas líneas; corrígelas o bórralas antes de usarlo:\n" + bad.joinToString("\n")
                    showProposal(script, "Lo ha creado la IA. Revísalo antes de usarlo." + warn)
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
        val ed = EditText(this)
        ed.setText(text)
        ed.typeface = Typeface.MONOSPACE
        ed.textSize = 14f
        ed.gravity = Gravity.TOP
        ed.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        ed.setPadding(48, 24, 48, 24)
        AlertDialog.Builder(this)
            .setTitle("Guion creado (puedes cambiarlo aquí)")
            .setMessage(note)
            .setView(ScrollView(this).apply { addView(ed) })
            .setPositiveButton("Usar este guion") { _, _ ->
                script.setText(ed.text.toString())
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
        val it = loadScripts().keys()
        while (it.hasNext()) names.add(it.next())
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
                if (name.isNotEmpty()) {
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
        val all = loadScripts()
        all.remove(name)
        prefs.edit().putString("scripts", all.toString()).apply()
        refreshSaved(null)
        toast("Guion «$name» borrado")
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
