package com.vanilla.tvlauncher

/**
 * منطق المظهر المخصّص (P15، D-018): ألوان رئيسية + خلفيات (سادة/متدرّجة) + خطوط + الاسم الظاهر. نقي (بدون Android) → بيتختبر على JVM.
 * الفاتح نصه أسود والغامق نصه أبيض (نفس قاعدة Themes.hard)، وبيتصلّح لون التمييز لو قريب من الخلفية (وضوح من بعيد).
 */
object Appearance {
    class Swatch(val id: Int, val name: String, val color: Int)
    class Bg(val id: Int, val name: String, val top: Int, val bottom: Int) { val gradient: Boolean get() = top != bottom }

    const val DEFAULT_NAME = "Vanilla TV"
    const val NAME_MAX = 24

    /** 0 = «من الثيم» (بدون تغيير) */
    val ACCENTS: List<Swatch> = listOf(
        Swatch(0, "من الثيم", 0),
        Swatch(1, "برتقالي", 0xFFF5A623.toInt()), Swatch(2, "أزرق", 0xFF4FA3F7.toInt()), Swatch(3, "أخضر", 0xFF4CC38A.toInt()),
        Swatch(4, "أحمر", 0xFFE85C5C.toInt()), Swatch(5, "بنفسجي", 0xFFA78BFA.toInt()), Swatch(6, "سماوي", 0xFF43C6D0.toInt()),
        Swatch(7, "وردي", 0xFFF472B6.toInt()), Swatch(8, "ذهبي", 0xFFD4A017.toInt())
    )

    /** 0 = «من الثيم». top==bottom = لون سادة، غير كده = تدرّج رأسي */
    val BGS: List<Bg> = listOf(
        Bg(0, "من الثيم", 0, 0),
        Bg(1, "أسود نقي", 0xFF000000.toInt(), 0xFF000000.toInt()),
        Bg(2, "كحلي متدرّج", 0xFF0F1B33.toInt(), 0xFF05080F.toInt()),
        Bg(3, "أخضر غامق متدرّج", 0xFF10261D.toInt(), 0xFF050C09.toInt()),
        Bg(4, "بنفسجي متدرّج", 0xFF21143D.toInt(), 0xFF0A0614.toInt()),
        Bg(5, "بني دافئ متدرّج", 0xFF2B1B10.toInt(), 0xFF0E0804.toInt()),
        Bg(6, "رمادي فحمي", 0xFF1B1D21.toInt(), 0xFF1B1D21.toInt()),
        Bg(7, "كريمي فاتح", 0xFFF7F1E6.toInt(), 0xFFF7F1E6.toInt()),
        Bg(8, "أبيض ثلجي متدرّج", 0xFFFFFFFF.toInt(), 0xFFE6ECF3.toInt())
    )

    /** "" = خط النظام. الباقي أسماء عائلات Typeface.create (موجودة في Android) */
    val FONTS: List<Pair<String, String>> = listOf(
        "" to "الافتراضي", "sans-serif-medium" to "سميك شوية", "sans-serif-light" to "رفيع",
        "serif" to "بسيط بذيول (Serif)", "sans-serif-condensed" to "مضغوط", "monospace" to "ثابت العرض", "casual" to "كاجوال"
    )

    fun accentName(i: Int): String = ACCENTS.getOrNull(i)?.name ?: ACCENTS[0].name
    fun bgName(i: Int): String = BGS.getOrNull(i)?.name ?: BGS[0].name
    fun fontName(i: Int): String = FONTS.getOrNull(i)?.second ?: FONTS[0].second
    fun fontFamily(i: Int): String = FONTS.getOrNull(i)?.first ?: ""
    fun next(cur: Int, size: Int): Int = if (size <= 0) 0 else ((if (cur < 0 || cur >= size) -1 else cur) + 1) % size

    fun bgOf(i: Int): Bg? = BGS.getOrNull(i)?.takeIf { it.id != 0 }

    private fun ch(c: Int, s: Int) = (c shr s) and 255
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    /** f > 0 → امزج مع الأبيض بنسبة f · f < 0 → امزج مع الأسود بنسبة |f| */
    fun shade(c: Int, f: Double): Int {
        val t = if (f >= 0) 255.0 else 0.0; val k = Math.abs(f).coerceIn(0.0, 1.0)
        fun m(v: Int) = Math.round(v + (t - v) * k).toInt()
        return rgb(m(ch(c, 16)), m(ch(c, 8)), m(ch(c, 0)))
    }

    /** لون التمييز لازم يبان على الخلفية: لو فرق السطوع < 0.30 بنغمّق/نفتّح اللون لحد ما يبان */
    fun fixContrast(accent: Int, bg: Int): Int {
        var a = accent
        val bgLight = Theme.lum(bg) > 0.5
        var guard = 0
        while (Math.abs(Theme.lum(a) - Theme.lum(bg)) < 0.30 && guard++ < 10) a = shade(a, if (bgLight) -0.2 else 0.2)
        return a
    }

    /** الثيم الأساسي + لون رئيسي (i) + خلفية (j). (0,0) = نفس الثيم بدون تغيير */
    fun apply(t: Theme, accentIdx: Int, bgIdx: Int): Theme {
        val a = ACCENTS.getOrNull(accentIdx)?.takeIf { it.color != 0 }?.color
        val b = bgOf(bgIdx)
        if (a == null && b == null) return t
        var bg = t.bg; var surface = t.surface; var card = t.card; var border = t.border; var text = t.text; var muted = t.muted
        if (b != null) {
            val light = Theme.lum(b.top) > 0.5
            bg = b.top
            surface = shade(b.top, if (light) -0.03 else 0.05)
            card = shade(b.top, if (light) -0.07 else 0.09)
            border = shade(b.top, if (light) -0.20 else 0.18)
            text = if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            muted = if (light) 0xFF262626.toInt() else 0xFFE2E2E2.toInt()
        }
        val primary = fixContrast(a ?: t.primary, bg)
        return Theme(t.id, t.name, bg, surface, card, border, primary, if (a != null) primary else t.accent, text, muted, t.danger, t.success)
    }

    /** الاسم الظاهر: مسافات مضغوطة، أقصى NAME_MAX، فاضي = الافتراضي. ما بيقطعش زوج surrogate */
    fun displayName(raw: String?): String {
        var s = (raw ?: "").replace(Regex("\\s+"), " ").trim()
        if (s.length > NAME_MAX) { s = s.substring(0, NAME_MAX); if (s.isNotEmpty() && Character.isHighSurrogate(s.last())) s = s.dropLast(1); s = s.trim() }
        return if (s.isEmpty()) DEFAULT_NAME else s
    }
}
