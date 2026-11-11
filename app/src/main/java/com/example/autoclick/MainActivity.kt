package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
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
    private val linkFields = ArrayList<EditText>()
    private val dateFields = ArrayList<EditText>()
    private lateinit var linkWait: EditText
    private lateinit var repeat: EditText
    private lateinit var stepTimeout: EditText
    private lateinit var pause: EditText
    private lateinit var precision: EditText
    private var steps = mutableListOf<Step>()
    private val pickCode = 42
    private val captureCode = 43

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

        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        label("Versión $ver").textSize = 12f

        button("❓ Cómo se usa (instrucciones)") { Help.show(this@MainActivity) }
        button("📋 Abrir el Etiquetador de sorteos") {
            startActivity(Intent(ctx, EtiquetadorActivity::class.java))
        }
        button("💬 Comentarios automáticos (guion)") {
            startActivity(Intent(ctx, CommentActivity::class.java))
        }

        button("1. Activar servicio de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            label(
                "Tu Android es anterior a la versión 11: para buscar imágenes y palabras hace falta " +
                    "permitir la captura de pantalla. Hazlo cada vez que abras la app."
            )
            button("1b. Permitir captura de pantalla") {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), captureCode)
            }
        }

        label("2. Pasos (se ejecutan en este orden):").textSize = 18f
        label(
            "Para el guion: «imagen 1» es la primera imagen de esta lista, «imagen 2» la segunda… " +
            "(solo se cuentan las imágenes, aunque haya otros pasos en medio)."
        )
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
        label(
            "Enlaces que se abren al iniciar (hasta 5). La fecha es solo una nota para ti: " +
                "no cambia nada. Se guardan hasta que pulses ✕."
        )
        val linksWatcher = object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { saveLinks() }
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
        }
        val oldLink = prefs.getString("link", "") ?: ""
        for (i in 0 until 5) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                root.addView(this)
            }
            val l = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                hint = "Enlace " + (i + 1)
                setText(prefs.getString("link$i", if (i == 0) oldLink else "") ?: "")
            }
            val d = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = "dd/mm/aaaa"
                minEms = 6
                setText(prefs.getString("date$i", "") ?: "")
            }
            val x = Button(ctx).apply {
                text = "✕"
                setOnClickListener { l.setText(""); d.setText("") }
            }
            row.addView(l, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(d)
            row.addView(x)
            l.addTextChangedListener(linksWatcher)
            d.addTextChangedListener(linksWatcher)
            linkFields.add(l)
            dateFields.add(d)
        }
        button("Guardar enlaces") {
            saveLinks()
            val n = linkFields.count { it.text.toString().trim().isNotEmpty() }
            toast(
                if (n == 0) "No hay ningún enlace escrito en los cuadros"
                else "$n enlace(s) guardado(s). En el guion, «enlace» abre el siguiente y «enlace 1» abre el primero"
            )
        }
        label("Segundos de espera tras abrir cada enlace:")
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
                toast("Arrastra el botón donde quieras. Toca ▶ para iniciar y ■ para parar. Con la ✕ de al lado lo quitas.")
            }
        }
        button("❌ Quitar botón flotante (y parar)") {
            val s = ClickService.instance
            if (s == null || !s.overlayShown()) toast("El botón flotante no está puesto")
            else {
                s.hideOverlay()
                toast("Botón flotante quitado")
            }
        }

        setContentView(ScrollView(ctx).apply { addView(root) })
        StarColors.watch(root)
    }

    override fun onResume() {
        super.onResume()
        AppLock.check(this)
        steps = StepStore.load(this)
        refreshList()
        // El Etiquetador puede haber cambiado los enlaces mientras esta pantalla estaba en segundo plano.
        // Se leen todos antes de escribir ninguno, porque cada cambio de texto vuelve a guardar los cuadros.
        val p = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        val fresh = linkFields.indices.map { p.getString("link$it", "") ?: "" }
        for (i in linkFields.indices) {
            if (linkFields[i].text.toString() != fresh[i]) linkFields[i].setText(fresh[i])
        }
    }

    override fun onPause() {
        super.onPause()
        AppLock.onPause()
        saveSettings()
    }

    private fun saveSettings() {
        getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putFloat("th", precision.text.toString().toFloatOrNull()?.coerceIn(0.3f, 1f) ?: 0.8f)
            .putLong("delay", (pause.text.toString().toLongOrNull() ?: 500L).coerceAtLeast(100L))
            .putInt("repeat", (repeat.text.toString().toIntOrNull() ?: 1).coerceAtLeast(0))
            .putInt("timeout", (stepTimeout.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0))
            .putInt("linkWait", (linkWait.text.toString().toIntOrNull() ?: 3).coerceAtLeast(0))
            .apply()
        saveLinks()
    }

    private fun saveLinks() {
        val e = getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
        for (i in 0 until linkFields.size) {
            e.putString("link$i", linkFields[i].text.toString().trim())
            e.putString("date$i", dateFields[i].text.toString().trim())
        }
        e.apply()
    }

    private fun refreshList() {
        listBox.removeAllViews()
        if (steps.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "(Aún no hay pasos)" })
            return
        }
        var imgNo = 0
        steps.forEachIndexed { i, s ->
            if (s.type == "image") imgNo++
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
                "image" -> "Imagen $imgNo"
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
        if (requestCode == captureCode) {
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, ScreenCaptureService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                toast("Captura de pantalla permitida")
            } else {
                toast("Has cancelado el permiso de captura de pantalla")
            }
            return
        }
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
