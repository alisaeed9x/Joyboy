package com.tttt.subtitler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v365) 🧭 الراوتر الداخلي — بديل OmniRoute جوه التطبيق نفسه (من غير سيرفر ولا Termux).
 *  - بيجمع كل المزوّدين اللي ليهم مفتاح (ChatGPT · Claude · Groq · OpenRouter · DeepSeek · Mistral · المخصص) + كل مفاتيح جيميناي (كل مفتاح لوحده).
 *  - الأوضاع: auto · auto/fast · auto/coding · auto/cheap · auto/offline · auto/smart — أو «مزوّد/موديل» لتثبيت مزوّد معيّن.
 *  - لو مزوّد وقع (429 كوتة / 401 مفتاح / 402 رصيد / 5xx / مهلة) بيتحط في «استراحة» وبيجرّب اللي بعده فورًا.
 *  - (v366) اتشال OmniRoute/Termux من السلسلة خالص — الراوتر ده هو الوحيد.
 */
object InternalRouter {
    const val ID = "router"
    val AUTO = listOf("auto", "auto/fast", "auto/coding", "auto/cheap", "auto/offline", "auto/smart")

    class Cand(val key: String, val pid: String, val src: AiApi.Src, val model: String)

    private val coolUntil = ConcurrentHashMap<String, Long>()
    private val coolWhy = ConcurrentHashMap<String, String>()
    private val latMs = ConcurrentHashMap<String, Int>()
    private val rr = AtomicInteger(0)

    private val ORDER = mapOf(
        "auto" to listOf("claude", "chatgpt", "gemini", "deepseek", "groq", "mistral", "openrouter", "custom"),
        "auto/smart" to listOf("claude", "chatgpt", "gemini", "deepseek", "groq", "mistral", "openrouter", "custom"),
        "auto/fast" to listOf("groq", "gemini", "mistral", "deepseek", "chatgpt", "openrouter", "claude", "custom"),
        "auto/coding" to listOf("claude", "deepseek", "chatgpt", "gemini", "groq", "openrouter", "mistral", "custom"),
        "auto/cheap" to listOf("groq", "gemini", "deepseek", "mistral", "openrouter", "chatgpt", "claude", "custom"),
        "auto/offline" to listOf("gemini", "groq", "openrouter", "deepseek", "mistral", "chatgpt", "claude", "custom")
    )

    /** كل مزوّد حقيقي (من غير الراوتر نفسه)؛ جيميناي بيتفرد لكل مفتاح */
    private fun all(mode: String): List<Cand> {
        val out = ArrayList<Cand>()
        for (s in AiApi.realSources()) {
            if (s.id == "omniroute") continue   // (v368) OmniRoute مصدر منفصل بيتبدّل معاه — مش جزء من الراوتر
            if (s.id == "gemini") {
                val m = if (mode == "auto/fast" || mode == "auto/cheap") "gemini-flash-lite-latest" else s.defModel
                Cfg.allMainKeys().forEachIndexed { i, k ->
                    out.add(Cand("gemini#$i", "gemini", AiApi.Src("gemini", s.label, s.kind, s.url, k, s.defModel, null), m))
                }
            } else out.add(Cand(s.id, s.id, s, s.defModel))
        }
        return out
    }

    /** (v366) في مفتاح/مزوّد مش في استراحة للوضع ده؟ (الترجمة بتسأل قبل ما تبعت عشان ماتضربش مفاتيح واقفة) */
    fun anyReady(model: String): Boolean = plan(model).any { !cooled(it.key) }

    fun hasPool(): Boolean = AiApi.realSources().any { it.id != "omniroute" }

    private fun rank(mode: String, pid: String): Int {
        if (pid == "omniroute") return 1000   // آخر واحد: الاحتياطي بتاع Termux
        val i = (ORDER[mode] ?: ORDER["auto"]!!).indexOf(pid)
        return if (i < 0) 500 else i
    }

    /** (v366) اسم المرشّح للعرض: جيميناي بيتميّز برقم المفتاح وآخر 4 حروف منه عشان تعرف أنهي مفتاح واقع */
    private fun tag(c: Cand): String = c.src.label.substringBefore(" (") +
        (if (c.pid == "gemini") " #" + ((c.key.substringAfter('#', "").toIntOrNull() ?: 0) + 1) + " …" + c.src.key.takeLast(4) else "")

