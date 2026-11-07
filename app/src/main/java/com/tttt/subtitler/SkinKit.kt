package com.tttt.subtitler

/**
 * (v817) السكنات الكاملة للواجهة — iOS (v816) + ويندوز 11 (Fluent) + ويندوز XP (Luna) + أموليد (أسود خالص).
 * كل سكن = Skin(look/ios) في Skin.kt بيفعّل: شكل الكروت والأزرار والـ chips والسويتش · شريط التبويب · صفوف الإعدادات ·
 * رأس الأقسام · نافذة الدايلوج وحركتها · أصوات اللمس · فولدرات · + حزمة جاهزة هنا: ثيم (فاتح/داكن) وباك أيقونات.
 * التطبيق: SkinKit.apply(id) وبعدها recreate(). الرجوع للأصلي ("classic") بيرجّع باك الأيقونات اللي كان قبل أول سكن.
 */
object SkinKit {
    /** حزمة السكن: ثيم فاتح · ثيم داكن · باك الأيقونات */
    class Kit(val light: String, val dark: String, val pack: String)
    val KITS: Map<String, Kit> = mapOf(
        "ios" to Kit("ios", "iosd", "outline"),
        "win11" to Kit("win11", "win11d", "rounded"),
        "xp" to Kit("winxp", "winxpd", "filled"),
        "amoled" to Kit("amoled_w", "amoled_w", "outline")
    )
    /** السكنات اللي بتظهر للمستخدم بالترتيب: (id, الاسم) */
    val CHOICES: List<Pair<String, String>> = listOf(
        "classic" to "🎛 الأصلي", "ios" to "🍎 iOS", "win11" to "🪟 ويندوز 11", "xp" to "🖥 ويندوز XP", "amoled" to "⬛ أموليد")
    val IDS: Set<String> = CHOICES.map { it.first }.toSet()

    private fun put(k: String, v: String) { try { Cfg.p.edit().putString(k, v).apply() } catch (_: Throwable) {} }

    fun apply(id0: String) {
        val id = if (id0 in IDS) id0 else "classic"
        val old = Skins.cur(); val th = Glass.theme()
        val kit = KITS[id]
        // أول مرة نسيب «الأصلي»: نحفظ باك الأيقونات الحالي عشان نرجّعه
        if (kit != null && KITS[old.id] == null) put("skin_prev_pack", IconPacks.activeId())
        put("skin", id); Skins.set(id)
        if (kit != null) {
            IconPacks.select(kit.pack)
            put("theme", if (th.isLight) kit.light else kit.dark)
        } else {
            val prev = Cfg.str("skin_prev_pack", Cfg.str("ios_prev_pack", IconPacks.DEFAULT)).ifBlank { IconPacks.DEFAULT }
            IconPacks.select(prev)
            when (th.id) { "ios" -> put("theme", "mx"); "iosd", "winxpd" -> put("theme", "amoled_w") }
        }
    }
}
