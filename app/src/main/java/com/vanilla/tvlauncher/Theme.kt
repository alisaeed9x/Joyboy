package com.vanilla.tvlauncher

/** ثيمات منقولة من Subtitler v475 (Theme.kt) — مجموعة مختارة، نفس الألوان ونفس قاعدة النص الأسود/الأبيض الواضح. */
class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val card: Int, val border: Int,
            val primary: Int, val accent: Int, val text: Int, val muted: Int, val danger: Int, val success: Int) {
    val isLight: Boolean get() = lum(bg) > 0.5
    companion object {
        fun lum(c: Int): Double { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 }
    }
}

object Themes {
    private val raw: List<Theme> = listOf(
        Theme("mx", "كريمي دافئ (أساسي)", 0xFFF7F1E6.toInt(), 0xFFEFE5D0.toInt(), 0xFFFFFBF2.toInt(), 0xFFDCC9A8.toInt(), 0xFF9A5B12.toInt(), 0xFFC65D21.toInt(), 0xFF2A1D12.toInt(), 0xFF4A3F30.toInt(), 0xFFC6493F.toInt(), 0xFF2E86D6.toInt()),
        Theme("default", "افتراضي (غرفة تحكم)", 0xFF0B0E11.toInt(), 0xFF12161B.toInt(), 0xFF1A1F26.toInt(), 0xFF262D36.toInt(), 0xFFF5A623.toInt(), 0xFF43C6D0.toInt(), 0xFFECEFF2.toInt(), 0xFF7C8591.toInt(), 0xFFE85C5C.toInt(), 0xFFF5A623.toInt()),
        Theme("light", "فاتح", 0xFFF5F6F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE1E4EA.toInt(), 0xFFE08E1D.toInt(), 0xFF1596A0.toInt(), 0xFF1B1F27.toInt(), 0xFF3F4650.toInt(), 0xFFD64545.toInt(), 0xFFE08E1D.toInt()),
        Theme("mxd", "MX داكن", 0xFF121212.toInt(), 0xFF1B1B1B.toInt(), 0xFF1E1E1E.toInt(), 0xFF2E2E2E.toInt(), 0xFF4FA3F7.toInt(), 0xFF2196F3.toInt(), 0xFFEDEDED.toInt(), 0xFF9AA0A6.toInt(), 0xFFFF6B6B.toInt(), 0xFF4FA3F7.toInt()),
        Theme("amoled_w", "أموليد", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0C0C0C.toInt(), 0xFF262626.toInt(), 0xFF6AB0FF.toInt(), 0xFF8AB4F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFA0A0A0.toInt(), 0xFFFF6B6B.toInt(), 0xFF6AB0FF.toInt()),
        Theme("eye_night", "ليلي مريح", 0xFF1C2128.toInt(), 0xFF222831.toInt(), 0xFF262D36.toInt(), 0xFF343C47.toInt(), 0xFF7FB2E5.toInt(), 0xFF8FBCBB.toInt(), 0xFFD6DCE4.toInt(), 0xFF8A95A3.toInt(), 0xFFE07B7B.toInt(), 0xFF7FB2E5.toInt()),
        Theme("sky", "سماوي", 0xFFF3F8FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFDCE9F3.toInt(), 0xFF2E86D6.toInt(), 0xFF4FBFD9.toInt(), 0xFF1B2733.toInt(), 0xFF3D4F61.toInt(), 0xFFD64545.toInt(), 0xFF2E86D6.toInt())
    )
    // نفس قاعدة Subtitler v475: الفاتح نصه أسود، الغامق نصه أبيض (وضوح من بعيد على التلفزيون)
    private fun hard(t: Theme): Theme {
        val light = t.isLight
        return Theme(t.id, t.name, t.bg, t.surface, t.card, t.border, t.primary, t.accent,
            if (light) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(),
            if (light) 0xFF262626.toInt() else 0xFFE2E2E2.toInt(), t.danger, t.success)
    }
    val all: List<Theme> = raw.map { hard(it) }
    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all[0]
}
