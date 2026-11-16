package com.tttt.subtitler

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/**
 * (v1055) لما الفيديو يخلص: تلات أزرار في نص الشاشة — ⏮ السابق · 🔄 إعادة من الأول · ⏭ التالي.
 * التالي بيبدأ لوحده بعد [autoNextSec] ثواني (لو > 0)؛ أي اختيار تاني بيلغي العدّاد.
 */
object EndOverlay {
    private val h = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    private var tick: Runnable? = null

    fun hide() {
        try { tick?.let { h.removeCallbacks(it) } } catch (_: Throwable) {}
        tick = null
        try { dlg?.setOnDismissListener(null); dlg?.dismiss() } catch (_: Throwable) {}
        dlg = null
    }

    fun show(a: Activity, hasPrev: Boolean, hasNext: Boolean, autoNextSec: Int, onPrev: () -> Unit, onReplay: () -> Unit, onNext: () -> Unit) {
        hide()
        try {
            if (a.isFinishing || a.isDestroyed) return
            val dd = a.resources.displayMetrics.density
            val big = DeviceUi.isTv(a)
            val th = Themes.byId(Cfg.str("theme", "mx"))
            fun px(v: Int) = (v * dd).toInt()
            var left = if (hasNext) autoNextSec else 0
            fun act(go: () -> Unit) { hide(); try { go() } catch (e: Throwable) { LogStore.err("EndOverlay:act", e) } }
            fun btn(label: String, onClick: () -> Unit): TextView = TextView(a).apply {
                text = label; textSize = if (big) 20f else 16f; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(px(if (big) 26 else 16), px(if (big) 18 else 13), px(if (big) 26 else 16), px(if (big) 18 else 13))
                fun paint(f: Boolean) {
                    background = GradientDrawable().apply { cornerRadius = px(16).toFloat(); setColor(if (f) th.primary else th.card); setStroke(px(2), th.primary) }
                    setTextColor(if (f) Color.WHITE else th.text)
                }
                paint(false)
                setOnFocusChangeListener { _, f -> paint(f) }
                setOnClickListener { onClick() }
            }
            val bPrev = btn("⏮ السابق") { act(onPrev) }
            val bReplay = btn("🔄 إعادة") { act(onReplay) }
            val bNext = btn(nextLabel(left)) { act(onNext) }
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR; gravity = Gravity.CENTER
                setPadding(px(14), px(12), px(14), px(12))
                background = GradientDrawable().apply { cornerRadius = px(22).toFloat(); setColor((th.card and 0x00FFFFFF) or 0xE6000000.toInt()) }
                val m = px(6)
                if (hasPrev) addView(bPrev, LinearLayout.LayoutParams(-2, -2).apply { setMargins(m, 0, m, 0) })
                addView(bReplay, LinearLayout.LayoutParams(-2, -2).apply { setMargins(m, 0, m, 0) })
                if (hasNext) addView(bNext, LinearLayout.LayoutParams(-2, -2).apply { setMargins(m, 0, m, 0) })
            }
            val d = Dialog(a)
            d.requestWindowFeature(Window.FEATURE_NO_TITLE)
            d.setContentView(row)
            d.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(-2, -2)
                setGravity(Gravity.CENTER)
                setDimAmount(0.35f)
            }
            d.setCancelable(true)
            d.setOnCancelListener { hide() }
            dlg = d
            d.show()
            bReplay.requestFocus()
            if (hasNext && left > 0) {
                val r = object : Runnable {
                    override fun run() {
                        left--
                        if (left <= 0) { act(onNext); return }
                        bNext.text = nextLabel(left)
                        h.postDelayed(this, 1000L)
                    }
                }
                tick = r
                h.postDelayed(r, 1000L)
            }
        } catch (e: Throwable) { LogStore.err("EndOverlay:show", e) }
    }

    private fun nextLabel(left: Int): String = if (left > 0) "⏭ التالي  ($left)" else "⏭ التالي"
}
