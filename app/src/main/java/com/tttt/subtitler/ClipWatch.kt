package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.FrameLayout
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** فيديو اتلقط من لينك في الكليبورد. playUrl فاضي = مفيش رابط مباشر لسه (يوتيوب أو صفحة): الشغل بيتم بشاشة الصيد */
class ClipVideo(val url: String, val title: String, val thumb: Bitmap?, val playUrl: String, val kind: String, val ref: String = "", val ua: String = "")

/** فحص لينكات الكليبورد في الخلفية أول ما البرنامج يتفتح، وتحديد اللي فيه فيديو (من غير UI) */
object ClipWatch {
    private val URL_RX = Regex("https?://[^\\s<>\"']+")
    private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    @Volatile var busy = false

    fun extract(t: String): List<String> =
        URL_RX.findAll(t).map { it.value.trimEnd('.', ',', ')', ']', '}', '!', '؟', '،', ';') }.filter { it.length > 10 }.distinct().take(5).toList()

    fun markSeen(ctx: Context, text: String) { try { ctx.getSharedPreferences("p", 0).edit().putString("clip_seen", text.trim()).apply() } catch (_: Throwable) {} }
    fun isSeen(ctx: Context, text: String) = try { ctx.getSharedPreferences("p", 0).getString("clip_seen", "") == text.trim() } catch (_: Throwable) { false }

    private fun decodeEnt(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">")
    private fun attr(tag: String, name: String): String? {
        val m = Regex("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return decodeEnt(m.groupValues[1].ifEmpty { m.groupValues[2] })
    }
    private fun abs(base: String, x: String): String? = try { URL(URL(base), x).toString() } catch (_: Throwable) { null }

    private class Http(val ctype: String, val body: ByteArray?, val finalUrl: String)
    private fun http(u: String, maxBytes: Int): Http? = try {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 7000; c.readTimeout = 8000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "ar,en;q=0.8")
        c.connect()
        val ct = (c.contentType ?: "").lowercase(); val fin = c.url.toString()
        if (ct.startsWith("video/") || ct.contains("mpegurl")) { c.disconnect(); Http(ct, null, fin) }
        else {
            val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16384)
            c.inputStream.use { ins -> while (out.size() < maxBytes) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
            c.disconnect(); Http(ct, out.toByteArray(), fin)
        }
    } catch (_: Throwable) { null }

