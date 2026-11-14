package com.tttt.subtitler

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** (v990) شريط تقدم مدوّر بحركة ناعمة — بيتملّي من اليمين في الـRTL */
private class TrBar(ctx: Context) : View(ctx) {
    var track: Int = 0
    var fill: Int = 0
    private var frac = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(f: Float, animate: Boolean) {
        val t = f.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate || Math.abs(t - frac) < 0.002f) { frac = t; invalidate(); return }
        anim = ValueAnimator.ofFloat(frac, t).apply {
            duration = 420L; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { frac = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2f
        if (w <= 0f || h <= 0f) return
        val save = c.save()
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) c.scale(-1f, 1f, w / 2f, h / 2f)
        p.color = track; c.drawRoundRect(0f, 0f, w, h, r, r, p)
        if (frac > 0f) { p.color = fill; c.drawRoundRect(0f, 0f, Math.max(h, w * frac), h, r, r, p) }
        c.restoreToCount(save)
    }
}

/**
 * (v990) مكتبة الترجمات — شاشة من «المزيد»:
 *  • كل ترجمة (حتى لو جزئية) في كارت: الاسم · الحالة · نسبة التقدم (شريط + %) · عدد الأسطر · اللهجة/الأسلوب/الشدة · المحرك.
 *  • أزرار: استكمال · إعادة · حذف (بيشتغلوا في الخلفية من غير ما تدخل على الفيديو) · ⚙ إعدادات متقدمة · ⋯ (السطور · تصدير · نسخ · فتح).
 *  • بحث في الأسماء وفي نص الترجمة نفسه · فلاتر الحالة · ترتيب · تحديث لايف للشغّال.
 * شكل: نفس مكوّنات البرنامج (ui.sheet + MtGlass) — كروت زجاج، كبسولات، Motion.stagger.
 */
class TrLibUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private val main = Handler(Looper.getMainLooper())
    private val dens = act.resources.displayMetrics.density
    private val amber = 0xFFF59E0B.toInt()
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    private var filter = "all"      // all · active · partial · done · failed
    private var sortBy = "new"      // new · name · pct
    private var query = ""
    private var hits: Map<String, Int> = emptyMap()
    private var searchTok = 0
    private var all: List<TrLib.Item> = emptyList()
    private var lastSig = ""
    private var tick: Runnable? = null
    private var shown = false

    private class Refs(val pct: TextView, val bar: TrBar, val sub: TextView, val status: TextView)
    private val refs = HashMap<String, Refs>()

    private val summary = ui.text("", 12f, th.muted)
    private val chipHost = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
    private val listCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }

    // ===================== مكوّنات صغيرة =====================
    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = ui.dp(top) }

    private fun glassBg(r: Int): android.graphics.drawable.Drawable =
        if (MtGlass.on()) MtGlass.pill(th, MtGlass.fill(th), ui.dp(r).toFloat(), dens) else ui.box(th.surface, th.border, 12)

    private fun pill(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): TextView =
        ui.text(label, 12.5f, if (primary) onPrimary else if (danger) th.danger else th.text, true).apply {
            gravity = Gravity.CENTER; maxLines = 1
            setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
            background = if (primary) Glass.primaryFill(th, ui.dp(16).toFloat()) else glassBg(16)
            isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        }

    private fun iconPill(label: String, onClick: () -> Unit): TextView =
        ui.text(label, 15f, th.text, true).apply {
            gravity = Gravity.CENTER; minWidth = ui.dp(38); minimumHeight = ui.dp(34)
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
            background = glassBg(14)
            isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        }

    private fun tag(label: String, color: Int): TextView = ui.text(label, 11f, color, true).apply {
        setPadding(ui.dp(9), ui.dp(4), ui.dp(9), ui.dp(4)); maxLines = 1
        background = glassBg(12)
    }

    private fun chipV(label: String, on: Boolean, go: () -> Unit): View = ui.text(label, 12.5f, if (on) onPrimary else th.text, on).apply {
        setPadding(ui.dp(13), ui.dp(8), ui.dp(13), ui.dp(8)); maxLines = 1
        background = if (on) Glass.primaryFill(th, ui.dp(18).toFloat()) else glassBg(18)
        isClickable = true; Glass.pressable(this); setOnClickListener { go() }
    }

    private fun stColor(s: String): Int = when (s) {
        "running" -> th.primary; "done" -> th.success; "failed" -> th.danger; "partial" -> amber; else -> th.muted
    }

    private fun stLabel(x: TrLib.Item): String = when (x.state) {
        "running" -> "● بترجم دلوقتي"
        "queued" -> "⏳ في الانتظار" + (x.job?.let { BgJobs.position(it) }?.takeIf { it > 0 }?.let { " (#$it)" } ?: "")
        "paused" -> "⏸ متوقفة مؤقتًا"
        "failed" -> "⚠ فشلت"
        "done" -> "✓ مكتملة"
        else -> "◐ ناقصة"
    }

    private fun fmtAgo(ts: Long): String {
        if (ts <= 0L) return ""
        val d = (System.currentTimeMillis() - ts) / 86400000L
        return when { d <= 0L -> "النهارده"; d == 1L -> "امبارح"; d < 30L -> "من $d يوم"; else -> "من ${d / 30} شهر" }
    }

    private fun fmtT(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r) else String.format(Locale.US, "%d:%02d", m, r)
    }

    private fun subLine(x: TrLib.Item): String {
        val sb = StringBuilder()
        if (x.subs > 0) sb.append(x.subs).append(" سطر")
        val rem = x.job?.remainSec ?: -1L
        if (x.state == "running" && rem >= 0L) { if (sb.isNotEmpty()) sb.append(" · "); sb.append("باقي ").append(BgJobs.fmtRemain(rem)) }
        if (x.state == "failed" && !x.job?.err.isNullOrBlank()) { if (sb.isNotEmpty()) sb.append(" · "); sb.append(x.job?.err ?: "") }
        val ago = fmtAgo(x.ts)
        if (ago.isNotEmpty() && !x.active) { if (sb.isNotEmpty()) sb.append(" · "); sb.append(ago) }
        return sb.toString()
    }

    private fun sigOf(l: List<TrLib.Item>) = l.joinToString("|") { it.id + ":" + it.state }

    // ===================== الشاشة =====================
    fun show() {
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = lp(-1, -1) }
        content.addView(summary, lp(-1, -2))
        val search = ui.input("🔍 دوّر في الأسماء وفي نص الترجمة", "")
        search.addTextChangedListener(object : TextWatcher {
            private var pending: Runnable? = null
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { main.removeCallbacks(it) }
                val r = Runnable { query = s?.toString()?.trim() ?: ""; runContentSearch(query) }
                pending = r; main.postDelayed(r, 260L)
            }
        })
        content.addView(search)
        content.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_RTL; addView(chipHost) }, lp(-1, -2, 4))
        val sc = ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(listCol) }
        content.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(6) })
        shown = true
        val d = ui.sheet(act, "📚 مكتبة الترجمات", listOf<View>(content), fixedH = true, onClose = { stop() })
        dlg = d
        render(true)
        d.show()
        val t = object : Runnable { override fun run() { if (!shown) return; try { liveRefresh() } catch (_: Throwable) {}; main.postDelayed(this, 1000L) } }
        tick = t; main.postDelayed(t, 1000L)
    }

    private var dlg: Dialog? = null

    private fun stop() { shown = false; tick?.let { main.removeCallbacks(it) }; tick = null; searchTok++ }

    private fun runContentSearch(q: String) {
        val tok = ++searchTok
        if (q.length < 2) { hits = emptyMap(); render(true); return }
        render(false)   // الأسماء فورًا، والنص لما البحث يخلّص
        val ids = all.filter { it.subs > 0 }.map { it.id }
        Thread {
            val m = HashMap<String, Int>()
            for (id in ids) {
                if (tok != searchTok) return@Thread
                val n = TrLib.countMatches(act, id, q)
                if (n > 0) m[id] = n
            }
            act.runOnUiThread { if (tok == searchTok && shown) { hits = m; render(true) } }
        }.apply { isDaemon = true }.start()
    }

    private fun filtered(): List<TrLib.Item> {
        val q = query.lowercase()
        var l = all.filter { x ->
            when (filter) {
                "active" -> x.active
                "partial" -> x.state == "partial"
                "done" -> x.state == "done"
                "failed" -> x.state == "failed"
                else -> true
            }
        }
        if (q.length >= 1) l = l.filter { x -> x.title.lowercase().contains(q) || (q.length >= 2 && (hits[x.id] ?: 0) > 0) }
        return when (sortBy) {
            "name" -> l.sortedBy { it.title.lowercase() }
            "pct" -> l.sortedByDescending { it.pct }
            else -> l.sortedWith(compareByDescending<TrLib.Item> { it.active }.thenByDescending { it.ts })
        }
    }

    private fun sortLabel() = when (sortBy) { "name" -> "↕ الاسم"; "pct" -> "↕ النسبة"; else -> "↕ الأحدث" }

    private fun render(animate: Boolean) {
        all = TrLib.items(act)
        lastSig = sigOf(all)
        refs.clear()
        val a = all.count { it.active }; val p = all.count { it.state == "partial" }; val dn = all.count { it.state == "done" }
        summary.text = all.size.toString() + " ترجمة  ·  " + a + " شغّالة  ·  " + p + " ناقصة  ·  " + dn + " مكتملة"
        chipHost.removeAllViews()
        fun chip(label: String, key: String) = chipHost.addView(chipV(label, filter == key) { filter = key; render(true) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
        chip("الكل (" + all.size + ")", "all")
        chip("جاري (" + a + ")", "active")
        chip("ناقصة (" + p + ")", "partial")
        chip("مكتملة (" + dn + ")", "done")
        val f = all.count { it.state == "failed" }
        if (f > 0) chip("فشلت ($f)", "failed")
        chipHost.addView(chipV(sortLabel(), false) { sortBy = when (sortBy) { "new" -> "name"; "name" -> "pct"; else -> "new" }; render(true) }, LinearLayout.LayoutParams(-2, -2))
        listCol.removeAllViews()
        val l = filtered()
        if (l.isEmpty()) listCol.addView(empty(), lp(-1, -2, 24))
        for (x in l) listCol.addView(card(x), lp(-1, -2, 8))
        if (animate) Motion.stagger(listCol, 10, 40L)
    }

    private fun empty(): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(ui.dp(20), ui.dp(30), ui.dp(20), ui.dp(30))
        addView(ui.text("📚", 40f, th.muted).apply { gravity = Gravity.CENTER })
        val none = all.isEmpty()
        addView(ui.text(if (none) "مفيش ترجمات لسه" else "مفيش نتايج", 15f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        addView(ui.text(if (none) "أي فيديو تترجمه (حتى لو جزئي) هيظهر هنا بنسبة التقدم." else "جرّب كلمة تانية أو غيّر الفلتر.", 12f, th.muted).apply { gravity = Gravity.CENTER })
    }

    private fun liveRefresh() {
        val cur = TrLib.items(act)
        if (sigOf(cur) != lastSig) { render(false); return }
        all = cur
        for (x in cur) {
            if (!x.active) continue
            val r = refs[x.id] ?: continue
            r.pct.text = TrLib.fmtPct(x)
            r.bar.set(x.pct / 100f, true)
            r.sub.text = subLine(x)
        }
    }

    private fun later() { main.postDelayed({ if (shown) render(false) }, 350L) }

    // ===================== الكارت =====================
    private fun card(x: TrLib.Item): View {
        val col = stColor(x.state)
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            background = ui.box(th.card, th.border, 16)
            isClickable = true; Glass.pressable(this); setOnClickListener { showLines(x) }
        }
        // رأس: الاسم + ⚙ + ⋯
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(ui.text(x.title, 14.5f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconPill("⚙") { settings(x) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(8) })
        head.addView(iconPill("⋯") { menu(x) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
        c.addView(head, lp(-1, -2))
        // الحالة + النسبة
        val st = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val status = ui.text(stLabel(x), 12.5f, col, true)
        val pct = ui.text(TrLib.fmtPct(x), 15f, col, true)
        st.addView(status, LinearLayout.LayoutParams(0, -2, 1f)); st.addView(pct)
        c.addView(st, lp(-1, -2, 10))
        val bar = TrBar(act).apply { track = th.border; fill = col }
        c.addView(bar, lp(-1, ui.dp(6), 6))
        bar.set(x.pct / 100f, false)
        val sub = ui.text(subLine(x), 11f, th.muted).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        c.addView(sub, lp(-1, -2, 6))
        val nHit = hits[x.id] ?: 0
        if (nHit > 0 && query.length >= 2) c.addView(ui.text("🔎 " + nHit + " سطر فيه \"" + query + "\"", 11.5f, th.primary, true), lp(-1, -2, 4))
        refs[x.id] = Refs(pct, bar, sub, status)
        // اللهجة · الأسلوب · الشدة
        val m = x.meta
        val tags = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        if (m != null && m.engine.isNotBlank()) {
            tags.addView(tag("🗣 " + m.dialect, th.text), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
            tags.addView(tag("🎭 " + m.style, th.text), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
            tags.addView(tag("💪 " + m.strength, th.text), LinearLayout.LayoutParams(-2, -2))
        } else tags.addView(tag("الإعدادات غير مسجّلة (ترجمة قديمة)", th.muted), LinearLayout.LayoutParams(-2, -2))
        c.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tags) }, lp(-1, -2, 10))
        if (m != null && m.engine.isNotBlank())
            c.addView(ui.text("⚙ " + m.engine, 10.5f, th.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(-1, -2, 6))
        if (m != null && m.hasOverride)
            c.addView(ui.text("✎ إعدادات متقدمة هتتطبق على الاستكمال/الإعادة: " + overrideText(m), 10.5f, th.primary).apply { maxLines = 2 }, lp(-1, -2, 4))
        // الأزرار
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        fun add(v: View) = row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        when (x.state) {
            "running" -> { add(pill("⏸ إيقاف مؤقت") { BgJobs.pause(x.id); later() }); add(pill("■ إيقاف", danger = true) { BgJobs.stop(x.id); Notice.show(act, "اتوقفت — اللي اترجم محفوظ وتقدر تكمّل", 2400L); later() }) }
            "paused" -> { add(pill("▶ استئناف", primary = true) { BgJobs.resume(act, x.id); later() }); add(pill("■ إيقاف", danger = true) { BgJobs.stop(x.id); later() }) }
            "queued" -> {
                x.job?.let { j -> add(pill("⚡ ابدأ دلوقتي", primary = true) { BgJobs.startNow(act, j); later() }) }
                add(pill("■ إلغاء", danger = true) { BgJobs.stop(x.id); later() })
            }
            "done" -> { add(pill("📤 تصدير", primary = true) { share(x) }); add(pill("↻ إعادة") { redo(x) }); add(pill("🗑 حذف", danger = true) { del(x) }) }
            else -> {
                add(pill(if (x.state == "failed") "↻ تانية" else "▶ استكمال", primary = true) { resume(x) })
                add(pill("↻ إعادة") { redo(x) }); add(pill("🗑 حذف", danger = true) { del(x) })
            }
        }
        c.addView(row, lp(-1, -2, 12))
        return c
    }

    private fun overrideText(m: TrLib.Meta): String {
        val p = ArrayList<String>()
        if (m.ovDialect.isNotBlank()) p.add(m.ovDialect)
        if (m.ovStyle.isNotBlank()) p.add(m.ovStyle)
        if (m.ovStrength.isNotBlank()) p.add(m.ovStrength)
        if (m.ovModel.isNotBlank()) p.add(m.ovModel)
        return p.joinToString(" · ")
    }

    // ===================== الإجراءات =====================
    private fun resume(x: TrLib.Item) {
        val why = TrLib.bgBlock(x)
        if (why != null) { Notice.show(act, why, 3400L); return }
        val ok = TrLib.startBg(act, x, "مكتبة الترجمات — استكمال")
        Notice.show(act, if (ok) "دخلت طابور الترجمة في الخلفية — هتكمّل من " + x.pct + "%" else "الترجمة دي شغّالة أو في الطابور فعلًا", 2800L)
        later()
    }

    private fun redo(x: TrLib.Item) {
        val why = TrLib.bgBlock(x)
        if (why != null) { Notice.show(act, why, 3400L); return }
        GAlert(act).setTitle("↻ إعادة الترجمة")
            .setMessage("اللي اترجم لحد دلوقتي (" + x.subs + " سطر) هيتمسح وتبدأ من الأول في الخلفية — بالإعدادات المتقدمة لو مظبوطة، وإلا بالإعدادات العامة. ملف SRT اللي جنب الفيديو بيفضل لحد ما الترجمة الجديدة تخلص.")
            .setPositiveButton("إعادة") { _, _ ->
                Thread {
                    try { if (x.active) BgJobs.stopAndWait(x.id, 7000L) } catch (_: Throwable) {}
                    TrLib.wipe(act, x)
                    val ok = TrLib.startBg(act, x, "مكتبة الترجمات — إعادة")
                    act.runOnUiThread {
                        Notice.show(act, if (ok) "بدأت إعادة الترجمة في الخلفية" else "مقدرتش أبدأ الإعادة (الترجمة لسه شغّالة؟)", 2800L)
                        if (shown) render(false)
                    }
                }.apply { isDaemon = true }.start()
            }
            .setNegativeButton("لا", null).show()
    }

    private fun del(x: TrLib.Item) {
        val srt = TrLib.writtenSrt(act, x)
        val g = GAlert(act).setTitle("🗑 حذف الترجمة")
            .setMessage("«" + x.title + "»\nهتتمسح ترجمة البرنامج المحفوظة (" + x.subs + " سطر). الفيديو نفسه مش هيتأثر." + (if (srt.isNotEmpty()) "\nوفيه ملف SRT جنب الفيديو كتبه البرنامج — تحب تمسحه كمان؟" else ""))
            .setPositiveButton("حذف الترجمة") { _, _ -> doDelete(x, false) }
            .setNegativeButton("لا", null)
        if (srt.isNotEmpty()) g.setNeutralButton("حذف + ملف SRT") { _, _ -> doDelete(x, true) }
        g.show()
    }

    private fun doDelete(x: TrLib.Item, withSrt: Boolean) {
        Thread {
            try { if (x.active) BgJobs.stopAndWait(x.id, 7000L); BgJobs.jobs.filter { it.vid == x.id && !it.active }.forEach { BgJobs.remove(it) } } catch (_: Throwable) {}
            val srt = if (withSrt) TrLib.writtenSrt(act, x) else emptyList()
            TrLib.wipe(act, x)
            TrLib.forget(act, x.id)
            for (f in srt) try { f.delete() } catch (_: Throwable) {}
            act.runOnUiThread { Notice.show(act, "اتمسحت الترجمة", 2000L); if (shown) render(false) }
        }.apply { isDaemon = true }.start()
    }

    private fun share(x: TrLib.Item) {
        val f = TrLib.exportFile(act, x)
        if (f == null) { Notice.show(act, "مفيش ترجمة محفوظة للتصدير", 2400L); return }
        try {
            val u = FmUtil.uriFor(act, f)
            val send = Intent(Intent.ACTION_SEND).setType("application/x-subrip").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(Intent.createChooser(send, "تصدير الترجمة"))
        } catch (e: Throwable) { LogStore.err("TrLibUi:share", e); Notice.show(act, "ما قدرتش أفتح المشاركة", 2400L) }
    }

    private fun saveBeside(x: TrLib.Item) {
        if (x.uri.isBlank()) { Notice.show(act, "الفيديو ده مش ملف على الجهاز", 2600L); return }
        val sv = TrLib.loadSaved(act, x.id)
        if (sv == null || sv.subs.isEmpty()) { Notice.show(act, "مفيش ترجمة محفوظة", 2400L); return }
        val off = Cfg.str("sub_offset_ms:" + x.id, "0").toLongOrNull() ?: 0L
        val p = try { SrtWriter.save(act, x.uri, sv.subs, off) } catch (_: Throwable) { null }
        Notice.show(act, if (p != null) "اتحفظ: " + p else "ما قدرتش أكتب جنب الفيديو (صلاحية الملفات أو فيه ملف تاني بنفس الاسم)", 3600L)
    }

    private fun copyText(x: TrLib.Item) {
        val t = TrLib.srtText(act, x.id)
        if (t == null) { Notice.show(act, "مفيش ترجمة محفوظة", 2400L); return }
        try {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("srt", t))
            Notice.show(act, "اتنسخت الترجمة", 2000L)
        } catch (_: Throwable) {}
    }

    private fun openVideo(x: TrLib.Item) {
        try {
            when {
                x.uri.isNotBlank() -> {
                    dlg?.dismiss()
                    act.startActivity(Intent(act, PlayerActivity::class.java).pipTask(act).apply {
                        data = Uri.parse(x.uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra("landscape", true); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
                    })
                }
                x.url.startsWith("http", true) -> { dlg?.dismiss(); Links.open(act, x.url, Links.VIDEO, x.title, "") }
                else -> Notice.show(act, "مفيش مصدر محفوظ للفيديو", 2400L)
            }
        } catch (e: Throwable) { LogStore.err("TrLibUi:open", e); Notice.show(act, "ما قدرتش أفتح الفيديو", 2400L) }
    }

    private fun menu(x: TrLib.Item) {
        val labels = arrayOf("📝 السطور (بحث وتعديل)", "📤 تصدير SRT (مشاركة)", "💾 حفظ SRT جنب الفيديو", "📋 نسخ النص", "▶ فتح الفيديو")
        GAlert(act).setTitle(x.title.take(60)).setItems(labels) { _, w ->
            when (w) { 0 -> showLines(x); 1 -> share(x); 2 -> saveBeside(x); 3 -> copyText(x); else -> openVideo(x) }
        }.setNegativeButton("إغلاق", null).show()
    }

    // ===================== إعدادات متقدمة =====================
    private fun settings(x: TrLib.Item) {
        val m = TrLib.meta(act, x.id)
        val glob = "زي العامة"
        var d = m?.ovDialect ?: ""; var st = m?.ovStyle ?: ""; var sg = m?.ovStrength ?: ""; var md = m?.ovModel ?: ""
        fun lbl(v: String) = v.ifBlank { glob }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(ui.text(x.title, 13.5f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val rec = if (m == null || m.engine.isBlank()) "غير مسجّل (ترجمة قديمة)" else (m.dialect + " · " + m.style + " · " + m.strength + "\n" + m.engine)
        col.addView(ui.text("المسجّل وقت الترجمة: " + rec, 11f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(2)) })
        fun group(title: String, v: View) {
            col.addView(ui.text(title, 12.5f, th.primary, true).apply { setPadding(0, ui.dp(12), 0, ui.dp(2)) })
            col.addView(v)
        }
        group("🗣 اللهجة", ui.chips(listOf(glob) + TrLib.DIALECTS, { lbl(d) }) { n -> d = if (n == glob) "" else n })
        group("🎭 الأسلوب", ui.chips(listOf(glob) + TrLib.STYLES, { lbl(st) }) { n -> st = if (n == glob) "" else n })
        group("💪 الشدة", ui.chips(listOf(glob) + TrLib.STRENGTHS, { lbl(sg) }) { n -> sg = if (n == glob) "" else n })
        group("⚙ المحرك (موديل جيميناي)", ui.chips(listOf(glob) + Models.quota.keys.toList().distinct(), { lbl(md) }) { n -> md = if (n == glob) "" else n })
        col.addView(ui.text("بتتطبق على الاستكمال والإعادة (في الخلفية) بس. اللي اترجم قبل كده مابيتغيّرش غير لو دوست «إعادة». التفريغ المحلي (ويسبر) بيتظبط من إعدادات البرنامج مش من هنا.", 11f, th.muted).apply { setPadding(0, ui.dp(12), 0, ui.dp(4)) })
        val sheet = ui.sheet(act, "⚙ إعدادات الترجمة", listOf<View>(col))
        col.addView(ui.button("💾 حفظ", true) { TrLib.setOverrides(act, x.id, d, st, sg, md); sheet.dismiss(); if (shown) render(false) })
        col.addView(ui.button("💾 حفظ + ▶ استكمال") {
            TrLib.setOverrides(act, x.id, d, st, sg, md); sheet.dismiss()
            if (x.state == "done") redo(x) else resume(x)
        })
        col.addView(ui.button("↺ رجوع للإعدادات العامة") { TrLib.setOverrides(act, x.id, "", "", "", ""); sheet.dismiss(); if (shown) render(false) })
        sheet.show()
    }

    // ===================== السطور: بحث وتعديل =====================
    private fun showLines(x: TrLib.Item) {
        val sv = TrLib.loadSaved(act, x.id)
        if (sv == null || sv.subs.isEmpty()) { Notice.show(act, "مفيش أسطر مترجمة لسه في الفيديو ده", 2600L); return }
        val local = sv.subs.toMutableList()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; layoutParams = lp(-1, -1) }
        val q = ui.input("🔍 دوّر في الأصل أو الترجمة", "")
        val cnt = ui.text("", 11f, th.muted).apply { setPadding(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(4)) }
        val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        col.addView(q); col.addView(cnt)
        col.addView(ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        lateinit var fill: () -> Unit
        fun lineRow(i: Int, s: Sub): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(9), ui.dp(10), ui.dp(9)); background = ui.box(th.card, th.border, 12)
            addView(ui.text(fmtT(s.start), 11f, th.muted, true).apply { minWidth = ui.dp(46) })
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            t.addView(ui.text(s.translated.ifBlank { "—" }, 13.5f, th.text, true))
            if (s.original.isNotBlank()) t.addView(ui.text(s.original, 11f, th.muted).apply { setPadding(0, ui.dp(2), 0, 0) })
            addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            isClickable = true; Glass.pressable(this)
            setOnClickListener {
                if (BgJobs.isActive(x.id)) { Notice.show(act, "الترجمة شغّالة دلوقتي — أوقفها الأول عشان تعدّل", 3000L); return@setOnClickListener }
                val et = ui.input("نص الترجمة", s.translated, 3)
                GAlert(act).setTitle("✎ تعديل السطر — " + fmtT(s.start)).setMessage(s.original.take(160)).setView(et)
                    .setPositiveButton("حفظ") { _, _ ->
                        val nt = et.text.toString().trim()
                        if (TrLib.editLine(act, x.id, i, nt)) { local[i] = local[i].copy(translated = nt); fill(); Notice.show(act, "اتعدّل", 1600L) }
                        else Notice.show(act, "ما قدرتش أحفظ التعديل", 2400L)
                    }.setNegativeButton("إلغاء", null).show()
            }
        }
        fill = {
            rows.removeAllViews()
            val qq = q.text.toString().trim()
            var n = 0; var total = 0
            for ((i, s) in local.withIndex()) {
                if (qq.isNotEmpty() && !s.translated.contains(qq, true) && !s.original.contains(qq, true)) continue
                total++
                if (n >= 300) continue
                n++
                rows.addView(lineRow(i, s), lp(-1, -2, 6))
            }
            cnt.text = if (total > n) "عرض $n من $total سطر — اكتب كلمة لتضييق البحث" else "$total سطر"
        }
        q.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill() }
        })
        fill()
        ui.sheet(act, "📝 " + x.title.take(40), listOf<View>(col), fixedH = true).show()
    }
}
