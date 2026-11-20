package com.tttt.subtitler

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView

/** لودينج على طريقة ويندوز 10/11: 5 نقط بتلف في دايرة — بتتسارع وبتتباطأ ورا بعض */
class WinSpinnerDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidateSelf() }
    }
    /** (v899) لودر MeeTube (AVD) بدل النقط لما MtLook.loaderOn() — الـCallback لازم مرجع قوي (الـDrawable بيمسكه weak) */
    private var avd: Drawable? = null
    private val avdCb = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { invalidateSelf() }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { scheduleSelf(what, `when`) }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) { unscheduleSelf(what) }
    }
    fun start() {
        if (MtLook.loaderOn()) {
            if (avd == null) avd = try { MtYt.app?.let { MtLook.newLoader(it) } } catch (_: Throwable) { null }
            avd?.let { it.callback = avdCb; (it as? android.graphics.drawable.Animatable)?.start(); invalidateSelf(); return }
        }
        if (!anim.isStarted) anim.start()
    }
    fun stop() { anim.cancel(); try { (avd as? android.graphics.drawable.Animatable)?.stop() } catch (_: Throwable) {} }

    override fun draw(c: Canvas) {
        val b = bounds
        avd?.let { if (MtLook.loaderOn()) { it.setBounds(b.left, b.top, b.right, b.bottom); it.draw(c); return } }
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val side = minOf(b.width(), b.height()).toFloat()
        val r = side * 0.30f; val dot = side * 0.055f
        for (k in 0 until 5) {
            var x = t - k * 0.075f
            x -= Math.floor(x.toDouble()).toFloat()
            val e = x * x * (3f - 2f * x)                       // بطيء في الأول والآخر، سريع في النص
            val ang = Math.toRadians(e * 360.0 - 90.0)
            paint.alpha = if (x < 0.04f || x > 0.96f) 120 else 255
            c.drawCircle(cx + (r * Math.cos(ang)).toFloat(), cy + (r * Math.sin(ang)).toFloat(), dot, paint)
        }
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: ColorFilter?) { paint.colorFilter = f }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * (v1122) لودينج البرنامج الموحّد: أي «⏳ …» (وإيموجي اللودينج) في الإشعارات/النصوص بقت بتتعرض بنفس لودينج تشغيل الفيديو
 * (WinSpinnerDrawable / لودر MeeTube) بدل الإيموجي.
 *  • `LoadUi.set(tv, "⏳ بجيب…")` ← بيشيل الإيموجي ويحط اللودينج قبل النص؛ أي نص تاني بعد كده (أو `set` بنص من غير ⏳) بيشيل اللودينج لوحده.
 *  • `LoadUi.view(ctx, dp, color)` ← View لودينج لوحده (بدل ProgressBar الدايري).
 */
object LoadUi {
    const val MARK = "⏳"
    private class St(val d: WinSpinnerDrawable) { var own = false; var on = false }
    private val map = java.util.WeakHashMap<TextView, St>()

    fun isLoad(t: CharSequence?): Boolean = t != null && t.toString().trimStart().startsWith(MARK)
    /** فيه ⏳ في أي مكان في النص (للشرايح/الحالات اللي الإيموجي فيها في النص مش في أوله) */
    fun has(t: CharSequence?): Boolean = t != null && t.toString().contains(MARK)
    /** النص من غير ⏳؛ «…» لوحدها = من غير نص (اللودينج كفاية) */
    fun strip(t: CharSequence?): String {
        val x = (t?.toString() ?: "").replace(MARK, "").replace("  ", " ").trim()
        return if (x == "…" || x == "...") "" else x
    }

    private fun state(tv: TextView): St = map.getOrPut(tv) {
        val st = St(WinSpinnerDrawable())
        tv.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!st.own && st.on) detach(tv) }   // نص جديد من برّه ← اللودينج يتشال
        })
        tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (st.on) st.d.start() }
            override fun onViewDetachedFromWindow(v: View) { st.d.stop() }
        })
        st
    }

    fun set(tv: TextView, t: CharSequence?, dp: Int = 0) {
        if (!has(t)) { map[tv]?.let { if (it.on) detach(tv) }; tv.text = t; return }
        val st = state(tv)
        val txt = strip(t)
        if (st.on && tv.text.toString() == txt) return   // نفس الحالة (بتتنده كل تيك) ← من غير requestLayout
        st.own = true
        try { tv.text = txt } finally { st.own = false }
        val den = tv.resources.displayMetrics.density
        val px = ((if (dp > 0) dp else if (txt.isEmpty()) 30 else 18) * den).toInt()
        st.d.setBounds(0, 0, px, px)
        st.d.setColorFilter(PorterDuffColorFilter(tv.currentTextColor, PorterDuff.Mode.SRC_IN))
        tv.compoundDrawablePadding = if (txt.isEmpty()) 0 else (8 * den).toInt()
        tv.setCompoundDrawablesRelative(st.d, null, null, null)
        st.on = true
        st.d.start()
    }

    fun detach(tv: TextView) {
        val st = map[tv] ?: return
        st.on = false; st.d.stop()
        tv.setCompoundDrawablesRelative(null, null, null, null)
    }

    /** View لودينج لوحده بنفس الأنيميشن (بدل ProgressBar الدايري) — بيشتغل لما يظهر وبيقف لما يتشال */
    fun view(ctx: android.content.Context, dp: Int = 44, color: Int = Color.WHITE): View {
        val d = WinSpinnerDrawable().apply { setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)) }
        val v = View(ctx)
        val px = (dp * ctx.resources.displayMetrics.density).toInt()
        v.minimumWidth = px; v.minimumHeight = px   // wrap_content = الحجم ده
        v.foreground = d
        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { d.start() }
            override fun onViewDetachedFromWindow(x: View) { d.stop() }
        })
        return v
    }
}


/**
 * (v1143) انتقال المتصفح: شريط رفيع لامع بيجري فوق (زي كروم/يوتيوب) بدل حبّة اللودينج الكبيرة. من غير نص ومن غير خلفية.
 * بيتحط في decorView وبيتشال بـremoveView؛ الأنيميشن بيقف لوحده لما الـView يتشال.
 */
object NavLoadBar {
    fun make(ctx: android.content.Context): android.view.View {
        val den = ctx.resources.displayMetrics.density
        val bar = android.widget.FrameLayout(ctx)
        bar.setBackgroundColor(0x22FFFFFF)
        val glow = View(ctx)
        glow.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x008FB4FF, 0xFF8FB4FF.toInt(), 0x008FB4FF))
        bar.addView(glow, android.widget.FrameLayout.LayoutParams((140 * den).toInt(), -1))
        var anim: android.animation.ValueAnimator? = null
        bar.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = (r - l).toFloat()
            if (w > 0f && anim == null) {
                anim = android.animation.ValueAnimator.ofFloat(-140f * den, w).apply {
                    duration = 900L; repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { glow.translationX = it.animatedValue as Float }
                    start()
                }
            }
        }
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { anim?.cancel(); anim = null }
        })
        bar.layoutParams = android.widget.FrameLayout.LayoutParams(-1, (3 * den).toInt().coerceAtLeast(3), android.view.Gravity.TOP)
        return bar
    }
}
