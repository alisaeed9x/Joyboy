package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * (v272) مزوّدين ترجمة مجانيين من غير مفتاح (من النوع اللي بيتذكر في قايمة public-apis) بيشتغلوا مع جوجل الداخلي في سلسلة واحدة:
 *  - «الداخلي أولًا» (الافتراضي): جوجل (gtCall) ← المزوّدين الخارجيين لو جوجل فشل في جمل.
 *  - «الريبو أولًا»: المزوّدين الخارجيين ← جوجل للي فضل.
 * بيتفعّل بس في مسار «تفريغ أوفلاين + ترجمة جوجل» (نفس مكان googleItems). كل مزوّد: جملة جملة، 4 طلبات بالتوازي،
 * وليه حد يومي للحروف (MyMemory ≈ 5000 حرف/يوم من غير إيميل) ولو الكوتة خلصت بيقف لباقي اليوم.
 */
object ExtTranslate {
    class Prov(val id: String, val label: String, val keys: List<String>, val dailyChars: Int, val call: (String, String) -> String?) {
        /** (v273) لو متحدد: المزوّد بيترجم دفعة (25 جملة) في طلب واحد بدل جملة جملة (محركات LLM) */
        var batch: ((List<String>, String) -> List<String?>)? = null
        /** (v274) بصمة المفتاح الحالي — لو المفتاح اتغيّر الإيقاف بسبب «مفتاح مرفوض» بيتفك لوحده */
        var keyHash: () -> Int = { 0 }
    }
    /** (v274) ملاحظة غير خطأ من مزوّد (مثلًا: الموديل اتغيّر لوحده) — المحرك بيعرضها كإشعار وبيصفّرها */
    @Volatile var lastNote = ""
    /** آخر خطأ لمزوّد دفعات (للّوج والإشعار) — بيتصفّر مع كل run */
    @Volatile var lastError = ""
    private class QuotaOut : Exception("quota")

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    val builtin: List<Prov> = listOf(
        Prov("mymemory", "MyMemory", listOf("mymemory"), 4800) { t, sl -> myMemory(t, sl) },
        Prov("lingva", "Lingva (جوجل عبر وسيط)", listOf("lingva"), 0) { t, sl -> lingva(t, sl) }
    )

    /**
     * (v366) 🧭 الراوتر الداخلي كمحرك ترجمة: بيجرّب مفاتيح جيميناي (gemini-2.5-flash) الأول، ولو الكوتة خلصت/المفتاح وقع بيلف على المفتاح اللي بعده،
     * وبعدين مزوّديك (Groq · OpenRouter · DeepSeek · Mistral · ChatGPT · Claude) بالترتيب ده (وضع auto/offline). بيتحط أول السلسلة؛ لو كله وقع الترجمة بتكمّل بجوجل ← MyMemory ← Lingva.
     */
    private const val ROUTER_MODE = "auto/offline"
    val router: Prov = Prov("router", "الراوتر الداخلي (جيميناي ← مزوّديك)", listOf("router"), 0) { _, _ -> null }.also {
        it.batch = { texts, sl -> routerBatch(texts, sl) }
    }
    private fun routerBatch(texts: List<String>, sl: String): List<String?> {
        if (!InternalRouter.anyReady(ROUTER_MODE)) throw ExtLlm.LlmErr(429, "كل مفاتيح الراوتر في استراحة (كوتة/مرفوضة)", 120)
        return ExtLlm.translateVia(texts, sl) { sys, user -> InternalRouter.chat(ROUTER_MODE, sys, listOf(AiApi.Msg("user", user)), 4096) }
    }

    /** المزوّدين المدمجين + الراوتر (لو فيه مفاتيح) + محركات LLM اللي مفتاحها مضبوط */
    fun all(): List<Prov> = builtin + listOfNotNull(libreProv()) + listOfNotNull(if (InternalRouter.hasPool()) router else null) + ExtLlm.provs()

    private fun day(): String = (System.currentTimeMillis() / 86_400_000L).toString()

