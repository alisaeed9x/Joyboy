package com.tttt.subtitler

// موديلات الأصل (BUILTIN_MODELS) + الكوتة اليومية (QUOTA_LIMITS) — مستخرجة من الـ HTML
class ModelInfo(val id: String, val desc: String)
object Models {
    /** (v141) موديل ثابت بالاسم: Gemini 3.1 Flash-Lite. مفيش «latest» اللي بيتحرك لوحده لموديل تاني من غير ما تعرف */
    const val DEFAULT = "gemini-flash-lite-latest"
    /** الاسم المتحرك القديم: بيتحوّل مرة واحدة للثابت (Cfg.init) */
    const val OLD_ALIAS = "gemini-flash-lite-latest"
    /** القايمة موديل واحد بس؛ أي موديل تاني بيتختار من «جلب كل الموديلات» أو بيتكتب يدوي */
    val builtin = listOf(ModelInfo(DEFAULT, "Gemini Flash-Lite (latest) — الاسم المتحرك: دايمًا أحدث flash-lite من جوجل"))
    val quota = mapOf(DEFAULT to 1000, OLD_ALIAS to 1000, "gemini-3.1-flash-lite" to 1000, "gemini-2.5-flash-lite" to 1500, "gemini-3-flash" to 1500,
        "gemini-2.5-flash" to 1500, "gemini-3.5-flash" to 250, "gemini-2.5-pro" to 50)
    /** (v227) موديل بديل لما الأساسي يرجّع 503 ورا بعض */
    fun alt(cur: String): String = if (cur.contains("2.5")) DEFAULT else "gemini-2.5-flash-lite"
    const val QUOTA_DEFAULT = 500
    fun quotaOf(id: String) = quota[id] ?: QUOTA_DEFAULT
}

