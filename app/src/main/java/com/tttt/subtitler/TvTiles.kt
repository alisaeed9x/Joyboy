package com.tttt.subtitler

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * (v491 م1) كارت تلفزيون على نمط Google TV / TCL:
 * المحتوى (بانر/صورة) واخد الشكل كله بزوايا مدوّرة، وأول ما ياخد التركيز بيتحاط بحلقة بيضاء
 * بينها وبين الكارت فراغ صغير (padding) + تكبير خفيف. شغّال بس جوه اللانشر (TvActivity).
 * أي View من النوع ده TvFocus بيسيبه لنفسه (مفيش حلقة مزدوجة).
 */
class TvTileView(ctx: Context, private val radiusDp: Int = 14) : FrameLayout(ctx) {
    /** المحتوى المقصوص بالزوايا — حط فيه الصورة/النص */
    val inner = FrameLayout(ctx)
    /** نداء إضافي عند تغيّر التركيز (مثلًا إظهار عنوان تحت الكارت) */
    var onFocus: ((Boolean) -> Unit)? = null
    /** (v602) أسلوب التركيز بتاع السكِن: RING = الأصلي بالظبط (حلقة بيضاء + تكبير 1.07)، والباقي بيتظبط من [styled] */
    var focusStyle: TvFocusStyle = TvFocusStyle.RING
    var focusScale: Float = 1.07f
    var focusMs: Long = 130L
    var accent: Int = 0xFFFFFFFF.toInt()
    /** (v681) لون الهالة خلف المتركّز. 0 = تلقائي: لون الصورة المصغّرة الغالب، وإلا لون التمييز */
    var glowColor: Int = 0
    private val d = ctx.resources.displayMetrics.density

    init {
        isFocusable = true; isClickable = true
        clipChildren = false; clipToPadding = false
        val p = (5 * d).toInt(); setPadding(p, p, p, p)
        inner.clipToOutline = true
        inner.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radiusDp * d) }
        }
        addView(inner, FrameLayout.LayoutParams(-1, -1))
        setOnFocusChangeListener { _, f -> paint(f); onFocus?.invoke(f) }
    }

    private fun edge(strokeDp: Float, color: Int, fill: Int, extraRadius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(fill); setStroke((strokeDp * d).toInt().coerceAtLeast(1), color); cornerRadius = (radiusDp + extraRadius) * d }

    /** (v681) كل الأساليب (RING/GLOW/LIFT/UNDERLINE) بقت بنفس المربع الأبيض + هالة بلون الصورة/التطبيق خلفه — الفرق بين السكنات في التكبير والحركة بس */
    private fun focusBg(): Drawable {
        val ringW = when (focusStyle) { TvFocusStyle.GLOW, TvFocusStyle.LIFT -> 2.5f; else -> 3f }
        val ring = edge(ringW, 0xFFFFFFFF.toInt(), 0x00000000, 5)
        if (!TvUserPrefs.halo()) return ring
        return TvGlow.under(ring, glowNow(), radiusDp + 5, d)
    }

    private fun glowNow(): Int {
        if (glowColor != 0) return glowColor
        val iv = bestImage(inner)
        return (if (iv != null) TvGlow.dominant(iv) else null) ?: accent
    }

    private fun bestImage(root: View): ImageView? {
        var best: ImageView? = null
        var bestArea = 0
        fun walk(x: View) {
            if (x is ImageView && x.drawable != null && x.visibility == View.VISIBLE) {
                val a = x.width * x.height
                if (a >= bestArea) { best = x; bestArea = a }
            }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(root)
        return best
    }

    private fun paint(f: Boolean) {
        background = if (f) focusBg() else null
        TvGlow.refresh(this)
        // (v633) «طلوع وتكبير المتركّز» (tv_lift) من إعدادات المظهر بتتطبّق على كل السكنات: 0 = بدون · 1 = خفيف · 2 = عادي · 3 = عالي
        val k = TvUserPrefs.liftK()
        val sc0 = when (focusStyle) { TvFocusStyle.RING -> 1.07f; TvFocusStyle.UNDERLINE -> 1.03f; else -> focusScale }
        val sc = 1f + (sc0 - 1f) * k
        val z = k * when (focusStyle) { TvFocusStyle.RING -> 10f; TvFocusStyle.GLOW -> 6f; TvFocusStyle.LIFT -> 22f; TvFocusStyle.UNDERLINE -> 4f }
        animate().scaleX(if (f) sc else 1f).scaleY(if (f) sc else 1f).translationZ(if (f) z * d else 0f).setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(Skins.dur(if (focusStyle == TvFocusStyle.RING) 130L else focusMs)).start()
    }
}

/** (v602) طبّق رموز السكِن (أسلوب التركيز · التكبير · المدة) على الكارت. كلاسيك = RING فمفيش تغيير عنده */
fun TvTileView.styled(tk: TvTokens, accent: Int = 0xFFFFFFFF.toInt()): TvTileView {
    focusStyle = tk.focusStyle; focusScale = tk.focusScale; focusMs = tk.focusMs; this.accent = accent
    return this
}

