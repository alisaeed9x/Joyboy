package com.vanilla.tvlauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * الرئيسية + ريل + مكدّس رجوع (شاشات فرعية + مشغّل + سكون) + ثيمات/إعدادات.
 * P4: المكتبة (مجلدات/فيديوهات/فرز/استبعاد/صور مصغّرة) متوصّلة بـ LibraryStore.
 * P5: السجل + متابعة المشاهدة + المشغّل الداخلي (PlayerLayer).
 * P6: البحث الموحّد (فيديوهات/مجلدات/سجل) + الأخيرة + تكامل خارجي اختياري (ExternalSearch).
 * لسه مؤقتة: يوتيوب (P7–8) · التطبيقات (P9).
 */
class HomeActivity : Activity() {
    private class Screen(val id: String, val icon: String, val label: String, val phase: Int)
    private val screens = listOf(
        Screen("home", "🏠", "الرئيسية", 2), Screen("library", "🎬", "الفيديوهات", 4), Screen("history", "🕘", "السجل", 5),
        Screen("search", "🔍", "البحث", 6), Screen("youtube", "▶", "يوتيوب", 7), Screen("apps", "🧩", "التطبيقات", 9),
        Screen("settings", "⚙", "الإعدادات", 9)
    )

    private val RAIL_MOVABLE = listOf("library", "history", "search", "youtube", "apps")   // الرئيسية أول واحدة والإعدادات آخر واحدة دايمًا
    private fun railOrder(): List<String> = listOf("home") + HomePrefs.order(Cfg.str("rail_order", ""), RAIL_MOVABLE) + listOf("settings")
    private val ROWS = listOf("cont" to "▶ متابعة المشاهدة", "lib" to "🎬 الفيديوهات", "more" to "🔍 وأكتر")
    private val router = BackRouter()
    private val h = Handler(Looper.getMainLooper())
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private lateinit var contentHost: FrameLayout
    private lateinit var saver: FrameLayout
    private lateinit var saverClock: TextView
    private lateinit var saverDate: TextView
    private lateinit var saverBox: LinearLayout
    private lateinit var clockTv: TextView
    private lateinit var player: PlayerLayer
    private val rail = LinkedHashMap<String, View>()
    private val stack = ArrayList<String>().apply { add("home") }   // مداخل: "home" · "library" · "folder:<key>" · "history" · ...
    private val focusMem = HashMap<String, Int>()                   // مدخل → ترتيب العنصر المركَّز لما فتحنا شاشة فوقه (يتستعاد بعد Back)
    private val pageMem = HashMap<String, Int>()                    // مدخل → عدد الصفحات المعروضة (القوائم الطويلة)
    private var scanTv: TextView? = null
    private var focusBeforePlay = -1
    private var histConfirm = false
    private var idleSec = 0
    private var wokeAt = 0L
    private val PAGE = 60
    private val REQ_PERM = 41
    private val REQ_VOICE = 42
    private val REQ_EXPORT = 43
    private val REQ_IMPORT = 44
    private val REQ_SUB = 45
    private var restoreConfirm = false          // استعادة النسخة = ضغطتين (بتستبدل الإعدادات والسجل)
    private var searchQuery = ""                 // نص البحث الحالي (بيفضل بعد الرجوع من المشغّل/المجلد)
    private var searchBox: LinearLayout? = null   // حاوية النتائج بس (بتتعاد رسمها من غير ما التركيز يسيب خانة الكتابة)
    private var searchInput: EditText? = null
    private var searchRun: Runnable? = null
    // يوتيوب داخل التطبيق (P8، D-013): الحالة بتفضل في الـ Activity عشان تتستعاد بعد الرجوع من المشغّل
    private var ytQuery = ""
    private var ytItems: List<YtItem> = emptyList()
    private var ytRes: YtRes? = null
    private var ytLoading = false
    private var ytError: String? = null
    private var ytGen = 0                       // بيلغي نتيجة بحث قديمة لو اتبدّل/اتمسح
    private var ytPending: String? = null       // استعلام جاي من شاشة البحث (بعد ضغطة صريحة)
    private var ytBox: LinearLayout? = null
    private var ytInput: EditText? = null
    @Volatile private var destroyed = false      // P11: نتايج الخيوط المتأخرة بعد onDestroy تتجاهل

    private fun baseId(entry: String) = entry.substringBefore(':')
    private fun excluded(): Set<String> = HomePrefs.hiddenSet(Cfg.str("lib_excl", ""))
    private fun sortMode(): String = Cfg.str("lib_sort", LibraryLogic.SORT_NAME).let { if (it in LibraryLogic.SORTS) it else LibraryLogic.SORT_NAME }
    private fun sortLabel(s: String) = when (s) { LibraryLogic.SORT_DATE -> "الأحدث"; LibraryLogic.SORT_PLACE -> "المكان"; else -> "الاسم" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this); HistoryStore.load(this); LibraryStore.loadCache(this)
        buildUi()
        show("home", null)
        // ترتيب التسجيل = من الأسفل للأعلى: الشاشة، ثم المشغّل، ثم السكون (السكون فوق الكل)
        router.register("screen") { screenBack() }
        router.register("player") { playerBack() }
        router.register("saver") { saverBack() }
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = android.window.OnBackInvokedCallback { dispatchBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
        if (LibraryStore.hasPermission(this)) { startScan(); LibraryStore.watch(this) { startScan() } }
        h.post(tick)
    }

    override fun onDestroy() {
        destroyed = true
        LibraryStore.unwatch(this); h.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()   // P11: يحفظ الموضع ويحرّر ExoPlayer لو الـ Activity اتقفلت أثناء التشغيل
        super.onDestroy()
    }

