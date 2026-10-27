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
    val primary: Int = th.primary
    val accent: Int = th.accent
    /** نص رئيسي / ثانوي فوق كارت التلفزيون */
    val fg: Int = if (dark) Color.WHITE else th.text
    val fgSub: Int = if (dark) 0xFFB8C0D4.toInt() else th.muted
    /** خلفية كارت (شبه شفافة على الغامق عشان تبان الخلفية) */
    val cardFill: Int = if (dark) 0xCC0E1116.toInt() else th.card
    val onPrimary: Int = if (Theme.lum(th.primary) > 0.55) Color.BLACK else Color.WHITE
}
