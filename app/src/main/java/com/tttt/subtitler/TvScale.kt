package com.tttt.subtitler

import android.content.Context
import android.content.res.Configuration

/**
 * (v507) ريزوليوشن ديناميك لوضع التلفزيون: الواجهة متصمّمة على شاشة 960×540dp (تلفزيون 1080p عادي).
 * على أي شاشة تانية (موبايل، تلفزيون 4K بكثافة مختلفة، شاشة كبيرة) بنغيّر الكثافة (densityDpi) عشان نفس التصميم يملا الشاشة بنفس النسبة —
 * فكل الـ dp والـ sp بتتكبّر/تتصغّر مع بعض. فوق ده في ضبط يدوي (⋮ المزيد ← حجم الواجهة) لو التلفزيون 100 بوصة وانت بعيد.
 */
object TvScale {
    private const val DESIGN_W = 960f
    private const val DESIGN_H = 540f

    /** معامل الشاشة التلقائي (>1 = الشاشة فيها dp أكتر من التصميم فنكبّر، <1 = شاشة أصغر فنصغّر) */
    fun autoFactor(c: Context): Float {
        val dm = c.resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels) / dm.density
        val h = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        return minOf(w / DESIGN_W, h / DESIGN_H).coerceIn(0.55f, 3f)
    }

    fun adjust(): Float = (Cfg.str("tv_scale_adj", "100").toIntOrNull() ?: 100).coerceIn(50, 220) / 100f

    /** بتلفّ الـ Context بتاع أكتيفيتي (من attachBaseContext). في وضع التلفزيون بس. */
    fun wrap(base: Context): Context {
        return try {
            Cfg.ensure(base)
            if (Cfg.str("tv_autoscale", "1") != "1" || !DeviceUi.isTv(base)) return base
            val f = (autoFactor(base) * adjust()).coerceIn(0.5f, 3.5f)
            if (kotlin.math.abs(f - 1f) < 0.04f) return base
            val cfg = Configuration(base.resources.configuration)
            val dm = base.resources.displayMetrics
            val newDensity = dm.density * f
            cfg.densityDpi = (cfg.densityDpi * f).toInt().coerceAtLeast(60)
            val wPx = dm.widthPixels; val hPx = dm.heightPixels
            cfg.screenWidthDp = (wPx / newDensity).toInt(); cfg.screenHeightDp = (hPx / newDensity).toInt()
            cfg.smallestScreenWidthDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp)
            base.createConfigurationContext(cfg)
        } catch (_: Throwable) { base }
    }
}
