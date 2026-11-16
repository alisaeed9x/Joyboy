package com.tttt.subtitler

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * (v397) شكل يوتيوب الأصلي جوه تبويب يوتيوب: ألوان (داكن/فاتح حسب ثيم التطبيق) + أيقونات مرسومة بالكود
 * (بتتبدّل بين مفرّغة ومصمتة زي شريط يوتيوب السفلي) + شعار + ريبل.
 */
object YtSkin {
    var dark = true
    var bg = 0xFF0F0F0F.toInt()
    /** (v516) لون الخلفية الصلب (للتفاصيل اللي بتتفرّغ في الأيقونات) — bg نفسه ممكن يبقى شفاف جزئيًا فوق خلفية اللانشر */
    var bgSolid = 0xFF0F0F0F.toInt()
    /** (v516) يوتيوب جوه قشرة اللانشر: الخلفية شبه شفافة عشان خلفية اللانشر تبان من تحتها */
    var translucent = false
    var surface = 0xFF272727.toInt()
    var text = 0xFFF1F1F1.toInt()
    var sub = 0xFFAAAAAA.toInt()
    var chip = 0xFF272727.toInt()
    var chipOnBg = 0xFFF1F1F1.toInt()
    var chipOnText = 0xFF0F0F0F.toInt()
    var line = 0x1FFFFFFF
    val red = 0xFFFF0000.toInt()   // شعار يوتيوب بس
    /** (v471) لون التمييز = لون سمة البرنامج (خط التحميل · شريط التقدم · الكبسولة · الأزرار المختارة) */
    var accent = 0xFF3EA6FF.toInt()
    var onAccent = Color.WHITE
    /** (v471) خط العناوين = نفس خط البرنامج (IBM Plex) لو اتحمّل، وإلا الخط المتوسط بتاع النظام */
    var medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var fontTried = false

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    /** (v471) الألوان كلها من سمة البرنامج الحالية (قبل كده كانت ألوان يوتيوب الثابتة وبس بتتبدّل بين داكن وفاتح) */
    fun init(th: Theme, ctx: Context? = null) {
        dark = !th.isLight
        bgSolid = th.bg
        bg = if (translucent) (th.bg and 0x00FFFFFF) or 0xB8000000.toInt() else th.bg
        text = th.text; sub = th.muted; line = th.border
        // سطح الكروت/الشرايح لازم يبان فوق الخلفية (في أموليد مثلًا السطح أسود زي الخلفية)
        val s = listOf(th.surface, th.card).firstOrNull { Math.abs(Theme.lum(it) - Theme.lum(th.bg)) >= 0.03 } ?: mix(th.bg, th.border, 0.55f)
        surface = s; chip = s
        accent = th.primary
        onAccent = if (Theme.lum(th.primary) > 0.6) 0xFF111111.toInt() else Color.WHITE
        chipOnBg = th.primary; chipOnText = onAccent
        if (ctx != null && !fontTried) {
            fontTried = true
            try { medium = Typeface.createFromAsset(ctx.applicationContext.assets, "fonts/ibm_plex.ttf") } catch (_: Throwable) {}
        }
    }

    /** ريبل رمادي شفاف (بيتحط على foreground أي View) */
    fun ripple(oval: Boolean = false): RippleDrawable {
        val mask: android.graphics.drawable.Drawable =
            if (oval) android.graphics.drawable.GradientDrawable().apply { setShape(android.graphics.drawable.GradientDrawable.OVAL); setColor(Color.BLACK) }
            else ColorDrawable(Color.BLACK)
        return RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000), null, mask)
    }

    /** لون دايرة الأفاتار الافتراضي من اسم القناة */
    fun avatarColor(name: String): Int {
        val pal = intArrayOf(0xFF5C6BC0.toInt(), 0xFF26A69A.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(), 0xFF00838F.toInt(),
            0xFFC2185B.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt(), 0xFF546E7A.toInt(), 0xFFD84315.toInt())
        return pal[(name.hashCode() and 0x7fffffff) % pal.size]
    }
}

