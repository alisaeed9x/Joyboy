package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * (v345) شريط الحالة الموحّد: كبسولة صغيرة فوق بتظهر في كل الصفحات (الرئيسية · المتصفح · الملفات) لما مهمة تبدأ أو تخلص —
 * ترجمة الخلفية + قص/تحميل/ضغط… — بتعرض أول مهمة شغّالة بنسبتها و«+N» للباقي، وبتختفي لوحدها بعد ثواني من غير ما تغطي حاجة.
 * دوسة عليها = صفحة المهام. parent لازم يكون FrameLayout بيغطي الشاشة. بتتنده detach() من onDestroy.
 */
class StatusStrip(private val act: Activity, private val parent: FrameLayout, private val ui: Ui, private val th: Theme, private val onOpen: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val tv: TextView = ui.text("", 12.5f, Color.WHITE, true).apply {
        setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; maxWidth = ui.dp(300)
        setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)); background = ui.box(0xEE1B1E27.toInt(), 0x558FB4FF.toInt(), 18)
        elevation = ui.dp(9).toFloat(); visibility = View.GONE; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setOnClickListener { hide(); onOpen() }
    }
    /** الصفحة اللي فيها الشريط ممكن تخبّيه (صفحة المهام نفسها) */
    var hidden = false
        set(v) { field = v; if (v) hide() }
    private var lastSig = ""
    private var lastDone = -1
    private var shown = false
    private val hideRun = Runnable { hide() }
    private val listener: () -> Unit = { if (!act.isDestroyed && !act.isFinishing) main.post { refresh() } }
    private val tick = object : Runnable { override fun run() { if (!act.isDestroyed && !act.isFinishing) { refresh(); main.postDelayed(this, 1500L) } } }

    init {
        parent.addView(tv, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(34) })
        TaskCenter.extra.add(listener)
        main.postDelayed(tick, 1500L)
    }

    fun detach() { TaskCenter.extra.remove(listener); main.removeCallbacks(tick); main.removeCallbacks(hideRun) }

    private fun hide() { main.removeCallbacks(hideRun); if (shown) { shown = false; tv.animate().cancel(); tv.animate().alpha(0f).setDuration(160L).withEndAction { if (!shown) tv.visibility = View.GONE }.start() } }
    private fun show(text: String, holdMs: Long) {
        if (hidden) return
        tv.text = text
        if (!shown) { shown = true; tv.animate().cancel(); tv.alpha = 0f; tv.visibility = View.VISIBLE; tv.animate().alpha(1f).setDuration(180L).start() }
        main.removeCallbacks(hideRun); main.postDelayed(hideRun, holdMs)
    }

    fun refresh() {
        if (act.isDestroyed || act.isFinishing) return
        val run = TaskCenter.items.filter { it.state == 0 || it.state == 1 }
        val bg = BgJobs.jobs.filter { it.active }
        val n = run.size + bg.size
        val done = TaskCenter.items.count { it.state == 2 }
        val firstRun = run.firstOrNull { it.state == 1 } ?: run.firstOrNull()
        val finished = lastDone >= 0 && done > lastDone
        lastDone = done
        if (finished) {
            val d = TaskCenter.items.firstOrNull { it.state == 2 }
            show("✅ خلصت: " + (d?.title ?: "").take(26), 3200L); lastSig = ""; return
        }
        if (n == 0) { lastSig = ""; if (shown) hide(); return }
        val head = if (firstRun != null) "⚙ " + firstRun.title.take(24) + (if (firstRun.state == 1) " · " + firstRun.pct + "%" else "")
                   else "🌐 ترجمة: " + bg[0].title.take(22) + " · " + bg[0].pct + "%"
        val text = head + (if (n > 1) "  +" + (n - 1) else "")
        val sig = n.toString() + "|" + (firstRun?.id ?: bg.firstOrNull()?.vid ?: "")
        if (sig != lastSig) { lastSig = sig; show(text, 3500L) }   // مهمة جديدة بدأت أو العدد اتغيّر: يظهر
        else if (shown) tv.text = text                             // وهو ظاهر: النسبة بتتحدّث بس
    }
}
