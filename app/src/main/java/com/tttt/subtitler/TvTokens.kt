package com.tttt.subtitler

import android.graphics.Color

/** (v597 م1) أسلوب حلقة التركيز — كل سكِن يختار أسلوبه */
enum class TvFocusStyle { RING, GLOW, LIFT, UNDERLINE }

/**
 * (v597 م1) رموز التصميم (Design Tokens) الخاصة بسكِن: استدارات · مسافات · أحجام خطوط · أسلوب التركيز · مدد الحركة.
 * الألوان مش هنا — بتتحسب من الثيم الحالي في [TvPalette] عشان أي سكِن يشتغل مع أي سمة.
 * مدد الحركة بتعدّي على Skins.dur (وضع الحركة الموجود) فمفيش تعارض مع «وضع الواجهة والحركة».
 */
class TvTokens(
    val radiusCard: Int = 14,
    val radiusThumb: Int = 10,
    val radiusPill: Int = 28,
    val gap: Int = 8,          // مسافة بين الكروت (dp)
    val pad: Int = 12,         // حشو داخل الكارت (dp)
    val textTitle: Float = 17f,
    val textBody: Float = 13f,
    val textCaption: Float = 12f,
    val focusStyle: TvFocusStyle = TvFocusStyle.RING,
    val focusScale: Float = 1.05f,
    val focusMs: Long = 130L,
    val pageMs: Long = 220L
) {
    fun focusDur(): Long = Skins.dur(focusMs)
    fun pageDur(): Long = Skins.dur(pageMs)

    companion object {
        /** رموز سكِن «كلاسيك» = نفس قيم الواجهة الحالية */
        val CLASSIC = TvTokens()
    }
}

/** ألوان محلولة من السمة الحالية (فاتحة/غامقة) — أي سكِن بياخد ألوانه من هنا مش من أرقام ثابتة */
class TvPalette(val th: Theme) {
    val dark: Boolean = Theme.lum(th.bg) < 0.35
    val bg: Int = th.bg
    val surface: Int = th.surface
    val border: Int = th.border
    /** (v634) إطار الكروت: كان th.border وبيختفي على الخلفية الغامقة — دلوقتي شفاف أبيض/أسود حسب الخلفية */
    val outline: Int = if (dark) 0x77FFFFFF else 0x66000000
    val primary: Int = th.primary
    val accent: Int = th.accent
    /** نص رئيسي / ثانوي فوق كارت التلفزيون */
    val fg: Int = if (dark) Color.WHITE else th.text
    val fgSub: Int = if (dark) 0xFFB8C0D4.toInt() else th.muted
    /** خلفية كارت (شبه شفافة على الغامق عشان تبان الخلفية) */
    val cardFill: Int = if (dark) 0xCC0E1116.toInt() else th.card
    val onPrimary: Int = if (Theme.lum(th.primary) > 0.55) Color.BLACK else Color.WHITE
}

/**
 * (v633) إعدادات المظهر الحالية للمستخدم (Cfg) — السكنات كانت بتقرا رموزها الثابتة بس فكانت إعدادات الأيقونات والتركيز
 * مابتتطبّقش عليها. أي سكن/كارت لازم يعدّي على هنا بدل ما يثبّت القيم.
 */
object TvUserPrefs {
    private fun s(k: String, d: String): String = try { Cfg.str(k, d) } catch (_: Throwable) { d }
    fun lift(): Int = (s("tv_lift", "2").toIntOrNull() ?: 2).coerceIn(0, 3)
    /** معامل التكبير: بدون 0 · خفيف 0.5 · عادي 1 · عالي 1.6 */
    fun liftK(): Float = when (lift()) { 0 -> 0f; 1 -> 0.5f; 2 -> 1f; else -> 1.6f }
    fun halo(): Boolean = s("tv_halo", "1") != "0"
    fun iconPx(): Int = (s("tv_icon_px", "86").toIntOrNull() ?: 86).coerceIn(56, 140)
    /** نسبة حجم الأيقونة للافتراضي (86) */
    fun iconK(): Float = iconPx() / 86f
    fun squircle(): Boolean = s("tv_icon_shape", "circle") == "squircle"
    /** always | focus | off */
    fun names(): String = s("tv_names", "always")
}
