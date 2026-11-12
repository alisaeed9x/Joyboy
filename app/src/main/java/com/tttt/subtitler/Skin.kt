package com.tttt.subtitler

import android.view.animation.Interpolator

/**
 * (v907) السكنات اتمسحت (الأصلي · ويندوز 11 · فقاعي · حاد · هادئ · iOS · XP · أموليد + أصواتها).
 * فاضل شكل واحد ثابت = حركة MeeTube (MtGlass). الأدوات دي مجرد pass-through عشان نقط الاستدعاء القديمة تفضل شغّالة؛
 * مفيش لا اختيار سكن ولا سكن مخزّن. (تخطيطات التلفزيون TvSkin ملهاش علاقة — لسه زي ما هي.)
 */
object Skins {
    fun dur(ms: Long): Long = ms.coerceAtLeast(60L)
    fun rad(r: Int): Int = r
    fun radF(px: Float, @Suppress("UNUSED_PARAMETER") density: Float): Float = px
    fun interp(tension: Float): Interpolator = android.view.animation.OvershootInterpolator(tension)
    fun icon(name: String): String = name
}
