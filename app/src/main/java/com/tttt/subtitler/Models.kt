package com.tttt.subtitler

// موديلات الأصل (BUILTIN_MODELS) + الكوتة اليومية (QUOTA_LIMITS) — مستخرجة من الـ HTML
class ModelInfo(val id: String, val desc: String)
object Models {
    /** (v459) الموديل الواحد لكل حاجة: gemini-flash-lite-latest — بطلب صريح من صاحب التطبيق (كتابةً): موديل واحد بس، مفيش لفّ ولا بديل */
    const val DEFAULT = "gemini-flash-lite-latest"
    /** الاسم المتحرك القديم: بيتحوّل مرة واحدة للثابت (Cfg.init) */
    const val OLD_ALIAS = "gemini-flash-lite-latest"
    /** القايمة موديل واحد بس؛ أي موديل تاني بيتختار من «جلب كل الموديلات» أو بيتكتب يدوي */
    val builtin = listOf(ModelInfo(DEFAULT, "Gemini Flash-Lite (latest) — الموديل الواحد الثابت لكل الترجمة والتنقيح"))
    val quota = mapOf(DEFAULT to 1000, OLD_ALIAS to 1000, "gemini-3.1-flash-lite" to 1000, "gemini-2.5-flash-lite" to 1500, "gemini-3-flash" to 1500,
        "gemini-2.5-flash" to 1500, "gemini-3.5-flash" to 250, "gemini-2.5-pro" to 50)
    /** (v227) موديل بديل لما الأساسي يرجّع 503 ورا بعض */
    /** (v1242) بطلب صاحب التطبيق (واتساب): موديل واحد flash-lite-latest لكل المفاتيح، والاحتياطي في المفاتيح مش في تغيير الموديل. false = مفيش تبديل موديل */
    const val FALLBACK = false
    private val dead = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var avail: List<String>? = null
    /** (v1241) موديل اتأكد إنه مش متاح (404): مابنجرّبوش تاني في الجلسة دي */
    fun markDead(id: String) { dead.add(id) }
    private fun discover(key: String) {
        if (avail != null || key.isBlank() || key == "off") return
        try { avail = Api.listModels(key).map { it.id } } catch (_: Throwable) {}
    }
    /** (v1326) موديلات مش ليها لازمة في التفريغ/الترجمة (صور/فيديو/صوت/live/تضمين…): بتتستبعد من قايمة الاختيار ومن الاحتياطي */
    val JUNK = Regex("image|imagen|veo|tts|live|audio|native|embed|robotics|computer|aqa")
    /** (v1241) الموديل الاحتياطي لما الأساسي 503: بيتختار من الموديلات الموجودة فعلًا لمفتاحك (GET /models) ومش من الميتة */
    fun alt(cur: String, key: String = ""): String {
        discover(key)
        val av = avail
        val pref = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-3-flash-preview", "gemini-3-flash", "gemini-2.0-flash", "gemini-2.5-flash-lite")
        for (c in pref) if (c != cur && c !in dead && (av.isNullOrEmpty() || c in av)) return c
        if (!av.isNullOrEmpty()) for (c in av) if (c != cur && c !in dead && c.contains("flash") && !JUNK.containsMatchIn(c) && !Regex("thinking|exp").containsMatchIn(c)) return c
        return cur
    }
    const val QUOTA_DEFAULT = 500
    fun quotaOf(id: String) = quota[id] ?: QUOTA_DEFAULT
}


/** (v1237) تتبّع ضغط جيميناي (503/500): بعد خطأين ورا بعض الضغط بيعتبر «شغّال» ٤٥ ثانية — مفيش نسخ احتياطية ولا سباق مفتاحين، ومهلة الطلب ٣٠ ثانية */
object Overload {
    @Volatile private var lastAt = 0L
    @Volatile private var streak = 0
    private val hits = java.util.concurrent.ConcurrentLinkedQueue<Long>()
    fun hit() { streak++; val n = System.currentTimeMillis(); lastAt = n; hits.add(n); while (hits.size > 40) hits.poll() }
    /** (v1304) ضغط ممتد: 3 أخطاء ضغط أو أكتر في آخر 60 ثانية حتى لو بينهم نجاحات (الـstreak بيتصفّر مع أي نجاح، ومع الطلبات المتوازية كان نادرًا ما يتفعّل) */
    fun pressured(): Boolean { val lim = System.currentTimeMillis() - 60_000L; var c = 0; for (t in hits) if (t >= lim) c++; return c >= 3 }
    fun busy(): Boolean = active() || pressured()
    fun ok() { streak = 0 }
    fun active(): Boolean = streak >= 2 && System.currentTimeMillis() - lastAt < 45_000L
}
