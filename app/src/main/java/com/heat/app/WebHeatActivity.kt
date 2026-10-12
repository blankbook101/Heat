package com.heat.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.heat.app.core.Prefs
import com.heat.app.core.Sink
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs Heat Web (the files in /web, bundled as assets) inside the app so Android can pair with
 * iPhones and browsers by QR code. Files received there are written to Downloads/Heat through
 * [HeatBridge], the same place the Bluetooth / Wi-Fi Direct transfers land.
 */
class WebHeatActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var camReq: PermissionRequest? = null
    private val bridge = HeatBridge()

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val cb = fileCb ?: return@registerForActivityResult
        fileCb = null
        if (uris.isEmpty()) cb.onReceiveValue(null) else cb.onReceiveValue(uris.toTypedArray())
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val req = camReq ?: return@registerForActivityResult
        camReq = null
        if (ok) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else req.deny()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            addJavascriptInterface(bridge, "HeatAndroid")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    loader.shouldInterceptRequest(request.url)

                // Never navigate away from Heat Web's own pages.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.host != WebViewAssetLoader.DEFAULT_DOMAIN
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    if (request.origin.host != WebViewAssetLoader.DEFAULT_DOMAIN ||
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE !in request.resources
                    ) { request.deny(); return }
                    if (ContextCompat.checkSelfPermission(this@WebHeatActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        camReq?.deny()
                        camReq = request
                        askCamera.launch(Manifest.permission.CAMERA)
                    }
                }

                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams
                ): Boolean {
                    fileCb?.onReceiveValue(null)
                    fileCb = callback
                    pickFiles.launch("*/*")
                    return true
                }
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.heatBack&&window.heatBack())===true") { handled ->
                    if (handled != "true") finish()
                }
            }
        })

        if (savedInstanceState == null) {
            val prefs = Prefs(this)
            val url = Uri.Builder()
                .scheme("https").authority(WebViewAssetLoader.DEFAULT_DOMAIN).path("/assets/index.html")
                .appendQueryParameter("name", prefs.name)
                .appendQueryParameter("egg", if (prefs.egg) "1" else "0")
                .appendQueryParameter("mode", prefs.avatarMode.toString())
                .build().toString()
            web.loadUrl(url)
        } else {
            web.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        bridge.abortAll(this)
        fileCb?.onReceiveValue(null)
        web.removeJavascriptInterface("HeatAndroid")
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    /** Called from the page: streams a received file into Downloads/Heat chunk by chunk. */
    inner class HeatBridge {
        private val streams = ConcurrentHashMap<String, Pair<Uri, OutputStream>>()
        private var n = 0

        @JavascriptInterface
        fun beginSave(name: String, mime: String): String = try {
            val display = Sink.clean(name)
            val (uri, os) = Sink.open(applicationContext, display, mime.ifBlank { Sink.mimeOf(display) })
            val id = "s${++n}"
            streams[id] = uri to os
            id
        } catch (_: Exception) {
            ""
        }

        @JavascriptInterface
        fun writeChunk(id: String, b64: String) {
            val os = streams[id]?.second ?: return
            try { os.write(Base64.decode(b64, Base64.DEFAULT)) } catch (_: Exception) { /* endSave will report it */ }
        }

        @JavascriptInterface
        fun endSave(id: String): Boolean {
            val (uri, os) = streams.remove(id) ?: return false
            return try {
                os.flush(); os.close()
                Sink.finish(applicationContext, uri)
                true
            } catch (_: Exception) {
                Sink.abort(applicationContext, uri)
                false
            }
        }

        @JavascriptInterface
        fun abortSave(id: String) {
            val (uri, os) = streams.remove(id) ?: return
            runCatching { os.close() }
            Sink.abort(applicationContext, uri)
        }

        fun abortAll(ctx: android.content.Context) {
            for ((id, p) in streams) { runCatching { p.second.close() }; Sink.abort(ctx, p.first); streams.remove(id) }
        }
    }
}
