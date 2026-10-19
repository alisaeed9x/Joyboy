package com.tttt.subtitler

/** (v238) بريسيتس الترجمة: 4 بس. كل بريست بيظبط **كل** إعدادات الترجمة وتوزيع المفاتيح بحيث مفيش مفتاح يفضل من غير شغل. كلهم على Models.DEFAULT (flash-lite-latest) */
object TrPresets {
    class P(val id: String, val icon: String, val name: String, val desc: String, val build: (Int) -> Map<String, String>)

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** (مفاتيح أساسية، احتياطية) من عدد المفاتيح الكلي n — الباقي منقّح. 0,0 = توزيع تلقائي (n ≤ 2: الكل أساسي).
     *  بدون تنقيح: كل المفاتيح تترجم ماعدا واحد احتياطي (بيجدد الباتشات الفاشلة وبيشتغل في مهام الشخصيات/الاسم). مع التنقيح: ~٢٥٪ منقّحين + احتياطي واحد من 5 مفاتيح.
     *  المنقّح والاحتياطي بيشتغلوا كمان في التنقيح الجزئي (Engine.partialKeys) فمفيش مفتاح بيقعد فاضي. */
    fun split(n: Int, refine: Boolean): Pair<Int, Int> {
        if (n <= 2) return 0 to 0
        if (!refine) return (n - 1) to 1
        val ref = Math.round(n * 0.25).toInt().coerceAtLeast(1)
        val bk = if (n >= 5) 1 else 0
        return (n - ref - bk).coerceAtLeast(1) to bk
    }

    private fun mk(n: Int, kmode: Int, chunk: Int, aheadMin: Int, hls: Int, prefine: Boolean, hitiming: Boolean, soundtags: Boolean, vis: Boolean): Map<String, String> {
        val (nm, nb) = split(n, prefine)
        val main = if (nm > 0) nm else n
        // «المقاطع قدّام مكان التشغيل» لازم تكون أكتر من عدد المفاتيح الأساسية وإلا مفاتيح بتقعد مستنية
        val ahead = maxOf(aheadMin, main + 1).coerceAtMost(20)
        return linkedMapOf(
            "kmode" to KeyMode.SPLIT_A.toString(), "chunk" to chunk.toString(), "ahead" to ahead.toString(), "hls_ahead" to hls.toString(),
            "prefine" to b(prefine), "hitiming" to b(hitiming), "soundtags" to b(soundtags), "vis_auto" to b(vis),
            "model" to Models.DEFAULT, "trstart" to "1", "keepgoing" to "1",
            "nmain" to nm.toString(), "nback" to nb.toString(), "hard_mode" to "0")
    }

    /** (v240) بريست «ترجمة الهارد سب»: الوضع البصري بس (بيترجم الترجمة المحروقة + النصوص في الصورة) والترجمة الصوتية واقفة */
    const val HARD_ID = "hardsub"

    val all: List<P> = listOf(
        P("fast", "⚡", "ترجمة سريعة", "كل المفاتيح تترجم بالتوازي (ماعدا احتياطي واحد)، مقاطع 90ث، من غير تنقيح ولا أصوات خلفية ولا وضع بصري.") { n -> mk(n, 2, 90, 4, 5, false, false, false, false) },
        P("accurate", "🎯", "ترجمة دقيقة", "مقاطع 30ث ودقة توقيت أعلى، تنقيح جزئي بمفاتيح مخصوصة، وأصوات الخلفية شغّالة.") { n -> mk(n, 1, 30, 3, 3, true, true, true, false) },
        P("transcript", "📝", "ترانسكريبشن ثم سياق", "كل المفاتيح تنسخ الكلام، وبعدها النص كله يتترجم بالسياق الكامل (ضمائر وأجناس مظبوطة).") { n -> mk(n, 4, 60, 4, 4, false, true, false, false) },
        P("full", "👁", "شاملة (صوت + بصري)", "الصوت بدقة + تنقيح + الوضع البصري للنص اللي على الشاشة (بمفتاح الوضع البصري).") { n -> mk(n, 1, 60, 3, 3, true, true, true, true) },
        P(HARD_ID, "🔤", "ترجمة الهارد سب (بصري بس)", "الوضع البصري بيترجم الترجمة المحروقة في الفيديو + أي نصوص في الصورة. من غير ترجمة صوتية خالص.") { _ -> linkedMapOf("hard_mode" to "1", "vis_auto" to "1") }
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** بيكتب إعدادات البريست كلها في Cfg (الإعدادات القديمة بتتغطّى) وبيرجّع اللي اتكتب */
    fun apply(pr: P): Map<String, String> {
        val kv = pr.build(Cfg.allMainKeys().size)
        for ((k, v) in kv) Cfg.put(k, v)
        Cfg.put("preset_last", pr.id)
        return kv
    }
}
