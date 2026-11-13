package com.estrella.sorteos

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Sorteos Star: la app entera está en assets/sorteos.html.
 * Aquí se añade lo que el navegador no puede hacer:
 *  - Recoger los comentarios de una publicación de Instagram o TikTok a partir de su enlace
 *    (se abre la página en un segundo WebView, se cargan todos los comentarios y se leen).
 *  - Guardar y compartir el certificado, copiar/pegar, vibrar, elegir imágenes y el botón «atrás».
 */
class MainActivity : Activity() {
    private lateinit var web: WebView          // la app
    private lateinit var colBox: LinearLayout  // pantalla de recogida (barra + página)
    private lateinit var colWeb: WebView       // la publicación de Instagram / TikTok
    private lateinit var colStatus: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var collecting = false
    private var liveMode = false      // true: chat de un directo; false: comentarios de una publicación
    private var liveStarted = false   // se ha pulsado «Empezar a contar»
    private var noChatRounds = 0
    private lateinit var useBtn: Button
    private lateinit var colHint: TextView
    private var lastCount = -1
    private var sameRounds = 0
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pendingSave: Pair<String, String>? = null
    private var pageReady = false
    @Volatile private var vendor = false   // modo vendedor abierto en esta sesión

    private val desktopUA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** navigator.vibrate no funciona dentro de un WebView: lo redirigimos a la app. */
    private val shim = """
        <script>
        (function(){try{navigator.vibrate=function(p){try{SorteoApp.vibrate(JSON.stringify(Array.isArray(p)?p:[p]))}catch(e){}return true;};}catch(e){}})();
        </script>
    """.trimIndent()

    /**
     * Un paso de la recogida: pulsa «cargar más comentarios», baja hasta el final
     * y devuelve el texto de los comentarios que se ven.
     * TikTok enseña apodos, así que allí se saca el @usuario del enlace de cada comentario.
     */
    private val stepJs = """
        (function(){
          try{
            var more=/(cargar m[aá]s comentarios|load more comments|ver m[aá]s comentarios|view more comments|more comments|m[aá]s comentarios|ver todos los \d+ comentarios|view all \d+ comments)/i;
            var nodes=document.querySelectorAll('[aria-label],button,[role=button]');
            for(var i=0;i<nodes.length;i++){
              var n=nodes[i],t=(n.getAttribute('aria-label')||n.innerText||'').trim();
              if(t.length<45&&more.test(t)&&!/respuest|repl/i.test(t)){try{n.click()}catch(e){}}
            }
            var all=document.querySelectorAll('div,section,ul');
            for(var j=0;j<all.length;j++){
              var el=all[j];
              if(el.scrollHeight>el.clientHeight+30){
                var oy=getComputedStyle(el).overflowY;
                if(oy==='auto'||oy==='scroll')el.scrollTop=el.scrollHeight;
              }
            }
            window.scrollTo(0,document.body.scrollHeight);
            if(location.hostname.indexOf('tiktok')>=0){
              var items=document.querySelectorAll('[data-e2e^="comment-level-1"]'),out=[];
              for(var k=0;k<items.length;k++){
                var p=items[k],a=null,up=p;
                for(var d=0;d<7&&up&&!a;d++){up=up.parentElement;if(up)a=up.querySelector('a[href*="/@"]');}
                if(!a)continue;
                var m=(a.getAttribute('href')||'').match(/\/@([A-Za-z0-9._]{2,30})/);
                if(m)out.push('@'+m[1]+': '+(p.innerText||'').replace(/\s+/g,' ').trim());
              }
              if(out.length)return out.join('\n');
            }
            var r=document.querySelector('[data-e2e="comment-list"]')||document.querySelector('[class*="CommentList"]')||document.querySelector('article')||document.querySelector('main')||document.body;
            return r.innerText;
          }catch(e){return ''}
        })()
    """.trimIndent()

