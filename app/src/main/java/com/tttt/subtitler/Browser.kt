package com.tttt.subtitler

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** تخزين المتصفح: السجل · المفضلة · التبويبات المحفوظة (ملفات JSON صغيرة في filesDir) */
object BrowserStore {
    class Entry(val url: String, var title: String, var time: Long)
    private var hist: ArrayList<Entry>? = null
    private var marks: ArrayList<Entry>? = null

    private fun load(c: Context, n: String): ArrayList<Entry> {
        val out = ArrayList<Entry>()
        try {
            val a = JSONArray(java.io.File(c.filesDir, n).readText())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(Entry(o.getString("u"), o.optString("t"), o.optLong("d"))) }
        } catch (_: Exception) {}
        return out
    }
    private fun save(c: Context, n: String, l: List<Entry>) {
        try {
            val a = JSONArray()
            for (e in l) a.put(JSONObject().put("u", e.url).put("t", e.title).put("d", e.time))
            java.io.File(c.filesDir, n).writeText(a.toString())
        } catch (e: Exception) { LogStore.err("BrowserStore", e) }
    }
    private fun skip(u: String) = u.isBlank() || u.startsWith("about:") || u.startsWith("data:") || u.startsWith("javascript:")

    fun history(c: Context): ArrayList<Entry> = hist ?: load(c, "br_history.json").also { hist = it }
    private fun addTo(c: Context, h: ArrayList<Entry>, file: String, url: String, title: String) {
        if (h.isNotEmpty() && h[0].url == url) { h[0].time = System.currentTimeMillis(); if (title.isNotBlank()) h[0].title = title }
        else { h.add(0, Entry(url, title, System.currentTimeMillis())); while (h.size > 1000) h.removeAt(h.size - 1) }
        save(c, file, h)
    }
    fun addHistory(c: Context, url: String, title: String) {
        if (skip(url)) return
        addTo(c, history(c), "br_history.json", url, title)
    }
    fun setTitle(c: Context, url: String, title: String) {
        if (skip(url) || title.isBlank()) return
        val e = history(c).firstOrNull { it.url == url }
        if (e != null) { if (e.title != title) { e.title = title; save(c, "br_history.json", history(c)) }; return }
        val pe = privacy(c).firstOrNull { it.url == url } ?: return
        if (pe.title != title) { pe.title = title; save(c, "br_privacy.json", privacy(c)) }
    }

    // (v358) سجل الخصوصية: اللينكات اللي اتصنّفت «للكبار» (محليًا أو بالذكاء الصناعي). بيتعرض بعد القفل بس.
    private var priv: ArrayList<Entry>? = null
    fun privacy(c: Context): ArrayList<Entry> = priv ?: load(c, "br_privacy.json").also { priv = it }
    fun removePrivacy(c: Context, e: Entry) { privacy(c).remove(e); save(c, "br_privacy.json", privacy(c)) }
    fun clearPrivacy(c: Context) { privacy(c).clear(); save(c, "br_privacy.json", privacy(c)) }
    private fun addPrivacy(c: Context, url: String, title: String) { addTo(c, privacy(c), "br_privacy.json", url, title) }
    /** نقل زيارة اتسجّلت عادي لسجل الخصوصية (بعد ما الذكاء الصناعي صنّفها) */
    fun moveToPrivacy(c: Context, url: String) {
        val h = history(c); val e = h.firstOrNull { it.url == url } ?: return
        h.remove(e); save(c, "br_history.json", h)
        addPrivacy(c, e.url, e.title)
    }
    /** كل زيارة صفحة بتعدّي من هنا: للكبار ← سجل الخصوصية، غير كده ← السجل العادي + تصنيف بالذكاء الصناعي في الخلفية */
    fun recordVisit(c: Context, url: String, title: String) {
        if (skip(url)) return
        if (!PrivacyFilter.on()) { addHistory(c, url, title); return }
        if (PrivacyFilter.localAdult(url, title)) { addPrivacy(c, url, title); return }
        addHistory(c, url, title)
        PrivacyFilter.aiCheck(url, title) { moveToPrivacy(c, url) }
    }
    fun removeHistory(c: Context, e: Entry) { history(c).remove(e); save(c, "br_history.json", history(c)) }
    /** (v412) اللي اتمسح من السجل (العادي أو الخصوصية) بسبب حجب موقع — بيتحفظ عشان زرار ↩ تراجع يرجّعه */
    class Purged(val priv: Boolean, val e: Entry)
    /** بيمسح من السجل وسجل الخصوصية كل لينك الدومين بتاعه فيه كلمة [key] */
    fun purgeByKey(c: Context, key: String): List<Purged> {
        val out = ArrayList<Purged>()
        val h = history(c); val hi = h.filter { SiteBlock.purgeMatch(key, it.url) }
        if (hi.isNotEmpty()) { h.removeAll(hi.toSet()); hi.forEach { out.add(Purged(false, it)) }; save(c, "br_history.json", h) }
        val p = privacy(c); val pi = p.filter { SiteBlock.purgeMatch(key, it.url) }
        if (pi.isNotEmpty()) { p.removeAll(pi.toSet()); pi.forEach { out.add(Purged(true, it)) }; save(c, "br_privacy.json", p) }
        return out
    }
    fun restorePurged(c: Context, l: List<Purged>) {
        if (l.isEmpty()) return
        val h = history(c); val p = privacy(c)
        for (x in l) (if (x.priv) p else h).add(x.e)
        h.sortByDescending { it.time }; p.sortByDescending { it.time }
        save(c, "br_history.json", h); save(c, "br_privacy.json", p)
    }
    fun clearHistory(c: Context) { history(c).clear(); save(c, "br_history.json", history(c)) }

    fun bookmarks(c: Context): ArrayList<Entry> = marks ?: load(c, "br_bookmarks.json").also { marks = it }
    fun isMarked(c: Context, url: String) = bookmarks(c).any { it.url == url }
    /** بيرجّع true لو اتضافت، false لو اتشالت */
    fun toggleMark(c: Context, url: String, title: String): Boolean {
        val b = bookmarks(c)
        val ex = b.firstOrNull { it.url == url }
        val added = if (ex != null) { b.remove(ex); false } else { b.add(0, Entry(url, title, System.currentTimeMillis())); true }
        save(c, "br_bookmarks.json", b); return added
    }
    fun removeMark(c: Context, e: Entry) { bookmarks(c).remove(e); save(c, "br_bookmarks.json", bookmarks(c)) }

    class SavedTab(val url: String, val title: String, val desktop: Boolean)
    fun saveTabs(c: Context, tabs: List<SavedTab>, cur: Int) {
        try {
            val a = JSONArray()
            for (t in tabs) a.put(JSONObject().put("u", t.url).put("t", t.title).put("k", t.desktop))
            java.io.File(c.filesDir, "br_tabs.json").writeText(JSONObject().put("cur", cur).put("tabs", a).toString())
        } catch (e: Exception) { LogStore.err("BrowserStore.tabs", e) }
    }
    fun loadTabs(c: Context): Pair<List<SavedTab>, Int> {
        val out = ArrayList<SavedTab>(); var cur = 0
        try {
            val o = JSONObject(java.io.File(c.filesDir, "br_tabs.json").readText())
            cur = o.optInt("cur", 0); val a = o.getJSONArray("tabs")
            for (i in 0 until a.length()) { val t = a.getJSONObject(i); out.add(SavedTab(t.getString("u"), t.optString("t"), t.optBoolean("k", false))) }
        } catch (_: Exception) {}
        return out to cur
    }
}

class BTab(val id: Long) {
    var wv: WebView? = null
    var url = ""
    var title = ""
    var desktop = false
    var progress = 100
    var opener = -1L
    var errUrl = ""
    var loaded = false
    var priv = false                 // (v124) تبويب متخفي: من غير سجل ولا حفظ
    var auto = false                 // (v433) متخفي اتفتح لوحده لموقع محجوب (🚨): الفيديو بيتسجل مخفي بالقفل مش متخفي بالكامل
    var thumb: android.graphics.Bitmap? = null
    @Volatile var gen = 0            // (v203) جيل الصفحة الحالية: كل صفحة جديدة بتزوّده فاللقطات القديمة بتتمسح
    var capUrl = ""
    var blockedUrl: String? = null      // (v412) موقع محجوب مستني قرارك (🚨 طوارئ / ▶ كمّل) — غطا أسود فوق الصفحة
    var allowKey: String? = null        // (v412) «كمّل» اتداس على الموقع ده في التبويب ده (بيتشال أول ما تسيب الموقع)
    var hold: PopupGuard.Hold? = null   // (v401) بوب-أب في الحجر: مش في قايمة التبويبات لحد ما نحكم عليه
}

/**
 * متصفح كامل جوه البرنامج (v91): تبويبات (بتتحفظ وترجع لما تفتح تاني) · سجل · مفضلة · إعدادات · بحث في الصفحة ·
 * نسخة الكمبيوتر · تحميل ملفات · قايمة ضغطة طويلة على اللينكات والصور · وبيلقط الفيديوهات زي الأول (قايمة «لقيت فيديو» + أزرار التحكم على الفيديو).
 */
@SuppressLint("SetJavaScriptEnabled")
class BrowserActivity : Activity() {
    // ===== (v531) ريموت الموبايل ← فيديو شغّال في المتصفح (نفس شاشة «مشغّل الفيديو» في الريموت) =====
    companion object {
        @Volatile private var curRef: java.lang.ref.WeakReference<BrowserActivity>? = null
        fun live(): BrowserActivity? = curRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        /** حالة أول/أكبر فيديو في الصفحة الحالية، أو null لو مفيش فيديو */
        fun remoteState(): org.json.JSONObject? { val o = live() ?: return null; return o.rState() }
        fun remoteAct(a: String, v: Long): Boolean { val o = live() ?: return false; return o.rAct(a, v) }
        private const val RC_FIND = "var vs=[].slice.call(document.querySelectorAll('video'));var v=vs.filter(function(x){return !x.paused})[0]||vs.sort(function(a,b){return b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight})[0];if(!v)return '';"
    }

