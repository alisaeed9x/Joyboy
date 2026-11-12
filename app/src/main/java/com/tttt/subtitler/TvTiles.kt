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
    @Volatile private var haloRoomNow: IntArray? = null
    private fun focusBg(): Drawable {
        val ringW = when (focusStyle) { TvFocusStyle.GLOW, TvFocusStyle.LIFT -> 2.5f; else -> 3f }
        val ringPx = (ringW * d).toInt().coerceAtLeast(1)
        val ring = TvGlow.inside(edge(ringW, 0xFFFFFFFF.toInt(), 0x00000000, 5), ringPx)   // (v696) الحلقة كلها جوه الحدود — مابتتقصّش
        if (!TvUserPrefs.halo()) return ring
        return TvGlow.under(ring, glowNow(), radiusDp + 5, d, 5, haloRoomNow)
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
        val k0 = TvUserPrefs.liftK()
        val sc00 = when (focusStyle) { TvFocusStyle.RING -> 1.07f; TvFocusStyle.UNDERLINE -> 1.03f; else -> focusScale }
        val scFit = if (f) TvGlow.fit(this, 1f + (sc00 - 1f) * k0) else 1f   // (v696) التكبير ما يعدّيش المساحة المتاحة (حافة الشاشة/الأب)
        haloRoomNow = if (f) TvGlow.haloRoom(this, scFit) else null
        background = if (f) focusBg() else null
        TvGlow.refresh(this)
        // (v633) «طلوع وتكبير المتركّز» (tv_lift) من إعدادات المظهر بتتطبّق على كل السكنات: 0 = بدون · 1 = خفيف · 2 = عادي · 3 = عالي
        val k = TvUserPrefs.liftK()
        val sc0 = when (focusStyle) { TvFocusStyle.RING -> 1.07f; TvFocusStyle.UNDERLINE -> 1.03f; else -> focusScale }
        val sc = if (f) scFit else 1f + (sc0 - 1f) * k
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
    /**
     * (v696) مساحة الرسم المتاحة حوالين [v] (بالبكسل: يسار · فوق · يمين · تحت) قبل ما أي أب يقصّه أو تقف عند حافة الشاشة.
     * السبب القديم لحلقة التركيز «المقطوعة من الأطراف»: الهالة (حتى ~29dp) والحلقة (نص سمكها برّه) والتكبير بيترسموا برّه حدود العنصر،
     * وأي أب clipChildren/clipToPadding أو حافة الشاشة (آخر عنصر في الصف/الشبكة) بيقصّهم.
     */
    fun room(v: View): IntArray {
        val big = 1 shl 20
        val r = intArrayOf(big, big, big, big)
        if (v.width <= 0 || v.height <= 0) return r   // لسه ما اتعملش له layout
        try {
            val a = IntArray(2); v.getLocationOnScreen(a)
            fun lim(cl: Int, ct: Int, cr: Int, cb: Int, l: Int, t: Int, rr: Int, b: Int) {
                r[0] = minOf(r[0], l - cl); r[1] = minOf(r[1], t - ct); r[2] = minOf(r[2], cr - rr); r[3] = minOf(r[3], cb - b)
            }
            val vl = a[0]; val vt = a[1]; val vr = a[0] + v.width; val vb = a[1] + v.height
            var cur: View = v
            var p = v.parent
            val loc = IntArray(2)
            while (p is ViewGroup) {
                val g: ViewGroup = p
                cur.getLocationOnScreen(loc)
                if (g.clipChildren) lim(loc[0], loc[1], loc[0] + cur.width, loc[1] + cur.height, vl, vt, vr, vb)   // الأب بيقصّ ابنه على حدوده
                if (g.clipToPadding && (g.paddingLeft + g.paddingTop + g.paddingRight + g.paddingBottom) > 0) {
                    g.getLocationOnScreen(loc)
                    lim(loc[0] + g.paddingLeft, loc[1] + g.paddingTop, loc[0] + g.width - g.paddingRight, loc[1] + g.height - g.paddingBottom, vl, vt, vr, vb)
                }
                cur = g; p = g.parent
            }
            cur.getLocationOnScreen(loc)
            lim(loc[0], loc[1], loc[0] + cur.width, loc[1] + cur.height, vl, vt, vr, vb)   // حدود النافذة
        } catch (_: Throwable) {}
        for (k in 0..3) r[k] = r[k].coerceAtLeast(0)
        return r
    }

    /** أكبر تكبير مسموح من غير ما العنصر يطلع من المساحة المتاحة (بياخد نص المساحة للتكبير والنص التاني للهالة) */
    fun fit(v: View, desired: Float): Float {
        if (desired <= 1f || v.width <= 0 || v.height <= 0) return desired
        val r = room(v)
        val m = minOf(r[0], r[1], r[2], r[3]).toFloat()
        val maxGrow = m / maxOf(v.width, v.height).toFloat()   // نمو كل جنب = (s-1)*size/2 ≤ m/2
        return minOf(desired, 1f + maxGrow).coerceAtLeast(1f)
    }

    /** مساحة الهالة بعد خصم التكبير */
    fun haloRoom(v: View, scale: Float): IntArray {
        val r = room(v)
        val grow = ((scale - 1f) * maxOf(v.width, v.height) / 2f).toInt()
        return IntArray(4) { ((r[it] - grow) / scale).toInt().coerceAtLeast(0) }
    }

    /** حلقة كلها جوه حدود العنصر (السمك مرسوم لجوه مش نصه برّه) فمستحيل تتقصّ */
    fun inside(top: Drawable, strokePx: Int): Drawable {
        val h = (strokePx / 2f + 0.5f).toInt()
        return LayerDrawable(arrayOf(top)).also { it.setLayerInset(0, h, h, h, h) }
    }

    private fun layered(specs: List<Triple<Int, Int, Float>>, rgb: Int, radiusPx: (Int) -> Float, d: Float, room: IntArray?, top: Drawable?): Drawable {
        val layers = ArrayList<Drawable>()
        for ((e, a, sw) in specs) layers.add(GradientDrawable().apply { setColor(0x00000000); setStroke(sw.toInt().coerceAtLeast(1), (a shl 24) or rgb); cornerRadius = radiusPx(e) })
        if (top != null) layers.add(top)
        val ld = LayerDrawable(layers.toTypedArray())
        for ((i, sp) in specs.withIndex()) {
            val o = sp.first * d; val half = sp.third / 2f
            val ins = IntArray(4) { k ->
                val allowed = if (room == null) o else minOf(o, room[k] - half).coerceAtLeast(-half)   // مفيش مساحة برّه = الهالة تترسم جوه (ورا المحتوى)
                (-allowed).toInt()
            }
            ld.setLayerInset(i, ins[0], ins[1], ins[2], ins[3])
        }
        return ld
    }

    /** هالة (حلقات خارج الحدود) تحت drawable الحلقة البيضاء. [room] من haloRoom() — بتقصّر الهالة جنب الحواف */
    fun under(top: Drawable, color: Int, radiusDp: Int, d: Float, innerDp: Int = 5, room: IntArray? = null): Drawable {
        val spec = arrayOf(intArrayOf(18, 0x12), intArrayOf(12, 0x26), intArrayOf(7, 0x48), intArrayOf(3, 0x80))
        return layered(spec.map { Triple(it[0], it[1], (it[0] + innerDp) * d) }, color and 0x00FFFFFF, { e -> (radiusDp + e) * d }, d, room, top)
    }

    /** هالة بس (من غير حلقة) — للعناصر اللي بترسم تركيزها بنفسها (تابات · دواير · أزرار) */
    fun only(color: Int, radiusPx: Float, d: Float, room: IntArray? = null): Drawable {
        val spec = arrayOf(intArrayOf(16, 0x14), intArrayOf(11, 0x2A), intArrayOf(6, 0x4A), intArrayOf(3, 0x80))
        return layered(spec.map { Triple(it[0], it[1], it[0] * d) }, color and 0x00FFFFFF, { e -> radiusPx + e * d }, d, room, null)
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
