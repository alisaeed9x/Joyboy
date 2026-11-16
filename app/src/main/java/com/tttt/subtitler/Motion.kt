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
import android.view.animation.TranslateAnimation
import java.util.WeakHashMap

/**
 * (v312) أنيميشنز مركزية خفيفة: كلها قصيرة (≤ 300ms) وبتتبع «مقياس مدة الأنيميشن» بتاع النظام
 * (لو المستخدم قفله من خيارات المطوّر بتتنفّذ فورًا من غير حركة).
 */
object Motion {
    private fun dp(v: View, n: Float): Float = n * v.resources.displayMetrics.density
    private fun ease(def: android.view.animation.Interpolator): android.view.animation.Interpolator = if (MtGlass.on()) MtGlass.EMPHASIZED_DECELERATE else def

    /** (v445) ظهور القوايم المنسدلة: فيد + تكبير خفيف + انزلاق ناحية الزرار */
    // (v1071) مفيش أنيميشن: الحالة النهائية فورًا
    fun popIn(v: View, up: Boolean) {
        v.animate().cancel()
        v.alpha = 1f
        v.scaleX = 1f
        v.scaleY = 1f
        v.translationY = 0f
    }

    /** نطّة: العنصر بيكبر من حجم أصغر بارتداد خفيف (اختيار chip، ظهور زرار عائم، تبديل سويتش) */
    // (v1071) مفيش أنيميشن: الحجم النهائي على طول
    fun pop(v: View, from: Float = 0.8f) {
        v.animate().cancel()
        v.scaleX = 1f
        v.scaleY = 1f
    }

    /** قفزة صغيرة لفوق وبعدين استقرار (أيقونة الشريط السفلي لما تتفعّل) */
    // (v1071) مفيش أنيميشن: مكانها النهائي على طول
    fun bounce(v: View) {
        v.animate().cancel()
        v.translationY = 0f
        v.scaleX = 1f
        v.scaleY = 1f
    }

    /** دخول بتلاشي + انزلاق من اتجاه */
    // (v1071) مفيش أنيميشن: ظاهرة على طول
    fun fadeSlideIn(v: View, dx: Float = 0f, dy: Float = 0f, dur: Long = 240L, delay: Long = 0L) {
        v.animate().cancel()
        v.alpha = 1f
        v.translationX = 0f
        v.translationY = 0f
    }

    /** انزلاق أفقي من غير تلاشي (للقوايم والـ WebView: التلاشي على الـ WebView بيعمل وميض) */
    // (v1071) مفيش أنيميشن: مكانها النهائي على طول
    fun slideIn(v: View, side: Int, dur: Long = 240L) {
        v.animate().cancel()
        v.translationX = 0f
    }

    /** أول N عناصر في الحاوية بتدخل ورا بعض */
    fun stagger(group: ViewGroup, max: Int = 10, step: Long = 40L) {
        val d = group.resources.displayMetrics.density
        val n = minOf(group.childCount, max)
        for (i in 0 until n) fadeSlideIn(group.getChildAt(i), 0f, 16f * d, 240L, i * step)
    }

    /** تبديل تبويب: الصفحة الجديدة بتنزلق من جنب الاتجاه (side = -1 من الشمال، 1 من اليمين) */
    // (v1071) مفيش أنيميشن: الصفحة الجديدة ظاهرة على طول — من غير انزلاق ولا فروست
    fun tabEnter(v: View, side: Int) {
        v.animate().cancel()
        v.setLayerType(View.LAYER_TYPE_NONE, null)
        v.alpha = 1f
        v.translationX = 0f
        v.translationY = 0f
    }

    /** (v621) الصفحة اللي خرجت: بتتلاشى وتنزلق شوية في عكس اتجاه الدخول وبعدين تتخفي. stay() = لسه المفروض تبقى ظاهرة؟ */
    // (v1071) مفيش أنيميشن: الصفحة القديمة بتتخفي على طول (لو مش المفروض تفضل ظاهرة)
    fun tabExit(v: View, side: Int, stay: () -> Boolean) {
        v.animate().cancel()
        v.setLayerType(View.LAYER_TYPE_NONE, null)
        v.alpha = 1f
        v.translationX = 0f
        // (v940) INVISIBLE: الصفحة تفضل متقاسة وجاهزة
        if (!stay()) v.visibility = View.INVISIBLE
    }

    /** قوايم ListView: كل صف بيدخل بتأخير بسيط عن اللي قبله (بيشتغل مرة عند الجدولة بس، مش مع كل سكرول) */
    // (v1071) مفيش أنيميشن: القوايم بتظهر على طول — من غير دخول متدرّج
    fun listAnim(lv: ViewGroup) {
        lv.layoutAnimation = null
    }

    fun replay(lv: ViewGroup) { }

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
