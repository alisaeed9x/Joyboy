package com.tttt.subtitler

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

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

    private fun focusBg(): Drawable? = when (focusStyle) {
        TvFocusStyle.RING -> edge(3f, 0xFFFFFFFF.toInt(), 0x00000000, 5)
        // وهج ناعم: هالة شفافة عريضة + حد أبيض رفيع (Glass)
        TvFocusStyle.GLOW -> LayerDrawable(arrayOf(edge(5f, 0x44FFFFFF, 0x1FFFFFFF, 6), edge(2.5f, 0xFFFFFFFF.toInt(), 0x00000000, 5)))
        // طلوع: حد أبيض رفيع + ظل عالي (Hub)
        TvFocusStyle.LIFT -> edge(2f, 0xDDFFFFFF.toInt(), 0x00000000, 5)
        // خط تحت الكارت بلون التمييز
        TvFocusStyle.UNDERLINE -> LayerDrawable(arrayOf(GradientDrawable().apply { setColor(accent); cornerRadius = 2 * d })).also { ld ->
            val pad = (5 * d).toInt(); val bar = (4 * d).toInt()
            ld.setLayerInset(0, pad + (radiusDp * d * 0.5f).toInt(), (height - pad - bar).coerceAtLeast(0), pad + (radiusDp * d * 0.5f).toInt(), pad)
        }
    }

    private fun paint(f: Boolean) {
        background = if (f) focusBg() else null
        val sc = when (focusStyle) { TvFocusStyle.RING -> 1.07f; TvFocusStyle.UNDERLINE -> 1.03f; else -> focusScale }
        val z = when (focusStyle) { TvFocusStyle.RING -> 10f; TvFocusStyle.GLOW -> 6f; TvFocusStyle.LIFT -> 22f; TvFocusStyle.UNDERLINE -> 4f }
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
