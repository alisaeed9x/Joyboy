package com.tttt.subtitler

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * (v461/v462) شاشة التلفزيون على تقسيمة يوتيوب TV الرسمية:
 * شريط علوي (الشعار · بحث · ساعة) + ريل تنقّل جانبي + محتوى بصفوف أفقية
 * (كمّل مشاهدة · المفضلة · أحدث الفيديوهات) بكروت فيديو 16:9 بتكبر مع التركيز.
 * (v476) بقى لانشر كامل: بانر رئيسي + وصول سريع + تطبيقات الجهاز + زر Home.
 * بحث موحّد (مكتبة + أخيرة + يوتيوب) بصوت الريموت أو الكتابة من الموبايل (TypeServer)،
 * قفل PIN برقم سري على أقسام، تخصيص إظهار الصفوف، دفء ليلي، ووضع سكون.
 */
class TvActivity : Activity() {
    // (v507) ريزوليوشن ديناميك لوضع التلفزيون
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(TvScale.wrap(newBase)) }
    private lateinit var ui: Ui
    private lateinit var th: Theme
    private lateinit var content: LinearLayout
    private lateinit var saver: FrameLayout
    private lateinit var saverClock: android.widget.TextView
    private lateinit var saverTemp: android.widget.TextView
    private lateinit var saverPray: LinearLayout       // (v525) مواقيت الصلاة فوق ساعة شاشة السكون
    private var saverCdTv: android.widget.TextView? = null
    private var saverNextAt = 0L
    private lateinit var saverBg: ImageView
    private lateinit var clockTv: android.widget.TextView
    private lateinit var warmView: View
    private val h = Handler(Looper.getMainLooper())
    private var idleSec = 0
    private var favs: LinkedHashSet<String> = LinkedHashSet()
    private var lastScan: List<VideoItem> = emptyList()
    private var firstRail: View? = null
    private var compactUi = false          // (v503) شاشة ضيقة (موبايل/تابلت صغير): القايمة بالجنب بدل فوق
    private var railBox: View? = null
    private var lastScrollTarget = -1
    // (v496) شكل Google TV: خلفية متدرّجة + وهج بلون العنصر المتركّز + كبسولات علوية
    private lateinit var scrollV: ScrollView
    private lateinit var glowDrawable: GradientDrawable
    private var glowColor = 0xFF3A2C86.toInt()
    private var glowAnim: ValueAnimator? = null
    private val glowMap = java.util.WeakHashMap<View, Int>()
    private var searchBox: LinearLayout? = null
    private var tabsBoxV: View? = null
    private var tabActions: List<() -> Unit> = emptyList()
    private var tabAutoBlock = -1   // (v513) التاب اللي اتفتح بالتركيز: مايتفتحش تاني لما نرجع عليه بـ Back (لحد ما التركيز يروح لتاب تاني)
    private var tcV: View? = null
    private var searchExtras: List<View> = emptyList()
    private var searchExpanded = false
    private val tabViews = ArrayList<TvOwnLinear>()
    private var curTab = 0
    private val fg: Int get() = if (Theme.lum(th.bg) < 0.2f) Color.WHITE else th.text
    // (v495) البحث بقى جوه الشريط العلوي: النتايج بتظهر مكان صفوف الرئيسية وانت بتكتب (مفيش صفحة بحث منفصلة)
    private var searchEt: EditText? = null
    private var inSearch = false
    private var muteSearch = false
    private val searchRun = Runnable { searchEt?.text?.toString()?.let { runSearch(it) } }

    private fun favLoad() { favs = LinkedHashSet(Cfg.str("tv_favs", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }) }
    private fun favSave() { Cfg.put("tv_favs", favs.joinToString(",")) }
    private fun favToggle(id: String, title: String) {
        if (!favs.remove(id)) { favs.add(id); Notice.show(this, "⭐ اتضافت للمفضلة: $title", 1600L) } else Notice.show(this, "⭐ اتشالت من المفضلة", 1400L)
        favSave(); if (storageKind != null) foldersHost?.let { fillStorageView(it) } else if (inSearch) runSearch(searchEt?.text?.toString() ?: "") else showHome()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); favLoad()
        DeviceUi.applyOrientation(this)   // (v494) وضع التلفزيون على موبايل = لاندسكيب تلقائي
        // (v491) الثيم الأساسي على التلفزيون = أسود (أموليد) — مرة واحدة بس، وتقدر تغيّره من الإعدادات بعدها
        if (DeviceUi.isTv(this) && Cfg.str("tv_black_v491", "0") != "1") { Cfg.put("theme", "amoled_w"); Cfg.put("tv_black_v491", "1") }
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        TypeServer.start(); TvWatchNext.sync(this); TvLink.start(this)   // (v518) ريموت الموبايل
        val rootF = FrameLayout(this).apply {
            if (Theme.lum(th.bg) < 0.2f) background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0A0F22.toInt(), 0xFF150E27.toInt())) else setBackgroundColor(th.bg)
        }
        wallView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; isFocusable = false; isClickable = false; visibility = View.GONE }
        wallScrim = View(this).apply { setBackgroundColor(TvWall.scrimColor()); isFocusable = false; isClickable = false; visibility = View.GONE }
        rootF.addView(wallView, FrameLayout.LayoutParams(-1, -1))
        rootF.addView(wallScrim, FrameLayout.LayoutParams(-1, -1))
        glowDrawable = GradientDrawable().apply { gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = ui.dp(640).toFloat(); setGradientCenter(0.5f, 0.55f); colors = intArrayOf(0x00000000, 0x00000000) }
        rootF.addView(View(this).apply { background = glowDrawable; isFocusable = false; isClickable = false }, FrameLayout.LayoutParams(-1, -1))
        compactUi = resources.configuration.screenWidthDp < 720
        val rootCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        rootCol.addView(topBar(), LinearLayout.LayoutParams(-1, -2))
        val scroll = object : ScrollView(this) {
            // (v496) اللانشر بيحرّك الصفحة بنفسه بسلاسة (alignScroll) بدل القفز الافتراضي
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0
        }.apply { isVerticalScrollBarEnabled = false }
        scrollV = scroll
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (compactUi) setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(80)) else setPadding(ui.dp(34), ui.dp(2), ui.dp(34), ui.dp(80))
            clipChildren = false
        }
        scroll.addView(content)
        rootCol.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val rb0 = railBox
        val body: View = if (compactUi && rb0 != null) LinearLayout(this).apply {   // (v530) رجعت لشكل v525: كبسولة تابات فوق على الشاشة الواسعة، والريل الجانبي على الضيقة بس
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(ScrollView(this@TvActivity).apply { isVerticalScrollBarEnabled = false; addView(rb0) }, LinearLayout.LayoutParams(ui.dp(if (compactUi) 62 else 76), -1))
            addView(rootCol, LinearLayout.LayoutParams(0, -1, 1f))
        } else rootCol
        rootF.addView(body, FrameLayout.LayoutParams(-1, -1))
        rootF.viewTreeObserver.addOnGlobalFocusChangeListener { _, nw -> if (nw != null) onFocusMoved(nw) }
        warmView = View(this).apply { setBackgroundColor(0x2EFF8C1A); isClickable = false; isFocusable = false; visibility = if (Cfg.str("tv_warm", "0") == "1") View.VISIBLE else View.GONE }
        rootF.addView(warmView, FrameLayout.LayoutParams(-1, -1))
        saver = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        // (v514) شاشة التوقف: خلفية اللانشر (لو متحطة) + كارت تظليل 60% في النص فيه ساعة إنجليزي ديجيتال 12 ساعة كبيرة + درجة الحرارة سيليزيوس تحتها
        saverBg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        saver.addView(saverBg, FrameLayout.LayoutParams(-1, -1))
        saverClock = ui.text("", 100f, Color.WHITE, true).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR; includeFontPadding = false }
        saverTemp = ui.text("", 44f, Color.WHITE, true).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR; setPadding(0, ui.dp(6), 0, 0) }
        val saverCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(ui.dp(64), ui.dp(36), ui.dp(64), ui.dp(32))
            background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = ui.dp(36).toFloat() }
        }
        saverPray = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; setPadding(0, 0, 0, ui.dp(14)) }
        saverCol.addView(saverPray, LinearLayout.LayoutParams(-2, -2))
        saverCol.addView(saverClock, LinearLayout.LayoutParams(-2, -2))
        saverCol.addView(saverTemp, LinearLayout.LayoutParams(-2, -2))
        saverCol.addView(ui.text("📺 Subtitler — دوس أي زرار عشان تكمل", 15f, 0xFFB8BDC9.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(14), 0, 0) }, LinearLayout.LayoutParams(-2, -2))
        saver.addView(saverCol, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        rootF.addView(saver, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootF)
        TvFocus.attach(this, th.primary)   // (v490) حلقة تركيز واضحة على كل زرار/كارت في اللانشر
        showHome()
        applyWall()   // (v506)
        refreshTopPrayer()   // (v527)
        h.post(tick)
        handleShow(intent)   // (v516) راجع من يوتيوب (قشرة اللانشر) على «تطبيقات» أو «الرئيسية»
        h.postDelayed({ if (!isDestroyed && !isFinishing) { if (!VideoScan.hasPermission(this)) askVideoPermission() else askDefaultHome() } }, 1500L)   // (v485) (v500) الصلاحية الأول
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = SimpleDateFormat(if (Cfg.str("tv_clock24", "0") == "1") "HH:mm" else "hh:mm a", Locale.getDefault()).format(Date())   // (v484) نظام 12 ساعة بتوقيت الجهاز (v516: أو 24 من المظهر)
            if (::clockTv.isInitialized) clockTv.text = t
            if (saver.visibility == View.VISIBLE) { saverClock.text = SimpleDateFormat("hh:mm a", Locale.US).format(Date()); saverPrayTick() } else { idleSec++; if (idleSec >= saverLimit()) showSaver() }
            prayerTick(); topPrayTick()
            h.postDelayed(this, 1000)
        }
    }

    private fun showSaver() {
        try {
            val d = if (::wallView.isInitialized && wallView.visibility == View.VISIBLE) wallView.drawable else null
            saverBg.setImageDrawable(d); saverBg.visibility = if (d != null) View.VISIBLE else View.GONE
            saverClock.text = SimpleDateFormat("hh:mm a", Locale.US).format(Date())
            val c = Cfg.str("tv_temp_c", ""); saverTemp.text = if (c.isNotBlank()) "$c°C" else ""
            saverTemp.visibility = if (c.isNotBlank()) View.VISIBLE else View.GONE
            if (System.currentTimeMillis() - (Cfg.str("tv_temp_ts", "0").toLongOrNull() ?: 0L) > 30 * 60_000L) fetchTemp()
            loadPrayer(false) { res -> fillSaverPrayer(res) }   // (v525) المواقيت من الكاش أو النت
        } catch (e: Throwable) { LogStore.err("TvHome:saver", e) }
        saver.visibility = View.VISIBLE; idleSec = 0
    }
    /** (v514) درجة الحرارة بالسيليزيوس: موقع تقريبي من IP (ipwho.is) ثم Open-Meteo — بدون مفاتيح، ومتخزنة نص ساعة */
    private fun fetchTemp() {
        Thread {
            try {
                fun get(u: String): String { val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection; c.connectTimeout = 6000; c.readTimeout = 6000; return c.inputStream.bufferedReader().use { it.readText() } }
                val loc = org.json.JSONObject(get("https://ipwho.is/"))
                val lat = loc.getDouble("latitude"); val lon = loc.getDouble("longitude")
                val w = org.json.JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m&temperature_unit=celsius"))
                val t = Math.round(w.getJSONObject("current").getDouble("temperature_2m")).toInt().toString()
                Cfg.put("tv_temp_c", t); Cfg.put("tv_temp_ts", System.currentTimeMillis().toString())
                runOnUiThread { try { if (!isDestroyed) { saverTemp.text = "$t°C"; saverTemp.visibility = View.VISIBLE } } catch (_: Throwable) { } }
            } catch (e: Throwable) { LogStore.err("TvHome:temp", e) }
        }.start()
    }
    private fun wake() { idleSec = 0; if (::saver.isInitialized && saver.visibility == View.VISIBLE) saver.visibility = View.GONE }
    override fun onUserInteraction() { super.onUserInteraction(); wake() }
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        // (v500) شاشة التوقف: أول ضغطة بتصحّيها بس ومش بتتنفّذ (كانت OK بتفتح التطبيق اللي تحتها)
        val wasSaver = ::saver.isInitialized && saver.visibility == View.VISIBLE
        wake(); TvKill.watch(this, ev); muteHold(ev)
        if (wasSaver && ev.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE && ev.keyCode != KeyEvent.KEYCODE_VOLUME_UP && ev.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && ev.keyCode != KeyEvent.KEYCODE_0) return true
        // (v498) زرار المساعد/البحث في الريموت (لو وصل للبرنامج) = بحث صوتي
        if ((ev.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || ev.keyCode == KeyEvent.KEYCODE_SEARCH) && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) { voiceToSearch(); return true }
        if (shortcutKey(ev)) return true   // (v505) اختصارات الريموت
        return super.dispatchKeyEvent(ev)
    }

    // (v498) دوسة طويلة (5 ثواني) على زرار كتم الصوت = بحث صوتي. مش بنبلع المفتاح؛ الكتم بيشتغل عادي
    private var muteRun: Runnable? = null
    private fun muteHold(ev: KeyEvent) {
        if (ev.keyCode != KeyEvent.KEYCODE_VOLUME_MUTE) return
        if (ev.action == KeyEvent.ACTION_UP) { muteRun?.let { h.removeCallbacks(it) }; muteRun = null; return }
        if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return
        muteRun?.let { h.removeCallbacks(it) }
        val r = Runnable { muteRun = null; if (!isFinishing && !isDestroyed) voiceToSearch() }
        muteRun = r; h.postDelayed(r, 5000L)
    }
    private fun voiceToSearch() {
        val et = searchEt ?: return
        startVoice { q -> showHome(false); et.setText(q); et.setSelection(et.text.length) }
    }   // (v486) 0 لمدة 5 ثواني = إلغاء تثبيت
    override fun onDestroy() {
        h.removeCallbacks(wallLoop); h.removeCallbacks(tick); muteRun?.let { h.removeCallbacks(it) }
        try { sr?.destroy() } catch (_: Throwable) {}; sr = null
        super.onDestroy()
    }

    // (v500) رجوع من المشغّل/المتصفح: العدّاد يبدأ من الصفر، و«آخر مشاهدة» تتحدّث لو اتغيّرت (كانت بتفضل قديمة)
    private var histHost: LinearLayout? = null
    private var recSig = ""
    private var resumedOnce = false
    override fun onPause() { h.removeCallbacks(wallLoop); super.onPause() }   // (v527) مفيش تنزيل خلفيات وانت في تطبيق تاني
    private fun recentsSig(): String = try { recents().take(40).joinToString("|") { it.uri + ":" + it.posSec.toInt() + ":" + it.ts } } catch (_: Throwable) { "" }
    override fun onResume() {
        super.onResume(); idleSec = 0
        TvSetup.onResume(this)   // (v536) أول مرة: شاشة الأذونات
        if (resumedOnce && wallMode() != "off") { h.removeCallbacks(wallLoop); h.postDelayed(wallLoop, 1500L) }   // (v527) كمّل دورة الخلفيات بعد الرجوع
        if (resumedOnce) refreshTopPrayer()
        try { content.animate().cancel(); content.alpha = 1f; content.scaleX = 1f; content.scaleY = 1f; content.translationY = 0f } catch (_: Throwable) { }   // (v513) رجوع من صفحة اتفتحت بالتركيز
        val sig = recentsSig()
        if (resumedOnce && sig != recSig && !inSearch && storageKind == null && curTab == 0) {
            val hh = histHost
            if (hh != null && Cfg.str("tv_rows_hide2", "").split(",").map { it.trim() }.none { it == "hist" }) {
                val hadFocus = try { currentFocus?.let { isInside(it, hh) } == true } catch (_: Throwable) { false }
                fillHistory(hh)
                if (hadFocus) hh.post { try { (hh.getChildAt(1) as? ViewGroup)?.let { r -> firstFocusable(r)?.requestFocus() } } catch (_: Throwable) {} }
            }
        }
        recSig = sig; resumedOnce = true
    }
    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.isShown) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    // (v500) صلاحية الفيديوهات: على التلفزيون ما كانش في أي مكان بيطلبها (الرسالة بس بتقول «اديها»)
    private var askedPerm = false
    private fun askVideoPermission() {
        if (VideoScan.hasPermission(this)) return
        try { requestPermissions(arrayOf(VideoScan.permission()), 11); askedPerm = true } catch (e: Throwable) { LogStore.err("Tv:perm", e) }
    }
    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 11 && VideoScan.hasPermission(this)) { Notice.show(this, "✅ تمام — بقرا الفيديوهات", 1800L); showHome(false); TvSetup.refresh(this) }
        else if (rc == 11) { Notice.show(this, "⚠ من غير الصلاحية مش هقدر أعرض الفيديوهات", 3000L); TvSetup.refresh(this) }
        else if (rc == TvSetup.RC_NOTIF || rc == TvSetup.RC_MIC || rc == TvSetup.RC_LOC) TvSetup.refresh(this)   // (v536)
        else if (rc == 92) { if (res.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) { Cfg.put("tv_loc", ""); if (prayerCd != null || curTab == -1) showPrayer() } }
        else if (rc == TvWifi.RC_LOC) { TvWifi.show(this) }
        else if (rc == 91 && res.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) { val cb = pendingVoice; pendingVoice = null; if (cb != null) startVoiceInApp(cb) }
        else if (rc == 91) { pendingVoice = null; Notice.show(this, "⚠ البحث الصوتي محتاج صلاحية الميكروفون", 3000L) }
    }

    /** (v476) لانشر: زر Home وإحنا شغّالين = ارجع للرئيسية. Back عمره ما بيقفل اللانشر (بس بيرجّع للرئيسية). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) { wake(); showHome() } else handleShow(intent)
    }
    private fun handleShow(i: Intent?) {
        val w = i?.getStringExtra("tv_show") ?: return
        i.removeExtra("tv_show")
        when (w) { "apps" -> showApps(); "home" -> showHome(); "youtube" -> openMain("youtube"); "browser" -> openBrowser(); "sd" -> showStorage("sd"); "int" -> showStorage("int"); "settings" -> guardPin { openMain("settings") }; "mic" -> voiceToSearch(); "videos" -> showVideosPage()
            "search" -> { val q = i.getStringExtra("tv_q") ?: ""; i.removeExtra("tv_q"); wake(); if (q.isNotBlank()) h.post { showSearch(q) } } }   // (v522) اختصارات الريموت · (v533) بحث من الموبايل
    }
    private fun saverLimit(): Int { val m = Cfg.str("tv_saver_min", "3").toIntOrNull() ?: 3; return if (m <= 0) Int.MAX_VALUE else m * 60 }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        wake()
        if (storageKind != null && openFolder != null && foldersHost != null) { closeFolder(); return }   // (v490) من جوه فولدر: ارجع لقايمة الفولدرات
        showHome(true, true)   // (v493) من جوه الفلاشة/الذاكرة: ارجع للرئيسية
    }

    // ===== (v496) الشريط العلوي على نمط Google TV: [الشعار | الساعة] ... كبسولة أزرار · كبسولة تابات · كبسولة بحث =====
    private fun pillBg(color: Int, rDp: Int = 28, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = ui.dp(rDp).toFloat(); if (strokeDp > 0) setStroke(ui.dp(strokeDp), strokeColor) }
    private fun ovalBg(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            if (compactUi) setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(4)) else setPadding(ui.dp(32), ui.dp(18), ui.dp(32), ui.dp(8))
            clipChildren = false
        }
        // 1) كبسولة البحث (بتتوسّع لما التركيز يدخلها، ومعاها 🎤 و📱)
        val sb = TvOwnLinear(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(16), ui.dp(2), ui.dp(8), ui.dp(2)); background = pillBg(0x99000000.toInt(), 28, 0x33FFFFFF, 1)
        }
        searchBox = sb
        sb.addView(ui.text("🔍", 17f, fg))
        val et = TvOwnEdit(this).apply {
            setSingleLine(); textSize = 15f; setTextColor(fg); setHintTextColor(0xAAFFFFFF.toInt()); hint = "بحث"
            setBackgroundColor(Color.TRANSPARENT); setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, a, ev ->   // (v531) لصقت لينك في البحث وداست بحث/Enter ← يتفتح
                val isGo = a == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || a == android.view.inputmethod.EditorInfo.IME_ACTION_GO || a == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                    (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
                val u = if (isGo) searchUrl(v.text?.toString() ?: "") else null
                if (u != null) { openSearchLink(u); true } else false
            }
            setOnFocusChangeListener { _, f -> sb.background = if (f) pillBg(0xCC000000.toInt(), 28, Color.WHITE, 2) else pillBg(0x99000000.toInt(), 28, 0x33FFFFFF, 1) }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) { onSearchText(e?.toString() ?: "") }
            })
        }
        searchEt = et
        sb.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = roundBtn("🎤", "بحث صوتي", 34) { startVoice { q -> et.setText(q); et.setSelection(et.text.length) } }
        val ph = roundBtn("📱", "اكتب من الموبايل", 34) { phoneTypeDialog { q -> et.setText(q); et.setSelection(et.text.length) } }
        mic.visibility = View.GONE; ph.visibility = View.GONE
        searchExtras = listOf(mic, ph); searchExpanded = false
        sb.addView(mic); sb.addView(ph)
        bar.addView(sb, LinearLayout.LayoutParams(if (compactUi) 0 else ui.dp(170), -2).apply { marginEnd = ui.dp(14); if (compactUi) weight = 1f })
        // 2) كبسولة التابات (الصفحة الحالية متظلّلة، والمتركّز أبيض)
        val tabsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)); background = pillBg(0x99000000.toInt(), 30, 0x33FFFFFF, 1)
        }
        tabsBoxV = tabsBox
        tabActions = emptyList()
        tabViews.clear()
        val defs = listOf<Pair<String, () -> Unit>>(
            "الصفحة الرئيسية" to { showHome() },
            "الفيديوهات" to { showVideosPage(false) },   // (v531) الترتيب: الرئيسية · الفيديوهات · التطبيقات (· يوتيوب)
            "تطبيقات" to { showApps() })   // (v538) تاب يوتيوب اتشال: يوتيوب بقى أول أيقونة جوه «تطبيقاتك» وبيتفتح بدوسة زي أي تطبيق
        tabActions = defs.map { it.second }
        defs.forEachIndexed { i, d -> val t = tabBtn(d.first, i, d.second); tabViews.add(t); if (!compactUi) tabsBox.addView(t) }   // (v530) رجوع لشكل v525
        firstRail = tabViews[0]
        setTab(0)
        if (compactUi) {
            // (v503) قايمة جنبية: أيقونات (الرئيسية · تطبيقات · يوتيوب · متصفح · فيديوهات · ملفات) + الإعدادات + المزيد
            val icons = listOf("🏠", "🎬", "📱")   // (v530) يوتيوب رجع تاب ظاهر
            val rail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8)); background = pillBg(0x99000000.toInt(), 24, 0x33FFFFFF, 1)
            }
            tabViews.forEachIndexed { i, t ->
                (t.getChildAt(0) as? TextView)?.apply { text = icons[i]; textSize = 20f }
                t.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); t.contentDescription = defs[i].first
                t.setOnFocusChangeListener { v, f -> styleTab(v as TvOwnLinear, i, f); if (f) { Notice.show(this@TvActivity, defs[i].first, 1100L); tabFocused(i) } }
                t.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) }
                rail.addView(t)
            }
            // (v527) تابات مخصّصة (تطبيق أو فولدر فيديو بعينه) + زرار ➕ للإضافة/الشيل
            sideTabs().forEach { t -> rail.addView(customTab(t)) }
            rail.addView(roundBtn("➕", "أضف تاب (تطبيق / فولدر)", 42) { sideTabsMenu() })
            // (v516) مواقيت الصلاة · Google Play (اختياري) · المظهر
            rail.addView(roundBtn("📶", "الواي فاي (دوسة = تشغيل/إيقاف · طويلة = الشبكات)", 42, { TvWifi.show(this) }) { TvWifi.quick(this) })   // (v536)
            rail.addView(roundBtn("🕌", "مواقيت الصلاة", 42) { showPrayer() })
            if (playOn()) rail.addView(roundBtn("🛍", "Google Play", 42) { openPlay() })
            rail.addView(roundBtn("🎨", "المظهر", 42) { showAppearance() })
            rail.addView(roundBtn("⚙️", "إعدادات البرنامج", 42) { guardPin { openMain("settings") } })
            rail.addView(roundBtn("⋮", "المزيد", 42) { moreMenu() })
            railBox = rail
        } else bar.addView(tabsBox, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(14) })   // (v530) شكل v525
        // 3) كبسولة الأزرار الدائرية: إعدادات البرنامج · المزيد (الإعدادات/التطبيقات/المشغّل الافتراضي/إلغاء التثبيت…)
        val rb = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)); background = pillBg(0x99000000.toInt(), 30, 0x33FFFFFF, 1)
        }
        rb.addView(roundBtn("⚙️", "إعدادات البرنامج", 42) { guardPin { openMain("settings") } })
        rb.addView(roundBtn("⋯", "المزيد", 42) { moreMenu() })
        if (!compactUi) { bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }   // (v512) أزرار الإعدادات اتنقلت لصف فوق «🎛 تخصيص» في الرئيسية (كانت بتطلع بره الشاشة)
        // 4) الشعار | الساعة على الشمال زي «Google TV | 3:10»
        val tc = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        if (!compactUi) {
            tc.addView(roundBtn("⚙️", "الإعدادات", 42) { guardPin { openMain("settings") } })   // (v550) كلمة «Subtitler» اتبدّلت بزرار الإعدادات
            tc.addView(View(this).apply { setBackgroundColor(0x66FFFFFF) }, LinearLayout.LayoutParams(ui.dp(2), ui.dp(20)).apply { setMargins(ui.dp(12), 0, ui.dp(12), 0) })
        }
        tcV = tc
        clockTv = ui.text("", 19f, fg, true).apply { setShadowLayer(8f, 0f, 2f, Color.BLACK) }
        tc.addView(clockTv)
        // (v527) الصلاة الجاية + المتبقي ليها جنب الساعة
        topPrayTv = ui.text("", if (compactUi) 13f else 16f, 0xFFFFD27A.toInt(), true).apply { setShadowLayer(8f, 0f, 2f, Color.BLACK); visibility = View.GONE; setSingleLine(); setPadding(ui.dp(14), 0, 0, 0) }
        tc.addView(topPrayTv)
        tc.addView(roundBtn("📶", "الواي فاي (دوسة = تشغيل/إيقاف · طويلة = الشبكات)", 40, { TvWifi.show(this) }) { TvWifi.quick(this) }.apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = ui.dp(12) })   // (v525) واي فاي سريع جنب الساعة
        bar.addView(tc)
        return bar
    }

    /** زرار دائري: المتركّز بيبقى أبيض ويكبر شوية، واسمه بيظهر في إشعار صغير */
    private fun roundBtn(icon: String, label: String, sizeDp: Int, onLong: (() -> Unit)? = null, onClick: () -> Unit): View {
        val tv = ui.text(icon, if (sizeDp >= 40) 19f else 15f, fg).apply { gravity = Gravity.CENTER }
        val b = TvOwnLinear(this).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true; background = ovalBg(Color.TRANSPARENT)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }   // (v536)
            setOnFocusChangeListener { v, f ->
                v.background = ovalBg(if (f) Color.WHITE else Color.TRANSPARENT)
                tv.setTextColor(if (f) Color.BLACK else fg)
                v.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(Skins.dur(150L)).setInterpolator(DecelerateInterpolator()).start()
                if (f) Notice.show(this@TvActivity, label, 1100L)
            }
        }
        b.addView(tv)
        b.layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
        b.contentDescription = label
        return b
    }

    private fun tabBtn(label: String, idx: Int, onClick: () -> Unit): TvOwnLinear {
        val b = TvOwnLinear(this).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(ui.dp(20), ui.dp(9), ui.dp(20), ui.dp(9))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, f -> styleTab(v as TvOwnLinear, idx, f); if (f) tabFocused(idx) }
        }
        b.addView(ui.text(label, 15f, fg, true).apply { gravity = Gravity.CENTER })
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) }
        return b
    }
    private fun styleTab(b: TvOwnLinear, idx: Int, f: Boolean) {
        val sel = idx == curTab
        b.background = pillBg(if (f) Color.WHITE else if (sel) 0x66FFFFFF else Color.TRANSPARENT, 24)
        (b.getChildAt(0) as? TextView)?.setTextColor(if (f) Color.BLACK else if (sel) fg else 0xCCFFFFFF.toInt())
    }
    /** (v512) التركيز على تاب «الرئيسية»/«تطبيقات» بيفتح صفحته فورًا (من غير OK) بتلاشي سريع؛ باقي التابات بتفتح أنشطة تانية فبتفضل بالدوسة */
    private var tabPending = -1
    private val tabGo = Runnable {
        val i = tabPending; tabPending = -1
        if (i < 0 || i == curTab || isDestroyed) return@Runnable
        try {
            content.animate().cancel(); content.alpha = 0f; content.translationY = ui.dp(10).toFloat()
            if (i == 0) showHome(false, true) else if (i == 1) showVideosPage(false) else if (i == 2) showApps(false)
            content.animate().alpha(1f).translationY(0f).setDuration(Skins.dur(180L)).setInterpolator(DecelerateInterpolator()).start()
        } catch (e: Throwable) { LogStore.err("TvHome:tabGo", e); content.alpha = 1f; content.translationY = 0f }
    }
    private val tabGoAct = Runnable {
        val i = tabPending; tabPending = -1
        if (i < 3 || isDestroyed || tabViews.getOrNull(i)?.isFocused != true) return@Runnable
        val act = tabActions.getOrNull(i) ?: return@Runnable
        tabAutoBlock = i
        try {
            content.animate().cancel()
            content.animate().alpha(0.15f).scaleX(0.97f).scaleY(0.97f).setDuration(Skins.dur(140L)).setInterpolator(DecelerateInterpolator())
                .withEndAction { try { act() } catch (e: Throwable) { LogStore.err("TvHome:tabAct", e) } }.start()
        } catch (e: Throwable) { LogStore.err("TvHome:tabAct2", e); act() }
    }
    private fun tabFocused(i: Int) {
        h.removeCallbacks(tabGo); h.removeCallbacks(tabGoAct); tabPending = -1
        if (i != tabAutoBlock) tabAutoBlock = -1
        if (i == curTab) return
        if (i in 0..2) { tabPending = i; h.postDelayed(tabGo, 90L) }
        else if (i != tabAutoBlock && i != 3) { tabPending = i; h.postDelayed(tabGoAct, 380L) }   // (v530) يوتيوب (2) بيتفتح بالدوسة بس   // فيديوهات/ملفات: تتفتح لوحدها بعد ما التركيز يستقر 380ms (عشان التنقل السريع ما يفتحش كل تاب)
    }
    private fun setTab(i: Int) { curTab = i; tabViews.forEachIndexed { k, v -> styleTab(v, k, v.isFocused) } }

    // ===== (v527) تابات القايمة الجانبية المخصّصة: تطبيق أو فولدر فيديو بعينه، بدوسة واحدة =====
    private class SideTab(val type: String, val id: String, val label: String)
    private fun sideTabs(): List<SideTab> = Cfg.str("tv_side_tabs", "").split("\n").mapNotNull {
        val p = it.split("|", limit = 3)
        if (p.size == 3 && (p[0] == "app" || p[0] == "folder") && p[1].isNotBlank()) SideTab(p[0], p[1], p[2]) else null
    }
    private fun saveSideTabs(l: List<SideTab>) { Cfg.put("tv_side_tabs", l.joinToString("\n") { it.type + "|" + it.id + "|" + it.label.replace("|", " ").replace("\n", " ") }); recreate() }

    private fun customTab(t: SideTab): View {
        val b = TvOwnLinear(this).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); contentDescription = t.label
            background = pillBg(Color.TRANSPARENT, 24)
            setOnClickListener { openSideTab(t) }
            setOnFocusChangeListener { v, f -> v.background = pillBg(if (f) Color.WHITE else Color.TRANSPARENT, 24); if (f) Notice.show(this@TvActivity, t.label, 1100L) }
        }
        val icon: android.graphics.drawable.Drawable? = if (t.type == "app") try { packageManager.getApplicationIcon(t.id) } catch (_: Throwable) { null } else null
        if (icon != null) b.addView(ImageView(this).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)))
        else b.addView(ui.text(if (t.type == "app") "📱" else "📁", 20f, fg).apply { gravity = Gravity.CENTER })
        b.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(2), 0, ui.dp(2)) }
        return b
    }

    private fun openSideTab(t: SideTab) {
        if (t.type == "app") { launchApp(t.id); return }
        if (!scanned) { Notice.show(this, "⏳ لسه بقرا الفيديوهات — جرّب تاني بعد ثانيتين", 2400L); return }
        val f = VideoLib.group(lastScan).firstOrNull { it.key == t.id }
        if (f == null) { Notice.show(this, "⚠ الفولدر ده مش لاقيه (اتمسح أو الفلاشة مش موصّلة)", 2600L); return }
        openFolderView(f)
    }

    private fun sideTabsMenu() {
        val cur = sideTabs()
        val items = ArrayList<String>(); items.add("📲 أضف تطبيق"); items.add("📁 أضف فولدر فيديو")
        if (cur.isNotEmpty()) items.add("🗑 شيل تاب (" + cur.size + ")")
        GAlert(this).setTitle("➕ تابات القايمة الجانبية").setItems(items.toTypedArray()) { _, i ->
            when (i) { 0 -> pickSideApp(); 1 -> pickSideFolder(); else -> removeSideTab() }
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun pickSideApp() {
        val pm = packageManager
        val all = allLaunchApps().sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (all.isEmpty()) { Notice.show(this, "⚠ مفيش تطبيقات متاحة", 1800L); return }
        GAlert(this).setTitle("📲 اختار التطبيق").setItems(all.map { it.loadLabel(pm).toString() }.toTypedArray()) { _, i ->
            val pkg = all[i].activityInfo.packageName
            val cur = sideTabs()
            if (cur.any { it.type == "app" && it.id == pkg }) { Notice.show(this, "التاب ده موجود قبل كده", 1600L); return@setItems }
            saveSideTabs(cur + SideTab("app", pkg, all[i].loadLabel(pm).toString()))
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun pickSideFolder() {
        if (!VideoScan.hasPermission(this)) { Notice.show(this, "⚠ محتاج إذن الفيديوهات — افتح «الفيديوهات» مرة وادّي الإذن", 2800L); return }
        if (!scanned) { Notice.show(this, "⏳ لسه بقرا الفيديوهات — جرّب تاني بعد ثانيتين", 2400L); return }
        val all = VideoLib.sortFolders(VideoLib.group(lastScan), VideoLib.SORT_NAME)
        if (all.isEmpty()) { Notice.show(this, "مفيش فولدرات فيها فيديوهات", 2000L); return }
        GAlert(this).setTitle("📁 اختار الفولدر").setItems(all.map { "📁 " + it.name + " · " + it.videos.size + (if (it.path.isNotBlank()) "   (" + it.path + ")" else "") }.toTypedArray()) { _, i ->
            val f = all[i]; val cur = sideTabs()
            if (cur.any { it.type == "folder" && it.id == f.key }) { Notice.show(this, "التاب ده موجود قبل كده", 1600L); return@setItems }
            saveSideTabs(cur + SideTab("folder", f.key, f.name))
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun removeSideTab() {
        val cur = sideTabs()
        GAlert(this).setTitle("🗑 شيل أنهي تاب؟").setItems(cur.map { (if (it.type == "app") "📱 " else "📁 ") + it.label }.toTypedArray()) { _, i ->
            saveSideTabs(cur.filterIndexed { k, _ -> k != i })
        }.setNegativeButton("إلغاء", null).show()
    }

    /** قايمة «المزيد»: كل اللي كان في الريل القديم + أزرار اللانشر (إعدادات الأندرويد · تطبيقات السيستم · المشغّل الافتراضي · إلغاء التثبيت) */
    private fun moreMenu() {
        val items = arrayOf("🛠 إعدادات الأندرويد", "📦 تطبيقات السيستم", "🏠 خليه المشغّل الافتراضي", "🗑 إلغاء تثبيت اللانشر",
            "🎛 تخصيص الصفوف", "🔒 قفل TV", "🌡 دفء ليلي (شغّل/اقفل)", "🌙 نوم (شاشة سكون)", "🎵 الموسيقى", "📋 المهام",
            "🎮 اختصارات الريموت",
            "🖼 خلفية اللانشر",
            "📐 حجم الواجهة",
            "🎨 المظهر",
            if (playOn()) "🛍 اختصار Google Play: شغّال (دوس يقفله)" else "🛍 اختصار Google Play: مقفول (دوس يشغّله)",
            "📱 ريموت الموبايل (اقتران · تحديث)",
            "🔐 الأذونات (ريموت الموبايل · واي فاي · فيديوهات…)")
        GAlert(this).setTitle("⋯ المزيد").setItems(items) { _, i ->
            when (i) {
                0 -> openSystemSettings(); 1 -> openSystemApps(); 2 -> setAsDefaultLauncher(); 3 -> uninstallSelf()
                4 -> rowsDialog(); 5 -> pinMenu(); 6 -> toggleWarm(); 7 -> showSaver()
                8 -> openMain("music"); 9 -> openMain("tasks"); 10 -> remoteShortcuts(); 11 -> wallDialog(); 12 -> scaleDialog(); 13 -> showAppearance(); 14 -> { Cfg.put("tv_play", if (playOn()) "0" else "1"); recreate() }
                15 -> TvLinkUi.show(this)
                16 -> TvSetup.open(this)   // (v536)
            }
        }.setNegativeButton("إغلاق", null).show()
    }

    // ===== (v505) اختصارات الريموت: زرار في الريموت = يفتح تطبيق/قسم بدوسة واحدة (شغّال وانت جوه اللانشر) =====
    private val KEYS_PASS = setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    private val KEYS_RESERVED = setOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP)

    private fun keymap(): LinkedHashMap<String, Pair<String, String>> {
        val m = LinkedHashMap<String, Pair<String, String>>()
        Cfg.str("tv_keymap", "").lineSequence().forEach { l -> val p = l.split("\t"); if (p.size >= 3 && p[0].isNotBlank()) m[p[0]] = p[1] to p[2] }
        return m
    }
    private fun saveKeymap(m: Map<String, Pair<String, String>>) = Cfg.put("tv_keymap", m.entries.joinToString("\n") { it.key + "\t" + it.value.first + "\t" + it.value.second })
    private fun keyId(ev: KeyEvent) = if (ev.keyCode == KeyEvent.KEYCODE_UNKNOWN) "s" + ev.scanCode else "k" + ev.keyCode
    private fun keyName(id: String): String {
        if (id.startsWith("s")) return "زرار (كود " + id.substring(1) + ")"
        return when (val c = id.substring(1).toIntOrNull() ?: 0) {
            KeyEvent.KEYCODE_PROG_RED -> "🔴 الأحمر"
            KeyEvent.KEYCODE_PROG_GREEN -> "🟢 الأخضر"
            KeyEvent.KEYCODE_PROG_YELLOW -> "🟡 الأصفر"
            KeyEvent.KEYCODE_PROG_BLUE -> "🔵 الأزرق"
            else -> KeyEvent.keyCodeToString(c).removePrefix("KEYCODE_")
        }
    }

    private fun shortcutKey(ev: KeyEvent): Boolean {
        if (ev.keyCode in KEYS_PASS || ev.keyCode in KEYS_RESERVED) return false
        if (currentFocus is EditText) return false   // وانت بتكتب في البحث الأرقام تفضل أرقام
        val t = keymap()[keyId(ev)]?.first ?: return false
        if (ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) runTarget(t)
        return true
    }
    private fun runTarget(t: String) {
        when (t) {
            "int:youtube" -> openMain("youtube")
            "int:browser" -> openBrowser()
            "int:videos" -> showVideosPage()
            "int:files" -> guardPin { startActivity(Intent(this, FileManagerActivity::class.java)) }
            "int:voice" -> voiceToSearch()
            else -> launchApp(t)
        }
    }

    private fun remoteShortcuts() {
        val m = keymap(); val ids = m.keys.toList()
        val items = ids.map { keyName(it) + "  ←  " + (m[it]?.second ?: "") }.toMutableList(); items.add("➕ إضافة اختصار جديد")
        GAlert(this).setTitle("🎮 اختصارات الريموت").setItems(items.toTypedArray()) { _, i ->
            if (i >= ids.size) captureKey() else confirmDeleteKey(ids[i])
        }.setNegativeButton("إغلاق", null).show()
    }
    private fun confirmDeleteKey(id: String) {
        val name = keymap()[id]?.second ?: ""
        GAlert(this).setTitle("حذف الاختصار؟").setMessage(keyName(id) + "  ←  " + name)
            .setPositiveButton("🗑 حذف") { _, _ -> val m = keymap(); m.remove(id); saveKeymap(m); Notice.show(this, "اتحذف", 1400L) }
            .setNegativeButton("إلغاء", null).show()
    }

    /** الخطوة 1: «دوس على الزرار» — بنستني أول زرار يتداس في الريموت (الأسهم/OK/رجوع بيشتغلوا عادي عشان تقدر تلغي) */
    private fun captureKey() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(16)); background = ui.box(th.card, th.border, 22)
        }
        root.addView(ui.text("🎮 دوس على زرار الريموت", 20f, th.primary, true))
        root.addView(ui.text("دوس دلوقتي على الزرار اللي عايز تعمله اختصار (الألوان، الأرقام، أزرار الميديا…)", 15f, th.text).apply { setPadding(0, ui.dp(10), 0, ui.dp(12)) })
        val cancel = ui.text("إلغاء", 16f, th.text, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); background = ui.box(th.surface, th.border, 12)
            isFocusable = true; isClickable = true
        }
        root.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        val act = this
        val d = object : GDialog(act) {
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                val c = e.keyCode
                if (c in KEYS_PASS) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    if (c in KEYS_RESERVED) { Notice.show(act, "⚠ الزرار ده محجوز — اختار زرار تاني", 2200L); return true }
                    val id = keyId(e)
                    dismiss(); act.h.post { act.pickTarget(id) }
                }
                return true
            }
        }
        cancel.setOnClickListener { d.dismiss() }
        d.setContentView(root)
        d.show()
        cancel.post { try { cancel.requestFocus() } catch (_: Throwable) {} }
    }

    /** الخطوة 2: اختار التطبيق */
    private fun pickTarget(id: String) {
        val internals = listOf("int:youtube" to "▶️ يوتيوب (جوه Subtitler)", "int:browser" to "🌐 المتصفح", "int:videos" to "🎬 الفيديوهات",
            "int:files" to "📁 الملفات", "int:voice" to "🎤 بحث صوتي")
        val pm = packageManager
        val apps = try { allLaunchApps().sortedBy { it.loadLabel(pm).toString().lowercase() } } catch (_: Throwable) { emptyList() }
        val targets = internals + apps.map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        GAlert(this).setTitle("🎮 " + keyName(id) + " — يفتح إيه؟").setItems(targets.map { it.second }.toTypedArray()) { _, i -> confirmSave(id, targets[i]) }
            .setNegativeButton("إلغاء", null).show()
    }
    /** الخطوة 3: حفظ */
    private fun confirmSave(id: String, t: Pair<String, String>) {
        GAlert(this).setTitle("حفظ الاختصار؟")
            .setMessage(keyName(id) + "  ←  " + t.second + "\n\nدوسة واحدة على الزرار هتفتحه (وانت في شاشة اللانشر).")
            .setPositiveButton("💾 حفظ") { _, _ -> val m = keymap(); m[id] = t.first to t.second; saveKeymap(m); Notice.show(this, "✅ اتحفظ", 1600L) }
            .setNegativeButton("إلغاء", null).show()
    }


    // ===== (v506) خلفيات اللانشر: 3 مدمجة (طبيعة) + صور من الإنترنت (Picsum أو صورة اليوم من Bing — من غير مفتاح) =====
    private lateinit var wallView: ImageView
    private lateinit var wallScrim: View
    private var wallSeq = 0

    private fun decodeFileScaled(f: java.io.File): android.graphics.Bitmap? = try {
        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, b)
        var ss = 1; while (b.outWidth / ss > 2200) ss *= 2
        android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss })
    } catch (_: Throwable) { null }

    private fun showWall(bmp: android.graphics.Bitmap?, my: Int) {
        runOnUiThread {
            if (isDestroyed || my != wallSeq || bmp == null) return@runOnUiThread
            val newD = android.graphics.drawable.BitmapDrawable(resources, bmp)
            val oldD = if (wallView.visibility == View.VISIBLE) wallView.drawable else null
            wallView.visibility = View.VISIBLE; wallScrim.visibility = View.VISIBLE
            if (oldD != null) {   // (v527) تبديل بتلاشي متقاطع، وبعده نرجع لصورة واحدة عشان الذاكرة ماتتراكمش
                val td = android.graphics.drawable.TransitionDrawable(arrayOf(oldD, newD)); td.isCrossFadeEnabled = true
                wallView.setImageDrawable(td); td.startTransition(700)
                h.postDelayed({ if (!isDestroyed && my == wallSeq) wallView.setImageDrawable(newD) }, 760L)
            } else wallView.setImageDrawable(newD)
        }
    }

    /** (v527) الخلفية: بتتغيّر كل دقيقة من Bing ومن صور الإنترنت المنوّعة (Picsum) بالتبادل — الخلفيات المدمجة اتشالت.
     *  الوضع المحفوظ: auto (الاتنين) · bing · picsum · off. القديم «builtin» بيتعامل كـ auto. */
    private fun wallMode(): String { val m = Cfg.str("tv_wall", "auto"); return if (m == "off" || m == "bing" || m == "picsum") m else "auto" }
    private fun wallEveryMs(): Long = (Cfg.str("tv_wall_sec", "60").toLongOrNull() ?: 60L).coerceIn(15L, 3600L) * 1000L   // (v537)
    private val wallLoop = object : Runnable { override fun run() { if (isDestroyed || isFinishing) return; nextWall(false); h.postDelayed(this, wallEveryMs()) } }
    private var wallN = 0
    private var bingUrls: List<String> = emptyList()
    private val wallBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** بيطبّق الخلفية: يعرض آخر صورة محفوظة فورًا وبعدين يبدأ دورة التبديل كل دقيقة */
    private fun applyWall() {
        val mode = wallMode()
        val my = ++wallSeq
        h.removeCallbacks(wallLoop)
        if (mode == "off") { wallView.setImageDrawable(null); wallView.visibility = View.GONE; wallScrim.visibility = View.GONE; return }
        Thread {
            try {
                val f = java.io.File(filesDir, "wall_cur.jpg")
                if (f.exists()) showWall(decodeFileScaled(f), my)
            } catch (e: Throwable) { LogStore.err("Tv:wall", e) }
        }.apply { isDaemon = true }.start()
        h.post(wallLoop)   // أول صورة جديدة فورًا ثم كل دقيقة
    }

    /** بينزّل الصورة الجاية (Bing/Picsum بالتبادل) ويعرضها. لو النزول فشل بيفضل على الحالية ويحاول بعد دقيقة */
    private fun nextWall(force: Boolean) {
        val mode = wallMode()
        if (mode == "off" || !wallBusy.compareAndSet(false, true)) return
        val my = wallSeq
        val useBing = when (mode) { "bing" -> true; "picsum" -> false; else -> (wallN++ % 2 == 0) }
        Thread {
            try {
                val f = java.io.File(filesDir, "wall_cur.jpg")
                if (fetchWall(useBing, f) || fetchWall(!useBing && mode == "auto", f)) showWall(decodeFileScaled(f), my)
                else if (force) runOnUiThread { if (!isDestroyed) Notice.show(this, "⚠ ما قدرتش أنزّل خلفية جديدة (النت؟) — شغّال بآخر صورة", 2800L) }
            } catch (e: Throwable) { LogStore.err("Tv:wallNext", e) } finally { wallBusy.set(false) }
        }.apply { isDaemon = true }.start()
    }

    private fun httpBytes(url: String): ByteArray? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 20000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android TV) Subtitler")
            if (c.responseCode in 200..299) c.inputStream.use { it.readBytes() } else null
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    private fun fetchWall(bing: Boolean, dest: java.io.File): Boolean {
        return try {
            val url = if (bing) {
                if (bingUrls.isEmpty()) {   // آخر 8 صور من أرشيف Bing (بتتجاب مرة وتتلف عليها عشوائي)
                    val j = org.json.JSONObject(String(httpBytes("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US") ?: return false))
                    val arr = j.getJSONArray("images")
                    bingUrls = (0 until arr.length()).map { "https://www.bing.com" + arr.getJSONObject(it).getString("url") }
                }
                bingUrls.randomOrNull() ?: return false
            } else "https://picsum.photos/seed/" + System.currentTimeMillis() + "/1920/1080"
            val data = httpBytes(url) ?: return false
            if (data.size < 20_000) return false
            val tmp = java.io.File(dest.path + ".tmp"); tmp.writeBytes(data)
            if (decodeFileScaled(tmp) == null) { tmp.delete(); return false }
            tmp.renameTo(dest)
        } catch (e: Throwable) { LogStore.err("Tv:wallFetch", e); false }
    }

    private fun wallDialog() {
        val cur = wallMode()
        fun mk(k: String, t: String) = (if (k == cur) "✔ " else "") + t
        val items = arrayOf(mk("auto", "🔄 تلقائي — Bing + صور منوّعة، تتغيّر كل دقيقة"), mk("bing", "📰 Bing بس (كل دقيقة)"), mk("picsum", "🌐 صور منوّعة من الإنترنت بس (كل دقيقة)"), mk("off", "🚫 بدون خلفية"), "⏭ خلفية جديدة دلوقتي")
        GAlert(this).setTitle("🖼 خلفية اللانشر").setItems(items) { _, i ->
            when (i) {
                0 -> { Cfg.put("tv_wall", "auto"); applyWall() }
                1 -> { Cfg.put("tv_wall", "bing"); applyWall() }
                2 -> { Cfg.put("tv_wall", "picsum"); applyWall() }
                3 -> { Cfg.put("tv_wall", "off"); applyWall() }
                4 -> { if (wallMode() == "off") { Cfg.put("tv_wall", "auto"); applyWall() } else nextWall(true) }
            }
        }.setNegativeButton("إغلاق", null).show()
    }


    // ===== (v507) حجم الواجهة: تلقائي حسب الشاشة + ضبط يدوي =====
    private fun scaleDialog() {
        val auto = TvScale.autoFactor(this)
        val opts = listOf(50 to "أصغر بكتير (50%)", 70 to "أصغر (70%)", 85 to "أصغر شوية (85%)", 100 to "تلقائي (100%)", 115 to "أكبر شوية (115%)", 130 to "أكبر (130%)", 160 to "أكبر جدًا (160%)", 200 to "ضخم (200%)")
        val cur = Cfg.str("tv_scale_adj", "100").toIntOrNull() ?: 100
        GAlert(this).setTitle("📐 حجم الواجهة")
            .setMessage("الواجهة بتتظبط تلقائي على مقاس شاشتك (دلوقتي ×" + String.format(java.util.Locale.US, "%.2f", auto) + "). لو الشاشة كبيرة وانت بعيد كبّر، ولو قريب صغّر.\nالحالي: " + cur + "%")
            .setItems(opts.map { (if (it.first == cur) "✔ " else "") + it.second }.toTypedArray()) { _, i ->
                Cfg.put("tv_scale_adj", opts[i].first.toString()); recreate()
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun toggleWarm() {
        val on = Cfg.str("tv_warm", "0") != "1"
        Cfg.put("tv_warm", if (on) "1" else "0"); warmView.visibility = if (on) View.VISIBLE else View.GONE
        Notice.show(this, if (on) "🌡 الدفء الليلي اشتغل" else "🌡 الدفء الليلي اتقفل", 1400L)
    }

    // ===== (v496) تركيز: توسيع البحث · سكرول سلس · وهج بلون العنصر =====
    private fun isInside(v: View, anc: View): Boolean { var p: View? = v; while (p != null) { if (p === anc) return true; p = p.parent as? View }; return false }

    private fun onFocusMoved(nw: View) {
        if (tabViews.none { it === nw }) { h.removeCallbacks(tabGoAct); tabAutoBlock = -1 }   // (v513) التركيز خرج من التابات
        updateSearchExpand(nw); alignScroll(nw)   // (v504) التوهّج بقى ثابت: مبقاش بيتغيّر مع التركيز
    }

    private fun updateSearchExpand(nw: View) {
        val sb = searchBox ?: return
        val want = isInside(nw, sb) || (searchEt?.text?.isNotEmpty() == true)
        if (want == searchExpanded) return
        searchExpanded = want
        searchExtras.forEach { it.visibility = if (want) View.VISIBLE else View.GONE }
        if (compactUi) return
        // (v512) العرض الموسّع بيتحسب من المتاح فعلًا في الشريط عشان كبسولة التابات والساعة ما يتزقّوش/يتمدّدوا
        var maxW = ui.dp(430)
        try {
            val bar = sb.parent as? ViewGroup; val tb = tabsBoxV; val tcv = tcV
            if (want && bar != null && tb != null && tcv != null && bar.width > 0) {
                tcv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                val free = bar.width - bar.paddingLeft - bar.paddingRight - tb.width - tcv.measuredWidth - ui.dp(14) * 3
                maxW = free.coerceIn(ui.dp(170), ui.dp(430))
            }
        } catch (_: Throwable) { }
        val from = sb.layoutParams.width; val to = if (want) maxW else ui.dp(170)
        ValueAnimator.ofInt(from, to).setDuration(Skins.dur(220L)).apply {
            interpolator = DecelerateInterpolator()
            addUpdateListener { a -> sb.layoutParams.width = a.animatedValue as Int; sb.requestLayout() }
            start()
        }
    }

    /** الصف المتركّز بيتثبّت في مكان ثابت تقريبًا (زي Google TV) بحركة سلسة؛ التركيز على الشريط العلوي بيرجّع للأول */
    private fun alignScroll(nw: View) {
        if (!::content.isInitialized || !::scrollV.isInitialized) return
        var anchor: View = nw; var p: View? = nw
        while (p != null && p !== content) { if (p is HorizontalScrollView) anchor = p; p = p.parent as? View }
        if (p == null) {   // التركيز فوق (الشريط العلوي) — على الشاشة الواسعة بس نرجع لفوق؛ مع القايمة الجنبية ما نحرّكش حاجة
            if (scrollV.scrollY != 0 && railBox?.let { isInside(nw, it) } != true) scrollV.smoothScrollTo(0, 0)   // (v527) التركيز في القايمة الجانبية ما يحرّكش الصفحة
            return
        }
        // (v504) السكرول بيتحرك بس لو الصف اللي عليه التركيز طلع (كله أو جزء منه) بره المنطقة الظاهرة — غير كده ثابت
        var y = 0; var q: View = anchor
        while (q !== content) { y += q.top; q = q.parent as? View ?: break }
        val m = ui.dp(24); val top = scrollV.scrollY; val bot = top + scrollV.height
        if (y - m >= top && y + anchor.height + m <= bot) return
        val ty = (y - ui.dp(140)).coerceAtLeast(0)
        if (kotlin.math.abs(ty - top) < ui.dp(12)) return
        scrollV.smoothScrollTo(0, ty)
    }

    private fun bmpGlow(b: android.graphics.Bitmap): Int? = try {
        val s = android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true); val px = s.getPixel(0, 0); s.recycle()
        val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
        if (hsv[2] < 0.12f) null else { hsv[1] = (hsv[1] * 1.3f).coerceIn(0.35f, 1f); hsv[2] = 0.9f; Color.HSVToColor(hsv) }
    } catch (_: Throwable) { null }

    // (v503) التوهّج كان بيتشغّل مع كل حركة تركيز (تدرّج بحجم الشاشة كله بيتعاد رسمه 500ms) وكان ده سبب التهنيج: دلوقتي بعد ما التركيز يستقر 220ms بس
    private var glowTarget: View? = null
    private val glowRun = Runnable { glowTarget?.let { if (!isDestroyed) glowForNow(it) } }
    private fun glowFor(nw: View) { glowTarget = nw; h.removeCallbacks(glowRun); h.postDelayed(glowRun, 220L) }

    private fun glowForNow(nw: View) {
        var c = glowMap[nw]
        if (c == null && nw is TvTileView) {
            val bmp = ((nw.inner.getChildAt(0) as? ImageView)?.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            c = if (bmp != null) bmpGlow(bmp) else null
            if (c != null) glowMap[nw] = c
        }
        val target = c ?: 0xFF3A2C86.toInt()
        if (target == glowColor && glowAnim?.isRunning != true) return
        try {
            val loc = IntArray(2); nw.getLocationOnScreen(loc)
            val cx = ((loc[0] + nw.width / 2f) / resources.displayMetrics.widthPixels).coerceIn(0.1f, 0.9f)
            glowDrawable.setGradientCenter(cx, 0.5f)
        } catch (_: Throwable) { }
        glowAnim?.cancel()
        glowAnim = ValueAnimator.ofObject(ArgbEvaluator(), glowColor, target).setDuration(Skins.dur(350L)).apply {
            addUpdateListener { a -> glowColor = a.animatedValue as Int; glowDrawable.colors = intArrayOf((glowColor and 0x00FFFFFF) or 0x77000000, glowColor and 0x00FFFFFF) }
            start()
        }
    }

    /** يجرّب الـIntents بالترتيب لحد ما واحد يشتغل (أجهزة TV كتير بتشيل بعض شاشات الإعدادات) */
    private fun tryIntents(failMsg: String, vararg list: Intent) {
        for (i in list) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) { }
        }
        Notice.show(this, failMsg, 2400L)
    }

    private fun openSystemSettings() = tryIntents("⚠ إعدادات الأندرويد مش متاحة على الجهاز ده",
        Intent(android.provider.Settings.ACTION_SETTINGS),
        Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.MainSettings"))

    /** تطبيقات السيستم (مش تطبيقات البرنامج): صفحة «كل التطبيقات» في إعدادات الأندرويد عشان تمسح/توقف/تمسح الكاش */
    private fun openSystemApps() = tryIntents("⚠ شاشة التطبيقات مش متاحة على الجهاز ده",
        Intent(android.provider.Settings.ACTION_MANAGE_ALL_APPLICATIONS_SETTINGS),
        Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS),
        Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.device.apps.AppsActivity"),
        Intent(android.provider.Settings.ACTION_SETTINGS))

    /** خلّي Subtitler المشغّل (Home) الافتراضي: RoleManager على أندرويد 10+، وإلا شاشة المشغّل الافتراضي في الإعدادات */
    @Suppress("DEPRECATION")
    private fun setAsDefaultLauncher() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    if (rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) { Notice.show(this, "✅ Subtitler هو المشغّل الافتراضي بالفعل", 2200L); return }
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 83); return
                }
            }
        } catch (e: Throwable) { LogStore.err("TvHome:role", e) }
        tryIntents("⚠ اختار المشغّل الافتراضي من إعدادات الأندرويد ← التطبيقات الافتراضية",
            Intent(android.provider.Settings.ACTION_HOME_SETTINGS),
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))   // آخر حل: شاشة «اختار المشغّل» بتظهر لو في أكتر من واحد
    }

    /** (v485) هل Subtitler هو الشاشة الرئيسية (Home) الحالية؟ */
    private fun isDefaultHome(): Boolean = try {
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        ri?.activityInfo?.packageName == packageName
    } catch (_: Throwable) { false }

    /** (v485) أول ما اللانشر يفتح وهو مش الافتراضي: نافذة تسأل تخليه الافتراضي */
    private fun askDefaultHome() {
        if (Cfg.str("tv_ask_home", "1") == "0" || isDefaultHome()) return
        GAlert(this).setTitle("🏠 خلّي Subtitler المشغّل الافتراضي؟")
            .setMessage("دلوقتي شاشة التلفزيون الأصلية هي الرئيسية. تحب Subtitler يبقى هو الرئيسية؟ (تقدر ترجّعها لأي وقت، أو تمسح اللانشر بزرار 🗑 أو دوسة طويلة على 0 في الريموت)")
            .setPositiveButton("أيوه، خليه الافتراضي") { _, _ -> setAsDefaultLauncher() }
            .setNeutralButton("ما تسألنيش تاني") { _, _ -> Cfg.put("tv_ask_home", "0") }
            .setNegativeButton("بعدين", null).show()
    }

    /** إلغاء تثبيت Subtitler نفسه (لو حصلت مشكلة/بج في اللانشر) — بعد تأكيد */
    @Suppress("DEPRECATION")
    private fun uninstallSelf() {
        GAlert(this).setTitle("🗑 إلغاء تثبيت اللانشر").setMessage("هيتمسح Subtitler من الجهاز بالكامل (ببياناته). التلفزيون هيرجع للمشغّل الأصلي. متأكد؟")
            .setPositiveButton("امسح") { _, _ ->
                tryIntents("⚠ ما قدرتش أفتح شاشة إلغاء التثبيت",
                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")),
                    Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName")))
            }.setNegativeButton("لأ", null).show()
    }

    // ===== الكروت والصفوف =====
    private fun card(wDp: Int, hDp: Int, fill: (LinearLayout) -> Unit, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9)); background = ui.box(th.surface, th.border, 14)
        }
        c.setOnFocusChangeListener { v, f ->
            v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 14)
            v.animate().scaleX(if (f) 1.05f else 1f).scaleY(if (f) 1.05f else 1f).setDuration(130).start()
            v.elevation = if (f) ui.dp(8).toFloat() else 0f
        }
        c.setOnClickListener { onClick() }
        if (onLong != null) c.setOnLongClickListener { onLong(); true }
        fill(c)
        c.layoutParams = LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp)).apply { setMargins(ui.dp(7), ui.dp(6), ui.dp(7), ui.dp(6)) }
        return c
    }

    private fun rowInto(parent: LinearLayout, title: String, cards: List<View>) {
        if (cards.isEmpty()) return
        parent.addView(ui.text(title, 17f, th.primary, true).apply { setPadding(ui.dp(6), ui.dp(14), 0, ui.dp(2)) })
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        cards.forEach { inner.addView(it) }
        parent.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(inner) }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun recents(): List<Recent> = Recents.parse(try { File(filesDir, "recent.json").readText() } catch (_: Exception) { "" })

    /** (v492 م2) كارت فيديو 16:9 كبير على نمط Google TV: الصورة واخدة الكارت كله، والعنوان بيظهر تحت الكارت المتركّز بس */
    private fun vTile(thumb: (ImageView) -> Unit, title: String, sub: String, pct: Int = -1, fav: Boolean = false,
                      onClick: () -> Unit, onLong: (() -> Unit)? = null, folder: Boolean = false, wDp: Int = 236): View {
        val hDp = wDp * 133 / 236
        val t = TvTileView(this, 14)
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        t.inner.addView(iv, FrameLayout.LayoutParams(-1, -1))
        if (pct >= 0) {
            val track = FrameLayout(this).apply { setBackgroundColor(0x88000000.toInt()) }
            val frac = pct.coerceIn(0, 100)
            track.addView(View(this).apply { setBackgroundColor(th.primary) }, FrameLayout.LayoutParams(ui.dp((wDp * frac / 100).coerceAtLeast(0)), -1, Gravity.START))
            t.inner.addView(track, FrameLayout.LayoutParams(-1, ui.dp(4), Gravity.BOTTOM))
        }
        if (fav) t.inner.addView(ui.text("⭐", 14f, Color.WHITE).apply { setPadding(ui.dp(7), ui.dp(4), ui.dp(7), ui.dp(4)) }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        thumb(iv)
        t.setOnClickListener { onClick() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        t.contentDescription = title
        // (v511) الاسم تحت الكارت دايمًا (كل الكروت). الفولدر: الاسم في نص الكارت دايمًا بخط كبير وتظليل على قد الاسم.
        // الفيديو: الاسم تحته دايمًا + نفس الاسم في نص الكارت المتركّز بس (بيختفي من اللي ماتركّزش).
        val cap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), 0)
        }
        if (!folder) cap.addView(ui.text(title, 13f, Color.WHITE, true).apply { maxLines = 2 })
        if (sub.isNotBlank()) cap.addView(ui.text(sub, 11f, if (folder) Color.WHITE else th.muted).apply { maxLines = 1 })
        val mid = ui.text(if (folder) title.removePrefix("📁 ") else title, if (folder) 21f else 17f, Color.WHITE, true).apply {
            maxLines = if (folder) 3 else 2; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = ui.dp(wDp - 30)
            setPadding(ui.dp(12), ui.dp(5), ui.dp(12), ui.dp(5))
            background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(10).toFloat() }
            visibility = View.VISIBLE
        }
        t.inner.addView(mid, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        if (!folder) t.onFocus = { f -> mid.visibility = if (f) View.INVISIBLE else View.VISIBLE }   // (v512) الاسم في النص على كل الفيديوهات ما عدا المتركّز
        val w = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        w.addView(t, LinearLayout.LayoutParams(ui.dp(wDp + 10), ui.dp(hDp + 10)))
        w.addView(cap, LinearLayout.LayoutParams(ui.dp(wDp + 10), ui.dp(68)))
        w.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(2)) }
        return w
    }

    private fun recentCard(r: Recent): View {
        val vi = lastScan.firstOrNull { it.uri == r.uri }
        return vTile({ iv -> if (vi != null) Thumbs.load(this, vi) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } },
            r.title, "⏸ " + PlayerLogic.clock((r.posSec * 1000).toLong()) + " · " + r.percent + "%", r.percent, favs.contains(r.id),
            { playRecent(r) }, { favToggle(r.id, r.title) })
    }

    private fun videoCard(v: VideoItem, wDp: Int = 236): View = vTile({ iv -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } },
        v.title, "📁 " + v.folderName + if (v.durMs > 0) " · " + PlayerLogic.clock(v.durMs) else "", -1, favs.contains(v.videoId),
        { playFile(v.uri, v.landscape) }, { favToggle(v.videoId, v.title) }, false, wDp)

    // ===== (v530) صفحة الفيديوهات: أشكال العرض (مربعات كبيرة / صغيرة / قائمة) للفولدرات وللفيديوهات =====
    private fun foldView() = Cfg.str("tv_fold_view", "grid")
    private fun vidView() = Cfg.str("tv_vid_view", "grid")
    private fun wOf(mode: String) = if (mode == "dense") 168 else 236
    private fun perRowFor(wDp: Int): Int {
        val wdp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 68f
        return (wdp / (wDp + 30f)).toInt().coerceIn(2, 8)
    }
    private fun addCards(body: LinearLayout, cards: List<View>, mode: String) {
        if (mode == "list") cards.forEach { body.addView(it) } else gridInto(body, cards, perRowFor(wOf(mode)))
    }
    private val viewLabels = linkedMapOf("grid" to "▦ مربعات كبيرة", "dense" to "▪ مربعات صغيرة", "list" to "☰ قائمة")

    /** صف قائمة: مصغّرة + الاسم + التفاصيل (للفولدرات والفيديوهات) */
    private fun listRow(thumb: (ImageView) -> Unit, title: String, sub: String, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val t = TvTileView(this, 12)
        t.inner.setBackgroundColor(0x99000000.toInt())
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.border) }
        row.addView(iv, LinearLayout.LayoutParams(ui.dp(128), ui.dp(72)).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        thumb(iv)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), 0, ui.dp(10), 0) }
        col.addView(ui.text(title, 17f, Color.WHITE, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotBlank()) col.addView(ui.text(sub, 12.5f, 0xFFB8C0D4.toInt()).apply { maxLines = 1; setPadding(0, ui.dp(3), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        t.inner.addView(row, FrameLayout.LayoutParams(-1, -1))
        t.setOnClickListener { onClick() }
        if (onLong != null) t.setOnLongClickListener { onLong(); true }
        t.contentDescription = title
        t.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(92)).apply { setMargins(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2)) }
        return t
    }
    private fun videoItemView(v: VideoItem, mode: String): View =
        if (mode == "list") listRow({ iv -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } }, (if (favs.contains(v.videoId)) "⭐ " else "") + v.title,
            (if (v.durMs > 0) PlayerLogic.clock(v.durMs) + " · " else "") + VideoLib.fmtSize(v.size) + " · 📁 " + v.folderName, { playFile(v.uri, v.landscape) }, { favToggle(v.videoId, v.title) })
        else videoCard(v, wOf(mode))

    // ===== (v476) لانشر قوي: بانر رئيسي + وصول سريع + تطبيقات الجهاز + كروت =====
    private var heroImg: ImageView? = null
    private var heroUri: String = ""
    private var heroBtn: View? = null

    private fun onPrimary(): Int = if (Theme.lum(th.primary) > 0.55) Color.BLACK else Color.WHITE

    /** زرار حبّة (pill) كبير بتركيز واضح: حدّ سميك + تكبير */
    private fun pill(t: String, primary: Boolean = false, f: () -> Unit): View {
        val b = LinearLayout(this).apply {
            isFocusable = true; isClickable = true; gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(12))
            background = ui.box(if (primary) th.primary else th.surface, if (primary) th.primary else th.border, 28)
            setOnClickListener { f() }
            setOnFocusChangeListener { v, hasF ->
                v.background = ui.box(if (primary) th.primary else if (hasF) th.card else th.surface, if (hasF) th.text else if (primary) th.primary else th.border, 28, if (hasF) 3 else 1)
                v.animate().scaleX(if (hasF) 1.07f else 1f).scaleY(if (hasF) 1.07f else 1f).setDuration(120).start()
            }
        }
        b.addView(ui.text(t, 15f, if (primary) onPrimary() else th.text, true))
        b.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) }
        return b
    }

    private fun hero(recs: List<Recent>): View {
        val r = recs.firstOrNull()
        val box = FrameLayout(this).apply { background = ui.box(th.card, th.border, 24); clipToOutline = true }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f }
        heroImg = iv; heroUri = r?.uri ?: ""
        box.addView(iv, FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(30), ui.dp(22), ui.dp(30), ui.dp(18))
        }
        if (r != null) {
            col.addView(ui.text("▶ كمّل من حيث وقفت", 14f, th.primary, true))
            col.addView(ui.text(r.title, 28f, th.text, true).apply { maxLines = 2; setPadding(0, ui.dp(4), 0, ui.dp(2)) })
            col.addView(ui.text("⏸ " + PlayerLogic.clock((r.posSec * 1000).toLong()) + " · " + r.percent + "%", 14f, th.muted))
            val track = FrameLayout(this).apply { background = ui.box(th.border, th.border, 4) }
            track.addView(View(this).apply { setBackgroundColor(th.primary) }, FrameLayout.LayoutParams(ui.dp((320 * r.percent.coerceIn(0, 100) / 100).coerceAtLeast(0)), ui.dp(6)))
            col.addView(track, LinearLayout.LayoutParams(ui.dp(320), ui.dp(6)).apply { topMargin = ui.dp(10) })
        } else {
            col.addView(ui.text("أهلاً بيك 👋", 30f, th.text, true))
            col.addView(ui.text("ابدأ بفيديو من مكتبتك أو افتح يوتيوب", 16f, th.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(16), 0, 0) }
        val first = if (r != null) pill("▶ كمّل", true) { playRecent(r) } else pill("🎬 الفيديوهات", true) { showVideosPage() }
        heroBtn = first
        btns.addView(first)
        btns.addView(pill("🔍 بحث") { showSearch() })
        btns.addView(pill("▶ يوتيوب") { openMain("youtube") })
        col.addView(btns)
        box.addView(col, FrameLayout.LayoutParams(-1, -1))
        box.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(250)).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(4)) }
        return box
    }

    private fun tile(icon: String, label: String, f: () -> Unit): View = card(168, 104, { c ->
        c.gravity = Gravity.CENTER
        c.addView(ui.text(icon, 30f, th.text).apply { gravity = Gravity.CENTER })
        c.addView(ui.text(label, 14f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
    }, f)

    private fun quickTiles(): List<View> = listOf(
        tile("▶", "يوتيوب") { openMain("youtube") },
        tile("🌐", "المتصفح") { openBrowser() },
        tile("🎬", "الفيديوهات") { showVideosPage() },
        tile("🎵", "الموسيقى") { openMain("music") },
        tile("📂", "الملفات") { guardPin { startActivity(Intent(this, FileManagerActivity::class.java)) } },
        tile("📋", "المهام") { openMain("tasks") },
        tile("⚙️", "الإعدادات") { guardPin { openMain("settings") } }
    )

    /** تطبيقات الجهاز (Leanback الأول، وإلا اللانشر العادي) — من غير QUERY_ALL_PACKAGES (انظر <queries> في الـ Manifest) */
    private fun appCards(): List<View> {
        val pm = packageManager
        fun q(cat: String): List<android.content.pm.ResolveInfo> = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (_: Throwable) { emptyList() }
        var l = q(Intent.CATEGORY_LEANBACK_LAUNCHER)
        if (l.isEmpty()) l = q(Intent.CATEGORY_LAUNCHER)
        return l.filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }.take(40).map { appCard(it) }
    }

    private fun appCard(ri: android.content.pm.ResolveInfo): View {
        val pm = packageManager; val pkg = ri.activityInfo.packageName
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4)) }
        try { iv.setImageDrawable(ri.activityInfo.loadBanner(pm) ?: ri.loadIcon(pm)) } catch (_: Throwable) { }
        return card(206, 142, { c ->
            c.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(84)))
            c.addView(ui.text(ri.loadLabel(pm).toString(), 12.5f, th.text, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, ui.dp(5), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        }, { launchApp(pkg) })
    }

    private fun launchApp(pkg: String) {
        bumpUse(pkg)
        try {
            val i = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
            if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else Notice.show(this, "⚠ التطبيق مش متاح", 1800L)
        } catch (e: Exception) { LogStore.err("Tv:app", e); Notice.show(this, "⚠ ما قدرتش أفتح التطبيق", 2000L) }
    }

    // ===== (v484) الشاشة الرئيسية الجديدة: دواير التطبيقات · آخر المشغّل · الفولدرات · اشتراكات يوتيوب · تخصيص =====
    private val SYS_BLOCK = listOf("settings", "setup", "provision", "wizard", "packageinstaller", "permissioncontroller", "inputmethod", "bugreport",
        "certinstaller", "systemui", "onetimeinitializer", "keychain", "companiondevice", "tvlauncher", "launcher", "oem", "backuprestore",
        "diagnostic", "tvrecommendations", "sharedstorage", "cts", "factory", "wfd", "miracast", "dreams", "tvsystem")

    /** تطبيقات أساسية بس: بنشيل تطبيقات السيستم الداخلية (الإعدادات/الإعداد الأول/…) إلا لو فعّلت «إظهار تطبيقات السيستم» من التخصيص */
    private fun essentialApps(): List<android.content.pm.ResolveInfo> {
        val all = launchQuery()
        val showSys = Cfg.str("tv_apps_sys", "0") == "1"
        val pins = pinnedApps()
        return all.filter { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg == "com.android.vending") return@filter false   // (v539) Google Play أيقونة ثابتة تالتة
            if (pkg in pins) return@filter true   // (v495) التطبيق اللي ضفته بإيدك بيظهر حتى لو سيستم
            if (showSys) return@filter true
            val fl = ri.activityInfo.applicationInfo.flags
            val pureSys = (fl and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 && (fl and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            !(pureSys && SYS_BLOCK.any { pkg.lowercase().contains(it) })
        }.sortedWith(compareByDescending<android.content.pm.ResolveInfo> { appUse()[it.activityInfo.packageName] ?: 0 }
            .thenBy { lbl(it).lowercase() })   // (v485) الأكتر استخدامًا الأول
            .sortedBy { val i = pins.indexOf(it.activityInfo.packageName); if (i < 0) Int.MAX_VALUE else i }   // (v495) المضافين بالتخصيص الأول بترتيب إضافتهم
    }

    /** (v495) كل التطبيقات القابلة للفتح (بما فيها السيستم) — لقايمة «أضف تطبيق» في التخصيص */
    private fun allLaunchApps(): List<android.content.pm.ResolveInfo> = launchQuery().filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName != "com.android.vending" }
    // ===== (v521) كاش التطبيقات: ما بنسأل PackageManager ولا نبني الأيقونات من الأول كل مرة بندخل «تطبيقات»/الرئيسية =====
    private var launchCache: List<android.content.pm.ResolveInfo>? = null
    private var launchAt = 0L
    private fun launchQuery(force: Boolean = false): List<android.content.pm.ResolveInfo> {
        val c = launchCache
        if (!force && c != null && System.currentTimeMillis() - launchAt < 45_000L) return c
        val pm = packageManager
        fun q(cat: String): List<android.content.pm.ResolveInfo> = try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (_: Throwable) { emptyList() }
        val l = (q(Intent.CATEGORY_LEANBACK_LAUNCHER) + q(Intent.CATEGORY_LAUNCHER)).distinctBy { it.activityInfo.packageName }
        launchCache = l; launchAt = System.currentTimeMillis()
        return l
    }
    private val lblCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun lbl(ri: android.content.pm.ResolveInfo): String {
        val k = ri.activityInfo.packageName
        return lblCache.getOrPut(k) { (try { ri.loadLabel(packageManager).toString() } catch (_: Throwable) { "" }).ifBlank { k.substringAfterLast('.') } }
    }
    private class IcoC(val bmp: Bitmap, val glow: Int?)
    private val icoCache = java.util.concurrent.ConcurrentHashMap<String, IcoC>()
    private fun icoFor(ri: android.content.pm.ResolveInfo, px: Int): IcoC {
        val pkg = ri.activityInfo.packageName
        val key = pkg + "@" + px + (if (squircle()) "s" else "c")
        icoCache[key]?.let { return it }
        val label = lbl(ri)
        val bmp = circleIcon(try { ri.loadIcon(packageManager) } catch (_: Throwable) { null }, px) ?: letterIcon(label, th.primary, px)
        var glow: Int? = null
        try { glow = bmpGlow(Bitmap.createBitmap(bmp, px / 4, px / 4, px / 2, px / 2)) } catch (_: Throwable) { }
        val c = IcoC(bmp, glow); icoCache[key] = c
        return c
    }
    private var warmed = false
    /** يبني أيقونات كل التطبيقات في الخلفية أول ما اللانشر يفتح، فصفحة «تطبيقات» تفتح فورًا */
    private fun warmApps() {
        if (warmed) return; warmed = true
        val px = ui.dp(iconDp())
        Thread { try { for (ri in launchQuery()) { if (isDestroyed) break; icoFor(ri, px) } } catch (e: Throwable) { LogStore.err("TvHome:warm", e) } }.also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY }.start()
    }
    private fun builtinIcon(glyph: String, color: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.clipPath(shapePath(px)); cv.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = px * 0.46f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        cv.drawText(glyph, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }
    /** (v521) يوتيوب والمتصفح بقوا أيقونتين عاديتين في «تطبيقاتك» و«كل التطبيقات» بدل تابين بيفتحوا لوحدهم أول ما التركيز يعدّي عليهم */
    private fun builtinApps(): List<View> {
        val px = ui.dp(iconDp())
        return listOf(
            dockItem(builtinIcon("▶", 0xFFE62117.toInt(), px), "يوتيوب", 0xFFE62117.toInt(), { openMain("youtube") }),
            dockItem(builtinIcon("🌐", 0xFF2F80ED.toInt(), px), "المتصفح", 0xFF2F80ED.toInt(), { openBrowser() }),
            dockItem(builtinIcon("🛍", 0xFF34A853.toInt(), px), "Google Play", 0xFF34A853.toInt(), { openPlay() }))   // (v539) أول 3 أيقونات دايمًا: يوتيوب · المتصفح · Google Play
    }

    private fun pinnedApps(): List<String> = Cfg.str("tv_pin_apps", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    private fun pinnedFolders(): List<String> = Cfg.str("tv_pin_folders", "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    /** عدّاد فتح كل تطبيق من اللانشر (pkg=عدد) */
    private fun appUse(): Map<String, Int> = Cfg.str("tv_app_use", "").lineSequence().mapNotNull { l ->
        val i = l.lastIndexOf('='); if (i <= 0) null else l.substring(0, i) to (l.substring(i + 1).toIntOrNull() ?: 0)
    }.toMap()
    private fun bumpUse(pkg: String) {
        val m = LinkedHashMap(appUse()); m[pkg] = (m[pkg] ?: 0) + 1
        Cfg.put("tv_app_use", m.entries.joinToString("\n") { it.key + "=" + it.value })
    }

    // ===== (v516) شريط التطبيقات على نمط دوك TVMorph =====
    // دواير الأيقونة فيها واخدة الدايرة كلها · المتركّز بيطلع لفوق ويكبر وبيتوهّج من ورا واسمه بيظهر · انتقال ناعم بين التطبيقات
    private fun iconDp(): Int = (Cfg.str("tv_icon_px", "86").toIntOrNull() ?: 86).coerceIn(56, 140)
    private fun squircle(): Boolean = Cfg.str("tv_icon_shape", "circle") == "squircle"
    private fun liftLevel(): Int = (Cfg.str("tv_lift", "2").toIntOrNull() ?: 2).coerceIn(0, 3)
    private fun namesMode(): String = Cfg.str("tv_names", "always")   // (v519) الاسم ظاهر دايمًا افتراضيًا
    private fun playOn(): Boolean = Cfg.str("tv_play", "1") != "0"

    private fun shapePath(px: Int): android.graphics.Path = android.graphics.Path().apply {
        if (squircle()) addRoundRect(0f, 0f, px.toFloat(), px.toFloat(), px * 0.26f, px * 0.26f, android.graphics.Path.Direction.CW)
        else addCircle(px / 2f, px / 2f, px / 2f, android.graphics.Path.Direction.CW)
    }

    /** متوسط ألوان البكسلات الظاهرة في الأيقونة (من غير تغميق) — بيملا الفراغ حوالين الأيقونة اللي مش بتملا الدايرة */
    private fun rawAvg(d: Drawable): Int = try {
        val b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 8, 8); d.draw(Canvas(b))
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = b.getPixel(x, y)
            if ((p ushr 24) > 40) { r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++ }
        }
        b.recycle()
        if (n == 0L) th.surface else Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
    } catch (_: Throwable) { th.surface }

    /** (v516) أيقونة التطبيق مقصوصة بالشكل المختار (دايرة/مربع مدوّر) والصورة واخدة الشكل كله — الأيقونات العادية بتتكبّر لحد ما تملا مش بتفضل مربع صغير جوه الدايرة */
    private fun circleIcon(d: Drawable?, px: Int): Bitmap? {
        if (d == null) return null
        return try {
            val src: Drawable = d.constantState?.newDrawable()?.mutate() ?: d
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            cv.clipPath(shapePath(px))
            if (src is android.graphics.drawable.AdaptiveIconDrawable) {
                val o = (px * 0.25f).toInt()
                src.background?.let { it.setBounds(-o, -o, px + o, px + o); it.draw(cv) }
                src.foreground?.let { it.setBounds(-o, -o, px + o, px + o); it.draw(cv) }
            } else {
                cv.drawColor(rawAvg(src))
                val iw = if (src.intrinsicWidth > 0) src.intrinsicWidth else px
                val ih = if (src.intrinsicHeight > 0) src.intrinsicHeight else px
                val sc = maxOf(px.toFloat() / iw, px.toFloat() / ih) * 1.06f
                val w = (iw * sc).toInt(); val hh = (ih * sc).toInt()
                val l = (px - w) / 2; val t = (px - hh) / 2
                src.setBounds(l, t, l + w, t + hh); src.draw(cv)
            }
            bmp
        } catch (_: Throwable) { null }
    }

    /** أيقونة حرف على لون (للتطبيقات المقترحة اللي لسه مش متثبتة) */
    private fun letterIcon(label: String, color: Int, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.clipPath(shapePath(px)); cv.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; textSize = px * 0.5f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        cv.drawText(label.trim().take(1).uppercase(), px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }

    /** عنصر دوك: أيقونة + اسم. المتركّز: بيكبر ويطلع لفوق، وهالة لون الأيقونة بتتوهّج من وراه، وحلقة بيضاء، واسمه بيظهر */
    private fun dockItem(bmp: Bitmap?, label: String, glow: Int?, onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val px = ui.dp(iconDp())
        val gColor = glow ?: th.primary
        val sq = squircle()
        fun ring(f: Boolean) = GradientDrawable().apply {
            shape = if (sq) GradientDrawable.RECTANGLE else GradientDrawable.OVAL
            if (sq) cornerRadius = (px + ui.dp(10)) * 0.26f
            setColor(Color.TRANSPARENT); setStroke(ui.dp(3), if (f) Color.WHITE else Color.TRANSPARENT)
        }
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = px * 0.5f
                colors = intArrayOf((gColor and 0x00FFFFFF) or 0xE0000000.toInt(), gColor and 0x00FFFFFF)
            }
            alpha = 0f; isFocusable = false; isClickable = false
        }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; if (bmp != null) setImageBitmap(bmp) }
        val frame = FrameLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(ui.dp(5), ui.dp(5), ui.dp(5), ui.dp(5)); background = ring(false) }
        frame.addView(halo, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
        frame.addView(iv, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
        val mode = namesMode()
        val name = ui.text(label, 14f, Color.WHITE, true).apply {
            maxLines = 1; gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ui.dp(10), 0, 0)
            alpha = if (mode == "always") 1f else 0f; setShadowLayer(8f, 0f, 2f, Color.BLACK)
            if (mode == "off") visibility = View.INVISIBLE
        }
        val lv = liftLevel()
        val big = when (lv) { 0 -> 1f; 1 -> 1.15f; 2 -> 1.3f; else -> 1.45f }
        val up = if (lv == 0) 0f else ui.dp(6 + lv * 4).toFloat()
        val haloOn = Cfg.str("tv_halo", "1") != "0"
        val c = TvOwnLinear(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; isFocusable = true; isClickable = true
            clipChildren = false; clipToPadding = false
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
            contentDescription = label
            setOnFocusChangeListener { v, f ->
                frame.background = ring(f)
                val d = Skins.dur(230L)
                frame.animate().scaleX(if (f) big else 1f).scaleY(if (f) big else 1f).translationY(if (f) -up else 0f).setDuration(d)
                    .setInterpolator(if (f) android.view.animation.OvershootInterpolator(1.1f) else DecelerateInterpolator()).start()
                halo.animate().alpha(if (f && haloOn) 1f else 0f).scaleX(if (f) 2.1f else 1f).scaleY(if (f) 2.1f else 1f).setDuration(d + 60L).start()
                if (mode != "off") name.animate().alpha(if (f) 1f else if (mode == "always") 1f else 0f).translationY(if (f) -up * 0.4f else 0f).setDuration(d).start()
                v.animate().translationZ(if (f) ui.dp(10).toFloat() else 0f).setDuration(d).start()
            }
        }
        c.addView(frame, LinearLayout.LayoutParams(-2, -2))
        c.addView(name, LinearLayout.LayoutParams(px + ui.dp(34), -2))
        c.layoutParams = LinearLayout.LayoutParams(px + ui.dp(40), -2).apply { setMargins(ui.dp(8), ui.dp(30), ui.dp(8), ui.dp(18)) }
        return c
    }

    private fun appCircle(ri: android.content.pm.ResolveInfo): View {
        val pm = packageManager; val pkg = ri.activityInfo.packageName
        val px = ui.dp(iconDp())
        val label = lbl(ri)
        val ic = icoFor(ri, px)   // (v521) من الكاش — (v519) لو الأيقونة ما اتحمّلتش بنرسم حرف على لون
        return dockItem(ic.bmp, label, ic.glow, { launchApp(pkg) }, { openAppInfo(pkg) })
    }

    private fun openPlay() {
        if (packageManager.getLaunchIntentForPackage("com.android.vending") == null) { Notice.show(this, "⚠ Google Play مش متثبّت على الجهاز ده", 2200L); return }
        launchApp("com.android.vending")
    }
    // ===== (v516) مواقيت الصلاة (بالموقع) =====
    private var prayerCd: TextView? = null
    private var prayerNextAt = 0L
    private val PRAY_NAMES = listOf("Fajr" to "الفجر", "Sunrise" to "الشروق", "Dhuhr" to "الظهر", "Asr" to "العصر", "Maghrib" to "المغرب", "Isha" to "العشاء")
    private val PRAY_METHODS = listOf("5" to "الهيئة المصرية العامة للمساحة", "4" to "أم القرى (مكة)", "3" to "رابطة العالم الإسلامي", "2" to "أمريكا الشمالية (ISNA)",
        "1" to "جامعة العلوم الإسلامية بكراتشي", "8" to "منطقة الخليج", "9" to "الكويت", "10" to "قطر", "13" to "تركيا (ديانت)", "12" to "اتحاد المنظمات الإسلامية بفرنسا")
    private class PrayerRes(val city: String, val times: Map<String, String>)

    private fun fmt12(t: String): String {
        val hm = t.split(":"); val h24 = hm.getOrNull(0)?.toIntOrNull() ?: return t; val m = hm.getOrNull(1) ?: "00"
        return (if (h24 % 12 == 0) 12 else h24 % 12).toString() + ":" + m + (if (h24 < 12) " ص" else " م")
    }
    private fun timeToday(t: String): Long {
        val hm = t.split(":")
        return java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hm[0].toInt()); set(java.util.Calendar.MINUTE, hm[1].toInt()); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun askLocPermOnce() {
        try {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
            if (Cfg.str("tv_loc_asked", "0") == "1") return
            Cfg.put("tv_loc_asked", "1")
            requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 92)
        } catch (e: Throwable) { LogStore.err("TvHome:locperm", e) }
    }

    @Suppress("MissingPermission")
    private fun deviceLoc(): Pair<Double, Double>? = try {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
        else {
            val lm = getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            val l = lm.getProviders(true).mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Throwable) { null } }.maxByOrNull { it.time }
            if (l != null) l.latitude to l.longitude else null
        }
    } catch (_: Throwable) { null }

    private fun httpText(u: String): String { val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection; c.connectTimeout = 8000; c.readTimeout = 8000; return try { c.inputStream.bufferedReader().use { it.readText() } } finally { try { c.disconnect() } catch (_: Throwable) { } } }

    /** الموقع: موقع الجهاز لو الإذن متاح، وإلا تقريبي من الـIP (بيتخزّن 24 ساعة). النتيجة: (lat, lon, اسم المكان) */
    private fun resolveLoc(force: Boolean): Triple<Double, Double, String> {
        val p = Cfg.str("tv_loc", "").split("|")
        val ts = p.getOrNull(3)?.toLongOrNull() ?: 0L
        if (!force && p.size >= 4 && System.currentTimeMillis() - ts < 24L * 3600_000L) return Triple(p[0].toDouble(), p[1].toDouble(), p[2])
        var lat: Double; var lon: Double; var city = ""
        val dl = deviceLoc()
        if (dl != null) { lat = dl.first; lon = dl.second; city = "حسب موقع الجهاز" }
        else {
            val j = org.json.JSONObject(httpText("https://ipwho.is/"))
            lat = j.getDouble("latitude"); lon = j.getDouble("longitude")
            city = listOf(j.optString("city", ""), j.optString("country", "")).filter { it.isNotBlank() }.joinToString("، ")
        }
        Cfg.put("tv_loc", "$lat|$lon|$city|${System.currentTimeMillis()}")
        return Triple(lat, lon, city)
    }

    private fun loadPrayer(force: Boolean, cb: (PrayerRes?) -> Unit) {
        Thread {
            val res = try {
                val (lat, lon, city) = resolveLoc(force)
                val day = SimpleDateFormat("dd-MM-yyyy", Locale.US).format(Date())
                val key = day + "|" + prayerMethod() + "|" + String.format(Locale.US, "%.2f,%.2f", lat, lon)
                val cached = Cfg.str("tv_pray", "").split("\n")
                val times: Map<String, String> = if (!force && cached.size >= 2 && cached[0] == key) {
                    cached[1].split(";").mapNotNull { val kv = it.split("="); if (kv.size == 2) kv[0] to kv[1] else null }.toMap()
                } else {
                    val j = org.json.JSONObject(httpText("https://api.aladhan.com/v1/timings/$day?latitude=$lat&longitude=$lon&method=${prayerMethod()}"))
                    val t = j.getJSONObject("data").getJSONObject("timings")
                    val m = PRAY_NAMES.associate { it.first to t.getString(it.first).take(5) }
                    Cfg.put("tv_pray", key + "\n" + m.entries.joinToString(";") { it.key + "=" + it.value })
                    m
                }
                if (times.size < 5) null else PrayerRes(city, times)
            } catch (e: Throwable) { LogStore.err("TvHome:prayer", e); null }
            runOnUiThread { if (!isDestroyed) cb(res) }
        }.apply { isDaemon = true }.start()
    }
    private fun prayerMethod(): String = Cfg.str("tv_pray_method", "5")

    private fun showPrayer(force: Boolean = false) {
        h.removeCallbacks(searchRun)
        muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
        storageKind = null; openFolder = null; foldersHost = null; setTab(-1)
        content.removeAllViews(); prayerCd = null; prayerNextAt = 0L
        content.addView(sectionTitle("🕌 مواقيت الصلاة"))
        val body = host(); content.addView(body)
        body.addView(ui.text("⏳ بجيب مكانك ومواقيت الصلاة…", 15f, th.muted).apply { setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(6)) })
        askLocPermOnce()
        loadPrayer(force) { res -> if (body.parent != null) renderPrayer(body, res) }
    }

    private fun renderPrayer(body: LinearLayout, res: PrayerRes?) {
        body.removeAllViews()
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(10), 0, ui.dp(20)) }
        actions.addView(pill("🔄 حدّث الموقع") { showPrayer(true) })
        actions.addView(pill("📐 طريقة الحساب") { prayerMethodDialog() })
        actions.addView(pill("→ الرئيسية") { showHome() })
        if (res == null) {
            body.addView(ui.text("⚠ ما قدرتش أجيب المواقيت — اتأكد من النت وجرّب تاني", 15f, th.muted).apply { setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(6)) })
            body.addView(actions); try { actions.getChildAt(0).requestFocus() } catch (_: Throwable) { }
            return
        }
        val now = System.currentTimeMillis()
        val order = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
        val nextOrNull: String? = order.firstOrNull { timeToday(res.times[it] ?: "00:00") > now }
        val nextKey: String = nextOrNull ?: "Fajr"
        val nextAt = if (nextOrNull != null) timeToday(res.times[nextKey] ?: "00:00") else timeToday(res.times["Fajr"] ?: "00:00") + 24L * 3600_000L
        prayerNextAt = nextAt
        val nextAr = PRAY_NAMES.first { it.first == nextKey }.second
        val big = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(30), ui.dp(22), ui.dp(30), ui.dp(22)); background = ui.box(0x99000000.toInt(), 0x33FFFFFF, 24)
        }
        big.addView(ui.text("الصلاة الجاية: $nextAr", 22f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
        big.addView(ui.text(fmt12(res.times[nextKey] ?: ""), 40f, th.primary, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, ui.dp(2)) })
        val cd = ui.text("--:--:--", 26f, Color.WHITE, true).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR }
        big.addView(cd); prayerCd = cd
        big.addView(ui.text("📍 " + res.city, 13f, 0xFFB8BDC9.toInt()).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) })
        body.addView(big, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        val cards = PRAY_NAMES.map { (k, ar) ->
            val isNext = k == nextKey
            card(138, 104, { c ->
                c.gravity = Gravity.CENTER
                c.addView(ui.text(ar, 16f, if (isNext) th.primary else th.text, true).apply { gravity = Gravity.CENTER })
                c.addView(ui.text(fmt12(res.times[k] ?: ""), 20f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
                if (isNext) c.addView(ui.text("◀ الجاية", 11f, th.primary).apply { gravity = Gravity.CENTER })
            }, { })
        }
        body.addView(hrow(cards))
        body.addView(actions)
        cards.getOrNull(PRAY_NAMES.indexOfFirst { it.first == nextKey }.coerceAtLeast(0))?.let { c -> c.post { try { c.requestFocus() } catch (_: Throwable) { } } }
    }

    // (v527) شريط اللانشر العلوي: «🕌 العصر بعد 1:23:45»
    private var topPrayTv: TextView? = null
    private var topPrayAt = 0L
    private var topPrayName = ""
    private fun refreshTopPrayer() {
        loadPrayer(false) { res ->
            if (res == null) { topPrayAt = 0L; topPrayTv?.visibility = View.GONE; return@loadPrayer }
            val now = System.currentTimeMillis()
            val order = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
            val nextOrNull: String? = order.firstOrNull { timeToday(res.times[it] ?: "00:00") > now }
            val nextKey: String = nextOrNull ?: "Fajr"
            topPrayAt = timeToday(res.times[nextKey] ?: "00:00") + (if (nextOrNull == null) 24L * 3600_000L else 0L)
            topPrayName = PRAY_NAMES.first { it.first == nextKey }.second
            topPrayTick()
        }
    }
    private fun topPrayTick() {
        val tv = topPrayTv ?: return
        if (topPrayAt <= 0L) return
        val left = (topPrayAt - System.currentTimeMillis()) / 1000
        if (left <= 0L) { topPrayAt = 0L; refreshTopPrayer(); return }
        val t = if (left >= 3600L) String.format(Locale.US, "%d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60) else String.format(Locale.US, "%02d:%02d", left / 60, left % 60)
        tv.text = "🕌 " + topPrayName + " بعد " + t
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
    }

    private fun prayerTick() {
        val cd = prayerCd ?: return
        if (!cd.isAttachedToWindow || prayerNextAt <= 0L) return
        val left = (prayerNextAt - System.currentTimeMillis()) / 1000
        if (left <= 0L) { prayerNextAt = 0L; showPrayer(); return }
        cd.text = String.format(Locale.US, "%02d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
    }

    /** (v525) شاشة السكون: «متبقي على صلاة X» بعدّاد حي فوق، وتحته مواقيت اليوم كلها (الجاية متظلّلة) */
    private fun fillSaverPrayer(res: PrayerRes?) {
        if (!::saverPray.isInitialized) return
        saverPray.removeAllViews(); saverCdTv = null; saverNextAt = 0L
        if (res == null) { saverPray.visibility = View.GONE; return }
        val now = System.currentTimeMillis()
        val order = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
        val nextOrNull: String? = order.firstOrNull { timeToday(res.times[it] ?: "00:00") > now }
        val nextKey: String = nextOrNull ?: "Fajr"
        saverNextAt = timeToday(res.times[nextKey] ?: "00:00") + (if (nextOrNull == null) 24L * 3600_000L else 0L)
        val nextAr = PRAY_NAMES.first { it.first == nextKey }.second
        saverPray.addView(ui.text("🕌 متبقي على صلاة $nextAr", 22f, 0xFFE8EBF4.toInt(), true).apply { gravity = Gravity.CENTER })
        val cd = ui.text("--:--:--", 40f, 0xFFFFD27A.toInt(), true).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR; textDirection = View.TEXT_DIRECTION_LTR; setPadding(0, ui.dp(2), 0, ui.dp(8)) }
        saverPray.addView(cd); saverCdTv = cd
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for ((k, ar) in PRAY_NAMES) {
            val isNext = k == nextKey
            val colV = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
                if (isNext) background = GradientDrawable().apply { setColor(0x44FFD27A); cornerRadius = ui.dp(14).toFloat() }
            }
            colV.addView(ui.text(ar, 14f, if (isNext) 0xFFFFD27A.toInt() else 0xFFB8BDC9.toInt(), isNext).apply { gravity = Gravity.CENTER })
            colV.addView(ui.text(fmt12(res.times[k] ?: ""), 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
            row.addView(colV)
        }
        saverPray.addView(row)
        saverPray.visibility = View.VISIBLE
        saverPrayTick()
    }

    private fun saverPrayTick() {
        val tv = saverCdTv ?: return
        if (saverNextAt <= 0L) return
        val left = (saverNextAt - System.currentTimeMillis()) / 1000
        if (left <= 0L) { saverNextAt = 0L; loadPrayer(false) { res -> fillSaverPrayer(res) }; return }
        tv.text = String.format(Locale.US, "%02d:%02d:%02d", left / 3600, (left % 3600) / 60, left % 60)
    }

    private fun prayerMethodDialog() {
        val cur = prayerMethod()
        GAlert(this).setTitle("📐 طريقة حساب المواقيت")
            .setItems(PRAY_METHODS.map { (if (it.first == cur) "✔ " else "") + it.second }.toTypedArray()) { _, i -> Cfg.put("tv_pray_method", PRAY_METHODS[i].first); showPrayer(true) }
            .setNegativeButton("إغلاق", null).show()
    }

    // ===== (v516) المظهر: كل إعدادات شكل اللانشر في صفحة واحدة =====
    private fun applyScrim() { if (::wallScrim.isInitialized) wallScrim.setBackgroundColor(TvWall.scrimColor()) }

    private fun showAppearance(focusIdx: Int = 0) {
        h.removeCallbacks(searchRun)
        muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
        storageKind = null; openFolder = null; foldersHost = null; setTab(-1); prayerCd = null
        content.removeAllViews()
        content.addView(sectionTitle("🎨 المظهر"))
        class Opt(val title: String, val value: () -> String, val act: () -> Unit)
        fun cyc(key: String, def: String, vals: List<String>): () -> Unit = { val i = vals.indexOf(Cfg.str(key, def)); Cfg.put(key, vals[(i + 1).mod(vals.size)]) }
        fun tog(key: String, def: String): () -> Unit = { Cfg.put(key, if (Cfg.str(key, def) == "0") "1" else "0") }
        fun onOff(key: String, def: String) = if (Cfg.str(key, def) == "0") "مقفول" else "شغّال"
        val iconSizes = listOf("64", "76", "86", "100", "116", "132")
        val liftNames = listOf("بدون", "خفيف", "متوسط", "عالي")
        val saverNames = mapOf("1" to "دقيقة", "3" to "3 دقايق", "5" to "5 دقايق", "10" to "10 دقايق", "0" to "أبدًا")
        val wallSecNames = mapOf("30" to "30 ثانية", "60" to "دقيقة", "300" to "5 دقايق", "900" to "15 دقيقة")
        // (v537) الإعدادات متقسّمة لمجموعات بعناوين بدل شبكة واحدة مخلوطة — كل الإعدادات القديمة موجودة، + جديد: مدة تبديل الخلفية · غيّر الخلفية دلوقتي · رجّع للافتراضي
        class Grp(val title: String, val opts: List<Opt>)
        val groups = listOf(
            Grp("🎨 الألوان والخلفية", listOf(
                Opt("🎨 السمة (الألوان)", { th.name }, { themeDialog() }),
                Opt("🖼 خلفية اللانشر", { when (wallMode()) { "off" -> "بدون"; "picsum" -> "صور منوّعة"; "bing" -> "Bing"; else -> "تلقائي (Bing + منوّعة)" } }, { wallDialog() }),
                Opt("⏱ تبديل الخلفية كل", { wallSecNames[Cfg.str("tv_wall_sec", "60")] ?: "دقيقة" }, { cyc("tv_wall_sec", "60", listOf("30", "60", "300", "900"))(); h.removeCallbacks(wallLoop); if (wallMode() != "off") h.postDelayed(wallLoop, wallEveryMs()) }),
                Opt("🔄 غيّر الخلفية دلوقتي", { "" }, { if (wallMode() == "off") { Cfg.put("tv_wall", "auto"); applyWall() } else nextWall(true) }),
                Opt("🌑 تعتيم الخلفية", { (Cfg.str("tv_scrim", "50")) + "%" }, { cyc("tv_scrim", "50", listOf("0", "25", "40", "50", "65", "80", "90"))(); applyScrim() }),
                Opt("🌡 دفء ليلي", { if (Cfg.str("tv_warm", "0") == "1") "شغّال" else "مقفول" }, { toggleWarm() })
            )),
            Grp("⭕ الأيقونات والأسماء", listOf(
                Opt("⭕ شكل أيقونات التطبيقات", { if (squircle()) "مربع مدوّر" else "دايرة" }, cyc("tv_icon_shape", "circle", listOf("circle", "squircle"))),
                Opt("🔎 حجم الأيقونات", { iconDp().toString() }, cyc("tv_icon_px", "86", iconSizes)),
                Opt("🏷 أسماء التطبيقات", { when (namesMode()) { "always" -> "دايمًا"; "off" -> "مخفية"; else -> "عند التركيز" } }, cyc("tv_names", "always", listOf("focus", "always", "off"))),
                Opt("📐 حجم الواجهة", { Cfg.str("tv_scale_adj", "100") + "%" }, { scaleDialog() })
            )),
            Grp("✨ التركيز والحركة", listOf(
                Opt("⬆ طلوع وتكبير المتركّز", { liftNames[liftLevel()] }, cyc("tv_lift", "2", listOf("0", "1", "2", "3"))),
                Opt("✨ وهج خلف المتركّز", { onOff("tv_halo", "1") }, tog("tv_halo", "1")),
                Opt("🎞 وضع الواجهة والحركة", { Skins.cur().name }, { skinDialog() })
            )),
            Grp("🏠 الرئيسية والتطبيقات", listOf(
                Opt("🎛 تخصيص الصفوف", { "" }, { rowsDialog() }),
                Opt("↕ ترتيب الصفوف", { "" }, { orderDialog() }),
                Opt("📲 تطبيقات السيستم", { if (Cfg.str("tv_apps_sys", "0") == "1") "ظاهرة" else "مخفية" }, tog("tv_apps_sys", "0")),
                Opt("🛍 اختصار Google Play", { onOff("tv_play", "1") }, { Cfg.put("tv_play", if (playOn()) "0" else "1"); recreate() })
            )),
            Grp("🕒 الساعة والشاشة", listOf(
                Opt("🕒 شكل الساعة", { if (Cfg.str("tv_clock24", "0") == "1") "24 ساعة" else "12 ساعة" }, tog("tv_clock24", "0")),
                Opt("💤 شاشة التوقف بعد", { saverNames[Cfg.str("tv_saver_min", "3")] ?: "3 دقايق" }, cyc("tv_saver_min", "3", listOf("1", "3", "5", "10", "0")))
            )),
            Grp("♻ إعادة الضبط", listOf(
                Opt("♻ رجّع المظهر للافتراضي", { "" }, { resetAppearance() })
            ))
        )
        val allCards = ArrayList<View>()
        var idx = 0
        for (g in groups) {
            content.addView(sectionTitle(g.title))
            val cards = g.opts.map { o ->
                val my = idx++
                card(if (compactUi) 250 else 270, 84, { c ->
                    c.gravity = Gravity.CENTER_VERTICAL
                    c.addView(ui.text(o.title, 14.5f, th.text, true))
                    val v = o.value()
                    if (v.isNotEmpty()) c.addView(ui.text(v, 13f, th.primary, true).apply { setPadding(0, ui.dp(4), 0, 0) })
                }, { o.act(); if (!isFinishing && !isDestroyed && curTab == -1) showAppearance(my) })
            }
            allCards.addAll(cards)
            gridInto(content, cards, if (compactUi) 2 else 3)
        }
        val cards = allCards
        content.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, ui.dp(24)); addView(pill("→ الرئيسية") { showHome() }) })
        cards.getOrNull(focusIdx.coerceIn(0, cards.size - 1))?.let { c -> c.post { try { c.requestFocus() } catch (_: Throwable) { } } }
    }

    /** (v537) بيرجّع إعدادات المظهر لقيمها الأصلية (السمة والصفوف والتابات ما بتتمسّحش) */
    private fun resetAppearance() {
        GAlert(this).setTitle("♻ رجّع المظهر للافتراضي؟")
            .setMessage("الخلفية والتعتيم وحجم الأيقونات والأسماء والحركة والساعة وشاشة التوقف هترجع لأصلها. السمة والصفوف والتابات مش هتتغيّر.")
            .setPositiveButton("رجّع") { _, _ ->
                val defs = mapOf("tv_scrim" to "50", "tv_scale_adj" to "100", "tv_icon_shape" to "circle", "tv_icon_px" to "86", "tv_names" to "always",
                    "tv_lift" to "2", "tv_halo" to "1", "tv_clock24" to "0", "tv_saver_min" to "3", "tv_apps_sys" to "0", "tv_wall" to "auto", "tv_wall_sec" to "60", "tv_warm" to "0")
                for ((k, v) in defs) Cfg.put(k, v)
                recreate()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun themeDialog() {
        val all = Themes.all
        GAlert(this).setTitle("🎨 اختار السمة")
            .setItems(all.map { (if (it.id == th.id) "✔ " else "") + it.name }.toTypedArray()) { _, i -> Cfg.put("theme", all[i].id); recreate() }
            .setNegativeButton("إلغاء", null).show()
    }
    private fun skinDialog() {
        val cur = Skins.cur().id
        GAlert(this).setTitle("🎞 وضع الواجهة والحركة")
            .setItems(Skins.all.map { (if (it.id == cur) "✔ " else "") + it.name }.toTypedArray()) { _, i -> Cfg.put("skin", Skins.all[i].id); Skins.set(Skins.all[i].id); showAppearance() }
            .setNegativeButton("إلغاء", null).show()
    }

    /** (v491) متوسط لون أيقونة (مغمّق) — خلفية كارت التطبيق اللي ملوش بانر */
    private fun avgColor(d: android.graphics.drawable.Drawable?): Int {
        if (d == null) return th.surface
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 8, 8); d.draw(android.graphics.Canvas(bmp))
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            for (x in 0 until 8) for (y in 0 until 8) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24) > 40) { r += (p shr 16) and 255; g += (p shr 8) and 255; bl += p and 255; n++ }
            }
            bmp.recycle()
            if (n == 0L) th.surface else Color.rgb((r / n * 0.5).toInt(), (g / n * 0.5).toInt(), (bl / n * 0.5).toInt())
        } catch (_: Throwable) { th.surface }
    }

    /** (v491) كارت تطبيق على نمط Google TV: البانر (أو الأيقونة على لون مستخرج منها) واخد الكارت كله، وحلقة بيضاء + تكبير عند التركيز */
    private fun appTile(ri: android.content.pm.ResolveInfo): View {
        val pm = packageManager; val pkg = ri.activityInfo.packageName
        val t = TvTileView(this, 14)
        val banner = try { ri.activityInfo.loadBanner(pm) } catch (_: Throwable) { null }
        if (banner != null) {
            t.inner.setBackgroundColor(th.surface)
            t.inner.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageDrawable(banner) }, FrameLayout.LayoutParams(-1, -1))
        } else {
            val icon = try { ri.loadIcon(pm) } catch (_: Throwable) { null }
            t.inner.setBackgroundColor(avgColor(try { ri.loadIcon(pm) } catch (_: Throwable) { null }))
            t.inner.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageDrawable(icon) },
                FrameLayout.LayoutParams(ui.dp(62), ui.dp(62), Gravity.CENTER).apply { bottomMargin = ui.dp(14) })
            t.inner.addView(ui.text(ri.loadLabel(pm).toString(), 12f, Color.WHITE, true).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(ui.dp(6), 0, ui.dp(6), ui.dp(8)) },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        t.setOnClickListener { launchApp(pkg) }
        t.setOnLongClickListener { openAppInfo(pkg); true }
        t.contentDescription = ri.loadLabel(pm).toString()
        t.layoutParams = LinearLayout.LayoutParams(ui.dp(222), ui.dp(130)).apply { setMargins(ui.dp(6), ui.dp(12), ui.dp(6), ui.dp(12)) }
        return t
    }

    private fun openAppInfo(pkg: String) = tryIntents("⚠ ما قدرتش أفتح معلومات التطبيق",
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))

    private fun hrow(items: List<View>): View {
        val inner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false; clipToPadding = false }
        items.forEach { inner.addView(it) }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(inner) }
    }
    /** (v496) عنوان صف زي Google TV: خط كبير + أيقونة الصف في دايرة صغيرة جنبه (لو العنوان بيبدأ بإيموجي) */
    private fun sectionTitle(t: String): View {
        val sp = t.indexOf(' ')
        val hasIcon = t.isNotEmpty() && t.codePointAt(0) >= 0x2190 && sp in 1..4
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(28), 0, ui.dp(6)) }
        if (hasIcon) {
            val badge = FrameLayout(this).apply { background = ovalBg(0x26FFFFFF) }
            badge.addView(ui.text(t.substring(0, sp), 14f, fg).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            row.addView(badge, LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginEnd = ui.dp(10) })
        }
        row.addView(ui.text(if (hasIcon) t.substring(sp + 1) else t, 22f, fg, true).apply { setShadowLayer(8f, 0f, 2f, Color.BLACK) })
        return row
    }
    private fun host(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false }

    private fun histCard(r: Recent, byUri: Map<String, VideoItem>): View {
        val vi = byUri[r.uri]
        return vTile({ iv -> if (vi != null) Thumbs.load(this, vi) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } },
            r.title, "⏸ " + PlayerLogic.clock((r.posSec * 1000).toLong()) + " · " + r.percent + "%", r.percent, favs.contains(r.id),
            { playRecent(r) }, { favToggle(r.id, r.title) })
    }

    /** آخر الفيديوهات المشغّلة (السجل): الأحدث أول واحد، والسهم بيمشي للأقدم فالأقدم */
    private fun fillHistory(hh: LinearLayout) {
        hh.removeAllViews()
        val recs = recents().take(40)
        if (recs.isEmpty()) return
        val byUri = lastScan.associateBy { it.uri }
        hh.addView(sectionTitle("🕘 آخر الفيديوهات المشغّلة"))
        hh.addView(hrow(recs.map { histCard(it, byUri) }))
    }

    private fun foldSort() = Cfg.str("tv_fold_sort", VideoLib.SORT_NAME)
    private fun vidSort() = Cfg.str("tv_vid_sort", VideoLib.SORT_NEW)   // (v530) الأحدث فوق جوه الفولدر (الفيديو الجديد يظهر في أول الفولدر)

    /** قايمة الفيديوهات مترتبة فولدرات، وزرار ⋮ لترتيب الفولدرات وترتيب الفيديوهات جواها */
    private var openFolder: String? = null      // (v490) مفتاح الفولدر المفتوح (null = قايمة الفولدرات)
    private var foldersHost: LinearLayout? = null
    private var storageKind: String? = null   // (v493) "int" | "sd" | null = الرئيسية
    private var storageQuery = ""
    private var scanned = false
    private var lastScanAt = 0L

    private fun closeFolder() {
        val back = openFolder; openFolder = null
        val fh = foldersHost ?: return
        fillStorageView(fh)
        // رجّع التركيز على الفولدر اللي كنت فيه
        fh.post { try { (fh.findViewWithTag<View>("fold:$back") ?: fh.findViewWithTag<View>("fold:first"))?.requestFocus() } catch (_: Throwable) {} }
    }

    private fun gridInto(parent: LinearLayout, cards: List<View>, perRow: Int) {
        var k = 0
        while (k < cards.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false }
            for (c in cards.subList(k, minOf(k + perRow, cards.size))) row.addView(c)
            parent.addView(row, LinearLayout.LayoutParams(-1, -2))
            k += perRow
        }
    }

    /** (v493 م3) الفيديو على أي ذاكرة؟ الفلاشة = أي مسار خارج /storage/emulated */
    private fun isSd(v: VideoItem): Boolean = v.folderKey.startsWith("/mnt/media_rw/") ||
        (v.folderKey.startsWith("/storage/") && !v.folderKey.startsWith("/storage/emulated/") && !v.folderKey.startsWith("/storage/self/"))
    private fun storageVideos(kind: String): List<VideoItem> = if (kind == "all") lastScan else lastScan.filter { isSd(it) == (kind == "sd") }   // (v530) all = كل الفيديوهات
    private fun perRow(): Int {
        val wdp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 68f
        return (wdp / 266f).toInt().coerceIn(2, 6)
    }

    /** (v493) كارت تخزين في الرئيسية: الفلاشة / الذاكرة الداخلية */
    private fun storageTile(icon: String, label: String, sub: String, kind: String): View {
        val t = TvTileView(this, 14)
        t.inner.setBackgroundColor(th.card)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        col.addView(ui.text(icon, 40f, Color.WHITE).apply { gravity = Gravity.CENTER })
        col.addView(ui.text(label, 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        col.addView(ui.text(sub, 11.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(6), ui.dp(2), ui.dp(6), 0) })
        t.inner.addView(col, FrameLayout.LayoutParams(-1, -1))
        t.setOnClickListener { if (!VideoScan.hasPermission(this)) askVideoPermission(); showStorage(kind) }
        t.contentDescription = label
        t.tag = "stor:$kind"
        t.layoutParams = LinearLayout.LayoutParams(ui.dp(246), ui.dp(143)).apply { setMargins(ui.dp(5), ui.dp(8), ui.dp(5), ui.dp(8)) }
        return t
    }

    private fun storageSub(kind: String): String {
        if (!VideoScan.hasPermission(this)) return "محتاج إذن الفيديوهات"
        if (!scanned) return "⏳ بقرا الفيديوهات…"
        val l = storageVideos(kind)
        if (l.isEmpty()) return if (kind == "sd") "مفيش فلاشة / مفيش فيديوهات" else "مفيش فيديوهات"
        return l.map { it.folderKey }.distinct().size.toString() + " فولدر · " + l.size + " فيديو"
    }

    /** (v525) الفيديوهات في الرئيسية = شريط واحد فيه فولدرات الفيديو من الفلاشة والذاكرة الداخلية (الفلاشة الأول)، تتنقل بينها بالأسهم */
    private fun fillStorageHome(fh: LinearLayout) {
        fh.removeAllViews()
        fh.addView(sectionTitle("🎬 الفيديوهات"))
        if (!VideoScan.hasPermission(this)) { fh.addView(hrow(listOf(tile("🔐", "اسمح بالفيديوهات") { askVideoPermission() }))); return }
        if (!scanned) { fh.addView(ui.text("⏳ بقرا الفيديوهات…", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(6)) }); return }
        val groups = VideoLib.sortFolders(VideoLib.group(lastScan), foldSort())
        if (groups.isEmpty()) { fh.addView(ui.text("مفيش فيديوهات على الفلاشة ولا على الذاكرة الداخلية", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(6)) }); return }
        val usb = groups.filter { g -> g.videos.firstOrNull()?.let { v -> isSd(v) } == true }
        val internal = groups.filter { g -> g.videos.firstOrNull()?.let { v -> isSd(v) } != true }
        val cards = (usb + internal).take(60).map { homeFolderTile(it) } + listOf(tile("📂", "كل الفيديوهات") { showVideosPage() })
        fh.addView(hrow(cards))
    }

    private fun homeFolderTile(f: FolderItem): View {
        val sd = f.videos.firstOrNull()?.let { isSd(it) } == true
        return vTile({ iv -> f.videos.firstOrNull()?.let { v -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } } },
            "📁 " + f.name, (if (sd) "💾 فلاشة · " else "📱 داخلية · ") + f.videos.size.toString() + " فيديو", -1, false, { openFolderView(f) }, null, true)
    }

    private fun showStorage(kind: String) {
        storageKind = kind; openFolder = null; storageQuery = ""
        content.removeAllViews()
        val sv = host(); content.addView(sv)
        fillStorageView(sv, true)
        // (v550) كان بيفتح من غير فحص: لو الفحص ماخلصش بتفضل رسالة «بقرا الفيديوهات» ومفيش غير مربع البحث يتركّز عليه فمتعرفش تتحرك. دلوقتي بنفحص ونرجّع نملا الصفحة ونركّز على أول فولدر
        if (VideoScan.hasPermission(this) && !(scanned && System.currentTimeMillis() - lastScanAt < 20000L)) Thread {
            val items = try { VideoScan.scan(this) } catch (_: Exception) { null }
            if (items != null) runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                lastScan = items; scanned = true; lastScanAt = System.currentTimeMillis()
                if (storageKind == kind && storageQuery.isBlank()) foldersHost?.let { fillStorageView(it, true) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** (v493) كارت فولدر فيديو: 16:9 بصورة أول فيديو، والاسم تحته وهو متركّز */
    private fun folderCard(f: FolderItem, first: Boolean, fh: LinearLayout, mode: String = "grid"): View {
        val thumb: (ImageView) -> Unit = { iv -> f.videos.firstOrNull()?.let { v -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } } }
        val go: () -> Unit = { openFolder = f.key; storageQuery = ""; fillStorageView(fh, true) }
        val c = if (mode == "list") listRow(thumb, "📁 " + f.name, f.videos.size.toString() + " فيديو · " + (if (f.videos.firstOrNull()?.let { isSd(it) } == true) "💾 فلاشة" else "📱 داخلية") + " · " + VideoLib.fmtSize(f.videos.sumOf { it.size }), go)
                else vTile(thumb, "📁 " + f.name, f.videos.size.toString() + " فيديو", -1, false, go, null, true, wOf(mode))
        c.tag = if (first) "fold:first" else "fold:" + f.key
        return c
    }

    /** (v493 م3) جوه الفلاشة/الذاكرة: شريط بحث فوق، وتحته فولدرات الفيديو بس؛ ولما تدخل فولدر تظهر فيديوهاته */
    private fun fillStorageView(fh: LinearLayout, focusFirst: Boolean = false) {
        val kind = storageKind ?: return
        if (kind == "all" && openFolder == null && storageQuery.isBlank()) { fillVideosPage(fh, focusFirst); return }   // (v531) تصميم صفحة الفيديوهات الجديد
        foldersHost = fh
        fh.removeAllViews()
        val label = if (kind == "sd") "الفلاشة" else if (kind == "all") "الفيديوهات" else "الذاكرة الداخلية"
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(4)) }
        head.addView(pill("→ رجوع") { if (openFolder != null) closeFolder() else showHome(true, true) }.apply { setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)); tag = "stor:back" })
        val et = EditText(this).apply {
            setText(storageQuery); setSingleLine(); textSize = 16f; setTextColor(th.text); setHintTextColor(th.muted)
            hint = "🔍 ابحث في $label…"; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.box(th.surface, th.border, 22)
            setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.surface, if (f) Color.WHITE else th.border, 22, if (f) 3 else 1) }
        }
        head.addView(et, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(10), 0, ui.dp(10), 0) })
        head.addView(pill("⇅ ترتيب") { sortMenu(fh) }.apply { setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)) })
        head.addView(pill("▦ عرض") { viewMenu(fh) }.apply { setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)) })
        fh.addView(head, LinearLayout.LayoutParams(-1, -2))
        val body = host()
        fh.addView(body)
        fun msg(t: String) {
            body.addView(ui.text(t, 13f, th.muted).apply { setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(6)) })
            if (focusFirst) fh.post { try { fh.findViewWithTag<View>("stor:back")?.requestFocus() } catch (_: Throwable) {} }   // (v550) مفيش كروت: التركيز على «رجوع» مش على مربع البحث
        }
        fun render(focus: Boolean) {
            body.removeAllViews()
            if (!VideoScan.hasPermission(this)) { msg("محتاج إذن الوصول للفيديوهات — افتح «الفيديوهات» مرة واحدة وادّي الإذن"); return }
            if (!scanned) { msg("⏳ بقرا الفيديوهات…"); return }
            val vids0 = storageVideos(kind)
            if (vids0.isEmpty()) { msg(if (kind == "sd") "مفيش فلاشة متوصلة أو مفيش فيديوهات عليها" else "مفيش فيديوهات على الذاكرة الداخلية"); return }
            val q = storageQuery.trim()
            if (q.isNotEmpty()) {
                val hits = VideoLib.search(vids0, q)
                if (hits.isEmpty()) { msg("مفيش نتائج لـ «$q»"); return }
                val vm = vidView()
                val cards = hits.take(120).map { videoItemView(it, vm) }
                addCards(body, cards, vm)
                if (focus) body.post { try { cards.firstOrNull()?.requestFocus() } catch (_: Throwable) {} }
                return
            }
            val all = VideoLib.sortFolders(VideoLib.group(vids0), foldSort())
            val open = openFolder?.let { k -> all.firstOrNull { it.key == k } }
            if (open == null) {
                openFolder = null
                val fm = foldView()
                body.addView(ui.text("📁 " + all.size + " فولدر · 🎞 " + vids0.size + " فيديو · الترتيب: " + VideoLib.sortLabelOf(foldSort()) + " · العرض: " + (viewLabels[fm] ?: ""), 13f, th.muted).apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(2)) })
                val cards = all.take(300).mapIndexed { i, f -> folderCard(f, i == 0, fh, fm) }
                addCards(body, cards, fm)
                if (focus) body.post { try { cards.firstOrNull()?.requestFocus() } catch (_: Throwable) {} }
                return
            }
            // (v525) شريط فولدرات فوق: بدّل بين فولدرات الفلاشة والذاكرة الداخلية من غير ما ترجع للرئيسية
            val sibs = VideoLib.sortFolders(VideoLib.group(lastScan), foldSort())
            if (sibs.size > 1) body.addView(hrow(sibs.take(60).map { sf ->
                pill((if (sf.key == open.key) "● " else "") + (if (sf.videos.firstOrNull()?.let { v -> isSd(v) } == true) "💾 " else "📱 ") + sf.name) {
                    storageKind = if (kind == "all") "all" else if (sf.videos.firstOrNull()?.let { v -> isSd(v) } == true) "sd" else "int"
                    openFolder = sf.key; storageQuery = ""; fillStorageView(fh, true)
                }
            }))
            body.addView(ui.text("📁 ${open.name} · ${open.videos.size} فيديو · الترتيب: " + VideoLib.sortLabelOf(vidSort()), 16f, th.primary, true).apply { setPadding(ui.dp(10), ui.dp(8), 0, ui.dp(2)) })
            val vids = VideoLib.sortVideos(open.videos, vidSort())
            val vm = vidView()
            val vcards = vids.take(400).map { videoItemView(it, vm) }
            addCards(body, vcards, vm)
            if (focus) body.post { try { vcards.firstOrNull()?.requestFocus() } catch (_: Throwable) {} }
        }
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { storageQuery = e?.toString() ?: ""; render(false) }
        })
        render(focusFirst)
    }

    /** (v531) صفحة «الفيديوهات»: شريط أفقي لآخر الفيديوهات المشاهدة، وتحته الفولدرات مربعات صغيرة والاسم جنب كل مربع */
    private fun fillVideosPage(fh: LinearLayout, focus: Boolean) {
        foldersHost = fh
        fh.removeAllViews()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(2)) }
        head.addView(ui.text("🎬 الفيديوهات", 20f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(pill("⇅ ترتيب") { sortMenu(fh) }.apply { setPadding(ui.dp(14), ui.dp(9), ui.dp(14), ui.dp(9)) })
        fh.addView(head, LinearLayout.LayoutParams(-1, -2))
        if (!VideoScan.hasPermission(this)) { fh.addView(hrow(listOf(tile("🔐", "اسمح بالفيديوهات") { askVideoPermission() }))); return }
        val hh = host(); fh.addView(hh); fillHistory(hh)
        if (!scanned) { fh.addView(ui.text("⏳ بقرا الفيديوهات…", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(6)) }); return }
        val groups = VideoLib.sortFolders(VideoLib.group(lastScan), foldSort())
        if (groups.isEmpty()) { fh.addView(ui.text("مفيش فيديوهات على الفلاشة ولا على الذاكرة الداخلية", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(6)) }); return }
        fh.addView(sectionTitle("📁 الفولدرات (" + groups.size + ")"))
        val dm = resources.displayMetrics
        val perRow = ((dm.widthPixels / dm.density - 68f) / 230f).toInt().coerceIn(1, 5)
        val cards = groups.take(120).mapIndexed { i, f -> miniFolder(f, i == 0) }
        var k = 0
        while (k < cards.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; clipChildren = false }
            for (j in 0 until perRow) {
                val cc = cards.getOrNull(k + j)
                if (cc != null) row.addView(cc, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
                else row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
            fh.addView(row, LinearLayout.LayoutParams(-1, -2))
            k += perRow
        }
        if (focus) fh.post { try { firstFocusable(fh)?.requestFocus() } catch (_: Throwable) {} }
    }

    /** (v531) فولدر صغير: مربع مصغّر + الاسم وعدد الفيديوهات جنبه */
    private fun miniFolder(f: FolderItem, first: Boolean): View {
        val sd = f.videos.firstOrNull()?.let { isSd(it) } == true
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            isFocusable = true; isClickable = true; setPadding(ui.dp(8), ui.dp(8), ui.dp(12), ui.dp(8))
            background = ui.box(th.surface, th.border, 14)
            setOnClickListener { openFolderView(f) }
            setOnFocusChangeListener { v, fo -> v.background = ui.box(if (fo) th.card else th.surface, if (fo) th.primary else th.border, 14, if (fo) 3 else 1) }
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF000000.toInt())
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dp(10).toFloat()) } }
            clipToOutline = true
        }
        f.videos.firstOrNull()?.let { v -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } }
        c.addView(iv, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
        val tc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, 0, 0) }
        tc.addView(ui.text(f.name, 14f, th.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        tc.addView(ui.text(f.videos.size.toString() + " فيديو · " + (if (sd) "💾" else "📱"), 11f, th.muted).apply { maxLines = 1 })
        c.addView(tc, LinearLayout.LayoutParams(0, -2, 1f))
        c.tag = if (first) "fold:first" else "fold:" + f.key
        return c
    }

    private fun sortMenu(fh: LinearLayout) {
        fun lab(k: String) = VideoLib.sortLabelOf(k)
        val items = arrayOf("📁 ترتيب الفولدرات: " + lab(foldSort()), "🎞 ترتيب الفيديوهات جوه الفولدر: " + lab(vidSort()))
        GAlert(this).setTitle("⋮ الترتيب").setItems(items) { _, which ->
            val key = if (which == 0) "tv_fold_sort" else "tv_vid_sort"
            val cur = if (which == 0) foldSort() else vidSort()
            val labels = VideoLib.sortLabels.map { (if (it.first == cur) "✓ " else "") + it.second }.toTypedArray()
            GAlert(this).setTitle(if (which == 0) "📁 رتّب الفولدرات حسب" else "🎞 رتّب الفيديوهات جوه حسب")
                .setItems(labels) { _, i -> Cfg.put(key, VideoLib.sortLabels[i].first); fillStorageView(fh, true) }
                .setNegativeButton("إلغاء", null).show()
        }.setNegativeButton("إغلاق", null).show()
    }

    private fun viewMenu(fh: LinearLayout) {
        val items = arrayOf("📁 عرض الفولدرات: " + (viewLabels[foldView()] ?: ""), "🎞 عرض الفيديوهات جوه الفولدر: " + (viewLabels[vidView()] ?: ""))
        GAlert(this).setTitle("▦ شكل العرض").setItems(items) { _, which ->
            val key = if (which == 0) "tv_fold_view" else "tv_vid_view"
            val cur = if (which == 0) foldView() else vidView()
            val keys = viewLabels.keys.toList()
            val labels = keys.map { (if (it == cur) "✓ " else "") + (viewLabels[it] ?: it) }.toTypedArray()
            GAlert(this).setTitle(if (which == 0) "📁 شكل الفولدرات" else "🎞 شكل الفيديوهات")
                .setItems(labels) { _, i -> Cfg.put(key, keys[i]); fillStorageView(fh, true) }
                .setNegativeButton("إلغاء", null).show()
        }.setNegativeButton("إغلاق", null).show()
    }

    /** (v530) صفحة «الفيديوهات»: كل فولدرات الفيديو (الفلاشة + الداخلية) مترتبة بنفس شكل الرئيسية — جوه الفولدر فيديوهاته، وبتبدّل الشكل والترتيب */
    private fun showVideosPage(focus: Boolean = true) {
        h.removeCallbacks(searchRun)
        muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
        storageKind = "all"; openFolder = null; storageQuery = ""; setTab(1)
        content.removeAllViews(); heroBtn = null; heroImg = null
        val sv = host(); content.addView(sv)
        fillStorageView(sv, focus)
        if (VideoScan.hasPermission(this) && !(scanned && System.currentTimeMillis() - lastScanAt < 20000L)) Thread {   // فحص جديد (مش لو لسه فاحصين من أقل من 20ث — التنقّل بين التابات يفضل سلس)
            val items = try { VideoScan.scan(this) } catch (_: Exception) { null }
            if (items != null) runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val changed = !scanned || items.size != lastScan.size
                lastScan = items; scanned = true; lastScanAt = System.currentTimeMillis()
                if (changed && storageKind == "all") foldersHost?.let { fillStorageView(it) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** (v530) صف يوتيوب في الرئيسية: زرار «افتح يوتيوب» دايمًا أول كارت + أحدث فيديوهات اشتراكاتك + «كل الاشتراكات».
     *  لو مفيش اشتراكات/تسجيل دخول: كارت تسجيل الدخول وكارت بحث يوتيوب بدل ما الصف يختفي. اختار من الصف بالأسهم وOK. */
    private fun fillYt(yh: LinearLayout) {
        fun head(extra: List<View>) {
            yh.removeAllViews()
            yh.addView(sectionTitle("▶ يوتيوب"))
            yh.addView(hrow(extra))
        }
        head(listOf(tile("▶", "افتح يوتيوب") { openMain("youtube") }, tile("🔍", "ابحث في يوتيوب") { showSearch() }))
        Thread {
            val subs = try { YtStore.read(YtStore.SUBS) } catch (e: Throwable) { LogStore.err("TvHome:ytsubs", e); emptyList() }
            val items = try {
                if (subs.isEmpty()) emptyList() else (YtRss.cachedFeed(subs) ?: YtRss.feed(subs, false, true)).items.filter { it.kind == 0 }.take(30)
            } catch (e: Throwable) { LogStore.err("TvHome:yt", e); emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (items.isEmpty()) {
                    head(listOf(tile("▶", "افتح يوتيوب") { openMain("youtube") }, tile("🔍", "ابحث في يوتيوب") { showSearch() },
                        tile("👤", if (YtAccount.loggedIn()) "زامن اشتراكاتك" else "سجّل دخول يوتيوب") { openMain("youtube") }))
                    return@runOnUiThread
                }
                head(listOf(tile("▶", "افتح يوتيوب") { openMain("youtube") }, tile("🔍", "ابحث في يوتيوب") { showSearch() }) +
                    items.map { ytCard(it) } + listOf(tile("📺", "كل الاشتراكات") { openMain("youtube") }))
                yh.addView(ui.text("أحدث فيديوهات اشتراكاتك — اختار بالأسهم", 12f, th.muted).apply { setPadding(ui.dp(12), ui.dp(2), 0, 0) })
            }
        }.apply { isDaemon = true }.start()
    }

    private fun ytCard(v: NpItem): View = vTile({ iv ->
            if (v.thumb.isNotBlank()) try { com.bumptech.glide.Glide.with(this).load(v.thumb).centerCrop().into(iv) } catch (_: Throwable) { }
        }, v.title, v.channel + if (v.age.isNotBlank()) " · " + v.age else "", -1, false, { openYt(v.id) })

    private fun openYt(id: String) {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("force_phone", true); putExtra("from_tv", true); putExtra("tab", "youtube"); putExtra("yt_open", id)
            })
        } catch (e: Throwable) { LogStore.err("TvHome:ytopen", e); Notice.show(this, "⚠ ما قدرتش أفتح الفيديو", 2000L) }
    }

    /** (v495) صف لكل فولدر ضفته من التخصيص: فيديوهاته بشريط عرضي زي «آخر المشغّل» و«اشتراكات يوتيوب» */
    private fun fillPinned(ph: LinearLayout) {
        ph.removeAllViews()
        val pins = pinnedFolders()
        if (pins.isEmpty() || !scanned) return
        val groups = VideoLib.group(lastScan).associateBy { it.key }
        for (k in pins) {
            val f = groups[k] ?: continue
            ph.addView(sectionTitle("📁 " + f.name))
            val vids = VideoLib.sortVideos(f.videos, vidSort()).take(30)
            val cards = vids.map { videoCard(it) } + listOf(tile("📂", "كل الفولدر (" + f.videos.size + ")") { openFolderView(f) })
            ph.addView(hrow(cards))
        }
    }

    // (v551) كاش الرئيسية: الرجوع من الفيديوهات/التطبيقات/الفلاشة كان بيبني الصفوف كلها من الأول (يوتيوب بكروتين وبعدين النتايج، فحص جديد، مصغّرات async) فبتحصل نتشة. دلوقتي بنرجّع نفس الـViews المبنية من غير ما نتلمس
    private var homeCache: List<View>? = null
    private var homeCacheAt = 0L
    private var homeCacheSig = ""

    private fun showHome(focus: Boolean = true, reuse: Boolean = false) {
        if (reuse && !inSearch) {
            val cache = homeCache
            if (cache != null && System.currentTimeMillis() - homeCacheAt < 600_000L) {
                h.removeCallbacks(searchRun)
                muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
                content.removeAllViews(); heroBtn = null; heroImg = null; setTab(0); prayerCd = null
                storageKind = null; openFolder = null; foldersHost = null
                for (v in cache) { (v.parent as? ViewGroup)?.removeView(v); content.addView(v) }
                // السجل بس بيتحدّث لو اتغيّر (فيديو اتشغّل)
                val sig = recentsSig()
                if (sig != homeCacheSig) { homeCacheSig = sig; histHost?.let { fillHistory(it) } }
                if (focus) (heroBtn ?: firstRail)?.let { f -> content.post { try { f.requestFocus() } catch (_: Exception) {} } }
                return
            }
        }
        h.removeCallbacks(searchRun); warmApps()
        muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
        content.removeAllViews(); heroBtn = null; heroImg = null; setTab(0); prayerCd = null
        storageKind = null; openFolder = null; foldersHost = null
        try { YtAccount.init(this); YtRss.attach(this) } catch (_: Throwable) { }
        val hidden = Cfg.str("tv_rows_hide2", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // 1) (v519) شريط التطبيقات اتنقل لآخر الصفحة (قبل أزرار الإعدادات والتخصيص) — شوف الكتلة قبل «6) تخصيص»
        // 2) آخر المشغّل  3) فولدراتك المضافة  4) الفلاشة/الذاكرة  5) اشتراكات يوتيوب (بتتملي أول ما الفحص يخلص)
        val hh = host(); val ph = host(); val fh = host(); val yh = host(); val ah = host()
        histHost = hh
        if ("hist" !in hidden) fillHistory(hh)
        if ("yt" !in hidden) fillYt(yh)   // (v530) يوتيوب فوق: صف الاشتراكات ظاهر دايمًا
        if ("vids" !in hidden) fillStorageHome(fh)
        // (v538) ترتيب الصفوف من «↕ ترتيب الصفوف» (tv_rows_order) — كان ثابت
        val rowHosts = mapOf("hist" to hh, "yt" to yh, "pin" to ph, "vids" to fh, "apps" to ah)
        for (id in rowsOrder()) {
            val v = rowHosts[id] ?: continue
            if ((id == "hist" || id == "yt" || id == "vids") && id in hidden) continue
            content.addView(v)
        }
        if (VideoScan.hasPermission(this) && scanned && System.currentTimeMillis() - lastScanAt < 20000L) {   // (v531) الفحص طازة: املا الصفوف على طول من غير فحص جديد
            fillPinned(ph)
        } else if (VideoScan.hasPermission(this)) Thread {
            val items = try { VideoScan.scan(this) } catch (_: Exception) { emptyList() }
            lastScan = items; scanned = true; lastScanAt = System.currentTimeMillis()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if ("hist" !in hidden) fillHistory(hh)   // دلوقتي نعرف نجيب مصغّرات السجل
                fillPinned(ph)
                if ("vids" !in hidden) fillStorageHome(fh)
                if (storageKind != null) foldersHost?.let { fillStorageView(it) }   // (v493) لو دخلت الفلاشة قبل ما الفحص يخلص
                if (inSearch) runSearch(searchEt?.text?.toString() ?: "")   // (v495) لو بتدوّر والفحص خلّص
            }
        }.start()
        // 5.5) (v519) شريط تطبيقات الأندرويد: آخر حاجة تحت، قبل الإعدادات والتخصيص — أيقونة + اسم ظاهرين دايمًا
        run {   // (v521) يوتيوب والمتصفح دايمًا أول أيقونتين (حتى لو صف التطبيقات مخفي)
            val circles = ArrayList<View>(builtinApps())
            if ("apps" !in hidden) circles.addAll(essentialApps().map { appCircle(it) })   // (v496) دواير على نمط Google TV
            val px = ui.dp(iconDp())
            circles.add(dockItem(builtinIcon("➕", 0xFF3A3F55.toInt(), px), "أضف تطبيق", 0xFF3A3F55.toInt(), { pickApps() }))   // (v538) إضافة تطبيق للرئيسية من آخر الصف
            ah.addView(sectionTitle("تطبيقاتك"))
            ah.addView(hrow(circles))
        }
        // (v525) صف «تطبيقات قد تحتاجها» (التطبيقات الأكتر شيوعًا اللي مش متثبتة وبأيقونة حرف) اتشال بالكامل
        // 6) تخصيص
        // (v512) صف الإعدادات فوق زرار التخصيص
        content.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(18), 0, 0)
            addView(pill("⚙️ إعدادات البرنامج") { guardPin { openMain("settings") } })
            addView(pill("⋯ المزيد") { moreMenu() })
            addView(pill("🛠 إعدادات الأندرويد") { openSystemSettings() })
            addView(pill("🌙 نوم") { showSaver() })
            addView(pill("📶 الواي فاي") { TvWifi.quick(this@TvActivity) })   // (v525) (v536) دوسة = تشغيل/إيقاف
        })
        if (!compactUi) content.addView(LinearLayout(this).apply {   // (v530) رجوع v525: على الشاشة الواسعة مفيش ريل جانبي — نفس الأزرار هنا
            gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(8), 0, 0)
            addView(pill("🕌 مواقيت الصلاة") { showPrayer() })
            if (playOn()) addView(pill("🛍 Google Play") { openPlay() })
            addView(pill("🎨 المظهر") { showAppearance() })
        })
        content.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, ui.dp(12), 0, ui.dp(24)); addView(pill("🎛 تخصيص") { rowsDialog() }); addView(pill("↕ ترتيب الصفوف") { orderDialog() }); addView(pill("➕ أضف تطبيق") { pickApps() }) })   // (v538)
        homeCache = (0 until content.childCount).map { content.getChildAt(it) }; homeCacheAt = System.currentTimeMillis()
        homeCacheSig = recentsSig()
        if (focus) (heroBtn ?: firstRail)?.let { f -> content.post { try { f.requestFocus() } catch (_: Exception) {} } }
    }

    /** (v496) تاب «تطبيقات»: كل التطبيقات (بما فيها السيستم) دواير في شبكة */
    private fun showApps(focus: Boolean = true) {
        h.removeCallbacks(searchRun)
        muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
        storageKind = null; openFolder = null; foldersHost = null; setTab(2)
        content.removeAllViews()
        content.addView(sectionTitle("📱 كل التطبيقات"))
        val apps = builtinApps() + allLaunchApps().sortedBy { lbl(it).lowercase() }.map { appCircle(it) }   // (v521) يوتيوب + المتصفح أول اتنين
        val wdp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 68f
        gridInto(content, apps, (wdp / (iconDp() + 56f)).toInt().coerceIn(3, 10))
        if (focus) apps.firstOrNull()?.let { f -> content.post { try { f.requestFocus() } catch (_: Throwable) {} } }
    }

    // ===== (v495) بحث موحّد جوه الرئيسية: بيتكتب في الشريط العلوي والنتايج بتظهر مكان الصفوف =====
    private fun showSearch(prefill: String = "") {
        val et = searchEt ?: return
        if (prefill.isNotEmpty()) { et.setText(prefill); et.setSelection(et.text.length) }
        try { et.requestFocus() } catch (_: Throwable) { }
    }

    private fun onSearchText(q: String) {
        if (muteSearch) return
        h.removeCallbacks(searchRun)
        if (q.isBlank()) { if (inSearch) showHome(false); return }   // مسحت الكلمة = رجوع للرئيسية من غير ما التركيز يسيب الشريط
        h.postDelayed(searchRun, 260L)
    }

    private fun openFolderView(f: FolderItem) {
        muteSearch = true; searchEt?.setText(""); muteSearch = false; inSearch = false
        storageKind = "all"; openFolder = f.key; storageQuery = ""; setTab(1)   // (v530)
        content.removeAllViews()
        val sv = host(); content.addView(sv)
        fillStorageView(sv, true)
    }

    private fun folderTile(f: FolderItem): View = vTile({ iv -> f.videos.firstOrNull()?.let { v -> Thumbs.load(this, v) { bmp -> if (bmp != null) runOnUiThread { iv.setImageBitmap(bmp) } } } },
        "📁 " + f.name, f.videos.size.toString() + " فيديو", -1, false, { openFolderView(f) }, null, true)

    /** (v531) أول لينك http(s) في نص البحث (null لو مفيش) */
    private fun searchUrl(t: String): String? = Regex("https?://\\S+").find(t.trim())?.value?.trimEnd('.', ',', ')', '،')
    private fun openSearchLink(u: String) {
        try { Links.open(this, u) } catch (e: Throwable) { LogStore.err("TvHome:searchLink", e); Notice.show(this, "⚠ ما قدرتش أفتح اللينك", 2200L) }
    }

    private fun runSearch(q: String) {
        val ql = q.trim()
        if (ql.isEmpty()) return
        inSearch = true; storageKind = null; openFolder = null; foldersHost = null
        content.removeAllViews()
        searchUrl(ql)?.let { lu ->   // (v531) نص البحث فيه لينك: كارت «افتح اللينك» أول حاجة (من غير ما تدوّر عليه كبحث)
            content.addView(sectionTitle("🔗 لينك"))
            content.addView(hrow(listOf(card(300, 92, { c ->
                c.addView(ui.text("▶ افتح اللينك", 16f, th.text, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
                c.addView(ui.text(lu.take(60), 11f, th.muted).apply { gravity = Gravity.CENTER; maxLines = 1; layoutDirection = View.LAYOUT_DIRECTION_LTR }, LinearLayout.LayoutParams(-1, -2))
            }, { openSearchLink(lu) }))))
        }
        val low = ql.lowercase()
        val recHits = recents().filter { it.title.lowercase().contains(low) || VideoLib.nameMatches(it.title, ql) }   // (v521)
        val libHits = VideoLib.search(lastScan, ql)
        val folders = VideoLib.group(lastScan).filter { it.name.lowercase().contains(low) || VideoLib.nameMatches(it.name, ql) }   // (v521) تقريبي
        val byUri = lastScan.associateBy { it.uri }
        var any = false
        if (folders.isNotEmpty()) { any = true; content.addView(sectionTitle("📁 فولدرات")); content.addView(hrow(folders.take(20).map { folderTile(it) })) }
        if (recHits.isNotEmpty()) { any = true; content.addView(sectionTitle("🕘 من آخر المشاهدة")); content.addView(hrow(recHits.take(20).map { histCard(it, byUri) })) }
        if (libHits.isNotEmpty()) { any = true; content.addView(sectionTitle("🎬 من مكتبتك")); content.addView(hrow(libHits.take(30).map { videoCard(it) })) }
        // (v497) لو مفيش نتايج محلية: يبحث في يوتيوب نفسه ويعرض النتايج هنا في نفس صفحة البحث (من غير ما يفتح يوتيوب البرنامج)
        val yh = host()
        if (!any) {
            // (v533) الفحص لسه شغّال: ما نستناهوش — يوتيوب بيدوّر بالتوازي (والبحث بيتعاد لوحده لما الفحص يخلص)
            if (!scanned && VideoScan.hasPermission(this)) content.addView(ui.text("⏳ بقرا الفيديوهات… وبدوّر في يوتيوب لـ «$ql»", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(16), ui.dp(10), ui.dp(0)) })
            else content.addView(ui.text("مفيش نتايج محلية لـ «$ql» — بدوّر في يوتيوب…", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(16), ui.dp(10), ui.dp(0)) })
            content.addView(yh); ytSearchLater(ql, yh)
            addBrowserRow(ql)   // (v521) «ابحث في المتصفح» آخر حاجة تحت (كان فوق يوتيوب)
        } else {
            // (v530) فيه نتايج محلية: نتايج يوتيوب بتظهر تحتها لوحدها (من غير دوسة)
            content.addView(yh)
            ytSearchLater(ql, yh)
            addBrowserRow(ql)   // (v521) آخر حاجة تحت
        }
    }

    /** (v498) صف «ابحث في المتصفح»: كارت بيفتح متصفح البرنامج على نتيجة بحث بنفس الاسم (محرك البحث من إعدادات المتصفح) */
    private fun addBrowserRow(q: String) {
        content.addView(sectionTitle("🌐 على المتصفح"))
        val bh = host()
        bh.addView(hrow(listOf(card(252, 92, { c ->
            c.addView(ui.text("ابحث عن «$q» في المتصفح", 14f, th.text, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -1))
        }, { openBrowserSearch(q) }))))
        content.addView(bh)
    }

    private fun openBrowserSearch(q: String) {
        val base = when (getSharedPreferences("p", 0).getString("br_engine", "Google")) {
            "DuckDuckGo" -> "https://duckduckgo.com/?q="
            "Bing" -> "https://www.bing.com/search?q="
            else -> "https://www.google.com/search?q="
        }
        try { startActivity(Intent(this, BrowserActivity::class.java).putExtra("from_tv", true).putExtra("start", base + Uri.encode(q)).putExtra("noauto", true)) }
        catch (e: Throwable) { LogStore.err("TvHome:browserSearch", e) }
    }

    private var ytRun: Runnable? = null
    /** (v530) بحث يوتيوب بعد 600ms من آخر كتابة عشان كل حرف ما يعملش طلب نت */
    private fun ytSearchLater(q: String, yh: LinearLayout) {
        ytRun?.let { h.removeCallbacks(it) }
        yh.removeAllViews()
        yh.addView(ui.text("⏳ بجهّز نتايج يوتيوب لـ «$q»…", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(6)) })
        val r = Runnable { if (!isDestroyed && inSearch) ytSearch(q, yh) }
        ytRun = r; h.postDelayed(r, 600L)
    }
    private var ytSeq = 0
    /** (v497) بحث يوتيوب (NewPipe) وعرض النتايج في نفس الصفحة؛ النتيجة القديمة بتتجاهل لو الكلمة اتغيّرت */
    private fun ytSearch(q: String, yh: LinearLayout) {
        val my = ++ytSeq
        yh.removeAllViews()
        yh.addView(ui.text("⏳ بدوّر في يوتيوب عن «$q»…", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(6)) })
        Thread {
            val items = try { YtNp.searchFiltered(q, "videos", 0, 0).items.filter { it.kind == 0 }.take(24) } catch (e: Throwable) { LogStore.err("TvHome:ytsearch", e); null }
            runOnUiThread {
                if (isDestroyed || my != ytSeq || !inSearch) return@runOnUiThread
                yh.removeAllViews()
                if (items == null) { yh.addView(ui.text("⚠ مقدرتش أبحث في يوتيوب — اتأكد من النت", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(6)) }); return@runOnUiThread }
                if (items.isEmpty()) { yh.addView(ui.text("مفيش نتايج على يوتيوب لـ «$q»", 14f, th.muted).apply { setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(6)) }); return@runOnUiThread }
                yh.addView(sectionTitle("▶ نتايج يوتيوب لـ «$q»"))
                yh.addView(hrow(items.map { ytCard(it) }))   // (v521) شريط أفقي تتنقل فيه بالأسهم بدل شبكة طويلة
            }
        }.start()
    }

    private var voiceCb: ((String) -> Unit)? = null
    private var pendingVoice: ((String) -> Unit)? = null
    private var sr: android.speech.SpeechRecognizer? = null
    private fun startVoice(cb: (String) -> Unit) {
        voiceCb = cb
        try {
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "قول اسم الفيديو…")
            }
            startActivityForResult(i, 77)
        } catch (e: Exception) { LogStore.err("Tv:voice", e); startVoiceInApp(cb) }   // (v500) أغلب التلفزيونات مفيهاش شاشة RecognizerIntent: نستعمل خدمة التعرّف مباشرة
    }

    /** (v500) تعرّف على الصوت من جوه البرنامج (SpeechRecognizer) لما شاشة النظام مش موجودة */
    private fun startVoiceInApp(cb: (String) -> Unit) {
        try {
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) { Notice.show(this, "⚠ مفيش خدمة تعرّف على الصوت على الجهاز ده (محتاج تطبيق Google)", 3200L); return }
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                pendingVoice = cb; requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 91); return
            }
            try { sr?.destroy() } catch (_: Throwable) {}
            val r = android.speech.SpeechRecognizer.createSpeechRecognizer(this); sr = r
            r.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { Notice.show(this@TvActivity, "🎤 اتكلّم دلوقتي…", 3000L) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}
                override fun onError(e: Int) {
                    Notice.show(this@TvActivity, if (e == android.speech.SpeechRecognizer.ERROR_NO_MATCH || e == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "⚠ ما سمعتش حاجة — جرّب تاني" else "⚠ البحث الصوتي فشل (كود $e)", 2600L)
                }
                override fun onResults(b: Bundle?) {
                    val q = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                    if (!q.isNullOrEmpty() && !isDestroyed) runOnUiThread { cb(q) }
                }
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            })
        } catch (e: Throwable) { LogStore.err("Tv:voiceIn", e); Notice.show(this, "⚠ البحث الصوتي مش متاح على الجهاز ده", 2600L) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 77 && res == RESULT_OK) {
            val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            voiceCb?.invoke(q); voiceCb = null
        }
    }

    private fun phoneTypeDialog(apply: (String) -> Unit) {
        val url = TypeServer.url()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8)) }
        col.addView(ui.text("امسح الكود بكاميرا موبايلك (أو افتح العنوان في المتصفح — لازم نفس الواي فاي) واكتب هناك:", 13f, th.text))
        if (url.isNotEmpty()) {
            val qr = QrCode.bitmap(url, ui.dp(190))
            if (qr != null) {
                val iv = ImageView(this).apply { setImageBitmap(qr); setBackgroundColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
                col.addView(iv, LinearLayout.LayoutParams(ui.dp(190), ui.dp(190)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(8) })
            }
        }
        col.addView(ui.text(if (url.isEmpty()) "⚠ مش متصل بواي فاي" else url, 17f, th.primary, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(10)) })
        val got = ui.text(if (TypeServer.lastText.isEmpty()) "لسه ماوصلش حاجة…" else "وصل: " + TypeServer.lastText, 13f, th.muted)
        col.addView(got)
        val dlg = GAlert(this).setTitle("📱 اكتب من الموبايل").setView(col)
            .setPositiveButton("استخدم اللي وصل") { _, _ -> if (TypeServer.lastText.isNotEmpty()) apply(TypeServer.lastText) }
            .setNegativeButton("إغلاق", null).create()
        dlg.show()
        val poll = object : Runnable { override fun run() { if (!dlg.isShowing) return; got.text = if (TypeServer.lastText.isEmpty()) "لسه ماوصلش حاجة…" else "وصل: " + TypeServer.lastText; h.postDelayed(this, 1200) } }
        h.postDelayed(poll, 1200)
    }

    // ===== تخصيص الصفوف وقفل PIN =====
    // ===== (v538) ترتيب صفوف الرئيسية =====
    private val ROW_IDS = listOf("hist", "yt", "pin", "vids", "apps")
    private fun rowsOrder(): List<String> {
        val saved = Cfg.str("tv_rows_order", "").split(",").map { it.trim() }.filter { it in ROW_IDS }.distinct()
        return saved + ROW_IDS.filter { it !in saved }
    }
    private fun rowName(id: String): String = when (id) {
        "hist" -> "🕘 آخر الفيديوهات المشغّلة"; "yt" -> "▶ اشتراكات يوتيوب"; "pin" -> "📁 فولدراتك المثبّتة"
        "vids" -> "🎬 الفيديوهات (الفلاشة + الذاكرة)"; else -> "📲 تطبيقاتك"
    }
    private fun orderDialog() {
        val ord = rowsOrder()
        GAlert(this).setTitle("↕ ترتيب الرئيسية — دوس على صف تحرّكه")
            .setItems(ord.mapIndexed { i, id -> (i + 1).toString() + ". " + rowName(id) }.toTypedArray()) { _, i -> moveRow(ord, i) }
            .setNeutralButton("↺ الترتيب الأصلي") { _, _ -> Cfg.put("tv_rows_order", ""); showHome() }
            .setNegativeButton("تمام") { _, _ -> showHome() }.show()
    }
    private fun moveRow(ord: List<String>, i: Int) {
        val id = ord[i]
        val names = ArrayList<String>(); val tos = ArrayList<Int>()
        if (i > 0) { names.add("⏫ أول صف"); tos.add(0); names.add("⬆ فوق خطوة"); tos.add(i - 1) }
        if (i < ord.size - 1) { names.add("⬇ تحت خطوة"); tos.add(i + 1); names.add("⏬ آخر صف"); tos.add(ord.size - 1) }
        GAlert(this).setTitle(rowName(id)).setItems(names.toTypedArray()) { _, k ->
            val m = ord.toMutableList(); m.removeAt(i); m.add(tos[k], id)
            Cfg.put("tv_rows_order", m.joinToString(","))
            orderDialog()
        }.setNegativeButton("رجوع") { _, _ -> orderDialog() }.show()
    }

    private fun rowsDialog() {
        val ids = listOf("apps" to "📲 دواير التطبيقات", "hist" to "🕘 آخر الفيديوهات المشغّلة", "vids" to "🎬 الفيديوهات (الفلاشة + الذاكرة الداخلية)", "yt" to "▶ اشتراكات يوتيوب", "sys" to "⚙ إظهار تطبيقات السيستم كمان")
        val hidden = Cfg.str("tv_rows_hide2", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        var sysOn = Cfg.str("tv_apps_sys", "0") == "1"
        val labels = ids.map { it.second }.toTypedArray()
        val checked = BooleanArray(ids.size) { if (ids[it].first == "sys") sysOn else ids[it].first !in hidden }
        GAlert(this).setTitle("🎛 تخصيص الشاشة الرئيسية")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val k = ids[which].first
                if (k == "sys") sysOn = isChecked else if (isChecked) hidden.remove(k) else hidden.add(k)
            }
            .setPositiveButton("تمام") { _, _ -> Cfg.put("tv_rows_hide2", hidden.joinToString(",")); Cfg.put("tv_apps_sys", if (sysOn) "1" else "0"); showHome() }
            .setNeutralButton("➕ أضف تطبيق / فولدر") { _, _ ->
                Cfg.put("tv_rows_hide2", hidden.joinToString(",")); Cfg.put("tv_apps_sys", if (sysOn) "1" else "0"); addMenu()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    /** (v495) إضافة أي تطبيق أو أي فولدر فيديو للرئيسية */
    private fun addMenu() {
        val items = arrayOf("📲 أضف / شيل تطبيقات (" + pinnedApps().size + ")", "📁 أضف / شيل فولدرات فيديو (" + pinnedFolders().size + ")", "↕ رتّب صفوف الرئيسية")
        GAlert(this).setTitle("➕ إضافة للرئيسية").setItems(items) { _, which -> if (which == 0) pickApps() else if (which == 1) pickFolders() else orderDialog() }
            .setNegativeButton("إغلاق") { _, _ -> showHome() }.show()
    }

    private fun pickApps() {
        val pm = packageManager
        val all = allLaunchApps().sortedBy { it.loadLabel(pm).toString().lowercase() }
        if (all.isEmpty()) { Notice.show(this, "⚠ مفيش تطبيقات متاحة", 1800L); showHome(); return }
        val pins = pinnedApps().toMutableList()
        val labels = all.map { it.loadLabel(pm).toString() }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].activityInfo.packageName in pins }
        GAlert(this).setTitle("📲 التطبيقات اللي تظهر في الرئيسية")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                val pkg = all[which].activityInfo.packageName
                if (on) { if (pkg !in pins) pins.add(pkg) } else pins.remove(pkg)
            }
            .setPositiveButton("تمام") { _, _ -> Cfg.put("tv_pin_apps", pins.joinToString("\n")); showHome() }
            .setNegativeButton("إلغاء") { _, _ -> showHome() }.show()
    }

    private fun pickFolders() {
        if (!VideoScan.hasPermission(this)) { Notice.show(this, "⚠ محتاج إذن الفيديوهات — افتح «الفيديوهات» مرة وادّي الإذن", 2800L); showHome(); return }
        if (!scanned) { Notice.show(this, "⏳ لسه بقرا الفيديوهات — جرّب تاني بعد ثانيتين", 2400L); showHome(); return }
        val all = VideoLib.sortFolders(VideoLib.group(lastScan), VideoLib.SORT_NAME)
        if (all.isEmpty()) { Notice.show(this, "مفيش فولدرات فيها فيديوهات", 2000L); showHome(); return }
        val pins = pinnedFolders().toMutableList()
        val labels = all.map { "📁 " + it.name + " · " + it.videos.size + (if (it.path.isNotBlank()) "   (" + it.path + ")" else "") }.toTypedArray()
        val checked = BooleanArray(all.size) { all[it].key in pins }
        GAlert(this).setTitle("📁 فولدرات الفيديو اللي تظهر في الرئيسية")
            .setMultiChoiceItems(labels, checked) { _, which, on ->
                val k = all[which].key
                if (on) { if (k !in pins) pins.add(k) } else pins.remove(k)
            }
            .setPositiveButton("تمام") { _, _ -> Cfg.put("tv_pin_folders", pins.joinToString("\n")); showHome() }
            .setNegativeButton("إلغاء") { _, _ -> showHome() }.show()
    }

    private fun sha(s: String): String = try {
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (_: Exception) { s }

    private fun guardPin(next: () -> Unit) {
        if (Cfg.str("tv_pin", "").isEmpty()) { next(); return }
        numpad("🔒 اكتب الرقم السري") { pin -> if (sha(pin) == Cfg.str("tv_pin", "")) next() else Notice.show(this, "⚠ الرقم غلط", 1800L) }
    }

    private fun pinMenu() {
        if (Cfg.str("tv_pin", "").isEmpty()) {
            numpad("🔒 اختار رقم سري جديد (4 أرقام أو أكتر)") { p1 ->
                numpad("🔒 اكتبه تاني للتأكيد") { p2 ->
                    if (p1 == p2 && p1.length >= 4) { Cfg.put("tv_pin", sha(p1)); Notice.show(this, "🔒 القفل اتفعّل", 1800L) }
                    else Notice.show(this, "⚠ الرقمين مش زي بعض أو قصيرين", 2200L)
                }
            }
        } else {
            GAlert(this).setTitle("🔒 قفل TV").setMessage("القفل مفعّل على «الملفات» و«الإعدادات».")
                .setPositiveButton("إلغاء القفل") { _, _ -> guardPin { Cfg.put("tv_pin", ""); Notice.show(this, "🔓 القفل اتشال", 1600L) } }
                .setNegativeButton("سيبه", null).show()
        }
    }

    private fun numpad(title: String, done: (String) -> Unit) {
        var pin = ""
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(10)) }
        val dots = ui.text("○ ○ ○ ○", 22f, th.primary, true).apply { gravity = Gravity.CENTER }
        col.addView(dots)
        fun paintDots() { dots.text = if (pin.isEmpty()) "○ ○ ○ ○" else pin.map { "●" }.joinToString(" ") }
        lateinit var dlg: android.app.Dialog
        fun press(d: String) {
            when (d) {
                "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                "✓" -> { if (pin.isNotEmpty()) { dlg.dismiss(); done(pin) } ; return }
                else -> if (pin.length < 8) pin += d
            }
            paintDots()
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✓")
        for (r in 0 until 4) {
            val rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_LTR }
            for (k in 0 until 3) {
                val key = keys[r * 3 + k]
                val b = ui.text(key, 19f, th.text, true).apply {
                    gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                    background = ui.box(th.surface, th.border, 12)
                    setOnClickListener { press(key) }
                    setOnFocusChangeListener { v, f -> v.background = ui.box(if (f) th.card else th.surface, if (f) th.primary else th.border, 12) }
                }
                rowL.addView(b, LinearLayout.LayoutParams(ui.dp(64), ui.dp(52)).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
            col.addView(rowL)
        }
        dlg = GAlert(this).setTitle(title).setView(col).setNegativeButton("إلغاء", null).create()
        dlg.show()
    }

    // ===== تشغيل وفتح الأقسام =====
    /** (v480 م6) المتصفح بمؤشر الريموت — Back منه بيرجّع للرئيسية */
    private fun openBrowser() {
        try { startActivity(Intent(this, BrowserActivity::class.java).putExtra("from_tv", true)) } catch (e: Throwable) { LogStore.err("TvHome:browser", e) }
    }

    private fun openMain(tab: String?) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("force_phone", true)
            putExtra("from_tv", true)   // (v467) عشان رجوع Main من الزيارة دي يرجّع لواجهة التلفزيون
            if (tab != null) putExtra("tab", tab)
        })
        try { overridePendingTransition(0, 0) } catch (_: Throwable) { }   // (v516) من غير انتقال «تطبيق جديد» — يوتيوب بقى جزء من اللانشر
    }

    private fun playFile(uriStr: String, landscape: Boolean) {
        try {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                data = Uri.parse(uriStr); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra("landscape", landscape); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
            })
        } catch (e: Exception) { LogStore.err("Tv:play", e); Notice.show(this, "⚠ ما قدرتش أشغّل الملف ده", 2200L) }
    }

    private fun playRecent(r: Recent) {
        if (r.uri.isNotEmpty()) playFile(r.uri, true)
        else if (r.url.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)).setClass(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("force_phone", true))
    }
}
