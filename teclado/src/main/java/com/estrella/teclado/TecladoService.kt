package com.estrella.teclado

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.BreakIterator

/**
 * Star Teclado: un teclado QWERTY en español que escribe con letras bonitas.
 * Arriba hay una tira para elegir la letra; 🎨 cambia los colores y ⚙ abre la app.
 * En los campos de contraseña escribe siempre con letra normal.
 */
class TecladoService : InputMethodService() {
    private lateinit var aj: Ajustes
    private val h = Handler(Looper.getMainLooper())

    private var shift = 0          // 0 minúsculas, 1 una mayúscula, 2 bloqueo de mayúsculas
    private var ultimoShift = 0L
    private var pagina = 0         // 0 letras, 1 números, 2 corazones y emojis
    private var esClave = false
    private var scrollTira = -1

    private val letras = ArrayList<Pair<TextView, Char>>()
    private var teclaShift: TextView? = null
    private var tira: HorizontalScrollView? = null

    private var fotoCache: Bitmap? = null
    private var fotoClave = ""

    private val acentos = mapOf('a' to 'á', 'e' to 'é', 'i' to 'í', 'o' to 'ó', 'u' to 'ú', 'n' to 'ñ', 'c' to 'ç', 'y' to 'ü')

    override fun onCreate() {
        super.onCreate()
        aj = Ajustes(this)
    }

