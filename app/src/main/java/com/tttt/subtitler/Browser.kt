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
import android.view.MotionEvent
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
import android.widget.HorizontalScrollView
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

    class SavedTab(val url: String, val title: String, val desktop: Boolean, val state: String = "")
    /** (v820) حالة الـWebView (تاريخ الرجوع/التقدّم + مكان السكرول) كنص — عشان الرجوع جوه المتصفح يفضل شغّال حتى بعد ما المتصفح يتقفل ويتفتح تاني (كان بيتحفظ الرابط بس) */
    fun packState(b: android.os.Bundle?): String {
        if (b == null) return ""
        return try {
            val p = android.os.Parcel.obtain()
            try { b.writeToParcel(p, 0); val by = p.marshall(); if (by.size > 400_000) "" else android.util.Base64.encodeToString(by, android.util.Base64.NO_WRAP) } finally { p.recycle() }
        } catch (_: Throwable) { "" }
    }
    fun unpackState(s: String): android.os.Bundle? {
        if (s.isBlank()) return null
        return try {
            val by = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)
            val p = android.os.Parcel.obtain()
            try { p.unmarshall(by, 0, by.size); p.setDataPosition(0); android.os.Bundle.CREATOR.createFromParcel(p) } finally { p.recycle() }
        } catch (_: Throwable) { null }
    }
    fun saveTabs(c: Context, tabs: List<SavedTab>, cur: Int) {
        try {
            val a = JSONArray()
            for (t in tabs) a.put(JSONObject().put("u", t.url).put("t", t.title).put("k", t.desktop).put("s", t.state))
            java.io.File(c.filesDir, "br_tabs.json").writeText(JSONObject().put("cur", cur).put("tabs", a).toString())
        } catch (e: Exception) { LogStore.err("BrowserStore.tabs", e) }
    }
    fun loadTabs(c: Context): Pair<List<SavedTab>, Int> {
        val out = ArrayList<SavedTab>(); var cur = 0
        try {
            val o = JSONObject(java.io.File(c.filesDir, "br_tabs.json").readText())
            cur = o.optInt("cur", 0); val a = o.getJSONArray("tabs")
            for (i in 0 until a.length()) { val t = a.getJSONObject(i); out.add(SavedTab(t.getString("u"), t.optString("t"), t.optBoolean("k", false), t.optString("s", ""))) }
        } catch (_: Exception) {}
        return out to cur
    }
}

