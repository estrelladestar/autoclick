package com.example.autoclick

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Captura de pantalla para Android 10 o inferior (por ejemplo EMUI 12 de Huawei),
 * donde AccessibilityService.takeScreenshot() no existe.
 * Usa MediaProjection y necesita un servicio en primer plano (aparece una notificación).
 */
class ScreenCaptureService : Service() {
    companion object {
        @Volatile
        var instance: ScreenCaptureService? = null
        private const val CHANNEL = "captura"
    }

    private var projection: MediaProjection? = null
    private var projectionCb: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var w = 0
    private var h = 0
    private var dpi = 0
    private var last: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        release()
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mgr.getMediaProjection(code, data)
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    release()
                    stopSelf()
                }
            }
            p.registerCallback(cb, Handler(Looper.getMainLooper()))
            projectionCb = cb
            projection = p
            readMetrics()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader = r
            display = p.createVirtualDisplay(
                "autoclick", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
            instance = this
        } catch (e: Exception) {
            release()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Captura de pantalla", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("AutoClick Imagen")
            .setContentText("Captura de pantalla activa")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun readMetrics() {
        val dm = realMetrics()
        w = dm.widthPixels
        h = dm.heightPixels
        dpi = dm.densityDpi
    }

    /** Última imagen de la pantalla, o null si todavía no ha llegado ningún cuadro. */
    @Synchronized
    fun grab(): Bitmap? {
        val dm = realMetrics()
        if (dm.widthPixels != w || dm.heightPixels != h) resize(dm)
        val r = reader ?: return null
        val img = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img != null) {
            try {
                val plane = img.planes[0]
                val pad = (plane.rowStride - plane.pixelStride * img.width) / plane.pixelStride
                val tmp = Bitmap.createBitmap(img.width + pad, img.height, Bitmap.Config.ARGB_8888)
                tmp.copyPixelsFromBuffer(plane.buffer)
                val out = Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
                if (out !== tmp) tmp.recycle()
                last = out
            } finally {
                img.close()
            }
        }
        return last
    }

    /** La pantalla ha girado: se ajusta el tamaño de la captura. */
    private fun resize(dm: DisplayMetrics) {
        w = dm.widthPixels
        h = dm.heightPixels
        dpi = dm.densityDpi
        val old = reader
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        display?.resize(w, h, dpi)
        display?.surface = r.surface
        old?.close()
        last = null
    }

    private fun release() {
        instance = null
        display?.release()
        display = null
        reader?.close()
        reader = null
        last = null
        val p = projection
        val cb = projectionCb
        projection = null
        projectionCb = null
        try {
            if (p != null && cb != null) p.unregisterCallback(cb)
            p?.stop()
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
