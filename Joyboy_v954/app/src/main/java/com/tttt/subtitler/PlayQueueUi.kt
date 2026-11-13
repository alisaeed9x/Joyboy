package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * (v694) واجهة طابور التشغيل + «ضيف للطابور» + «كمّل على الموبايل».
 *  - show(act): ورقة الطابور — تابين (📺 التلفزيون / 📱 الموبايل)، سحب ☰ لإعادة الترتيب، ▶ شغّل دلوقتي، ✕ شيل واحد، 🗑 مسح الكل.
 *  - add(act, ref, title): من يوتيوب / المتصفح / المشغّل / المكتبة — بيضيف لطابور التلفزيون أو الموبايل (وبيسأل لو مش واضح).
 *  - handoffToPhone(act): الفيديو الشغّال على التلفزيون يكمّل على الموبايل من نفس الثانية.
 */
object PlayQueueUi {
    private const val BG = 0xFF0F1830.toInt()
    private const val CARD = 0xFF16233F.toInt()
    private const val LINE = 0xFF2A3A5E.toInt()
    private const val DIM = 0xFF8FA3C8.toInt()
    private const val ACC = 0xFF5B8DEF.toInt()
    private const val RED = 0xFFE5484D.toInt()

    private fun short(ref: String): String = try { android.net.Uri.parse(ref).let { (it.host ?: "") + (it.lastPathSegment?.let { s -> "/" + s } ?: "") } } catch (_: Throwable) { ref }.ifBlank { ref }.take(60)

    private fun isLocal(ref: String) = ref.startsWith("content:", true) || ref.startsWith("file:", true) || ref.startsWith("/")

    // ───────────────────────── إضافة ─────────────────────────

    /** يضيف للطابور. على التلفزيون نفسه ← طابوره؛ على الموبايل: وضع التلفزيون ← التلفزيون، مفيش تلفزيون ← الموبايل، غير كده بيسأل */
    fun add(act: Activity, ref0: String, title: String, forceTv: Boolean? = null) {
        val ref = ref0.trim()
        if (ref.isEmpty()) return
        val isTv = DeviceUi.isTv(act)
        fun toPhone() {
            if (ref.startsWith(LibraryCore.SCHEME, true)) { Notice.show(act, "⚠ ده فيديو في مكتبة التلفزيون — ضيفه لليستة التلفزيون", 3500L); return }
            PlayQueue.add(act, ref, title)
            Notice.show(act, "➕ اتضاف للليستة — " + (if (isTv) "التلفزيون" else "الموبايل") + " (" + PlayQueue.size(act) + ")" + (if (title.isNotBlank()) " — " + title.take(40) else ""), 2800L)
        }
        fun toTv() {
            Thread {
                var msg = ""
                try {
                    RemoteClient.init(act)
                    if (!RemoteClient.ensureConnected(act, 8000L)) msg = "⚠ مش متصل بالتلفزيون — اتأكد إنه شغّال وعلى نفس الواي فاي"
                    else {
                        val r = if (isLocal(ref)) CastServer.publish(act, ref, title) else ref   // ملف من الموبايل: التلفزيون يسحبه من الموبايل وقت التشغيل
                        if (r == null) msg = "⚠ ما قدرتش أجهّز الملف للتلفزيون"
                        else {
                            val (c, _) = RemoteClient.postJson("/queue", JSONObject().put("a", "add").put("ref", r).put("title", title))
                            msg = if (c == 200) "➕ اتضاف لليستة التلفزيون" + (if (title.isNotBlank()) " — " + title.take(40) else "") else "⚠ فشل الإضافة (كود $c)"
                        }
                    }
                } catch (e: Throwable) { LogStore.err("PlayQueueUi:addTv", e); msg = "⚠ حصلت مشكلة" }
                try { act.runOnUiThread { Notice.show(act, msg, 3200L) } } catch (_: Throwable) {}
            }.also { it.isDaemon = true }.start()
        }
        if (isTv) { toPhone(); return }
        // (v779) من ريموت الموبايل (➕) أو فيديو من مكتبة التلفزيون ← ليستة التلفزيون على طول من غير سؤال
        if (forceTv == true || ref.startsWith(LibraryCore.SCHEME, true)) { toTv(); return }
        if (forceTv == false) { toPhone(); return }
        try { RemoteClient.init(act) } catch (_: Throwable) {}
        when {
            PlayTarget.enabled() -> toTv()
            RemoteClient.lastTv() == null -> toPhone()
            else -> try {
                GAlert(act).setTitle("➕ ضيفه لأنهي ليستة؟").setMessage(title.ifBlank { short(ref) })
                    .setPositiveButton("📺 التلفزيون") { _, _ -> toTv() }
                    .setNegativeButton("📱 الموبايل") { _, _ -> toPhone() }.show()
            } catch (_: Throwable) { toPhone() }
        }
    }