    override fun onCreateInputView(): View = construir()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val clase = info.inputType and InputType.TYPE_MASK_CLASS
        val variacion = info.inputType and InputType.TYPE_MASK_VARIATION
        esClave = (clase == InputType.TYPE_CLASS_TEXT && (variacion == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variacion == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variacion == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (clase == InputType.TYPE_CLASS_NUMBER && variacion == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        if (!restarting) pagina = if (clase == InputType.TYPE_CLASS_NUMBER || clase == InputType.TYPE_CLASS_PHONE) 1 else 0
        shift = 0
        setInputView(construir())
        mirarMayus()
    }

    // ───────────────────────── escribir ─────────────────────────

    private fun estilo(): Estilo = if (esClave) ESTILOS[0] else ESTILOS[aj.estilo]

    private fun escribir(t: String) {
        currentInputConnection?.commitText(Fuentes.convertir(t, estilo()), 1)
    }

    private fun letra(c: Char) {
        val ch = if (shift > 0) c.uppercaseChar() else c
        escribir(ch.toString())
        if (shift == 1) { shift = 0; refrescarLetras() }
    }

    private fun borrar() {
        val ic = currentInputConnection ?: return
        val sel = ic.getSelectedText(0)
        if (!sel.isNullOrEmpty()) { ic.commitText("", 1); return }
        val antes = ic.getTextBeforeCursor(24, 0)?.toString() ?: ""
        if (antes.isEmpty()) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return }
        // Borra un «carácter que se ve» entero: letra bonita + tilde + raya, emojis, etc.
        val bi = BreakIterator.getCharacterInstance()
        bi.setText(antes)
        val fin = bi.last()
        var ini = bi.previous()
        if (ini == BreakIterator.DONE) ini = 0
        ic.deleteSurroundingText((fin - ini).coerceAtLeast(1), 0)
    }

    private fun intro() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val accion = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val sinAccion = ((ei?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multilinea = ((ei?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        if (!sinAccion && !multilinea && accion != EditorInfo.IME_ACTION_NONE && accion != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(accion)
        } else if (multilinea) {
            ic.commitText("\n", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        mirarMayus()
    }

    private fun simboloIntro(): String {
        val ei = currentInputEditorInfo ?: return "⏎"
        if ((ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) return "⏎"
        if ((ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "⏎"
        return when (ei.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "🔍"
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO -> "➤"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "⏎"
        }
    }

    /** Pone la mayúscula sola al empezar una frase. */
    private fun mirarMayus() {
        if (shift == 2) return
        val ic = currentInputConnection ?: return
        val tipo = currentInputEditorInfo?.inputType ?: 0
        val nuevo = if (tipo != 0 && !esClave && ic.getCursorCapsMode(tipo) != 0) 1 else 0
        if (nuevo != shift) { shift = nuevo; refrescarLetras() }
    }

    private fun pulsarShift() {
        val ahora = SystemClock.uptimeMillis()
        shift = when {
            shift == 1 && ahora - ultimoShift < 400 -> 2
            shift == 0 -> 1
            else -> 0
        }
        ultimoShift = ahora
        refrescarLetras()
    }

    private fun refrescarLetras() {
        val e = estilo()
        for ((tv, c) in letras) tv.text = Fuentes.convertir((if (shift > 0) c.uppercaseChar() else c).toString(), e)
        teclaShift?.let {
            it.text = when (shift) { 0 -> "⇧"; 1 -> "⬆"; else -> "⇪" }
            it.isSelected = shift == 2
        }
    }

    // ───────────────────────── dibujar el teclado ─────────────────────────

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun construir(): View {
        letras.clear(); teclaShift = null
        val tema = TEMAS[aj.tema]
        val conFoto = aj.foto != 0L
        val altoTecla = dp(when (aj.alto) { 0 -> 42f; 2 -> 58f; else -> 50f })
        val altoTira = dp(42f)
        val margen = dp(2.5f)

        val raiz = LinearLayout(this)
        raiz.orientation = LinearLayout.VERTICAL
        raiz.setPadding(dp(3f), dp(3f), dp(3f), dp(6f))

        // Tira de arriba: 🎨  [estilos…]  ⚙
        val barra = LinearLayout(this)
        barra.orientation = LinearLayout.HORIZONTAL
        barra.gravity = Gravity.CENTER_VERTICAL
        barra.addView(botonBarra("🎨", tema) {
            aj.tema = (aj.tema + 1) % TEMAS.size
            guardarScroll(); setInputView(construir())
        })
        val sv = HorizontalScrollView(this)
        sv.isHorizontalScrollBarEnabled = false
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        val actual = if (esClave) 0 else aj.estilo
        ESTILOS.forEachIndexed { i, e ->
            val chip = TextView(this)
            chip.text = Fuentes.convertir("Abc", e)
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            chip.gravity = Gravity.CENTER
            chip.setPadding(dp(12f), 0, dp(12f), 0)
            chip.maxLines = 1
            val sel = i == actual
            chip.setTextColor(if (sel) contraste(tema.acento) else tema.textoEspecial)
            chip.background = redondo(if (sel) tema.acento else alfa(tema.especial, if (conFoto) 0xB0 else 0xFF), dp(16f).toFloat())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34f))
            lp.setMargins(dp(3f), 0, dp(3f), 0)
            chip.layoutParams = lp
            chip.setOnClickListener { v ->
                toque(v)
                if (esClave) return@setOnClickListener
                aj.estilo = i
                guardarScroll(); setInputView(construir())
            }
            fila.addView(chip)
        }
        sv.addView(fila)
        sv.layoutParams = LinearLayout.LayoutParams(0, altoTira, 1f)
        barra.addView(sv)
        barra.addView(botonBarra("⚙", tema) {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(i) } catch (_: Exception) {}
        })
        raiz.addView(barra)
        tira = sv
        val sx = scrollTira
        if (sx >= 0) sv.post { sv.scrollTo(sx, 0) }
        else sv.post {
            val chip = fila.getChildAt(actual)
            if (chip != null) sv.scrollTo((chip.left - dp(60f)).coerceAtLeast(0), 0)
        }

        // Filas de teclas
        val filas: List<List<K>> = when (pagina) {
            1 -> listOf(
                "1234567890".map { K.Txt(it.toString(), digito = true) },
                listOf("@", "#", "€", "%", "&", "*", "-", "+", "(", ")").map { K.Txt(it) },
                listOf<K>(K.Pag("♥☺", 2, 1.5f)) + listOf("¿", "¡", "!", "?", ":", ";", "\"", "'").map { K.Txt(it) } + K.Borrar,
                listOf(K.Pag("ABC", 0, 1.5f), K.Txt(",", 1f), K.Espacio, K.Txt(".", 1f), K.Intro),
            )
            2 -> listOf(
                listOf("♥", "♡", "★", "☆", "✿", "❀", "✨", "☾", "☀", "❄").map { K.Txt(it) },
                listOf("😊", "😍", "🥰", "😂", "😘", "👍", "🙏", "🎉", "💖", "🌸").map { K.Txt(it) },
                listOf<K>(K.Pag("?123", 1, 1.5f)) + listOf("✓", "♪", "♛", "ღ", "➳", "∞", "✧", "/").map { K.Txt(it) } + K.Borrar,
                listOf(K.Pag("ABC", 0, 1.5f), K.Txt(",", 1f), K.Espacio, K.Txt(".", 1f), K.Intro),
            )
            else -> listOf(
                "qwertyuiop".map { K.Letra(it) },
                "asdfghjklñ".map { K.Letra(it) },
                listOf<K>(K.Shift) + "zxcvbnm".map { K.Letra(it) } + K.Borrar,
                listOf(K.Pag("?123", 1, 1.5f), K.Txt(",", 1f), K.Espacio, K.Txt(".", 1f), K.Intro),
            )
        }
        for (f in filas) {
            val ll = LinearLayout(this)
            ll.orientation = LinearLayout.HORIZONTAL
            f.forEachIndexed { col, k -> ll.addView(tecla(k, col, tema, conFoto, altoTecla, margen)) }
            raiz.addView(ll)
        }

        // Fondo: foto o degradado
        val altoTotal = altoTira + filas.size * (altoTecla + 2 * margen) + dp(9f)
        val foto = if (conFoto) cargarFoto(resources.displayMetrics.widthPixels, altoTotal) else null
        raiz.background = if (foto != null) BitmapDrawable(resources, foto)
        else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(tema.fondo1, tema.fondo2))

        refrescarLetras()
        return raiz
    }

    private fun guardarScroll() { scrollTira = tira?.scrollX ?: -1 }

    private sealed class K(val peso: Float) {
        class Letra(val c: Char) : K(1f)
        class Txt(val t: String, p: Float = 1f, val digito: Boolean = false) : K(p)
        class Pag(val t: String, val a: Int, p: Float) : K(p)
        object Shift : K(1.5f)
        object Borrar : K(1.5f)
        object Espacio : K(4.5f)
        object Intro : K(1.5f)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun tecla(k: K, col: Int, tema: Tema, conFoto: Boolean, alto: Int, margen: Int): TextView {
        val tv = TextView(this)
        tv.gravity = Gravity.CENTER
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.includeFontPadding = false
        val lp = LinearLayout.LayoutParams(0, alto, k.peso)
        lp.setMargins(margen, margen, margen, margen)
        tv.layoutParams = lp

        val especial = k is K.Shift || k is K.Borrar || k is K.Pag || k is K.Intro
        val opaco = if (conFoto) 0xC0 else 0xFF
        var color = if (especial) tema.especial else tema.tecla
        var texto = if (especial) tema.textoEspecial else tema.texto
        if (tema.arcoiris && (k is K.Letra || k is K.Txt)) { color = ARCOIRIS[col % ARCOIRIS.size]; texto = Color.WHITE }
        if (k is K.Intro) { color = tema.acento; texto = contraste(tema.acento) }
        val radio = dp(9f).toFloat()
        val sld = StateListDrawable()
        sld.addState(intArrayOf(android.R.attr.state_pressed), redondo(mezclar(color, tema.acento), radio))
        sld.addState(intArrayOf(android.R.attr.state_selected), redondo(tema.acento, radio))
        sld.addState(intArrayOf(), redondo(alfa(color, opaco), radio))
        tv.background = sld
        tv.setTextColor(texto)
        tv.elevation = dp(1.5f).toFloat()
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)

        when (k) {
            is K.Letra -> {
                letras.add(tv to k.c)
                tv.setOnClickListener { toque(it); letra(k.c) }
                acentos[k.c]?.let { con -> tv.setOnLongClickListener { toque(it); letra(con); true } }
            }
            is K.Txt -> {
                tv.text = if (k.digito) Fuentes.convertir(k.t, estilo()) else k.t
                tv.setOnClickListener {
                    toque(it)
                    if (k.digito) escribir(k.t) else currentInputConnection?.commitText(k.t, 1)
                    if (k.t == "." || k.t == "?" || k.t == "!") mirarMayus()
                }
            }
            is K.Pag -> {
                tv.text = k.t
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                tv.typeface = Typeface.DEFAULT_BOLD
                tv.setOnClickListener { toque(it); pagina = k.a; guardarScroll(); setInputView(construir()); mirarMayus() }
            }
            K.Shift -> {
                teclaShift = tv
                tv.setOnClickListener { toque(it); pulsarShift() }
            }
            K.Borrar -> {
                tv.text = "⌫"
                val repetir = object : Runnable {
                    override fun run() { borrar(); h.postDelayed(this, 55) }
                }
                tv.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { v.isPressed = true; toque(v); borrar(); h.postDelayed(repetir, 420) }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; h.removeCallbacks(repetir); mirarMayus() }
                    }
                    true
                }
            }
            K.Espacio -> {
                tv.text = estilo().nombre
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                tv.alpha = 0.95f
                tv.setOnClickListener { toque(it); escribir(" "); mirarMayus() }
                tv.setOnLongClickListener {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); true
                }
            }
            K.Intro -> {
                tv.text = simboloIntro()
                tv.setOnClickListener { toque(it); intro() }
            }
        }
        return tv
    }

    private fun botonBarra(t: String, tema: Tema, accion: () -> Unit): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.gravity = Gravity.CENTER
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        tv.setTextColor(tema.textoEspecial)
        tv.background = redondo(alfa(tema.especial, 0xD0), dp(17f).toFloat())
        val lp = LinearLayout.LayoutParams(dp(40f), dp(34f))
        lp.setMargins(dp(3f), 0, dp(3f), 0)
        tv.layoutParams = lp
        tv.setOnClickListener { toque(it); accion() }
        return tv
    }

