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
    val view = IconTextView(act)
    private val main = Handler(Looper.getMainLooper())
    private val size = ui.dp(46)
    private val prefs = act.getSharedPreferences("p", 0)
    private val listener: () -> Unit = { if (!act.isDestroyed && !act.isFinishing) main.post { refresh() } }
    private val tick = object : Runnable { override fun run() { if (!act.isDestroyed && !act.isFinishing) { refresh(); main.postDelayed(this, 1500L) } } }
    private var shown = false
    private var downX = 0f; private var downY = 0f; private var startTx = 0f; private var startTy = 0f; private var moved = false
    /** الشاشة اللي فيها الزرار ممكن تخبّيه (صفحة المهام نفسها مثلًا) */
    var hidden = false
        set(v) { field = v; refresh() }

    init {
        view.apply {
            textSize = 15f; setTextColor(Color.WHITE); typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; includeFontPadding = false; visibility = View.GONE; elevation = ui.dp(8).toFloat()
            setPadding(0, 0, 0, 0)
        }
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
        val active = TaskCenter.active() + BgJobs.jobs.count { it.active }
        val total = TaskCenter.total() + BgJobs.jobs.size
        val show = total > 0 && !hidden
        val n = if (active > 0) active else total
        view.text = if (n > 99) "99+" else n.toString()
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active > 0) 0xFFE53935.toInt() else 0xFF6B7280.toInt())
            setStroke(ui.dp(2), 0xEEFFFFFF.toInt())
        }
        if (show && !shown) { view.visibility = View.VISIBLE; Motion.pop(view, 0.5f) }
        if (!show && shown) view.visibility = View.GONE
        shown = show
        Motion.breathe(view, show && active > 0)
    }
}
