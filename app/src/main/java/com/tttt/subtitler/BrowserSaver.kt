package com.tttt.subtitler

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * (v783) توفير الداتا + السرعة للمتصفح — ملف واحد منفصل، مالوش أي تدخّل في مانع الإعلانات.
 *
 * ⚠ قاعدة حديدية: الملف ده عمره ما بيقرا ولا بيكتب مفتاح "adblock" ولا بيلمس AdBlock/PopupGuard/adWhite.
 *   فحص الإعلانات في shouldInterceptRequest بيحصل الأول (سطر v722) وبعده بس بنجي إحنا — يعني الحظر شغّال في كل الأوضاع بما فيها الطوارئ.
 *
 * القياس: عدّاد النظام لتطبيق Joyboy (TrafficStats لكل UID) كل ثانية، والفرق بيتنسب لنوع الشبكة وقتها (خلوي / واي فاي) = «قياس».
 * التوفير: «تقدير ≈» بأرقام متوسطة ثابتة، ومابيتجمعش أبدًا مع القياس.
 */
class BrowserSaver(private val act: BrowserActivity, private val frame: FrameLayout, private val host: Host) {

    interface Host {
        fun wv(): WebView?
        fun tab(): BTab?
        fun tabs(): List<BTab>
        fun adsBlocked(): Int
        fun fullscreen(): Boolean
        fun reloadCur()
    }

    companion object {
        const val OFF = 0; const val BAL = 1; const val SMART = 2; const val MAX = 3; const val EMERG = 4
        private const val N_NONE = 0; private const val N_CELL = 1; private const val N_WIFI = 2
        private const val AD_AVG = 25_000L     // تقدير: متوسط حجم طلب إعلاني اتمنع
        private const val IMG_AVG = 40_000L    // تقدير: متوسط صورة اتأجّلت/اتمنعت
        private const val FONT_AVG = 30_000L   // تقدير: متوسط خط ويب اتمنع
        private val FONT_EXT = listOf(".woff2", ".woff", ".ttf", ".otf")

        fun isArchive(u: String?): Boolean = u != null && u.startsWith("file://") && u.contains("/br_offline/")
        fun hostOf(u: String?): String = try { android.net.Uri.parse(u ?: "").host ?: "" } catch (_: Throwable) { "" }
        fun fmt(b: Long): String = when {
            b < 1024L -> "$b B"
            b < 1048576L -> String.format(Locale.US, "%.0f KB", b / 1024.0)
            b < 1073741824L -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
            else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
        }
        private fun sha1(s: String): String = try { java.security.MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } } catch (_: Throwable) { s.hashCode().toString() }

