package com.tttt.subtitler

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/** (v135) توبيب «المهام»: كل عمليات القص والصوت والـ GIF والضغط والترجمة الثابتة، بتقدّمها وناتجها */
class TasksUi(private val act: Activity, private val ui: Ui, private val th: Theme, private val onTool: (String) -> Unit = {}) {
    val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(th.bg) }
    private val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(16)) }
    // (v147) قسمين في الصفحة: ترجمة الخلفية فوق، وباقي المهام (تحميل · قص · صوت · GIF · ضغط · ترجمة ثابتة) تحتها
    private val bgHead = ui.text("🌙 ترجمة في الخلفية", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(2)) }
    private val bgList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val taskHead = ui.text("⚙ باقي المهام", 13f, th.muted, true).apply { setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(2)) }
    private val taskList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val empty = ui.text("مفيش مهام لسه.\nدوس على أي أداة فوق واختار الفيديو، أو افتح فيديو في المشغّل وادوس 🧰 أدوات. التحميلات (⬇) وتحميلات المتصفح بتظهر هنا كمان.", 14f, th.muted).apply { gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(60), ui.dp(24), 0) }

    /** (v324) لما الصفحة تتفتح من الزرار العايم (الرئيسية) بيتحط هنا إجراء الرجوع وبيظهر زرار ✕؛ لو null (نافذة المشغّل فيها زرار إغلاق لوحدها) الزرار مخفي */
    private val closeB = ui.button("✕") { onClose?.invoke() }.apply { visibility = View.GONE }
    var onClose: (() -> Unit)? = null
    /** (v472) دوسة على مهمة خالصة = افتح الفيديو/الصوت الناتج في مشغّل البرنامج (لو null بيتفتح بتطبيق خارجي) */
    var onOpenOut: ((TaskItem) -> Unit)? = null
        set(v) { field = v; closeB.visibility = if (v != null) View.VISIBLE else View.GONE }

    private val sumTv = ui.text("", 12f, th.primary, true).apply { setPadding(ui.dp(16), 0, ui.dp(12), ui.dp(2)); visibility = View.GONE }
    private class Row(val card: LinearLayout, val title: TextView, val status: TextView, val bar: ProgressBar, val btns: LinearLayout)
    private val rows = HashMap<Int, Row>()
    private class BgRow(val job: BgJob, val card: LinearLayout, val title: TextView, val status: TextView, val bar: ProgressBar, val btns: LinearLayout) { var sig = "" }
    private val bgRows = HashMap<String, BgRow>()

    init {
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(14), ui.dp(12), ui.dp(6)) }
        head.addView(ui.text("⏳ المهام", 18f, th.primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(ui.button("🗑 مسح المنتهي") { BgJobs.clearFinished(); TaskCenter.clearFinished() }, LinearLayout.LayoutParams(-2, -2))
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
        // (v137) الأدوات مباشرة من هنا: دوس الأداة → اختار الفيديو → نافذة الإعدادات → تبدأ
        fun toolRow(vararg p: Pair<String, String>): LinearLayout {
            val r = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2)) }
            for ((k, lab) in p) r.addView(ui.button(lab) { onTool(k) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(ui.dp(3), 0, ui.dp(3), 0) })
            return r
        }
        root.addView(toolRow("trim" to "✂ قص", "audio" to "🎧 صوت", "gif" to "🎞 GIF"), LinearLayout.LayoutParams(-1, -2))
        root.addView(toolRow("compress" to "📦 ضغط / دقة", "hardsub" to "🎬 ترجمة ثابتة"), LinearLayout.LayoutParams(-1, -2))
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        list.addView(bgHead); list.addView(bgList); list.addView(taskHead); list.addView(taskList)
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

    // ===== ترجمة الخلفية: نفس الأربع زراير =====
    private fun bgStatus(j: BgJob): String = when (j.state) {
        "queued" -> if (j.paused) "⏸ مؤجّل" else "⏳ مستني دوره"
        "running" -> if (j.paused) "⏸ متوقف مؤقتًا — ${j.pct}%" else "🌙 بيترجم ${j.pct}%" + BgJobs.fmtRemain(j.remainSec).let { if (it.isEmpty()) "" else " · باقي $it" }
        "done" -> "✅ خلصت" + (if (j.srt.isNotEmpty()) " · اتحفظ SRT جنب الفيديو" else "") + (if (j.err.isNotEmpty()) " — " + j.err else "")
        "failed" -> "⚠ وقفت: " + j.err
        else -> "⏹ اتوقفت (${j.pct}%) — دوس 🔁 تكمّل من اللي اتحفظ"
    }

    private fun bgRetry(j: BgJob) {
        when {
            j.state == "stopped" -> if (!BgJobs.resumeStopped(act, j)) Notice.show(act, "بيترجم بالفعل", 2300L)
            j.active -> Thread {
                // شغّالة أو مستنية: وقّفها (التقدم محفوظ) وبعدين رجّعها للطابور تكمّل من اللي اتحفظ
                BgJobs.stopAndWait(j.vid, 6000L)
                val n = BgJobs.find(j.vid)
                if (n != null && n.state == "stopped") BgJobs.resumeStopped(act, n)
            }.apply { isDaemon = true }.start()
            else -> if (!BgJobs.retry(act, j)) Notice.show(act, "بيترجم بالفعل", 2300L)
        }
    }

    private fun makeBgRow(j: BgJob): BgRow {
        val card = ui.card()
        val title = ui.text(j.title, 14f, th.text, true).apply { maxLines = 2 }
        val status = ui.text("", 12f, th.primary).apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progressTintList = android.content.res.ColorStateList.valueOf(th.primary) }
        val btns = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        card.addView(title); card.addView(status); card.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(8))); card.addView(btns)
        return BgRow(j, card, title, status, bar, btns)
    }

    private fun paintBg(j: BgJob, r: BgRow) {
        r.btns.removeAllViews()
        val four = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        act4(four, if (j.paused) "▶ استكمال" else "⏸ مؤقت", j.active) { if (j.paused) BgJobs.resume(act, j.vid) else BgJobs.pause(j.vid) }
        act4(four, "⏹ إيقاف", j.active) { BgJobs.stop(j.vid) }
        act4(four, "✕ إلغاء", true) { BgJobs.remove(j) }
        act4(four, "🔁 إعادة", true) { bgRetry(j) }
        r.btns.addView(four, LinearLayout.LayoutParams(-1, -2))
    }

    fun refresh() {
        // --- ترجمة الخلفية ---
        val jobs = BgJobs.jobs.toList()
        val vids = jobs.map { it.vid }.toSet()
        for (k in bgRows.keys.filter { it !in vids || bgRows[it]?.job !== jobs.lastOrNull { j -> j.vid == it } }) bgRows.remove(k)?.let { bgList.removeView(it.card) }
        jobs.forEachIndexed { idx, j ->
            val r: BgRow = bgRows[j.vid] ?: makeBgRow(j).also { nr -> bgRows[j.vid] = nr; bgList.addView(nr.card, minOf(idx, bgList.childCount)) }
            val sig = j.state + (if (j.paused) "1" else "0")
            if (r.sig != sig) { r.sig = sig; paintBg(j, r) }
            r.status.text = bgStatus(j); r.bar.progress = j.pct
        }
        bgHead.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
        bgList.visibility = bgHead.visibility

        // --- باقي المهام ---
        val items = TaskCenter.items.toList()
        val runN = items.count { it.state == 1 && !it.paused }; val spd = TaskCenter.speedSum()
        sumTv.text = "⚡ $runN شغّالة" + (if (spd > 0L) "  ·  " + NetUse.fmt(spd) + "/ث" else "") + "  ·  " + items.count { it.state == 0 } + " مستنية"
        sumTv.visibility = if (runN > 0 || items.any { it.state == 0 }) View.VISIBLE else View.GONE
        val ids = items.map { it.id }.toSet()
        val gone = rows.keys.filter { it !in ids }
        for (k in gone) { rows.remove(k)?.let { taskList.removeView(it.card) } }
        taskHead.visibility = if (items.isEmpty() || jobs.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty() && jobs.isEmpty()) { if (empty.parent == null) list.addView(empty) } else if (empty.parent != null) list.removeView(empty)
        items.forEachIndexed { idx, t ->
            val r: Row = rows[t.id] ?: makeRow(t).also { nr -> rows[t.id] = nr; taskList.addView(nr.card, minOf(idx, taskList.childCount)); nr.card.tag = -1 }
            // الأزرار بتتبني من جديد بس لما حالة المهمة تتغيّر (عشان الدوسة ما تضيعش وسط تحديث التقدم)
            val sig = t.state * 2 + (if (t.paused) 1 else 0)
            if (r.card.tag != sig) { r.card.tag = sig; paint(t, r) } else { r.status.text = statusOf(t); r.bar.progress = t.pct }
        }
    }
}
