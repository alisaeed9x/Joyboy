package com.tttt.subtitler.phonehome

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.app.WallpaperManager
import android.content.*
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import kotlin.math.hypot
import com.tttt.subtitler.AppWall
import com.tttt.subtitler.Cfg
import com.tttt.subtitler.LogStore
import com.tttt.subtitler.Notice
import com.tttt.subtitler.PrayerTimes
import com.tttt.subtitler.Themes

/**
 * (v772) Joyboy Launcher جوه التطبيق (كان تطبيق لوحده JoyboyLauncher_v3): شاشة رئيسية للموبايل — صفحات أيقونات +
 * فولدرات + درج تطبيقات + ريسنتس + إيماءات، ومعاها ساعة ومواقيت صلاة لايت وزرار تغيير الخلفية (AppWall) وزرار تنضيف الرام.
 */
class PhoneHomeActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var content: SwipeFrame
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var hotseat: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerList: RecyclerView
    private lateinit var drawerAdapter: AppAdapter
    private lateinit var pagesAdapter: PagesAdapter

    private val prefs by lazy { getSharedPreferences(HOME_PREFS, MODE_PRIVATE) }
    private var allApps = listOf<AppItem>()
    private var pages: MutableList<MutableList<Entry>> = mutableListOf(mutableListOf())
    private var anim: SpringAnimation? = null

    // drag state
    private var drag: Entry? = null
    private var ghost: ImageView? = null
    private var dragView: View? = null
    private var dragFromFolder = false
    private var dragMoved = false
    private var needCancel = false
    private var lastX = 0f
    private var lastY = 0f
    private var dragX0 = 0f
    private var dragY0 = 0f
    private var offX = 0f
    private var offY = 0f
    private var lastReorder = 0L
    private var mergeTarget: Entry? = null
    private var mergeSince = 0L
    private var mergeReady = false
    private var mergeView: View? = null
    private var edgeDir = 0
    private var edgeSince = 0L

    // folder popup
    private var popup: FrameLayout? = null
    private var popupFolder: Entry.Folder? = null
    private var popupTitle: EditText? = null
    private var recents: FrameLayout? = null
    private lateinit var widgets: HomeWidgets          // (v773)
    private lateinit var studio: ThemeStudio            // (v775) ستور الثيمات + التوليد العشوائي + المعاينة
    private var themeWallViews: List<View> = emptyList()   // (v775) خلفية الثيم (صورة/تدرّج + تعتيم)
    private var homeMenu: PopupWindow? = null          // (v773) قايمة الضغطة الطويلة

    // (v772) الساعة + المواقيت + الخلفية + الرام
    private lateinit var clock: HomeClock
    private val ui = Handler(Looper.getMainLooper())
    private var prayerAt = 0L
    private var ramBusy = false
    private var wallViews: List<View> = emptyList()
    private val tick = object : Runnable {
        override fun run() { clock.refresh(); ui.postDelayed(this, 20_000L) }
    }

    var progress = 0f
        set(v) { field = v; applyProgress() }

    private val progressProp = object : FloatPropertyCompat<PhoneHomeActivity>("progress") {
        override fun getValue(o: PhoneHomeActivity) = o.progress
        override fun setValue(o: PhoneHomeActivity, v: Float) { o.progress = v }
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = reload()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.ensure(this)
        HomeThemeStore.cur = HomeThemeStore.load(prefs)   // (v773) ثيم الذكاء قبل بناء الواجهة
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        reload()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        // لانشر: زرار الرجوع مابيخرّجش من الشاشة الرئيسية — بيقفل اللي مفتوح بس
        when {
            widgets.isOpen -> widgets.close()
            recents != null -> closeRecents(true)
            popup != null -> closeFolder(true)
            progress > 0.01f -> animateTo(0f)
        }
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        registerReceiver(pkgReceiver, f)
        widgets.start()
    }

    override fun onStop() { super.onStop(); widgets.stop(); try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {} }

    override fun onPause() { super.onPause(); ui.removeCallbacks(tick) }

    override fun onDestroy() { super.onDestroy(); ui.removeCallbacks(tick); AppWall.detach(this) }

    override fun onResume() {
        super.onResume()
        stage.springTo(DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_LOW, .8f)
        stage.springTo(DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_LOW, .8f)
        stage.springTo(DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_MEDIUM, 1f)
        if (RecentsStore.openRequested) { RecentsStore.openRequested = false; root.post { openRecents() } }
        rebindWall()
        ui.removeCallbacks(tick); tick.run()
        loadPrayer(false)
        updateRamLabel()
        maybeAskDefault()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN) {
            if (widgets.isOpen) { widgets.close(); return }
            if (RecentsStore.openRequested) { RecentsStore.openRequested = false; root.post { openRecents() }; return }
            if (recents != null) { closeRecents(true); return }
            closeFolder(false)
            if (progress > 0.01f) animateTo(0f) else pager.setCurrentItem(0, true)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        widgets.onResult(req, res, data)
    }

    // ───────── touch (drag & drop lives here) ─────────
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastX = ev.rawX; lastY = ev.rawY
        if (drag == null) return super.dispatchTouchEvent(ev)
        if (needCancel) {
            needCancel = false
            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c); c.recycle()
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDragMove(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDrop()
        }
        return true
    }

    // ───────── UI ─────────
    private fun buildUi() {
        root = FrameLayout(this)
        widgets = HomeWidgets(this, prefs, root)
        studio = ThemeStudio(this, prefs, { pages.firstOrNull()?.filter { it !== Entry.Placeholder } ?: emptyList() }) { recreate() }
        attachWall()   // (v772) خلفية Joyboy المتغيّرة تحت الواجهة كلها
        attachThemeWall()   // (v775) لو الثيم الشغّال له خلفية: بتتحط فوق خلفية Joyboy وتحت الواجهة
        stage = FrameLayout(this)
        root.addView(stage, -1, -1)

        content = SwipeFrame(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pagesAdapter = PagesAdapter(pages, ::onWorkspaceClick, ::onWorkspaceLong) { showHomeMenu() }
        pager = ViewPager2(this).apply {
            adapter = pagesAdapter
            setPageTransformer(pageTransformer(HomeThemeStore.cur?.transition ?: ""))
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(p: Int) = updateDots(p)
                override fun onPageScrolled(p: Int, off: Float, px: Int) {
                    val n = pagesAdapter.itemCount
                    if (n > 1) try {
                        WallpaperManager.getInstance(this@PhoneHomeActivity)
                            .setWallpaperOffsets(root.windowToken, (p + off) / (n - 1), .5f)
                    } catch (_: Exception) {}
                }
            })
        }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        dots.setOnLongClickListener { showHomeMenu(); true }
        hotseat = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10))
            background = dockBg()
        }
        hotseat.setOnLongClickListener { showHomeMenu(); true }
        // (v772) الصفحة بتاخد المساحة اللي فاضلة تحت الساعة: ارتفاع الصف بيتظبط عشان الـ4 صفوف يبانوا كلهم
        pager.addOnLayoutChangeListener { _, _, t, _, bt, _, _, _, _ ->
            val rows = PAGE_CAP / 4
            val rh = ((bt - t - dp(16)) / rows).coerceIn(dp(70), dp(96))
            if (rh != pagesAdapter.rowH) { pagesAdapter.rowH = rh; pagesAdapter.notifyDataSetChanged() }
        }
        val compact = resources.configuration.screenHeightDp < 720
        clock = HomeClock(this, compact, { nextWall() }, { cleanRam() }, { loadPrayer(true) }, { studio.openStore() })
        HomeThemeStore.cur?.let { clock.applyTheme(it) }
        clock.view.setOnLongClickListener { showHomeMenu(); true }
        col.addView(clock.view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        col.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(dots, LinearLayout.LayoutParams(-1, -2))
        col.addView(hotseat, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
        content.addView(col, -1, -1)
        content.onDrag = { dy -> anim?.cancel(); progress = (-dy / (root.height * .6f)).coerceIn(0f, 1f) }
        content.onEnd = { _, v -> animateTo(if (v < -800f || progress > .4f) 1f else 0f) }
        stage.addView(content, -1, -1)

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.INVISIBLE
            background = GradientDrawable().apply {
                val r = dp(HomeThemeStore.cur?.drawerRadius ?: 28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(surfaceColor())
            }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val handle = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x55888888) }
        }
        header.addView(handle, LinearLayout.LayoutParams(dp(36), dp(4)).apply { topMargin = dp(10) })
        val search = EditText(this).apply {
            hint = "ابحث في التطبيقات"; maxLines = 1; isSingleLine = true
            setTextColor(onSurfaceColor()); setHintTextColor(0x88888888.toInt())
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(surfaceVariantColor())
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    val src = if (q.isEmpty()) allApps else allApps.filter { it.label.lowercase().contains(q) }
                    drawerAdapter.items = src.map { Entry.App(it) as Entry }.toMutableList()
                    drawerAdapter.notifyDataSetChanged()
                }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        header.addView(search, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(dp(16), dp(12), dp(16), dp(8)) })
        attachDrawerClose(header)
        drawerAdapter = AppAdapter(mutableListOf(), true,
            { e, v -> if (e is Entry.App) launch(e.item, v) },
            { e, v -> if (e is Entry.App) showMenu(e.item, v) })
        drawerList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PhoneHomeActivity, 4)
            adapter = drawerAdapter; setPadding(dp(12), 0, dp(12), 0); clipToPadding = false
        }
        drawer.addView(header, -1, -2)
        drawer.addView(drawerList, LinearLayout.LayoutParams(-1, 0, 1f))
        stage.addView(drawer, -1, -1)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(0, b.top, 0, b.bottom)
            drawer.setPadding(0, b.top / 2, 0, b.bottom)
            ins
        }
        root.post { applyProgress() }
    }

    private fun attachDrawerClose(h: View) {
        var y0 = 0f
        var vt: VelocityTracker? = null
        h.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { y0 = e.rawY; vt = VelocityTracker.obtain(); vt!!.addMovement(e) }
                MotionEvent.ACTION_MOVE -> {
                    vt?.addMovement(e); anim?.cancel()
                    progress = (1f - (e.rawY - y0) / (root.height * .6f)).coerceIn(0f, 1f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle(); vt = null
                    animateTo(if (vy > 800f || progress < .6f) 0f else 1f)
                }
            }
            true
        }
    }

    private fun applyProgress() {
        if (!::drawer.isInitialized) return
        val p = progress.coerceIn(0f, 1f)
        val h = if (drawer.height > 0) drawer.height else root.height
        drawer.translationY = (1f - p) * h
        drawer.visibility = if (p <= .001f) View.INVISIBLE else View.VISIBLE
        content.alpha = 1f - p
        val s = 1f - .1f * p
        content.scaleX = s; content.scaleY = s
        content.swipeEnabled = p < .01f
    }

    private fun animateTo(t: Float) {
        anim?.cancel()
        anim = SpringAnimation(this, progressProp).setSpring(
            SpringForce(t).setStiffness(450f).setDampingRatio(.85f)
        ).also { it.start() }
        if (t == 0f) currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    // ───────── data ─────────
    private fun reload() {
        Thread {
            val pack = prefs.getString("pack", null)?.let { try { IconPack(this, it) } catch (_: Exception) { null } }
            val apps = Apps.load(this, pack, prefs.getBoolean("themed", true))
            runOnUiThread { bind(apps) }
        }.start()
    }

    private fun hotseatDefaults(apps: List<AppItem>): List<AppItem> {
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        return intents.mapNotNull { i ->
            val pkg = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            apps.firstOrNull { it.cn.packageName == pkg }
        }.distinctBy { it.cn.packageName }
    }

    /** (v773) الدوك: اللي اختاره المستخدم، أو الافتراضي (اتصال/رسائل/متصفح/كاميرا) لو لسه ما خصّصش */
    private fun dockApps(apps: List<AppItem>): List<AppItem> {
        if (!HomeDock.customized(prefs)) return hotseatDefaults(apps)
        val map = apps.associateBy { it.cn.flattenToString() }
        return HomeDock.get(prefs).mapNotNull { map[it] }.take(HomeDock.MAX)
    }

    private fun toggleDock(a: AppItem) {
        if (!HomeDock.customized(prefs)) HomeDock.save(prefs, hotseatDefaults(allApps).map { it.cn.flattenToString() })
        if (HomeDock.has(prefs, a.cn)) HomeDock.remove(prefs, a.cn)
        else if (!HomeDock.add(prefs, a.cn)) {
            Toast.makeText(this, "الدوك مليان (" + HomeDock.MAX + " تطبيقات) — شيل واحد الأول", Toast.LENGTH_SHORT).show()
            return
        }
        reload()
    }

    private fun pageTransformer(kind: String) = ViewPager2.PageTransformer { page, pos ->
        val a = abs(pos).coerceAtMost(1f)
        when (kind) {
            "none" -> { page.alpha = 1f; page.scaleX = 1f; page.scaleY = 1f }
            "slide" -> { page.alpha = 1f - a * .3f; page.scaleX = 1f; page.scaleY = 1f }
            "zoom" -> { page.alpha = 1f - a; page.scaleX = 1f - a * .3f; page.scaleY = 1f - a * .3f }
            "fade" -> { page.alpha = 1f - a; page.scaleX = 1f; page.scaleY = 1f }
            else -> { page.alpha = 1f - a * .5f; page.scaleX = 1f - a * .1f; page.scaleY = 1f - a * .1f }
        }
    }

    private fun accent(): Int = HomeThemeStore.cur?.accent ?: Color.WHITE
    private fun dockBg() = GradientDrawable().apply { cornerRadius = dp(HomeThemeStore.cur?.dockRadius ?: 32).toFloat(); setColor(HomeThemeStore.cur?.dock ?: 0x33FFFFFF) }

    private fun bind(apps: List<AppItem>) {
        allApps = apps
        val hot = dockApps(apps)
        pages = LayoutStore.restore(prefs, apps, hot.map { it.cn }.toSet())
        pagesAdapter.pages = pages
        pagesAdapter.notifyDataSetChanged()
        drawerAdapter.items = apps.map { Entry.App(it) as Entry }.toMutableList()
        drawerAdapter.notifyDataSetChanged()
        hotseat.removeAllViews()
        hotseat.visibility = if (hot.isEmpty()) View.GONE else View.VISIBLE
        val th = HomeThemeStore.cur
        hot.forEach { a ->
            if (th == null || !HomeIcons.shaped(th)) {
                val iv = ImageView(this).apply {
                    setImageDrawable(a.copyIcon()); pressEffect()
                    setOnClickListener { launch(a, this) }
                    setOnLongClickListener { showMenu(a, this); true }
                }
                hotseat.addView(iv, LinearLayout.LayoutParams(0, dp(58), 1f))
            } else {
                // (v775) أيقونة الدوك بشكل الثيم: خلية بالعرض + أيقونة مربّعة في النص (عشان الشكل ما يتمطّش)
                val px = dp(if (th.iconDp > 0) th.iconDp.coerceIn(44, 60) else 56)
                val iv = HomeIcons.make(this, th, px).apply {
                    setImageDrawable(a.copyIcon()); pressEffect()
                    setOnClickListener { launch(a, this) }
                    setOnLongClickListener { showMenu(a, this); true }
                }
                val cell = FrameLayout(this)
                cell.addView(iv, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
                hotseat.addView(cell, LinearLayout.LayoutParams(0, dp(58), 1f))
            }
        }
        LayoutStore.save(prefs, pages)
        updateDots(pager.currentItem)
    }

    private fun updateDots(sel: Int) {
        dots.removeAllViews()
        val n = pagesAdapter.itemCount
        if (n <= 1) return
        for (i in 0 until n) {
            val d = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat(); setColor(if (i == sel) accent() else ColorUtils.setAlphaComponent(accent(), 0x66))
                }
            }
            dots.addView(d, LinearLayout.LayoutParams(if (i == sel) dp(16) else dp(6), dp(6)).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
    }

    /** folders with <2 items collapse, pages overflow forward, empty pages vanish; then persist. */
    private fun cleanup() {
        var changed = false
        pages.forEach { pg ->
            for (i in pg.indices.reversed()) {
                val e = pg[i]
                if (e is Entry.Folder) {
                    if (e.items.isEmpty()) { pg.removeAt(i); changed = true }
                    else if (e.items.size == 1) { pg[i] = Entry.App(e.items[0]); changed = true }
                }
            }
        }
        var i = 0
        while (i < pages.size) {
            while (pages[i].size > PAGE_CAP) {
                if (i + 1 >= pages.size) pages.add(mutableListOf())
                pages[i + 1].add(0, pages[i].removeAt(pages[i].size - 1)); changed = true
            }
            i++
        }
        var j = pages.size - 1
        while (j >= 0 && pages.size > 1) {
            if (pages[j].isEmpty()) { pages.removeAt(j); changed = true }
            j--
        }
        if (changed) {
            pagesAdapter.notifyDataSetChanged()
            if (pager.currentItem >= pages.size) pager.setCurrentItem(pages.size - 1, false)
            updateDots(pager.currentItem)
        }
        LayoutStore.save(prefs, pages)
    }

    // ───────── actions ─────────
    private fun launch(a: AppItem, v: View) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(a.cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = ActivityOptions.makeClipRevealAnimation(v, 0, 0, v.width, v.height)
        try { startActivity(i, opts.toBundle()) } catch (_: Exception) { return }
        stage.springTo(DynamicAnimation.SCALE_X, .9f, SpringForce.STIFFNESS_LOW, 1f)
        stage.springTo(DynamicAnimation.SCALE_Y, .9f, SpringForce.STIFFNESS_LOW, 1f)
        stage.springTo(DynamicAnimation.ALPHA, 0f, SpringForce.STIFFNESS_MEDIUM, 1f)
    }

    private fun onWorkspaceClick(e: Entry, v: View) {
        when (e) {
            is Entry.App -> launch(e.item, v)
            is Entry.Folder -> openFolder(e, v)
            else -> {}
        }
    }

    private fun showMenu(a: AppItem, v: View) {
        val inDock = dockApps(allApps).any { it.cn == a.cn }
        PopupMenu(this, v).apply {
            menu.add(0, 1, 0, "معلومات التطبيق")
            menu.add(0, 2, 1, "إلغاء التثبيت")
            menu.add(0, 4, 2, if (inDock) "شيله من الدوك" else "ثبّته في الدوك")
            menu.add(0, 3, 3, "إعدادات اللانشر")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.cn.packageName}")))
                    2 -> startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.cn.packageName}")))
                    3 -> showSettings()
                    4 -> toggleDock(a)
                }
                true
            }
        }.show()
    }

    private fun showEntryMenu(e: Entry, v: View) {
        if (e is Entry.App) { showMenu(e.item, v); return }
        if (e !is Entry.Folder) return
        PopupMenu(this, v).apply {
            menu.add(0, 1, 0, "فك المجلد")
            menu.add(0, 2, 1, "إعدادات اللانشر")
            setOnMenuItemClickListener {
                if (it.itemId == 1) {
                    for (pg in pages) {
                        val idx = pg.indexOfFirst { x -> x === e }
                        if (idx >= 0) {
                            pg.removeAt(idx)
                            pg.addAll(idx, e.items.map { ap -> Entry.App(ap) })
                            break
                        }
                    }
                    pagesAdapter.notifyDataSetChanged(); cleanup()
                } else showSettings()
                true
            }
        }.show()
    }

    // ───────── folders ─────────
    private fun openFolder(f: Entry.Folder, v: View) {
        closeFolder(false)
        val surface = surfaceColor()
        val onSurface = onSurfaceColor()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000); alpha = 0f
            setOnClickListener { closeFolder(true) }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; alpha = 0f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(32).toFloat(); setColor(ColorUtils.setAlphaComponent(surface, 0xF2))
            }
            isClickable = true
        }
        val title = EditText(this).apply {
            setText(f.name); gravity = Gravity.CENTER; background = null; textSize = 18f
            setSingleLine(); setTextColor(onSurface)
        }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PhoneHomeActivity, 3)
            adapter = AppAdapter(f.items.map { Entry.App(it) as Entry }.toMutableList(), true,
                { e, view -> closeFolder(false); if (e is Entry.App) launch(e.item, view) },
                { e, view -> if (e is Entry.App) startFolderDrag(f, e, view) })
        }
        val rows = ((f.items.size + 2) / 3).coerceIn(1, 4)
        panel.addView(title, LinearLayout.LayoutParams(-1, -2))
        panel.addView(grid, LinearLayout.LayoutParams(-1, dp(96) * rows))
        scrim.addView(panel, FrameLayout.LayoutParams(root.width - dp(64), -2, Gravity.CENTER))
        root.addView(scrim, -1, -1)
        popup = scrim; popupFolder = f; popupTitle = title
        scrim.animate().alpha(1f).setDuration(180).start()
        panel.post {
            val pl = IntArray(2); panel.getLocationOnScreen(pl)
            val il = IntArray(2); v.getLocationOnScreen(il)
            panel.pivotX = il[0] + v.width / 2f - pl[0]; panel.pivotY = il[1] + v.height / 2f - pl[1]
            panel.scaleX = .25f; panel.scaleY = .25f
            panel.springTo(DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_MEDIUM, .75f)
            panel.springTo(DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_MEDIUM, .75f)
            panel.springTo(DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_MEDIUM, 1f)
        }
    }

    private fun closeFolder(animate: Boolean) {
        val s = popup ?: return
        popupFolder?.name = popupTitle?.text?.toString()?.trim().orEmpty().ifEmpty { "مجلد" }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(s.windowToken, 0)
        popup = null; popupFolder = null; popupTitle = null
        if (animate) s.animate().alpha(0f).setDuration(140).withEndAction { root.removeView(s) }.start()
        else root.removeView(s)
        pagesAdapter.notifyItemChanged(pager.currentItem)
        LayoutStore.save(prefs, pages)
    }

    private fun startFolderDrag(f: Entry.Folder, e: Entry.App, cell: View) {
        if (drag != null) return
        val loc = IntArray(2); cell.getLocationOnScreen(loc)
        val w = cell.width
        f.items.remove(e.item)
        pages[pager.currentItem].add(Entry.Placeholder)
        closeFolder(false)
        beginDrag(e, loc, w, null, true)
    }

    // ───────── drag & drop ─────────
    private fun onWorkspaceLong(e: Entry, v: View) {
        if (e === Entry.Placeholder || drag != null) return
        val list = pages[pager.currentItem]
        val idx = list.indexOfFirst { it === e }
        if (idx < 0) return
        list[idx] = Entry.Placeholder
        v.alpha = 0f
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        beginDrag(e, loc, v.width, v, false)
    }

    private fun beginDrag(e: Entry, loc: IntArray, cellW: Int, cell: View?, fromFolder: Boolean) {
        drag = e; dragView = cell; dragFromFolder = fromFolder
        dragMoved = false; needCancel = true
        dragX0 = lastX; dragY0 = lastY
        mergeTarget = null; mergeReady = false; edgeDir = 0
        val rl = IntArray(2); root.getLocationOnScreen(rl)
        val g = ImageView(this).apply {
            setImageDrawable(e.makeIcon()); elevation = dp(10).toFloat()
            translationX = loc[0] + cellW / 2f - dp(28) - rl[0]
            translationY = (loc[1] + dp(8) - rl[1]).toFloat()
        }
        root.addView(g, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.LEFT))
        offX = g.translationX - lastX; offY = g.translationY - lastY
        g.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
        ghost = g
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun pageRv(i: Int): RecyclerView? {
        val inner = pager.getChildAt(0) as? RecyclerView ?: return null
        return (inner.findViewHolderForAdapterPosition(i) as? PagesAdapter.PH)?.rv
    }

    private fun clearMerge() {
        mergeView?.let {
            it.springTo(DynamicAnimation.SCALE_X, 1f, SpringForce.STIFFNESS_MEDIUM, .6f)
            it.springTo(DynamicAnimation.SCALE_Y, 1f, SpringForce.STIFFNESS_MEDIUM, .6f)
        }
        mergeView = null; mergeTarget = null; mergeReady = false
    }

    private fun onDragMove(x: Float, y: Float) {
        ghost?.let { it.translationX = x + offX; it.translationY = y + offY }
        if (!dragMoved) {
            if (hypot(x - dragX0, y - dragY0) > dp(12)) dragMoved = true else return
        }
        val now = SystemClock.uptimeMillis()

        // dwell at a screen edge → flip page (new page if at the end)
        val dir = if (x < dp(40)) -1 else if (x > root.width - dp(40)) 1 else 0
        if (dir != 0) {
            if (edgeDir != dir) { edgeDir = dir; edgeSince = now }
            else if (now - edgeSince > 700) { flipPage(dir); edgeSince = now + 400 }
            clearMerge()
            return
        } else edgeDir = 0

        val pi = pager.currentItem
        val rv = pageRv(pi) ?: return
        val list = pages[pi]
        val phIdx = list.indexOfFirst { it === Entry.Placeholder }
        if (phIdx < 0) return
        val loc = IntArray(2); rv.getLocationOnScreen(loc)
        val lx = x - loc[0]; val ly = y - loc[1]
        val child = rv.findChildViewUnder(lx, ly)

        if (child != null) {
            val p = rv.getChildAdapterPosition(child)
            if (p == RecyclerView.NO_POSITION || p == phIdx) { clearMerge(); return }
            val target = list[p]
            val cx = child.left + child.width / 2f
            val cy = child.top + dp(8) + dp(28)
            val inCenter = abs(lx - cx) < child.width * .28f && abs(ly - cy) < dp(34)
            val dragging = drag
            val canMerge = dragging is Entry.App && (target is Entry.App || target is Entry.Folder)
            if (inCenter && canMerge) {
                if (mergeTarget !== target) { clearMerge(); mergeTarget = target; mergeView = child; mergeSince = now }
                else if (!mergeReady && now - mergeSince > 350) {
                    mergeReady = true
                    child.springTo(DynamicAnimation.SCALE_X, 1.18f, SpringForce.STIFFNESS_MEDIUM, .5f)
                    child.springTo(DynamicAnimation.SCALE_Y, 1.18f, SpringForce.STIFFNESS_MEDIUM, .5f)
                    root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                return
            }
            clearMerge()
            if (now - lastReorder > 140) {
                lastReorder = now
                list.removeAt(phIdx); list.add(p, Entry.Placeholder)
                rv.adapter?.notifyItemMoved(phIdx, p)
            }
        } else {
            clearMerge()
            val last = rv.getChildAt(rv.childCount - 1)
            if (last != null && ly >= last.top && now - lastReorder > 140 && phIdx != list.size - 1) {
                lastReorder = now
                list.removeAt(phIdx); list.add(Entry.Placeholder)
                rv.adapter?.notifyItemMoved(phIdx, list.size - 1)
            }
        }
    }

    private fun flipPage(dir: Int) {
        val rtl = root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val step = if (rtl) -dir else dir
        val cur = pager.currentItem
        val target = cur + step
        if (target < 0) return
        if (target >= pages.size) {
            if (pages[cur].count { it !== Entry.Placeholder } == 0) return
            pages.add(mutableListOf()); pagesAdapter.notifyItemInserted(pages.size - 1)
        }
        pages[cur].removeAll { it === Entry.Placeholder }
        pages[target].add(Entry.Placeholder)
        pagesAdapter.notifyItemChanged(cur); pagesAdapter.notifyItemChanged(target)
        pager.setCurrentItem(target, true)
        updateDots(target)
    }

    private fun endDrag() {
        drag = null; dragView = null
        ghost?.let { root.removeView(it) }; ghost = null
        clearMerge(); edgeDir = 0
    }

    private fun onDrop() {
        val e = drag ?: return
        drag = null
        val g = ghost
        val doMerge = mergeReady
        val target = mergeTarget
        clearMerge()

        var pPage = -1; var pIdx = -1
        pages.forEachIndexed { i, l ->
            val k = l.indexOfFirst { it === Entry.Placeholder }
            if (k >= 0) { pPage = i; pIdx = k }
        }
        val pp = pPage; val pi = pIdx

        // tap without moving → restore + context menu
        if (!dragMoved && !dragFromFolder) {
            if (pp >= 0) pages[pp][pi] = e
            val anchor = dragView
            anchor?.alpha = 1f
            endDrag()
            if (anchor != null) showEntryMenu(e, anchor)
            return
        }

        // drop onto another icon/folder → merge
        if (doMerge && target != null && e is Entry.App && pp >= 0) {
            val list = pages[pp]
            list.removeAt(pi)
            val ti = list.indexOfFirst { it === target }
            if (ti >= 0) {
                if (target is Entry.App) list[ti] = Entry.Folder("مجلد", mutableListOf(target.item, e.item))
                else if (target is Entry.Folder) target.items.add(e.item)
            } else list.add(pi.coerceAtMost(list.size), e)
            pagesAdapter.notifyItemChanged(pp)
            if (g != null) g.animate().alpha(0f).scaleX(.3f).scaleY(.3f).setDuration(160)
                .withEndAction { endDrag(); cleanup() }.start()
            else { endDrag(); cleanup() }
            return
        }

        // plain drop into the placeholder slot
        if (pp < 0) pages[pager.currentItem].add(e) else pages[pp][pi] = e
        val rv = if (pp == pager.currentItem) pageRv(pp) else null
        val vh = rv?.findViewHolderForAdapterPosition(pi)
        if (g != null && vh != null) {
            val cl = IntArray(2); vh.itemView.getLocationOnScreen(cl)
            val rl = IntArray(2); root.getLocationOnScreen(rl)
            val tx = cl[0] + vh.itemView.width / 2f - dp(28) - rl[0]
            val ty = (cl[1] + dp(8) - rl[1]).toFloat()
            g.animate().translationX(tx).translationY(ty).scaleX(1f).scaleY(1f).setDuration(170)
                .withEndAction { rv?.adapter?.notifyItemChanged(pi); endDrag(); cleanup() }.start()
        } else {
            if (pp >= 0) pagesAdapter.notifyItemChanged(pp)
            endDrag(); cleanup()
        }
    }

    // ───────── recents ─────────
    private fun loadRecents(): MutableList<RecentCard> {
        val byPkg = allApps.groupBy { it.cn.packageName }
        return RecentsStore.list(prefs).mapNotNull { pkg ->
            // (v772) Joyboy نفسه: يفتح الأيقونة الأساسية (مش المتصفح/يوتيوب)
            val a = (if (pkg == packageName) byPkg[pkg]?.firstOrNull { it.cn.className.endsWith("LaunchSplashActivity") } else null)
                ?: byPkg[pkg]?.firstOrNull() ?: return@mapNotNull null
            val f = RecentsStore.thumbFile(this, pkg)
            RecentCard(a, if (f.exists()) BitmapFactory.decodeFile(f.path) else null)
        }.toMutableList()
    }

    private fun killApp(pkg: String) {
        if (pkg == packageName) return   // (v772) ما نقتلش Joyboy نفسه (ترجمة/تحميل في الخلفية)
        try { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) } catch (_: Exception) {}
    }

    private fun openRecents() {
        if (recents != null || root.width == 0) return
        closeFolder(false)
        if (progress > .01f) animateTo(0f)
        val cards = loadRecents()
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0xB3000000.toInt()); alpha = 0f
            setOnClickListener { closeRecents(true) }
        }
        if (cards.isEmpty()) {
            scrim.addView(TextView(this).apply {
                text = "مفيش تطبيقات حديثة"; setTextColor(Color.WHITE); textSize = 16f
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            root.addView(scrim, -1, -1)
        } else {
            val cardW = (root.width * .62f).toInt()
            val cardH = minOf((cardW * 2f).toInt(), (root.height * .64f).toInt())
            val adapter = RecentsAdapter(cards, cardW, cardH + dp(40)) { c, v -> launch(c.item, v); closeRecents(false) }
            val rv = RecyclerView(this).apply {
                layoutManager = LinearLayoutManager(this@PhoneHomeActivity, RecyclerView.HORIZONTAL, false)
                this.adapter = adapter
                clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
                val side = (root.width - cardW) / 2 - dp(8)
                setPadding(side, 0, side, 0)
            }
            LinearSnapHelper().attachToRecyclerView(rv)
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.UP) {
                override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
                override fun getSwipeEscapeVelocity(d: Float) = d * 3f
                override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                    val pos = vh.bindingAdapterPosition
                    if (pos < 0) return
                    val pkg = adapter.items[pos].item.cn.packageName
                    killApp(pkg); RecentsStore.remove(prefs, this@PhoneHomeActivity, pkg)
                    adapter.items.removeAt(pos); adapter.notifyItemRemoved(pos)
                    if (adapter.items.isEmpty()) scrim.postDelayed({ closeRecents(true) }, 200)
                }
            }).attachToRecyclerView(rv)
            scrim.addView(rv, FrameLayout.LayoutParams(-1, cardH + dp(48), Gravity.CENTER).apply { bottomMargin = dp(40) })
            val clear = TextView(this).apply {
                text = "مسح الكل"; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(28), dp(12), dp(28), dp(12))
                background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0x33FFFFFF) }
                pressEffect()
                setOnClickListener {
                    val n = rv.childCount
                    for (i in 0 until n) rv.getChildAt(i).animate()
                        .translationY(-rv.height.toFloat()).alpha(0f).setStartDelay(i * 40L).setDuration(220).start()
                    scrim.postDelayed({
                        adapter.items.forEach { killApp(it.item.cn.packageName) }
                        RecentsStore.clear(prefs, this@PhoneHomeActivity)
                        closeRecents(true)
                    }, 240L + n * 40L)
                }
            }
            scrim.addView(clear, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(72) })
            root.addView(scrim, -1, -1)
            rv.alpha = 0f; rv.translationY = dp(120).toFloat()
            rv.springTo(DynamicAnimation.ALPHA, 1f, SpringForce.STIFFNESS_LOW, 1f)
            rv.springTo(DynamicAnimation.TRANSLATION_Y, 0f, SpringForce.STIFFNESS_LOW, .8f)
        }
        recents = scrim
        scrim.animate().alpha(1f).setDuration(180).start()
    }

    private fun closeRecents(animate: Boolean) {
        val s = recents ?: return
        recents = null
        if (animate) s.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(s) }.start()
        else root.removeView(s)
    }

    // ───────── settings ─────────
    private fun gestureServiceOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, GestureService::class.java)
        return s.contains(cn.flattenToString()) || s.contains(cn.flattenToShortString())
    }

    private fun showSettings() {
        val gState = if (!gestureServiceOn()) "غير مفعّلة" else if (prefs.getBoolean("gestures", true)) "شغّالة" else "مقفولة"
        val rows = listOf<Pair<String, () -> Unit>>(
            "🖼 غيّر الخلفية دلوقتي" to { nextWall() },
            "🧹 تنضيف الرام" to { cleanRam() },
            "🧩 الودجت" to { widgets.open() },
            "🎨 ستور الثيمات" to { studio.openStore() },
            "حزمة الأيقونات (المثبّتة على الجهاز)" to { pickPack() },
            "تحميل حزم أيقونات من Google Play" to { openPlayPacks() },
            ("أيقونات Material You: " + if (prefs.getBoolean("themed", true)) "شغّال" else "مقفول") to {
                prefs.edit().putBoolean("themed", !prefs.getBoolean("themed", true)).apply(); reload()
            },
            "خلفية النظام (اختيار من الجهاز)" to { startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "الخلفية")) },
            ("إيماءات الشاشة الكاملة: $gState") to {
                if (!gestureServiceOn()) {
                    Toast.makeText(this, "فعّل «Joyboy Launcher Gestures» من إمكانية الوصول", Toast.LENGTH_LONG).show()
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else prefs.edit().putBoolean("gestures", !prefs.getBoolean("gestures", true)).apply()
            },
            "فتح الأخيرة" to { root.post { openRecents() } },
            (if (isDefaultHome()) "✅ ده التطبيق الافتراضي للشاشة الرئيسية" else "⭐ خليه التطبيق الافتراضي") to { askDefault() }
        )
        AlertDialog.Builder(this).setItems(rows.map { it.first }.toTypedArray()) { _, w -> rows[w].second() }.show()
    }

    // ───────── (v773) قايمة الضغطة الطويلة · ثيم الذكاء · الافتراضي · حزم Play ─────────
    private fun showHomeMenu() {
        homeMenu?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF2242428.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        fun row(t: String, f: () -> Unit) {
            box.addView(TextView(this).apply {
                text = t; textSize = 15f; setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                pressEffect()
                setOnClickListener { homeMenu?.dismiss(); f() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        row("⚙️  الإعدادات") { showSettings() }
        row("🧩  الودجت") { widgets.open() }
        row("🎨  ستور الثيمات") { studio.openStore() }
        row("🖼  تغيير الخلفية") { nextWall() }
        val w = dp(210)
        box.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val h = box.measuredHeight
        val x = (lastX - w / 2f).toInt().coerceIn(dp(8), (root.width - w - dp(8)).coerceAtLeast(dp(8)))
        val y = (lastY - h - dp(12)).toInt().coerceIn(dp(48), (root.height - h - dp(48)).coerceAtLeast(dp(48)))
        val pw = PopupWindow(box, w, -2, true).apply {
            elevation = dp(8).toFloat(); isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(0))
        }
        pw.showAtLocation(root, Gravity.NO_GRAVITY, x, y)
        homeMenu = pw
    }

    private fun openPlayPacks() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=icon%20pack&c=apps")))
        } catch (_: Throwable) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=icon%20pack&c=apps"))) } catch (_: Throwable) {}
        }
    }

    private fun isDefaultHome(): Boolean {
        val ri = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName && ri?.activityInfo?.name == PhoneHomeActivity::class.java.name
    }

    @Suppress("DEPRECATION")
    private fun askDefault() {
        if (isDefaultHome()) { Toast.makeText(this, "هو التطبيق الافتراضي بالفعل", Toast.LENGTH_SHORT).show(); return }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME)) {
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQ_ROLE)
                    return
                }
            }
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Throwable) {
            Toast.makeText(this, "افتح إعدادات الأندرويد ← التطبيقات الافتراضية ← الشاشة الرئيسية", Toast.LENGTH_LONG).show()
        }
    }

    /** مرة واحدة بس: لو اللانشر اتفتح وهو مش الافتراضي */
    private fun maybeAskDefault() {
        if (isDefaultHome() || prefs.getBoolean("asked_default", false) || isFinishing) return
        prefs.edit().putBoolean("asked_default", true).apply()
        AlertDialog.Builder(this).setTitle("خليه التطبيق الافتراضي؟")
            .setMessage("Joyboy Launcher مش هو الشاشة الرئيسية دلوقتي.")
            .setPositiveButton("أيوه") { _, _ -> askDefault() }
            .setNegativeButton("بعدين", null).show()
    }

    // ───────── (v772) الخلفية · المواقيت · الرام ─────────
    private fun attachWall() {
        try {
            for (v in wallViews) root.removeView(v)
            wallViews = emptyList()
            // (v775) الثيم له خلفيته الخاصة ← مفيش داعي لتحميل خلفية Joyboy تحتها
            if (HomeThemeStore.cur?.hasWall == true) { AppWall.detach(this); return }
            val before = root.childCount
            AppWall.attach(this, root, Themes.byId(Cfg.str("theme", "mx")))
            wallViews = (before until root.childCount).map { root.getChildAt(it) }
        } catch (e: Throwable) { LogStore.err("PhoneHome:wall", e) }
    }

    /** Main/المتصفح بيمسكوا AppWall وقت ما يفتحوا — نرجّعه للانشر لما نرجع للشاشة الرئيسية */
    private fun rebindWall() {
        if (AppWall.ownedBy(this)) return
        attachWall()
        // الصورة + طبقة التعتيم لازم يرجعوا تحت الواجهة
        for (v in wallViews.reversed()) { root.removeView(v); root.addView(v, 0) }
    }

    /** (v775) خلفية الثيم: صورة Bing اللي اتحفظت معاه (أو تدرّج ألوانه) + طبقة تعتيم خفيفة */
    private fun attachThemeWall() {
        try {
            for (v in themeWallViews) root.removeView(v)
            themeWallViews = emptyList()
            val t = HomeThemeStore.cur ?: return
            if (!t.hasWall) return
            val bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val bmp = HomeThemeStore.wallFile(this, t)?.let { ThemeWall.decodeFile(it, 1920) }
            if (bmp != null) bg.setImageBitmap(bmp) else bg.setImageDrawable(ThemeWall.gradient(t))
            val scrim = View(this).apply { setBackgroundColor(0x59000000) }
            root.addView(bg, 0, FrameLayout.LayoutParams(-1, -1))
            root.addView(scrim, 1, FrameLayout.LayoutParams(-1, -1))
            themeWallViews = listOf(bg, scrim)
        } catch (e: Throwable) { LogStore.err("PhoneHome:themewall", e) }
    }

    /** زرار «غيّر الخلفية»: لو للثيم خلفية يجيب صورة تانية بنفس كلماته، وإلا خلفية Joyboy العادية */
    private fun nextWall() {
        val t = HomeThemeStore.cur
        if (t != null && t.hasWall) studio.nextWall(t) { attachThemeWall() } else AppWall.nextNow(this)
    }

    private fun loadPrayer(force: Boolean) {
        if (!force && System.currentTimeMillis() - prayerAt < 20 * 60_000L) return
        prayerAt = System.currentTimeMillis()
        PrayerTimes.loadData(this, force) { d ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (d != null) clock.setData(d)
                else {
                    prayerAt = 0L
                    if (force) Notice.show(this, "مقدرتش أجيب مواقيت الصلاة (اتأكد من النت)", 2200L)
                }
            }
        }
    }

    private fun gb(mb: Long): String = String.format(java.util.Locale.US, "%.1f", mb / 1024.0)

    private fun updateRamLabel() {
        val a = try { RamCleaner.info(this).first } catch (_: Throwable) { return }
        clock.setRamLabel("🧹 رام · " + gb(a) + " جيجا فاضية")
    }

    private fun cleanRam() {
        if (ramBusy) return
        ramBusy = true
        clock.setRamLabel("🧹 بنضّف…")
        try { com.bumptech.glide.Glide.get(this).clearMemory() } catch (_: Throwable) {}   // كاش صور Joyboy نفسه
        Thread {
            val r = try { RamCleaner.clean(this) } catch (e: Throwable) { LogStore.err("PhoneHome:ram", e); null }
            runOnUiThread {
                ramBusy = false
                if (isDestroyed || isFinishing) return@runOnUiThread
                updateRamLabel()
                val msg = if (r == null) "مقدرتش أنضّف الرام"
                    else if (r.freedMb >= 30L) "🧹 اتحرّر " + r.freedMb + " ميجا — الفاضي دلوقتي " + gb(r.availMb) + " من " + gb(r.totalMb) + " جيجا"
                    else "الرام نضيفة أصلًا (الفاضي " + gb(r.availMb) + " جيجا)"
                Notice.show(this, msg, 2800L)
            }
        }.apply { isDaemon = true }.start()
    }

    private val REQ_ROLE = 7703

    private fun pickPack() {
        val packs = IconPack.installed(this)
        val names = (listOf("الافتراضي") + packs.map { it.second }).toTypedArray()
        AlertDialog.Builder(this).setTitle("حزمة الأيقونات").setItems(names) { _, w ->
            prefs.edit().apply { if (w == 0) remove("pack") else putString("pack", packs[w - 1].first) }.apply()
            reload()
        }.show()
    }
}