    private fun rcJs(js: String, waitMs: Long = 900L): String? {
        val w = wvOrNull ?: return null
        val box = arrayOfNulls<String>(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        try {
            runOnUiThread { try { w.evaluateJavascript(js) { r -> box[0] = r; latch.countDown() } } catch (_: Throwable) { latch.countDown() } }
        } catch (_: Throwable) { return null }
        return if (latch.await(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) box[0] else null
    }

    private fun rState(): org.json.JSONObject? {
        val raw = rcJs("(function(){" + RC_FIND + "return JSON.stringify({pos:Math.round(v.currentTime*1000),dur:isFinite(v.duration)?Math.round(v.duration*1000):0,playing:!v.paused&&!v.ended,speed:v.playbackRate,title:document.title||''})})()") ?: return null
        return try {
            val s = org.json.JSONTokener(raw).nextValue() as? String ?: return null
            if (s.isEmpty()) return null
            val o = org.json.JSONObject(s)
            val sp = o.optDouble("speed", 1.0)
            org.json.JSONObject().put("kind", "browser").put("title", o.optString("title")).put("pos", o.optLong("pos")).put("dur", o.optLong("dur"))
                .put("playing", o.optBoolean("playing")).put("speed", (if (sp == Math.floor(sp)) sp.toInt().toString() else sp.toString()) + "x")
        } catch (_: Throwable) { null }
    }

    private fun rAct(a: String, v: Long): Boolean {
        val w = wvOrNull ?: return false
        fun runJs(js: String) { try { runOnUiThread { try { w.evaluateJavascript(js, null) } catch (_: Throwable) {} } } catch (_: Throwable) {} }
        when (a) {
            "toggle" -> runJs(TvPointer.jsToggle())
            "seek" -> runJs(TvPointer.jsSeek((v / 1000L).toInt()))
            "seekto" -> runJs("(function(){" + RC_FIND + "v.currentTime=" + (v / 1000.0) + "})()")
            "speed" -> runJs("(function(){" + RC_FIND + "var r=[0.5,0.75,1,1.25,1.5,2];var i=r.indexOf(v.playbackRate);v.playbackRate=r[i<0?3:(i+1)%r.length]})()")
            "next" -> runJs("(function(){var b=document.querySelector('.ytp-next-button,[aria-label=\"Next\"],[aria-label=\"التالي\"]');if(b)b.click()})()")
            "prev" -> runJs("(function(){var b=document.querySelector('.ytp-prev-button');if(b&&b.getAttribute('aria-disabled')!='true'){b.click();return}" + RC_FIND + "v.currentTime=0})()")
            else -> {}   // باقي أزرار المشغّل (ترجمة/خط/…) خاصة بمشغّل البرنامج — مفيش ليها معنى في المتصفح
        }
        return true
    }

    // (v507) ريزوليوشن ديناميك لوضع التلفزيون
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(TvScale.wrap(newBase)) }
    private var stripUi: StatusStrip? = null   // (v345)
    private var dockUi: MiniDock? = null   // (v344)
    override fun finish() { super.finish(); try { if (!NavAnim.take(this)) overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Browser:finish", e) } }

    lateinit var badge: Button
    // (v324) شريط «تحميل؟» اللي بيظهر تحت الصفحة (زي شريط «لقيت فيديو»): تحميل · إلغاء، بيختفي بعد 9 ثواني (كان 6) أو بلمسة برّاه = مش عايز أحمّل
    private lateinit var dlBar: LinearLayout
    private lateinit var dlTxt: IconTextView
    private lateinit var dlLine: View
    private var dlHide: Runnable? = null
    private var dlAct: (() -> Unit)? = null
    private val DL_PROMPT_MS = 9000L
    private var tasksFabUi: TasksFab? = null
    lateinit var hint: TextView
    lateinit var th: Theme
    lateinit var ui: Ui
    val h = Handler(Looper.getMainLooper())
    val items = ArrayList<Found>()
    private val keys = HashSet<String>()
    private var dlg: Dialog? = null
    private var listBox: LinearLayout? = null
    private var statusTv: TextView? = null
    private var autoOpen = false
    private var autoShown = false
    private var uaWeb = ""
    @Volatile private var dead = false
    private var adOn = true
    // (v469) D12 إحصائيات حظر الإعلانات لكل موقع + قائمة سماح (تعطيل الحظر لموقع بعينه) — بتتحفظ في نفس مخزن «p»
    private val adStats: MutableMap<String, Int> by lazy { val m = LinkedHashMap<String, Int>(); try { val o = org.json.JSONObject(prefs().getString("adstats", "{}") ?: "{}"); val ks = o.keys(); while (ks.hasNext()) { val h = ks.next(); m[h] = o.optInt(h) } } catch (_: Throwable) {}; m }
    private val adWhite: MutableSet<String> by lazy { try { (prefs().getString("adwhite", "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableSet() } catch (_: Throwable) { mutableSetOf() } }
    private var adStatDirty = 0
    private fun hostOf(u: String?): String = try { android.net.Uri.parse(u ?: "").host ?: "" } catch (_: Throwable) { "" }
    private fun adFor(u: String?): Boolean = adOn && hostOf(u) !in adWhite
    private fun countAd(u: String) { val h = hostOf(u); if (h.isEmpty()) return; adStats[h] = (adStats[h] ?: 0) + 1; if (++adStatDirty >= 5) saveAdStats() }
    private fun saveAdStats() { adStatDirty = 0; try { val o = org.json.JSONObject(); for ((h, n) in adStats) o.put(h, n); prefs().edit().putString("adstats", o.toString()).apply() } catch (_: Throwable) {} }
    private var blockedN = 0
    private var lastTouchAt = 0L                                  // (v401) آخر لمسة منك (بوب-أب بعد لمسة ≠ بوب-أب من نفسه)
    private var lastPopNotice = 0L
    private val holds = ArrayList<PopupGuard.Hold>()              // (v401) بوب-أبات في الحجر
    private val blockedPops = ArrayList<PopupGuard.Blocked>()     // (v401) آخر اللي اتمنع (للعرض و«افتحه»)
    private lateinit var ctl: LinearLayout
    private lateinit var tvB: IconTextView
    private lateinit var shield: TextView
    private lateinit var frame: FrameLayout
    private lateinit var customBox: FrameLayout
    private lateinit var content: LinearLayout
    private var customView: View? = null
    private var customCb: CustomViewCallback? = null
    private var oldOrient = -1
    private val pageTitles = HashMap<String, String>()

    // ===== (v480 م6) تلفزيون: مؤشر + تبديل الوضع =====
    private val tvOn: Boolean by lazy { TvFocus.on(this) }
    private var tvPtr: TvPointer? = null
    private var tvPointerMode = true            // true = مؤشر · false = تنقل بالتركيز (الأسهم للصفحة نفسها)
    private var tvBar: View? = null
    private var tvModeB: TextView? = null
    private var tvOkLong = false

    // ===== (v231) زرار الطوارئ + التراجع =====
    private lateinit var panicCover: View
    private lateinit var panicB: TextView
    private lateinit var undoB: TextView
    private lateinit var panicCol: LinearLayout
    private var panicUrl: String? = null        // الصفحة اللي اتمسحت (للتراجع)
    private var panicTabId = -1L
    private lateinit var blockCover: FrameLayout    // (v412) غطا أسود للمواقع المحجوبة
    private lateinit var blockTxt: TextView
    private var panicKey: String? = null            // (v412) الموقع اللي اتحجب في آخر طوارئ (للتراجع)
    private var panicPurged: List<BrowserStore.Purged> = emptyList()   // (v412) اللي اتمسح من السجل (للتراجع)
    private var lastPurgeKey: String? = null
    private var panicClearId = -1L              // التبويب اللي مستني جوجل يحمّل (نمسح تاريخ الرجوع ونشيل الغطا)
    private val GOOGLE = "https://www.google.com"

    // ===== التبويبات =====
    private val tabs = ArrayList<BTab>()
    private var curIdx = 0
    private var nextId = 1L
    private val tab: BTab get() = tabs[curIdx]
    private val wvOrNull: WebView? get() = tabs.getOrNull(curIdx)?.wv
    private lateinit var webHolder: FrameLayout
    private lateinit var addr: EditText
    private lateinit var backB: TextView
    private lateinit var fwdB: TextView
    private lateinit var reloadB: TextView
    private lateinit var tabsB: TextView
    private lateinit var progV: View
    private lateinit var progTrack: FrameLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findEt: EditText
    private lateinit var findCount: TextView
    private var tabsDlg: Dialog? = null
    private var tabsBox: LinearLayout? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val DESK_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun prefs() = getSharedPreferences("p", 0)
    private fun home() = prefs().getString("br_home", "")?.ifBlank { null } ?: "https://www.google.com"
    private fun searchUrl(q: String): String = when (prefs().getString("br_engine", "Google")) {
        "DuckDuckGo" -> "https://duckduckgo.com/?q="
        "Bing" -> "https://www.bing.com/search?q="
        else -> "https://www.google.com/search?q="
    } + Uri.encode(q)
    private fun uaOf(t: BTab) = if (t.desktop) DESK_UA else uaWeb

    private fun titleFor(f: Found): String = f.title.ifEmpty { Sniff.cleanTitle(pageTitles[f.ref] ?: if (f.ref == wvOrNull?.url) wvOrNull?.title else null) }

    private val JS = "(function(){var o=[];function a(x){try{if(x)o.push(new URL(x,location.href).href)}catch(e){}}" +
        "document.querySelectorAll('video,source').forEach(function(v){a(v.currentSrc);a(v.src)});" +
        "document.querySelectorAll('meta[property^=\"og:video\"],meta[name=\"twitter:player:stream\"]').forEach(function(m){a(m.content)});" +
        "document.querySelectorAll('a[href]').forEach(function(l){if(/\\.(mp4|m3u8|webm|mkv|mov|m4v)([?#]|\$)/i.test(l.href))a(l.href)});" +
        "return JSON.stringify(o)})()"

    // (v120) جودات أي موقع: عناصر <source> جوه نفس <video> (بـ label/size/res) = نفس الفيديو بجودات مختلفة
    private val JSQ = """(function(){var g=[];document.querySelectorAll('video').forEach(function(v){var m=[];v.querySelectorAll('source').forEach(function(s){var u=s.src;if(!u)return;var t=(s.getAttribute('label')||s.getAttribute('res')||s.getAttribute('size')||s.getAttribute('data-res')||s.getAttribute('title')||'');var mm=/([0-9]{3,4})/.exec(t);m.push({u:u,h:mm?parseInt(mm[1]):0})});if(m.length>1)g.push(m)});return JSON.stringify(g)})()"""
    private val webGroups = ArrayList<List<Pair<String, Int>>>()

    /** لو الفيديو اللي هيتفتح ليه جودات تانية في نفس الصفحة: قايمة الجودات (بحجمها الكلي) بنفس صيغة يوتيوب، وإلا null */
    private fun webQlist(f: Found): String? {
        val k = Sniff.key(f.url)
        val grp = webGroups.firstOrNull { g -> g.any { Sniff.key(it.first) == k } }
        val raw = ArrayList<Triple<String, Int, Long>>()   // url, height, size
        if (grp != null) {
            for (m in grp) { val it0 = vis().firstOrNull { x -> Sniff.key(x.url) == Sniff.key(m.first) }
                raw.add(Triple(m.first, if (m.second > 0) m.second else Sniff.heightOf(m.first), it0?.size ?: -1L)) }
        } else {
            val sk = Sniff.skeleton(f.url)
            for (x in vis()) if (x.ref == f.ref && x.kind != "HLS" && Sniff.skeleton(x.url) == sk) raw.add(Triple(x.url, Sniff.heightOf(x.url), x.size))
            if (raw.map { it.second }.filter { it > 0 }.toSet().size < 2) return null
        }
        val uniq = raw.distinctBy { Sniff.key(it.first) }.sortedByDescending { it.second }
        if (uniq.size < 2) return null
        val a = JSONArray()
        for ((i, t) in uniq.withIndex()) a.put(JSONObject().put("l", Sniff.qLabel(t.second, i + 1, t.third)).put("u", t.first).put("a", "").put("h", t.second))
        return a.toString()
    }

    // ===== بناء الشاشة =====
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.ensure(this)   // (v310) المتصفح ممكن يتفتح من أيقونة اللانشر قبل Main
        DeviceUi.applyOrientation(this)   // (v494) وضع التلفزيون/التابلت على موبايل = لاندسكيب تلقائي
        th = Themes.byId(try { prefs().getString("theme", "mx") } catch (_: Exception) { "mx" })
        ui = Ui(this, th)
        applyBars(th)
        TvFocus.attach(this, th.primary)   // (v477)
        adOn = try { prefs().getBoolean("adblock", true) } catch (_: Exception) { true }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); if (tvOn) setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(8)) else setPadding(0, ui.dp(28), 0, 0) }
        // (v517) تلفزيون: أزرار الشريط أكبر (52dp) عشان التركيز بالريموت يبان
        fun tbtn(t: String, size: Float = 20f, f: (View) -> Unit): TextView = IconTextView(this).apply {
            text = t; textSize = if (tvOn) size * 1.25f else size; gravity = Gravity.CENTER; setTextColor(th.text)
            layoutParams = LinearLayout.LayoutParams(ui.dp(if (tvOn) 54 else 38), ui.dp(if (tvOn) 52 else 40)); setOnClickListener { f(it) }
        }
        backB = tbtn("‹", 26f) { wvOrNull?.let { if (it.canGoBack()) it.goBack() } }
        fwdB = tbtn("›", 26f) { wvOrNull?.let { if (it.canGoForward()) it.goForward() } }
        addr = ui.input("رابط الموقع / كلمة بحث", "").apply {
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(if (tvOn) 52 else 40), 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
            layoutDirection = View.LAYOUT_DIRECTION_LTR; setSelectAllOnFocus(true); textSize = if (tvOn) 17f else 13f
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        reloadB = tbtn("⟳", 20f) { val w = wvOrNull ?: return@tbtn; if (tab.progress < 100) w.stopLoading() else w.reload() }
        tabsB = IconTextView(this).apply {
            text = "1"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(th.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(0x00000000, th.text, 6); layoutParams = LinearLayout.LayoutParams(ui.dp(if (tvOn) 38 else 28), ui.dp(if (tvOn) 38 else 28)).apply { setMargins(ui.dp(5), ui.dp(if (tvOn) 7 else 6), ui.dp(5), ui.dp(6)) }
            setOnClickListener { showTabs() }
        }
        val rotB = tbtn("🔄", 18f) { toggleRotate() }   // (v494) تدوير الشاشة لاندسكيب ⇄ بورتريه
        val menuB = tbtn("⋮", 22f) { showMenu(it) }
        val bar = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(2), 0, ui.dp(2), 0)
            addView(backB); addView(fwdB); addView(addr); addView(reloadB); if (!tvOn) addView(rotB); addView(tabsB); addView(menuB)
        }
        if (tvOn) {   // (v480 م6) زرار تبديل الوضع: 🖱 مؤشر / 🎯 تنقل بالتركيز
            tvPointerMode = Cfg.str("tv_br_mode", "ptr") != "focus"
            val mb = tbtn(if (tvPointerMode) "🖱" else "🎯", 18f) { tvToggleMode() }
            tvModeB = mb
            bar.addView(mb, 4)
            // (v517) 🏠 رجوع لشاشة التلفزيون (بدل الشريط السفلي اللي اتشال) — أول زرار في الشريط
            bar.addView(tbtn("🏠", 18f) { persistTabs(); if (!TvFocus.goHome(this)) finish() }, 0)
            tvBar = bar
        }
        progV = View(this).apply { setBackgroundColor(th.primary) }
        progTrack = FrameLayout(this).apply { visibility = View.INVISIBLE; addView(progV, FrameLayout.LayoutParams(0, -1)) }

        findEt = ui.input("دوّر في الصفحة", "").apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(40), 1f); textSize = 13f }
        findCount = ui.text("", 12f, th.muted).apply { setPadding(ui.dp(6), 0, ui.dp(6), 0) }
        findBar = LinearLayout(this).apply {
            visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(2))
            addView(findEt); addView(findCount)
            addView(tbtn("▲", 14f) { wvOrNull?.findNext(false) }); addView(tbtn("▼", 14f) { wvOrNull?.findNext(true) }); addView(tbtn("✕", 16f) { closeFind() })
        }
        findEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { val q = s?.toString() ?: ""; if (q.isEmpty()) { wvOrNull?.clearMatches(); findCount.text = "" } else wvOrNull?.findAllAsync(q) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b2: Int, c: Int) {}
        })

        hint = ui.text("شغّل الفيديو في الصفحة وهلقطه تلقائي · أو الصق لينك يوتيوب فوق", 12f, th.muted).apply { setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(4)) }
        webHolder = FrameLayout(this)
        badge = IconButton(this).apply {
            isAllCaps = false; textSize = 15f; visibility = View.GONE
            setTextColor(if (th.isLight) Color.WHITE else Color.BLACK); background = ui.box(th.primary, th.primary, 8)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(6)) }
            setOnClickListener { showList() }
        }
        dlTxt = IconTextView(this).apply { textSize = 13f; setTextColor(th.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER }
        dlLine = View(this).apply { setBackgroundColor(th.primary) }
        dlBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE
            background = ui.box(th.card, th.border, 12)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            addView(dlTxt, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@BrowserActivity).apply {
                layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(ui.button("⬇ تحميل", true) { val a = dlAct; hideDl(); a?.invoke() }, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { marginEnd = ui.dp(6) })
                addView(ui.button("✕ إلغاء") { hideDl() }, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
            }, LinearLayout.LayoutParams(-1, -2))
            addView(dlLine, LinearLayout.LayoutParams(-1, ui.dp(2)))
        }
        // (v231) غلاف منطقة الصفحة: الصفحة + غطا أبيض وقت الطوارئ + زرارين عايمين تحت ‹ › (🚨 طوارئ · ↩ تراجع)
        panicCover = View(this).apply { setBackgroundColor(Color.WHITE); visibility = View.GONE; isClickable = true }
        panicB = IconTextView(this).apply {
            text = "🚨"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xCCD32F2F.toInt(), 0x66FFFFFF, 22); elevation = ui.dp(4).toFloat()
            setOnClickListener { panic() }
        }
        undoB = IconTextView(this).apply {
            text = "↩"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xCC1E2A3A.toInt(), 0x66FFFFFF, 22); elevation = ui.dp(4).toFloat(); visibility = View.GONE
            setOnClickListener { undoPanic() }
        }
        tvB = IconTextView(this).apply {   // (v533) «إرسال للتلفزيون»: فوق زرار الإعلانات والطوارئ — يبعت الصفحة الحالية ويفتحها على التلفزيون إجباري
            text = "📺"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xCC1E6FE0.toInt(), 0x66FFFFFF, 22); elevation = ui.dp(4).toFloat()
            setOnClickListener { sendPageToTv() }
        }
        ctl = buildControls()   // (v248) زرار مانع الإعلانات 🛡 بقى فوق زرار الطوارئ على طول
        panicCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL
            addView(tvB, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { bottomMargin = ui.dp(6) })
            addView(ctl, LinearLayout.LayoutParams(-2, -2))
            addView(panicB, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
            addView(undoB, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { topMargin = ui.dp(6) })
        }
        blockCover = buildBlockCover()
        val webWrap = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(webHolder, FrameLayout.LayoutParams(-1, -1))
            addView(panicCover, FrameLayout.LayoutParams(-1, -1))
            addView(panicCol, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(0, 0, ui.dp(4), ui.dp(8)) })
            addView(blockCover, FrameLayout.LayoutParams(-1, -1))   // (v412) فوق كل حاجة
        }
        if (tvOn) {   // (v480 م6) المؤشر فوق الصفحة والغطا والأزرار العايمة
            val ptr = TvPointer(this, webWrap, th.primary)
            ptr.onTap = { lastTouchAt = android.os.SystemClock.uptimeMillis() }
            ptr.onTopEdge = { tvToToolbar() }
            ptr.onBottomEdge = { tvToBottomBar() }
            ptr.enabled = tvPointerMode
            webWrap.addView(ptr.view, FrameLayout.LayoutParams(-1, -1))
            tvPtr = ptr
        }
        // (v336) زرار المهام العايم اتشال — المهام في الشريط السفلي
        //   // (v324) زرار المهام العايم (دايرة حمراء بالعدد) فوق الصفحة
        root.addView(bar); root.addView(progTrack, LinearLayout.LayoutParams(-1, ui.dp(3))); root.addView(findBar); root.addView(hint)
        root.addView(webWrap, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(badge)
        root.addView(dlBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(6)) })   // (v324)
        // (v117) شريط البوابات: الفيديوهات · الموسيقى · المتصفح (إحنا فيه دلوقتي) · الملفات · (v324) يوتيوب
        val gates = BottomNav(this, ui, th, 2) { i -> if (i != 2) NavAnim.set(2, i); if (i == 0) goMain("videos") else if (i == 1) goMain("music") else if (i == 4) goMain("youtube") else if (i == 5) goMain("tasks") else if (i == 7) goMain("settings") else if (i == 3) { startActivity(Intent(this, FileManagerActivity::class.java)); NavAnim.peek(this); finish() } }   // (v310) الملفات · (v364) الذكاء
        if (!tvOn) root.addView(gates.view, LinearLayout.LayoutParams(-1, -2))   // (v517) تلفزيون: الشريط السفلي اتشال — 🏠 في شريط العنوان
        content = root
        frame = FrameLayout(this); frame.setBackgroundColor(th.bg)
        customBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(customBox, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        dockUi = MiniDock(this, frame, ui, th, { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fullmusic", true)) })   // (v344)
        stripUi = StatusStrip(this, frame, ui, th) { goMain("tasks") }   // (v345)
        tasksFabUi = TasksFab(this, frame, ui) { goMain("tasks") }   // (v420)

        // التبويبات: رجّع المحفوظة (لو مفعّل) وبعدها لو جاي بلينك افتحه في تبويب جديد
        val start = intent?.getStringExtra("start")?.trim() ?: ""
        // (v203) وضع «إصلاح اللينك»: أول فيديو يتلقط بيتحط مكان لينك الفيديو القديم بنفس هويته
        repairId = intent?.getStringExtra("repair_id")?.takeIf { it.isNotEmpty() }
        repairUrl = intent?.getStringExtra("repair_url") ?: ""; repairTitle = intent?.getStringExtra("repair_title") ?: ""
        if (repairId != null) {
            hint.text = "🔧 بتحدّث لينك «" + repairTitle.ifEmpty { "الفيديو" } + "» — دوس تشغيل في الصفحة وأنا ألقط اللينك الجديد وأرجّعك للمشغّل بنفس الترجمة"
            h.postDelayed({ if (!dead) Notice.show(this, "🔧 شغّل الفيديو في الصفحة وأنا هلقطه", 4000L) }, 800L)
        }
        if (tvOn && repairId == null) { hint.text = "🖱 الأسهم = مؤشر · OK = دوسة · Menu = الشريط · OK طويلة = تبديل الوضع · شغّل الفيديو وأنا هلقطه"; h.postDelayed({ if (!dead && repairId == null) hint.visibility = View.GONE }, 12000L) }   // (v517) التلميحة بتختفي بعد 12 ثانية
        val keep = prefs().getBoolean("br_save_tabs", true)
        val saved = if (keep) BrowserStore.loadTabs(this) else (emptyList<BrowserStore.SavedTab>() to 0)
        for (s in saved.first) tabs.add(BTab(nextId++).also { it.url = s.url; it.title = s.title; it.desktop = s.desktop })
        if (tabs.isNotEmpty()) curIdx = saved.second.coerceIn(0, tabs.size - 1)
        if (start.isNotEmpty()) { autoOpen = intent?.getBooleanExtra("noauto", false) != true; newTab(start, true) }
        else if (tabs.isEmpty()) newTab(home(), true)
        else select(curIdx)
        scanLoop()
    }

    /** (v117) رجوع للشاشة الرئيسية على بوابة معيّنة (videos / yt) */
    private fun goMain(tab: String) {
        persistTabs()
        if (DeviceUi.isTv(this) && tab == "videos") { try { startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tv_show", "videos")); finish(); return } catch (_: Throwable) {} }   // (v530)   // (v482) تلفزيون: الرئيسية = شاشة التلفزيون
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tab", tab))
        NavAnim.peek(this)
        finish()
    }
    /** (v117) أي صفحة فيديو يوتيوب بتتفتح في المتصفح بتتسجل في سجل بوابة يوتيوب (المعرّف + الاسم) */
    private var lastYtId = ""
    private val ytIds = HashMap<String, String>()
    private fun noteYt(url: String?, title: String?) { /* (v124) سجل يوتيوب اتشال */ }

    // ===== إنشاء WebView لتبويب =====
    private fun makeWebView(t: BTab): WebView {
        val w = WebView(this); WebMute.register(w)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = popMode() != PopupGuard.OFF   // (v401) الذكي بيشوف كل بوب-أب ويحكم عليه بنفسه
        }
        if (uaWeb.isEmpty()) uaWeb = w.settings.userAgentString ?: ""
        if (t.desktop) w.settings.userAgentString = DESK_UA
        if (t.priv) { w.settings.cacheMode = WebSettings.LOAD_NO_CACHE; @Suppress("DEPRECATION") w.settings.saveFormData = false }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(Color.WHITE)
        w.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(v: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                // (v401) المانع الذكي: البوب-أب بيتفتح في الحجر وبنحكم عليه (إعلان = يتقفل · صفحة مشاهدة = تبويب)
                if (t.hold != null) { popBlocked("", listOf("بوب-أب جوه بوب-أب"), true); return true }
                if (popMode() != PopupGuard.OFF) return popupWindow(t, v, isUserGesture, resultMsg)
                // نافذة جديدة بلمسة منك = تبويب جديد؛ من غير لمسة (بوب-أب) بتتمنع لو مانع الإعلانات شغال
                if (!isUserGesture && adOn) return true
                val nt = newTab(null, true, t.id, t.priv)
                val tr = resultMsg?.obj as? WebView.WebViewTransport
                if (tr == null || nt.wv == null) return false
                tr.webView = nt.wv; resultMsg?.sendToTarget(); return true
            }
            override fun onProgressChanged(v: WebView?, p: Int) {
                t.progress = p; if (t === tabs.getOrNull(curIdx)) updateProgress()
                if (p >= 70 && t.id == panicClearId && (v?.url ?: "").contains("google.")) endPanicCover()   // (v231) جوجل ثبت: شيل الغطا
            }
            override fun onReceivedTitle(v: WebView?, title: String?) {
                if (title.isNullOrBlank()) return
                t.title = title; v?.url?.let { if (!t.priv && t.hold == null) BrowserStore.setTitle(this@BrowserActivity, it, title); pageTitles[it] = title }
                noteYt(v?.url, title)
                if (tabsDlg?.isShowing == true) renderTabs()
            }
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (view == null) return
                if (t.hold != null) { cb?.onCustomViewHidden(); return }   // (v401) بوب-أب في الحجر مايخطفش الشاشة
                if (customView != null) { cb?.onCustomViewHidden(); return }
                customView = view; customCb = cb
                customBox.addView(view, FrameLayout.LayoutParams(-1, -1)); customBox.visibility = View.VISIBLE; content.visibility = View.GONE
                oldOrient = requestedOrientation; requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
            override fun onHideCustomView() { leaveCustom() }
            override fun onShowFileChooser(v: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?): Boolean {
                fileCb?.onReceiveValue(null); fileCb = cb
                return try { startActivityForResult(p?.createIntent(), 4711); true } catch (e: Throwable) { fileCb?.onReceiveValue(null); fileCb = null; false }
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                val g0 = t.gen
                Sniff.accept(u)?.let { a ->
                    val hd = t.hold
                    if (hd != null) {   // (v401) فيديو في بوب-أب = دليل إنه صفحة مشاهدة
                        hd.media = true
                        if (hd.sniffed.size < 6) { hd.sniffed.add(a) }
                    } else {
                        h.post { if (!dead) addFound(Found(a, Sniff.kindOf(a), "", v.url ?: t.url, uaOf(t)), t, g0) }
                    }
                    Unit
                }
                if (adFor(u) && AdBlock.blocked(u)) { blockedN++; countAd(u); h.post { if (!dead) shield.text = "🛡 $blockedN" }; return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) }
                return null
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                val hd = t.hold
                if (hd != null) {   // (v401) بوب-أب في الحجر: كل خطوة تحويل بتتسجّل
                    if (!u.startsWith("http")) {
                        if (!u.startsWith("about:") && !u.startsWith("data:") && !u.startsWith("blob:")) h.post { popKill(hd, u, listOf("بيحاول يفتح تطبيق/سوق")) }
                        return true
                    }
                    hd.addUrl(u)
                    if (adFor(u) && AdBlock.blocked(u)) { h.post { popKill(hd, u, listOf("شبكة إعلانات معروفة")) }; return true }
                    return false
                }
                if (r.isForMainFrame && u.startsWith("http") && gateBlocked(t, u)) return true   // (v412) موقع محجوب
                if (r.isForMainFrame && !r.hasGesture() && popMode() != PopupGuard.OFF && android.os.SystemClock.uptimeMillis() - lastTouchAt > 1500L) {
                    // (v401) تحويل تلقائي للصفحة نفسها من غير لمسة لينك شكله إعلان
                    val why = ArrayList<String>()
                    if (u.startsWith("http") && PopupGuard.urlScore(u, why) >= 5 && !PopupGuard.sameReg(PopupGuard.hostOf(u), PopupGuard.hostOf(v.url ?: ""))) { popBlocked(u, why + "تحويل تلقائي من غير لمسة", false); return true }
                }
                if (!u.startsWith("http")) {
                    if (u.startsWith("tel:") || u.startsWith("mailto:") || u.startsWith("sms:") || u.startsWith("geo:")) {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Throwable) {}
                        return true
                    }
                    return adOn          // intent:// / market:// وأمثالها: بتتمنع (الإعلانات بتفتح تطبيقات)
                }
                if (adFor(u) && AdBlock.blocked(u)) { blockedN++; countAd(u); shield.text = "🛡 $blockedN"; return true }
                return false
            }
            override fun onPageStarted(v: WebView, url: String?, f: android.graphics.Bitmap?) {
                val hd = t.hold
                if (hd != null) {   // (v401) بوب-أب في الحجر
                    if (url != null && !url.startsWith("data:")) { t.url = url; hd.addUrl(url); h.post { popDecide(hd, false) } }
                    v.evaluateJavascript(adJs(), null)
                    return
                }
                if (url != null && gateBlocked(t, url)) return   // (v412) شبكة أمان: تحويل/رجوع/JS وصل لموقع محجوب
                if (url != null && !url.startsWith("data:")) { t.url = url; newPage(t, url) }
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav() }
                if (adFor(url)) v.evaluateJavascript(adJs(), null)
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                val hd = t.hold
                if (hd != null) { if (url != null && !url.startsWith("data:")) { t.url = url; hd.addUrl(url) }; return }
                if (t.blockedUrl != null && url != null && SiteBlock.keyOf(url) == SiteBlock.keyOf(t.blockedUrl)) return   // (v412)
                if (url != null && !url.startsWith("data:")) { t.url = url; newPage(t, url); noteYt(url, null) }
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav() }
            }
            override fun onPageFinished(v: WebView, url: String?) {
                val hd = t.hold
                if (hd != null) {   // (v401) بوب-أب في الحجر: نسيبه لحظة يخلّص تحويلاته وبعدين نفحصه
                    if (url != null && !url.startsWith("data:")) { t.url = url; hd.addUrl(url) }
                    v.evaluateJavascript(adJs(), null)
                    h.postDelayed({ popProbe(hd) }, 700L)
                    return
                }
                if (t.blockedUrl != null && url != null && SiteBlock.keyOf(url) == SiteBlock.keyOf(t.blockedUrl)) return   // (v412) موقع محجوب: مفيش تسجيل ولا اصطياد
                if (url != null && !url.startsWith("data:")) {
                    t.url = url
                    val ti = v.title?.takeIf { it.isNotBlank() } ?: ""
                    if (ti.isNotEmpty()) { t.title = ti; pageTitles[url] = ti }
                    if (!t.priv && !(t.id == panicClearId && SiteBlock.keyOf(url) == lastPurgeKey)) BrowserStore.recordVisit(this@BrowserActivity, url, ti)   // (v412) صفحة جوجل بعد الطوارئ ماتتسجّلش لو هي نفسها اللي اتمسحت
                    noteYt(url, ti)
                }
                if (t.id == panicClearId && (url ?: "").contains("google.")) { try { v.clearHistory() } catch (_: Throwable) {}; endPanicCover(); updateNav() }   // (v231) الرجوع مايرجّعش الصفحة اللي اتمسحت
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav(); updateStar(); scanOnce() }
                if (adFor(url)) v.evaluateJavascript(adJs(), null)
                saveAdStats()
                persistTabs()
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (!r.isForMainFrame) return
                if (t.hold != null) return   // (v401) بوب-أب في الحجر: المهلة هتحكم
                val u = r.url.toString()
                if (u.startsWith("data:") || t.errUrl == u) return
                t.errUrl = u
                v.loadDataWithBaseURL(u, errHtml(u, e.description?.toString() ?: ""), "text/html", "utf-8", u)
                if (t.id == panicClearId) endPanicCover()   // (v231) صفحة الخطأ حلّت مكان القديمة
            }
        }
        w.setDownloadListener { url, ua, cd, mime, _ ->
            // (v203) وضع إصلاح اللينك: زرار «تحميل» في الصفحة حتى لو اللينك من غير .mp4 = ده لينك الفيديو
            if (repairId != null && url.startsWith("http", true) && (mime?.startsWith("video") == true || mime == "application/octet-stream" || Sniff.accept(url) != null)) {
                addFound(Found(url, Sniff.kindOf(url), "", t.url, ua ?: uaOf(t)), t)
                return@setDownloadListener
            }
            Sniff.accept(url)?.let { a -> addFound(Found(a, Sniff.kindOf(a), "", t.url, ua ?: uaOf(t)), t) }
            val name = URLUtil.guessFileName(url, cd, mime)
            showDl(name) { startDownload(url, ua, name, mime) }   // (v324) شريط تحت الصفحة بدل إشعار فوق
        }
        w.setOnLongClickListener { v -> longPress(v as WebView) }
        return w
    }

    private fun errHtml(url: String, msg: String): String =
        "<html dir='rtl'><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
        "<body style='font-family:sans-serif;background:#16161a;color:#eee;text-align:center;padding:48px 18px'>" +
        "<h2>⚠ الصفحة ما فتحتش</h2><p style='color:#bbb'>" + TextUtils.htmlEncode(msg) + "</p>" +
        "<p style='color:#888;word-break:break-all;direction:ltr;font-size:12px'>" + TextUtils.htmlEncode(url) + "</p>" +
        "<button style='padding:12px 28px;font-size:16px;border:0;border-radius:10px;background:#4f8cff;color:#fff' onclick='location.replace(" + JSONObject.quote(url) + ")'>جرّب تاني</button></body></html>"

    // ===== إدارة التبويبات =====
    /** url = null: تبويب فاضي بيستنى WebViewTransport (نافذة جديدة من صفحة) */
    private fun newTab(url: String?, switchTo: Boolean, opener: Long = -1L, priv: Boolean = false): BTab {
        val t = BTab(nextId++); t.opener = opener; t.priv = priv
        t.desktop = prefs().getBoolean("br_desktop_default", false)
        if (url == null) { t.wv = makeWebView(t); t.loaded = true } else t.url = url
        tabs.add(t)
        if (switchTo) select(tabs.size - 1)
        refreshTabsCount(); persistTabs()
        return t
    }
    private fun select(i: Int) {
        if (i !in tabs.indices) return
        tabs.getOrNull(curIdx)?.let { snapTab(it) }
        wvOrNull?.let { try { it.onPause() } catch (_: Throwable) {} }
        webHolder.removeAllViews()
        val prevIdx = curIdx
        curIdx = i
        if (prevIdx != i && tabs.size > 1) Motion.slideIn(webHolder, if (i > prevIdx) -1 else 1, 230L)   // (v313) تبديل التبويب بانزلاق
        val t = tabs[i]
        webGroups.clear(); openKey = null; refreshBadge()   // (v203) الاصطياد بتاع التبويب ده بس
        val first = t.wv == null
        if (first) t.wv = makeWebView(t)
        val w = t.wv!!
        (w.parent as? ViewGroup)?.removeView(w)
        webHolder.addView(w, FrameLayout.LayoutParams(-1, -1))
        try { w.onResume() } catch (_: Throwable) {}
        if (!t.loaded) { t.loaded = true; if (t.url.isNotBlank()) loadIn(w, t, t.url) }
        showUrl(t.url, true); updateNav(); updateProgress(); updateStar(); refreshTabsCount(); closeFind()
        addr.hint = if (t.priv) "🕶 متخفي — رابط الموقع / كلمة بحث" else "رابط الموقع / كلمة بحث"
        tabsB.setTextColor(if (t.priv) 0xFFE53935.toInt() else th.text)
        endPanicCover(); refreshBlockCover()
        scanOnce(); persistTabs()
        if (tvOn) h.post { if (!dead) tvToPage() }
    }
    private fun closeTab(i: Int) {
        if (i !in tabs.indices) return
        val t = tabs[i]
        val wasCur = i == curIdx
        val openerIdx = tabs.indexOfFirst { it.id == t.opener }
        try { t.wv?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); if (t.priv) { it.clearCache(true); it.clearHistory(); it.clearFormData() }; it.destroy() } } catch (_: Throwable) {}
        tabs.removeAt(i)
        if (tabs.isEmpty()) { newTab(home(), true); return }
        if (wasCur) select(if (openerIdx in tabs.indices) openerIdx else (i - 1).coerceAtLeast(0).coerceAtMost(tabs.size - 1))
        else { if (i < curIdx) curIdx--; refreshTabsCount(); persistTabs() }
    }
    private fun refreshTabsCount() { if (::tabsB.isInitialized) tabsB.text = tabs.size.toString() }
    private fun persistTabs() {
        if (!prefs().getBoolean("br_save_tabs", true)) return
        // (v124) التبويبات المتخفية عمرها ما بتتحفظ
        val keep = tabs.filter { !it.priv }
        val ci = keep.indexOf(tabs.getOrNull(curIdx)).let { if (it < 0) 0 else it }
        BrowserStore.saveTabs(this, keep.map { BrowserStore.SavedTab(if (it.id == panicClearId) GOOGLE else (it.wv?.url?.takeIf { u -> !u.startsWith("data:") } ?: it.url), it.title, it.desktop) }, ci)
    }

    // ===== تحميل لينك / بحث =====
    private fun load(raw: String) {
        var u = raw.trim(); if (u.isEmpty()) return
        if (!u.startsWith("http") && !u.startsWith("about:")) u = if (u.contains(".") && !u.contains(" ")) "https://$u" else searchUrl(u)
        addr.clearFocus(); endPanicCover()
        if (Sniff.isDirect(u)) { Sniff.accept(u)?.let { addFound(Found(it, Sniff.kindOf(it), "", tab.url, uaOf(tab))) }; return }   // لينك فيديو مباشر: مفيش صفحة تتحمّل
        YtExtract.videoId(u)?.let { ytFetch(it) }
        val t = tab; t.loaded = true; t.errUrl = ""
        loadIn(t.wv ?: return, t, u)
    }
    private fun loadIn(w: WebView, t: BTab, u: String) {
        t.url = u; t.errUrl = ""
        if (gateBlocked(t, u)) return   // (v412) موقع محجوب: غطا أسود وبلاش تحميل
        t.blockedUrl = null; refreshBlockCover()
        w.loadUrl(u)
    }

    // ===== (v412) المواقع المحجوبة بزرار 🚨: غطا أسود قبل ما الصفحة تتحمّل · 🚨 طوارئ ← جوجل · ▶ كمّل ← افتح الموقع =====
    /** true = الموقع محجوب ومش مسموح في التبويب ده دلوقتي: الصفحة اتوقّفت والغطا ظهر */
    private fun gateBlocked(t: BTab, u: String?): Boolean {
        if (u == null || t.hold != null) return false
        val k = SiteBlock.keyOf(u) ?: return false
        if (!SiteBlock.has(this, k)) return false
        if (t.priv) return false   // (v433) متخفي أصلًا: كمّل عادي
        // (v433) الموقع المحجوب بيتفتح لوحده في تبويب متخفي (مفيش سجل) + إشعار من غير اسم الموقع
        try { t.wv?.stopLoading() } catch (_: Throwable) {}
        val oldId = t.id
        val canBack = try { t.wv?.canGoBack() == true } catch (_: Throwable) { false }
        if (canBack) { t.url = try { t.wv?.url ?: "" } catch (_: Throwable) { "" } }
        val nt = newTab(u, true, -1L, true); nt.auto = true
        Notice.show(this, "🕶 اتفتح متخفي", 2200L)
        if (!canBack) h.post { val i = tabs.indexOfFirst { it.id == oldId }; if (i >= 0 && tabs.size > 1) closeTab(i) }
        return true
    }
    private fun refreshBlockCover() {
        if (!::blockCover.isInitialized) return
        val u = tabs.getOrNull(curIdx)?.blockedUrl
        if (u != null) { blockTxt.text = PopupGuard.hostOf(u); blockCover.visibility = View.VISIBLE } else blockCover.visibility = View.GONE
    }
    private fun buildBlockCover(): FrameLayout {
        fun btn(label: String, fill: Int, f: () -> Unit) = IconTextView(this).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(fill, 0x66FFFFFF, 14); setOnClickListener { f() }
        }
        blockTxt = ui.text("", 14f, 0xFFBBBBBB.toInt()).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(24), 0, ui.dp(24), 0)
            addView(ui.text("🚫", 44f, Color.WHITE).apply { gravity = Gravity.CENTER })
            addView(ui.text("موقع محجوب", 20f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(4)) })
            addView(blockTxt, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(22) })
            addView(LinearLayout(this@BrowserActivity).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(btn("🚨 طوارئ", 0xFFD32F2F.toInt()) { blockEmergency() }, LinearLayout.LayoutParams(0, ui.dp(54), 1f).apply { marginEnd = ui.dp(8) })
                addView(btn("▶ كمّل", 0xFF2E7D32.toInt()) { blockContinue() }, LinearLayout.LayoutParams(0, ui.dp(54), 1f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK); visibility = View.GONE; isClickable = true; isFocusable = true
            addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
    }
    private fun blockContinue() {
        val t = tabs.getOrNull(curIdx) ?: return
        val u = t.blockedUrl ?: return
        t.allowKey = SiteBlock.keyOf(u); t.blockedUrl = null; refreshBlockCover()
        val w = t.wv ?: return
        t.loaded = true; loadIn(w, t, u)
    }
    private fun blockEmergency() {
        val t = tabs.getOrNull(curIdx) ?: return
        val u = t.blockedUrl ?: return
        t.blockedUrl = null; refreshBlockCover()
        panic(u)
    }
    private fun showBlocked() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun render() {
            box.removeAllViews()
            val l = SiteBlock.list(this)
            if (l.isEmpty()) box.addView(ui.text("مفيش مواقع محجوبة. زرار 🚨 على أي موقع بيحجبه وبيمسحه من السجل.", 13f, th.muted))
            for (k in l) {
                val c = ui.card()
                val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(ui.text(k, 14f, th.text, true).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(IconTextView(this).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6)); setOnClickListener { SiteBlock.remove(this@BrowserActivity, k); render() } })
                c.addView(row); box.addView(c)
            }
        }
        render()
        sheet("🚫 المواقع المحجوبة", box).show()
    }

    private fun showUrl(u: String?, force: Boolean = false) {
        if (!::addr.isInitialized) return
        if (!u.isNullOrEmpty() && u != "about:blank" && (force || !addr.hasFocus())) addr.setText(u)
    }
    private fun updateNav() {
        val w = wvOrNull
        backB.alpha = if (w?.canGoBack() == true) 1f else 0.35f
        fwdB.alpha = if (w?.canGoForward() == true) 1f else 0.35f
    }
    private fun updateProgress() {
        val p = tab.progress
        reloadB.text = if (p < 100) "✕" else "⟳"
        if (p >= 100) { progTrack.animate().cancel(); progTrack.animate().setStartDelay(0L).alpha(0f).setDuration(220L).withEndAction { progTrack.visibility = View.INVISIBLE }.start(); return }
        progTrack.animate().cancel(); progTrack.alpha = 1f
        progTrack.visibility = View.VISIBLE
        val w = progTrack.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        progV.layoutParams = FrameLayout.LayoutParams((w * p / 100f).toInt().coerceAtLeast(ui.dp(12)), -1)
    }
    private fun updateStar() { /* حالة النجمة بتتحسب وقت فتح القايمة */ }

    // ===== (v231) 🚨 طوارئ: مسح الصفحة فورًا وفتح جوجل · ↩ تراجع =====
    private fun endPanicCover() {
        panicClearId = -1L
        if (::panicCover.isInitialized) panicCover.visibility = View.GONE
    }
    private fun panic(blockedUrl: String? = null) {
        if (dead || tabs.isEmpty()) return
        val t = tabs[curIdx]
        val w = t.wv ?: return
        leaveCustom(); closeFind()
        try { dlg?.dismiss() } catch (_: Throwable) {}
        try { tabsDlg?.dismiss() } catch (_: Throwable) {}
        val cur = blockedUrl ?: (w.url ?: t.url)
        // الصفحة اللي هتتمسح بتتحفظ للتراجع (ماعدا لو إحنا أصلًا على الصفحة الرئيسية لجوجل — الدوسة التانية ماتضيّعش اللي اتحفظ)
        if (cur.isNotBlank() && !cur.startsWith("data:") && !cur.startsWith("about:") && cur.trimEnd('/') != GOOGLE) { panicUrl = cur; panicTabId = t.id; panicKey = null; panicPurged = emptyList() }
        // (v412) الموقع ده بيتمسح من السجل (كل لينك دومينه فيه اسمه) وبيتحجب: أي دخول تاني بيبدأ بغطا أسود. جوجل نفسه مابيتحجبش (هو صفحة الهروب)
        SiteBlock.keyOf(cur)?.let { k ->
            lastPurgeKey = k
            panicPurged = panicPurged + BrowserStore.purgeByKey(this, k)
            if (k != SiteBlock.keyOf(GOOGLE) && SiteBlock.add(this, k)) panicKey = k
        }
        WebMute.pauseAll()                              // أي صوت/فيديو يقف
        panicCover.visibility = View.VISIBLE            // الصفحة القديمة تختفي في نفس اللحظة
        panicClearId = t.id
        t.thumb = null; t.title = ""; t.errUrl = ""
        // اللقطات المصطادة من الصفحة دي تتمسح (الشريط «🎬 لقيت…» كان هيفضل ظاهر)
        t.gen++
        items.removeAll { it.tabId == t.id }
        keys.removeAll { it.startsWith("${t.id}:") }
        webGroups.clear(); openKey = null; autoShown = false; refreshBadge()
        showUrl(GOOGLE, true)
        w.stopLoading(); w.loadUrl(GOOGLE)
        undoB.visibility = if (panicUrl != null) View.VISIBLE else View.GONE
        persistTabs()   // (persistTabs بتحفظ جوجل مكان الصفحة اللي اتمسحت)
    }
    private fun undoPanic() {
        val u = panicUrl ?: run { undoB.visibility = View.GONE; return }
        val i = tabs.indexOfFirst { it.id == panicTabId }
        panicUrl = null; undoB.visibility = View.GONE
        panicKey?.let { SiteBlock.remove(this, it) }; panicKey = null   // (v412) التراجع بيشيل الحجب ويرجّع اللي اتمسح من السجل
        BrowserStore.restorePurged(this, panicPurged); panicPurged = emptyList()
        if (i < 0) { newTab(u, true); return }          // التبويب اتقفل: افتحها في تبويب جديد
        if (i != curIdx) select(i)
        val t = tabs[i]; val w = t.wv ?: return
        endPanicCover(); t.loaded = true
        loadIn(w, t, u)
        Notice.show(this, "↩ رجّعت الصفحة", 1500L)
    }

    // ===== (v231) تحكم الفيديو (كان شريط جنب الشاشة): جوه قايمة ⋮ =====
    private fun showVideoControls() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun row(vararg p: Pair<String, () -> Unit>): LinearLayout {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            for ((lab, f) in p) r.addView(ui.button(lab) { f() }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            return r
        }
        box.addView(row(
            "⏪ 10ث" to { vjs("v.currentTime=Math.max(0,v.currentTime-10);return ''", false) },
            "⏯ تشغيل/إيقاف" to { vjs("if(v.paused){v.play()}else{v.pause()};return ''", false) },
            "10ث ⏩" to { vjs("v.currentTime=v.currentTime+10;return ''", false) }))
        box.addView(row(
            "⤢ ملاءمة الشاشة" to { vjs("s.fit=(s.fit+1)%3;v.style.setProperty('object-fit',['contain','fill','cover'][s.fit],'important');return ['يلائم','تمديد','قص'][s.fit]") },
            "⏱ السرعة" to { vjs("var a=[0.75,1,1.25,1.5,2];var i=a.indexOf(s.sp);s.sp=a[(i+1)%a.length];v.playbackRate=s.sp;return 'السرعة '+s.sp+'x'") }))
        box.addView(row(
            "🔍＋ تكبير" to { vjs("s.z=Math.min(4,s.z+0.25);v.style.setProperty('transform','scale('+s.z+')','important');return 'تكبير x'+s.z.toFixed(2)") },
            "🔍－ تصغير" to { vjs("s.z=Math.max(1,s.z-0.25);v.style.setProperty('transform','scale('+s.z+')','important');return 'تكبير x'+s.z.toFixed(2)") }))
        sheet("🎞 تحكم الفيديو", box).show()
    }

    // ===== القايمة ⋮ =====
    private fun popupMenu(anchor: View, entries: List<Pair<String, () -> Any?>>) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.card, th.border, 14); elevation = ui.dp(8).toFloat() }
        val sv = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
        val width = ui.dp(if (tvOn) 310 else 240)
        val pw = TvFocus.pop(this@BrowserActivity, sv, width, -2, true)
        pw.setBackgroundDrawable(ColorDrawable(0))
        for ((t, f) in entries) col.addView(IconTextView(this).apply {
            text = t; textSize = if (tvOn) 17f else 14f; setTextColor(th.text); minHeight = ui.dp(if (tvOn) 54 else 44); gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6)); setOnClickListener { pw.dismiss(); f() }
        })
        col.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val maxH = (resources.displayMetrics.heightPixels * 0.72f).toInt()
        pw.height = minOf(col.measuredHeight, maxH)
        try { pw.showAsDropDown(anchor, anchor.width - width, ui.dp(2)) } catch (_: Throwable) {}
    }
    /** (v494) زرار تدوير: لو لاندسكيب يروّح بورتريه، والعكس */
    private fun toggleRotate() {
        val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (land) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun showMenu(anchor: View) {
        val url = tab.wv?.url ?: tab.url
        val marked = BrowserStore.isMarked(this, url)
        val menuItems: List<Pair<String, () -> Unit>> = listOf(
            "➕ تبويب جديد" to { newTab(home(), true) },
            "🕶 تبويب متخفي جديد" to { newTab(home(), true, -1L, true) },
            (if (marked) "★ شيل من المفضلة" else "☆ ضيف للمفضلة") to {
                val added = BrowserStore.toggleMark(this, url, tab.title.ifBlank { Sniff.nameOf(url) })
                Notice.show(this, if (added) "اتضافت للمفضلة" else "اتشالت من المفضلة", 1800L)
            },
            "📚 المفضلة" to { showBookmarks() },
            "🕘 السجل" to { showHistory() },
            "🔒 سجل الخصوصية" to { showPrivacyHistory() },
            "🚫 المواقع المحجوبة (${SiteBlock.list(this).size})" to { showBlocked() },
            "🔄 تدوير الشاشة (لاندسكيب ⇄ بورتريه)" to { toggleRotate() },
            "🔍 بحث في الصفحة" to { openFind() },
            (if (tab.desktop) "🖥 نسخة الكمبيوتر: شغالة ✓" else "🖥 نسخة الكمبيوتر") to { toggleDesktop() },
            "🏠 الصفحة الرئيسية" to { load(home()) },
            "📋 نسخ الرابط" to { copyUrl(url) },
            "📤 مشاركة الصفحة" to { shareUrl(url) },
            "🎞 تحكم الفيديو" to { showVideoControls() },
            "🎬 الفيديوهات الملقوطة (${groupsOf(vis()).size})" to { showList() },
            "📖 وضع القارئ" to { readerMode() },
            "🛡 إحصائيات حظر الإعلانات" to { adStatsDialog() },
            (if (hostOf(url) in adWhite) "🛡 فعّل حظر الإعلانات للموقع ده" else "🛡 عطّل حظر الإعلانات للموقع ده") to { toggleAdWhite(url) },
            "⚙️ الإعدادات" to { showSettings() },
            "✕ خروج من المتصفح" to { finish() }
        )
        // (v517) تلفزيون: القايمة بتشيل اللي مش بيتستعمل بالريموت (متخفي · سجل الخصوصية · تدوير · نسخ/مشاركة · وضع القارئ · إحصائيات وسماح الإعلانات)
        val tvDrop = listOf("🕶", "🔒", "🔄 تدوير", "📋 نسخ", "📤", "📖", "🛡 إحصائيات", "🛡 فعّل", "🛡 عطّل")
        popupMenu(anchor, if (tvOn) menuItems.filter { m -> tvDrop.none { m.first.startsWith(it) } } else menuItems)
    }
    /** (v469) D11 وضع القارئ: بيستخرج نص المقال (article ← main ← body) وبيعرضه في عرض قراءة نضيف بخط كبير من غير فوضى الصفحة */
    private fun readerMode() {
        val w = tab.wv ?: return
        val js = "(function(){var el=document.querySelector('article')||document.querySelector('main')||document.body;if(!el)return '';return (el.innerText||'').trim();})()"
        try {
            w.evaluateJavascript(js) { res ->
                val txt = try { org.json.JSONTokener(res ?: "\"\"").nextValue()?.toString() ?: "" } catch (_: Throwable) { "" }
                if (txt.length < 200) { Notice.show(this, "الصفحة دي مافيهاش مقال مناسب لوضع القارئ", 2600L); return@evaluateJavascript }
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(18)) }
                col.addView(ui.text(tab.title.ifBlank { "📖 وضع القارئ" }, 18f, th.primary, true).apply { setPadding(0, 0, 0, ui.dp(10)) })
                col.addView(ui.text(txt, 16.5f, th.text).apply { setLineSpacing(ui.dp(5).toFloat(), 1.15f) })
                val sv = ScrollView(this).apply { addView(col); setBackgroundColor(th.bg) }
                val d = GDialog(this); d.setContentView(sv)
                d.window?.apply { setBackgroundDrawable(android.graphics.drawable.ColorDrawable(th.bg)); setLayout(-1, -1) }
                d.show()
            }
        } catch (e: Throwable) { LogStore.err("Browser:reader", e) }
    }

    /** (v469) D12 حوار إحصائيات الحظر: الإجمالي + أكتر المواقع حظرًا + المواقع المعطّلة + مسح */
    private fun adStatsDialog() {
        saveAdStats()
        val top = adStats.entries.sortedByDescending { it.value }.take(15)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12)) }
        col.addView(ui.text("🛡 اتحظر " + adStats.values.sum() + " عنصر إعلاني في " + adStats.size + " موقع", 15f, th.primary, true).apply { setPadding(0, 0, 0, ui.dp(8)) })
        if (top.isEmpty()) col.addView(ui.text("لسه مفيش عناصر محظورة متسجلة", 13f, th.muted))
        for ((h, n) in top) col.addView(ui.text("• " + h + " — " + n + " عنصر", 13.5f, th.text).apply { setPadding(0, ui.dp(3), 0, ui.dp(3)) })
        if (adWhite.isNotEmpty()) col.addView(ui.text("الحظر معطّل في: " + adWhite.joinToString(" · "), 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        var dlg: android.app.Dialog? = null
        col.addView(ui.button("🧹 مسح الإحصائيات") { adStats.clear(); try { prefs().edit().remove("adstats").apply() } catch (_: Throwable) {}; dlg?.dismiss(); Notice.show(this, "اتمسحت الإحصائيات", 1800L) })
        dlg = ui.sheet(this, "🛡 إحصائيات حظر الإعلانات", listOf<View>(ScrollView(this).apply { addView(col) })); dlg.show()
    }

    /** (v469) D12 تعطيل/تفعيل الحظر لموقع الصفحة الحالية (قائمة سماح) + إعادة تحميل */
    private fun toggleAdWhite(url: String) {
        val h = hostOf(url); if (h.isEmpty()) { Notice.show(this, "مفيش موقع مفتوح", 2000L); return }
        if (h in adWhite) adWhite.remove(h) else adWhite.add(h)
        try { prefs().edit().putString("adwhite", adWhite.joinToString("\n")).apply() } catch (_: Throwable) {}
        Notice.show(this, if (h in adWhite) "🛡 الحظر اتعطّل لـ " + h + " — بعيد تحميل الصفحة" else "🛡 الحظر اشتغل لـ " + h + " — بعيد تحميل الصفحة", 2600L)
        try { tab.wv?.reload() } catch (_: Throwable) {}
    }

    private fun copyUrl(u: String) {
        try { ClipWatch.markSeen(this, u); (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("url", u)); Notice.show(this, "اتنسخ اللينك", 1800L) } catch (e: Throwable) { LogStore.err("Browser:copy", e) }
    }
    private fun shareUrl(u: String) {
        try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u), "مشاركة")) } catch (e: Throwable) { LogStore.err("Browser:share", e) }
    }
    private fun toggleDesktop() {
        val t = tab; val w = t.wv ?: return
        t.desktop = !t.desktop
        w.settings.userAgentString = if (t.desktop) DESK_UA else uaWeb.ifEmpty { null }
        Notice.show(this, if (t.desktop) "نسخة الكمبيوتر: شغالة" else "نسخة الموبايل", 1800L)
        w.reload(); persistTabs()
    }

    // ===== بحث في الصفحة =====
    private fun openFind() {
        findBar.visibility = View.VISIBLE; findEt.requestFocus()
        wvOrNull?.setFindListener { active, num, _ -> findCount.text = if (num == 0) "0" else "${active + 1}/$num" }
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(findEt, 0) } catch (_: Throwable) {}
    }
    private fun closeFind() {
        if (!::findBar.isInitialized || findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE; findEt.setText(""); findCount.text = ""
        wvOrNull?.clearMatches()
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(findEt.windowToken, 0) } catch (_: Throwable) {}
    }

    // ===== شاشات (sheets) =====
    private fun sheet(title: String, vararg views: View): Dialog = ui.sheet(this, title, views.toList(), false)

    // ===== (v124) شاشة التبويبات زي كروم: مربعات فيها لقطة من الصفحة + العنوان + ✕، وقسم منفصل للمتخفي =====
    private var tabsPrivView = false
    private fun snapTab(t: BTab) {
        val w = t.wv ?: return
        if (w.width <= 0 || w.height <= 0 || w.parent == null) return
        try {
            val tw = ui.dp(190); val th2 = (tw * 1.15f).toInt()
            val sc = tw.toFloat() / w.width
            val srcH = minOf(w.height, (th2 / sc).toInt())
            val bmp = android.graphics.Bitmap.createBitmap(tw, (srcH * sc).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.RGB_565)
            val c = android.graphics.Canvas(bmp); c.scale(sc, sc); w.draw(c)
            t.thumb = bmp
        } catch (_: Throwable) {}
    }
    private fun showTabs() {
        if (tabsDlg?.isShowing == true) return
        tabs.getOrNull(curIdx)?.let { snapTab(it) }
        tabsPrivView = tabs.getOrNull(curIdx)?.priv == true
        val d = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        tabsDlg = d
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(28), 0, 0) }
        val head = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) }
        val modeNormal = ui.button("التبويبات") { tabsPrivView = false; renderTabs() }
        val modePriv = ui.button("🕶 متخفي") { tabsPrivView = true; renderTabs() }
        head.addView(modeNormal, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        head.addView(modePriv, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        head.addView(IconTextView(this).apply { text = "✕"; textSize = 20f; setTextColor(th.text); setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); setOnClickListener { d.dismiss() } })
        val actions = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(4)) }
        actions.addView(ui.button("➕ تبويب جديد", true) { d.dismiss(); newTab(home(), true, -1L, tabsPrivView) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(4) } })
        actions.addView(ui.button("🗑 اقفل الكل") { closeAllTabs(tabsPrivView) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(16)) }
        tabsBox = box
        root.addView(head); root.addView(actions)
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(-1, 0, 1f))
        d.setContentView(root)
        d.window?.apply { setBackgroundDrawable(ColorDrawable(th.bg)); setLayout(-1, -1) }
        d.setOnDismissListener { tabsDlg = null; tabsBox = null }
        renderTabs(); TvFocus.attachDialog(this, d, th.primary); d.show()   // (v480 م7)
        Motion.fadeSlideIn(root, 0f, 40f * resources.displayMetrics.density, 240L, 0L); Motion.stagger(box, 8, 35L)
    }
    private fun renderTabs() {
        val box = tabsBox ?: return
        box.removeAllViews()
        val list = tabs.withIndex().filter { it.value.priv == tabsPrivView }
        if (list.isEmpty()) box.addView(ui.text(if (tabsPrivView) "مفيش تبويبات متخفية — مفيش سجل ولا حفظ فيها" else "مفيش تبويبات", 13f, th.muted).apply { setPadding(ui.dp(12), ui.dp(16), ui.dp(12), 0) })
        val cardW = (resources.displayMetrics.widthPixels - ui.dp(12) * 3) / 2
        if (list.isNotEmpty() && !prefs().getBoolean("br_swipe_hint_done", false))
            box.addView(ui.text("👆 اسحب أي تبويب يمين أو شمال عشان تقفله", 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(6)) })
        for (pair in list.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for ((i, t) in pair) {
                val url = t.wv?.url ?: t.url
                val ttl = t.title.ifBlank { Sniff.nameOf(url).ifBlank { "تبويب جديد" } }
                val dark = t.priv
                val card = SwipeCard(this) { swipeClose(t) }.apply {
                    orientation = LinearLayout.VERTICAL
                    background = ui.box(if (dark) 0xFF2B2D31.toInt() else th.card, if (i == curIdx) th.primary else th.border, 14, if (i == curIdx) 3 else 1)
                    setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
                    setOnClickListener { tabsDlg?.dismiss(); select(i) }
                }
                val bar = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL }
                bar.addView(ui.text((if (dark) "🕶 " else "") + ttl, 12f, if (dark) Color.WHITE else th.text, i == curIdx).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(6), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
                bar.addView(IconTextView(this).apply { text = "✕"; textSize = 16f; setTextColor(if (dark) 0xFFBBBBBB.toInt() else th.muted); setPadding(ui.dp(10), ui.dp(6), ui.dp(8), ui.dp(6)); setOnClickListener { closeTab(i); if (tabsDlg?.isShowing == true) renderTabs() } })
                card.addView(bar)
                val thumbH = (cardW * 1.1f).toInt()
                val bmp = t.thumb
                if (bmp != null) {
                    card.addView(android.widget.ImageView(this).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP; background = ui.box(Color.WHITE, 0x00000000, 10) }, LinearLayout.LayoutParams(-1, thumbH))
                } else {
                    card.addView(FrameLayout(this).apply {
                        background = ui.box(if (dark) 0xFF1B1C1F.toInt() else th.surface, 0x00000000, 10)
                        addView(ui.text(Sniff.nameOf(url).ifBlank { "🌐" }.take(24), 14f, if (dark) 0xFFBBBBBB.toInt() else th.muted).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    }, LinearLayout.LayoutParams(-1, thumbH))
                }
                row.addView(card, LinearLayout.LayoutParams(cardW, -2).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) })
            }
            box.addView(row)
        }
    }
    /** (v200) قفل تبويب بالسحب — بالـ id مش بالـ index عشان لو الشاشة اتعمل لها render وسط الأنيميشن ما يتقفلش تبويب غلط */
    private fun swipeClose(t: BTab) {
        val i = tabs.indexOfFirst { it.id == t.id }
        if (i >= 0) closeTab(i)
        prefs().edit().putBoolean("br_swipe_hint_done", true).apply()
        if (tabsDlg?.isShowing == true) renderTabs()
    }
    private fun closeAllTabs(priv: Boolean = false) {
        val victims = tabs.filter { it.priv == priv }
        val curT = tabs.getOrNull(curIdx)
        for (t in victims) try { t.wv?.let { (it.parent as? ViewGroup)?.removeView(it); if (t.priv) { it.clearCache(true); it.clearHistory(); it.clearFormData() }; it.destroy() } } catch (_: Throwable) {}
        tabs.removeAll(victims.toSet())
        tabsDlg?.dismiss()
        if (tabs.isEmpty()) { newTab(home(), true); return }
        if (curT != null && curT in victims) select(0) else { curIdx = tabs.indexOf(curT).coerceAtLeast(0); webGroups.clear(); refreshBadge(); refreshTabsCount(); persistTabs() }
    }

    private fun entryRow(e: BrowserStore.Entry, onOpen: () -> Unit, onDel: () -> Unit): View {
        val c = ui.card()
        val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(ui.text(e.title.ifBlank { Sniff.nameOf(e.url) }, 14f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        col.addView(ui.text(e.url, 11f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(IconTextView(this).apply { text = "✕"; textSize = 18f; setTextColor(th.muted); setPadding(ui.dp(12), ui.dp(6), ui.dp(4), ui.dp(6)); setOnClickListener { onDel() } })
        c.addView(row); c.setOnClickListener { onOpen() }
        return c
    }
    private fun showHistory() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val q = ui.input("دوّر في السجل", "")
        lateinit var d: Dialog
        fun render() {
            box.removeAllViews()
            val f = q.text.toString().trim().lowercase()
            val l = BrowserStore.history(this).filter { f.isEmpty() || it.url.lowercase().contains(f) || it.title.lowercase().contains(f) }.take(200)
            if (l.isEmpty()) box.addView(ui.text("مفيش حاجة في السجل", 13f, th.muted))
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
            var lastDay = ""
            for (e in l) {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(e.time))
                if (day != lastDay) { lastDay = day; box.addView(ui.text(fmt.format(java.util.Date(e.time)).substringBefore(' '), 12f, th.primary, true).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(2)) }) }
                box.addView(entryRow(e, { d.dismiss(); load(e.url) }, { BrowserStore.removeHistory(this, e); render() }))
            }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val clear = ui.button("🗑 امسح السجل كله") { BrowserStore.clearHistory(this); render(); Notice.show(this, "السجل اتمسح", 1800L) }
        d = sheet("🕘 السجل", q, clear, box)
        render(); d.show()
    }
    /** (v358) سجل الخصوصية: مقفول بنفس نمط/بصمة المجلد المخفي. بيتقفل من أول ما الدايلوج يتقفل (كل فتحة بتطلب القفل تاني) */
    private var privLock: LockUi? = null
    private fun showPrivacyHistory() {
        val lk = privLock ?: LockUi(this, ui, th).also { privLock = it }
        lk.gate { openPrivacyHistory() }
    }
    private fun openPrivacyHistory() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val q = ui.input("دوّر في سجل الخصوصية", "")
        lateinit var d: Dialog
        fun render() {
            box.removeAllViews()
            val f = q.text.toString().trim().lowercase()
            val l = BrowserStore.privacy(this).filter { f.isEmpty() || it.url.lowercase().contains(f) || it.title.lowercase().contains(f) }.take(200)
            if (l.isEmpty()) box.addView(ui.text("مفيش حاجة هنا. اللينكات اللي الذكاء الصناعي (أو الكلمات الواضحة) يصنّفها «للكبار» بتتحط هنا بدل السجل العادي.", 13f, th.muted))
            val fmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
            var lastDay = ""
            for (e in l) {
                val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(e.time))
                if (day != lastDay) { lastDay = day; box.addView(ui.text(fmt.format(java.util.Date(e.time)).substringBefore(' '), 12f, th.primary, true).apply { setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(2)) }) }
                box.addView(entryRow(e, { d.dismiss(); load(e.url) }, { BrowserStore.removePrivacy(this, e); render() }))
            }
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { render() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val clear = ui.button("🗑 امسح سجل الخصوصية كله") { BrowserStore.clearPrivacy(this); render(); Notice.show(this, "سجل الخصوصية اتمسح", 1800L) }
        d = sheet("🔒 سجل الخصوصية", q, clear, box)
        try { d.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) } catch (_: Throwable) {}
        render(); d.show()
    }
    private fun showBookmarks() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var d: Dialog
        fun render() {
            box.removeAllViews()
            val l = BrowserStore.bookmarks(this)
            if (l.isEmpty()) box.addView(ui.text("مفيش مفضلة لسه — من ⋮ دوس «ضيف للمفضلة»", 13f, th.muted))
            for (e in l) box.addView(entryRow(e, { d.dismiss(); load(e.url) }, { BrowserStore.removeMark(this, e); render() }))
        }
        d = sheet("⭐ المفضلة", box)
        render(); d.show()
    }
    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.switchRow("🛡 مانع الإعلانات", adOn) { v ->
            adOn = v; prefs().edit().putBoolean("adblock", v).apply()
            shield.text = if (adOn) "🛡 $blockedN" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f
        })
        box.addView(ui.text("🚫 البوب-أب (النوافذ المنبثقة)", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        box.addView(ui.chips(listOf("ذكي", "صارم", "مقفول"), { prefs().getString("pop_mode", "ذكي") ?: "ذكي" }) { setPopMode(it) })
        box.addView(ui.text("ذكي: يفتح البوب-أب في الخفا ويفحصه — إعلان يتقفل، وصفحة تحويل لمشاهدة الفيديو تتفتح تبويب. صارم: بس اللينكات الحقيقية. مقفول: السلوك القديم.", 11f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) })
        box.addView(ui.switchRow("🖥 نسخة الكمبيوتر في التبويبات الجديدة", prefs().getBoolean("br_desktop_default", false)) { v -> prefs().edit().putBoolean("br_desktop_default", v).apply() })
        box.addView(ui.switchRow("💾 احفظ التبويبات لما أخرج وارجّعها", prefs().getBoolean("br_save_tabs", true)) { v ->
            prefs().edit().putBoolean("br_save_tabs", v).apply()
            if (v) persistTabs() else try { java.io.File(filesDir, "br_tabs.json").delete() } catch (_: Throwable) {}
        })
        box.addView(ui.text("محرك البحث", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        box.addView(ui.chips(listOf("Google", "DuckDuckGo", "Bing"), { prefs().getString("br_engine", "Google") ?: "Google" }) { prefs().edit().putString("br_engine", it).apply() })
        box.addView(ui.text("الصفحة الرئيسية (فاضي = جوجل)", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        val homeEt = ui.input("https://www.google.com", prefs().getString("br_home", "") ?: "").apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        homeEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { prefs().edit().putString("br_home", s?.toString()?.trim() ?: "").apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        box.addView(homeEt)
        box.addView(ui.button("🧹 امسح الكاش") { try { tabs.forEach { it.wv?.clearCache(true) } } catch (_: Throwable) {}; Notice.show(this, "الكاش اتمسح", 1800L) })
        box.addView(ui.button("🍪 امسح الكوكيز وبيانات المواقع (هتتسجّل خروج من المواقع)") {
            try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
            Notice.show(this, "الكوكيز وبيانات المواقع اتمسحت", 2000L)
        })
        box.addView(ui.switchRow("🔒 فصل لينكات الكبار في «سجل الخصوصية» المقفول", PrivacyFilter.on()) { Cfg.put("pf_on", if (it) "1" else "0") })
        box.addView(ui.switchRow("🤖 الذكاء الصناعي يصنّف الصفحات (بيبعت الرابط والعنوان بس، مرة لكل موقع)", PrivacyFilter.aiOn()) { Cfg.put("pf_ai", if (it) "1" else "0") })
        box.addView(ui.button("🕘 امسح السجل") { BrowserStore.clearHistory(this); Notice.show(this, "السجل اتمسح", 1800L) })
        sheet("⚙️ إعدادات المتصفح", box).show()
    }

    // ===== ضغطة طويلة على لينك/صورة =====
    private fun longPress(w: WebView): Boolean {
        val r = w.hitTestResult
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { linkSheet(extra, false); return true }
            WebView.HitTestResult.IMAGE_TYPE -> { linkSheet(extra, true); return true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val hd = Handler(Looper.getMainLooper()) { m -> val u = m.data?.getString("url"); linkSheet(if (!u.isNullOrBlank()) u else extra, false); true }
                w.requestFocusNodeHref(hd.obtainMessage()); return true
            }
        }
        return false
    }
    private fun linkSheet(u: String, image: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var d: Dialog
        box.addView(ui.text(u, 11f, th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR })
        if (!image) {
            box.addView(ui.button("➕ افتح في تبويب جديد") { d.dismiss(); newTab(u, true, tab.id) })
            box.addView(ui.button("🫥 افتح في تبويب جديد (في الخلفية)") { d.dismiss(); newTab(u, false, tab.id); Notice.show(this, "اتفتح في تبويب جديد", 1500L) })
        } else {
            box.addView(ui.button("🖼 افتح الصورة في تبويب جديد") { d.dismiss(); newTab(u, true, tab.id) })
            box.addView(ui.button("⬇ احفظ الصورة") { d.dismiss(); startDownload(u, uaOf(tab), URLUtil.guessFileName(u, null, null), null) })
        }
        if (!image) box.addView(ui.button("🎬 افتح بـ… (مشغّل · صوت · يوتيوب)") { d.dismiss(); Links.sheet(this, ui, u, "", "browser", false) { persistTabs() } })   // (v417) الموزّع الموحّد
        box.addView(ui.button("📋 انسخ اللينك") { d.dismiss(); copyUrl(u) })
        box.addView(ui.button("📤 شارك") { d.dismiss(); shareUrl(u) })
        d = sheet(if (image) "🖼 صورة" else "🔗 لينك", box); d.show()
    }

    private fun startDownload(url: String, ua: String?, name: String, mime: String?) {
        try {
            val ck = try { CookieManager.getInstance().getCookie(url) } catch (_: Throwable) { null }
            // (v324) التحميل بيظهر في «المهام» (DmTasks بتتابع DownloadManager)
            DmTasks.start(applicationContext, url, name, mime, ua ?: uaWeb, ck, tab.url)
            Notice.show(this, "⬇ اتضاف للتحميل — تابعه من زرار المهام: $name", 2600L)
        } catch (e: Throwable) { LogStore.err("Browser:download", e); Notice.show(this, "التحميل فشل: " + (e.message ?: "").take(60), 3000L) }
    }

    // ===== (v324) شريط «تحميل؟» =====
    private fun showDl(name: String, go: () -> Unit) {
        if (dead) return
        dlHide?.let { h.removeCallbacks(it) }
        dlAct = go
        dlTxt.text = "⬇ تحميل «$name»؟"
        dlBar.visibility = View.VISIBLE
        dlLine.animate().cancel(); dlLine.scaleX = 1f
        dlLine.post {
            dlLine.pivotX = dlLine.width.toFloat()
            dlLine.animate().scaleX(0f).setDuration(DL_PROMPT_MS).setInterpolator(android.view.animation.LinearInterpolator()).start()
        }
        val r = Runnable { hideDl() }
        dlHide = r; h.postDelayed(r, DL_PROMPT_MS)
    }
    private fun hideDl() {
        dlHide?.let { h.removeCallbacks(it) }; dlHide = null; dlAct = null
        if (!::dlBar.isInitialized) return
        dlLine.animate().cancel()
        dlBar.visibility = View.GONE
    }
    /** لمسة في أي حتة برّا شريط التحميل = «مش عايز أحمّل» (اللمسة نفسها بتكمّل لحد الصفحة عادي) */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_UP) lastTouchAt = android.os.SystemClock.uptimeMillis()
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && ::dlBar.isInitialized && dlBar.visibility == View.VISIBLE && dlBar.isLaidOut) {
            val r = android.graphics.Rect()
            if (dlBar.getGlobalVisibleRect(r) && !r.contains(ev.rawX.toInt(), ev.rawY.toInt())) hideDl()
        }
        return super.dispatchTouchEvent(ev)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 4711) { fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data)); fileCb = null; return }
        if (privLock?.onResult(requestCode, resultCode == RESULT_OK) == true) return
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ===== صيد الفيديو (زي الأول) =====
    private fun scanOnce() {
        if (dead) return
        val wv = wvOrNull ?: return
        try {
            wv.evaluateJavascript(JS) { r ->
                try {
                    val s = JSONTokener(r ?: "").nextValue() as? String
                    if (!s.isNullOrEmpty()) { val arr = JSONArray(s); for (i in 0 until arr.length()) Sniff.accept(arr.getString(i))?.let { addUrl(it) } }
                } catch (e: Throwable) { LogStore.err("Browser:scan2", e) }
            }
        } catch (e: Throwable) { LogStore.err("Browser:scan3", e) }
        scanGroups()
    }
    private fun scanGroups() {
        val wv = wvOrNull ?: return
        try {
            wv.evaluateJavascript(JSQ) { r ->
                try {
                    val s = JSONTokener(r ?: "").nextValue() as? String ?: return@evaluateJavascript
                    val arr = JSONArray(s)
                    for (i in 0 until arr.length()) {
                        val ga = arr.getJSONArray(i); val g = ArrayList<Pair<String, Int>>()
                        for (j in 0 until ga.length()) { val o = ga.getJSONObject(j); val u = Sniff.accept(o.optString("u")) ?: continue; g.add(u to o.optInt("h")); addUrl(u) }
                        if (g.size > 1 && webGroups.none { x -> x.any { y -> g.any { z -> Sniff.key(z.first) == Sniff.key(y.first) } } }) webGroups.add(g)
                    }
                } catch (e: Throwable) { LogStore.err("Browser:grp", e) }
            }
        } catch (e: Throwable) { LogStore.err("Browser:grp2", e) }
    }
    private fun scanLoop() { if (dead) return; scanOnce(); h.postDelayed({ scanLoop() }, 2000) }

    private val ytPicks = HashMap<String, YtExtract.Pick>()   // (v119) معرّف → أحسن اختيار (فيه الصوت المنفصل وقايمة الجودات)
    private fun ytFetch(id: String) {
        hint.text = "⏳ بجيب روابط يوتيوب…"
        val tt = tab
        Thread {
            val r = try { YtExtract.fetch(id) } catch (e: Throwable) { LogStore.err("Browser:ytFetch", e); emptyList() }   // (v468) تسجيل بدل البلع الصامت
            // (v203) دايمًا بنجيب قايمة الجودات الكاملة (144p…1080p + الصوت) عشان تتجمّع في اصطياد واحد
            val pk = try { YtExtract.fetchPick(id, 0) } catch (e: Throwable) { LogStore.err("Browser:ytPick", e); null }   // (v468)
            runOnUiThread {
                if (dead) return@runOnUiThread
                // لو المستخدم سابك الصفحة دي قبل ما النتيجة توصل: نتجاهلها (مش هتتحط على صفحة تانية)
                if (!tabs.contains(tt) || YtExtract.videoId(tt.url) != id) return@runOnUiThread
                val g = tt.gen
                r.forEach { ytIds[Sniff.key(it.url)] = id; addFound(it, tt, g) }
                if (pk != null) {
                    ytPicks[id] = pk
                    val f = Found(pk.url, if (pk.label == "HLS") "HLS" else "YT " + pk.label.substringBefore(' '), pk.title, "https://www.youtube.com/", pk.ua)
                    ytIds[Sniff.key(f.url)] = id
                    if (r.isEmpty()) addFound(f, tt, g) else refreshBadge()
                }
                if (r.isEmpty() && pk == null && vis().isEmpty()) hint.text = "يوتيوب ما رضيش يدّي لينك مباشر — شغّل الفيديو في الصفحة وأنا هحاول ألقطه\n" + YtExtract.lastWhy
            }
        }.apply { isDaemon = true }.start()
    }

    fun addUrl(u: String) = addFound(Found(u, Sniff.kindOf(u), "", wvOrNull?.url ?: "", if (tabs.isNotEmpty()) uaOf(tab) else uaWeb))
    fun addFound(f: Found, t: BTab? = tabs.getOrNull(curIdx), g: Int = t?.gen ?: 0) {
        if (t == null) return
        f.tabId = t.id; f.gen = g
        if (g != t.gen) return   // لقطة من صفحة قديمة
        if (!keys.add("${t.id}:$g:" + Sniff.key(f.url))) return
        if (f.size > 0 || f.kind == "HLS") { accept(f); return }
        probe(f) { accept(f) }   // حجم مش معروف: نفحصه الأول، وأي فيديو أقل من 3 ميجا (إعلانات غالبًا) بيتتجاهل
    }
    private fun accept(f: Found) {
        if (dead) return
        if (f.size in 1 until Sniff.MIN_BYTES) return
        val t = tabs.firstOrNull { it.id == f.tabId } ?: return
        if (f.gen != t.gen) return      // الصفحة اتغيّرت وإحنا بنفحص الحجم
        items.add(f)
        if (t !== tabs.getOrNull(curIdx)) return
        refreshBadge()
        if (repairId != null && !repairPending) { repairPending = true; h.postDelayed({ repairPending = false; autoRepair() }, 2000L) }   // نستنى ثانيتين عشان باقي الجودات توصل
        if (autoOpen && !autoShown) { autoShown = true; showList() }
        else if (dlg?.isShowing != true && vis().size == 1) Notice.show(this, "🎬 لقيت فيديو", 2300L)
    }

    // ===== (v401) مانع البوب-أب الذكي =====
    private fun popMode(): Int = if (!adOn) PopupGuard.OFF else PopupGuard.modeOf(prefs().getString("pop_mode", "ذكي"))
    private fun adJs(): String = if (popMode() != PopupGuard.OFF) AdBlock.JS_SMART + ";" + PopupGuard.TRACK else AdBlock.JS
    private fun allowedHosts(): Set<String> = try { prefs().getStringSet("pop_allow", emptySet()) ?: emptySet() } catch (_: Throwable) { emptySet() }
    private fun setPopMode(m: String) {
        prefs().edit().putString("pop_mode", m).apply()
        val on = PopupGuard.modeOf(m) != PopupGuard.OFF
        for (x in tabs) try { x.wv?.settings?.javaScriptCanOpenWindowsAutomatically = on } catch (_: Throwable) {}
        Notice.show(this, when (m) { "مقفول" -> "مانع البوب-أب اتقفل"; "صارم" -> "مانع البوب-أب: صارم"; else -> "مانع البوب-أب: ذكي" }, 2200L)
    }

    /** نافذة منبثقة جديدة: من غير ضغطة/لمسة قريبة = إعلان واتمنع فورًا؛ غير كده بتتفتح في الحجر وبنفحصها */
    private fun popupWindow(opener: BTab, openerView: WebView?, gesture: Boolean, resultMsg: Message?): Boolean {
        val touchAgo = android.os.SystemClock.uptimeMillis() - lastTouchAt
        if (!gesture && touchAgo > 2500L) { popBlocked("", listOf("من غير ضغطة منك"), true); return true }
        if (holds.size >= 3) { popBlocked("", listOf("بوب-أبات كتير ورا بعض"), true); return true }
        val msg = resultMsg ?: return false
        val tr = msg.obj as? WebView.WebViewTransport ?: return false
        val t = BTab(nextId++); t.opener = opener.id; t.priv = opener.priv
        t.desktop = prefs().getBoolean("br_desktop_default", false)
        t.wv = makeWebView(t); t.loaded = true
        val hd = PopupGuard.Hold(t, openerView?.url ?: opener.url, opener.title, gesture, touchAgo)
        t.hold = hd; holds.add(hd)
        tr.webView = t.wv; msg.sendToTarget()
        try { openerView?.evaluateJavascript(PopupGuard.READ_CLICK) { r -> hd.setClick(r) } } catch (e: Throwable) { LogStore.err("Browser:popClick", e) }   // (v468)
        val tm = Runnable { popDecide(hd, true) }
        hd.timer = tm; h.postDelayed(tm, PopupGuard.MAX_WAIT_MS)
        return true
    }

    private fun popProbe(hd: PopupGuard.Hold) {
        if (hd.done || dead) return
        val w = hd.tab.wv ?: return
        try { w.evaluateJavascript(PopupGuard.PROBE) { r -> hd.setProbe(r); popDecide(hd, false) } } catch (e: Throwable) { LogStore.err("Browser:popProbe", e) }   // (v468)
    }

    private fun popDecide(hd: PopupGuard.Hold, final: Boolean) {
        if (hd.done || dead) return
        val w = hd.tab.wv
        val fin = w?.url?.takeIf { it.isNotBlank() && it != "about:blank" } ?: hd.chain.lastOrNull() ?: ""
        val v = PopupGuard.verdict(hd, fin, popMode(), final, allowedHosts())
        when (v.action) {
            PopupGuard.WAIT -> return
            PopupGuard.ALLOW -> popPromote(hd)
            else -> popKill(hd, fin, v.why)
        }
    }

    /** صفحة سليمة (مشاهدة/لينك حقيقي): تتحوّل لتبويب عادي وتتفتح */
    private fun popPromote(hd: PopupGuard.Hold) {
        if (hd.done) return
        hd.done = true; hd.timer?.let { h.removeCallbacks(it) }; holds.remove(hd)
        val t = hd.tab; t.hold = null
        tabs.add(t); refreshTabsCount(); persistTabs()
        select(tabs.size - 1)
        for (a in hd.sniffed) addFound(Found(a, Sniff.kindOf(a), "", t.url, uaOf(t)), t)
    }

    /** إعلان: بيتقفل ويتمسح */
    private fun popKill(hd: PopupGuard.Hold, url: String, why: List<String>) {
        if (hd.done) return
        hd.done = true; hd.timer?.let { h.removeCallbacks(it) }; holds.remove(hd)
        val t = hd.tab; t.hold = null
        val w = t.wv; t.wv = null
        h.post { try { w?.let { (it.parent as? ViewGroup)?.removeView(it); it.stopLoading(); it.destroy() } } catch (_: Throwable) {} }
        popBlocked(url, why, false)
    }

    private fun popBlocked(url: String, why: List<String>, silent: Boolean) {
        blockedN++
        if (::shield.isInitialized && adOn) shield.text = "🛡 $blockedN"
        if (url.isBlank() || silent) return
        blockedPops.add(0, PopupGuard.Blocked(url, why.joinToString(" · "), System.currentTimeMillis()))
        while (blockedPops.size > 30) blockedPops.removeAt(blockedPops.size - 1)
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPopNotice < 5000L) return
        lastPopNotice = now
        val host = PopupGuard.hostOf(url).ifEmpty { "موقع" }
        Notice.ask(this, "🚫 بوب-أب اتمنع من «$host»" + (why.firstOrNull()?.let { "\n$it" } ?: "") + " — تفتحه؟", "افتحه", "تمام", 5000L,
            { allowHost(host); newTab(url, true, tab.id) }, {})
    }

    private fun allowHost(host: String) {
        try { val cur = HashSet(allowedHosts()); cur.add(PopupGuard.reg(host)); prefs().edit().putStringSet("pop_allow", cur).apply() } catch (_: Throwable) {}
    }

    /** ضغطة طويلة على 🛡: الوضع + آخر اللي اتمنع */
    private fun showPopupSheet() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.text("بيفتح أي نافذة منبثقة في الخفا الأول ويفحصها: لينكها · ضغطتك · فيها فيديو؟ · نفس اسم الفيلم؟ — لو إعلان بيقفلها، ولو صفحة تحويل لمشاهدة الفيديو بيفتحها تبويب عادي.", 12f, th.muted).apply { setPadding(0, 0, 0, ui.dp(8)) })
        box.addView(ui.chips(listOf("ذكي", "صارم", "مقفول"), { prefs().getString("pop_mode", "ذكي") ?: "ذكي" }) { setPopMode(it) })
        box.addView(ui.text("اللي اتمنع في الجلسة دي", 12f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        if (blockedPops.isEmpty()) box.addView(ui.text("مفيش بوب-أب اتمنع لسه", 13f, th.muted))
        for (b in blockedPops.take(12)) {
            val host = PopupGuard.hostOf(b.url).ifEmpty { b.url.take(40) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(4), 0, ui.dp(4)) }
            row.addView(ui.text("$host\n${b.why}", 12f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(ui.button("افتح") { allowHost(host); newTab(b.url, true, tab.id) }, LinearLayout.LayoutParams(ui.dp(76), ui.dp(40)))
            box.addView(row)
        }
        box.addView(ui.button("🧹 امسح المواقع اللي سمحت بيها") { prefs().edit().remove("pop_allow").apply(); Notice.show(this, "اتمسحت", 1800L) }, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(10) })
        sheet("🚫 مانع البوب-أب", box).show()
    }

    // ===== (v203) صفحة جديدة = اصطياد جديد =====
    private val NOISE = Regex("[?&](t|start|time_continue|feature|si|fbclid|pp|utm_[a-z]+)=[^&#]*", RegexOption.IGNORE_CASE)
    private fun newPage(t: BTab, url: String?) {
        val u = (url ?: return).substringBefore('#').replace(NOISE, "")
        if (u.isEmpty() || u.startsWith("data:") || u == t.capUrl) return
        if (panicUrl != null && t.id == panicTabId && !u.contains("google.")) { panicUrl = null; if (::undoB.isInitialized) undoB.visibility = View.GONE }   // (v231) مشيت لصفحة تانية: مفيش تراجع
        t.capUrl = u; t.gen++
        items.removeAll { it.tabId == t.id && it.gen != t.gen }
        keys.removeAll { it.startsWith("${t.id}:") && !it.startsWith("${t.id}:${t.gen}:") }
        if (t === tabs.getOrNull(curIdx)) { webGroups.clear(); autoShown = false; openKey = null; refreshBadge() }
    }
    /** اللقطات الحالية للتبويب اللي قدامك (صفحته الحالية بس) */
    private fun vis(): List<Found> { val t = tabs.getOrNull(curIdx) ?: return emptyList(); return items.filter { it.tabId == t.id && it.gen == t.gen } }
    private fun refreshBadge() {
        val n = groupsOf(vis()).size
        if (n == 0) { badge.visibility = View.GONE; hint.visibility = View.VISIBLE }
        else { badge.text = "🎬 لقيت $n فيديو — اضغط للاختيار"; badge.visibility = View.VISIBLE; hint.visibility = View.GONE }
        if (dlg?.isShowing == true) renderList()
    }

    // ===== (v203) تجميع الجودات: كل لينكات نفس الفيديو = اصطياد واحد =====
    private class Qv(val label: String, val h: Int, val f: Found, val audio: String?)
    private class Grp(val key: String, val title: String, val qs: List<Qv>)
    private var openKey: String? = null
    private var repairId: String? = null
    private var repairUrl = ""
    private var repairTitle = ""
    private var repairPending = false
    private val KIND_H = Regex("(\\d{3,4})p")

    private fun groupKey(f: Found): String {
        val k = Sniff.key(f.url)
        ytIds[k]?.let { return "yt:$it" }
        val gi = webGroups.indexOfFirst { g -> g.any { Sniff.key(it.first) == k } }
        if (gi >= 0) return "g:$gi"
        if (f.url.contains("videoplayback")) Regex("[?&]id=([^&]+)").find(f.url)?.let { return "gv:" + it.groupValues[1] }
        return if (f.kind == "HLS") "u:$k" else "s:" + f.ref.substringBefore('#') + "|" + Sniff.skeleton(f.url)
    }
    private fun hOf(f: Found): Int = KIND_H.find(f.kind)?.groupValues?.get(1)?.toIntOrNull()
        ?: webGroups.firstNotNullOfOrNull { g -> g.firstOrNull { Sniff.key(it.first) == Sniff.key(f.url) }?.second?.takeIf { it > 0 } }
        ?: Sniff.heightOf(f.url).let { if (it > 0) it else (hCache[Sniff.key(f.url)] ?: 0).coerceAtLeast(0) }   // (v533) لو مش معروف: اللي اتقاس بالفحص
    private fun nameFor(f: Found): String = titleFor(f).ifEmpty { Sniff.nameOf(f.url).substringBeforeLast('.') }

    /** (v231) نفس الاسم (من غير الامتداد) في نفس الصفحة = نفس الفيديو بجودة تانية، حتى لو الهيكل مختلف. الأسماء العامة (master/index/video…) مابتتحسبش */

    /** (v247) نفس الاسم الظاهر (عنوان الفيديو/الصفحة) في نفس الصفحة = اصطياد واحد بجودات، حتى لو أسماء الملفات في اللينكات مختلفة */

    private fun groupsOf(list: List<Found>): List<Grp> {
        // تجميع: اللينكات اللي ليها نفس مفتاح الهيكل/الفيديو أو نفس الاسم = مجموعة واحدة (union-find)
        val par = IntArray(list.size) { it }
        fun find(x: Int): Int { var a = x; while (par[a] != a) { par[a] = par[par[a]]; a = par[a] }; return a }
        val seenK = HashMap<String, Int>()
        fun link(k: String, i: Int) { val j = seenK[k]; if (j == null) seenK[k] = i else par[find(i)] = find(j) }
        val gk = list.map { groupKey(it) }
        list.forEachIndexed { i, f -> link(if (gk[i].startsWith("yt:")) gk[i] else "u:" + Sniff.key(f.url), i) }   // (v264) كل لينك = اصطياد لوحده (زي الأول)؛ يوتيوب بس بيتجمّع بالـ videoId
        val order = LinkedHashMap<Int, ArrayList<Int>>()
        for (i in list.indices.reversed()) order.getOrPut(find(i)) { ArrayList() }.add(i)
        val out = ArrayList<Grp>()
        for ((_, idx) in order) {
            val fs = idx.map { list[it] }
            val ks = idx.map { gk[it] }.distinct()
            val key = ks.firstOrNull { it.startsWith("yt:") } ?: ("u:" + Sniff.key(fs.first().url))
            val first = fs.first()
            val qs = ArrayList<Qv>()
            val pk = if (key.startsWith("yt:")) ytPicks[key.removePrefix("yt:")] else null
            if (pk != null && pk.opts.isNotEmpty()) {
                val id = key.removePrefix("yt:")
                for (o in pk.opts) {
                    val fo = Found(o.url, "YT " + o.h + "p", pk.title, "https://www.youtube.com/", pk.ua)
                    ytIds[Sniff.key(o.url)] = id
                    qs.add(Qv(o.label, o.h, fo, o.audio))
                }
            } else {
                val seen = HashSet<String>(); val seenH = HashSet<Int>()
                val sorted = fs.filter { seen.add(Sniff.key(it.url)) }.sortedByDescending { if (it.kind == "HLS") Int.MAX_VALUE else hOf(it) }
                for (f in sorted) {
                    val h = if (f.kind == "HLS") 0 else hOf(f)
                    if (h > 0 && !seenH.add(h)) continue       // (v231) جودة واحدة مرة واحدة (لينكين بنفس الارتفاع = نفس الجودة)
                    val lb = if (f.kind == "HLS") "HLS · " + ((hCache[Sniff.key(f.url)] ?: 0).takeIf { it > 0 }?.let { "تلقائي (لحد ${it}p)" } ?: "جودة تلقائية") else if (h > 0) Sniff.qLabel(h, qs.size + 1, f.size) else "جودة ${qs.size + 1} · " + f.kind + (if (f.size > 0) " · " + Sniff.fmtSize(f.size) else "")
                    qs.add(Qv(lb, h, f, null))
                }
            }
            out.add(Grp(key, nameFor(first), qs))
        }
        // (v557) نفس الاسم بأكتر من اصطياد: اللي مفيهوش أي جودة معروفة (تلقائية ساكتة / «جودة 1») بيختفي لو فيه اصطياد بنفس الاسم ليه جودات حقيقية
        // (أكتر من جودة، أو ارتفاع معروف، أو HLS «تلقائي (لحد Np)»). فيديو لوحده بجودة واحدة من غير اسم مكرّر بيفضل زي ما هو.
        fun rich(g: Grp) = g.qs.size > 1 || g.qs.any { it.h > 0 || (it.f.kind == "HLS" && (hCache[Sniff.key(it.f.url)] ?: 0) > 0) }
        fun nm(g: Grp) = g.title.trim().lowercase()
        val richNames = out.filter { nm(it).isNotEmpty() && rich(it) }.map { nm(it) }.toSet()
        return out.filter { rich(it) || nm(it).isEmpty() || nm(it) !in richNames }
    }

    /** (v203) اختيار تلقائي: نفس لينك/هيكل الفيديو القديم لو اتلقط، وإلا أحدث فيديو اتلقط بأقرب جودة للقديمة */
    private fun autoRepair() {
        if (dead || repairId == null || dlg?.isShowing == true) return
        val gs = groupsOf(vis()); if (gs.isEmpty()) return
        val all = gs.flatMap { it.qs }
        val oldKey = Sniff.key(repairUrl); val oldSkel = Sniff.skeleton(repairUrl); val oldH = Sniff.heightOf(repairUrl)
        fun dist(q: Qv) = Math.abs(q.h - oldH)
        val q = all.firstOrNull { Sniff.key(it.f.url) == oldKey }
            ?: all.filter { Sniff.skeleton(it.f.url) == oldSkel }.minByOrNull { dist(it) }
            ?: gs.first().qs.let { qs -> if (oldH > 0 && qs.any { it.h > 0 }) qs.minByOrNull { dist(it) } else qs.first() }
            ?: return
        applyRepair(q)
    }
    private fun applyRepair(q: Qv) {
        val id = repairId ?: return; repairId = null
        val f = q.f; val app = applicationContext
        val ck = try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        val pageNow = wvOrNull?.url ?: ""
        val ua = f.ua.ifEmpty { uaWeb }
        val ttl = repairTitle.ifEmpty { titleFor(f) }
        Thread {
            WebVideos.update(app, id) { it.copy(url = f.url, ref = f.ref.ifEmpty { it.ref }, ua = ua.ifEmpty { it.ua }, cookie = if (ck.isNotEmpty()) ck else it.cookie,
                page = if (WebIdent.specific(pageNow)) pageNow else it.page, ts = System.currentTimeMillis()) }
            WebIdent.bindLink(id, f.url, f.ref, ttl)
        }.apply { isDaemon = true }.start()
        Notice.show(this, "✓ اتحدّث لينك الفيديو — برجّعك للمشغّل", 2500L)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra("url", f.url); putExtra("ref", f.ref); putExtra("cookie", ck); putExtra("ua", ua)
            putExtra("title", ttl); putExtra("autotr", false); putExtra("forceid", id)
            if (q.audio != null) putExtra("aurl", q.audio)
        })
        finish()
    }
    private val szCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun szTxt(q: Qv): String { val n = szCache[q.f.url] ?: return ""; return if (n > 0) " · " + Sniff.fmtSize(n) else "" }
    private fun dlHeaders(f: Found): HashMap<String, String> {
        val m = HashMap<String, String>()
        val ua = f.ua.ifEmpty { uaWeb }; if (ua.isNotEmpty()) m["User-Agent"] = ua
        if (f.ref.isNotEmpty()) m["Referer"] = f.ref
        val ck = try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        if (ck.isNotEmpty()) m["Cookie"] = ck
        return m
    }
    private fun isHls(f: Found) = f.kind == "HLS" || f.url.contains(".m3u8", true)
    /** التحميل بيتحط في تبويب «المهام» (إيقاف مؤقت / استكمال / إلغاء) زي تحميل المشغّل */
    private fun queueDl(job: Downloader.Job) {
        val app = applicationContext
        TaskCenter.add(app, "download", (if (job.audioOnly) "🎧 " else "⬇ ") + job.name) { t ->
            job.ctl.cancelled = false; job.ctl.paused = false
            job.ctl.ext = { t.cancelled }
            t.onCancel = { job.ctl.cancelled = true }
            t.onPause = { p -> job.ctl.paused = p }
            val res = Downloader.run(app, job) { what, pct -> t.pct = pct.coerceIn(0, 99); t.msg = what; TaskCenter.changed() }
            if (!res.first) throw java.io.IOException(res.second)
            t.msg = ""; t.outPath = res.second
        }
        Notice.show(this, "⬇ اتضاف للتحميل — تابعه من تبويب «المهام»", 3000L)
    }
    private fun dlVideo(g: Grp, q: Qv) = queueDl(Downloader.Job(g.title + qTag(q), q.f.url, q.audio, isHls(q.f), q.h, dlHeaders(q.f)))
    private fun dlAudio(g: Grp) {
        // نفس منطق المشغّل: لو فيه صوت لوحده يتحمّل هو بس، وإلا بنسحب أصغر جودة ونستخرج منها الصوت
        val q = g.qs.firstOrNull { it.audio != null } ?: g.qs.filter { it.h > 0 && !isHls(it.f) }.minByOrNull { it.h } ?: g.qs.last()
        queueDl(Downloader.Job(g.title, q.f.url, q.audio, isHls(q.f), 1, dlHeaders(q.f), true))
    }

    private fun probe(f: Found, done: () -> Unit) {
        val ck = try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        Thread {
            try {
                val c = java.net.URL(f.url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "GET"; c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                c.setRequestProperty("Range", "bytes=0-0")
                c.setRequestProperty("User-Agent", f.ua.ifEmpty { uaWeb })
                if (f.ref.isNotEmpty()) c.setRequestProperty("Referer", f.ref)
                if (ck.isNotEmpty()) c.setRequestProperty("Cookie", ck)
                c.connect()
                val cr = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                f.size = cr ?: if (c.responseCode == 206) -1L else c.contentLengthLong
                f.mime = c.contentType ?: ""
                c.disconnect()
            } catch (e: Throwable) { LogStore.err("Browser:probe", e) }
            runOnUiThread { if (!dead) done() }
        }.apply { isDaemon = true }.start()
    }

    fun showList() {
        if (dlg == null) {
            val lb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val st = ui.text("", 13f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) }
            listBox = lb; statusTv = st
            dlg = ui.sheet(this, "🎬 صيد الفيديو", listOf<View>(st, lb), false)
        }
        renderList(); dlg?.show()
    }
    private fun renderList() {
        val box = listBox ?: return
        box.removeAllViews()
        val gs = groupsOf(vis())
        statusTv?.text = if (gs.isEmpty()) "⏳ بدوّر على فيديوهات… شغّل الفيديو في الصفحة لو ما ظهرش" else "اختار الفيديو اللي عايزه (${gs.size})"
        if (repairId == null) for (g in gs) for (q in g.qs) if (q.h <= 0 && q.audio == null && !q.f.kind.startsWith("YT")) probeHeight(q.f)   // (v533)
        for (g in gs) box.addView(groupCard(g))
    }
    // (v533) الجودة الحقيقية للينكات اللي مفيهاش رقم جودة: HLS ← أعلى RESOLUTION في الماستر · ملف عادي ← MediaMetadataRetriever (بالهيدرز). بيتحفظ والقايمة بتتحدّث
    private val hCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val hBusy: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private fun probeHeight(f: Found) {
        val k = Sniff.key(f.url)
        if (hCache.containsKey(k) || !hBusy.add(k)) return
        val hs = dlHeaders(f); val u = f.url; val hls = isHls(f)
        Thread {
            var h = -1
            try {
                if (hls) {
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = true
                    for ((a, b) in hs) c.setRequestProperty(a, b)
                    val txt = try { c.inputStream.bufferedReader().use { it.readText().take(300000) } } finally { c.disconnect() }
                    h = Regex("RESOLUTION=(\\d+)x(\\d+)").findAll(txt).mapNotNull { m -> minOf(m.groupValues[1].toInt(), m.groupValues[2].toInt()).takeIf { it > 0 } }.maxOrNull() ?: -1
                } else {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(u, hs)
                        val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        val hh = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        if (w > 0 && hh > 0) h = minOf(w, hh)   // الضلع الأقصر = رقم الجودة (فيديو طولي 720x1280 = 720p)
                    } finally { try { r.release() } catch (_: Throwable) {} }
                }
            } catch (e: Throwable) { LogStore.err("Browser:probeH", e) }
            hCache[k] = h
            if (h > 0) runOnUiThread { if (!dead && dlg?.isShowing == true) renderList() }
        }.apply { isDaemon = true }.start()
    }
    private val selQ = HashMap<String, Int>()   // (v231) الجودة المختارة لكل اصطياد (مفتاح المجموعة ← رقم الجودة)

    /** (v231) اصطياد واحد لكل فيديو: العنوان + اختيار الجودة (لو أكتر من جودة) + زرار أزرق كبير «🌐 شاهد وترجم» وتحته ▶ فرجة · ⬇ تحميل (بحجمه) · 📋 نسخ · 🎧 صوت */
    private fun groupCard(g: Grp): View {
        val c = ui.card()
        c.addView(ui.text(g.title, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setOnLongClickListener { g.qs.getOrNull((selQ[g.key] ?: 0).coerceIn(0, g.qs.size - 1))?.let { copyUrl(it.f.url) }; true } })
        if (repairId != null) {   // وضع إصلاح اللينك: اختيار الجودة بس
            if (g.qs.size > 1) c.addView(ui.text("🎞 ${g.qs.size} جودات", 13f, th.primary, true))
            for (q in g.qs) c.addView(ui.button("🔧 استخدم " + q.label, true) { dlg?.dismiss(); applyRepair(q) })
            return c
        }
        val cur = (selQ[g.key] ?: 0).coerceIn(0, g.qs.size - 1)
        val q = g.qs[cur]
        if (g.qs.size > 1) {
            c.addView(ui.text("🎞 ${g.qs.size} جودات — اختار الجودة", 12f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(2)) })
            val labels = g.qs.mapIndexed { i, x -> if (x.h > 0) x.h.toString() + "p" else if (isHls(x.f)) "تلقائي" else "جودة " + (i + 1) }
            c.addView(ui.chips(labels, { labels[cur] }) { pick -> val ni = labels.indexOf(pick); if (ni >= 0 && ni != cur) { selQ[g.key] = ni; renderList() } })
        } else {
            c.addView(ui.text(q.label, 13f, th.primary, true))
        }
        // (v533) 4 أزرار بس: ترجمة (لونها الأساسي) + مشاهدة فقط · مشاهدة ع التلفزيون · تحميل — الثلاثة بنفس الأزرق. ضغطة طويلة على الاسم = نسخ اللينك · على «تحميل» = صوت بس (M4A)
        c.addView(ui.button("🌐 ترجمة", true) { dlg?.dismiss(); openPlayer(q.f, false, q.audio) })
        fun blue(t: String, f: () -> Unit) = ui.button(t) { f() }.apply { setTextColor(Color.WHITE); background = ui.box(BLUE, BLUE, 12) }
        c.addView(blue("▶ مشاهدة فقط") { dlg?.dismiss(); openPlayer(q.f, true, q.audio) })
        c.addView(blue("📺 مشاهدة ع التلفزيون") { dlg?.dismiss(); sendToTv(g, q) })
        val hasSz = q.label.contains("MB") || q.label.contains("GB")
        val dlB = blue("⬇ تحميل" + (if (hasSz) "" else szTxt(q))) { dlg?.dismiss(); dlVideo(g, q) }
        dlB.setOnLongClickListener { dlg?.dismiss(); dlAudio(g); true }
        c.addView(dlB)
        if (!hasSz && !isHls(q.f) && szCache[q.f.url] == null) {   // الحجم مش معروف: نسأل السيرفر ونحدّث الزرار
            szCache[q.f.url] = -2L
            val hs2 = dlHeaders(q.f); val u1 = q.f.url; val u2 = q.audio
            Thread {
                var n = try { Downloader.sizeOf(u1, hs2) } catch (_: Throwable) { -1L }
                if (n > 0 && u2 != null) { val a = try { Downloader.sizeOf(u2, hs2) } catch (_: Throwable) { -1L }; if (a > 0) n += a }
                szCache[u1] = n
                runOnUiThread { if (!dead && n > 0) dlB.text = "⬇ تحميل · " + Sniff.fmtSize(n) }
            }.apply { isDaemon = true }.start()
        }
        return c
    }
    private val BLUE = 0xFF1E6FE0.toInt()
    /** (v533) علامة الجودة اللي بتتضاف لاسم التحميل/التشغيل عشان نفس الفيديو بجودات مختلفة مايبقوش بنفس الاسم */
    private fun qTag(q: Qv): String {
        val h = if (q.h > 0) q.h else (hCache[Sniff.key(q.f.url)] ?: 0)
        return if (h > 0) " [${h}p]" else if (isHls(q.f)) " [HLS]" else ""
    }
    /** (v533) «مشاهدة ع التلفزيون»: يوتيوب ← لينك الفيديو · غيره ← اللينك المباشر ومعاه Referer/UA/Cookie الصفحة */
    private fun sendToTv(g: Grp, q: Qv) {
        try { RemoteClient.init(this) } catch (_: Throwable) {}
        if (RemoteClient.lastTv() == null) { Notice.show(this, "📺 مفيش تلفزيون مقترن — اقترن من «الريموت» الأول", 3200L); return }
        val f = q.f
        val isYt = f.kind.startsWith("YT") || f.url.contains("googlevideo") || f.url.contains("videoplayback")
        val yid = ytIds[Sniff.key(f.url)] ?: YtExtract.videoId(f.ref) ?: (if (isYt) YtExtract.videoId(wvOrNull?.url ?: "") else null)
        val url = if (yid != null) "https://www.youtube.com/watch?v=$yid" else f.url
        val title = g.title + qTag(q)
        val ref = if (yid != null) "" else f.ref
        val ua = if (yid != null) "" else f.ua.ifEmpty { uaWeb }
        val ck = if (yid != null) "" else try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        Notice.show(this, "📺 بوصّل بالتلفزيون…", 2500L)
        Thread {
            var msg: String
            try {
                msg = if (!RemoteClient.ensureConnected(this, 8000L)) "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else (RemoteClient.openLink(url, title, "video", ref, ua, ck)?.let { "⚠ $it" } ?: "✅ اتبعت للتلفزيون")
            } catch (e: Throwable) { LogStore.err("Browser:sendToTv", e); msg = "⚠ حصلت مشكلة" }
            runOnUiThread {
                if (dead) return@runOnUiThread
                Notice.show(this, msg, 2600L)
                // (v554) مفيش شاشة تحكم على الموبايل بعد الإرسال
            }
        }.apply { isDaemon = true }.start()
    }

    /** (v533) الصفحة الحالية تتبعت للتلفزيون وتتفتح هناك إجباري (حتى لو فيه فيديو شغّال: التلفزيون بيقفله ويفتح الجديد) */
    private fun sendPageToTv() {
        val u = wvOrNull?.url ?: ""
        if (!u.startsWith("http", true)) { Notice.show(this, "مفيش صفحة تتبعت للتلفزيون", 2200L); return }
        try { RemoteClient.init(this) } catch (_: Throwable) {}
        if (RemoteClient.lastTv() == null) { Notice.show(this, "📺 مفيش تلفزيون مقترن — اقترن من «الريموت» الأول", 3200L); return }
        Links.sendToTv(this, u)
    }

    private fun leaveCustom() {
        val v = customView ?: return
        customBox.removeView(v); customBox.visibility = View.GONE; content.visibility = View.VISIBLE
        customView = null; try { customCb?.onCustomViewHidden() } catch (e: Throwable) { LogStore.err("Browser:custom", e) }; customCb = null
        if (oldOrient != -1) requestedOrientation = oldOrient
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        applyBars(th)
    }
    private fun vjs(body: String, toast: Boolean = true) {
        val wv = wvOrNull ?: return
        val js = "(function(){var vs=[].slice.call(document.querySelectorAll('video'));vs.sort(function(a,b){return b.videoWidth*b.videoHeight-a.videoWidth*a.videoHeight});" +
            "var v=vs[0];if(!v)return 'مفيش فيديو';var s=window.__sv=window.__sv||{fit:0,z:1,sp:1};$body})()"
        wv.evaluateJavascript(js) { r ->
            if (!toast) return@evaluateJavascript
            val t = try { JSONTokener(r ?: "").nextValue() as? String ?: "" } catch (_: Throwable) { "" }
            if (t.isNotEmpty()) Notice.show(this, t, 2300L)
        }
    }
    /** (v231) الشريط الجانبي القديم اتشال: كل أزراره (تقديم/تأخير/تشغيل/ملاءمة/تكبير/سرعة) بقت في ⋮ ← «🎞 تحكم الفيديو»، وفضل زرار مانع الإعلانات بس */
    private fun buildControls(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) }
        shield = IconTextView(this).apply {
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            background = ui.box(0xB0000000.toInt(), 0x66FFFFFF, 10)
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), -2).apply { setMargins(0, ui.dp(3), 0, ui.dp(3)) }
            setOnLongClickListener { showPopupSheet(); true }   // (v401) ضغطة طويلة = مانع البوب-أب الذكي
            setOnClickListener {
                adOn = !adOn; try { prefs().edit().putBoolean("adblock", adOn).apply() } catch (e: Exception) { LogStore.err("Browser:ad", e) }
                shield.text = if (adOn) "🛡 $blockedN" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f
                Notice.show(this@BrowserActivity, if (adOn) "مانع الإعلانات شغال" else "مانع الإعلانات اتقفل", 2300L)
                try { wvOrNull?.reload() } catch (e: Throwable) { LogStore.err("Browser:reload", e) }
            }
        }
        shield.text = if (adOn) "🛡" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f
        col.addView(shield)
        return col
    }

    fun openPlayer(f: Found, noSub: Boolean, audio: String? = null) { if (noSub) openPlayerNow(f, true, audio) else ensureKeys { openPlayerNow(f, false, audio) } }
    fun openPlayerNow(f: Found, noSub: Boolean, audio: String? = null) {
        // (v117) فيديو يوتيوب: بنعرف معرّفه (من لينك الصفحة) عشان يتسجل في بوابة يوتيوب ويتحفظ تقدمه وترجمته على معرّفه
        val isYtStream = f.kind.startsWith("YT") || f.url.contains("googlevideo") || f.url.contains("videoplayback")
        val yid = ytIds[Sniff.key(f.url)] ?: YtExtract.videoId(f.ref) ?: (if (isYtStream) YtExtract.videoId(wvOrNull?.url ?: "") else null) ?: ""
        // (v543 م4) وضع التلفزيون: يوتيوب ← ytid بس (التلفزيون يستخرج بنفسه)، وغير كده ← اللينك المصطاد بالهيدرز
        val tvCookie = try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" }
        if (PlayTarget.open(this, MediaSpec(
                url = if (yid.isEmpty()) f.url else null, ytid = yid.ifEmpty { null },
                headers = mapOf("ref" to f.ref, "ua" to f.ua.ifEmpty { uaWeb }, "cookie" to tvCookie),
                title = titleFor(f), how = if (yid.isEmpty() || noSub) Links.VIDEO else Links.VIDEO_TR))) return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            if (yid.isNotEmpty()) {
                putExtra("ytid", yid)
                ytPicks[yid]?.let { pk ->
                    val o = pk.opts.firstOrNull { it.url == f.url }
                    if (pk.url == f.url || o != null) { putExtra("aurl", audio ?: o?.audio ?: (if (pk.url == f.url) pk.audio else null) ?: ""); putExtra("qlist", pk.optsJson()) }
                }
            }
            if (yid.isEmpty()) try { webQlist(f)?.let { putExtra("qlist", it) } } catch (e: Throwable) { LogStore.err("Browser:wq", e) }
            putExtra("url", f.url); putExtra("ref", f.ref)
            putExtra("cookie", try { CookieManager.getInstance().getCookie(f.url) ?: "" } catch (_: Throwable) { "" })
            putExtra("ua", f.ua.ifEmpty { uaWeb }); putExtra("title", titleFor(f)); putExtra("nosub", noSub); putExtra("autotr", !noSub); putExtra("incognito", tabs.getOrNull(curIdx)?.let { it.priv && !it.auto } == true); putExtra("hsite", tabs.getOrNull(curIdx)?.let { SiteBlock.keyOf(it.url)?.let { k -> SiteBlock.has(this@BrowserActivity, k) } } == true); putExtra("ret", "browser")
        })
    }

    // ===== (v480 م6) المتصفح على التلفزيون: مؤشر / تنقل بالتركيز / شريط العنوان =====
    // الصفحة (مؤشر): أسهم = حركة، OK = دوسة، OK طويلة = تبديل لوضع التركيز، عند الحافة = سكرول (ضغطتين على حافة فوق/تحت من غير سكرول = الشريط/زرار الفيديو).
    // Menu = شريط العنوان والتبويبات (وMenu تاني أو Back أو ↓ = رجوع للصفحة). شاشة كاملة: OK = تشغيل/إيقاف، ←/→ = ±10ث.
    private fun isIn(v: View?, root: View?): Boolean {
        if (root == null) return false
        var p: View? = v
        while (p != null) { if (p === root) return true; p = p.parent as? View }
        return false
    }

    private fun tvToPage() {
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(frame.windowToken, 0)
            wvOrNull?.requestFocus()
        } catch (_: Throwable) {}
    }

    private fun tvToToolbar() { try { addr.requestFocus() } catch (_: Throwable) {} }

    /** زرار «لقيت فيديو» أو شريط «تحميل؟» لو ظاهر */
    private fun tvToBottomBar(): Boolean = try {
        if (badge.visibility == View.VISIBLE) badge.requestFocus()
        else if (::dlBar.isInitialized && dlBar.visibility == View.VISIBLE) dlBar.requestFocus()
        else false
    } catch (_: Throwable) { false }

    private fun tvToggleMode() {
        tvPointerMode = !tvPointerMode
        Cfg.put("tv_br_mode", if (tvPointerMode) "ptr" else "focus")
        tvPtr?.enabled = tvPointerMode
        tvModeB?.text = if (tvPointerMode) "🖱" else "🎯"
        Notice.show(this, if (tvPointerMode) "🖱 وضع المؤشر — الأسهم بتحرّك المؤشر · OK = دوسة" else "🎯 وضع التركيز — الأسهم بتروح للصفحة نفسها", 2400L)
        tvToPage()
    }

    private fun tvSeekStep(rep: Int): Int = 10 * (1 + minOf(rep, 20) / 5)

    /** فيديو HTML5 ملء الشاشة: OK تشغيل/إيقاف، ←/→ ±10ث (والضغط المستمر أسرع) */
    private fun tvFullKey(ev: android.view.KeyEvent): Boolean {
        val down = ev.action == android.view.KeyEvent.ACTION_DOWN
        val js: String? = when (ev.keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if (down && ev.repeatCount == 0) TvPointer.jsToggle() else null
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_MEDIA_REWIND ->
                if (down) TvPointer.jsSeek(-tvSeekStep(ev.repeatCount)) else null
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                if (down) TvPointer.jsSeek(tvSeekStep(ev.repeatCount)) else null
            else -> return false
        }
        if (js != null) wvOrNull?.evaluateJavascript(js, null)
        return true
    }

    private fun tvOk(ev: android.view.KeyEvent): Boolean {
        if (ev.action == android.view.KeyEvent.ACTION_DOWN) {
            if (ev.repeatCount == 0) tvOkLong = false
            else if (ev.repeatCount == 1 && !tvOkLong) { tvOkLong = true; tvToggleMode() }
            return true
        }
        if (tvOkLong) { tvOkLong = false; return true }
        tvPtr?.click()
        return true
    }

    private fun tvKey(ev: android.view.KeyEvent): Boolean {
        if (!::frame.isInitialized) return false
        if (customView != null) return tvFullKey(ev)
        val kc = ev.keyCode
        val down = ev.action == android.view.KeyEvent.ACTION_DOWN
        val f = currentFocus
        val inBar = isIn(f, tvBar) || (::findBar.isInitialized && isIn(f, findBar))
        val inPage = f == null || isIn(f, webHolder)

        if (kc == android.view.KeyEvent.KEYCODE_MENU) {
            if (down && ev.repeatCount == 0) { if (inPage) tvToToolbar() else tvToPage() }
            return true
        }
        if (inBar) {
            if (kc == android.view.KeyEvent.KEYCODE_BACK) { if (down) tvToPage(); return true }
            val barToFind = isIn(f, tvBar) && ::findBar.isInitialized && findBar.visibility == View.VISIBLE
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN && !barToFind) { if (down) tvToPage(); return true }
            return false
        }
        if (!inPage) {
            if (kc == android.view.KeyEvent.KEYCODE_BACK) {
                if (down) { if (::dlBar.isInitialized && dlBar.visibility == View.VISIBLE && isIn(f, dlBar)) hideDl(); tvToPage() }
                return true
            }
            if (kc == android.view.KeyEvent.KEYCODE_DPAD_UP && (f === badge || (::dlBar.isInitialized && isIn(f, dlBar)))) { if (down) tvToPage(); return true }
            return false
        }
        // الصفحة نفسها (الوضعين): مفاتيح الميديا والصفحة
        when (kc) {
            android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (down && ev.repeatCount == 0) wvOrNull?.evaluateJavascript(TvPointer.jsToggle(), null); return true
            }
            android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) wvOrNull?.evaluateJavascript(TvPointer.jsSeek(-tvSeekStep(ev.repeatCount)), null); return true }
            android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) wvOrNull?.evaluateJavascript(TvPointer.jsSeek(tvSeekStep(ev.repeatCount)), null); return true }
            android.view.KeyEvent.KEYCODE_CHANNEL_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> { if (down) tvPtr?.page(wvOrNull, false); return true }
            android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) tvPtr?.page(wvOrNull, true); return true }
        }
        if (!tvPointerMode) return false   // وضع التركيز: الأسهم وOK للصفحة نفسها
        return when (kc) {
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) tvPtr?.move(kc, ev.repeatCount, wvOrNull); true }
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> tvOk(ev)
            else -> false
        }
    }

    override fun dispatchKeyEvent(ev: android.view.KeyEvent): Boolean {
        TvKill.watch(this, ev)   // (v486) 0 لمدة 5 ثواني = إلغاء تثبيت
        if (tvOn && !dead) { try { if (tvKey(ev)) return true } catch (e: Throwable) { LogStore.err("Browser:tvKey", e) } }
        return super.dispatchKeyEvent(ev)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { leaveCustom(); return }
        if (::dlBar.isInitialized && dlBar.visibility == View.VISIBLE) { hideDl(); return }   // (v324)
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        val w = wvOrNull
        if (w != null && w.canGoBack()) { w.goBack(); return }
        // تبويب اتفتح من تبويب تاني: الرجوع يقفله ويرجّعك للأصلي
        if (tabs.size > 1 && tab.opener >= 0 && tabs.any { it.id == tab.opener }) { closeTab(curIdx); return }
        persistTabs()
        super.onBackPressed()
    }
    // (v149) أول ما تخرج من المتصفح: كل الفيديو/الصوت في كل التبويبات يقف (من غير ما يكمّل في الخلفية)، وكل التبويبات تتجمّد
    override fun onPause() { persistTabs(); WebMute.pauseAll(); for (t in tabs) try { t.wv?.onPause() } catch (_: Throwable) {}; super.onPause() }
    override fun onResume() { super.onResume(); curRef = java.lang.ref.WeakReference(this); try { wvOrNull?.onResume() } catch (_: Throwable) {} }
    override fun onDestroy() {
        try { dockUi?.detach() } catch (_: Throwable) {}
        try { stripUi?.detach() } catch (_: Throwable) {}

        dead = true; h.removeCallbacksAndMessages(null)
        if (curRef?.get() === this) curRef = null
        try { tasksFabUi?.detach() } catch (_: Throwable) {}
        try { dlg?.dismiss() } catch (e: Throwable) { LogStore.err("Browser:d1", e) }
        try { tabsDlg?.dismiss() } catch (e: Throwable) { LogStore.err("Browser:d2", e) }
        for (t in tabs) try { t.wv?.destroy() } catch (e: Throwable) { LogStore.err("Browser:d3", e) }
        super.onDestroy()
    }
}
