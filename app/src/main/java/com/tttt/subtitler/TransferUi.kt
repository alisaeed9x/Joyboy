package com.tttt.subtitler

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * (v607) مركز النقل — شاشة واحدة للموبايل والتلفزيون:
 *  📤 إرسال: اختار ملفات (فيديوهات · صوتيات · صور · APK · تطبيقات مثبتة) وابعتها للتلفزيون/الموبايل أو لأي تطبيق تاني (مشاركة)
 *  📥 استقبال: (موبايل) تصفّح كتالوج التلفزيون ونزّل منه، بما فيه اللي التلفزيون «عرضه» عليك
 *  📂 الواصل: الملفات اللي وصلت في فولدراتها (فتح · مشاركة · إرسال · حذف)
 *  وكمان هدف مشاركة: أي تطبيق يشارك ملفات لـ«Joyboy نقل» تتحفظ في فولدرها أو تتبعت للتلفزيون.
 */
class TransferActivity : Activity() {
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private val h = Handler(Looper.getMainLooper())
    private val tvMode: Boolean by lazy { try { DeviceUi.isTv(this) } catch (_: Throwable) { false } }

    private var mode = 0                       // 0 إرسال · 1 استقبال · 2 الواصل
    private var cat = XCat.VIDEO
    private val sel = LinkedHashSet<String>()
    private var rows: List<Row> = emptyList()
    private var gen = 0
    private var needAccess = false
    private var infoMsg = ""

    private class Row(
        val key: String, val title: String, val sub: String, val size: Long, val cat: String,
        val ent: XEntry? = null, val rem: XRemote.Item? = null, val file: File? = null,
        val header: Boolean = false, val group: String = ""
    )

    private lateinit var statusTv: TextView
    private lateinit var tabsBox: LinearLayout
    private lateinit var catBox: LinearLayout
    private lateinit var info: TextView
    private lateinit var accessBtn: TextView
    private lateinit var list: ListView
    private lateinit var actionsBox: LinearLayout
    private val adapter = RowAdapter()

