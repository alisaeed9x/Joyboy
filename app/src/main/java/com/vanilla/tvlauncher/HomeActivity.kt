package com.vanilla.tvlauncher

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * الرئيسية + ريل + مكدّس رجوع (شاشات فرعية + مشغّل + سكون) + ثيمات/إعدادات.
 * P4: المكتبة (مجلدات/فيديوهات/فرز/استبعاد/صور مصغّرة) متوصّلة بـ LibraryStore.
 * P5: السجل + متابعة المشاهدة + المشغّل الداخلي (PlayerLayer).
 * لسه مؤقتة: البحث (P6) · يوتيوب (P7–8) · التطبيقات (P9).
 */
class HomeActivity : Activity() {
    private class Screen(val id: String, val icon: String, val label: String, val phase: Int)
    private val screens = listOf(
        Screen("home", "🏠", "الرئيسية", 2), Screen("library", "🎬", "الفيديوهات", 4), Screen("history", "🕘", "السجل", 5),
        Screen("search", "🔍", "البحث", 6), Screen("youtube", "▶", "يوتيوب", 7), Screen("apps", "🧩", "التطبيقات", 9),
        Screen("settings", "⚙", "الإعدادات", 9)
    )

    private val ROWS = listOf("cont" to "▶ متابعة المشاهدة", "lib" to "🎬 الفيديوهات", "more" to "🔍 وأكتر")
    private val router = BackRouter()
    private val h = Handler(Looper.getMainLooper())
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private lateinit var contentHost: FrameLayout
    private lateinit var saver: FrameLayout
    private lateinit var saverClock: TextView
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