    // ===== إعدادات =====
    /** internal = جوجل الداخلي أولًا (افتراضي) · external = المزوّدين الخارجيين أولًا */
    fun externalFirst(): Boolean = Cfg.str("tx_order", "internal") == "external"
    fun strict(): Boolean = Cfg.str("ext_strict", "off") == "on"
    /** (v274) المحرك الأساسي اللي اخترته من «▶ ترجمة ← أونلاين» (id مزوّد) — بيتجرّب الأول مهما كان الترتيب. فاضي = مفيش */
    fun primaryId(): String = Cfg.str("ext_primary").trim()
    /** (v274) سلسلة الترجمة بالترتيب الفعلي (null = جوجل الداخلي). بعدها لو كلها فشلت الباتش بيتحوّل لجيميناي بمفاتيحك */
    fun chain(): List<Prov?> {
        val ext = active()
        // (v277) id المحفوظ لمحركات LLM هو id البريست (chatgpt) بينما id المزوّد llm_chatgpt — قبل كده المحرك الأساسي ماكانش بيتلقى أبدًا؛ وكمان وضع «الريبو بس» ماكانش لازم يستبعد اللي اخترته بإيدك
        val pid = primaryId()
        val prim = if (pid.isEmpty()) null else all().firstOrNull { enabled(it) && (it.id == pid || it.id == "llm_$pid") }
        // (v366) الراوتر أول واحد بعد المحرك الأساسي؛ ومزوّدين LLM بتوعك جواه فمابيتكرروش بعد جوجل
        val rt = ext.firstOrNull { it.id == "router" }?.takeIf { it.id != prim?.id }
        // (v368) 🛰 OmniRoute ⇄ 🏠 المفاتيح الداخلية (الراوتر): المختار الأول والتاني احتياطي له (TrSource.mode)
        val om = ext.firstOrNull { it.id == "llm_omniroute" }?.takeIf { it.id != prim?.id }
        val rest = ext.filter { it.id != prim?.id && it.id != "router" && it.id != "llm_omniroute" && !(rt != null && it.id.startsWith("llm_")) }
        val steps = ArrayList<Prov?>()
        prim?.let { steps.add(it) }
        if (TrSource.mode() == TrSource.OMNI) { om?.let { steps.add(it) }; rt?.let { steps.add(it) } } else { rt?.let { steps.add(it) }; om?.let { steps.add(it) } }
        if (externalFirst()) { steps.addAll(rest); steps.add(null) } else { steps.add(null); steps.addAll(rest) }
        return steps
    }
    /** (v278) اسم المحرك بوضوح: جوجل الداخلي / ChatGPT… + الموديل / مجاني من الريبو / سيرفرك */
    fun whoLabel(p: Prov?): String = when {
        p == null -> "🏠 جوجل الداخلي"
        p.id == "router" -> "🧭 " + p.label
        p.id.startsWith("llm_") -> "🤖 " + p.label + (ExtLlm.byId(p.id.removePrefix("llm_"))?.let { " · " + ExtLlm.model(it) } ?: "")
        p.id == "libre" -> "🌐 LibreTranslate (سيرفرك)"
        else -> "🌐 " + p.label + " (مجاني من الريبو)"
    }
    /** (v278) سطر «مين بيترجم دلوقتي» للإشعارات: السلسلة بالترتيب، أو جيميناي بعدد مفاتيحه */
    fun setupLine(): String = if (TrWizard.googleOn()) "المترجم: " + chain().joinToString(" ← ") { whoLabel(it) } + " ← 🔑 جيميناي (مفاتيحك)"
        else "المترجم: 🔑 جيميناي — " + (Cfg.keys("keys").size + Cfg.keys("extra").size) + " مفتاح"
    fun chainLabel(): String = chain().joinToString(" ← ") { it?.label ?: "جوجل" } + " ← جيميناي (مفاتيحك)"
    fun enabled(p: Prov): Boolean = Cfg.str("ext_on_" + p.id, "on") != "off"
    fun setEnabled(p: Prov, on: Boolean) { Cfg.put("ext_on_" + p.id, if (on) "on" else "off") }
    /** مزوّد موجود في القايمة المسحوبة من الريبو؟ */
    fun inRepo(p: Prov): Boolean = PublicApis.listed(p.keys) != null

