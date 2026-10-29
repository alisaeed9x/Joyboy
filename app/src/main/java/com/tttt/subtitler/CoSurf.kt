package com.tttt.subtitler

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.LinkedBlockingQueue

/**
 * (v646) مزامنة المتصفح — مش سكرين ميرورينج: الموبايل بيبعت «أحداث» صغيرة (لينك · سكرول · ضغطة)
 * والتلفزيون بينفّذها في متصفحه هو. كل رسالة كام بايت، فمفيش لاج بتاع بث الصورة.
 * الموبايل: toggle() / nav() / scrolled() / touch().  التلفزيون: recv() (من TvLink مسار /cosurf).
 */
object CoSurf {
    @Volatile var on = false
    private val ui = Handler(Looper.getMainLooper())
    private val q = LinkedBlockingQueue<JSONObject>()
    private var worker: Thread? = null
    @Volatile private var lastUrl = ""
    @Volatile private var scrollPending = false
    @Volatile private var fails = 0
    private var ctx: Context? = null

    // مكان وأول لمسة (لتمييز الضغطة من السحب) + المحدّد اللي اتحسب وقت اللمس
    private var dx = 0f; private var dy = 0f; private var dt = 0L
    @Volatile private var sel: String? = null
    @Volatile private var wantSend = false

    // ===================== الموبايل =====================
    /** @return false لو مفيش تلفزيون متصل */
    fun toggle(c: Context): Boolean {
        if (on) { stop(); return true }
        if (RemoteClient.current == null) return false
        ctx = c.applicationContext; on = true; fails = 0; lastUrl = ""
        return true
    }

    fun stop() {
        if (!on) return
        on = false
        send(JSONObject().put("op", "end"))
    }

    /** أول ما المزامنة تشتغل: ابعت الصفحة الحالية ومكان السكرول */
    fun start(w: WebView, url: String) { nav(url, w.settings.userAgentString ?: ""); scrolled(w) }

    fun nav(url: String, ua: String) {
        if (!on || url.isEmpty() || url == lastUrl) return
        if (!(url.startsWith("http://", true) || url.startsWith("https://", true))) return
        lastUrl = url
        send(JSONObject().put("op", "nav").put("url", url).put("ua", ua))
    }

    /** سكرول: أقصى ~14 رسالة في الثانية، والأخيرة دايمًا بتوصل (trailing) */
    fun scrolled(w: WebView) {
        if (!on || scrollPending) return
        scrollPending = true
        ui.postDelayed({
            scrollPending = false
            if (!on) return@postDelayed
            @Suppress("DEPRECATION") val sc = w.scale
            val maxY = w.contentHeight * sc - w.height
            val ry = if (maxY > 1f) (w.scrollY / maxY).coerceIn(0f, 1f) else 0f
            val o = JSONObject().put("op", "scroll").put("ry", ry.toDouble())
            q.removeIf { it.optString("op") == "scroll" }   // آخر قيمة بس
            send(o)
        }, 70L)
    }