class BTab(val id: Long) {
    var wv: WebView? = null
    @Volatile var url = ""
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
    var frozen: android.os.Bundle? = null   // (v783) تبويب خلفي مجمّد: حالة الـWebView محفوظة والـWebView نفسه اتمسح (رام أقل)
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
            "toggle" -> runJs("window.__jbT=Date.now();" + TvPointer.jsToggle())   // (v783) حارس التشغيل التلقائي يعرف إنه أمر منك
            "seek" -> runJs("window.__jbT=Date.now();" + TvPointer.jsSeek((v / 1000L).toInt()))
            "seekto" -> runJs("(function(){" + RC_FIND + "v.currentTime=" + (v / 1000.0) + "})()")
            "speed" -> runJs("(function(){" + RC_FIND + "var r=[0.5,0.75,1,1.25,1.5,2];var i=r.indexOf(v.playbackRate);v.playbackRate=r[i<0?3:(i+1)%r.length]})()")
            "next" -> runJs("(function(){var b=document.querySelector('.ytp-next-button,[aria-label=\"Next\"],[aria-label=\"التالي\"]');if(b)b.click()})()")
            "prev" -> runJs("(function(){var b=document.querySelector('.ytp-prev-button');if(b&&b.getAttribute('aria-disabled')!='true'){b.click();return}" + RC_FIND + "v.currentTime=0})()")
            "close", "exit" -> {
                runJs("(function(){" + RC_FIND + "v.pause()})()")
                runOnUiThread {
                    try {
                        leaveCustom()
                    } catch (_: Throwable) {
                    }
                    try {
                        WebMute.pauseAll()
                    } catch (_: Throwable) {
                    }
                    try {
                        finish()
                    } catch (_: Throwable) {
                    }
                }
            }
            else -> {}
        }
        return true
    }

    // (v507) ريزوليوشن ديناميك لوضع التلفزيون
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(TvScale.wrap(newBase)) }
    private var stripUi: StatusStrip? = null   // (v345)
    private var dockUi: MiniDock? = null   // (v344)
    override fun finish() { super.finish(); try { if (!NavAnim.take(this)) overridePendingTransition(R.anim.act_stay, R.anim.act_exit) } catch (e: Exception) { LogStore.err("Browser:finish", e) } }

    private lateinit var vidMark: VidMark   // (v717) إطار أزرق حوالين مربع الفيديو في الصفحة + كبسولة «اتلقط · دوس للخيارات»
    private var badgeTrash: DragTrash? = null   // (v736) سلة ✕ لدايرة الاصطياد
    lateinit var badge: ClipOrb   // (v716) دايرة الاصطياد: نفس شكل دايرة الكليبورد (حلقة + نقطة بتلف)
    // (v324) شريط «تحميل؟» اللي بيظهر تحت الصفحة (زي شريط «لقيت فيديو»): تحميل · إلغاء، بيختفي بعد 9 ثواني (كان 6) أو بلمسة برّاه = مش عايز أحمّل
    private lateinit var dlBar: LinearLayout
    private lateinit var dlTxt: IconTextView
    private lateinit var dlLine: View
    private var dlHide: Runnable? = null
    private var dlAct: (() -> Unit)? = null
    private val DL_PROMPT_MS = 9000L
    private var tasksFabUi: TasksFab? = null
    private lateinit var saver: BrowserSaver   // (v783) توفير الداتا + الكبسولة + تجميد التبويبات + أوفلاين
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
    private var actStrip: FrameLayout? = null               // (v808) طبقة شفافة فوق أسفل الصفحة (موبايل بس): الصف التحتاني + زرار الأدوات
    private lateinit var toolsHost: LinearLayout        // (v808) عمود الأدوات + زرار ☰ (لـ TvModeFab يحسب مكان الكبسولة)
    private var toolsRelayout: (() -> Unit)? = null     // (v823) بيرتّب عمود الأدوات رأسي (بورتريه) أو أفقي (لاندسكيب)
    private lateinit var toolsInner: LinearLayout        // (v801) صف الأدوات جوه الشريط (🕶 ① 🕘 🛡 🔄 + ألسنة التلفزيون)
    private lateinit var navBackB: TextView
    private var topBarV: LinearLayout? = null            // (v848) هيدر المتصفح (لتلوينه في الوضع المخفي)
    private var gatesNav: BottomNav? = null              // (v848) الفوتر (لتلوينه في الوضع المخفي)
    private val barBtns = ArrayList<TextView>()          // (v848) أزرار الهيدر اللي لونها بيتغيّر
    private var privLook = false
    private val PRIV_BG = 0xFF2A0F3D.toInt()             // بنفسجي غامق (باذنجاني)
    private lateinit var navFwdB: TextView
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

    private fun titleFor(f: Found): String = f.title.ifEmpty { Sniff.cleanTitle(pageTitles[f.page.ifEmpty { f.ref }] ?: if (f.page.ifEmpty { f.ref } == wvOrNull?.url) wvOrNull?.title else null) }

    private val JS = "(function(){var o=[];function a(x){try{if(x)o.push(new URL(x,location.href).href)}catch(e){}}" +
        "function ad(v){var e=v;for(var i=0;e&&e.nodeType===1&&i<7;i++,e=e.parentElement){var c=(typeof e.className==='string'?e.className:'')+' '+(e.id||'');if(/(?:^|[^A-Za-z0-9])(?:ads?|adv|advert|advertisement|adslot|adbox|preroll|vast|vpaid|sponsored)(?![A-Za-z0-9])/i.test(c))return true}return false}" +
        "document.querySelectorAll('video,source').forEach(function(v){if(ad(v))return;a(v.currentSrc);a(v.src)});" +
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
            for (x in vis()) if (x.page.ifEmpty { x.ref } == f.page.ifEmpty { f.ref } && x.kind != "HLS" && Sniff.skeleton(x.url) == sk) raw.add(Triple(x.url, Sniff.heightOf(x.url), x.size))
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
        NetLedger.start(this); NetLog.start(this); NetGate.apply(this)
        super.onCreate(b)
        Cfg.ensure(this)   // (v310) المتصفح ممكن يتفتح من أيقونة اللانشر قبل Main
        DeviceUi.applyOrientation(this)   // (v494) وضع التلفزيون/التابلت على موبايل = لاندسكيب تلقائي
        th = Themes.current()
        ui = Ui(this, th)
        applyBars(th)
        TvFocus.attach(this, th.primary)   // (v477)
        adOn = try { prefs().getBoolean("adblock", true) } catch (_: Exception) { true }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); if (tvOn) setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(8)) else setPadding(0, ui.dp(28), 0, 0) }
        // (v517) تلفزيون: أزرار الشريط أكبر (52dp) عشان التركيز بالريموت يبان
        fun tbtn(t: String, size: Float = 20f, f: (View) -> Unit): TextView = IconTextView(this).apply {
            text = t; textSize = if (tvOn) size * 1.25f else size; gravity = Gravity.CENTER; setTextColor(th.text)
            layoutParams = LinearLayout.LayoutParams(ui.dp(if (tvOn) 54 else 38), ui.dp(if (tvOn) 52 else 40)); setOnClickListener { f(it) }
            if (tvOn) background = TvBlur.frostDrawable(12f, resources.displayMetrics.density)
        }
        backB = tbtn("‹", 26f) { wvOrNull?.let { if (it.canGoBack()) saver.back(it, tab) } }   // (v783) رجوع سريع من الكاش
        fwdB = tbtn("›", 26f) { wvOrNull?.let { if (it.canGoForward()) it.goForward() } }
        addr = ui.input("رابط الموقع / كلمة بحث", "").apply {
            layoutParams = LinearLayout.LayoutParams(0, ui.dp(if (tvOn) 52 else 40), 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
            layoutDirection = View.LAYOUT_DIRECTION_LTR; setSelectAllOnFocus(true); textSize = if (tvOn) 17f else 13f
            imeOptions = EditorInfo.IME_ACTION_GO; inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        reloadB = tbtn("🔄", 18f) { val w = wvOrNull ?: return@tbtn; if (tab.progress < 100) w.stopLoading() else w.reload() }
        tabsB = IconTextView(this).apply {
            text = "1"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(th.text); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(0x00000000, th.text, 6); layoutParams = LinearLayout.LayoutParams(ui.dp(if (tvOn) 38 else 28), ui.dp(if (tvOn) 38 else 28)).apply { setMargins(ui.dp(5), ui.dp(if (tvOn) 7 else 6), ui.dp(5), ui.dp(6)) }
            setOnClickListener { showTabs() }
        }
        val menuB = tbtn("⋮", 22f) { showMenu(it) }
        barBtns.add(menuB)
        val bar = LinearLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(2), 0, ui.dp(2), 0)
            if (tvOn) { addView(backB); addView(fwdB) }   /* (v823) الموبايل: ‹ › في الشريط السفلي بس (كانوا متكررين فوق وتحت) */
            addView(addr); addView(reloadB); if (!tvOn) addView(tabsB); addView(menuB)
        }
        topBarV = bar; barBtns.add(reloadB)
        if (tvOn) {   // (v480 م6) زرار تبديل الوضع: 🖱 مؤشر / 🎯 تنقل بالتركيز
            tvPointerMode = Cfg.str("tv_br_mode", "ptr") != "focus"
            val mb = tbtn(if (tvPointerMode) "🖱" else "🎯", 18f) { tvToggleMode() }
            tvModeB = mb
            bar.addView(mb, 4)
            // (v517) 🏠 رجوع لشاشة التلفزيون (بدل الشريط السفلي اللي اتشال) — أول زرار في الشريط
            bar.addView(tbtn("🏠", 18f) { persistTabs(); if (!TvFocus.goHome(this)) finish() }, 0)
            val quickNames = arrayOf("▶ يوتيوب", "🔍 جوجل", "📰 أخبار جوجل")
            val quickUrls = arrayOf("https://www.youtube.com", "https://www.google.com", "https://news.google.com")
            bar.addView(tbtn("⭐", 18f) {
                GAlert(this)
                    .setTitle("⭐ مواقع سريعة")
                    .setItems(quickNames) { _, i ->
                        load(quickUrls[i])
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }, 1)
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

        hint = ui.noHint().apply { setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(4)) }
        webHolder = FrameLayout(this)
        // (v594) علامة الاصطياد بقت كبسولة بتطلع من الجنب الشمال بدل الشريط السفلي؛ دوسة عليها بتفتح نافذة عايمة صغيرة
        // (v716) دايرة الاصطياد بنفس شكل/أنيميشن دايرة لينك الكليبورد: 🔍 وهي بتدوّر (بعد ما تدوس في الصفحة) ← 🎬 أخضر لما يتلقط فيديو؛
        // دوسة = لوحة الأزرار (تلفزيون · تحميل · شاهد وترجم · شاهد · إلغاء)، ضغطة طويلة = قايمة الاصطياد
        badge = ClipOrb(this).apply {
            visibility = View.GONE; accent = th.primary; elevation = ui.dp(6).toFloat()
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.15f else 1f).scaleY(if (f) 1.15f else 1f).setDuration(120).start() }
            setOnClickListener { badgeTap() }
            setOnLongClickListener { if (hasExtras()) showAutoResults() else showList(); true }   // (v975) showList نفسها بترجع إشعار لو مفيش فيديو (موبايل)   // (v838) ضغطة طويلة: لو فيه ملفات/لينكات حقيقية ← نتيجة الفحص (فيها زرار الفيديوهات)
            // (v736) الدايرة تتسحب لأي مكان، وسيبها على ✕ تحت = تتخبّى (بترجع لوحدها لما يتلقط فيديو جديد)
            // (v801) على الموبايل الدايرة ثابتة في نص الشريط اللي فوق الشريط السفلي — مفيش سحب (التلفزيون زي ما هو)
            if (tvOn) setOnTouchListener(object : View.OnTouchListener {
                var dx = 0f; var dy = 0f; var sx = 0f; var sy = 0f; var drag = false
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = v.translationX; sy = v.translationY; drag = false }
                        MotionEvent.ACTION_MOVE -> {
                            if (!drag && Math.abs(e.rawX - dx) + Math.abs(e.rawY - dy) > ui.dp(10)) {
                                drag = true; v.parent?.requestDisallowInterceptTouchEvent(true); v.cancelLongPress(); v.isPressed = false
                                (badgeTrash ?: DragTrash(this@BrowserActivity, frame, ui).also { badgeTrash = it }).show()
                            }
                            if (drag) {
                                val pw = (v.parent as? View)?.width ?: 0; val ph = (v.parent as? View)?.height ?: 0
                                v.translationX = (sx + e.rawX - dx).coerceIn(-v.left.toFloat(), (pw - v.left - v.width).coerceAtLeast(0).toFloat())
                                v.translationY = (sy + e.rawY - dy).coerceIn(-v.top.toFloat(), (ph - v.top - v.height).coerceAtLeast(0).toFloat())
                                badgeTrash?.update(v)
                                return true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (drag) {
                            drag = false
                            val hit = badgeTrash?.near(v) == true
                            badgeTrash?.hide()
                            if (hit) { v.visibility = View.GONE; v.translationX = 0f; v.translationY = 0f }
                            return true
                        }
                    }
                    return false
                }
            })
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
            setOnClickListener {
                val synced = CoSurf.on
                panic(); PlayTarget.clearTvHistory(this@BrowserActivity, "all"); PlayTarget.wipeWatchHistory(this@BrowserActivity)   // (v558) الزرار الأحمر يمسح سجل التلفزيون · (v810) وبقى يمسح سجل المشاهدة كله (آخر المشغّلة + السجل + المصطادة حتى المخفي) على الموبايل والتلفزيون
                if (synced) {   // (v649) المزامنة شغّالة: افصل، التلفزيون يمسح ويخرج للرئيسية، وإنت كمان تخرج من المتصفح
                    CoSurf.panic(); refreshCoBtns()
                    if (isTaskRoot) try { startActivity(Intent(this@BrowserActivity, MainActivity::class.java)) } catch (_: Throwable) {}
                    finish()
                }
            }
        }
        undoB = IconTextView(this).apply {
            text = "↩"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xCC1E2A3A.toInt(), 0x66FFFFFF, 22); elevation = ui.dp(4).toFloat(); visibility = View.GONE
            setOnClickListener { undoPanic() }
        }
        ctl = buildControls()   // (v248) زرار مانع الإعلانات 🛡 بقى فوق زرار الطوارئ على طول
        // (v592) زرار التدوير 🔄 بقى عايم في العمود (بدل ما كان ثابت فوق جنب التابات): من فوق لتحت ← 📺 · 🛡 · 🔄 · 🚨 · ↩
        val rotFloat = IconTextView(this).apply {
            text = "🔄"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = ui.box(0xCC1E2A3A.toInt(), 0x66FFFFFF, 22); elevation = ui.dp(4).toFloat()
            setOnClickListener { toggleRotate() }
            if (tvOn) visibility = View.GONE   // التلفزيون ما كانش فيه زرار تدوير
        }
        panicCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL
            if (tvOn) {   // (v801) التلفزيون: العمود العايم زي ما هو. الموبايل: كل الأزرار في actStrip (شريط فوق الشريط السفلي)
                addView(ctl, LinearLayout.LayoutParams(-2, -2))
                addView(rotFloat, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { bottomMargin = ui.dp(6) })
                addView(panicB, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
                addView(undoB, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { topMargin = ui.dp(6) })
            }
        }
        if (!tvOn) buildActStrip(rotFloat)
        blockCover = buildBlockCover()
        if (tvOn) TvModeFab.dock(this, panicCol) else TvModeFab.dockStrip(this, toolsHost, toolsInner)   // (v592) زرار 📺 العايم يقعد فوق العمود ده · (v801) على الموبايل جوه شريط الأدوات
        vidMark = VidMark(this) { act -> markAct(act) }
        val webWrap = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(webHolder, FrameLayout.LayoutParams(-1, -1))
            if (!tvOn) addView(vidMark, FrameLayout.LayoutParams(-1, -1))   // (v717) فوق الصفحة وتحت الغطا والأزرار العايمة؛ اللمس بيعدّي للصفحة
            addView(panicCover, FrameLayout.LayoutParams(-1, -1))
            if (tvOn) {   // (v801) على الموبايل: العمود والدايرة والألسنة اتنقلوا لشريط actStrip فوق الشريط السفلي
                addView(panicCol, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply { setMargins(0, 0, ui.dp(4), ui.dp(8)) })
                addView(badge, FrameLayout.LayoutParams(ui.dp(60), ui.dp(60), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = ui.dp(4) })   // (v595) كبسولة الاصطياد على الجنب اليمين
            }
            addView(blockCover, FrameLayout.LayoutParams(-1, -1))   // (v412) فوق كل حاجة
            if (!tvOn) actStrip?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }   // (v808) شفافة فوق أسفل الصفحة (مش صف منفصل بخلفية)
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
        root.addView(bar); root.addView(progTrack, LinearLayout.LayoutParams(-1, ui.dp(3)))
        if (tvOn) root.addView(findBar)
        // (p3) الرعشة: التلميحة كانت صف في الـroot فكل ظهور/اختفاء ليها (مع كل نتيجة فحص) كان بيغيّر ارتفاع الـWebView فالصفحة تعيد التوزيع وترتعش — بقت طبقة فوق الصفحة من غير ما تاخد مكان
        hint.setBackgroundColor((th.bg and 0x00FFFFFF) or 0xE6000000.toInt()); hint.elevation = ui.dp(2).toFloat()
        webWrap.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        if (!tvOn) { findBar.elevation = ui.dp(8).toFloat(); webWrap.addView(findBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP)) }   // (v923) البحث في الصفحة طبقة فوق الصفحة مش صف بيزقّها
        root.addView(webWrap, LinearLayout.LayoutParams(-1, 0, 1f))
        if (tvOn) root.addView(dlBar, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(6)) })   // (v324)
        else { dlBar.elevation = ui.dp(8).toFloat(); webWrap.addView(dlBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(ui.dp(8), 0, ui.dp(8), ui.dp(66)) }) }   // (v923) الرعشة: شريط «تحميل؟» بقى طبقة فوق الصفحة (فوق أزرار actStrip) بدل صف بيزقّ الـWebView
        // (v117) شريط البوابات: الفيديوهات · الموسيقى · المتصفح (إحنا فيه دلوقتي) · الملفات · (v324) يوتيوب
        val gates = BottomNav(this, ui, th, 2) { i -> if (i != 2) NavAnim.set(2, i); if (i == 0) goMain("videos") else if (i == 1) goMain("music") else if (i == 4) { MeeTubeLink.open(this); NavAnim.peek(this) } else if (i == 5) goMain("tasks") else if (i == 7) goMain("settings") else if (i == 3) { startActivity(TabNav.front(FilesHub.intent(this))); NavAnim.peek(this) } }   // (v973) من غير finish: التبويبات بتفضل شغّالة   // (v310) الملفات · (v364) الذكاء
        gatesNav = gates
        val floatNav = !tvOn && MtFloat.eligible(this)   // (v900) نفس شريط صفحة الميديا: عايم زجاجي
        if (!tvOn && !floatNav) root.addView(gates.view, LinearLayout.LayoutParams(-1, -2))   // (v517) تلفزيون: الشريط السفلي اتشال — 🏠 في شريط العنوان
        content = root
        frame = FrameLayout(this); frame.setBackgroundColor(th.bg)
        customBox = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        frame.addView(if (floatNav) MtFloat.build(this, th, ui, root, gates, null, reserve = true) { b ->   // (v900) · (v910) الصفحة تكمّل تحت الشريط الزجاجي (من غير شريط أبيض)؛ أزرار المتصفح وشريط التحميل بيترفعوا فوقه
            actStrip?.let { if (it.paddingBottom != b) it.setPadding(0, 0, 0, b) }
            (dlBar.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> val nb = ui.dp(66) + b; if (lp.bottomMargin != nb) { lp.bottomMargin = nb; dlBar.layoutParams = lp } }   // (v923)
        } else root, FrameLayout.LayoutParams(-1, -1))
        frame.addView(customBox, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        RotateHint.attach(this)   // (v823) زرار «لفّ الشاشة» لما اتجاه التليفون يخالف اتجاه المتصفح
        dockUi = MiniDock(this, frame, ui, th, { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("fullmusic", true)) })   // (v344)
        stripUi = StatusStrip(this, frame, ui, th) { goMain("tasks") }   // (v345)
        tasksFabUi = TasksFab(this, frame, ui) { goMain("tasks") }   // (v420)
        saver = BrowserSaver(this, frame, object : BrowserSaver.Host {   // (v783)
            override fun wv(): WebView? = wvOrNull
            override fun tab(): BTab? = tabs.getOrNull(curIdx)
            override fun tabs(): List<BTab> = tabs
            override fun adsBlocked(): Int = blockedN
            override fun fullscreen(): Boolean = customView != null
            override fun reloadCur() { try { wvOrNull?.reload() } catch (_: Throwable) {} }
        })
        saver.attach()

        // التبويبات: رجّع المحفوظة (لو مفعّل) وبعدها لو جاي بلينك افتحه في تبويب جديد
        val start = intent?.getStringExtra("start")?.trim() ?: ""
        // (v203) وضع «إصلاح اللينك»: أول فيديو يتلقط بيتحط مكان لينك الفيديو القديم بنفس هويته
        repairId = intent?.getStringExtra("repair_id")?.takeIf { it.isNotEmpty() }
        repairUrl = intent?.getStringExtra("repair_url") ?: ""; repairTitle = intent?.getStringExtra("repair_title") ?: ""
        repairTv = intent?.getBooleanExtra("repair_tv", false) == true   // (v820) الإصلاح لفيديو شغّال على التلفزيون: اللينك الجديد يتبعت له
        if (repairId != null) {
            hint.text = "🔧 بتحدّث لينك «" + repairTitle.ifEmpty { "الفيديو" } + "» — دوس تشغيل في الصفحة وأنا ألقط اللينك الجديد وأرجّعك للمشغّل بنفس الترجمة"
            h.postDelayed({ if (!dead) Notice.show(this, "🔧 شغّل الفيديو في الصفحة وأنا هلقطه", 4000L) }, 800L)
        }
        if (SubHunt.active) {   // (v746) وضع صيد الترجمة
            hint.text = "🔎 بدوّر على ترجمة «" + (SubHunt.info?.label() ?: "") + "» — دوس تحميل على أي ملف ترجمة أو ZIP وأنا أضيفه للفيديو علطول"
            h.postDelayed({ if (!dead) Notice.show(this, "🔎 دوس تحميل على ملف الترجمة وهتتضاف على الفيديو", 4500L) }, 800L)
        }
        if (tvOn && repairId == null && !SubHunt.active) { hint.text = "🖱 الأسهم = مؤشر · OK = دوسة · Menu = الشريط · OK طويلة = تبديل الوضع · شغّل الفيديو وأنا هلقطه"; h.postDelayed({ if (!dead && repairId == null) hint.visibility = View.GONE }, 12000L) }   // (v517) التلميحة بتختفي بعد 12 ثانية
        // (v918) فتح المتصفح من الشريط كان بيرعش 3-4 ثواني: استرجاع التابات (unpackState لكل تاب) + إنشاء الـWebView + تحميل الصفحة كله كان في onCreate 
        // على الـmain thread وقت انتقال الشاشة. دلوقتي الشاشة (الشريط + الخلفية) بترسم والانتقال يخلّص الأول، وبعدها (≈ فريمين بعد الأنيميشن) بنسترجع التابات ونحمّل.
        h.postDelayed({
        if (dead) return@postDelayed
        tabsReady = true
            val keep = prefs().getBoolean("br_save_tabs", true)
            val saved = if (keep) BrowserStore.loadTabs(this) else (emptyList<BrowserStore.SavedTab>() to 0)
            for (s in saved.first) tabs.add(BTab(nextId++).also { it.url = s.url; it.title = s.title; it.desktop = s.desktop; it.frozen = if (SiteBlock.keyOf(s.url)?.let { k -> SiteBlock.has(this, k) } == true) null else BrowserStore.unpackState(s.state) })   // (v820) frozen = تاريخ الرجوع المحفوظ: select() بيرجّعه بـ restoreState
            if (tabs.isNotEmpty()) curIdx = saved.second.coerceIn(0, tabs.size - 1)
            if (start.isNotEmpty()) { autoOpen = intent?.getBooleanExtra("noauto", false) != true; newTab(start, true) }
            else if (tabs.isEmpty()) newTab(home(), true)
            else select(curIdx)
            scanLoop()
        }, 360L)
    }

    /** (v117) رجوع للشاشة الرئيسية على بوابة معيّنة (videos / yt) */
    private fun goMain(tab: String) {
        persistTabs()
        if (DeviceUi.isTv(this) && tab == "videos") { try { startActivity(Intent(this, TvActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("tv_show", "videos")); finish(); return } catch (_: Throwable) {} }   // (v530)   // (v482) تلفزيون: الرئيسية = شاشة التلفزيون
        startActivity(TabNav.main(this, tab))   // (v973) Main فاضلة شغّالة: بتتجاب قدّام من غير بناء ولا finish للمتصفح
        NavAnim.peek(this)
    }
    /** (v820) رجوع للشاشة الرئيسية وفتح قسم من الإعدادات الشاملة (مثلًا capture = 🔗 الاصطياد · tvset = 📺 التلفزيون) */
    private fun goMainSettings(sec: String) {
        persistTabs()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("reopen_tab", sec))
        NavAnim.peek(this)
        finish()
    }
    /** (v117) أي صفحة فيديو يوتيوب بتتفتح في المتصفح بتتسجل في سجل بوابة يوتيوب (المعرّف + الاسم) */
    private var lastYtId = ""
    private val ytIds = HashMap<String, String>()
    private fun noteYt(url: String?, title: String?) { /* (v124) سجل يوتيوب اتشال */ }

    // ===== إنشاء WebView لتبويب =====
    /** (v977) لقطة من صيّاد الجافا — بتعدّي على نفس الفحص (verifyFound) زي أي لقطة */
    private fun onHook(t: BTab, u: String, k: String, frame: String, origin: String) {
        if (dead || t.hold != null) return
        val a = Sniff.acceptHooked(u) ?: return
        val kind = when (k) { "hls" -> "HLS"; "dash" -> "DASH"; else -> Sniff.kindOf(a) }
        val og = origin.takeIf { it.startsWith("http", true) }
        val ref = frame.takeIf { it.startsWith("http", true) } ?: (og?.plus("/")) ?: t.url
        val pg = t.url; val g0 = t.gen
        val f = Found(a, kind, "", ref, uaOf(t), pg)
        if (og != null) f.origin = og
        h.post { if (!dead) addFound(f, t, g0) }
    }
    private fun makeWebView(t: BTab): WebView {
        if (PopupGuard.learned.isEmpty()) try { PopupGuard.learned.addAll(prefs().getStringSet("pop_learned", emptySet()) ?: emptySet()) } catch (_: Throwable) {}   // (v816)
        val w = WebView(this); WebMute.register(w)
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            textZoom = try { prefs().getInt("br_zoom", 100) } catch (_: Throwable) { 100 }   // (v854)
            useWideViewPort = true; loadWithOverviewMode = true
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = popMode() != PopupGuard.OFF   // (v401) الذكي بيشوف كل بوب-أب ويحكم عليه بنفسه
        }
        if (uaWeb.isEmpty()) uaWeb = w.settings.userAgentString ?: ""
        if (t.desktop) w.settings.userAgentString = DESK_UA
        VidHook.install(w) { u, k, f, o -> onHook(t, u, k, f, o) }   // (v977) صيّاد جافا جوه كل الإطارات للمشغّلات الخارجية (لينكات من غير امتداد / قوايم HLS-DASH من fetch/XHR)
        if (t.priv) { w.settings.cacheMode = WebSettings.LOAD_NO_CACHE; @Suppress("DEPRECATION") w.settings.saveFormData = false }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        w.setBackgroundColor(Color.WHITE)
        if (::saver.isInitialized) saver.applyTo(w, t, t.url)   // (v783)
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
                // (v722) فحص الإعلانات الأول (كان الصيّاد بيشتغل قبله فيلقط فيديو الإعلان) + طلب جاي من صفحة/إطار شبكة إعلانات = إعلان برضه
                val rf = if (r.isForMainFrame) null else try { r.requestHeaders?.get("Referer") } catch (_: Throwable) { null }
                if (adFor(u) && (AdBlock.blocked(u) || (rf != null && AdBlock.blocked(rf)))) { blockedN++; countAd(u); h.post { if (!dead) shield.text = "🛡 $blockedN" }; return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) }
                Sniff.accept(u)?.let { a ->
                    val hd = t.hold
                    if (hd != null) {   // (v401) فيديو في بوب-أب = دليل إنه صفحة مشاهدة
                        hd.media = true
                        if (hd.sniffed.size < 6) { hd.sniffed.add(a) }
                    } else {
                        val pg = t.url; val og = try { r.requestHeaders?.get("Origin") } catch (_: Throwable) { null }; h.post { if (!dead) addFound(Found(a, Sniff.kindOf(a), "", rf?.takeIf { it.startsWith("http", true) } ?: pg, uaOf(t), pg).also { f0 -> if (!og.isNullOrBlank() && og.startsWith("http", true)) f0.origin = og }, t, g0) }   // (v847) Referer الحقيقي بتاع الطلب · (v850) t.url بدل v.url — shouldInterceptRequest بيشتغل على thread خلفي وv.url كان بيكرّش التطبيق
                    }
                    Unit
                }
                val svRes = saver.intercept(u, t)   // (v783) بعد فحص الإعلانات دايمًا: طوارئ الداتا بتمنع الخطوط بس
                if (svRes != null) return svRes
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
                if (r.isForMainFrame && u.startsWith("http") && gateBlocked(t, u)) return true
                if (SubHunt.active && r.isForMainFrame && u.startsWith("http") && SubHunt.urlLooksSub(u)) { huntCapture(u, uaOf(t), null, v.url ?: t.url); return true }   // (v746) لينك مباشر لملف ترجمة   // (v412) موقع محجوب
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
                NetLedger.web(url)
                val hd = t.hold
                if (hd != null) {   // (v401) بوب-أب في الحجر
                    if (url != null && !url.startsWith("data:")) { t.url = url; hd.addUrl(url); h.post { popDecide(hd, false) } }
                    v.evaluateJavascript(adJs(), null)
                    return
                }
                if (url != null && gateBlocked(t, url)) return   // (v412) شبكة أمان: تحويل/رجوع/JS وصل لموقع محجوب
                if (url != null && !url.startsWith("data:")) { t.url = url; newPage(t, url) }
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav() }
                if (url != null && CoSurf.on && t === tabs.getOrNull(curIdx)) CoSurf.nav(url, v.settings.userAgentString ?: "")   // (v646)
                if (adFor(url)) v.evaluateJavascript(adJs(), null)
                if (url != null) saver.pageStarted(t, v, url)   // (v783) بعد حقن مانع الإعلانات
            }
            override fun doUpdateVisitedHistory(v: WebView, url: String?, isReload: Boolean) {
                val hd = t.hold
                if (hd != null) { if (url != null && !url.startsWith("data:")) { t.url = url; hd.addUrl(url) }; return }
                if (t.blockedUrl != null && url != null && SiteBlock.keyOf(url) == SiteBlock.keyOf(t.blockedUrl)) return   // (v412)
                if (url != null && !url.startsWith("data:")) { t.url = url; newPage(t, url); noteYt(url, null) }
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav() }
                if (url != null && CoSurf.on && t === tabs.getOrNull(curIdx)) CoSurf.nav(url, v.settings.userAgentString ?: "")   // (v646)
            }
            override fun onPageFinished(v: WebView, url: String?) {
                if (CoSurf.on && t === tabs.getOrNull(curIdx)) CoSurf.pageDone(url)   // (v649)
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
                    if (!t.priv && !BrowserSaver.isArchive(url) && !(t.id == panicClearId && SiteBlock.keyOf(url) == lastPurgeKey)) BrowserStore.recordVisit(this@BrowserActivity, url, ti)   // (v412) صفحة جوجل بعد الطوارئ ماتتسجّلش لو هي نفسها اللي اتمسحت
                    noteYt(url, ti)
                }
                if (t.id == panicClearId && (url ?: "").contains("google.")) { try { v.clearHistory() } catch (_: Throwable) {}; endPanicCover(); updateNav() }   // (v231) الرجوع مايرجّعش الصفحة اللي اتمسحت
                if (t === tabs.getOrNull(curIdx)) { showUrl(url); updateNav(); updateStar(); scanOnce(); scheduleAutoScan(t, longArrayOf(1000L, 3500L)); scheduleFlashKb(t) }   // (v838) فحص أوتوماتيك · (v842) كيبورد ألعاب الفلاش
                if (adFor(url)) v.evaluateJavascript(adJs(), null)
                saver.pageFinished(t, v, url)   // (v783)
                saveAdStats()
                persistTabs()
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (!r.isForMainFrame) return
                if (t.hold != null) return   // (v401) بوب-أب في الحجر: المهلة هتحكم
                val u = r.url.toString()
                if (u.startsWith("http") && saver.onError(t, v, u, e.errorCode)) return   // (v783) أوفلاين: كاش ثم نسخة محفوظة قبل صفحة الخطأ
                if (u.startsWith("data:") || t.errUrl == u) return
                t.errUrl = u
                v.loadDataWithBaseURL(u, errHtml(u, e.description?.toString() ?: ""), "text/html", "utf-8", u)
                if (t.id == panicClearId) endPanicCover()   // (v231) صفحة الخطأ حلّت مكان القديمة
            }
        }
        w.setDownloadListener { url, ua, cd, mime, _ ->
            if (SubHunt.active && SubHunt.looksLikeSub(url, cd, mime)) { huntCapture(url, ua ?: uaOf(t), cd, t.url); return@setDownloadListener }   // (v746)
            // (v203) وضع إصلاح اللينك: زرار «تحميل» في الصفحة حتى لو اللينك من غير .mp4 = ده لينك الفيديو
            if (repairId != null && url.startsWith("http", true) && (mime?.startsWith("video") == true || mime == "application/octet-stream" || Sniff.accept(url) != null)) {
                addFound(Found(url, Sniff.kindOf(url), "", t.url, ua ?: uaOf(t)), t)
                return@setDownloadListener
            }
            Sniff.accept(url)?.let { a -> addFound(Found(a, Sniff.kindOf(a), "", t.url, ua ?: uaOf(t)), t) }
            val name = URLUtil.guessFileName(url, cd, mime)
            dlDialog(name, url, ua ?: uaOf(t), mime)   // (v754) نافذة تحميل: الاسم · الحجم · المدة · تنبيه الداتا
        }
        w.setOnLongClickListener { v -> longPress(v as WebView) }
        // (v646) مزامنة المتصفح مع التلفزيون: سكرول + ضغطة (من غير ما نبلع الحدث)
        w.setOnScrollChangeListener { v, _, _, _, _ -> if (CoSurf.on && v === wvOrNull) CoSurf.scrolled(v as WebView) }
        w.setOnTouchListener { v, e -> if (CoSurf.on && v === wvOrNull) CoSurf.touch(v as WebView, e); false }
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
    /** (v848) الوضع المخفي: هيدر + فوتر + شريط الحالة بنفسجي غامق · (مرحلة 3) FLAG_SECURE = المعاينة في الريسنت سودا (ولقطة الشاشة ممنوعة) */
    private fun applyPrivLook(on: Boolean) {
        if (!::content.isInitialized) return
        privLook = on
        val bg = if (on) PRIV_BG else th.bg
        try {
            content.setBackgroundColor(bg)
            topBarV?.setBackgroundColor(if (on) PRIV_BG else 0x00000000)
            for (b in barBtns) b.setTextColor(if (on) 0xFFF3E5F5.toInt() else th.text)
            // (v923) شريط الرابط: كان كلام أسود على بنفسجي — في المخفي نص فاتح وخلفية شفافة فاتحة (وحتى وهو في فوكس)
            if (::addr.isInitialized) {
                addr.setTextColor(if (on) 0xFFFFFFFF.toInt() else th.text)
                addr.setHintTextColor(if (on) 0xFFD1B3E0.toInt() else th.muted)
                addr.highlightColor = if (on) 0x66CE93D8 else addr.highlightColor
                addr.background = if (on) ui.box(0x33FFFFFF, 0x66E1BEE7, 10) else ui.box(th.surface, th.border, 10)
                addr.setOnFocusChangeListener { v, f -> v.background = if (privLook) ui.box(0x33FFFFFF, if (f) 0xFFE1BEE7.toInt() else 0x66E1BEE7, 10) else ui.box(th.surface, if (f) th.primary else th.border, 10) }
            }
            if (::tabsB.isInitialized) tabsB.background = ui.box(0x00000000, if (on) 0xFFE1BEE7.toInt() else th.text, 6)
            gatesNav?.tint(if (on) PRIV_BG else null)
            window.statusBarColor = bg; window.navigationBarColor = bg
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                var f = window.decorView.systemUiVisibility
                f = if (on || !th.isLight) f and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                window.decorView.systemUiVisibility = f
            }
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {}
    }
    private fun select(i: Int) {
        if (i !in tabs.indices) return
        tabs.getOrNull(curIdx)?.let { snapTab(it) }
        wvOrNull?.let { try { it.evaluateJavascript(BrowserSaver.JS_STOPMEDIA, null) } catch (_: Throwable) {}; try { it.onPause() } catch (_: Throwable) {} }   // (v788) الفيديو في التبويب اللي سبته يقف فورًا (onPause لوحدها مش بتوقّف التحميل)
        webHolder.removeAllViews()
        val prevIdx = curIdx
        curIdx = i
        if (prevIdx != i && tabs.size > 1) Motion.slideIn(webHolder, if (i > prevIdx) -1 else 1, 230L)   // (v313) تبديل التبويب بانزلاق
        val t = tabs[i]
        saver.touch(t)   // (v783)
        webGroups.clear(); openKey = null; markHidden = false; if (::vidMark.isInitialized) vidMark.clear(); refreshBadge()   // (v203) الاصطياد بتاع التبويب ده بس
        setKbPan(false); scheduleFlashKb(t)   // (v842)
        val first = t.wv == null
        if (first) t.wv = makeWebView(t)
        val w = t.wv!!
        (w.parent as? ViewGroup)?.removeView(w)
        webHolder.addView(w, FrameLayout.LayoutParams(-1, -1))
        try { w.onResume() } catch (_: Throwable) {}
        val fz = t.frozen   // (v783) تبويب كان مجمّد: رجّع حالته (الصفحة من الكاش لو متاحة)
        if (fz != null) { t.frozen = null; t.loaded = true; val ok = try { w.restoreState(fz) != null } catch (_: Throwable) { false }; if (!ok && t.url.isNotBlank()) loadIn(w, t, t.url) }
        else if (!t.loaded) { t.loaded = true; if (t.url.isNotBlank()) loadIn(w, t, t.url) }
        showUrl(t.url, true); updateNav(); updateProgress(); updateStar(); refreshTabsCount(); closeFind()
        addr.hint = if (t.priv) "🕶 متخفي — رابط الموقع / كلمة بحث" else "رابط الموقع / كلمة بحث"
        tabsB.setTextColor(if (t.priv) 0xFFE1BEE7.toInt() else th.text)
        applyPrivLook(t.priv)   // (v848)
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
    /** (v820) حالة تاريخ التبويب: من الـWebView الشغّال، أو من النسخة المجمّدة لو التبويب خلفي */
    private fun stateOf(t: BTab): android.os.Bundle? {
        val w = t.wv ?: return t.frozen
        return try { android.os.Bundle().also { w.saveState(it) } } catch (_: Throwable) { t.frozen }
    }
    @Volatile private var tabsReady = false   // (v918) مفيش حفظ للتابات قبل ما الاسترجاع المؤجَّل يخلّص (وإلا نمسح التابات المحفوظة)
    private fun persistTabs() {
        if (!tabsReady) return
        if (!prefs().getBoolean("br_save_tabs", true)) return
        // (v124) التبويبات المتخفية عمرها ما بتتحفظ
        val keep = tabs.filter { !it.priv }
        val ci = keep.indexOf(tabs.getOrNull(curIdx)).let { if (it < 0) 0 else it }
        BrowserStore.saveTabs(this, keep.map { BrowserStore.SavedTab(if (it.id == panicClearId) GOOGLE else (it.wv?.url?.takeIf { u -> !u.startsWith("data:") } ?: it.url), it.title, it.desktop, if (it.id == panicClearId) "" else BrowserStore.packState(stateOf(it))) }, ci)
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
        saver.beforeLoad(w, t, u)   // (v783)
        val hdr = saver.headersFor(u)
        if (hdr != null && u.startsWith("http")) w.loadUrl(u, hdr) else w.loadUrl(u)   // Save-Data: on لما التوفير شغّال
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
        if (::navBackB.isInitialized) {   // (v801) سهمي الشريط السفلي: الباك يظهر لو ينفع ترجع، والفوروورد لو رجعت قبل كده
            navBackB.visibility = if (w?.canGoBack() == true) View.VISIBLE else View.GONE
            navFwdB.visibility = if (w?.canGoForward() == true) View.VISIBLE else View.GONE
        }
    }
    private fun updateProgress() {
        val p = tab.progress
        reloadB.text = if (p < 100) "✕" else "🔄"
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
        WebMute.pauseAll()
        try {
            w.evaluateJavascript("(function(){var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{a[i].pause()}catch(e){}}})()", null)
        } catch (_: Throwable) {
        }
        panicCover.visibility = View.VISIBLE
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
    private fun popupMenu(anchor: View, entries: List<Pair<String, () -> Any?>>, quick: List<Pair<String, () -> Any?>> = emptyList()) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.box(th.card, th.border, 14); elevation = ui.dp(8).toFloat() }
        val sv = ScrollView(this).apply { addView(col); isVerticalScrollBarEnabled = false }
        val width = ui.dp(if (tvOn) 310 else 240)
        val pw = TvFocus.pop(this@BrowserActivity, sv, width, -2, true)
        pw.setBackgroundDrawable(ColorDrawable(0))
        if (quick.isNotEmpty()) {   // (v854) صف أيقونات الحاجات اللي بتتعمل كتير (تبويب · متخفي · مفضلة · نسخ · مشاركة · رئيسية)
            val qr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, 0, 0, ui.dp(4)) }
            for ((t, f) in quick) qr.addView(IconTextView(this).apply {
                text = t; textSize = 20f; setTextColor(th.text); gravity = Gravity.CENTER; minHeight = ui.dp(46)
                background = ui.box(th.bg, th.border, 10)
                setOnClickListener { pw.dismiss(); f() }
            }, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)) })
            col.addView(qr)
            col.addView(View(this).apply { setBackgroundColor(th.border) }, LinearLayout.LayoutParams(-1, ui.dp(1)).apply { setMargins(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(4)) })
        }
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

    // ===== (v646) مزامنة المتصفح مع التلفزيون =====
    private fun toggleCoSurf() {
        if (tvOn) return
        if (!CoSurf.toggle(this)) { Notice.show(this, "مش متصل بتلفزيون — اربط الريموت الأول", 2400L); return }
        Notice.show(this, if (CoSurf.on) "📺 المزامنة شغّالة — اللي تعمله هنا بيتعمل على التلفزيون" else "📺 المزامنة اتقفلت", 2400L)
        val w = wvOrNull
        if (CoSurf.on && w != null) CoSurf.start(w, w.url ?: tab.url)
        refreshCoBtns()
    }

    // ===== (v647) الزرار العايم 📺① (مزامنة الأحداث CoSurf) — (v649) ② المرآة اتشالت =====
    private var coBtn1: TextView? = null
    private val coOff = 0xF2232838.toInt(); private val coOn = 0xFF2E9E5B.toInt()
    /** لسان (كبسولة) طالع من الجنب الشمال — نفس شكل لسان التلفزيون/الكنترولر على اليمين بس مقلوب */
    private fun coTab(t: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false
        elevation = ui.dp(6).toFloat(); setOnClickListener { onTap() }
        val r = ui.dp(18).toFloat()
        background = android.graphics.drawable.GradientDrawable().apply { setColor(coOff); cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f); setStroke(ui.dp(1), 0x66FFFFFF) }
    }
    private fun buildCoButtons(): View {
        val b = coTab("📺\n①") { toggleCoSurf() }; coBtn1 = b
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(b, LinearLayout.LayoutParams(ui.dp(38), ui.dp(50)))
            addView(coTab("🕶") { newTab(home(), true, -1L, true) }, LinearLayout.LayoutParams(ui.dp(38), ui.dp(50)).apply { topMargin = ui.dp(6) })   // (v649) تصفح مخفي
        }
    }
    private fun coTint(b: TextView?, on: Boolean) { (b?.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (on) coOn else coOff) }
    private fun refreshCoBtns() { coTint(coBtn1, CoSurf.on) }
    /** على التلفزيون: افتح نفس الرابط في التبويب الحالي (بنفس الـUA بتاع الموبايل عشان الصفحة تطلع بنفس الشكل) */
    fun coNav(u: String, ua: String) {
        val w = wvOrNull ?: return
        if ((w.url ?: tab.url) == u) return
        if (ua.isNotEmpty() && w.settings.userAgentString != ua) w.settings.userAgentString = ua
        loadIn(w, tab, u)
    }
    /** (v649) الكوكيز اتحدّثت من الموبايل: حمّل الصفحة تاني مرة واحدة بالجلسة الجديدة */
    fun coReload(u: String) { val w = wvOrNull ?: return; if ((w.url ?: tab.url) == u) try { w.reload() } catch (_: Throwable) {} }
    /** (v649) على التلفزيون: اخرج من المتصفح. purge=true (🚨) يمسح الموقع الحالي من السجل ويحجبه زي الموبايل */
    fun coExit(purge: Boolean) {
        try {
            if (purge) {
                val u = wvOrNull?.url ?: tab.url
                SiteBlock.keyOf(u)?.let { k ->
                    BrowserStore.purgeByKey(this, k)
                    if (k != SiteBlock.keyOf(GOOGLE)) SiteBlock.add(this, k)
                }
                try { wvOrNull?.clearHistory() } catch (_: Throwable) {}
            }
            WebMute.pauseAll(); coEnd()
        } catch (e: Throwable) { LogStore.err("Browser:coExit", e) }
        finish()
    }
    fun coJs(js: String) { try { wvOrNull?.evaluateJavascript(js, null) } catch (_: Throwable) {} }
    /** المزامنة اتقفلت: رجّع الـUA الأصلي */
    fun coEnd() { try { wvOrNull?.settings?.userAgentString = if (tab.desktop) DESK_UA else uaWeb.ifEmpty { null } } catch (_: Throwable) {} }

    private fun showMenu(anchor: View) {
        val url = tab.wv?.url ?: tab.url
        val marked = BrowserStore.isMarked(this, url)
        // (v854) القايمة اتنظّمت: صف أيقونات سريعة فوق + 4 مجموعات (بتفتح قايمة فرعية) + الإعدادات والخروج. مفيش حاجة اتمسحت.
        val favToggle = { val added = BrowserStore.toggleMark(this, url, tab.title.ifBlank { Sniff.nameOf(url) }); Notice.show(this, if (added) "اتضافت للمفضلة" else "اتشالت من المفضلة", 1800L) }
        val quick: List<Pair<String, () -> Any?>> = listOf(
            "➕" to { newTab(home(), true) },
            "🕶" to { newTab(home(), true, -1L, true) },
            (if (marked) "★" else "☆") to favToggle,
            "📋" to { copyUrl(url) },
            "📤" to { shareUrl(url) },
            "🏠" to { load(home()) }
        )
        val gBook: List<Pair<String, () -> Unit>> = listOf(
            "📚 المفضلة" to { showBookmarks() },
            "🕘 السجل" to { showHistory() },
            "🔒 سجل الخصوصية" to { showPrivacyHistory() },
            "🚫 المواقع المحجوبة (${SiteBlock.list(this).size})" to { showBlocked() }
        )
        val gPage: List<Pair<String, () -> Unit>> = listOf(
            "🔍 بحث في الصفحة" to { openFind() },
            "📖 وضع القارئ" to { readerMode() },
            (if (tab.desktop) "🖥 نسخة الكمبيوتر: شغالة ✓" else "🖥 نسخة الكمبيوتر") to { toggleDesktop() },
            "🔄 تدوير الشاشة (لاندسكيب ⇄ بورتريه)" to { toggleRotate() },
            (if (CoSurf.on) "📺 مزامنة مع التلفزيون: شغّالة ✓" else "📺 مزامنة مع التلفزيون") to { toggleCoSurf() }
        )
        val gVid: List<Pair<String, () -> Unit>> = listOf(
            "🎬 الفيديوهات الملقوطة (${groupsOf(vis()).size})" to { showList() },
            "🔎 افحص الصفحة (فيديوهات · ملفات · برامج · لينكات رفع)" to { pageScan() },
            "🎞 تحكم الفيديو" to { showVideoControls() }
        )
        val gProt: List<Pair<String, () -> Unit>> = listOf(
            "📶 توفير الداتا والسرعة" to { saver.showPanel() },
            "📊 استهلاك الداتا" to { saver.showStats() },
            "🛡 إحصائيات حظر الإعلانات" to { adStatsDialog() },
            (if (hostOf(url) in adWhite) "🛡 فعّل حظر الإعلانات للموقع ده" else "🛡 عطّل حظر الإعلانات للموقع ده") to { toggleAdWhite(url) }
        )
        // (v517) تلفزيون: القايمة بتشيل اللي مش بيتستعمل بالريموت (متخفي · سجل الخصوصية · تدوير · نسخ/مشاركة · وضع القارئ · إحصائيات وسماح الإعلانات)
        val tvDrop = listOf("📺", "🕶", "🔒", "🔄 تدوير", "📋 نسخ", "📤", "📖", "🛡 إحصائيات", "🛡 فعّل", "🛡 عطّل", "🔍 بحث في الصفحة", "🖥 نسخة الكمبيوتر")
        fun keep(l: List<Pair<String, () -> Unit>>) = if (tvOn) l.filter { m -> tvDrop.none { m.first.startsWith(it) } } else l
        fun group(title: String, l: List<Pair<String, () -> Unit>>): Pair<String, () -> Any?>? {
            val k = keep(l); if (k.isEmpty()) return null
            return (title + "  ›") to { popupMenu(anchor, listOf<Pair<String, () -> Any?>>("‹ رجوع" to { showMenu(anchor) }) + k) }
        }
        val entries = ArrayList<Pair<String, () -> Any?>>()
        if (tvOn) {   // التلفزيون من غير صف الأيقونات: التبويب الجديد والرئيسية والمفضلة بنصوص
            entries.add("➕ تبويب جديد" to { newTab(home(), true) })
            entries.add((if (marked) "★ شيل من المفضلة" else "☆ ضيف للمفضلة") to favToggle)
            entries.add("🏠 الصفحة الرئيسية" to { load(home()) })
        }
        group("📚 المفضلة والسجل", gBook)?.let { entries.add(it) }
        group("📄 الصفحة الحالية", gPage)?.let { entries.add(it) }
        group("🎬 الفيديو والاصطياد", gVid)?.let { entries.add(it) }
        group("🛡 الحماية والداتا", gProt)?.let { entries.add(it) }
        entries.add("⚙️ الإعدادات" to { showSettings() })
        entries.add("✕ خروج من المتصفح" to { finish() })
        popupMenu(anchor, entries, if (tvOn) emptyList() else quick)
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
        val clear = ui.button("🗑 امسح السجل كله") { BrowserStore.clearHistory(this); PlayTarget.clearTvHistory(this, "browser"); render(); Notice.show(this, "السجل اتمسح", 1800L) }
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
        lateinit var dlg: Dialog
        fun jump(f: () -> Unit): () -> Unit = { try { dlg.dismiss() } catch (_: Throwable) {}; f() }
        // (v854) الإعدادات اتقسّمت أقسام بتتفتح وتتقفل (كلها مقفولة ما عدا الحماية)، ومفيش إعداد اتمسح + إعدادات جديدة (حجم الخط · قفل المتخفي)
        var tgt: LinearLayout = box
        fun sec(title: String, open: Boolean = false) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = if (open) View.VISIBLE else View.GONE; setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(6)) }
            val head = ui.button(title + (if (open) "  ▴" else "  ▾"), false) {}
            head.setOnClickListener { val show = body.visibility != View.VISIBLE; body.visibility = if (show) View.VISIBLE else View.GONE; head.text = title + (if (show) "  ▴" else "  ▾") }
            box.addView(head); box.addView(body); tgt = body
        }
        // ---- الحماية والفحص ----
        sec("🛡 الحماية والفحص", true)
        // (v839) الحماية كلها (مانع الإعلانات + البوب-أب) في اختيار واحد بدل ما كانت مفرّقة: مقفول · خفيف · متوسط · قوي
        tgt.addView(ui.text("🛡 الحماية (إعلانات · بوب-أب · تحويلات)", 12f, th.muted, true).apply { setPadding(0, ui.dp(4), 0, ui.dp(2)) })
        lateinit var protDesc: TextView
        tgt.addView(ui.chips(PROT, { protLevel() }) { setProtection(it); protDesc.text = protDescOf(it) })
        protDesc = ui.text(protDescOf(protLevel()), 11f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(6)) }
        tgt.addView(protDesc)
        tgt.addView(ui.switchRow("🔎 فحص الصفحة أوتوماتيك (فيديوهات · ملفات · برامج · لينكات رفع)", autoScanOn()) { v -> Cfg.put("auto_scan", if (v) "1" else "0") })
        tgt.addView(ui.noHint().apply { setPadding(0, 0, 0, ui.dp(6)) })
        tgt.addView(ui.switchRow("🔒 فصل لينكات الكبار في «سجل الخصوصية» المقفول", PrivacyFilter.on()) { Cfg.put("pf_on", if (it) "1" else "0") })
        tgt.addView(ui.switchRow("🤖 الذكاء الصناعي يصنّف الصفحات (بيبعت الرابط والعنوان بس، مرة لكل موقع)", PrivacyFilter.aiOn()) { Cfg.put("pf_ai", if (it) "1" else "0") })
        tgt.addView(ui.switchRow("🕶 اقفل كل التبويبات المتخفية لما أخرج من التطبيق (هوم / ريسنت)", prefs().getBoolean("br_priv_close", false)) { v -> prefs().edit().putBoolean("br_priv_close", v).apply() })
        // ---- التصفح ----
        sec("🌐 التصفح")
        tgt.addView(ui.switchRow("🖥 نسخة الكمبيوتر في التبويبات الجديدة", prefs().getBoolean("br_desktop_default", false)) { v -> prefs().edit().putBoolean("br_desktop_default", v).apply() })
        tgt.addView(ui.switchRow("💾 احفظ التبويبات لما أخرج وارجّعها", prefs().getBoolean("br_save_tabs", true)) { v ->
            prefs().edit().putBoolean("br_save_tabs", v).apply()
            if (v) persistTabs() else try { java.io.File(filesDir, "br_tabs.json").delete() } catch (_: Throwable) {}
        })
        tgt.addView(ui.text("🔠 حجم الخط في الصفحات", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        val zNames = listOf("صغير", "عادي", "كبير", "كبير جدًا"); val zVals = listOf(85, 100, 120, 150)
        tgt.addView(ui.chips(zNames, { zNames[zVals.indexOf(prefs().getInt("br_zoom", 100)).coerceAtLeast(0)] }) { pick -> val i = zNames.indexOf(pick); if (i >= 0) { prefs().edit().putInt("br_zoom", zVals[i]).apply(); applyTextZoom() } })
        tgt.addView(ui.text("محرك البحث", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        tgt.addView(ui.chips(listOf("Google", "DuckDuckGo", "Bing"), { prefs().getString("br_engine", "Google") ?: "Google" }) { prefs().edit().putString("br_engine", it).apply() })
        tgt.addView(ui.text("الصفحة الرئيسية (فاضي = جوجل)", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        val homeEt = ui.input("https://www.google.com", prefs().getString("br_home", "") ?: "").apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        homeEt.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { prefs().edit().putString("br_home", s?.toString()?.trim() ?: "").apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        tgt.addView(homeEt)
        // ---- الفيديو ----
        sec("🎞 الفيديو")
        tgt.addView(ui.noHint().apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        val dqNames = listOf("الأعلى", "لحد 1080p", "لحد 720p", "لحد 480p", "لحد 360p"); val dqVals = listOf("0", "1080", "720", "480", "360")
        tgt.addView(ui.chips(dqNames, { dqNames[dqVals.indexOf(prefs().getString("br_defq", "0") ?: "0").coerceAtLeast(0)] }) { pick -> val i = dqNames.indexOf(pick); if (i >= 0) { prefs().edit().putString("br_defq", dqVals[i]).apply(); renderList() } })
        tgt.addView(ui.noHint().apply { setPadding(0, 0, 0, ui.dp(4)) })
        // ---- البيانات والخصوصية ----
        sec("🧹 البيانات والخصوصية")
        tgt.addView(ui.button("🧹 امسح الكاش") { try { tabs.forEach { it.wv?.clearCache(true) } } catch (_: Throwable) {}; Notice.show(this, "الكاش اتمسح", 1800L) })
        tgt.addView(ui.button("🍪 امسح الكوكيز وبيانات المواقع (هتتسجّل خروج من المواقع)") {
            try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData() } catch (_: Throwable) {}
            Notice.show(this, "الكوكيز وبيانات المواقع اتمسحت", 2000L)
        })
        tgt.addView(ui.button("🕘 امسح السجل") { BrowserStore.clearHistory(this); PlayTarget.clearTvHistory(this, "browser"); Notice.show(this, "السجل اتمسح", 1800L) })
        tgt.addView(ui.button("🕶 اقفل كل التبويبات المتخفية دلوقتي") {
            if (tabs.none { it.priv }) Notice.show(this, "مفيش تبويبات متخفية مفتوحة", 1800L) else { closeAllTabs(true); Notice.show(this, "التبويبات المتخفية اتقفلت", 1800L) }
        })
        // ---- اختصارات ----
        sec("🔗 اختصارات (داتا · حظر · اصطياد · تلفزيون)")
        // (v820) اللي بيأثر على المتصفح ومكانه التاني
        tgt.addView(ui.button("📶 توفير الداتا والسرعة", false, jump { saver.showPanel() }))
        tgt.addView(ui.button("🚫 المواقع المحجوبة (${SiteBlock.list(this).size})", false, jump { showBlocked() }))
        tgt.addView(ui.button("🛡 إحصائيات حظر الإعلانات", false, jump { adStatsDialog() }))
        if (!tvOn) tgt.addView(ui.button("🔗 اصطياد الفيديو من المتصفحات (مراقب الصفحات)", false, jump { goMainSettings("capture") }))
        tgt.addView(ui.button("📺 إعدادات المتصفح على التلفزيون", false, jump { goMainSettings("tvset") }))
        dlg = sheet("⚙️ إعدادات المتصفح", box); dlg.show()
    }
    /** (v854) حجم الخط بتاع الصفحات (textZoom) على كل التبويبات */
    private fun applyTextZoom() {
        val z = try { prefs().getInt("br_zoom", 100) } catch (_: Throwable) { 100 }
        for (t in tabs) try { t.wv?.settings?.textZoom = z } catch (_: Throwable) {}
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

    // ===== (v754) نافذة التحميل (زي داونلود مانجر) =====
    private fun dlDialog(name: String, url: String, ua: String?, mime: String?) {
        if (dead) return
        try {
            val onData = NetLedger.transport() == NetLedger.MOBILE
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val nameV = ui.text("📄 $name", 15f, th.text)
            val sizeV = ui.text("📦 الحجم: بيتحسب…", 13f, th.muted)
            val etaV = ui.text("⏱ المدة: —", 13f, th.muted)
            box.addView(nameV); box.addView(sizeV); box.addView(etaV)
            if (onData) box.addView(ui.text("⚠ التحميل ده هيتحمّل على داتا الموبايل. تكمّل؟", 14f, 0xFFD32F2F.toInt()))
            var d: android.app.Dialog? = null
            val row = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) }
            row.addView(ui.button(if (onData) "آه، حمّل على الداتا" else "⬇ تحميل", true) { d?.dismiss(); startDownload(url, ua, name, mime) }, LinearLayout.LayoutParams(0, ui.dp(46), 1f).apply { marginEnd = ui.dp(6) })
            row.addView(ui.button(if (onData) "لا" else "✕ إلغاء") { d?.dismiss() }, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
            box.addView(row)
            d = sheet("⬇ تحميل", box)
            if (onData) d.setCancelable(false)   // على الداتا: لازم ترد آه أو لا
            d.show()
            Thread {
                val hdr = HashMap<String, String>()
                if (!ua.isNullOrEmpty()) hdr["User-Agent"] = ua
                try { CookieManager.getInstance().getCookie(url)?.let { hdr["Cookie"] = it } } catch (_: Throwable) {}
                if (tab.url.isNotEmpty()) hdr["Referer"] = tab.url
                val sz = try { Downloader.sizeOf(url, hdr) } catch (_: Throwable) { -1L }
                val bps = NetLedger.lastSpeed()
                h.post {
                    sizeV.text = if (sz > 0) "📦 الحجم: " + Sniff.fmtSize(sz) else "📦 الحجم: مش معروف"
                    etaV.text = if (sz > 0 && bps > 0) { val s = sz / bps; "⏱ المدة: حوالي " + (if (s >= 3600) "${s / 3600} س ${(s % 3600) / 60} د" else if (s >= 60) "${s / 60} د ${s % 60} ث" else "${maxOf(s, 1)} ث") + " (حسب آخر سرعة: " + String.format("%.1f", bps * 8 / 1_000_000.0) + " Mbps)" } else "⏱ المدة: مش معروفة"
                }
            }.also { it.isDaemon = true }.start()
        } catch (e: Throwable) { LogStore.err("Browser:dlDialog", e); showDl(name) { startDownload(url, ua, name, mime) } }
    }

    // ===== (v838) فحص أوتوماتيك: من غير دوس على الفيديو ولا على 🔎 =====
    // أول ما الصفحة تتفتح (وبعدها كذا مرة) بنفحص الـDOM: الفيديوهات بتتضاف للاصطياد، والملفات/البرامج ولينكات الرفع بنتأكد
    // الأول (من غير تحميل) إنها ملف حقيقي — مش صفحة ولا إعلان ولا دوّامة تحويلات — وبعدين بتتعدّ على الدايرة وبتظهر رسالة بالعدد.
    private class AutoSnap(val gen: Int) {
        val files = ArrayList<ScanItem>()   // ملفات/برامج اتأكدنا إنها ملف حقيقي
        val hosts = ArrayList<ScanItem>()   // لينكات مواقع رفع اتحوّلت لرابط مباشر واتأكدنا إنه ملف حقيقي (title = اللينك الأصلي)
        var pages: List<ScanItem> = emptyList()   // صفحات فيديو / مشغّلات مدمجة (مش متأكد منها، بتتفتح بدوسة)
        val tried = HashSet<String>()
        var hostTries = 0; var srvTries = 0
        var pending = 0; var last = 0L; var hunts = 0   // (v851) hunts = كام مرة فحصنا جافا الصفحة
        var sv = 0; var sf = 0; var sl = 0   // اللي اتعلن عنه قبل كده (فيديو · ملفات · لينكات)
        var cand = 0; var fake = 0; var vfy = 0   // (v980) المسح الخلفي: لينكات اتجرّبت · اللي طلع مزيّف · فحوصات شغّالة دلوقتي
        var deepAt = 0L; var watching = false; var deepOnCatch = false
    }
    private val snaps = HashMap<Long, AutoSnap>()
    private val verifyPool by lazy { java.util.concurrent.Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } } }
    private fun snapOf(t: BTab): AutoSnap { val s0 = snaps[t.id]; if (s0 != null && s0.gen == t.gen) return s0; return AutoSnap(t.gen).also { snaps[t.id] = it } }
    private fun curSnap(): AutoSnap? { val t = tabs.getOrNull(curIdx) ?: return null; val s0 = snaps[t.id] ?: return null; return if (s0.gen == t.gen) s0 else null }
    private fun hasExtras(): Boolean { val s0 = curSnap() ?: return false; return s0.files.isNotEmpty() || s0.hosts.isNotEmpty() }
    private fun autoScanOn(): Boolean = try { Cfg.str("auto_scan", "1") == "1" } catch (_: Throwable) { true }

    // (v842) ألعاب الفلاش (Ruffle): الكيبورد كان بيظهر ويختفي. السبب المرجّح: adjustResize بيصغّر الـWebView أول ما الكيبورد يطلع
    // فالصفحة بتاخد resize وبتشيل التركيز من خانة الكتابة فالكيبورد يتقفل ويتفتح تاني في لوب. الحل: في صفحات الفلاش بس
    // نخلّي الشاشة تتحرّك (adjustPan) من غير ما نغيّر حجم الصفحة. باقي المواقع زي ما هي (adjustResize).
    private var kbPan = false
    private fun setKbPan(on: Boolean) {
        if (kbPan == on) return
        kbPan = on
        try { window.setSoftInputMode(if (on) android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN else android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) } catch (_: Throwable) {}
    }
    private val FLASH_JS = """(function(){try{if(window.RufflePlayer)return true;if(document.querySelector('ruffle-player,ruffle-embed,ruffle-object'))return true;var e=document.querySelectorAll('embed,object');for(var i=0;i<e.length;i++){var u=(e[i].src||e[i].data||'')+'';var ty=(e[i].type||'')+'';if(/\.swf(\?|#|$)/i.test(u)||/shockwave/i.test(ty))return true}return false}catch(x){return false}})()"""
    private fun scheduleFlashKb(t: BTab) {
        val g = t.gen
        for (d in longArrayOf(800L, 2500L, 6000L)) h.postDelayed({
            if (dead || t.gen != g || t !== tabs.getOrNull(curIdx)) return@postDelayed
            try { t.wv?.evaluateJavascript(FLASH_JS) { r -> if (!dead && t === tabs.getOrNull(curIdx)) setKbPan(r == "true") } } catch (_: Throwable) {}
        }, d)
    }

    private fun scheduleAutoScan(t: BTab, delays: LongArray) {
        if (!autoScanOn()) return
        val g = t.gen
        for (d in delays) h.postDelayed({ autoScan(t, g, d <= 9000L, false) }, d)
        h.postDelayed({ if (!dead && t.gen == g) deepScan(t, false) }, 5000L)   // (v980) المسح العميق أوتوماتيك من غير دوس
    }

    /** مرّة فحص: تشغيل مكتوم للفيديوهات (اختياري) + لمّ الـDOM + تأكيد الملفات. manual = من زرار 🔎 */
    private fun autoScan(t: BTab, g: Int, play: Boolean, manual: Boolean, deep: Boolean = false) {
        if (dead || t.gen != g || t.hold != null || t.blockedUrl != null) return
        if (t !== tabs.getOrNull(curIdx) || customView != null) return
        if (!manual && !autoScanOn()) return
        val wv = t.wv ?: return
        val page = wv.url ?: t.url
        if (!page.startsWith("http", true)) return
        val sn = snapOf(t)
        val now = android.os.SystemClock.uptimeMillis()
        if (!manual && now - sn.last < 900L) return
        sn.last = now
        val noAuto = prefs().contains("sv_autoplay") && !prefs().getBoolean("sv_autoplay", true)   // توفير الداتا قافل التشغيل التلقائي
        if (play && (manual || !noAuto)) {
            try {
                wv.evaluateJavascript(if (manual) PageScan.PLAY_JS else PageScan.AUTO_PLAY_JS, null)   // فيديوهات الصفحة بتشتغل مكتومة فطلباتها بتتلقط (السنيفر) من غير ما تدوس عليها
                if (!manual) h.postDelayed({ if (!dead) try { wv.evaluateJavascript(PageScan.AUTO_PAUSE_JS, null) } catch (_: Throwable) {} }, 2000L)
            } catch (_: Throwable) {}
        }
        try {
            wv.evaluateJavascript(PageScan.DOM_JS) { r ->
                if (dead || t.gen != g) return@evaluateJavascript
                var list: List<ScanItem> = emptyList()
                try { list = PageScan.parse((JSONTokener(r ?: "").nextValue() as? String) ?: "[]", page) } catch (e: Throwable) { LogStore.err("Browser:autoScan", e) }
                val ua = uaOf(t)
                list.filter { it.cat == PageScan.C_VIDEO && (manual || deep || !it.viaLink) }.take(if (manual || deep) 30 else 12).forEach { addFound(Found(it.url, Sniff.kindOf(it.url), "", page, ua), t, g) }
                sn.pages = list.filter { it.cat == PageScan.C_PAGE }
                if (sn.hunts < (if (deep) 4 else 2) && (manual || deep || list.none { it.cat == PageScan.C_VIDEO })) { sn.hunts++; autoHunt(t, g, sn, wv, page, ua, manual) }   // (v851) مفيش فيديو ظاهر: افحص الجافا والمشغّلات الخارجية
                autoServers(t, g, sn, sn.pages.filter { it.title.startsWith(PageScan.SRV_TAG) || (deep && it.title == "مشغّل مدمج" && !PageScan.needsDispatcher(it.url)) }, ua, page)   // (v850) كل سيرفرات الفيلم مش السيرفر المفتوح بس
                verifyAuto(t, g, sn, list.filter { it.cat == PageScan.C_FILE || it.cat == PageScan.C_HOST }, ua.ifBlank { PageScan.UA }, page)
            }
        } catch (e: Throwable) { LogStore.err("Browser:autoScan2", e) }
    }

    /** (v851) فحص جافا الصفحة (inline + خارجي، مضغوط/base64) + دخول المشغّلات الخارجية بـReferer الصفحة؛ اللي محتاج جافا يشتغل بيتفتح في متصفح مخفي */
    private fun autoHunt(t: BTab, g: Int, sn: AutoSnap, wv: WebView, page: String, ua: String, manual: Boolean) {
        if (PageScan.needsDispatcher(page)) return
        try {
            wv.evaluateJavascript(PageScan.SCRIPT_JS) { r ->
                if (dead || t.gen != g) return@evaluateJavascript
                var seed = ""; var scripts: List<String> = emptyList(); var frames: List<String> = emptyList(); var live: List<String> = emptyList()
                try {
                    val o = JSONObject((JSONTokener(r ?: "").nextValue() as? String) ?: "{}")
                    fun arr(k: String): List<String> = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
                    seed = o.optString("i"); scripts = arr("s"); frames = arr("f"); live = arr("p")
                } catch (e: Throwable) { LogStore.err("Browser:huntJs", e) }
                sn.pending++
                verifyPool.execute {
                    val res = try { StreamHunt.hunt(page, ua.ifBlank { PageScan.UA }, seed, scripts, frames) } catch (e: Throwable) { LogStore.err("Browser:hunt", e); null }
                    val more = ArrayList<ClipVideo>()
                    res?.embeds?.take(2)?.forEach { (eu, rf) ->
                        if (sn.tried.add("hunt:" + Sniff.key(eu))) try { ClipWatch.resolve(this, eu, rf)?.takeIf { it.playUrl.isNotEmpty() }?.let { more.add(it) } } catch (e: Throwable) { LogStore.err("Browser:huntWv", e) }
                    }
                    runOnUiThread {
                        sn.pending--
                        if (dead || t.gen != g) return@runOnUiThread
                        var n = 0
                        live.mapNotNull { Sniff.accept(it) }.forEach { addFound(Found(it, Sniff.kindOf(it), "", page, ua), t, g); n++ }
                        res?.hits?.forEach { addFound(Found(it.url, Sniff.kindOf(it.url), "", it.ref, ua), t, g); n++ }
                        more.forEach { v -> addFound(Found(v.playUrl, v.kind, v.title, v.ref.ifEmpty { page }, v.ua.ifEmpty { ua }), t, g); n++ }
                        if (manual && n == 0) Notice.show(this, "🔎 فحصت الجافا والمشغّلات ومالقيتش لينك فيديو", 3000L)
                    }
                }
            }
        } catch (e: Throwable) { LogStore.err("Browser:autoHunt", e) }
    }

    /** (v850) سيرفرات الفيلم (أزرار فيها data-embed/data-url…): كل واحد بيتفتح في الخلفية وبيتسحب فيديوه ويتضاف للاصطياد — حد أقصى 8 */
    private fun autoServers(t: BTab, g: Int, sn: AutoSnap, servers: List<ScanItem>, ua: String, page: String) {
        for (x in servers) {
            if (sn.srvTries >= 8) break
            val k = "srv:" + Sniff.key(x.url)
            if (k in sn.tried) continue
            sn.tried.add(k); sn.srvTries++; sn.pending++; sn.cand++
            verifyPool.execute {
                val v = try { ClipWatch.resolve(this, x.url, page) } catch (e: Throwable) { LogStore.err("Browser:srv", e); null }   // (v851) Referer = صفحة الفيلم
                runOnUiThread {
                    sn.pending--
                    if (dead || t.gen != g) return@runOnUiThread
                    if (v == null || v.playUrl.isEmpty()) { sn.fake++; return@runOnUiThread }   // (v980) السيرفر ماطلعش منه فيديو
                    addFound(Found(v.playUrl, v.kind, v.title, v.ref.ifEmpty { x.url }, v.ua.ifEmpty { ua }), t, g)
                }
            }
        }
    }

    /** بيتأكد (على thread خلفي، من غير تحميل) إن كل ملف/لينك رفع ملف حقيقي؛ اللي ينجح بيتضاف للعدّاد */
    private fun verifyAuto(t: BTab, g: Int, sn: AutoSnap, cands: List<ScanItem>, ua: String, page: String) {
        for (x in cands) {
            if (sn.tried.size >= 60) break
            val k = Sniff.key(x.url)
            if (k in sn.tried) continue
            if (x.cat == PageScan.C_HOST) { if (sn.hostTries >= 8) continue; sn.hostTries++ }
            sn.tried.add(k)
            sn.pending++; sn.cand++
            verifyPool.execute {
                var real: PageScan.RealFile? = null
                try {
                    val ck: (String) -> String = { u -> try { CookieManager.getInstance().getCookie(u) ?: "" } catch (_: Throwable) { "" } }
                    real = if (x.cat == PageScan.C_HOST) PageScan.resolve(x.url, ua)?.let { r -> PageScan.probeReal(r.url, ua, page, ck) } else PageScan.probeReal(x.url, ua, page, ck)
                } catch (e: Throwable) { LogStore.err("Browser:verify", e) }
                val rf = real
                runOnUiThread {
                    sn.pending--
                    if (dead || t.gen != g) return@runOnUiThread
                    if (rf == null) { sn.fake++; return@runOnUiThread }   // (v980) مش ملف حقيقي
                    val key = Sniff.key(rf.url)
                    if (x.cat == PageScan.C_HOST) { if (sn.hosts.none { Sniff.key(it.url) == key }) sn.hosts.add(ScanItem(PageScan.C_HOST, rf.url, x.url, rf.name.ifBlank { x.name }, rf.ext, rf.size)) }
                    else if (sn.files.none { Sniff.key(it.url) == key }) sn.files.add(ScanItem(PageScan.C_FILE, rf.url, x.url, rf.name.ifBlank { x.name }, rf.ext, rf.size))
                    if (t === tabs.getOrNull(curIdx)) { refreshBadge(); announce(t) }
                }
            }
        }
    }

    /** رسالة واحدة بالعدد الكلي («🎬 لقط 2 فيديو · 📦 1 ملف/برنامج · 🔗 1 رابط تحميل») كل ما يزيد حاجة؛ بتتجمّع في لحظة واحدة */
    private var noteQueued = false
    private fun announce(t: BTab) {
        if (noteQueued) return
        noteQueued = true
        h.postDelayed({ noteQueued = false; announceNow(t) }, 700L)
    }
    private fun announceNow(t: BTab) {
        if (dead || t !== tabs.getOrNull(curIdx)) return
        val sn = snapOf(t)
        val v = groupsOf(vis()).size; val nf = sn.files.size; val nl = sn.hosts.size
        val up = v > sn.sv || nf > sn.sf || nl > sn.sl
        sn.sv = v; sn.sf = nf; sn.sl = nl
        if (!up || dlg?.isShowing == true) return
        val parts = ArrayList<String>()
        if (v > 0) parts.add("🎬 لقط $v فيديو")
        if (nf > 0) parts.add("📦 $nf ملف/برنامج")
        if (nl > 0) parts.add("🔗 $nl رابط تحميل")
        Notice.show(this, parts.joinToString(" · "), 2600L)
    }

    // ===== (v980) المسح العميق الأوتوماتيك: بيشتغل في الخلفية من غير نتيجة بتظهر؛ اللينك لازم يتأكد (حجم/فيديو حقيقي) قبل ما يتعدّ =====
    /** report=true (دوسة العدسة): لو خلّص ومفيش لينك حقيقي يقولك. الفحص نفسه (addFound/verifyFound) بيرفض أي لينك مزيّف */
    private fun deepScan(t: BTab, report: Boolean) {
        if (dead || !autoScanOn() || t.priv || t.hold != null || t.blockedUrl != null) return
        val page = t.wv?.url ?: t.url
        if (!page.startsWith("http", true) || PageScan.needsDispatcher(page)) return
        val sn = snapOf(t); val g = t.gen
        val now = android.os.SystemClock.uptimeMillis()
        if (now - sn.deepAt < 15000L) return
        sn.deepAt = now
        autoScan(t, g, true, false, true)
        h.postDelayed({ if (!dead && t.gen == g) autoScan(t, g, true, false, true) }, 4000L)   // ثانية مرة: مشغّلات اتحمّلت متأخر
        if (report && !sn.watching) { sn.watching = true; watchDeep(t, g, sn, 0) }
    }
    private fun watchDeep(t: BTab, g: Int, sn: AutoSnap, waited: Int) {
        h.postDelayed({
            if (dead || t.gen != g) { sn.watching = false; return@postDelayed }
            val w = waited + 700
            val busy = sn.pending > 0 || sn.vfy > 0
            if ((busy && w < 30000) || w < 6000) { watchDeep(t, g, sn, w); return@postDelayed }
            sn.watching = false
            if (t !== tabs.getOrNull(curIdx) || dlg?.isShowing == true) return@postDelayed
            val real = items.count { it.tabId == t.id && it.gen == g } + sn.files.size + sn.hosts.size
            if (real > 0) return@postDelayed
            if (sn.cand > 0) Notice.show(this, "🔎 جرّبت " + sn.cand + " لينك وكلهم مش حقيقيين (مفيش فيديو فعلي ولا حجم)", 4200L)
            else Notice.show(this, "🔎 مفيش لينكات فيديو حقيقية في الصفحة دي", 3200L)
        }, 700L)
    }

    // ===== (v837) زرار 🔎: فحص يدوي — بيشغّل نفس الفحص الأوتوماتيك دلوقتي ويفتح النتيجة (الملفات والروابط المتأكَّد منها بس) =====
    private var scanBusy = false
    private fun pageScan() {
        if (dead || scanBusy) return
        val t = tabs.getOrNull(curIdx) ?: return
        val wv = t.wv ?: return
        val page = wv.url ?: t.url
        if (!page.startsWith("http", true)) { Notice.show(this, "افتح صفحة موقع الأول", 1800L); return }
        scanBusy = true
        Notice.show(this, "🔎 بفحص الصفحة…", 2200L)
        val g = t.gen; val sn = snapOf(t)
        autoScan(t, g, true, true)
        var waited = 0
        lateinit var poll: Runnable
        poll = Runnable {
            waited += 500
            if (dead || t.gen != g) { scanBusy = false; return@Runnable }
            if (sn.pending > 0 && waited < 9000) { h.postDelayed(poll, 500L); return@Runnable }   // لسه بنتأكد إن الملفات حقيقية
            scanBusy = false
            if (tabs.contains(t)) showScanResults(sn.hosts + sn.files + sn.pages.take(15), page, uaOf(t), t)
        }
        h.postDelayed(poll, 2200L)
    }
    private fun showAutoResults() {
        val t = tabs.getOrNull(curIdx) ?: return
        val sn = snapOf(t)
        showScanResults(sn.hosts + sn.files + sn.pages.take(15), wvOrNull?.url ?: t.url, uaOf(t), t)
    }
    private fun showScanResults(list: List<ScanItem>, page: String, ua: String, t: BTab) {
        val n = groupsOf(vis()).size
        val hosts = list.filter { it.cat == PageScan.C_HOST }
        val files = list.filter { it.cat == PageScan.C_FILE }
        val pages = list.filter { it.cat == PageScan.C_PAGE }
        if (n == 0 && hosts.isEmpty() && files.isEmpty() && pages.isEmpty()) {
            Notice.show(this, "🔎 مفيش لينكات تتحمّل — بدوّر على فيديو في الصفحة نفسها…", 2600L)
            resolvePage(ScanItem(PageScan.C_PAGE, page, "", PageScan.niceName(page)), ua, t, false)
            return
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        lateinit var d: Dialog
        fun head(x: String) = box.addView(ui.text(x, 13.5f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        fun row(label: String, sub: String, act: () -> Unit) = box.addView(ui.button(label.take(70) + "\n" + sub.take(60)) { d.dismiss(); act() })
        fun sz(x: ScanItem) = if (x.size > 0) " · " + Sniff.fmtSize(x.size) else ""
        if (n > 0) {
            head("🎬 فيديوهات ($n)")
            box.addView(ui.button("🎬 افتح الفيديوهات الملقوطة — تحميل · تلفزيون · مشاهدة", true) { d.dismiss(); showList() })
        }
        if (hosts.isNotEmpty()) {
            head("🔗 روابط رفع (${hosts.size}) — ملفات حقيقية")
            hosts.forEach { x -> row("🔗 " + x.name, x.ext.uppercase() + sz(x) + " · " + PageScan.hostOf(x.title.ifBlank { x.url })) { resolveHost(x, ua) } }
        }
        if (files.isNotEmpty()) {
            head("📦 ملفات وبرامج (${files.size}) — ملفات حقيقية")
            files.forEach { x -> row("⬇ " + x.name, x.ext.uppercase() + sz(x) + " · " + PageScan.hostOf(x.url)) { dlDialog(x.name, x.url, ua, null) } }
        }
        if (pages.isNotEmpty()) {
            head("📄 صفحات فيديو (${pages.size}) — دوس يجيب الفيديو")
            pages.forEach { x -> row("🎞 " + x.title.ifBlank { x.name }, PageScan.hostOf(x.url)) { resolvePage(x, ua, t, true) } }
        }
        d = sheet("🔎 نتيجة فحص الصفحة", box); d.show()
    }
    /** لينك موقع رفع (MediaFire …) ← رابط تحميل مباشر ← نافذة التحميل على طول (لو اتحوّل واتأكد قبل كده بيفتح النافذة بالرابط المباشر على طول) */
    private fun resolveHost(x: ScanItem, ua: String) {
        if (!PageScan.isHostLink(x.url)) { dlDialog(x.name, x.url, ua, null); return }
        Notice.show(this, "⏳ بجيب رابط التحميل المباشر…", 2500L)
        Thread {
            val r = try { PageScan.resolve(x.url, ua) } catch (e: Throwable) { LogStore.err("Browser:resolveHost", e); null }
            runOnUiThread {
                if (dead) return@runOnUiThread
                if (r == null) { Notice.show(this, "❌ ماقدرتش أجيب الرابط المباشر — بفتح الصفحة", 3200L); newTab(x.url, true, tab.id); return@runOnUiThread }
                dlDialog(r.name.ifBlank { x.name }, r.url, ua, null)
            }
        }.apply { isDaemon = true }.start()
    }
    /** صفحة فيديو (أو الصفحة الحالية) ← متصفح مخفي بيسحب الفيديو ← يتضاف للاصطياد ويفتح القايمة (تلفزيون · تحميل · مشاهدة) */
    private fun resolvePage(x: ScanItem, ua: String, t: BTab, openOnFail: Boolean) {
        if (PageScan.needsDispatcher(x.url)) { Links.sheet(this, ui, x.url, x.title, "browser", false) { persistTabs() }; return }
        Notice.show(this, "⏳ بفتح الصفحة في الخلفية وبسحب الفيديو…", 3000L)
        Thread {
            val v = try { ClipWatch.resolve(this, x.url) } catch (e: Throwable) { LogStore.err("Browser:resolvePage", e); null }
            runOnUiThread {
                if (dead) return@runOnUiThread
                if (v == null || v.playUrl.isEmpty()) {
                    Notice.show(this, "❌ مالقيتش فيديو مباشر في الصفحة دي", 3000L)
                    if (openOnFail) newTab(x.url, true, t.id)
                    return@runOnUiThread
                }
                addFound(Found(v.playUrl, v.kind, v.title, v.ref.ifEmpty { x.url }, v.ua.ifEmpty { ua }), t)
                h.postDelayed({ if (!dead) showList() }, 900L)   // نستنى فحص الحجم يخلص عشان يظهر في القايمة
            }
        }.apply { isDaemon = true }.start()
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
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && fanViews.isNotEmpty()) {   // (v801) دوسة في أي مكان برّا الأزرار = تتصغّر لعلامة البحث بس (والدوسة بتكمّل عادي)
            val rx = ev.rawX.toInt(); val ry = ev.rawY.toInt(); var inside = false
            val r = android.graphics.Rect()
            for (v in fanViews + listOf<View>(badge)) { try { if (v.getGlobalVisibleRect(r) && r.contains(rx, ry)) { inside = true; break } } catch (_: Throwable) {} }
            if (!inside) closeFan()
        }
        // (v852) اتشال: دوسة على الفيديو ماعادتش بتفتح الأزرار — الدايرة بس بتكتب عدد الفيديوهات، ودوسة عليها = دواير الفيديوهات
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP && ev.eventTime - ev.downTime < 450L && actScrim == null) {   // (v716) دوسة في الصفحة نفسها = الدايرة تدوّر على فيديو
            try { val r = android.graphics.Rect(); if (webHolder.getGlobalVisibleRect(r) && r.contains(ev.rawX.toInt(), ev.rawY.toInt())) kickScan() } catch (_: Throwable) {}
        }
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
        if (adOn && AdBlock.blocked(f.url)) return   // (v722) فيديو إعلان (شبكة إعلانات/مراهنات) مش بيتحسب لقطة
        f.tabId = t.id; f.gen = g
        if (g != t.gen) return   // لقطة من صفحة قديمة
        if (!keys.add("${t.id}:$g:" + Sniff.key(f.url))) return
        if (f.kind.startsWith("YT")) { if (f.size > 0) { accept(f); return }; probe(f) { accept(f) }; return }   // يوتيوب زي ما كان
        // (v976) كل لينك ملقوط بيتفحص فعلًا قبل ما يظهر: لازم يرد بفيديو/قايمة HLS حقيقية (مش صفحة HTML / 403 / 404 / لينك منتهي) ولو HLS أول مقطع فيها لازم يتحمّل.
        // بيجرّب Referer/Origin بتوع المشغّل الخارجي ثم صفحة المتصفح ثم أصل اللينك ثم من غير — واللي ينجح بيتثبّت على اللقطة (المشغّل والتحميل بياخدوه)
        val sn0 = snapOf(t); sn0.cand++; sn0.vfy++
        verifyFound(f) { ok -> sn0.vfy--; if (ok) accept(f) else rejectFake(f) }   // أي فيديو أقل من 3 ميجا (إعلانات غالبًا) بيتتجاهل في accept
    }
    private class Peek(val code: Int, val mime: String, val body: ByteArray, val total: Long)
    /** (v976) أول maxBytes بايت من اللينك (GET Range) بالهيدرز دي. null = مفيش رد (شبكة/مهلة) */
    private fun httpPeek(url: String, hd: Map<String, String>, maxBytes: Int): Peek? {
        var c: java.net.HttpURLConnection? = null
        return try {
            c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 9000; c.instanceFollowRedirects = true
            c.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            for ((k, v) in hd) c.setRequestProperty(k, v)
            val code = c.responseCode
            val mime = c.contentType ?: ""
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: (if (code == 200) c.contentLengthLong else -1L)
            val st = if (code in 200..299) c.inputStream else null
            val buf = java.io.ByteArrayOutputStream()
            if (st != null) { val b = ByteArray(4096); while (buf.size() < maxBytes) { val n = st.read(b, 0, minOf(b.size, maxBytes - buf.size())); if (n <= 0) break; buf.write(b, 0, n) } }
            Peek(code, mime, buf.toByteArray(), total)
        } catch (_: Throwable) { null } finally { try { c?.disconnect() } catch (_: Throwable) {} }
    }
    private class VRes(val ok: Boolean, val definite: Boolean, val size: Long, val mime: String)   // definite = السيرفر ردّ ورفض (مش مهلة شبكة)
    private fun looksHtml(p: Peek): Boolean {
        val m = p.mime.lowercase()
        if (m.startsWith("text/html") || m.contains("json") || m.contains("xhtml")) return true
        val s = String(p.body, 0, minOf(p.body.size, 200), Charsets.ISO_8859_1).trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        return s.startsWith("<!doctype") || s.startsWith("<html") || s.startsWith("<head") || s.startsWith("<body") || s.startsWith("{")
    }
    private fun resolveUrl(base: String, ref: String): String = try { java.net.URL(java.net.URL(base), ref.trim()).toString() } catch (_: Throwable) { ref.trim() }
    private fun checkMedia(f: Found, hd: Map<String, String>): VRes {
        val pk = httpPeek(f.url, hd, 4096) ?: return VRes(false, false, -1L, "")
        if (pk.code !in 200..299) return VRes(false, true, -1L, "")
        val head = String(pk.body, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val hls = isHls(f) || head.startsWith("#EXTM3U")
        if (hls) {
            if (!head.startsWith("#EXTM3U")) return VRes(false, true, -1L, pk.mime)
            // قايمة HLS حقيقية: نتأكد إن أول مستوى وأول مقطع بيتحمّلوا (أغلب «الفيديو بيفضل 0%» بيبقى المقطع مرفوض)
            var pl = httpPeek(f.url, hd, 120_000) ?: return VRes(false, false, -1L, pk.mime)
            var plUrl = f.url
            var text = String(pl.body, Charsets.UTF_8)
            if (text.contains("#EXT-X-STREAM-INF")) {
                val v = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return VRes(false, true, -1L, pk.mime)
                plUrl = resolveUrl(f.url, v)
                pl = httpPeek(plUrl, hd, 120_000) ?: return VRes(false, false, -1L, pk.mime)
                if (pl.code !in 200..299 || !String(pl.body, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")) return VRes(false, true, -1L, pk.mime)
                text = String(pl.body, Charsets.UTF_8)
            }
            val seg = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return VRes(false, true, -1L, pk.mime)
            val sp = httpPeek(resolveUrl(plUrl, seg), hd, 2048) ?: return VRes(true, false, -1L, pk.mime)   // مهلة على المقطع: مش دليل إنه مزيّف
            if (sp.code !in 200..299 || looksHtml(sp)) return VRes(false, true, -1L, pk.mime)
            return VRes(true, true, -1L, pk.mime)
        }
        if (f.kind == "DASH") return if (head.contains("<MPD", true)) VRes(true, true, -1L, pk.mime) else VRes(false, true, -1L, pk.mime)
        if (looksHtml(pk)) return VRes(false, true, -1L, pk.mime)
        val b = pk.body
        fun u(i: Int): Int = if (i < b.size) (b[i].toInt() and 0xFF) else -1
        val magic = (u(4) == 0x66 && u(5) == 0x74 && u(6) == 0x79 && u(7) == 0x70) ||   // mp4/mov: ftyp
            (u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3) ||   // webm/mkv
            (u(0) == 0x46 && u(1) == 0x4C && u(2) == 0x56) ||   // flv
            (u(0) == 0x47 && u(188) == 0x47)   // mpeg-ts
        val m = pk.mime.lowercase()
        if (!magic && (m.startsWith("text/") || m.startsWith("image/"))) return VRes(false, true, -1L, pk.mime)   // فيديو حقيقي: video/* أو octet-stream أو بصمة ملف فيديو
        return VRes(true, true, pk.total, pk.mime)
    }
    private fun headerCandidates(f: Found): List<HashMap<String, String>> {
        val base = dlHeaders(f); base.remove("Referer"); base.remove("Origin")
        fun mk(ref: String?, org: String?): HashMap<String, String> { val m = HashMap(base); if (!ref.isNullOrEmpty()) m["Referer"] = ref; if (!org.isNullOrEmpty()) m["Origin"] = org; return m }
        val refO = Sniff.originOf(f.ref); val pageO = Sniff.originOf(f.page); val urlO = Sniff.originOf(f.url)
        val out = ArrayList<HashMap<String, String>>()
        fun add(m: HashMap<String, String>) { if (out.none { it == m }) out.add(m) }
        add(mk(f.ref.takeIf { it.isNotEmpty() }, f.origin.ifEmpty { refO ?: "" }))   // زي ما المشغّل الخارجي بعت
        add(mk(f.ref.takeIf { it.isNotEmpty() }, null))
        if (f.page.startsWith("http", true)) { add(mk(f.page, pageO)); add(mk(f.page, null)) }
        if (refO != null) add(mk("$refO/", refO))
        if (urlO != null) add(mk("$urlO/", null))
        add(mk(null, null))
        return out
    }
    private fun verifyFound(f: Found, done: (Boolean) -> Unit) {
        Thread {
            var ok = false; var definiteNo = false
            try {
                for (hd in headerCandidates(f)) {
                    val r = checkMedia(f, hd)
                    if (r.ok) {
                        ok = true
                        hd["Referer"]?.let { f.ref = it }; f.origin = hd["Origin"] ?: ""
                        if (r.size > 0 && f.size <= 0) f.size = r.size
                        if (r.mime.isNotEmpty() && f.mime.isEmpty()) f.mime = r.mime
                        break
                    }
                    if (r.definite) definiteNo = true
                }
            } catch (e: Throwable) { LogStore.err("Browser:verify", e); ok = true }   // خطأ غير متوقع: ما نخبّيش اللقطة
            val keep = ok || !definiteNo   // نخبّي بس لو السيرفر ردّ ورفض بكل الهيدرز (مش مهلة شبكة)
            if (!keep) LogStore.add("🚫 لينك ملقوط مزيّف/مرفوض اتخبّى: " + f.url.take(140))
            runOnUiThread { if (!dead) done(keep) }
        }.apply { isDaemon = true }.start()
    }
    private val huntedGen = HashSet<String>()
    /** (v976) لينك ملقوط اتبيّن إنه مزيّف: لو ما فضلش أي فيديو سليم في الصفحة بعد 3.5 ثانية، ندوّر في الصفحة والمشغّلات الخارجية (مرة واحدة لكل صفحة) */
    private fun rejectFake(f: Found) {
        val t = tabs.firstOrNull { it.id == f.tabId } ?: return
        if (f.gen != t.gen || t.priv) return
        val sf0 = snaps[t.id]; if (sf0 != null && sf0.gen == f.gen) sf0.fake++   // (v980)
        val k = "${t.id}:${t.gen}"; val g = t.gen
        if (!huntedGen.add(k)) return
        h.postDelayed({
            if (dead || !tabs.contains(t) || t.gen != g) return@postDelayed
            if (items.any { it.tabId == t.id && it.gen == g }) return@postDelayed
            val page = t.url; if (!page.startsWith("http", true)) return@postDelayed
            val ua = uaOf(t)
            Thread {
                val v = try { ClipWatch.resolve(this, page) } catch (e: Throwable) { LogStore.err("Browser:autoHunt", e); null }
                if (v == null || v.playUrl.isEmpty()) return@Thread
                runOnUiThread { if (!dead && t.gen == g) addFound(Found(v.playUrl, v.kind, v.title, v.ref.ifEmpty { page }, v.ua.ifEmpty { ua }, page), t, g) }
            }.apply { isDaemon = true }.start()
        }, 3500L)
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
        // (v715) من غير فتح القايمة لوحدها: العلامة الدايرة بتظهر وإنت تدوس عليها
        announce(t)   // (v838) رسالة بالعدد: 🎬 لقط N فيديو (+ ملفات ولينكات لو اتلقطت)
        val sn1 = snapOf(t); if (!sn1.deepOnCatch) { sn1.deepOnCatch = true; deepScan(t, false) }   // (v980) لقط فيديو ← المسح يشتغل في الخلفية لوحده
    }

    // ===== (v401) مانع البوب-أب الذكي =====
    private fun popMode(): Int = if (!adOn) PopupGuard.OFF else PopupGuard.modeOf(prefs().getString("pop_mode", "ذكي"))
    private fun adJs(): String = if (popMode() != PopupGuard.OFF) AdBlock.JS_SMART + ";" + PopupGuard.TRACK else AdBlock.JS
    private fun allowedHosts(): Set<String> = try { prefs().getStringSet("pop_allow", emptySet()) ?: emptySet() } catch (_: Throwable) { emptySet() }
    private fun setPopMode(m: String, notify: Boolean = true) {
        prefs().edit().putString("pop_mode", m).apply()
        val on = PopupGuard.modeOf(m) != PopupGuard.OFF
        for (x in tabs) try { x.wv?.settings?.javaScriptCanOpenWindowsAutomatically = on } catch (_: Throwable) {}
        if (notify) Notice.show(this, when (m) { "مقفول" -> "مانع البوب-أب اتقفل"; "صارم" -> "مانع البوب-أب: صارم"; else -> "مانع البوب-أب: ذكي" }, 2200L)
    }
    // (v839) مستوى الحماية = مانع الإعلانات + وضع البوب-أب (من نفس المفاتيح القديمة: adblock · pop_mode — مفيش مفتاح جديد)
    private val PROT = listOf("مقفول", "خفيف", "متوسط", "قوي")
    private fun protLevel(): String {
        if (!adOn) return "مقفول"
        return when (PopupGuard.modeOf(prefs().getString("pop_mode", "ذكي"))) { PopupGuard.OFF -> "خفيف"; PopupGuard.STRICT -> "قوي"; else -> "متوسط" }
    }
    private fun protDescOf(l: String): String = when (l) {
        "مقفول" -> "من غير أي حماية — الإعلانات والبوب-أب بتظهر زي ما هي."
        "خفيف" -> "بيمنع الإعلانات المعروفة بس. البوب-أب شغّال عادي."
        "قوي" -> "إعلانات + بوب-أب صارم: مفيش نافذة بتتفتح غير بلينك حقيقي دوست عليه. ممكن بعض مواقع الفيديو ما تفتحش تبويب التشغيل — «افتحه» من 🚫 مانع البوب-أب."
        else -> "إعلانات + بوب-أب ذكي: الإعلان بيتقفل، وصفحة التحويل لمشاهدة الفيديو بتتفتح تبويب. (الموصى بيه)"
    }
    private fun setProtection(l: String) {
        adOn = l != "مقفول"
        prefs().edit().putBoolean("adblock", adOn).apply()
        if (::shield.isInitialized) { shield.text = if (adOn) "🛡 $blockedN" else "🛡✗"; shield.alpha = if (adOn) 1f else 0.5f }
        setPopMode(when (l) { "قوي" -> "صارم"; "متوسط" -> "ذكي"; else -> "مقفول" }, false)
        Notice.show(this, "🛡 الحماية: $l", 2000L)
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
            else -> {
                try {
                    val nw = PopupGuard.learn(hd, v.score, allowedHosts())
                    if (nw.isNotEmpty()) prefs().edit().putStringSet("pop_learned", HashSet(PopupGuard.learned)).apply()
                } catch (_: Throwable) {}
                popKill(hd, fin, v.why)
            }
        }
    }

    /** صفحة سليمة (مشاهدة/لينك حقيقي): تتحوّل لتبويب عادي وتتفتح */
    private fun popPromote(hd: PopupGuard.Hold) {
        if (hd.done) return
        hd.done = true; hd.timer?.let { h.removeCallbacks(it) }; holds.remove(hd)
        val t = hd.tab; t.hold = null
        tabs.add(t); refreshTabsCount(); persistTabs()
        select(tabs.size - 1)
        scheduleAutoScan(t, longArrayOf(1500L, 5000L, 12000L))   // (v980) صفحة البوب-أب كانت بتخلّص تحميل وهي مش الحالية فالفحص كان بيتخطاها
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
        try { if (PopupGuard.learned.remove(PopupGuard.reg(host))) prefs().edit().putStringSet("pop_learned", HashSet(PopupGuard.learned)).apply() } catch (_: Throwable) {}   // (v816)
        try { val cur = HashSet(allowedHosts()); cur.add(PopupGuard.reg(host)); prefs().edit().putStringSet("pop_allow", cur).apply() } catch (_: Throwable) {}
    }

    /** ضغطة طويلة على 🛡: الوضع + آخر اللي اتمنع */
    private fun showPopupSheet() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        box.addView(ui.noHint().apply { setPadding(0, 0, 0, ui.dp(8)) })
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
        box.addView(ui.button("🧹 امسح المسموح والمتعلَّم") { prefs().edit().remove("pop_allow").remove("pop_learned").apply(); PopupGuard.learned.clear(); Notice.show(this, "اتمسحت", 1800L) }, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(10) })
        sheet("🚫 مانع البوب-أب", box).show()
    }

    // ===== (v203) صفحة جديدة = اصطياد جديد =====
    private val NOISE = Regex("[?&](t|start|time_continue|feature|si|fbclid|pp|utm_[a-z]+)=[^&#]*", RegexOption.IGNORE_CASE)
    private fun newPage(t: BTab, url: String?) {
        val u = (url ?: return).substringBefore('#').replace(NOISE, "")
        if (u.isEmpty() || u.startsWith("data:") || u == t.capUrl) return
        if (panicUrl != null && t.id == panicTabId && !u.contains("google.")) { panicUrl = null; if (::undoB.isInitialized) undoB.visibility = View.GONE }   // (v231) مشيت لصفحة تانية: مفيش تراجع
        t.capUrl = u; t.gen++; markHidden = false
        snaps.remove(t.id)   // (v838) صفحة جديدة = عدّاد ملفات/لينكات جديد
        scheduleAutoScan(t, longArrayOf(3000L, 8000L, 16000L, 30000L))
        items.removeAll { it.tabId == t.id && it.gen != t.gen }
        keys.removeAll { it.startsWith("${t.id}:") && !it.startsWith("${t.id}:${t.gen}:") }
        if (t === tabs.getOrNull(curIdx)) { webGroups.clear(); autoShown = false; openKey = null; refreshBadge() }
    }
    /** اللقطات الحالية للتبويب اللي قدامك (صفحته الحالية بس) */
    private fun vis(): List<Found> { val t = tabs.getOrNull(curIdx) ?: return emptyList(); return items.filter { it.tabId == t.id && it.gen == t.gen } }
    /** (v716) دوسة على الدايرة: فيديو واحد ← لوحة الأزرار (تلفزيون · تحميل · شاهد وترجم · شاهد · إلغاء)، غير كده ← القايمة. «تحميل» بيسأل عن الجودة */
    private fun badgeTap() {
        try {
            if (fanViews.isNotEmpty()) { closeFan(); return }   // (v801) الدايرة والأزرار مفتوحة حواليها: دوسة عليها = تصغير
            val gs = groupsOf(vis())
            if (gs.isEmpty() && hasExtras()) { showAutoResults(); return }   // (v838) مفيش فيديو بس فيه ملفات/لينكات حقيقية
            if (!tvOn) {   // (v975) موبايل: مفيش شاشة اصطياد قديمة (لا جنب ولا نص) — مفيش فيديو = إشعار صغير · فيديو واحد = الأزرار حوالين الدايرة · أكتر = دواير الفيديوهات ثم الأزرار
                if (gs.isEmpty()) { Notice.show(this, "🔍 لسه بدوّر على فيديو — شغّله في الصفحة لو ما ظهرش", 2600L); kickScan(); return }
                if (gs.all { it.qs.isNotEmpty() }) { if (gs.size == 1) openFan(gs[0]) else showPick(gs) }
                else Notice.show(this, "⏳ بجهّز الفيديو… جرّب تاني بعد ثانيتين", 2200L)
                return
            }
            if (gs.size == 1 && gs[0].qs.isNotEmpty()) showActions(gs[0])   // (v852) فيديو واحد: اسمه + الأزرار (من غير ما تتنشر حوالين الدايرة)
            else if (gs.size > 1 && !tvOn && gs.all { it.qs.isNotEmpty() }) showPick(gs)   // (v852) أكتر من فيديو: دايرة لكل فيديو فوق الدايرة + اسمه
            else showList()
        } catch (e: Throwable) { LogStore.err("Browser:badgeTap", e); showList() }
    }


    // ===== (v801) الشريط فوق الشريط السفلي (موبايل): كل الأزرار العايمة اتجمّعت هنا =====
    private fun panelOn(): Boolean = try { Cfg.str("vid_panel", "0") == "1" } catch (_: Throwable) { false }
    private fun stripBtn(t: String, sp: Float = 18f, w: Int = 40, onTap: () -> Unit): TextView = IconTextView(this).apply {
        text = t; textSize = sp; gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
        background = ui.box(0xCC1E2A3A.toInt(), 0x66FFFFFF, 20)
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(ui.dp(w), ui.dp(38)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }
    }
    private fun coPill(t: String, onTap: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; includeFontPadding = false
        setOnClickListener { onTap() }
        background = android.graphics.drawable.GradientDrawable().apply { setColor(coOff); cornerRadius = ui.dp(19).toFloat(); setStroke(ui.dp(1), 0x66FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(38)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) }
    }
    /** صفين فوق الشريط السفلي: فوق = أدوات (🕶 · 📺① · 🕘 · 🛡 · 🔄 · ألسنة التلفزيون 📺 🎮 ▶) · تحت = ‹ › (شمال) · دايرة الاصطياد (نص) · 🚨 (يمين) */
    private fun buildActStrip(rotFloat: TextView) {
        // (v808) الأدوات كلها (🕶 · 📺① · 🕘 · 🛡 · 🔄 · ألسنة التلفزيون) بقت عمود رأسي مقفول، بيفتح لفوق بزرار واحد فوق زرار الرجوع
        fun vm(v: View) { (v.layoutParams as? LinearLayout.LayoutParams)?.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
        toolsInner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
        val bTab = stripBtn("🕶") { newTab(home(), true, -1L, true) }   // تصفح مخفي
        // (v848) 🕶 اتنقل برة عمود الأدوات: ظاهر دايمًا (جنب ⚙️ و☰)
        bTab.layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), 0) }
        val co = coPill("📺①") { toggleCoSurf() }; coBtn1 = co; toolsInner.addView(co); vm(co)   // مزامنة المتصفح مع التلفزيون
        val bHist = stripBtn("🕘") { showHistory() }   // السجل — دخول سريع
        toolsInner.addView(bHist); vm(bHist)
        ctl.setPadding(0, 0, 0, 0); (shield.layoutParams as? LinearLayout.LayoutParams)?.let { it.setMargins(0, 0, 0, 0); it.height = ui.dp(38) }
        shield.gravity = Gravity.CENTER; shield.setPadding(ui.dp(6), 0, ui.dp(6), 0)
        toolsInner.addView(ctl, LinearLayout.LayoutParams(-2, ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) })   // 🛡 عدّاد الإعلانات
        (rotFloat.parent as? ViewGroup)?.removeView(rotFloat)
        rotFloat.layoutParams = LinearLayout.LayoutParams(ui.dp(38), ui.dp(38)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
        // (v848) 🔄 المخفي اتشال: زرار التدوير الوحيد بقى في الهيدر (تدوير الشاشة فضل في قايمة ⋮)

        // الزرار الواحد: دوسة = العمود يتفرد لفوق · دوسة تانية = يتقفل
        var toolsOpen = false
        lateinit var toolsToggle: TextView
        // (v823) لاندسكيب: العمود كان بيفتح لفوق والشاشة قصيرة فأزرار كتير بتختفي ← بيتفرد بالعرض (يمين زرار ☰) بدل الطول
        fun isLand() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fun slideX() = if (isLand()) -ui.dp(16).toFloat() else 0f
        fun slideY() = if (isLand()) 0f else ui.dp(16).toFloat()
        toolsToggle = stripBtn("☰", 20f) {
            toolsOpen = !toolsOpen
            toolsToggle.text = if (toolsOpen) "✕" else "☰"
            toolsInner.animate().cancel()
            if (toolsOpen) {
                toolsInner.visibility = View.VISIBLE; toolsInner.alpha = 0f; toolsInner.translationY = slideY(); toolsInner.translationX = slideX()
                toolsInner.animate().alpha(1f).translationY(0f).translationX(0f).setDuration(170L).start()
            } else {
                toolsInner.animate().alpha(0f).translationY(slideY()).translationX(slideX()).setDuration(130L).withEndAction { if (!toolsOpen) toolsInner.visibility = View.GONE }.start()
            }
        }
        toolsToggle.layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
        // (v820) زرار ⚙️ إعدادات المتصفح: ثابت (مش عايم) فوق زرار ☰ مباشرة، والعمود المفتوح بيطلع فوقه
        val gearB = stripBtn("⚙️", 18f) { showSettings() }
        gearB.layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), ui.dp(3), ui.dp(3), 0) }
        if (tvOn) gearB.visibility = View.GONE   // التلفزيون: الإعدادات في قايمة ⋮ بالريموت
        val toolsPanel = LinearLayout(this).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        // (v823) بورتريه: عمود رأسي (الأدوات فوق ثم ⚙️ ثم ☰) · لاندسكيب: صف أفقي (☰ ثم ⚙️ ثم الأدوات على يمينهم)
        toolsRelayout = {
            val land = isLand()
            toolsPanel.removeAllViews()
            toolsPanel.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            toolsPanel.gravity = if (land) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            toolsInner.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            toolsInner.gravity = if (land) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
            (gearB.layoutParams as? LinearLayout.LayoutParams)?.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), if (land) ui.dp(3) else 0)
            (bTab.layoutParams as? LinearLayout.LayoutParams)?.setMargins(ui.dp(3), ui.dp(3), ui.dp(3), if (land) ui.dp(3) else 0)
            if (land) { toolsPanel.addView(toolsToggle); toolsPanel.addView(gearB); toolsPanel.addView(bTab); toolsPanel.addView(toolsInner, LinearLayout.LayoutParams(-2, -2)) }
            else { toolsPanel.addView(toolsInner, LinearLayout.LayoutParams(-2, -2)); toolsPanel.addView(bTab); toolsPanel.addView(gearB); toolsPanel.addView(toolsToggle) }
            toolsInner.translationX = 0f; toolsInner.translationY = 0f
            toolsPanel.requestLayout()
        }
        toolsRelayout?.invoke()

        // الصف التحتاني: سهمين · الدايرة · 🚨 — بدون خلفية خالص (الأزرار نفسها موجودة والصفحة باينة وراها)
        navBackB = stripBtn("‹", 26f) { wvOrNull?.let { if (it.canGoBack()) saver.back(it, tab) } }.apply { visibility = View.GONE }
        navFwdB = stripBtn("›", 26f) { wvOrNull?.let { if (it.canGoForward()) it.goForward() } }.apply { visibility = View.GONE }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER_VERTICAL
            addView(undoB, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })   // ↩ تراجع الطوارئ: بيظهر بس بعد 🚨
            addView(navBackB); addView(navFwdB)
        }
        val bottom = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(left, FrameLayout.LayoutParams(-2, -1, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = ui.dp(4) })
            addView(badge, FrameLayout.LayoutParams(ui.dp(60), ui.dp(60), Gravity.CENTER))   // (v975) 56→60 (الحلقة بقت جوه حدودها)
            addView(panicB, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = ui.dp(6) })
            if (!tvOn) addView(stripBtn("🔎", 18f) { pageScan() }, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = ui.dp(56) })   // (v837) فحص الصفحة
        }
        actStrip = FrameLayout(this).apply {   // شفافة تمامًا وبتعدّي اللمس للصفحة إلا على الأزرار نفسها
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(toolsPanel, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin = ui.dp(4); bottomMargin = ui.dp(60) })
            addView(bottom, FrameLayout.LayoutParams(-1, ui.dp(60), Gravity.BOTTOM))
        }
        toolsHost = toolsPanel
    }

    // ===== (v801) أزرار الفيديو حوالين الدايرة (بدل العمود تحتها): بتفتح تلقائي لما تدوس على الفيديو، ودوسة في أي مكان تاني = تتصغّر =====
    private val fanViews = ArrayList<View>()
    private fun closeFan() {
        if (fanViews.isEmpty()) return
        for (v in fanViews.toList()) try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
        fanViews.clear()
    }
    private fun openFan(g: Grp) {
        if (dead || !::frame.isInitialized || badge.visibility != View.VISIBLE || badge.width == 0) return
        closeFan(); closeActions()
        val bl = IntArray(2); badge.getLocationOnScreen(bl)
        val fl = IntArray(2); frame.getLocationOnScreen(fl)
        val cx = bl[0] - fl[0] + badge.width / 2f; val cy = bl[1] - fl[1] + badge.height / 2f
        // الترتيب من اليمين لليسار (قراءة عربي): مشاهدة موبايل · بالترجمة موبايل · مشاهدة تلفزيون · بالترجمة تلفزيون · تحميل
        // (v975) التحميل في النص: مشاهدة موبايل · بالترجمة موبايل · ⬇ تحميل (نص) · مشاهدة تلفزيون · بالترجمة تلفزيون
        val acts = listOf(
            Triple("mw", "📱\nمشاهدة", 0xFF2E9E5B.toInt()), Triple("mt", "📱🌐\nبالترجمة", 0xFF1E6BFF.toInt()),
            Triple("dl", "⬇\nتحميل", 0xFF455A64.toInt()),
            Triple("tw", "📺\nمشاهدة", 0xFFE53935.toInt()), Triple("tt", "📺🌐\nبالترجمة", 0xFF7B1FA2.toInt()))
        val rad = ui.dp(108).toFloat(); val sz = ui.dp(58)
        acts.forEachIndexed { i, (act, label, fill) ->
            val ang = Math.toRadians(-20.0 - 35.0 * i)
            val x = cx + rad * Math.cos(ang).toFloat(); val y = cy + rad * Math.sin(ang).toFloat()
            val b = TextView(this).apply {
                text = label; setTextColor(Color.WHITE); textSize = 10.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; includeFontPadding = false
                maxLines = 2; setPadding(ui.dp(2), 0, ui.dp(2), 0); elevation = ui.dp(14).toFloat()
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill); setStroke(ui.dp(2), 0xCCFFFFFF.toInt()) }
                setOnClickListener { closeFan(); fanAct(act, g) }
            }
            val lp = FrameLayout.LayoutParams(sz, sz, Gravity.TOP or Gravity.LEFT).apply { leftMargin = (x - sz / 2f).toInt(); topMargin = (y - sz / 2f).toInt() }
            frame.addView(b, lp); fanViews.add(b)
            b.translationX = cx - x; b.translationY = cy - y; b.scaleX = 0.2f; b.scaleY = 0.2f; b.alpha = 0f
            b.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(i * 25L).setDuration(190L).start()
        }
    }
    /** دوسة على الفيديو في الصفحة: لو الدايرة لقت فيديو واحد والدوسة جوه مربعه ← الأزرار تفتح حوالين الدايرة */
    private fun tapMaybeVideo(rx: Float, ry: Float) {
        if (tvOn || dead || fanViews.isNotEmpty() || badge.visibility != View.VISIBLE) return
        val wv = wvOrNull ?: return
        val g = try { groupsOf(vis()).singleOrNull() } catch (_: Throwable) { null } ?: return
        if (g.qs.isEmpty()) return
        val loc = IntArray(2); wv.getLocationOnScreen(loc)
        try {
            wv.evaluateJavascript(VidMark.RECT_JS) { r ->
                try {
                    if (dead || fanViews.isNotEmpty() || r.isNullOrBlank() || r == "null") return@evaluateJavascript
                    val a = JSONArray(r); if (a.length() < 5) return@evaluateJavascript
                    val k = wv.width.toFloat() / a.getDouble(4).toFloat().coerceAtLeast(1f)
                    val l = a.getDouble(0).toFloat() * k + loc[0]; val t = a.getDouble(1).toFloat() * k + loc[1]
                    val w = a.getDouble(2).toFloat() * k; val hh = a.getDouble(3).toFloat() * k
                    if (rx >= l && rx <= l + w && ry >= t && ry <= t + hh) openFan(g)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    // ===== (v716) لوحة الأزرار تحت الدايرة =====
    private var actScrim: View? = null
    private fun closeActions() {
        val v = actScrim ?: return; actScrim = null
        try { (v.parent as? ViewGroup)?.removeView(v) } catch (_: Throwable) {}
    }
    private fun showActions(g: Grp) {
        closeActions()
        val root = findViewById<FrameLayout>(android.R.id.content) ?: run { showList(); return }
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x66000000); isClickable = true; setOnClickListener { closeActions() } }
        // (v941) كارت في نص الشاشة بأزرار بعرض كامل بدل العمود الضيق اللي كان على اليمين
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(12))
            background = ui.box(th.card, th.border, 16)
            elevation = ui.dp(12).toFloat()
        }
        card.addView(ui.text(g.title.ifBlank { "فيديو" }, 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        card.addView(ui.button("▶  مشاهدة بس", true) { closeActions(); doAct("watch", g) })
        card.addView(ui.button("🌐  مشاهدة وترجمة") { closeActions(); doAct("tr", g) })
        card.addView(ui.button("⬇  تحميل") { closeActions(); doAct("dl", g) })
        card.addView(ui.button("📺  مشاهدة ع التلفزيون") { closeActions(); doAct("tv", g) })
        card.addView(ui.button("✕  إلغاء") { closeActions() })
        scrim.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = ui.dp(20); rightMargin = ui.dp(20) })
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        actScrim = scrim
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(160).start()
    }
    /** (v852) فيديوهين أو أكتر: دايرة مرقّمة لكل فيديو فوق دايرة الاصطياد، وجنب كل واحدة اسم الفيديو اللي هيتحمّل. دوسة على دايرة = أزرار الفيديو ده */
    private fun showPick(gs: List<Grp>) {
        closeActions(); closeFan()
        val root = findViewById<FrameLayout>(android.R.id.content) ?: run { showList(); return }
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x66000000); isClickable = true; setOnClickListener { closeActions() } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; elevation = ui.dp(12).toFloat() }
        gs.take(6).forEachIndexed { i, g ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
                isClickable = true; isFocusable = true
                setOnClickListener { closeActions(); if (tvOn) showActions(g) else openFan(g) }   // (v975) موبايل: الأزرار حوالين الدايرة بدل الكارت اللي في النص
            }
            row.addView(TextView(this).apply {
                text = g.title.ifBlank { "فيديو " + (i + 1) }; setTextColor(Color.WHITE); textSize = 12f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = ui.dp(230)
                setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5)); gravity = Gravity.END
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xCC101418.toInt()); cornerRadius = ui.dp(14).toFloat() }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = ui.dp(8) })
            row.addView(ClipDot(this, (i + 1).toString(), 0xFF34C759.toInt()), LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
            col.addView(row, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; bottomMargin = ui.dp(8) })
        }
        val bl = IntArray(2); badge.getLocationOnScreen(bl)
        val rl = IntArray(2); root.getLocationOnScreen(rl)
        val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.RIGHT).apply {
            bottomMargin = (root.height - (bl[1] - rl[1]) + ui.dp(6)).coerceAtLeast(ui.dp(10))
            rightMargin = (root.width - (bl[0] - rl[0]) - badge.width).coerceAtLeast(ui.dp(10))
        }
        scrim.addView(col, lp)
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        actScrim = scrim
        scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(160).start()
    }
    private fun doAct(act: String, g: Grp) {
        val q = g.qs.getOrNull(qIdx(g)) ?: return
        when (act) {
            "tv" -> sendToTv(g, q)
            "dl" -> askQuality(g)
            "tr" -> openPlayer(q.f, false, q.audio)
            "watch" -> openPlayer(q.f, true, q.audio)
        }
    }
    /** (v716) اختيار جودة التحميل: كل جودات الاصطياد (+ جودات HLS من الماستر) + «🎧 صوت بس». جودة واحدة بس = تحميل على طول */
    private fun askQuality(g: Grp, only: Qv? = null) {
        val src = if (only != null) listOf(only) else g.qs
        Notice.show(this, "⏳ بجيب الجودات وأحجامها…", 2500L)
        Thread {
            val opts = ArrayList<DlQuality.Opt>()
            for (q in src) {
                if (isHls(q.f)) {
                    val vs = try { Downloader.variants(q.f.url, dlHeaders(q.f)) } catch (_: Throwable) { emptyList() }
                    if (vs.isNotEmpty()) {
                        val sz = try { Downloader.variantBytes(vs, dlHeaders(q.f)) } catch (_: Throwable) { emptyList() }   // (v975) الحجم الكلي مش الريت
                        vs.forEachIndexed { i, v -> opts.add(DlQuality.Opt(Downloader.variantLabel(v, sz.getOrElse(i) { 0L }), v.h, v.url, null, true, q)) }
                        continue
                    }
                }
                val h = if (q.h > 0) q.h else (hCache[Sniff.key(q.f.url)] ?: 0)
                var sTxt = ""
                if (!q.label.contains("MB") && !q.label.contains("GB")) {   // (v975) الحجم الكلي دايمًا: لو لسه مش معروف نسأل السيرفر دلوقتي (الفيديو + الصوت لو منفصل)
                    var n = szCache[q.f.url] ?: -1L
                    if (n <= 0L) {
                        val hs = dlHeaders(q.f)
                        n = try { Downloader.sizeOf(q.f.url, hs) } catch (_: Throwable) { -1L }
                        if (n > 0L && q.audio != null) { val a = try { Downloader.sizeOf(q.audio, hs) } catch (_: Throwable) { -1L }; if (a > 0L) n += a }
                        if (n > 0L) szCache[q.f.url] = n
                    }
                    if (n > 0L) sTxt = " · " + Sniff.fmtSize(n)
                }
                opts.add(DlQuality.Opt(q.label + sTxt, h, q.f.url, q.audio, isHls(q.f), q))
            }
            if (src.size > 1) {   // (v853) جودات جاية من أكتر من لينك: ترتيب موحّد من الأعلى، والجودة المتكررة مرة (الملف المباشر أولى من HLS)
                val ord = opts.withIndex().sortedWith(compareByDescending<IndexedValue<DlQuality.Opt>> { if (it.value.h > 0) it.value.h else 0 }.thenBy { it.index }).map { it.value }
                val seenH = HashSet<Int>(); val direct = ord.filter { !it.hls && it.h > 0 }.map { it.h }.toSet()
                opts.clear()
                for (o in ord) { if (o.h > 0 && o.hls && o.h in direct) continue; if (o.h > 0 && !seenH.add(o.h)) continue; opts.add(o) }
            }
            runOnUiThread {
                if (dead || opts.isEmpty()) return@runOnUiThread
                if (opts.size == 1) dlOpt(g, opts[0])
                else DlQuality.show(this, "⬇ اختار جودة التحميل", opts, { dlAudio(g) }) { o -> dlOpt(g, o) }
            }
        }.apply { isDaemon = true }.start()
    }
    private fun dlOpt(g: Grp, o: DlQuality.Opt) {
        val q = o.tag as? Qv ?: return
        queueDl(Downloader.Job(g.title + (if (o.h > 0) " [${o.h}p]" else qTag(q)), o.url, o.audio, o.hls, o.h, dlHeaders(q.f)))
    }

    // (v716) بعد أي دوسة في الصفحة: الدايرة تظهر بتدوّر (🔍) 5 ثواني لحد ما يتلقط فيديو
    private var scanUntil = 0L
    private var lastKick = 0L
    private fun kickScan() {
        val now = android.os.SystemClock.uptimeMillis()
        if (dead || now - lastKick < 1200L) return
        lastKick = now; scanUntil = now + 5000L
        tabs.getOrNull(curIdx)?.let { deepScan(it, true) }   // (v980) دوسة العدسة: المسح يبدأ في الخلفية وأقولك لو مفيش لينك حقيقي
        h.post { if (!dead) refreshBadge() }
        h.postDelayed({ if (!dead) refreshBadge() }, 5100L)
    }
    private fun showBadge() {
        if (badge.visibility != View.VISIBLE) {   // بتطلع من الجنب
            badge.visibility = View.VISIBLE
            if (tvOn) { badge.translationX = ui.dp(70).toFloat(); badge.animate().translationX(0f).setDuration(260L).start() }
            else { badge.scaleX = 0.3f; badge.scaleY = 0.3f; badge.animate().scaleX(1f).scaleY(1f).setDuration(220L).start() }   // (v801) في نص الشريط: بتكبر مكانها
        }
    }
    private fun refreshBadge() {
        val n = groupsOf(vis()).size
        val sn = curSnap()
        val nf = sn?.files?.size ?: 0; val nl = sn?.hosts?.size ?: 0
        badge.showCount = false
        badge.files = nf; badge.links = nl   // (v838) عدّادات 📦 ملفات/برامج · 🔗 روابط رفع على الدايرة
        if (n == 0 && nf + nl == 0) {
            closeFan()
            if (android.os.SystemClock.uptimeMillis() < scanUntil) { badge.found = false; badge.glyph = "🔍"; badge.count = 1; showBadge() }
            else badge.visibility = View.GONE
            // (v941) التلميحة تظهر بس لو فيها نص حقيقي (إصلاح/صيد ترجمة/يوتيوب) — الشريط الأبيض الفاضي اتشال
            hint.visibility = if (hint.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        } else if (n == 0) {   // (v838) مفيش فيديو بس فيه ملفات/لينكات حقيقية اتلقطت
            closeFan()
            val first = !badge.found || badge.visibility != View.VISIBLE
            badge.found = true; badge.glyph = if (nf > 0) "📦" else "🔗"; badge.count = 1; hint.visibility = View.GONE
            showBadge()
            if (first) badge.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction { badge.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
        } else {
            val first = !badge.found || badge.visibility != View.VISIBLE
            badge.found = true; badge.glyph = null; badge.count = n; badge.showCount = true; hint.visibility = View.GONE   // (v852) 🎬 + رقم (1 · 2 · …)
            showBadge()
            if (first) badge.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction { badge.animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
        }
        if (dlg?.isShowing == true) renderList()
        updateMark()
    }

    // ===== (v717) علامة مربع الفيديو =====
    private var markGen = 0
    private var markLoop = false
    private var markHidden = false   // ✕ على اللوحة: مخفية لحد صفحة/تبويب جديد
    /** بتشتغل لما فيه اصطياد في الصفحة: بتسأل الصفحة كل ~⅓ ثانية عن مكان الفيديو وتحط الإطار عليه؛ مفيش اصطياد = بتقف وتخفي الإطار */
    private fun updateMark() {
        if (dead || !::vidMark.isInitialized || tvOn || !panelOn()) return   // (v801) اللوحة الزرقا على الفيديو متقفلة افتراضيًا — الأزرار بقت حوالين الدايرة (Cfg vid_panel=1 يرجّعها)
        val has = try { groupsOf(vis()).any { it.qs.isNotEmpty() } } catch (_: Throwable) { false }
        if (!has) { markGen++; markLoop = false; vidMark.clear(); return }
        if (markLoop) return
        markLoop = true; val gen = ++markGen
        h.post { markStep(gen) }
    }
    private fun markStep(gen: Int) {
        if (dead || gen != markGen) return
        val wv = wvOrNull
        val wanted = !markHidden && customView == null && actScrim == null && fanViews.isEmpty() && dlg?.isShowing != true && panicCover.visibility != View.VISIBLE
        if (wv == null || !wanted) vidMark.clear()
        else try {
            wv.evaluateJavascript(VidMark.RECT_JS) { r ->
                try {
                    if (dead || gen != markGen || actScrim != null) return@evaluateJavascript
                    if (r.isNullOrBlank() || r == "null") { vidMark.clear(); return@evaluateJavascript }
                    val a = JSONArray(r)
                    if (a.length() < 5) { vidMark.clear(); return@evaluateJavascript }
                    val k = wv.width.toFloat() / a.getDouble(4).toFloat().coerceAtLeast(1f)
                    vidMark.place(a.getDouble(0).toFloat() * k, a.getDouble(1).toFloat() * k, a.getDouble(2).toFloat() * k, a.getDouble(3).toFloat() * k)
                } catch (_: Throwable) { vidMark.clear() }
            }
        } catch (_: Throwable) {}
        h.postDelayed({ markStep(gen) }, 350L)
    }
    /** أزرار اللوحة الزرقا: mw/mt = موبايل (من غير/بالترجمة) · tw/tt = تلفزيون (من غير/بالترجمة) · dl = تحميل · close. أكتر من فيديو ملقوط ← القايمة */
    private fun markAct(act: String) {
        try {
            if (act == "close") { markHidden = true; vidMark.clear(); return }
            val g = groupsOf(vis()).singleOrNull()
            if (g == null) { showList(); return }
            fanAct(act, g)
        } catch (e: Throwable) { LogStore.err("Browser:markAct", e) }
    }
    /** (v975) زرار من الأزرار حوالين الدايرة، على فيديو بعينه (فيديو واحد أو اللي اتختار من دواير الفيديوهات) */
    private fun fanAct(act: String, g: Grp) {
        try {
            val q = g.qs.getOrNull(qIdx(g)) ?: return
            when (act) {
                "mw" -> openPlayer(q.f, true, q.audio)
                "mt" -> openPlayer(q.f, false, q.audio)
                "tw" -> sendToTv(g, q, false)
                "tt" -> ensureKeys { sendToTv(g, q, true) }
                "dl" -> askQuality(g)
            }
        } catch (e: Throwable) { LogStore.err("Browser:fanAct", e) }
    }

    // ===== (v203) تجميع الجودات: كل لينكات نفس الفيديو = اصطياد واحد =====
    private class Qv(val label: String, val h: Int, val f: Found, val audio: String?)
    private class Grp(val key: String, val title: String, val qs: List<Qv>)
    private var openKey: String? = null
    private var repairId: String? = null
    private var repairTv = false
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
        return if (f.kind == "HLS") "u:$k" else "s:" + f.page.ifEmpty { f.ref }.substringBefore('#') + "|" + Sniff.skeleton(f.url)
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
        // (v558) نفس الاسم بأكتر من اصطياد = فيديو واحد: بنستنى فحص الجودة يخلص (من غير ما نعرض الكروت الأول ونشيلها بعدين):
        //  - لو فيه اصطياد ليه جودات حقيقية (أكتر من جودة، ارتفاع معروف، أو HLS «تلقائي لحد Np») ← بس هو (أو هما) اللي بيظهر.
        //  - لو لسه الفحص شغّال ← مفيش حاجة من المجموعة دي بتظهر لحد ما يخلص (أقصى 12 ثانية).
        //  - لو الفحص خلص ومفيش جودات معروفة ← بيظهر اصطياد واحد بس.
        // فيديو لوحده من غير اسم مكرّر، أو من غير اسم أصلًا، بيفضل زي ما هو.
        fun unknownQ(q: Qv) = q.h <= 0 && q.audio == null && !q.f.kind.startsWith("YT")
        fun rich(g: Grp) = g.qs.size > 1 || g.qs.any { it.h > 0 || (it.f.kind == "HLS" && (hCache[Sniff.key(it.f.url)] ?: 0) > 0) }
        val nowMs = System.currentTimeMillis()
        fun pending(g: Grp) = !rich(g) && g.qs.any { q -> unknownQ(q) && !hCache.containsKey(Sniff.key(q.f.url)) && nowMs - (hAt[Sniff.key(q.f.url)] ?: nowMs) < PROBE_WAIT_MS }
        fun nm(g: Grp) = g.title.trim().lowercase()
        val drop = HashSet<Grp>()
        val merged = HashMap<Grp, Grp>()
        for ((_, gs) in out.filter { nm(it).isNotEmpty() }.groupBy { nm(it) }) {
            if (gs.size < 2) continue
            val rch = gs.filter { rich(it) }
            if (rch.isNotEmpty()) {
                gs.filter { it !in rch }.forEach { drop.add(it) }
                // (v853) نفس الاسم بأكتر من اصطياد فيهم جودات (مثلًا HLS بكل الجودات + ملف بجودة ثابتة) = أيقونة واحدة وزرار تحميل واحد؛ الجودة الثابتة بتقع في مكانها وسط الباقي (بالارتفاع)
                if (rch.size > 1 && rch.none { it.key.startsWith("yt:") }) {
                    val seenU = HashSet<String>(); val seenHH = HashSet<Int>()
                    val mq = rch.flatMap { it.qs }.filter { seenU.add(Sniff.key(it.f.url)) }
                        .sortedByDescending { if (it.f.kind == "HLS") Int.MAX_VALUE else it.h }
                        .filter { q -> q.h <= 0 || seenHH.add(q.h) }
                    merged[rch.first()] = Grp(rch.first().key, rch.first().title, mq)
                    rch.drop(1).forEach { drop.add(it) }
                }
                continue
            }
            if (gs.any { pending(it) }) {
                if (repairId == null) for (g in gs) for (q in g.qs) if (unknownQ(q)) probeHeight(q.f)
                gs.forEach { drop.add(it) }
            } else gs.drop(1).forEach { drop.add(it) }
        }
        return out.filter { it !in drop }.map { merged[it] ?: it }
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
        if (repairTv) { LinkPhone.applyFromBrowser(this, id, f.url, ck, ua); finish(); return }   // (v820) للتلفزيون: ابعت اللينك وارجع (مفيش مشغّل على الموبايل)
        Thread {
            WebVideos.update(app, id) { it.copy(url = f.url, ref = f.ref.ifEmpty { it.ref }, ua = ua.ifEmpty { it.ua }, cookie = if (ck.isNotEmpty()) ck else it.cookie,
                page = if (WebIdent.specific(pageNow)) pageNow else it.page, ts = System.currentTimeMillis()) }
            WebIdent.bindLink(id, f.url, f.page.ifEmpty { f.ref }, ttl)
        }.apply { isDaemon = true }.start()
        Notice.show(this, "✓ اتحدّث لينك الفيديو — برجّعك للمشغّل", 2500L)
        startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
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
        if (f.origin.isNotEmpty()) m["Origin"] = f.origin   // (v976)
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

    /** (v594) نافذة عايمة صغيرة على الجنب (من غير تعتيم) بدل الشيت الكبير */
    private fun buildFloatList(st: TextView, lb: LinearLayout): Dialog {
        val dm = resources.displayMetrics
        val maxH = (dm.heightPixels * 0.62f).toInt()
        val sv = object : ScrollView(this) {
            override fun onMeasure(w: Int, h: Int) { super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)) }
        }.apply { addView(lb); isVerticalScrollBarEnabled = false }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(ui.text("🎬 صيد الفيديو", 14f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.text("✕", 16f, th.muted, true).apply { setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4)); setOnClickListener { dlg?.dismiss() } })
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = ui.box(th.card, th.border, 16); setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(10))
            addView(head); addView(st); addView(sv, LinearLayout.LayoutParams(-1, -2))
        }
        val d = GDialog(this).apply { plain = true }
        d.setContentView(col)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(0)); clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(minOf(ui.dp(320), (dm.widthPixels * 0.88f).toInt()), -2); setGravity(Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            attributes = attributes.apply { x = ui.dp(6); dimAmount = 0f }
        }
        d.setCanceledOnTouchOutside(true)
        return d
    }
    fun showList() {
        if (!tvOn && groupsOf(vis()).isEmpty()) {   // (v975) مفيش فيديو: إشعار صغير بدل الشاشة الكبيرة العريضة «بدوّر…»
            Notice.show(this, "🔍 لسه مفيش فيديو ملقوط — شغّل الفيديو في الصفحة", 2600L); kickScan(); return
        }
        if (dlg == null) {
            val lb = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val st = ui.text("", 13f, th.muted).apply { setPadding(0, 0, 0, ui.dp(6)) }
            listBox = lb; statusTv = st
            dlg = buildFloatList(st, lb)
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
    private val hAt = java.util.concurrent.ConcurrentHashMap<String, Long>()   // (v558) وقت بداية فحص كل لينك
    private val PROBE_WAIT_MS = 12000L
    private fun probeHeight(f: Found) {
        val k = Sniff.key(f.url)
        if (hCache.containsKey(k) || !hBusy.add(k)) return
        hAt[k] = System.currentTimeMillis()
        try { android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (!dead) refreshBadge() }, PROBE_WAIT_MS + 500L) } catch (_: Throwable) {}
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
            runOnUiThread { if (!dead) refreshBadge() }   // (v558) العدّاد والقايمة يتحدّثوا لما الفحص يخلص (حتى لو الجودة ماطلعتش)
        }.apply { isDaemon = true }.start()
    }
    private val selQ = HashMap<String, Int>()   // (v231) الجودة المختارة لكل اصطياد (مفتاح المجموعة ← رقم الجودة)

    /** (v231) اصطياد واحد لكل فيديو: العنوان + اختيار الجودة (لو أكتر من جودة) + زرار أزرق كبير «🌐 شاهد وترجم» وتحته ▶ فرجة · ⬇ تحميل (بحجمه) · 📋 نسخ · 🎧 صوت */
    /** (v594) الجودة اللي بتتختار لوحدها لما ما تختارش: الإعداد «الجودة التلقائية» (br_defq): 0 = الأعلى · غير كده = أعلى جودة لحد الرقم، ولو مفيش أقل منه ياخد أقل واحدة موجودة */
    private fun defIdx(g: Grp): Int {
        val cap = prefs().getString("br_defq", "0")?.toIntOrNull() ?: 0
        if (cap <= 0) return 0
        val known = g.qs.mapIndexed { i, x -> i to x.h }.filter { it.second > 0 }
        if (known.isEmpty()) return 0
        val ok = known.filter { it.second <= cap }
        return (if (ok.isNotEmpty()) ok.maxByOrNull { it.second } else known.minByOrNull { it.second })?.first ?: 0
    }
    private fun qIdx(g: Grp): Int = (selQ[g.key] ?: defIdx(g)).coerceIn(0, g.qs.size - 1)

    /** (v594) كارت صغير: العنوان + اختيار الجودة + صف أزرار صغيرة (🌐 ترجمة · ▶ شاهد · 📺 تلفزيون · ⬇ حمّل). ضغطة طويلة على الاسم = نسخ اللينك · على «حمّل» = صوت بس */
    private fun groupCard(g: Grp): View {
        val c = ui.card()
        c.addView(ui.text(g.title, 13f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setOnLongClickListener { g.qs.getOrNull(qIdx(g))?.let { copyUrl(it.f.url) }; true } })
        if (repairId != null) {   // وضع إصلاح اللينك: اختيار الجودة بس
            if (g.qs.size > 1) c.addView(ui.text("🎞 ${g.qs.size} جودات", 13f, th.primary, true))
            for (q in g.qs) c.addView(ui.button("🔧 استخدم " + q.label, true) { dlg?.dismiss(); applyRepair(q) })
            return c
        }
        val cur = qIdx(g)
        val q = g.qs[cur]
        if (g.qs.size > 1) {
            val labels = g.qs.mapIndexed { i, x -> if (x.h > 0) x.h.toString() + "p" else if (isHls(x.f)) "تلقائي" else "جودة " + (i + 1) }
            c.addView(ui.chips(labels, { labels[cur] }) { pick -> val ni = labels.indexOf(pick); if (ni >= 0 && ni != cur) { selQ[g.key] = ni; renderList() } })
        }
        val hasSz = q.label.contains("MB") || q.label.contains("GB")
        val info = ui.text(q.label + (if (hasSz) "" else szTxt(q)), 11f, th.muted).apply { setPadding(0, ui.dp(2), 0, ui.dp(2)) }
        c.addView(info)
        fun sb(t: String, fill: Int, txt: Int, f: () -> Unit) = IconTextView(this).apply {
            text = t; textSize = 11.5f; gravity = Gravity.CENTER; maxLines = 1; setTextColor(txt); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ui.box(fill, fill, 10); setOnClickListener { f() }
        }
        val onP = if (th.isLight) Color.WHITE else Color.BLACK
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun add(v: View) { row.addView(v, LinearLayout.LayoutParams(0, ui.dp(38), 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }) }
        add(sb("🌐 ترجمة", th.primary, onP) { dlg?.dismiss(); openPlayer(q.f, false, q.audio) })
        add(sb("▶ شاهد", BLUE, ON_BLUE) { dlg?.dismiss(); openPlayer(q.f, true, q.audio) })
        add(sb("📺 تلفزيون", BLUE, ON_BLUE) { dlg?.dismiss(); sendToTv(g, q) })
        add(sb("➕ إضافة للليستة", BLUE, ON_BLUE) { dlg?.dismiss(); queueAdd(g, q) })   // (v694)
        val dlB = sb("⬇ حمّل", BLUE, ON_BLUE) { dlg?.dismiss(); if (isHls(q.f)) askQuality(g, q) else dlVideo(g, q) }   // (v716) HLS: اختيار الجودة من الماستر
        dlB.setOnLongClickListener { dlg?.dismiss(); dlAudio(g); true }
        add(dlB)
        c.addView(row)
        if (!hasSz && !isHls(q.f) && szCache[q.f.url] == null) {   // الحجم مش معروف: نسأل السيرفر ونحدّث السطر
            szCache[q.f.url] = -2L
            val hs2 = dlHeaders(q.f); val u1 = q.f.url; val u2 = q.audio
            Thread {
                var n = try { Downloader.sizeOf(u1, hs2) } catch (_: Throwable) { -1L }
                if (n > 0 && u2 != null) { val a = try { Downloader.sizeOf(u2, hs2) } catch (_: Throwable) { -1L }; if (a > 0) n += a }
                szCache[u1] = n
                runOnUiThread { if (!dead && n > 0) info.text = q.label + " · " + Sniff.fmtSize(n) }
            }.apply { isDaemon = true }.start()
        }
        return c
    }
    private val BLUE: Int get() = Themes.current().primary   // (v913) accent = MeeTube (الأزرق الثابت اتشال)
    private val ON_BLUE: Int get() = Themes.current().onPrimary
    /** (v533) علامة الجودة اللي بتتضاف لاسم التحميل/التشغيل عشان نفس الفيديو بجودات مختلفة مايبقوش بنفس الاسم */
    private fun qTag(q: Qv): String {
        val h = if (q.h > 0) q.h else (hCache[Sniff.key(q.f.url)] ?: 0)
        return if (h > 0) " [${h}p]" else if (isHls(q.f)) " [HLS]" else ""
    }
    /** (v694) «🎞 طابور»: يوتيوب ← لينك المشاهدة · غيره ← اللينك المباشر */
    private fun queueAdd(g: Grp, q: Qv) {
        val f = q.f
        val isYt = f.kind.startsWith("YT") || f.url.contains("googlevideo") || f.url.contains("videoplayback")
        val yid = ytIds[Sniff.key(f.url)] ?: YtExtract.videoId(f.ref) ?: (if (isYt) YtExtract.videoId(wvOrNull?.url ?: "") else null)
        PlayQueueUi.add(this, if (yid != null) "https://www.youtube.com/watch?v=$yid" else f.url, g.title + qTag(q))
    }

    /** (v533) «مشاهدة ع التلفزيون»: يوتيوب ← لينك الفيديو · غيره ← اللينك المباشر ومعاه Referer/UA/Cookie الصفحة */
    private fun sendToTv(g: Grp, q: Qv, translate: Boolean = false) {
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
        // (v820) صفحة الفيديو (مش لينك الفيديو): بتتحفظ جنبه على التلفزيون عشان «تحديث اللينك» يشتغل لما اللينك يخلص
        val pageNow = if (yid != null) "" else (try { wvOrNull?.url ?: tabs.getOrNull(curIdx)?.url } catch (_: Throwable) { null } ?: "").takeIf { WebIdent.specific(it) } ?: ""
        Notice.show(this, if (translate) "📺🌐 بوصّل بالتلفزيون (بالترجمة)…" else "📺 بوصّل بالتلفزيون…", 2500L)
        Thread {
            var msg: String
            try {
                msg = if (!RemoteClient.ensureConnected(this, 8000L)) "⚠ ما قدرتش أوصل للتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                else (RemoteClient.openLink(url, title, if (translate) Links.VIDEO_TR else Links.VIDEO, ref, ua, ck, page = pageNow)?.let { "⚠ $it" } ?: (if (translate) "✅ اتبعت للتلفزيون — هيترجم" else "✅ اتبعت للتلفزيون"))
            } catch (e: Throwable) { LogStore.err("Browser:sendToTv", e); msg = "⚠ حصلت مشكلة" }
            runOnUiThread {
                if (dead) return@runOnUiThread
                Notice.show(this, msg, 2600L)
                // (v554) مفيش شاشة تحكم على الموبايل بعد الإرسال
            }
        }.apply { isDaemon = true }.start()
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
                title = titleFor(f), how = if (yid.isEmpty() || noSub) Links.VIDEO else Links.VIDEO_TR), { openPlayerNow(f, noSub, audio) })) return
        startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
            if (yid.isNotEmpty()) {
                putExtra("ytid", yid)
                ytPicks[yid]?.let { pk ->
                    val o = pk.opts.firstOrNull { it.url == f.url }
                    if (pk.url == f.url || o != null) { putExtra("aurl", audio ?: o?.audio ?: (if (pk.url == f.url) pk.audio else null) ?: ""); putExtra("qlist", pk.optsJson()) }
                }
            }
            var openUrl = f.url
            if (yid.isEmpty()) try {
                val ql = webQlist(f)
                ql?.let { putExtra("qlist", it) }
                // (v714) فيه كذا جودة: افتح على الجودة اللي مختارها (yt_maxh) — أعلى واحدة لحد الحد ده، ولو مفيش ينزل لأقل واحدة؛ مش أعلى القايمة
                val mh = Cfg.int("yt_maxh", 0)
                if (ql != null && mh > 0) {
                    val qa = JSONArray(ql); var best: JSONObject? = null; var low: JSONObject? = null; var cur = 0
                    for (i in 0 until qa.length()) { val q = qa.getJSONObject(i); val h = q.optInt("h"); if (Sniff.key(q.optString("u")) == Sniff.key(f.url)) cur = h
                        if (h <= 0) continue
                        if (h <= mh && (best == null || h > best.optInt("h"))) best = q
                        if (low == null || h < low.optInt("h")) low = q }
                    val pk = best ?: low
                    if (pk != null && cur > mh && pk.optString("u").isNotEmpty()) openUrl = pk.optString("u")
                }
            } catch (e: Throwable) { LogStore.err("Browser:wq", e) }
            putExtra("url", openUrl); putExtra("ref", f.ref)
            try { (wvOrNull?.url ?: tabs.getOrNull(curIdx)?.url)?.takeIf { WebIdent.specific(it) }?.let { putExtra("page", it) } } catch (_: Throwable) { }   // (v750) صفحة الفيديو اللي اتصاد منها — بتتحفظ في السجل عشان تتفتح لوحدها لو اللينك بوظ
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
        if (actScrim != null) { closeActions(); return }   // (v716)
        if (customView != null) { leaveCustom(); return }
        if (::dlBar.isInitialized && dlBar.visibility == View.VISIBLE) { hideDl(); return }   // (v324)
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        val w = wvOrNull
        if (w != null && w.canGoBack()) { saver.back(w, tab); return }   // (v783)
        // تبويب اتفتح من تبويب تاني: الرجوع يقفله ويرجّعك للأصلي
        if (tabs.size > 1 && tab.opener >= 0 && tabs.any { it.id == tab.opener }) { closeTab(curIdx); return }
        persistTabs()
        if (!isTaskRoot && !DeviceUi.isTv(this)) { NavAnim.set(2, 0); startActivity(TabNav.main(this, "videos")); NavAnim.peek(this); return }   // (v973) التبويبات شغّالة: Back = ميديا (مش أي شاشة تحتك)
        super.onBackPressed()
    }
    // (v149) أول ما تخرج من المتصفح: كل الفيديو/الصوت في كل التبويبات يقف (من غير ما يكمّل في الخلفية)، وكل التبويبات تتجمّد
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); if (::saver.isInitialized) try { saver.onTrim(level) } catch (_: Throwable) {} }   // (v783)
    override fun onConfigurationChanged(c: android.content.res.Configuration) { super.onConfigurationChanged(c); try { toolsRelayout?.invoke() } catch (_: Throwable) {} }   // (v823) لاندسكيب/بورتريه: ترتيب الأدوات
    override fun onPause() { if (::saver.isInitialized) saver.stop(); persistTabs(); WebMute.pauseAll(); for (t in tabs) try { t.wv?.onPause() } catch (_: Throwable) {}; super.onPause() }
    override fun onResume() { super.onResume(); if (::saver.isInitialized) saver.start(); refreshCoBtns(); curRef = java.lang.ref.WeakReference(this); try { wvOrNull?.onResume() } catch (_: Throwable) {} }
    /** (v746) صيد الترجمة: تحميل الملف بكوكيز الصفحة وفكه وإضافته للفيديو */
    private fun huntCapture(url: String, ua: String, cd: String?, referer: String?) {
        val ck = try { CookieManager.getInstance().getCookie(url) } catch (_: Throwable) { null }
        Notice.show(this, "⬇ بنزّل ملف الترجمة…", 2500L)
        SubHunt.capture(applicationContext, url, ua, ck, referer, cd) { ok, msg ->
            runOnUiThread {
                if (dead) return@runOnUiThread
                Notice.show(this, msg, if (ok) 5000L else 7000L)
                if (ok && Cfg.bool("subhunt_close", true) && PlayerActivity.hasLive()) h.postDelayed({ if (!dead) finish() }, 1800L)
            }
        }
    }

    /** (v854) هوم/ريسنت (مش فتح المشغّل من جوه التطبيق): لو الإعداد شغّال ← التبويبات المتخفية تتقفل */
    override fun onUserLeaveHint() {
        try { if (prefs().getBoolean("br_priv_close", false) && tabs.any { it.priv }) closeAllTabs(true) } catch (_: Throwable) {}
        super.onUserLeaveHint()
    }
    override fun onDestroy() {
        SubHunt.stop()   // (v746)
        try { if (::saver.isInitialized) saver.destroy() } catch (_: Throwable) {}   // (v783)
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
