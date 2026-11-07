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

    /** تشغيل/إيقاف السكن (متوافق مع v816): بيتفوّض لـ SkinKit — اندهه وبعدها recreate() */
    fun enable(on: Boolean) = SkinKit.apply(if (on) ID else "classic")
}
