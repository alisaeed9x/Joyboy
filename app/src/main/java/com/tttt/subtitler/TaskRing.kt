package com.tttt.subtitler

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.util.Locale

/**
 * (v472) تجميع حالة كل المهام الشغّالة في رقم واحد: نسبة إجمالية + الوقت المتبقي (اتحسب من سرعة التقدّم الفعلية).
 * المهمة الواقفة مؤقتًا (⏸) مش بتتحسب في النسبة ولا في الوقت — بتتعدّ بس عشان الدايرة تفضل ظاهرة.
 */
object TaskAgg {
    class Snap(val running: Int, val paused: Int, val frac: Float, val etaSec: Long)
    private class Smp(var pct: Int, var at: Long, var rate: Double)   // rate = نسبة مئوية في الثانية (متوسط متحرك)
    private val smp = HashMap<Int, Smp>()

    @Synchronized fun snap(): Snap {
        val now = System.currentTimeMillis()
        val items = TaskCenter.items.toList()
        val live = items.filter { it.state == 0 || it.state == 1 }
        val act = live.filter { !(it.state == 1 && it.paused) }
        val pausedT = live.size - act.size + items.count { it.state == 6 }
        val bg = BgJobs.jobs.filter { it.active }
        val bgAct = bg.filter { !it.paused }
        val pausedAll = pausedT + (bg.size - bgAct.size)
        val ids = items.map { it.id }.toHashSet(); smp.keys.removeAll { it !in ids }
        var rateSum = 0.0
        for (t in act) {
            if (t.state != 1) { smp.remove(t.id); continue }
            val s = smp.getOrPut(t.id) { Smp(t.pct, now, 0.0) }
            if (t.pct != s.pct) {
                val dt = (now - s.at) / 1000.0
                if (t.pct > s.pct && dt > 0.15) { val inst = (t.pct - s.pct) / dt; s.rate = if (s.rate <= 0.0) inst else s.rate * 0.7 + inst * 0.3 }
                else if (t.pct < s.pct) s.rate = 0.0
                s.pct = t.pct; s.at = now
            }
            rateSum += s.rate
        }
        val n = act.size + bgAct.size
        val frac: Float
        if (n > 0) frac = ((act.sumOf { it.pct } + bgAct.sumOf { it.pct }) / (100f * n)).coerceIn(0f, 1f)
        else {
            val p = items.filter { it.state == 1 || it.state == 6 }.map { it.pct } + bg.filter { it.paused }.map { it.pct }
            frac = if (p.isEmpty()) 0f else (p.sum() / (100f * p.size)).coerceIn(0f, 1f)
        }
        var eta = -1L
        if (act.isNotEmpty() && rateSum > 0.05) eta = (act.sumOf { 100 - it.pct } / rateSum).toLong().coerceAtLeast(0L)
        val bgEta = bgAct.maxOfOrNull { it.remainSec } ?: -1L
        if (bgEta >= 0L && (act.isEmpty() || eta >= 0L)) eta = maxOf(eta, bgEta)
        return Snap(n, pausedAll, frac, eta)
    }

    fun fmt(sec: Long): String = when {
        sec >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec % 3600 / 60, sec % 60)
        else -> String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
    }
}

/** (v472) دايرة زي دايرة مشغّل الموسيقى: حلقة تقدّم بتلف حواليها + الوقت المتبقي (أو النسبة) في النص + عدد المهام في ركن صغير */
class TaskRingView(ctx: Context, private val accent: Int) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF01B1E27.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33FFFFFF; strokeWidth = 4f * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * d; color = accent }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 12f * d }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBFC6D4.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT; textSize = 8.5f * d }
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }
    private val badgeTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 9.5f * d }
    private val box = RectF()
    private var frac = 0f; private var main = ""; private var small = ""; private var count = 0; private var dim = false

    fun set(frac: Float, main: String, small: String, count: Int, dim: Boolean) {
        if (this.frac == frac && this.main == main && this.small == small && this.count == count && this.dim == dim) return
        this.frac = frac; this.main = main; this.small = small; this.count = count; this.dim = dim; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = minOf(w, h) / 2f
        c.drawCircle(w / 2f, h / 2f, r, bg)
        val inset = 4f * d + track.strokeWidth / 2f
        box.set(w / 2f - r + inset, h / 2f - r + inset, w / 2f + r - inset, h / 2f + r - inset)
        c.drawArc(box, 0f, 360f, false, track)
        arc.color = if (dim) 0xFF8A93A6.toInt() else accent
        if (frac > 0.005f) c.drawArc(box, -90f, 360f * frac.coerceIn(0f, 1f), false, arc)
        val cy = h / 2f
        if (small.isEmpty()) c.drawText(main, w / 2f, cy - (txt.ascent() + txt.descent()) / 2f, txt)
        else {
            c.drawText(main, w / 2f, cy - (txt.ascent() + txt.descent()) / 2f - 4f * d, txt)
            c.drawText(small, w / 2f, cy + 11f * d, sub)
        }
        if (count > 1) {
            val br = 8.5f * d; val bx = w - br - 1f * d; val by = br + 1f * d
            c.drawCircle(bx, by, br, badge)
            c.drawText(if (count > 9) "9+" else count.toString(), bx, by - (badgeTxt.ascent() + badgeTxt.descent()) / 2f, badgeTxt)
        }
    }
}
