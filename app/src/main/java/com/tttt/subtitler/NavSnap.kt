package com.tttt.subtitler

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import java.lang.ref.WeakReference

/**
 * (v923) الحل الكبير لانتقالات الشاشات: كل تبويب (ميديا · يوتيوب · متصفح · ملفات · مهام) أكتيفيتي منفصلة، فالقديمة بتتقفل والجديدة بتتبني
 * ببطء → ظهور مفاجئ واختفاء مفاجئ. دلوقتي:
 *  1) لحظة ما الشاشة القديمة تقف (onActivityPaused) وفي انتقال تبويب (NavAnim.set بيسلّح) بناخد لقطة مصغّرة منها.
 *  2) الشاشة الجديدة (أي أكتيفيتي في البرنامج، MeeTube أو Joyboy) أول ما تبدأ بتحط اللقطة فوقها — فأول فريم هو نفس الشاشة القديمة بالظبط.
 *  3) بعد ما الشاشة الجديدة ترسم أول فريمات (+ مهلة قصيرة) اللقطة بتتلاشى بحركة frostIn (فيد + تكبير خفيف) — نفس إحساس تبويبات MeeTube.
 * اللقطة بتتمسح لوحدها لو الانتقال ماتمّش في ثانيتين، ومابتاخدش لمس. ما بتشتغلش لو الحركات مقفولة من إعدادات النظام.
 */
object NavSnap : Application.ActivityLifecycleCallbacks {
    private var armedUntil = 0L
    private var shot: Bitmap? = null
    private var shotAt = 0L
    private var shotBarPx = 0      // (v1096) ارتفاع الشريط السفلي (بكسل كامل) في الشاشة القديمة وقت اللقطة
    private var shotDecorH = 0
    private var from: WeakReference<Activity>? = null
    private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** بيتنده من NavAnim.set لما انتقال تبويب يبدأ — اللقطة بتتاخد في الثانيتين الجايين بس */
    private var cur: WeakReference<Activity>? = null

