package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
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
              private val onImport: () -> Unit) {

    private class St(val title: String, val tab: Int, val items: ArrayList<YtItem>, var next: Page?, val more: ((Page) -> YtRes)?,
                      val chUrl: String?, val chName: String?, val q: String?, val filter: String,
                      val vid: YtItem? = null, val det: YtDetail? = null) {
        var pos = 0
        var cMode = false; var cLoaded = false; val ci = ArrayList<YtItem>(); var cn: Page? = null; var cm: ((Page) -> YtRes)? = null
        fun list(): ArrayList<YtItem> = if (cMode) ci else items
        fun nxt(): Page? = if (cMode) cn else next
        fun mf(): ((Page) -> YtRes)? = if (cMode) cm else more
    }

    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val h = Handler(Looper.getMainLooper())
    private val lv = ListView(act)
    private val statusTv: TextView
    private val search: EditText
    private val sugBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val tabViews = ArrayList<TextView>()
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

    init {
        // الشريط العلوي: خانة البحث + زرار 🔍
        search = EditText(act).apply {
            hint = "ابحث في يوتيوب"; setHintTextColor(th.muted); setTextColor(th.text); textSize = 14f
            setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            background = GradientDrawable().apply { setColor(th.surface); cornerRadius = ui.dp(20).toFloat() }
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { doSearch(text.toString()); true } else false }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { suggest(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        val goBtn = ui.text("🔍", 18f, th.primary, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(10), 0, ui.dp(10), 0); setOnClickListener { doSearch(search.text.toString()) }
        }
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(8), ui.dp(6), ui.dp(4)) }
        bar.addView(search, LinearLayout.LayoutParams(0, ui.dp(42), 1f))
        bar.addView(goBtn, LinearLayout.LayoutParams(-2, ui.dp(42)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        root.addView(sugBox, LinearLayout.LayoutParams(-1, -2))

        // التبويبات
        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(6)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val defs = listOf("🏠 الرئيسية", "📺 اشتراكاتي", "🕘 السجل", "🔖 المحفوظات")
        defs.forEachIndexed { i, t ->
            val tv = ui.text(t, 13f, th.text, true).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)); setSingleLine()
                setOnClickListener { openTab(i) }
            }
            tabViews.add(tv)
            tabs.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabs) }, LinearLayout.LayoutParams(-1, -2))

        // صف القناة (اسم + اشتراك)
        chanName = ui.text("", 15f, th.text, true).apply { setSingleLine(); ellipsize = TextUtils.TruncateAt.END }
        subBtn = ui.text("🔔 اشترك", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            setOnClickListener { toggleSub() }
        }
        chanRow.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(6))
        chanRow.addView(chanName, LinearLayout.LayoutParams(0, -2, 1f))
        chanRow.addView(subBtn, LinearLayout.LayoutParams(-2, -2))
        root.addView(chanRow, LinearLayout.LayoutParams(-1, -2))

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
        root.addView(subsRow, LinearLayout.LayoutParams(-1, -2))
        paintNotif()
        YtRss.attach(act)
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
        root.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(filterRow) }, LinearLayout.LayoutParams(-1, -2))

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
        root.addView(detailBox, LinearLayout.LayoutParams(-1, -2))

        statusTv = ui.text("", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), 0); visibility = View.GONE; setOnClickListener { retry?.invoke() } }
        root.addView(statusTv, LinearLayout.LayoutParams(-1, -2))

        lv.apply {
            divider = null; setBackgroundColor(th.bg); selector = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            this.adapter = this@YtProUi.adapter
            setOnItemClickListener { _, _, p, _ -> cur?.list()?.getOrNull(p)?.let { if (it.kind != 3) open(it) } }
            setOnItemLongClickListener { _, v, p, _ -> cur?.list()?.getOrNull(p)?.let { if (it.kind == 3) copy(it.title) else menu(v, it) }; true }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
                override fun onScroll(v: AbsListView?, first: Int, vis: Int, total: Int) { if (total > 0 && first + vis >= total - 3) loadMore() }
            })
        }
        root.addView(lv, LinearLayout.LayoutParams(-1, 0, 1f))
        paintTabs(-1)
    }

    // ───────── واجهة Main ─────────
    fun refresh() { if (cur == null) openTab(0) else adapter.notifyDataSetChanged() }
    fun onHide() { hideKb(); sugBox.visibility = View.GONE }
    fun back(): Boolean {
        if (sugBox.visibility == View.VISIBLE) { sugBox.visibility = View.GONE; return true }
        if (stack.isNotEmpty()) { gen++; bind(stack.removeAt(stack.size - 1)); return true }
        return false
    }
    /** ملف اشتراكات: NewPipe JSON أو subscriptions.csv بتاع Google Takeout */
    fun importUri(u: Uri) {
        Thread {
            var n = 0
            try {
                val txt = act.contentResolver.openInputStream(u)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val subs = YtStore.read(YtStore.SUBS)
                fun add(url: String, name: String) { if (url.isNotBlank() && subs.none { it.channelUrl == url }) { subs.add(YtItem("", name.ifBlank { url }, "", url, "", -1L, 0L, "", 1, url)); n++ } }
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

    // ───────── تنقّل ─────────
    private fun openTab(i: Int) {
        hideKb(); sugBox.visibility = View.GONE; stack.clear(); paintTabs(i)
        when (i) {
            0 -> load("🏠 الرئيسية", 0, null, null, null, "", false, false) { Pair(YtNp.home(YtStore.read(YtStore.HIST)), null) }
            1 -> {
                val subs = YtStore.read(YtStore.SUBS)
                if (subs.isEmpty()) { bind(St("📺 اشتراكاتي", 1, ArrayList(), null, null, null, null, null, "")); status("مفيش اشتراكات لسه.\nافتح قناة من أي فيديو (ضغطة طويلة ← 📺 افتح القناة) واضغط 🔔 اشترك.\n\nأو استورد ملف اشتراكات (دوس هنا)") { onImport() } }
                else openSubs(false)
            }
            2 -> bind(St("🕘 السجل", 2, ArrayList(YtStore.read(YtStore.HIST)), null, null, null, null, null, "")).also { if (cur!!.items.isEmpty()) status("السجل فاضي") }
            3 -> bind(St("🔖 المحفوظات", 3, ArrayList(YtStore.read(YtStore.SAVED)), null, null, null, null, null, "")).also { if (cur!!.items.isEmpty()) status("مفيش محفوظات — ضغطة طويلة على أي فيديو ← 🔖") }
        }
    }

    private fun doSearch(q0: String, filter: String = "videos") {
        val q = q0.trim(); if (q.isEmpty()) return
        hideKb(); sugBox.visibility = View.GONE; search.setText(q); search.setSelection(q.length); sugGen++
        paintTabs(-1)
        load("🔍 $q", -1, null, null, q, filter, true, false) { val r = YtNp.searchFirst(q, filter); Pair(r, { p: Page -> YtNp.searchMore(q, filter, p) }) }
    }

    private fun open(it: YtItem) {
        when (it.kind) {
            0 -> openDetail(it)
            1 -> openChannel(it.channelUrl, it.title)
            2 -> load("📃 " + it.title, -1, null, null, null, "", true, false) { YtNp.playlist(it.url) }
        }
    }

    private fun openChannel(url: String, name: String) {
        if (url.isBlank()) { Notice.show(act, ("القناة دي مالهاش لينك").toString(), 2500L); return }
        load(name, -1, url, name, null, "", true, true) { val c = YtNp.channel(url); Pair(c.first, c.more) }
    }

    private fun toggleSub() {
        val st = cur ?: return; val url = st.chUrl ?: return
        val now = YtStore.toggle(YtStore.SUBS, YtItem("", st.chName ?: url, "", url, "", -1L, 0L, "", 1, url))
        paintSub(now); try { YtNotify.schedule(act) } catch (_: Throwable) {}
        Notice.show(act, (if (now) "✅ اشتركت في " + (st.chName ?: "") else "اتشال الاشتراك").toString(), 2200L)
    }

    // ───────── (v327) فيد الاشتراكات RSS + إشعارات ─────────
    private fun openSubs(force: Boolean) {
        val subs = YtStore.read(YtStore.SUBS)
        if (subs.isEmpty()) { openTab(1); return }
        hideKb(); sugBox.visibility = View.GONE; stack.clear(); paintTabs(1)
        load("📺 اشتراكاتي", 1, null, null, null, "", false, false) {
            val r = YtRss.feed(subs, force)
            YtNotify.markSeen(r.items)   // اللي اتعرض ما يجيش عليه إشعار
            Pair(r, null)
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
        openDetail(YtItem(id, "", "", "", "https://i.ytimg.com/vi/$id/mqdefault.jpg", -1L, -1L, "", 0, "https://www.youtube.com/watch?v=$id"))
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
                    if (r.items.isEmpty()) status("مفيش نتايج")
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
        cur = st; statusTv.visibility = View.GONE
        val det = st.vid != null
        detailBox.visibility = if (det) View.VISIBLE else View.GONE
        chanRow.visibility = if (st.chUrl != null && !det) View.VISIBLE else View.GONE
        subsRow.visibility = if (st.tab == 1 && !det && st.items.isNotEmpty()) View.VISIBLE else View.GONE; paintNotif()
        if (st.chUrl != null) { chanName.text = st.chName ?: ""; paintSub(YtStore.contains(YtStore.SUBS, YtItem("", "", "", st.chUrl, "", -1L, 0L, "", 1, st.chUrl))) }
        filterRow.visibility = if (st.q != null && !det) View.VISIBLE else View.GONE
        filterViews.forEach { tv -> paintChip(tv, tv.tag == st.filter) }
        if (det) paintDetail(st)
        adapter.set(st.list()); lv.setSelection(st.pos)
        if (st.tab >= 0) paintTabs(st.tab) else if (det) paintTabs(-1)
    }

    // ───────── (v326) تفاصيل الفيديو ─────────
    private fun openDetail(v: YtItem) {
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

    private fun detItem(st: St): YtItem {
        val v = st.vid!!; val d = st.det
        return YtItem(v.id, d?.title?.ifBlank { v.title } ?: v.title, d?.channel?.ifBlank { v.channel } ?: v.channel, d?.channelUrl?.ifBlank { v.channelUrl } ?: v.channelUrl,
            d?.thumb?.ifBlank { v.thumb } ?: v.thumb, d?.dur ?: v.dur, v.count, v.age, 0, v.url)
    }
    private fun playCur(tr: Boolean) { val st = cur ?: return; if (st.vid == null) return; play(detItem(st), tr) }
    private fun play(it: YtItem, tr: Boolean) { YtStore.addFront(YtStore.HIST, it, 200); onPlay(it.id, it.title, tr) }
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

    private fun status(t: String, onTap: (() -> Unit)? = null) { statusTv.text = t; statusTv.visibility = View.VISIBLE; retry = onTap }

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
    private fun menu(anchor: View, it: YtItem) {
        val m = PopupMenu(act, anchor)
        if (it.kind == 0) {
            m.menu.add(0, 1, 0, "▶ شغّل"); m.menu.add(0, 2, 1, "🌐 شغّل وترجم"); m.menu.add(0, 10, 7, "ℹ التفاصيل")
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
                3 -> { val on = YtStore.toggle(YtStore.SAVED, it); Notice.show(act, (if (on) "🔖 اتحفظ" else "اتشال من المحفوظات").toString(), 1800L); if (cur?.tab == 3) openTab(3) }
                4 -> openChannel(it.channelUrl, it.channel)
                5 -> copy(if (it.kind == 0) YtNp.watchUrl(it.id) else it.url)
                6 -> onBrowser(YtNp.watchUrl(it.id))
                7 -> { YtStore.remove(YtStore.HIST, it); openTab(2) }
                8 -> open(it)
                9 -> { val on = YtStore.toggle(YtStore.SUBS, it); Notice.show(act, (if (on) "✅ اشتركت" else "اتشال الاشتراك").toString(), 1800L) }
            }
            true
        }
        m.show()
    }

    // ───────── رسم ─────────
    private fun paintTabs(sel: Int) = tabViews.forEachIndexed { i, tv -> paintChip(tv, i == sel) }
    private fun paintChip(tv: TextView, on: Boolean) {
        tv.setTextColor(if (on) Color.WHITE else th.text)
        tv.background = GradientDrawable().apply { setColor(if (on) th.primary else th.surface); cornerRadius = ui.dp(16).toFloat() }
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

    private class Hold(val thumb: ImageView, val dur: TextView, val title: TextView, val meta: TextView, val box: View, val col: View)

    private inner class Adapter : BaseAdapter() {
        private var items: List<YtItem> = emptyList()
        fun set(l: List<YtItem>) { items = l; notifyDataSetChanged() }
        fun clear() { items = emptyList(); notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
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
                hd = Hold(th0, dur, title, meta, box, col); row.tag = hd; v = row
            } else { v = cv; hd = cv.tag as Hold }
            val it = items[p]
            hd.title.text = it.title
            val cm = it.kind == 3
            hd.box.visibility = if (cm) View.GONE else View.VISIBLE
            hd.title.maxLines = if (cm) 10 else 2
            (hd.col.layoutParams as LinearLayout.LayoutParams).height = if (cm) -2 else ui.dp(85)
            val seen = it.kind == 0 && recents().containsKey("yt:" + it.id)
            hd.title.alpha = if (seen) 0.6f else 1f
            hd.meta.text = when (it.kind) {
                3 -> listOf(it.channel, if (it.count > 0) "♥ " + fmtN(it.count) else "", it.age).filter { s -> s.isNotBlank() }.joinToString(" · ")
                0 -> listOf(it.channel, if (it.count >= 0) fmtN(it.count) + " مشاهدة" else "", it.age).filter { s -> s.isNotBlank() }.joinToString(" · ")
                1 -> "📺 قناة" + if (it.count >= 0) " · " + fmtN(it.count) + " مشترك" else ""
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