    // ───────────────────────── كمّل على الموبايل ─────────────────────────

    /** الفيديو الشغّال على التلفزيون يكمّل على الموبايل من نفس الثانية (لينك / يوتيوب / فيديو الموبايل نفسه). ملف جوه التلفزيون مش ينفع. */
    fun handoffToPhone(act: Activity, done: (() -> Unit)? = null) {
        Notice.show(act, "📱 باخد الفيديو من التلفزيون…", 2200L)
        Thread {
            var msg: String? = null
            var info: JSONObject? = null
            try {
                RemoteClient.init(act)
                if (!RemoteClient.ensureConnected(act, 8000L)) msg = "⚠ مش متصل بالتلفزيون"
                else {
                    val (c, t) = RemoteClient.postJson("/handoff", JSONObject())
                    if (c == 200) info = JSONObject(t) else msg = if (c == 404) "⚠ مفيش فيديو شغّال على التلفزيون" else "⚠ فشل (كود $c)"
                }
            } catch (e: Throwable) { LogStore.err("PlayQueueUi:handoff", e); msg = "⚠ حصلت مشكلة" }
            try {
                act.runOnUiThread {
                    if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                    val i0 = info
                    if (i0 == null) { Notice.show(act, msg ?: "⚠ حصلت مشكلة", 3500L); return@runOnUiThread }
                    val ref = i0.optString("ref")
                    if (ref.isEmpty()) { Notice.show(act, "⚠ الفيديو ده ملف جوه التلفزيون — بيتشغّل من التلفزيون بس", 5000L); return@runOnUiThread }
                    try {
                        PlayTarget.allowPhoneOnce()
                        val pos = i0.optLong("pos", 0L)
                        act.startActivity(Intent(act, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(Links.EX_URL, ref).putExtra(Links.EX_HOW, Links.VIDEO).putExtra(Links.EX_TITLE, i0.optString("title"))
                            .apply {
                                if (pos > 0L) putExtra("link_startms", pos)
                                val hr = i0.optString("hdr_ref"); val hu = i0.optString("hdr_ua"); val hc = i0.optString("hdr_cookie")
                                if (hr.isNotEmpty() || hu.isNotEmpty() || hc.isNotEmpty()) putExtra("link_ref", hr).putExtra("link_ua", hu).putExtra("link_cookie", hc)
                            })
                        Thread { try { RemoteClient.player("close") } catch (_: Throwable) {} }.also { it.isDaemon = true }.start()   // نقفل المشغّل على التلفزيون
                        PlayTarget.nowTitle = null; try { TvModeFab.refresh() } catch (_: Throwable) {}
                        done?.invoke()
                    } catch (e: Throwable) { LogStore.err("PlayQueueUi:handoff2", e) }
                }
            } catch (_: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    // ───────────────────────── ورقة الطابور ─────────────────────────

    fun show(act: Activity) {
        try { RemoteClient.init(act) } catch (_: Throwable) {}
        val dens = act.resources.displayMetrics.density
        fun dp(n: Int) = (n * dens).toInt()
        val isTv = DeviceUi.isTv(act)
        var tvTab = !isTv && (PlayTarget.enabled() || PlayTarget.nowTitle != null)
        var items: List<PlayQueue.Item> = emptyList()
        var tvCount = -1

        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(BG); setPadding(dp(14), dp(14), dp(14), dp(10)) }
        fun tv(t: String, sp: Float, c: Int = Color.WHITE, bold: Boolean = false) = TextView(act).apply {
            text = t; textSize = sp; setTextColor(c); gravity = Gravity.CENTER; if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        fun bg(fill: Int, stroke: Int, r: Int) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(r).toFloat(); setColor(fill); setStroke(dp(1), stroke) }

        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(tv("📋 الليستة", 19f, Color.WHITE, true).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(tv("✕", 20f, DIM).apply { background = bg(CARD, LINE, 20); setOnClickListener { d.dismiss() } }, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(head, LinearLayout.LayoutParams(-1, -2))

        val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val tabTv = tv("", 14f, Color.WHITE, true); val tabPh = tv("", 14f, Color.WHITE, true)
        if (!isTv) {
            tabs.addView(tabTv, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
            tabs.addView(tabPh, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
            root.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val note = tv("", 12f, DIM).apply { gravity = Gravity.START }
        root.addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val sv = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list, ViewGroup.LayoutParams(-1, -2)) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })

        val foot = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val clearB = tv("🗑 مسح الكل", 15f, Color.WHITE, true).apply { background = bg(0xFF3A1A20.toInt(), RED, 14) }
        foot.addView(clearB, LinearLayout.LayoutParams(0, dp(50), 1f))
        root.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        fun tvPost(body: JSONObject, then: (List<PlayQueue.Item>?) -> Unit) {
            Thread {
                var out: List<PlayQueue.Item>? = null
                try {
                    if (RemoteClient.ensureConnected(act, 7000L)) {
                        val (c, t) = RemoteClient.postJson("/queue", body)
                        if (c == 200) out = PlayQueue.fromJson(JSONObject(t).optJSONArray("items"))
                    }
                } catch (e: Throwable) { LogStore.err("PlayQueueUi:tvPost", e) }
                try { act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed && d.isShowing) then(out) } } catch (_: Throwable) {}
            }.also { it.isDaemon = true }.start()
        }

        lateinit var render: () -> Unit
        fun push(l: List<PlayQueue.Item>) {
            items = l
            if (tvTab) tvPost(JSONObject().put("a", "set").put("items", PlayQueue.toJson(l))) { r -> if (r != null) { items = r; tvCount = r.size; render() } else { note.text = "⚠ مش متصل بالتلفزيون — التعديل ماوصلش" } }
            else PlayQueue.setAll(act, l)
        }
        fun load() {
            if (tvTab) {
                note.text = "⏳ بجيب طابور التلفزيون…"
                tvPost(JSONObject().put("a", "get")) { r -> if (r != null) { items = r; tvCount = r.size; note.text = ""; render() } else { items = emptyList(); note.text = "⚠ مش متصل بالتلفزيون"; render() } }
            } else { items = PlayQueue.list(act); render() }
        }
        fun playNow(q: PlayQueue.Item) {
            if (tvTab) tvPost(JSONObject().put("a", "play").put("id", q.id)) { r -> if (r != null) { d.dismiss(); Notice.show(act, "📺 بشغّله على التلفزيون…", 2200L) } else note.text = "⚠ مش متصل بالتلفزيون" }
            else { PlayQueue.remove(act, q.id); if (PlayQueue.playHere(act, q)) d.dismiss() else { PlayQueue.add(act, q.ref, q.title); load() } }
        }

        render = {
            val phoneN = PlayQueue.size(act)
            tabTv.text = "📺 التلفزيون" + (if (tvCount >= 0) " ($tvCount)" else "")
            tabPh.text = "📱 الموبايل ($phoneN)"
            tabTv.background = bg(if (tvTab) ACC else CARD, if (tvTab) ACC else LINE, 12)
            tabPh.background = bg(if (!tvTab) ACC else CARD, if (!tvTab) ACC else LINE, 12)
            if (isTv) { tabPh.text = "" }
            list.removeAllViews()
            clearB.alpha = if (items.isEmpty()) 0.4f else 1f
            if (items.isEmpty()) {
                list.addView(tv("الليستة فاضية.\nدوس ➕ في الريموت، أو «➕ إضافة للليستة» على أي فيديو في يوتيوب أو المتصفح أو المكتبة.", 14f, DIM).apply { setPadding(dp(8), dp(40), dp(8), dp(40)) })
            } else {
                val gap = dp(6); val rowH = dp(62)
                for ((i, q) in items.withIndex()) {
                    val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; background = bg(CARD, LINE, 14); setPadding(dp(4), 0, dp(4), 0) }
                    val handle = tv("☰", 22f, DIM)
                    val ttl = tv((i + 1).toString() + ". " + q.title.ifBlank { short(q.ref) }, 14f, Color.WHITE).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), 0) }
                    val play = tv("▶", 20f, ACC).apply { setOnClickListener { playNow(q) } }
                    val del = tv("✕", 19f, RED).apply { setOnClickListener { push(items.filter { it.id != q.id }); render() } }
                    row.addView(handle, LinearLayout.LayoutParams(dp(44), -1)); row.addView(ttl, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(play, LinearLayout.LayoutParams(dp(44), -1)); row.addView(del, LinearLayout.LayoutParams(dp(44), -1))
                    list.addView(row, LinearLayout.LayoutParams(-1, rowH).apply { topMargin = gap })
                    var startY = 0f; var target = i
                    handle.setOnTouchListener { _, ev ->
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { startY = ev.rawY; target = i; row.alpha = 0.92f; row.elevation = dp(8).toFloat(); sv.requestDisallowInterceptTouchEvent(true) }
                            MotionEvent.ACTION_MOVE -> {
                                val dy = ev.rawY - startY
                                row.translationY = dy
                                target = (i + Math.round(dy / (rowH + gap))).coerceIn(0, items.size - 1)
                                for (k in 0 until list.childCount) {
                                    if (k == i) continue
                                    list.getChildAt(k).translationY = when { i < k && k <= target -> -(rowH + gap).toFloat(); target <= k && k < i -> (rowH + gap).toFloat(); else -> 0f }
                                }
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                sv.requestDisallowInterceptTouchEvent(false)
                                if (ev.actionMasked == MotionEvent.ACTION_UP && target != i) { val l = items.toMutableList(); val m = l.removeAt(i); l.add(target, m); push(l) }
                                render()
                            }
                        }
                        true
                    }
                }
            }
        }
        tabTv.setOnClickListener { if (!tvTab) { tvTab = true; load() } }
        tabPh.setOnClickListener { if (tvTab) { tvTab = false; load() } }
        clearB.setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            try {
                GAlert(act).setTitle("🗑 مسح الليستة كلها؟").setMessage("هيتمسح " + items.size + " فيديو من ليستة " + (if (tvTab) "التلفزيون" else "الموبايل") + ".")
                    .setPositiveButton("امسح الكل") { _, _ -> push(emptyList()); render() }
                    .setNegativeButton("إلغاء", null).show()
            } catch (_: Throwable) { push(emptyList()); render() }
        }
        d.setContentView(root)
        d.setOnDismissListener { }
        d.show()
        load()
        if (!isTv) tvPost(JSONObject().put("a", "get")) { r -> if (r != null) { tvCount = r.size; if (tvTab) { items = r }; render() } }   // عدّاد التلفزيون على التاب
    }
}
