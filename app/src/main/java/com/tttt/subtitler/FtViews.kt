package com.tttt.subtitler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator

/** رسم أيقونة نوع الجهاز بالكانفس (من غير صور) — كل الألوان من الثيم */
object FtDraw {
    fun glyph(c: Canvas, p: Paint, type: String, cx: Float, cy: Float, s: Float, color: Int) {
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.09f; p.color = color; p.strokeCap = Paint.Cap.ROUND
        val r = RectF()
        when (type) {
            "tv" -> {
                r.set(cx - s * 0.5f, cy - s * 0.34f, cx + s * 0.5f, cy + s * 0.2f); c.drawRoundRect(r, s * 0.07f, s * 0.07f, p)
                c.drawLine(cx - s * 0.2f, cy + s * 0.38f, cx + s * 0.2f, cy + s * 0.38f, p)
                c.drawLine(cx, cy + s * 0.2f, cx, cy + s * 0.38f, p)
            }
            "tablet" -> {
                r.set(cx - s * 0.42f, cy - s * 0.5f, cx + s * 0.42f, cy + s * 0.5f); c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                c.drawLine(cx - s * 0.08f, cy + s * 0.38f, cx + s * 0.08f, cy + s * 0.38f, p)
            }
            "pc" -> {
                r.set(cx - s * 0.5f, cy - s * 0.36f, cx + s * 0.5f, cy + s * 0.14f); c.drawRoundRect(r, s * 0.05f, s * 0.05f, p)
                c.drawLine(cx - s * 0.58f, cy + s * 0.32f, cx + s * 0.58f, cy + s * 0.32f, p)
                c.drawLine(cx - s * 0.5f, cy + s * 0.14f, cx - s * 0.58f, cy + s * 0.32f, p)
                c.drawLine(cx + s * 0.5f, cy + s * 0.14f, cx + s * 0.58f, cy + s * 0.32f, p)
            }
            "phone" -> {
                r.set(cx - s * 0.26f, cy - s * 0.5f, cx + s * 0.26f, cy + s * 0.5f); c.drawRoundRect(r, s * 0.09f, s * 0.09f, p)
                c.drawLine(cx - s * 0.07f, cy + s * 0.38f, cx + s * 0.07f, cy + s * 0.38f, p)
            }
            else -> {
                c.drawCircle(cx, cy, s * 0.4f, p)
                c.drawCircle(cx, cy, s * 0.14f, p)
            }
        }
    }
}

/** رادار: دواير + موجة بتتوسّع + شعاع دوّار، المركز = الجهاز الحالي، وكل جهاز بيظهر بحركة «ظهور» */
class FtRadarView(ctx: Context, private val th: Theme) : View(ctx) {
    private class Dot(val id: String, val name: String, val type: String, val seen: Long, var rtt: Int)

    var meName: String = ""
    var meType: String = "phone"
    var scanning: Boolean = false
        set(v) {
            field = v
            if (v) { if (!anim.isStarted) anim.start() } else { anim.cancel(); phase = 0f }
            invalidate()
        }

