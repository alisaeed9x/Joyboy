package com.tttt.subtitler

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * (v722) صفحة استهلاك الداتا: واي فاي لوحده وداتا الموبايل لوحدها —
 * إجمالي كل يوم، وكل فيديو، وكل ملف، وكل صفحة، وكل جلسة، مع وقت أول وآخر استهلاك.
 */
class NetStatsUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    private var tab = "day"
    private var days = 1
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }

    private fun f(b: Long) = NetUse.fmt(b)
    private fun rangeName() = when (days) { 1 -> "النهارده"; 7 -> "آخر 7 أيام"; else -> "الكل (45 يوم)" }

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

    private fun line(t: String, color: Int = th.text, size: Float = 12f) = ui.text(t, size, color).apply { setPadding(0, ui.dp(1), 0, ui.dp(1)) }

    private fun detail(tag: String, d: Long, u: Long) = "$tag  (↓${f(d)}  ↑${f(u)})"

    private fun rowView(name: String, r: NetLedger.Row, withTime: Boolean): View {
        val c = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.surface, th.border, 10)
        }
        c.addView(ui.text(name, 13f, th.text, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        c.addView(line("📶 واي فاي: " + detail(f(r.wifi()), r.dn[NetLedger.WIFI], r.up[NetLedger.WIFI]), th.text))
        c.addView(line("📱 داتا الموبايل: " + detail(f(r.mobile()), r.dn[NetLedger.MOBILE], r.up[NetLedger.MOBILE]), if (r.mobile() > 0L) th.danger else th.muted))
        if (r.other() > 0L) c.addView(line("⛔ أخرى: " + f(r.other()), th.muted))
        if (withTime && r.last > 0L) c.addView(line("🕒 من " + NetLedger.dateTimeOf(r.first) + " إلى " + NetLedger.dateTimeOf(r.last), th.muted, 11f))
        return c
    }

    private fun add(v: View) { col.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }) }

    private fun title(kind: String): String = when (kind) {
        "day" -> "📅 كل يوم"; "vid" -> "🎞 كل فيديو"; "file" -> "📄 كل ملف"; "page" -> "🖥 كل صفحة"; else -> "⏱ كل جلسة"
    }

    private fun render() {
        col.removeAllViews()
        // الشبكة الحالية + ملخص النهارده
        val t1 = NetLedger.totalOf(1)
        col.addView(line("الشبكة دلوقتي: " + NetLedger.transportLabel(), th.primary, 13f))
        val wo = NetGate.enabled(act)
        add(ui.button(if (wo) "🔒 واي فاي بس: شغّال (داتا الموبايل ممنوعة) — اضغط للسماح" else "🔓 واي فاي بس: واقف (البرنامج ممكن يستهلك داتا) — اضغط للمنع") {
            NetGate.set(act, !wo); render()
        })
        val sum = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8)); background = ui.box(th.surface, th.border, 10)
        }
        sum.addView(ui.text("إجمالي النهارده", 13f, th.primary, true))
        sum.addView(line("📶 واي فاي: " + detail(f(t1.wifi()), t1.dn[NetLedger.WIFI], t1.up[NetLedger.WIFI]), th.text, 13f))
        sum.addView(line("📱 داتا الموبايل: " + detail(f(t1.mobile()), t1.dn[NetLedger.MOBILE], t1.up[NetLedger.MOBILE]), if (t1.mobile() > 0L) th.danger else th.muted, 13f))
        if (t1.other() > 0L) sum.addView(line("⛔ أخرى: " + f(t1.other()), th.muted))
        add(sum)

        add(chipRow(chip("📅 الأيام", tab == "day") { tab = "day"; render() }, chip("🎞 الفيديوهات", tab == "vid") { tab = "vid"; render() },
            chip("📄 الملفات", tab == "file") { tab = "file"; render() }, chip("🖥 الصفحات", tab == "page") { tab = "page"; render() },
            chip("⏱ الجلسات", tab == "ses") { tab = "ses"; render() }))
        add(chipRow(chip("النهارده", days == 1) { days = 1; render() }, chip("آخر 7 أيام", days == 7) { days = 7; render() }, chip("الكل", days == 45) { days = 45; render() }))

        val tot = NetLedger.totalOf(days)
        add(ui.text(title(tab) + " — " + rangeName() + "  (واي فاي " + f(tot.wifi()) + " · داتا " + f(tot.mobile()) + ")", 13f, th.primary, true))

        val rows: List<NetLedger.Row> = if (tab == "day") NetLedger.perDay(days) else NetLedger.rows(tab, days)
        if (tab == "file") add(line("الملفات: حجم المحتوى بالظبط (تقريبي، من غير مصاريف البروتوكول) — الأرقام الدقيقة في الإجماليات والفيديوهات والصفحات.", th.muted, 11f))
        if (tab == "vid") {
            val rest = NetLedger.Row("", 0L, 0L)
            for (i in 0..2) { rest.dn[i] = tot.dn[i]; rest.up[i] = tot.up[i] }
            for (r in rows) for (i in 0..2) { rest.dn[i] = (rest.dn[i] - r.dn[i]).coerceAtLeast(0L); rest.up[i] = (rest.up[i] - r.up[i]).coerceAtLeast(0L) }
            if (rest.total() > 0L) add(rowView("— غير منسوب لفيديو (تصفح / واجهة / خلفية)", rest, false))
        }
        if (rows.isEmpty()) add(line("مفيش استهلاك مسجّل في المدى ده لسه.", th.muted))
        var shown = 0
        for (r in rows) {
            if (shown >= 60) { add(line("… وغيرها (أول 60 بس)", th.muted, 11f)); break }
            val nm = when (tab) { "ses" -> "جلسة " + r.name; else -> r.name }
            add(rowView(nm, r, true))
            shown++
        }

        add(ui.button("📋 نسخ التقرير") { copyReport() })
        add(ui.button("🗑 تصفير السجل") {
            android.app.AlertDialog.Builder(act).setMessage("تصفّر كل سجل الاستهلاك؟").setPositiveButton("تصفير") { _, _ -> NetLedger.clear(); render() }.setNegativeButton("لا", null).show()
        })
        add(line("العدّاد بيقيس كل ترافيك التطبيق (بما فيه تشغيل الفيديو الأونلاين والمتصفح) من عدّاد النظام، ويفرّق الواي فاي عن الداتا وقت الاستهلاك. الترافيك المحلي مع التلفزيون على نفس الواي فاي بيتحسب واي فاي.", th.muted, 11f))
    }

    private fun copyReport() {
        val sb = StringBuilder("📶 تقرير استهلاك الداتا — " + rangeName() + "\n")
        val tot = NetLedger.totalOf(days)
        sb.append("الإجمالي: واي فاي ").append(f(tot.wifi())).append(" · داتا ").append(f(tot.mobile())).append("\n\n")
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
        ui.sheet(act, "📶 استهلاك الداتا: واي فاي / موبايل", listOf<View>(col)).show()
    }
}
