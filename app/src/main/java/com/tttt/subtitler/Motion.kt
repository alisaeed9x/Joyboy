package com.tttt.subtitler

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
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
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * (v312) أنيميشنز مركزية خفيفة: كلها قصيرة (≤ 300ms) وبتتبع «مقياس مدة الأنيميشن» بتاع النظام
 * (لو المستخدم قفله من خيارات المطوّر بتتنفّذ فورًا من غير حركة).
 */
object Motion {
    private fun dp(v: View, n: Float): Float = n * v.resources.displayMetrics.density
    private fun ease(def: android.view.animation.Interpolator): android.view.animation.Interpolator = if (MtGlass.on()) MtGlass.EMPHASIZED_DECELERATE else def

    /** (v445) ظهور القوايم المنسدلة: فيد + تكبير خفيف + انزلاق ناحية الزرار */
    fun popIn(v: View, up: Boolean) {
        v.animate().cancel()
        if (MtGlass.on()) {   // (v893)
            v.alpha = 0f; v.scaleX = 0.94f; v.scaleY = 0.94f; v.translationY = dp(v, if (up) 12f else -12f)
            v.animate().setStartDelay(0L).alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(Skins.dur(MtGlass.ENTER_MS)).setInterpolator(MtGlass.EMPHASIZED_DECELERATE).start()
            return
        }
        v.alpha = 0f; v.scaleX = 0.94f; v.scaleY = 0.94f; v.translationY = dp(v, if (up) 12f else -12f)
        v.animate().setStartDelay(0L).alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(Skins.dur(220L)).setInterpolator(DecelerateInterpolator(1.7f)).start()
    }

    /** نطّة: العنصر بيكبر من حجم أصغر بارتداد خفيف (اختيار chip، ظهور زرار عائم، تبديل سويتش) */
    fun pop(v: View, from: Float = 0.8f) {
        if (MtGlass.on()) { MtGlass.pop(v, minOf(from, 0.97f), 1f + (1f - from) * 0.5f); return }   // (v893) دفعة MeeTube
        v.animate().cancel()
        v.scaleX = from; v.scaleY = from
        v.animate().setStartDelay(0L).scaleX(1f).scaleY(1f).setDuration(Skins.dur(280L)).setInterpolator(Skins.interp(2.6f)).start()
    }

    private val springs = WeakHashMap<View, HashMap<DynamicAnimation.ViewProperty, SpringAnimation>>()

    /** (v1247) سبرينج فيزيائي (androidx.dynamicanimation الموجودة أصلًا في المشروع): بيبدأ بسرعة الحركة الحالية فمفيش قفزة سرعة لو الحركة اتقطعت، وبيستقر ناعم (damping قريب من الحرج: من غير ارتداد ظاهر). لو الحركات مقفولة من النظام بيحط القيمة فورًا */
    fun spring(v: View, prop: DynamicAnimation.ViewProperty, to: Float, stiffness: Float = 380f, damping: Float = 0.9f) {
        try {
            if (Skins.dur(100L) <= 0L) { cancelSprings(v); prop.setValue(v, to); return }
            val m = springs.getOrPut(v) { HashMap() }
            val s = m.getOrPut(prop) { SpringAnimation(v, prop).apply { spring = SpringForce(to) } }
            s.spring.setFinalPosition(to).setStiffness(stiffness).setDampingRatio(damping)
            s.start()
        } catch (e: Throwable) { try { prop.setValue(v, to) } catch (_: Throwable) {} }
    }
    fun cancelSprings(v: View) { try { springs[v]?.values?.forEach { if (it.isRunning) it.cancel() } } catch (_: Throwable) {} }

    /** (v1203) استقرار الأيقونة لما تتفعّل: من أصغر شوية ومنزّلة 3dp لمكانها في اتجاه واحد (expo-out) — من غير قفزة لفوق ورجوع */
    fun bounce(v: View) {
        v.animate().cancel(); cancelSprings(v)
        v.scaleX = 0.86f; v.scaleY = 0.86f; v.translationY = dp(v, 3f)
        spring(v, DynamicAnimation.SCALE_X, 1f, 420f, 0.88f); spring(v, DynamicAnimation.SCALE_Y, 1f, 420f, 0.88f); spring(v, DynamicAnimation.TRANSLATION_Y, 0f, 420f, 0.9f)   // (v1247) سبرينج بدل expo-out
    }

    private val blurring = WeakHashMap<View, Animator>()