    /** P11: زر Home وإحنا شغّالين (singleTask) = ارجع للرئيسية (يقفل المشغّل بعد حفظ الموضع) — سلوك Launcher المعتاد */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            hideIme()
            if (saver.visibility == View.VISIBLE) wake()
            if (::player.isInitialized && player.active) player.close()
            navigate("home")
        }
    }
    override fun onPause() { if (::player.isInitialized) player.onPause(); super.onPause() }
    override fun onUserInteraction() { super.onUserInteraction(); wake() }

    /** المشغّل بياخد مفاتيح التحكم فقط. BACK عمره ما بيعدّي هنا (بيروح للـ router). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (::player.isInitialized && player.active && e.keyCode != KeyEvent.KEYCODE_BACK) {
            if (saver.visibility == View.VISIBLE) { wake(); return true }   // أول ضغطة تصحّي السكون بس
            idleSec = 0
            if (player.handleKey(e)) return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_PERM) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) { startScan(); LibraryStore.watch(this) { startScan() } }
            else Toast.makeText(this, "من غير إذن الفيديو المكتبة مش هتظهر", Toast.LENGTH_LONG).show()
            refreshCurrent()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_SUB) { if (::player.isInitialized) player.onSubPicked(if (res == RESULT_OK) data else null); return }
        if (req == REQ_EXPORT && res == RESULT_OK) { data?.data?.let { exportTo(it) }; return }
        if (req == REQ_IMPORT && res == RESULT_OK) { data?.data?.let { importFrom(it) }; return }
        if (req == REQ_VOICE && res == RESULT_OK) {
            val t = ExternalSearch.voiceText(data) ?: return
            if (stack.last() == "search") applyQuery(t) else if (stack.last() == "youtube") runYt(t)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { dispatchBack() }

    private fun dispatchBack() {
        if (router.dispatch() == null) rail["home"]?.requestFocus()   // الجذر: مفيش خروج، بس رجّع التركيز
    }

    // ===== طبقات الرجوع =====
    private fun saverBack(): Boolean {
        if (saver.visibility == View.VISIBLE) { wake(); return true }
        return SystemClock.uptimeMillis() - wokeAt < 600L   // Back جه مع ضغطة صحّت السكون → يتبلع
    }
    private fun playerBack(): Boolean {
        if (saver.visibility == View.VISIBLE) return false    // السكون أعلى → يصحّى الأول
        if (!player.active) return false
        player.onBack(); return true                           // لوحة الإعدادات/بطاقة «التالي» تتقفل الأول، وبعدها يحفظ الموضع ويرجّع للشاشة
    }
    private fun screenBack(): Boolean {
        if (stack.last() == "search" && SearchLogic.backStep(searchQuery) == SearchLogic.BACK_CLEAR) {   // DESIGN_NAV: نص البحث يتمسح الأول
            searchQuery = ""; hideIme(); show("search", null); return true
        }
        if (stack.last() == "youtube" && ytQuery.isNotBlank()) {   // Back = خطوة واحدة: نتايج يوتيوب تتمسح الأول
            ytClear(); hideIme(); show("youtube", null); return true
        }
        if (stack.size <= 1) return false
        val from = stack.removeAt(stack.size - 1)
        val to = stack.last()
        histConfirm = false
        show(to, baseId(from), focusMem[to] ?: -1)   // يرجع لنفس العنصر اللي فتح الشاشة اللي خرجنا منها
        return true
    }

    // ===== السكون =====
    private val tick = object : Runnable {
        override fun run() {
            val t = SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
            val min = Cfg.int("saver_min", 5)
            if (saver.visibility == View.VISIBLE) {
                // P10: ورا السكون مفيش داعي نحدّث ساعة الشريط؛ وساعة السكون بتتحدّث لما الدقيقة تتغيّر بس
                if (saverClock.text.toString() != t) { saverClock.text = t; saverDate.text = SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date()) }
                if (SaverLogic.shouldMove(idleSec)) moveSaverClock()
                idleSec++
            } else {
                clockTv.text = t
                val playing = player.active && player.playing
                if (playing) idleSec = 0                      // فيديو شغّال: السكون ما يقاطعوش
                else { idleSec++; if (SaverLogic.shouldShow(idleSec, min, false)) showSaver() }
            }
            h.postDelayed(this, 1000)
        }
    }
    private fun showSaver() {
        val st = Cfg.int("saver_style", SaverLogic.STYLE_CLOCK_DATE)
        saverClock.visibility = if (st == SaverLogic.STYLE_BLACK) View.GONE else View.VISIBLE
        saverDate.visibility = if (st == SaverLogic.STYLE_CLOCK_DATE) View.VISIBLE else View.GONE
        saverClock.text = ""      // يتملّى في أول tick
        saver.visibility = View.VISIBLE; idleSec = 0; moveSaverClock()
    }
    private fun wake() {
        idleSec = 0
        if (::saver.isInitialized && saver.visibility == View.VISIBLE) { saver.visibility = View.GONE; wokeAt = SystemClock.uptimeMillis() }
    }
    private fun moveSaverClock() {
        val w = saver.width; val hh = saver.height
        if (w <= 0 || hh <= 0) return
        saverBox.translationX = SaverLogic.offset(w, Random.nextInt()).toFloat()
        saverBox.translationY = SaverLogic.offset(hh, Random.nextInt()).toFloat()
    }

    // ===== بناء الواجهة =====
    private fun buildUi() {
        // P15 (D-018): الثيم + لون رئيسي مخصّص + خلفية مخصّصة (سادة/متدرّجة) + خط + اسم ظاهر — كله بيتحفظ
        th = Appearance.apply(Themes.byId(Cfg.str("theme", "mx")), Cfg.int("cust_accent", 0), Cfg.int("cust_bg", 0))
        ui = Ui(this, th, Cfg.int("motion", 2), Cfg.int("font_pct", 100) / 100f, Cfg.bool("nav_sound", false), Appearance.fontFamily(Cfg.int("cust_font", 0)))
        val cbg = Appearance.bgOf(Cfg.int("cust_bg", 0))
        val root = FrameLayout(this).apply {
            if (cbg != null && cbg.gradient) background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(cbg.top, cbg.bottom)) else setBackgroundColor(th.bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(28), ui.dp(14), ui.dp(28), ui.dp(10))
        }
        bar.addView(ui.text(Appearance.displayName(Cfg.str("disp_name", "")), 26f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        clockTv = ui.text("", 20f, th.text, true); bar.addView(clockTv)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val railCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); setBackgroundColor(th.card) }
        rail.clear()
        for (sid in railOrder()) {
            val s = screens.first { it.id == sid }
            if (s.id == "apps" && !Cfg.bool("apps_on", false)) continue   // P9: صفحة اختيارية — مخفية من الريل افتراضيًا
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)) }
            item.addView(ui.text(s.icon, 26f, th.text).apply { gravity = Gravity.CENTER })
            item.addView(ui.text(s.label, 14f, th.text, true).apply { gravity = Gravity.CENTER })
            ui.focusable(item, 12) { navigate(s.id) }
            railCol.addView(item, LinearLayout.LayoutParams(ui.dp(110), -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
            rail[s.id] = item
        }
        body.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(railCol) }, LinearLayout.LayoutParams(-2, -1))
        contentHost = FrameLayout(this)
        body.addView(contentHost, LinearLayout.LayoutParams(0, -1, 1f))
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // المشغّل فوق الشاشات وتحت السكون
        player = PlayerLayer(this, ui, th) { onPlayerClosed() }
        root.addView(player.root, FrameLayout.LayoutParams(-1, -1))

        saver = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); visibility = View.GONE }
        // ألوان خافتة (سطوع قليل) + حركة كل دقيقة = تقليل حرق الشاشة
        saverClock = ui.text("", 56f, 0xFF8A8A8A.toInt(), true).apply { gravity = Gravity.CENTER }
        saverDate = ui.text("", 22f, 0xFF6E6E6E.toInt()).apply { gravity = Gravity.CENTER }
        saverBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        saverBox.addView(saverClock); saverBox.addView(saverDate)
        saver.addView(saverBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(saver, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    // ===== التنقل =====
    private fun navigate(id: String) {
        if (stack.last() == id) return
        if (id == "search") searchQuery = ""
        val i = stack.indexOf(id)
        if (id == "home") { stack.clear(); stack.add("home") }
        else if (i >= 0) { while (stack.size > i + 1) stack.removeAt(stack.size - 1) }
        else stack.add(id)
        histConfirm = false
        show(id, null)
    }

    /** فتح شاشة فرعية فوق الحالية، مع تسجيل العنصر المركَّز عشان Back يرجّعه */
    private fun push(entry: String) {
        focusMem[stack.last()] = indexOfFocused()
        stack.add(entry); histConfirm = false
        show(entry, null)
    }

    private fun refreshCurrent() { if (::player.isInitialized && player.active) return; show(stack.last(), null, indexOfFocused()) }

    private fun show(entry: String, focusKey: String?, restoreIdx: Int = -1) {
        if (baseId(entry) != "settings") restoreConfirm = false
        scanTv = null; searchBox = null; searchInput = null; ytBox = null; ytInput = null
        searchRun?.let { h.removeCallbacks(it) }
        contentHost.removeAllViews()
        val id = baseId(entry)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(28), ui.dp(12), ui.dp(28), ui.dp(24)) }
        val s = screens.first { it.id == id }
        if (id != "search" && id != "youtube") hideIme()
        if (id != "library" || entry == "library") col.addView(ui.text(s.icon + "  " + s.label, 26f, th.primary, true))
        when (id) {
            "home" -> buildHome(col)
            "library" -> if (entry == "library") buildLibrary(col) else buildFolder(col, entry, entry.substringAfter("folder:"))
            "history" -> buildHistory(col)
            "settings" -> buildSettings(col)
            "search" -> buildSearch(col)
            "youtube" -> buildYoutube(col)
            "apps" -> buildApps(col)
            else -> {
                col.addView(ui.text("القسم ده لسه ما اتنفّذش — بيتنفّذ في المرحلة ${s.phase}.", 18f, th.text).apply { setPadding(0, ui.dp(16), 0, 0) })
                col.addView(ui.text("الرجوع (Back) بيرجّعك للشاشة اللي قبل.", 15f, th.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
            }
        }
        contentHost.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) })
        contentHost.post {
            val items = ArrayList<View>(); collectFocusables(col, items)
            val target = if (restoreIdx >= 0) items.getOrNull(restoreIdx) else null
            val sub = entry.contains(':')
            (target ?: (if (sub) items.firstOrNull() else null) ?: (if (id == "search") searchInput else if (id == "youtube") ytInput else null) ?: rail[focusKey ?: id] ?: rail[id] ?: rail["home"])?.requestFocus()
        }
    }

    private fun collectFocusables(v: View, out: MutableList<View>) {
        if (v.isFocusable && v !is ScrollView) out.add(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) collectFocusables(v.getChildAt(i), out)
    }

    /** ترتيب العنصر المركَّز بين عناصر المحتوى (-1 لو التركيز في الريل) */
    private fun indexOfFocused(): Int {
        val f = currentFocus ?: return -1
        val host = contentHost.getChildAt(0) ?: return -1
        val items = ArrayList<View>(); collectFocusables(host, items)
        return items.indexOf(f)
    }

    // ===== مكوّنات مشتركة =====
    private fun button(col: LinearLayout, label: String, onClick: () -> Unit): View {
        val b = ui.text(label, 17f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12)) }
        ui.focusable(b, 12, onClick)
        col.addView(b, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        return b
    }
    private fun sectionTitle(col: LinearLayout, s: String) = col.addView(ui.text(s, 18f, th.text, true).apply { setPadding(0, ui.dp(20), 0, ui.dp(6)) })
    private fun note(col: LinearLayout, s: String, muted: Boolean = false) =
        col.addView(ui.text(s, 17f, if (muted) th.muted else th.text).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })

    /** صف أزرار أفقي (للشريط العلوي في المكتبة) */
    private fun actionRow(col: LinearLayout, vararg acts: Pair<String, () -> Unit>) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(10), 0, ui.dp(6)) }
        for ((label, fn) in acts) {
            val b = ui.text(label, 16f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
            ui.focusable(b, 12, fn)
            r.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
    }

    private fun cols(cardDp: Int): Int {
        val contentPx = resources.displayMetrics.widthPixels - ui.dp(110 + 16 + 56)
        return maxOf(2, contentPx / ui.dp(cardDp + 16))
    }
    private fun grid(col: LinearLayout, cards: List<View>, n: Int, wDp: Int, hDp: Int) {
        var i = 0
        while (i < cards.size) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in cards.subList(i, minOf(i + n, cards.size)))
                r.addView(c, LinearLayout.LayoutParams(ui.dp(wDp), ui.dp(hDp)).apply { setMargins(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) })
            col.addView(r); i += n
        }
    }

    private fun textCard(title: String, sub: String, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14)) }
        c.addView(ui.text(title, 17f, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub.isNotEmpty()) c.addView(ui.text(sub, 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) })
        return ui.focusable(c, 14, onClick)
    }

    private fun videoCard(v: VideoItem, sub: String, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.card) }
        c.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(108)))
        Thumbs.load(this, v, ui.dp(210), ui.dp(108)) { bmp -> if (bmp != null) iv.setImageBitmap(bmp) }   // تحميل تدريجي في الخلفية
        c.addView(ui.text(v.name, 14f, th.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ui.dp(4), 0, 0) })
        if (sub.isNotEmpty()) c.addView(ui.text(sub, 12f, th.muted))
        return ui.focusable(c, 14, onClick)
    }

    // ===== المكتبة (P4) =====
    private fun startScan() { LibraryStore.scan(this) { onLibUpdate() } }

    /** تقدّم جزئي = تحديث نص الحالة فقط؛ النتيجة النهائية = إعادة رسم بنفس التركيز */
    private fun onLibUpdate() {
        if (destroyed) return
        if (LibraryStore.scanning) { scanTv?.text = "جارٍ الفحص… ${LibraryStore.scanned} فيديو"; return }
        when (stack.last()) {
            // P11: أثناء الكتابة نعيد رسم النتايج بس (الكيبورد والتركيز يفضلوا)؛ لو التركيز على نتيجة نعيد بناء الشاشة باستعادة التركيز
            "search" -> if (currentFocus === searchInput && !(::player.isInitialized && player.active)) renderSearchResults() else refreshCurrent()
            "youtube" -> { }   // يوتيوب مش معتمد على المكتبة: لا إعادة رسم
            else -> refreshCurrent()
        }
    }

    private fun requestPerm() {
        try { requestPermissions(arrayOf(LibraryStore.permission()), REQ_PERM) }
        catch (_: Throwable) { Toast.makeText(this, "تعذّر طلب الإذن — فعّله من إعدادات التطبيق", Toast.LENGTH_LONG).show() }
    }

    /** true = المكتبة جاهزة للعرض. غير كده بيرسم حالة (إذن/تحميل/فاضي/خطأ) ويرجّع false */
    private fun libraryGate(col: LinearLayout): Boolean {
        if (!LibraryStore.hasPermission(this)) {
            note(col, "محتاج إذن قراءة الفيديوهات عشان نعرض مكتبتك. الإذن للفيديو بس.")
            button(col, "🔓 منح إذن الفيديو") { requestPerm() }
            return false
        }
        val vis = LibraryLogic.visible(LibraryStore.items, excluded())
        if (vis.isEmpty()) {
            if (LibraryStore.scanning) { scanTv = ui.text("جارٍ الفحص… ${LibraryStore.scanned} فيديو", 17f, th.muted); col.addView(scanTv.also { it!!.setPadding(0, ui.dp(14), 0, 0) }) }
            else {
                LibraryStore.error?.let { if (it != "no_permission") note(col, "حصل خطأ في الفحص: $it", true) }
                note(col, if (LibraryStore.items.isEmpty()) "مفيش فيديوهات على الجهاز (أو USB مش متوصّل/ما اتفهرسش)." else "كل المجلدات مستبعدة — رجّعها من الإعدادات.")
                button(col, "🔄 إعادة الفحص") { startScan(); refreshCurrent() }
            }
            return false
        }
        return true
    }

    private fun scanStatus(col: LinearLayout) {
        val t = ui.text(if (LibraryStore.scanning) "جارٍ الفحص… ${LibraryStore.scanned} فيديو" else "", 14f, th.muted)
        scanTv = t; col.addView(t)
    }

    private fun cycleSort() {
        val s = sortMode(); Cfg.put("lib_sort", LibraryLogic.SORTS[(LibraryLogic.SORTS.indexOf(s) + 1) % LibraryLogic.SORTS.size])
        refreshCurrent()
    }

    private fun buildLibrary(col: LinearLayout) {
        if (!libraryGate(col)) return
        val excl = excluded(); val items = LibraryStore.items
        val sort = sortMode()
        actionRow(col, "↕ الترتيب: ${sortLabel(sort)}" to { cycleSort() }, "🔄 إعادة الفحص" to { startScan(); refreshCurrent() })
        scanStatus(col)
        val vis = LibraryLogic.visible(items, excl)
        val cards = ArrayList<View>()
        cards.add(textCard("كل الفيديوهات", "${vis.size} فيديو") { push("folder:" + LibraryLogic.ALL) })
        for (f in LibraryLogic.sortFolders(LibraryLogic.folders(items, excl), sort))
            cards.add(textCard(f.name, "${f.count} فيديو") { push("folder:" + f.key) })
        grid(col, cards, cols(200), 200, 100)
    }

    private fun buildFolder(col: LinearLayout, entry: String, key: String) {
        val items = LibraryStore.items; val excl = excluded()
        val name = if (key == LibraryLogic.ALL) "كل الفيديوهات" else items.firstOrNull { it.folderKey == key }?.folderName ?: "مجلد"
        col.addView(ui.text("🎬  $name", 26f, th.primary, true))
        val list = LibraryLogic.sortItems(LibraryLogic.inFolder(items, excl, key), sortMode())
        if (list.isEmpty()) {
            note(col, "المجلد ده فاضي دلوقتي (اتمسح أو اتنقل أو مستبعد).")
            button(col, "↩ رجوع للمجلدات") { screenBack() }
            return
        }
        val acts = ArrayList<Pair<String, () -> Unit>>()
        acts.add("↕ الترتيب: ${sortLabel(sortMode())}" to { cycleSort() })
        if (key != LibraryLogic.ALL) acts.add("🚫 استبعاد المجلد ده" to {
            Cfg.put("lib_excl", HomePrefs.toggle(Cfg.str("lib_excl", ""), key))
            Toast.makeText(this, "اتستبعد — ترجّعه من الإعدادات", Toast.LENGTH_SHORT).show()
            screenBack()
        })
        actionRow(col, *acts.toTypedArray())
        scanStatus(col)
        val shown = (pageMem[entry] ?: 1) * PAGE
        val cards = ArrayList<View>()
        for (i in 0 until minOf(shown, list.size)) {
            val v = list[i]
            cards.add(videoCard(v, LibraryLogic.formatDur(v.durMs)) { playList(list, i) })
        }
        grid(col, cards, cols(210), 210, 170)
        if (list.size > shown) {
            val more = textCard("عرض المزيد", "${list.size - shown} فيديو كمان") { pageMem[entry] = (pageMem[entry] ?: 1) + 1; refreshCurrent() }
            col.addView(more, LinearLayout.LayoutParams(ui.dp(210), ui.dp(90)).apply { setMargins(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(6)) })
        }
    }

    // ===== التشغيل (P5) =====
    private fun playList(list: List<VideoItem>, index: Int) {
        focusBeforePlay = indexOfFocused()
        player.open(PlayQueue(list, index))
    }

    private var histWarned = false
    private fun onPlayerClosed() {
        if (HistoryStore.writeFailed && !histWarned) { histWarned = true; Toast.makeText(this, "مش قادر أحفظ سجل المشاهدة — التخزين ممتلئ أو غير متاح؟", Toast.LENGTH_LONG).show() }
        // السجل اتغيّر → أعد رسم الشاشة الحالية (متابعة المشاهدة/السجل) ورجّع التركيز لنفس العنصر
        show(stack.last(), null, focusBeforePlay)
    }

    /** تشغيل من السجل/متابعة المشاهدة: القايمة = فيديوهات نفس المجلد مرتبة بالاسم (السابق/التالي هنا مش من السجل) */
    private fun playEntry(e: WatchEntry) {
        val items = LibraryStore.items
        val v = HistoryLogic.find(e, items)
        if (v == null) {
            Toast.makeText(this, if (items.isEmpty()) "المكتبة لسه ما اتفحصتش" else "الملف ده اتنقل أو اتمسح", Toast.LENGTH_LONG).show(); return
        }
        val ctx = LibraryLogic.sortItems(LibraryLogic.inFolder(items, excluded(), v.folderKey), LibraryLogic.SORT_NAME).let { if (it.any { x -> x.id == v.id }) it else listOf(v) }
        playList(ctx, ctx.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
    }

    // ===== الرئيسية =====
    private fun buildHome(col: LinearLayout) {
        fun row(title: String, cards: List<View>, w: Int = 230, hgt: Int = 110) {
            col.addView(ui.text(title, 19f, th.text, true).apply { setPadding(0, ui.dp(18), 0, ui.dp(6)) })
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in cards) r.addView(c, LinearLayout.LayoutParams(ui.dp(w), ui.dp(hgt)).apply { setMargins(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6)) })
            col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) })
        }
        val hidden = HomePrefs.hiddenSet(Cfg.str("rows_hide", ""))
        val items = LibraryStore.items
        val hasLib = LibraryStore.hasPermission(this) && LibraryLogic.visible(items, excluded()).isNotEmpty()

        fun rowCont() { if ("cont" !in hidden) {
            val cont = HistoryLogic.continueList(HistoryStore.entries, 12).mapNotNull { e -> HistoryLogic.find(e, items)?.let { e to it } }
            if (cont.isEmpty()) row("▶ متابعة المشاهدة", listOf(textCard("مفيش حاجة للمتابعة", "أي فيديو توقف في نصه هيظهر هنا") { navigate("library") }))
            else row("▶ متابعة المشاهدة", cont.map { (e, v) ->
                val pct = if (e.durMs > 0) (e.posMs * 100 / e.durMs).toInt() else 0
                videoCard(v, "$pct% · ${LibraryLogic.formatDur(e.posMs)}") { playEntry(e) }
            }, 210, 170)
        } }
        fun rowLib() { if ("lib" !in hidden) {
            if (!LibraryStore.hasPermission(this)) row("🎬 الفيديوهات", listOf(textCard("منح إذن الفيديو", "عشان تظهر مكتبتك") { navigate("library") }))
            else if (!hasLib) row("🎬 الفيديوهات", listOf(textCard(if (LibraryStore.scanning) "جارٍ الفحص…" else "مفيش فيديوهات", "افتح المكتبة") { navigate("library") }))
            else {
                val recent = LibraryLogic.sortItems(LibraryLogic.visible(items, excluded()), LibraryLogic.SORT_DATE).take(10)
                val vis = LibraryLogic.visible(items, excluded())
                row("🎬 الفيديوهات", listOf(textCard("المكتبة والمجلدات", "${vis.size} فيديو") { navigate("library") }) +
                    recent.mapIndexed { i, v -> videoCard(v, LibraryLogic.formatDur(v.durMs)) { playList(recent, i) } }, 210, 170)
            }
        } }
        fun appsCard(): List<View> = if (Cfg.bool("apps_on", false)) listOf(textCard("التطبيقات", "تطبيقات التلفزيون") { navigate("apps") }) else emptyList()
        fun rowMore() { if ("more" !in hidden) row("🔍 وأكتر", listOf(textCard("البحث", "فيديوهات · مجلدات · سجل") { navigate("search") }, textCard("يوتيوب", "بحث وتشغيل (محتاج نت)") { navigate("youtube") }) + appsCard()) }
        for (id in HomePrefs.order(Cfg.str("rows_order", ""), ROWS.map { it.first })) when (id) { "cont" -> rowCont(); "lib" -> rowLib(); "more" -> rowMore() }
        if (ROWS.all { it.first in hidden }) col.addView(ui.text("كل الصفوف مخفية — فعّلها من الإعدادات.", 17f, th.muted).apply { setPadding(0, ui.dp(20), 0, 0) })
    }

    // ===== السجل (P5) =====
    private fun buildHistory(col: LinearLayout) {
        val list = HistoryStore.entries
        if (list.isEmpty()) { note(col, "السجل فاضي. أي فيديو تتفرج عليه (أكتر من 5 ثواني) هيظهر هنا."); return }
        button(col, if (histConfirm) "⚠ اضغط OK تاني لتأكيد مسح السجل كله" else "🗑 مسح السجل كله") {
            if (!histConfirm) { histConfirm = true; refreshCurrent() }
            else { histConfirm = false; HistoryStore.clear(this); refreshCurrent() }
        }
        val items = LibraryStore.items
        val shown = (pageMem["history"] ?: 1) * 50
        for (e in list.take(shown)) {
            val missing = items.isNotEmpty() && HistoryLogic.find(e, items) == null
            val pct = if (e.completed) "اكتمل" else if (e.durMs > 0) "${e.posMs * 100 / e.durMs}% · ${LibraryLogic.formatDur(e.posMs)}" else LibraryLogic.formatDur(e.posMs)
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)) }
            main.addView(ui.text(e.name, 16f, if (missing) th.muted else th.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            main.addView(ui.text((if (missing) "⚠ غير موجود (اتنقل أو اتمسح) · " else "") + pct, 13f, th.muted))
            ui.focusable(main, 12) { playEntry(e) }
            r.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
            val del = ui.text("✕", 20f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
            ui.focusable(del, 12) { HistoryStore.remove(this, e.key); refreshCurrent() }
            r.addView(del, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, 0, 0) })
            col.addView(r, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        if (list.size > shown) button(col, "عرض المزيد (${list.size - shown})") { pageMem["history"] = (pageMem["history"] ?: 1) + 1; refreshCurrent() }
    }

    // ===== البحث الموحّد (P6) =====
    private fun recents(): List<String> = SearchLogic.decodeRecents(Cfg.str("search_recent", ""))
    private fun saveRecents(l: List<String>) = Cfg.put("search_recent", SearchLogic.encodeRecents(l))
    /** بيسجّل الاستعلام في «الأخيرة» — بس عند تأكيد البحث أو فتح نتيجة (مش مع كل حرف). */
    private fun rememberQuery() { saveRecents(SearchLogic.addRecent(recents(), searchQuery)) }

    private fun imm() = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
    private fun showIme(v: View) { try { v.requestFocus(); imm().showSoftInput(v, InputMethodManager.SHOW_IMPLICIT) } catch (_: Throwable) { } }
    private fun hideIme() { try { imm().hideSoftInputFromWindow(contentHost.windowToken, 0) } catch (_: Throwable) { } }

    private fun scheduleSearch() {
        searchRun?.let { h.removeCallbacks(it) }
        val r = Runnable { renderSearchResults() }; searchRun = r; h.postDelayed(r, 220L)
    }

    /** يطبّق استعلام جاهز (من الأخيرة أو الصوت): يملأ الخانة، يرسم النتايج فورًا، ويركّز على أول نتيجة. */
    private fun applyQuery(q: String) {
        searchQuery = q; pageMem["search"] = 1
        searchInput?.let { it.setText(q); it.setSelection(it.text.length) }
        searchRun?.let { h.removeCallbacks(it) }
        renderSearchResults(); focusFirstResult()
    }

    private fun focusFirstResult() {
        val b = searchBox ?: return
        val items = ArrayList<View>(); collectFocusables(b, items)
        items.firstOrNull()?.requestFocus()
    }

    private fun buildSearch(col: LinearLayout) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(10), 0, ui.dp(6)) }
        val et = EditText(this).apply {
            setText(searchQuery); setSelection(text.length)
            hint = "اكتب اسم فيديو أو مجلد…"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 22f * ui.fontScale
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true)
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
        }
        ui.focusable(et, 14) { showIme(et) }   // OK على الخانة = افتح الكيبورد
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString() ?: ""
                if (t != searchQuery) { searchQuery = t; pageMem["search"] = 1; scheduleSearch() }
            }
        })
        et.setOnEditorActionListener { _, actionId, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                rememberQuery(); hideIme()
                searchRun?.let { h.removeCallbacks(it) }
                renderSearchResults(); focusFirstResult(); true
            } else false
        }
        searchInput = et
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = ui.text("🎤", 24f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
        ui.focusable(mic, 14) { if (!ExternalSearch.startVoice(this, REQ_VOICE)) Toast.makeText(this, "البحث الصوتي مش متاح على التلفزيون ده — اكتب بالكيبورد", Toast.LENGTH_LONG).show() }
        row.addView(mic, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, 0, 0) })
        val clr = ui.text("✕", 22f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
        ui.focusable(clr, 14) { applyQueryClear() }
        row.addView(clr, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, 0, 0) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchBox = box
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        renderSearchResults()
    }

    private fun applyQueryClear() {
        searchQuery = ""; pageMem["search"] = 1
        searchInput?.setText("")
        searchRun?.let { h.removeCallbacks(it) }
        renderSearchResults(); searchInput?.requestFocus()
    }

    private fun renderSearchResults() {
        val box = searchBox ?: return
        box.removeAllViews()
        val q = searchQuery.trim()
        if (q.isEmpty()) { renderRecents(box); return }
        val r = SearchLogic.search(LibraryStore.items, excluded(), HistoryStore.entries, q)
        if (LibraryStore.items.isEmpty() && !LibraryStore.hasPermission(this))
            box.addView(ui.text("المكتبة المحلية مش متاحة (محتاج إذن الفيديو) — البحث في السجل شغّال.", 15f, th.muted).apply { setPadding(0, ui.dp(10), 0, ui.dp(4)) })
        if (r.isEmpty) {
            box.addView(ui.text("مفيش نتيجة لـ «$q» في المكتبة ولا المجلدات ولا السجل.", 18f, th.text, true).apply { setPadding(0, ui.dp(18), 0, ui.dp(6)) })
        } else {
            if (r.videos.isNotEmpty()) {
                box.addView(ui.text("🎬 فيديوهات (${r.videos.size})", 18f, th.text, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
                val shown = (pageMem["search"] ?: 1) * PAGE
                val cards = ArrayList<View>()
                for (i in 0 until minOf(shown, r.videos.size)) {
                    val v = r.videos[i]
                    cards.add(videoCard(v, v.folderName) { rememberQuery(); playList(r.videos, i) })
                }
                grid(box, cards, cols(210), 210, 170)
                if (r.videos.size > shown) {
                    val more = textCard("عرض المزيد", "${r.videos.size - shown} فيديو كمان") { pageMem["search"] = (pageMem["search"] ?: 1) + 1; refreshCurrent() }
                    box.addView(more, LinearLayout.LayoutParams(ui.dp(210), ui.dp(90)).apply { setMargins(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(6)) })
                }
            }
            if (r.folders.isNotEmpty()) {
                box.addView(ui.text("📁 مجلدات (${r.folders.size})", 18f, th.text, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
                grid(box, r.folders.take(PAGE).map { f -> textCard(f.name, "${f.count} فيديو") { rememberQuery(); push("folder:" + f.key) } }, cols(210), 210, 110)
            }
            if (r.history.isNotEmpty()) {
                box.addView(ui.text("🕘 في سجل المشاهدة (${r.history.size})", 18f, th.text, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
                val items = LibraryStore.items
                for (e in r.history.take(30)) {
                    val missing = items.isNotEmpty() && HistoryLogic.find(e, items) == null
                    val pct = if (e.completed) "اكتمل" else if (e.durMs > 0) "${e.posMs * 100 / e.durMs}% · ${LibraryLogic.formatDur(e.posMs)}" else LibraryLogic.formatDur(e.posMs)
                    val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)) }
                    main.addView(ui.text(e.name, 16f, if (missing) th.muted else th.text, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    main.addView(ui.text((if (missing) "⚠ غير موجود (اتنقل أو اتمسح) · " else "") + pct, 13f, th.muted))
                    ui.focusable(main, 12) { rememberQuery(); playEntry(e) }
                    box.addView(main, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
                }
            }
        }
        if (Cfg.bool("search_yt", true)) {
            if (r.isEmpty) box.addView(ui.text("البحث على الإنترنت مش بيحصل لوحده — بيتفتح بس لو اخترته:", 14f, th.muted).apply { setPadding(0, ui.dp(6), 0, ui.dp(2)) })
            button(box, "🔎 البحث على يوتيوب عن «$q»") {
                rememberQuery(); ytPending = q; push("youtube")   // ضغطة صريحة بس: بيفتح شاشة يوتيوب الداخلية ويبحث (D-013)
            }
        }
    }

    private fun renderRecents(box: LinearLayout) {
        val rec = recents()
        if (rec.isEmpty()) {
            box.addView(ui.text("لسه ماعملتش أي بحث. اكتب اسم فيديو أو مجلد (OK على الخانة يفتح الكيبورد)، أو دوس 🎤 لو الجهاز بيدعم الصوت.", 16f, th.muted).apply { setPadding(0, ui.dp(16), 0, 0) })
            return
        }
        box.addView(ui.text("🕘 عمليات البحث الأخيرة", 18f, th.text, true).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
        for (q in rec) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val main = ui.text(q, 17f, th.text, true).apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            ui.focusable(main, 12) { applyQuery(q) }
            r.addView(main, LinearLayout.LayoutParams(0, -2, 1f))
            val del = ui.text("✕", 20f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
            ui.focusable(del, 12) { saveRecents(SearchLogic.removeRecent(recents(), q)); refreshCurrent() }
            r.addView(del, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, 0, 0) })
            box.addView(r, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, ui.dp(4), 0, ui.dp(4)) })
        }
        button(box, "🗑 مسح كل عمليات البحث الأخيرة") { saveRecents(emptyList()); refreshCurrent() }
    }

    // ===== يوتيوب داخل التطبيق (P8) =====
    private fun ytClear() { ++ytGen; ytQuery = ""; ytItems = emptyList(); ytRes = null; ytError = null; ytLoading = false }

    private fun focusYtAt(n: Int) {
        val b = ytBox ?: return
        val l = ArrayList<View>(); collectFocusables(b, l)
        (l.getOrNull(n) ?: l.lastOrNull())?.requestFocus()
    }

    /** بحث جديد (من الكيبورد/الصوت/شاشة البحث). الشبكة في خيط خلفي، والنتيجة بترجع على الـ Main وتتجاهل لو اتبدّل البحث. */
    private fun runYt(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        ytQuery = t; ytRes = null; ytItems = emptyList(); ytError = null; ytLoading = true
        ytInput?.let { it.setText(t); it.setSelection(it.text.length) }
        val g = ++ytGen
        renderYt()
        Thread {
            var r: YtRes? = null; var err: String? = null
            try { r = YtNp.searchFirst(t) } catch (e: Throwable) { err = YtLogic.friendlyError(e) }
            val rr = r; val ee = err
            h.post {
                if (destroyed || g != ytGen) return@post
                ytLoading = false; ytRes = rr; ytItems = rr?.items ?: emptyList(); ytError = ee
                if (stack.last() == "youtube") { renderYt(); focusYtAt(0) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun ytMore() {
        val r = ytRes ?: return
        if (ytLoading || !r.hasMore) return
        ytLoading = true; val g = ytGen; val q = ytQuery; val oldN = ytItems.size
        renderYt()
        Thread {
            var nr: YtRes? = null
            try { nr = YtNp.searchMore(q, r) } catch (_: Throwable) { }
            val res = nr
            h.post {
                if (destroyed || g != ytGen) return@post
                ytLoading = false
                if (res != null) { ytItems = YtLogic.mergeById(ytItems, res.items); ytRes = res }
                else Toast.makeText(this, "تعذّر تحميل المزيد — اتأكد من النت", Toast.LENGTH_SHORT).show()
                if (stack.last() == "youtube") { renderYt(); focusYtAt(oldN) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun playYt(list: List<YtItem>, index: Int) {
        focusBeforePlay = indexOfFocused()
        player.open(PlayQueue(list.map { YtLogic.toVideoItem(it) }, index))   // السابق/التالي = نتايج البحث؛ مش بيتسجّل في السجل المحلي (D-014)
    }

    private fun ytCard(y: YtItem, onClick: () -> Unit): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(th.card) }
        c.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(108)))
        NetImage.load(y.thumb) { bmp -> if (bmp != null) iv.setImageBitmap(bmp) }
        c.addView(ui.text(y.title, 14f, th.text, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, ui.dp(4), 0, 0) })
        val sub = (if (y.durSec > 0) LibraryLogic.formatDur(y.durSec * 1000L) + " · " else "") + YtLogic.subtitle(y)
        if (sub.isNotBlank()) c.addView(ui.text(sub, 12f, th.muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        return ui.focusable(c, 14, onClick)
    }

    private fun buildYoutube(col: LinearLayout) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(10), 0, ui.dp(6)) }
        val et = EditText(this).apply {
            setText(ytQuery); setSelection(text.length)
            hint = "ابحث في يوتيوب…"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 22f * ui.fontScale
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true)
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
        }
        ui.focusable(et, 14) { showIme(et) }
        et.setOnEditorActionListener { _, actionId, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) { hideIme(); runYt(et.text.toString()); true } else false
        }
        ytInput = et
        row.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        val mic = ui.text("🎤", 24f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
        ui.focusable(mic, 14) { if (!ExternalSearch.startVoice(this, REQ_VOICE)) Toast.makeText(this, "البحث الصوتي مش متاح على التلفزيون ده — اكتب بالكيبورد", Toast.LENGTH_LONG).show() }
        row.addView(mic, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, 0, 0) })
        val clr = ui.text("✕", 22f, th.text, true).apply { setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); gravity = Gravity.CENTER }
        ui.focusable(clr, 14) { ytClear(); ytInput?.setText(""); renderYt(); ytInput?.requestFocus() }
        row.addView(clr, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(8), 0, 0, 0) })
        col.addView(row, LinearLayout.LayoutParams(-1, -2))

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ytBox = box
        col.addView(box, LinearLayout.LayoutParams(-1, -2))
        val pend = ytPending; ytPending = null
        if (pend != null) runYt(pend) else renderYt()
    }

    private fun renderYt() {
        val box = ytBox ?: return
        box.removeAllViews()
        val q = ytQuery
        if (q.isBlank()) {
            note(box, "البحث والتشغيل هنا من جوه التطبيق (NewPipe) ومحتاجين نت. مفيش حاجة بتتفتح لوحدها.", true)
            note(box, "اكتب اسم الفيديو (OK على الخانة يفتح الكيبورد) أو دوس 🎤. يوتيوب مش بيأثّر على مكتبتك المحلية ولا سجلها.", true)
            return
        }
        if (ytLoading && ytItems.isEmpty()) { note(box, "جارٍ البحث في يوتيوب عن «$q»…", true); return }
        ytError?.let { e ->
            note(box, "تعذّر البحث: $e\nاتأكد من النت وحاول تاني.", true)
            button(box, "🔄 إعادة المحاولة") { runYt(q) }
        }
        if (ytError == null && ytItems.isEmpty()) note(box, "مفيش نتايج لـ «$q» على يوتيوب.")
        if (ytItems.isNotEmpty()) {
            val all = ytItems
            grid(box, all.mapIndexed { i, y -> ytCard(y) { playYt(all, i) } }, cols(210), 210, 190)
            if (ytLoading) note(box, "جارٍ تحميل المزيد…", true)
            else if (ytRes?.hasMore == true) {
                val more = textCard("عرض المزيد", "نتايج أكتر من يوتيوب") { ytMore() }
                box.addView(more, LinearLayout.LayoutParams(ui.dp(210), ui.dp(90)).apply { setMargins(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(6)) })
            }
        }
        button(box, "↗ فتح نفس البحث في تطبيق يوتيوب الرسمي (لو مثبّت)") {
            if (!ExternalSearch.openYoutubeSearch(this, q)) Toast.makeText(this, "تطبيق يوتيوب الرسمي مش متاح على الجهاز ده", Toast.LENGTH_LONG).show()
        }
    }

    // ===== التطبيقات (P9) =====
    private fun buildApps(col: LinearLayout) {
        val apps = AppsStore.list(this)
        if (apps.isEmpty()) {
            note(col, "مفيش تطبيقات تلفزيون يقدر النظام يعرضها هنا (أو الجهاز مش بيسمح بالاكتشاف).", true)
            return
        }
        note(col, "${apps.size} تطبيق — OK للفتح. الرجوع للانشر بيتم بزرار Home/Back حسب سلوك النظام.", true)
        val cards = apps.map { a ->
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            AppsStore.icon(this, a.pkg)?.let { iv.setImageDrawable(it) }
            c.addView(iv, LinearLayout.LayoutParams(-1, ui.dp(84)))
            c.addView(ui.text(a.label, 14f, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            ui.focusable(c, 14) { if (!AppsStore.launch(this, a.pkg)) Toast.makeText(this, "تعذّر فتح «${a.label}» — ممكن اتشال أو مش متوافق", Toast.LENGTH_LONG).show() }
        }
        grid(col, cards, cols(190), 190, 140)
    }

    // ===== الإعدادات =====
    private fun buildSettings(col: LinearLayout) {
        sectionTitle(col, "الثيم (بيتحفظ ويفضل بعد إعادة التشغيل)")
        for (t in Themes.all) button(col, (if (t.id == th.id) "✓ " else "") + t.name) { Cfg.put("theme", t.id); rebuildKeepingScreen(indexOfFocused()) }

        sectionTitle(col, "المظهر المخصّص (فوق الثيم — بيتحفظ)")
        val ca = Cfg.int("cust_accent", 0); val cb = Cfg.int("cust_bg", 0); val cf = Cfg.int("cust_font", 0)
        button(col, "اللون الرئيسي: ${Appearance.accentName(ca)}   (OK للتغيير)") { Cfg.put("cust_accent", Appearance.next(ca, Appearance.ACCENTS.size)); rebuildKeepingScreen(indexOfFocused()) }
        button(col, "الخلفية: ${Appearance.bgName(cb)}   (OK للتغيير)") { Cfg.put("cust_bg", Appearance.next(cb, Appearance.BGS.size)); rebuildKeepingScreen(indexOfFocused()) }
        button(col, "الخط: ${Appearance.fontName(cf)}   (OK للتغيير)") { Cfg.put("cust_font", Appearance.next(cf, Appearance.FONTS.size)); rebuildKeepingScreen(indexOfFocused()) }
        if (ca != 0 || cb != 0 || cf != 0) button(col, "↩ إرجاع المظهر لوضع الثيم") { Cfg.put("cust_accent", 0); Cfg.put("cust_bg", 0); Cfg.put("cust_font", 0); rebuildKeepingScreen(indexOfFocused()) }
        col.addView(ui.text("الاسم الظاهر في الشريط العلوي (OK للكتابة، فاضي = ${Appearance.DEFAULT_NAME}):", 15f, th.muted).apply { setPadding(0, ui.dp(12), 0, ui.dp(2)) })
        val nameEt = EditText(this).apply {
            setText(Cfg.str("disp_name", "")); hint = Appearance.DEFAULT_NAME; setHintTextColor(th.muted); setTextColor(th.text); textSize = 20f * ui.fontScale
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_DONE; setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(Appearance.NAME_MAX))
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
        }
        ui.focusable(nameEt, 14) { showIme(nameEt) }
        nameEt.setOnEditorActionListener { _, actionId, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) { Cfg.put("disp_name", nameEt.text.toString().trim()); hideIme(); rebuildKeepingScreen(indexOfFocused()); true } else false
        }
        col.addView(nameEt, LinearLayout.LayoutParams(-1, -2))

        sectionTitle(col, "الحركة والخط")
        val motion = Cfg.int("motion", 2)
        button(col, "الحركة: " + listOf("بدون حركة", "خفيفة", "كاملة")[motion.coerceIn(0, 2)] + "   (OK للتغيير)") {
            Cfg.put("motion", HomePrefs.cycle(listOf(2, 1, 0), motion)); rebuildKeepingScreen(indexOfFocused())
        }
        val fp = Cfg.int("font_pct", 100)
        button(col, "حجم الخط: $fp%   (OK للتغيير)") { Cfg.put("font_pct", HomePrefs.cycle(listOf(100, 120, 150), fp)); rebuildKeepingScreen(indexOfFocused()) }

        sectionTitle(col, "صفوف الرئيسية")
        val hidden = HomePrefs.hiddenSet(Cfg.str("rows_hide", ""))
        for ((id, label) in ROWS) button(col, (if (id in hidden) "✗ مخفي: " else "✓ ظاهر: ") + label) {
            Cfg.put("rows_hide", HomePrefs.toggle(Cfg.str("rows_hide", ""), id)); rebuildKeepingScreen(indexOfFocused())
        }

        sectionTitle(col, "المكتبة")
        button(col, "الترتيب الافتراضي: ${sortLabel(sortMode())}   (OK للتغيير)") { cycleSort() }
        button(col, "🔄 إعادة فحص المكتبة الآن") { if (LibraryStore.hasPermission(this)) { startScan(); Toast.makeText(this, "بدأ الفحص", Toast.LENGTH_SHORT).show() } else requestPerm() }
        val ex = LibraryLogic.excludedFolders(LibraryStore.items, excluded())
        if (ex.isEmpty()) col.addView(ui.text("مفيش مجلدات مستبعدة (الاستبعاد من جوه المجلد: «🚫 استبعاد المجلد ده»).", 14f, th.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
        for (f in ex) button(col, "↩ إرجاع: ${f.name}" + if (f.count == 0) "  (مش موجود دلوقتي)" else "  (${f.count} فيديو)") {
            Cfg.put("lib_excl", HomePrefs.toggle(Cfg.str("lib_excl", ""), f.key)); rebuildKeepingScreen(indexOfFocused())
        }

        sectionTitle(col, "البحث")
        val nRec = SearchLogic.decodeRecents(Cfg.str("search_recent", "")).size
        button(col, if (nRec == 0) "عمليات البحث الأخيرة: فاضية" else "🗑 مسح عمليات البحث الأخيرة ($nRec)") { Cfg.put("search_recent", ""); rebuildKeepingScreen(indexOfFocused()) }
        val ytOn = Cfg.bool("search_yt", true)
        button(col, "زرار «البحث على يوتيوب» في شاشة البحث: " + (if (ytOn) "ظاهر" else "مخفي") + "   (OK للتغيير)") { Cfg.put("search_yt", !ytOn); rebuildKeepingScreen(indexOfFocused()) }

        sectionTitle(col, "يوتيوب (NewPipe)")
        val ytq = Cfg.int("yt_maxh", 1080)
        button(col, "أعلى جودة للتشغيل: ${YtLogic.qualityLabel(ytq)}   (OK للتغيير)") { Cfg.put("yt_maxh", HomePrefs.cycle(listOf(720, 1080, 0), ytq)); rebuildKeepingScreen(indexOfFocused()) }

        sectionTitle(col, "التطبيقات والأصوات")
        val appsOn = Cfg.bool("apps_on", false)
        button(col, "صفحة التطبيقات: " + (if (appsOn) "ظاهرة" else "مخفية (اختيارية)") + "   (OK للتغيير)") { Cfg.put("apps_on", !appsOn); rebuildKeepingScreen(indexOfFocused()) }
        val snd = Cfg.bool("nav_sound", false)
        button(col, "أصوات التنقل: " + (if (snd) "شغّالة" else "متوقفة") + "   (OK للتغيير)") { Cfg.put("nav_sound", !snd); rebuildKeepingScreen(indexOfFocused()) }

        sectionTitle(col, "ترتيب الأقسام (⬆ ⬇ — OK على السهم)")
        col.addView(ui.text("صفوف الرئيسية:", 15f, th.muted))
        for (id in HomePrefs.order(Cfg.str("rows_order", ""), ROWS.map { it.first }))
            orderRow(col, ROWS.first { it.first == id }.second, { moveOrder("rows_order", ROWS.map { it.first }, id, -1) }, { moveOrder("rows_order", ROWS.map { it.first }, id, 1) })
        col.addView(ui.text("القايمة الجانبية (الرئيسية أول واحدة والإعدادات آخر واحدة دايمًا):", 15f, th.muted).apply { setPadding(0, ui.dp(10), 0, 0) })
        for (id in HomePrefs.order(Cfg.str("rail_order", ""), RAIL_MOVABLE)) {
            val sc = screens.first { it.id == id }
            orderRow(col, sc.icon + " " + sc.label + (if (id == "apps" && !Cfg.bool("apps_on", false)) " (مخفية حاليًا)" else ""), { moveOrder("rail_order", RAIL_MOVABLE, id, -1) }, { moveOrder("rail_order", RAIL_MOVABLE, id, 1) })
        }
        button(col, "↩ إرجاع الترتيب الافتراضي") { Cfg.put("rows_order", ""); Cfg.put("rail_order", ""); rebuildKeepingScreen(indexOfFocused()) }

        sectionTitle(col, "المشغّل (بيتطبّق على الفيديو الجاي)")
        fun cyc(label: String, key: String, def: Int, list: List<Int>, fmt: (Int) -> String) {
            val v = Cfg.int(key, def); button(col, "$label: ${fmt(v)}   (OK للتغيير)") { Cfg.put(key, HomePrefs.cycle(list, v)); rebuildKeepingScreen(indexOfFocused()) }
        }
        fun cycS(label: String, key: String, list: List<String>, fmt: (String) -> String) {
            val v = Cfg.str(key, ""); val nx = list[(list.indexOf(v).let { if (it < 0) 0 else it } + 1) % list.size]
            button(col, "$label: ${fmt(v)}   (OK للتغيير)") { Cfg.put(key, nx); rebuildKeepingScreen(indexOfFocused()) }
        }
        fun tog(label: String, key: String, def: Boolean) {
            val v = Cfg.bool(key, def); button(col, "$label: " + (if (v) "شغّال" else "متوقف") + "   (OK للتغيير)") { Cfg.put(key, !v); rebuildKeepingScreen(indexOfFocused()) }
        }
        cyc("خطوة التقديم/التأخير", "seek_base", 10, PlayerLogic.SEEK_BASES) { "$it ثانية" }
        cyc("السرعة الافتراضية", "player_speed_i", PlayerLogic.speeds.indexOf(1f), PlayerLogic.speeds.indices.toList()) { PlayerLogic.speedLabel(PlayerLogic.speeds[it.coerceIn(0, PlayerLogic.speeds.size - 1)]) }
        cyc("حجم الترجمة", "sub_scale", 100, listOf(80, 100, 130, 160, 200)) { "$it%" }
        cyc("لون الترجمة", "sub_color", 0xFFFFFFFF.toInt(), PlayerLogic.SUB_COLORS.map { it.first }) { PlayerLogic.SUB_COLORS[PlayerLogic.idxOf(PlayerLogic.SUB_COLORS, it)].second }
        cyc("خلفية الترجمة", "sub_bg", 0x77000000, PlayerLogic.SUB_BGS.map { it.first }) { PlayerLogic.SUB_BGS[PlayerLogic.idxOf(PlayerLogic.SUB_BGS, it)].second }
        cycS("لغة الصوت المفضّلة", "pref_audio", PlayerLogic.LANGS.map { it.first }) { PlayerLogic.langLabel(it) }
        cycS("لغة الترجمة المفضّلة", "pref_sub", PlayerLogic.LANGS.map { it.first }) { if (it.isEmpty()) "تلقائي (عربي لو موجودة)" else PlayerLogic.langLabel(it) }
        cyc("إخفاء أزرار التحكم بعد", "hide_secs", 4, PlayerLogic.HIDE_SECS) { "$it ثانية" }
        tog("الاستكمال التلقائي من آخر موضع", "player_resume", true)
        tog("تشغيل التالي تلقائيًا", "player_autonext", true)
        note(col, "ترجمة خارجية (.srt/.vtt/.ass): من لوحة المشغّل (▲▼) «ترجمة خارجية» بمنتقي الملفات، أو حطّ الملف في مجلد التطبيق: ${subsDir()?.absolutePath?.substringAfter("/Android/", "Android/data/…/files/subs") ?: "(غير متاح)"} بنفس اسم الفيديو.", true)

        sectionTitle(col, "نسخ احتياطي واستعادة")
        note(col, "بيحفظ الإعدادات + سجل المشاهدة + ربط الترجمات (مش فهرس المكتبة — بيتعاد فحصه).", true)
        button(col, "💾 حفظ نسخة احتياطية الآن") { backupNow() }
        val bf = backupFile()
        button(col, when {
            restoreConfirm -> "⚠ اضغط OK تاني لتأكيد الاستعادة (بتستبدل إعداداتك وسجلك الحاليين)"
            bf.isFile -> "📂 استعادة من النسخة المحلية (" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(bf.lastModified())) + ")"
            else -> "📂 استعادة من النسخة المحلية — مفيش نسخة لسه"
        }) {
            if (!bf.isFile) Toast.makeText(this, "مفيش نسخة محلية — اعمل «حفظ نسخة» الأول", Toast.LENGTH_SHORT).show()
            else if (!restoreConfirm) { restoreConfirm = true; rebuildKeepingScreen(indexOfFocused()) }
            else { restoreConfirm = false; restoreFromFile(bf) }
        }
        button(col, "📤 تصدير لمكان تختاره (USB / تخزين)") { pickExport() }
        button(col, "📥 استيراد من ملف (بيستبدل إعداداتك وسجلك)") { pickImport() }

        sectionTitle(col, "شاشة التوقف")
        val sst = Cfg.int("saver_style", SaverLogic.STYLE_CLOCK_DATE)
        button(col, "الشكل: " + SaverLogic.styleLabel(sst) + "   (OK للتغيير)") { Cfg.put("saver_style", HomePrefs.cycle(SaverLogic.STYLES, sst)); rebuildKeepingScreen(indexOfFocused()) }
        val min = Cfg.int("saver_min", 5)
        button(col, "بعد: " + (if (min == 0) "معطّلة" else "$min دقيقة") + "   (OK للتغيير)") {
            Cfg.put("saver_min", HomePrefs.cycle(listOf(0, 1, 5, 10, 30), min)); rebuildKeepingScreen(indexOfFocused())
        }
    }

    /** صف ترتيب: الاسم + ⬆ ⬇ (كل صف = زرارين بالظبط عشان التركيز يتبع العنصر بعد النقل) */
    private fun orderRow(col: LinearLayout, label: String, up: () -> Unit, down: () -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(2), 0, ui.dp(2)) }
        r.addView(ui.text(label, 17f, th.text, true), LinearLayout.LayoutParams(0, -2, 1f))
        for ((sym, fn) in listOf("⬆" to up, "⬇" to down)) {
            val b = ui.text(sym, 20f, th.text, true).apply { setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8)); gravity = Gravity.CENTER }
            ui.focusable(b, 12, fn)
            r.addView(b, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
        }
        col.addView(r, LinearLayout.LayoutParams(-1, -2))
    }
    private fun moveOrder(key: String, all: List<String>, id: String, dir: Int) {
        val ord = HomePrefs.order(Cfg.str(key, ""), all)
        val n = HomePrefs.move(ord, id, dir)
        if (n == ord) { Toast.makeText(this, if (dir < 0) "ده أول واحد" else "ده آخر واحد", Toast.LENGTH_SHORT).show(); return }
        Cfg.put(key, n.joinToString(","))
        rebuildKeepingScreen((indexOfFocused() + dir * 2).coerceAtLeast(0))   // التركيز يتبع العنصر (2 زرار في كل صف)
    }

    // ===== نسخ احتياطي (P15، D-018) =====
    private fun backupFile(): File = File(getExternalFilesDir(null) ?: filesDir, "vtl_backup.txt")
    private fun subsDir(): File? = try { File(getExternalFilesDir(null) ?: filesDir, "subs").apply { mkdirs() } } catch (_: Throwable) { null }
    private fun backupText(): String = BackupLogic.encode(Cfg.snapshot(), HistoryStore.exportText())

    private fun backupNow() {
        try {
            val f = backupFile(); val txt = backupText(); val tmp = File(f.path + ".tmp"); tmp.writeText(txt)
            if (!tmp.renameTo(f)) { f.writeText(txt); tmp.delete() }
            Toast.makeText(this, "اتحفظت النسخة: " + f.absolutePath.substringAfter("/Android/", f.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Throwable) { Toast.makeText(this, "فشل الحفظ: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
        rebuildKeepingScreen(indexOfFocused())
    }
    private fun restoreFromFile(f: File) {
        val t = try { f.readText() } catch (e: Throwable) { null }
        if (t == null) Toast.makeText(this, "مقدرتش أقرا ملف النسخة", Toast.LENGTH_LONG).show() else applyBackup(t)
    }
    private fun applyBackup(text: String) {
        val d = BackupLogic.decode(text); val data = d.data
        if (data == null) { Toast.makeText(this, "الاستعادة فشلت: " + d.error + " — ما اتغيّرش حاجة", Toast.LENGTH_LONG).show(); return }
        val n = HistoryStore.replaceFromText(this, data.history)
        if (n < 0) { Toast.makeText(this, "سجل المشاهدة في النسخة تالف — ما اتغيّرش حاجة", Toast.LENGTH_LONG).show(); return }
        if (!Cfg.restoreAll(data.prefs)) { Toast.makeText(this, "السجل اتستعاد لكن كتابة الإعدادات فشلت", Toast.LENGTH_LONG).show(); return }
        Toast.makeText(this, "تمت الاستعادة: " + BackupLogic.summary(data, n), Toast.LENGTH_LONG).show()
        rebuildKeepingScreen()
    }
    @Suppress("DEPRECATION")
    private fun pickExport() {
        try { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, "vtl_backup.txt"), REQ_EXPORT) }
        catch (_: Throwable) { Toast.makeText(this, "التلفزيون ده مفيهوش مُنتقي ملفات — استخدم «حفظ نسخة احتياطية الآن»", Toast.LENGTH_LONG).show() }
    }
    @Suppress("DEPRECATION")
    private fun pickImport() {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT) }
        catch (_: Throwable) { Toast.makeText(this, "التلفزيون ده مفيهوش مُنتقي ملفات — استخدم «استعادة من النسخة المحلية»", Toast.LENGTH_LONG).show() }
    }
    private fun exportTo(uri: Uri) {
        try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(backupText().toByteArray(Charsets.UTF_8)) } ?: throw IllegalStateException("no stream"); Toast.makeText(this, "اتصدّرت النسخة", Toast.LENGTH_SHORT).show() }
        catch (e: Throwable) { Toast.makeText(this, "فشل التصدير: " + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
    }
    private fun importFrom(uri: Uri) {
        val txt = try {
            contentResolver.openInputStream(uri)?.use { inp ->
                val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
                while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > BackupLogic.MAX_BYTES) break; out.write(buf, 0, n) }
                if (total > BackupLogic.MAX_BYTES) null else out.toString("UTF-8")
            }
        } catch (_: Throwable) { null }
        if (txt == null) Toast.makeText(this, "مقدرتش أقرا الملف (أو كبير بشكل غير طبيعي)", Toast.LENGTH_LONG).show() else applyBackup(txt)
    }

    private fun rebuildKeepingScreen(restoreIdx: Int = -1) {
        val cur = stack.last()
        buildUi(); show(cur, null, restoreIdx)
    }
}
