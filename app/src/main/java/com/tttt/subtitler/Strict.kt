package com.tttt.subtitler

/**
 * (v410) الأوضاع التلاتة وحدودها:
 *  ☁ أونلاين (g_wav / g_aac) — جيميناي: لهجات + أسلوب + ضمائر + بصري
 *  🌗 نص-أونلاين (loc_google) — Whisper على الجهاز + جوجل (فصحى بس) — جيميناي ممنوع
 * المعرّف بيتخزّن في tr_mode_id. أي كود بيستخدم جيميناي لازم يسأل noGemini() الأول.
 */
object Strict {
    const val SEMI = "loc_google"
    /** (v425) الأوضاع المشالة وبديلها: أوفلاين ML Kit / لينجو ← وشبر + LibreTranslate · وشبر + جيميناي (محلي/سياق) ← جيميناي */
    private val REMOVED = mapOf("off_ml" to SEMI, "off_vl" to SEMI, "vl_gemini" to SEMI, "loc_ai" to "g_wav", "loc_ctx" to "g_wav", "s_only" to "w_only", "visual" to "g_wav", "loc_beat" to SEMI, "b_gem" to "g_wav")   // (v452) Beat اتشال   // (v427) SenseVoice/بصري اتشالوا
    fun id(): String = try {
        var i = Cfg.str("tr_mode_id", "")
        REMOVED[i]?.let { m -> Cfg.put("tr_mode_id", m); Cfg.put("tr_saved", "0"); i = m }   // الإعداد المحفوظ بيتمسح عشان يسأل من جديد
        i
    } catch (_: Throwable) { "" }
    fun semi(): Boolean = id() == SEMI
    /** true = جيميناي ممنوع تمامًا (مفاتيح، تحويل لهجة، بصري، تسليم احتياطي) */
    fun noGemini(): Boolean = id() == SEMI
    /** (v440) 🆓 ترجمة بـ FreeLLM الصوتي: نفس مسار جيميناي (صوت ← موديل) بس على موديلات Gemini المجانية بالتوزيع — مفيش وشبر ولا مفاتيح جيميناي بتاعتك */
    const val FREE_GEM = "f_gem"
    fun freeGem(): Boolean = id() == FREE_GEM
    /** مفاتيح جيميناي مش بتتلمس خالص (نص-أونلاين / وشبر + FreeLLM) */
    /** (v440) 🆓 وشبر + FreeLLM: وضع وشبر بموديلات Gemini/Groq المجانية بمفاتيحها (مفيش جيميناي خالص) */
    const val FREE_TXT = "loc_free"
    fun freeText(): Boolean = id() == FREE_TXT
    fun noGemKeys(): Boolean = noGemini() || freeText()
    const val NO_VISUAL_MSG = "👁 الوضع البصري شغّال في الوضع الأونلاين (جيميناي) بس"
    const val NO_DIALECT_MSG = "🗣 اللهجات في الوضع الأونلاين (جيميناي) بس — الوضع ده فصحى"
}