    /** المزوّدين اللي هيتستخدموا فعلًا: مفعّلين، ولو «الريبو بس» شغّال لازم يكونوا في القايمة المسحوبة */
    fun active(): List<Prov> = all().filter { enabled(it) && (!strict() || inRepo(it)) }

    // ===== (v274) إيقاف مؤقت لمزوّد فشل: مفتاح مرفوض (لحد ما المفتاح يتغيّر) / كوتة أو رصيد (دقايق) / رابط أو موديل غلط / سيرفر واقع =====
    private val coolUntil = ConcurrentHashMap<String, Long>()
    private val coolWhy = ConcurrentHashMap<String, String>()
    private val badKey = ConcurrentHashMap<String, Int>()

    /** سبب إن المزوّد [id] واقف دلوقتي، أو null لو شغّال. [keyHash] لتمييز «المفتاح اتغيّر» */
    fun blockedWhy(id: String, keyHash: Int = 0): String? {
        val bk = badKey[id]
        if (bk != null) { if (bk == keyHash) return "المفتاح مرفوض — غيّره عشان يتجرّب تاني" else badKey.remove(id) }
        val u = coolUntil[id] ?: return null
        val left = u - System.currentTimeMillis()
        if (left <= 0L) { coolUntil.remove(id); coolWhy.remove(id); return null }
        return (coolWhy[id] ?: "واقف") + " — بيتجرّب تاني بعد " + ((left + 59_999L) / 60_000L) + " د"
    }
    fun clearBlock(id: String) { badKey.remove(id); coolUntil.remove(id); coolWhy.remove(id) }
    fun noteFail(id: String, keyHash: Int, e: ExtLlm.LlmErr) {
        val now = System.currentTimeMillis()
        fun cool(ms: Long, why: String) { coolUntil[id] = now + ms; coolWhy[id] = why }
        when (e.code) {
            401, 403 -> badKey[id] = keyHash
            402 -> cool(1_800_000L, "الرصيد خلص")
            429 -> cool((if (e.retryAfterSec > 0) e.retryAfterSec.coerceIn(30, 3600) else 600) * 1000L, "الكوتة/الحد خلص")
            404 -> cool(1_800_000L, "الرابط أو الموديل غلط")
            in 500..599 -> cool(60_000L, "الخدمة واقعة")
        }
    }

    // ===== LibreTranslate (سيرفرك أو سيرفر بمفتاح): POST {url}/translate بدفعة جمل =====
    fun libreProv(): Prov? {
        val u = Cfg.str("libre_url").trim().trimEnd('/')
        if (u.isEmpty()) return null
        return Prov("libre", "LibreTranslate", listOf("libretranslate"), 0) { _, _ -> null }.also { pv ->
            pv.keyHash = { Cfg.str("libre_key").hashCode() }
            pv.batch = { texts, sl -> libre(u, texts, sl) }
        }
    }
    private fun libre(url: String, texts: List<String>, sl0: String): List<String?> {
        val sl = if (sl0 == "zh-CN") "zh" else sl0
        val j = JSONObject().put("q", JSONArray(texts)).put("source", sl).put("target", "ar").put("format", "text")
        Cfg.str("libre_key").trim().takeIf { it.isNotEmpty() }?.let { j.put("api_key", it) }
        val o = JSONObject(ExtLlm.post("$url/translate", emptyMap(), j.toString()))
        val out = arrayOfNulls<String>(texts.size)
        val arr = o.optJSONArray("translatedText")
        if (arr != null) { for (i in 0 until minOf(arr.length(), texts.size)) out[i] = arr.optString(i, "").trim().takeIf { it.isNotEmpty() } }
        else if (texts.size == 1) out[0] = o.optString("translatedText", "").trim().takeIf { it.isNotEmpty() }
        return out.toList()
    }