    private fun bitmap(u: String?): Bitmap? {
        if (u.isNullOrEmpty()) return null
        val h = http(u, 2_000_000) ?: return null
        val b = h.body ?: return null
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1; while (o.outWidth / (s * 2) >= 640) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Throwable) { null }
    }
    /** لقطة من أول ثانية في ملف فيديو مباشر (بمهلة، عشان ما تعلّقش الفحص) */
    private fun frame(u: String): Bitmap? {
        if (Sniff.kindOf(u) == "HLS") return null
        var r: Bitmap? = null
        val t = Thread {
            try { val m = MediaMetadataRetriever(); m.setDataSource(u, HashMap<String, String>()); r = m.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC); m.release() } catch (_: Throwable) {}
        }.apply { isDaemon = true }
        t.start(); t.join(9000)
        return r
    }
    private fun niceName(u: String) = try { java.net.URLDecoder.decode(Sniff.nameOf(u), "UTF-8") } catch (_: Throwable) { Sniff.nameOf(u) }

    /** يشتغل على thread خلفي. بيرجّع null لو اللينك مفيهوش فيديو */
    fun resolve(act: Activity, u: String): ClipVideo? {
        if (Sniff.isDirect(u)) return ClipVideo(u, niceName(u), frame(u), u, Sniff.kindOf(u))
        YtExtract.videoId(u)?.let { id ->
            val h = http("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8"), 100_000)
            val j = try { JSONObject(String(h?.body ?: return null)) } catch (_: Throwable) { return null }   // خاص/محذوف/مش فيديو
            return ClipVideo(u, j.optString("title").ifEmpty { "فيديو يوتيوب" }, bitmap(j.optString("thumbnail_url").ifEmpty { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }), "", "YT")
        }
        val h = http(u, 400_000) ?: return null
        if (h.body == null) return ClipVideo(u, niceName(h.finalUrl), null, h.finalUrl, Sniff.kindOf(h.finalUrl))   // الرابط نفسه بيرجّع فيديو من غير امتداد
        val html = String(h.body, Charsets.UTF_8)
        val metas = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.toList()
        fun meta(vararg names: String): String? { for (m in metas) { val k = (attr(m, "property") ?: attr(m, "name") ?: "").lowercase(); if (k in names) attr(m, "content")?.takeIf { it.isNotBlank() }?.let { return it } }; return null }
        val title = meta("og:title", "twitter:title") ?: Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(html)?.groupValues?.get(1)?.let { decodeEnt(it).trim() } ?: ""
        val img = meta("og:image", "og:image:url", "twitter:image")?.let { abs(h.finalUrl, it) }
        val cands = ArrayList<String>()
        for (n in listOf("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream")) meta(n)?.let { cands.add(it) }
        Regex("<(?:video|source)\\s[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { m -> attr(m.value, "src")?.let { cands.add(it) } }
        Regex("https?:\\\\?/\\\\?/[^\"'\\s<>]+?\\.(?:mp4|m3u8|webm)[^\"'\\s<>]*", RegexOption.IGNORE_CASE).findAll(html).take(8).forEach { cands.add(it.value.replace("\\/", "/").replace("\\u0026", "&")) }
        val direct = cands.mapNotNull { abs(h.finalUrl, decodeEnt(it)) }.firstNotNullOfOrNull { Sniff.accept(it) }
        val hasVid = direct != null || meta("og:video", "og:video:url", "og:video:secure_url", "twitter:player") != null || (meta("og:type") ?: "").startsWith("video")
        if (hasVid) return ClipVideo(u, title.ifEmpty { Sniff.nameOf(u) }, bitmap(img) ?: (direct?.let { frame(it) }), direct ?: "", if (direct != null) Sniff.kindOf(direct) else "WEB", if (direct != null) h.finalUrl else "", UA)
        return probe(act, u)   // مفيش دليل ثابت في الـ HTML: متصفح مخفي ياخد 9 ثواني يشوف الصفحة بتحمّل فيديو ولا لأ
    }

    /** متصفح مخفي: بيحمّل الصفحة ويستنى أي طلب فيديو (بيشتغل مع المواقع اللي بتشغّل الفيديو أوتوماتيك) */
    @SuppressLint("SetJavaScriptEnabled")
    private fun probe(act: Activity, u: String): ClipVideo? {
        val latch = CountDownLatch(1); val found = AtomicReference<String?>(null); val info = AtomicReference("")
        val ref = AtomicReference<WebView?>(null); val ua = AtomicReference(UA)
        val hh = Handler(Looper.getMainLooper())
        act.runOnUiThread {
            try {
                val wv = WebView(act); ref.set(wv); WebMute.register(wv); WebMute.mute(wv)
                wv.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW }
                ua.set(wv.settings.userAgentString ?: UA)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                        val x = r.url.toString()
                        WebMute.mute(v)
                        Sniff.accept(x)?.let { a -> if (found.compareAndSet(null, a)) hh.post { grab(wv, info); } ; hh.postDelayed({ latch.countDown() }, 700) }
                        if (AdBlock.blocked(x)) return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                        return null
                    }
                    override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) { WebMute.mute(v) }
                    override fun onPageFinished(v: WebView, url: String?) { WebMute.mute(v); grab(v, info) }
                }
                hh.postDelayed({ latch.countDown() }, 9000)
                wv.loadUrl(u)
            } catch (_: Throwable) { latch.countDown() }
        }
        latch.await(10, TimeUnit.SECONDS)
        val f = found.get()
        val j = try { JSONObject(info.get()) } catch (_: Throwable) { JSONObject() }
        act.runOnUiThread { try { ref.get()?.stopLoading(); ref.get()?.destroy() } catch (_: Throwable) {} }
        if (f == null) return null
        val img = j.optString("i").takeIf { it.startsWith("http") }
        return ClipVideo(u, j.optString("t").ifEmpty { Sniff.nameOf(u) }, bitmap(img) ?: frame(f), f, Sniff.kindOf(f), u, ua.get())
    }
    private fun grab(v: WebView, info: AtomicReference<String>) {
        try {
            v.evaluateJavascript("(function(){var m=document.querySelector('meta[property=\"og:image\"]');var t=document.querySelector('meta[property=\"og:title\"]');return JSON.stringify({t:(t&&t.content)||document.title||'',i:(m&&m.content)||''})})()") { r ->
                try { (JSONTokener(r ?: "").nextValue() as? String)?.let { if (it.isNotEmpty()) info.set(it) } } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }
}

/**
 * (v358) دايرة اللينك العايمة — بديل إشعار «تم التقاط لينك» اللي كان بيظهر فوق.
 * زي دايرة مشغّل الموسيقى: حلقة زجاجية، في نصها صورة الفيديو (أو 🔗 لحد ما تتحمّل)، ونقطة صغيرة بتلف حوالين الحلقة
 * وقوس بيقصر مع الوقت. لما المهلة تخلص بتختفي لوحدها. لمسة = روح للفيديو، ضغطة طويلة = اللينك اللي بعده (أو إخفاء).
 */
class ClipOrb(ctx: Context) : View(ctx) {
    var accent: Int = 0xFF5B9DFF.toInt()
    var count = 1
    var found = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var thumb: Bitmap? = null
        set(v) { field = v; shader = null; invalidate() }
    /** 0 → 1 مع الوقت؛ القوس بيتحسب منه */
    var elapsed = 0f
        set(v) { field = v.coerceIn(0f, 1f) }
    private var shader: BitmapShader? = null
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val mx = Matrix()
    private val t0 = System.nanoTime()
    private var running = false
    private val tick = object : Runnable { override fun run() { invalidate(); if (running && isAttachedToWindow) postOnAnimation(this) else running = false } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!running) { running = true; postOnAnimation(tick) } }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val ro = minOf(width, height) * 0.46f
        val ra = ro * 0.70f; val rt = ro * 0.86f
        val col = if (found) 0xFF34C759.toInt() else accent
        // زجاج
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = 0x66101418
        c.drawCircle(cx, cy, ro, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0x99FFFFFF.toInt()
        c.drawCircle(cx, cy, ro, p)
        // غلاف/صورة في النص
        val b = thumb
        if (b != null && !b.isRecycled) {
            val sh = shader ?: BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { shader = it }
            val sc = maxOf(2 * ra / b.width, 2 * ra / b.height)
            mx.reset(); mx.setScale(sc, sc); mx.postTranslate(cx - b.width * sc / 2f, cy - b.height * sc / 2f); sh.setLocalMatrix(mx)
            p.reset(); p.isAntiAlias = true; p.shader = sh; c.drawCircle(cx, cy, ra, p); p.shader = null
        } else {
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = (col and 0x00FFFFFF) or 0x55000000
            c.drawCircle(cx, cy, ra, p)
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = ra * 1.0f
            c.drawText("🔗", cx, cy + ra * 0.34f, p)
        }
        // مسار رفيع + قوس الوقت المتبقي
        box.set(cx - rt, cy - rt, cx + rt, cy + rt)
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d; p.color = 0x33FFFFFF
        c.drawOval(box, p)
        p.color = col; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(box, -90f, 360f * (1f - elapsed), false, p)
        // النقطة الصغيرة اللي بتلف حوالين الحلقة
        val ang = Math.toRadians(((System.nanoTime() - t0) / 1e9 * 220.0) % 360.0 - 90.0)
        val ox = cx + (ro + 1f * d) * Math.cos(ang).toFloat(); val oy = cy + (ro + 1f * d) * Math.sin(ang).toFloat()
        p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = (col and 0x00FFFFFF) or 0x55000000
        c.drawCircle(ox, oy, 6.5f * d, p)
        p.color = col; c.drawCircle(ox, oy, 4f * d, p)
        p.color = Color.WHITE; c.drawCircle(ox, oy, 1.6f * d, p)
        // عدّاد لو فيه أكتر من لينك
        if (count > 1) {
            val bx = cx + ro * 0.72f; val by = cy - ro * 0.72f
            p.reset(); p.isAntiAlias = true; p.style = Paint.Style.FILL; p.color = 0xFFE53935.toInt(); c.drawCircle(bx, by, 8f * d, p)
            p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.textSize = 10f * d; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            c.drawText(count.toString(), bx, by + 3.6f * d, p)
        }
    }
}

