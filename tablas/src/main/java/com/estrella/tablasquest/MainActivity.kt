package com.estrella.tablasquest

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView

/** Tablas Quest: una pantalla web (assets/tablas.html). El progreso se guarda en el propio WebView. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true   // para guardar el progreso (localStorage)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.loadUrl("file:///android_asset/tablas.html")
        setContentView(web)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Atrás: si estás dentro de una pantalla, vuelve al inicio; desde el inicio, sale de la app
        web.evaluateJavascript("(function(){var p=document.querySelector('.pant.on');if(p&&p.id!=='inicio'){volver();return 1}return 0})()") { r ->
            if (r != "1") runOnUiThread { super.onBackPressed() }
        }
    }

    override fun onPause() {
        super.onPause()
        web.evaluateJavascript("try{speechSynthesis.cancel()}catch(e){}", null)
    }
}
