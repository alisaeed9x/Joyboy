package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * (v346) حساب جوجل ليوتيوب — بدون مفتاح API ولا إعداد في Google Cloud:
 *  1) تسجيل الدخول بصفحة جوجل الحقيقية في WebView (بيانات الدخول مابتعدّيش على التطبيق، بس الكوكيز اللي بتطلع بعدها بتتحفظ).
 *  2) الكوكيز بتتحفظ في ملف خاص (yt_acc) مش في Cfg — عشان ماتدخلش النسخ الاحتياطي ولا التصدير، ولا يمسحها «مسح المتصفح».
 *  3) طلبات يوتيوب الداخلية (InnerTube browse) بتتوقّع بـ SAPISIDHASH — نفس اللي المتصفح بيعمله.
 *  4) القراءة بماشي عام (walk) على الـJSON مش على مسار ثابت، فتغيّر الشكل الداخلي لصفحة يوتيوب مايكسرهاش بسهولة.
 * آخر رد اتحفظ في filesDir/yt_acc_last.json للتشخيص لو حاجة ما اتعرفتش.
 */
object YtAccount {
    private const val ORIGIN = "https://www.youtube.com"
    private const val API_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    // من غير كلمة wv — جوجل بترفض الدخول من WebView الواضح
    private const val LOGIN_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val SYNC_EVERY_MS = 6 * 60 * 60 * 1000L

    @Volatile private var sp: SharedPreferences? = null
    @Volatile private var dir: File? = null

    fun init(c: Context) {
        if (sp == null) { val a = c.applicationContext; sp = a.getSharedPreferences("yt_acc", 0); dir = a.filesDir }
    }

    private fun cookie(): String = sp?.getString("cookie", "") ?: ""

    /** (v459) الجلسة كانت لقطة واحدة من يوم تسجيل الدخول وبتعفّن (يوتيوب بيبدّل كوكيز SAPISID)، فصفحة الاشتراكات
     *  كانت بتفشل بـ«رد غير صالح/جلسة منتهية» رغم إن loggedIn() بتقول مسجّل. مخزن كوكيز الـ WebView بيضل حديث،
     *  فقبل أي طلب بناخد منه نسخة أجدد لو فيها SAPISID مختلف عن المحفوظ. */
    private fun refreshCookie() {
        try {
            val fresh = android.webkit.CookieManager.getInstance().getCookie(ORIGIN) ?: return
            if (!fresh.contains("SAPISID=") && !fresh.contains("__Secure-3PAPISID=")) return
            val cur = cookie()
            fun sid(t: String): String? = t.split(";").map { it.trim() }.firstOrNull { it.startsWith("__Secure-3PAPISID=") || it.startsWith("SAPISID=") }
            if (sid(fresh) != null && sid(fresh) != sid(cur)) {
                sp?.edit()?.putString("cookie", fresh)?.putLong("login_at", System.currentTimeMillis())?.apply()
                LogStore.add("🍪 جلسة يوتيوب اتحدّثت من مخزن الويب")
            }
        } catch (_: Throwable) {}
    }
    private fun cv(name: String): String? {
        for (p in cookie().split(";")) { val t = p.trim(); if (t.startsWith("$name=")) return t.substring(name.length + 1) }
        return null
    }
    fun loggedIn(): Boolean = cv("SAPISID") != null || cv("__Secure-3PAPISID") != null
    fun syncDue(every: Long = SYNC_EVERY_MS): Boolean = System.currentTimeMillis() - (sp?.getLong("sync_at", 0L) ?: 0L) > every
    @Volatile private var syncing = false
    /** (v389) مزامنة في الخلفية (نفس زرار «🔄 مزامنة الاشتراكات») من غير ما المستخدم يدخل صفحة «أنت»؛ مرة واحدة في نفس الوقت */
    fun syncAsync(force: Boolean = false, onDone: (Int) -> Unit = {}) {
        if (syncing || !loggedIn()) return
        if (!force && System.currentTimeMillis() - lastSyncAt() < 60_000L) return   // مش أكتر من مرة في الدقيقة
        syncing = true
        Thread {
            try { val n = syncSubs(); LogStore.add("🔄 مزامنة تلقائية للاشتراكات: +$n قناة"); onDone(n) }
            catch (e: Throwable) { LogStore.add("⚠ المزامنة التلقائية فشلت: " + (e.message ?: e.javaClass.simpleName).take(120)) }
            finally { syncing = false }
        }.also { it.isDaemon = true; it.start() }
    }
    fun lastSyncAt(): Long = sp?.getLong("sync_at", 0L) ?: 0L
    fun chanCount(): Int = sp?.getInt("chan_n", 0) ?: 0