/** (v496) أي View بيرسم تركيزه بنفسه (دواير/تابات/أزرار الشريط العلوي) — TvFocus بيسيبه من غير حلقته العامة */
interface TvOwnFocus
class TvOwnLinear(ctx: Context) : android.widget.LinearLayout(ctx), TvOwnFocus
class TvOwnEdit(ctx: Context) : android.widget.EditText(ctx), TvOwnFocus


/**
 * (v681) هالة التركيز: حلقات شفافة متدرّجة خارج حدود العنصر بلون التطبيق/الصورة — بتخلّي المتركّز «منوّر» وسط الباقي.
 * مرسومة بـstroke بس (من غير تعبئة) فمش بتغطّي ولا بتلوّن محتوى الكارت.
 */
object TvGlow {
    /** هالة (حلقات خارج الحدود) تحت drawable الحلقة البيضاء */
    fun under(top: Drawable, color: Int, radiusDp: Int, d: Float, innerDp: Int = 5): Drawable {
        val layers = ArrayList<Drawable>()
        val spec = arrayOf(intArrayOf(18, 0x12), intArrayOf(12, 0x26), intArrayOf(7, 0x48), intArrayOf(3, 0x80))
        val rgb = color and 0x00FFFFFF
        for (sp in spec) {
            val e = sp[0]
            layers.add(GradientDrawable().apply {
                setColor(0x00000000)
                setStroke(((e + innerDp) * d).toInt(), (sp[1] shl 24) or rgb)
                cornerRadius = (radiusDp + e) * d
            })
        }
        layers.add(top)
        val ld = LayerDrawable(layers.toTypedArray())
        for (i in spec.indices) { val o = (spec[i][0] * d).toInt(); ld.setLayerInset(i, -o, -o, -o, -o) }
        return ld
    }

    /** هالة بس (من غير حلقة) — للعناصر اللي بترسم تركيزها بنفسها (تابات · دواير · أزرار) */
    fun only(color: Int, radiusPx: Float, d: Float): Drawable {
        val layers = ArrayList<Drawable>()
        val spec = arrayOf(intArrayOf(16, 0x14), intArrayOf(11, 0x2A), intArrayOf(6, 0x4A), intArrayOf(3, 0x80))
        val rgb = color and 0x00FFFFFF
        for (sp in spec) {
            val e = sp[0]
            layers.add(GradientDrawable().apply {
                setColor(0x00000000); setStroke((e * d).toInt(), (sp[1] shl 24) or rgb)
                cornerRadius = radiusPx + e * d
            })
        }
        val ld = LayerDrawable(layers.toTypedArray())
        for (i in spec.indices) { val o = (spec[i][0] * d).toInt(); ld.setLayerInset(i, -o, -o, -o, -o) }
        return ld
    }

    /** الهالة بتترسم برا حدود الـView — لازم الأب يتعاد رسمه وإلا بتفضل بقايا بعد ما التركيز يمشي */
    fun refresh(v: View) {
        try {
            var p: android.view.ViewParent? = v.parent
            var n = 0
            while (p != null && n < 3) { (p as? View)?.invalidate(); p = p.parent; n++ }
            v.invalidate()
        } catch (_: Throwable) {}
    }

    /** اللون الغالب في صورة (بيتحسب على نسخة 12×12) مع رفع التشبّع والسطوع عشان الهالة تبان كإنها بتنوّر. null = ماقدرناش */
    fun dominant(iv: ImageView): Int? {
        try {
            val dr = iv.drawable ?: return null
            val bmp = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val src = (dr as? android.graphics.drawable.BitmapDrawable)?.bitmap
            if (src != null && !src.isRecycled) {
                c.drawBitmap(src, null, Rect(0, 0, 12, 12), Paint(Paint.FILTER_BITMAP_FLAG))
            } else {
                val cp = dr.constantState?.newDrawable()?.mutate() ?: return null
                cp.setBounds(0, 0, 12, 12); cp.draw(c)
            }
            var sr = 0.0; var sg = 0.0; var sb = 0.0; var sw = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until 12) for (x in 0 until 12) {
                val px = bmp.getPixel(x, y)
                Color.colorToHSV(px, hsv)
                val w = (hsv[1] * hsv[2] + 0.02f).toDouble()   // الألوان الزاهية ليها وزن أعلى من الرمادي
                sr += Color.red(px) * w; sg += Color.green(px) * w; sb += Color.blue(px) * w; sw += w
            }
            bmp.recycle()
            if (sw <= 0.0) return null
            Color.colorToHSV(Color.rgb((sr / sw).toInt(), (sg / sw).toInt(), (sb / sw).toInt()), hsv)
            if (hsv[1] < 0.10f) return 0xFFEAF0FF.toInt()                 // صورة رمادية/سودا: هالة بيضاء ناعمة
            hsv[1] = (hsv[1] * 1.5f).coerceIn(0.6f, 1f)                  // أحمر رمادي ← أحمر أعلى درجة
            hsv[2] = 1f
            return Color.HSVToColor(hsv)
        } catch (_: Throwable) { return null }
    }
}
