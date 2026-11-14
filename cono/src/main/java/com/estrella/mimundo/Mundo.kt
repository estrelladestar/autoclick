package com.estrella.mimundo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import java.util.Locale

/**
 * Mi Mundo: avatar, mascota tipo Tamagotchi, preguntas para ganar monedas y tienda (assets/mundo.html).
 * Los datos se guardan en SharedPreferences («mundo»).
 */
object Mundo {
    /** Petición de micrófono pendiente mientras el sistema pregunta el permiso (ensayo del poema). */
    var pendingMic: PermissionRequest? = null
    const val MIC_CODE = 77
    @Volatile var ttsStatus = "init"

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun webView(activity: Activity, mini: Boolean, say: (String, String) -> Unit, hush: () -> Unit): WebView {
        val web = WebView(activity)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false  // música y sonidos sin tocar antes
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = !mini
        web.addJavascriptInterface(Bridge(activity, mini, say, hush), "MundoApp")
        // Micrófono para «Grabarme recitando» (taller de poemas)
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                activity.runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingMic?.deny()
                            pendingMic = request
                            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_CODE)
                        }
                    } else {
                        request.deny()
                    }
                }
            }
        }
        val html = activity.assets.open("mundo.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        web.loadDataWithBaseURL("https://mundo.local/", html, "text/html", "UTF-8", null)
        return web
    }

    class Bridge(
        private val activity: Activity,
        private val mini: Boolean,
        private val say: (String, String) -> Unit,
        private val hush: () -> Unit
    ) {
        private val main = Handler(Looper.getMainLooper())
        private val prefs = activity.getSharedPreferences("mundo", Context.MODE_PRIVATE)

        @JavascriptInterface fun isMini(): Boolean = mini
        @JavascriptInterface fun load(): String = prefs.getString("state", "") ?: ""
        @JavascriptInterface fun save(json: String) { prefs.edit().putString("state", json).apply() }
        @JavascriptInterface fun speak(text: String) { main.post { say(text, "es") } }
        @JavascriptInterface fun speakLang(text: String, lang: String) { main.post { say(text, lang) } }
        @JavascriptInterface fun stopSpeak() { main.post { hush() } }
        /** Estado de la voz: ok, init, noengine, nolang */
        @JavascriptInterface fun ttsStatus(): String = Mundo.ttsStatus
        @JavascriptInterface fun openTtsSettings() {
            main.post {
                try { activity.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) { try { activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (e2: Exception) {} }
            }
        }
        @JavascriptInterface fun installVoice() {
            main.post {
                try { activity.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (e: Exception) {
                    try { activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=com.google.android.tts")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e2: Exception) { try { activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e3: Exception) {} }
                }
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            try {
                @Suppress("DEPRECATION")
                val v = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(ms.toLong().coerceIn(10L, 500L), VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (e: Exception) {
            }
        }

    }
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: Pair<String, String>? = null
    private var triedGoogle = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mundo.ttsStatus = "init"
        startTts(null)
        web = Mundo.webView(this, false, { text, lang -> speak(text, lang) }, { tts?.stop() })
        setContentView(web)
    }

    /** Arranca el motor de voz. Si el de por defecto falla, prueba el de Google. */
    private fun startTts(engine: String?) {
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { t ->
                    curLang = ""
                    val r = t.setLanguage(Locale("es", "ES"))
                    val r2 = if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale("es")) else r
                    curLang = "es"
                    t.setPitch(1.6f)   // voz aguda de mascota
                    t.setSpeechRate(1.0f)
                    ttsReady = true
                    Mundo.ttsStatus = if (r2 == TextToSpeech.LANG_MISSING_DATA || r2 == TextToSpeech.LANG_NOT_SUPPORTED) "nolang" else "ok"
                    pending?.let { speak(it.first, it.second) }
                    pending = null
                }
            } else if (!triedGoogle) {
                triedGoogle = true
                try { tts?.shutdown() } catch (e: Exception) {}
                startTts("com.google.android.tts")
            } else {
                Mundo.ttsStatus = "noengine"
            }
        }
        tts = if (engine == null) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
    }

    private var curLang = ""

    private fun setLang(t: TextToSpeech, lang: String) {
        if (lang == curLang) return
        curLang = lang
        if (lang == "en") {
            val r = t.setLanguage(Locale.UK)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale.US)
        } else {
            val r = t.setLanguage(Locale("es", "ES"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) t.setLanguage(Locale("es"))
        }
    }

    private fun speak(text: String, lang: String) {
        if (!ttsReady) { pending = Pair(text, lang); return }
        val t = tts ?: return
        setLang(t, lang)
        // Las palabras en inglés van a la cola para no cortar lo que esté diciendo en español.
        val max = try { TextToSpeech.getMaxSpeechInputLength() - 10 } catch (e: Exception) { 3900 }
        val parts = if (text.length <= max) listOf(text) else text.chunked(max)
        parts.forEachIndexed { i, p ->
            val mode = if (i == 0 && lang != "en") TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val r = t.speak(p, mode, null, "mascota$i")
            if (r == TextToSpeech.ERROR && Mundo.ttsStatus == "ok") Mundo.ttsStatus = "error"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != Mundo.MIC_CODE) return
        val req = Mundo.pendingMic ?: return
        Mundo.pendingMic = null
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
        } else {
            req.deny()
        }
    }

    override fun onPause() {
        super.onPause()
        tts?.stop()
        web.evaluateJavascript("window.pauseAll&&pauseAll()", null)
    }

    override fun onResume() {
        super.onResume()
        web.evaluateJavascript("window.resumeAll&&resumeAll()", null)
    }

    override fun onDestroy() {
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }
}