    /** (v1203) بلور بيصفى مع الدخول (أندرويد 12+ وموبايل/تابلت بس؛ غير كده مفيش أثر). بيتشال في الآخر فمفيش تكلفة بعد كده. */
    fun blurIn(v: View, dur: Long = 360L, delay: Long = 0L, radiusDp: Float = 7f) {
        if (Build.VERSION.SDK_INT < 31 || !MtGlass.on()) return
        blurring.remove(v)?.cancel()
        val r0 = radiusDp * v.resources.displayMetrics.density
        val a = ValueAnimator.ofFloat(r0, 0f)
        a.startDelay = delay; a.duration = Skins.dur(dur); a.interpolator = MtGlass.EXPO_OUT
        a.addUpdateListener { x ->
            val r = x.animatedValue as Float
            try { v.setRenderEffect(if (r < 0.5f) null else RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)) } catch (_: Throwable) {}
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try { v.setRenderEffect(null) } catch (_: Throwable) {}
                if (blurring[v] === animation) blurring.remove(v)
            }
        })
        blurring[v] = a
        a.start()
    }

    /** دخول بتلاشي + انزلاق من اتجاه */
    fun fadeSlideIn(v: View, dx: Float = 0f, dy: Float = 0f, dur: Long = 320L, delay: Long = 0L, blur: Boolean = false) {
        v.animate().cancel()
        v.alpha = 0f; v.translationX = dx; v.translationY = dy
        v.animate().setStartDelay(delay).alpha(1f).translationX(0f).translationY(0f).setDuration(Skins.dur(dur)).setInterpolator(MtGlass.EXPO_OUT).start()   // (v1203) expo-out
        if (blur) blurIn(v, dur, delay)
    }

    /** انزلاق أفقي من غير تلاشي (للقوايم والـ WebView: التلاشي على الـ WebView بيعمل وميض) */
    fun slideIn(v: View, side: Int, dur: Long = 300L) {
        v.animate().cancel()
        v.translationX = side * dp(v, 40f)
        v.animate().setStartDelay(0L).translationX(0f).setDuration(Skins.dur(dur)).setInterpolator(MtGlass.EXPO_OUT).start()
    }

    /** أول N عناصر في الحاوية بتدخل ورا بعض */
    fun stagger(group: ViewGroup, max: Int = 10, step: Long = 55L) {
        // (v1203) كل عنصر: يطلع من blur لوضوح + ارتفاع بسيط (14dp)، بتأخير متساوي بين العناصر — البلور لأول 5 بس عشان الأداء
        val d = group.resources.displayMetrics.density
        val n = minOf(group.childCount, max)
        for (i in 0 until n) fadeSlideIn(group.getChildAt(i), 0f, 14f * d, 360L, i * step, blur = i < 5)
    }

    /** تبديل تبويب: الصفحة الجديدة بتنزلق من جنب الاتجاه (side = -1 من الشمال، 1 من اليمين) */
    fun tabEnter(v: View, side: Int) {
        if (MtGlass.on()) { MtGlass.frostIn(v, side * dp(v, 14f)); return }   // (v893) الصفحة بتطلع من زجاج مثلّج (frostIn) بدل الانزلاق
        // (v621) طبقة هاردوير طول الأنيميشن: الصفحة بتتحرك كـ texture واحدة بدل ما الشجرة كلها تتعاد رسمها مع كل فريم (كان ده سبب التهنيج)
        v.animate().cancel()
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.alpha = 0f; v.translationX = side * dp(v, 28f); v.translationY = 0f
        v.animate().setStartDelay(0L).alpha(1f).translationX(0f).setDuration(Skins.dur(280L)).setInterpolator(MtGlass.EXPO_OUT)
            .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
    }

    /** (v621) الصفحة اللي خرجت: بتتلاشى وتنزلق شوية في عكس اتجاه الدخول وبعدين تتخفي. stay() = لسه المفروض تبقى ظاهرة؟ */
    fun tabExit(v: View, side: Int, stay: () -> Boolean) {
        v.animate().cancel()
        v.visibility = View.VISIBLE
        if (MtGlass.on()) {   // (v893) خروج سريع: فيد 90ms
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            v.animate().setStartDelay(0L).alpha(0f).translationX(side * dp(v, 8f)).setDuration(Skins.dur(MtGlass.FADE_OUT_MS)).setInterpolator(MtGlass.STANDARD)
                .withEndAction {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                    v.alpha = 1f
                    v.translationX = 0f
                    // (v940) INVISIBLE: الصفحة تفضل متقاسة وجاهزة
                    if (!stay()) v.visibility = View.INVISIBLE
                }.start()
            return
        }
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        v.animate().setStartDelay(0L).alpha(0f).translationX(side * dp(v, 18f)).setDuration(Skins.dur(170L)).setInterpolator(ease(DecelerateInterpolator(1.2f)))
            .withEndAction {
                v.setLayerType(View.LAYER_TYPE_NONE, null); v.alpha = 1f; v.translationX = 0f
                if (!stay()) v.visibility = View.INVISIBLE   // (v940) INVISIBLE: الصفحة تفضل متقاسة وجاهزة
            }.start()
    }

    /** قوايم ListView: كل صف بيدخل بتأخير بسيط عن اللي قبله (بيشتغل مرة عند الجدولة بس، مش مع كل سكرول) */
    fun listAnim(lv: ViewGroup) {
        val set = AnimationSet(true)
        set.addAnimation(AlphaAnimation(0f, 1f))
        set.addAnimation(TranslateAnimation(Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0f, Animation.RELATIVE_TO_SELF, 0.35f, Animation.RELATIVE_TO_SELF, 0f))
        set.duration = Skins.dur(320L)
        set.interpolator = MtGlass.EXPO_OUT   // (v1203)
        lv.layoutAnimation = LayoutAnimationController(set, 0.1f)
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
        val a = ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, if (Design.isNew()) 1.04f else 1.06f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, if (Design.isNew()) 1.04f else 1.06f))
        a.duration = if (Design.isNew()) 1200L else 900L; a.repeatCount = ValueAnimator.INFINITE; a.repeatMode = ValueAnimator.REVERSE; a.interpolator = AccelerateDecelerateInterpolator()
        breathing[v] = a
        a.start()
    }
}