    private fun toque(v: View) {
        if (aj.vibrar) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
    }

    /** La foto de fondo recortada al tamaño del teclado (como «recortar al centro»). */
    private fun cargarFoto(ancho: Int, alto: Int): Bitmap? {
        val clave = "${aj.foto}-$ancho-$alto"
        if (clave == fotoClave && fotoCache != null) return fotoCache
        val f = File(filesDir, "fondo.jpg")
        if (!f.exists() || ancho <= 0 || alto <= 0) return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var muestra = 1
            while (o.outWidth / (muestra * 2) >= ancho && o.outHeight / (muestra * 2) >= alto) muestra *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestra }) ?: return null
            val objetivo = ancho.toFloat() / alto
            var w = bmp.width; var hh = (w / objetivo).toInt()
            if (hh > bmp.height) { hh = bmp.height; w = (hh * objetivo).toInt() }
            val rec = Bitmap.createBitmap(bmp, (bmp.width - w) / 2, (bmp.height - hh) / 2, w.coerceAtLeast(1), hh.coerceAtLeast(1))
            val fin = Bitmap.createScaledBitmap(rec, ancho, alto, true)
            fotoCache = fin; fotoClave = clave
            fin
        } catch (e: Throwable) { null }
    }
}

// ───────────────────────── colores ─────────────────────────

fun redondo(color: Int, radio: Float): Drawable = GradientDrawable().apply { setColor(color); cornerRadius = radio }

fun alfa(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

fun mezclar(a: Int, b: Int): Int = Color.rgb(
    (Color.red(a) + Color.red(b)) / 2, (Color.green(a) + Color.green(b)) / 2, (Color.blue(a) + Color.blue(b)) / 2,
)

/** Blanco o negro, lo que se lea mejor encima de ese color. */
fun contraste(c: Int): Int =
    if (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114 > 160) Color.parseColor("#222222") else Color.WHITE
