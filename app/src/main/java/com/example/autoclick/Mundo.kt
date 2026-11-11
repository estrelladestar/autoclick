package com.example.autoclick

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
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
 * «Mi mundo»: avatar, mascota tipo Tamagotchi, preguntas para ganar monedas y tienda (assets/mundo.html).
 * Los datos se guardan en SharedPreferences («mundo») para que la pantalla principal también vea la mascota.
 */
object Mundo {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun webView(activity: Activity, mini: Boolean, say: (String) -> Unit, hush: () -> Unit): WebView {
        val web = WebView(activity)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = !mini
        web.addJavascriptInterface(Bridge(activity, mini, say, hush), "MundoApp")
        val html = activity.assets.open("mundo.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        web.loadDataWithBaseURL("https://mundo.local/", html, "text/html", "UTF-8", null)
        return web
    }

    class Bridge(
        private val activity: Activity,
        private val mini: Boolean,
        private val say: (String) -> Unit,
        private val hush: () -> Unit
    ) {
        private val main = Handler(Looper.getMainLooper())
        private val prefs = activity.getSharedPreferences("mundo", Context.MODE_PRIVATE)

        @JavascriptInterface fun isMini(): Boolean = mini
        @JavascriptInterface fun load(): String = prefs.getString("state", "") ?: ""
        @JavascriptInterface fun save(json: String) { prefs.edit().putString("state", json).apply() }
        @JavascriptInterface fun speak(text: String) { main.post { say(text) } }
        @JavascriptInterface fun stopSpeak() { main.post { hush() } }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                @Suppress("DEPRECATION")
                val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(10L, 500L), VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun openMundo() {
            main.post { activity.startActivity(Intent(activity, MundoActivity::class.java)) }
        }
    }
}

class MundoActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    val r = t.setLanguage(Locale("es", "ES"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale("es"))
                    t.setPitch(1.6f)   // voz aguda de mascota
                    t.setSpeechRate(1.05f)
                    ttsReady = true
                }
            }
        }
        web = Mundo.webView(this, false, { text -> speak(text) }, { tts?.stop() })
        setContentView(web)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mascota")
    }

    override fun onPause() {
        super.onPause()
        tts?.stop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
