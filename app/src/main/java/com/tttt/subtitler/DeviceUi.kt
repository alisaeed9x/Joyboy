package com.tttt.subtitler

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * (v461) أوضاع الواجهة: نفس النسخة الواحدة بتتحول موبايل / تابلت / تلفزيون.
 * الوضع بييجي من الجهاز نفسه (تلفزيون حقيقي أو شاشة عريضة) أو من سويتش المستخدم في
 * الإعدادات (🖥 وضع الواجهة) — عشان يقدر يجرب واجهة التلفزيون والتابلت على موبايله
 * حتى لو معندوش شاشة كبيرة. السويتش بيعدّي: موبايل ← تلفزيون ← تابلت ← موبايل.
 */
object DeviceUi {
    const val AUTO = "auto"; const val PHONE = "phone"; const val TABLET = "tablet"; const val TV = "tv"

    fun pref(): String = Cfg.str("ui_mode", AUTO)

    fun isRealTv(ctx: Context): Boolean = try {
        val um = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        um?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    } catch (_: Exception) { false }

    fun isRealTablet(ctx: Context): Boolean = try {
        ctx.resources.configuration.smallestScreenWidthDp >= 600
    } catch (_: Exception) { false }

    /** الوضع الفعلي بعد تطبيق اختيار المستخدم */
    fun mode(ctx: Context): String = when (pref()) {
        PHONE, TABLET, TV -> pref()
        else -> if (isRealTv(ctx)) TV else if (isRealTablet(ctx)) TABLET else PHONE
    }

    fun isTv(ctx: Context): Boolean = mode(ctx) == TV

    /** (v494) تجربة وضع التلفزيون/التابلت على موبايل: الشاشة تتقفل لاندسكيب تلقائي (التلفزيون الحقيقي والتابلت الحقيقي ما بيتلمسوش) */
    fun forcesLandscape(ctx: Context): Boolean = try {
        Cfg.ensure(ctx)
        val m = mode(ctx)
        (m == TV || m == TABLET) && !isRealTv(ctx) && !isRealTablet(ctx)
    } catch (_: Throwable) { false }

    fun applyOrientation(act: android.app.Activity) {
        try { if (forcesLandscape(act)) act.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE } catch (_: Throwable) { }
    }
    fun isTablet(ctx: Context): Boolean = mode(ctx) == TABLET

    fun label(m: String): String = when (m) {
        PHONE -> "📱 موبايل"; TABLET -> "📟 تابلت"; TV -> "📺 تلفزيون"; else -> "🔄 تلقائي"
    }

    /** خطوة السويتش الجاية (التلقائي بيتحسب كموبايل على التليفونات) */
    fun cycleNext(ctx: Context): String = when (mode(ctx)) {
        PHONE -> TV; TV -> TABLET; else -> PHONE
    }
}
