package com.tttt.subtitler

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator

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
