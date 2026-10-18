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
    fun id(): String = try { Cfg.str("tr_mode_id", "") } catch (_: Throwable) { "" }
    fun semi(): Boolean = id() == SEMI
    fun offline(): Boolean = id() == OFF
    /** true = جيميناي ممنوع تمامًا (مفاتيح، تحويل لهجة، بصري، تسليم احتياطي) */
    fun noGemini(): Boolean { val i = id(); return i == SEMI || i == OFF }
    const val NO_VISUAL_MSG = "👁 الوضع البصري شغّال في الوضع الأونلاين (جيميناي) بس"
    const val NO_DIALECT_MSG = "🗣 اللهجات في الوضع الأونلاين (جيميناي) بس — الوضع ده فصحى"
}