    // ===== (v609) رادار البحث عن التلفزيونات (موبايل بس) =====
    private var radar: RadarView? = null
    private var radarTxt: TextView? = null
    private var radarSub: TextView? = null
    private var radarOff: TextView? = null
    private var scanning = false
    private var foundTvs: List<RemoteClient.Found> = emptyList()
    private var pairing = false
    private val rcListener: () -> Unit = { if (!isFinishing && !isDestroyed) refreshRadar() }

    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK
    private val thumbs = object : LruCache<String, Bitmap>(160) {}
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r).also { it.isDaemon = true } }

    // ───────── دورة الحياة ─────────
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        try { RemoteClient.init(this) } catch (_: Throwable) {}
        try { DeviceUi.applyOrientation(this) } catch (_: Throwable) {}
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        try { window.setBackgroundDrawable(ColorDrawable(th.bg)); window.statusBarColor = th.bg; window.navigationBarColor = th.bg } catch (_: Throwable) {}
        FileCatalog.cleanTmp(this)
        buildUi()
        handleIntent(intent)
        reload()
        if (!tvMode) {
            try { RemoteClient.addListener(rcListener) } catch (_: Throwable) {}
            refreshRadar()
            if (RemoteClient.state != RemoteClient.St.CONNECTED) scan()   // بحث تلقائي أول ما تفتح الشاشة
        }
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIntent(i) }

    override fun onResume() {
        super.onResume()
        updateStatus()
        if (needAccess && hasAccess()) reload()
    }

    override fun onDestroy() { gen++; h.removeCallbacksAndMessages(null); try { RemoteClient.removeListener(rcListener) } catch (_: Throwable) {}; super.onDestroy() }

    // ───────── الصلاحيات ─────────
    private fun hasAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true
        return if (Build.VERSION.SDK_INT >= 33)
            listOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO, android.Manifest.permission.READ_MEDIA_IMAGES)
                .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun askAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            else requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 31)
        } catch (e: Throwable) { LogStore.err("Transfer:perm", e); Notice.show(this, "⚠ مش قادر أفتح إعدادات الصلاحية", 2600L) }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (hasAccess()) reload()
    }

    // ───────── مكوّنات الواجهة ─────────
    private fun lp(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt)

    private fun pill(label: String, on: Boolean, f: () -> Unit): TextView = IconTextView(this).apply {
        text = label; textSize = if (tvMode) 18f else 13f; gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1
        setPadding(ui.dp(if (tvMode) 20 else 12), ui.dp(if (tvMode) 12 else 10), ui.dp(if (tvMode) 20 else 12), ui.dp(if (tvMode) 12 else 10))
        fun paint(foc: Boolean) {
            setTextColor(if (on) onPrimary else th.text)
            background = ui.box(if (on) th.primary else th.surface, if (foc) th.accent else if (on) th.primary else th.border, 20, if (foc) 3 else 1)
        }
        paint(false)
        setOnFocusChangeListener { _, foc -> paint(foc) }
        setOnClickListener { f() }
    }

    private fun buildUi() {
        val pad = ui.dp(if (tvMode) 28 else 12)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg)
            setPadding(pad, ui.dp(if (tvMode) 20 else 12), pad, ui.dp(8))
        }
        // العنوان
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(ui.text("📤 مركز النقل", if (tvMode) 26f else 20f, th.primary, true))
        statusTv = ui.text("", if (tvMode) 15f else 12f, th.muted)
        titleCol.addView(statusTv)
        head.addView(titleCol, lp(0, -2, 1f))
        head.addView(pill("✕", false) { finish() })
        root.addView(head, lp(-1, -2))
        // (v609) كارت الرادار: بيدوّر على التلفزيونات وتدوس على واحد تتصل بيه (الموبايل بس)
        if (!tvMode) {
            val rv = RadarView(this, th).apply { onTap = { b -> onBlipTap(b) } }
            radar = rv
            val tTxt = ui.text("", 14.5f, th.text, true).apply { maxLines = 2 }
            val tSub = ui.text("", 12f, th.muted).apply { maxLines = 3 }
            radarTxt = tTxt; radarSub = tSub
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            btns.addView(pill("📡 ابحث", true) { scan() }, lp(-2, -2).apply { setMargins(0, 0, ui.dp(6), 0) })
            val off = pill("⏏ قطع", false) { try { RemoteClient.disconnect(this) } catch (_: Throwable) {}; refreshRadar() }
            radarOff = off
            btns.addView(off, lp(-2, -2))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tTxt); col.addView(tSub, lp(-2, -2).apply { topMargin = ui.dp(2) }); col.addView(btns, lp(-2, -2).apply { topMargin = ui.dp(8) })
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
                background = ui.box(th.card, th.border, 14); setPadding(ui.dp(8), ui.dp(6), ui.dp(10), ui.dp(6))
            }
            card.addView(rv, lp(ui.dp(150), ui.dp(150)))
            card.addView(col, lp(0, -2, 1f).apply { marginStart = ui.dp(8) })
            root.addView(card, lp(-1, -2).apply { topMargin = ui.dp(8) })
        }
        // التابات الرئيسية
        tabsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(tabsBox, lp(-1, -2).apply { topMargin = ui.dp(8) })
        // التصنيفات
        catBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(catBox, FrameLayout.LayoutParams(-2, -2)) }
        root.addView(hs, lp(-1, -2).apply { topMargin = ui.dp(6) })
        // معلومات + صلاحية
        info = ui.text("", if (tvMode) 15f else 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4)) }
        root.addView(info, lp(-1, -2))
        accessBtn = pill("🔓 اسمح بالوصول للملفات", true) { askAccess() }.apply { visibility = View.GONE }
        root.addView(accessBtn, lp(-1, -2).apply { bottomMargin = ui.dp(4) })
        // القايمة
        list = ListView(this).apply {
            adapter = this@TransferActivity.adapter
            divider = null; dividerHeight = ui.dp(2); isFocusable = true; cacheColorHint = Color.TRANSPARENT
            val hi = ui.box(th.surface, th.accent, 12, 2)
            selector = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), hi)
                addState(intArrayOf(android.R.attr.state_focused), hi)
                addState(intArrayOf(android.R.attr.state_selected), hi)
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            setOnItemClickListener { _, _, p, _ -> onRowClick(p) }
        }
        root.addView(list, lp(-1, 0, 1f))
        // الأزرار
        actionsBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val ahs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actionsBox, FrameLayout.LayoutParams(-2, -2)) }
        root.addView(ahs, lp(-1, -2).apply { topMargin = ui.dp(6) })
        setContentView(root)
    }

    private fun renderTabs() {
        tabsBox.removeAllViews()
        val tabs = listOf("📤 إرسال", "📥 استقبال", "📂 الواصل")
        tabs.forEachIndexed { i, t ->
            tabsBox.addView(pill(t, mode == i) { if (mode != i) { mode = i; if (mode == 2 && cat == XCat.APP) cat = XCat.APK; reload() } }, lp(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
    }

    private fun renderCats() {
        catBox.removeAllViews()
        val cats = if (mode == 2) XCat.INBOX else XCat.SEND
        if (cat !in cats) cat = cats[0]
        for (c in cats) catBox.addView(pill(XCat.label(c), cat == c) { if (cat != c) { cat = c; reload() } }, lp(-2, -2).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) })
    }

    private fun renderActions() {
        actionsBox.removeAllViews()
        fun add(label: String, primary: Boolean = false, f: () -> Unit) {
            actionsBox.addView(pill(label, primary, f), lp(-2, -2).apply { setMargins(ui.dp(3), ui.dp(2), ui.dp(3), ui.dp(2)) })
        }
        when (mode) {
            0 -> {
                add("☑ الكل") { toggleAll() }
                if (tvMode) add("📱 اعرض للموبايل", true) { offerToPhone() } else add("📺 للتلفزيون", true) { sendToTv() }
                add("↗ لتطبيق تاني") { shareSelected() }
                add("➕ من الجهاز") { pickDocs() }
            }
            1 -> {
                if (tvMode) {
                    add("📂 افتح الواصل", true) { mode = 2; reload() }
                } else {
                    add("☑ الكل") { toggleAll() }
                    add("⬇ نزّل المحدد", true) { downloadSelected() }
                    add("🔄 تحديث") { reload() }
                }
            }
            else -> {
                add("☑ الكل") { toggleAll() }
                add("▶ فتح", true) { openSelected() }
                add("↗ مشاركة") { shareSelected() }
                if (tvMode) add("📱 اعرض للموبايل") { offerToPhone() } else add("📺 للتلفزيون") { sendToTv() }
                add("🗑 حذف") { deleteSelected() }
            }
        }
    }

    private fun updateStatus() {
        statusTv.text = if (tvMode) "📺 التلفزيون — الموبايل يبعتلك من مركز النقل ← إرسال ← للتلفزيون" else "الإرسال لتطبيق تاني شغّال حتى من غير تلفزيون"
        if (!tvMode) refreshRadar()
    }

    private fun updateInfo() {
        val real = rows.count { !it.header }
        val s = if (infoMsg.isNotEmpty()) infoMsg else if (real == 0) "مفيش ملفات هنا" else "$real عنصر" + (if (sel.isNotEmpty()) " · اتحدّد " + sel.size else "")
        info.text = s
        accessBtn.visibility = if (needAccess) View.VISIBLE else View.GONE
    }

    // ───────── التحميل ─────────
    private fun reload() {
        val my = ++gen
        sel.clear(); rows = emptyList(); needAccess = false; infoMsg = ""
        adapter.notifyDataSetChanged()
        renderTabs(); renderCats(); renderActions(); updateStatus()
        when {
            mode == 0 -> loadLocal(my)
            mode == 1 && tvMode -> { infoMsg = "الملفات بتوصل لوحدها لما الموبايل يبعت — شوفها في 📂 الواصل. ولو عاوز تبعت للموبايل: 📤 إرسال ← 📱 اعرض للموبايل، والموبايل ينزّلها من 📥 استقبال."; updateInfo() }
            mode == 1 -> loadRemote(my)
            else -> loadInbox(my)
        }
    }

    private fun grouped(items: List<Row>): List<Row> {
        val order = LinkedHashMap<String, MutableList<Row>>()
        for (r in items) order.getOrPut(r.group) { ArrayList() }.add(r)
        val out = ArrayList<Row>()
        for ((g, l) in order) {
            out.add(Row("h:$g", "📁 " + (if (g.isEmpty()) "(بدون فولدر)" else g) + "  (" + l.size + ")", "", 0L, "", header = true, group = g))
            out.addAll(l)
        }
        return out
    }

    private fun entSub(e: XEntry): String = when (e.cat) {
        XCat.APP -> (if (e.sub.isNotEmpty()) "v" + e.sub + " · " else "") + Sniff.fmtSize(e.size) + (if (e.splits) " · APK مقسّم (.apks)" else "")
        else -> Sniff.fmtSize(e.size)
    }

    private fun deliver(my: Int, built: List<Row>, noAccess: Boolean, msg: String = "") {
        runOnUiThread {
            if (my != gen || isFinishing || isDestroyed) return@runOnUiThread
            rows = built; needAccess = noAccess; infoMsg = msg
            adapter.notifyDataSetChanged(); updateInfo()
            if (tvMode) list.post { try { list.requestFocus() } catch (_: Throwable) {} }
        }
    }

    private fun loadLocal(my: Int) {
        infoMsg = "⏳ بقرا " + XCat.label(cat) + "…"; updateInfo()
        val app = applicationContext; val c = cat
        Thread {
            val items = FileCatalog.list(app, c)
            val noAccess = c != XCat.APP && items.isEmpty() && !hasAccess()
            val base = items.map { Row(it.key, it.name, entSub(it), it.size, it.cat, ent = it, group = it.sub) }
            val built = if (c == XCat.APP) base else grouped(base)
            deliver(my, built, noAccess, if (noAccess) "محتاج صلاحية الوصول للملفات عشان أعرضها" else "")
        }.also { it.isDaemon = true }.start()
    }

    private fun loadRemote(my: Int) {
        infoMsg = "⏳ بجيب كتالوج التلفزيون…"; updateInfo()
        val app = applicationContext; val c = cat
        Thread {
            val (offers, e1) = XRemote.list(app, "outbox")
            val (items, e2) = if (e1 == null) XRemote.list(app, c) else Pair(emptyList<XRemote.Item>(), e1)
            val err = e1 ?: e2
            val out = ArrayList<Row>()
            if (offers.isNotEmpty()) {
                out.add(Row("h:offers", "📬 عروض من التلفزيون (" + offers.size + ")", "", 0L, "", header = true, group = "offers"))
                for (o in offers) out.add(Row("o:" + o.key, o.name, (if (o.size > 0L) Sniff.fmtSize(o.size) else "") , o.size, o.cat, rem = o, group = "offers"))
            }
            val base = items.map { Row("r:" + it.key, it.name, if (it.size > 0L) Sniff.fmtSize(it.size) else (if (it.cat == XCat.APP) "APK" else ""), it.size, it.cat, rem = it, group = it.sub) }
            out.addAll(if (c == XCat.APP) base else grouped(base))
            deliver(my, out, false, if (err != null) "⚠ $err" else "")
        }.also { it.isDaemon = true }.start()
    }

    private fun loadInbox(my: Int) {
        infoMsg = "⏳…"; updateInfo()
        val app = applicationContext; val c = cat
        Thread {
            val dir = Xfer.inboxDir(app, c)
            val files = (dir.listFiles() ?: emptyArray<File>()).filter { it.isFile && !it.name.endsWith(".part") }.sortedByDescending { it.lastModified() }
            val built = files.map { f -> Row(f.absolutePath, f.name, Sniff.fmtSize(f.length()) + " · " + java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US).format(java.util.Date(f.lastModified())), f.length(), c, file = f) }
            deliver(my, built, false, if (built.isEmpty()) "مفيش ملفات واصلة هنا (" + dir.absolutePath + ")" else "📂 " + dir.absolutePath)
        }.also { it.isDaemon = true }.start()
    }

    // ───────── الاختيار ─────────
    private fun onRowClick(p: Int) {
        val r = rows.getOrNull(p) ?: return
        if (r.header) {
            val grp = rows.filter { !it.header && it.group == r.group }.map { it.key }
            if (grp.all { it in sel }) sel.removeAll(grp.toSet()) else sel.addAll(grp)
        } else if (!sel.remove(r.key)) sel.add(r.key)
        adapter.notifyDataSetChanged(); updateInfo()
    }

    private fun toggleAll() {
        val all = rows.filter { !it.header }.map { it.key }
        if (all.isNotEmpty() && all.all { it in sel }) sel.clear() else { sel.clear(); sel.addAll(all) }
        adapter.notifyDataSetChanged(); updateInfo()
    }

    private fun picked(): List<Row> = rows.filter { !it.header && it.key in sel }

    // ───────── الرادار ─────────
    private fun refreshRadar() {
        val rv = radar ?: return
        val st = RemoteClient.state
        val cur = RemoteClient.current
        val paired = try { RemoteClient.pairedList() } catch (_: Throwable) { emptyList() }
        val blips = ArrayList<RadarView.Blip>()
        for (f in foundTvs) blips.add(RadarView.Blip(f.id, f.name, paired.any { it.id == f.id }, true, f))
        for (t in paired) if (blips.none { it.id == t.id }) blips.add(RadarView.Blip(t.id, t.name, true, false, null))
        rv.blips = blips
        rv.connectedId = if (st == RemoteClient.St.CONNECTED) (cur?.id ?: "") else ""
        rv.scanning = scanning || st == RemoteClient.St.CONNECTING
        val connected = st == RemoteClient.St.CONNECTED
        val (head, sub) = when {
            connected -> Pair("🟢 متصل بـ «" + (cur?.name ?: "التلفزيون") + "»", "جاهز: ابعت واستقبل ملفات على الواي فاي.")
            st == RemoteClient.St.CONNECTING -> Pair("🟡 بوصّل بـ «" + (cur?.name ?: "التلفزيون") + "»…", "")
            pairing -> Pair("🟡 بقترن…", "")
            scanning -> Pair("🟡 بدوّر على التلفزيونات…", "اتأكد إن التلفزيون شغّال وعلى نفس الواي فاي.")
            st == RemoteClient.St.LOST -> Pair("🟠 الاتصال انقطع", "بحاول أرجّع تلقائي — أو دوس على التلفزيون في الرادار.")
            foundTvs.isNotEmpty() -> Pair("🔴 مش متصل — لقيت " + foundTvs.size + " تلفزيون", "دوس على التلفزيون في الرادار عشان تتصل.")
            blips.isNotEmpty() -> Pair("🔴 مش متصل", "التلفزيونات المحفوظة مش ظاهرة دلوقتي — دوس 📡 ابحث أو دوس عليها.")
            else -> Pair("🔴 مفيش تلفزيون", "دوس 📡 ابحث. أول مرة هتحتاج كود الاقتران اللي بيظهر على التلفزيون.")
        }
        radarTxt?.text = head
        radarSub?.text = if (sub.isNotEmpty()) sub else (if (st == RemoteClient.St.CONNECTING && RemoteClient.lastError.isNotEmpty()) RemoteClient.lastError else "")
        radarOff?.visibility = if (connected || st == RemoteClient.St.CONNECTING) View.VISIBLE else View.GONE
        // لما يتصل الرادار يصغّر عشان القايمة تاخد مكان
        val sz = ui.dp(if (connected) 96 else 150)
        (rv.layoutParams as? LinearLayout.LayoutParams)?.let { if (it.width != sz || it.height != sz) { it.width = sz; it.height = sz; rv.layoutParams = it } }
    }

    private fun scan() {
        if (tvMode || scanning) return
        scanning = true; refreshRadar()
        Thread {
            val fs = try { RemoteClient.discover(4500L) } catch (e: Throwable) { LogStore.err("Transfer:scan", e); emptyList() }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                scanning = false; foundTvs = fs; refreshRadar()
                // تلفزيون واحد مقترن اتلاقى ومفيش اتصال: اتصل تلقائي
                val one = fs.singleOrNull()
                if (one != null && RemoteClient.state != RemoteClient.St.CONNECTED && RemoteClient.state != RemoteClient.St.CONNECTING &&
                    (try { RemoteClient.pairedList().any { it.id == one.id } } catch (_: Throwable) { false })) connectFound(one)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun onBlipTap(b: RadarView.Blip) {
        if (pairing) return
        val f = b.found
        if (f != null) { connectFound(f); return }
        // محفوظ بس مش ظاهر: جرّب بآخر عنوان معروف
        val tv = try { RemoteClient.pairedList().firstOrNull { it.id == b.id } } catch (_: Throwable) { null } ?: return
        Notice.show(this, "🟡 بحاول أوصل بـ «" + tv.name + "»…", 2200L)
        try { RemoteClient.connect(applicationContext, tv, true) } catch (e: Throwable) { LogStore.err("Transfer:connect", e) }
        refreshRadar()
    }

    private fun connectFound(f: RemoteClient.Found) {
        val tv = try { RemoteClient.pairedList().firstOrNull { it.id == f.id } } catch (_: Throwable) { null }
        if (tv != null) {
            try { tv.ip = f.ip; tv.port = f.port; RemoteClient.saveTv(tv); RemoteClient.connect(applicationContext, tv, true) } catch (e: Throwable) { LogStore.err("Transfer:connect", e) }
            Notice.show(this, "🟡 بوصّل بـ «" + f.name + "»…", 2200L); refreshRadar(); return
        }
        pairFlow(f, "")
    }

    private fun pairFlow(f: RemoteClient.Found, code: String) {
        pairing = true; refreshRadar()
        Thread {
            val r = try { RemoteClient.pair(f, code) } catch (e: Throwable) { LogStore.err("Transfer:pair", e); Pair(null, "حصلت مشكلة") }
            runOnUiThread {
                pairing = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                val tv = r.first
                when {
                    tv != null -> { try { RemoteClient.connect(applicationContext, tv, true) } catch (e: Throwable) { LogStore.err("Transfer:connect", e) }; Notice.show(this, "✅ اتقرنت بـ «" + tv.name + "»", 2400L) }
                    r.second == "need_code" -> askCode(f)
                    r.second == "locked" -> Notice.show(this, "🔒 التلفزيون قافل الاقتران دلوقتي — جرّب بعد شوية", 3500L)
                    else -> Notice.show(this, "⚠ " + r.second, 3500L)
                }
                refreshRadar()
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun askCode(f: RemoteClient.Found) {
        val et = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER; hint = "الكود"; gravity = Gravity.CENTER; setTextColor(th.text); setHintTextColor(th.muted) }
        GAlert(this).setTitle("📺 كود الاقتران").setMessage("اكتب الكود الظاهر على شاشة التلفزيون (" + f.name + ")").setView(et)
            .setPositiveButton("اقتران") { _, _ -> pairFlow(f, et.text.toString().trim()) }
            .setNegativeButton("إلغاء", null).show()
    }

    // ───────── الإرسال ─────────
    private fun collectSend(done: (List<SendFile>) -> Unit) {
        val pk = picked()
        if (pk.isEmpty()) { Notice.show(this, "اختار ملفات الأول (دوسة على الملف)", 2200L); return }
        val app = applicationContext
        Thread {
            val out = ArrayList<SendFile>()
            for (r in pk) {
                val e = r.ent
                if (e != null) FileCatalog.materialize(app, e)?.let { out.add(it) }
                else { val f = r.file; if (f != null) out.add(SendFile(f.name, Uri.fromFile(f), f.length(), r.cat)) }
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) done(out) }
        }.also { it.isDaemon = true }.start()
    }

    private fun sendToTv() {
        val paired = try { RemoteClient.lastTv() != null } catch (_: Throwable) { false }
        if (!paired) { Notice.show(this, "⚠ مفيش تلفزيون مقترن — دوس على التلفزيون في الرادار فوق عشان تقترن", 3600L); scan(); return }
        collectSend { files ->
            if (files.isEmpty()) { Notice.show(this, "⚠ ما قدرتش أجهّز الملفات للإرسال", 2600L); return@collectSend }
            XferUi.sendUris(this, files.map { it.uri }, files.map { it.name })
            sel.clear(); adapter.notifyDataSetChanged(); updateInfo()
        }
    }

    /** (تلفزيون) يعرض الملفات المحددة للموبايل: الموبايل بينزّلها من 📥 استقبال ← عروض من التلفزيون */
    private fun offerToPhone() {
        val pk = picked()
        if (pk.isEmpty()) { Notice.show(this, "اختار ملفات الأول", 2200L); return }
        val ents = pk.mapNotNull { r -> r.ent ?: r.file?.let { FileCatalog.fileEntry(it, r.cat) } }
        TvOutbox.add(ents)
        Notice.show(this, "📱 اتعرض " + ents.size + " ملف — افتح مركز النقل على الموبايل ← 📥 استقبال", 4200L)
        sel.clear(); adapter.notifyDataSetChanged(); updateInfo()
    }

    private fun downloadSelected() {
        val items = picked().mapNotNull { it.rem }
        if (items.isEmpty()) { Notice.show(this, "اختار ملفات من التلفزيون الأول", 2200L); return }
        if (XRemote.download(applicationContext, items)) {
            Notice.show(this, "⬇ بدأ تنزيل " + items.size + " ملف — التقدم في زرار المهام، والملفات في فولدراتها (Joyboy)", 4200L)
            sel.clear(); adapter.notifyDataSetChanged(); updateInfo()
        } else Notice.show(this, "⚠ مش متصل بالتلفزيون", 2600L)
    }

    // ───────── المشاركة مع تطبيق تاني ─────────
    private fun shareSelected() { collectSend { files -> shareFiles(files) } }

    private fun shareFiles(files: List<SendFile>) {
        val uris = ArrayList<Uri>()
        val tmp = File(cacheDir, "xfer_tmp").also { it.mkdirs() }
        for (f in files) {
            try {
                val u = if (f.uri.scheme == "file") {
                    val src = File(f.uri.path ?: continue)
                    val shared = if (src.name == f.name) src else File(tmp, f.name).also { src.copyTo(it, true) }
                    FmUtil.uriFor(this, shared)
                } else f.uri
                uris.add(u)
            } catch (e: Throwable) { LogStore.err("Transfer:share", e) }
        }
        if (uris.isEmpty()) { Notice.show(this, "⚠ ما قدرتش أجهّز الملفات للمشاركة", 2600L); return }
        val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
        i.type = if (files.size == 1) Xfer.mimeOf(files[0].name).let { if (it == "application/octet-stream") "*/*" else it } else "*/*"
        if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cd = ClipData.newRawUri("files", uris[0])
        for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k]))
        i.clipData = cd
        try { startActivity(Intent.createChooser(i, "ابعت لـ…")) } catch (e: Throwable) { LogStore.err("Transfer:chooser", e); Notice.show(this, "⚠ مفيش تطبيق يستقبل", 2400L) }
    }

    // ───────── استقبال من تطبيق تاني / اختيار ملفات من الجهاز ─────────
    @Suppress("DEPRECATION")
    private fun handleIntent(intentIn: Intent?) {
        val src = intentIn ?: return
        val a = src.action ?: return
        if (a != Intent.ACTION_SEND && a != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        try {
            if (a == Intent.ACTION_SEND) {
                val u = src.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (u != null) uris.add(u)
            } else {
                val l = src.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (l != null) uris.addAll(l)
            }
        } catch (_: Throwable) {}
        val cd = src.clipData
        if (uris.isEmpty() && cd != null) { for (k in 0 until cd.itemCount) { val u = cd.getItemAt(k).uri; if (u != null) uris.add(u) } }
        setIntent(Intent(this, TransferActivity::class.java))   // استهلكناه: لف الشاشة ما يعيدش السؤال
        if (uris.isEmpty()) { h.post { Notice.show(this, "⚠ مفيش ملفات في اللي اتشارك (اللينكات: استخدم «ابعت للتلفزيون»)", 3200L) }; return }
        h.post { incomingDialog(uris, "وصل " + uris.size + " ملف من تطبيق تاني") }
    }

    private fun incomingDialog(uris: List<Uri>, title: String) {
        val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        labels.add("💾 احفظهم في فولدراتهم (فيديوهات · صوتيات · صور · APK)"); acts.add { XIn.save(applicationContext, uris); mode = 2; reload(); Notice.show(this, "💾 بيتحفظوا… متقفلش الشاشة لحد ما يخلصوا", 3200L) }
        if (!tvMode) { labels.add("📺 ابعتهم للتلفزيون"); acts.add { sendUrisToTv(uris) } }
        labels.add("↗ شاركهم لتطبيق تاني"); acts.add { shareRaw(uris) }
        GAlert(this).setTitle(title).setItems(labels.toTypedArray()) { _, i -> acts[i]() }.setNegativeButton("إلغاء", null).show()
    }

    private fun sendUrisToTv(uris: List<Uri>) {
        val paired = try { RemoteClient.lastTv() != null } catch (_: Throwable) { false }
        if (!paired) { Notice.show(this, "⚠ مفيش تلفزيون مقترن — دوس على التلفزيون في الرادار فوق عشان تقترن", 3600L); scan(); return }
        XferUi.sendUris(this, uris)
    }

    private fun shareRaw(uris: List<Uri>) {
        val i = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        if (uris.size == 1) i.putExtra(Intent.EXTRA_STREAM, uris[0]) else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val cd = ClipData.newRawUri("files", uris[0]); for (k in 1 until uris.size) cd.addItem(ClipData.Item(uris[k])); i.clipData = cd
        try { startActivity(Intent.createChooser(i, "ابعت لـ…")) } catch (_: Throwable) { Notice.show(this, "⚠ مفيش تطبيق يستقبل", 2400L) }
    }

    private fun pickDocs() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }, 71)
        } catch (e: Throwable) { LogStore.err("Transfer:pick", e); Notice.show(this, "⚠ مفيش منتقي ملفات على الجهاز ده", 2600L) }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 71 || res != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val cd = data.clipData
        if (cd != null) { for (k in 0 until cd.itemCount) { val u = cd.getItemAt(k).uri; if (u != null) uris.add(u) } }
        else { val u = data.data; if (u != null) uris.add(u) }
        if (uris.isNotEmpty()) incomingDialog(uris, "اخترت " + uris.size + " ملف")
    }

    // ───────── الواصل: فتح / حذف ─────────
    private fun openSelected() {
        val pk = picked()
        if (pk.size != 1 || pk[0].file == null) { Notice.show(this, "اختار ملف واحد بس عشان تفتحه", 2400L); return }
        val f = pk[0].file ?: return
        val e = f.extension.lowercase()
        try {
            if (e in FmUtil.VIDEO || e in FmUtil.AUDIO) {
                startActivity(Intent(this, PlayerActivity::class.java).pipTask(this).apply {
                    data = Uri.fromFile(f); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("landscape", e in FmUtil.VIDEO); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
                })
            } else {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(FmUtil.uriFor(this@TransferActivity, f), if (XCat.isApk(f.name) && e == "apk") "application/vnd.android.package-archive" else FmUtil.mime(f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        } catch (ex: Throwable) { LogStore.err("Transfer:open", ex); Notice.show(this, "⚠ مفيش برنامج يفتح الملف ده", 2600L) }
    }

    private fun deleteSelected() {
        val files = picked().mapNotNull { it.file }
        if (files.isEmpty()) { Notice.show(this, "اختار ملفات الأول", 2200L); return }
        GAlert(this).setTitle("🗑 حذف " + files.size + " ملف؟").setMessage("هيتمسحوا من الجهاز نهائيًا.")
            .setPositiveButton("احذف") { _, _ ->
                var n = 0
                for (f in files) if (try { f.delete() } catch (_: Throwable) { false }) n++
                FmUtil.scan(this, files)
                try { VideoScan.cache = null } catch (_: Throwable) {}
                Notice.show(this, "🗑 اتحذف $n", 2000L)
                reload()
            }.setNegativeButton("إلغاء", null).show()
    }

    // ───────── العرض ─────────
    private fun loadThumb(r: Row, key: String, into: ImageView, tagOf: () -> String) {
        thumbs.get(key)?.let { into.setImageBitmap(it); return }
        if (Build.VERSION.SDK_INT < 29) return
        val e = r.ent; val f = r.file
        pool.execute {
            val bmp: Bitmap? = try {
                when {
                    e?.uri != null && e.uri.scheme == "content" -> contentResolver.loadThumbnail(e.uri, Size(120, 120), null)
                    f != null && r.cat == XCat.IMAGE -> android.media.ThumbnailUtils.createImageThumbnail(f, Size(120, 120), null)
                    f != null && r.cat == XCat.VIDEO -> android.media.ThumbnailUtils.createVideoThumbnail(f, Size(120, 120), null)
                    else -> null
                }
            } catch (_: Throwable) { null }
            if (bmp != null) { thumbs.put(key, bmp); runOnUiThread { if (tagOf() == key) into.setImageBitmap(bmp) } }
        }
    }

    private inner class RowView : LinearLayout(this@TransferActivity) {
        val chk: TextView = ui.text("☐", if (tvMode) 26f else 21f, th.primary)
        val img = ImageView(this@TransferActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val emo: TextView = ui.text("", if (tvMode) 26f else 22f, th.text).apply { gravity = Gravity.CENTER }
        val t1: TextView = ui.text("", if (tvMode) 18f else 14f, th.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
        val t2: TextView = ui.text("", if (tvMode) 14f else 11.5f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        var key = ""
        init {
            orientation = HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(if (tvMode) 10 else 7), ui.dp(8), ui.dp(if (tvMode) 10 else 7))
            val sz = ui.dp(if (tvMode) 64 else 48)
            val box = FrameLayout(this@TransferActivity).apply { background = ui.box(th.surface, th.border, 8) }
            box.addView(img, FrameLayout.LayoutParams(-1, -1))
            box.addView(emo, FrameLayout.LayoutParams(-1, -1))
            addView(chk, LayoutParams(ui.dp(34), -2))
            addView(box, LayoutParams(sz, sz).apply { marginEnd = ui.dp(10) })
            val col = LinearLayout(this@TransferActivity).apply { orientation = VERTICAL }
            col.addView(t1); col.addView(t2)
            addView(col, LayoutParams(0, -2, 1f))
        }
        fun bind(r: Row, on: Boolean) {
            key = r.key
            chk.text = if (on) "☑" else "☐"
            t1.text = r.title; t2.text = r.sub
            img.setImageDrawable(null); emo.text = XCat.emoji(r.cat); emo.visibility = View.VISIBLE
            background = if (on) ui.box(th.surface, th.primary, 12, 2) else null
            if (r.cat == XCat.APP && r.ent != null) {
                try { img.setImageDrawable(packageManager.getApplicationIcon(r.ent.pkg)); emo.visibility = View.GONE } catch (_: Throwable) {}
            } else if (r.cat == XCat.IMAGE || r.cat == XCat.VIDEO) {
                loadThumb(r, r.key, img) { key }
                if (thumbs.get(r.key) != null) emo.visibility = View.GONE
            }
        }
    }

    private inner class RowAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position].header) 1 else 0
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val r = rows[position]
            if (r.header) {
                val v = (convertView as? TextView) ?: ui.text("", if (tvMode) 17f else 14f, th.primary, true).apply { setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(4)) }
                val grp = rows.filter { !it.header && it.group == r.group }
                val all = grp.isNotEmpty() && grp.all { it.key in sel }
                v.text = (if (all) "☑ " else "☐ ") + r.title
                return v
            }
            val v = (convertView as? RowView) ?: RowView()
            v.bind(r, r.key in sel)
            return v
        }
    }
}


