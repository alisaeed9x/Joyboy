package com.tttt.subtitler

import android.graphics.Typeface

/**
 * (v816) سكن iOS للموبايل — سكن أساسي للواجهة كلها (مش سمة): Skin(id = "ios") في Skin.kt بيفعّل:
 *  - كروت مسطّحة بزوايا كبيرة من غير حدود (grouped cards) · أزرار tinted/flat · chips رمادية خفيفة · سويتش أخضر
 *  - شريط تبويب سفلي شفاف بأيقونات outline كبيرة وتسميات 10sp ومن غير مؤشّر · صفوف الإعدادات بمربعات ملوّنة مدوّرة
 *  - ثيمات iOS فاتح/داكن (Theme.kt) + باك الأيقونات «outline» + فولدر أزرق + دايلوج بلور قوي وحركة نطّة ناعمة
 * الخط: الخط الافتراضي للنظام (SF Pro مش متاح على أندرويد).
 */
object IosSkin {
    const val ID = "ios"
    fun on(): Boolean = try { Skins.cur().ios } catch (_: Throwable) { false }

    /** ألوان مربعات أيقونات الإعدادات (نفس باليتة iOS) */
    val TILE = intArrayOf(0xFF007AFF.toInt(), 0xFF34C759.toInt(), 0xFFFF9500.toInt(), 0xFFFF3B30.toInt(), 0xFFAF52DE.toInt(),
        0xFF5AC8FA.toInt(), 0xFFFF2D55.toInt(), 0xFF8E8E93.toInt(), 0xFF5856D6.toInt(), 0xFFFFCC00.toInt())
    val bold: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    private fun put(k: String, v: String) { try { Cfg.p.edit().putString(k, v).apply() } catch (_: Throwable) {} }

    /** تشغيل/إيقاف السكن: بيبدّل الثيم وباك الأيقونات وبيرجّعهم لما كانوا لما تقفله. اندهه وبعدها recreate() */
    fun enable(on: Boolean) {
        val th = Glass.theme()
        put("skin", if (on) ID else "classic"); Skins.set(if (on) ID else "classic")
        if (on) {
            val cur = IconPacks.activeId()
            if (cur != "outline") put("ios_prev_pack", cur)
            IconPacks.select("outline")
            if (th.id != "ios" && th.id != "iosd") put("theme", if (th.isLight) "ios" else "iosd")
        } else {
            IconPacks.select(Cfg.str("ios_prev_pack", IconPacks.DEFAULT).ifBlank { IconPacks.DEFAULT })
            if (th.id == "ios") put("theme", "mx") else if (th.id == "iosd") put("theme", "amoled_w")
        }
    }
}
