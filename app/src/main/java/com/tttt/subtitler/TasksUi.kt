package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** (v1038) «مركز التحميل»: كل التحميلات (فيديو · صوت · متصفح · نقل من/إلى التلفزيون) بتقدّمها وإيقاف/استكمال/إلغاء/إعادة. أدوات القص والصوت والـGIF والضغط والترجمة الثابتة وقايمة ترجمة الخلفية اتشالت من الصفحة دي (الترجمة في 📚 مكتبة الترجمات) */
class TasksUi(private val act: Activity, private val ui: Ui, private val th: Theme) {
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }
    private val taskList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val empty = ui.text("مفيش تحميلات لسه.\nأي تحميل (⬇) من المشغّل أو المتصفح أو التلفزيون بيظهر هنا، وتقدر توقّفه وتكمّله وتفتح الملف بعد ما يخلص.", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(60), ui.dp(24), 0) }

    /** (v324) لما الصفحة تتفتح من الزرار العايم (الرئيسية) بيتحط هنا إجراء الرجوع وبيظهر زرار ✕؛ لو null (نافذة المشغّل فيها زرار إغلاق لوحدها) الزرار مخفي */
    private val closeB = ui.button("✕") { onClose?.invoke() }.apply { visibility = View.GONE }
    var onClose: (() -> Unit)? = null
    /** (v472) دوسة على مهمة خالصة = افتح الفيديو/الصوت الناتج في مشغّل البرنامج (لو null بيتفتح بتطبيق خارجي) */
    var onOpenOut: ((TaskItem) -> Unit)? = null
        set(v) { field = v; closeB.visibility = if (v != null) View.VISIBLE else View.GONE }
    /** (v1361) زرار «⚙ إعدادات الترجمة» في مركز التحميل — بيفتح إعدادات الترجمة اللي بتأثر في برومبت باتش الصوت */
    var onTrSettings: (() -> Unit)? = null

    // (v1198) المهام + مركز التحميل = صفحة واحدة: طابور ترجمة الخلفية فوق التحميلات
    private val bgq = QueueUi(act, ui, th)
    private val bgSec = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE }
    private val sumTv = ui.text("", 12f, th.primary, true).apply { setPadding(ui.dp(16), 0, ui.dp(12), ui.dp(2)); visibility = View.GONE }
    private class Row(val card: LinearLayout, val title: TextView, val status: TextView, val bar: ProgressBar, val btns: LinearLayout)
    private val rows = HashMap<Int, Row>()

    init {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(6)) }
        head.addView(ui.text("📋 المهام", 18f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.button("🗑 مسح المنتهي") { TaskCenter.clearFinished() }, LinearLayout.LayoutParams(-2, -2))
        head.addView(closeB, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
        val sv = ScrollView(act).apply { addView(list); overScrollMode = View.OVER_SCROLL_NEVER }
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        // (v819) إجمالي السرعة + أزرار «الكل» + عدد مسارات التحميل (تحميل الملف الواحد بأكتر من اتصال زي IDM)
        root.addView(sumTv, LinearLayout.LayoutParams(-1, -2))
        val bulk = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2)) }
        act4(bulk, "⏸ وقّف الكل", true) { TaskCenter.pauseAll() }
        act4(bulk, "▶ كمّل الكل", true) { TaskCenter.resumeAll() }
        act4(bulk, "🔁 أعد الفاشل", true) { TaskCenter.retryFailed() }
        root.addView(bulk, LinearLayout.LayoutParams(-1, -2))
        val connRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(2), ui.dp(12), ui.dp(2)) }
        connRow.addView(ui.text("🧵 مسارات التحميل", 12f, th.muted, true), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        val cv = listOf("1", "2", "4", "6", "8")
        connRow.addView(ui.chips(cv, { Cfg.int("dl_conns", 4).coerceIn(1, 8).toString().let { if (it in cv) it else "4" } }) { n -> Cfg.put("dl_conns", n) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(connRow, LinearLayout.LayoutParams(-1, -2))
        // (v1361 — بطلب علي) باتش الصوت للترجمة برّه التطبيق: فصل صوت أي فيديو + ملف برومبت، يتحفظوا على الموبايل ويتشاركوا لأي AI
        val apRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(2)) }
        apRow.addView(ui.button("🎧 ترجمة عن طريق تحميل باتش ملف الصوت", true) { GhAudio.pickAndStart(act) }, LinearLayout.LayoutParams(0, -2, 1f))
        apRow.addView(ui.button("⚙ إعدادات الترجمة") { try { onTrSettings?.invoke() } catch (e: Throwable) { LogStore.err("TasksUi:trset", e) } }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(6) })
        root.addView(apRow, LinearLayout.LayoutParams(-1, -2))
        // (v1366 — بطلب علي) زرار التفريغ التجريبي بـ Groq Whisper: بيظهر بس لما السويتش يتفتح من إعدادات باتش الصوت
        if (Cfg.bool("groq_exp", false)) {
            val gqRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(4)) }
            gqRow.addView(ui.button("🧪 تفريغ Groq Whisper (تجريبي)", true) { GhAudio.pickAndStartGroq(act) }, LinearLayout.LayoutParams(0, -2, 1f))
            root.addView(gqRow, root.indexOfChild(sv), LinearLayout.LayoutParams(-1, -2))
        }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        bgSec.addView(ui.text("🌙 ترجمة الخلفية", 13f, th.primary, true).apply { setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(2)) })
        bgSec.addView(bgq.content)
        list.addView(bgSec)
        list.addView(ui.text("⬇ التحميلات والنقل", 13f, th.primary, true).apply { setPadding(ui.dp(4), ui.dp(10), 0, ui.dp(2)) })
        list.addView(taskList)
        refresh()
    }

    private fun dlInfo(t: TaskItem): String {
        if (t.dlDone <= 0L) return ""
        val sp = if (t.dlSpeed > 0L && !t.paused) t.dlSpeed else 0L
        var s = "\n💾 " + NetUse.fmt(t.dlDone) + (if (t.dlTotal > 0L) " / " + NetUse.fmt(t.dlTotal) else "")
        if (sp > 0L) s += "  ·  ⚡ " + NetUse.fmt(sp) + "/ث"
        if (sp > 0L && t.dlTotal > t.dlDone) { val r = (t.dlTotal - t.dlDone) / sp; s += "  ·  ⏱ باقي " + (if (r >= 3600L) String.format("%d:%02d:%02d", r / 3600, r % 3600 / 60, r % 60) else String.format("%d:%02d", r / 60, r % 60)) }
        return s
    }
    private fun canPlayPart(t: TaskItem): Boolean = (t.state == 1 || t.state == 6) && t.partPath.isNotEmpty() && java.io.File(t.partPath).let { it.isFile && it.length() > 300_000L }

    private fun statusOf(t: TaskItem): String = when (t.state) {
        0 -> "⏳ في الانتظار"
        1 -> (if (t.paused) "⏸ واقف مؤقتًا… " else "⚙ شغّال… ") + t.pct + "%" + (if (t.msg.isNotBlank()) "  ·  " + t.msg else "") + dlInfo(t) + (if (canPlayPart(t)) "\n▶ دوس عشان تتفرج على اللي اتحمّل" else "")
        2 -> "✅ خلصت وتحفظت" + (if (t.outUri != null || t.outPath.isNotBlank()) "  ·  دوس عشان تفتحه" else "") + (if (t.outPath.isNotBlank()) "\n" + t.outPath else "") + (if (t.outSize > 0) "  ·  " + Sniff.fmtSize(t.outSize) else "")
        3 -> "❌ فشلت: " + t.msg + savedNote(t)
        5 -> "⏹ " + t.msg + savedNote(t)
        6 -> "⏸ " + t.msg
        else -> "🚫 " + t.msg.ifBlank { "اتلغت" }
    }
    /** (v819) اللي اتحمّل قبل الفشل/الإيقاف محفوظ ← ▶ استكمال بيكمّل منه */
    private fun savedNote(t: TaskItem): String = if (t.dlDone > 0L && t.partFiles.isNotEmpty()) "\n💾 محفوظ " + NetUse.fmt(t.dlDone) + (if (t.dlTotal > 0L) " / " + NetUse.fmt(t.dlTotal) else "") + " — دوس ▶ استكمال" else ""

    private fun open(t: TaskItem, share: Boolean) {
        val u = t.outUri ?: return
        try {
            val i = if (share) Intent.createChooser(Intent(Intent.ACTION_SEND).setType(t.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "مشاركة")
                    else Intent(Intent.ACTION_VIEW).setDataAndType(u, t.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            act.startActivity(i)
        } catch (_: Throwable) { Notice.show(act, "مفيش تطبيق يفتح الملف ده", 2400L) }
    }

    private fun makeRow(t: TaskItem): Row {
        val card = ui.card()
        val title = ui.text(t.title, 14f, th.text, true)
        val status = ui.text("", 12f, th.muted).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(th.primary)
        }
        val btns = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        card.addView(title); card.addView(status); card.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(8))); card.addView(btns)
        return Row(card, title, status, bar, btns)
    }

    /** (v147) زرار صغير من الأربعة — بيفضل ظاهر دايمًا، ولو مش ينفع في الحالة دي بيتعتّم */
    private fun act4(row: LinearLayout, label: String, on: Boolean, f: () -> Unit) {
        val b = ui.button(label) { if (on) f() }
        b.textSize = 12f; b.setPadding(ui.dp(2), ui.dp(6), ui.dp(2), ui.dp(6)); b.minHeight = ui.dp(40); b.minimumHeight = ui.dp(40)
        b.alpha = if (on) 1f else 0.35f
        row.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(2), 0, ui.dp(2), 0) })
    }

    private fun openOut(t: TaskItem) { val f = onOpenOut; if (f != null) f(t) else open(t, false) }

    private fun paint(t: TaskItem, r: Row) {
        r.status.text = statusOf(t)
        val openable = t.state == 2 && (t.outUri != null || t.outPath.isNotBlank())   // (v472) المهمة الخالصة كلها قابلة للدوس = فتح الفيديو
        val playPart = canPlayPart(t)
        r.card.setOnClickListener(if (openable || playPart) View.OnClickListener { openOut(t) } else null)
        r.card.isClickable = openable || playPart
        r.bar.visibility = if (t.state == 1 || t.state == 0) View.VISIBLE else View.GONE
        r.bar.progress = t.pct
        r.btns.removeAllViews()
        r.btns.orientation = LinearLayout.VERTICAL
        val live = t.state == 0 || t.state == 1 || t.state == 6
        val isPaused = t.state == 6 || (t.state == 1 && t.paused)
        val four = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        // (v819) فاشلة/موقوفة (⏹) ← ▶ استكمال من الملف الجزئي · ✕ على الشغّالة = إلغاء ومسح الجزئي وتفضل «اتلغت» عشان تعيدها · على المنتهية = شيل
        val canResume = (t.state == 3 || t.state == 5) && t.workKeep != null
        act4(four, if (isPaused || canResume) "▶ استكمال" else "⏸ مؤقت", live || canResume) { if (canResume) TaskCenter.pause(t, false) else TaskCenter.pause(t, !isPaused) }
        act4(four, "⏹ إيقاف", live) { TaskCenter.stop(t) }
        act4(four, if (live) "✕ إلغاء" else "🗑 شيل", t.state != 2) { TaskCenter.cancel(t) }
        act4(four, if (t.state == 3 || t.state == 5) "🔁 من الأول" else "🔁 إعادة", t.workKeep != null) { TaskCenter.retry(t) }
        r.btns.addView(four, LinearLayout.LayoutParams(-1, -2))
        if (playPart) {
            val one = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            act4(one, "▶ شغّل اللي اتحمّل لحد دلوقتي", true) { openOut(t) }
            r.btns.addView(one, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        }
        if (t.state == 2) {
            val two = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            if (t.outUri != null) { act4(two, "▶ فتح", true) { openOut(t) }; act4(two, "📤 مشاركة", true) { open(t, true) } }
            act4(two, "🗑 حذف", true) { TaskCenter.remove(t) }
            r.btns.addView(two, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        }
    }

    fun refresh() {
        try { val hasBg = BgJobs.jobs.isNotEmpty(); bgSec.visibility = if (hasBg) View.VISIBLE else View.GONE; if (hasBg) bgq.refresh() } catch (_: Throwable) {}
        // --- التحميلات ---
        val items = TaskCenter.items.toList()
        val runN = items.count { it.state == 1 && !it.paused }; val spd = TaskCenter.speedSum()
        sumTv.text = "⚡ $runN شغّالة" + (if (spd > 0L) "  ·  " + NetUse.fmt(spd) + "/ث" else "") + "  ·  " + items.count { it.state == 0 } + " مستنية"
        sumTv.visibility = if (runN > 0 || items.any { it.state == 0 }) View.VISIBLE else View.GONE
        val ids = items.map { it.id }.toSet()
        val gone = rows.keys.filter { it !in ids }
        for (k in gone) { rows.remove(k)?.let { taskList.removeView(it.card) } }
        if (items.isEmpty() && BgJobs.jobs.isEmpty()) { if (empty.parent == null) list.addView(empty) } else if (empty.parent != null) list.removeView(empty)
        items.forEachIndexed { idx, t ->
            val r: Row = rows[t.id] ?: makeRow(t).also { nr -> rows[t.id] = nr; taskList.addView(nr.card, minOf(idx, taskList.childCount)); nr.card.tag = -1 }
            // الأزرار بتتبني من جديد بس لما حالة المهمة تتغيّر (عشان الدوسة ما تضيعش وسط تحديث التقدم)
            val sig = t.state * 2 + (if (t.paused) 1 else 0)
            if (r.card.tag != sig) { r.card.tag = sig; paint(t, r) } else { r.status.text = statusOf(t); r.bar.progress = t.pct }
        }
    }
}