/** (v609) رادار: دواير + شعاع دوّار وقت البحث + كل تلفزيون نقطة (أخضر = متصل · لون السمة = مقترن · برتقالي = جديد · رمادي = محفوظ مش ظاهر). دوس على نقطة = اتصال/اقتران */
class RadarView(ctx: android.content.Context, private val th: Theme) : View(ctx) {
    class Blip(val id: String, val name: String, val paired: Boolean, val online: Boolean, val found: RemoteClient.Found?)

    var blips: List<Blip> = emptyList()
        set(v) { field = v; invalidate() }
    var connectedId: String = ""
        set(v) { field = v; invalidate() }
    var scanning: Boolean = false
        set(v) {
            field = v
            if (v) { if (!anim.isStarted) anim.start() } else { anim.cancel(); sweep = 0f }
            invalidate()
        }
    var onTap: ((Blip) -> Unit)? = null

    private var sweep = 0f
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hits = ArrayList<Triple<Blip, Float, Float>>()
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2200L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { a -> sweep = a.animatedValue as Float; invalidate() }
    }

    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (scanning && !anim.isStarted) anim.start() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f - 4f * d
        if (r <= 0f) return
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = th.border
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, p)
        c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
        if (scanning) {
            for (k in 0 until 26) {
                val a = Math.toRadians((sweep - k * 2.4f).toDouble())
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d
                p.color = (th.primary and 0x00FFFFFF) or ((((1f - k / 26f) * 120f).toInt()) shl 24)
                c.drawLine(cx, cy, (cx + r * Math.cos(a)).toFloat(), (cy + r * Math.sin(a)).toFloat(), p)
            }
        }
        p.style = Paint.Style.FILL; p.color = th.primary
        c.drawCircle(cx, cy, 5f * d, p)
        hits.clear()
        blips.forEachIndexed { i, b ->
            val ang = Math.toRadians((((b.id.hashCode() and 0x7fffffff) % 360) + i * 47).toDouble())
            val rr = r * (if (i % 2 == 0) 0.6f else 0.82f)
            val x = (cx + rr * Math.cos(ang)).toFloat(); val y = (cy + rr * Math.sin(ang)).toFloat()
            val conn = b.id == connectedId
            val col = when { conn -> 0xFF2ECC71.toInt(); !b.online -> th.muted; b.paired -> th.primary; else -> 0xFFFFA726.toInt() }
            if (b.online) {
                val ph = if (scanning) ((sweep / 360f) + i * 0.23f) % 1f else 0.35f
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
                p.color = (col and 0x00FFFFFF) or (((1f - ph) * 150f).toInt() shl 24)
                c.drawCircle(x, y, (8f + 14f * ph) * d, p)
            }
            p.style = Paint.Style.FILL; p.color = col
            c.drawCircle(x, y, 8f * d, p)
            if (!b.online) { p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = th.border; c.drawCircle(x, y, 11f * d, p) }
            if (conn) { p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = 0xFF2ECC71.toInt(); c.drawCircle(x, y, 13f * d, p) }
            tp.textSize = 10.5f * d; tp.color = th.text
            c.drawText(b.name.take(14), x, y + 25f * d, tp)
            hits.add(Triple(b, x, y))
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val hit = hits.minByOrNull { t -> Math.hypot((e.x - t.second).toDouble(), (e.y - t.third).toDouble()) }
            if (hit != null && Math.hypot((e.x - hit.second).toDouble(), (e.y - hit.third).toDouble()) < 34f * d) onTap?.invoke(hit.first)
            performClick()
        }
        return e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_UP
    }
    override fun performClick(): Boolean = super.performClick()
}