/** أيقونات يوتيوب (شبكة 24×24). filled = مصمتة (التبويب المختار). cut = لون خلفية الشريط للتفاصيل اللي بتتفرّغ. */
class YtIcon(ctx: Context, private val kind: String) : View(ctx) {
    var color: Int = YtSkin.text
        set(v) { field = v; invalidate() }
    var filled: Boolean = false
        set(v) { field = v; invalidate() }
    var cut: Int = YtSkin.bgSolid
        set(v) { field = v; invalidate() }
    var glyph: Float = 0.72f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sz = minOf(w, h) * glyph
        c.save(); c.translate((w - sz) / 2f, (h - sz) / 2f); val k = sz / 24f; c.scale(k, k)
        p.reset(); p.isAntiAlias = true; p.color = color
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = 1.9f
        p.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        when (kind) {
            "home" -> {
                val a = Path()
                a.moveTo(12f, 3f); a.lineTo(3.2f, 10.6f); a.lineTo(5.2f, 10.6f); a.lineTo(5.2f, 20f); a.lineTo(9.8f, 20f)
                a.lineTo(9.8f, 14.2f); a.lineTo(14.2f, 14.2f); a.lineTo(14.2f, 20f); a.lineTo(18.8f, 20f)
                a.lineTo(18.8f, 10.6f); a.lineTo(20.8f, 10.6f); a.close()
                c.drawPath(a, p)
            }
            "subs" -> {
                p.style = Paint.Style.STROKE
                c.drawLine(6f, 4.4f, 18f, 4.4f, p); c.drawLine(4f, 7.4f, 20f, 7.4f, p)
                p.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
                c.drawRoundRect(RectF(3f, 10.2f, 21f, 20.6f), 2.2f, 2.2f, p)
                val t = Path(); t.moveTo(10.2f, 12.6f); t.lineTo(15.4f, 15.4f); t.lineTo(10.2f, 18.2f); t.close()
                p.style = Paint.Style.FILL; p.color = if (filled) cut else color
                c.drawPath(t, p)
            }
            "you" -> {
                c.drawCircle(12f, 8.2f, 3.7f, p)
                val b = Path()
                b.moveTo(4.6f, 20.2f); b.cubicTo(4.6f, 15.8f, 8.1f, 13.7f, 12f, 13.7f)
                b.cubicTo(15.9f, 13.7f, 19.4f, 15.8f, 19.4f, 20.2f); b.close()
                c.drawPath(b, p)
            }
            "search" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.1f
                c.drawCircle(10.2f, 10.2f, 6.1f, p); c.drawLine(14.8f, 14.8f, 20.4f, 20.4f, p)
            }
            "bell" -> {
                p.style = Paint.Style.STROKE
                val b = Path()
                b.moveTo(12f, 3.4f); b.cubicTo(8.6f, 3.4f, 6.6f, 6f, 6.6f, 9.2f); b.lineTo(6.6f, 13f); b.lineTo(4.6f, 16.2f)
                b.lineTo(19.4f, 16.2f); b.lineTo(17.4f, 13f); b.lineTo(17.4f, 9.2f); b.cubicTo(17.4f, 6f, 15.4f, 3.4f, 12f, 3.4f); b.close()
                c.drawPath(b, p)
                val d = Path(); d.moveTo(9.8f, 19.2f); d.cubicTo(10.3f, 20.8f, 13.7f, 20.8f, 14.2f, 19.2f)
                c.drawPath(d, p)
            }
            "back" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                val a = Path(); a.moveTo(20f, 12f); a.lineTo(4.6f, 12f); a.moveTo(11f, 5.6f); a.lineTo(4.6f, 12f); a.lineTo(11f, 18.4f)
                c.drawPath(a, p)
            }
            "close" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                c.drawLine(6f, 6f, 18f, 18f, p); c.drawLine(18f, 6f, 6f, 18f, p)
            }
            "more" -> {
                p.style = Paint.Style.FILL
                c.drawCircle(12f, 5f, 1.9f, p); c.drawCircle(12f, 12f, 1.9f, p); c.drawCircle(12f, 19f, 1.9f, p)
            }
            "mic" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.9f
                c.drawRoundRect(RectF(8.8f, 3f, 15.2f, 13.6f), 3.2f, 3.2f, p)
                val a = Path(); a.moveTo(5.8f, 11f); a.cubicTo(5.8f, 15f, 8.6f, 17.4f, 12f, 17.4f); a.cubicTo(15.4f, 17.4f, 18.2f, 15f, 18.2f, 11f)
                c.drawPath(a, p); c.drawLine(12f, 17.4f, 12f, 21f, p)
            }
            "gear" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.9f
                c.drawCircle(12f, 12f, 3.2f, p)
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f, 12f, 12f); c.drawLine(12f, 2.8f, 12f, 5.4f, p); c.restore()
                }
                c.drawCircle(12f, 12f, 6.9f, p)
            }
        }
        c.restore()
    }
}