/**
 * أول ما البرنامج يفتح (أو يرجع له الفوكس): لو في الكليبورد لينك جديد، بتظهر دايرة عايمة صغيرة (ClipOrb) بدل الإشعار اللي فوق.
 * الفحص بيشتغل في الخلفية بالتوازي: أول ما الصورة تجهز بتظهر جوه الدايرة. المهلة 10 ثواني وبعدها تختفي لوحدها.
 * لمسة على الدايرة: فيديو مباشر يشتغل على طول، لينك يوتيوب بيفتح عن طريق يوتيوب، وغير كده بتفتح شاشة الصيد في المتصفح.
 * ضغطة طويلة: اللينك اللي بعده (لو فيه أكتر من واحد) أو إخفاء. مفيش بوب-أب ولا توست.
 */
fun Activity.checkClipboard(onPlay: (ClipVideo) -> Unit) {
    if (ClipWatch.busy) return
    val text = try { (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: "" } catch (_: Throwable) { "" }
    if (text.isBlank() || ClipWatch.isSeen(this, text)) return
    ClipWatch.markSeen(this, text)   // مرة واحدة لكل نسخة: ما يسألش تاني على نفس اللينك
    val links = ClipWatch.extract(text)
    if (links.isEmpty()) return
    val root = findViewById<FrameLayout>(android.R.id.content) ?: return
    ClipWatch.busy = true
    val results = HashMap<String, ClipVideo?>()
    val doneSet = HashSet<String>()
    Thread {
        for (u in links) {
            val r = try { ClipWatch.resolve(this, u) } catch (_: Throwable) { null }
            synchronized(results) { results[u] = r; doneSet.add(u) }
        }
    }.apply { isDaemon = true }.start()

    val d = resources.displayMetrics.density
    val size = (64 * d).toInt()
    val main = Handler(Looper.getMainLooper())
    val life = 10_000L
    val orb = ClipOrb(this).apply { count = links.size; elevation = 10 * d; alpha = 0f }
    root.addView(orb, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
    root.post {
        orb.translationX = (root.width - size - 10 * d).coerceAtLeast(0f)
        orb.translationY = (root.height * 0.58f).coerceAtLeast(0f)
        orb.scaleX = 0.4f; orb.scaleY = 0.4f
        orb.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
    }
    var idx = 0; var t0 = System.currentTimeMillis(); var closed = false
    lateinit var ticker: Runnable
    fun close() {
        if (closed) return; closed = true
        main.removeCallbacks(ticker); ClipWatch.busy = false
        orb.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(200).withEndAction { try { root.removeView(orb) } catch (_: Throwable) {} }.start()
    }
    fun sync() {
        val u = links[idx]
        val r = synchronized(results) { results[u] }
        val fin = synchronized(results) { u in doneSet }
        if (fin) { orb.found = r != null; if (r?.thumb != null && orb.thumb !== r.thumb) orb.thumb = r.thumb }
    }
    ticker = Runnable {
        if (closed) return@Runnable
        if (isFinishing || isDestroyed) { close(); return@Runnable }
        orb.elapsed = (System.currentTimeMillis() - t0).toFloat() / life
        sync()
        if (orb.elapsed >= 1f) close() else main.postDelayed(ticker, 80L)
    }
    main.postDelayed(ticker, 80L)
    orb.setOnClickListener {
        val u = links[idx]
        val r = synchronized(results) { results[u] }
        close()
        onPlay(r ?: ClipVideo(u, u, null, "", "WEB"))   // لسه مش معروف/مفيش فيديو ثابت: playClip بيفتح شاشة الصيد (أو يوتيوب)
    }
    orb.setOnLongClickListener {
        if (idx + 1 < links.size) { idx++; orb.count = links.size - idx; orb.thumb = null; orb.found = false; t0 = System.currentTimeMillis(); orb.elapsed = 0f; sync() } else close()
        true
    }
}
