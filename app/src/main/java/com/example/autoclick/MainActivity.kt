package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {
    private lateinit var listBox: LinearLayout
    private lateinit var link: EditText
    private lateinit var linkWait: EditText
    private lateinit var repeat: EditText
    private lateinit var stepTimeout: EditText
    private lateinit var pause: EditText
    private lateinit var precision: EditText
    private var steps = mutableListOf<Step>()
    private val pickCode = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val ctx = this

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t
            setPadding(0, 32, 0, 0)
            root.addView(this)
        }
        fun button(t: String, f: () -> Unit) = Button(ctx).apply {
            text = t
            setOnClickListener { f() }
            root.addView(this)
        }
        fun field(type: Int, value: String) = EditText(ctx).apply {
            inputType = type
            setText(value)
            root.addView(this)
        }

        label("Pulsa automáticamente imágenes, palabras o puntos de la pantalla, en el orden que tú elijas.")
            .textSize = 16f

        button("1. Activar servicio de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        label("2. Pasos (se ejecutan en este orden):").textSize = 18f
        listBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            root.addView(this)
        }
        button("+ Añadir imagen") {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, pickCode
            )
        }
        button("+ Añadir palabra") { askWord() }
        button("+ Añadir punto (tú eliges dónde pulsar)") {
            val s = ClickService.instance
            if (s == null) toast("Primero activa el servicio de accesibilidad")
            else {
                s.pickPoint()
                toast("Ve a la pantalla que quieras y toca el punto exacto")
            }
        }

        label("3. Ajustes").textSize = 18f
        label("Enlace que se abre al iniciar (opcional):")
        link = field(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            prefs.getString("link", "") ?: ""
        )
        label("Segundos de espera tras abrir el enlace:")
        linkWait = field(InputType.TYPE_CLASS_NUMBER, prefs.getInt("linkWait", 3).toString())
        label("Veces que se repite toda la secuencia (0 = sin parar):")
        repeat = field(InputType.TYPE_CLASS_NUMBER, prefs.getInt("repeat", 1).toString())
        label("Pausa entre búsquedas y toques (milisegundos):")
        pause = field(InputType.TYPE_CLASS_NUMBER, prefs.getLong("delay", 500L).toString())
        label("Precisión de las imágenes (0.5 a 1.0):")
        precision = field(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL,
            prefs.getFloat("th", 0.8f).toString()
        )
        label("Segundos máximos esperando cada paso (0 = esperar siempre):")
        stepTimeout = field(InputType.TYPE_CLASS_NUMBER, prefs.getInt("timeout", 0).toString())

        label("4. Botón flotante").textSize = 18f
        button("Guardar y mostrar botón flotante") {
            saveSettings()
            val s = ClickService.instance
            if (s == null) toast("Primero activa el servicio de accesibilidad")
            else {
                s.showOverlay()
                toast("Arrastra el botón donde quieras. Toca ▶ para iniciar y ■ para parar. Mantenlo pulsado para ocultarlo.")
            }
        }

        setContentView(ScrollView(ctx).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        steps = StepStore.load(this)
        refreshList()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    private fun saveSettings() {
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putFloat("th", precision.text.toString().toFloatOrNull()?.coerceIn(0.3f, 1f) ?: 0.8f)
            .putLong("delay", (pause.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(100L))
            .putInt("repeat", (repeat.text.toString().toIntOrNull() ?: 1).coerceAtLeast(0))
            .putInt("timeout", (stepTimeout.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0))
            .putString("link", link.text.toString().trim())
            .putInt("linkWait", (linkWait.text.toString().toIntOrNull() ?: 3).coerceAtLeast(0))
            .apply()
    }

    private fun refreshList() {
        listBox.removeAllViews()
        if (steps.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "(Aún no hay pasos)" })
            return
        }
        steps.forEachIndexed { i, s ->
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 24, 0, 24)
            }

            val top = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            if (s.type == "image") {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val thumb = BitmapFactory.decodeFile(File(filesDir, s.value).path, opts)
                top.addView(ImageView(this@MainActivity).apply {
                    adjustViewBounds = true
                    maxHeight = 140
                    maxWidth = 280
                    if (thumb != null) setImageBitmap(thumb)
                })
            }
            val title = when (s.type) {
                "image" -> "Imagen"
                "word" -> "Palabra: " + s.value
                else -> "Punto (" + s.x.toInt() + ", " + s.y.toInt() + ")"
            }
            top.addView(TextView(this@MainActivity).apply {
                text = "  " + (i + 1) + ". " + title
                textSize = 16f
            })
            row.addView(top)

            val bar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.addView(TextView(this@MainActivity).apply { text = "Pulsar " })
            val timesField = EditText(this@MainActivity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(s.times.toString())
                minEms = 2
            }
            timesField.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(e: Editable?) {
                    s.times = (e?.toString()?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    StepStore.save(this@MainActivity, steps)
                }

                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
            bar.addView(timesField)
            bar.addView(TextView(this@MainActivity).apply { text = " veces  " })

            fun small(t: String, f: () -> Unit) {
                val b = Button(this@MainActivity)
                b.text = t
                b.setOnClickListener { f() }
                bar.addView(b)
            }
            small("↑") {
                if (i > 0) {
                    steps.add(i - 1, steps.removeAt(i))
                    StepStore.save(this@MainActivity, steps)
                    refreshList()
                }
            }
            small("↓") {
                if (i < steps.size - 1) {
                    steps.add(i + 1, steps.removeAt(i))
                    StepStore.save(this@MainActivity, steps)
                    refreshList()
                }
            }
            small("✕") {
                val removed = steps.removeAt(i)
                if (removed.type == "image") File(filesDir, removed.value).delete()
                StepStore.save(this@MainActivity, steps)
                refreshList()
            }
            row.addView(bar)
            listBox.addView(row)
        }
    }

    private fun askWord() {
        val input = EditText(this)
        input.hint = "Palabra o frase (varias, separadas por comas)"
        AlertDialog.Builder(this)
            .setTitle("Añadir palabra")
            .setView(input)
            .setPositiveButton("Añadir") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    steps.add(Step(System.currentTimeMillis(), "word", t))
                    StepStore.save(this, steps)
                    refreshList()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val id = System.currentTimeMillis()
        val name = "img_" + id + ".png"
        contentResolver.openInputStream(uri)?.use { input ->
            File(filesDir, name).outputStream().use { input.copyTo(it) }
        }
        steps.add(Step(id, "image", name))
        StepStore.save(this, steps)
        refreshList()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
