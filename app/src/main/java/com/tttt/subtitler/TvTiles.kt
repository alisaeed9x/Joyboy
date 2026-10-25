package com.tttt.subtitler

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
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

    private fun paint(f: Boolean) {
        background = if (f) GradientDrawable().apply {
            setColor(0x00000000); setStroke((3 * d).toInt(), 0xFFFFFFFF.toInt()); cornerRadius = (radiusDp + 5) * d
        } else null
        animate().scaleX(if (f) 1.07f else 1f).scaleY(if (f) 1.07f else 1f).translationZ(if (f) 10 * d else 0f).setInterpolator(android.view.animation.DecelerateInterpolator())
            .setDuration(Skins.dur(130L)).start()
    }
}

/** (v496) أي View بيرسم تركيزه بنفسه (دواير/تابات/أزرار الشريط العلوي) — TvFocus بيسيبه من غير حلقته العامة */
interface TvOwnFocus
class TvOwnLinear(ctx: Context) : android.widget.LinearLayout(ctx), TvOwnFocus
class TvOwnEdit(ctx: Context) : android.widget.EditText(ctx), TvOwnFocus
