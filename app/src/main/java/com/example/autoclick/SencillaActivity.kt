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
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * «Star App Sencilla»: una sola pantalla. Pones hasta 5 enlaces, tus menciones y unas pocas opciones,
 * y el resto se hace solo: seguir a la tienda, me gusta, seguir cuentas de la descripción,
 * comentar, mirar historias y cambiar de cuenta, y vuelve a empezar.
 */
class SencillaActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    // Hasta 5 enlaces de publicación, cada uno con su foto al lado
    private val linkBoxes = mutableListOf<EditText>()
    private val botonesElegir = mutableListOf<TextView>()
    private var enlaceElegido = -1 // el enlace que usa el guion en todas las vueltas; -1 = todos en orden
    private lateinit var estadoEnlaces: TextView
    private val fotos = mutableListOf<ImageView>()
    private val fotoPendiente = arrayOfNulls<Runnable>(5)
    private val fotoHandler = Handler(Looper.getMainLooper())
    private lateinit var cuentasBox: EditText
    private lateinit var mencionesBox: EditText
    private lateinit var nMencionesSpin: Spinner
    private val botonesGuardar = ArrayList<TextView>()
    private lateinit var botonListas: Button
    private lateinit var resumenMenciones: TextView
    private lateinit var resumenFrases: TextView
    private var listasRecogidas = true
    private lateinit var fraseBox: CheckBox
    private lateinit var frasesSpin: Spinner
    private lateinit var frasesTextoBox: EditText
    private lateinit var seguidosBox: EditText
    private lateinit var entreSpin: Spinner
    private lateinit var historiasSpin: Spinner
    private lateinit var subirHistoriaBox: CheckBox
    private lateinit var megustaBox: CheckBox
    private lateinit var seguirDescBox: CheckBox
    private lateinit var guardarPubBox: CheckBox
    private lateinit var repostBox: CheckBox
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
            text = "Pon hasta 5 enlaces y tus menciones. Lo demás se hace solo: sigue a la tienda, sube la historia, da me gusta, sigue las cuentas de la descripción, comenta, mira historias y cambia de cuenta, y vuelve a empezar."
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

        // Recoger / mostrar las listas largas (menciones y frases) para no tener que bajar tanto
        botonListas = Button(ctx).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = conEfecto(redondo(NARANJA, 16))
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                listasRecogidas = !listasRecogidas
                prefs.edit().putBoolean("s_listas_recogidas", listasRecogidas).apply()
                aplicarListas()
            }
        }
        root.addView(botonListas, lpRaiz(abajo = 6))

        // 1. Hasta 5 enlaces, cada uno con la foto de su publicación
        seccionNueva("🔗 1. Enlaces de publicaciones (hasta 5)", AZUL)
        texto("📷 Si no sale la foto de la publicación, pulsa el cuadro gris y elige tú una foto de la galería (por ejemplo una captura).")
        texto("Pega cada enlace. A la izquierda sale la foto de la publicación. Si no marcas ninguno, el guion los usa uno tras otro, en orden. Si marcas ○ en uno, el guion usa solo ese.")
        texto("💾 al lado de un enlace guarda para esa publicación las menciones de «4» (cuántas y si llevan frase). Cuando el guion vuelva a ese enlace, las usa solas. Si sale ✅, ya tiene lo suyo guardado (pulsa ✅ para cambiarlo o quitarlo).")
        estadoEnlaces = texto("")
        enlaceElegido = prefs.getInt("s_link_sel", -1)
        val guardados = (prefs.getString("s_links", null) ?: prefs.getString("s_link", "") ?: "").lines()
        for (i in 0 until 5) {
            val foto = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = redondo(Color.parseColor("#E0E0E0"), 10)
                // La foto se recorta con las mismas esquinas redondeadas del fondo
                clipToOutline = true
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                contentDescription = "Foto del enlace ${i + 1}: pulsa para subirla"
                // Pulsar la foto: elegir tú una foto de la galería para ese enlace
                setOnClickListener { elegirFotoDeGaleria(i) }
            }
            fotos.add(foto)
            val campo = EditText(ctx).apply {
                setText(guardados.getOrElse(i) { "" }.trim())
                hint = "Enlace ${i + 1}"
                setHintTextColor(GRIS_CLARO)
                setTextColor(TEXTO)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                background = redondo(Color.parseColor("#FAFAFA"), 12, colorSec)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            linkBoxes.add(campo)
            val elegir = TextView(ctx).apply {
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(colorSec)
                setPadding(dp(10), dp(4), dp(4), dp(4))
                contentDescription = "Usar solo el enlace ${i + 1}"
                setOnClickListener { elegirEnlace(i) }
            }
            botonesElegir.add(elegir)
            val guardarBtn = TextView(ctx).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                contentDescription = "Guardar las menciones para el enlace ${i + 1}"
                setOnClickListener { guardarParaEnlace(i) }
            }
            botonesGuardar.add(guardarBtn)
            val fila = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(foto, LinearLayout.LayoutParams(dp(72), dp(72)))
                addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
                addView(elegir, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(4) })
                addView(guardarBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(2) })
            }
            seccion.addView(fila, lpRaiz(arriba = 10))
            alEscribir(campo) {
                guardarListas()
                pedirFoto(i)
                refrescarGuardados()
            }
            pedirFoto(i)
        }

        refrescarEnlaces()

        seccionNueva("👥 2. Cuentas (en orden; vacío = todas)", MORADO)
        texto("Sepáralas con espacios. Con 2 o más, la app cambia de cuenta sola.")
        cuentasBox = caja(prefs.getString("s_cuentas", "") ?: "", pista = "cuenta1 cuenta2 …")

        seccionNueva("💬 3. Tus menciones (una por línea)", NARANJA)
        Macro.ponerMencionesDeFabrica(this)
        mencionesBox = caja(prefs.getString("s_menciones", "") ?: "", multi = true, pista = "@amiga1")
        texto("📌 Tus 379 menciones van fijas dentro de la app (salen también en otro móvil). Puedes añadir más abajo del todo, una por línea: se quedan guardadas.")
        resumenMenciones = texto("")
        alEscribir(mencionesBox) { guardarListas(); aplicarListas() }

        seccionNueva("🔢 4. Menciones por comentario", ROSA)
        nMencionesSpin = lista(
            listOf("Sin menciones (solo frase)", "1 mención", "2 menciones", "3 menciones", "4 menciones", "5 menciones"),
            prefs.getInt("s_nmen", 3)
        )
        fraseBox = marca("✅ Con frase", prefs.getBoolean("s_frase", true))
        refrescarGuardados()

        seccionNueva("✨ 5. Frases", FUCSIA)
        frasesSpin = lista(
            listOf("Lista fija (frases de Trono)", "Chris Botti", "Mis frases (escribo abajo)", "📌 Chris Botti fijo (2227 frases)"),
            prefs.getInt("s_frases_op", 0)
        )
        frasesTextoBox = caja(prefs.getString("s_frases_texto", "") ?: "", multi = true, pista = "Solo si eliges «Mis frases», una por línea")
        alEscribir(frasesTextoBox) { guardarListas(); aplicarListas() }
        texto("Las listas fijas ya vienen guardadas en la app.")
        resumenFrases = texto("")
        listasRecogidas = prefs.getBoolean("s_listas_recogidas", true)
        // «Fija las frases»: la primera vez se deja puesta la lista fija Chris Botti
        if (!prefs.getBoolean("s_frases_fijadas", false)) {
            frasesSpin.setSelection(3)
            prefs.edit().putBoolean("s_frases_fijadas", true).putInt("s_frases_op", 3).apply()
        }
        frasesSpin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("s_frases_op", pos).apply()
                aplicarListas()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        aplicarListas()

        seccionNueva("🔁 6. Comentarios seguidos por publicación", VERDE_AZULADO)
        seguidosBox = caja(prefs.getInt("s_seguidos", 10).toString(), numero = true)

        seccionNueva("⏱ 7. Segundos entre comentarios", INDIGO)
        entreSpin = lista(ENTRE_TEXTOS, ENTRE_VALORES.indexOf(prefs.getString("s_entre", "10-20") ?: "10-20").coerceAtLeast(0))

        seccionNueva("📸 8. Historias", CIAN)
        subirHistoriaBox = marca("📤 Subir historia etiquetando a la tienda", prefs.getBoolean("s_subir_hist", true))
        texto("La historia solo lleva la etiqueta de la tienda, sin frase ni menciones.")
        historiasSpin = lista(HIST_TEXTOS, HIST_VALORES.indexOf(prefs.getString("s_hist", "1-2") ?: "1-2").coerceAtLeast(0))

        seccionNueva("❤️ 9. En cada publicación", ROJO)
        megustaBox = marca("❤️ Dar me gusta si el corazón está vacío", prefs.getBoolean("s_megusta", true))
        seguirDescBox = marca("➕ Seguir a las cuentas de la descripción (solo si no las sigues)", prefs.getBoolean("s_seguirdesc", true))
        guardarPubBox = marca("🔖 Guardar la publicación (solo si no está guardada)", prefs.getBoolean("s_guardarpub", false))
        repostBox = marca("🔁 Volver a publicar / repost (solo si no lo has hecho)", prefs.getBoolean("s_repost", false))

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

        root.addView(Button(ctx).apply {
            text = "🐞 Informe (si algo falla)"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = conEfecto(degradado(Color.parseColor("#7C4DFF"), Color.parseColor("#536DFE"), 30))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { mostrarInforme() }
        }, lpRaiz(arriba = 0, abajo = 14))

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

    /**
     * Si el cuadro de menciones está vacío, trae las de Star App (o Historias) al abrir la app.
     * Si ya hay lista, se queda fija: no cambia sola aunque cambie la de Star App.
     */
    private fun traerMencionesDeOtraApp() {
        if (mencionesBox.text.toString().isNotBlank()) return
        Thread {
            val lista = leerMencionesDeOtraApp()
            if (lista != null) fotoHandler.post {
                if (mencionesBox.text.toString().isNotBlank()) return@post
                mencionesBox.setText(lista)
                toast("Menciones de Star App cargadas: ${lista.lines().size}")
            }
        }.start()
    }

    /** Lee las menciones de la primera de estas apps que esté instalada (null si no hay ninguna). */
    private fun leerMencionesDeOtraApp(): String? {
        for (autoridad in listOf("com.example.autoclick.menciones", "com.example.autoclick.historias.menciones")) {
            try {
                contentResolver.query(Uri.parse("content://$autoridad/menciones"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val texto = (c.getString(0) ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                        if (texto.isNotEmpty()) return texto
                    }
                }
            } catch (e: Exception) {
                // Esa app no está instalada o no deja leer: se prueba la siguiente
            }
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        asegurarImagenes()
        traerMencionesDeOtraApp()
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

    /** Llama a «accion» cada vez que cambia lo escrito en un cuadro. */
    private fun alEscribir(campo: EditText, accion: () -> Unit) {
        campo.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                accion()
            }
        })
    }

    /** Marca (○ pasa a ●) o quita la marca del enlace que usa el guion en todas las vueltas. */
    private fun elegirEnlace(i: Int) {
        if (linkBoxes[i].text.toString().trim().isEmpty()) {
            toast("Pon primero el enlace ${i + 1}")
            return
        }
        enlaceElegido = if (enlaceElegido == i) -1 else i
        guardarListas()
        refrescarEnlaces()
        if (enlaceElegido == i) cargarDeEnlace(i)
    }

    /** Oculta o enseña los cuadros largos de menciones y frases; cuando están recogidos se dice cuántas hay. */
    private fun aplicarListas() {
        if (!::botonListas.isInitialized || !::resumenFrases.isInitialized) return
        val nMen = mencionesBox.text.toString().lines().count { it.trim().isNotEmpty() }
        val nFr = when (frasesSpin.selectedItemPosition) {
            1 -> Macro.chrisBottiPhrases(this).size
            2 -> frasesTextoBox.text.toString().lines().count { it.trim().isNotEmpty() }
            3 -> Macro.chrisBotti1000Phrases(this).size
            else -> Macro.fixedPhrases(this).size
        }
        botonListas.text = if (listasRecogidas) "🔽 Ver las listas de menciones y frases" else "🔼 Recoger las listas de menciones y frases"
        val vis = if (listasRecogidas) View.GONE else View.VISIBLE
        mencionesBox.visibility = vis
        // El cuadro de «Mis frases» solo hace falta si se eligen tus frases
        frasesTextoBox.visibility = if (listasRecogidas || frasesSpin.selectedItemPosition != 2) View.GONE else View.VISIBLE
        resumenMenciones.text = "📋 $nMen menciones guardadas" + if (listasRecogidas) " (recogidas: pulsa «Ver las listas» arriba)" else ""
        resumenFrases.text = "📋 $nFr frases en la lista elegida" + if (frasesSpin.selectedItemPosition == 3) " · 📌 fijas" else ""
    }

    // ---- Menciones guardadas por publicación (botón 💾) ----

    private fun idPublicacion(u: String): String? =
        Regex("/(p|reel|reels|tv)/([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(2)

    private fun porEnlace(): MutableList<String> =
        (prefs.getString("por_enlace", "") ?: "").lines().filter { it.contains("|") }.toMutableList()

    private fun guardadoDe(id: String): Pair<Int, Boolean>? {
        val p = porEnlace().firstOrNull { it.startsWith("$id|") }?.split("|") ?: return null
        val n = p.getOrNull(1)?.toIntOrNull() ?: return null
        return Pair(n, p.getOrNull(2) == "1")
    }

    private fun describir(n: Int, frase: Boolean): String = when {
        n == 0 -> "sin menciones (solo frase)"
        n == 1 -> "1 mención " + if (frase) "con frase" else "sin frase"
        else -> "$n menciones " + if (frase) "con frase" else "sin frase"
    }

    /** 💾: guarda para esa publicación lo que está puesto en «4. Menciones por comentario». */
    private fun guardarParaEnlace(i: Int) {
        val id = idPublicacion(linkBoxes[i].text.toString().trim())
        if (id == null) {
            toast("Pon primero el enlace ${i + 1} (de una publicación de Instagram)")
            return
        }
        val n = nMencionesSpin.selectedItemPosition
        val frase = fraseBox.isChecked || n == 0
        val ya = guardadoDe(id)
        if (ya != null && ya == Pair(n, frase)) {
            // Ya estaba igual: se pregunta si se quiere quitar
            android.app.AlertDialog.Builder(this)
                .setTitle("Enlace ${i + 1}")
                .setMessage("Ya tiene guardado: ${describir(n, frase)}.\n\nPara cambiarlo, elige otras menciones en «4» y pulsa 💾 otra vez.")
                .setPositiveButton("Dejarlo") { _, _ -> }
                .setNegativeButton("Quitar lo guardado") { _, _ ->
                    prefs.edit().putString("por_enlace", porEnlace().filter { !it.startsWith("$id|") }.joinToString("\n")).apply()
                    refrescarGuardados()
                    toast("Quitado: el enlace ${i + 1} usará lo general")
                }
                .show()
            return
        }
        val lista = porEnlace().filter { !it.startsWith("$id|") }.toMutableList()
        lista.add("$id|$n|${if (frase) 1 else 0}")
        prefs.edit().putString("por_enlace", lista.joinToString("\n")).apply()
        refrescarGuardados()
        toast("💾 Guardado para el enlace ${i + 1}: ${describir(n, frase)}")
    }

    /** Al marcar un enlace, se ponen en «4» las menciones que tenga guardadas. */
    private fun cargarDeEnlace(i: Int) {
        val id = idPublicacion(linkBoxes[i].text.toString().trim()) ?: return
        val g = guardadoDe(id) ?: return
        nMencionesSpin.setSelection(g.first.coerceIn(0, 5))
        fraseBox.isChecked = g.second
        toast("Enlace ${i + 1}: ${describir(g.first, g.second)} (lo guardado)")
    }

    /** 💾 si no tiene nada guardado; ✅ si ya tiene. */
    private fun refrescarGuardados() {
        botonesGuardar.forEachIndexed { i, b ->
            val id = idPublicacion(linkBoxes.getOrNull(i)?.text?.toString()?.trim() ?: "")
            b.text = if (id != null && guardadoDe(id) != null) "✅" else "💾"
            b.alpha = if (id == null) 0.35f else 1f
        }
    }

    /** Pone ● en el enlace marcado (○ en los demás) y dice qué usará el guion. */
    private fun refrescarEnlaces() {
        if (!::estadoEnlaces.isInitialized) return
        botonesElegir.forEachIndexed { i, b -> b.text = if (i == enlaceElegido) "●" else "○" }
        estadoEnlaces.text = if (enlaceElegido in 0..4) {
            "Ahora el guion usa solo el enlace ${enlaceElegido + 1}. Pulsa ● para volver a usar todos en orden."
        } else {
            "Ahora el guion usa todos los enlaces, en orden."
        }
    }

    /** Espera un momento (mientras se escribe el enlace) y luego busca la foto de su publicación. */
    private fun pedirFoto(i: Int) {
        fotoPendiente[i]?.let { fotoHandler.removeCallbacks(it) }
        val r = Runnable { mostrarFoto(i) }
        fotoPendiente[i] = r
        fotoHandler.postDelayed(r, 900L)
    }

    private val FOTO_CODE = 7300

    /** Abre la galería para elegir la foto del enlace i (por si Instagram no deja sacarla sola). */
    private fun elegirFotoDeGaleria(i: Int) {
        if (linkBoxes[i].text.toString().trim().isEmpty()) {
            toast("Pon primero el enlace ${i + 1}")
            return
        }
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), FOTO_CODE + i)
        } catch (e: Exception) {
            toast("No se puede abrir la galería")
        }
    }

    private fun mostrarFoto(i: Int) {
        val url = linkBoxes[i].text.toString().trim()
        if (url.isEmpty()) {
            fotos[i].setImageDrawable(null)
            return
        }
        FotoEnlace.cargar(this, url) { bmp ->
            // Solo se pone si el enlace sigue siendo el mismo; si no hay foto, se quita la anterior
            if (linkBoxes[i].text.toString().trim() == url) fotos[i].setImageBitmap(bmp)
        }
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

    /**
     * Lo que se escribe a mano (enlaces, menciones y tus frases): se guarda al momento, así no se pierde.
     * Los enlaces van como link0…link4; «linkSelUrl» es el que marcas con ● (vacío: el guion los usa uno tras otro).
     */
    private fun guardarListas() {
        // Mientras la pantalla se monta, algún cuadro aún no existe: en ese caso no se guarda nada
        if (!::mencionesBox.isInitialized || !::frasesTextoBox.isInitialized) return
        val enlaces = linkBoxes.map { it.text.toString().trim() }
        // Si el enlace marcado se ha vaciado, vuelve a usarse todos en orden
        if (enlaceElegido in 0..4 && enlaces.getOrElse(enlaceElegido) { "" }.isEmpty()) enlaceElegido = -1
        refrescarEnlaces()
        val menciones = mencionesBox.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        val ed = prefs.edit()
        enlaces.forEachIndexed { i, u -> ed.putString("link$i", u) }
        ed.putString("s_links", enlaces.joinToString("\n"))
        ed.putString("linkSelUrl", if (enlaceElegido in 0..4) enlaces.getOrElse(enlaceElegido) { "" } else "")
        ed.putInt("s_link_sel", enlaceElegido)
        ed.putString("s_menciones", mencionesBox.text.toString())
        ed.putString("friends", menciones)
        ed.putString("menciones_fijas", menciones)
        ed.putBoolean("menciones_fijas_on", true)
        ed.putString("s_frases_texto", frasesTextoBox.text.toString())
        ed.apply()
    }

    /** Guarda lo que hay en la pantalla y lo deja listo para el motor de la app. */
    private fun guardar() {
        guardarListas()
        val frases = when (frasesSpin.selectedItemPosition) {
            1 -> Macro.chrisBottiPhrases(this).joinToString("\n")
            2 -> frasesTextoBox.text.toString()
            3 -> Macro.chrisBotti1000Phrases(this).joinToString("\n")
            else -> Macro.fixedPhrases(this).joinToString("\n")
        }
        prefs.edit()
            .putString("s_cuentas", cuentasBox.text.toString().trim())
            .putInt("s_nmen", nMencionesSpin.selectedItemPosition)
            .putBoolean("s_frase", fraseBox.isChecked)
            .putInt("s_frases_op", frasesSpin.selectedItemPosition)
            .putInt("s_seguidos", seguidosBox.text.toString().toIntOrNull()?.coerceIn(1, 50) ?: 10)
            .putString("s_entre", ENTRE_VALORES[entreSpin.selectedItemPosition])
            .putString("s_hist", HIST_VALORES[historiasSpin.selectedItemPosition])
            .putBoolean("s_megusta", megustaBox.isChecked)
            .putBoolean("s_seguirdesc", seguirDescBox.isChecked)
            .putBoolean("s_guardarpub", guardarPubBox.isChecked)
            .putBoolean("s_repost", repostBox.isChecked)
            .putBoolean("s_subir_hist", subirHistoriaBox.isChecked)
            // Lo que lee el motor: las frases elegidas
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
        // Lo primero tras abrir el enlace: subir la historia etiquetando a la tienda (y a las cuentas de la descripción)
        sb.append("enlace\nesperar 8\n")
        if (subirHistoriaBox.isChecked) sb.append("historia\nesperar 3-5\n")
        sb.append("seguir\nesperar 1-3\n")
        if (megustaBox.isChecked) sb.append("megusta\nesperar 1-2\n")
        if (guardarPubBox.isChecked) sb.append("guardar\nesperar 1-2\n")
        if (repostBox.isChecked) sb.append("repost\nesperar 2-4\n")
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
        if (linkBoxes.none { it.text.toString().trim().isNotEmpty() }) out.add("Pon al menos un enlace de publicación (cuadro 1).")
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
        if (requestCode in FOTO_CODE until FOTO_CODE + 5) {
            val i = requestCode - FOTO_CODE
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                val url = linkBoxes[i].text.toString().trim()
                val ok = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    bytes != null && FotoEnlace.guardarManual(this, url, bytes)
                } catch (e: Exception) { false }
                if (ok) { mostrarFoto(i); toast("📷 Foto puesta en el enlace ${i + 1}") } else toast("No se ha podido poner esa foto")
            }
            return
        }
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

    /** Enseña el informe del último guion (qué ha hecho y dónde se ha atascado), para copiarlo y pegarlo en el chat. */
    private fun mostrarInforme() {
        val txt = Macro.traceText(this)
        val head = Macro.reportHead(this)
        val body = txt.removePrefix(head)
        AlertDialog.Builder(this)
            .setTitle("🐞 Informe")
            .setMessage(head + (if (body.isBlank()) "Todavía no hay informe. Pulsa «Empezar todo automático» y, si falla, vuelve aquí." else body.takeLast(6000)))
            .setPositiveButton("Copiar") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("informe Sencilla", txt))
                toast("Informe copiado. Pégalo en el chat.")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}
