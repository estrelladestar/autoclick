package com.example.autoclick

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*

class CommentActivity : Activity() {
    private val defaultScript = """# Cada línea es un paso. Lo que va después de | son segundos de espera extra.
# Abre el siguiente enlace de la app, espera a que cargue,
# abre la caja de comentarios, escribe @amigos al azar de tu cuadro de menciones
# (cuántos, lo eliges abajo) y pulsa la imagen 1 (la de la lista de pasos de la app).
enlace
esperar 8
campo
esperar 1
escribir menciones
esperar 1.5
imagen 1 | 30
repetir 10"""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun label(t: String) = TextView(ctx).apply { text = t; root.addView(this) }

        Button(ctx).apply {
            text = "❓ Cómo se usa (instrucciones)"
            setOnClickListener { Help.show(this@CommentActivity) }
            root.addView(this)
        }

        label(
            "GUION (un paso por línea):\n" +
            "• esperar 5 → espera 5 segundos\n" +
            "• tocar Texto → toca el botón o texto que diga así\n" +
            "• tocar 500,1200 → toca esa posición de la pantalla\n" +
            "• escribir lista → pone el siguiente comentario de tu lista\n" +
            "• escribir azar → pone un comentario de tu lista al azar\n" +
            "• escribir menciones → pone @amigos al azar de tu cuadro de menciones (cuántos: selector de abajo)\n" +
            "• escribir menciones 3 → igual, con 3 menciones (de 1 a 5)\n" +
            "• enlace → abre el siguiente enlace guardado (o enlace 2 = el enlace 2)\n" +
            "• imagen 1 → busca en pantalla la imagen 1 de la app y la pulsa (imagen 2 = otra)\n" +
            "• escribir Hola → pone ese texto fijo\n" +
            "• campo → selecciona la caja de texto\n" +
            "• Añade | 30 al final de cualquier línea para esperar 30 s después\n" +
            "• repetir 10 → repite todo el guion 10 veces\n" +
            "• # al inicio = nota (se ignora)"
        )
        val script = EditText(ctx).apply {
            minLines = 8
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("script", defaultScript))
            root.addView(this)
        }
        Button(ctx).apply {
            text = "Restaurar guion de ejemplo"
            setOnClickListener { script.setText(defaultScript) }
            root.addView(this)
        }
        label("TUS COMENTARIOS, uno por línea (cópialos del Etiquetador con «Copiar la lista en un solo texto»):")
        val box = EditText(ctx).apply {
            minLines = 6
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("comments", ""))
            root.addView(this)
        }
        label("TUS MENCIONES, una por línea (se rellenan solas desde el cuadro «Menciones» del Etiquetador; también puedes escribirlas aquí):")
        val friendsBox = EditText(ctx).apply {
            minLines = 4
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(prefs.getString("friends", ""))
            root.addView(this)
        }
        label("Menciones por comentario (1 a 5), para «escribir menciones»:")
        val mentionsN = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, listOf("1", "2", "3", "4", "5"))
            setSelection((prefs.getInt("mentionsN", 2) - 1).coerceIn(0, 4))
            root.addView(this)
        }
        label("Límites de seguridad: máximo 50 comentarios por tanda y al menos 10 s entre un comentario y el siguiente.")

        Button(ctx).apply {
            text = "Iniciar"
            setOnClickListener {
                val list = box.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                val s = ClickService.instance
                when {
                    s == null -> toast("Primero activa el servicio de accesibilidad de AutoClick Imagen")
                    else -> {
                        prefs.edit()
                            .putString("script", script.text.toString())
                            .putString("comments", box.text.toString())
                            .putString("friends", friendsBox.text.toString())
                            .putInt("mentionsN", mentionsN.selectedItemPosition + 1)
                            .apply()
                        Macro.start(s, script.text.toString(), list)
                        moveTaskToBack(true)
                    }
                }
            }
            root.addView(this)
        }
        setContentView(ScrollView(ctx).apply { addView(root) })
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
