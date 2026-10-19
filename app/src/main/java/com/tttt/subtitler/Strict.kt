package com.tttt.subtitler

/**
 * (v410) الأوضاع التلاتة وحدودها:
 *  ☁ أونلاين (g_wav / g_aac) — جيميناي: لهجات + أسلوب + ضمائر + بصري
 *  🌗 نص-أونلاين (loc_google) — Whisper على الجهاز + جوجل (فصحى بس) — جيميناي ممنوع
 *  📴 أوفلاين (off_ml) — Whisper + ML Kit على الجهاز (فصحى بس) — لا جيميناي ولا نت
 * المعرّف بيتخزّن في tr_mode_id. أي كود بيستخدم جيميناي لازم يسأل noGemini() الأول.
 */
object Strict {
    const val SEMI = "loc_google"
    const val OFF = "off_ml"
    /** (v420) 📴 أوفلاين + لينجو: Whisper + Gemma محلي (بخطوات VideoLingo) وبعده ML Kit كاحتياطي — كله على الجهاز */
    const val OFF_VL = "off_vl"
    /** (v425) الأوضاع المشالة وبديلها: أوفلاين ML Kit / لينجو ← وشبر + LibreTranslate · وشبر + جيميناي (محلي/سياق) ← جيميناي */
    private val REMOVED = mapOf("off_ml" to SEMI, "off_vl" to SEMI, "vl_gemini" to SEMI, "loc_ai" to "g_wav", "loc_ctx" to "g_wav")
    fun id(): String = try {
        var i = Cfg.str("tr_mode_id", "")
        REMOVED[i]?.let { m -> Cfg.put("tr_mode_id", m); Cfg.put("tr_saved", "0"); i = m }   // الإعداد المحفوظ بيتمسح عشان يسأل من جديد
        i
    } catch (_: Throwable) { "" }
    fun semi(): Boolean = id() == SEMI
    fun offline(): Boolean = id() == OFF || id() == OFF_VL
    /** true = جيميناي ممنوع تمامًا (مفاتيح، تحويل لهجة، بصري، تسليم احتياطي) */
    fun noGemini(): Boolean { val i = id(); return i == SEMI || i == OFF || i == OFF_VL }
    const val NO_VISUAL_MSG = "👁 الوضع البصري شغّال في الوضع الأونلاين (جيميناي) بس"
    const val NO_DIALECT_MSG = "🗣 اللهجات في الوضع الأونلاين (جيميناي) بس — الوضع ده فصحى"
}
