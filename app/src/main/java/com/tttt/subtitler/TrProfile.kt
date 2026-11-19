package com.tttt.subtitler

/**
 * (v1098) T3 — متكيّف على v1095: tr_kind/tr_engine مفاتيح تفضيل (لو فاضية بتتحسب من الخطة المحفوظة في TrWizard)،
 * والمحفوظ بيشمل كمان tr_bal/fastmode/dual_listen لأن وضع الترجمة في 1095 هو اللي بيحدّد موضع السلايدر (TrSlider).
 *
 * (v1076) سلايدر «النوع» التلاتي + اللهجة والشدة + حفظ الإعدادات للفيديوهات الجاية.
 *
 * المواضع (شمال ← يمين): تفريغ بس · ترجمة فصحى · ترجمة بلهجة.
 * مفيش مفتاح جديد للنوع — بيتحسب من المفاتيح القديمة:
 *   tr_kind = transcribe            ← تفريغ
 *   tr_kind = translate + لهجة فاضية ← فصحى
 *   tr_kind = translate + لهجة       ← لهجة
 * اللهجة بتتكتب في lang و conv_dialect زي ما TrWizard.apply بيعمل (الفصحى = "").
 * آخر لهجة اخترتها بتتحفظ في tr_dia_last عشان ترجع لها لو بدّلت لفصحى وبعدين لهجة.
 *
 * زرار «حفظ»: بيصوّر الإعدادات دي في مفاتيح trp_* ، ومع كل فيديو جديد بترجع تتطبّق (restore).
 * قبل أول حفظ مفيش أي تغيير في السلوك القديم.
 */
object TrProfile {
    const val TX = 0
    const val MSA = 1
    const val DIA = 2
    const val DEF_DIALECT = "مصري"
    val degrees = listOf("خفيفة", "متوسطة", "شديدة")

    private const val SAVED = "trp_saved"
    /** الإعدادات اللي بتتحفظ مع بعض */
    private val keys = listOf("tr_kind", "tr_engine", "lang", "conv_dialect", "tone_strength", "tr_slider", "tr_dia_last", "tr_bal", "fastmode", "dual_listen")   // (v1098) الثلاثة الأخيرة = أوضاع 1095

    /** نقي (متختبر): النوع من القيم الخام */
    fun modeOf(kind: String, convDialect: String, lang: String): Int {
        if (kind == "transcribe") return TX
        val d = convDialect.ifBlank { lang }.trim()
        return if (d.isEmpty() || d == "فصحى") MSA else DIA
    }

    /** (v1098) النوع/المحرك: المفتاح لو اتحدد، وإلا من آخر نظام اشتغل (الخطة المحفوظة) — وإلا ترجمة جيميناي */
    fun kind(): String = try {
        Cfg.str("tr_kind", "").ifBlank { if (TrWizard.savedPlan()?.only == true) "transcribe" else "translate" }
    } catch (_: Throwable) { "translate" }
    fun engine(): String = try {
        Cfg.str("tr_engine", "").ifBlank { if (TrWizard.savedPlan()?.offline == true) "off" else "gem" }
    } catch (_: Throwable) { "gem" }

    fun mode(): Int = try { modeOf(kind(), Cfg.str("conv_dialect", ""), Cfg.str("lang", "فصحى")) } catch (_: Throwable) { MSA }

    /** (v1100) T2b: هل المستخدم اختار لهجة صريحة؟ — الشدة وقتها لازم تشتغل حتى لو السلايدر سريع (Engine.toneB) */
    fun forcesTone(): Boolean = try { mode() == DIA && !Strict.hideTrOpts() } catch (_: Throwable) { false }

    fun dialects(): List<String> = TrWizard.dialects.filter { it != "فصحى" }

    /** اللهجة الحالية لو فيه، وإلا آخر لهجة اتختارت، وإلا مصري */
    fun dialect(): String {
        val d = Cfg.str("conv_dialect", "").ifBlank { Cfg.str("lang", "") }.trim()
        if (d.isNotEmpty() && d != "فصحى") return d
        return Cfg.str("tr_dia_last", DEF_DIALECT).ifBlank { DEF_DIALECT }
    }

    fun degree(): String = Cfg.str("tone_strength", "متوسطة").let { if (it in degrees) it else "متوسطة" }

