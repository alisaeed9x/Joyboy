package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.io.File

/** (v760) حلقة بسيطة: خلفية + أقواس ملوّنة (كل قوس = نسبة من الدايرة) */
private class NetRing(ctx: Context) : View(ctx) {
    var fracs: FloatArray = FloatArray(0)
    var colors: IntArray = IntArray(0)
    var track: Int = 0
    var stroke: Float = 10f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h)
        val l = (w - s) / 2f + stroke / 2f; val t = (h - s) / 2f + stroke / 2f
        val r = RectF(l, t, l + s - stroke, t + s - stroke)
        p.strokeWidth = stroke
        p.color = track; c.drawArc(r, 0f, 360f, false, p)
        var a = -90f
        for (i in fracs.indices) {
            val sw = fracs[i] * 360f
            if (sw < 0.6f) continue
            p.color = colors[i]
            c.drawArc(r, a, minOf(sw, 359.4f), false, p)
            a += sw
        }
    }
}

/**
 * (v760) صفحة استهلاك الداتا — مبسّطة:
 *  • دايرة كبيرة: إجمالي المدى (النهارده / 7 أيام / الكل) + واي فاي وداتا الموبايل تحتها.
 *  • تحتها 3 دواير: فيديوهات · ملفات · صفحات (الأقسام متنافية: كل ميجا في قسم واحد بس).
 *  • تحت كل دايرة: أكبر عنصر فيها (فيديو/ملف/صفحة) — دوسة عليه بتفتحه، و«عرض الكل» بيفتح أكبر 20.
 */
class NetStatsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private var days = 1
    private val dialogs = ArrayList<Dialog>()
    private val cVideo get() = th.primary
    private val cFile = 0xFF22C55E.toInt()
    private val cPage = 0xFFF59E0B.toInt()
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }

    private fun f(b: Long) = NetUse.fmt(b)
    private fun rangeName() = when (days) { 1 -> "النهارده"; 7 -> "آخر 7 أيام"; else -> "الكل (45 يوم)" }
    private fun vu(b: Long): Pair<String, String> { val s = f(b); val i = s.indexOf(' '); return if (i > 0) Pair(s.substring(0, i), s.substring(i + 1)) else Pair(s, "") }

    private fun chip(t: String, on: Boolean, go: () -> Unit): View = ui.text(t, 12f, if (on) th.bg else th.text, on).apply {
        background = ui.box(if (on) th.primary else th.card, th.border, 10)
        setPadding(ui.dp(12), ui.dp(7), ui.dp(12), ui.dp(7))
        setOnClickListener { go() }
    }

    private fun chipRow(vararg chips: View): View {
        val inner = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        for (c in chips) inner.addView(c, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6) })
        return HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(inner) }
    }

    private fun add(v: View, top: Int = 6) { col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(top) }) }

    private fun title(kind: String): String = when (kind) {
        "day" -> "📅 كل يوم"; "vid" -> "🎞 كل فيديو"; "file" -> "📄 كل ملف"; "page" -> "🖥 كل صفحة"; else -> "⏱ كل جلسة"
    }

    /** دايرة: arcs = نسب الأقواس، وفي النص رقم + وحدة */
    private fun ring(fr: FloatArray, cl: IntArray, sizeDp: Int, strokeDp: Int, big: String, bigSp: Float, small: String): View {
        val rv = NetRing(act).apply { fracs = fr; colors = cl; track = th.border; stroke = ui.dp(strokeDp).toFloat() }
        val center = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(ui.text(big, bigSp, th.text, true).apply { gravity = Gravity.CENTER; maxLines = 1 })
            if (small.isNotEmpty()) addView(ui.text(small, 11f, th.muted).apply { gravity = Gravity.CENTER; maxLines = 1 })
        }
        return FrameLayout(act).apply {
            addView(rv, FrameLayout.LayoutParams(-1, -1))
            addView(center, FrameLayout.LayoutParams(-1, -1))
            layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        }
    }

    private fun items(kind: String): List<NetLedger.Row> =
        NetLedger.rows(kind, days).filter { it.total() > 0L }.sortedByDescending { it.total() }

    private fun render() {
        col.removeAllViews()
        val tot = NetLedger.totalOf(days)
        val cats = NetLedger.catRows(days)
        val sum = cats.fold(0L) { a, r -> a + r.total() }
        val denom = if (sum > 0L) sum.toFloat() else 1f

        add(chipRow(chip("النهارده", days == 1) { days = 1; render() }, chip("آخر 7 أيام", days == 7) { days = 7; render() }, chip("الكل", days == 45) { days = 45; render() }, chip("📡 لوج النت", false) { try { NetLog.show(act, ui, th) } catch (_: Throwable) {} }))   // (v815) لوج النت جوه صفحة الداتا

        // الدايرة الكبيرة: الإجمالي مقسّم بألوان الأقسام التلاتة
        val (bv, bu) = vu(tot.total())
        val big = ring(floatArrayOf(cats[0].total() / denom, cats[1].total() / denom, cats[2].total() / denom), intArrayOf(cVideo, cFile, cPage),
            190, 16, bv, 30f, bu + "  ·  " + rangeName())
        val bigWrap = LinearLayout(act).apply { gravity = Gravity.CENTER; addView(big) }
        add(bigWrap, 10)

        // واي فاي / داتا الموبايل (الداتا بالأحمر لو اتستهلك منها)
        val split = LinearLayout(act).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER }
        split.addView(ui.text("📶 واي فاي  " + f(tot.wifi()), 13f, th.text, true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(18) })
        split.addView(ui.text("📱 داتا  " + f(tot.mobile()), 13f, if (tot.mobile() > 0L) th.danger else th.muted, true))
        add(split, 6)

        // الدواير التلاتة
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBaselineAligned(false) }
        val specs = listOf(Triple("vid", "🎞 فيديوهات", cVideo), Triple("file", "📄 ملفات", cFile), Triple("page", "🖥 صفحات", cPage))
        for ((i, sp) in specs.withIndex()) {
            row.addView(catColumn(sp.first, sp.second, sp.third, cats[i], denom), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) })
        }
        add(row, 16)

        add(ui.noHint(), 10)

        val wo = NetGate.enabled(act)
        add(ui.button(if (wo) "🔒 واي فاي بس: شغّال — دوس للسماح بداتا الموبايل" else "🔓 واي فاي بس: واقف — دوس لمنع داتا الموبايل") {
            NetGate.set(act, !wo); render()
        }, 12)
        add(ui.button("📋 نسخ التقرير الكامل") { copyReport() }, 0)
        add(ui.button("🗑 تصفير السجل") {
            GAlert(act).setMessage("تصفّر كل سجل الاستهلاك؟").setPositiveButton("تصفير") { _, _ -> NetLedger.clear(); render() }.setNegativeButton("لا", null).show()
        }, 0)
    }

    /** عمود قسم واحد: دايرة بالحجم + النسبة، تحتها أكبر عنصر (بيتفتح بالدوس) و«عرض الكل» */
    private fun catColumn(kind: String, name: String, color: Int, r: NetLedger.Row, denom: Float): View {
        val c = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val share = r.total() / denom
        val (v, u) = vu(r.total())
        val rg = ring(floatArrayOf(share), intArrayOf(color), 96, 9, v, 15f, u)
        rg.setOnClickListener { showList(kind, name, color) }
        c.addView(rg)
        val pct = if (r.total() > 0L) (share * 100f).toInt().coerceIn(0, 100).toString() + "%" else "—"
        c.addView(ui.text(name + "  " + pct, 12f, th.text, true).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(4)) })

        val top = items(kind).firstOrNull()
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7)); background = ui.box(th.surface, th.border, 10)
        }
        val lbl = when (kind) { "vid" -> "أكبر فيديو"; "file" -> "أكبر ملف"; else -> "أكبر صفحة" }
        card.addView(ui.text(lbl, 10f, th.muted))
        if (top == null) {
            card.addView(ui.text("—", 12f, th.muted))
        } else {
            card.addView(ui.text(cleanName(top.name), 11f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            card.addView(ui.text(f(top.total()), 13f, color, true).apply { setPadding(0, ui.dp(2), 0, 0) })
            if (top.ref.isNotEmpty()) card.addView(ui.text("افتح ›", 10f, th.primary))
            card.setOnClickListener { openItem(kind, top) }
        }
        c.addView(card, LinearLayout.LayoutParams(-1, -2))
        c.addView(ui.text("عرض الكل ›", 11f, th.primary).apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, ui.dp(2)); setOnClickListener { showList(kind, name, color) } })
        return c
    }

    private fun cleanName(n: String): String = n.removePrefix("جلسة ").trim()

    /** قايمة أكبر 20 عنصر في القسم — كل واحد بيتفتح بالدوس */
    private fun showList(kind: String, name: String, color: Int) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val list = items(kind)
        box.addView(ui.text(name + " — " + rangeName() + "  (" + f(list.fold(0L) { a, r -> a + r.total() }) + ")", 13f, th.primary, true))
        if (list.isEmpty()) box.addView(ui.text("مفيش استهلاك مسجّل في المدى ده لسه.", 12f, th.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        for (r in list.take(20)) {
            val rowV = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.surface, th.border, 10)
            }
            rowV.addView(ui.text(cleanName(r.name), 13f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            rowV.addView(ui.text(f(r.total()) + "   ·   📶 " + f(r.wifi()) + "   📱 " + f(r.mobile()) + (if (r.last > 0L) "   ·   🕒 " + NetLedger.dateTimeOf(r.last) else ""), 11f, if (r.mobile() > 0L) th.danger else color).apply { setPadding(0, ui.dp(2), 0, 0) })
            if (r.ref.isNotEmpty()) rowV.addView(ui.text("دوس للفتح ›", 10f, th.primary))
            rowV.setOnClickListener { openItem(kind, r) }
            box.addView(rowV, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        }
        val d = ui.sheet(act, name, listOf<View>(box))
        dialogs.add(d); d.show()
    }

    private fun closeAll() { for (d in dialogs) try { d.dismiss() } catch (_: Throwable) {}; dialogs.clear() }

    // ===================== فتح العنصر =====================
    private fun openItem(kind: String, r: NetLedger.Row) {
        if (r.ref.isBlank()) { Notice.show(act, "مفيش مرجع محفوظ للعنصر ده (استهلاك قديم قبل الإصدار ده)", 2800L); return }
        try {
            when (kind) {
                "vid" -> openVideo(r)
                "file" -> openFile(r)
                else -> { closeAll(); act.startActivity(Intent(act, BrowserActivity::class.java).putExtra("start", r.ref)) }
            }
        } catch (e: Throwable) { Notice.show(act, "ما قدرتش أفتحه", 2400L) }
    }

    private fun localUri(ref: String): Uri = if (ref.startsWith("/")) Uri.fromFile(File(ref)) else Uri.parse(ref)

    private fun playLocal(u: Uri) {
        closeAll()
        act.startActivity(Intent(act, PlayerActivity::class.java).pipTask(act).apply {
            data = u; addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("landscape", true); putExtra("fresh", false); putExtra("nosub", false); putExtra("ask", false)
        })
    }

    private fun openVideo(r: NetLedger.Row) {
        if (r.ref.startsWith("http", true)) { closeAll(); Links.open(act, r.ref, Links.VIDEO, r.name, ""); return }
        if (r.ref.startsWith("/") && !File(r.ref).exists()) { Notice.show(act, "الفيديو ده اتمسح أو اتنقل", 2400L); return }
        playLocal(localUri(r.ref))
    }

    private fun openFile(r: NetLedger.Row) {
        val isPath = r.ref.startsWith("/")
        if (isPath && !File(r.ref).exists()) { Notice.show(act, "الملف ده اتمسح أو اتنقل", 2400L); return }
        val ext = (if (isPath) File(r.ref).extension else r.name.substringAfterLast('.', "")).lowercase()
        val mime = r.mime.ifBlank { FmUtil.mime(File(if (isPath) r.ref else r.name)) }
        val u = localUri(r.ref)
        when {
            mime.startsWith("video/") || ext in FmUtil.VIDEO -> playLocal(u)
            mime.startsWith("audio/") || ext in FmUtil.AUDIO -> { closeAll(); Links.playAudioHere(act, u.toString(), cleanName(r.name)) }
            else -> {   // صورة / PDF / أي ملف: بيتفتح في البرنامج المناسب
                val vuri = if (isPath) FmUtil.uriFor(act, File(r.ref)) else u
                closeAll()
                act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(vuri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
    }

    private fun copyReport() {
        val sb = StringBuilder("📶 تقرير استهلاك الداتا — " + rangeName() + "\n")
        val tot = NetLedger.totalOf(days)
        sb.append("الإجمالي: واي فاي ").append(f(tot.wifi())).append(" · داتا ").append(f(tot.mobile())).append("\n")
        val cats = NetLedger.catRows(days)
        sb.append("🎞 فيديوهات ").append(f(cats[0].total())).append(" · 📄 ملفات ").append(f(cats[1].total())).append(" · 🖥 صفحات ").append(f(cats[2].total())).append("\n\n")
        for (k in listOf("day", "vid", "file", "page", "ses")) {
            val rs = if (k == "day") NetLedger.perDay(days) else NetLedger.rows(k, days)
            if (rs.isEmpty()) continue
            sb.append(title(k)).append('\n')
            for (r in rs.take(40)) {
                sb.append("• ").append(r.name).append(" | واي فاي ").append(f(r.wifi())).append(" | داتا ").append(f(r.mobile()))
                if (r.last > 0L) sb.append(" | ").append(NetLedger.dateTimeOf(r.first)).append(" → ").append(NetLedger.dateTimeOf(r.last))
                sb.append('\n')
            }
            sb.append('\n')
        }
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("net", sb.toString()))
            Notice.show(act, "اتنسخ التقرير", 2000L)
        } catch (_: Throwable) {}
    }

    fun show() {
        NetLedger.flushNow()
        render()
        val d = ui.sheet(act, "📶 استهلاك الداتا", listOf<View>(col), solid = true)   // (v924) معتمة: الإعدادات اللي وراها كانت بتبان وتلخبط
        dialogs.add(d); d.show()
    }
}
