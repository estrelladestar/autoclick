package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.widget.*
import java.io.File

/**
 * «Mis fotos»: un álbum dentro de la app donde se guardan todas las imágenes que quieras (PNG, JPG…),
 * para no tener que subirlas cada vez. Desde aquí se elige cuál es «imagen 1», «imagen 2»…
 */
object Galeria {
    const val PEDIR = 55

    fun dir(ctx: Context): File = File(ctx.filesDir, "galeria").apply { mkdirs() }

    fun lista(ctx: Context): List<File> =
        (dir(ctx).listFiles()?.filter { it.isFile } ?: emptyList()).sortedByDescending { it.lastModified() }

    /** Abre el selector de archivos (se ven también los PNG de Descargas) y deja elegir varias fotos. */
    fun pedir(act: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { act.startActivityForResult(i, PEDIR) } catch (e: Exception) {
            act.startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, PEDIR)
        }
    }

    /** Las fotos que vienen del selector (una o varias). */
    fun uris(data: Intent?): List<Uri> {
        val out = ArrayList<Uri>()
        val clip = data?.clipData
        if (clip != null) for (k in 0 until clip.itemCount) clip.getItemAt(k).uri?.let { out.add(it) }
        else data?.data?.let { out.add(it) }
        return out
    }

    /** Guarda una foto en «Mis fotos». Devuelve el archivo guardado. */
    fun guardar(ctx: Context, uri: Uri): File? {
        val tipo = try { ctx.contentResolver.getType(uri) } catch (e: Exception) { null } ?: ""
        val ext = when { "png" in tipo -> "png"; "webp" in tipo -> "webp"; else -> "jpg" }
        val f = File(dir(ctx), "foto_" + System.currentTimeMillis() + "_" + (Math.random() * 1000).toInt() + "." + ext)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            if (BitmapFactory.decodeFile(f.path) == null) { f.delete(); null } else f
        } catch (e: Exception) { f.delete(); null }
    }

    /** La primera vez, mete en «Mis fotos» las imágenes que ya tienes puestas, para no perderlas. */
    fun iniciar(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (p.getBoolean("galeria_ini", false)) return
        StepStore.load(ctx).filter { it.type == "image" }.forEach { st ->
            val src = File(ctx.filesDir, st.value)
            if (src.exists()) try { src.copyTo(File(dir(ctx), "foto_" + st.id + "_" + src.name), overwrite = true) } catch (e: Exception) {}
        }
        p.edit().putBoolean("galeria_ini", true).apply()
    }

    /** Pone una foto de «Mis fotos» como imagen del guion: nueva al final, o en lugar de otra (sin cambiar su número). */
    fun usar(ctx: Context, foto: File, enLugarDe: Long?): Int {
        val all = StepStore.load(ctx)
        val id = System.currentTimeMillis()
        val name = "img_" + id + "_" + foto.name
        foto.copyTo(File(ctx.filesDir, name), overwrite = true)
        val k = if (enLugarDe == null) -1 else all.indexOfFirst { it.id == enLugarDe }
        if (k >= 0) {
            val old = all[k]
            File(ctx.filesDir, old.value).delete()
            all[k] = Step(id, "image", name, times = old.times)
        } else all.add(Step(id, "image", name))
        StepStore.save(ctx, all)
        val imgs = all.filter { it.type == "image" }
        return imgs.indexOfFirst { it.id == id } + 1
    }

    /**
     * Muestra «Mis fotos» en una cuadrícula. Al tocar una: si [enLugarDe] no es null la pone en lugar de esa imagen;
     * si no, pregunta si añadirla como imagen nueva o borrarla del álbum.
     */
    fun mostrar(act: Activity, enLugarDe: Long?, titulo: String, cambiado: () -> Unit) {
        iniciar(act)
        val fotos = lista(act)
        val dp = act.resources.displayMetrics.density
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0) }
        var dlg: AlertDialog? = null
        if (fotos.isEmpty()) box.addView(TextView(act).apply {
            text = "Todavía no tienes fotos guardadas.\nPulsa «+ Guardar fotos» para añadir todas las que quieras (PNG o JPG)."
            textSize = 16f; setPadding(0, (16 * dp).toInt(), 0, 0)
        })
        val grid = GridLayout(act).apply { columnCount = 3 }
        val cell = ((act.resources.displayMetrics.widthPixels - 110 * dp) / 3).toInt()
        fotos.forEach { f ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeFile(f.path, opts)
            val v = ImageView(act).apply {
                if (bmp != null) setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.parseColor("#EDE7F6"))
                val pad = (6 * dp).toInt(); setPadding(pad, pad, pad, pad)
                setOnClickListener {
                    if (enLugarDe != null) {
                        val n = usar(act, f, enLugarDe)
                        Toast.makeText(act, "Puesta como imagen $n", Toast.LENGTH_SHORT).show()
                        dlg?.dismiss(); cambiado()
                    } else {
                        val nueva = StepStore.load(act).count { it.type == "image" } + 1
                        AlertDialog.Builder(act)
                            .setTitle("¿Qué hago con esta foto?")
                            .setItems(arrayOf("Usarla como imagen $nueva (nueva)", "Borrarla de Mis fotos")) { _, w ->
                                if (w == 0) {
                                    val n = usar(act, f, null)
                                    Toast.makeText(act, "Añadida como imagen $n", Toast.LENGTH_SHORT).show()
                                    dlg?.dismiss(); cambiado()
                                } else {
                                    f.delete()
                                    Toast.makeText(act, "Borrada de Mis fotos (las del guion no cambian)", Toast.LENGTH_SHORT).show()
                                    dlg?.dismiss(); mostrar(act, enLugarDe, titulo, cambiado)
                                }
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = cell; height = cell
                val m = (4 * dp).toInt(); setMargins(m, m, m, m)
            })
        }
        box.addView(grid)
        val scroll = ScrollView(act).apply { addView(box) }
        dlg = AlertDialog.Builder(act)
            .setTitle(titulo + " (" + fotos.size + ")")
            .setView(scroll)
            .setPositiveButton("+ Guardar fotos") { _, _ -> pedir(act) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Después de elegir fotos en el selector: las guarda en «Mis fotos» y vuelve a enseñar el álbum. */
    fun recibir(act: Activity, data: Intent?, cambiado: () -> Unit) {
        val us = uris(data)
        var ok = 0
        us.forEach { u ->
            try { act.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            if (guardar(act, u) != null) ok++
        }
        Toast.makeText(act, if (ok == 1) "Foto guardada en Mis fotos" else "$ok fotos guardadas en Mis fotos", Toast.LENGTH_LONG).show()
        mostrar(act, null, "📁 Mis fotos", cambiado)
    }
}
