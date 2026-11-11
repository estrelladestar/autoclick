package com.example.autoclick

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Contraseña para entrar en Star App.
 * La primera vez la elige la persona; se guarda cifrada (solo una huella SHA-256 con sal, nunca la contraseña).
 * Se pide al abrir la app y otra vez si ha estado más de 5 minutos fuera.
 * El botón flotante y el guion en marcha no se ven afectados.
 */
object AppLock {
    private const val KEY_HASH = "lock_hash"
    private const val KEY_SALT = "lock_salt"
    private const val RELOCK_MS = 5 * 60 * 1000L

    @Volatile private var unlocked = false
    @Volatile private var lastSeen = 0L
    private var dialogOpen = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun isSet(ctx: Context): Boolean = prefs(ctx).getString(KEY_HASH, null) != null

    private fun hash(pw: String, salt: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest((salt + ":" + pw).toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    private fun save(ctx: Context, pw: String) {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_SALT, salt).putString(KEY_HASH, hash(pw, salt)).apply()
    }

    private fun matches(ctx: Context, pw: String): Boolean {
        val p = prefs(ctx)
        val salt = p.getString(KEY_SALT, null) ?: return false
        return hash(pw, salt) == p.getString(KEY_HASH, null)
    }

    /** Llamar en onPause de cada pantalla. */
    fun onPause() {
        lastSeen = System.currentTimeMillis()
    }

    /** Llamar en onResume de cada pantalla: si hace falta, tapa la pantalla y pide la contraseña. */
    fun check(a: Activity) {
        val now = System.currentTimeMillis()
        if (unlocked && (lastSeen == 0L || now - lastSeen < RELOCK_MS)) {
            show(a, true)
            return
        }
        unlocked = false
        show(a, false)
        if (dialogOpen) return
        if (!isSet(a)) askCreate(a) else askPassword(a)
    }

    private fun show(a: Activity, visible: Boolean) {
        a.findViewById<View>(android.R.id.content)?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun field(a: Activity, hint: String) = EditText(a).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        isSingleLine = true
    }

    private fun box(a: Activity, vararg fields: EditText) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * a.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        fields.forEach { addView(it) }
    }

    private fun askCreate(a: Activity) {
        val f1 = field(a, "Contraseña (mínimo 4)")
        val f2 = field(a, "Repite la contraseña")
        dialogOpen = true
        val d = AlertDialog.Builder(a)
            .setTitle("🔒 Crea tu contraseña")
            .setMessage("Star App te la pedirá cada vez que la abras, para que nadie más pueda entrar.\n\n" +
                "Apúntala bien: si la olvidas, habrá que borrar los datos de la app y se perderá lo guardado " +
                "(guarda antes una copia con «💾 Guardar copia»).")
            .setView(box(a, f1, f2))
            .setCancelable(false)
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Salir") { _, _ -> dialogOpen = false; a.finishAffinity() }
            .create()
        d.setOnDismissListener { dialogOpen = false }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = f1.text.toString()
                val p2 = f2.text.toString()
                when {
                    p1.length < 4 -> Toast.makeText(a, "Pon al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                    p1 != p2 -> Toast.makeText(a, "Las dos contraseñas no son iguales", Toast.LENGTH_SHORT).show()
                    else -> {
                        save(a, p1)
                        unlocked = true
                        lastSeen = 0L
                        dialogOpen = false
                        show(a, true)
                        Toast.makeText(a, "Contraseña guardada", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                    }
                }
            }
        }
        d.show()
    }

    private fun askPassword(a: Activity) {
        val f = field(a, "Contraseña")
        dialogOpen = true
        val d = AlertDialog.Builder(a)
            .setTitle("🔒 Star App")
            .setMessage("Escribe tu contraseña para entrar.")
            .setView(box(a, f))
            .setCancelable(false)
            .setPositiveButton("Entrar", null)
            .setNegativeButton("Salir") { _, _ -> dialogOpen = false; a.finishAffinity() }
            .create()
        d.setOnDismissListener { dialogOpen = false }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (matches(a, f.text.toString())) {
                    unlocked = true
                    lastSeen = 0L
                    dialogOpen = false
                    show(a, true)
                    d.dismiss()
                } else {
                    f.setText("")
                    Toast.makeText(a, "Contraseña incorrecta", Toast.LENGTH_SHORT).show()
                }
            }
        }
        d.show()
    }

    /** Cambiar la contraseña (pide la actual). */
    fun change(a: Activity) {
        val cur = field(a, "Contraseña actual")
        val n1 = field(a, "Contraseña nueva (mínimo 4)")
        val n2 = field(a, "Repite la nueva")
        val d = AlertDialog.Builder(a)
            .setTitle("🔒 Cambiar contraseña")
            .setView(box(a, cur, n1, n2))
            .setPositiveButton("Cambiar", null)
            .setNegativeButton("Cancelar", null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val p1 = n1.text.toString()
                when {
                    !matches(a, cur.text.toString()) -> Toast.makeText(a, "La contraseña actual no es correcta", Toast.LENGTH_SHORT).show()
                    p1.length < 4 -> Toast.makeText(a, "Pon al menos 4 caracteres", Toast.LENGTH_SHORT).show()
                    p1 != n2.text.toString() -> Toast.makeText(a, "Las dos contraseñas nuevas no son iguales", Toast.LENGTH_SHORT).show()
                    else -> {
                        save(a, p1)
                        Toast.makeText(a, "Contraseña cambiada", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                    }
                }
            }
        }
        d.show()
    }
}
