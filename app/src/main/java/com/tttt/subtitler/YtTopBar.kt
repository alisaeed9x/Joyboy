package com.tttt.subtitler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * (v407) الخط الأزرق الرفيع اللي فوق صفحة يوتيوب (زي خط التحميل في يوتيوب): بيتحرك يمين وشمال طول ما فيه تحميل
 * (فتح صفحة · تحديث الرئيسية/الاشتراكات في الخلفية) ويختفي لوحده لما التحميل يخلص.
 */
class YtTopLine(ctx: Context) : View(ctx) {
    private val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3EA6FF.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x333EA6FF }
    private var f = 0f
    private var anim: ValueAnimator? = null

    init { visibility = GONE }

    fun start() {
        visibility = VISIBLE
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { f = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() {
        anim?.cancel(); anim = null
        visibility = GONE
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        c.drawRect(0f, 0f, w, h, track)
        val sw = w * 0.38f
        val x = -sw + (w + sw) * f
        c.drawRect(x, 0f, x + sw, h, seg)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        super.onDetachedFromWindow()
    }
}
