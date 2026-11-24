package com.estrella.tablasquest

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Tablas Quest: el juego está entero en assets/tablas.html (sin cambios).
 * El progreso se guarda en el localStorage del WebView.
 * Aquí solo se añade: el botón «atrás» del móvil (cierra la ventana abierta o sale).
 */
class MainActivity : Activity() {
    private lateinit var web: WebView

    /** Atrás: cierra el cuadro abierto si lo hay; si no, sale de la app. */
    private val backJs = """
        (function(){
          var m=document.querySelector('.modal.on');
          if(m){m.classList.remove('on');return 'ok';}
          return 'exit';
        })()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true                 // guarda monedas, niveles y el gatito
        web.settings.mediaPlaybackRequiresUserGesture = false // sonidos
        web.settings.textZoom = 100                           // que el tamaño de letra del móvil no descuadre el juego
        web.setBackgroundColor(Color.TRANSPARENT)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = WebView.OVER_SCROLL_NEVER
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Cualquier enlace externo se abre en el navegador, no dentro del juego.
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: Exception) {}
                return true
            }
        }
        val html = assets.open("tablas.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        // Dirección fija para que el progreso guardado no se pierda entre versiones.
        web.loadDataWithBaseURL("https://tablasquest.local/", html, "text/html", "UTF-8", null)
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript(backJs) { r ->
            if (r == null || r.contains("exit")) finish()
        }
    }

    override fun onPause() {
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
