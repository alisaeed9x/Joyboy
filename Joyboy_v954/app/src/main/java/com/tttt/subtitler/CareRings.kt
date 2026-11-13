package com.tttt.subtitler

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/** (v825 · Joyboy Care م1) حركة: لو المستخدم قافل الأنيميشن من النظام بنعرض الحلقات ثابتة. المدة 450–900ms حسب حجم التغيير. */
object CareMotion {
    fun reduced(c: Context): Boolean = try { Settings.Global.getFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (_: Throwable) { false }
    fun dur(delta: Float): Long = (450f + 450f * abs(delta).coerceIn(0f, 1f)).toLong()
}

/**
 * (v830) ألوان العناية بقت مشتقة من **سمة البرنامج الحالية** (الخلفية/الكروت/الحدود/النص/اللون الأساسي) بدل باليت بني ثابتة.
 * ok/warn ألوان حالة ثابتة (أخضر/كهرمان) عشان معناها يفضل واضح على أي سمة؛ bad = لون الخطر بتاع السمة؛ accent = اللون الأساسي للسمة (الأزرار والشرايح).
 */
class CarePalette(val bg: Int, val surface: Int, val line: Int, val text: Int, val muted: Int, val ok: Int, val warn: Int, val bad: Int,
                  val accent: Int = ok, val onAccent: Int = bg) {
    companion object {
        fun of(th: Theme): CarePalette {
            val light = th.isLight
            val ok = if (light) 0xFF2E7D32.toInt() else 0xFF7BD88F.toInt()
            val warn = if (light) 0xFFB26A00.toInt() else 0xFFFFB74D.toInt()
            val on = if (Theme.lum(th.primary) > 0.62) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            return CarePalette(th.bg, th.card, th.border, th.text, th.muted, ok, warn, th.danger, th.primary, on)
        }
    }
}

private fun View.spPx(sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

/** حلقة واحدة: مسار + قوس بحروف مدوّرة + رقم في النص. الوصف النصي لـTalkBack بيتحدّث مع كل تغيير. */
class CircularMetricRing(ctx: Context) : View(ctx) {
    var track = 0; var color = 0; var textColor = 0; var mutedColor = 0
    var strokePx = 12f; var bigSp = 20f
    var big = ""; var small = ""
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun setFrac(target: Float?, animate: Boolean = true) {
        val t = (target ?: 0f).coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate || CareMotion.reduced(context) || !isAttachedToWindow) { shown = t; invalidate(); return }
        anim = ValueAnimator.ofFloat(shown, t).apply {
            duration = CareMotion.dur(t - shown); interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val l = (width - s) / 2f + strokePx / 2f; val t = (height - s) / 2f + strokePx / 2f
        val r = RectF(l, t, l + s - strokePx, t + s - strokePx)
        p.strokeWidth = strokePx
        p.color = track; c.drawArc(r, 0f, 360f, false, p)
        if (shown > 0.004f) { p.color = color; c.drawArc(r, -90f, 360f * shown, false, p) }
        val cx = width / 2f; val cy = height / 2f
        tp.color = textColor; tp.textSize = spPx(bigSp)
        val off = if (small.isEmpty()) -(tp.ascent() + tp.descent()) / 2f else -spPx(2f)
        c.drawText(big, cx, cy + off, tp)
        if (small.isNotEmpty()) { tp.color = mutedColor; tp.textSize = spPx(10f); c.drawText(small, cx, cy + spPx(bigSp) * 0.8f, tp) }
    }
}

/** حلقات متداخلة: الخارجية = الدرجة الإجمالية، والداخلية = RAM / تخزين / بطارية. كل طبقة نسبة + لون. */
class MultiLayerRing(ctx: Context) : View(ctx) {
    class Layer(var frac: Float, val color: Int)
    var layers: List<Layer> = emptyList()
    var track = 0; var textColor = 0; var mutedColor = 0
    var strokePx = 14f; var gapPx = 6f; var bigSp = 34f
    var big = ""; var small = ""
    private var from = FloatArray(0); private var shown = FloatArray(0)
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun setLayers(l: List<Layer>, animate: Boolean = true) {
        val target = FloatArray(l.size) { l[it].frac.coerceIn(0f, 1f) }
        layers = l; anim?.cancel()
        if (shown.size != target.size) shown = FloatArray(target.size)
        if (!animate || CareMotion.reduced(context) || !isAttachedToWindow) { shown = target; invalidate(); return }
        from = shown.copyOf()
        val maxD = target.indices.maxOfOrNull { abs(target[it] - from[it]) } ?: 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CareMotion.dur(maxD); interpolator = DecelerateInterpolator()
            addUpdateListener { a -> val f = a.animatedValue as Float; for (i in target.indices) shown[i] = from[i] + (target[i] - from[i]) * f; invalidate() }; start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        p.strokeWidth = strokePx
        for (i in layers.indices) {
            val inset = strokePx / 2f + i * (strokePx + gapPx)
            val rad = s / 2f - inset
            if (rad <= strokePx) break
            val r = RectF(cx - rad, cy - rad, cx + rad, cy + rad)
            p.color = track; c.drawArc(r, 0f, 360f, false, p)
            val f = shown.getOrElse(i) { 0f }
            if (f > 0.004f) { p.color = layers[i].color; c.drawArc(r, -90f, 360f * f, false, p) }
        }
        tp.color = textColor; tp.textSize = spPx(bigSp)
        val off = if (small.isEmpty()) -(tp.ascent() + tp.descent()) / 2f else -spPx(2f)
        c.drawText(big, cx, cy + off, tp)
        if (small.isNotEmpty()) { tp.color = mutedColor; tp.textSize = spPx(11f); c.drawText(small, cx, cy + spPx(bigSp) * 0.75f, tp) }
    }
}

/** مدار الفحص: قوس بيلف وقت الفحص (من غير حركة لو الأنيميشن مقفول) ثم حلقة كاملة عند الانتهاء. */
class ScanOrbit(ctx: Context) : View(ctx) {
    var track = 0; var color = 0; var textColor = 0
    var strokePx = 12f; var label = ""
    private var angle = 0f
    private var running = false
    private var done = false
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }

    fun start() {
        running = true; done = false; anim?.cancel()
        if (CareMotion.reduced(context)) { invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1400L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }; start()
        }
    }
    fun finish(ok: Boolean = true) { running = false; done = ok; anim?.cancel(); invalidate() }
    fun reset() { running = false; done = false; anim?.cancel(); invalidate() }

    override fun onDetachedFromWindow() { anim?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val l = (width - s) / 2f + strokePx / 2f; val t = (height - s) / 2f + strokePx / 2f
        val r = RectF(l, t, l + s - strokePx, t + s - strokePx)
        p.strokeWidth = strokePx
        p.color = track; c.drawArc(r, 0f, 360f, false, p)
        p.color = color
        if (done) c.drawArc(r, 0f, 360f, false, p)
        else if (running) c.drawArc(r, if (CareMotion.reduced(context)) -90f else angle - 90f, 100f, false, p)
        tp.color = textColor; tp.textSize = spPx(14f)
        c.drawText(label, width / 2f, height / 2f - (tp.ascent() + tp.descent()) / 2f, tp)
    }
}
