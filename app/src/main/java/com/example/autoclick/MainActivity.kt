package com.example.autoclick

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var preview: ImageView
    private lateinit var word: EditText
    private lateinit var precision: EditText
    private lateinit var pause: EditText
    private lateinit var once: CheckBox
    private lateinit var useImg: CheckBox
    private val pickCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun label(t: String) = TextView(ctx).apply { text = t; root.addView(this) }
        fun button(t: String, f: () -> Unit) = Button(ctx).apply {
            text = t; setOnClickListener { f() }; root.addView(this)
        }

        label("Busca una palabra o una imagen en la pantalla y toca sobre ella.").textSize = 16f
        button("1. Activar servicio de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("2. Elegir imagen a buscar") {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, pickCode
            )
        }
        preview = ImageView(ctx).apply {
            adjustViewBounds = true
            maxHeight = 400
            root.addView(this)
        }
        File(filesDir, "target.png").takeIf { it.exists() }?.let {
            preview.setImageBitmap(BitmapFactory.decodeFile(it.path))
        }

        label("Palabras a buscar, separadas por comas:")
        word = EditText(ctx).apply {
            setText(prefs.getString("word", "")); root.addView(this)
        }

        label("Precisión de la imagen (0.5 a 1.0):")
        precision = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(prefs.getFloat("th", 0.8f).toString()); root.addView(this)
        }
        label("Pausa entre búsquedas (milisegundos):")
        pause = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getLong("delay", 500L).toString()); root.addView(this)
        }
        useImg = CheckBox(ctx).apply {
            text = "Buscar también la imagen elegida"
            isChecked = prefs.getBoolean("img", false); root.addView(this)
        }
        once = CheckBox(ctx).apply {
            text = "Parar tras el primer toque"
            isChecked = prefs.getBoolean("once", false); root.addView(this)
        }

        button("3. Guardar y mostrar botón flotante") {
            prefs.edit()
                .putFloat("th", precision.text.toString().toFloatOrNull()?.coerceIn(0.3f, 1f) ?: 0.8f)
                .putLong("delay", (pause.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(100L))
                .putString("word", word.text.toString().trim())
                .putBoolean("img", useImg.isChecked)
                .putBoolean("once", once.isChecked)
                .apply()
            val s = ClickService.instance
            if (s == null) toast("Primero activa el servicio de accesibilidad")
            else { s.showOverlay(); toast("Ve a la app y pulsa ▶ en el botón flotante") }
        }

        setContentView(ScrollView(ctx).apply { addView(root) })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        contentResolver.openInputStream(uri)?.use { input ->
            File(filesDir, "target.png").outputStream().use { input.copyTo(it) }
        }
        preview.setImageBitmap(BitmapFactory.decodeFile(File(filesDir, "target.png").path))
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