    /**
     * Lee los mensajes del chat del directo que se ven ahora mismo.
     * Devuelve {"v": mensajes vistos, "m": [[usuario, texto], ...]}.
     * TikTok: cada mensaje es [data-e2e="chat-message"]; si hay enlace al perfil se usa el @usuario, si no el apodo.
     * Instagram y otros: mensajes que empiezan por un enlace al perfil (/usuario/).
     * Se quitan los avisos («se ha unido», regalos, me gusta…).
     */
    private val liveJs = """
        (function(){try{
          var out=[];
          var bad=/(se ha unido|se uni[oó]|joined|ha empezado a seguir|started following|followed the host|le ha dado|liked the live|ha enviado|sent |ha compartido|shared the live|dio me gusta|está viendo|is watching)/i;
          function add(u,t){u=(u||'').replace(/\s+/g,' ').trim();t=(t||'').replace(/\s+/g,' ').trim();
            if(!u||u.length>40||!t)return; if(bad.test(t)&&t.length<70)return; out.push([u,t]);}
          var tk=document.querySelectorAll('[data-e2e="chat-message"]');
          if(tk.length){
            for(var i=0;i<tk.length;i++){var m=tk[i],u='';
              var a=m.querySelector('a[href*="/@"]');
              if(a){var mm=(a.getAttribute('href')||'').match(/\/@([A-Za-z0-9._]{2,30})/);if(mm)u='@'+mm[1];}
              var ne=m.querySelector('[data-e2e="message-owner-name"]');
              var nick=ne?((ne.getAttribute('title')||ne.innerText||'').trim()):'';
              if(!u)u=nick;
              var t=(m.innerText||'');
              if(ne&&ne.innerText){var k=t.indexOf(ne.innerText);if(k>=0)t=t.slice(k+ne.innerText.length);}
              t=t.replace(/^\s*[:：·]\s*/,'');
              add(u,t);
            }
            return JSON.stringify({v:tk.length,m:out});
          }
          var links=document.querySelectorAll('a[href^="/"]'),v=0;
          for(var j=0;j<links.length;j++){var l=links[j];
            var mm2=(l.getAttribute('href')||'').match(/^\/([A-Za-z0-9._]{2,30})\/?$/);if(!mm2)continue;
            var name=(l.innerText||'').trim();if(name.toLowerCase()!==mm2[1].toLowerCase())continue;
            var row=l.parentElement;
            for(var d=0;d<4&&row&&(row.innerText||'').trim()===name;d++)row=row.parentElement;
            if(!row)continue;var tx=(row.innerText||'').trim();
            if(tx.indexOf(name)===0)tx=tx.slice(name.length);
            if(!tx.trim()||tx.length>300)continue;v++;add('@'+mm2[1],tx);
          }
          return JSON.stringify({v:v,m:out});
        }catch(e){return '{"v":0,"m":[]}'}})()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)

        // ---------- la app ----------
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true                 // tiendas, reglas e historial
        web.settings.mediaPlaybackRequiresUserGesture = false // sonidos del sorteo
        web.settings.textZoom = 100
        web.setBackgroundColor(Color.TRANSPARENT)
        web.addJavascriptInterface(Bridge(), "SorteoApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (e: Exception) {}
                return true
            }
            override fun onPageFinished(view: WebView?, url: String?) { pageReady = true; handleShare(intent); deliverLector() }
        }
        web.webChromeClient = object : WebChromeClient() {
            // Para elegir el logo o abrir un archivo de comentarios
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try {
                    startActivityForResult(p!!.createIntent(), 77); true
                } catch (e: Exception) {
                    fileCb = null; false
                }
            }
        }
        val html = assets.open("sorteos.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replaceFirst("<head>", "<head>\n$shim")
        web.loadDataWithBaseURL("https://sorteos.local/", html, "text/html", "UTF-8", null)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        // ---------- pantalla de recogida ----------
        colBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); visibility = View.GONE }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#7B2FF7"), Color.parseColor("#F72585")))
        }
        colStatus = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            text = "Abriendo la publicación…"
        }
        colHint = TextView(this).apply {
            setTextColor(Color.parseColor("#F0E6FF")); textSize = 12.5f
            text = "Si te pide iniciar sesión, hazlo aquí. La app baja sola por los comentarios. Cuando el número deje de subir, pulsa «Usar comentarios»."
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        useBtn = pill("✅ Usar comentarios", Color.WHITE, Color.parseColor("#7B2FF7")) { onMainButton() }
        val cancel = pill("✕ Cancelar", Color.parseColor("#33FFFFFF"), Color.WHITE) { askCancel() }
        btns.addView(useBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        btns.addView(cancel, LinearLayout.LayoutParams(0, dp(44), 0.7f))
        bar.addView(colStatus); bar.addView(colHint); bar.addView(btns)
        colBox.addView(bar, LinearLayout.LayoutParams(-1, -2))

        colWeb = WebView(this)
        colWeb.settings.javaScriptEnabled = true
        colWeb.settings.domStorageEnabled = true
        colWeb.settings.userAgentString = desktopUA      // versión de ordenador: enseña todos los comentarios
        colWeb.settings.useWideViewPort = true
        colWeb.settings.loadWithOverviewMode = true
        colWeb.settings.builtInZoomControls = true
        colWeb.settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(colWeb, true) // la sesión se queda guardada
        colWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: ""
                return !(s == "http" || s == "https")   // no saltar a la app de Instagram/TikTok
            }
        }
        colBox.addView(colWeb, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(colBox, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        Licencia.syncNetTime(this)   // fecha real de internet, para que atrasar el reloj no alargue el plan
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(t: String, bg: Int, fg: Int, onTap: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(fg); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(bg) }
        setOnClickListener { onTap() }
    }

    // ---------- recogida de comentarios ----------
    private fun startCollect(url: String, live: Boolean = false) {
        collecting = true; lastCount = -1; sameRounds = 0; noChatRounds = 0
        liveMode = live; liveStarted = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // que la pantalla no se apague
        if (live) {
            colStatus.text = "🔴 Abriendo el directo…"
            colHint.text = "Si te pide iniciar sesión, hazlo aquí. Cuando quieras empezar el sorteo, pulsa «Empezar a contar»: desde ese momento se apunta a todos los que comenten."
            useBtn.text = "▶️ Empezar a contar"
        } else {
            colStatus.text = "Abriendo la publicación…"
            colHint.text = "Si te pide iniciar sesión, hazlo aquí. La app baja sola por los comentarios. Cuando el número deje de subir, pulsa «Usar comentarios»."
            useBtn.text = "✅ Usar comentarios"
        }
        colBox.visibility = View.VISIBLE
        colWeb.loadUrl(url)
        ui.removeCallbacks(loop)
        ui.postDelayed(loop, 3500)
    }

    private fun onMainButton() {
        if (liveMode && !liveStarted) {
            liveStarted = true
            useBtn.text = "⏹️ Parar y sortear"
            colStatus.text = "🔴 Contando… 👥 0 personas"
            colHint.text = "Se apunta a cada persona que comenta. Deja esta pantalla abierta. Cuando quieras, pulsa «Parar y sortear»."
            vibrate(longArrayOf(0, 80))
            return
        }
        finishCollect(true)
    }

    private fun askCancel() {
        if (liveMode && liveStarted) {
            AlertDialog.Builder(this)
                .setMessage("¿Salir sin sortear? Se perderán los comentarios apuntados del directo.")
                .setPositiveButton("Salir") { _, _ -> finishCollect(false) }
                .setNegativeButton("Seguir contando", null)
                .show()
        } else finishCollect(false)
    }

    private fun liveTick() {
        colWeb.evaluateJavascript(liveJs) { txt ->
            if (!collecting) return@evaluateJavascript
            web.evaluateJavascript("onLive(${txt ?: "'{}'"},$liveStarted)") { r ->
                val parts = (r ?: "").trim('"').split("|")
                val people = parts.getOrNull(0)?.toIntOrNull() ?: 0
                val msgs = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val seen = parts.getOrNull(2)?.toIntOrNull() ?: 0
                if (seen == 0) noChatRounds++ else noChatRounds = 0
                colStatus.text = when {
                    !liveStarted && noChatRounds > 8 -> "🔴 No veo el chat. ¿Ha empezado el directo? ¿Hay que iniciar sesión?"
                    !liveStarted && seen > 0 -> "🔴 Directo abierto: veo el chat ✅ Pulsa «Empezar a contar»"
                    !liveStarted -> "🔴 Esperando el chat del directo…"
                    noChatRounds > 8 -> "⚠️ Ya no veo el chat (¿ha terminado el directo?) · 👥 $people personas"
                    else -> "🔴 Contando… 👥 $people personas · 💬 $msgs comentarios"
                }
            }
        }
    }

    private val loop: Runnable = object : Runnable {
        override fun run() {
            if (!collecting) return
            if (liveMode) { liveTick(); ui.postDelayed(this, 1000); return }
            colWeb.evaluateJavascript(stepJs) { txt ->
                if (!collecting) return@evaluateJavascript
                // txt ya es un texto de JavaScript entre comillas: se lo pasamos tal cual a la app
                web.evaluateJavascript("onSnap(${txt ?: "''"})") { r ->
                    val n = r?.trim('"')?.toDoubleOrNull()?.toInt() ?: 0
                    if (n == lastCount) sameRounds++ else { sameRounds = 0; lastCount = n }
                    colStatus.text = when {
                        n == 0 && sameRounds > 4 -> "No veo comentarios todavía. ¿Hay que iniciar sesión o abrir los comentarios?"
                        n == 0 -> "Buscando comentarios…"
                        sameRounds >= 6 -> "💬 $n comentarios · parece que ya están todos ✅"
                        else -> "💬 $n comentarios recogidos… (sigue cargando)"
                    }
                    if (sameRounds == 6 && n > 0) vibrate(longArrayOf(0, 60, 80, 60))
                }
            }
            ui.postDelayed(this, 1600)
        }
    }

    private fun finishCollect(use: Boolean) {
        collecting = false
        ui.removeCallbacks(loop)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val wasLive = liveMode
        colBox.visibility = View.GONE
        colWeb.loadUrl("about:blank")
        if (use && wasLive) {
            web.evaluateJavascript("onLiveDone()", null)
        } else if (use) {
            // Última lectura y se pasan a la app
            web.evaluateJavascript("onCollectDone()") { r ->
                val n = r?.toDoubleOrNull()?.toInt() ?: 0
                if (n > 0) Toast.makeText(this, "✅ $n comentarios recogidos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Si se comparte un enlace desde Instagram o TikTok con «Compartir → Sorteos Star». */
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val m = Regex("https?://\\S+").find(t)?.value ?: return
        i.action = null
        web.evaluateJavascript(
            "(function(){var e=document.getElementById('postUrl');e.value=${JSONObject.quote(m)};e.dispatchEvent(new Event('input'));go('part');})()", null
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        deliverLector()
    }

    // ---------- leer desde la app de Instagram / TikTok ----------
    /** Abre la publicación o el directo en la app de Instagram/TikTok y lo lee con el permiso de accesibilidad. */
    private fun collectInApp(url: String, live: Boolean) {
        val svc = LectorService.instance
        if (svc != null) { svc.begin(url, live); return }
        val on = LectorService.enabledInSettings(this)
        AlertDialog.Builder(this)
            .setTitle("Leer desde la app de Instagram / TikTok")
            .setMessage(
                (if (on) "El permiso está activado pero el móvil lo ha parado. Apágalo y vuelve a encenderlo.\n\n" else "") +
                "Para leer los comentarios desde la app de Instagram o TikTok (con tu sesión de siempre), Sorteos Star necesita el permiso de accesibilidad.\n\n" +
                "1. Pulsa «Activar permiso».\n2. Busca «Sorteos Star (leer comentarios)» (a veces dentro de «Aplicaciones instaladas» o «Servicios descargados»).\n3. Actívalo y vuelve aquí.\n\n" +
                "Solo mira Instagram y TikTok, y solo cuando pulsas «Empezar»."
            )
            .setPositiveButton("Activar permiso") { _, _ ->
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {}
            }
            .setNeutralButton("Usar la web") { _, _ -> startCollect(url, live) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Al volver de Instagram/TikTok: pasa lo leído a la app. */
    private fun deliverLector() {
        if (!pageReady || !LectorResult.ready) return
        LectorResult.ready = false
        if (LectorResult.live) {
            val arr = JSONArray()
            for ((u, t) in LectorResult.rows) arr.put(JSONArray().put(u).put(t))
            web.evaluateJavascript("onLiveImport(${JSONObject.quote(arr.toString())});onLiveDone();", null)
        } else {
            web.evaluateJavascript("resetSnaps();", null)
            val sb = StringBuilder()
            for ((i, sn) in LectorResult.snaps.withIndex()) {
                sb.append("onSnap(").append(JSONObject.quote(sn.second)).append(",").append(JSONObject.quote(sn.first)).append(");")
                if (i % 40 == 39) { web.evaluateJavascript(sb.toString(), null); sb.setLength(0) }
            }
            sb.append("onCollectDone();")
            web.evaluateJavascript(sb.toString()) { r ->
                val n = r?.toDoubleOrNull()?.toInt() ?: 0
                if (n > 0) Toast.makeText(this, "✅ $n comentarios recogidos", Toast.LENGTH_SHORT).show()
            }
        }
        LectorResult.snaps.clear(); LectorResult.rows.clear()
    }

    // ---------- puente con el HTML ----------
    inner class Bridge {
        @JavascriptInterface
        fun collect(url: String) { ui.post { collectInApp(url, false) } }

        @JavascriptInterface
        fun collectLive(url: String) { ui.post { collectInApp(url, true) } }

        @JavascriptInterface
        fun collectWeb(url: String, live: Boolean) { ui.post { startCollect(url, live) } }

        @JavascriptInterface
        fun lastDump(): String = LectorResult.lastDump

        // ---------- cargar los comentarios de golpe (Apify) ----------
        @JavascriptInterface
        fun apifyOk(): Boolean = Apify.hasToken()

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun apifyFetch(url: String, max: Int) {
            Thread {
                try {
                    val rows = Apify.fetch(url, max) { n -> ui.post { web.evaluateJavascript("onApifyProgress($n)", null) } }
                    val q = JSONObject.quote(rows.toString())
                    ui.post { web.evaluateJavascript("onApifyDone($q)", null) }
                } catch (e: Exception) {
                    val q = JSONObject.quote(e.message ?: "No se pudieron cargar los comentarios")
                    ui.post { web.evaluateJavascript("onApifyError($q)", null) }
                }
            }.start()
        }

        @JavascriptInterface
        fun apifyCancel() { Apify.cancelled = true }

        // ---------- planes y códigos ----------
        @JavascriptInterface
        fun plan(): String = Licencia.current(this@MainActivity).toString()

        @JavascriptInterface
        fun activate(code: String): String = Licencia.activate(this@MainActivity, code).toString()

        @JavascriptInterface
        fun vendorUnlock(phrase: String): Boolean { vendor = Licencia.vendorOk(phrase); return vendor }

        @JavascriptInterface
        fun makeCode(device: String, plan: String, days: Int): String {
            if (!vendor || plan.isEmpty() || plan[0] !in "SCA" || Licencia.clean(device).length != 8) return ""
            return Licencia.make(this@MainActivity, device, plan[0], days)
        }

        @JavascriptInterface
        fun openWhatsApp(phone: String, text: String) {
            ui.post {
                val num = phone.filter { it.isDigit() }
                val u = Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
            }
        }

        @JavascriptInterface
        fun vibrate(json: String) {
            val nums = json.trim('[', ']').split(',').mapNotNull { it.trim().toDoubleOrNull()?.toLong() }
            if (nums.isEmpty() || nums.all { it <= 0L }) return
            if (nums.size == 1) vibrate(longArrayOf(0, nums[0].coerceIn(5L, 600L)))
            else vibrate(LongArray(nums.size + 1) { if (it == 0) 0L else nums[it - 1].coerceIn(0L, 600L) })
        }

        @JavascriptInterface
        fun setColor(hex: String) {
            ui.post { try { window.statusBarColor = Color.parseColor(hex) } catch (e: Exception) {} }
        }

        @JavascriptInterface
        fun getClipboard(): String {
            return try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString() ?: ""
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun copy(text: String) {
            ui.post {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Sorteo", text))
            }
        }

        @JavascriptInterface
        fun saveImage(dataUrl: String, name: String) {
            ui.post {
                val uri = saveToGallery(dataUrl, name)
                if (uri != null) Toast.makeText(this@MainActivity, "💾 Guardado en la galería (Imágenes › Sorteos)", Toast.LENGTH_LONG).show()
            }
        }

        @JavascriptInterface
        fun shareImage(dataUrl: String, name: String, text: String) {
            ui.post {
                val uri = saveToGallery(dataUrl, name) ?: return@post
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Compartir el resultado"))
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        try {
            @Suppress("DEPRECATION")
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {}
    }

    /** Guarda el PNG en la galería y devuelve su dirección (sirve también para compartirlo). */
    private fun saveToGallery(dataUrl: String, name: String): Uri? {
        return try {
            val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sorteos")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: return null
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                uri
            } else {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    pendingSave = dataUrl to name
                    requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 78)
                    return null
                }
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Sorteos")
                dir.mkdirs()
                val f = File(dir, name)
                FileOutputStream(f).use { it.write(bytes) }
                val cv = ContentValues().apply {
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, f.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo guardar la imagen", Toast.LENGTH_SHORT).show()
            null
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val p = pendingSave
        pendingSave = null
        if (code == 78 && p != null && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (saveToGallery(p.first, p.second) != null)
                Toast.makeText(this, "💾 Guardado en la galería (Imágenes › Sorteos)", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCb = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (colBox.visibility == View.VISIBLE) {
            if (!liveMode && colWeb.canGoBack()) colWeb.goBack() else askCancel()
            return
        }
        web.evaluateJavascript("onBack()") { r -> if (r == null || r.contains("exit")) finish() }
    }

    override fun onPause() { web.onPause(); colWeb.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume(); colWeb.onResume(); deliverLector() }
    override fun onDestroy() { ui.removeCallbacksAndMessages(null); colWeb.destroy(); web.destroy(); super.onDestroy() }
}