    private fun cooled(k: String) = (coolUntil[k] ?: 0L) > System.currentTimeMillis()

    private fun penalize(c: Cand, e: Throwable) {
        val code = (e as? ExtLlm.LlmErr)?.code ?: 0
        val retry = (e as? ExtLlm.LlmErr)?.retryAfterSec ?: 0
        val sec = when {
            code == 401 || code == 403 -> 1800
            code == 402 -> 3600
            code == 404 -> 600
            code == 429 -> if (retry in 1..900) retry else 60
            else -> 30
        }
        coolUntil[c.key] = System.currentTimeMillis() + sec * 1000L
        coolWhy[c.key] = tag(c) + ": " + (e.message ?: "فشل").take(70)
    }

    /** السلسلة المرتبة للطلب ده */
    fun plan(model: String): List<Cand> {
        val mode = if (model in AUTO) model else "auto"
        val pool = all(mode)
        // تثبيت مزوّد: «groq/llama-3.3-70b» أو «gemini/gemini-2.5-flash»
        if (model !in AUTO && model.contains('/')) {
            val pid = model.substringBefore('/'); val mm = model.substringAfter('/')
            val pinned = pool.filter { it.pid == pid }
            if (pinned.isNotEmpty()) return pinned.map { Cand(it.key, it.pid, it.src, mm) }
        }
        val start = rr.getAndIncrement()
        val sorted = pool.sortedWith(compareBy({ rank(mode, it.pid) }, { (latMs[it.key] ?: 0) / 1500 }))
        // لف بسيط بين مفاتيح جيميناي عشان الحمل يتوزّع
        val groups = sorted.groupBy { it.pid }.toList().sortedBy { rank(mode, it.first) }
        val rotated = groups.flatMap { (_, l) -> if (l.size > 1) l.drop(start % l.size) + l.take(start % l.size) else l }
        val (ready, cool) = rotated.partition { !cooled(it.key) }
        return ready + cool.sortedBy { coolUntil[it.key] ?: 0L }
    }

    fun models(): List<AiModels.Info> {
        val out = ArrayList<AiModels.Info>()
        AUTO.forEach { out.add(AiModels.describe(it, "الراوتر الداخلي", 0)) }
        for (s in AiApi.realSources()) if (s.defModel.isNotEmpty()) out.add(AiModels.describe(s.id + "/" + s.defModel, s.label, 0))
        return out
    }

    /** ملخص حالة المزوّدين (للشريط) */
    fun summary(): String {
        val p = all("auto"); if (p.isEmpty()) return ""
        val up = p.count { !cooled(it.key) }
        return "🧭 الراوتر الداخلي: $up/${p.size} جاهز" + (coolWhy.entries.firstOrNull { cooled(it.key) }?.let { " · ⏸ " + it.value } ?: "")
    }

    fun chat(model: String, system: String, msgs: List<AiApi.Msg>, maxTokens: Int): String {
        val chain = plan(model)
        if (chain.isEmpty()) throw ExtLlm.LlmErr(0, "مفيش مزوّد جاهز — ضيف مفتاح واحد على الأقل (جيميناي أو Groq أو أي مزوّد)")
        val fails = ArrayList<String>()
        var tried = 0
        for (c in chain) {
            if (tried >= 6) break
            val t0 = System.currentTimeMillis()
            try {
                val r = AiApi.chat(c.src, c.model, system, msgs, maxTokens)
                if (r.isBlank()) throw ExtLlm.LlmErr(0, "رد فاضي")
                val ms = (System.currentTimeMillis() - t0).toInt()
                latMs[c.key] = ((latMs[c.key] ?: ms) * 2 + ms) / 3
                coolUntil.remove(c.key)
                OmniMeta.record(c.src.label.substringBefore(" ("), c.model, if (model in AUTO) model else "pinned", ms, tried)
                return r
            } catch (e: Throwable) {
                tried++
                penalize(c, e)
                fails.add(coolWhy[c.key] ?: (tag(c) + ": فشل"))
            }
        }
        throw ExtLlm.LlmErr(0, "كل المزوّدين فشلوا:\n" + fails.joinToString("\n"))
    }
}
