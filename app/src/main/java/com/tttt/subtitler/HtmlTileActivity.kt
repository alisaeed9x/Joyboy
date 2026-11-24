package com.tttt.subtitler

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray

/** (v1275) بلاطات HTML: صفحة HTML متضمّنة في التطبيق بتتفتح في شاشة كاملة، ومفاتيح الذكاء الاصطناعي فيها هي مفاتيح التطبيق (بتتحقن وقت الفتح، مفيش مفتاح جوه الملف). */
object HtmlTiles {
    class Tile(val id: String, val asset: String, val emoji: String, val title: String)
    val all = listOf(Tile("fath_kalam", "tiles/fath_kalam.html", "💞", "فتح كلام"))
    fun open(act: Activity, t: Tile) {
        try { act.startActivity(android.content.Intent(act, HtmlTileActivity::class.java).putExtra("asset", t.asset).putExtra("id", t.id)) }
        catch (e: Throwable) { LogStore.err("HtmlTiles:open", e) }
    }
}

class HtmlTileActivity : Activity() {
    private var web: WebView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val asset = intent?.getStringExtra("asset") ?: run { finish(); return }
        val id = intent?.getStringExtra("id") ?: "tile"
        val root = FrameLayout(this)
        setContentView(root)
        val w = WebView(this)
        web = w
        root.addView(w, ViewGroup.LayoutParams(-1, -1))
        with(w.settings) {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false; allowContentAccess = false
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(v: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                try { android.app.AlertDialog.Builder(this@HtmlTileActivity).setMessage(message).setPositiveButton("تمام") { _, _ -> result?.confirm() }.setOnCancelListener { result?.cancel() }.show() } catch (_: Throwable) { result?.confirm() }
                return true
            }
            override fun onJsConfirm(v: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                try { android.app.AlertDialog.Builder(this@HtmlTileActivity).setMessage(message).setPositiveButton("تمام") { _, _ -> result?.confirm() }.setNegativeButton("إلغاء") { _, _ -> result?.cancel() }.setOnCancelListener { result?.cancel() }.show() } catch (_: Throwable) { result?.cancel() }
                return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val u = request?.url?.toString() ?: return false
                if (u.startsWith("https://appassets.androidplatform.net/")) return false
                return try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))); true } catch (_: Throwable) { false }
            }
        }
        Thread {
            try {
                var html = assets.open(asset).bufferedReader(Charsets.UTF_8).readText()
                val keys = try { Cfg.ensure(this); Cfg.allMainKeys() } catch (_: Throwable) { emptyList<String>() }
                val arr = JSONArray(); for (k in keys) { val t = k.trim(); if (t.isNotEmpty()) arr.put(t) }
                val inj = "<script>window.JB_KEYS=" + arr.toString().replace("</", "<\\/") + ";</script>"
                val i = html.indexOf("<head>")
                html = if (i >= 0) html.substring(0, i + 6) + inj + html.substring(i + 6) else inj + html
                runOnUiThread { try { w.loadDataWithBaseURL("https://appassets.androidplatform.net/" + id + "/", html, "text/html", "UTF-8", null) } catch (e: Throwable) { LogStore.err("HtmlTile:load", e) } }
            } catch (e: Throwable) { LogStore.err("HtmlTile:read", e); runOnUiThread { finish() } }
        }.apply { isDaemon = true; start() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { val w = web; if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed() }

    override fun onDestroy() { try { web?.destroy() } catch (_: Throwable) {}; super.onDestroy() }
}
