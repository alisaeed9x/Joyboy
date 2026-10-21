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
        // (v467) قايمة منضّفة بطلب صاحب التطبيق: سمة واحدة من كل نوع (اتحذف: أموليد الأصفر، دافئ، رملي، أزرق هادئ المكرر، حماية العين، رمادي ناعم) —
        // ونصوص السمات الفاتحة (الـ muted) بقت غامقة/سودا عشان تبان على الخلفيات الفاتحة (فانيلا/كريمي/رملي)
        Theme("mx", "كريمي دافئ (أساسي)", 0xFFF7F1E6.toInt(), 0xFFEFE5D0.toInt(), 0xFFFFFBF2.toInt(), 0xFFDCC9A8.toInt(), 0xFF9A5B12.toInt(), 0xFFC65D21.toInt(), 0xFF2A1D12.toInt(), 0xFF4A3F30.toInt(), 0xFFC6493F.toInt(), 0xFF2E86D6.toInt()),
        Theme("default", "افتراضي (غرفة تحكم)", 0xFF0B0E11.toInt(), 0xFF12161B.toInt(), 0xFF1A1F26.toInt(), 0xFF262D36.toInt(), 0xFFF5A623.toInt(), 0xFF43C6D0.toInt(), 0xFFECEFF2.toInt(), 0xFF7C8591.toInt(), 0xFFE85C5C.toInt(), 0xFFF5A623.toInt()),
        Theme("light", "فاتح", 0xFFF5F6F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE1E4EA.toInt(), 0xFFE08E1D.toInt(), 0xFF1596A0.toInt(), 0xFF1B1F27.toInt(), 0xFF3F4650.toInt(), 0xFFD64545.toInt(), 0xFFE08E1D.toInt()),
        Theme("sakura", "ساكورا", 0xFFFFF5F7.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFAFB.toInt(), 0xFFF3D9E1.toInt(), 0xFFE8628A.toInt(), 0xFF8E7CC3.toInt(), 0xFF2E1F26.toInt(), 0xFF54424C.toInt(), 0xFFD64545.toInt(), 0xFFE8628A.toInt()),
        Theme("sky", "سماوي", 0xFFF3F8FC.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFDCE9F3.toInt(), 0xFF2E86D6.toInt(), 0xFF4FBFD9.toInt(), 0xFF1B2733.toInt(), 0xFF3D4F61.toInt(), 0xFFD64545.toInt(), 0xFF2E86D6.toInt()),
        Theme("win11", "ويندوز 11", 0xFFF3F3F3.toInt(), 0xFFFBFBFB.toInt(), 0xFFFFFFFF.toInt(), 0xFFE5E5E5.toInt(), 0xFF0078D4.toInt(), 0xFF005FB8.toInt(), 0xFF1B1B1B.toInt(), 0xFF3B3B3B.toInt(), 0xFFC42B1C.toInt(), 0xFF0078D4.toInt()),
        Theme("infinix", "إنفينكس", 0xFF121214.toInt(), 0xFF1B1B1F.toInt(), 0xFF232327.toInt(), 0xFF333338.toInt(), 0xFFFF5722.toInt(), 0xFFFFA000.toInt(), 0xFFF5F5F7.toInt(), 0xFF96969C.toInt(), 0xFFFF3B30.toInt(), 0xFFFF5722.toInt()),
        Theme("amoled_w", "أموليد", 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0C0C0C.toInt(), 0xFF262626.toInt(), 0xFF6AB0FF.toInt(), 0xFF8AB4F8.toInt(), 0xFFFFFFFF.toInt(), 0xFFA0A0A0.toInt(), 0xFFFF6B6B.toInt(), 0xFF6AB0FF.toInt()),
        Theme("eye_sepia", "ورقي مريح (سيبيا)", 0xFFF4ECD8.toInt(), 0xFFFAF4E4.toInt(), 0xFFFBF6E9.toInt(), 0xFFE0D3B6.toInt(), 0xFF8B5E34.toInt(), 0xFF8C7048.toInt(), 0xFF433422.toInt(), 0xFF5C4B30.toInt(), 0xFFB5483A.toInt(), 0xFF8B5E34.toInt()),
        Theme("eye_night", "ليلي مريح", 0xFF1C2128.toInt(), 0xFF222831.toInt(), 0xFF262D36.toInt(), 0xFF343C47.toInt(), 0xFF7FB2E5.toInt(), 0xFF8FBCBB.toInt(), 0xFFD6DCE4.toInt(), 0xFF8A95A3.toInt(), 0xFFE07B7B.toInt(), 0xFF7FB2E5.toInt()),
        Theme("eye_dusk", "غسق دافئ (قليل الضوء الأزرق)", 0xFF24201C.toInt(), 0xFF2B2621.toInt(), 0xFF302A25.toInt(), 0xFF433B33.toInt(), 0xFFD9A066.toInt(), 0xFFC98B5B.toInt(), 0xFFE6DCCF.toInt(), 0xFF9C9082.toInt(), 0xFFD9695F.toInt(), 0xFFD9A066.toInt()),
        Theme("mxd", "MX داكن", 0xFF121212.toInt(), 0xFF1B1B1B.toInt(), 0xFF1E1E1E.toInt(), 0xFF2E2E2E.toInt(), 0xFF4FA3F7.toInt(), 0xFF2196F3.toInt(), 0xFFEDEDED.toInt(), 0xFF9AA0A6.toInt(), 0xFFFF6B6B.toInt(), 0xFF4FA3F7.toInt()),
        Theme("win11d", "ويندوز 11 داكن", 0xFF202020.toInt(), 0xFF282828.toInt(), 0xFF2C2C2C.toInt(), 0xFF3D3D3D.toInt(), 0xFF60CDFF.toInt(), 0xFF4CC2FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFA0A0A0.toInt(), 0xFFFF99A4.toInt(), 0xFF60CDFF.toInt()),
        Theme("winxp", "ويندوز XP", 0xFFECE9D8.toInt(), 0xFFF5F4EC.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F9DB9.toInt(), 0xFF0054E3.toInt(), 0xFF7A9CC6.toInt(), 0xFF000000.toInt(), 0xFF4A4A4A.toInt(), 0xFFC83232.toInt(), 0xFF2A66C8.toInt()),
        Theme("win7", "ويندوز 7 (إيرو)", 0xFFD6E4F0.toInt(), 0xFFEAF2FA.toInt(), 0xFFFFFFFF.toInt(), 0xFF8FA8BF.toInt(), 0xFF1F6FB2.toInt(), 0xFF57A7D8.toInt(), 0xFF10222E.toInt(), 0xFF33424F.toInt(), 0xFFC42B1C.toInt(), 0xFF1F6FB2.toInt())
    )

    fun byId(id: String?): Theme = if (id == "custom") custom() else all.firstOrNull { it.id == id } ?: all[0]   // all[0] = mx

    // (v469) D1 استوديو السمات: سمة مخصصة بتتولّد من سمة أساس + لون تمييز مختار + إزاحة تشبّع/إضاءة — بتتحفظ في Cfg وبتتطبّق من byId("custom") على الواجهة كلها
    private fun shift(c: Int, satD: Float, ligD: Float): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((c shr 16) and 255, (c shr 8) and 255, c and 255, hsv)
        hsv[1] = (hsv[1] + satD).coerceIn(0.05f, 1f)
        hsv[2] = (hsv[2] + ligD).coerceIn(0.08f, 1f)
        return android.graphics.Color.HSVToColor(hsv)
    }
    fun custom(): Theme {
        var base = byId(Cfg.str("cust_base", "mx")); if (base.id == "custom") base = all[0]
        val hex = Cfg.str("cust_accent", "")
        val acc = if (hex.length >= 6) try { android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#" + hex) } catch (_: Throwable) { base.accent } else base.accent
        val s = Cfg.int("cust_sat", 0).coerceIn(-50, 50) / 100f
        val l = Cfg.int("cust_light", 0).coerceIn(-50, 50) / 100f
        return Theme("custom", "🎨 سمتي المخصصة", base.bg, base.surface, base.card, base.border,
            shift(base.primary, s * 0.6f, l * 0.6f), shift(acc, s, l), base.text, base.muted, base.danger, base.success)
    }
    private val PAIRS = mapOf("mx" to "mxd", "win11" to "win11d", "light" to "default", "eye_sepia" to "eye_dusk", "eye_night" to "sky",
        "sky" to "eye_night", "sakura" to "amoled_w", "amoled_w" to "mx", "infinix" to "mx", "winxp" to "win11d", "win7" to "win11d",
        "mxd" to "mx", "win11d" to "win11", "default" to "light", "eye_dusk" to "eye_sepia")
    /** (v352) سويتش داكن/فاتح: الثيم المقابل للحالي (فاتح ↔ داكن) */
    fun partner(id: String): String = PAIRS[id] ?: if (byId(id).isLight) "eye_night" else "mx"
}
