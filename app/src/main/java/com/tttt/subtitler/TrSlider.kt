package com.tttt.subtitler

/**
 * (v1075) سلايدر واحد «سريع ↔ دقيق» بيتحكم في بلوكات محرك الترجمة (Engine.kt).
 * الموضع 0..10: 0 = أسرع ما يمكن، 5 = متوازن، 10 = أدق ما يمكن (وأبطأ).
 * كل بلوك ليه عتبة: بلوك السرعة شغّال لما الموضع <= العتبة، وبلوك الدقة شغّال لما الموضع >= العتبة.
 * الشيك بوكس في الواجهة للعرض بس (المستخدم مابيدوسش عليهم) — بيتحسبوا من نفس الجدول ده.
 * منطق نقي (onAt) متختبر؛ on() بيقرا الموضع من الإعدادات.
 */
object TrSlider {
    const val KEY = "tr_slider"
    const val MAX = 10
    const val DEF = 5

    /** speed = true: بلوك بيزوّد السرعة (شغّال من الموضع 0 لحد t). speed = false: بلوك بيزوّد الدقة (شغّال من t لحد 10) */
    class Block(val id: String, val label: String, val speed: Boolean, val t: Int)

    val blocks: List<Block> = listOf(
        // ===== بيزوّد السرعة =====
        Block("direct", "⚡ مباشر: صوت ← ترجمة في طلب واحد (stream)", true, 2),
        Block("light", "🪶 برومبت خفيف لأول باتشين", true, 2),
        Block("race", "🏁 سباق مفتاحين + تقسيم أول باتش لطلبين", true, 2),
        Block("hedge", "🛡 نسخة احتياطية للباتش المتأخر", true, 6),
        Block("cache", "💾 كاش الأسطر المتكررة", true, 7),
        // ===== بيزوّد الدقة =====
        Block("tone", "🗣 أسلوب الترجمة (شعبي/جرئ…)", false, 2),
        Block("holes", "🕳 سدّ الثغرات الصوتية", false, 2),
        Block("twostep", "🧩 تفريغ ثم ترجمة مجمّعة بالسياق", false, 3),
        Block("listen", "🎧 بلوك دقة السماع", false, 3),
        Block("ctx", "↔ سطر قبل/بعد الباتش", false, 3),
        Block("fewshot", "📝 أمثلة الأسلوب", false, 4),
        Block("topic", "🧠 ذاكرة الموضوع", false, 4),
        Block("fidelity", "🔢 أمانة الأرقام والأسماء", false, 4),
        Block("refine", "✍ تنقيح جزئي ونهائي", false, 5),
        Block("chars", "🎭 تحليل الشخصيات", false, 6),
        Block("pron", "🩺 تصحيح الضمائر والأجناس", false, 6),
        Block("relisten", "🔁 إعادة سماع الجمل واطية الثقة", false, 7),
        Block("cross", "🎯 تسميع مزدوج (لغير الإنجليزي)", false, 8),
        Block("slowaudio", "🐢 صوت بنص السرعة لأول خطوتين", false, 8),
        Block("hitiming", "⏱ دقة توقيت أعلى", false, 9),
        Block("refine2", "✨ تحسين على مرحلتين", false, 10),
        Block("crossall", "🎯 التسميع المزدوج حتى للإنجليزي", false, 10)
    )

    private val byId: Map<String, Block> = blocks.associateBy { it.id }

    fun clamp(p: Int) = p.coerceIn(0, MAX)

    /** نقي: هل البلوك شغّال عند الموضع p؟ (id مجهول = شغّال، عشان ما نكسرش حاجة بالغلط) */
    fun onAt(id: String, p: Int): Boolean {
        val b = byId[id] ?: return true
        val q = clamp(p)
        return if (b.speed) q <= b.t else q >= b.t
    }

    fun pos(): Int = try { clamp(Cfg.int(KEY, DEF)) } catch (_: Throwable) { DEF }
    fun setPos(p: Int) { try { Cfg.put(KEY, clamp(p).toString()) } catch (_: Throwable) {} }
    fun on(id: String): Boolean = onAt(id, pos())

    /** المسار المباشر (صوت ← ترجمة في طلب واحد) = الموضع 0..2؛ فوق كده تفريغ ثم ترجمة مجمّعة */
    fun direct(p: Int = pos()): Boolean = onAt("direct", p)

    /** (v1090) المتوازن 4..6 = المحرك البسيط (FastTr) — مش بلوكات المحرك القديم. 0..3 سريع و7..10 دقيق على المحرك القديم */
    const val BAL_LO = 4
    const val BAL_HI = 6
    fun balanced(p: Int = pos()): Boolean = clamp(p) in BAL_LO..BAL_HI

    fun speedBlocks(): List<Block> = blocks.filter { it.speed }
    fun accuracyBlocks(): List<Block> = blocks.filter { !it.speed }

    fun title(p: Int): String = when (clamp(p)) {
        0, 1 -> "⚡ أسرع"
        2, 3 -> "⚡ سريع"
        4, 5, 6 -> "⚖ متوازن (المحرك البسيط)"
        7, 8 -> "🎯 دقيق"
        else -> "🎯 أدق (وأبطأ)"
    }
}
