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
            val bmp = Bitmap.createBitmap((decor.width * k).toInt().coerceAtLeast(1), (decor.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            PixelCopy.request(w, bmp, { res ->
                if (res == PixelCopy.SUCCESS) {
                    shot?.recycle(); shot = bmp; shotAt = SystemClock.uptimeMillis(); from = WeakReference(a); armedUntil = 0L
                } else bmp.recycle()
            }, Handler(Looper.getMainLooper()))
        } catch (_: Throwable) {}
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
            val bmp = Bitmap.createBitmap((decor.width * k).toInt().coerceAtLeast(1), (decor.height * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.scale(k, k)
            decor.draw(c)
            shot?.recycle(); shot = bmp; shotAt = SystemClock.uptimeMillis(); from = WeakReference(a)
            armedUntil = 0L
        } catch (_: Throwable) { shot = null }
    }

    override fun onActivityStarted(a: Activity) {
        try {
            val bmp = shot ?: return
            if (SystemClock.uptimeMillis() - shotAt > 2000L || from?.get() === a) return
            shot = null; from = null
            if (!animsOn(a)) { bmp.recycle(); return }
            val decor = a.window?.decorView as? ViewGroup ?: run { bmp.recycle(); return }
            val iv = ImageView(a).apply {
                setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_XY
                isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                elevation = 1000f
            }
            decor.addView(iv, ViewGroup.LayoutParams(-1, -1))
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
                        iv.animate().alpha(0f).scaleX(1.025f).scaleY(1.025f).setDuration(240L).setInterpolator(EASE).withEndAction { finish() }.start()
                    }, 70L)
                }
            }
            iv.postDelayed({ finish() }, 1600L)   // أمان
        } catch (_: Throwable) {}
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityResumed(a: Activity) { cur = WeakReference(a) }
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}
}
