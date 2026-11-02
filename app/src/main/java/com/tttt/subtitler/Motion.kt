package com.tttt.subtitler

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.LayoutAnimationController
import android.view.animation.OvershootInterpolator
import android.view.animation.TranslateAnimation
import java.util.WeakHashMap

/**
 * (v312) أنيميشنز مركزية خفيفة: كلها قصيرة (≤ 300ms) وبتتبع «مقياس مدة الأنيميشن» بتاع النظام
 * (لو المستخدم قفله من خيارات المطوّر بتتنفّذ فورًا من غير حركة).
 */
object Motion {
    private fun dp(v: View, n: Float): Float = n * v.resources.displayMetrics.density

    /** (v445) ظهور القوايم المنسدلة: فيد + تكبير خفيف + انزلاق ناحية الزرار */
    fun popIn(v: View, up: Boolean) {
        v.animate().cancel()
        v.alpha = 0f; v.scaleX = 0.94f; v.scaleY = 0.94f; v.translationY = dp(v, if (up) 12f else -12f)
        v.animate().setStartDelay(0L).alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(Skins.dur(220L)).setInterpolator(DecelerateInterpolator(1.7f)).start()
    }

    /** نطّة: العنصر بيكبر من حجم أصغر بارتداد خفيف (اختيار chip، ظهور زرار عائم، تبديل سويتش) */
    fun pop(v: View, from: Float = 0.8f) {
        v.animate().cancel()
        v.scaleX = from; v.scaleY = from
        v.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).setDuration(Skins.dur(280L)).setInterpolator(Skins.interp(2.6f)).start()
    }

    /** قفزة صغيرة لفوق وبعدين استقرار (أيقونة الشريط السفلي لما تتفعّل) */
    fun bounce(v: View) {
        v.animate().cancel()
        v.animate().setStartDelay(0L).translationY(-dp(v, 3f)).scaleX(if (Skins.cur().overshoot <= 0f) 1.08f else 1.2f).scaleY(if (Skins.cur().overshoot <= 0f) 1.08f else 1.2f).setDuration(Skins.dur(110L)).setInterpolator(DecelerateInterpolator())
            .withEndAction {
                v.animate().setStartDelay(0L).translationY(0f).scaleX(1f).scaleY(1f).setDuration(Skins.dur(280L)).setInterpolator(Skins.interp(3f)).start()
            }.start()
    }

    /** دخول بتلاشي + انزلاق من اتجاه */
    fun fadeSlideIn(v: View, dx: Float = 0f, dy: Float = 0f, dur: Long = 240L, delay: Long = 0L) {
        v.animate().cancel()
        v.alpha = 0f; v.translationX = dx; v.translationY = dy
        v.animate().setStartDelay(delay).alpha(1f).translationX(0f).translationY(0f).setDuration(Skins.dur(dur)).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** انزلاق أفقي من غير تلاشي (للقوايم والـ WebView: التلاشي على الـ WebView بيعمل وميض) */
    fun slideIn(v: View, side: Int, dur: Long = 240L) {
        v.animate().cancel()
        v.translationX = side * dp(v, 40f)
        v.animate().setStartDelay(0L).translationX(0f).setDuration(Skins.dur(dur)).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    /** أول N عناصر في الحاوية بتدخل ورا بعض */
    fun stagger(group: ViewGroup, max: Int = 10, step: Long = 40L) {
        val d = group.resources.displayMetrics.density
        val n = minOf(group.childCount, max)
        for (i in 0 until n) fadeSlideIn(group.getChildAt(i), 0f, 16f * d, 240L, i * step)
    }

    /** تبديل تبويب: الصفحة الجديدة بتنزلق من جنب الاتجاه (side = -1 من الشمال، 1 من اليمين) */
    fun tabEnter(v: View, side: Int) {
        // (v621) طبقة هاردوير طول الأنيميشن: الصفحة بتتحرك كـ texture واحدة بدل ما الشجرة كلها تتعاد رسمها مع كل فريم (كان ده سبب التهنيج)
        v.animate().cancel()
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.alpha = 0f; v.translationX = side * dp(v, 28f); v.translationY = 0f
        v.animate().setStartDelay(0L).alpha(1f).translationX(0f).setDuration(Skins.dur(230L)).setInterpolator(DecelerateInterpolator(1.8f))
            .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
    }

    /** (v621) الصفحة اللي خرجت: بتتلاشى وتنزلق شوية في عكس اتجاه الدخول وبعدين تتخفي. stay() = لسه المفروض تبقى ظاهرة؟ */
    fun tabExit(v: View, side: Int, stay: () -> Boolean) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.animate().setStartDelay(0L).alpha(0f).translationX(side * dp(v, 18f)).setDuration(Skins.dur(170L)).setInterpolator(DecelerateInterpolator(1.2f))
            .withEndAction {
                v.setLayerType(View.LAYER_TYPE_NONE, null); v.alpha = 1f; v.translationX = 0f
                if (!stay()) v.visibility = View.GONE
            }.start()
    }

    /** قوايم ListView: كل صف بيدخل بتأخير بسيط عن اللي قبله (بيشتغل مرة عند الجدولة بس، مش مع كل سكرول) */
    fun listAnim(lv: ViewGroup) {
        val set = AnimationSet(true)
        set.addAnimation(AlphaAnimation(0f, 1f))
        set.addAnimation(TranslateAnimation(Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0.35f, Animation.RELATIVE_TO_SELF, 0f))
        set.duration = Skins.dur(240L)
        set.interpolator = DecelerateInterpolator(1.4f)
        lv.layoutAnimation = LayoutAnimationController(set, 0.08f)
    }

    fun replay(lv: ViewGroup) { lv.scheduleLayoutAnimation() }

    private val breathing = WeakHashMap<View, ObjectAnimator>()

    /** تنفّس: نبض بطيء مستمر لحد ما يتقفل (زرار المهام وفيه مهام شغّالة) */
    fun breathe(v: View, on: Boolean) {
        val cur = breathing[v]
        if (!on) {
            if (cur != null) { cur.cancel(); breathing.remove(v); v.scaleX = 1f; v.scaleY = 1f }
            return
        }
        if (cur != null) return
        val a = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f))
        a.duration = 900L; a.repeatCount = ValueAnimator.INFINITE; a.repeatMode = ValueAnimator.REVERSE; a.interpolator = AccelerateDecelerateInterpolator()
        breathing[v] = a
        a.start()
    }
}
