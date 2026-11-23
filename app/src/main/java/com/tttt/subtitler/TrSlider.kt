package com.tttt.subtitler

/**
 * (v1097) T2 — سلايدر واحد «سريع ↔ دقيق» (0..10) متكيّف على v1095.
 * المناطق بتتمرّر على أوضاع v1095 الموجودة (مش بتستبدلها):
 *   0..3  ⚡ سريع    → fastmode=1  · tr_bal=0
 *   4..6  ⚖ متوازن  → tr_bal=1 (محرك FastTr)
 *   7..10 🎯 الأدق   → fastmode=0 · tr_bal=0 · dual_listen=2
 * ٧٥/٢٥ (dual_listen=0) بيفضل من «🔧 إعدادات متقدمة» (بيبان هنا كـ٧).
 * الموضع جوه المنطقة بيتحفظ في tr_slider، وبيتحدّد التلقائي من الإعدادات لو اتغيّرت من أي قايمة تانية.
 * البلوكات (id) بتتحكم في الـEngine بس لو wired = true (اتوصّلت فعلًا في Engine.kt بتاع 1095).
 */
object TrSlider {
    const val KEY = "tr_slider"
    const val MAX = 10
    const val SPEED = 0; const val BAL = 1; const val ACC = 2   // (v1199) وضع «مفتاح واحد» اتشال

    /** speed = true: بلوك بيزوّد السرعة (شغّال من 0 لحد t). speed = false: بيزوّد الدقة (شغّال من t لحد 10). wired = اتوصّل بالـEngine في 1095 */
    class Block(val id: String, val label: String, val speed: Boolean, val t: Int, val wired: Boolean)

    val blocks: List<Block> = listOf(
        Block("direct", "⚡ مباشر: صوت ← ترجمة في طلب واحد (stream)", true, 2, false),
        Block("light", "🪶 برومبت خفيف لأول باتشين", true, 2, true),
        Block("race", "🏁 سباق مفتاحين + تقسيم أول باتش لطلبين", true, 2, true),
        Block("hedge", "🛡 نسخة احتياطية للباتش المتأخر", true, 6, true),
        Block("cache", "💾 كاش الأسطر المتكررة", true, 7, true),
        Block("tone", "🗣 أسلوب الترجمة (شعبي/جرئ…)", false, 2, true),
        Block("holes", "🕳 سدّ الثغرات الصوتية", false, 2, true),
        Block("twostep", "🧩 تفريغ ثم ترجمة مجمّعة بالسياق", false, 3, false),
        Block("listen", "🎧 بلوك دقة السماع", false, 2, true),
        Block("ctx", "↔ سطر قبل/بعد الباتش", false, 2, true),
        Block("fewshot", "📝 أمثلة الأسلوب", false, 4, true),
        Block("topic", "🧠 ذاكرة الموضوع", false, 4, true),
        Block("fidelity", "🔢 أمانة الأرقام والأسماء", false, 4, true),
        Block("refine", "✍ تنقيح جزئي ونهائي", false, 5, true),
        Block("chars", "🎭 تحليل الشخصيات", false, 6, true),
        Block("pron", "🩺 تصحيح الضمائر والأجناس", false, 6, true),
        Block("relisten", "🔁 إعادة سماع الجمل واطية الثقة", false, 7, true),
        Block("cross", "🎯 تسميع مزدوج (لغير الإنجليزي)", false, 8, true),
        Block("slowaudio", "🐢 صوت بنص السرعة لأول خطوتين", false, 8, false),
        Block("hitiming", "⏱ دقة توقيت أعلى", false, 9, true),
        Block("refine2", "✨ تحسين على مرحلتين", false, 10, true),
        Block("crossall", "🎯 التسميع المزدوج حتى للإنجليزي", false, 10, true)
    )
    private val byId: Map<String, Block> = blocks.associateBy { it.id }

    fun clamp(p: Int) = p.coerceIn(0, MAX)
    fun zone(p: Int): Int = when (clamp(p)) { in 0..3 -> SPEED; in 4..6 -> BAL; else -> ACC }   // (v1199) 3 مناطق: سريع · متوازن · دقيق
    private fun defOf(z: Int) = when (z) { SPEED -> 2; BAL -> 5; else -> 8 }

