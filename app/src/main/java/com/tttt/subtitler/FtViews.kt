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
            "audio" -> {   // (v824) سماعة
                r.set(cx - s * 0.38f, cy - s * 0.46f, cx + s * 0.38f, cy + s * 0.3f); c.drawArc(r, 180f, 180f, false, p)
                c.drawRoundRect(RectF(cx - s * 0.46f, cy - s * 0.02f, cx - s * 0.26f, cy + s * 0.34f), s * 0.06f, s * 0.06f, p)
                c.drawRoundRect(RectF(cx + s * 0.26f, cy - s * 0.02f, cx + s * 0.46f, cy + s * 0.34f), s * 0.06f, s * 0.06f, p)
            }
            "watch" -> {   // (v824) ساعة
                r.set(cx - s * 0.22f, cy - s * 0.24f, cx + s * 0.22f, cy + s * 0.24f); c.drawRoundRect(r, s * 0.08f, s * 0.08f, p)
                c.drawLine(cx - s * 0.12f, cy - s * 0.24f, cx - s * 0.12f, cy - s * 0.46f, p); c.drawLine(cx + s * 0.12f, cy - s * 0.24f, cx + s * 0.12f, cy - s * 0.46f, p)
                c.drawLine(cx - s * 0.12f, cy + s * 0.24f, cx - s * 0.12f, cy + s * 0.46f, p); c.drawLine(cx + s * 0.12f, cy + s * 0.24f, cx + s * 0.12f, cy + s * 0.46f, p)
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
    /** conn: 0 = Joyboy/ربط · 1 = Quick Share · 2 = بلوتوث */
    private class Dot(val id: String, val name: String, val type: String, val seen: Long, var rtt: Int, val conn: Int, val note: String)
    /** (v831) مكان ثابت لكل جهاز (slot) — مفيش جهاز بيقفز لما جهاز تاني يظهر */
    private val slotOf = HashMap<String, Int>()

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
        for (x in l) dots.add(Dot(x.id, x.name, x.type, old[x.id]?.seen ?: System.currentTimeMillis(), x.rtt, if (x.bt != null) 2 else if (x.qs) 1 else 0, x.btNote))
        val ids = dots.map { it.id }.toSet()
        slotOf.keys.retainAll(ids)
        val used = slotOf.values.toHashSet()
        for (dt in dots) if (dt.id !in slotOf) { var k = 0; while (k in used) k++; slotOf[dt.id] = k; used.add(k) }
        invalidate()
    }

    private fun typeShort(t: String, note: String): String = when (t) {
        "tv" -> "تلفزيون"; "tablet" -> "تابلت"; "phone" -> "موبايل"; "pc" -> "كمبيوتر"
        "audio" -> if (note.isNotEmpty()) note else "سماعة / سبيكر"
        "watch" -> "ساعة"; "periph" -> "كيبورد / ماوس"; else -> "غير معروف"
    }
    private fun connColor(c: Int): Int = when (c) { 2 -> 0xFF2F80ED.toInt(); 1 -> 0xFF14A38B.toInt(); else -> th.primary }
    private fun connTag(c: Int): String = when (c) { 2 -> "BT"; 1 -> "QS"; else -> "JB" }

    /** (v831) توزيع دوّار متساوي (sunflower) بسعة ثابتة لكل شريحة عدد → الأجهزة مش بتتحط فوق بعض حتى مع 10–30 جهاز */
    private fun slotsFor(cx: Float, cy: Float, r: Float, cap: Int): List<FloatArray> {
        val rIn = maxOf(0.42f * r, 82f * d)
        val rOut = maxOf(rIn + 10f * d, r - 30f * d)
        val out = ArrayList<FloatArray>(cap)
        for (i in 0 until cap) {
            val rr = rIn + (rOut - rIn) * Math.sqrt((i + 0.5) / cap).toFloat()
            val a = i * 2.399963
            out.add(floatArrayOf((cx + rr * Math.cos(a)).toFloat(), (cy + rr * Math.sin(a)).toFloat()))
        }
        return out
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
        val need = maxOf(dots.size, (slotOf.values.maxOrNull() ?: -1) + 1)
        val cap = when { need <= 8 -> 8; need <= 14 -> 14; need <= 22 -> 22; else -> maxOf(32, need) }
        val scale = when (cap) { 8 -> 1f; 14 -> 0.76f; 22 -> 0.6f; else -> 0.5f }
        val slots = slotsFor(cx, cy, r, cap)
        dots.forEach { dt ->
            val sl = slots[(slotOf[dt.id] ?: 0).coerceIn(0, slots.size - 1)]
            val x = sl[0]; val y = sl[1]
            val age = ((now - dt.seen) / 520f).coerceIn(0f, 1f)
            if (age < 1f) animating = true
            val sc = if (age >= 1f) 1f else over.getInterpolation(age)
            val rad = 25f * d * scale * sc
            val cc = connColor(dt.conn)
            hits.add(Triple(x, y, dt.id))
            p.style = Paint.Style.FILL; p.color = th.card; c.drawCircle(x, y, rad, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = cc; c.drawCircle(x, y, rad, p)
            if (age < 1f) { p.strokeWidth = 1.5f * d; p.color = (cc and 0x00FFFFFF) or (((1f - age) * 160f).toInt() shl 24); c.drawCircle(x, y, rad + 12f * d * age, p) }
            FtDraw.glyph(c, p, dt.type, x, y, 27f * d * scale * sc, cc)
            // شارة طريقة الاتصال (BT / QS / JB)
            if (age >= 1f) {
                val br = 8.5f * d * maxOf(scale, 0.75f)
                val bx = x + rad * 0.74f; val by = y - rad * 0.74f
                p.style = Paint.Style.FILL; p.color = cc; c.drawCircle(bx, by, br, p)
                tp.textSize = 7.5f * d * maxOf(scale, 0.8f); tp.color = Color.WHITE; tp.isFakeBoldText = true
                c.drawText(connTag(dt.conn), bx, by + tp.textSize * 0.35f, tp)
                tp.isFakeBoldText = false
            }
            tp.textSize = 11.5f * d * maxOf(scale, 0.78f); tp.color = th.text
            c.drawText(dt.name.take(if (scale >= 0.76f) 14 else 9), x, y + rad + 13f * d * maxOf(scale, 0.78f), tp)
            if (scale >= 0.76f) {
                tp.textSize = 9.5f * d; tp.color = th.muted
                c.drawText(typeShort(dt.type, dt.note), x, y + rad + 25f * d, tp)
            }
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
