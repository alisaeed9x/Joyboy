package com.tttt.subtitler

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** حالة بسيطة مشتركة بين الشاشات (الملفات المختارة والجهاز لكل جلسة — لإعادة المحاولة) */
object FtUiState {
    val picks = ConcurrentHashMap<String, List<FtPick>>()
    val peers = ConcurrentHashMap<String, FtPeer>()
}

/**
 * (v653) نقل الملفات الذكي — الشاشة الرئيسية. بنفس نظام الواجهات والثيم الحالي (Views برمجية + Theme + Ui) من غير أي ألوان ثابتة.
 * الرئيسية (4 بطاقات) ← الرادار ← اختيار الملفات ← الإرسال / الاستقبال ← السجل ← الهوية.
 * D-pad: كل العناصر قابلة للتركيز بحد واضح، والرادار بيحدّث الصفوف في مكانها من غير ما يسرق التركيز.
 */
class FileTransferActivity : Activity() {
    private lateinit var th: Theme
    private lateinit var ui: Ui
    private val h = Handler(Looper.getMainLooper())
    private val tvMode: Boolean by lazy { try { DeviceUi.isTv(this) } catch (_: Throwable) { false } }
    private lateinit var content: FrameLayout
    private val stack = ArrayList<Pair<String, () -> View>>()
    private var curTag = ""
    private var picker: FtPicker? = null
    private var radar: FtRadarView? = null
    private var updater: (() -> Unit)? = null
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    // الرادار
    private val peers = LinkedHashMap<String, FtPeer>()
    private val miss = HashMap<String, Int>()
    private var scanning = false
    private var scanGen = 0
    private var radarMode = "m2m"
    private var presetPicks: List<FtPick>? = null
    private var radarStatus: TextView? = null
    private var radarList: LinearLayout? = null
    private class PeerRow(val root: View, val name: TextView, val sub: TextView, val glyph: GlyphView, val badge: TextView)
    private val peerRows = HashMap<String, PeerRow>()
    private val shownSids = HashSet<String>()
    private var hubL: (() -> Unit)? = null

    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

    // ───────── دورة الحياة ─────────
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Cfg.init(this)
        try { RemoteClient.init(this) } catch (_: Throwable) {}
        try { DeviceUi.applyOrientation(this) } catch (_: Throwable) {}
        th = Themes.byId(Cfg.str("theme", "mx")); ui = Ui(this, th)
        try { window.setBackgroundDrawable(ColorDrawable(th.bg)); window.statusBarColor = th.bg; window.navigationBarColor = th.bg } catch (_: Throwable) {}
        FtIdentity.init(this); FtStore.init(this)
        FtPeers.start(this)
        val pad = ui.dp(if (tvMode) 28 else 12)
        val root = FrameLayout(this).apply { setBackgroundColor(th.bg); layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(pad, ui.dp(if (tvMode) 20 else 12), pad, ui.dp(8)) }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val l: () -> Unit = { onHub() }
        hubL = l; FtHub.listen(l)
        go("home") { buildHome() }
        handleIntent(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIntent(i) }

    private fun handleIntent(i: Intent?) {
        val sid = i?.getStringExtra("ft_sid") ?: return
        val s = FtHub.find(sid) ?: return
        i?.removeExtra("ft_sid")
        if (shownSids.add(sid) || curTag != "progress") go("progress") { buildProgress(s) }
    }

    override fun onResume() { super.onResume(); FtHub.uiVisible = true; picker?.refreshIfNeeded(); onHub() }
    override fun onPause() { FtHub.uiVisible = false; super.onPause() }

    override fun onDestroy() {
        scanGen++
        h.removeCallbacksAndMessages(null)
        hubL?.let { FtHub.unlisten(it) }
        if (!tvMode && !FtIdentity.keepReady() && FtHub.sessions.none { it.active }) FtPeers.stop()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (curTag == "picker" && picker?.back() == true) return
        if (curTag == "tvbrowse" && backHook?.invoke() == true) return
        pop()
    }

    private fun onHub() {
        if (isFinishing || isDestroyed) return
        try { updater?.invoke() } catch (e: Throwable) { LogStore.err("FtUi:upd", e) }
        for (s in FtHub.pendingIncoming()) { if (shownSids.add(s.sid)) { go("progress") { buildProgress(s) }; break } }
    }

    // ───────── التنقل ─────────
    private fun go(tag: String, b: () -> View) { stack.add(Pair(tag, b)); show() }

    private fun pop() { if (stack.size > 1) { stack.removeAt(stack.size - 1); show() } else finish() }

    private fun show() {
        val (tag, b) = stack.last()
        if (curTag == "radar" && tag != "radar") scanGen++
        if (tag == "home") presetPicks = null
        curTag = tag; picker = null; radar = null; updater = null; backHook = null; progRefresh = null; radarStatus = null; radarList = null; peerRows.clear()
        content.removeAllViews()
        val v = b()
        content.addView(v, FrameLayout.LayoutParams(-1, -1))
        if (tvMode) v.post { v.requestFocus() }
    }

    // ───────── مكوّنات ─────────
    private fun lp(w: Int, hh: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, hh, wt)