    // ───────── تسجيل الدخول ─────────
    @SuppressLint("SetJavaScriptEnabled")
    fun login(act: Activity, ui: Ui, th: Theme, onDone: (Boolean) -> Unit) {
        init(act)
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val wv = WebView(act)
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(wv, true)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = LOGIN_UA
        var done = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                if (done) return
                val u = url ?: return
                if (!u.contains("youtube.com")) return
                val ck = cm.getCookie(ORIGIN) ?: ""
                if (ck.contains("SAPISID=") || ck.contains("__Secure-3PAPISID=")) {
                    done = true
                    try { cm.flush() } catch (_: Throwable) {}
                    sp?.edit()?.putString("cookie", ck)?.putLong("login_at", System.currentTimeMillis())?.putLong("sync_at", 0L)?.apply()
                    d.dismiss(); onDone(true)
                }
            }
        }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg) }
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); setBackgroundColor(th.surface)
        }
        val title = ui.text("🔐 تسجيل الدخول بحساب جوجل", 14f, th.text, true)
        val close = ui.text("✕", 18f, th.text, true).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)); setOnClickListener { d.dismiss() } }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(close, LinearLayout.LayoutParams(-2, -2))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(root)
        d.setOnKeyListener { _, kc, ev ->
            if (kc == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && wv.canGoBack()) { wv.goBack(); true } else false
        }
        d.setOnDismissListener { try { wv.stopLoading(); wv.destroy() } catch (_: Throwable) {}; if (!done) onDone(false) }
        d.show()
        wv.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&continue=" + java.net.URLEncoder.encode("https://m.youtube.com/", "UTF-8"))
    }

    fun logout() {
        sp?.edit()?.clear()?.apply()
        try {
            val cm = CookieManager.getInstance()
            for ((host, dom) in listOf("https://www.youtube.com" to ".youtube.com", "https://m.youtube.com" to ".youtube.com",
                "https://accounts.google.com" to ".google.com", "https://www.google.com" to ".google.com")) {
                val ck = cm.getCookie(host) ?: continue
                for (p in ck.split(";")) {
                    val n = p.substringBefore("=").trim(); if (n.isEmpty()) continue
                    cm.setCookie(host, "$n=; Max-Age=0; Path=/")
                    cm.setCookie(host, "$n=; Max-Age=0; Path=/; Domain=$dom")
                }
            }
            cm.flush()
        } catch (_: Throwable) {}
    }

    // ───────── InnerTube ─────────
    private fun ver(): String = Cfg.str("yt_client_ver", "2.20260601.01.00")
    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { String.format("%02x", it.toInt() and 0xff) }

    private fun auth(): String {
        val ts = System.currentTimeMillis() / 1000
        val sid = cv("SAPISID") ?: cv("__Secure-3PAPISID") ?: ""
        val p1 = cv("__Secure-1PAPISID") ?: sid
        val p3 = cv("__Secure-3PAPISID") ?: sid
        fun h(v: String) = "${ts}_" + sha1("$ts $v $ORIGIN")
        return "SAPISIDHASH ${h(sid)} SAPISID1PHASH ${h(p1)} SAPISID3PHASH ${h(p3)}"
    }

    private fun ctx(): JSONObject = JSONObject().put("client",
        JSONObject().put("clientName", "WEB").put("clientVersion", ver()).put("hl", YtLocale.hl()).put("gl", YtLocale.gl()))   // (v471) اللغة من YtLocale

    /** (v471) هيدرز حسابك (كوكيز + SAPISIDHASH) لطلبات الويب اللي بتخرج من NewPipe (next/browse/search) — null لو مش مسجّل الدخول */
    fun authHeaders(): Map<String, String>? {
        if (!loggedIn()) return null
        return try {
            refreshCookie()
            val ck = cookie(); if (ck.isBlank()) return null
            mapOf("Cookie" to ck, "Authorization" to auth(), "X-Goog-AuthUser" to "0", "Origin" to ORIGIN, "X-Origin" to ORIGIN)
        } catch (_: Throwable) { null }
    }

    /** (v471) طلب InnerTube خفيف: بيشتغل من غير تسجيل دخول، ولو مسجّل بيتبعت بحسابك (لغة وترتيب حسابك). مهلة قصيرة عشان الرئيسية ما تتعطّلش */
    private fun postLite(path: String, body: JSONObject): JSONObject {
        val c = URL("$ORIGIN/youtubei/v1/$path?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 12000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", API_UA)
            c.setRequestProperty("Origin", ORIGIN)
            c.setRequestProperty("X-Youtube-Client-Name", "1"); c.setRequestProperty("X-Youtube-Client-Version", ver())
            c.setRequestProperty("Accept-Language", YtLocale.acceptLanguage())
            c.setRequestProperty("Accept-Encoding", "gzip")
            val ah = authHeaders()
            if (ah != null) for ((k, v) in ah) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            var ins = if (code >= 400) c.errorStream else c.inputStream
            if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
            val txt = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code >= 400) throw RuntimeException("يوتيوب رد بخطأ $code")
            return JSONObject(txt)
        } finally { c.disconnect() }
    }

    /** (v471) فيديوهات مقترحة لفيديو من ردّ next الخفيف (بدل StreamInfo الكامل) */
    fun relatedLite(videoId: String): List<NpItem> {
        val j = postLite("next", JSONObject().put("context", ctx()).put("videoId", videoId))
        return parse(j).items.filter { it.kind == 0 && it.id != videoId && it.dur !in 1L..60L }
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        refreshCookie()   // (v459) نسخة أجدد من الكوكيز لو متاحة
        if (!loggedIn()) throw RuntimeException("مش مسجّل الدخول — سجّل بحساب جوجل الأول")
        val c = URL("$ORIGIN/youtubei/v1/$path?prettyPrint=false").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000; c.readTimeout = 30000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", API_UA)
            c.setRequestProperty("Cookie", cookie())
            c.setRequestProperty("Authorization", auth())
            c.setRequestProperty("Origin", ORIGIN); c.setRequestProperty("X-Origin", ORIGIN)
            c.setRequestProperty("X-Goog-AuthUser", "0")
            c.setRequestProperty("X-Youtube-Client-Name", "1"); c.setRequestProperty("X-Youtube-Client-Version", ver())
            c.setRequestProperty("Accept-Language", YtLocale.acceptLanguage())
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            var ins = if (code >= 400) c.errorStream else c.inputStream
            if (ins != null && "gzip".equals(c.contentEncoding, true)) ins = GZIPInputStream(ins)
            val txt = ins?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 401 || code == 403) { try { sp?.edit()?.remove("cookie")?.apply() } catch (_: Throwable) {}; throw RuntimeException("يوتيوب رفض الجلسة ($code) — الجلسة انتهت واتمسحت، سجّل الدخول تاني من تبويب «أنت»") }   // (v459) بنمسح الجلسة الميتة بدل ما نفضل نجربها
            if (code >= 400) throw RuntimeException("يوتيوب رد بخطأ $code")
            try { dir?.let { File(it, "yt_acc_last.json").writeText(txt.take(300_000)) } } catch (_: Throwable) {}
            return JSONObject(txt)
        } finally { c.disconnect() }
    }

    private fun browse(id: String): JSONObject = post("browse", JSONObject().put("context", ctx()).put("browseId", id))
    private fun browseMore(token: String): JSONObject = post("browse", JSONObject().put("context", ctx()).put("continuation", token))

    // ───────── قراءة الرد (ماشي عام على الـJSON) ─────────
    private fun walk(n: Any?, f: (String, JSONObject) -> Unit) {
        when (n) {
            is JSONObject -> { val ks = n.keys(); while (ks.hasNext()) { val k = ks.next(); val v = n.opt(k); if (v is JSONObject) f(k, v); walk(v, f) } }
            is JSONArray -> for (i in 0 until n.length()) walk(n.opt(i), f)
        }
    }
    private fun dig(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch in '\u0660'..'\u0669') ('0' + (ch - '\u0660')) else if (ch in '\u06F0'..'\u06F9') ('0' + (ch - '\u06F0')) else ch)
        return sb.toString()
    }
    private fun txt(o: JSONObject?): String {
        if (o == null) return ""
        val s = o.optString("simpleText"); if (s.isNotEmpty()) return s
        val c = o.optString("content"); if (c.isNotEmpty()) return c
        val r = o.optJSONArray("runs") ?: return ""
        val sb = StringBuilder(); for (i in 0 until r.length()) sb.append(r.optJSONObject(i)?.optString("text") ?: "")
        return sb.toString()
    }
    private fun secs(s0: String): Long {
        val s = dig(s0).trim(); if (s.isEmpty() || !s.contains(':')) return -1L
        var t = 0L
        for (p in s.split(":")) { val n = p.filter { it in '0'..'9' }.toLongOrNull() ?: return -1L; t = t * 60 + n }
        return t
    }
    private fun fixUrl(u: String): String = if (u.startsWith("//")) "https:$u" else u
    private fun lastThumb(o: JSONObject?): String {
        val a = o?.optJSONArray("thumbnails") ?: return ""
        return if (a.length() == 0) "" else fixUrl(a.optJSONObject(a.length() - 1)?.optString("url") ?: "")
    }
    private fun chanUrlOf(o: JSONObject?): String {
        val id = o?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId") ?: ""
        return if (id.startsWith("UC")) "https://www.youtube.com/channel/$id" else ""
    }
    private fun vid(id: String, title: String, ch: String, chUrl: String, dur: Long, age: String) =
        NpItem(id, title, ch, chUrl, "https://i.ytimg.com/vi/$id/mqdefault.jpg", dur, -1L, age, 0, YtNp.watchUrl(id))

    private fun video(o: JSONObject): NpItem? {
        val id = o.optString("videoId"); if (id.length != 11) return null
        val by = o.optJSONObject("ownerText") ?: o.optJSONObject("longBylineText") ?: o.optJSONObject("shortBylineText")
        var dur = secs(txt(o.optJSONObject("lengthText")))
        if (dur < 0) dur = o.optString("lengthSeconds").toLongOrNull() ?: -1L
        return vid(id, txt(o.optJSONObject("title")), txt(by), chanUrlOf(by), dur, txt(o.optJSONObject("publishedTimeText")))
    }
    private fun chan(o: JSONObject): NpItem? {
        val id = o.optString("channelId"); if (!id.startsWith("UC")) return null
        val u = "https://www.youtube.com/channel/$id"
        return NpItem("", txt(o.optJSONObject("title")), "", u, lastThumb(o.optJSONObject("thumbnail")), -1L, -1L, "", 1, u)
    }
    private fun plist(o: JSONObject): NpItem? {
        val pid = o.optString("playlistId"); if (pid.isEmpty()) return null
        val th0 = lastThumb(o.optJSONArray("thumbnails")?.optJSONObject(0)).ifEmpty { lastThumb(o.optJSONObject("thumbnail")) }
        val cnt = dig(o.optString("videoCount")).filter { it in '0'..'9' }.toLongOrNull() ?: -1L
        return NpItem("", txt(o.optJSONObject("title")), txt(o.optJSONObject("longBylineText")), "", th0, -1L, cnt, "", 2, "yta:VL$pid")
    }
    /** الشكل الجديد (lockupViewModel) بتاع يوتيوب لقوايم السجل والتشغيل */
    private fun lockup(o: JSONObject): NpItem? {
        val id = o.optString("contentId"); if (id.isEmpty()) return null
        val md = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = md?.optJSONObject("title")?.optString("content") ?: ""
        val parts = ArrayList<String>()
        walk(md?.optJSONObject("metadata")) { k, p -> if (k == "text") { val t = p.optString("content"); if (t.isNotEmpty()) parts.add(t) } }
        val type = o.optString("contentType")
        if (type.contains("CHANNEL")) {   // (v350) القنوات بالشكل الجديد
            if (!id.startsWith("UC")) return null
            val u = "https://www.youtube.com/channel/$id"
            return NpItem("", title, "", u, "", -1L, -1L, "", 1, u)
        }
        if (type.contains("PLAYLIST")) return NpItem("", title, parts.firstOrNull() ?: "", "", "", -1L, -1L, "", 2, "yta:VL$id")
        if (!type.contains("VIDEO") || id.length != 11) return null
        var dur = -1L
        walk(o.optJSONObject("contentImage")) { k, p -> if (k == "thumbnailBadgeViewModel" && dur < 0) dur = secs(p.optString("text")) }
        return vid(id, title, parts.firstOrNull() ?: "", "", dur, parts.drop(1).joinToString(" · "))
    }

    class Parsed(val items: List<NpItem>, val next: String?)
    private fun parse(j: JSONObject): Parsed {
        val out = ArrayList<NpItem>(); val seen = HashSet<String>()
        var tok: String? = null
        walk(j) { k, o ->
            val x: NpItem? = when (k) {
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> video(o)
                "channelRenderer", "gridChannelRenderer" -> chan(o)
                "playlistRenderer", "gridPlaylistRenderer" -> plist(o)
                "lockupViewModel" -> lockup(o)
                else -> null
            }
            if (x != null && seen.add(x.kind.toString() + x.id + x.url)) out.add(x)
            if (k == "continuationItemRenderer" && tok == null) {
                val t = o.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token") ?: ""
                if (t.isNotEmpty()) tok = t
            }
        }
        return Parsed(out, tok)
    }

    // ───────── واجهة للشاشة ─────────
    fun listFirst(browseId: String): YtRes { val r = parse(browse(browseId)); return YtRes(r.items, r.next?.let { Page(it) }) }
    fun listMore(p: Page): YtRes { val r = parse(browseMore(p.url ?: "")); return YtRes(r.items, r.next?.let { Page(it) }) }

    private fun row(title: String, sub: String, key: String) = NpItem("act", title, sub, "", "", -1L, -1L, "", 2, "yta:$key")
    fun menuItems(): List<NpItem> = listOf(
        row("📺 قنواتي", "كل القنوات اللي مشترك فيها (${chanCount()})", "FEchannels"),
        row("🕘 سجل مشاهدتي في يوتيوب", "اللي شفته على حسابك", "FEhistory"),
        row("👍 فيديوهات الإعجاب", "اللي عملتلها لايك", "VLLL"),
        row("⏰ شاهد لاحقًا", "قايمة شاهد لاحقًا بتاعتك", "VLWL"),
        row("📃 قوايم التشغيل بتاعتي", "القوايم اللي عملتها أو حفظتها", "FEplaylist_aggregation"),
        row("🔄 مزامنة الاشتراكات دلوقتي", syncLabel(), "!sync"),
        row("🚪 تسجيل الخروج", "بيشيل بيانات الدخول من التطبيق", "!out"))

    private fun syncLabel(): String {
        val t = lastSyncAt(); if (t <= 0L) return "لسه ما اتزامنتش"
        val m = (System.currentTimeMillis() - t) / 60000
        return "آخر مزامنة: " + (if (m < 1) "دلوقتي" else if (m < 60) "من $m دقيقة" else if (m < 1440) "من ${m / 60} ساعة" else "من ${m / 1440} يوم")
    }

    private fun sameChan(a: String, b: String): Boolean { val x = YtRss.chanId(a); val y = YtRss.chanId(b); return if (x != null && y != null) x == y else a == b }

    /** بيجيب كل قنوات حسابك ويضيف الجديد منها على اشتراكاتك المحلية (مابيمسحش أي حاجة). بيرجّع عدد القنوات الجديدة */
    fun syncSubs(): Int {
        val chans = ArrayList<NpItem>()
        var r = parse(browse("FEchannels"))
        chans.addAll(r.items.filter { it.kind == 1 })
        var tok = r.next; var pages = 0
        while (tok != null && pages++ < 40) { r = parse(browseMore(tok)); chans.addAll(r.items.filter { it.kind == 1 }); tok = r.next }
        if (chans.isEmpty()) throw RuntimeException("ما لقيتش قنوات في ردّ يوتيوب — غالبًا الجلسة انتهت: من تبويب «أنت» سجّل الخروج وادخل تاني. (الرد اتحفظ في yt_acc_last.json)")
        val subs = YtStore.read(YtStore.SUBS); var added = 0
        for (c in chans) if (subs.none { sameChan(it.channelUrl, c.channelUrl) }) { subs.add(c); added++ }
        YtStore.write(YtStore.SUBS, subs)
        sp?.edit()?.putLong("sync_at", System.currentTimeMillis())?.putInt("chan_n", chans.size)?.apply()
        return added
    }

    // ───────── (v403) أفعال على حسابك: اشتراك · لايك · تعليق (best effort — بتفشل بهدوء لو يوتيوب غيّر الشكل) ─────────
    fun setSubscribed(chanUrl: String, on: Boolean) {
        val id = YtRss.chanId(chanUrl) ?: throw RuntimeException("مش قادر أعرف معرّف القناة")
        post(if (on) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("context", ctx()).put("channelIds", JSONArray().put(id)))
    }
    fun setLiked(videoId: String, on: Boolean) {
        post(if (on) "like/like" else "like/removelike",
            JSONObject().put("context", ctx()).put("target", JSONObject().put("videoId", videoId)))
    }
    private fun findStr(n: Any?, key: String): String? {
        when (n) {
            is JSONObject -> {
                val d = n.optString(key); if (d.isNotEmpty() && n.opt(key) is String) return d
                val ks = n.keys(); while (ks.hasNext()) { val r = findStr(n.opt(ks.next()), key); if (r != null) return r }
            }
            is JSONArray -> for (i in 0 until n.length()) { val r = findStr(n.opt(i), key); if (r != null) return r }
        }
        return null
    }
    /** بينشر تعليق على الفيديو (بيجيب بارامتر الإنشاء من قسم التعليقات في ردّ next) */
    fun postComment(videoId: String, text: String) {
        val r1 = post("next", JSONObject().put("context", ctx()).put("videoId", videoId))
        var tok: String? = null
        walk(r1) { k, o -> if (tok == null && k == "itemSectionRenderer" && o.optString("sectionIdentifier").contains("comment")) tok = findStr(o, "token") }
        if (tok == null) walk(r1) { k, o -> if (tok == null && k == "engagementPanelSectionListRenderer" && o.optString("panelIdentifier").contains("comments")) tok = findStr(o, "token") }
        val t = tok ?: throw RuntimeException("ما لقيتش قسم التعليقات (التعليقات مقفولة؟)")
        val r2 = post("next", JSONObject().put("context", ctx()).put("continuation", t))
        val params = findStr(r2, "createCommentParams") ?: throw RuntimeException("يوتيوب مدّاش صلاحية التعليق من هنا")
        post("comment/create_comment", JSONObject().put("context", ctx()).put("createCommentParams", params).put("commentText", text))
    }
}
