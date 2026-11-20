package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

        // Colores de la pantalla
        private val AZUL = Color.parseColor("#1E88E5")
        private val MORADO = Color.parseColor("#8E24AA")
        private val NARANJA = Color.parseColor("#FB8C00")
        private val ROSA = Color.parseColor("#D81B60")
        private val FUCSIA = Color.parseColor("#EC407A")
        private val VERDE_AZULADO = Color.parseColor("#00897B")
        private val INDIGO = Color.parseColor("#3949AB")
        private val CIAN = Color.parseColor("#00ACC1")
        private val ROJO = Color.parseColor("#E53935")
        private val TEXTO = Color.parseColor("#263238")
        private val GRIS = Color.parseColor("#455A64")
        private val GRIS_CLARO = Color.parseColor("#90A4AE")
        private val FONDO_ARRIBA = Color.parseColor("#FDE2F3")
        private val FONDO_ABAJO = Color.parseColor("#DDEBFF")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Macro.installCrashLogger(this)
        prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(36), dp(16), dp(28))
        }
        // Cada bloque va en su tarjeta blanca; «seccion» es la tarjeta donde se coloca lo siguiente
        var seccion: LinearLayout = root
        var colorSec: Int = AZUL

        fun seccionNueva(titulo: String, color: Int) {
            colorSec = color
            val tarjeta = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = redondo(Color.WHITE, 18)
                elevation = dp(4).toFloat()
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            root.addView(tarjeta, lpRaiz(abajo = 14))
            seccion = tarjeta
            tarjeta.addView(TextView(ctx).apply {
                text = titulo
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = redondo(color, 12)
            })
        }

        fun texto(t: String, tam: Float = 13f, color: Int = GRIS): TextView = TextView(ctx).apply {
            text = t
            textSize = tam
            setTextColor(color)
            setPadding(dp(2), dp(10), dp(2), 0)
            seccion.addView(this, lpRaiz(arriba = 10))
        }

        fun caja(valor: String, multi: Boolean = false, numero: Boolean = false, pista: String = ""): EditText = EditText(ctx).apply {
            setText(valor)
            hint = pista
            setHintTextColor(GRIS_CLARO)
            setTextColor(TEXTO)
            inputType = when {
                numero -> InputType.TYPE_CLASS_NUMBER
                multi -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (multi) {
                minLines = 3
                gravity = Gravity.TOP
            }
            background = redondo(Color.parseColor("#FAFAFA"), 12, colorSec)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            seccion.addView(this, lpRaiz(arriba = 10))
        }

        fun lista(opciones: List<String>, sel: Int): Spinner = Spinner(ctx).apply {
            adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, opciones) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply {
                        setTextColor(TEXTO)
                        textSize = 15f
                    }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getDropDownView(position, convertView, parent) as TextView).apply {
                        setTextColor(TEXTO)
                        setBackgroundColor(Color.WHITE)
                        setPadding(dp(14), dp(12), dp(14), dp(12))
                    }
            }
            setSelection(sel.coerceIn(0, opciones.size - 1))
            background = redondo(claro(colorSec), 12, colorSec)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            seccion.addView(this, lpRaiz(arriba = 10))
        }

        fun marca(t: String, on: Boolean): CheckBox = CheckBox(ctx).apply {
            text = t
            textSize = 15f
            setTextColor(TEXTO)
            buttonTintList = ColorStateList.valueOf(colorSec)
            isChecked = on
            setPadding(dp(4), dp(10), dp(4), dp(4))
            seccion.addView(this, lpRaiz(arriba = 6))
        }

        // Cabecera con degradado
        val cabecera = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = degradado(MORADO, Color.parseColor("#FF4081"), 22)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            elevation = dp(6).toFloat()
        }
        cabecera.addView(TextView(ctx).apply {
            text = "⭐ Star App Sencilla"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        cabecera.addView(TextView(ctx).apply {
            text = "Pon el enlace y tus menciones. Lo demás se hace solo: sigue a la tienda, da me gusta, sigue las cuentas de la descripción, comenta, mira historias y cambia de cuenta, y vuelve a empezar."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, 0)
        })
        root.addView(cabecera, lpRaiz(abajo = 12))

        // Estado de la accesibilidad (verde si está activada, naranja si falta)
        estado = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        root.addView(estado, lpRaiz(abajo = 10))

        root.addView(Button(ctx).apply {
            text = "⚙ Activar la app (accesibilidad)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = conEfecto(redondo(AZUL, 16))
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { pedirAccesibilidad() }
        }, lpRaiz(abajo = 6))

        seccionNueva("🔗 1. Enlace de la publicación", AZUL)
        linkBox = caja(prefs.getString("s_link", "") ?: "", pista = "https://www.instagram.com/p/…")

        seccionNueva("👥 2. Cuentas (en orden; vacío = todas)", MORADO)
        texto("Sepáralas con espacios. Con 2 o más, la app cambia de cuenta sola.")
        cuentasBox = caja(prefs.getString("s_cuentas", "") ?: "", pista = "cuenta1 cuenta2 …")

        seccionNueva("💬 3. Tus menciones (una por línea)", NARANJA)
        mencionesBox = caja(prefs.getString("s_menciones", "") ?: "", multi = true, pista = "@amiga1")

        seccionNueva("🔢 4. Menciones por comentario", ROSA)
        nMencionesSpin = lista(
            listOf("Sin menciones (solo frase)", "1 mención", "2 menciones", "3 menciones", "4 menciones", "5 menciones"),
            prefs.getInt("s_nmen", 3)
        )
        fraseBox = marca("✅ Con frase", prefs.getBoolean("s_frase", true))

        seccionNueva("✨ 5. Frases", FUCSIA)
        frasesSpin = lista(
            listOf("Lista fija (frases de Trono)", "Chris Botti", "Mis frases (escribo abajo)"),
            prefs.getInt("s_frases_op", 0)
        )
        frasesTextoBox = caja(prefs.getString("s_frases_texto", "") ?: "", multi = true, pista = "Solo si eliges «Mis frases», una por línea")

        seccionNueva("🔁 6. Comentarios seguidos por publicación", VERDE_AZULADO)
        seguidosBox = caja(prefs.getInt("s_seguidos", 10).toString(), numero = true)

        seccionNueva("⏱ 7. Segundos entre comentarios", INDIGO)
        entreSpin = lista(ENTRE_TEXTOS, ENTRE_VALORES.indexOf(prefs.getString("s_entre", "10-20") ?: "10-20").coerceAtLeast(0))

        seccionNueva("📸 8. Historias", CIAN)
        historiasSpin = lista(HIST_TEXTOS, HIST_VALORES.indexOf(prefs.getString("s_hist", "1-2") ?: "1-2").coerceAtLeast(0))

        seccionNueva("❤️ 9. En cada publicación", ROJO)
        megustaBox = marca("❤️ Dar me gusta si el corazón está vacío", prefs.getBoolean("s_megusta", true))
        seguirDescBox = marca("➕ Seguir a las cuentas de la descripción (solo si no las sigues)", prefs.getBoolean("s_seguirdesc", true))

        root.addView(Button(ctx).apply {
            text = "🚀 EMPEZAR TODO AUTOMÁTICO"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = 18f
            background = conEfecto(degradado(Color.parseColor("#FF4081"), Color.parseColor("#FF6D00"), 30))
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setOnClickListener { empezar() }
        }, lpRaiz(arriba = 8, abajo = 14))

        root.addView(TextView(ctx).apply {
            text = "Para parar, toca ■ en el botón flotante."
            textSize = 13f
            setTextColor(Color.parseColor("#6A1B9A"))
            gravity = Gravity.CENTER
        }, lpRaiz())

        setContentView(ScrollView(ctx).apply {
            background = degradado(FONDO_ARRIBA, FONDO_ABAJO, 0)
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        asegurarImagenes()
        val activa = ClickService.instance != null
        estado.text = if (activa) "✅ Activada: ya puedes empezar" else "⚠ Falta activarla en Accesibilidad (pulsa el botón azul)"
        estado.background = redondo(Color.parseColor(if (activa) "#43A047" else "#FB8C00"), 14)
    }

    override fun onPause() {
        super.onPause()
        guardar()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun redondo(color: Int, radio: Int, borde: Int? = null): GradientDrawable = GradientDrawable().apply {
        setCornerRadius(dp(radio).toFloat())
        setColor(color)
        if (borde != null) setStroke(dp(2), borde)
    }

    private fun degradado(a: Int, b: Int, radio: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(a, b)).apply {
            setCornerRadius(dp(radio).toFloat())
        }

    /** Al pulsar se ve un destello blanco sobre el botón. */
    private fun conEfecto(fondo: GradientDrawable): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Color.parseColor("#55FFFFFF")), fondo, null)

    /** Un tono muy claro del color (para el fondo de las listas). */
    private fun claro(c: Int): Int = Color.rgb(
        Color.red(c) + ((255 - Color.red(c)) * 0.85f).toInt(),
        Color.green(c) + ((255 - Color.green(c)) * 0.85f).toInt(),
        Color.blue(c) + ((255 - Color.blue(c)) * 0.85f).toInt()
    )

    private fun lpRaiz(arriba: Int = 0, abajo: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(arriba)
            bottomMargin = dp(abajo)
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
