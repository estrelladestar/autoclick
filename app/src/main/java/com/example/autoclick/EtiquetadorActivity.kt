package com.example.autoclick

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Muestra el Etiquetador de sorteos (assets/etiquetador.html) dentro de la app
 * y lo conecta con el guion de comentarios automáticos.
 */
class EtiquetadorActivity : Activity() {
    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)

        // Cualquier enlace que se abra desde la página sale a Instagram o al navegador.
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                openExternal(request.url)
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val tmp = WebView(this@EtiquetadorActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        openExternal(r.url)
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = tmp
                resultMsg.sendToTarget()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AutoClickApp")

        val html = assets.open("etiquetador.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        web.loadDataWithBaseURL("https://etiquetador.local/", html, "text/html", "UTF-8", null)
        setContentView(web)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun openAccessibility() {
            main.post { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        /** Guarda el cuadro de menciones de la página para que el guion pueda usarlo. */
        @JavascriptInterface
        fun saveFriends(text: String) {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("friends", text).apply()
        }

        /** Guarda los comentarios generados en la lista de la página («TUS COMENTARIOS» del guion). */
        @JavascriptInterface
        fun saveComments(text: String) {
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("comments", text).apply()
        }

        /** Guarda el guion y los comentarios e inicia el guion. Devuelve un mensaje para mostrar. */
        @JavascriptInterface
        fun run(script: String, comments: String): String {
            val service = ClickService.instance
                ?: return "Primero activa el servicio de accesibilidad de la app (botón 1)."
            val list = comments.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (list.isEmpty()) return "No hay comentarios."
            getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
                .putString("script", script)
                .putString("comments", comments)
                .apply()
            main.post {
                Macro.start(service, script, list)
                moveTaskToBack(true)
            }
            return "Iniciado con " + list.size + " comentarios. Abre la publicación en Instagram y toca ■ en el botón flotante para parar."
        }
    }
}
