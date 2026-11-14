package com.estrella.joyas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Star Joyas: el juego está entero en assets/joyas.html (el mismo archivo que la versión HTML).
 * El progreso (niveles, estrellas, monedas) se guarda en el localStorage del WebView.
 * Aquí solo se añade: vibración de verdad, el botón «atrás» y la pausa al salir.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView

    /** navigator.vibrate no funciona dentro de un WebView: lo redirigimos a la app. */
    private val shim = """
        <script>
        (function(){
          try{
            navigator.vibrate=function(p){
              try{JoyasApp.vibrate(JSON.stringify(Array.isArray(p)?p:[p]))}catch(e){}
              return true;
            };
          }catch(e){}
        })();
        </script>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true                 // guarda niveles, estrellas y monedas
        web.settings.mediaPlaybackRequiresUserGesture = false // sonidos y música
        web.settings.textZoom = 100                           // que el tamaño de letra del móvil no descuadre el juego
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.addJavascriptInterface(Bridge(this), "JoyasApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: Exception) {}
                return true
            }
        }
        val html = assets.open("joyas.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replaceFirst("<head>", "<head>\n$shim")
        // Dirección fija para que el progreso guardado no se pierda entre versiones.
        web.loadDataWithBaseURL("https://starjoyas.local/", html, "text/html", "UTF-8", null)
        setContentView(web)
    }

    class Bridge(private val ctx: Context) {
        @JavascriptInterface
        fun vibrate(json: String) {
            try {
                val nums = json.trim('[', ']').split(',').mapNotNull { it.trim().toDoubleOrNull()?.toLong() }
                if (nums.isEmpty() || nums.all { it <= 0L }) return
                @Suppress("DEPRECATION")
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
                if (nums.size == 1) {
                    v.vibrate(VibrationEffect.createOneShot(nums[0].coerceIn(5L, 500L), VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    val pattern = LongArray(nums.size + 1)
                    pattern[0] = 0L
                    for (i in nums.indices) pattern[i + 1] = nums[i].coerceIn(0L, 500L)
                    v.vibrate(VibrationEffect.createWaveform(pattern, -1))
                }
            } catch (e: Exception) {
            }
        }
    }

    /** Atrás: cierra la ventana abierta; jugando, pausa; en el mapa, vuelve al inicio; en el inicio, sale. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("(function(){try{return starBack()}catch(e){return 'exit'}})()") { r ->
            if (r == null || r.contains("exit")) finish()
        }
    }

    override fun onPause() {
        web.evaluateJavascript("try{starPause()}catch(e){}", null)
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