    /** المنطقة الحالية من إعدادات v1095 الفعلية (اللي ممكن تتغيّر من أي قايمة) */
    fun zoneNow(): Int = try {
        if (Cfg.bool("tr_bal", false)) BAL else if (Cfg.bool("fastmode", false)) SPEED else ACC
    } catch (_: Throwable) { ACC }

    /** نقي: هل البلوك شغّال عند الموضع p؟ (id مجهول = شغّال، عشان ما نكسرش حاجة بالغلط) */
    fun onAt(id: String, p: Int): Boolean {
        val b = byId[id] ?: return true
        val q = clamp(p)
        return if (b.speed) q <= b.t else q >= b.t
    }

    /** الموضع: المحفوظ لو لسه في نفس منطقة الإعدادات الحالية، وإلا الافتراضي للمنطقة (٧ لو ٧٥/٢٥) */
    fun pos(): Int = try {
        val z = zoneNow(); val s = Cfg.int(KEY, -1)
        if (s in 0..MAX && zone(s) == z) s
        else if (z == ACC && Cfg.int("dual_listen", 1) == 0) 7 else defOf(z)
    } catch (_: Throwable) { 5 }

    /** بيحفظ الموضع، ولو المنطقة اتغيّرت بيظبط أوضاع v1095 (وملمسش dual_listen جوه نفس المنطقة عشان ٧٥/٢٥ ما يتمسحش) */
    fun setPos(p: Int) {
        try {
            val q = clamp(p); val z = zone(q); val was = zoneNow()
            Cfg.put(KEY, q.toString())
            if (z != was) when (z) {
                SPEED -> { Cfg.put("tr_bal", "0"); Cfg.put("tr_one", "0"); Cfg.put("fastmode", "1") }
                BAL -> { Cfg.put("tr_bal", "1"); Cfg.put("tr_one", "0") }
                else -> { Cfg.put("tr_bal", "0"); Cfg.put("tr_one", "0"); Cfg.put("fastmode", "0"); Cfg.put("dual_listen", "2") }
            }
        } catch (_: Throwable) {}
    }
    fun on(id: String): Boolean = onAt(id, pos())

    fun speedBlocks(): List<Block> = blocks.filter { it.speed && it.wired }
    fun accuracyBlocks(): List<Block> = blocks.filter { !it.speed && it.wired }

    /** (v1199) 3 محطات: ⚡ سريع (0-3) · ⚖ متوازن (4-6) · 🎯 دقيق (7-10). الموضع الداخلي (0..10) لسه هو اللي بيتحفظ وبيتقرا في Engine */
    const val STATIONS = 3
    private val REP = intArrayOf(2, 5, 8)
    fun station(p: Int): Int = when (clamp(p)) { in 0..3 -> 0; in 4..6 -> 1; else -> 2 }
    fun posOf(s: Int): Int = REP[s.coerceIn(0, STATIONS - 1)]

    fun title(p: Int): String = when (station(p)) {
        0 -> "⚡ سريع"
        1 -> "⚖ متوازن (المحرك البسيط)"
        else -> "🎯 دقيق"
    }
}

/**
 * (v1199) توحيد القيم القديمة: الزراير بتعرض فصحى + مصري بس (باقي اللهجات مخفية مش ممسوحة)، والأسلوب بقى حرفي + عائلي بس.
 * الأساليب القديمة (تلقائي/شعبي/جرئ/+18) بتتقرا حرفي، واللهجات القديمة بتفضل زي ما هي من غير ما نمسح الإعدادات.
 */
object TrNorm {
    const val LITERAL = "حرفي"; const val FAMILY = "عائلي"
    fun style(v: String): String = if (v.trim() == FAMILY) FAMILY else LITERAL
    fun dialect(v: String): String = v.trim()   // (v1200) اللهجات التانية موجودة في الكود زي ما هي — مخفية من الزراير بس
    /** بيتنادى من Cfg.str — بيلمس 4 مفاتيح بس */
    fun fix(k: String, v: String): String = when (k) {
        "tone_style", "style" -> style(v)
        "conv_dialect", "lang" -> dialect(v)
        else -> v
    }
}
