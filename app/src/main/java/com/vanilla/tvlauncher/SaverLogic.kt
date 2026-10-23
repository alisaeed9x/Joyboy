package com.vanilla.tvlauncher

/** منطق شاشة التوقف النقي (P10): متى تظهر، حركة الساعة ضد حرق الشاشة، وأنماط العرض. */
object SaverLogic {
    const val STYLE_CLOCK_DATE = 0
    const val STYLE_CLOCK = 1
    const val STYLE_BLACK = 2
    val STYLES = listOf(STYLE_CLOCK_DATE, STYLE_CLOCK, STYLE_BLACK)

    fun styleLabel(s: Int): String = when (s) { STYLE_CLOCK_DATE -> "ساعة + تاريخ"; STYLE_CLOCK -> "ساعة فقط"; else -> "شاشة سودا" }

    /** تظهر بعد minutes دقيقة خمول (0 = معطّلة)، ومش أثناء فيديو شغّال */
    fun shouldShow(idleSec: Int, minutes: Int, playing: Boolean): Boolean = minutes > 0 && !playing && idleSec >= minutes * 60

    /** الساعة بتتحرّك كل دقيقة (أول ثانية في السكون كمان) عشان ما تتحرقش الشاشة */
    fun shouldMove(saverSec: Int): Boolean = saverSec % 60 == 0

    /** إزاحة عشوائية جوه ±size/4 (r أي رقم، حتى سالب). size صغير = 0 */
    fun offset(size: Int, r: Int): Int {
        val lim = size / 4
        if (lim <= 0) return 0
        return Math.floorMod(r, 2 * lim + 1) - lim
    }
}