    /** (v274) تجربة مزوّد بجملة واحدة (من الإعدادات): سطر نتيجة، وبيفك الإيقاف لو نجح */
    fun testProv(p: Prov): String = try {
        val r = p.batch?.invoke(listOf("Hello, how are you today?"), "en") ?: listOf(p.call("Hello, how are you today?", "en"))
        val tr = r.firstOrNull()
        if (tr.isNullOrBlank()) "❌ ${p.label}: ما رجّعش ترجمة" else { clearBlock(p.id); "✅ ${p.label} شغّال: «$tr»" }
    } catch (e: Exception) { "❌ ${p.label}: " + (e.message ?: e.javaClass.simpleName).take(140) }

    /** (v276) اختبار لحظي بقياس الزمن (من غير حفظ): (نجح؟، ms، الترجمة أو سبب الفشل). بيفك الإيقاف لو نجح */
    fun testTimed(p: Prov): Triple<Boolean, Long, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val r = p.batch?.invoke(listOf("Hello, how are you today?"), "en") ?: listOf(p.call("Hello, how are you today?", "en"))
            val tr = r.firstOrNull(); val ms = System.currentTimeMillis() - t0
            if (tr.isNullOrBlank()) Triple(false, ms, "ما رجّعش ترجمة") else { clearBlock(p.id); Triple(true, ms, tr) }
        } catch (e: Exception) { Triple(false, System.currentTimeMillis() - t0, (e.message ?: e.javaClass.simpleName).take(90)) }
    }

    // ===== عدّادات =====
    private fun usedToday(id: String): Int {
        val p = Cfg.str("ext_use_$id").split(":")
        return if (p.size == 2 && p[0] == day()) (p[1].toIntOrNull() ?: 0) else 0
    }
    private fun addUsed(id: String, n: Int) { if (n > 0) Cfg.put("ext_use_$id", day() + ":" + (usedToday(id) + n)) }
    private fun dead(id: String): Boolean = Cfg.str("ext_dead_$id") == day()
    private fun markDead(id: String) { Cfg.put("ext_dead_$id", day()) }
    private fun addCount(id: String, n: Int) { if (n > 0) Cfg.put("ext_n_$id", ((Cfg.str("ext_n_$id").toIntOrNull() ?: 0) + n).toString()) }

    /** سطر حالة للعرض في الإعدادات */
    fun statLine(p: Prov): String {
        val n = Cfg.str("ext_n_${p.id}").toIntOrNull() ?: 0
        val q = if (p.dailyChars > 0) " · النهارده ${usedToday(p.id)}/${p.dailyChars} حرف" else ""
        val d = if (dead(p.id)) " · ⛔ الكوتة خلصت لباقي اليوم" else (blockedWhy(p.id, p.keyHash())?.let { " · ⛔ $it" } ?: "")
        return "ترجم $n جملة إجمالًا$q$d"
    }

    // ===== شبكة =====
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 12000; c.readTimeout = 20000
            c.setRequestProperty("User-Agent", UA)
            if (c.responseCode != 200) return null
            val t = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            NetUse.api(url.length.toLong() + 200L, t)
            return t
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }

    /** تخمين لغة المصدر من الحروف (MyMemory مابيقبلش «auto») */
    fun guessLang(t: String): String {
        for (ch in t) {
            val c = ch.code
            if (c in 0xAC00..0xD7AF || c in 0x1100..0x11FF) return "ko"
            if (c in 0x3040..0x30FF) return "ja"
        }
        for (ch in t) if (ch.code in 0x4E00..0x9FFF) return "zh-CN"
        for (ch in t) if (ch.code in 0x0400..0x04FF) return "ru"
        return "en"
    }

    private fun myMemory(text: String, sl0: String): String? {
        val sl = if (sl0 == "auto") guessLang(text) else sl0
        if (sl == "ar") return null
        val q = text.take(450)
        val t = get("https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(q, "UTF-8") + "&langpair=" + URLEncoder.encode("$sl|ar", "UTF-8")) ?: return null
        val j = JSONObject(t)
        val st = j.opt("responseStatus")?.toString()?.toIntOrNull() ?: 200
        val tr = j.optJSONObject("responseData")?.optString("translatedText", "") ?: ""
        if (st == 429) throw QuotaOut()
        val up = tr.uppercase()
        if (up.startsWith("MYMEMORY WARNING")) throw QuotaOut()
        if (st != 200 || tr.isBlank() || up.contains("PLEASE SELECT TWO DISTINCT") || up.contains("QUERY LENGTH LIMIT")) return null
        if (tr.trim() == q.trim()) return null   // رجّع نفس النص = ما ترجمش
        return tr.trim()
    }

    private val lingvaHosts = listOf("https://lingva.ml", "https://lingva.thedaviddelta.com")
    private fun lingva(text: String, sl0: String): String? {
        val sl = if (sl0 == "zh-CN") "zh" else sl0
        if (sl == "ar") return null
        val path = URLEncoder.encode(text.take(400), "UTF-8").replace("+", "%20")
        for (h in lingvaHosts) {
            try {
                val t = get("$h/api/v1/$sl/ar/$path") ?: continue
                val tr = JSONObject(t).optString("translation", "")
                if (tr.isNotBlank()) return tr.trim()
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * يترجم [texts] بمزوّد واحد (جملة جملة، 4 بالتوازي). بيرجّع نفس الطول، والفاشل null.
     * [alive] لو رجّعت false بيوقف. بيرجّع كمان سطر لوج.
     */
    fun run(p: Prov, texts: List<String>, sl: String, alive: () -> Boolean): List<String?> {
        val out = arrayOfNulls<String>(texts.size)
        lastError = ""
        if (texts.isEmpty()) return out.toList()
        if (dead(p.id)) { lastError = "الكوتة اليومية خلصت"; return out.toList() }
        blockedWhy(p.id, p.keyHash())?.let { lastError = it; return out.toList() }
        val bt = p.batch
        if (bt != null) {   // (v273) محرك LLM: 25 جملة في الطلب، ولو فشل طلبين ورا بعض بنقف
            var okN = 0; var failed = 0
            for (chunk in texts.indices.chunked(25)) {
                if (!alive()) break
                try {
                    val r = bt(chunk.map { texts[it] }, sl)
                    for ((q, i) in chunk.withIndex()) r.getOrNull(q)?.takeIf { it.isNotBlank() }?.let { out[i] = it; okN++ }
                } catch (e: Exception) {
                    lastError = (e.message ?: e.javaClass.simpleName).take(120)
                    if (e is ExtLlm.LlmErr) { noteFail(p.id, p.keyHash(), e); if (e.code in intArrayOf(401, 402, 403, 404, 429)) break }   // (v274) مفتاح/رصيد/كوتة/موديل: مفيش فايدة من إعادة المحاولة
                    if (++failed >= 2) break
                }
            }
            addCount(p.id, okN)
            return out.toList()
        }
        val budget = if (p.dailyChars > 0) (p.dailyChars - usedToday(p.id)).coerceAtLeast(0) else Int.MAX_VALUE
        var spent = 0
        val allow = BooleanArray(texts.size)
        for (i in texts.indices) { val len = texts[i].length; if (len in 1..450 && spent + len <= budget) { allow[i] = true; spent += len } }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = texts.indices.filter { allow[it] }.map { i ->
                Callable<Unit> {
                    if (!alive() || dead(p.id)) return@Callable
                    try { out[i] = p.call(texts[i], sl)?.takeIf { it.isNotBlank() } }
                    catch (_: QuotaOut) { markDead(p.id) }
                    catch (_: Exception) {}
                    Unit
                }
            }
            pool.invokeAll(jobs)
        } finally { pool.shutdown() }
        var chars = 0; var n = 0
        for (i in texts.indices) if (out[i] != null) { chars += texts[i].length; n++ }
        addUsed(p.id, chars); addCount(p.id, n)
        return out.toList()
    }
}