    /** مراقبة اللمس على الـWebView (من غير ما نبلع الحدث): ضغطة = تتبعت للتلفزيون */
    fun touch(w: WebView, e: MotionEvent) {
        if (!on) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dx = e.x; dy = e.y; dt = System.currentTimeMillis(); sel = null; wantSend = false
                @Suppress("DEPRECATION") val sc = if (w.scale > 0f) w.scale else 1f
                val js = "(function(x,y){var e=document.elementFromPoint(x,y);if(!e)return '';" +
                    "var c=e.closest('a,button,input,select,textarea,label,summary,[role=button],[onclick],[tabindex]')||e;" +
                    "function p(n){if(!n||n===document.documentElement)return 'html';if(n===document.body)return 'body';" +
                    "if(n.id&&document.querySelectorAll('#'+CSS.escape(n.id)).length===1)return '#'+CSS.escape(n.id);" +
                    "var i=1,s=n;while(s=s.previousElementSibling){if(s.tagName===n.tagName)i++}" +
                    "return p(n.parentElement)+'>'+n.tagName.toLowerCase()+':nth-of-type('+i+')'}" +
                    "return p(c)})(" + (e.x / sc) + "," + (e.y / sc) + ")"
                try {
                    w.evaluateJavascript(js) { r ->
                        val s = try { JSONTokener(r ?: "").nextValue() as? String } catch (_: Throwable) { null }
                        if (!s.isNullOrEmpty()) { sel = s; if (wantSend) { wantSend = false; sendTap(s) } }
                    }
                } catch (_: Throwable) {}
            }
            MotionEvent.ACTION_UP -> {
                val slop = android.view.ViewConfiguration.get(w.context).scaledTouchSlop
                val tap = Math.abs(e.x - dx) < slop && Math.abs(e.y - dy) < slop && System.currentTimeMillis() - dt < 400L
                if (tap) { val s = sel; if (s != null) sendTap(s) else { wantSend = true } }
            }
        }
    }

    /** (v647) للمرآة المعكوسة: ابعت حدث للتلفزيون من غير ما تلزم المزامنة تكون شغّالة */
    fun post(o: JSONObject) { send(o) }

    private fun sendTap(s: String) { send(JSONObject().put("op", "tap").put("sel", s)) }

    private fun send(o: JSONObject) {
        q.add(o)
        if (worker?.isAlive == true) return
        worker = Thread {
            while (true) {
                val m = q.poll(30, java.util.concurrent.TimeUnit.SECONDS) ?: break
                try {
                    val (c, _) = RemoteClient.postJson("/cosurf", m)
                    if (c == 200) fails = 0 else if (++fails >= 5) lost()
                } catch (_: Throwable) { if (++fails >= 5) lost() }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun lost() {
        if (!on) return
        on = false; q.clear()
        val c = ctx ?: return
        ui.post { try { Notice.show(c, "📺 المزامنة وقفت — التلفزيون مش بيرد", 2600L) } catch (_: Throwable) {} }
    }

    // ===================== التلفزيون =====================
    fun recv(j: JSONObject) {
        val b = BrowserActivity.live()
        when (j.optString("op")) {
            "nav" -> {
                val u = j.optString("url")
                if (b != null) b.coNav(u, j.optString("ua"))
                else TvLink.openJson(JSONObject().put("url", u).put("how", Links.WEB))
            }
            "scroll" -> b?.coJs("(function(ry){var d=document.documentElement,b=document.body;" +
                "var my=Math.max(0,Math.max(d.scrollHeight,b?b.scrollHeight:0)-window.innerHeight);" +
                "window.scrollTo(window.scrollX,ry*my)})(" + j.optDouble("ry", 0.0) + ")")
            "tap" -> {
                val s = j.optString("sel")
                if (s.isNotEmpty()) b?.coJs("(function(s){var e=document.querySelector(s);if(!e)return;" +
                    "var o={bubbles:true,cancelable:true,view:window};" +
                    "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){try{e.dispatchEvent(new MouseEvent(t,o))}catch(_){}});" +
                    "try{e.focus()}catch(_){}e.click()})(" + JSONObject.quote(s) + ")")
            }
            "end" -> b?.coEnd()
            "openweb" -> if (b == null) TvLink.showTv("browser")   // (v647) المرآة: افتح المتصفح
            "mtap" -> b?.coJs("(function(rx,ry){var x=rx*window.innerWidth,y=ry*window.innerHeight;var e=document.elementFromPoint(x,y);if(!e)return;" +
                "var o={bubbles:true,cancelable:true,view:window,clientX:x,clientY:y};" +
                "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){try{e.dispatchEvent(new MouseEvent(t,o))}catch(_){}});" +
                "try{e.focus()}catch(_){}e.click()})(" + j.optDouble("rx", 0.0) + "," + j.optDouble("ry", 0.0) + ")")
            "mscroll" -> b?.coJs("window.scrollBy(0," + j.optDouble("r", 0.0) + "*window.innerHeight)")
        }
    }
}