    /** d فاضي = فصحى */
    fun applyDialect(d: String) {
        if (d.isNotBlank()) Cfg.put("tr_dia_last", d)
        Cfg.put("lang", d.ifBlank { "فصحى" })
        Cfg.put("conv_dialect", d)
        TrWizard.syncSaved("conv_dialect", d)
    }

    fun setMode(m: Int) {
        when (m) {
            TX -> Cfg.put("tr_kind", "transcribe")
            MSA -> { Cfg.put("tr_kind", "translate"); applyDialect("") }
            else -> { Cfg.put("tr_kind", "translate"); applyDialect(dialect()) }
        }
        // (v1119) الاختيار الصريح هنا هو مصدر الحقيقة ل tr_s_only — عشان الخطة المحفوظة تطابق النوع
        try { TrWizard.syncSaved("tr_s_only", if (m == TX) "1" else "0") } catch (_: Throwable) {}
    }

    fun setDegree(g: String) { if (g in degrees) Cfg.put("tone_strength", g) }

    /** الشدة بتأثر لما الأسلوب مش «حرفي» (الأسلوب الحرفي بيطفّي بلوك الأسلوب كله) */
    fun styleIsLiteral(): Boolean = Cfg.str("tone_style", "") == "حرفي"

    // ===== الحفظ =====
    private fun cur(k: String): String = when (k) {
        "tr_kind" -> kind()
        "tr_engine" -> engine()
        "tr_bal" -> if (Cfg.bool(k, false)) "1" else "0"
        "fastmode" -> if (Cfg.bool(k, false)) "1" else "0"
        "dual_listen" -> Cfg.int(k, 1).toString()
        "tr_slider" -> TrSlider.pos().toString()
        "tone_strength" -> degree()
        "lang" -> Cfg.str(k, "فصحى").ifBlank { "فصحى" }
        "tr_dia_last" -> dialect()
        else -> Cfg.str(k, "")
    }

    fun hasSaved(): Boolean = try { Cfg.bool(SAVED, false) } catch (_: Throwable) { false }

    fun save() {
        for (k in keys) Cfg.put("trp_$k", cur(k))
        Cfg.put(SAVED, "1")
    }

    /** فيه فرق بين الإعدادات الحالية والمحفوظة؟ */
    fun dirty(): Boolean = hasSaved() && keys.any { cur(it) != Cfg.str("trp_$it", "") }

    /** سطر ملخّص للمحفوظ: «🗣 لهجة مصري · شدة متوسطة · جيميناي» */
    fun savedSummary(): String {
        if (!hasSaved()) return ""
        val kind = Cfg.str("trp_tr_kind", "translate").ifBlank { "translate" }; val dia = Cfg.str("trp_conv_dialect", "")
        val what = when (modeOf(kind, dia, Cfg.str("trp_lang", "فصحى"))) {
            TX -> "✍ تفريغ بس"
            MSA -> "🔤 ترجمة فصحى"
            else -> "🗣 لهجة " + dia.ifBlank { Cfg.str("trp_lang", "") }
        }
        val eng = if (Cfg.str("trp_tr_engine", "gem") == "off") "📴 وشبر" else "☁ جيميناي"
        return what + (if (kind != "transcribe" && modeOf(kind, dia, Cfg.str("trp_lang", "فصحى")) == DIA) " · شدة " + Cfg.str("trp_tone_strength", "متوسطة") else "") + " · " + eng
    }

    /**
     * بيطبّق المحفوظ على الإعدادات الشغّالة (مع كل فيديو جديد). بيرجّع true لو غيّر حاجة.
     * مابيشتغلش لو مفيش حفظ، أو لو الشاشة بتتحكم في تلفزيون (الكتابة وقتها للتلفزيون مش للجهاز ده).
     */
    fun restore(): Boolean {
        if (!hasSaved() || Cfg.tvOv != null) return false
        var changed = false
        for (k in keys) {
            val v = Cfg.str("trp_$k", "")
            if (v.isEmpty() && k != "conv_dialect") continue
            if (cur(k) == v && (k != "conv_dialect" || Cfg.str(k, "") == v)) continue
            Cfg.put(k, v); changed = true
        }
        if (changed) TrWizard.syncSaved("conv_dialect", Cfg.str("conv_dialect", ""))
        return changed
    }
}
