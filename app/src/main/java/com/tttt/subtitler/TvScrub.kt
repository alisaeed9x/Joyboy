package com.tttt.subtitler

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v569) شريط التقديم/التأخير على التلفزيون بالريموت العادي: بيظهر أول ما تدوس يمين/شمال (والشريط الكبير مخفي)
 * ويعرض: الدقيقة اللي هتروحلها (يسار) · مدة الفيديو كله (يمين) · شريط تقدّم بمؤشر + علامة المكان اللي كنت فيه · الفرق (+30ث / −1:00).
 * الضغط المتكرر بيحرّك المؤشر بس (من غير ما يعمل seek في كل دوسة)، والـ seek بيتنفّذ لما تسيب الزرار شوية أو تدوس OK.
 * الكلاس ده عرض بس — المنطق (التجميع والتثبيت) في Player.tvSeek.
 */
class TvScrubOverlay(act: Activity, ui: Ui, private val accent: Int) {
    val view = LinearLayout(act)
    private val delta = TextView(act)
    private val pos = TextView(act)
    private val total = TextView(act)
    private val bar = Bar(act, accent)

    init {
        view.apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; visibility = View.GONE
            isFocusable = false; isClickable = false
            setPadding(ui.dp(22), ui.dp(14), ui.dp(22), ui.dp(16))
            background = GradientDrawable().apply { setColor(0xE6101216.toInt()); cornerRadius = ui.dp(18).toFloat(); setStroke(ui.dp(1), 0x33FFFFFF) }
        }
        delta.apply { textSize = 22f; setTextColor(accent); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; text = "" }
        for (t in listOf(pos, total)) t.apply { textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; text = "00:00" }
        total.setTextColor(0xFFB8BDC6.toInt())
        view.addView(delta, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        row.addView(pos, LinearLayout.LayoutParams(ui.dp(92), -2))
        row.addView(bar, LinearLayout.LayoutParams(0, ui.dp(34), 1f))
        row.addView(total, LinearLayout.LayoutParams(ui.dp(92), -2).apply { marginStart = ui.dp(6) })
        view.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
    }

    val shown: Boolean get() = view.visibility == View.VISIBLE

    /** [target] المكان الجديد · [origin] المكان اللي بدأت منه · [dur] مدة الفيديو (0 = مجهولة) */
    fun show(target: Long, origin: Long, dur: Long) {
        pos.text = PlayerLogic.clock(target)
        total.text = if (dur > 0) PlayerLogic.clock(dur) else "--:--"
        val d = target - origin
        delta.text = if (d == 0L) "" else (if (d > 0) "⏩ +" else "⏪ −") + span(Math.abs(d))
        bar.frac = if (dur > 0) (target.toFloat() / dur).coerceIn(0f, 1f) else 0f
        bar.orig = if (dur > 0) (origin.toFloat() / dur).coerceIn(0f, 1f) else -1f
        bar.invalidate()
        view.visibility = View.VISIBLE
    }

    fun hide() { view.visibility = View.GONE }

    private fun span(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) PlayerLogic.clock(ms) else "$s ث"
    }

    private class Bar(ctx: Context, private val accent: Int) : View(ctx) {
        var frac = 0f
        var orig = -1f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { isFocusable = false; isClickable = false }
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val th = 8f * d; val cy = height / 2f; val w = width.toFloat()
            p.color = 0x55FFFFFF; c.drawRoundRect(RectF(0f, cy - th / 2, w, cy + th / 2), th / 2, th / 2, p)
            p.color = accent; c.drawRoundRect(RectF(0f, cy - th / 2, w * frac, cy + th / 2), th / 2, th / 2, p)
            if (orig >= 0f) { p.color = 0xFFFFFFFF.toInt(); val x = (w * orig).coerceIn(2f * d, w - 2f * d); c.drawRect(x - 1.5f * d, cy - 9f * d, x + 1.5f * d, cy + 9f * d, p) }   // علامة «كنت هنا»
            p.color = Color.WHITE; c.drawCircle((w * frac).coerceIn(10f * d, w - 10f * d), cy, 11f * d, p)
        }
    }
}