    private val dots = ArrayList<Dot>()
    /** (v654) دوسة على جهاز في الرادار نفسه */
    var onPick: ((String) -> Unit)? = null
    private val hits = ArrayList<Triple<Float, Float, String>>()
    private var phase = 0f
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val over = OvershootInterpolator(2.2f)
    private val anim: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { a -> phase = a.animatedValue as Float; invalidate() }
    }
    private val onPrimary: Int get() = if (th.isLight) Color.WHITE else Color.BLACK

    fun setPeers(l: List<FtPeer>) {
        val old = dots.associateBy { it.id }
        dots.clear()
        for (x in l) dots.add(Dot(x.id, x.name, x.type, old[x.id]?.seen ?: System.currentTimeMillis(), x.rtt))
        invalidate()
    }

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (onPick == null) return super.onTouchEvent(e)
        if (e.action == android.view.MotionEvent.ACTION_DOWN) return hits.any { Math.hypot((it.first - e.x).toDouble(), (it.second - e.y).toDouble()) < 36f * d }
        if (e.action == android.view.MotionEvent.ACTION_UP) {
            val h = hits.minByOrNull { Math.hypot((it.first - e.x).toDouble(), (it.second - e.y).toDouble()) }
            if (h != null && Math.hypot((h.first - e.x).toDouble(), (h.second - e.y).toDouble()) < 36f * d) { performClick(); onPick?.invoke(h.third); return true }
        }
        return false
    }
    override fun performClick(): Boolean = super.performClick()

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (scanning && !anim.isStarted) anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val r = minOf(w, h) / 2f - 6f * d
        if (r <= 0f) return
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d; p.color = th.border
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, p)
        if (scanning) {
            // موجة متوسّعة
            for (k in 0..1) {
                val ph = (phase + k * 0.5f) % 1f
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d
                p.color = (th.primary and 0x00FFFFFF) or (((1f - ph) * 110f).toInt() shl 24)
                c.drawCircle(cx, cy, r * ph, p)
            }
            // شعاع دوّار
            val base = phase * 360f
            for (k in 0 until 22) {
                val a = Math.toRadians((base - k * 2.6f).toDouble())
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * d
                p.color = (th.accent and 0x00FFFFFF) or ((((1f - k / 22f) * 90f).toInt()) shl 24)
                c.drawLine(cx, cy, (cx + r * Math.cos(a)).toFloat(), (cy + r * Math.sin(a)).toFloat(), p)
            }
        }
        // الأجهزة
        val now = System.currentTimeMillis()
        var animating = false
        hits.clear()
        dots.forEachIndexed { i, dt ->
            val ang = Math.toRadians((((dt.id.hashCode() and 0x7fffffff) % 360) + i * 137).toDouble())
            val rr = r * (if (i % 2 == 0) 0.62f else 0.84f)
            val x = (cx + rr * Math.cos(ang)).toFloat(); val y = (cy + rr * Math.sin(ang)).toFloat()
            val age = ((now - dt.seen) / 520f).coerceIn(0f, 1f)
            if (age < 1f) animating = true
            val sc = if (age >= 1f) 1f else over.getInterpolation(age)
            val rad = 25f * d * sc
            hits.add(Triple(x, y, dt.id))
            p.style = Paint.Style.FILL; p.color = th.card; c.drawCircle(x, y, rad, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = th.primary; c.drawCircle(x, y, rad, p)
            if (age < 1f) { p.strokeWidth = 1.5f * d; p.color = (th.primary and 0x00FFFFFF) or (((1f - age) * 160f).toInt() shl 24); c.drawCircle(x, y, rad + 12f * d * age, p) }
            FtDraw.glyph(c, p, dt.type, x, y, 27f * d * sc, th.primary)
            tp.textSize = 12f * d; tp.color = th.text
            c.drawText(dt.name.take(16), x, y + rad + 15f * d, tp)
        }
        // المركز: الجهاز الحالي
        // (v654) جهازي = أخضر في النص باسمه
        p.style = Paint.Style.FILL; p.color = th.success; c.drawCircle(cx, cy, 28f * d, p)
        FtDraw.glyph(c, p, meType, cx, cy, 30f * d, onPrimary)
        tp.textSize = 12.5f * d; tp.color = th.success; tp.isFakeBoldText = true
        if (meName.isNotEmpty()) c.drawText(meName.take(18), cx, cy + 46f * d, tp)
        tp.isFakeBoldText = false
        if (animating) postInvalidateOnAnimation()
    }
}

/** دايرة تقدم بنسبة مئوية — الحركة ناعمة */
class FtRingView(ctx: Context, private val th: Theme) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown = 0f
    private var target = 0f
    private var anim: ValueAnimator? = null
    var label: String = ""
        set(v) { field = v; invalidate() }
    var sub: String = ""
        set(v) { field = v; invalidate() }
    var failed: Boolean = false
        set(v) { field = v; invalidate() }
    /** (v672) دايرة بتلف (انتظار موافقة / تجهيز) بدل نسبة ثابتة */
    var spinning: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            spinAnim?.cancel()
            if (v) {
                val a = ValueAnimator.ofFloat(0f, 360f).apply { duration = 1100L; repeatCount = ValueAnimator.INFINITE; interpolator = android.view.animation.LinearInterpolator(); addUpdateListener { spinAngle = it.animatedValue as Float; invalidate() } }
                spinAnim = a; a.start()
            }
            invalidate()
        }
    private var spinAnim: ValueAnimator? = null
    private var spinAngle = 0f

    fun setProgress(f: Float) {
        val t = f.coerceIn(0f, 1f)
        if (Math.abs(t - target) < 0.001f) return
        target = t
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, t).apply { duration = 350L; addUpdateListener { shown = it.animatedValue as Float; invalidate() } }
        anim = a; a.start()
    }

    override fun onDetachedFromWindow() { anim?.cancel(); spinAnim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val sw = 10f * d
        val r = minOf(w, h) / 2f - sw
        if (r <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.ROUND
        p.color = th.border; c.drawCircle(cx, cy, r, p)
        p.color = if (failed) th.danger else th.primary
        if (spinning) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), spinAngle - 90f, 100f, false, p)
        else if (shown > 0.003f) c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 360f * shown, false, p)
        p.style = Paint.Style.FILL
        p.textAlign = Paint.Align.CENTER
        p.color = th.text; p.textSize = 30f * d
        c.drawText(label, cx, cy + 4f * d, p)
        if (sub.isNotEmpty()) { p.color = th.muted; p.textSize = 12f * d; c.drawText(sub, cx, cy + 24f * d, p) }
    }
}
