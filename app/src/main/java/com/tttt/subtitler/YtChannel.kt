package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.bumptech.glide.Glide
import org.schabi.newpipe.extractor.Page
import java.util.Locale

/**
 * (v403) الاشتراك: بيتحفظ في التطبيق، ولو إنت مسجّل بحساب جوجل (وإعداد «مزامنة الاشتراك مع حسابي» شغّال) بيتسجّل على حسابك كمان.
 * الإشعارات: لكل قناة مشتركة جرس (الكل / بدون) — والإشعارات العامة من إعدادات يوتيوب.
 */
object YtSubscribe {
    fun item(url: String, name: String, avatar: String = ""): NpItem = NpItem("", name.ifBlank { url }, "", url, avatar, -1L, 0L, "", 1, url)
    fun isSub(url: String): Boolean = url.isNotBlank() && YtStore.contains(YtStore.SUBS, item(url, ""))
    fun syncOn(): Boolean = Cfg.str("yt_acc_sync", "1") != "0"

    /** بيقلب الاشتراك؛ done(now) بتتنادى فورًا على الـ UI، ومزامنة الحساب بتتم في الخلفية بإشعار نتيجتها */
    fun toggle(act: Activity, url: String, name: String, avatar: String, done: (Boolean) -> Unit) {
        if (url.isBlank()) { Notice.show(act, "القناة دي مالهاش لينك", 2200L); return }
        val now = YtStore.toggle(YtStore.SUBS, item(url, name, avatar))
        try { YtNotify.schedule(act) } catch (_: Throwable) {}
        Notice.show(act, if (now) "✅ اشتركت في " + name else "اتشال الاشتراك", 2000L)
        done(now)
        if (syncOn() && YtAccount.loggedIn()) {
            Thread {
                try {
                    YtAccount.setSubscribed(url, now)
                    act.runOnUiThread { Notice.show(act, if (now) "✅ اتسجّل على حسابك كمان" else "✅ اتشال من حسابك كمان", 2200L) }
                } catch (e: Throwable) {
                    act.runOnUiThread { Notice.show(act, "اتحفظ في التطبيق بس — حسابك رفض: " + (e.message ?: e.javaClass.simpleName).take(80), 3600L) }
                }
            }.apply { isDaemon = true }.start()
        }
    }

    /** قايمة الجرس: الكل / بدون / إلغاء الاشتراك */
    fun bellMenu(act: Activity, anchor: View, url: String, name: String, avatar: String, changed: () -> Unit) {
        val m = PopupMenu(act, anchor)
        val on = YtNotify.chanOn(url)
        m.menu.add(0, 1, 0, (if (on) "✓ " else "") + "🔔 الكل (إشعار بكل فيديو جديد)")
        m.menu.add(0, 2, 1, (if (!on) "✓ " else "") + "🔕 بدون إشعارات")
        m.menu.add(0, 3, 2, "❌ إلغاء الاشتراك")
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { YtNotify.setChanOn(url, true); if (!YtNotify.enabled()) Notice.show(act, "الإشعارات العامة مقفولة — شغّلها من إعدادات يوتيوب", 3200L) else Notice.show(act, "🔔 هتجيلك إشعارات بفيديوهات " + name, 2400L); changed() }
                2 -> { YtNotify.setChanOn(url, false); Notice.show(act, "🔕 اتقفلت إشعارات " + name, 2200L); changed() }
                else -> toggle(act, url, name, avatar) { changed() }
            }
            true
        }
        m.show()
    }
}

interface YtChanHost {
    fun openVideo(v: NpItem)
    fun openPlaylist(url: String, title: String)
    fun download(v: NpItem)   // (v459) زرار ⬇ على كارت الفيديو
    fun audio(v: NpItem) {}   // (v472) 🎧 صوت بس فوق الصورة
    fun state(open: Boolean)
}