    override fun onDestroy() { LibraryStore.unwatch(this); h.removeCallbacksAndMessages(null); super.onDestroy() }
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
        player.close(); return true                           // يحفظ الموضع ويرجّع للشاشة اللي فتحته
    }
    private fun screenBack(): Boolean {
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
            clockTv.text = t
            val min = Cfg.int("saver_min", 5)
            if (saver.visibility == View.VISIBLE) {
                saverClock.text = t
                if (idleSec % 60 == 0) moveSaverClock()
                idleSec++
            } else if (player.active && player.playing) {
                idleSec = 0                                   // فيديو شغّال: السكون ما يقاطعوش
            } else {
                idleSec++
                if (min > 0 && idleSec >= min * 60) showSaver()
            }
            h.postDelayed(this, 1000)
        }
    }
    private fun showSaver() { saver.visibility = View.VISIBLE; idleSec = 0; moveSaverClock() }
    private fun wake() {
        idleSec = 0
        if (::saver.isInitialized && saver.visibility == View.VISIBLE) { saver.visibility = View.GONE; wokeAt = SystemClock.uptimeMillis() }
    }
    private fun moveSaverClock() {
        val w = saver.width; val hh = saver.height
        if (w <= 0 || hh <= 0) return
        saverClock.translationX = Random.nextInt(-(w / 4), w / 4).toFloat()
        saverClock.translationY = Random.nextInt(-(hh / 4), hh / 4).toFloat()
    }

    // ===== بناء الواجهة =====
    private fun buildUi() {
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th, Cfg.int("motion", 2), Cfg.int("font_pct", 100) / 100f)
        val root = FrameLayout(this).apply { setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(28), ui.dp(14), ui.dp(28), ui.dp(10))
        }
        bar.addView(ui.text("Vanilla TV", 26f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        clockTv = ui.text("", 20f, th.text, true); bar.addView(clockTv)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val railCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); setBackgroundColor(th.card) }
        rail.clear()
        for (s in screens) {
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
        saverClock = ui.text("", 56f, 0xFFB0B0B0.toInt(), true).apply { gravity = Gravity.CENTER }
        saver.addView(saverClock, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(saver, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    // ===== التنقل =====
    private fun navigate(id: String) {
        if (stack.last() == id) return
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
        scanTv = null
        contentHost.removeAllViews()
        val id = baseId(entry)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(28), ui.dp(12), ui.dp(28), ui.dp(24)) }
        val s = screens.first { it.id == id }
        if (id != "library" || entry == "library") col.addView(ui.text(s.icon + "  " + s.label, 26f, th.primary, true))
        when (id) {
            "home" -> buildHome(col)
            "library" -> if (entry == "library") buildLibrary(col) else buildFolder(col, entry, entry.substringAfter("folder:"))
            "history" -> buildHistory(col)
            "settings" -> buildSettings(col)
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
            (target ?: (if (sub) items.firstOrNull() else null) ?: rail[focusKey ?: id] ?: rail[id] ?: rail["home"])?.requestFocus()
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
        if (LibraryStore.scanning) { scanTv?.text = "جارٍ الفحص… ${LibraryStore.scanned} فيديو"; return }
        refreshCurrent()
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

    private fun onPlayerClosed() {
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

        if ("cont" !in hidden) {
            val cont = HistoryLogic.continueList(HistoryStore.entries, 12).mapNotNull { e -> HistoryLogic.find(e, items)?.let { e to it } }
            if (cont.isEmpty()) row("▶ متابعة المشاهدة", listOf(textCard("مفيش حاجة للمتابعة", "أي فيديو توقف في نصه هيظهر هنا") { navigate("library") }))
            else row("▶ متابعة المشاهدة", cont.map { (e, v) ->
                val pct = if (e.durMs > 0) (e.posMs * 100 / e.durMs).toInt() else 0
                videoCard(v, "$pct% · ${LibraryLogic.formatDur(e.posMs)}") { playEntry(e) }
            }, 210, 170)
        }
        if ("lib" !in hidden) {
            if (!LibraryStore.hasPermission(this)) row("🎬 الفيديوهات", listOf(textCard("منح إذن الفيديو", "عشان تظهر مكتبتك") { navigate("library") }))
            else if (!hasLib) row("🎬 الفيديوهات", listOf(textCard(if (LibraryStore.scanning) "جارٍ الفحص…" else "مفيش فيديوهات", "افتح المكتبة") { navigate("library") }))
            else {
                val recent = LibraryLogic.sortItems(LibraryLogic.visible(items, excluded()), LibraryLogic.SORT_DATE).take(10)
                val vis = LibraryLogic.visible(items, excluded())
                row("🎬 الفيديوهات", listOf(textCard("المكتبة والمجلدات", "${vis.size} فيديو") { navigate("library") }) +
                    recent.mapIndexed { i, v -> videoCard(v, LibraryLogic.formatDur(v.durMs)) { playList(recent, i) } }, 210, 170)
            }
        }
        if ("more" !in hidden) row("🔍 وأكتر", listOf(textCard("البحث", "المرحلة 6") { navigate("search") }, textCard("يوتيوب", "المرحلة 7") { navigate("youtube") }))
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

    // ===== الإعدادات =====
    private fun buildSettings(col: LinearLayout) {
        sectionTitle(col, "الثيم (بيتحفظ ويفضل بعد إعادة التشغيل)")
        for (t in Themes.all) button(col, (if (t.id == th.id) "✓ " else "") + t.name) { Cfg.put("theme", t.id); rebuildKeepingScreen(indexOfFocused()) }

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

        sectionTitle(col, "شاشة التوقف")
        val min = Cfg.int("saver_min", 5)
        button(col, "بعد: " + (if (min == 0) "معطّلة" else "$min دقيقة") + "   (OK للتغيير)") {
            Cfg.put("saver_min", HomePrefs.cycle(listOf(0, 1, 5, 10, 30), min)); rebuildKeepingScreen(indexOfFocused())
        }
    }

    private fun rebuildKeepingScreen(restoreIdx: Int = -1) {
        val cur = stack.last()
        buildUi(); show(cur, null, restoreIdx)
    }
}
