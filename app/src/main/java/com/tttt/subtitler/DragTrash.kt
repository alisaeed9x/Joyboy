package com.tttt.subtitler

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * (v736) سلة ✕ مشتركة للدواير العايمة الثابتة (دايرة التحميل · دايرة اللينك): بتظهر تحت لما تسحب الدايرة،
 * وسيب الدايرة عليها = تتخبّى. (دايرة الموسيقى ليها سلتها في MiniDock.)
 */
class DragTrash(act: Activity, private val parent: FrameLayout, private val ui: Ui) {
    private val view = TextView(act).apply {
        text = "✕"; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        visibility = View.GONE; elevation = ui.dp(24).toFloat()
        background = ui.box(0xCCE53935.toInt(), 0x88FFFFFF.toInt(), 32, 2)
    }

    init { parent.addView(view, FrameLayout.LayoutParams(ui.dp(64), ui.dp(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(90) }) }

    fun show() {
        view.bringToFront()
        view.visibility = View.VISIBLE; view.alpha = 0f; view.scaleX = 0.6f; view.scaleY = 0.6f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start()
    }

    fun hide() { view.animate().alpha(0f).setDuration(120L).withEndAction { view.visibility = View.GONE }.start() }

    /** الدايرة المسحوبة فوق السلة (بإحداثيات الشاشة، فتنفع مهما كان أبو الـview) */
    fun near(v: View): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationOnScreen(a); view.getLocationOnScreen(b)
        val dx = (a[0] + v.width / 2f) - (b[0] + view.width / 2f)
        val dy = (a[1] + v.height / 2f) - (b[1] + view.height / 2f)
        return Math.hypot(dx.toDouble(), dy.toDouble()) < ui.dp(70)
    }

    fun update(v: View) { val sc = if (near(v)) 1.3f else 1f; if (view.scaleX != sc) { view.scaleX = sc; view.scaleY = sc } }
}