    private fun styleFocus(v: View, fill: Int, r: Int, on: Boolean = false) {
        v.isFocusable = true; v.isClickable = true
        fun paint(f: Boolean) {
            v.background = ui.box(fill, if (f) th.accent else if (on) th.primary else th.border, r, if (f) 3 else 1)
            v.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f).setDuration(110).start()
        }
        paint(false)
        v.setOnFocusChangeListener { _, f -> paint(f) }
    }

    private fun btn(label: String, primary: Boolean = false, f: () -> Unit): TextView = IconTextView(this).apply {
        text = label; textSize = if (tvMode) 18f else 14f; gravity = Gravity.CENTER; maxLines = 1
        setPadding(ui.dp(if (tvMode) 20 else 14), ui.dp(if (tvMode) 13 else 11), ui.dp(if (tvMode) 20 else 14), ui.dp(if (tvMode) 13 else 11))
        isFocusable = true; isClickable = true
        fun paint(foc: Boolean) {
            setTextColor(if (primary) onPrimary else th.text)
            background = ui.box(if (primary) th.primary else th.surface, if (foc) th.accent else if (primary) th.primary else th.border, 18, if (foc) 3 else 1)
        }
        paint(false)
        setOnFocusChangeListener { _, foc -> paint(foc) }
        setOnClickListener { f() }
    }

    private fun header(title: String, sub: String = "", right: View? = null): LinearLayout {
        val hd = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        if (stack.size > 1) hd.addView(btn("→") { pop() }, lp(-2, -2).apply { marginEnd = ui.dp(8) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(ui.text(title, if (tvMode) 26f else 20f, th.primary, true))
        if (sub.isNotEmpty()) col.addView(ui.text(sub, if (tvMode) 15f else 12f, th.muted))
        hd.addView(col, lp(0, -2, 1f))
        if (right != null) hd.addView(right, lp(-2, -2))
        return hd
    }

    private fun scroll(inner: View): ScrollView = ScrollView(this).apply { isFillViewport = false; addView(inner, FrameLayout.LayoutParams(-1, -2)) }

    private fun col(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }

    private fun listSelector(): StateListDrawable {
        val hi = ui.box(th.surface, th.accent, 12, 2)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), hi)
            addState(intArrayOf(android.R.attr.state_focused), hi)
            addState(intArrayOf(android.R.attr.state_selected), hi)
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
    }

    /** أيقونة نوع الجهاز مرسومة بالألوان من الثيم */
    private inner class GlyphView(var type: String) : View(this@FileTransferActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            FtDraw.glyph(c, p, type, width / 2f, height / 2f, s * 0.62f, th.primary)
        }
    }

    private fun chip(type: String, size: Int): GlyphView = GlyphView(type).apply {
        background = GradientDrawable().apply { setColor(th.surface); cornerRadius = ui.dp(14).toFloat(); setStroke(ui.dp(1), th.border) }
        layoutParams = LinearLayout.LayoutParams(ui.dp(size), ui.dp(size))
    }

    private fun lanLine(): String {
        val ip = try { RemoteClient.ipTowards("") } catch (_: Throwable) { "" }
        return if (ip.isEmpty()) "📵 مش متصل بشبكة محلية (واي فاي)" else "📶 الشبكة المحلية · $ip"
    }

    private fun typeIcon(t: String) = when (t) { "tv" -> "📺"; "tablet" -> "📱"; else -> "📱" }

    // ═════════════ الرئيسية ═════════════
    private fun buildHome(): View {
        val root = col()
        val gear = btn("⚙") { go("settings") { buildSettings() } }
        root.addView(header("📤 نقل الملفات", "جهازك: " + FtIdentity.name() + " · " + FtIdentity.typeLabel(FtIdentity.type()), gear))
        val body = col()
        // جلسات شغّالة / طلبات مستنية
        val live = col()
        body.addView(live, lp(-1, -2).apply { topMargin = ui.dp(8) })
        fun fillLive() {
            live.removeAllViews()
            for (s in FtHub.sessions.filter { it.active }.take(4)) {
                val pend = !s.outgoing && s.status == FtSession.WAITING && s.decision == 0
                val t = if (pend) "📥 طلب من «" + s.peerName + "» — " + s.files.size + " ملف · اضغط للرد"
                else (if (s.outgoing) "📤 بيتبعت لـ «" else "📥 بيستقبل من «") + s.peerName + "» · " + s.percent() + "%"
                val v = ui.text(t, if (tvMode) 16f else 13.5f, th.text, true).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
                styleFocus(v, th.card, 14, true)
                v.setOnClickListener { go("progress") { buildProgress(s) } }
                live.addView(v, lp(-1, -2).apply { bottomMargin = ui.dp(6) })
            }
        }
        fillLive(); updater = { fillLive() }

        data class Card(val icon: String, val title: String, val desc: String, val go: () -> Unit)
        val cards = ArrayList<Card>()
        if (tvMode) {
            cards.add(Card("📲", "من التلفزيون للموبايل", "ابعت ملفات من التلفزيون لموبايل أو تابلت قريب") { radarMode = "tv2m"; go("radar") { buildRadar() } })
        } else {
            cards.add(Card("📱", "موبايل ← موبايل", "ابعت ملفات لموبايل أو تابلت قريب منك") { radarMode = "m2m"; go("radar") { buildRadar() } })
            cards.add(Card("📺", "موبايل ← تلفزيون", "ابعت فيديوهات وصور وملفات للتلفزيون") { radarMode = "m2tv"; go("radar") { buildRadar() } })
            cards.add(Card("📲", "تلفزيون ← موبايل", "تصفّح ملفات التلفزيون ونزّل منها") { radarMode = "tv2m"; go("radar") { buildRadar() } })
        }
        cards.add(Card("📂", "مدير الملفات", "استعرض وأدِر الملفات والمجلدات") { try { startActivity(Intent(this, FileManagerActivity::class.java)) } catch (e: Throwable) { LogStore.err("FtUi:fm", e) } })

        val per = if (tvMode) 4 else 2
        var rowL: LinearLayout? = null
        for ((i, c) in cards.withIndex()) {
            if (i % per == 0) { rowL = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }; body.addView(rowL, lp(-1, -2).apply { topMargin = ui.dp(8) }) }
            val cv = col().apply { setPadding(ui.dp(14), ui.dp(16), ui.dp(14), ui.dp(16)); gravity = Gravity.CENTER_HORIZONTAL }
            val ic = TextView(this).apply {
                text = c.icon; textSize = if (tvMode) 34f else 30f; gravity = Gravity.CENTER
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(th.primary, th.accent)).apply { cornerRadius = ui.dp(18).toFloat() }
            }
            cv.addView(ic, lp(ui.dp(if (tvMode) 72 else 62), ui.dp(if (tvMode) 72 else 62)))
            cv.addView(ui.text(c.title, if (tvMode) 19f else 15.5f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(2)) })
            cv.addView(ui.text(c.desc, if (tvMode) 14f else 12f, th.muted).apply { gravity = Gravity.CENTER })
            styleFocus(cv, th.card, 18)
            cv.setOnClickListener { c.go() }
            rowL!!.addView(cv, lp(0, -2, 1f).apply { setMargins(ui.dp(4), 0, ui.dp(4), 0) })
        }
        if (cards.size % per != 0 && !tvMode) rowL?.addView(View(this), lp(0, 1, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        bar.addView(btn("🕘 سجل النقل") { go("history") { buildHistory() } }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        bar.addView(btn("🪪 الهوية والإعدادات") { go("settings") { buildSettings() } }, lp(0, -2, 1f))
        body.addView(bar, lp(-1, -2).apply { topMargin = ui.dp(14) })
        body.addView(ui.text(lanLine(), if (tvMode) 15f else 12f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, 0) })
        body.addView(ui.text("🔒 الاتصال مشفّر وكل استقبال بيستنى موافقتك — شغّال من غير إنترنت", if (tvMode) 14f else 11.5f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, 0) })
        root.addView(scroll(body), lp(-1, 0, 1f))
        return root
    }

    // ═════════════ الرادار ═════════════
    private fun modeTitle(): String = when (radarMode) {
        "m2m" -> "اختار الجهاز المستهدف"; "m2tv" -> "اختار التلفزيون"
        "tv2m" -> if (tvMode) "اختار الموبايل" else "اختار التلفزيون"
        else -> "اختار الجهاز"
    }

    private fun visibleFor(p: FtPeer): Boolean = when (radarMode) {
        "m2m" -> p.ft && !p.isTv
        "m2tv" -> p.isTv
        "tv2m" -> if (tvMode) p.ft && !p.isTv else p.isTv
        else -> p.ft || p.link != null
    }

    private fun buildRadar(): View {
        val root = col()
        val refresh = btn("🔄") { startScan(true) }
        root.addView(header(modeTitle(), "الأجهزة القريبة على نفس الشبكة", refresh))
        val rv = FtRadarView(this, th).apply { meName = FtIdentity.name(); meType = FtIdentity.type() }
        radar = rv
        root.addView(rv, lp(-1, ui.dp(if (tvMode) 300 else 250)).apply { topMargin = ui.dp(6) })
        val st = ui.text("", if (tvMode) 16f else 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(4), 0, ui.dp(6)) }
        radarStatus = st
        root.addView(st, lp(-1, -2))
        val lst = col()
        radarList = lst
        root.addView(scroll(lst), lp(-1, 0, 1f))
        renderPeers()
        startScan(false)
        return root
    }

    private fun qualityOf(p: FtPeer): String = when {
        p.rtt <= 0 -> ""
        p.rtt <= 20 -> " · اتصال ممتاز"
        p.rtt <= 80 -> " · اتصال كويس"
        else -> " · اتصال ضعيف"
    }

    private fun displayName(p: FtPeer): String =
        if (peers.values.count { it.name == p.name } > 1) p.name + " #" + p.id.takeLast(3) else p.name

    private fun renderPeers() {
        val lst = radarList ?: return
        val vis = peers.values.filter { visibleFor(it) }
        radar?.setPeers(vis)
        val ids = vis.map { it.id }.toSet()
        for (id in peerRows.keys.toList()) { if (id !in ids) { peerRows.remove(id)?.let { lst.removeView(it.root) } } }
        for (p in vis) {
            var row = peerRows[p.id]
            if (row == null) {
                val g = chip(p.type, if (tvMode) 56 else 46)
                val name = ui.text("", if (tvMode) 19f else 15.5f, th.text, true)
                val sub = ui.text("", if (tvMode) 14f else 11.5f, th.muted)
                val badge = ui.text("", if (tvMode) 13f else 11f, th.primary, true)
                val c = col(); c.addView(name); c.addView(sub)
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(10), ui.dp(8), ui.dp(12), ui.dp(8)) }
                r.addView(g); r.addView(c, lp(0, -2, 1f).apply { marginStart = ui.dp(10); marginEnd = ui.dp(6) }); r.addView(badge)
                styleFocus(r, th.card, 14)
                val pid = p.id
                r.setOnClickListener { peers[pid]?.let { onPeer(it) } }
                lst.addView(r, lp(-1, -2).apply { bottomMargin = ui.dp(6) })
                row = PeerRow(r, name, sub, g, badge)
                peerRows[p.id] = row
            }
            row.name.text = displayName(p)
            row.glyph.type = p.type; row.glyph.invalidate()
            row.sub.text = FtIdentity.typeLabel(p.type) + " · " + p.ip + qualityOf(p)
            row.badge.text = if (p.ft) "🔒 آمن" else "🔗 ربط"
        }
        val n = vis.size
        radarStatus?.text = if (scanning && n == 0) "⏳ بدوّر على أجهزة قريبة…" else if (n == 0) "مفيش أجهزة ظاهرة — اتأكد إن الجهاز التاني فاتح «نقل الملفات» وعلى نفس الواي فاي" else if (scanning) "⏳ لقيت $n جهاز — لسه بدوّر…" else "لقيت $n جهاز — اضغط على جهاز للإرسال"
    }

    private fun startScan(manual: Boolean) {
        if (scanning) { if (manual) Notice.show(this, "⏳ البحث شغّال", 1500L); return }
        scanning = true; radar?.scanning = true
        val my = ++scanGen
        val app = applicationContext
        renderPeers()
        Thread {
            val res = try {
                FtPeers.discover(app, 3800L) { p -> h.post { if (my == scanGen && !isFinishing && !isDestroyed) { peers[p.id] = p; miss.remove(p.id); renderPeers() } } }
            } catch (e: Throwable) { LogStore.err("FtUi:scan", e); emptyList() }
            h.post {
                scanning = false
                if (my != scanGen || isFinishing || isDestroyed) return@post
                radar?.scanning = false
                val ids = res.map { it.id }.toSet()
                for (p in res) { peers[p.id] = p; miss.remove(p.id) }
                for (id in peers.keys.toList()) { if (id !in ids) { val m = (miss[id] ?: 0) + 1; if (m >= 2) { peers.remove(id); miss.remove(id) } else miss[id] = m } }
                renderPeers()
                h.postDelayed({ if (curTag == "radar" && !isFinishing && !isDestroyed) startScan(false) }, 9000L)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun onPeer(p: FtPeer) {
        if (radarMode == "tv2m" && !tvMode) {
            if (p.link != null) go("tvbrowse") { buildTvBrowse(p) }
            else Notice.show(this, "⚠ التلفزيون ده محتاج تحديث — أو ابعت من التلفزيون نفسه (نقل الملفات ← من التلفزيون للموبايل)", 4500L)
            return
        }
        if (!p.ft && p.link == null) { Notice.show(this, "⚠ الجهاز ده مش بيدعم النقل", 2500L); return }
        val pre = presetPicks
        if (pre != null) {
            AlertDialog.Builder(this).setTitle("إرسال").setMessage("تبعت " + pre.size + " ملف لـ «" + p.name + "»؟")
                .setPositiveButton("إرسال") { _, _ -> startSend(p, pre) }.setNegativeButton("إلغاء", null).show()
            return
        }
        go("picker") { buildPicker(p) }
    }

    private fun buildPicker(p: FtPeer): View {
        val pk = FtPicker(this, ui, th, tvMode, { picks -> startSend(p, picks) }, { pop() })
        picker = pk
        return pk.view
    }

    private fun startSend(p: FtPeer, picks: List<FtPick>) {
        val s = try { if (p.ft) FtSend.start(applicationContext, p, picks) else FtLinkSend.start(applicationContext, p, picks) }
        catch (e: Throwable) { LogStore.err("FtUi:send", e); Notice.show(this, "⚠ فشل بدء الإرسال", 2500L); return }
        FtUiState.picks[s.sid] = picks
        FtUiState.peers[s.sid] = p
        presetPicks = null
        shownSids.add(s.sid)
        while (stack.size > 1) stack.removeAt(stack.size - 1)
        go("progress") { buildProgress(s) }
    }

    // ═════════════ الاستقبال / التقدم ═════════════
    private var progRefresh: (() -> Unit)? = null
    private var backHook: (() -> Boolean)? = null

    private fun fmtT(sec: Long): String { val s = sec.coerceAtLeast(0L); return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60) else String.format("%d:%02d", s / 60, s % 60) }

    private fun buildProgress(s: FtSession): View {
        val box = FrameLayout(this)
        var mode = ""
        fun render() {
            val pending = !s.outgoing && s.status == FtSession.WAITING && s.decision == 0 && !s.cancelled
            val want = if (pending) "req" else "prog"
            if (want != mode) {
                mode = want; box.removeAllViews(); progRefresh = null
                val v = if (pending) requestView(s) else progressView(s)
                box.addView(v, FrameLayout.LayoutParams(-1, -1))
                if (tvMode) v.post { v.requestFocus() }
            }
            progRefresh?.invoke()
        }
        updater = { render() }
        render()
        return box
    }

    private fun requestView(s: FtSession): View {
        val root = col()
        root.addView(header("📥 طلب استقبال", "من «" + s.peerName + "»"))
        val body = col()
        val total = s.files.sumOf { it.size }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); background = ui.box(th.card, th.border, 14) }
        top.addView(chip(s.peerType, if (tvMode) 64 else 52))
        val tc = col()
        tc.addView(ui.text(s.peerName, if (tvMode) 21f else 17f, th.text, true))
        tc.addView(ui.text(FtIdentity.typeLabel(s.peerType) + " · " + s.peerIp, if (tvMode) 14f else 12f, th.muted))
        val summary = ui.text("", if (tvMode) 16f else 13.5f, th.primary, true)
        tc.addView(summary)
        top.addView(tc, lp(0, -2, 1f).apply { marginStart = ui.dp(10) })
        body.addView(top, lp(-1, -2).apply { topMargin = ui.dp(8) })
        fun upd() { val a = s.acceptedFiles; summary.text = a.size.toString() + " من " + s.files.size + " ملف · " + Sniff.fmtSize(a.sumOf { it.size }) }
        upd()
        if (s.retry) body.addView(ui.text("🔁 إعادة محاولة لملفات فشلت قبل كده", if (tvMode) 14f else 12f, th.muted).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0) })
        val tr = FtStore.isTrusted(s.peerId, s.peerFp)
        body.addView(ui.text("🪪 بصمة هويته: " + FtCrypto.fpPretty(s.peerFp) + (if (tr) " · جهاز موثوق" else ""), if (tvMode) 14f else 11.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), 0) })
        body.addView(ui.text("المعاينة مش متاحة قبل النقل — كل ملف بيتفحص بالهاش بعد ما يوصل، ومفيش ملف بيتكتب فوق ملف موجود.", if (tvMode) 13.5f else 11.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(6)) })
        // قايمة الملفات (دوسة = استبعاد/إضافة)
        val shownMax = 150
        for (f in s.files.take(shownMax)) {
            val tvr = ui.text("", if (tvMode) 16f else 13.5f, th.text).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); maxLines = 2 }
            fun paint() { tvr.text = (if (f.accepted) "☑ " else "☐ ") + XCat.emoji(f.cat) + " " + f.rel + " · " + Sniff.fmtSize(f.size); tvr.alpha = if (f.accepted) 1f else 0.55f }
            paint()
            styleFocus(tvr, th.surface, 10)
            tvr.setOnClickListener { f.accepted = !f.accepted; paint(); upd() }
            body.addView(tvr, lp(-1, -2).apply { bottomMargin = ui.dp(3) })
        }
        if (s.files.size > shownMax) body.addView(ui.text("… و " + (s.files.size - shownMax) + " ملف كمان (هيتقبلوا مع الباقي)", if (tvMode) 14f else 12f, th.muted).apply { setPadding(ui.dp(6), ui.dp(4), 0, ui.dp(4)) })
        body.addView(ui.text("📂 مكان الحفظ", if (tvMode) 16f else 13.5f, th.text, true).apply { setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(2)) })
        val l0 = "حسب النوع (Movies · Music · Pictures)"; val l1 = "Download/Joyboy"
        body.addView(ui.chips(listOf(l0, l1), { if (s.saveMode == 0) l0 else l1 }, { s.saveMode = if (it == l0) 0 else 1 }))
        body.addView(ui.switchRow("🤝 ثق في الجهاز ده (استقبال تلقائي منه بعد كده لو فعّلت الإعداد)", s.trustAfter) { s.trustAfter = it })
        root.addView(scroll(body), lp(-1, 0, 1f))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        bar.addView(btn("✅ قبول الكل", true) { for (f in s.files) f.accepted = true; s.decision = 1; FtHub.changed() }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        bar.addView(btn("✔ قبول المحدد") { if (s.acceptedFiles.isEmpty()) Notice.show(this, "اختار ملف واحد على الأقل", 2000L) else { s.decision = 1; FtHub.changed() } }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        bar.addView(btn("⛔ رفض") { s.decision = 2; FtHub.changed(); pop() }, lp(0, -2, 1f))
        root.addView(bar, lp(-1, -2).apply { topMargin = ui.dp(6) })
        return root
    }

    private fun stIcon(f: FtFile, out: Boolean): String = when (f.state) {
        FtState.WAIT -> "⏳"; FtState.RUN -> if (out) "⬆" else "⬇"; FtState.DONE -> "✅"; FtState.FAIL -> "❌"; else -> "⏹"
    }
    private fun stLabel(f: FtFile): String = when (f.state) {
        FtState.WAIT -> "في الانتظار"; FtState.RUN -> "بيتنقل"; FtState.DONE -> "اكتمل"; FtState.FAIL -> "فشل"; else -> "اتلغى"
    }

    private fun progressView(s: FtSession): View {
        val root = col()
        root.addView(header((if (s.outgoing) "📤 إرسال إلى «" else "📥 استقبال من «") + s.peerName + "»", if (s.legacy) "عبر ربط التلفزيون" else "🔒 مشفّر من طرف لطرف"))
        val top = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        top.addView(chip(s.peerType, if (tvMode) 56 else 44))
        val ring = FtRingView(this, th)
        top.addView(ring, lp(ui.dp(if (tvMode) 190 else 160), ui.dp(if (tvMode) 190 else 160)).apply { topMargin = ui.dp(6) })
        val stats = ui.text("", if (tvMode) 16f else 13f, th.text, true).apply { gravity = Gravity.CENTER }
        val msg = ui.text("", if (tvMode) 15f else 12.5f, th.muted).apply { gravity = Gravity.CENTER }
        val sas = ui.text("", if (tvMode) 15f else 12.5f, th.primary, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6)); background = ui.box(th.surface, th.border, 10); visibility = View.GONE }
        top.addView(stats); top.addView(msg); top.addView(sas, lp(-2, -2).apply { topMargin = ui.dp(4) })
        root.addView(top, lp(-1, -2))
        val lv = ListView(this).apply { divider = null; dividerHeight = ui.dp(2); isFocusable = true; cacheColorHint = Color.TRANSPARENT; selector = listSelector() }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = s.files.size
            override fun getItem(p: Int): Any = s.files[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val f = s.files[p]
                val t = (cv as? TextView) ?: ui.text("", if (tvMode) 15f else 12.5f, th.text).apply { setPadding(ui.dp(10), ui.dp(7), ui.dp(10), ui.dp(7)); maxLines = 3 }
                val detail = when (f.state) {
                    FtState.RUN -> Sniff.fmtSize(f.done.coerceAtMost(f.size)) + " / " + Sniff.fmtSize(f.size)
                    FtState.FAIL -> f.err
                    FtState.CANCEL -> if (f.err.isNotEmpty()) f.err else stLabel(f)
                    else -> Sniff.fmtSize(f.size)
                }
                t.text = stIcon(f, s.outgoing) + " " + f.rel + "\n    " + stLabel(f) + " · " + detail
                t.setTextColor(if (f.state == FtState.FAIL) th.danger else if (!f.accepted) th.muted else th.text)
                return t
            }
        }
        root.addView(lv, lp(-1, 0, 1f).apply { topMargin = ui.dp(6) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(bar, lp(-1, -2).apply { topMargin = ui.dp(6) })
        var lastStatus = -1
        fun buttons() {
            bar.removeAllViews()
            if (s.active) {
                bar.addView(btn("⏹ إلغاء النقل", false) { s.cancelled = true; s.msg = "بيلغي…"; FtHub.changed() }, lp(0, -2, 1f))
            } else {
                val failed = s.failedFiles()
                if (s.outgoing && failed.isNotEmpty() && FtUiState.picks.containsKey(s.sid) && FtUiState.peers.containsKey(s.sid))
                    bar.addView(btn("🔁 إعادة محاولة الفاشل (" + failed.size + ")", true) { retryFailed(s) }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
                bar.addView(btn("🕘 السجل") { go("history") { buildHistory() } }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
                bar.addView(btn("✔ تم", failed.isEmpty() || !s.outgoing) { while (stack.size > 1) stack.removeAt(stack.size - 1); show() }, lp(0, -2, 1f))
            }
            if (tvMode) bar.post { bar.requestFocus() }
        }
        progRefresh = {
            val pct = s.percent()
            ring.setProgress(pct / 100f); ring.label = "$pct%"
            ring.sub = s.doneFiles().toString() + "/" + s.acceptedFiles.size + " ملف"
            ring.failed = s.status == FtSession.FAILED || s.status == FtSession.REJECTED
            val eta = s.etaSec()
            stats.text = Sniff.fmtSize(s.doneBytes()) + " / " + Sniff.fmtSize(s.totalBytes()) +
                (if (s.active && s.speedBps > 1.0) " · ⚡ " + String.format("%.1f MB/s", s.speedBps / 1048576.0) else "") + (if (s.active && eta >= 0L) " · باقي " + fmtT(eta) else "")
            msg.text = s.msg
            if (s.sas.isNotEmpty() && !s.legacy) { sas.text = "🔐 كود التحقق: " + s.sas + " — لازم يطابق الرقم على الجهاز التاني"; sas.visibility = View.VISIBLE } else sas.visibility = View.GONE
            (lv.adapter as BaseAdapter).notifyDataSetChanged()
            if (s.status != lastStatus) { lastStatus = s.status; buttons() }
        }
        return root
    }

    private fun retryFailed(s: FtSession) {
        val rel = s.failedFiles().map { it.rel }.toSet()
        val picks = FtUiState.picks[s.sid]?.filter { it.rel in rel } ?: emptyList()
        val peer = FtUiState.peers[s.sid]
        if (picks.isEmpty() || peer == null) { Notice.show(this, "⚠ مش قادر أعيد المحاولة — ابعت الملفات تاني", 3000L); return }
        val ns = try { if (peer.ft) FtSend.start(applicationContext, peer, picks, true) else FtLinkSend.start(applicationContext, peer, picks) }
        catch (e: Throwable) { LogStore.err("FtUi:retry", e); Notice.show(this, "⚠ فشلت إعادة المحاولة", 2500L); return }
        FtUiState.picks[ns.sid] = picks; FtUiState.peers[ns.sid] = peer; shownSids.add(ns.sid)
        if (stack.size > 1) stack.removeAt(stack.size - 1)
        go("progress") { buildProgress(ns) }
    }

    // ═════════════ تصفّح ملفات التلفزيون (ربط) ═════════════
    private class RE(val name: String, val dir: Boolean, val size: Long, val path: String)

    private fun buildTvBrowse(p: FtPeer): View {
        val root = col()
        root.addView(header("📺 ملفات " + p.name, "اختار ملفات أو مجلدات ونزّلها على الموبايل"))
        val info = ui.text("⏳ بوصّل…", if (tvMode) 15f else 12.5f, th.muted).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4)) }
        root.addView(info)
        var items: List<RE> = emptyList()
        var parent = ""
        var cur = ""
        val sel = LinkedHashMap<String, RE>()
        val lv = ListView(this).apply { divider = null; dividerHeight = ui.dp(2); isFocusable = true; cacheColorHint = Color.TRANSPARENT; selector = listSelector() }
        val bottom = ui.text("", if (tvMode) 16f else 13f, th.text, true).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(2)) }
        fun bar() { bottom.text = if (sel.isEmpty()) "مفيش ملفات متحدّدة" else "اتحدّد " + sel.size + " عنصر" }
        fun toggle(e: RE) { if (sel.remove(e.path) == null) sel[e.path] = e; (lv.adapter as BaseAdapter).notifyDataSetChanged(); bar() }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(i: Int): Any = items[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, par: ViewGroup?): View {
                val e = items[i]
                val t = (cv as? TextView) ?: ui.text("", if (tvMode) 17f else 14f, th.text).apply { setPadding(ui.dp(12), ui.dp(11), ui.dp(12), ui.dp(11)); maxLines = 1 }
                t.text = (if (sel.containsKey(e.path)) "☑ " else "☐ ") + (if (e.dir) "📁 " else XCat.emoji(XCat.catOf(e.name)) + " ") + e.name + (if (e.dir) "" else " · " + Sniff.fmtSize(e.size))
                return t
            }
        }
        fun load(path: String) {
            info.text = "⏳ بحمّل…"
            val app = applicationContext
            Thread {
                var er: String? = null
                if (RemoteClient.state != RemoteClient.St.CONNECTED || RemoteClient.current?.id != p.link?.id) { val lf = p.link; er = if (lf == null) "التلفزيون مش بيدعم الربط" else FtLinkSend.connect(app, lf) }
                var list: List<RE> = emptyList(); var par = ""; var noAcc = false; var curP = path
                if (er == null) {
                    val (c, t) = RemoteClient.getText("/fs/list?path=" + URLEncoder.encode(path, "UTF-8"), 25000)
                    if (c == 404) er = "التلفزيون محتاج تحديث للنسخة دي" else if (c != 200) er = "ما قدرتش أجيب القايمة ($c)" else {
                        try {
                            val j = JSONObject(t)
                            noAcc = !j.optBoolean("access", true); par = j.optString("parent"); curP = j.optString("path")
                            val a = j.optJSONArray("items")
                            val out = ArrayList<RE>()
                            if (a != null) for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(RE(o.optString("n"), o.optBoolean("d"), o.optLong("s"), o.optString("p"))) }
                            list = out
                        } catch (e: Throwable) { er = "رد التلفزيون مش مفهوم" }
                    }
                }
                h.post {
                    if (isFinishing || isDestroyed || curTag != "tvbrowse") return@post
                    if (er != null) { info.text = "⚠ $er"; return@post }
                    items = list; parent = par; cur = curP
                    info.text = (if (cur.isEmpty()) "الذاكرة" else cur) + " · " + list.size + " عنصر" + (if (noAcc) " · ⚠ التلفزيون محتاج صلاحية «كل الملفات»" else "")
                    (lv.adapter as BaseAdapter).notifyDataSetChanged(); bar()
                }
            }.also { it.isDaemon = true }.start()
        }
        lv.setOnItemClickListener { _, _, i, _ -> val e = items.getOrNull(i) ?: return@setOnItemClickListener; if (e.dir) load(e.path) else toggle(e) }
        lv.setOnItemLongClickListener { _, _, i, _ -> val e = items.getOrNull(i); if (e != null && e.dir) { toggle(e); true } else false }
        backHook = { if (cur.isNotEmpty()) { load(parent); true } else false }
        root.addView(lv, lp(-1, 0, 1f))
        root.addView(bottom)
        val bt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        bt.addView(btn("⬇ نزّل المحدد", true) { downloadFromTv(p, sel.values.toList()) }, lp(0, -2, 1.4f).apply { marginEnd = ui.dp(4) })
        bt.addView(btn("✕ إلغاء التحديد") { sel.clear(); (lv.adapter as BaseAdapter).notifyDataSetChanged(); bar() }, lp(0, -2, 1f))
        root.addView(bt, lp(-1, -2).apply { topMargin = ui.dp(4) })
        bar(); load("")
        return root
    }

    private fun downloadFromTv(p: FtPeer, chosen: List<RE>) {
        if (chosen.isEmpty()) { Notice.show(this, "اختار ملف أو مجلد الأول", 2200L); return }
        val app = applicationContext
        Notice.show(this, "⏳ بجهّز القايمة…", 1500L)
        Thread {
            val jobs = ArrayList<XRemote.PathJob>()
            fun walk(path: String, rel: String, depth: Int) {
                if (jobs.size > FtOut.MAX_FILES || depth > 6) return
                val (c, t) = RemoteClient.getText("/fs/list?path=" + URLEncoder.encode(path, "UTF-8"), 25000)
                if (c != 200) return
                val a = try { JSONObject(t).optJSONArray("items") } catch (_: Throwable) { null } ?: return
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val n = o.optString("n"); val pa = o.optString("p")
                    if (o.optBoolean("d")) walk(pa, "$rel/$n", depth + 1) else if (jobs.size <= FtOut.MAX_FILES) jobs.add(XRemote.PathJob(pa, n, o.optLong("s"), rel))
                }
            }
            for (e in chosen) { if (e.dir) walk(e.path, FtPath.cleanRel(e.name), 0) else jobs.add(XRemote.PathJob(e.path, e.name, e.size, "")) }
            h.post {
                if (jobs.isEmpty()) Notice.show(this, "⚠ مفيش ملفات صالحة", 2500L)
                else if (jobs.size > FtOut.MAX_FILES) Notice.show(this, "⚠ لحد " + FtOut.MAX_FILES + " ملف في المرة — قسّمهم على دفعات", 3500L)
                else if (XRemote.downloadPaths(app, p.name, jobs)) Notice.show(this, "⬇ بدأ تنزيل " + jobs.size + " ملف — التقدم في صفحة المهام", 3500L)
                else Notice.show(this, "⚠ مش متصل بالتلفزيون", 2500L)
            }
        }.also { it.isDaemon = true }.start()
    }

    // ═════════════ السجل ═════════════
    private var histDir = "out"
    private var histFilter = "all"
    private class HRow(val title: String, val sub: String, val state: String, val h: FtHist?, val s: FtSession?)

    private fun buildHistory(): View {
        val root = col()
        root.addView(header("🕘 سجل النقل", "الملفات اللي اتبعتت واللي اتستلمت"))
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val flt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(tabs, lp(-1, -2).apply { topMargin = ui.dp(8) })
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(flt, FrameLayout.LayoutParams(-2, -2)) }, lp(-1, -2).apply { topMargin = ui.dp(6) })
        val empty = ui.text("", if (tvMode) 16f else 13f, th.muted).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(16)) }
        root.addView(empty, lp(-1, -2))
        var rows: List<HRow> = emptyList()
        val lv = ListView(this).apply { divider = null; dividerHeight = ui.dp(2); isFocusable = true; cacheColorHint = Color.TRANSPARENT; selector = listSelector() }
        val ad = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(i: Int): Any = rows[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, cv: View?, par: ViewGroup?): View {
                val r = rows[i]
                val t = (cv as? TextView) ?: ui.text("", if (tvMode) 16f else 13.5f, th.text).apply { setPadding(ui.dp(12), ui.dp(9), ui.dp(12), ui.dp(9)); maxLines = 3 }
                val ic = when (r.state) { "ok" -> "✅"; "run" -> "⏳"; "fail" -> "❌"; else -> "⏹" }
                t.text = ic + " " + r.title + "\n    " + r.sub
                t.setTextColor(if (r.state == "fail") th.danger else th.text)
                return t
            }
        }
        lv.adapter = ad
        fun rebuild() {
            val out = histDir == "out"
            val live = FtHub.sessions.filter { it.active && it.outgoing == out }.map { s ->
                HRow(s.files.size.toString() + " ملف · " + s.percent() + "%", (if (out) "إلى " else "من ") + s.peerName + " · " + Sniff.fmtSize(s.totalBytes()), "run", null, s)
            }
            val hist = FtStore.history().filter { it.dir == histDir }.map { x ->
                HRow(x.name, x.peerName + " · " + Sniff.fmtSize(x.size) + " · " + fmt.format(Date(x.ts)) + (if (x.note.isNotEmpty()) " · " + x.note else ""), x.state, x, null)
            }
            rows = (live + hist).filter { when (histFilter) { "all" -> true; "ok" -> it.state == "ok"; "run" -> it.state == "run"; else -> it.state == "fail" || it.state == "cancel" } }
            ad.notifyDataSetChanged()
            empty.text = if (rows.isEmpty()) "مفيش عمليات هنا" else ""
        }
        fun pills() {
            tabs.removeAllViews(); flt.removeAllViews()
            tabs.addView(btn("📤 المُرسَلة", histDir == "out") { histDir = "out"; pills(); rebuild() }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
            tabs.addView(btn("📥 المُستلَمة", histDir == "in") { histDir = "in"; pills(); rebuild() }, lp(0, -2, 1f))
            for ((k, l) in listOf("all" to "الكل", "ok" to "اكتملت", "run" to "جارية", "fail" to "فشلت"))
                flt.addView(btn(l, histFilter == k) { histFilter = k; pills(); rebuild() }, lp(-2, -2).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
        }
        lv.setOnItemClickListener { _, _, i, _ ->
            val r = rows.getOrNull(i) ?: return@setOnItemClickListener
            val ss = r.s
            if (ss != null) { go("progress") { buildProgress(ss) }; return@setOnItemClickListener }
            val hh = r.h ?: return@setOnItemClickListener
            val f = File(hh.path)
            val exists = hh.path.startsWith("/") && f.isFile
            val labels = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
            if (exists) {
                labels.add("▶ فتح الملف"); acts.add { openFile(f) }
                labels.add("↻ إعادة إرسال"); acts.add { resend(f) }
            }
            labels.add("🗑 حذف من السجل"); acts.add { FtStore.remove(hh); rebuild() }
            AlertDialog.Builder(this).setTitle(hh.name).setItems(labels.toTypedArray()) { _, k -> acts[k]() }.setNegativeButton("إغلاق", null).show()
        }
        root.addView(lv, lp(-1, 0, 1f).apply { topMargin = ui.dp(6) })
        pills(); rebuild()
        updater = { rebuild() }
        return root
    }

    private fun openFile(f: File) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(FmUtil.uriFor(this, f), FmUtil.mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Throwable) { LogStore.err("FtUi:open", e); Notice.show(this, "⚠ مفيش تطبيق يفتح الملف ده", 2500L) }
    }

    private fun resend(f: File) {
        presetPicks = listOf(FtPick(f.name, f.length(), Uri.fromFile(f), f.absolutePath))
        radarMode = "any"
        go("radar") { buildRadar() }
    }

    // ═════════════ الهوية والإعدادات ═════════════
    private fun buildSettings(): View {
        val root = col()
        root.addView(header("🪪 الهوية والإعدادات", "اسم جهازك اللي بيظهر للأجهزة التانية"))
        val body = col()
        val card = col().apply { setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); background = ui.box(th.card, th.border, 14) }
        val nameTv = ui.text("", if (tvMode) 28f else 24f, th.primary, true)
        val metaTv = ui.text("", if (tvMode) 15f else 12.5f, th.muted)
        val fp = try { FtCrypto.fpPretty(FtCrypto.fingerprint(FtCrypto.identity(this).public.encoded)) } catch (_: Throwable) { "—" }
        fun refresh() { nameTv.text = FtIdentity.name(); metaTv.text = "النوع: " + FtIdentity.typeLabel(FtIdentity.type()) + (if (FtIdentity.isCustom()) " · اسم معدّل" else " · اسم تلقائي") }
        refresh()
        card.addView(nameTv); card.addView(metaTv)
        card.addView(ui.text("🪪 بصمة هويتك: $fp", if (tvMode) 14f else 11.5f, th.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
        card.addView(ui.text("الاسم بيتحفظ تلقائي. مفيش أسماء أشخاص ولا أرقام موبايل بتتاخد.", if (tvMode) 14f else 11.5f, th.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        body.addView(card, lp(-1, -2).apply { topMargin = ui.dp(8) })
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        r1.addView(btn("✏ تعديل الاسم") {
            val et = ui.input("اسم الجهاز", FtIdentity.name())
            GAlert(this).setTitle("✏ اسم الجهاز").setView(et)
                .setPositiveButton("حفظ") { _, _ -> if (FtIdentity.setName(et.text.toString())) { refresh(); Notice.show(this, "✅ اتحفظ", 1500L) } else Notice.show(this, "اكتب اسم صالح", 2000L) }
                .setNegativeButton("إلغاء", null).show()
        }, lp(0, -2, 1f).apply { marginEnd = ui.dp(4) })
        r1.addView(btn("🎲 اسم عشوائي") { FtIdentity.regenerate(peers.values.map { it.name }.toSet()); refresh() }, lp(0, -2, 1f))
        body.addView(r1, lp(-1, -2).apply { topMargin = ui.dp(8) })
        body.addView(btn("↩ استعادة الاسم الافتراضي") { FtIdentity.resetDefault(); refresh() }, lp(-1, -2).apply { topMargin = ui.dp(6) })

        body.addView(ui.section("🔒 الاستقبال", true,
            ui.switchRow("استقبال تلقائي من الأجهزة الموثوقة بس (اللي حدّدتها بنفسك وبنفس البصمة)", FtIdentity.autoTrusted()) { FtIdentity.setAutoTrusted(it) },
            ui.switchRow("فضّل جاهز للاستقبال بعد قفل شاشة النقل (طول ما التطبيق شغّال)", FtIdentity.keepReady()) { FtIdentity.setKeepReady(it) },
            ui.text("من غير الإعدادين دول: أي طلب استقبال لازم توافق عليه بإيدك.", if (tvMode) 14f else 11.5f, th.muted)))

        val tr = FtStore.trusted()
        val trBox = col()
        fun fillTrusted() {
            trBox.removeAllViews()
            val l = FtStore.trusted()
            if (l.isEmpty()) trBox.addView(ui.text("مفيش أجهزة موثوقة", if (tvMode) 15f else 12.5f, th.muted))
            for (t in l) {
                val row = btn("✕ " + t.name + " · " + FtCrypto.fpPretty(t.fp).take(9)) { FtStore.untrust(t.id); fillTrusted() }
                trBox.addView(row, lp(-1, -2).apply { bottomMargin = ui.dp(4) })
            }
        }
        fillTrusted()
        body.addView(ui.section("🤝 الأجهزة الموثوقة (" + tr.size + ")", true, trBox))

        val net = ui.text(lanLine() + "\nمنافذ النقل: TCP " + FtPeers.PORT + " · UDP " + FtPeers.UDP_PORT + "\nالخدمة: " + (if (FtPeers.running) "شغّالة" else "واقفة"), if (tvMode) 14f else 11.5f, th.muted)
        body.addView(ui.section("📡 الشبكة", false, net))
        body.addView(btn("🗑 مسح سجل النقل") {
            AlertDialog.Builder(this).setTitle("مسح السجل؟").setMessage("هيتمسح سجل العمليات بس — الملفات نفسها مش هتتأثر.")
                .setPositiveButton("مسح") { _, _ -> FtStore.clear(); Notice.show(this, "اتمسح", 1500L) }.setNegativeButton("إلغاء", null).show()
        }, lp(-1, -2).apply { topMargin = ui.dp(8) })
        root.addView(scroll(body), lp(-1, 0, 1f))
        return root
    }
}
