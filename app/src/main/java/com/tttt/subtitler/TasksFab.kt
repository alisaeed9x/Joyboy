package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * (v324) زرار «المهام» العايم: دايرة حمراء فيها الرقم (عدد المهام الشغّالة والمستنية — ترجمة الخلفية + قص/تحميل/ضغط…).
 * بيظهر طول ما فيه حاجة في صفحة المهام؛ لو كلها خلصت بيفضل ظاهر رمادي برقم المهام الباقية عشان توصل لناتجها.
 * بيتسحب بالصباع لأي مكان في الشاشة ومكانه بيتحفظ (نسبة من الشاشة) ومشترك بين الرئيسية والمتصفح والملفات.
 * parent لازم يكون FrameLayout؛ onOpen بيفتح صفحة المهام.
 */
class TasksFab(private val act: Activity, private val parent: FrameLayout, private val ui: Ui, private val onOpen: () -> Unit) {
    val view = TaskRingView(act, Themes.byId(Cfg.str("theme", "mx")).primary)   // (v472) دايرة بحلقة تقدّم (زي دايرة مشغّل الموسيقى)
    private val main = Handler(Looper.getMainLooper())
    private val size = ui.dp(56)
    private val prefs = act.getSharedPreferences("p", 0)
    private val listener: () -> Unit = { if (!act.isDestroyed && !act.isFinishing) main.post { refresh() } }
    private val tick = object : Runnable { override fun run() { if (!act.isDestroyed && !act.isFinishing) { refresh(); main.postDelayed(this, 1000L) } } }
    private var shown = false
    private var downX = 0f; private var downY = 0f; private var startTx = 0f; private var startTy = 0f; private var moved = false
    /** الشاشة اللي فيها الزرار ممكن تخبّيه (صفحة المهام نفسها مثلًا) */
    var hidden = false
        set(v) { field = v; refresh() }

    init {
        view.apply { visibility = View.GONE; elevation = ui.dp(8).toFloat() }
        view.setOnTouchListener { v, e -> touch(v, e) }
        parent.addView(view, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
        parent.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob -> if (r - l != or2 - ol || b - t != ob - ot) place() }
        TaskCenter.extra.add(listener)
        main.postDelayed(tick, 1500L)
        parent.post { place(); refresh() }
    }

    /** بيتنده من onDestroy بتاع الشاشة */
    fun detach() { TaskCenter.extra.remove(listener); main.removeCallbacks(tick); Motion.breathe(view, false) }

    private fun place() {
        val w = parent.width; val h = parent.height
        if (w <= 0 || h <= 0) return
        // أول مرة (مفيش مكان متحفظ): ناحية الشمال على ارتفاع ~70% من الشاشة
        val fx = prefs.getFloat("tfab_fx", 0.02f); val fy = prefs.getFloat("tfab_fy", 0.68f)
        view.translationX = (fx * (w - size)).coerceIn(0f, (w - size).toFloat())
        view.translationY = (fy * (h - size)).coerceIn(0f, (h - size).toFloat())
    }

    private fun touch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; startTx = v.translationX; startTy = v.translationY; moved = false
                (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX; val dy = e.rawY - downY
                if (!moved && Math.abs(dx) + Math.abs(dy) > ui.dp(8)) moved = true
                if (moved) {
                    val w = parent.width; val h = parent.height
                    v.translationX = (startTx + dx).coerceIn(0f, (w - size).coerceAtLeast(0).toFloat())
                    v.translationY = (startTy + dy).coerceIn(0f, (h - size).coerceAtLeast(0).toFloat())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) {
                    val w = parent.width - size; val h = parent.height - size
                    if (w > 0 && h > 0) prefs.edit().putFloat("tfab_fx", v.translationX / w).putFloat("tfab_fy", v.translationY / h).apply()
                } else onOpen()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    fun refresh() {
        val a = TaskAgg.snap()   // (v472) إجمالي المهام الشغّالة (الواقفة مؤقتًا مش بتتحسب في النسبة والوقت)
        val alive = a.running + a.paused
        val show = alive > 0 && !hidden   // (v420) بيظهر وقت الشغل بس؛ بعد ما يخلص بيختفي (الناتج في ⋯ المزيد ← المهام)
        val pctTxt = (a.frac * 100f).toInt().coerceIn(0, 100).toString() + "%"
        if (a.running == 0) view.set(a.frac, "⏸", pctTxt, a.paused, true)
        else if (a.etaSec >= 0L) view.set(a.frac, TaskAgg.fmt(a.etaSec), pctTxt, alive, false)
        else view.set(a.frac, pctTxt, "", alive, false)
        if (show && !shown) { view.visibility = View.VISIBLE; Motion.pop(view, 0.5f) }
        if (!show && shown) view.visibility = View.GONE
        shown = show
        Motion.breathe(view, show && a.running > 0)
    }
}
