package com.tttt.subtitler

// ثيمات مستخرجة بالسكريبت من CSS البرنامج الأصلي (html[data-theme])
class Theme(val id: String, val name: String, val bg: Int, val surface: Int, val card: Int, val border: Int,
            val primary: Int, val accent: Int, val text: Int, val muted: Int, val danger: Int, val success: Int) {
    val isLight: Boolean get() = lum(bg) > 0.5
    companion object {
        fun lum(c: Int): Double { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return (0.299*r + 0.587*g + 0.114*b) / 255.0 }
        fun parse(s: String): Int { var h = s.trim().removePrefix("#"); if (h.length == 3) h = h.map { "$it$it" }.joinToString(""); return (0xFF000000L or h.take(6).toLong(16)).toInt() }
    }
}

object Themes {
    val all: List<Theme> = listOf(
        // أبيض وأزرق زي MX Player — الثيم الأساسي
        Theme("mx", "MX أبيض وأزرق", 0xFFFFFFFF.toInt(), 0xFFF3F5F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFE3E8EF.toInt(), 0xFF2196F3.toInt(), 0xFF1565C0.toInt(), 0xFF1B1F27.toInt(), 0xFF6B7280.toInt(), 0xFFE53935.toInt(), 0xFF2E7D32.toInt()),
        Theme("default", "افتراضي (غرفة تحكم)", 0xFF0B0E11.toInt(), 0xFF12161B.toInt(), 0xFF1A1F26.toInt(), 0xFF262D36.toInt(), 0xFFF5A623.toInt(), 0xFF43C6D0.toInt(), 0xFFECEFF2.toInt(), 0xFF7C8591.toInt(), 0xFFE85C5C.toInt(), 0xFF4FD1A5.toInt()),
        Theme("amoled", "أموليد", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0A0A0A.toInt(), 0xFF1E1E1E.toInt(), 0xFFF5A623.toInt(), 0xFF43C6D0.toInt(), 0xFFECEFF2.toInt(), 0xFF7C8591.toInt(), 0xFFE85C5C.toInt(), 0xFF4FD1A5.toInt()),
        Theme("light", "فاتح", 0xFFF5F6F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE1E4EA.toInt(), 0xFFE08E1D.toInt(), 0xFF1596A0.toInt(), 0xFF1B1F27.toInt(), 0xFF6B7280.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("warm", "دافئ", 0xFFFBF4EC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFF9F1.toInt(), 0xFFEBDCC9.toInt(), 0xFFD9772E.toInt(), 0xFFC0562F.toInt(), 0xFF2B221A.toInt(), 0xFF8A7A66.toInt(), 0xFFC6493F.toInt(), 0xFF3E9B6F.toInt()),
        Theme("sakura", "ساكورا", 0xFFFFF5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFAFB.toInt(), 0xFFF3D9E1.toInt(), 0xFFE8628A.toInt(), 0xFF8E7CC3.toInt(), 0xFF2E1F26.toInt(), 0xFF8A6E78.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("sky", "سماوي", 0xFFF3F8FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFDCE9F3.toInt(), 0xFF2E86D6.toInt(), 0xFF4FBFD9.toInt(), 0xFF1B2733.toInt(), 0xFF6C8299.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("mint", "نعناع", 0xFFF2FAF6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9EFE1.toInt(), 0xFF1E9E75.toInt(), 0xFF38B58F.toInt(), 0xFF1B2A22.toInt(), 0xFF6A8577.toInt(), 0xFFD64545.toInt(), 0xFF1E9E75.toInt()),
        Theme("sand", "رملي", 0xFFFAF6EE.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFDF8.toInt(), 0xFFEBE0C9.toInt(), 0xFFB8863A.toInt(), 0xFF8C7048.toInt(), 0xFF2B261B.toInt(), 0xFF857A63.toInt(), 0xFFC6493F.toInt(), 0xFF3E9B6F.toInt()),
        Theme("win11", "ويندوز 11", 0xFFF3F3F3.toInt(), 0xFFFBFBFB.toInt(), 0xFFFFFFFF.toInt(), 0xFFE5E5E5.toInt(), 0xFF0078D4.toInt(), 0xFF005FB8.toInt(), 0xFF1B1B1B.toInt(), 0xFF5D5D5D.toInt(), 0xFFC42B1C.toInt(), 0xFF107C10.toInt()),
        Theme("infinix", "إنفينكس", 0xFF121214.toInt(), 0xFF1B1B1F.toInt(), 0xFF232327.toInt(), 0xFF333338.toInt(), 0xFFFF5722.toInt(), 0xFFFFA000.toInt(), 0xFFF5F5F7.toInt(), 0xFF96969C.toInt(), 0xFFFF3B30.toInt(), 0xFF34C759.toInt()),
        // (v352) أموليد بكلام أبيض نقي
        Theme("amoled_w", "أموليد · كلام أبيض", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0C0C0C.toInt(), 0xFF262626.toInt(), 0xFF6AB0FF.toInt(), 0xFF8AB4F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFA0A0A0.toInt(), 0xFFFF6B6B.toInt(), 0xFF5CD68A.toInt()),
        // ثيمات مريحة للعين: ألوان هادئة منخفضة التباين والضوء الأزرق، من غير أبيض ساطع ولا أسود قاسي
        Theme("eye_sepia", "ورقي مريح (سيبيا)", 0xFFF4ECD8.toInt(), 0xFFFAF4E4.toInt(), 0xFFFBF6E9.toInt(), 0xFFE0D3B6.toInt(), 0xFF8B5E34.toInt(), 0xFF6F7F4F.toInt(), 0xFF433422.toInt(), 0xFF8A7860.toInt(), 0xFFB5483A.toInt(), 0xFF5E8B4F.toInt()),
        Theme("eye_green", "أخضر حماية العين", 0xFFCCE8CF.toInt(), 0xFFD8EFDA.toInt(), 0xFFE2F3E4.toInt(), 0xFFAFD3B4.toInt(), 0xFF2E7D5B.toInt(), 0xFF4F8F6F.toInt(), 0xFF1F3326.toInt(), 0xFF587463.toInt(), 0xFFB5483A.toInt(), 0xFF2E7D5B.toInt()),
        Theme("eye_gray", "رمادي ناعم", 0xFFE9EBEE.toInt(), 0xFFF1F2F4.toInt(), 0xFFF5F6F8.toInt(), 0xFFD3D7DD.toInt(), 0xFF4F7CAC.toInt(), 0xFF6A8CAF.toInt(), 0xFF2A2F36.toInt(), 0xFF6B7280.toInt(), 0xFFC0504D.toInt(), 0xFF3F8F6B.toInt()),
        Theme("eye_night", "ليلي مريح", 0xFF1C2128.toInt(), 0xFF222831.toInt(), 0xFF262D36.toInt(), 0xFF343C47.toInt(), 0xFF7FB2E5.toInt(), 0xFF8FBCBB.toInt(), 0xFFD6DCE4.toInt(), 0xFF8A95A3.toInt(), 0xFFE07B7B.toInt(), 0xFF7FCB9B.toInt()),
        Theme("eye_dusk", "غسق دافئ (قليل الضوء الأزرق)", 0xFF24201C.toInt(), 0xFF2B2621.toInt(), 0xFF302A25.toInt(), 0xFF433B33.toInt(), 0xFFD9A066.toInt(), 0xFFC98B5B.toInt(), 0xFFE6DCCF.toInt(), 0xFF9C9082.toInt(), 0xFFD9695F.toInt(), 0xFF9BC088.toInt()),
        // أزواج الوضع الداكن للثيمات الفاتحة (سويتش داكن/فاتح)
        Theme("mxd", "MX داكن", 0xFF121212.toInt(), 0xFF1B1B1B.toInt(), 0xFF1E1E1E.toInt(), 0xFF2E2E2E.toInt(), 0xFF4FA3F7.toInt(), 0xFF2196F3.toInt(), 0xFFEDEDED.toInt(), 0xFF9AA0A6.toInt(), 0xFFFF6B6B.toInt(), 0xFF5CD68A.toInt()),
        Theme("win11d", "ويندوز 11 داكن", 0xFF202020.toInt(), 0xFF282828.toInt(), 0xFF2C2C2C.toInt(), 0xFF3D3D3D.toInt(), 0xFF60CDFF.toInt(), 0xFF4CC2FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFA0A0A0.toInt(), 0xFFFF99A4.toInt(), 0xFF6CCB5F.toInt()),
        Theme("winxp", "ويندوز XP", 0xFFECE9D8.toInt(), 0xFFECE9D8.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F9DB9.toInt(), 0xFF2A66C8.toInt(), 0xFF3C9C3C.toInt(), 0xFF000000.toInt(), 0xFF4A4A4A.toInt(), 0xFFC83232.toInt(), 0xFF3C9C3C.toInt())
    )
    fun byId(id: String?): Theme = all.firstOrNull { it.id == id } ?: all[0]   // all[0] = mx
    private val PAIRS = mapOf("mx" to "mxd", "win11" to "win11d", "light" to "default", "warm" to "eye_dusk", "sand" to "eye_dusk", "eye_sepia" to "eye_dusk",
        "eye_green" to "eye_night", "eye_gray" to "eye_night", "sky" to "mxd", "mint" to "eye_night", "sakura" to "amoled",
        "mxd" to "mx", "win11d" to "win11", "default" to "light", "eye_dusk" to "warm", "eye_night" to "eye_gray", "amoled" to "mx", "amoled_w" to "mx", "infinix" to "mx", "winxp" to "win11")
    /** (v352) سويتش داكن/فاتح: الثيم المقابل للحالي (فاتح ↔ داكن) */
    fun partner(id: String): String = PAIRS[id] ?: if (byId(id).isLight) "eye_night" else "mx"
}
