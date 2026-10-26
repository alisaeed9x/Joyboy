package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.GridView
import android.widget.PopupMenu
import android.widget.TextView
import com.bumptech.glide.Glide
import org.json.JSONObject
import org.schabi.newpipe.extractor.Page
import java.util.Locale

/**
 * (v325) يوتيوب جوه التطبيق — بدل صفحة الويب (YoutubeUi): واجهة أصلية على NewPipeExtractor (نفس محرك Flow).
 * بحث باقتراحات (فيديوهات/قنوات/قوايم) · الرئيسية بمقترحات من سجلك · اشتراكاتي (فيد مدموج) · السجل · المحفوظات ·
 * صفحة قناة باشتراك · قوايم تشغيل. أي فيديو بتدوس عليه بيفتح في مشغّلنا (onPlay)، وضغطة طويلة = قايمة (ترجمة/حفظ/قناة/نسخ).
 * نفس واجهة YoutubeUi القديمة (root · refresh · onHide · importUri · back) فMain بيبدّل الاسم بس.
 */
class YtProUi(private val act: Activity, private val ui: Ui, private val th: Theme,
              private val recents: () -> Map<String, *>,
              private val onPlay: (String, String, Boolean) -> Unit,
              private val onBrowser: (String) -> Unit,
              private val onImport: () -> Unit,
              private val onAudio: (NpItem) -> Unit = {},                      // (v337) 🎧 صوت بس في مشغّل الموسيقى
              private val onDownload: (NpItem, Boolean) -> Unit = { _, _ -> },
              private val onPubHistory: () -> Unit = {},
              private val onPlayAt: ((String, String, Boolean, Long, YtPick?) -> Unit)? = null) {   // (v402) تشغيل من ثانية معيّنة (زرار التكبير في المشغّل المصغّر)   // (v349) السجل العام   // (v337) ⬇ تحميل (true = صوت بس)

    private class St(val title: String, val tab: Int, val items: ArrayList<NpItem>, var next: Page?, val more: ((Page) -> YtRes)?,
                      val chUrl: String?, val chName: String?, val q: String?, val filter: String,
                      val vid: NpItem? = null, val det: YtDetail? = null) {
        var pos = 0
        var durF = 0; var dateF = 0   // (v469) C5: فلاتر بحث (مدة/تاريخ) بتتطبق على صفحات التحميل التالية كمان
        var cMode = false; var cLoaded = false; val ci = ArrayList<NpItem>(); var cn: Page? = null; var cm: ((Page) -> YtRes)? = null
        fun list(): ArrayList<NpItem> = if (cMode) ci else items
        fun nxt(): Page? = if (cMode) cn else next
        fun mf(): ((Page) -> YtRes)? = if (cMode) cm else more
    }

    private val skinInit = YtSkin.init(th, act)   // (v471) ألوان وخط يوتيوب بقوا من سمة البرنامج نفسها (كانت ألوان يوتيوب الثابتة)
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(YtSkin.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val h = Handler(Looper.getMainLooper())
    // (v337) لودينج ويندوز (نفس اللي في المشغّل) بدل «⏳ …» وقت البحث/التحميل، وطبقة لودينج فوق الصفحة وقت جلب رابط الفيديو
    private val spinDr = WinSpinnerDrawable().apply { setColorFilter(PorterDuffColorFilter(th.primary, PorterDuff.Mode.SRC_IN)) }
    private val spinView = View(act).apply { foreground = spinDr }
    // (v389) اللودينج كان بيتحرّك لنص الصفحة بس بيتحط تحت الـ ListView (اللي ليه خلفية) فكان مخفي في الرئيسية والاشتراكات — بقى طبقة فوق الصفحة في النص
    private val spinBox = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; isClickable = false
        addView(spinView, LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)))
        addView(ui.text("جاري التحميل…", 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
    }
    private fun spin(on: Boolean) { if (on) { spinBox.visibility = View.VISIBLE; spinDr.start() } else { spinBox.visibility = View.GONE; spinDr.stop() }; loadBusy = on; updateLine() }

    // (v407) الخط الأزرق الرفيع فوق (تحميل صفحة أو تحديث خلفي) + كبسولة «⬆ فيديوهات جديدة» لما التحديث الخلفي يجيب جديد وإنت نازل في القايمة
    private val topLine = YtTopLine(act)
    private var loadBusy = false
    private var bgBusy = false
    private val lineWatch = Runnable { loadBusy = false; bgBusy = false; updateLine() }
    private fun updateLine() {
        h.removeCallbacks(lineWatch)
        if (loadBusy || bgBusy) { topLine.start(); h.postDelayed(lineWatch, 40_000L) } else topLine.stop()
    }
    private fun bgLine(on: Boolean) { bgBusy = on; updateLine() }
    private var pendingItems: List<NpItem>? = null
    private var pendingTab = -1
    private val newPill = TextView(act).apply {
        text = "⬆ فيديوهات جديدة"; textSize = 13f; setTextColor(YtSkin.onAccent); gravity = Gravity.CENTER; includeFontPadding = false
        typeface = YtSkin.medium
        setPadding(ui.dp(16), 0, ui.dp(16), 0); elevation = ui.dp(6).toFloat()
        background = GradientDrawable().apply { setColor(YtSkin.accent); cornerRadius = ui.dp(18).toFloat() }
        visibility = View.GONE; alpha = 0f
        setOnClickListener { applyPending() }
    }
    private var pillN = 1
    private fun showPill(n: Int) {
        pillN = n
        if (watch.isOpen || chan.isOpen) return   // (v428) الكبسولة بس في الرئيسية/الاشتراكات — مش فوق صفحة مشاهدة أو قناة
        newPill.text = if (n >= 2) "⬆ " + n + " فيديو جديد" else "⬆ فيديوهات جديدة"
        newPill.animate().cancel()
        if (newPill.visibility != View.VISIBLE) { newPill.visibility = View.VISIBLE; newPill.translationY = -ui.dp(12).toFloat(); newPill.alpha = 0f }
        newPill.animate().alpha(1f).translationY(0f).setDuration(Skins.dur(220L)).start()
    }
    private fun hidePill() {
        pendingItems = null; pendingTab = -1
        if (newPill.visibility != View.VISIBLE) return
        newPill.animate().cancel()
        newPill.animate().alpha(0f).translationY(-ui.dp(12).toFloat()).setDuration(Skins.dur(160L)).withEndAction { newPill.visibility = View.GONE }.start()
    }
    /** (v428) فتح/قفل صفحة المشاهدة أو القناة: الكبسولة تتخبّى فورًا (elevation بتاعها كان بيرسمها فوق الصفحة) وترجع لما ترجع للقايمة */
    private fun pillGate(covered: Boolean) {
        if (covered) { newPill.animate().cancel(); newPill.visibility = View.GONE; newPill.alpha = 0f; return }
        val st = cur
        if (pendingItems != null && st != null && st.tab == pendingTab && st.vid == null && st.q == null && st.chUrl == null) showPill(pillN)
    }
    /** دوسة على الكبسولة: القايمة تتبدّل بالجديد وترجع لفوق */
    private fun applyPending() {
        val p = pendingItems; val t = pendingTab
        hidePill()
        val st = cur ?: return
        if (p == null || st.tab != t || st.vid != null) return
        st.items.clear(); st.items.addAll(p); st.pos = 0
        if (t == 1) { try { YtNotify.markSeen(p); YtNotify.markOpened(act) } catch (_: Throwable) {} }   // اللي اتعرض ما يجيش عليه عدّاد/إشعار
        animRows = true; h.postDelayed({ animRows = false }, 700L)
        adapter.set(st.list()); lv.setSelection(0)
    }
    /** تحديث خلفي خلص (0 = الرئيسية · 1 = الاشتراكات): لو فيه جديد مش معروض والصفحة دي مفتوحة → كبسولة */
    private fun onFeedUpdate(tab: Int, fresh: List<NpItem>) {
        if (act.isFinishing || act.isDestroyed) return
        bgLine(false)
        val st = cur ?: return
        if (st.tab != tab || st.vid != null || st.q != null || st.chUrl != null || st.items.isEmpty()) return
        if (tab == 0 && chipSel != 0) return
        val vids = fresh.filter { it.kind == 0 }
        if (vids.isEmpty()) return
        if (tab == 1) YtSnap.save(act, 1, vids)   // (v472) آخر نسخة سليمة للاشتراكات
        val have = HashSet<String>(); for (x in st.items) have.add(x.id)
        val seenIds: Set<String> = if (tab == 0) YtStore.read(YtStore.HIST).map { it.id }.toHashSet() else emptySet()
        val items = if (tab == 0) vids.filter { it.id !in seenIds } else vids
        val newOnes = items.count { it.id !in have }
        if (newOnes < (if (tab == 0) 6 else 1)) return
        pendingItems = items; pendingTab = tab
        showPill(newOnes)
    }
    // فحص دوري (كل 4 دقايق) وإنت فاتح الرئيسية أو الاشتراكات: لو فيه فيديوهات جديدة تظهر الكبسولة من غير ما تعمل تحديث
    private val pollRun: Runnable = object : Runnable {
        override fun run() {
            h.postDelayed(this, 4 * 60_000L)
            if (root.visibility != View.VISIBLE || watch.isOpen || chan.isOpen || bgBusy || loadBusy) return
            val st = cur ?: return
            if (st.vid != null || st.q != null || st.chUrl != null) return
            if (st.tab == 1) {
                val subs = YtStore.read(YtStore.SUBS)
                if (subs.isNotEmpty()) { bgLine(true); YtRss.refreshAsync(subs) }
            } else if (st.tab == 0 && chipSel == 0 && YtNp.homeAgeMs() > 5 * 60_000L) {
                if (YtNp.refreshHomeAsync(YtStore.read(YtStore.HIST), YtStore.read(YtStore.SUBS))) bgLine(true)
            }
        }
    }
    private fun startPoll() { h.removeCallbacks(pollRun); h.postDelayed(pollRun, 4 * 60_000L) }

    // (v407) recents() بتقرا ملف recent.json وتحلّله — كانت بتتنده لكل كارت في كل getView (سبب تقطيع/بطء الفتح والسكرول). دلوقتي كاش 3 ثواني
    private var recMemo: Map<String, *> = emptyMap<String, Any?>()
    private var recMemoAt = 0L
    private fun recs(): Map<String, *> {
        val n = android.os.SystemClock.uptimeMillis()
        if (n - recMemoAt > 3000L) { recMemo = try { recents() } catch (_: Throwable) { emptyMap<String, Any?>() }; recMemoAt = n }
        return recMemo
    }
    // (v390) سحب لتحت = تحديث (الرئيسية · الاشتراكات · أنت) زي قايمة الفيديوهات
    private val ptr = IconTextView(act).apply {
        text = "🔄"; textSize = 20f; gravity = Gravity.CENTER; background = ui.box(th.surface, th.border, 22)
        elevation = ui.dp(6).toFloat(); alpha = 0f; translationY = -ui.dp(50).toFloat()
    }
    private fun endPull() {
        lv.animate().translationY(0f).setDuration(250).start()
        ptr.animate().alpha(0f).translationY(-ui.dp(50).toFloat()).setDuration(250).start()
    }
    private fun pullRefresh() {
        when (cur?.tab) {
            0 -> { YtNp.clearHome(); if (YtAccount.loggedIn()) YtAccount.syncAsync(true); openTab(0) }
            1 -> openSubs(true)
            4 -> if (YtAccount.loggedIn()) syncNow() else openTab(4)
        }
    }
    private val busyDr = WinSpinnerDrawable()
    private val busyOff = Runnable { busy(false) }
    /** (v337) بيتضاف في Main فوق الصفحة (pane) — بيظهر وإحنا بنجيب رابط الفيديو قبل ما المشغّل يفتح */
    val busyView = FrameLayout(act).apply {
        visibility = View.GONE; isClickable = true; setBackgroundColor(0x66000000)
        addView(View(act).apply { foreground = busyDr }, FrameLayout.LayoutParams(ui.dp(72), ui.dp(72), Gravity.CENTER))
    }
    fun busy(on: Boolean) {
        h.removeCallbacks(busyOff)
        if (on) { busyView.visibility = View.VISIBLE; busyDr.start(); h.postDelayed(busyOff, 40_000L) }
        else { busyView.visibility = View.GONE; busyDr.stop() }
    }
    /** (v467) يوتيوب بيحترم وضع الواجهة: تابلت/تلفزيون/لاندسكيب = شبكة أعمدة، مش قايمة موبايل ممدودة */
    private fun ytCols(): Int = when { DeviceUi.isTv(act) -> 4; DeviceUi.isTablet(act) -> 2; act.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE -> 2; else -> 1 }
    private val lv = GridView(act)
    /** (v477) عرض كارت الفيديو: على التلفزيون عرض العمود (4 أعمدة)، وعلى الباقي عرض الشاشة زي ما كان */
    private fun cardW(): Int {
        val sw = act.resources.displayMetrics.widthPixels
        return if (DeviceUi.isTv(act)) (sw - ui.dp(32) - ui.dp(36)) / 4 else sw
    }
    /** (v477) تلفزيون: التركيز يروح لشبكة الفيديوهات (بيتندهي من Main لما تفتح تبويب يوتيوب) */
    fun tvFocusList() { if (DeviceUi.isRemote(act)) lv.post { try { if (root.visibility == View.VISIBLE && lv.isShown && !watch.isOpen && !chan.isOpen) lv.requestFocus() } catch (_: Throwable) {} } }   // (v479) مايسرقش التركيز وصفحة المشاهدة/القناة مفتوحة
    private lateinit var ytDock: YtDock               // (v417) شريط مصغّر سفلي بيكمّل الفيديو لما تنزّل من صفحة المشاهدة
    private lateinit var watch: YtWatchPage          // (v402) صفحة المشاهدة (مشغّل مصغّر + تعليقات + مقترحات)
    private lateinit var chan: YtChannelPage         // (v403) صفحة القناة (بانر + اشتراك + جرس + تابات)
    private var navBar: View? = null
    private var navLine: View? = null
    private var lastDownX = 0
    @Volatile private var animRows = false
    private val statusTv: TextView
    private val search: EditText
    private val sugBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    // (v348) شريط يوتيوب السفلي + الشورتس: page = كل الشاشة القديمة (بحث/قوايم)، shortsHost = صفحة الشورتس فوقها
    private val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private var navSel = 0
    private lateinit var topTitle: TextView
    private val lite = act.packageName.endsWith(".lite")
    /** (v475) تبويبات يوتيوب في شريط البرنامج السفلي: Main بيسمع هنا (onTab) وبيبعت الضغطات (tapTab) */
    var onTab: ((Int) -> Unit)? = null
    val currentTab: Int get() = navSel
    private val promptBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE; setPadding(ui.dp(28), ui.dp(16), ui.dp(28), ui.dp(16)) }
    private val navIcons = ArrayList<YtIcon>()
    private val navLabs = ArrayList<TextView>()
    private val navBars = ArrayList<View>()   // (v472) خط التمييز تحت التبويب النشط
    private val chanRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
    private val chanName: TextView
    private val subBtn: TextView
    private val filterRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
    private val subsRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }   // (v327)
    private lateinit var notifBtn: TextView
    private val filterViews = ArrayList<TextView>()

    private val detailBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(ui.dp(10), ui.dp(2), ui.dp(10), ui.dp(4)) }
    private lateinit var dThumb: ImageView
    private lateinit var dTitle: TextView
    private lateinit var dMeta: TextView
    private lateinit var dChan: TextView
    private lateinit var dDesc: TextView
    private lateinit var dSave: TextView
    private lateinit var dRel: TextView
    private lateinit var dCom: TextView
    private var cur: St? = null
    private val stack = ArrayList<St>()
    private var gen = 0
    private var loadingMore = false
    private var sugGen = 0
    private var retry: (() -> Unit)? = null
    private val adapter = Adapter()
    private val FILTERS = listOf("" to "الكل", "videos" to "فيديوهات", "channels" to "قنوات", "playlists" to "قوايم")
    // (v469) C5/C10: ترتيب القوايم · إخفاء المُشاهَد · فلاتر بحث (مدة/تاريخ) · تشغيل قايمة كاملة
    private var sortMode = 0
    private var hideSeen = try { Cfg.str("ytp_hide_seen", "0") == "1" } catch (_: Throwable) { false }
    private var pendDurF = 0; private var pendDateF = 0
    private var pendingPlayAll = false
    private val sortLabels = listOf("الافتراضي", "الأحدث", "الأكثر مشاهدة", "الأطول", "الأقصر")
    private val durLabels = listOf("الكل", "قصير", "متوسط", "طويل")
    private val dateLabels = listOf("الكل", "اليوم", "الأسبوع", "الشهر")
    private var ctlRow: LinearLayout? = null
    private var ctlSort: TextView? = null
    private var ctlHide: TextView? = null
    private var ctlDur: TextView? = null
    private var ctlDate: TextView? = null

    // (v397) شريط يوتيوب العلوي (شعار + أيقونات) وشريط البحث وشرائح الرئيسية
    private val appBar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(YtSkin.bg) }
    private val searchBar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; setBackgroundColor(YtSkin.bg) }
    private var searchTyping = false
    private val HOME_CHIPS = listOf("الكل" to "", "موسيقى" to "موسيقى", "ألعاب" to "ألعاب", "أخبار" to "أخبار", "مباشر" to "بث مباشر", "رياضة" to "رياضة",
        "طبخ" to "وصفات طبخ", "كوميديا" to "كوميديا", "قرآن" to "قرآن كريم", "أفلام" to "أفلام", "تعلّم" to "شرح دروس", "تقنية" to "تقنية")
    private val chipViews = ArrayList<TextView>()
    private var chipSel = 0
    private val chipsScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE; setBackgroundColor(YtSkin.bg) }

    init {
        // (v397) الشريط العلوي زي يوتيوب: شعار + أيقونات (إشعارات · بحث · إعدادات)؛ البحث بيفتح شريط (سهم رجوع + خانة + مسح)
        search = EditText(act).apply {
            hint = "ابحث في يوتيوب"; setHintTextColor(YtSkin.sub); setTextColor(YtSkin.text); textSize = 15f
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(ui.dp(16), 0, ui.dp(12), 0)
            background = GradientDrawable().apply { setColor(YtSkin.surface); cornerRadius = ui.dp(20).toFloat() }
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { doSearch(text.toString()); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { suggest(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        fun iconBtn(kind: String, desc: String, onClick: () -> Unit): YtIcon = YtIcon(act, kind).apply {
            contentDescription = desc; foreground = YtSkin.ripple(true); setOnClickListener { onClick() }
        }
        // (v479 م5) تلفزيون: زرار 🎤 بحث صوتي (RecognizerIntent) جنب البحث — الكتابة بلوحة مفاتيح النظام زي ما هي
        fun micBtn(): TextView = TextView(act).apply { text = "🎤"; textSize = 20f; gravity = Gravity.CENTER; foreground = YtSkin.ripple(true); contentDescription = "بحث صوتي"; setOnClickListener { startVoice() } }
        // (v472) تبويبات يوتيوب (الرئيسية · الاشتراكات · أنت) بقت في الصف العلوي نفسه مع البحث والإعدادات — مفيش شريط سفلي تاني فوق شريط البرنامج
        val tabsBox = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        listOf("الرئيسية", "الاشتراكات", "أنت").forEachIndexed { i, name ->
            val lab = TextView(act).apply { text = name; textSize = 14.5f; gravity = Gravity.CENTER; setSingleLine(); includeFontPadding = false; typeface = YtSkin.medium }
            val bar = View(act).apply { background = GradientDrawable().apply { setColor(YtSkin.accent); cornerRadius = ui.dp(2).toFloat() }; visibility = View.INVISIBLE }
            val cell = FrameLayout(act).apply { foreground = YtSkin.ripple(); setOnClickListener { navTap(i) } }
            cell.addView(lab, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            cell.addView(bar, FrameLayout.LayoutParams(ui.dp(26), ui.dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            navLabs.add(lab); navBars.add(bar)
            tabsBox.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        }
        appBar.setPadding(ui.dp(6), ui.dp(2), ui.dp(4), ui.dp(2))
        // (v475) التبويبات (الرئيسية · الاشتراكات · أنا) بقت في الشريط السفلي بتاع البرنامج — فوق بقى عنوان الصفحة + بحث + إعدادات بس
        topTitle = TextView(act).apply { text = "يوتيوب"; textSize = 19f; setTextColor(YtSkin.text); typeface = YtSkin.medium; gravity = Gravity.CENTER_VERTICAL or Gravity.START; setSingleLine(); setPadding(ui.dp(12), 0, ui.dp(8), 0) }
        appBar.addView(topTitle, LinearLayout.LayoutParams(0, ui.dp(46), 1f))
        if (DeviceUi.isTv(act)) {   // (v527) تلفزيون: تابات يوتيوب (الرئيسية · الاشتراكات · أنا) جوه صفحة يوتيوب نفسها (الشريط السفلي مشال على التلفزيون) — بالأسهم + OK
            for (k in 0 until tabsBox.childCount) tabsBox.getChildAt(k).apply { isFocusable = true; isClickable = true }
            appBar.addView(tabsBox, LinearLayout.LayoutParams(ui.dp(360), ui.dp(46)).apply { marginEnd = ui.dp(10) })
        }
        if (DeviceUi.isRemote(act)) appBar.addView(micBtn(), LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)))
        appBar.addView(iconBtn("search", "بحث") { enterSearch(true) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        appBar.addView(iconBtn("gear", "إعدادات يوتيوب") { settings() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val backBtn = iconBtn("back", "رجوع") { exitSearch() }.apply { scaleX = -1f }
        val clearBtn = iconBtn("close", "مسح") { search.setText(""); search.requestFocus(); showKb() }
        searchBar.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4))
        searchBar.addView(backBtn, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        searchBar.addView(search, LinearLayout.LayoutParams(0, ui.dp(40), 1f))
        if (DeviceUi.isRemote(act)) searchBar.addView(micBtn(), LinearLayout.LayoutParams(ui.dp(48), ui.dp(44)))
        searchBar.addView(clearBtn, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        page.addView(appBar, LinearLayout.LayoutParams(-1, -2))
        page.addView(searchBar, LinearLayout.LayoutParams(-1, -2))
        page.addView(sugBox, LinearLayout.LayoutParams(-1, -2))
        // (v397) شرائح الرئيسية (الكل · موسيقى · ألعاب …): الضغط على شريحة بيبدّل فيد الرئيسية من غير ما تسيب الصفحة
        val chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) }
        HOME_CHIPS.forEachIndexed { i, (label, term) ->
            val tv = TextView(act).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine()
                typeface = YtSkin.medium
                setPadding(ui.dp(12), 0, ui.dp(12), 0)
                setOnClickListener { homeChip(i, term) }
            }
            chipViews.add(tv)
            chipRow.addView(tv, LinearLayout.LayoutParams(-2, ui.dp(32)).apply { marginEnd = ui.dp(8) })
        }
        chipsScroll.addView(chipRow)
        page.addView(chipsScroll, LinearLayout.LayoutParams(-1, -2))
        paintHomeChips()

        // صف القناة (اسم + اشتراك)
        chanName = ui.text("", 15f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        subBtn = ui.text("🔔 اشترك", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            setOnClickListener { toggleSub() }
        }
        chanRow.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(6))
        chanRow.addView(chanName, LinearLayout.LayoutParams(0, -2, 1f))
        chanRow.addView(subBtn, LinearLayout.LayoutParams(-2, -2))
        page.addView(chanRow, LinearLayout.LayoutParams(-1, -2))

        // (v327) صف الاشتراكات: إشعارات الفيديوهات الجديدة + تحديث
        notifBtn = ui.text("", 12f, th.text, true).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)); setSingleLine(); ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { toggleNotif() }
        }
        val refBtn = ui.text("🔄 تحديث", 12f, th.primary, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); setOnClickListener { openSubs(true) }
        }
        subsRow.setPadding(ui.dp(14), ui.dp(2), ui.dp(10), ui.dp(4))
        subsRow.addView(notifBtn, LinearLayout.LayoutParams(0, -2, 1f))
        subsRow.addView(refBtn, LinearLayout.LayoutParams(-2, -2))
        page.addView(subsRow, LinearLayout.LayoutParams(-1, -2))
        paintNotif()
        YtRss.attach(act); YtAccount.init(act)
        YtNp.attach(act)   // (v407) كاش الرئيسية على الملف
        YtNp.onHome = { r -> act.runOnUiThread { onFeedUpdate(0, r.items) } }
        YtRss.onFeed = { r -> act.runOnUiThread { onFeedUpdate(1, r.items) } }
        try { YtNotify.schedule(act) } catch (_: Throwable) {}

        // فلاتر نتايج البحث
        filterRow.setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(6)); filterRow.layoutDirection = View.LAYOUT_DIRECTION_RTL
        FILTERS.forEach { (k, n) ->
            val tv = ui.text(n, 12f, th.text).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5)); tag = k
                setOnClickListener { cur?.q?.let { q -> doSearch(q, k) } }
            }
            filterViews.add(tv)
            filterRow.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        page.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filterRow) }, LinearLayout.LayoutParams(-1, -2))

        // (v469) صف التحكم في القوايم: ترتيب · إخفاء المُشاهَد · (للبحث) مدة/تاريخ الرفع
        val ctl = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(6)) }
        fun ctlChip(onClick: (View) -> Unit): TextView = ui.text("", 12f, th.text).apply { gravity = Gravity.CENTER; setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5)); setOnClickListener { v -> onClick(v) }; paintChip(this, false) }
        ctlSort = ctlChip { v -> sortMenu(v) }
        ctlHide = ctlChip { toggleHideSeen() }
        ctlDur = ctlChip { v -> durMenu(v) }
        ctlDate = ctlChip { v -> dateMenu(v) }
        for (c in listOf(ctlSort!!, ctlHide!!, ctlDur!!, ctlDate!!)) ctl.addView(c, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        ctlRow = ctl
        page.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(ctl) }, LinearLayout.LayoutParams(-1, -2))

        // (v326) صندوق تفاصيل الفيديو: صورة ▶ + عنوان + أزرار + وصف + مقترحات/تعليقات
        dThumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.surface) }
        val playMark = ui.text("▶", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(0x99000000.toInt()) } }
        val tbox = FrameLayout(act).apply { setOnClickListener { playCur(false) } }
        tbox.addView(dThumb, FrameLayout.LayoutParams(-1, -1))
        tbox.addView(playMark, FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.CENTER))
        dTitle = ui.text("", 14f, th.text, true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        dMeta = ui.text("", 11f, th.muted).apply { maxLines = 2 }
        val tcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        tcol.addView(dTitle, LinearLayout.LayoutParams(-1, -2)); tcol.addView(dMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        val r1 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        r1.addView(tbox, LinearLayout.LayoutParams(ui.dp(130), ui.dp(73))); r1.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        dChan = ui.text("", 13f, th.primary, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END; setPadding(0, ui.dp(8), 0, ui.dp(4))
            setOnClickListener { cur?.det?.let { d -> openChannel(d.channelUrl, d.channel) } } }
        fun chip(t: String, onClick: () -> Unit) = ui.text(t, 12.5f, th.text, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); setSingleLine(); setOnClickListener { onClick() }; paintChip(this, false) }
        val bPlay = chip("▶ شغّل") { playCur(false) }
        val bTr = chip("🌐 شغّل وترجم") { playCur(true) }
        dSave = chip("🔖 احفظ") { saveCur() }
        val bCopy = chip("📋") { cur?.let { st -> copy(YtNp.watchUrl(st.vid?.id ?: "")) } }
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        listOf(bPlay, bTr, dSave, bCopy).forEach { b -> btns.addView(b, LinearLayout.LayoutParams(0, -2, if (b === bCopy) 0.5f else 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }) }
        dDesc = ui.text("", 12f, th.muted).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, ui.dp(8), 0, ui.dp(6))
            setOnClickListener { maxLines = if (maxLines == 3) 40 else 3 } }
        dRel = chip("📑 مقترحات") { setMode(false) }
        dCom = chip("💬 تعليقات") { setMode(true) }
        val seg = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        listOf(dRel, dCom).forEach { b -> seg.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }) }
        detailBox.addView(r1, LinearLayout.LayoutParams(-1, -2)); detailBox.addView(dChan, LinearLayout.LayoutParams(-1, -2))
        detailBox.addView(btns, LinearLayout.LayoutParams(-1, -2)); detailBox.addView(dDesc, LinearLayout.LayoutParams(-1, -2)); detailBox.addView(seg, LinearLayout.LayoutParams(-1, -2))
        page.addView(detailBox, LinearLayout.LayoutParams(-1, -2))

        statusTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), 0); visibility = View.GONE; setOnClickListener { retry?.invoke() } }
        page.addView(statusTv, LinearLayout.LayoutParams(-1, -2))
        page.addView(promptBox, LinearLayout.LayoutParams(-1, 0, 1f))   // (v475) شاشة «سجّل دخول / زامن» بدل «مفيش حاجة»

        lv.apply {
            setBackgroundColor(YtSkin.bg); selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            numColumns = ytCols(); horizontalSpacing = ui.dp(6); verticalSpacing = ui.dp(6); stretchMode = GridView.STRETCH_COLUMN_WIDTH
            if (DeviceUi.isRemote(act)) {   // (v477) يوتيوب على التلفزيون: تحديد واضح بالريموت + Menu = قايمة الفيديو
                selector = GradientDrawable().apply { setColor(0x14FFFFFF); setStroke(ui.dp(3), YtSkin.accent); cornerRadius = ui.dp(10).toFloat() }
                setDrawSelectorOnTop(true); descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                isFocusable = true; isFocusableInTouchMode = false
                horizontalSpacing = ui.dp(12); verticalSpacing = ui.dp(12); setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)); clipToPadding = false
                setOnKeyListener { _, k, e ->
                    if (e.action == android.view.KeyEvent.ACTION_DOWN && (k == android.view.KeyEvent.KEYCODE_MENU || k == android.view.KeyEvent.KEYCODE_BUTTON_Y)) {
                        val item = cur?.list()?.getOrNull(selectedItemPosition); val sv = selectedView
                        if (item != null && sv != null && item.kind != 3 && item.id != "act") { menu(sv, item); true } else false
                    } else false
                }
            }
            this.adapter = this@YtProUi.adapter
            setOnItemClickListener { _, view, p, _ -> cur?.list()?.getOrNull(p)?.let { if (it.kind != 3) open(it, view) } }
            // (v471) تسخين: أول ما صباعك يلمس فيديو بنبدأ نجيب رابطه — لما ترفع صباعك يكون غالبًا جاهز
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    try { val g = v as GridView; val p = g.pointToPosition(e.x.toInt(), e.y.toInt()); cur?.list()?.getOrNull(p)?.let { if (it.kind == 0 && it.id.length == 11) YtFast.warm(it.id) } } catch (_: Throwable) {}
                }
                false
            }
            setOnItemLongClickListener { _, v, p, _ -> cur?.list()?.getOrNull(p)?.let { if (it.kind == 3) copy(it.title) else if (it.id != "act") menu(v, it) }; true }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) { if (s == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) prewarmVisible() }   // (v472)
                override fun onScroll(v: AbsListView?, first: Int, vis: Int, total: Int) { if (total > 0 && first + vis >= total - 3) loadMore() }
            })
        }
        page.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        lv.viewTreeObserver.addOnGlobalLayoutListener { val c = ytCols(); if (lv.numColumns != c) lv.numColumns = c }   // (v467) الأعمدة بتتظبط لو الوضع/الاتجاه اتغيّر
        run {
            var y0 = 0f; var pulling = false; var pd = 0f
            val thr = ui.dp(60).toFloat()
            lv.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y0 = e.rawY; lastDownX = e.rawX.toInt(); pulling = false; false }
                    MotionEvent.ACTION_MOVE -> {
                        val t = cur?.tab ?: -1
                        if ((t != 0 && t != 1 && t != 4) || spinBox.visibility == View.VISIBLE) return@setOnTouchListener false
                        val dy = e.rawY - y0
                        val atTop = lv.firstVisiblePosition == 0 && (lv.childCount == 0 || lv.getChildAt(0).top >= lv.paddingTop)
                        if (!pulling && dy > ui.dp(10) && atTop) {
                            pulling = true; y0 = e.rawY
                            val c = MotionEvent.obtain(e); c.action = MotionEvent.ACTION_CANCEL; lv.onTouchEvent(c); c.recycle()
                        }
                        if (pulling) {
                            pd = ((e.rawY - y0) * 0.5f).coerceIn(0f, ui.dp(120).toFloat())
                            lv.translationY = pd; ptr.translationY = pd - ui.dp(50)
                            ptr.alpha = (pd / ui.dp(40)).coerceIn(0f, 1f); ptr.rotation = pd * 4f
                            true
                        } else false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (pulling) {
                            pulling = false
                            val go = pd >= thr && e.actionMasked == MotionEvent.ACTION_UP
                            endPull()
                            if (go) pullRefresh()
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
        // (v348) شريط يوتيوب السفلي: الرئيسية · الاشتراكات · الشورتس · أنت
        val content = FrameLayout(act)
        content.addView(page, FrameLayout.LayoutParams(-1, -1))
        content.addView(spinBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))   // (v389)
        content.addView(ptr, FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))   // (v390)
        content.addView(topLine, FrameLayout.LayoutParams(-1, ui.dp(3), Gravity.TOP))   // (v407) الخط الأزرق
        content.addView(newPill, FrameLayout.LayoutParams(-2, ui.dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(58) })   // (v407) كبسولة «فيديوهات جديدة»
        chan = YtChannelPage(act, ui, object : YtChanHost {   // (v403) تحت صفحة المشاهدة: فيديو من القناة بيفتح المشاهدة فوقها
            override fun openVideo(v: NpItem) { open(v, null) }
            override fun download(v: NpItem) { dlMenu(chan, v) }   // (v459) زرار ⬇ على كروت القناة
            override fun audio(v: NpItem) { playAudio(v) }   // (v472)
            override fun openPlaylist(url: String, title: String) { chan.close(false); load("📃 " + title, -1, null, null, null, "", true, false) { YtNp.playlist(url) } }
            override fun state(open: Boolean) { setNavHidden(open || watch.isOpen); pillGate(open || watch.isOpen); if (!open) tvFocusList() }   // (v479 م5) التركيز يرجع للشبكة
        })
        content.addView(chan, FrameLayout.LayoutParams(-1, -1))
        watch = YtWatchPage(act, ui, object : YtWatchHost {   // (v402)
            override fun expand(id: String, title: String, posMs: Long, translate: Boolean, pick: YtPick?) {
                val f = onPlayAt
                if (f != null) f(id, title, translate, posMs, pick) else onPlay(id, title, translate)
            }
            override fun openChannel(url: String, name: String) { watch.close(false); this@YtProUi.openChannel(url, name, "") }
            override fun openVideo(v: NpItem) { if (DeviceUi.isTv(act) || PlayTarget.enabled()) play(v, false) else watchVideo(v, null) }   // (v560) وضع التلفزيون: ما نفتحش صفحة المشاهدة (مشغّلها المصغّر بيشغّل على الموبايل)   // (v494) تلفزيون: من غير صفحة المشاهدة
            override fun audio(v: NpItem) { playAudio(v) }
            override fun download(anchor: View, v: NpItem) { dlMenu(anchor, v) }
            override fun state(open: Boolean) { if (open) ytDock.dismiss(); setNavHidden(open || chan.isOpen); pillGate(open || chan.isOpen); if (!open) tvFocusList() }   // (v479 م5)
            override fun dock(item: NpItem, kept: YtKept) { ytDock.show(item, kept) }   // (v417)
        })
        content.addView(watch, FrameLayout.LayoutParams(-1, -1))
        ytDock = YtDock(act, ui) { id, t, pos, pk -> val f = onPlayAt; if (f != null) f(id, t, false, pos, pk) else onPlay(id, t, false) }   // (v417)
        content.addView(ytDock, FrameLayout.LayoutParams(-1, ui.dp(58), Gravity.BOTTOM))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        navBar = null; navLine = null   // (v472) الشريط السفلي الخاص بيوتيوب اتشال (التبويبات فوق)
        paintNav()
    }

    // ───────── واجهة Main ─────────
    fun refresh() { recMemoAt = 0L; if (cur == null) openTab(0) else adapter.notifyDataSetChanged(); startPoll() }
    fun onHide() { hideKb(); sugBox.visibility = View.GONE; watch.pause(); h.removeCallbacks(pollRun) }
    /** (v348) Main.onStop: الشورتس تقف لما التطبيق يروح الخلفية أو يظهر المشغّل فوقه (بترجع في refresh() مع onResume) */
    fun onPause() { watch.pause(); ytDock.pause() }
    fun destroy() { h.removeCallbacks(pollRun); h.removeCallbacks(lineWatch); YtNp.onHome = null; YtRss.onFeed = null; try { ytDock.dismiss() } catch (_: Throwable) {}; watch.release() }
    /** (v479 م5) بحث صوتي على التلفزيون: النتيجة بترجع من Main.onActivityResult (كود 78) */
    private fun startVoice() {
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "قول اسم الفيديو…")
            }
            act.startActivityForResult(i, 78)
        } catch (e: Exception) { LogStore.err("YtPro:voice", e); Notice.show(act, "⚠ البحث الصوتي مش متاح على الجهاز ده", 2200L) }
    }
    fun voiceResult(q: String) { if (q.isNotBlank()) doSearch(q.trim()) }

    fun back(): Boolean {
        if (watch.isOpen) { if (watch.tvBack()) return true; watch.close(true); return true }   // (v402) (v479 م5) تلفزيون: أول Back يرجّع التركيز للفيديو
        if (chan.isOpen) { if (chan.tvBack()) return true; chan.close(true); return true }     // (v403) (v479 م5)
        if (sugBox.visibility == View.VISIBLE) { sugBox.visibility = View.GONE; return true }
        if (stack.isNotEmpty()) { gen++; bind(stack.removeAt(stack.size - 1)); return true }
        if (searchBar.visibility == View.VISIBLE) { exitSearch(); return true }   // (v397)
        return false
    }
    /** ملف اشتراكات: NewPipe JSON أو subscriptions.csv بتاع Google Takeout */
    fun importUri(u: Uri) {
        Thread {
            var n = 0
            try {
                val txt = act.contentResolver.openInputStream(u)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val subs = YtStore.read(YtStore.SUBS)
                fun add(url: String, name: String) { if (url.isNotBlank() && subs.none { it.channelUrl == url }) { subs.add(NpItem("", name.ifBlank { url }, "", url, "", -1L, 0L, "", 1, url)); n++ } }
                if (txt.trimStart().startsWith("{")) {
                    val arr = JSONObject(txt).optJSONArray("subscriptions")
                    if (arr != null) for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); add(o.optString("url"), o.optString("name")) }
                } else {
                    txt.lineSequence().drop(1).forEach { l ->
                        val p = l.split(","); if (p.size >= 3) { val id = p[0].trim(); if (id.startsWith("UC")) add("https://www.youtube.com/channel/$id", p.drop(2).joinToString(",").trim().trim('"')) }
                    }
                }
                YtStore.write(YtStore.SUBS, subs)
            } catch (e: Throwable) { act.runOnUiThread { Notice.show(act, ("⚠ ملف الاشتراكات ماتقرّاش: " + (e.message ?: "")).toString(), 3600L) }; return@Thread }
            act.runOnUiThread { Notice.show(act, ("✅ اتضاف $n اشتراك").toString(), 3000L); if (cur?.tab == 1) openTab(1) }
        }.start()
    }

    // ───────── (v397) بحث + شرائح ─────────
    private fun showKb() { try { search.requestFocus(); (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT) } catch (_: Throwable) {} }
    private fun enterSearch(focus: Boolean) {
        searchTyping = focus
        appBar.visibility = View.GONE; searchBar.visibility = View.VISIBLE
        if (focus) { search.requestFocus(); h.postDelayed({ showKb() }, 80L) }
    }
    private fun exitSearch() {
        searchTyping = false
        hideKb(); sugBox.visibility = View.GONE; searchBar.visibility = View.GONE; appBar.visibility = View.VISIBLE
        val wasResults = cur?.q != null
        search.setText("")
        if (wasResults) openTab(0)
    }
    private fun paintHomeChips() {
        chipViews.forEachIndexed { i, tv ->
            val on = i == chipSel
            tv.setTextColor(if (on) YtSkin.chipOnText else YtSkin.text)
            tv.background = GradientDrawable().apply { setColor(if (on) YtSkin.chipOnBg else YtSkin.chip); cornerRadius = ui.dp(8).toFloat() }
        }
    }
    private fun homeChip(i: Int, term: String) {
        if (i == chipSel && i != 0) return
        chipSel = i; paintHomeChips()
        if (i == 0) { openTab(0); return }
        hideKb()
        load("🏠 " + term, 0, null, null, null, "", false, false) { val r = YtNp.searchFirst(term, "videos"); Pair(r, { p: Page -> YtNp.searchMore(term, "videos", p) }) }
    }

    // ───────── تنقّل ─────────
    private fun openTab(i: Int) {
        hideKb(); sugBox.visibility = View.GONE; stack.clear(); paintTabs(i); hidePill()
        searchTyping = false; searchBar.visibility = View.GONE; appBar.visibility = View.VISIBLE   // (v397)
        if (i == 0) { chipSel = 0; paintHomeChips() }
        when (i) {
            0 -> load("🏠 الرئيسية", 0, null, null, null, "", false, false) {
                val hh = YtStore.read(YtStore.HIST); var ss = YtStore.read(YtStore.SUBS)
                if (ss.isEmpty() && YtAccount.loggedIn()) { try { YtAccount.syncSubs() } catch (_: Throwable) {}; ss = YtStore.read(YtStore.SUBS) }   // (v375) مسجّل بجوجل ولسه ما فتحتش الاشتراكات: هات القنوات الأول
                if (YtAccount.loggedIn()) YtAccount.syncAsync()   // (v390) مزامنة تلقائية في الخلفية في كل دخول للرئيسية (نجحت الرئيسية ولا فشلت)، مش بس بعد الفشل
                // (v407) أي رئيسية محفوظة (حتى لو قديمة، أو من الملف بعد قفل البرنامج) بتتعرض فورًا، والتحديث في الخلفية وبعدها كبسولة «فيديوهات جديدة»
                val seenIds = hh.map { it.id }.toHashSet()
                val cached = YtNp.homeStale()?.let { c0 -> YtRes(c0.items.filter { x -> x.id !in seenIds }, null) }?.takeIf { it.items.size >= 6 }
                if (cached != null) {
                    if (YtNp.homeAgeMs() > 90_000L && YtNp.refreshHomeAsync(hh, ss)) act.runOnUiThread { bgLine(true) }
                    Pair(cached, null)
                }
                else {
                    // (v389) علاج ذاتي: لو الرئيسية فضيت أو فشلت (كان لازم تدخل «أنت» وتعمل مزامنة) → نعمل نفس المزامنة + نعيد تهيئة NewPipe ونحاول تاني
                    var err: Throwable? = null
                    var r = try { YtNp.home(hh, ss) } catch (e: Throwable) { err = e; YtRes(emptyList(), null) }
                    if (r.items.isEmpty()) {
                        LogStore.add("🩹 الرئيسية فاضية" + (err?.let { " (" + (it.message ?: it.javaClass.simpleName).take(80) + ")" } ?: "") + " — مزامنة + إعادة تهيئة وإعادة محاولة")
                        YtNp.reset()
                        if (YtAccount.loggedIn()) { try { YtAccount.syncSubs() } catch (e: Throwable) { LogStore.add("⚠ مزامنة العلاج الذاتي فشلت: " + (e.message ?: "").take(100)) }; ss = YtStore.read(YtStore.SUBS) }
                        try { r = YtNp.home(hh, ss); err = null } catch (e: Throwable) { err = e }
                    }
                    if (r.items.isEmpty() && err != null) throw err
                    Pair(r, null)
                }
            }
            1 -> {
                val subs = YtStore.read(YtStore.SUBS)
                if (subs.isEmpty() && !YtAccount.loggedIn()) { bind(St("📺 اشتراكاتي", 1, ArrayList(), null, null, null, null, null, "")); emptyPrompt(1, null) }
                else openSubs(false)
            }
            2, 3 -> showLocal(i, false)
            4 -> openYou()
        }
    }

    // ───────── (v348) شريط يوتيوب السفلي + الشورتس ─────────
    /** (v475) i: 0 الرئيسية · 1 الاشتراكات · 2 أنا. دوسة على التبويب وانت فيه: يرجّعك لفوق ويحدّث (نفس سحب التحديث). comeBack = جاي من بوابة تانية: لو إنت أصلًا على الصفحة دي ما يعملش حاجة */
    fun tapTab(i: Int, comeBack: Boolean = false) {
        val st = cur
        val atRoot = st != null && !watch.isOpen && !chan.isOpen && st.vid == null && st.q == null && st.chUrl == null && stack.isEmpty() && st.tab == (if (i == 2) 4 else i)
        if (atRoot && comeBack) return
        if (watch.isOpen) watch.close(false)
        if (chan.isOpen) chan.close(false)
        if (atRoot) {
            try { lv.smoothScrollToPosition(0); lv.setSelection(0) } catch (_: Throwable) {}
            when (i) { 0, 1 -> pullRefresh(); else -> openTab(4) }
            return
        }
        when (i) { 0 -> openTab(0); 1 -> openTab(1); else -> openTab(4) }
    }
    private fun navTap(i: Int) { tapTab(i) }   // (v351) الشورتس اتشالت
    private fun paintNav() {
        for (i in navLabs.indices) {
            val on = i == navSel
            navLabs[i].setTextColor(if (on) YtSkin.accent else YtSkin.sub)
            val b = navBars.getOrNull(i) ?: continue
            if (on && b.visibility != View.VISIBLE) { b.visibility = View.VISIBLE; b.scaleX = 0.2f; b.animate().cancel(); b.animate().scaleX(1f).setDuration(Skins.dur(200L)).start() }
            else if (!on) { b.animate().cancel(); b.visibility = View.INVISIBLE }
        }
    }
    // ───────── (v348) «أنت»: سجل الجهاز + المحفوظات + التحميلات + حساب جوجل في صفحة واحدة ─────────
    private fun yrow(title: String, sub: String, key: String) = NpItem("act", title, sub, "", "", -1L, -1L, "", 2, "yta:$key")
    private fun openYou() {
        val rows = ArrayList<NpItem>()
        // (v475) أول سطر: لو مش مسجّل ← تسجيل الدخول · لو مسجّل ولسه مفيش قنوات متزامنة ← مزامنة
        if (!YtAccount.loggedIn()) rows.add(yrow("🔐 سجّل الدخول بحساب جوجل", "اشتراكاتك وسجلك وقوايمك وفيديوهات الإعجاب", "!login"))
        else if (YtStore.read(YtStore.SUBS).isEmpty()) rows.add(yrow("🔄 زامن اشتراكاتك دلوقتي", "لسه مفيش قنوات متزامنة من حسابك", "!sync"))
        rows.add(yrow("🕘 سجل المشاهدة", "اللي فتحته من يوتيوب على الجهاز (" + YtStore.read(YtStore.HIST).size + ")", "!hist"))
        rows.add(yrow("⏩ أكمل المشاهدة", "فيديوهات بدأتها ومكملتهاش — بتكمل من نفس المكان", "!cont"))   // (v469) C6
        rows.add(yrow("🕘 السجل العام", "فيديوهات وصوتيات من كل المصادر — بالمسح", "!pub"))
        rows.add(yrow("🔖 المحفوظات", "(" + YtStore.read(YtStore.SAVED).size + ")", "!saved"))
        rows.add(yrow("📥 التحميلات", "الفيديوهات والصوتيات اللي نزّلتها من يوتيوب", "!dl"))
        if (YtAccount.loggedIn()) rows.addAll(YtAccount.menuItems())
        rows.add(yrow("⚙ إعدادات يوتيوب", "الجودة · حفظ السجل · المسح", "!set"))
        if (lite) rows.add(yrow("⚙ إعدادات المشغّل والتطبيق", "الترجمة · شكل الترجمة · المظهر · الخلفية · الأمان", "!setall"))   // (v475) اللايت: «أنا» و«الإعدادات» مدموجين
        bind(St("👤 أنت", 4, rows, null, null, null, null, null, ""))
    }
    /** السجل المحلي (2) أو المحفوظات (3): push=true بيحط الصفحة الحالية في الـstack عشان الرجوع يرجّعك لـ«أنت» */
    private fun showLocal(tab: Int, push: Boolean) {
        val prev = cur; val prevPos = lv.firstVisiblePosition
        if (push && prev != null) { prev.pos = prevPos; stack.add(prev) }
        gen++
        val l = ArrayList(YtStore.read(if (tab == 2) YtStore.HIST else YtStore.SAVED))
        val st = St(if (tab == 2) "🕘 السجل" else "🔖 المحفوظات", tab, l, null, null, null, null, null, "")
        if (!push && prev != null && prev.tab == tab) st.pos = prevPos
        bind(st)
        if (l.isEmpty()) status(if (tab == 2) "السجل فاضي" else "مفيش محفوظات — ضغطة طويلة على أي فيديو ← 🔖")
    }

    private fun doSearch(q0: String, filter: String = "videos") {
        val q = q0.trim(); if (q.isEmpty()) return
        hideKb(); sugBox.visibility = View.GONE; search.setText(q); search.setSelection(q.length); sugGen++
        enterSearch(false); chipsScroll.visibility = View.GONE   // (v397)
        paintTabs(-1)
        val df = pendDurF; val tf = pendDateF   // (v469) C5
        load("🔍 $q", -1, null, null, q, filter, true, false, df, tf) { val r = YtNp.searchFiltered(q, filter, df, tf); Pair(r, { p: Page -> val m = YtNp.searchMore(q, filter, p); YtRes(YtNp.applyF(m.items, df, tf), m.next) }) }
    }

    /** (v402) فيديو: صفحة المشاهدة (بأنيميشن من مكان الكارت) أو المشغّل مباشرة حسب إعداد «لما تدوس على فيديو» */
    /** (v406) علامة «مدبلج»: من الكاش، وإلا نفحص لغات الصوت في الخلفية بعد ما الكارت يفضل ظاهر ~0.8 ثانية */
    private fun bindDub(c: Card, vi: NpItem) {
        c.dub.tag = vi.id; c.dub.visibility = View.GONE
        if (vi.kind != 0 || vi.id.length != 11 || !YtDub.enabled()) return
        val cached = YtDub.get(vi.id)
        if (cached != null) { paintDub(c, vi, cached); return }
        c.root.postDelayed({
            if (c.dub.tag == vi.id) YtDub.request(vi.id) { l -> act.runOnUiThread { if (c.dub.tag == vi.id) paintDub(c, vi, l) } }
        }, 800L)
    }
    private fun paintDub(c: Card, vi: NpItem, langs: List<String>) {
        if (!YtDub.isDubbed(langs)) { c.dub.visibility = View.GONE; return }
        c.dub.visibility = View.VISIBLE
        c.dub.setOnClickListener { x ->
            val m = android.widget.PopupMenu(act, x)
            val pref = YtLang.pref()
            langs.forEachIndexed { i, l -> m.menu.add(0, i, i, (if (l == pref) "✓ " else "") + "🌐 " + YtLang.name(l, false)) }
            m.setOnMenuItemClickListener { mi -> YtLang.setOverride(vi.id, langs[mi.itemId]); open(vi, c.root); true }
            m.show()
        }
    }

    private fun setNavHidden(h: Boolean) { navBar?.visibility = if (h) View.GONE else View.VISIBLE; navLine?.visibility = if (h) View.GONE else View.VISIBLE }

    private fun watchVideo(v: NpItem, view: View?) {
        hideKb(); sugBox.visibility = View.GONE
        markCtx()
        val it = asWatch(v)
        if (YtStore.histOn()) YtStore.addFront(YtStore.HIST, it, 200)
        logWatch(it, false)
        YtStore.markApp(it.id)
        watch.seekOnOpenMs = try { YtProg.resumeMs(it.id, recs()["yt:" + it.id] as? Recent) } catch (_: Throwable) { 0L }   // (v469) C6
        var r: android.graphics.Rect? = null
        if (view != null) {
            val loc = IntArray(2); view.getLocationOnScreen(loc)
            val w = view.width; val x = loc[0]
            if (w > 0) r = android.graphics.Rect(x, loc[1], x + w, loc[1] + w * 9 / 16)
        }
        watch.open(it, r)
    }

    private fun open(it: NpItem, srcView: View? = null) {
        if (it.kind == 2 && it.url.startsWith("yta:")) { openAcc(it); return }
        if (it.url == "ytpl:all") { cur?.let { st -> playAll(st) }; return }   // (v469) C7
        if (it.url.startsWith("ytdm:")) { Notice.show(act, "ده تنزيل شغّال في مدير تنزيلات النظام — لما يخلص هيظهر تحت في القايمة والملف في التنزيلات/Subtitler", 3000L); return }   // (v469) C3
        when (it.kind) {
            0 -> if (YtOpen.mode() == YtOpen.PAGE && it.id.length == 11 && !DeviceUi.isTv(act) && !PlayTarget.enabled()) watchVideo(it, srcView) else play(it, false)   // (v560) وضع التلفزيون = play() ← PlayTarget يبعت للتلفزيون   // (v494) وضع التلفزيون: دايمًا المشغّل على طول   // (v337) دوسة على الفيديو = يتشغّل على طول (التفاصيل من الضغطة الطويلة ← ℹ)
            1 -> openChannel(it.channelUrl, it.title, it.thumb)
            2 -> load("📃 " + it.title, -1, null, null, null, "", true, false) { YtNp.playlist(it.url) }
        }
    }

    /** (v403) صفحة القناة الجديدة (بانر + أفاتار + مشتركين + اشترك + جرس + تابات). لو اللينك ناقص بندوّر على القناة بالاسم */
    private fun openChannel(url: String, name: String, avatar: String = "") {
        if (url.isBlank() && name.isBlank()) { Notice.show(act, ("القناة دي مالهاش لينك").toString(), 2500L); return }
        hideKb(); sugBox.visibility = View.GONE
        chan.open(url, name, avatar)
    }

    // ───────── (v346) حساب جوجل ─────────
    private fun startLogin() {
        val from = navSel
        val after: (Boolean) -> Unit = { ok ->
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (ok) { Notice.show(act, "✅ اتسجّل الدخول", 2200L); syncNow(); openTab(if (from == 2) 4 else from) }
                else Notice.show(act, "اتقفلت صفحة الدخول من غير ما يتم", 2200L)
            }
        }
        // (v488) تلفزيون: اختيار بين QR من الموبايل أو الدخول اليدوي
        if (TvFocus.onCtx(act)) {
            GAlert(act).setTitle("🔐 تسجيل الدخول بحساب جوجل")
                .setItems(arrayOf("📱 بالـQR من الموبايل (أسهل)", "⌨ يدوي على التلفزيون")) { _, which ->
                    if (which == 0) YtLink.showTvQr(act, ui, th, after) else YtAccount.login(act, ui, th, after)
                }.setNegativeButton("إلغاء", null).show()
            return
        }
        YtAccount.login(act, ui, th, after)
    }
    private fun syncNow() {
        Notice.show(act, "🔄 بجيب اشتراكاتك من يوتيوب…", 2000L)
        Thread {
            try {
                val n = YtAccount.syncSubs()
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    Notice.show(act, "✅ اتضاف $n قناة جديدة (الإجمالي " + YtStore.read(YtStore.SUBS).size + ")", 3500L)
                    when (cur?.tab) { 0 -> openTab(0); 1 -> openSubs(false); 4 -> openTab(4) }
                }
            } catch (e: Throwable) {
                act.runOnUiThread { Notice.show(act, "⚠ " + (e.message ?: "فشلت المزامنة").take(140), 4800L) }
            }
        }.start()
    }
    private fun openAcc(it: NpItem) {
        val key = it.url.removePrefix("yta:")
        when (key) {
            "!sync" -> syncNow()
            "!hist" -> showLocal(2, true)
            "!cont" -> showContinue()
            "!pub" -> onPubHistory()
            "!saved" -> showLocal(3, true)
            "!dl" -> openDownloads()
            "!login" -> startLogin()
            "!set" -> settings()
            "!setall" -> (act as? MainActivity)?.openSettingsRoot()
            "!out" -> GAlert(act).setTitle("🚪 تسجيل الخروج").setMessage("هتتشال بيانات الدخول من التطبيق. الاشتراكات اللي اتحفظت هتفضل زي ما هي.")
                .setPositiveButton("خروج") { _, _ -> YtAccount.logout(); Notice.show(act, "اتسجّل الخروج", 2000L); openTab(4) }
                .setNegativeButton("إلغاء", null).show()
            else -> {
                val t = if (key.startsWith("VLPL")) "📃 " + it.title else it.title
                load(t, -1, null, null, null, "", true, false) { Pair(YtAccount.listFirst(key), { p: Page -> YtAccount.listMore(p) }) }
            }
        }
    }

    private fun toggleSub() {
        val st = cur ?: return; val url = st.chUrl ?: return
        val now = YtStore.toggle(YtStore.SUBS, NpItem("", st.chName ?: url, "", url, "", -1L, 0L, "", 1, url))
        paintSub(now); try { YtNotify.schedule(act) } catch (_: Throwable) {}
        Notice.show(act, (if (now) "✅ اشتركت في " + (st.chName ?: "") else "اتشال الاشتراك").toString(), 2200L)
    }

    // ───────── (v327) فيد الاشتراكات RSS + إشعارات ─────────
    private fun openSubs(force: Boolean) {
        hidePill()
        val subs = YtStore.read(YtStore.SUBS)
        if (subs.isEmpty() && !YtAccount.loggedIn()) { openTab(1); return }
        hideKb(); sugBox.visibility = View.GONE; stack.clear(); paintTabs(1)
        load("📺 اشتراكاتي", 1, null, null, null, "", false, false) {
            // (v346) لو مسجّل بجوجل: بنزامن قنوات الحساب الأول (كل 6 ساعات أو لما تدوس تحديث) وبعدين نقرأ الاشتراكات من جديد
            // (v350) لو القنوات فاضية أو المزامنة فشلت: نحاول تاني ونظهر السبب بدل «مفيش نتايج» الصامتة
            var syncErr: String? = null
            if (YtAccount.loggedIn() && (force || YtStore.read(YtStore.SUBS).isEmpty())) {
                try { YtAccount.syncSubs() } catch (e: Throwable) { syncErr = e.message ?: e.javaClass.simpleName }
            } else if (YtAccount.loggedIn()) {   // (v390) مزامنة تلقائية في الخلفية في كل دخول؛ لو جابت قنوات جديدة والصفحة لسه مفتوحة بتتحدّث
                YtAccount.syncAsync { n -> if (n > 0) act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed && cur?.tab == 1) openSubs(false) } }
            }
            val subsNow = YtStore.read(YtStore.SUBS)
            var r = YtRes(emptyList(), null)
            var more: ((Page) -> YtRes)? = null
            // (v471) مسجّل بحسابك: فيد الاشتراكات الرسمي الأول — طلب واحد بدل طلب لكل قناة، العناوين بلغة حسابك، والمدد والتصفّح للصفحات اللي بعدها، وأقل فشلًا من RSS.
            // RSS (بإعادة المحاولة ورابط قايمة الرفع) بقى الاحتياطي. الإعداد yt_subs_official
            val official = YtAccount.loggedIn() && Cfg.bool("yt_subs_official", true)
            if (official) {
                try {
                    val a = YtAccount.listFirst("FEsubscriptions")
                    val vids = a.items.filter { it.kind == 0 && it.dur !in 1L..60L }
                    if (vids.isNotEmpty()) { r = YtRes(vids, a.next); more = { p -> val x = YtAccount.listMore(p); YtRes(x.items.filter { it.kind == 0 && it.dur !in 1L..60L }, x.next) } }
                } catch (e: Throwable) {
                    if (syncErr == null) syncErr = e.message ?: e.javaClass.simpleName
                    LogStore.add("⚠ فيد الاشتراكات الرسمي فشل — بجرّب RSS: " + (e.message ?: e.javaClass.simpleName).take(80))
                }
            }
            if (r.items.isEmpty()) {
                r = YtRss.feed(subsNow, force, !force)   // (v354) أول عرض فوري من الكاش لو موجود
                if (!force && r.items.isNotEmpty()) { act.runOnUiThread { bgLine(true) }; YtRss.refreshAsync(subsNow) }   // وبنحدّث في الخلفية للزيارة الجاية (والكبسولة لو فيه جديد)
            }
            if (r.items.isEmpty() && YtAccount.loggedIn() && !official) {
                // (v350) بديل: فيد الاشتراكات الرسمي من حسابك مباشرة (من غير RSS)
                try {
                    val a = YtAccount.listFirst("FEsubscriptions")
                    val vids = a.items.filter { it.kind == 0 }
                    if (vids.isNotEmpty()) { r = YtRes(vids, a.next); more = { p -> val x = YtAccount.listMore(p); YtRes(x.items.filter { it.kind == 0 }, x.next) } }
                } catch (e: Throwable) { if (syncErr == null) syncErr = e.message ?: e.javaClass.simpleName }
            }
            if (r.items.isEmpty() && syncErr != null) throw RuntimeException(syncErr)
            if (r.items.isNotEmpty()) { YtNotify.markSeen(r.items); YtNotify.markOpened(act) }   // اللي اتعرض ما يجيش عليه إشعار ولا عدّاد
            Pair(r, more)
        }
    }
    private fun paintNotif() {
        if (!::notifBtn.isInitialized) return
        notifBtn.text = if (YtNotify.enabled()) "🔔 إشعارات الفيديوهات الجديدة: شغّالة" else "🔕 إشعارات الفيديوهات الجديدة: مقفولة"
        notifBtn.alpha = if (YtNotify.enabled()) 1f else 0.6f
    }
    private fun toggleNotif() {
        val on = !YtNotify.enabled(); YtNotify.setEnabled(act, on); paintNotif()
        Notice.show(act, (if (on) "🔔 هتجيلك إشعارات بالفيديوهات الجديدة (فحص كل ساعة تقريبًا)" else "🔕 الإشعارات اتقفلت").toString(), 2600L)
    }
    /** (v532) لينك يوتيوب مش فيديو (من الريموت/مشاركة): قناة ← صفحة القناة · قايمة تشغيل ← القايمة · غير كده بيفضل في يوتيوب نفسه */
    fun openUrl(u: String): Boolean {
        return try {
            val low = u.lowercase()
            when {
                Regex("youtube\\.com/(@|channel/|c/|user/)").containsMatchIn(low) -> { openChannel(u, "", ""); true }
                low.contains("list=") -> { load("📃 قايمة تشغيل", -1, null, null, null, "", true, false) { YtNp.playlist(u) }; true }
                else -> false
            }
        } catch (e: Throwable) { LogStore.err("YtProUi:openUrl", e); false }
    }

    /** فتح تفاصيل فيديو من معرّفه (من إشعار مثلًا) */
    fun openVideo(id: String) {
        onPlay(id, "", false)   // (v337) من الإشعار: تشغيل مباشر من غير صفحة التفاصيل
    }

    // ───────── تحميل وعرض ─────────
    private fun load(title: String, tab: Int, chUrl: String?, chName: String?, q: String?, filter: String, push: Boolean, showChan: Boolean,
                     durF: Int = 0, dateF: Int = 0,
                     job: () -> Pair<YtRes, ((Page) -> YtRes)?>) {
        val my = ++gen
        val prev = cur; val prevPos = lv.firstVisiblePosition
        retry = { load(title, tab, chUrl, chName, q, filter, push, showChan, job = job) }
        // (v472) الرئيسية والاشتراكات بتفتح فورًا من آخر نسخة سليمة (قرص) من غير شاشة فاضية؛ التحديث في الخلفية، ولو فشل/رجع فاضي النسخة القديمة بتفضل
        val snap: List<NpItem>? = if (!push && chUrl == null && q == null && (tab == 0 || tab == 1)) {
            try {
                if (tab == 1) YtSnap.load(act, 1)
                else { val seen = YtStore.read(YtStore.HIST).map { it.id }.toHashSet(); YtNp.homeStale()?.items?.filter { it.id !in seen }?.takeIf { it.size >= 6 } }
            } catch (_: Throwable) { null }
        } else null
        val snapL: List<NpItem> = snap ?: emptyList()
        val onSnap = snapL.isNotEmpty()
        if (onSnap) {
            val s0 = St(title, tab, ArrayList(snapL), null, null, chUrl, chName, q, filter)
            bind(s0); bgLine(true)
        } else {
            status("⏳ …", null); adapter.clear(); chanRow.visibility = View.GONE; filterRow.visibility = View.GONE; detailBox.visibility = View.GONE; subsRow.visibility = View.GONE
        }
        Thread {
            try {
                if (tab != 1) YtNp.init()   // (v472) الاشتراكات مش محتاجة NewPipe (الفيد الرسمي/RSS) — NewPipe بيتهيّأ لوحده لو احتاجه كاحتياطي
                val (r, more) = job()
                if (tab == 1 && r.items.isNotEmpty()) YtSnap.save(act, 1, r.items)
                act.runOnUiThread {
                    if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (onSnap) {
                        bgLine(false)
                        val c = cur
                        if (r.items.isEmpty() || c == null || c.tab != tab || c.vid != null || c.q != null || c.chUrl != null) return@runOnUiThread   // رجع فاضي أو إنت مشيت لصفحة تانية: نسيب كل حاجة زي ما هي
                        val nst0 = St(title, tab, ArrayList(r.items), r.next, more, chUrl, chName, q, filter)
                        nst0.durF = durF; nst0.dateF = dateF
                        val same = r.items.take(8).map { it.id } == snapL.take(8).map { it.id }
                        val keep = lv.firstVisiblePosition
                        if (same) { nst0.pos = keep; cur = nst0; adapter.set(nst0.list()); lv.setSelection(keep) }   // نفس القايمة: تتحدّث في مكانها (وتركّب التصفّح للصفحات اللي بعدها)
                        else if (keep > 1) { pendingItems = r.items; pendingTab = tab; showPill(r.items.count { x -> snapL.none { it.id == x.id } }.coerceAtLeast(1)) }   // إنت نازل في القايمة: كبسولة «فيديوهات جديدة»
                        else { animRows = true; h.postDelayed({ animRows = false }, 700L); bind(nst0) }
                        return@runOnUiThread
                    }
                    if (push && prev != null) { prev.pos = prevPos; stack.add(prev) }
                    val nst = St(title, tab, ArrayList(r.items), r.next, more, chUrl, chName, q, filter)
                    nst.durF = durF; nst.dateF = dateF
                    bind(nst)
                    if (r.items.isEmpty()) { if (tab == 0 || tab == 1) emptyPrompt(tab, null) else status("مفيش نتايج\n\n(دوس هنا لإعادة المحاولة)", retry) }   // (v475)
                }
            } catch (e: Throwable) {
                act.runOnUiThread {
                    if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (onSnap) { bgLine(false); Notice.show(act, "⚠ تعذّر التحديث — عرضت آخر نسخة محفوظة", 2400L); return@runOnUiThread }
                    if (prev != null) cur = prev
                    if ((tab == 0 || tab == 1) && chUrl == null && q == null) emptyPrompt(tab, e.message ?: e.javaClass.simpleName)   // (v475) بدل رسالة خطأ لوحدها: زرار دخول/مزامنة/إعادة محاولة
                    else status("⚠ " + (e.message ?: e.javaClass.simpleName).take(160) + "\n\n(دوس هنا لإعادة المحاولة)", retry)
                }
            }
        }.start()
    }

    /** (v472) تسخين استباقي: لما القايمة تقف نجيب روابط أول فيديوهين ظاهرين (خفيف) ونحمّل صور اللي جاي — الفتح بعدها شبه فوري */
    private fun prewarmVisible() {
        try {
            if (!Cfg.bool("yt_prewarm", true)) return
            val st = cur ?: return
            if (watch.isOpen || chan.isOpen || st.vid != null) return
            val l = st.list(); if (l.isEmpty()) return
            val f = lv.firstVisiblePosition.coerceAtLeast(0)
            var n = 0
            for (i in f until minOf(l.size, f + 4)) { val x = l[i]; if (x.kind == 0 && x.id.length == 11) { YtFast.warm(x.id); if (++n >= 2) break } }
            val sw = act.resources.displayMetrics.widthPixels
            for (i in (f + 3) until minOf(l.size, f + 9)) { val t = l[i].thumb; if (t.isNotBlank()) Glide.with(act).load(t).centerCrop().override(sw, sw * 9 / 16).preload() }
        } catch (_: Throwable) {}
    }

    private fun bind(st: St) {
        cur = st; statusTv.visibility = View.GONE; spin(false); hidePrompt()
        // (v469) C7: صف «شغّل الكل» على رأس أي قايمة تشغيل
        if (st.vid == null && st.title.startsWith("📃") && st.items.any { it.kind == 0 } && st.items.none { it.url == "ytpl:all" })
            st.items.add(0, NpItem("act", "▶ شغّل الكل", st.title.removePrefix("📃").trim(), "", "", -1L, st.items.count { it.kind == 0 }.toLong(), "", 2, "ytpl:all"))
        if (pendingPlayAll && st.vid == null && st.title.startsWith("📃")) { pendingPlayAll = false; h.postDelayed({ playAll(st) }, 350L) }
        applyListPrefs(st)   // (v469) C10
        paintCtl(st)
        val det = st.vid != null
        detailBox.visibility = if (det) View.VISIBLE else View.GONE
        chanRow.visibility = if (st.chUrl != null && !det) View.VISIBLE else View.GONE
        subsRow.visibility = if (st.tab == 1 && !det && st.items.isNotEmpty()) View.VISIBLE else View.GONE; paintNotif()
        if (st.chUrl != null) { chanName.text = st.chName ?: ""; paintSub(YtStore.contains(YtStore.SUBS, NpItem("", "", "", st.chUrl, "", -1L, 0L, "", 1, st.chUrl))) }
        filterRow.visibility = if (st.q != null && !det) View.VISIBLE else View.GONE
        filterViews.forEach { tv -> paintChip(tv, tv.tag == st.filter) }
        if (det) paintDetail(st)
        animRows = true; h.postDelayed({ animRows = false }, 700L)   // (v354) ظهور ناعم: الصفوف الأولى بتطلع بالتتابع
        adapter.grid = false   // (v397) كروت يوتيوب بدل الشبكة
        adapter.cards = st.tab == 0 || st.tab == 1 || st.q != null || st.chUrl != null || det
        chipsScroll.visibility = if (st.tab == 0 && !det && st.chUrl == null) View.VISIBLE else View.GONE
        if (st.q != null) enterSearch(false) else if (!searchTyping) { searchBar.visibility = View.GONE; appBar.visibility = View.VISIBLE }
        adapter.set(st.list()); lv.setSelection(st.pos)
        if (DeviceUi.isRemote(act) && (act.currentFocus == null || (act.currentFocus === search && !searchTyping))) tvFocusList()   // (v477) (v479 م5) بعد البحث التركيز ينتقل لنتايج البحث
        h.postDelayed({ prewarmVisible() }, 450L)   // (v472)
        if (st.tab >= 0) paintTabs(st.tab) else if (det) paintTabs(-1)
        if (DeviceUi.isTv(act)) tvCrumb(st, det)   // (v527)
    }

    /** (v527) تلفزيون: «إنت فين» — مسار ثابت فوق: يوتيوب › الاشتراكات › قناة X (أو بحث: كذا / اسم القسم) */
    private fun tvCrumb(st: St, det: Boolean) {
        if (!::topTitle.isInitialized) return
        val sb = StringBuilder("يوتيوب › ").append(when (navSel) { 0 -> "الرئيسية"; 1 -> "الاشتراكات"; else -> "أنا" })
        if (st.chUrl != null) sb.append(" › قناة ").append(st.chName ?: "")
        else if (st.q != null) sb.append(" › بحث: ").append(st.q)
        else if (st.tab >= 2 && st.title.isNotBlank()) sb.append(" › ").append(st.title)
        else if (det) sb.append(" › تفاصيل")
        topTitle.text = sb.toString(); topTitle.textSize = 17f
    }

    // ───────── (v326) تفاصيل الفيديو ─────────
    private fun openDetail(v: NpItem) {
        hideKb(); sugBox.visibility = View.GONE
        val my = ++gen
        val prev = cur; val prevPos = lv.firstVisiblePosition
        status("⏳ …", null); adapter.clear(); chanRow.visibility = View.GONE; filterRow.visibility = View.GONE; detailBox.visibility = View.GONE; subsRow.visibility = View.GONE
        retry = { openDetail(v) }
        Thread {
            try {
                val d = YtNp.details(v.id)
                act.runOnUiThread {
                    if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (prev != null) { prev.pos = prevPos; stack.add(prev) }
                    bind(St(v.title, -1, ArrayList(d.related), null, null, null, null, null, "", v, d))
                    if (d.related.isEmpty()) status("مفيش مقترحات")
                }
            } catch (e: Throwable) {
                act.runOnUiThread {
                    if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (prev != null) cur = prev
                    status("⚠ " + (e.message ?: e.javaClass.simpleName).take(160) + "\n\n(دوس هنا لإعادة المحاولة)", retry)
                }
            }
        }.start()
    }

    private fun detItem(st: St): NpItem {
        val v = st.vid!!; val d = st.det
        return NpItem(v.id, d?.title?.ifBlank { v.title } ?: v.title, d?.channel?.ifBlank { v.channel } ?: v.channel, d?.channelUrl?.ifBlank { v.channelUrl } ?: v.channelUrl,
            d?.thumb?.ifBlank { v.thumb } ?: v.thumb, d?.dur ?: v.dur, v.count, v.age, 0, v.url)
    }
    /** (v407) سجل يوتيوب المنفصل (yt_history.json): كل فيديو/صوت بيتفتح من يوتيوب بيتسجّل بقناته */
    private fun logWatch(it: NpItem, audio: Boolean) { try { YtHistory.add(act, it.id, it.title, it.channel, audio) } catch (_: Throwable) {} }
    private fun playCur(tr: Boolean) { val st = cur ?: return; if (st.vid == null) return; play(detItem(st), tr) }
    /** عنصر من التحميلات url بتاعه مكان الملف — بنرجّعه لرابط الفيديو قبل التشغيل/السجل */
    private fun asWatch(it: NpItem): NpItem = if (it.url.startsWith("http")) it else NpItem(it.id, it.title, it.channel, it.channelUrl, it.thumb, it.dur, it.count, it.age, 0, YtNp.watchUrl(it.id))
    /** (v340) لو الفيديو اتفتح من قايمة تشغيل (أو أي قايمة فيديوهات) نسجّلها عشان لوحة «التالي» تعرضها */
    private fun markCtx() { val st = cur; if (st != null && st.vid == null && st.title.startsWith("📃")) YtNext.setContext(st.list(), st.title.removePrefix("📃").trim()) else YtNext.clearContext() }
    private fun play(it0: NpItem, tr: Boolean) {
        markCtx(); ytDock.dismiss()   // (v417)
        val it = asWatch(it0)
        if (YtStore.histOn()) YtStore.addFront(YtStore.HIST, it, 200)
        logWatch(it, false)
        YtStore.markApp(it.id)
        // (v469) C6: استكمال تلقائي من آخر موضع (إلا في وضع الترجمة)
        if (!tr) {
            val rs = try { YtProg.resumeMs(it.id, recs()["yt:" + it.id] as? Recent) } catch (_: Throwable) { 0L }
            val f = onPlayAt
            if (rs > 0 && f != null) { f(it.id, it.title, false, rs, null); return }
        }
        onPlay(it.id, it.title, tr)
    }
    private fun playAudio(it0: NpItem) {
        markCtx(); ytDock.dismiss()   // (v417)
        val it = asWatch(it0)
        if (YtStore.histOn()) YtStore.addFront(YtStore.HIST, it, 200)
        logWatch(it, true)
        YtStore.markApp(it.id)
        onAudio(it)
    }
    /** (v472) ⬇ على أي صورة فيديو: صفحة اختيار الجودة مباشرة (من غير قايمة وسيطة) */
    private fun dlMenu(anchor: View, it: NpItem) { qualityDl(asWatch(it)) }
    /** (v472) اختيار الجودة ← مهمة تحميل في «المهام» (دايرة التقدم العايمة + عدّاد الباقي). الروابط بتتجاب من نفس السباق السريع (YtFast) بدل NewPipe التقيل */
    private fun qualityDl(v: NpItem) {
        Notice.show(act, "⏳ بجيب الجودات المتاحة…", 1500L)
        Thread {
            val labels = ArrayList<String>(); val urls = ArrayList<String>(); val auds = ArrayList<String>(); val hs = ArrayList<Int>(); var uas = ""
            try {
                val g = YtFast.video(v.id, 0)
                if (g != null) {
                    uas = g.ua
                    val a = org.json.JSONArray(g.qlist)
                    var anyAud = ""
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i); val au = o.optString("a")
                        if (au.isNotBlank() && anyAud.isEmpty()) anyAud = au
                        labels.add("🎬 " + o.optString("l") + if (au.isBlank()) "" else "  ·  صورة + صوت (بيتدمجوا)"); urls.add(o.optString("u")); auds.add(au); hs.add(o.optInt("h", 0))
                    }
                    val audioUrl = anyAud.ifBlank { try { YtFast.audio(v.id, 0)?.url ?: "" } catch (_: Throwable) { "" } }
                    if (audioUrl.isNotBlank()) { labels.add("🎧 صوت بس (أعلى جودة)"); urls.add(audioUrl); auds.add("A"); hs.add(0) }
                }
            } catch (_: Throwable) {}
            if (labels.isEmpty()) {   // قايمة الجودات مش جاية: على الأقل رابط واحد بالجودة الافتراضية
                try { val g2 = YtFast.video(v.id, 0); if (g2 != null && g2.url.isNotBlank()) { uas = g2.ua; labels.add("🎬 الجودة الافتراضية"); urls.add(g2.url); auds.add(g2.audio ?: ""); hs.add(0) } } catch (_: Throwable) {}
            }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (labels.isEmpty()) { Notice.show(act, "⚠ ما قدرتش أجيب روابط التحميل", 2600L); return@runOnUiThread }
                GAlert(act).setTitle("⬇ اختار جودة التحميل").setItems(labels.toTypedArray()) { _, w ->
                    val audioOnly = auds[w] == "A"
                    YtDlTask.start(act.applicationContext, v, urls[w], if (audioOnly) null else auds[w], uas, audioOnly, hs[w])
                    Notice.show(act, "⬇ اتضاف للمهام — الدايرة العايمة بتوريك الباقي", 2600L)
                }.setNegativeButton("إلغاء", null).show()
            }
        }.apply { isDaemon = true }.start()
    }
    // ───────── (v337) إعدادات يوتيوب ─────────
    private fun settings() {
        val m = act as? MainActivity
        if (m != null) { m.openSettingsTab("youtube"); return }   // (v355) إعدادات يوتيوب بقت قسم جوه إعدادات البرنامج
        settingsLegacy()
    }
    private fun settingsLegacy() {
        val q = Cfg.int("yt_maxh", 0)
        val items = arrayOf("🎞 الجودة الافتراضية: " + (if (q <= 0) "أعلى جودة" else q.toString() + "p"),
            "🕘 حفظ سجل المشاهدة: " + (if (YtStore.histOn()) "شغّال" else "مقفول"),
            "📥 التحميلات المسجّلة", "🗑 مسح السجل", "🗑 مسح سجل التحميلات",
            "👤 حساب جوجل: " + (if (YtAccount.loggedIn()) "مسجّل الدخول" else "مش مسجّل"),
            "⏭ تخطي الرعاة (SponsorBlock): " + (if (YtSponsor.on()) "شغّال" else "مقفول"))
        GAlert(act).setTitle("⚙ إعدادات يوتيوب").setItems(items) { _, w ->
            when (w) {
                0 -> qualityDialog()
                1 -> { val on = !YtStore.histOn(); Cfg.put("ytp_hist_on", if (on) "1" else "0"); Notice.show(act, (if (on) "🕘 السجل شغّال" else "🕘 السجل اتقفل (اللي اتسجّل قبل كده لسه موجود)").toString(), 2400L) }
                2 -> openDownloads()
                3 -> { YtStore.write(YtStore.HIST, emptyList()); PlayTarget.clearTvHistory(act, "yt"); Notice.show(act, ("🗑 السجل اتمسح").toString(), 1800L); if (cur?.tab == 2) showLocal(2, false) }
                4 -> { YtStore.write(YtStore.DL, emptyList()); Notice.show(act, ("🗑 سجل التحميلات اتمسح").toString(), 1800L) }
                5 -> openTab(4)
                6 -> { val on = !YtSponsor.on(); Cfg.put("yt_sponsor", if (on) "1" else "0"); Notice.show(act, (if (on) "⏭ تخطي الرعاة شغّال" else "⏭ تخطي الرعاة اتقفل").toString(), 2200L) }
                else -> {}
            }
        }.setNegativeButton("إغلاق", null).show()
    }
    private fun qualityDialog() {
        val hs = intArrayOf(0, 1080, 720, 480, 360, 240)
        val labels = hs.map { if (it == 0) "أعلى جودة" else it.toString() + "p" }.toTypedArray()
        GAlert(act).setTitle("🎞 الجودة الافتراضية").setItems(labels) { _, w ->
            Cfg.put("yt_maxh", hs[w].toString()); Notice.show(act, ("🎞 الجودة الافتراضية: " + labels[w]).toString(), 2200L)
        }.setNegativeButton("إلغاء", null).show()
    }
    private fun openDownloads() {
        hideKb(); sugBox.visibility = View.GONE
        val my = ++gen
        val prev = cur; val prevPos = lv.firstVisiblePosition
        Thread {
            val rows = ArrayList<NpItem>()
            try { for (r in YtDl.recs()) { val st = YtDl.status(act, r); rows.add(NpItem("act", (if (r.audio) "🎧 " else "🎬 ") + r.title, st.first + " · " + r.q, "", "", -1L, -1L, "", 2, "ytdm:" + r.dmId)) } } catch (_: Throwable) {}
            rows.addAll(YtStore.read(YtStore.DL))
            act.runOnUiThread {
                if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (prev != null && prev.title != "📥 التحميلات") { prev.pos = prevPos; stack.add(prev) }
                bind(St("📥 التحميلات", -1, rows, null, null, null, null, null, ""))
                if (rows.isEmpty()) status("مفيش تحميلات — ضغطة طويلة على أي فيديو ← ⬇")
            }
        }.apply { isDaemon = true }.start()
        // (v469) تحديث حي لحالة تنزيلات DownloadManager وانت في الصفحة
        h.postDelayed({ if (cur?.title == "📥 التحميلات" && !watch.isOpen && !chan.isOpen) openDownloads() }, 4000L)
    }
    private fun saveCur() {
        val st = cur ?: return; if (st.vid == null) return
        val on = YtStore.toggle(YtStore.SAVED, detItem(st)); Notice.show(act, (if (on) "🔖 اتحفظ" else "اتشال من المحفوظات").toString(), 1800L); paintDetail(st)
    }
    private fun copy(t: String) { (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("t", t)); Notice.show(act, ("📋 اتنسخ").toString(), 1500L) }

    private fun paintDetail(st: St) {
        val v = st.vid ?: return; val d = st.det
        dTitle.text = d?.title?.ifBlank { v.title } ?: v.title
        val views = d?.views ?: v.count
        dMeta.text = listOf(if (views >= 0) fmtN(views) + " مشاهدة" else "", if ((d?.likes ?: -1L) >= 0) "👍 " + fmtN(d!!.likes) else "", d?.age ?: v.age).filter { it.isNotBlank() }.joinToString(" · ")
        dChan.text = "📺 " + (d?.channel?.ifBlank { v.channel } ?: v.channel)
        if (v.id.length == 11) YtDislike.fetch(v.id) { dd -> act.runOnUiThread { if (dd >= 0 && cur?.vid?.id == v.id && !dMeta.text.contains("👎")) dMeta.text = dMeta.text.toString() + " · 👎 " + fmtN(dd) } }   // (v469) C8
        dDesc.text = (d?.desc ?: "").trim().ifBlank { "(مفيش وصف)" }; dDesc.maxLines = 3
        dSave.text = if (YtStore.contains(YtStore.SAVED, v)) "❌ محفوظ" else "🔖 احفظ"
        paintChip(dRel, !st.cMode); paintChip(dCom, st.cMode)
        val tu = d?.thumb?.ifBlank { v.thumb } ?: v.thumb
        try { if (tu.isNotBlank()) Glide.with(act).load(tu).centerCrop().override(ui.dp(300), ui.dp(170)).into(dThumb) else Glide.with(act).clear(dThumb) } catch (_: Throwable) {}
    }

    private fun setMode(comments: Boolean) {
        val st = cur ?: return; val v = st.vid ?: return
        if (st.cMode == comments) return
        st.cMode = comments; paintChip(dRel, !comments); paintChip(dCom, comments); statusTv.visibility = View.GONE
        if (!comments || st.cLoaded) { adapter.set(st.list()); lv.setSelection(0); if (st.list().isEmpty()) status(if (comments) "مفيش تعليقات" else "مفيش مقترحات"); return }
        adapter.clear(); status("⏳ …", null)
        Thread {
            try {
                val (r, more) = YtNp.comments(v.id)
                act.runOnUiThread {
                    st.ci.addAll(r.items); st.cn = r.next; st.cm = more; st.cLoaded = true
                    if (cur === st && st.cMode) { statusTv.visibility = View.GONE; adapter.set(st.ci); if (st.ci.isEmpty()) status("مفيش تعليقات") }
                }
            } catch (e: Throwable) {
                act.runOnUiThread { if (cur === st && st.cMode) { st.cMode = false; paintChip(dCom, false); paintChip(dRel, true); adapter.set(st.items); status("⚠ التعليقات مش متاحة: " + (e.message ?: e.javaClass.simpleName).take(120)) } }
            }
        }.start()
    }

    private fun loadMore() {
        val st = cur ?: return; val nx = st.nxt() ?: return; val mf = st.mf() ?: return
        if (loadingMore) return
        loadingMore = true
        val inC = st.cMode
        Thread {
            try {
                val r = mf(nx)
                act.runOnUiThread {
                    if (cur === st && st.cMode == inC) {
                        val l = st.list(); val seen = l.map { it.id + it.url + it.title.take(40) }.toHashSet()
                        r.items.forEach { if (seen.add(it.id + it.url + it.title.take(40))) l.add(it) }
                        if (inC) st.cn = r.next else st.next = r.next
                        if (!inC) applyListPrefs(st)   // (v469) C10
                        adapter.notifyDataSetChanged()
                    }
                    loadingMore = false
                }
            } catch (_: Throwable) { act.runOnUiThread { if (inC) st.cn = null else st.next = null; loadingMore = false } }
        }.start()
    }

    // ───────── (v475) شاشة «مش مسجّل دخول» / «لسه ما اتزامنتش» بزرار في النص بدل «مفيش حاجة» ─────────
    private fun hidePrompt() { if (promptBox.visibility == View.VISIBLE) promptBox.visibility = View.GONE; if (lv.visibility != View.VISIBLE) lv.visibility = View.VISIBLE }
    private fun showPrompt(msg: String, vararg acts: Pair<String, () -> Unit>) {
        spin(false); statusTv.visibility = View.GONE
        promptBox.removeAllViews()
        promptBox.addView(ui.text(msg, 15f, th.text).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, ui.dp(14)) }, LinearLayout.LayoutParams(-1, -2))
        acts.forEachIndexed { k, a -> promptBox.addView(ui.button(a.first, k == 0) { a.second() }, LinearLayout.LayoutParams(-1, -2)) }
        lv.visibility = View.GONE; promptBox.visibility = View.VISIBLE
    }
    /** الرئيسية/الاشتراكات رجعت فاضية أو فشلت: لو مش مسجّل ← تسجيل الدخول، لو مسجّل ولسه ما اتزامنش ← مزامنة. why = سبب الفشل لو فيه */
    private fun emptyPrompt(tab: Int, why: String?) {
        val pre = if (why.isNullOrBlank()) "" else "⚠ " + why.take(140) + "\n\n"
        if (!YtAccount.loggedIn()) {
            val m = if (tab == 1) "مش مسجّل دخول في يوتيوب.\nسجّل دخولك عشان تظهرلك اشتراكاتك." else "مش مسجّل دخول في يوتيوب.\nسجّل دخولك عشان الرئيسية تظهرلك فيديوهات على ذوقك."
            if (tab == 1) showPrompt(pre + m, "🔐 تسجيل الدخول" to { startLogin() }, "📥 استورد ملف اشتراكات" to { onImport() }, "↻ إعادة المحاولة" to { openTab(tab) })
            else showPrompt(pre + m, "🔐 تسجيل الدخول" to { startLogin() }, "↻ إعادة المحاولة" to { openTab(tab) })
        } else {
            val m = if (tab == 1) "الاشتراكات لسه ما اتزامنتش مع حسابك." else "الرئيسية فاضية لحد ما حسابك يتزامن."
            showPrompt(pre + m, "🔄 مزامنة الآن" to { syncNow() }, "↻ إعادة المحاولة" to { openTab(tab) })
        }
    }

    private fun status(t: String, onTap: (() -> Unit)? = null) {
        hidePrompt()
        retry = onTap
        if (t.startsWith("⏳")) { statusTv.visibility = View.GONE; spin(true) }   // (v337) لودينج بدل النص
        else { spin(false); statusTv.text = t; statusTv.visibility = View.VISIBLE }
    }

    // ───────── اقتراحات البحث ─────────
    private fun suggest(q: String) {
        val my = ++sugGen
        if (q.length < 2) { sugBox.visibility = View.GONE; return }
        h.postDelayed({
            if (my != sugGen) return@postDelayed
            Thread {
                val l = YtNp.suggestions(q).take(8)
                act.runOnUiThread {
                    if (my != sugGen || l.isEmpty()) { if (my == sugGen) sugBox.visibility = View.GONE; return@runOnUiThread }
                    sugBox.removeAllViews()
                    l.forEach { s ->
                        sugBox.addView(ui.text("🔍  $s", 14f, th.text).apply {
                            setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); setBackgroundColor(th.card)
                            setOnClickListener { doSearch(s) }
                        }, LinearLayout.LayoutParams(-1, -2))
                    }
                    sugBox.visibility = View.VISIBLE
                }
            }.start()
        }, 250L)
    }

    // ───────── (v469) تحكم القوايم: ترتيب · إخفاء مُشاهَد · فلاتر بحث ─────────
    private fun paintCtl(st: St?) {
        val row = ctlRow ?: return
        val show = st != null && st.vid == null && st.tab != 2 && st.tab != 3 && st.tab != 4 && !st.title.startsWith("📥")
        row.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || st == null) return
        ctlSort?.text = "↕ ترتيب: " + sortLabels[sortMode]
        ctlHide?.let { it.text = if (hideSeen) "🙈 المُشاهَد: مخفي" else "👁 المُشاهَد: ظاهر"; paintChip(it, hideSeen) }
        val isSearch = st.q != null
        ctlDur?.visibility = if (isSearch) View.VISIBLE else View.GONE
        ctlDate?.visibility = if (isSearch) View.VISIBLE else View.GONE
        if (isSearch) { ctlDur?.text = "⏱ المدة: " + durLabels[st.durF]; ctlDate?.text = "📅 الرفع: " + dateLabels[st.dateF] }
    }
    private fun applyListPrefs(st: St) {
        if (st.vid != null || st.tab == 2 || st.tab == 3 || st.tab == 4 || st.title.startsWith("📥")) return
        if (hideSeen) { val done = try { YtProg.completedIds(recs()) } catch (_: Throwable) { emptySet() }; if (done.isNotEmpty()) st.items.removeAll { it.kind == 0 && it.id in done } }
        when (sortMode) {
            1 -> st.items.sortByDescending { it.ts }
            2 -> st.items.sortByDescending { it.count }
            3 -> st.items.sortByDescending { it.dur }
            4 -> st.items.sortBy { if (it.dur > 0) it.dur else Long.MAX_VALUE }
        }
        val pa = st.items.firstOrNull { it.url == "ytpl:all" }
        if (pa != null) { st.items.remove(pa); st.items.add(0, pa) }
    }
    private fun sortMenu(anchor: View) {
        val m = PopupMenu(act, anchor)
        sortLabels.forEachIndexed { i, l -> m.menu.add(0, i, i, (if (i == sortMode) "✓ " else "") + l) }
        m.setOnMenuItemClickListener { mi -> sortMode = mi.itemId; cur?.let { st -> applyListPrefs(st); adapter.set(st.list()); paintCtl(st) }; true }
        m.show()
    }
    private fun durMenu(anchor: View) {
        val m = PopupMenu(act, anchor)
        durLabels.forEachIndexed { i, l -> m.menu.add(0, i, i, (if (i == pendDurF) "✓ " else "") + l) }
        m.setOnMenuItemClickListener { mi -> pendDurF = mi.itemId; cur?.let { st -> st.q?.let { q -> doSearch(q, st.filter) } }; true }
        m.show()
    }
    private fun dateMenu(anchor: View) {
        val m = PopupMenu(act, anchor)
        dateLabels.forEachIndexed { i, l -> m.menu.add(0, i, i, (if (i == pendDateF) "✓ " else "") + l) }
        m.setOnMenuItemClickListener { mi -> pendDateF = mi.itemId; cur?.let { st -> st.q?.let { q -> doSearch(q, st.filter) } }; true }
        m.show()
    }
    private fun toggleHideSeen() {
        hideSeen = !hideSeen
        try { Cfg.put("ytp_hide_seen", if (hideSeen) "1" else "0") } catch (_: Throwable) {}
        Notice.show(act, if (hideSeen) "🙈 الفيديوهات اللي كمّلتها هتتخبّى من القوايم" else "👁 المُشاهَد هيرجع يظهر", 2200L)
        val st = cur ?: return
        if (hideSeen) { applyListPrefs(st); adapter.set(st.list()); paintCtl(st) }
        else when {
            st.tab == 0 -> openTab(0)
            st.tab == 1 -> openSubs(false)
            st.q != null -> doSearch(st.q!!, st.filter)
            else -> paintCtl(st)
        }
    }
    /** (v469) C7: تشغيل قايمة كاملة من أولها بالترتيب (سياق «التالي» بياخد القايمة كلها) */
    private fun playAll(st: St) {
        val first = st.items.firstOrNull { it.kind == 0 } ?: return
        Notice.show(act, "▶ بنشغّل القايمة من الأول (" + st.items.count { it.kind == 0 } + " فيديو)", 2200L)
        play(first, false)
    }
    /** (v469) C6: قسم «أكمل المشاهدة» */
    private fun showContinue() {
        val prev = cur; val prevPos = lv.firstVisiblePosition
        if (prev != null) { prev.pos = prevPos; stack.add(prev) }
        gen++
        val items = ArrayList(try { YtProg.continueItems(recs()) } catch (_: Throwable) { emptyList<NpItem>() })
        bind(St("⏩ أكمل المشاهدة", -1, items, null, null, null, null, null, ""))
        if (items.isEmpty()) status("مفيش حاجة بدأتها ووقفت في النص — أي فيديو تفتحه وتقف بيه هيظهر هنا")
    }
    /** (v469) C11: مشاركة (بطابع زمني لو الفيديو ليه موضع محفوظ) */
    private fun shareItem(it: NpItem) {
        val sec = try { YtProg.resumeMs(it.id, recs()["yt:" + it.id] as? Recent) / 1000L } catch (_: Throwable) { 0L }
        val u = YtNp.watchUrl(it.id) + if (sec > 5) "&t=" + sec + "s" else ""
        try { act.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, u), "مشاركة")) } catch (_: Throwable) {}
    }

    // ───────── قايمة الضغطة الطويلة ─────────
    private fun menu(anchor: View, it: NpItem) {
        val m = PopupMenu(act, anchor)
        if (it.kind == 0) {
            m.menu.add(0, 1, 0, "▶ شغّل"); m.menu.add(0, 2, 1, "🌐 شغّل وترجم"); m.menu.add(0, 10, 7, "ℹ التفاصيل"); m.menu.add(0, 11, 8, "🎧 صوت بس (مشغّل الموسيقى)"); m.menu.add(0, 12, 9, "⬇ تحميل صوت"); m.menu.add(0, 13, 10, "⬇ تحميل فيديو")
            m.menu.add(0, 3, 2, if (YtStore.contains(YtStore.SAVED, it)) "❌ شيل من المحفوظات" else "🔖 احفظ")
            if (it.channelUrl.isNotBlank()) m.menu.add(0, 4, 3, "📺 افتح القناة")
            m.menu.add(0, 5, 4, "📋 انسخ اللينك"); m.menu.add(0, 6, 5, "🌐 افتحه في المتصفح")
            m.menu.add(0, 15, 11, "⏭ شغّل بعده (قائمة الانتظار)"); m.menu.add(0, 16, 12, "↗ مشاركة")   // (v469) C9/C11
            if (cur?.tab == 2) m.menu.add(0, 7, 6, "🗑 شيل من السجل")
        } else {
            m.menu.add(0, 8, 0, "فتح"); m.menu.add(0, 5, 1, "📋 انسخ اللينك")
            if (it.kind == 2 && !it.url.startsWith("yta:")) m.menu.add(0, 14, 3, "▶ شغّل الكل")   // (v469) C7
            if (it.kind == 1) m.menu.add(0, 9, 2, if (YtStore.contains(YtStore.SUBS, it)) "❌ إلغاء الاشتراك" else "🔔 اشترك")
        }
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> play(it, false)
                2 -> play(it, true)
                10 -> openDetail(it)
                11 -> playAudio(it)
                12 -> onDownload(asWatch(it), true)
                13 -> onDownload(asWatch(it), false)
                3 -> { val on = YtStore.toggle(YtStore.SAVED, it); Notice.show(act, (if (on) "🔖 اتحفظ" else "اتشال من المحفوظات").toString(), 1800L); if (cur?.tab == 3) showLocal(3, false) }
                4 -> openChannel(it.channelUrl, it.channel, it.avatar)
                5 -> copy(if (it.kind == 0) YtNp.watchUrl(it.id) else it.url)
                6 -> onBrowser(YtNp.watchUrl(it.id))
                7 -> { YtStore.remove(YtStore.HIST, it); showLocal(2, false) }
                15 -> { YtQueue.addNext(it); Notice.show(act, "⏭ اتضاف لقائمة الانتظار — هيتشغّل بعد الحالي", 2200L) }
                16 -> shareItem(it)
                8 -> open(it)
                14 -> { pendingPlayAll = true; open(it) }
                9 -> { val on = YtStore.toggle(YtStore.SUBS, it); Notice.show(act, (if (on) "✅ اشتركت" else "اتشال الاشتراك").toString(), 1800L) }
            }
            true
        }
        m.show()
    }

    // ───────── رسم ─────────
    /** sel: 0 رئيسية · 1 اشتراكات · 2/3/4 = «أنت» · غير كده (بحث/قناة) بيسيب التحديد زي ما هو */
    private fun paintTabs(sel: Int) {
        if (sel in 0..1) navSel = sel else if (sel in 2..4) navSel = 2
        paintNav()
        if (::topTitle.isInitialized) topTitle.text = when (navSel) { 0 -> "الرئيسية"; 1 -> "الاشتراكات"; else -> "أنا" }
        try { onTab?.invoke(navSel) } catch (_: Throwable) {}
    }
    private fun paintChip(tv: TextView, on: Boolean) {
        tv.setTextColor(if (on) YtSkin.chipOnText else YtSkin.text)
        tv.background = GradientDrawable().apply { setColor(if (on) YtSkin.chipOnBg else YtSkin.chip); cornerRadius = ui.dp(8).toFloat() }
    }
    private fun paintSub(on: Boolean) {
        subBtn.text = if (on) "✅ مشترك" else "🔔 اشترك"
        subBtn.background = GradientDrawable().apply { setColor(if (on) th.muted else th.primary); cornerRadius = ui.dp(16).toFloat() }
    }
    private fun hideKb() { try { (act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0) } catch (_: Throwable) {} }

    private fun fmtN(n: Long): String = when {
        n < 0 -> ""; n < 1000 -> n.toString()
        n < 1_000_000 -> String.format(Locale.US, "%.1fK", n / 1000.0)
        n < 1_000_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        else -> String.format(Locale.US, "%.1fB", n / 1_000_000_000.0)
    }
    private fun fmtDur(s: Long): String = if (s <= 0) "" else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    private class Hold(val thumb: ImageView, val dur: TextView, val title: TextView, val meta: TextView, val box: View, val col: View, val btns: View, val aud: View, val dl: View)

    private class Cell(val root: LinearLayout, val thumb: ImageView, val dur: TextView, val title: TextView, val meta: TextView)

    private class Card(val root: LinearLayout, val thumb: ImageView, val dur: TextView, val prog: View, val av: TextView, val avImg: ImageView,
                        val title: TextView, val meta: TextView, val more: View, val dub: TextView, val dl: View, val aud: View)

    /** (v397) كارت فيديو زي يوتيوب: صورة بعرض الشاشة 16:9 (مدة + شريط استكمال) + أفاتار القناة + العنوان + «القناة · المشاهدات · الوقت» + ⋮ */
    private fun makeCard(): Card {
        val sw = cardW()
        val medium = YtSkin.medium
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        val dur = TextView(act).apply {
            textSize = 12f; setTextColor(Color.WHITE); includeFontPadding = false; typeface = medium
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
        }
        val prog = View(act).apply { setBackgroundColor(YtSkin.accent); visibility = View.GONE }
        val box = FrameLayout(act)
        box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        box.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        box.addView(prog, FrameLayout.LayoutParams(0, ui.dp(3), Gravity.BOTTOM or Gravity.START))
        // (v406) علامة «مدبلج» (بتظهر لو الفيديو ليه أكتر من لغة صوت) — دوسة عليها بتختار اللغة
        val dub = TextView(act).apply {
            text = "🌐 مدبلج"; textSize = 11f; setTextColor(Color.WHITE); includeFontPadding = false; typeface = medium; visibility = View.GONE
            setPadding(ui.dp(7), ui.dp(4), ui.dp(7), ui.dp(4)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(5).toFloat() }
        }
        box.addView(dub, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        val tb = YtThumbBtns.attach(act, ui, box)   // (v472) 🎧 صوت + ⬇ تحميل فوق كل صورة
        val av = TextView(act).apply { gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.WHITE); includeFontPadding = false; typeface = medium }
        val avImg = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val avBox = FrameLayout(act)
        avBox.addView(av, FrameLayout.LayoutParams(-1, -1)); avBox.addView(avImg, FrameLayout.LayoutParams(-1, -1))
        val title = TextView(act).apply { textSize = if (DeviceUi.isTv(act)) 16f else 14f; setTextColor(YtSkin.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; typeface = medium }
        val meta = TextView(act).apply { textSize = if (DeviceUi.isTv(act)) 13f else 12f; setTextColor(YtSkin.sub); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        col.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        val more = YtIcon(act, "more").apply { foreground = YtSkin.ripple(true); glyph = 0.8f; contentDescription = "المزيد"; if (DeviceUi.isRemote(act)) visibility = View.GONE }   // (v521) تلفزيون: ⋮ بتاعة اللمس اتشالت (Menu بالريموت)
        val info = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.TOP
            setPadding(ui.dp(4), ui.dp(12), ui.dp(12), ui.dp(16))
        }
        info.addView(avBox, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(12) })
        info.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        info.addView(more, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(box, LinearLayout.LayoutParams(-1, sw * 9 / 16))
        root.addView(info, LinearLayout.LayoutParams(-1, -2))
        return Card(root, thumb, dur, prog, av, avImg, title, meta, more, dub, tb.dl, tb.aud)
    }

    /** (v378) كارت فيديو للشبكة (الرئيسية): صورة 16:9 وتحتها اسم الفيديو ثم القناة */
    private fun makeCell(): Cell {
        val cw = (act.resources.displayMetrics.widthPixels - ui.dp(40)) / 2
        val th0 = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.surface) }
        val dur = ui.text("", 11f, Color.WHITE, true).apply { setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() } }
        val box = FrameLayout(act)
        box.addView(th0, FrameLayout.LayoutParams(-1, -1))
        box.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
        val title = ui.text("", 13f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = ui.text("", 11f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(box, LinearLayout.LayoutParams(-1, cw * 9 / 16))
        col.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        col.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        return Cell(col, th0, dur, title, meta)
    }

    private inner class Adapter : BaseAdapter() {
        private var items: List<NpItem> = emptyList()
        var grid = false   // (v378) الرئيسية: فيديوهين جنب بعض
        var cards = false  // (v397) كروت يوتيوب
        fun set(l: List<NpItem>) { items = l; notifyDataSetChanged() }
        fun clear() { items = emptyList(); notifyDataSetChanged() }
        override fun getCount() = if (grid) (items.size + 1) / 2 else items.size
        override fun getItem(p: Int) = if (grid) items[p * 2] else items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 3
        override fun getItemViewType(p: Int): Int = if (grid) 1 else if (cards && items[p].kind == 0 && items[p].id != "act") 2 else 0
        private fun gridView(p: Int, cv: View?): View {
            val row: LinearLayout
            if (cv == null) {
                row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(10)) }
                val a = makeCell(); val b = makeCell()
                row.addView(a.root, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) })
                row.addView(b.root, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(5), 0, ui.dp(5), 0) })
                row.tag = arrayOf(a, b)
            } else row = cv as LinearLayout
            @Suppress("UNCHECKED_CAST") val pair = row.tag as Array<Cell>
            for (k in 0..1) {
                val c = pair[k]; val idx = p * 2 + k
                if (idx >= items.size) { c.root.visibility = View.INVISIBLE; c.root.setOnClickListener(null); c.root.setOnLongClickListener(null); continue }
                c.root.visibility = View.VISIBLE
                val vi = items[idx]
                c.title.text = vi.title
                c.title.alpha = if (vi.kind == 0 && recs().containsKey("yt:" + vi.id)) 0.6f else 1f
                c.meta.text = listOf(vi.channel, vi.age).filter { x -> x.isNotBlank() }.joinToString(" · ")
                val d = if (vi.kind == 0) fmtDur(vi.dur) else ""
                c.dur.text = d; c.dur.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
                try { if (vi.thumb.isNotBlank()) Glide.with(act).load(vi.thumb).centerCrop().override(ui.dp(300), ui.dp(170)).into(c.thumb) else Glide.with(act).clear(c.thumb) } catch (_: Throwable) {}
                c.root.setOnClickListener { if (vi.kind != 3) open(vi, c.root) }
                c.root.setOnLongClickListener { x -> if (vi.id != "act") menu(x, vi); true }
            }
            return row
        }
        private fun cardView(p: Int, cv: View?): View {
            val c: Card
            val v: View
            if (cv == null) { c = makeCard(); v = c.root; v.tag = c } else { v = cv; c = cv.tag as Card }
            val sw = cardW()
            val vi = items[p]
            v.animate().cancel()
            if (animRows && p < 4) {
                v.alpha = 0f; v.translationY = ui.dp(14).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(p * 28L).setDuration(Skins.dur(200L)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            } else { v.alpha = 1f; v.translationY = 0f }
            c.title.text = vi.title
            c.meta.text = listOf(vi.channel, if (vi.count >= 0) fmtN(vi.count) + " مشاهدة" else "", vi.age).filter { x -> x.isNotBlank() }.joinToString(" · ")
            val d = fmtDur(vi.dur)
            c.dur.text = d; c.dur.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            val rec = recs()["yt:" + vi.id] as? Recent
            val fr = if (rec != null && rec.durSec > 0.0) (rec.posSec / rec.durSec).coerceIn(0.0, 1.0) else 0.0
            if (fr > 0.01) { c.prog.layoutParams.width = (sw * fr).toInt(); c.prog.requestLayout(); c.prog.visibility = View.VISIBLE } else c.prog.visibility = View.GONE
            val nm = vi.channel.trim()
            c.av.text = if (nm.isEmpty()) "▶" else nm.substring(0, nm.offsetByCodePoints(0, 1)).uppercase(Locale.getDefault())
            c.av.background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(YtSkin.avatarColor(nm)) }
            if (vi.avatar.isNotBlank()) {
                c.avImg.visibility = View.VISIBLE
                try { Glide.with(act).load(vi.avatar).circleCrop().override(ui.dp(72), ui.dp(72)).into(c.avImg) } catch (_: Throwable) {}
            } else {
                c.avImg.visibility = View.GONE
                try { Glide.with(act).clear(c.avImg) } catch (_: Throwable) {}
            }
            try {
                if (vi.thumb.isNotBlank()) Glide.with(act).load(vi.thumb).centerCrop().override(sw, sw * 9 / 16)
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(140)).into(c.thumb)
                else Glide.with(act).clear(c.thumb)
            } catch (_: Throwable) {}
            c.more.setOnClickListener { x -> menu(x, vi) }
            YtThumbBtns.bind(c.aud, c.dl, vi.id, { playAudio(vi) }, { dlMenu(c.dl, vi) })   // (v472) 🎧 / ⬇ فوق الصورة
            bindDub(c, vi)
            // (v403) دوسة على صورة القناة أو سطر «القناة · المشاهدات» = صفحة القناة
            val chOpen = View.OnClickListener { openChannel(vi.channelUrl, vi.channel, vi.avatar) }
            val chMenu = View.OnLongClickListener { x -> menu(x, vi); true }
            for (cv2 in listOf<View>(c.av, c.avImg, c.meta)) { cv2.setOnClickListener(chOpen); cv2.setOnLongClickListener(chMenu) }
            return v
        }
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            if (grid) return gridView(p, cv)
            if (cards && items[p].kind == 0 && items[p].id != "act") return cardView(p, cv)
            val hd: Hold
            val v: View
            if (cv == null) {
                val th0 = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.surface) }
                val dur = ui.text("", 11f, Color.WHITE, true).apply { setPadding(ui.dp(5), ui.dp(1), ui.dp(5), ui.dp(1)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() } }
                val box = FrameLayout(act)
                box.addView(th0, FrameLayout.LayoutParams(-1, -1))
                box.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                val title = ui.text("", 14f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                val meta = ui.text("", 11f, th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(10), 0, ui.dp(10), 0); gravity = Gravity.CENTER_VERTICAL }
                col.addView(title, LinearLayout.LayoutParams(-1, -2)); col.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)) }
                row.addView(box, LinearLayout.LayoutParams(ui.dp(150), ui.dp(85)))
                row.addView(col, LinearLayout.LayoutParams(0, ui.dp(85), 1f))
                // (v472) 🎧 صوت بس · ⬇ تحميل بقوا فوق الصورة نفسها (كانوا على الجنب)
                val tb = YtThumbBtns.attach(act, ui, box, 30)
                hd = Hold(th0, dur, title, meta, box, col, tb.row, tb.aud, tb.dl); row.tag = hd; v = row
            } else { v = cv; hd = cv.tag as Hold }
            val it = items[p]
            // (v354) أنيميشن سلس: لو الصف لسه بيظهر من عرض جديد (أول 9 صفوف) بيدخل fade + slide بتتابع، غير كده بيتصفّر من أي أنيميشن قديم
            v.animate().cancel()
            if (animRows && p < 9) {
                v.alpha = 0f; v.translationY = ui.dp(22).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(p * 38L).setDuration(Skins.dur(300L)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            } else { v.alpha = 1f; v.translationY = 0f }
            hd.title.text = it.title
            val cm = it.kind == 3 || it.id == "act"
            hd.box.visibility = if (cm) View.GONE else View.VISIBLE
            val vid = it.kind == 0 && it.id.isNotBlank()
            hd.btns.visibility = if (vid) View.VISIBLE else View.GONE
            if (vid) { val item = it; YtThumbBtns.bind(hd.aud, hd.dl, item.id, { playAudio(item) }, { dlMenu(hd.dl, item) }) }
            hd.title.maxLines = if (cm) 10 else 2
            (hd.col.layoutParams as LinearLayout.LayoutParams).height = if (cm) -2 else ui.dp(85)
            val seen = it.kind == 0 && recs().containsKey("yt:" + it.id)
            hd.title.alpha = if (seen) 0.6f else 1f
            hd.meta.text = when (it.kind) {
                3 -> listOf(it.channel, if (it.count > 0) "♥ " + fmtN(it.count) else "", it.age).filter { s -> s.isNotBlank() }.joinToString(" · ")
                0 -> listOf(it.channel, if (it.count >= 0) fmtN(it.count) + " مشاهدة" else "", it.age).filter { s -> s.isNotBlank() }.joinToString(" · ")
                1 -> "📺 قناة" + if (it.count >= 0) " · " + fmtN(it.count) + " مشترك" else ""
                2 -> if (it.id == "act") it.channel else "📃 قايمة" + (if (it.channel.isNotBlank()) " · " + it.channel else "") + if (it.count >= 0) " · " + it.count + " فيديو" else ""
                else -> "📃 قايمة" + (if (it.channel.isNotBlank()) " · " + it.channel else "") + if (it.count >= 0) " · " + it.count + " فيديو" else ""
            }
            if (cm) return v
            val d = if (it.kind == 0) fmtDur(it.dur) else ""
            hd.dur.text = d; hd.dur.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            try { if (it.thumb.isNotBlank()) Glide.with(act).load(it.thumb).centerCrop().override(ui.dp(300), ui.dp(170)).into(hd.thumb) else Glide.with(act).clear(hd.thumb) } catch (_: Throwable) {}
            return v
        }
    }
}
