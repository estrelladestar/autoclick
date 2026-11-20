package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * «Star App Sencilla»: una sola pantalla. Pones el enlace, tus menciones y unas pocas opciones,
 * y el resto se hace solo: seguir a la tienda, me gusta, seguir cuentas de la descripción,
 * comentar, mirar historias y cambiar de cuenta, y vuelve a empezar.
 */
class SencillaActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var linkBox: EditText
    private lateinit var cuentasBox: EditText
    private lateinit var mencionesBox: EditText
    private lateinit var nMencionesSpin: Spinner
    private lateinit var fraseBox: CheckBox
    private lateinit var frasesSpin: Spinner
    private lateinit var frasesTextoBox: EditText
    private lateinit var seguidosBox: EditText
    private lateinit var entreSpin: Spinner
    private lateinit var historiasSpin: Spinner
    private lateinit var megustaBox: CheckBox
    private lateinit var seguirDescBox: CheckBox
    private lateinit var estado: TextView
    private var pendingScript: String? = null
    private val captureCode = 78

    companion object {
        private val ENTRE_VALORES = listOf("10-20", "20-30", "30-45", "45-60", "60-90")
        private val ENTRE_TEXTOS = listOf("10 a 20 segundos", "20 a 30 segundos", "30 a 45 segundos", "45 a 60 segundos", "1 a 1,5 minutos")
        private val HIST_VALORES = listOf("1-2", "2-3", "")
        private val HIST_TEXTOS = listOf("Mirar historias 1 a 2 minutos", "Mirar historias 2 a 3 minutos", "No mirar historias")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Macro.installCrashLogger(this)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        fun texto(t: String, size: Float = 15f, negrita: Boolean = false): TextView = TextView(ctx).apply {
            text = t
            textSize = size
            if (negrita) setTypeface(null, Typeface.BOLD)
            setPadding(0, 28, 0, 4)
            root.addView(this)
        }

        fun caja(valor: String, multi: Boolean = false, numero: Boolean = false, pista: String = ""): EditText = EditText(ctx).apply {
            setText(valor)
            hint = pista
            inputType = when {
                numero -> InputType.TYPE_CLASS_NUMBER
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multi) {
                minLines = 3
                gravity = Gravity.TOP
            }
            root.addView(this)
        }

        fun lista(opciones: List<String>, sel: Int): Spinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, opciones)
            setSelection(sel.coerceIn(0, opciones.size - 1))
            root.addView(this)
        }

        fun marca(t: String, on: Boolean): CheckBox = CheckBox(ctx).apply {
            text = t
            textSize = 15f
            isChecked = on
            root.addView(this)
        }

        texto("⭐ Star App Sencilla", 22f, true)
        texto("Pon el enlace y tus menciones. Lo demás se hace solo: sigue a la tienda, da me gusta, sigue las cuentas de la descripción, comenta, mira historias y cambia de cuenta, y vuelve a empezar.")
        estado = texto("")
        Button(ctx).apply {
            text = "⚙ Activar la app (accesibilidad)"
            setOnClickListener { pedirAccesibilidad() }
            root.addView(this)
        }

        texto("1. Enlace de la publicación", 16f, true)
        linkBox = caja(prefs.getString("s_link", "") ?: "", pista = "https://www.instagram.com/p/…")

        texto("2. Cuentas (en orden, separadas por espacio; vacío = todas las de Instagram)", 16f, true)
        cuentasBox = caja(prefs.getString("s_cuentas", "") ?: "", pista = "cuenta1 cuenta2 …")

        texto("3. Tus menciones (una por línea)", 16f, true)
        mencionesBox = caja(prefs.getString("s_menciones", "") ?: "", multi = true, pista = "@amiga1")

        texto("4. ¿Cuántas menciones por comentario?", 16f, true)
        nMencionesSpin = lista(
            listOf("Sin menciones (solo frase)", "1 mención", "2 menciones", "3 menciones", "4 menciones", "5 menciones"),
            prefs.getInt("s_nmen", 3)
        )
        fraseBox = marca("✅ Con frase", prefs.getBoolean("s_frase", true))

        texto("5. ¿Qué frases?", 16f, true)
        frasesSpin = lista(
            listOf("Lista fija (frases de Trono)", "Chris Botti", "Mis frases (escribo abajo)"),
            prefs.getInt("s_frases_op", 0)
        )
        frasesTextoBox = caja(prefs.getString("s_frases_texto", "") ?: "", multi = true, pista = "Solo si eliges «Mis frases», una por línea")

        texto("6. ¿Cuántos comentarios seguidos en cada publicación?", 16f, true)
        seguidosBox = caja(prefs.getInt("s_seguidos", 10).toString(), numero = true)

        texto("7. ¿Cuántos segundos entre un comentario y el siguiente?", 16f, true)
        entreSpin = lista(ENTRE_TEXTOS, ENTRE_VALORES.indexOf(prefs.getString("s_entre", "10-20") ?: "10-20").coerceAtLeast(0))

        texto("8. Historias", 16f, true)
        historiasSpin = lista(HIST_TEXTOS, HIST_VALORES.indexOf(prefs.getString("s_hist", "1-2") ?: "1-2").coerceAtLeast(0))

        texto("9. En cada publicación", 16f, true)
        megustaBox = marca("❤️ Dar me gusta si el corazón está vacío", prefs.getBoolean("s_megusta", true))
        seguirDescBox = marca("➕ Seguir a las cuentas de la descripción (solo si no las sigues)", prefs.getBoolean("s_seguirdesc", true))

        Button(ctx).apply {
            text = "🚀 EMPEZAR TODO AUTOMÁTICO"
            textSize = 18f
            setOnClickListener { empezar() }
            root.addView(this)
        }
        texto("Para parar, toca ■ en el botón flotante.")

        setContentView(ScrollView(ctx).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        asegurarImagenes()
        estado.text = if (ClickService.instance != null) "✅ La app está activada en Accesibilidad"
            else "⚠ Falta activarla en Accesibilidad (botón de arriba)"
    }

    override fun onPause() {
        super.onPause()
        guardar()
    }

    /** El bocadillo (imagen 1) y la flecha de enviar (imagen 2) vienen dentro de la app: se dejan como primeras. */
    private fun asegurarImagenes() {
        val nombres = listOf("img_fija_0_bocadillo_t.png", "img_fija_1_flecha.jpg")
        val enAssets = listOf("bocadillo_t.png", "flecha.jpg")
        val todas = StepStore.load(this)
        val ahora = System.currentTimeMillis()
        val fijas = nombres.mapIndexed { i, nombre ->
            todas.firstOrNull { it.type == "image" && it.value == nombre } ?: run {
                val f = File(filesDir, nombre)
                if (!f.exists()) assets.open(enAssets[i]).use { inp -> f.outputStream().use { inp.copyTo(it) } }
                Step(ahora + i, "image", nombre)
            }
        }
        val resto = todas.filterNot { it.type == "image" && it.value in nombres }
        val nueva = fijas + resto
        if (nueva != todas) StepStore.save(this, nueva)
    }

    /** Guarda lo que hay en la pantalla y lo deja listo para el motor de la app. */
    private fun guardar() {
        val link = linkBox.text.toString().trim()
        val menciones = mencionesBox.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val frases = when (frasesSpin.selectedItemPosition) {
            1 -> Macro.chrisBottiPhrases(this).joinToString("\n")
            2 -> frasesTextoBox.text.toString()
            else -> Macro.fixedPhrases(this).joinToString("\n")
        }
        prefs.edit()
            .putString("s_link", link)
            .putString("s_cuentas", cuentasBox.text.toString().trim())
            .putString("s_menciones", mencionesBox.text.toString())
            .putInt("s_nmen", nMencionesSpin.selectedItemPosition)
            .putBoolean("s_frase", fraseBox.isChecked)
            .putInt("s_frases_op", frasesSpin.selectedItemPosition)
            .putString("s_frases_texto", frasesTextoBox.text.toString())
            .putInt("s_seguidos", seguidosBox.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 10)
            .putString("s_entre", ENTRE_VALORES[entreSpin.selectedItemPosition])
            .putString("s_hist", HIST_VALORES[historiasSpin.selectedItemPosition])
            .putBoolean("s_megusta", megustaBox.isChecked)
            .putBoolean("s_seguirdesc", seguirDescBox.isChecked)
            // Lo que lee el motor: el enlace, las menciones fijas y las frases
            .putString("link0", link)
            .putString("linkSelUrl", link)
            .putString("friends", menciones)
            .putString("menciones_fijas", menciones)
            .putBoolean("menciones_fijas_on", true)
            .putString("phrases", frases)
            .apply()
    }

    /** El guion que se ejecuta, hecho con lo que hay elegido en la pantalla. */
    private fun guion(): String {
        val n = nMencionesSpin.selectedItemPosition
        val frase = fraseBox.isChecked || n == 0
        val linea = when {
            n == 0 -> "escribir solo frase"
            frase -> "escribir menciones $n frase"
            else -> "escribir menciones $n sinfrase"
        }
        val seguidos = seguidosBox.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 10
        val entre = ENTRE_VALORES[entreSpin.selectedItemPosition]
        val hist = HIST_VALORES[historiasSpin.selectedItemPosition]
        val cuentas = cuentasBox.text.toString().trim().split(Regex("[\\s,;]+")).map { it.removePrefix("@") }.filter { it.isNotEmpty() }
        val sb = StringBuilder()
        if (cuentas.size >= 2) sb.append("cuentas ${cuentas.joinToString(" ")}\n")
        sb.append("enlace\nesperar 8\nseguir\nesperar 1-3\n")
        if (megustaBox.isChecked) sb.append("megusta\nesperar 1-2\n")
        if (seguirDescBox.isChecked) sb.append("seguir descripcion\nesperar 2-4\n")
        for (k in 1..seguidos) {
            // Entre un comentario y el siguiente, los segundos que hayas elegido
            sb.append("imagen 1 opcional | 1-2\n$linea\nesperar 2-4\nimagen 2 | ${if (k < seguidos) entre else "2-4"}\n")
        }
        if (hist.isNotEmpty()) sb.append("historias $hist min\n")
        sb.append("cuenta siguiente\nrepetir 1000")
        return sb.toString()
    }

    /** Lo que falta antes de empezar (vacío si todo está listo). */
    private fun problemas(): List<String> {
        val out = ArrayList<String>()
        if (linkBox.text.toString().trim().isEmpty()) out.add("Pon el enlace de la publicación (cuadro 1).")
        if (nMencionesSpin.selectedItemPosition > 0 && mencionesBox.text.toString().lines().none { it.trim().isNotEmpty() }) {
            out.add("Pon tus menciones (cuadro 3), o elige «Sin menciones» en el cuadro 4.")
        }
        val cuentas = cuentasBox.text.toString().trim().split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (cuentas.size == 1) out.add("En el cuadro 2 pon 2 o más cuentas, o déjalo vacío para usar todas las de Instagram.")
        if (frasesSpin.selectedItemPosition == 2 && frasesTextoBox.text.toString().trim().isEmpty()) {
            out.add("Escribe tus frases (cuadro 5), o elige otra opción.")
        }
        return out
    }

    private fun empezar() {
        guardar()
        val faltan = problemas()
        if (faltan.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Antes de empezar, falta esto")
                .setMessage("• " + faltan.joinToString("\n\n• "))
                .setPositiveButton("Vale", null)
                .show()
            return
        }
        val s = ClickService.instance
        if (s == null) {
            pedirAccesibilidad()
            return
        }
        val script = guion()
        if (android.os.Build.VERSION.SDK_INT < 30 && ScreenCaptureService.instance == null) {
            // En Android 10 o anterior hace falta el permiso de captura de pantalla para ver las imágenes
            pendingScript = script
            AlertDialog.Builder(this)
                .setTitle("Falta el permiso de captura de pantalla")
                .setMessage("Sin él no se ven las imágenes. Pulsa «Dar permiso» y el guion empezará solo.")
                .setPositiveButton("Dar permiso") { _, _ ->
                    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                    try {
                        startActivityForResult(mgr.createScreenCaptureIntent(), captureCode)
                    } catch (e: Exception) {
                        toast("No se pudo pedir el permiso")
                    }
                }
                .setNegativeButton("Cancelar") { _, _ -> pendingScript = null }
                .show()
            return
        }
        arrancar(s, script)
    }

    private fun arrancar(s: ClickService, script: String) {
        Macro.start(s, script, emptyList())
        moveTaskToBack(true)
    }

    private fun pedirAccesibilidad() {
        val on = ClickService.enabledInSettings(this)
        AlertDialog.Builder(this)
            .setTitle("Falta la accesibilidad de Star App Sencilla")
            .setMessage(
                if (on) "Está activada, pero no funciona: en Ajustes, apágala y vuelve a encenderla."
                else "Pulsa «Abrir Ajustes», busca Star App Sencilla y actívala."
            )
            .setPositiveButton("Abrir Ajustes") { _, _ ->
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureCode) {
            val pending = pendingScript
            pendingScript = null
            if (resultCode == RESULT_OK && data != null) {
                startForegroundService(Intent(this, ScreenCaptureService::class.java).putExtra("code", resultCode).putExtra("data", data))
                val s = ClickService.instance
                if (pending != null && s != null) {
                    // Un momento para que la captura arranque; luego empieza el guion solo
                    Handler(Looper.getMainLooper()).postDelayed({ arrancar(s, pending) }, 1200L)
                }
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