        private val JS_LAZY = """(function(){if(window.__jbL)return;window.__jbL=1;function f(){try{var l=document.querySelectorAll('img:not([loading]),iframe:not([loading])');for(var i=0;i<l.length;i++){l[i].loading='lazy'}}catch(e){}}f();try{new MutationObserver(f).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}})();"""
        private val JS_GUARD = """(function(){if(window.__jbG)return;window.__jbG=1;var t=0;function g(){t=Date.now()}['touchend','click','keydown'].forEach(function(e){addEventListener(e,g,true)});function ok(){return Date.now()-Math.max(t,window.__jbT||0)<2500}function h(v){if(v.__jbg)return;v.__jbg=1;try{v.preload='none'}catch(e){}v.addEventListener('play',function(){try{if(!ok()&&(v.muted||v.loop||v.offsetWidth<320)){v.pause()}}catch(e){}},true)}function s(){var l=document.querySelectorAll('video');for(var i=0;i<l.length;i++)h(l[i])}s();try{new MutationObserver(s).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}})();"""
        private val JS_SRCSET = """(function(){if(window.__jbS)return;window.__jbS=1;function f(){try{var l=document.querySelectorAll('img[srcset]:not([data-jb])');for(var i=0;i<l.length;i++){var m=l[i];m.setAttribute('data-jb','1');var c=m.getAttribute('srcset').split(',').map(function(s){var a=s.trim().split(/\s+/);return{u:a[0],w:parseFloat(a[1])||0}}).filter(function(x){return x.u});if(c.length>1){c.sort(function(a,b){return a.w-b.w});var k=c[c.length-1];for(var j=0;j<c.length;j++){if(c[j].w>=400){k=c[j];break}}m.removeAttribute('srcset');m.src=k.u}}}catch(e){}}f();try{new MutationObserver(f).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}})();"""
        private const val JS_VID = "(function(){var l=document.querySelectorAll('video'),r=0;for(var i=0;i<l.length;i++){var v=l[i];if(!v.paused&&!v.ended){r=Math.max(r,v.readyState>2?1:2)}}return r})()"
        private const val JS_VQ = "(function(){var p=document.getElementById('movie_player');if(p&&p.getPlaybackQuality){try{return p.getPlaybackQuality()}catch(e){}}return ''})()"
        private val VQ_STEPS = intArrayOf(144, 240, 360, 480, 720, 1080)
        private const val TRK_AVG = 15_000L    // تقدير: متوسط حجم طلب تتبّع اتمنع
        // (v785) تراكرز/تحليلات طرف ثالث — قايمة المتصفح الخاصة (ماتلمسش AdBlock). «كامل» على الموقع بيعطّلها له.
        private val TRK = listOf("google-analytics.com", "googletagmanager.com", "analytics.google.com", "stats.g.doubleclick.net", "connect.facebook.net",
            "hotjar.com", "mixpanel.com", "segment.io", "segment.com", "amplitude.com", "clarity.ms", "scorecardresearch.com", "quantserve.com",
            "fullstory.com", "mouseflow.com", "crazyegg.com", "nr-data.net", "adobedtm.com", "omtrdc.net")
        private const val JS_NOPRE = "(function(){if(window.__jbP)return;window.__jbP=1;function f(){try{document.querySelectorAll('video:not([data-jbp]),audio:not([data-jbp])').forEach(function(m){m.setAttribute('data-jbp','1');if(m.paused)m.preload='none'});document.querySelectorAll('link[rel=prefetch],link[rel=prerender],link[rel=preload][as=video],link[rel=preload][as=fetch]').forEach(function(l){if(l.parentNode)l.parentNode.removeChild(l)})}catch(e){}}f();try{new MutationObserver(f).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}})();"
        private const val JS_IMGS = "(function(){var n=0;document.querySelectorAll('img').forEach(function(i){if(!i.complete||!i.naturalWidth)n++});return n})()"
        private const val JS_STOPVID = "(function(){document.querySelectorAll('video').forEach(function(v){try{v.pause();v.preload='none'}catch(e){}})})();"
    }

    private val h = Handler(Looper.getMainLooper())
    private val ui get() = act.ui
    private val th get() = act.th
    private val p get() = act.getSharedPreferences("p", 0)
    private val cm = act.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val uid = android.os.Process.myUid()

    // ===== حالة الشبكة والمستوى =====
    @Volatile private var net = N_NONE
    @Volatile private var metered = false
    @Volatile private var poorSys = false
    private var poorMeasured = false
    private var slowTicks = 0; private var fastTicks = 0
    @Volatile var level = BAL; private set
    private val poor get() = poorSys || poorMeasured
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private fun readNet() {
        try {
            val n = cm.activeNetwork
            val c = if (n != null) cm.getNetworkCapabilities(n) else null
            if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { net = N_NONE; metered = false; poorSys = false; return }
            net = if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { if (cm.isActiveNetworkMetered) N_CELL else N_WIFI }   // VPN: بنعتمد على «محدودة» من النظام
                  else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) N_CELL else N_WIFI
            metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) || (net == N_WIFI && p.getBoolean("sv_wifi_metered", false))
            val kb = c.linkDownstreamBandwidthKbps
            poorSys = kb in 1..1200
        } catch (_: Throwable) {}
    }

    private fun computeLevel(): Int {
        if (p.getBoolean("sv_emerg", false)) return EMERG
        when (p.getString("sv_mode", "auto")) { "off" -> return OFF; "always" -> return MAX }
        var l = when { net == N_CELL -> MAX; net == N_WIFI && metered -> SMART; net == N_WIFI -> BAL; else -> SMART }   // مفيش نت: ذكي (الواي فاي عمره ما بيتعامل على إنه مفتوح من غير ما النظام يقول)
        if (poor && l < SMART) l = SMART
        return l
    }

    private fun netName(): String = when { net == N_NONE -> "📴 مفيش نت"; net == N_CELL -> "📶 داتا موبايل"; metered -> "🛜 واي فاي محدود"; else -> "🛜 واي فاي" }
    private fun levelName(l: Int = level): String = when (l) { EMERG -> "🚨 طوارئ الداتا"; MAX -> "توفير أقصى"; SMART -> "توفير ذكي"; BAL -> "متوازن"; else -> "مقفول" } + if (poor && l != OFF) " · اتصال ضعيف" else ""

    private fun netChanged(manual: Boolean = false) {
        val oN = net; val oM = metered; val oL = level
        readNet(); level = computeLevel()
        if (manual || oN != net || oM != metered || oL != level) {
            offTabs.clear()
            reapplyAll()
            if (oL != level || oN != net) { try { Notice.show(act, netName() + " — " + levelName(), 2600L) } catch (_: Throwable) {} }
            updateCap()
        }
    }

    private fun reapplyAll() {
        for (t in host.tabs()) { val w = t.wv ?: continue; try { applyTo(w, t, w.url ?: t.url) } catch (_: Throwable) {} }
        val cur = host.tab(); val w = host.wv()
        if (cur != null && w != null) { val hs = hostOf(w.url ?: cur.url); try { w.evaluateJavascript(jsFor(levelFor(hs), hs), null) } catch (_: Throwable) {} }
    }

    // ===== بروفايلات المواقع (0 تلقائي · 1 نص أولًا · 2 كامل) =====
    // ===== (v785) إعدادات DATA & PERFORMANCE =====
    /** مفيش اختيار صريح = بيتبع مستوى التوفير (قبل كده: التوفير الذكي/الأقصى بيمنع التشغيل التلقائي) */
    private fun autoplayAllowed(lv: Int): Boolean = if (p.contains("sv_autoplay")) p.getBoolean("sv_autoplay", true) else lv < SMART
    private fun preloadAllowed(): Boolean = when (p.getString("sv_pre", "wifi")) { "always" -> true; "off" -> false; else -> net == N_WIFI && !metered }
    private fun bgRestrict(): Boolean = when (p.getString("sv_bg", "cell")) { "always" -> true; "off" -> false; else -> net == N_CELL }
    private fun imgMode(): String = p.getString("sv_img", "opt") ?: "opt"

    private fun siteMode(hs: String): Int = try { JSONObject(p.getString("sv_sites", "{}") ?: "{}").optInt(hs, 0) } catch (_: Throwable) { 0 }
    private fun setSiteMode(hs: String, m: Int) {
        try { val o = JSONObject(p.getString("sv_sites", "{}") ?: "{}"); if (m == 0) o.remove(hs) else o.put(hs, m); p.edit().putString("sv_sites", o.toString()).apply() } catch (_: Throwable) {}
    }
    private fun levelFor(hs: String): Int = if (level == OFF || siteMode(hs) == 2) OFF else level
    private fun textFirst(hs: String): Boolean {
        val lv = levelFor(hs); if (lv == OFF) return false
        return siteMode(hs) == 1 || lv == EMERG || (lv == MAX && p.getBoolean("sv_text_cell", false))
    }

    // ===== تطبيق الإعدادات على WebView =====
    private val offTabs = HashSet<Long>()               // تبويبات بتقرا من الكاش بس (أوفلاين)
    private val backTabs = HashMap<Long, Long>()        // رجوع سريع: الكاش الأول لحد وقت معيّن
    private val fileOpen = HashSet<Long>()              // تبويبات فاتحة أرشيف أوفلاين
    private val imgOk = HashMap<Long, String>()         // «حمّل الصور» اتداس: التبويب ← الموقع
    private val budgetBlk = HashMap<Long, String>()     // الميزانية اتعدّت: الصور موقوفة للتبويب ← الموقع

    fun applyTo(w: WebView, t: BTab, url: String?) {
        val hs = hostOf(url ?: t.url)
        val s = w.settings
        val noImg = when (imgMode()) {
            "never" -> imgOk[t.id] != hs
            "always" -> false
            else -> textFirst(hs) && imgOk[t.id] != hs || (budgetBlk[t.id] == hs && levelFor(hs) != OFF)
        }
        if (p.contains("sv_autoplay") && !p.getBoolean("sv_autoplay", true)) { try { s.mediaPlaybackRequiresUserGesture = true } catch (_: Throwable) {} }
        try { s.blockNetworkImage = noImg } catch (_: Throwable) {}
        if (!t.priv) {
            val now = SystemClock.elapsedRealtime()
            val back = backTabs[t.id]
            if (back != null && now > back) backTabs.remove(t.id)
            try { s.cacheMode = if (t.id in offTabs) WebSettings.LOAD_CACHE_ONLY else if (backTabs.containsKey(t.id)) WebSettings.LOAD_CACHE_ELSE_NETWORK else WebSettings.LOAD_DEFAULT } catch (_: Throwable) {}
        }
        if (t.id in fileOpen && !isArchive(url)) { fileOpen.remove(t.id); try { s.allowFileAccess = false } catch (_: Throwable) {} }
    }

    // ===== (v784) جودة الفيديو: سياسة قابلة للضبط + تكيّف + تقرير صادق عن الإتاحة =====
    // التحكم الحقيقي متاح بس في يوتيوب (واجهة المشغّل بتاعه). باقي المواقع مفيش طريقة موثوقة من WebView ← بنقول «مش متاح» ومابنلمسش DRM.
    private var vqCap = 0                    // السقف الحالي بالبكسل (0 = من غير تحكم)
    private var vqState = ""                 // آخر جودة اشتغلت فعلًا: "" لسه · "na" مش متاح · "720p" …
    private var vqPenalty = 0                // خطوات نزول بسبب التقطيع (0..2) — بترجع واحدة واحدة لما التشغيل يثبت
    private var vqBw = 0.0                   // أعلى سرعة اتقاست وقت تشغيل الفيديو (بايت/ث) بتتآكل ببطء
    private var vqStall = 0; private var vqOk = 0

    private fun vqPolicy(lv: Int): Int {
        if (lv == OFF || !p.getBoolean("sv_vq_auto", true)) return 0
        if (lv >= EMERG) return 240
        val user = p.getString("sv_qmax", "0")?.toIntOrNull() ?: 0
        if (user > 0) return user                                   // اختيارك بيغلب السياسة التلقائية
        var cap = when {
            poor -> 240
            net == N_CELL -> if (lv >= MAX) (p.getString("sv_q", "360")?.toIntOrNull() ?: 360) else 480
            net == N_WIFI && metered -> 480
            net == N_WIFI -> if (vqBw > 900_000.0) 1080 else if (vqBw > 0.0 && vqBw < 300_000.0) 480 else 720
            else -> 480
        }
        if (net == N_CELL) {                                        // ميزانية الداتا المتبقية
            val lim = (p.getString("sv_lim_day", "0")?.toLongOrNull() ?: 0L) * 1048576L
            if (lim > 0) { if (dCell >= lim * 95 / 100) cap = minOf(cap, 240) else if (dCell >= lim * 80 / 100) cap = minOf(cap, 360) }
        }
        if (vqPenalty > 0) {
            var i = VQ_STEPS.indexOfFirst { it >= cap }; if (i < 0) i = VQ_STEPS.size - 1
            cap = VQ_STEPS[maxOf(1, i - vqPenalty)]
        }
        return cap
    }
    private fun ytQ(cap: Int): String = when { cap <= 0 -> "highres"; cap <= 144 -> "tiny"; cap <= 240 -> "small"; cap <= 360 -> "medium"; cap <= 480 -> "large"; cap <= 720 -> "hd720"; else -> "hd1080" }
    private fun qName(q: String): String = when (q) { "tiny" -> "144p"; "small" -> "240p"; "medium" -> "360p"; "large" -> "480p"; "hd720" -> "720p"; "hd1080" -> "1080p"; "hd1440" -> "1440p"; "hd2160" -> "2160p"; "highres" -> "أعلى"; else -> q }
    // بيتطبّق مرة لكل فيديو/سقف (مش كل ثانيتين) عشان اختيارك اليدوي من قايمة يوتيوب يفضل لحد الفيديو الجاي. المدى (tiny..سقف) يسيب لليوتيوب ينزل لتحت لوحده لو النت ضعف.
    private fun ytJs(q: String): String = "window.__jbQv='$q';if(!window.__jbQi){window.__jbQi=setInterval(function(){var p=document.getElementById('movie_player');if(!p||!p.setPlaybackQualityRange||!window.__jbQv)return;try{var d=p.getVideoData&&p.getVideoData();var k=((d&&d.video_id)||'')+'|'+window.__jbQv;if(window.__jbQk!==k){window.__jbQk=k;p.setPlaybackQualityRange('tiny',window.__jbQv)}}catch(e){}},2000)};"
    private fun vqLabel(): String = when {
        vqState == "na" -> "جودة: مش متاح هنا"
        vqState.isNotEmpty() -> "جودة " + vqState + (if (vqCap > 0) " (سقف ${vqCap}p)" else "")
        else -> ""
    }
    private fun vqSummary(): String {
        val user = p.getString("sv_qmax", "0")?.toIntOrNull() ?: 0
        val mode = when { !p.getBoolean("sv_vq_auto", true) -> "التحكم مقفول"; user > 0 -> "اختيارك: ${user}p"; vqCap > 0 -> "تلقائي: سقف ${vqCap}p"; else -> "تلقائي" }
        val st = when { vqState == "na" -> " — الموقع ده مش بيدّي تحكم في الجودة"; vqState.isNotEmpty() -> " — شغّال $vqState"; else -> "" }
        return mode + (if (vqPenalty > 0) " (نازل $vqPenalty خطوة بسبب التقطيع)" else "") + st
    }
    private fun vqReeval(w: WebView) {
        val hs = hostOf(w.url); val cap = vqPolicy(levelFor(hs))
        if (cap == vqCap) return
        vqCap = cap
        if (isYt(hs)) try { w.evaluateJavascript(ytJs(ytQ(cap)), null) } catch (_: Throwable) {}
    }
    private fun isYt(hs: String) = hs.endsWith("youtube.com") || hs == "youtu.be"

    private fun jsFor(lv: Int, hs: String): String {
        val sb = StringBuilder()
        if (lv >= SMART) { sb.append(JS_LAZY); if (lv >= MAX && imgMode() != "always") sb.append(JS_SRCSET) }
        if (!autoplayAllowed(lv)) sb.append(JS_GUARD)
        if (lv != OFF && !preloadAllowed()) sb.append(JS_NOPRE)
        if (isYt(hs)) { vqCap = vqPolicy(lv); sb.append(ytJs(ytQ(vqCap))) }
        return sb.toString()
    }

    // ===== خطافات من المتصفح =====
    private var watchId = -1L
    private var pageBytes = 0L
    private var budgetHit = false
    private var finishAt = 0L
    private val stage = HashMap<Long, Int>()
    private val stageUrl = HashMap<Long, String>()
    private val savedFont = AtomicLong(0L)
    private val savedTrk = AtomicLong(0L)
    private val tabBytes = HashMap<Long, Long>()   // (v785) تقريبي: بينسب للتبويب المفتوح وقت التحميل/الفيديو

    /** قبل أي تحميل بيعمله المستخدم (loadIn) */
    fun beforeLoad(w: WebView, t: BTab, u: String) {
        stage.remove(t.id); stageUrl.remove(t.id); offTabs.remove(t.id); budgetBlk.remove(t.id)
        touch(t); applyTo(w, t, u)
    }

    /** هيدر Save-Data للصفحة الرئيسية (المواقع اللي بتدعمه بتبعت نسخة أخف) */
    fun headersFor(u: String): Map<String, String>? = if (levelFor(hostOf(u)) >= SMART) mapOf("Save-Data" to "on") else null

    fun pageStarted(t: BTab, w: WebView, url: String) {
        val su = stageUrl[t.id]
        if (su != null && su != url && !isArchive(url)) { stage.remove(t.id); stageUrl.remove(t.id); offTabs.remove(t.id) }
        if (budgetBlk[t.id] != null && budgetBlk[t.id] != hostOf(url)) budgetBlk.remove(t.id)
        applyTo(w, t, url)
        if (t === host.tab()) { watchId = t.id; pageBytes = 0L; budgetHit = false; finishAt = 0L; vqPenalty = 0; vqState = ""; vqStall = 0; vqOk = 0 }
        val hs = hostOf(url)
        try { w.evaluateJavascript(jsFor(levelFor(hs), hs), null) } catch (_: Throwable) {}
    }

    fun pageFinished(t: BTab, w: WebView, url: String?) {
        if (t === host.tab()) finishAt = SystemClock.elapsedRealtime()
        if (backTabs.remove(t.id) != null) applyTo(w, t, url)
        val hs = hostOf(url)
        try { w.evaluateJavascript(jsFor(levelFor(hs), hs), null) } catch (_: Throwable) {}
        if (url != null && url.startsWith("http") && !t.priv && level != EMERG && p.getBoolean("sv_off_auto", true)) {
            try { if (BrowserStore.isMarked(act, url)) saveArchive(t, w, false) } catch (_: Throwable) {}
        }
    }

    /** true = اتعاملنا مع الخطأ (كاش ثم أرشيف أوفلاين)؛ false = سيب المتصفح يعرض صفحة الخطأ */
    fun onError(t: BTab, w: WebView, u: String, code: Int): Boolean {
        if (t.priv) return false
        val netErr = net == N_NONE || code == -2 || code == -6 || code == -7 || code == -8
        if (!netErr) return false
        val st = stage[t.id] ?: 0
        if (st == 0) {
            stage[t.id] = 1; stageUrl[t.id] = u; offTabs.add(t.id); applyTo(w, t, u)
            h.post { try { w.loadUrl(u) } catch (_: Throwable) {} }
            return true
        }
        if (st == 1) {
            offTabs.remove(t.id); stage[t.id] = 2
            val f = archiveFor(u) ?: return false
            fileOpen.add(t.id)
            try { w.settings.allowFileAccess = true } catch (_: Throwable) {}
            h.post { try { w.loadUrl("file://" + f.absolutePath); Notice.show(act, "📴 نسخة أوفلاين محفوظة من الصفحة", 2600L) } catch (_: Throwable) {} }
            return true
        }
        return false
    }

    /** رجوع سريع: من الكاش الأول (حتى لو قديم) بدل ما ينتظر الشبكة */
    fun back(w: WebView, t: BTab) {
        if (!t.priv) { backTabs[t.id] = SystemClock.elapsedRealtime() + 8000L; try { w.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK } catch (_: Throwable) {} }
        w.goBack()
    }

    /** وضع الطوارئ: بيمنع الخطوط بس (الصور بتتمنع بإعداد blockNetworkImage والفيديو بالحارس)؛ بيتنفذ بعد فحص الإعلانات */
    fun intercept(u: String, t: BTab): WebResourceResponse? {
        if (level < SMART) return null
        val hs = hostOf(t.url)
        if (siteMode(hs) == 2) return null
        if (p.getBoolean("sv_trk", true)) {
            val rh = hostOf(u)
            if (rh.isNotEmpty() && rh != hs && TRK.any { rh == it || rh.endsWith(".$it") }) {
                savedTrk.addAndGet(TRK_AVG)
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        if (level < EMERG) return null
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        if (FONT_EXT.none { path.endsWith(it) }) return null
        savedFont.addAndGet(FONT_AVG)
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    // ===== تجميد التبويبات =====
    private val lastUse = HashMap<Long, Long>()
    fun touch(t: BTab) { lastUse[t.id] = SystemClock.elapsedRealtime() }

    private fun freezeLimitMs(): Long = when (level) { EMERG -> 60_000L; MAX -> 180_000L; SMART -> 300_000L; else -> 600_000L }

    fun freezeIdle(force: Boolean = false): Int {
        if (!p.getBoolean("sv_freeze", true) && !force) return 0
        val cur = host.tab(); val now = SystemClock.elapsedRealtime(); var n = 0
        for (t in host.tabs()) {
            if (t === cur || t.priv || t.hold != null || t.blockedUrl != null || !t.loaded) continue
            val w = t.wv ?: continue
            val idle = now - (lastUse[t.id] ?: now.also { lastUse[t.id] = it })
            if (idle < (if (force) 20_000L else freezeLimitMs())) continue
            try {
                val b = Bundle(); w.saveState(b)
                t.url = w.url?.takeIf { !it.startsWith("data:") } ?: t.url
                t.frozen = b
                w.stopLoading(); (w.parent as? ViewGroup)?.removeView(w); w.destroy()
                t.wv = null; t.loaded = false; n++
            } catch (_: Throwable) {}
        }
        return n
    }
    fun frozenCount(): Int = host.tabs().count { it.frozen != null && it.wv == null }
    fun onTrim(level: Int) { if (level >= 10) freezeIdle(true) }   // TRIM_MEMORY_RUNNING_LOW وأعلى

    // ===== أوفلاين =====
    private fun offDir(): File = File(act.filesDir, "br_offline").also { it.mkdirs() }
    private fun offIdx(): JSONObject = try { JSONObject(p.getString("sv_off", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
    private fun archiveFor(u: String): File? {
        val o = offIdx(); val k = sha1(u.substringBefore('#'))
        val e = o.optJSONObject(k) ?: return null
        val f = File(offDir(), e.optString("f")); return if (f.exists() && f.length() > 0) f else null
    }
    fun saveArchive(t: BTab, w: WebView, manual: Boolean) {
        val u = (w.url ?: "").substringBefore('#')
        if (!u.startsWith("http") || t.priv) { if (manual) Notice.show(act, "الصفحة دي مش قابلة للحفظ", 2000L); return }
        val k = sha1(u); val fn = "$k.mht"; val f = File(offDir(), fn)
        if (!manual) { val e = offIdx().optJSONObject(k); if (e != null && System.currentTimeMillis() - e.optLong("ts") < 12 * 3600_000L) return }
        val title = w.title ?: t.title
        try {
            w.saveWebArchive(f.absolutePath, false) { saved ->
                if (saved != null && f.exists()) {
                    try { val o = offIdx(); o.put(k, JSONObject().put("u", u).put("t", title ?: "").put("ts", System.currentTimeMillis()).put("f", fn)); p.edit().putString("sv_off", o.toString()).apply() } catch (_: Throwable) {}
                    trimOffline()
                    if (manual) Notice.show(act, "💾 اتحفظت أوفلاين (" + fmt(f.length()) + ")", 2200L)
                } else if (manual) Notice.show(act, "الحفظ فشل", 2000L)
            }
        } catch (e: Throwable) { if (manual) Notice.show(act, "الحفظ فشل", 2000L) }
    }
    private fun trimOffline() {
        try {
            val o = offIdx(); val rows = ArrayList<Triple<String, Long, Long>>()
            val it = o.keys(); while (it.hasNext()) { val k = it.next(); val e = o.optJSONObject(k) ?: continue; rows.add(Triple(k, e.optLong("ts"), File(offDir(), e.optString("f")).length())) }
            rows.sortByDescending { it.second }
            var tot = 0L; var n = 0
            for (r in rows) { n++; tot += r.third; if (n > 60 || tot > 100L * 1048576L) { o.optJSONObject(r.first)?.let { File(offDir(), it.optString("f")).delete() }; o.remove(r.first) } }
            p.edit().putString("sv_off", o.toString()).apply()
        } catch (_: Throwable) {}
    }
    private fun openArchive(f: File) {
        val w = host.wv() ?: return; val t = host.tab() ?: return
        fileOpen.add(t.id); try { w.settings.allowFileAccess = true } catch (_: Throwable) {}
        w.loadUrl("file://" + f.absolutePath)
    }

    // ===== القياس =====
    private var running = false
    private var lastRx = -1L; private var lastTx = -1L; private var lastTickAt = 0L
    private var supported = true
    private var sCell = 0L; private var sWifi = 0L
    private var dCell = 0L; private var dWifi = 0L; private var dSaved = 0L
    private var vidBytes = 0L; private var videoOn = false
    private var speed = 0.0
    private var tickN = 0
    private var dayKey = ""
    private var lastAds = 0; private var savedAds = 0L; private var savedImg = 0L
    private val siteBytes = HashMap<String, Long>()
    private var hist = JSONObject()
    private var cap: TextView? = null

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date())
    private fun loadDay() {
        dayKey = today()
        hist = try { JSONObject(p.getString("sv_hist", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val a = hist.optJSONArray(dayKey)
        dCell = a?.optLong(0) ?: 0L; dWifi = a?.optLong(1) ?: 0L; dSaved = a?.optLong(2) ?: 0L
    }
    private fun persist() {
        try {
            if (dayKey.isEmpty()) loadDay()
            hist.put(dayKey, JSONArray().put(dCell).put(dWifi).put(dSaved))
            val keys = ArrayList<String>(); val it = hist.keys(); while (it.hasNext()) keys.add(it.next())
            keys.sort(); while (keys.size > 100) hist.remove(keys.removeAt(0))
            p.edit().putString("sv_hist", hist.toString()).apply()
        } catch (_: Throwable) {}
    }
    private fun savedEst(): Long = savedAds + savedImg + savedFont.get() + savedTrk.get()

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (dayKey != today()) { persist(); loadDay() }
        val rx = TrafficStats.getUidRxBytes(uid); val tx = TrafficStats.getUidTxBytes(uid)
        supported = rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong()
        var dRx = 0L; var d = 0L
        if (supported) {
            if (lastRx >= 0) { dRx = maxOf(0L, rx - lastRx); d = dRx + maxOf(0L, tx - lastTx) }
            lastRx = rx; lastTx = tx
        }
        val dt = if (lastTickAt > 0) maxOf(0.2, (now - lastTickAt) / 1000.0) else 1.0
        lastTickAt = now
        val cur = host.tab(); val curHost = hostOf(cur?.url)
        val loading = cur != null && (cur.progress < 100 || (finishAt > 0 && now - finishAt < 2500L))
        if (d > 0 && net != N_NONE) {
            if (net == N_CELL) { sCell += d; dCell += d } else { sWifi += d; dWifi += d }
            if (videoOn) { vidBytes += d; if (curHost.isNotEmpty()) siteBytes[curHost] = (siteBytes[curHost] ?: 0L) + d; if (cur != null) tabBytes[cur.id] = (tabBytes[cur.id] ?: 0L) + d }
            else if (loading) { pageBytes += d; if (curHost.isNotEmpty()) siteBytes[curHost] = (siteBytes[curHost] ?: 0L) + d; if (cur != null) tabBytes[cur.id] = (tabBytes[cur.id] ?: 0L) + d }
        }
        speed = speed * 0.6 + (dRx / dt) * 0.4
        if (videoOn) { val bw = dRx / dt; vqBw = if (bw > vqBw) bw else vqBw * 0.995 + bw * 0.005 }
        // اتصال ضعيف: سرعة قياس تحت 25KB/ث وإحنا بنحمّل ≥6 ثواني ← ضعيف؛ فوق 150KB/ث ≥3 ثواني ← رجع
        if (loading && net != N_NONE) {
            if (dRx / dt < 25_000.0) { slowTicks++; fastTicks = 0 } else if (dRx / dt > 150_000.0) { fastTicks++; slowTicks = 0 } else { slowTicks = 0; fastTicks = 0 }
            if (!poorMeasured && slowTicks >= 6) { poorMeasured = true; netChanged(true) }
            else if (poorMeasured && fastTicks >= 3) { poorMeasured = false; netChanged(true) }
        }
        val ab = host.adsBlocked(); if (ab > lastAds) savedAds += (ab - lastAds) * AD_AVG; lastAds = ab
        // ميزانية الصفحة
        if (cur != null && cur.id == watchId && !budgetHit && loading && levelFor(curHost) != OFF) {
            val lim = budgetBytes()
            if (lim > 0 && pageBytes > lim) { budgetHit = true; onBudget(cur, curHost) }
        }
        if (tickN % 2 == 0) pollVideo()
        if (tickN % 15 == 0 && tickN > 0) freezeIdle()
        if (tickN % 10 == 0 && tickN > 0) { persist(); checkLimits() }
        tickN++
        updateCap()
    }

    private fun pollVideo() {
        val w = host.wv() ?: run { videoOn = false; return }
        try {
            w.evaluateJavascript(JS_VID) { r ->
                val on = (r == "1" || r == "2"); val stalled = (r == "2")
                videoOn = on
                if (!on) { vqState = ""; vqStall = 0 }
                else {
                    val hs = hostOf(w.url)
                    if (stalled) { vqOk = 0; vqStall++; if (vqStall >= 3 && vqPenalty < 2) { vqPenalty++; vqStall = 0 } }          // تقطيع متكرر ← انزل خطوة
                    else { vqStall = 0; vqOk++; if (vqOk >= 40 && vqPenalty > 0) { vqPenalty--; vqOk = 0 } }                       // ثبات ~80ث ← اطلع خطوة
                    vqReeval(w)
                    if (isYt(hs)) { try { w.evaluateJavascript(JS_VQ) { q -> vqState = qName((q ?: "").trim('"')) } } catch (_: Throwable) {} }
                    else vqState = "na"
                }
            }
        } catch (_: Throwable) { videoOn = false }
    }

    private fun budgetBytes(): Long {
        val mb = p.getString("sv_budget_mb", "3")?.toDoubleOrNull() ?: 3.0
        if (mb <= 0.0) return 0L
        var b = mb * 1048576.0
        if (level == EMERG) b = minOf(b, 1048576.0)
        if (poor) b *= 0.6
        return b.toLong()
    }

    private fun onBudget(t: BTab, hs: String) {
        val w = host.wv() ?: return
        val act2 = (levelFor(hs) >= MAX || textFirst(hs)) && imgMode() != "always"
        if (act2) {
            budgetBlk[t.id] = hs
            try { w.settings.blockNetworkImage = true } catch (_: Throwable) {}
            try { w.evaluateJavascript(JS_STOPVID, null) } catch (_: Throwable) {}
            try { w.evaluateJavascript(JS_IMGS) { r -> savedImg += ((r ?: "0").trim('"').toIntOrNull() ?: 0) * IMG_AVG } } catch (_: Throwable) {}
            Notice.show(act, "⚠ الصفحة تعدّت ميزانية " + fmt(budgetBytes()) + " — وقفت الصور والفيديو (دوس الكبسولة ← حمّل الصور)", 3800L)
        } else {
            Notice.show(act, "⚠ الصفحة دي تقيلة: " + fmt(pageBytes) + " اتحمّلت لحد دلوقتي", 3000L)
        }
    }

    private fun limStage(used: Long, lim: Long): Int = when { lim <= 0L -> 0; used >= lim -> 4; used * 100 >= lim * 95 -> 3; used * 100 >= lim * 80 -> 2; used * 100 >= lim * 50 -> 1; else -> 0 }
    /** إشعار لكل مرحلة مرة واحدة في النطاق (يوم/شهر/جلسة): 50 معلومة · 80 تحذير · 95 حرج · 100 توفير أقصى. بيرجّع true أول ما يوصل 100% */
    private fun stageNote(key: String, scope: String, used: Long, lim: Long, what: String, cell: Boolean): Boolean {
        val st = limStage(used, lim); if (st == 0) return false
        val saved = p.getString(key, "") ?: ""
        val prev = if (saved.startsWith("$scope:")) (saved.substringAfter(':').toIntOrNull() ?: 0) else 0
        if (st <= prev) return false
        p.edit().putString(key, "$scope:$st").apply()
        val tail = (if (cell) "داتا الموبايل " else "الواي فاي ") + what
        val auto = cell && p.getBoolean("sv_auto_emerg", true) && p.getString("sv_snooze", "") != dayKey
        when (st) {
            1 -> Notice.show(act, "ℹ️ استهلكت 50% من حد $tail (" + fmt(lim) + ")", 3000L)
            2 -> Notice.show(act, "🟠 استهلكت 80% من حد $tail", 4000L)
            3 -> Notice.show(act, "🔴 استهلكت 95% من حد $tail — قرّب تعدّيه", 4500L)
            else -> Notice.show(act, "🔴 وصلت حد $tail (" + fmt(lim) + ")" + (if (auto) " — شغّلت توفير أقصى (كمّل تصفح من ⚙ توفير الداتا)" else " — تقدر تكمّل تصفح عادي"), 5000L)
        }
        return st == 4
    }
    private fun checkLimits() {
        fun mb(k: String) = (p.getString(k, "0")?.toLongOrNull() ?: 0L) * 1048576L
        val hitD = stageNote("sv_n_day", dayKey, dCell, mb("sv_lim_day"), "اليومي", true)
        val hitM = stageNote("sv_n_mon", dayKey.take(6), monthCell(), mb("sv_lim_mon"), "الشهري", true)
        val hitS = stageNote("sv_n_ses", "s$sessId", sCell, mb("sv_lim_ses"), "للجلسة", true)
        stageNote("sv_n_wifi", dayKey, dWifi, mb("sv_lim_wifi"), "اليومي", false)   // اختياري: تنبيه بس
        if ((hitD || hitM || hitS) && p.getBoolean("sv_auto_emerg", true) && !p.getBoolean("sv_emerg", false) && p.getString("sv_snooze", "") != dayKey) {
            p.edit().putBoolean("sv_emerg", true).apply(); netChanged(true)      // 100%: توفير أقصى تلقائي — النت مابيتقطعش أبدًا
        }
    }
    private fun monthCell(): Long {
        var s = 0L; val pre = dayKey.take(6); val it = hist.keys()
        while (it.hasNext()) { val k = it.next(); if (k.startsWith(pre)) s += hist.optJSONArray(k)?.optLong(0) ?: 0L }
        return s
    }

    // ===== دورة الحياة =====
    fun attach() {
        readNet(); level = computeLevel(); loadDay(); lastAds = host.adsBlocked()
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(n: Network) { h.post { netChanged() } }
                    override fun onLost(n: Network) { h.post { netChanged() } }
                    override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { h.post { netChanged() } }
                }
                cm.registerDefaultNetworkCallback(cb); netCb = cb
            } catch (_: Throwable) {}
        }
        buildCap()
    }
    private var bgBlocked = false
    private fun setBgBlock(on: Boolean) { for (t in host.tabs()) { try { t.wv?.settings?.blockNetworkLoads = on } catch (_: Throwable) {} }; bgBlocked = on }
    fun start() { if (bgBlocked) setBgBlock(false); if (running) return; running = true; lastRx = -1L; lastTickAt = 0L; netChanged(); h.post(tickR) }
    fun stop() { running = false; h.removeCallbacks(tickR); persist(); if (bgRestrict()) setBgBlock(true) }   // (v785) قيود الخلفية: الصفحات ماتطلبش شبكة وإحنا برّه الشاشة (بترجع في onResume)
    fun destroy() { stop(); try { netCb?.let { cm.unregisterNetworkCallback(it) } } catch (_: Throwable) {}; netCb = null }
    private val tickR = object : Runnable { override fun run() { if (!running) return; try { tick() } catch (e: Throwable) { LogStore.err("Saver:tick", e) }; h.postDelayed(this, 1000L) } }

    // ===== الكبسولة العايمة (v785: تتوسّع في مكانها · تتسحب · أرقامها بتتحرك بنعومة) =====
    private var capOpen = false
    private val shown = LongArray(4); private val tgt = LongArray(4)     // [جلسة خلوي، جلسة واي فاي، اليوم خلوي، اليوم واي فاي]
    private var anim: android.animation.ValueAnimator? = null
    private var lastCol = 0
    private var dX = 0f; private var dY = 0f; private var bX = 0f; private var bY = 0f; private var moved = false
    private val sessId = System.currentTimeMillis()

    private fun clampCap() {
        val c = cap ?: return; val w = frame.width; val h = frame.height
        if (w == 0 || h == 0 || c.width == 0) return
        val minX = -c.left.toFloat(); val maxX = maxOf(minX, (w - c.right).toFloat())
        val minY = -c.top.toFloat(); val maxY = maxOf(minY, (h - c.bottom).toFloat())
        c.translationX = c.translationX.coerceIn(minX, maxX); c.translationY = c.translationY.coerceIn(minY, maxY)
    }
    private val capTouch = View.OnTouchListener { v, e ->
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { dX = e.rawX; dY = e.rawY; bX = v.translationX; bY = v.translationY; moved = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val ddx = e.rawX - dX; val ddy = e.rawY - dY
                if (!moved && (Math.abs(ddx) > ui.dp(8) || Math.abs(ddy) > ui.dp(8))) moved = true
                if (moved) { v.translationX = bX + ddx; v.translationY = bY + ddy; clampCap() }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (moved) p.edit().putFloat("sv_cap_dx", v.translationX).putFloat("sv_cap_dy", v.translationY).apply()
                else if (e.eventTime - e.downTime > 600L) showStats()
                else { capOpen = !capOpen; render(); v.post { clampCap() } }
            }
        }
        true
    }
    private fun buildCap() {
        val c = TextView(act).apply {
            textSize = 10.5f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); background = ui.box(0xE61E2A3A.toInt(), 0x66FFFFFF, 14); elevation = ui.dp(5).toFloat()
            isFocusable = false; visibility = View.GONE
            setOnTouchListener(capTouch)
        }
        cap = c
        frame.addView(c, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(84) })
        c.translationX = p.getFloat("sv_cap_dx", 0f); c.translationY = p.getFloat("sv_cap_dy", 0f)
        c.post { clampCap() }
    }
    private fun updateCap() {
        val c = cap ?: return
        if (!p.getBoolean("sv_cap", true) || host.fullscreen()) { c.visibility = View.GONE; anim?.cancel(); return }
        if (c.visibility != View.VISIBLE) c.visibility = View.VISIBLE
        val n = longArrayOf(sCell, sWifi, dCell, dWifi)
        if (n.contentEquals(tgt)) { render(); return }
        val from = shown.copyOf(); n.copyInto(tgt)
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            addUpdateListener { a -> val f = a.animatedValue as Float; for (i in 0..3) shown[i] = from[i] + ((tgt[i] - from[i]) * f).toLong(); render() }
            start()
        }
    }
    private fun render() {
        val c = cap ?: return
        val sb = StringBuilder()
        if (!capOpen) sb.append("📶 ").append(fmt(shown[0])).append("  🛜 ").append(fmt(shown[1]))
        else {
            sb.append("الجلسة  Σ ").append(fmt(shown[0] + shown[1])).append('\n').append("📶 ").append(fmt(shown[0])).append("   🛜 ").append(fmt(shown[1])).append('\n')
            sb.append("اليوم  Σ ").append(fmt(shown[2] + shown[3])).append('\n').append("📶 ").append(fmt(shown[2])).append("   🛜 ").append(fmt(shown[3])).append('\n')
            sb.append("⬇ ").append(fmt(speed.toLong())).append("/s")
            val parts = ArrayList<String>()
            val ch = hostOf(host.tab()?.url); val sbv = siteBytes[ch]
            if (sbv != null && sbv > 0) parts.add("🌐 " + fmt(sbv))
            if (videoOn) parts.add("🎬 " + fmt(vidBytes) + vqLabel().let { if (it.isEmpty()) "" else " · $it" })
            val sv = savedEst(); if (sv > 0) parts.add("💚≈" + fmt(sv))
            if (parts.isNotEmpty()) sb.append('\n').append(parts.joinToString("  "))
            sb.append("\nاضغط مطوّلًا للتفاصيل · اسحبها لتحريكها")
        }
        c.text = sb.toString()
        val lim = (p.getString("sv_lim_day", "0")?.toLongOrNull() ?: 0L) * 1048576L
        val col = when {
            level == EMERG -> 0xE6B71C1C.toInt()
            lim > 0 && dCell >= lim -> 0xE6B71C1C.toInt()
            lim > 0 && dCell >= lim * 8 / 10 -> 0xE6E65100.toInt()
            net == N_CELL -> 0xE61E2A3A.toInt()
            else -> 0xE6244A36.toInt()
        }
        if (col != lastCol) { lastCol = col; try { c.background = ui.box(col, 0x66FFFFFF, 14) } catch (_: Throwable) {} }
    }

    // ===== (v785) قياس النظام الاختياري + مسح الإحصائيات =====
    @Suppress("DEPRECATION")
    private fun usageGranted(): Boolean = try {
        val ao = act.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val m = if (Build.VERSION.SDK_INT >= 29) ao.unsafeCheckOpNoThrow("android:get_usage_stats", uid, act.packageName) else ao.checkOpNoThrow("android:get_usage_stats", uid, act.packageName)
        m == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    /** بايتات تطبيق Joyboy كله (مش المتصفح بس) النهارده من NetworkStatsManager؛ -1 لو مش متاح */
    @Suppress("DEPRECATION")
    private fun sysBytes(type: Int): Long = try {
        val nsm = act.getSystemService(Context.NETWORK_STATS_SERVICE) as android.app.usage.NetworkStatsManager
        val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val ns = nsm.queryDetailsForUid(type, null, cal.timeInMillis, System.currentTimeMillis(), uid)
        var sum = 0L; val b = android.app.usage.NetworkStats.Bucket()
        try { while (ns.hasNextBucket()) { ns.getNextBucket(b); sum += b.rxBytes + b.txBytes } } finally { ns.close() }
        sum
    } catch (_: Throwable) { -1L }

    private fun resetStats() {
        anim?.cancel()
        sCell = 0L; sWifi = 0L; dCell = 0L; dWifi = 0L; dSaved = 0L; vidBytes = 0L; pageBytes = 0L; savedAds = 0L; savedImg = 0L
        savedFont.set(0L); savedTrk.set(0L); siteBytes.clear(); tabBytes.clear(); hist = JSONObject(); lastAds = host.adsBlocked()
        for (i in 0..3) { shown[i] = 0L; tgt[i] = 0L }
        p.edit().remove("sv_hist").remove("sv_n_day").remove("sv_n_mon").remove("sv_n_ses").remove("sv_n_wifi").apply()
        persist(); updateCap()
    }

    // ===== شاشة الإحصائيات =====
    private fun row(label: String, value: String, tag: String): View = ui.text("$label: $value  [$tag]", 13f, th.text).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) }

    fun showStats() {
        persist()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        col.addView(ui.text(netName() + " — " + levelName(), 15f, th.primary, true).apply { setPadding(0, 0, 0, ui.dp(6)) })
        if (!supported) col.addView(ui.text("⚠ النظام مش بيدّي عدّاد الشبكة على الجهاز ده — القياس مش متاح", 12f, 0xFFD32F2F.toInt()))
        col.addView(ui.text("القياس = عدّاد النظام لتطبيق Joyboy وقت ما المتصفح مفتوح (أي داتا تانية للتطبيق في الوقت ده بتتحسب). التحميلات اللي بيعملها «نظام التحميل» مش بتتعد هنا. التقدير ≈ بيتعرض لوحده ومش بيتجمع مع القياس.", 11f, th.muted))
        col.addView(row("الجلسة 📶 خلوي", fmt(sCell), "قياس")); col.addView(row("الجلسة 🛜 واي فاي", fmt(sWifi), "قياس"))
        col.addView(row("النهارده 📶 خلوي", fmt(dCell), "قياس")); col.addView(row("النهارده 🛜 واي فاي", fmt(dWifi), "قياس"))
        val ch = hostOf(host.tab()?.url)
        col.addView(row("الموقع الحالي" + if (ch.isEmpty()) "" else " ($ch)", fmt(siteBytes[ch] ?: 0L), "قياس تقريبي: اللي نزل وهو بيحمّل/بيشغّل"))
        col.addView(row("الفيديو (الجلسة)", fmt(vidBytes) + if (videoOn) " — شغّال دلوقتي" else "", "قياس تقريبي"))
        col.addView(row("جودة الفيديو", vqSummary(), "تحكم يوتيوب بس"))
        col.addView(row("سرعة التنزيل", fmt(speed.toLong()) + "/ث", "قياس"))
        col.addView(row("موفّر النهارده", "≈ " + fmt(dSaved.coerceAtLeast(savedEst())), "تقدير"))
        col.addView(ui.text("   (إعلانات ≈ " + fmt(savedAds) + " · صور ≈ " + fmt(savedImg) + " · خطوط ≈ " + fmt(savedFont.get()) + " · تراكرز ≈ " + fmt(savedTrk.get()) + " — بمتوسطات ثابتة)", 11f, th.muted))
        val lim = (p.getString("sv_lim_day", "0")?.toLongOrNull() ?: 0L)
        if (lim > 0) col.addView(row("حد اليوم (خلوي)", fmt(dCell) + " من " + lim + " MB", "قياس"))
        val lm = (p.getString("sv_lim_mon", "0")?.toLongOrNull() ?: 0L)
        if (lm > 0) col.addView(row("حد الشهر (خلوي)", fmt(monthCell()) + " من " + lm + " MB", "قياس"))
        col.addView(ui.text("📅 آخر 7 أيام", 13f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        val keys = ArrayList<String>(); val it = hist.keys(); while (it.hasNext()) keys.add(it.next()); keys.sortDescending()
        var wc = 0L; var ww = 0L
        for (k in keys.take(7)) { val a = hist.optJSONArray(k) ?: continue; wc += a.optLong(0); ww += a.optLong(1); col.addView(ui.text(k.substring(4, 6) + "/" + k.substring(6) + "   📶 " + fmt(a.optLong(0)) + " · 🛜 " + fmt(a.optLong(1)), 12.5f, th.text).apply { setPadding(0, ui.dp(1), 0, ui.dp(1)) }) }
        col.addView(ui.text("الأسبوع: 📶 " + fmt(wc) + " · 🛜 " + fmt(ww) + "   |   الشهر (خلوي): " + fmt(monthCell()), 12f, th.muted))
        val curT = host.tab()
        if (curT != null) col.addView(row("التبويب الحالي", fmt(tabBytes[curT.id] ?: 0L), "قياس تقريبي"))
        val topT = tabBytes.entries.sortedByDescending { it.value }.take(5)
        if (topT.size > 1) {
            col.addView(ui.text("🗂 حسب التبويب (بينسب للتبويب المفتوح وقت التحميل — الخلفية مش بتتقاس)", 13f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
            for ((id, v) in topT) { val tt = host.tabs().firstOrNull { it.id == id }; col.addView(ui.text("• " + (tt?.title?.ifBlank { hostOf(tt?.url) } ?: "تبويب اتقفل").take(34) + " — " + fmt(v), 12.5f, th.text)) }
        }
        val ls = (p.getString("sv_lim_ses", "0")?.toLongOrNull() ?: 0L); if (ls > 0) col.addView(row("حد الجلسة (خلوي)", fmt(sCell) + " من " + ls + " MB", "قياس"))
        val lw = (p.getString("sv_lim_wifi", "0")?.toLongOrNull() ?: 0L); if (lw > 0) col.addView(row("حد الواي فاي اليومي", fmt(dWifi) + " من " + lw + " MB", "قياس"))
        col.addView(ui.text("📏 قياس النظام (NetworkStatsManager)", 13f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        if (usageGranted()) {
            val tv = ui.text("بيتحسب…", 12f, th.text)
            col.addView(tv)
            Thread {
                val m = if (Build.VERSION.SDK_INT >= 29) sysBytes(ConnectivityManager.TYPE_MOBILE) else -1L
                val w = sysBytes(ConnectivityManager.TYPE_WIFI)
                act.runOnUiThread { tv.text = "النهارده (تطبيق Joyboy كله): 📶 " + (if (m >= 0) fmt(m) else "غير متاح") + " · 🛜 " + (if (w >= 0) fmt(w) else "غير متاح") + "  [قياس النظام — للمقارنة بس، مش بيتجمّع مع الأرقام اللي فوق]" }
            }.start()
        } else {
            col.addView(ui.text("اختياري: بيدّي رقم أدق من النظام لتطبيق Joyboy كله. محتاج تدّي إذن «الوصول لبيانات الاستخدام» بإيدك — مش بنطلبه من نفسنا.", 11.5f, th.muted))
            col.addView(ui.button("📏 فعّل قياس النظام") { try { act.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (_: Throwable) { Notice.show(act, "الجهاز مش بيدعم شاشة الإذن دي", 3000L) } })
        }
        col.addView(ui.text("ℹ️ مفيش عدّ مزدوج: كل الأرقام من مصدر واحد (عدّاد النظام لتطبيق Joyboy) — الموقع والتبويب والفيديو مجرد توزيع لنفس الفرق مش عدّ تاني. التحميلات من جوه البرنامج (القابلة للاستكمال) بتتحسب مرة واحدة في الإجمالي (مش في المواقع)؛ واللي بينزّله مدير تنزيلات النظام بره عدّاد التطبيق فمش بتتحسب. الأرقام المكتوب جنبها «تقدير» متوسطات ثابتة ومابتتجمعش مع القياس.", 11f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        val top = siteBytes.entries.sortedByDescending { it.value }.take(5)
        if (top.isNotEmpty()) {
            col.addView(ui.text("🌐 أثقل المواقع في الجلسة (قياس تقريبي)", 13f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
            for ((k, v) in top) col.addView(ui.text("• $k — " + fmt(v), 12.5f, th.text))
        }
        col.addView(ui.text("🧊 تبويبات مجمّدة: " + frozenCount() + " · 📴 صفحات محفوظة: " + offIdx().length(), 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(6)) })
        var d: android.app.Dialog? = null
        col.addView(ui.button("⚙ إعدادات التوفير", true) { d?.dismiss(); showPanel() })
        col.addView(ui.button("🗑 مسح الإحصائيات") {
            android.app.AlertDialog.Builder(act).setTitle("مسح إحصائيات الداتا؟")
                .setMessage("هيتمسح عدّاد الجلسة واليوم والتاريخ والمواقع والتبويبات والتوفير المقدّر. الكاش والكوكيز والإعدادات مش هتتأثر.")
                .setPositiveButton("امسح") { _, _ -> resetStats(); d?.dismiss(); showStats() }.setNegativeButton("إلغاء", null).show()
        })
        d = ui.sheet(act, "📶 استهلاك الداتا", listOf<View>(ScrollView(act).apply { addView(col) })); d.show()
    }

    // ===== لوحة الإعدادات =====
    private fun watch(et: EditText, f: (String) -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { f(s?.toString()?.trim() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }
    private fun lab(s: String): View = ui.text(s, 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) }

    fun showPanel() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        var d: android.app.Dialog? = null
        col.addView(ui.text(netName() + " — " + levelName(), 15f, th.primary, true))
        col.addView(ui.text("🛡 مانع الإعلانات شغّال في كل الأوضاع — التوفير ماله أي علاقة بيه ومابيعدّلش فيه.", 11.5f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(4)) })
        col.addView(lab("وضع التوفير"))
        val mn = listOf("تلقائي", "دايمًا توفير", "مقفول"); val mv = listOf("auto", "always", "off")
        col.addView(ui.chips(mn, { mn[mv.indexOf(p.getString("sv_mode", "auto") ?: "auto").coerceAtLeast(0)] }) { pick -> val i = mn.indexOf(pick); if (i >= 0) { p.edit().putString("sv_mode", mv[i]).apply(); netChanged(true) } })
        col.addView(ui.text("تلقائي: داتا الموبايل = توفير أقصى · واي فاي محدود = ذكي · واي فاي مفتوح = متوازن (مفيش تغيير) · اتصال ضعيف = ذكي على الأقل.", 11f, th.muted))
        col.addView(ui.switchRow("🚨 وضع طوارئ الداتا (نص أولًا + خطوط وفيديو موقوفين)", p.getBoolean("sv_emerg", false)) { v -> p.edit().putBoolean("sv_emerg", v).apply(); netChanged(true) })
        col.addView(ui.switchRow("🅰 نص أولًا على داتا الموبايل (الصور بالضغط)", p.getBoolean("sv_text_cell", false)) { v -> p.edit().putBoolean("sv_text_cell", v).apply(); netChanged(true) })
        col.addView(ui.switchRow("🛜 اعتبر الواي فاي محدود دايمًا", p.getBoolean("sv_wifi_metered", false)) { v -> p.edit().putBoolean("sv_wifi_metered", v).apply(); netChanged(true) })

        val hs = hostOf(host.wv()?.url ?: host.tab()?.url)
        if (hs.isNotEmpty()) {
            col.addView(lab("بروفايل الموقع: $hs"))
            val sn = listOf("تلقائي", "نص أولًا", "كامل"); 
            col.addView(ui.chips(sn, { sn[siteMode(hs).coerceIn(0, 2)] }) { pick -> val i = sn.indexOf(pick); if (i >= 0) { setSiteMode(hs, i); host.tab()?.let { t -> imgOk.remove(t.id); budgetBlk.remove(t.id) }; reapplyAll(); host.reloadCur() } })
            col.addView(ui.button("🖼 حمّل صور الصفحة دي") { host.tab()?.let { t -> imgOk[t.id] = hs; budgetBlk.remove(t.id); host.wv()?.let { w -> applyTo(w, t, w.url) } }; d?.dismiss(); Notice.show(act, "🖼 الصور بتتحمّل", 1800L) })
        }
        col.addView(lab("ميزانية وزن الصفحة (MB) — بتتعدّى: على الداتا بنوقف الصور والفيديو، وعلى الواي فاي تنبيه بس"))
        val bn = listOf("1.5", "3", "6", "بدون"); val bv = listOf("1.5", "3", "6", "0")
        col.addView(ui.chips(bn, { bn[bv.indexOf(p.getString("sv_budget_mb", "3") ?: "3").coerceAtLeast(0)] }) { pick -> val i = bn.indexOf(pick); if (i >= 0) p.edit().putString("sv_budget_mb", bv[i]).apply() })
        col.addView(lab("جودة يوتيوب على داتا الموبايل (لو الصفحة بتدعم التحكم)"))
        val qn = listOf("240p", "360p", "480p"); val qv = listOf("240", "360", "480")
        col.addView(ui.chips(qn, { qn[qv.indexOf(p.getString("sv_q", "360") ?: "360").coerceAtLeast(0)] }) { pick -> val i = qn.indexOf(pick); if (i >= 0) { p.edit().putString("sv_q", qv[i]).apply(); reapplyAll() } })
        col.addView(ui.switchRow("🎬 جودة الفيديو التلقائية (حسب الشبكة والميزانية والتقطيع)", p.getBoolean("sv_vq_auto", true)) { v -> p.edit().putBoolean("sv_vq_auto", v).apply(); reapplyAll() })
        col.addView(lab("أقصى جودة فيديو — اختيارك بيغلب التلقائي (الطوارئ بس بتنزّلها)"))
        val xn = listOf("تلقائي", "240p", "360p", "480p", "720p", "1080p"); val xv = listOf("0", "240", "360", "480", "720", "1080")
        col.addView(ui.chips(xn, { xn[xv.indexOf(p.getString("sv_qmax", "0") ?: "0").coerceAtLeast(0)] }) { pick -> val i = xn.indexOf(pick); if (i >= 0) { p.edit().putString("sv_qmax", xv[i]).apply(); host.wv()?.let { w -> vqCap = -1; vqReeval(w) } } })
        col.addView(ui.text("التحكم بيشتغل في يوتيوب بس (من خلال مشغّله). أي موقع تاني مفيش طريقة موثوقة من WebView تجبر جودته — الكبسولة هتكتب «مش متاح هنا». الفيديو المحمي (DRM) مابنلمسوش.", 11f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(4)) })
        col.addView(lab("حد داتا الموبايل (MB — صفر = من غير حد)"))
        val limRow = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val e1 = ui.input("يوميًا", p.getString("sv_lim_day", "") ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val e2 = ui.input("شهريًا", p.getString("sv_lim_mon", "") ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        watch(e1) { p.edit().putString("sv_lim_day", it).apply() }; watch(e2) { p.edit().putString("sv_lim_mon", it).apply() }
        limRow.addView(e1, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }); limRow.addView(e2, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(limRow)
        col.addView(ui.switchRow("🚨 شغّل الطوارئ تلقائي لما أوصل الحد", p.getBoolean("sv_auto_emerg", true)) { v -> p.edit().putBoolean("sv_auto_emerg", v).apply() })
        col.addView(ui.text("⚙ DATA & PERFORMANCE", 13f, th.primary, true).apply { setPadding(0, ui.dp(12), 0, ui.dp(2)) })
        col.addView(lab("تحميل الصور — دايمًا: كاملة من غير توفير · محسّن: حسب مستوى التوفير · أبدًا: مقفولة (بتتحمّل بالضغط)"))
        val imN = listOf("دايمًا", "محسّن", "أبدًا"); val imV = listOf("always", "opt", "never")
        col.addView(ui.chips(imN, { imN[imV.indexOf(imgMode()).coerceAtLeast(0)] }) { pick -> val i = imN.indexOf(pick); if (i >= 0) { p.edit().putString("sv_img", imV[i]).apply(); host.tab()?.let { t -> imgOk.remove(t.id); budgetBlk.remove(t.id) }; reapplyAll() } })
        col.addView(ui.switchRow("🛡 حظر التراكرز والتحليلات (طرف ثالث — منفصل تمامًا عن مانع الإعلانات)", p.getBoolean("sv_trk", true)) { v -> p.edit().putBoolean("sv_trk", v).apply() })
        col.addView(ui.switchRow("▶ تشغيل الفيديو تلقائي (بيتطبّق على الصفحة الجاية)", autoplayAllowed(level)) { v -> p.edit().putBoolean("sv_autoplay", v).apply(); reapplyAll() })
        col.addView(lab("التحميل المسبق (preload/prefetch للصفحات والفيديو) — بيتطبّق على الصفحة الجاية"))
        val prN = listOf("مقفول", "واي فاي بس", "دايمًا"); val prV = listOf("off", "wifi", "always")
        col.addView(ui.chips(prN, { prN[prV.indexOf(p.getString("sv_pre", "wifi") ?: "wifi").coerceAtLeast(0)] }) { pick -> val i = prN.indexOf(pick); if (i >= 0) { p.edit().putString("sv_pre", prV[i]).apply(); reapplyAll() } })
        col.addView(lab("قيود الداتا في الخلفية — لما المتصفح يخرج من الشاشة الصفحات ماتطلبش شبكة (بترجع لما تفتحه)"))
        val bgN = listOf("مقفول", "على الداتا بس", "دايمًا"); val bgV = listOf("off", "cell", "always")
        col.addView(ui.chips(bgN, { bgN[bgV.indexOf(p.getString("sv_bg", "cell") ?: "cell").coerceAtLeast(0)] }) { pick -> val i = bgN.indexOf(pick); if (i >= 0) p.edit().putString("sv_bg", bgV[i]).apply() })
        col.addView(lab("حد الجلسة (خلوي) وحد الواي فاي اليومي (اختياري) — MB، صفر = من غير حد"))
        val limRow2 = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val e3 = ui.input("الجلسة", p.getString("sv_lim_ses", "") ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val e4 = ui.input("واي فاي يوميًا", p.getString("sv_lim_wifi", "") ?: "").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        watch(e3) { p.edit().putString("sv_lim_ses", it).apply() }; watch(e4) { p.edit().putString("sv_lim_wifi", it).apply() }
        limRow2.addView(e3, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) }); limRow2.addView(e4, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(limRow2)
        col.addView(ui.text("تنبيهات عند 50% (معلومة) · 80% (تحذير) · 95% (حرج) · 100% (توفير أقصى تلقائي لو السويتش اللي فوق شغّال). النت مابيتقطعش أبدًا.", 11f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(4)) })
        col.addView(ui.button("▶ كمّل تصفح بعد الحد (قفل الطوارئ لباقي اليوم)") { p.edit().putBoolean("sv_emerg", false).putString("sv_snooze", dayKey).apply(); netChanged(true); Notice.show(act, "تمام — كمّل عادي لباقي النهارده", 2500L) })
        col.addView(ui.switchRow("🧊 جمّد التبويبات الخلفية الخاملة", p.getBoolean("sv_freeze", true)) { v -> p.edit().putBoolean("sv_freeze", v).apply() })
        col.addView(ui.switchRow("⏸ تحميل الملفات قابل للإيقاف المؤقت والاستكمال", p.getBoolean("sv_dl_own", true)) { v -> p.edit().putBoolean("sv_dl_own", v).apply() })
        col.addView(ui.switchRow("💾 احفظ أوفلاين تلقائي للصفحات المفضّلة", p.getBoolean("sv_off_auto", true)) { v -> p.edit().putBoolean("sv_off_auto", v).apply() })
        col.addView(ui.switchRow("📶 الكبسولة العايمة", p.getBoolean("sv_cap", true)) { v -> p.edit().putBoolean("sv_cap", v).apply(); updateCap() })

        col.addView(ui.button("💾 احفظ الصفحة دي أوفلاين") { d?.dismiss(); val t = host.tab(); val w = host.wv(); if (t != null && w != null) saveArchive(t, w, true) })
        col.addView(ui.button("📴 الصفحات المحفوظة (" + offIdx().length() + ")") { d?.dismiss(); showOffline() })
        col.addView(ui.button("🧊 جمّد التبويبات الخلفية دلوقتي") { val n = freezeIdle(true); Notice.show(act, if (n > 0) "🧊 اتجمّد $n تبويب" else "مفيش تبويبات خلفية تتجمّد", 2000L) })
        col.addView(ui.button("🧪 فحص مانع الإعلانات") { d?.dismiss(); adTest() })
        d = ui.sheet(act, "📶 توفير الداتا والسرعة", listOf<View>(ScrollView(act).apply { addView(col) })); d.show()
    }

    private fun showOffline() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        var d: android.app.Dialog? = null
        val o = offIdx(); val rows = ArrayList<Pair<String, JSONObject>>(); val it = o.keys()
        while (it.hasNext()) { val k = it.next(); o.optJSONObject(k)?.let { rows.add(k to it) } }
        rows.sortByDescending { it.second.optLong("ts") }
        if (rows.isEmpty()) col.addView(ui.text("لسه مفيش صفحات محفوظة", 13f, th.muted))
        for ((k, e) in rows) {
            val f = File(offDir(), e.optString("f"))
            col.addView(ui.button("📄 " + e.optString("t").ifBlank { hostOf(e.optString("u")) }.take(40) + " · " + fmt(f.length())) { d?.dismiss(); openArchive(f) })
            col.addView(ui.button("🗑 مسح: " + hostOf(e.optString("u"))) { f.delete(); try { val oo = offIdx(); oo.remove(k); p.edit().putString("sv_off", oo.toString()).apply() } catch (_: Throwable) {}; d?.dismiss(); showOffline() })
        }
        d = ui.sheet(act, "📴 الصفحات المحفوظة", listOf<View>(ScrollView(act).apply { addView(col) })); d.show()
    }

    // ===== فحص انحدار مانع الإعلانات (بيستعمل AdBlock.blocked الحقيقي من غير ما يلمسه) =====
    private fun adTest() {
        val urls = listOf(
            "https://googleads.g.doubleclick.net/pagead/ads?client=ca-pub-1", "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://securepubads.g.doubleclick.net/gpt/pubads_impl.js", "https://www.googletagservices.com/tag/js/gpt.js",
            "https://static.adsafeprotected.com/iasPET.1.js", "https://ads.pubmatic.com/AdServer/js/showad.js",
            "https://a.exoclick.com/tag.js", "https://popads.net/pop.js", "https://connect.facebook.net/en_US/fbevents.js",
            "https://www.google-analytics.com/analytics.js", "https://cdn.taboola.com/libtrc/loader.js", "https://s.amazon-adsystem.com/aax2/apstag.js")
        val base = try { JSONObject(p.getString("sv_adbase", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        var blocked = 0; var regress = 0
        val nb = JSONObject()
        for (u in urls) {
            val b = try { AdBlock.blocked(u) } catch (_: Throwable) { false }
            if (b) blocked++
            nb.put(u, b)
            val was = base.optBoolean(u, false)
            val reg = was && !b; if (reg) regress++
            col.addView(ui.text((if (b) "✅ محظور" else "⚪ مش محظور") + (if (reg) "  ⚠ كان محظور قبل كده!" else "") + " — " + hostOf(u), 12.5f, if (reg) 0xFFD32F2F.toInt() else th.text))
        }
        col.addView(ui.text("المحظور: $blocked من " + urls.size + (if (base.length() == 0) " — ده أول فحص، اتسجّل كمرجع للمقارنة بعد كده" else if (regress == 0) " — مفيش أي تراجع عن آخر فحص ✓" else " — ⚠ تراجع في $regress رابط"), 13f, if (regress > 0) 0xFFD32F2F.toInt() else th.primary, true).apply { setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        col.addView(ui.text("الفحص بيسأل مانع الإعلانات الحقيقي (AdBlock.blocked) عن روابط معروفة. «مش محظور» لروابط التحليلات ممكن يكون طبيعي حسب قوايمك.", 11f, th.muted))
        // المرجع بيتحدّث بس لو مفيش تراجع (عشان التراجع ما يبقاش هو الطبيعي)
        if (regress == 0) { val m = JSONObject(base.toString()); val kk = nb.keys(); while (kk.hasNext()) { val k = kk.next(); if (nb.optBoolean(k) || !m.has(k)) m.put(k, nb.optBoolean(k) || m.optBoolean(k)) }; p.edit().putString("sv_adbase", m.toString()).apply() }
        ui.sheet(act, "🧪 فحص مانع الإعلانات", listOf<View>(ScrollView(act).apply { addView(col) })).show()
    }
}
