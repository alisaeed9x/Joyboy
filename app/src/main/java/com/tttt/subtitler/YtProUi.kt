package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import android.widget.ListView
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
              private val onPubHistory: () -> Unit = {}) {   // (v349) السجل العام   // (v337) ⬇ تحميل (true = صوت بس)

    private class St(val title: String, val tab: Int, val items: ArrayList<NpItem>, var next: Page?, val more: ((Page) -> YtRes)?,
                      val chUrl: String?, val chName: String?, val q: String?, val filter: String,
                      val vid: NpItem? = null, val det: YtDetail? = null) {
        var pos = 0
        var cMode = false; var cLoaded = false; val ci = ArrayList<NpItem>(); var cn: Page? = null; var cm: ((Page) -> YtRes)? = null
        fun list(): ArrayList<NpItem> = if (cMode) ci else items
        fun nxt(): Page? = if (cMode) cn else next
        fun mf(): ((Page) -> YtRes)? = if (cMode) cm else more
    }

    private val skinInit = YtSkin.init(th.bg)   // (v397) ألوان يوتيوب (داكن/فاتح حسب ثيم التطبيق)
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
    private fun spin(on: Boolean) { if (on) { spinBox.visibility = View.VISIBLE; spinDr.start() } else { spinBox.visibility = View.GONE; spinDr.stop() } }
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
    private val lv = ListView(act)
    @Volatile private var animRows = false
    private val statusTv: TextView
    private val search: EditText
    private val sugBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    // (v348) شريط يوتيوب السفلي + الشورتس: page = كل الشاشة القديمة (بحث/قوايم)، shortsHost = صفحة الشورتس فوقها
    private val page = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private var navSel = 0
    private val navIcons = ArrayList<YtIcon>()
    private val navLabs = ArrayList<TextView>()
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
        val logoMark = YtLogo(act)
        val logoWord = TextView(act).apply {
            text = "YouTube"; textSize = 19f; setTextColor(YtSkin.text); includeFontPadding = false; letterSpacing = -0.04f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        }
        val logoBox = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR
            setOnClickListener { openTab(0) }
        }
        logoBox.addView(logoMark, LinearLayout.LayoutParams(ui.dp(30), ui.dp(21)))
        logoBox.addView(logoWord, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(4) })
        appBar.setPadding(ui.dp(14), ui.dp(4), ui.dp(4), ui.dp(4))
        appBar.addView(logoBox, LinearLayout.LayoutParams(0, ui.dp(44), 1f))
        appBar.addView(iconBtn("bell", "الاشتراكات") { openTab(1) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        appBar.addView(iconBtn("search", "بحث") { enterSearch(true) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        appBar.addView(iconBtn("gear", "إعدادات يوتيوب") { settings() }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        val backBtn = iconBtn("back", "رجوع") { exitSearch() }.apply { scaleX = -1f }
        val clearBtn = iconBtn("close", "مسح") { search.setText(""); search.requestFocus(); showKb() }
        searchBar.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4))
        searchBar.addView(backBtn, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        searchBar.addView(search, LinearLayout.LayoutParams(0, ui.dp(40), 1f))
        searchBar.addView(clearBtn, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        page.addView(appBar, LinearLayout.LayoutParams(-1, -2))
        page.addView(searchBar, LinearLayout.LayoutParams(-1, -2))
        page.addView(sugBox, LinearLayout.LayoutParams(-1, -2))
        // (v397) شرائح الرئيسية (الكل · موسيقى · ألعاب …): الضغط على شريحة بيبدّل فيد الرئيسية من غير ما تسيب الصفحة
        val chipRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) }
        HOME_CHIPS.forEachIndexed { i, (label, term) ->
            val tv = TextView(act).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER; includeFontPadding = false; setSingleLine()
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
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

        lv.apply {
            divider = null; setBackgroundColor(YtSkin.bg); selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            this.adapter = this@YtProUi.adapter
            setOnItemClickListener { _, _, p, _ -> cur?.list()?.getOrNull(p)?.let { if (it.kind != 3) open(it) } }
            setOnItemLongClickListener { _, v, p, _ -> cur?.list()?.getOrNull(p)?.let { if (it.kind == 3) copy(it.title) else if (it.id != "act") menu(v, it) }; true }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
                override fun onScroll(v: AbsListView?, first: Int, vis: Int, total: Int) { if (total > 0 && first + vis >= total - 3) loadMore() }
            })
        }
        page.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        run {
            var y0 = 0f; var pulling = false; var pd = 0f
            val thr = ui.dp(60).toFloat()
            lv.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { y0 = e.rawY; pulling = false; false }
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
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(act).apply { setBackgroundColor(YtSkin.line) }, LinearLayout.LayoutParams(-1, ui.dp(1)))
        val navRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(YtSkin.bg) }
        listOf("home" to "الرئيسية", "subs" to "الاشتراكات", "you" to "أنت").forEachIndexed { i, d ->
            val icon = YtIcon(act, d.first).apply { glyph = 1f }
            val lab = TextView(act).apply { text = d.second; textSize = 10f; gravity = Gravity.CENTER; setSingleLine(); includeFontPadding = false }
            val cell = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(6))
                addView(icon, LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)))
                addView(lab, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(2) })
                foreground = YtSkin.ripple()
                setOnClickListener { navTap(i) }
            }
            navIcons.add(icon); navLabs.add(lab)
            navRow.addView(cell, LinearLayout.LayoutParams(0, ui.dp(54), 1f))
        }
        root.addView(navRow, LinearLayout.LayoutParams(-1, -2))
        paintNav()
    }

    // ───────── واجهة Main ─────────
    fun refresh() { if (cur == null) openTab(0) else adapter.notifyDataSetChanged() }
    fun onHide() { hideKb(); sugBox.visibility = View.GONE }
    /** (v348) Main.onStop: الشورتس تقف لما التطبيق يروح الخلفية أو يظهر المشغّل فوقه (بترجع في refresh() مع onResume) */
    fun onPause() { }
    fun destroy() { }
    fun back(): Boolean {
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
        hideKb(); sugBox.visibility = View.GONE; stack.clear(); paintTabs(i)
        searchTyping = false; searchBar.visibility = View.GONE; appBar.visibility = View.VISIBLE   // (v397)
        if (i == 0) { chipSel = 0; paintHomeChips() }
        when (i) {
            0 -> load("🏠 الرئيسية", 0, null, null, null, "", false, false) {
                val hh = YtStore.read(YtStore.HIST); var ss = YtStore.read(YtStore.SUBS)
                if (ss.isEmpty() && YtAccount.loggedIn()) { try { YtAccount.syncSubs() } catch (_: Throwable) {}; ss = YtStore.read(YtStore.SUBS) }   // (v375) مسجّل بجوجل ولسه ما فتحتش الاشتراكات: هات القنوات الأول
                if (YtAccount.loggedIn()) YtAccount.syncAsync()   // (v390) مزامنة تلقائية في الخلفية في كل دخول للرئيسية (نجحت الرئيسية ولا فشلت)، مش بس بعد الفشل
                val cached = YtNp.homeCached()   // (v362) فورًا من آخر مرة، والتحديث في الخلفية للزيارة الجاية
                if (cached != null) { YtNp.refreshHomeAsync(hh, ss); Pair(cached, null) }
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
                if (subs.isEmpty() && !YtAccount.loggedIn()) { bind(St("📺 اشتراكاتي", 1, ArrayList(), null, null, null, null, null, "")); status("مفيش اشتراكات لسه.\nسجّل الدخول بحساب جوجل من تبويب «أنت» وهتتجاب اشتراكاتك لوحدها،\nأو افتح قناة من أي فيديو (ضغطة طويلة ← 📺 افتح القناة) واضغط 🔔 اشترك.\n\nأو استورد ملف اشتراكات (دوس هنا)") { onImport() } }
                else openSubs(false)
            }
            2, 3 -> showLocal(i, false)
            4 -> openYou()
        }
    }

    // ───────── (v348) شريط يوتيوب السفلي + الشورتس ─────────
    private fun navTap(i: Int) { when (i) { 0 -> openTab(0); 1 -> openTab(1); else -> openTab(4) } }   // (v351) الشورتس اتشالت
    private fun paintNav() {
        for (i in navIcons.indices) {
            val on = i == navSel
            val ic = navIcons[i]
            ic.color = YtSkin.text; ic.cut = YtSkin.bg
            if (ic.filled != on) {
                ic.filled = on
                if (on) {
                    ic.animate().cancel(); ic.scaleX = 0.82f; ic.scaleY = 0.82f
                    ic.animate().scaleX(1f).scaleY(1f).setDuration(Skins.dur(220L)).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
                }
            }
            navLabs[i].setTextColor(if (on) YtSkin.text else YtSkin.sub)
            navLabs[i].typeface = android.graphics.Typeface.create("sans-serif-medium", if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }
    // ───────── (v348) «أنت»: سجل الجهاز + المحفوظات + التحميلات + حساب جوجل في صفحة واحدة ─────────
    private fun yrow(title: String, sub: String, key: String) = NpItem("act", title, sub, "", "", -1L, -1L, "", 2, "yta:$key")
    private fun openYou() {
        val rows = ArrayList<NpItem>()
        rows.add(yrow("🕘 سجل المشاهدة", "اللي فتحته من يوتيوب على الجهاز (" + YtStore.read(YtStore.HIST).size + ")", "!hist"))
        rows.add(yrow("🕘 السجل العام", "فيديوهات وصوتيات من كل المصادر — بالمسح", "!pub"))
        rows.add(yrow("🔖 المحفوظات", "(" + YtStore.read(YtStore.SAVED).size + ")", "!saved"))
        rows.add(yrow("📥 التحميلات", "الفيديوهات والصوتيات اللي نزّلتها من يوتيوب", "!dl"))
        if (YtAccount.loggedIn()) rows.addAll(YtAccount.menuItems())
        else rows.add(yrow("🔐 سجّل الدخول بحساب جوجل", "اشتراكاتك وسجلك وقوايمك وفيديوهات الإعجاب", "!login"))
        rows.add(yrow("⚙ إعدادات يوتيوب", "الجودة · حفظ السجل · المسح", "!set"))
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
        load("🔍 $q", -1, null, null, q, filter, true, false) { val r = YtNp.searchFirst(q, filter); Pair(r, { p: Page -> YtNp.searchMore(q, filter, p) }) }
    }

    private fun open(it: NpItem) {
        if (it.kind == 2 && it.url.startsWith("yta:")) { openAcc(it); return }
        when (it.kind) {
            0 -> play(it, false)   // (v337) دوسة على الفيديو = يتشغّل على طول (التفاصيل من الضغطة الطويلة ← ℹ)
            1 -> openChannel(it.channelUrl, it.title)
            2 -> load("📃 " + it.title, -1, null, null, null, "", true, false) { YtNp.playlist(it.url) }
        }
    }

    private fun openChannel(url: String, name: String) {
        if (url.isBlank()) { Notice.show(act, ("القناة دي مالهاش لينك").toString(), 2500L); return }
        load(name, -1, url, name, null, "", true, true) { val c = YtNp.channel(url); Pair(c.first, c.more) }
    }

    // ───────── (v346) حساب جوجل ─────────
    private fun startLogin() {
        YtAccount.login(act, ui, th) { ok ->
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (ok) { Notice.show(act, "✅ اتسجّل الدخول", 2200L); syncNow(); openTab(4) }
                else Notice.show(act, "اتقفلت صفحة الدخول من غير ما يتم", 2200L)
            }
        }
    }
    private fun syncNow() {
        Notice.show(act, "🔄 بجيب اشتراكاتك من يوتيوب…", 2000L)
        Thread {
            try {
                val n = YtAccount.syncSubs()
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    Notice.show(act, "✅ اتضاف $n قناة جديدة (الإجمالي " + YtStore.read(YtStore.SUBS).size + ")", 3500L)
                    when (cur?.tab) { 1 -> openSubs(false); 4 -> openTab(4) }
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
            "!pub" -> onPubHistory()
            "!saved" -> showLocal(3, true)
            "!dl" -> openDownloads()
            "!login" -> startLogin()
            "!set" -> settings()
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
            var r = YtRss.feed(subsNow, force, !force)   // (v354) أول عرض فوري من الكاش لو موجود
            if (!force && r.items.isNotEmpty()) YtRss.refreshAsync(subsNow)   // وبنحدّث في الخلفية للزيارة الجاية
            var more: ((Page) -> YtRes)? = null
            if (r.items.isEmpty() && YtAccount.loggedIn()) {
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
    /** فتح تفاصيل فيديو من معرّفه (من إشعار مثلًا) */
    fun openVideo(id: String) {
        onPlay(id, "", false)   // (v337) من الإشعار: تشغيل مباشر من غير صفحة التفاصيل
    }

    // ───────── تحميل وعرض ─────────
    private fun load(title: String, tab: Int, chUrl: String?, chName: String?, q: String?, filter: String, push: Boolean, showChan: Boolean,
                     job: () -> Pair<YtRes, ((Page) -> YtRes)?>) {
        val my = ++gen
        val prev = cur; val prevPos = lv.firstVisiblePosition
        status("⏳ …", null); adapter.clear(); chanRow.visibility = View.GONE; filterRow.visibility = View.GONE; detailBox.visibility = View.GONE; subsRow.visibility = View.GONE
        retry = { load(title, tab, chUrl, chName, q, filter, push, showChan, job) }
        Thread {
            try {
                YtNp.init()
                val (r, more) = job()
                act.runOnUiThread {
                    if (my != gen || act.isFinishing || act.isDestroyed) return@runOnUiThread
                    if (push && prev != null) { prev.pos = prevPos; stack.add(prev) }
                    bind(St(title, tab, ArrayList(r.items), r.next, more, chUrl, chName, q, filter))
                    if (r.items.isEmpty()) status("مفيش نتايج\n\n(دوس هنا لإعادة المحاولة)", retry)
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

    private fun bind(st: St) {
        cur = st; statusTv.visibility = View.GONE; spin(false)
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
        if (st.tab >= 0) paintTabs(st.tab) else if (det) paintTabs(-1)
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
    private fun playCur(tr: Boolean) { val st = cur ?: return; if (st.vid == null) return; play(detItem(st), tr) }
    /** عنصر من التحميلات url بتاعه مكان الملف — بنرجّعه لرابط الفيديو قبل التشغيل/السجل */
    private fun asWatch(it: NpItem): NpItem = if (it.url.startsWith("http")) it else NpItem(it.id, it.title, it.channel, it.channelUrl, it.thumb, it.dur, it.count, it.age, 0, YtNp.watchUrl(it.id))
    /** (v340) لو الفيديو اتفتح من قايمة تشغيل (أو أي قايمة فيديوهات) نسجّلها عشان لوحة «التالي» تعرضها */
    private fun markCtx() { val st = cur; if (st != null && st.vid == null && st.title.startsWith("📃")) YtNext.setContext(st.list(), st.title.removePrefix("📃").trim()) else YtNext.clearContext() }
    private fun play(it0: NpItem, tr: Boolean) {
        markCtx()
        val it = asWatch(it0)
        if (YtStore.histOn()) YtStore.addFront(YtStore.HIST, it, 200)
        YtStore.markApp(it.id)
        onPlay(it.id, it.title, tr)
    }
    private fun playAudio(it0: NpItem) {
        markCtx()
        val it = asWatch(it0)
        if (YtStore.histOn()) YtStore.addFront(YtStore.HIST, it, 200)
        YtStore.markApp(it.id)
        onAudio(it)
    }
    private fun dlMenu(anchor: View, it: NpItem) {
        val m = PopupMenu(act, anchor)
        m.menu.add(0, 1, 0, "🎧 تحميل صوت بس"); m.menu.add(0, 2, 1, "🎬 تحميل فيديو (" + (Cfg.int("yt_maxh", 0).let { h -> if (h <= 0) "أعلى جودة" else h.toString() + "p" }) + ")")
        m.setOnMenuItemClickListener { mi -> onDownload(asWatch(it), mi.itemId == 1); true }
        m.show()
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
            "👤 حساب جوجل: " + (if (YtAccount.loggedIn()) "مسجّل الدخول" else "مش مسجّل"))
        GAlert(act).setTitle("⚙ إعدادات يوتيوب").setItems(items) { _, w ->
            when (w) {
                0 -> qualityDialog()
                1 -> { val on = !YtStore.histOn(); Cfg.put("ytp_hist_on", if (on) "1" else "0"); Notice.show(act, (if (on) "🕘 السجل شغّال" else "🕘 السجل اتقفل (اللي اتسجّل قبل كده لسه موجود)").toString(), 2400L) }
                2 -> openDownloads()
                3 -> { YtStore.write(YtStore.HIST, emptyList()); Notice.show(act, ("🗑 السجل اتمسح").toString(), 1800L); if (cur?.tab == 2) showLocal(2, false) }
                4 -> { YtStore.write(YtStore.DL, emptyList()); Notice.show(act, ("🗑 سجل التحميلات اتمسح").toString(), 1800L) }
                5 -> openTab(4)
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
        load("📥 التحميلات", -1, null, null, null, "", true, false) { Pair(YtRes(YtStore.read(YtStore.DL), null), null) }
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
                        adapter.notifyDataSetChanged()
                    }
                    loadingMore = false
                }
            } catch (_: Throwable) { act.runOnUiThread { if (inC) st.cn = null else st.next = null; loadingMore = false } }
        }.start()
    }

    private fun status(t: String, onTap: (() -> Unit)? = null) {
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

    // ───────── قايمة الضغطة الطويلة ─────────
    private fun menu(anchor: View, it: NpItem) {
        val m = PopupMenu(act, anchor)
        if (it.kind == 0) {
            m.menu.add(0, 1, 0, "▶ شغّل"); m.menu.add(0, 2, 1, "🌐 شغّل وترجم"); m.menu.add(0, 10, 7, "ℹ التفاصيل"); m.menu.add(0, 11, 8, "🎧 صوت بس (مشغّل الموسيقى)"); m.menu.add(0, 12, 9, "⬇ تحميل صوت"); m.menu.add(0, 13, 10, "⬇ تحميل فيديو")
            m.menu.add(0, 3, 2, if (YtStore.contains(YtStore.SAVED, it)) "❌ شيل من المحفوظات" else "🔖 احفظ")
            if (it.channelUrl.isNotBlank()) m.menu.add(0, 4, 3, "📺 افتح القناة")
            m.menu.add(0, 5, 4, "📋 انسخ اللينك"); m.menu.add(0, 6, 5, "🌐 افتحه في المتصفح")
            if (cur?.tab == 2) m.menu.add(0, 7, 6, "🗑 شيل من السجل")
        } else {
            m.menu.add(0, 8, 0, "فتح"); m.menu.add(0, 5, 1, "📋 انسخ اللينك")
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
                4 -> openChannel(it.channelUrl, it.channel)
                5 -> copy(if (it.kind == 0) YtNp.watchUrl(it.id) else it.url)
                6 -> onBrowser(YtNp.watchUrl(it.id))
                7 -> { YtStore.remove(YtStore.HIST, it); showLocal(2, false) }
                8 -> open(it)
                9 -> { val on = YtStore.toggle(YtStore.SUBS, it); Notice.show(act, (if (on) "✅ اشتركت" else "اتشال الاشتراك").toString(), 1800L) }
            }
            true
        }
        m.show()
    }

    // ───────── رسم ─────────
    /** sel: 0 رئيسية · 1 اشتراكات · 2/3/4 = «أنت» · غير كده (بحث/قناة) بيسيب التحديد زي ما هو */
    private fun paintTabs(sel: Int) { if (sel in 0..1) navSel = sel else if (sel in 2..4) navSel = 2; paintNav() }
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

    private class Hold(val thumb: ImageView, val dur: TextView, val title: TextView, val meta: TextView, val box: View, val col: View, val btns: View, val aud: TextView, val dl: TextView)

    private class Cell(val root: LinearLayout, val thumb: ImageView, val dur: TextView, val title: TextView, val meta: TextView)

    private class Card(val root: LinearLayout, val thumb: ImageView, val dur: TextView, val prog: View, val av: TextView, val avImg: ImageView,
                        val title: TextView, val meta: TextView, val more: View)

    /** (v397) كارت فيديو زي يوتيوب: صورة بعرض الشاشة 16:9 (مدة + شريط استكمال) + أفاتار القناة + العنوان + «القناة · المشاهدات · الوقت» + ⋮ */
    private fun makeCard(): Card {
        val sw = act.resources.displayMetrics.widthPixels
        val medium = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        val dur = TextView(act).apply {
            textSize = 12f; setTextColor(Color.WHITE); includeFontPadding = false; typeface = medium
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
        }
        val prog = View(act).apply { setBackgroundColor(YtSkin.red); visibility = View.GONE }
        val box = FrameLayout(act)
        box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        box.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        box.addView(prog, FrameLayout.LayoutParams(0, ui.dp(3), Gravity.BOTTOM or Gravity.START))
        val av = TextView(act).apply { gravity = Gravity.CENTER; textSize = 15f; setTextColor(Color.WHITE); includeFontPadding = false; typeface = medium }
        val avImg = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        val avBox = FrameLayout(act)
        avBox.addView(av, FrameLayout.LayoutParams(-1, -1)); avBox.addView(avImg, FrameLayout.LayoutParams(-1, -1))
        val title = TextView(act).apply { textSize = 14f; setTextColor(YtSkin.text); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; typeface = medium }
        val meta = TextView(act).apply { textSize = 12f; setTextColor(YtSkin.sub); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title, LinearLayout.LayoutParams(-1, -2))
        col.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        val more = YtIcon(act, "more").apply { foreground = YtSkin.ripple(true); glyph = 0.8f; contentDescription = "المزيد" }
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
        return Card(root, thumb, dur, prog, av, avImg, title, meta, more)
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
                c.title.alpha = if (vi.kind == 0 && recents().containsKey("yt:" + vi.id)) 0.6f else 1f
                c.meta.text = listOf(vi.channel, vi.age).filter { x -> x.isNotBlank() }.joinToString(" · ")
                val d = if (vi.kind == 0) fmtDur(vi.dur) else ""
                c.dur.text = d; c.dur.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
                try { if (vi.thumb.isNotBlank()) Glide.with(act).load(vi.thumb).centerCrop().override(ui.dp(300), ui.dp(170)).into(c.thumb) else Glide.with(act).clear(c.thumb) } catch (_: Throwable) {}
                c.root.setOnClickListener { if (vi.kind != 3) open(vi) }
                c.root.setOnLongClickListener { x -> if (vi.id != "act") menu(x, vi); true }
            }
            return row
        }
        private fun cardView(p: Int, cv: View?): View {
            val c: Card
            val v: View
            if (cv == null) { c = makeCard(); v = c.root; v.tag = c } else { v = cv; c = cv.tag as Card }
            val sw = act.resources.displayMetrics.widthPixels
            val vi = items[p]
            v.animate().cancel()
            if (animRows && p < 6) {
                v.alpha = 0f; v.translationY = ui.dp(18).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(p * 45L).setDuration(Skins.dur(280L)).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
            } else { v.alpha = 1f; v.translationY = 0f }
            c.title.text = vi.title
            c.meta.text = listOf(vi.channel, if (vi.count >= 0) fmtN(vi.count) + " مشاهدة" else "", vi.age).filter { x -> x.isNotBlank() }.joinToString(" · ")
            val d = fmtDur(vi.dur)
            c.dur.text = d; c.dur.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            val rec = recents()["yt:" + vi.id] as? Recent
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
                // (v337) زرارين على الجنب: 🎧 صوت بس (مشغّل الموسيقى) · ⬇ تحميل
                fun sb(t: String) = ui.text(t, 17f, th.primary, true).apply { gravity = Gravity.CENTER; background = GradientDrawable().apply { setColor(th.surface); cornerRadius = ui.dp(18).toFloat() } }
                val aud = sb("🎧"); val dl = sb("⬇")
                val btns = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
                btns.addView(aud, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)))
                btns.addView(dl, LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { topMargin = ui.dp(8) })
                row.addView(btns, LinearLayout.LayoutParams(ui.dp(40), ui.dp(85)))
                hd = Hold(th0, dur, title, meta, box, col, btns, aud, dl); row.tag = hd; v = row
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
            if (vid) { val item = it; hd.aud.setOnClickListener { _ -> playAudio(item) }; hd.dl.setOnClickListener { x -> dlMenu(x, item) } }
            hd.title.maxLines = if (cm) 10 else 2
            (hd.col.layoutParams as LinearLayout.LayoutParams).height = if (cm) -2 else ui.dp(85)
            val seen = it.kind == 0 && recents().containsKey("yt:" + it.id)
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
