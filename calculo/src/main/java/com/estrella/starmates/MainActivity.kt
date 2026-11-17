package com.estrella.starmates

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Locale

/**
 * Star Mates: sumas, restas, multiplicar, dividir, tablas y reto de 1 minuto (assets/mates.html).
 * El progreso se guarda en SharedPreferences («starmates»).
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    val r = t.setLanguage(Locale("es", "ES"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale("es"))
                    t.setSpeechRate(0.95f)
                    ttsReady = true
                }
            }
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.setBackgroundColor(Color.TRANSPARENT)
        web.addJavascriptInterface(Bridge(), "StarMates")
        val html = assets.open("mates.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        web.loadDataWithBaseURL("https://starmates.local/", html, "text/html", "UTF-8", null)
        setContentView(web)
    }

    inner class Bridge {
        private val main = Handler(Looper.getMainLooper())
        private val prefs = getSharedPreferences("starmates", Context.MODE_PRIVATE)

        @JavascriptInterface fun load(): String = prefs.getString("state", "") ?: ""
        @JavascriptInterface fun save(json: String) { prefs.edit().putString("state", json).apply() }
        @JavascriptInterface fun speak(text: String) {
            main.post { if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mates") }
        }
        @JavascriptInterface fun vibrate(ms: Int) {
            try {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(10L, 500L), VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // El botón atrás del móvil vuelve al inicio de la app; desde el inicio, sale.
        web.evaluateJavascript("(function(){var p=document.querySelector('.pant.on');if(p&&p.id!=='inicio'){document.getElementById('salir').click();return 1}return 0})()") { r ->
            if (r != "1") runOnUiThread { super.onBackPressed() }
        }
    }

    override fun onPause() {
        super.onPause()
        tts?.stop()
        web.evaluateJavascript("window.pausarTodo&&pausarTodo()", null)
    }

    override fun onDestroy() {
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