    /**
     * (v927) اللقطة بقت بـPixelCopy (نسخ GPU غير متزامن، تلت المقاس) وبتتاخد هنا — قبل ما الشاشة الجديدة تبدأ — بدل decor.draw() المتزامن
     * اللي كان بيتنفّذ على الـUI thread في نص الانتقال (ده اللي كان بيهنّج الدوسة). أندرويد أقدم من 8 بيرجع للطريقة القديمة في onActivityPaused.
     */
    fun arm() {
        armedUntil = SystemClock.uptimeMillis() + 2000L
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val a = cur?.get() ?: return
            val w = a.window ?: return
            val decor = w.decorView
            if (decor.width <= 0 || decor.height <= 0) return
            val k = 0.33f
            shotBarPx = barHeight(decor); shotDecorH = decor.height
            val bmp = Bitmap.createBitmap((decor.width * k).toInt().coerceAtLeast(1), (decor.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            PixelCopy.request(w, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    shot?.recycle(); shot = bmp; shotAt = SystemClock.uptimeMillis(); from = WeakReference(a); armedUntil = 0L
                } else bmp.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {}
    }


    /** (v1096) T1: بيدوّر على الشريط السفلي (tag = tvfab_nav) ويرجّع ارتفاعه لو لازق تحت الشاشة، وإلا 0 (شريط جانبي/مخفي/عايم فوق المحتوى) */
    private fun barHeight(decor: View): Int {
        try {
            val d = decor as? ViewGroup ?: return 0
            val bar = d.findViewWithTag<View>("tvfab_nav") ?: return 0
            if (bar.visibility != View.VISIBLE || bar.height <= 0 || bar.width < decor.width * 0.8f) return 0
            // (v1134) الشريط الزجاجي العايم (جوه GlassView): اللقطة لازم تغطّي الشاشة كلها لحد تحت الشريط — لو اتقصّت عند الشريط، المنطقة اللي وراه بتكشف الشاشة الجديدة (أبيض لحد ما ترسم) وبيبان شريط أبيض
            if (bar.parent is com.tttt.subtitler.mtui.GlassView) return 0
            val loc = IntArray(2); bar.getLocationInWindow(loc)
            val dl = IntArray(2); decor.getLocationInWindow(dl)
            val top = loc[1] - dl[1]
            if (top < decor.height * 0.5f) return 0
            return (decor.height - top).coerceIn(0, decor.height / 3)
        } catch (_: Throwable) { return 0 }
    }

    fun install(app: Application) { try { app.registerActivityLifecycleCallbacks(this) } catch (_: Throwable) {} }

    private fun animsOn(a: Activity): Boolean = try {
        android.provider.Settings.Global.getFloat(a.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    } catch (_: Throwable) { true }

    override fun onActivityPaused(a: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= 26) return   // (v927) اللقطة اتاخدت في arm() بـPixelCopy
            if (SystemClock.uptimeMillis() > armedUntil) return
            val decor = a.window?.decorView ?: return
            if (decor.width <= 0 || decor.height <= 0) return
            val k = 0.5f
            shotBarPx = barHeight(decor); shotDecorH = decor.height
            val bmp = Bitmap.createBitmap((decor.width * k).toInt().coerceAtLeast(1), (decor.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(k, k)
            decor.draw(c)
            shot?.recycle(); shot = bmp; shotAt = SystemClock.uptimeMillis(); from = WeakReference(a)
            armedUntil = 0L
        } catch (_: Throwable) { shot = null }
    }

    /** (v973) أكتيفيتيز لسه متبنّية أول مرة: بس دي بتاخد اللقطة. اللي كانت شغّالة أصلًا (بتتجاب قدّام) شاشتها جاهزة — اللقطة هتتكرر مع أنيميشن النافذة */
    private val justCreated = java.util.WeakHashMap<Activity, Boolean>()

    override fun onActivityStarted(a: Activity) {
        try {
            // (v1006) المشغّل مبياخدش لقطة صفحة قديمة أبدًا — كانت بتظهر لقطة «آخر صفحة استوديو» فوق الفيديو لحظة وتعمل رعشة
            val cn = a.javaClass.simpleName
            if (cn.contains("Playback") || cn == "PlayerActivity") { shot?.recycle(); shot = null; from = null; justCreated.remove(a); return }
            if (justCreated.remove(a) == null) { shot?.recycle(); shot = null; from = null; return }
            val full = shot ?: return
            if (SystemClock.uptimeMillis() - shotAt > 2000L || from?.get() === a) return
            shot = null; from = null
            if (!animsOn(a)) { full.recycle(); return }
            val decor = a.window?.decorView as? ViewGroup ?: run { full.recycle(); return }
            // (v1096) T1: اللقطة من غير ارتفاع الشريط → الشريط الحقيقي للشاشة الجديدة يفضل ثابت ومتكرّرش
            val barPx = shotBarPx; val dh = shotDecorH.coerceAtLeast(1)
            val keepH = if (barPx > 0) (full.height * (1f - barPx.toFloat() / dh)).toInt().coerceIn(1, full.height) else full.height
            val bmp = if (keepH < full.height) { try { Bitmap.createBitmap(full, 0, 0, full.width, keepH) } catch (_: Throwable) { full } } else full
            if (bmp !== full) full.recycle()
            val dens = a.resources.displayMetrics.density
            val iv = ImageView(a).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = 1000f
            }
            // bottomMargin بدل حساب الارتفاع: الـdecor ممكن لسه ما اتقاسش وقت onActivityStarted
            decor.addView(iv, android.widget.FrameLayout.LayoutParams(-1, -1, android.view.Gravity.TOP).apply { bottomMargin = barPx })
            var done = false
            fun finish() {
                if (done) return; done = true
                try { iv.animate().cancel(); decor.removeView(iv) } catch (_: Throwable) {}
                try { bmp.recycle() } catch (_: Throwable) {}
            }
            // استنى فريمين حقيقيين للشاشة الجديدة (+ مهلة قصيرة) وبعدين فيد frostIn
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback {
                    iv.postDelayed({
                        if (done) return@postDelayed
                        iv.animate().alpha(0f).translationX(-NavAnim.dir * 10f * dens).setDuration(240L).setInterpolator(EASE).withEndAction { finish() }.start()
                    }, 70L)
                }
            }
            iv.postDelayed({ finish() }, 1600L)   // أمان
        } catch (_: Throwable) {}
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) { justCreated[a] = true }
    override fun onActivityResumed(a: Activity) { cur = WeakReference(a) }
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