/** صفحة القناة (زي يوتيوب): بانر · أفاتار · اسم ومشتركين · اشترك + جرس · تابات (الفيديوهات / البث / القوايم) · وصف */
class YtChannelPage(private val act: Activity, private val ui: Ui, private val host: YtChanHost) : FrameLayout(act) {
    private val sw = act.resources.displayMetrics.widthPixels
    private val medium: Typeface = YtSkin.medium
    private val scroll = ScrollView(act)
    private val body = LinearLayout(act)
    private val topName = TextView(act)
    private val banner = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface); visibility = View.GONE }
    private val avHost = FrameLayout(act)
    private val nameTv = TextView(act)
    private val metaTv = TextView(act)
    private val descTv = TextView(act)
    private val subB = TextView(act)
    private val bellB = YtIcon(act, "bell")
    private val tabsRow = LinearLayout(act)
    private val listBox = LinearLayout(act)
    // (v459) ترتيب فيديوهات القناة (الأحدث = ترتيب يوتيوب نفسه؛ الباقي ترتيب محلي على اللي اتحمّل)
    private val sortRow = LinearLayout(act)
    private var sortMode = 0
    private val sortLabels = listOf("الأحدث", "الأقدم", "أبجدي", "الأكتر مشاهدة")
    private var gen = 0
    private var closing = false
    private var chan: NpChanFull? = null
    private var curUrl = ""
    private var curName = ""
    private var curAv = ""
    private var tabKey = ""
    private var items = ArrayList<NpItem>()
    private var next: Page? = null
    private var more: ((Page) -> YtRes)? = null
    private var loadingMore = false
    private var tabLoad: ((String) -> Pair<YtRes, (Page) -> YtRes>)? = null
    // (v479 م5) تلفزيون: شبكة 4 أعمدة بكروت متركّزة بدل القايمة الطويلة
    private val tv = DeviceUi.isTv(act)
    private val cols = 4
    private var rowBox: LinearLayout? = null
    private var rowN = 0

    val isOpen: Boolean get() = visibility == View.VISIBLE && !closing

    /** (v479 م5) تلفزيون: التركيز بالأسهم يفضل جوه صفحة القناة (مايهربش للشبكة اللي تحتها) */
    override fun focusSearch(focused: View?, direction: Int): View? {
        if (!tv || visibility != View.VISIBLE) return super.focusSearch(focused, direction)
        return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
    }

    /** (v479 م5) Back على التلفزيون: لو التركيز في التابات/الترتيب/الشبكة يرجع لزرار الاشتراك فوق، وبعدها يقفل الصفحة */
    fun tvBack(): Boolean {
        if (!tv || !isOpen) return false
        val f = findFocus() ?: return false
        if (f === subB) return false
        scroll.scrollTo(0, 0); subB.requestFocus(); return true
    }

    init {
        visibility = View.GONE; isClickable = true; setBackgroundColor(YtSkin.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        // شريط علوي: رجوع + اسم القناة
        val back = YtIcon(act, "back").apply { contentDescription = "رجوع"; foreground = YtSkin.ripple(true); setOnClickListener { close(true) } }
        topName.apply { textSize = 16f; setTextColor(YtSkin.text); typeface = medium; setSingleLine(); ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(4), 0, ui.dp(12), 0) }
        top.addView(back, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        if (tv) back.isFocusable = false   // (v479 م5) Back بتاع الريموت كفاية — مايبقاش وقفة زيادة في التركيز
        top.addView(topName, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top, LinearLayout.LayoutParams(-1, ui.dp(48)))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(col, FrameLayout.LayoutParams(-1, -1))
        body.orientation = LinearLayout.VERTICAL; body.layoutDirection = View.LAYOUT_DIRECTION_RTL
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))

        nameTv.apply { textSize = 20f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        metaTv.apply { textSize = 12f; setTextColor(YtSkin.sub); maxLines = 2 }
        descTv.apply { textSize = 13f; setTextColor(YtSkin.sub); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(14), ui.dp(8), ui.dp(14), 0)
            setOnClickListener { maxLines = if (maxLines == 2) 60 else 2 } }
        subB.apply { textSize = 14f; typeface = medium; gravity = Gravity.CENTER; setPadding(ui.dp(22), ui.dp(9), ui.dp(22), ui.dp(9)); setOnClickListener { onSub() } }
        bellB.apply { glyph = 0.62f; contentDescription = "إشعارات القناة"; foreground = YtSkin.ripple(true); visibility = View.GONE
            setOnClickListener { v -> YtSubscribe.bellMenu(act, v, curUrl, curName, curAv) { paintSub() } } }
        tabsRow.apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(8)) }
        listBox.apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0) }
        val hc = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        hc.addView(nameTv, LinearLayout.LayoutParams(-1, -2)); hc.addView(metaTv, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        head.addView(avHost, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)).apply { marginEnd = ui.dp(14) })
        head.addView(hc, LinearLayout.LayoutParams(0, -2, 1f))
        val acts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), 0) }
        acts.addView(subB, LinearLayout.LayoutParams(0, -2, 1f))
        acts.addView(bellB, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { marginStart = ui.dp(8) })

        body.addView(banner, LinearLayout.LayoutParams(-1, if (tv) sw * 9 / 64 else sw * 9 / 32))
        body.addView(head, LinearLayout.LayoutParams(-1, -2))
        if (tv) { descTv.isClickable = false; descTv.isFocusable = false }
        body.addView(descTv, LinearLayout.LayoutParams(-1, -2))
        body.addView(acts, LinearLayout.LayoutParams(-1, -2))
        body.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabsRow) }, LinearLayout.LayoutParams(-1, -2))
        sortRow.apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(6)) }
        for (i in sortLabels.indices) {
            val tv = TextView(act).apply { text = sortLabels[i]; textSize = 12f; typeface = medium; gravity = Gravity.CENTER; tag = i
                setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6)); setOnClickListener { sortMode = i; paintSort(); renderList() } }
            sortRow.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        paintSort()
        body.addView(sortRow, LinearLayout.LayoutParams(-1, -2))
        body.addView(listBox, LinearLayout.LayoutParams(-1, -2))

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val ch = scroll.getChildAt(0)
            if (ch != null && y + scroll.height >= ch.height - ui.dp(500)) loadMore()
        }
    }

    // ───────── مساعدات ─────────
    private fun fmtN(n: Long): String = when {
        n >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", n / 1e9)
        n >= 1_000_000L -> String.format(Locale.US, "%.1fM", n / 1e6)
        n >= 1_000L -> String.format(Locale.US, "%.1fK", n / 1e3)
        else -> n.toString()
    }
    private fun fmtDur(s: Long): String = if (s <= 0) "" else if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)

    private fun avatar(name: String, sizeDp: Int, url: String): View {
        val colors = intArrayOf(0xFFE53935.toInt(), 0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF1E88E5.toInt(), 0xFF5E35B1.toInt())
        val c = colors[(name.hashCode() and 0x7fffffff) % colors.size]
        val f = FrameLayout(act)
        f.addView(TextView(act).apply {
            text = name.trim().take(1).uppercase(); gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = if (sizeDp >= 60) 28f else 15f; typeface = medium
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(c) }
        }, FrameLayout.LayoutParams(-1, -1))
        if (url.isNotBlank()) {
            val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            f.addView(iv, FrameLayout.LayoutParams(-1, -1))
            try { Glide.with(act).load(url).circleCrop().override(ui.dp(sizeDp), ui.dp(sizeDp)).into(iv) } catch (_: Throwable) {}
        }
        return f
    }

    private fun paintSub() {
        val on = YtSubscribe.isSub(curUrl)
        subB.text = if (on) "✓ مشترك" else "اشترك"
        subB.setTextColor(if (on) YtSkin.text else YtSkin.chipOnText)
        subB.background = GradientDrawable().apply { setColor(if (on) YtSkin.chip else YtSkin.chipOnBg); cornerRadius = ui.dp(20).toFloat() }
        bellB.visibility = if (on) View.VISIBLE else View.GONE
        bellB.filled = on && YtNotify.chanOn(curUrl)
        bellB.background = GradientDrawable().apply { setColor(YtSkin.chip); cornerRadius = ui.dp(23).toFloat() }
    }
    private fun onSub() {
        if (curUrl.isBlank()) { Notice.show(act, "لسه بحمّل القناة…", 1600L); return }
        YtSubscribe.toggle(act, curUrl, curName, curAv) { paintSub() }
    }

    private fun status(t: String) {
        listBox.removeAllViews(); rowBox = null; rowN = 0
        listBox.addView(TextView(act).apply { text = t; textSize = 13f; setTextColor(YtSkin.sub); gravity = Gravity.CENTER; setPadding(ui.dp(20), ui.dp(30), ui.dp(20), ui.dp(30)) }, LinearLayout.LayoutParams(-1, -2))
    }

    // ───────── فتح/قفل ─────────
    fun open(url0: String, name: String, avatar0: String) {
        val wasOpen = isOpen
        closing = false
        animate().cancel()
        if (tv) post { try { subB.requestFocus() } catch (_: Throwable) {} }   // (v479 م5) التركيز يدخل الصفحة أول ما تفتح
        gen++; val my = gen
        chan = null; tabLoad = null; tabKey = ""; items = ArrayList(); next = null; more = null; loadingMore = false
        curUrl = url0; curName = name; curAv = avatar0
        topName.text = name; nameTv.text = name; metaTv.text = ""; descTv.text = ""; descTv.maxLines = 2
        banner.visibility = View.GONE
        avHost.removeAllViews(); avHost.addView(avatar(name.ifBlank { "؟" }, 76, avatar0), FrameLayout.LayoutParams(-1, -1))
        tabsRow.removeAllViews()
        paintSub(); status("⏳ …")
        scroll.scrollTo(0, 0)
        if (!wasOpen) {
            host.state(true)
            visibility = View.VISIBLE; alpha = 0f; translationY = ui.dp(48).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(260L).setInterpolator(DecelerateInterpolator(1.5f)).start()
        }
        Thread {
            try {
                var u = url0
                if (u.isBlank()) u = YtNp.findChannelUrl(name) ?: throw RuntimeException("ملقيتش القناة دي")
                val c = try { YtNp.channelFull(u) } catch (e: Throwable) {
                    val alt = YtNp.findChannelUrl(name)
                    if (alt != null && alt != u) YtNp.channelFull(alt) else throw e
                }
                val first = c.tabs.firstOrNull()?.key ?: "videos"
                val r = c.loadTab(first)
                act.runOnUiThread { if (my == gen && !act.isFinishing && !act.isDestroyed) bindChan(c, first, r.first, r.second) }
            } catch (e: Throwable) {
                // (v469) C12: بديل RSS — لو NewPipe فشل ومعانا معرّف UC (أو اتحلّ من الرابط) بنعرض فيديوهات القناة من الفيد الرسمي
                val cid = try { YtRss.chanId(url0) } catch (_: Throwable) { null }
                if (cid != null) {
                    try {
                        val vids = YtRss.channelVideos(cid, url0)
                        if (vids.isNotEmpty()) {
                            val full = NpChanFull(url0, name, avatar0, "", -1L, "", false, listOf(NpChanTab("videos", "الفيديوهات"))) { _ ->
                                Pair(YtRes(vids, null), { _ -> YtRes(emptyList(), null) })
                            }
                            act.runOnUiThread { if (my == gen && !act.isFinishing && !act.isDestroyed) bindChan(full, "videos", YtRes(vids, null)) { _ -> YtRes(emptyList(), null) } }
                            return@Thread
                        }
                    } catch (_: Throwable) {}
                }
                LogStore.err("YtChannel:open", e)
                act.runOnUiThread {
                    if (my == gen) {
                        status("⚠ ما قدرتش أفتح القناة: " + (e.message ?: e.javaClass.simpleName).take(140) + "\n\n(دوس هنا لإعادة المحاولة)")
                        listBox.getChildAt(0)?.setOnClickListener { open(url0, name, avatar0) }
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun close(animated: Boolean) {
        if (visibility != View.VISIBLE || closing) return
        closing = true; gen++
        host.state(false)
        fun finish() { visibility = View.GONE; closing = false; alpha = 1f; translationY = 0f }
        if (!animated) { finish(); return }
        animate().alpha(0f).translationY(ui.dp(48).toFloat()).setDuration(200L).withEndAction { finish() }.start()
    }

    private fun bindChan(c: NpChanFull, key: String, first: YtRes, mf: (Page) -> YtRes) {
        try { YtRss.chanId(c.url)?.let { cid -> if (curUrl.isNotBlank() && curUrl != c.url) YtChanId.put(curUrl, cid) } } catch (_: Throwable) {}   // (v469) C2
        chan = c; tabLoad = c.loadTab
        curUrl = c.url.ifBlank { curUrl }; curName = c.name.ifBlank { curName }; if (c.avatar.isNotBlank()) curAv = c.avatar
        topName.text = curName
        nameTv.text = curName + if (c.verified) "  ✓" else ""
        metaTv.text = listOf(if (c.subs >= 0) fmtN(c.subs) + " مشترك" else "").filter { it.isNotBlank() }.joinToString(" · ")
        avHost.removeAllViews(); avHost.addView(avatar(curName.ifBlank { "؟" }, 76, curAv), FrameLayout.LayoutParams(-1, -1))
        descTv.text = c.desc.trim()
        descTv.visibility = if (c.desc.isBlank()) View.GONE else View.VISIBLE
        if (c.banner.isNotBlank()) { banner.visibility = View.VISIBLE; try { Glide.with(act).load(c.banner).centerCrop().into(banner) } catch (_: Throwable) {} }
        paintSub()
        tabsRow.removeAllViews()
        for (t in c.tabs) {
            val tv = TextView(act).apply { text = t.label; textSize = 13f; typeface = medium; gravity = Gravity.CENTER; tag = t.key
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); setOnClickListener { selectTab(t.key) } }
            tabsRow.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }
        tabKey = key; paintTabs(); paintSort()
        items = ArrayList(first.items); next = first.next; more = mf
        renderList()
    }

    private fun paintTabs() {
        for (i in 0 until tabsRow.childCount) {
            val tv = tabsRow.getChildAt(i) as? TextView ?: continue
            val on = tv.tag == tabKey
            tv.setTextColor(if (on) YtSkin.chipOnText else YtSkin.text)
            tv.background = GradientDrawable().apply { setColor(if (on) YtSkin.chipOnBg else YtSkin.chip); cornerRadius = ui.dp(10).toFloat() }
        }
    }

    private fun selectTab(key: String) {
        val c = chan ?: return
        if (key == tabKey) return
        tabKey = key; paintTabs(); paintSort(); items = ArrayList(); next = null; more = null; loadingMore = false
        status("⏳ …")
        val my = gen
        Thread {
            try {
                val r = c.loadTab(key)
                act.runOnUiThread { if (my == gen && tabKey == key) { items = ArrayList(r.first.items); next = r.first.next; more = r.second; renderList() } }
            } catch (e: Throwable) {
                LogStore.err("YtChannel:tab", e)
                act.runOnUiThread { if (my == gen && tabKey == key) status("⚠ " + (e.message ?: e.javaClass.simpleName).take(120)) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun loadMore() {
        val nx = next ?: return; val mf = more ?: return
        if (loadingMore) return
        loadingMore = true
        val my = gen; val key = tabKey
        Thread {
            try {
                val r = mf(nx)
                act.runOnUiThread {
                    if (my == gen && tabKey == key) {
                        val seen = items.map { it.id + it.url }.toHashSet()
                        val add = r.items.filter { seen.add(it.id + it.url) }
                        items.addAll(add); next = r.next
                        if (sortMode != 0) renderList() else if (tv) tvAppend(add) else for (v in add) listBox.addView(rowFor(v), LinearLayout.LayoutParams(-1, -2))
                    }
                    loadingMore = false
                }
            } catch (_: Throwable) { act.runOnUiThread { loadingMore = false } }
        }.apply { isDaemon = true }.start()
    }

    private fun paintSort() {
        for (i in 0 until sortRow.childCount) {
            val tv = sortRow.getChildAt(i) as? TextView ?: continue
            val on = (tv.tag as? Int) == sortMode
            tv.setTextColor(if (on) YtSkin.chipOnText else YtSkin.text)
            tv.background = GradientDrawable().apply { setColor(if (on) YtSkin.chipOnBg else YtSkin.chip); cornerRadius = ui.dp(9).toFloat() }
        }
        sortRow.visibility = if (tabKey == "playlists") View.GONE else View.VISIBLE
    }

    /** (v459) الترتيب المحلي على العناصر المحمّلة بس (NewPipe مفيهوش ترتيب من السيرفر غير الأحدث) */
    private fun sortedItems(): List<NpItem> {
        if (sortMode == 0 || items.size < 2 || items.firstOrNull()?.kind == 2) return items
        return when (sortMode) {
            1 -> items.sortedBy { if (it.ts > 0) it.ts else Long.MAX_VALUE }   // الأقدم: اللي ملوش تاريخ بيروح الآخر
            2 -> items.sortedBy { it.title.lowercase() }
            else -> items.sortedByDescending { it.count }
        }
    }

    private fun renderList() {
        listBox.removeAllViews(); rowBox = null; rowN = 0
        if (items.isEmpty()) { status("مفيش حاجة هنا"); return }
        if (tv) { tvAppend(sortedItems()); return }
        for (v in sortedItems()) listBox.addView(rowFor(v), LinearLayout.LayoutParams(-1, -2))
    }

    private fun rowFor(v: NpItem): View = if (v.kind == 2) playlistRow(v) else videoCard(v)

    // ───────── (v479 م5) تلفزيون: شبكة 4 أعمدة — كل صف 4 كروت متركّزة، OK = فتح، Menu = (شغّل · صوت · تحميل) ─────────
    private fun tvCw(): Int = (sw - ui.dp(32) - cols * ui.dp(12)) / cols

    private fun tvAppend(list: List<NpItem>) {
        val cw = tvCw()
        for (v in list) {
            val cur = rowBox
            val rb: LinearLayout = if (cur == null || rowN >= cols) {
                LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)); clipChildren = false }
                    .also { listBox.addView(it, LinearLayout.LayoutParams(-1, -2)); rowBox = it; rowN = 0 }
            } else cur
            rb.addView(tvCard(v), LinearLayout.LayoutParams(cw, -2).apply { setMargins(ui.dp(6), 0, ui.dp(6), 0) })
            rowN++
        }
    }

    private fun tvCard(v: NpItem): View {
        val cw = tvCw()
        val ch = cw * 9 / 16
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        val box = FrameLayout(act)
        box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        if (v.kind != 2 && v.dur > 0) box.addView(TextView(act).apply {
            text = fmtDur(v.dur); textSize = 13f; setTextColor(Color.WHITE); typeface = medium; includeFontPadding = false
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).centerCrop().override(cw, ch).into(thumb) } catch (_: Throwable) {}
        val title = TextView(act).apply { text = v.title; textSize = 15f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(2), ui.dp(8), ui.dp(2), 0) }
        val meta = TextView(act).apply {
            text = if (v.kind == 2) (if (v.count > 0) "📃 " + v.count + " فيديو" else "📃 قايمة تشغيل") else listOf(if (v.count > 0) fmtN(v.count) + " مشاهدة" else "", v.age).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12.5f; setTextColor(YtSkin.sub); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(ui.dp(2), ui.dp(2), ui.dp(2), 0)
        }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; isFocusable = true; isClickable = true }
        root.addView(box, LinearLayout.LayoutParams(cw, ch))
        root.addView(title, LinearLayout.LayoutParams(cw, -2))
        root.addView(meta, LinearLayout.LayoutParams(cw, -2))
        root.setOnClickListener { if (v.kind == 2) host.openPlaylist(v.url, v.title) else host.openVideo(fix(v)) }
        root.setOnLongClickListener { if (v.kind != 2 && v.id.length == 11) tvMenu(root, v); true }
        root.setOnFocusChangeListener { _, f -> if (f && v.kind == 0 && v.id.length == 11) YtFast.warm(v.id) }
        return root
    }

    private fun tvMenu(anchor: View, v: NpItem) {
        val m = PopupMenu(act, anchor)
        m.menu.add(0, 1, 0, "▶ شغّل"); m.menu.add(0, 2, 1, "🎧 صوت بس"); m.menu.add(0, 3, 2, "⬇ تحميل")
        m.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> host.openVideo(fix(v))
                2 -> host.audio(fix(v))
                3 -> host.download(fix(v))
            }
            true
        }
        m.show()
    }

    /** فيديو القناة: بنكمّل اسم القناة ولينكها (الصفحة بتجيب الفيديوهات من غيرهم) */
    private fun fix(v: NpItem): NpItem = if (v.kind == 0) NpItem(v.id, v.title, curName, curUrl, v.thumb, v.dur, v.count, v.age, 0, v.url, v.ts, curAv) else v

    private fun videoCard(v: NpItem): View {
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        val box = FrameLayout(act)
        box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
        if (v.dur > 0) box.addView(TextView(act).apply {
            text = fmtDur(v.dur); textSize = 12f; setTextColor(Color.WHITE); typeface = medium; includeFontPadding = false
            setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3)); background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = ui.dp(4).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) })
        try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).centerCrop().override(sw, sw * 9 / 16).into(thumb) } catch (_: Throwable) {}
        val title = TextView(act).apply { text = v.title; textSize = 14f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = TextView(act).apply {
            text = listOf(if (v.count > 0) fmtN(v.count) + " مشاهدة" else "", v.age).filter { it.isNotBlank() }.joinToString(" · ")
            textSize = 12f; setTextColor(YtSkin.sub); maxLines = 1
        }
        val infoCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        infoCol.addView(title, LinearLayout.LayoutParams(-1, -2)); infoCol.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        val tb = YtThumbBtns.attach(act, ui, box)   // (v472) 🎧 + ⬇ فوق الصورة
        YtThumbBtns.bind(tb.aud, tb.dl, v.id, { host.audio(fix(v)) }, { host.download(fix(v)) })
        val info = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.TOP; setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(16)) }
        info.addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; foreground = YtSkin.ripple(); setOnClickListener { host.openVideo(fix(v)) }; YtFast.warmOnTouch(this) { v.id } }
        root.addView(box, LinearLayout.LayoutParams(-1, sw * 9 / 16)); root.addView(info, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun playlistRow(v: NpItem): View {
        val thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(YtSkin.surface) }
        try { if (v.thumb.isNotBlank()) Glide.with(act).load(v.thumb).centerCrop().override(ui.dp(168), ui.dp(94)).into(thumb) } catch (_: Throwable) {}
        val title = TextView(act).apply { text = v.title; textSize = 14f; setTextColor(YtSkin.text); typeface = medium; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val meta = TextView(act).apply { text = if (v.count > 0) "📃 " + v.count + " فيديو" else "📃 قايمة تشغيل"; textSize = 12f; setTextColor(YtSkin.sub) }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(title, LinearLayout.LayoutParams(-1, -2)); col.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)); foreground = YtSkin.ripple()
            setOnClickListener { host.openPlaylist(v.url, v.title) } }
        row.addView(thumb, LinearLayout.LayoutParams(ui.dp(168), ui.dp(94)))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
        return row
    }

}
