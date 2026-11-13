package com.estrella.sorteos

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Planes como AppSorteos: Gratis (hasta 300 comentarios por sorteo), Starter (2.000), Creator (5.000), Agency (sin límite).
 *
 * Código de activación (12 letras, ej. C1A7-K3QZ-9MPX):
 *   1 letra de plan (S, C, A) + 3 de fecha de caducidad (días desde el 1-1-2026) + 8 de firma.
 *   La firma es un HMAC con la clave secreta y el «código de este móvil», así que cada código
 *   solo vale para el móvil para el que se hizo.
 */
object Licencia {
    private const val ABC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // sin I, L, O, U para no confundir
    private const val PREFS = "licencia"
    const val FOREVER = 32767

    fun hasKey() = BuildConfig.LIC_KEY.isNotEmpty()

    private fun b32(bytes: ByteArray, chars: Int): String {
        val sb = StringBuilder()
        var buf = 0L; var bits = 0; var i = 0
        while (sb.length < chars) {
            if (bits < 5) { buf = (buf shl 8) or ((if (i < bytes.size) bytes[i].toInt() else 0) and 0xFF).toLong(); bits += 8; i++ }
            sb.append(ABC[((buf shr (bits - 5)) and 31).toInt()]); bits -= 5
        }
        return sb.toString()
    }

    private fun num(n: Int, chars: Int): String {
        val sb = StringBuilder(); var v = n
        repeat(chars) { sb.insert(0, ABC[v and 31]); v = v shr 5 }
        return sb.toString()
    }

    private fun unnum(s: String): Int { var v = 0; for (c in s) { val d = ABC.indexOf(c); if (d < 0) return -1; v = (v shl 5) or d }; return v }

    /** Quita guiones/espacios y corrige letras que se confunden (O→0, I/L→1). */
    fun clean(s: String) = s.uppercase().replace('O', '0').replace('I', '1').replace('L', '1').filter { ABC.indexOf(it) >= 0 }

    /** Código de este móvil (8 letras). No cambia aunque se reinstale la app. */
    @SuppressLint("HardwareIds")
    fun deviceCode(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "x"
        val h = MessageDigest.getInstance("SHA-256").digest(("sorteos-dev|$id").toByteArray())
        return b32(h, 8)
    }

    private fun sign(device: String, plan: Char, exp: Int): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.LIC_KEY.toByteArray(), "HmacSHA256"))
        return b32(mac.doFinal("SS1|$device|$plan|$exp".toByteArray()), 8)
    }

    private fun dayOf(millis: Long): Int {
        val base = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply { clear(); set(2026, 0, 1) }
        return ((millis - base.timeInMillis) / 86_400_000L).toInt()
    }

    /**
     * El día de hoy SIN fiarse solo del reloj del móvil:
     * se guarda la fecha más adelantada que se ha visto (del móvil o de internet).
     * Así, atrasar la fecha del móvil no alarga el plan.
     */
    fun today(ctx: Context): Int {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getInt("maxDay", Int.MIN_VALUE)
        val now = dayOf(System.currentTimeMillis())
        return if (now > seen) { prefs.edit().putInt("maxDay", now).apply(); now } else seen
    }

    /** Fecha real sacada de internet (cabecera «Date» de una web). */
    fun saveNetTime(ctx: Context, millis: Long) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = dayOf(millis)
        if (d > prefs.getInt("maxDay", Int.MIN_VALUE)) prefs.edit().putInt("maxDay", d).apply()
    }

    /** Pregunta la hora a internet (en segundo plano). Si no hay conexión, no pasa nada. */
    fun syncNetTime(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            for (u in listOf("https://www.google.com", "https://www.cloudflare.com", "https://github.com")) {
                try {
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.requestMethod = "HEAD"; c.connectTimeout = 6000; c.readTimeout = 6000; c.useCaches = false
                    c.connect()
                    val t = c.date
                    c.disconnect()
                    if (t > 0L) { saveNetTime(app, t); return@Thread }
                } catch (e: Exception) {}
            }
        }.start()
    }

    fun dateOf(days: Int): String {
        if (days >= FOREVER) return "para siempre"
        val c = Calendar.getInstance(TimeZone.getTimeZone("Europe/Madrid")).apply { clear(); set(2026, 0, 1); add(Calendar.DAY_OF_YEAR, days) }
        return "%02d/%02d/%d".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR))
    }

    /** Crea un código (solo en modo vendedor). */
    fun make(ctx: Context, device: String, plan: Char, days: Int): String {
        val d = clean(device)
        val exp = if (days >= FOREVER) FOREVER else (today(ctx) + days).coerceAtMost(FOREVER - 1)
        val code = plan.toString() + num(exp, 3) + sign(d, plan, exp)
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8)
    }

    /** Comprueba un código para este móvil. Devuelve null si no vale. */
    fun check(ctx: Context, input: String): Pair<Char, Int>? {
        if (!hasKey()) return null
        val c = clean(input)
        if (c.length != 12 || c[0] !in "SCA") return null
        val exp = unnum(c.substring(1, 4)); if (exp < 0) return null
        if (sign(deviceCode(ctx), c[0], exp) != c.substring(4)) return null
        return c[0] to exp
    }

    fun activate(ctx: Context, input: String): JSONObject {
        val r = check(ctx, input) ?: return JSONObject().put("ok", false).put("why", if (hasKey()) "Ese código no es válido para este móvil" else "Esta versión de la app no acepta códigos todavía")
        if (r.second < today(ctx)) return JSONObject().put("ok", false).put("why", "Ese código ya ha caducado (${dateOf(r.second)})")
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", clean(input)).apply()
        return current(ctx).put("ok", true)
    }

    /** Plan actual: {"plan":"F"} o {"plan":"C","exp":"13/12/2026","left":30}. */
    fun current(ctx: Context): JSONObject {
        val code = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("code", null)
        val r = if (code != null) check(ctx, code) else null
        val out = JSONObject().put("device", deviceCode(ctx)).put("canActivate", hasKey())
        if (r == null) return out.put("plan", "F")
        val left = r.second - today(ctx)
        if (left < 0) return out.put("plan", "F").put("expired", r.first.toString()).put("expDate", dateOf(r.second))
        return out.put("plan", r.first.toString()).put("exp", dateOf(r.second)).put("left", if (r.second >= FOREVER) -1 else left)
    }

    /** Modo vendedor: se abre escribiendo la misma frase que el secreto SORTEOS_CLAVE. */
    fun vendorOk(phrase: String): Boolean {
        if (!hasKey()) return false
        val h = MessageDigest.getInstance("SHA-256").digest(phrase.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return h == BuildConfig.LIC_KEY
    }
}
