package com.tttt.subtitler

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * (v592) سبلاش Joyboy: صورة عشوائية من 3 (كلها بالطاقية) بتملا الشاشة كلها من غير أي حواف بيضا
 * وبتظهر مرة واحدة كل تشغيل للتطبيق (مش كل ما الشاشة تتفتح). لمسة = تخطّي.
 * بورتريه: الصورة (جاهزة بنسبة الشاشة الطويلة) center-crop. لاندسكيب/تلفزيون: الصورة كاملة في النص
 * وخلفها نفس الصورة مكبّرة وغامقة ومموّهة، فمفيش شريط أبيض ولا أسود فاضي.
 */
object SplashArt {
    private val ids = intArrayOf(R.drawable.splash_1, R.drawable.splash_2, R.drawable.splash_3)
    private var shownThisProcess = false
    private const val HOLD_MS = 1500L
    private const val FADE_MS = 350L

    fun show(act: Activity, savedState: Any?) {
        if (shownThisProcess || savedState != null) return
        shownThisProcess = true
        try {
            val decor = act.window.decorView as? ViewGroup ?: return
            val res = act.resources
            val id = ids[(Math.random() * ids.size).toInt().coerceIn(0, ids.size - 1)]
            val land = res.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val box = FrameLayout(act).apply {
                setBackgroundColor(0xFF2E1609.toInt()); isClickable = true; elevation = 1000f
            }
            if (land) {
                val o = BitmapFactory.Options().apply { inSampleSize = 24 }   // مصغّرة جدًا ← لما تتكبّر بتطلع مموّهة
                val small = BitmapFactory.decodeResource(res, id, o)
                if (small != null) box.addView(ImageView(act).apply {
                    setImageBitmap(small); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.75f
                }, FrameLayout.LayoutParams(-1, -1))
            }
            box.addView(ImageView(act).apply {
                setImageResource(id)
                scaleType = if (land) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(-1, -1))
            // شاشة كاملة فعلًا: نخفي شريط الحالة/التنقل طول فترة السبلاش ونرجّعهم بعدها
            val prevUi = decor.systemUiVisibility
            val hideUi = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            decor.systemUiVisibility = prevUi or hideUi
            decor.addView(box, ViewGroup.LayoutParams(-1, -1))
            var gone = false
            fun finish() {
                if (gone) return; gone = true
                box.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                    try { decor.removeView(box) } catch (_: Throwable) {}
                    try { if (decor.systemUiVisibility == (prevUi or hideUi)) decor.systemUiVisibility = prevUi } catch (_: Throwable) {}
                }.start()
            }
            box.setOnClickListener { finish() }
            box.postDelayed({ finish() }, HOLD_MS)
        } catch (e: Throwable) { try { LogStore.err("SplashArt", e) } catch (_: Throwable) {} }
    }
}
