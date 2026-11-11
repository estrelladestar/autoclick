package com.estrella.mimundo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.File

/** Avisos de la mascota («¡tiene hambre!») aunque la app esté cerrada, y guardar fotos del avatar. */
object Avisos {
    private const val CHANNEL = "mascota"

    fun programar(ctx: Context, minutos: Int, titulo: String, texto: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(ctx, AvisoReceiver::class.java).putExtra("t", titulo).putExtra("x", texto)
        val pi = PendingIntent.getBroadcast(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val cuando = System.currentTimeMillis() + minutos.coerceIn(20, 24 * 60) * 60_000L
        am.set(AlarmManager.RTC_WAKEUP, cuando, pi)
    }

    fun mostrar(ctx: Context, titulo: String, texto: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de tu mascota", NotificationManager.IMPORTANCE_DEFAULT))
        val abrir = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .build()
        try { nm.notify(7, n) } catch (e: SecurityException) { }
    }

    /** Guarda la foto (PNG en base64) en la galería, carpeta Pictures/MiMundo. Devuelve un mensaje. */
    fun guardarFoto(ctx: Context, base64: String, nombre: String): String {
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, nombre)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MiMundo")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                    ?: return "No se pudo guardar la foto"
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "📸 Foto guardada en tu galería (carpeta MiMundo)"
            } else {
                val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MiMundo").apply { mkdirs() }
                File(dir, nombre).writeBytes(bytes)
                "📸 Foto guardada en " + dir.absolutePath
            }
        } catch (e: Exception) {
            "No se pudo guardar la foto"
        }
    }
}

class AvisoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Avisos.mostrar(ctx, intent.getStringExtra("t") ?: "Mi Mundo", intent.getStringExtra("x") ?: "¡Tu mascota te echa de menos!")
    }
}
