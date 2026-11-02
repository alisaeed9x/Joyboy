package com.tttt.subtitler

import java.util.Locale

/**
 * (v471) لغة وبلد محتوى يوتيوب في مكان واحد (كانت متفرّقة: NewPipe عربي/مصر، طلبات الحساب عربي/مصر، والباقي ثابت).
 * الإعداد yt_hl: auto (لغة الجهاز؛ عربي لو مش معروفة) · ar · en · …
 * بيتستخدم في: تهيئة NewPipe، طلبات InnerTube بتاعة الحساب والطلبات الخفيفة، وهيدر Accept-Language.
 */
object YtLocale {
    fun hl(): String {
        val v = try { Cfg.str("yt_hl", "auto").trim().lowercase() } catch (_: Throwable) { "auto" }
        if (v.isNotEmpty() && v != "auto") return v
        val d = try { Locale.getDefault().language.lowercase() } catch (_: Throwable) { "" }
        return if (d.isBlank() || d == "und") "ar" else d
    }

    fun gl(): String {
        val d = try { Locale.getDefault().country.uppercase() } catch (_: Throwable) { "" }
        if (d.length == 2) return d
        return if (hl() == "ar") "EG" else "US"
    }

    fun acceptLanguage(): String { val h = hl(); return if (h == "en") "en-" + gl() + ",en;q=0.9" else h + "-" + gl() + "," + h + ";q=0.9,en;q=0.8" }

    /** اسم اللغة المعروض في الإعدادات */
    val names = listOf("تلقائي (لغة الجهاز)", "العربية", "English")
    val values = listOf("auto", "ar", "en")
}
