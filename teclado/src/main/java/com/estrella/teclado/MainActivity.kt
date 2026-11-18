package com.estrella.teclado

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * La app de Star Teclado: activar el teclado, elegir letras, colores, foto de fondo,
 * tamaño y vibración, y un convertidor para copiar textos con letras bonitas.
 */
class MainActivity : Activity() {
    private lateinit var aj: Ajustes
    private var estado1: TextView? = null
    private var estado2: TextView? = null
    private var textoConvertidor = "Hola, soy Estrella ✨"
    private val rosa = Color.parseColor("#C2307A")
    private val morado = Color.parseColor("#7B3FE4")
    private val oscuro = Color.parseColor("#2B1747")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        aj = Ajustes(this)
        pintar()
    }

    override fun onResume() { super.onResume(); mirarEstado() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) mirarEstado()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun activado(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun elegido(): Boolean =
        (Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: "").startsWith("$packageName/")

    private fun mirarEstado() {
        estado1?.text = if (activado()) "✅ Ya está activado" else "❌ Todavía sin activar"
        estado2?.text = if (elegido()) "✅ Star Teclado es tu teclado ahora" else "👉 Pulsa el botón y elige «Star Teclado»"
    }

    // ───────────────────────── pantalla ─────────────────────────

    private fun pintar() {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(14f), dp(16f), dp(14f), dp(30f))
        sv.addView(col)

        val cab = TextView(this)
        cab.text = Fuentes.convertir("Star Teclado", ESTILOS[5]) + " ⭐"
        cab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
        cab.setTextColor(rosa)
        cab.gravity = Gravity.CENTER
        col.addView(cab)
        val sub = texto("Escribe con letras bonitas en WhatsApp, Instagram, TikTok… y pon tu teclado de colores o con tu foto.", 15f, oscuro)
        sub.gravity = Gravity.CENTER
        col.addView(sub)

        // 1. Activar
        val t1 = tarjeta(col, "1. Activa el teclado")
        t1.addView(texto("Se abren los ajustes del móvil: enciende «Star Teclado». Android avisa de que un teclado puede leer lo que escribes; es un aviso normal de todos los teclados (este no guarda ni envía nada).", 14f, oscuro))
        estado1 = texto("", 15f, oscuro).also { it.typeface = Typeface.DEFAULT_BOLD; t1.addView(it) }
        t1.addView(boton("Activar Star Teclado", rosa) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })

        // 2. Elegir
        val t2 = tarjeta(col, "2. Elígelo como teclado")
        estado2 = texto("", 15f, oscuro).also { it.typeface = Typeface.DEFAULT_BOLD; t2.addView(it) }
        t2.addView(boton("Cambiar a Star Teclado", morado) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        t2.addView(texto("Truco: dejando pulsada la barra espaciadora puedes volver a tu otro teclado cuando quieras.", 13f, Color.GRAY))

        // 3. Probar
        val t3 = tarjeta(col, "3. Pruébalo aquí")
        val prueba = EditText(this)
        prueba.hint = "Escribe aquí para probar…"
        prueba.minLines = 2
        t3.addView(prueba)
        t3.addView(texto("Arriba del teclado eliges la letra. 🎨 cambia los colores. Deja pulsada a, e, i, o, u para poner la tilde (á é í ó ú); la y pone ü y la c pone ç. ♥☺ tiene corazones y emojis.", 13f, Color.GRAY))

        // Letras
        val t4 = tarjeta(col, "🔤 Letras del teclado")
        val filasEstilo = ArrayList<TextView>()
        ESTILOS.forEachIndexed { i, e ->
            val tv = TextView(this)
            tv.text = Fuentes.convertir("Hola Estrella 123", e) + "\n" + e.nombre
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            tv.setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(5f)
            tv.layoutParams = lp
            tv.setOnClickListener {
                aj.estilo = i
                marcarEstilos(filasEstilo)
                Toast.makeText(this, "Letra elegida: ${e.nombre}", Toast.LENGTH_SHORT).show()
            }
            filasEstilo.add(tv)
            t4.addView(tv)
        }
        marcarEstilos(filasEstilo)

        // Colores
        val t5 = tarjeta(col, "🎨 Colores del teclado")
        val chips = ArrayList<TextView>()
        var fila: LinearLayout? = null
        TEMAS.forEachIndexed { i, tema ->
            if (i % 2 == 0) {
                fila = LinearLayout(this).also { it.orientation = LinearLayout.HORIZONTAL; t5.addView(it) }
            }
            val tv = TextView(this)
            tv.text = tema.nombre
            tv.gravity = Gravity.CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setTextColor(tema.texto.let { if (tema.arcoiris) Color.WHITE else tema.textoEspecial })
            val lp = LinearLayout.LayoutParams(0, dp(58f), 1f)
            lp.setMargins(dp(4f), dp(4f), dp(4f), dp(4f))
            tv.layoutParams = lp
            tv.setOnClickListener {
                aj.tema = i
                marcarTemas(chips)
            }
            chips.add(tv)
            fila?.addView(tv)
        }
        marcarTemas(chips)

        // Foto
        val t6 = tarjeta(col, "🖼 Foto de fondo")
        val vista = ImageView(this)
        vista.scaleType = ImageView.ScaleType.CENTER_CROP
        vista.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(120f))
        val f = File(filesDir, "fondo.jpg")
        if (aj.foto != 0L && f.exists()) {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })?.let { vista.setImageBitmap(it) }
            t6.addView(vista)
        } else {
            t6.addView(texto("Pon una foto tuya detrás de las teclas (las teclas se ven un poco transparentes).", 14f, oscuro))
        }
        val bf = LinearLayout(this)
        bf.orientation = LinearLayout.HORIZONTAL
        bf.addView(boton("Elegir foto", rosa, 1f) {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            try { startActivityForResult(Intent.createChooser(i, "Elige una foto"), 7) } catch (_: Exception) {}
        })
        if (aj.foto != 0L) bf.addView(boton("Quitar foto", Color.GRAY, 1f) {
            aj.foto = 0L; File(filesDir, "fondo.jpg").delete(); pintar()
        })
        t6.addView(bf)

        // Ajustes
        val t7 = tarjeta(col, "⚙ Ajustes")
        t7.addView(texto("Tamaño de las teclas", 15f, oscuro))
        val bt = LinearLayout(this)
        bt.orientation = LinearLayout.HORIZONTAL
        val tams = listOf("Pequeño", "Normal", "Grande")
        val botTam = ArrayList<Button>()
        tams.forEachIndexed { i, n ->
            val b = boton(n, if (aj.alto == i) morado else Color.LTGRAY, 1f) {}
            b.setOnClickListener {
                aj.alto = i
                botTam.forEachIndexed { j, x -> x.background = fondoBoton(if (j == i) morado else Color.LTGRAY) }
            }
            botTam.add(b); bt.addView(b)
        }
        t7.addView(bt)
        val vib = Switch(this)
        vib.text = "Vibrar un poquito al pulsar"
        vib.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        vib.isChecked = aj.vibrar
        vib.setPadding(0, dp(10f), 0, 0)
        vib.setOnCheckedChangeListener { _, v -> aj.vibrar = v }
        t7.addView(vib)

        // Convertidor
        val t8 = tarjeta(col, "📋 Convertidor (copiar y pegar)")
        t8.addView(texto("Escribe tu texto y pulsa el estilo que quieras para copiarlo. Sirve aunque no uses el teclado (por ejemplo, para la biografía de Instagram).", 13f, Color.GRAY))
        val entrada = EditText(this)
        entrada.setText(textoConvertidor)
        t8.addView(entrada)
        val salida = LinearLayout(this)
        salida.orientation = LinearLayout.VERTICAL
        t8.addView(salida)
        fun rellenar() {
            salida.removeAllViews()
            ESTILOS.drop(1).forEach { e ->
                val r = Fuentes.convertir(textoConvertidor, e)
                val tv = TextView(this)
                tv.text = r
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                tv.setTextColor(oscuro)
                tv.setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                tv.background = fondoSuave()
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(5f)
                tv.layoutParams = lp
                tv.setOnClickListener {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Star Teclado", r))
                    Toast.makeText(this, "Copiado (${e.nombre})", Toast.LENGTH_SHORT).show()
                }
                salida.addView(tv)
            }
        }
        rellenar()
        entrada.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { textoConvertidor = s?.toString() ?: ""; rellenar() }
        })

        setContentView(sv)
        mirarEstado()
    }

    private fun marcarEstilos(l: List<TextView>) {
        val sel = aj.estilo
        l.forEachIndexed { i, tv ->
            val g = GradientDrawable()
            g.cornerRadius = dp(12f).toFloat()
            if (i == sel) { g.setColor(morado); tv.setTextColor(Color.WHITE) }
            else { g.setColor(Color.parseColor("#F4ECFF")); tv.setTextColor(oscuro) }
            tv.background = g
        }
    }

    private fun marcarTemas(l: List<TextView>) {
        val sel = aj.tema
        l.forEachIndexed { i, tv ->
            val t = TEMAS[i]
            val g = if (t.arcoiris) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, ARCOIRIS)
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(t.fondo1, t.fondo2))
            g.cornerRadius = dp(14f).toFloat()
            if (i == sel) g.setStroke(dp(4f), oscuro) else g.setStroke(dp(1f), Color.parseColor("#33000000"))
            tv.background = g
            tv.text = if (i == sel) "✓ ${t.nombre}" else t.nombre
        }
    }

    private fun tarjeta(padre: LinearLayout, titulo: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
        c.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18f).toFloat() }
        c.elevation = dp(3f).toFloat()
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14f)
        c.layoutParams = lp
        val t = TextView(this)
        t.text = titulo
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextColor(rosa)
        t.setPadding(0, 0, 0, dp(6f))
        c.addView(t)
        padre.addView(c)
        return c
    }

    private fun texto(s: String, sp: Float, color: Int): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.setPadding(0, dp(4f), 0, dp(4f))
        return t
    }

    private fun fondoBoton(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(24f).toFloat() }
    private fun fondoSuave() = GradientDrawable().apply { setColor(Color.parseColor("#FFF0F7")); cornerRadius = dp(10f).toFloat() }

    private fun boton(t: String, color: Int, peso: Float = 0f, accion: (View) -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.typeface = Typeface.DEFAULT_BOLD
        b.background = fondoBoton(color)
        b.stateListAnimator = null
        val lp = if (peso > 0f) LinearLayout.LayoutParams(0, dp(50f), peso)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50f))
        lp.setMargins(dp(3f), dp(8f), dp(3f), 0)
        b.layoutParams = lp
        b.setOnClickListener(accion)
        return b
    }

    // ───────────────────────── foto ─────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != 7 || resultCode != RESULT_OK || uri == null) return
        try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var muestra = 1
            while (maxOf(o.outWidth, o.outHeight) / (muestra * 2) >= 1600) muestra *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestra })
            } ?: throw IllegalStateException()
            FileOutputStream(File(filesDir, "fondo.jpg")).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            aj.foto = System.currentTimeMillis()
            Toast.makeText(this, "Foto puesta en el teclado ✨", Toast.LENGTH_SHORT).show()
            pintar()
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir esa foto", Toast.LENGTH_LONG).show()
        }
    }
}
