package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v273) محركات LLM بمفتاحك للترجمة (ChatGPT / Claude / Groq / OpenRouter / DeepSeek / Mistral / أي خدمة بصيغة OpenAI):
 *  - kind=openai: POST {url}/chat/completions (Authorization: Bearer) — بيغطي أي خدمة متوافقة.
 *  - kind=anthropic: POST {url}/messages (x-api-key + anthropic-version) — Claude.
 * بتدخل سلسلة ExtTranslate كمزوّدين (25 جملة في الطلب، سطور مرقّمة)، وبتتفعّل بس لو المفتاح مضبوط.
 * نفس المفتاح بيستخدمه التفريغ السحابي (RemoteStt) للي بيدعمه (OpenAI / Groq / مخصص).
 */
object ExtLlm {
    class Preset(val id: String, val label: String, val kind: String, val url: String, val model: String, val sttModel: String, val keys: List<String>)

    val presets: List<Preset> = listOf(
        Preset("chatgpt", "ChatGPT (OpenAI)", "openai", "https://api.openai.com/v1", "gpt-4o-mini", "whisper-1", listOf("openai", "chatgpt")),
        Preset("claude", "Claude (Anthropic)", "anthropic", "https://api.anthropic.com/v1", "claude-haiku-5-5", "", listOf("anthropic", "claude")),
        Preset("omniroute", "OmniRoute (بوابة كل المزوّدين)", "openai", "http://localhost:20128/v1", "auto", "deepgram/nova-3", listOf("omniroute")),
        Preset("groq", "Groq", "openai", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "whisper-large-v3-turbo", listOf("groq")),
        Preset("openrouter", "OpenRouter", "openai", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "", listOf("openrouter")),
        Preset("deepseek", "DeepSeek", "openai", "https://api.deepseek.com/v1", "deepseek-chat", "", listOf("deepseek")),
        Preset("mistral", "Mistral", "openai", "https://api.mistral.ai/v1", "mistral-small-latest", "", listOf("mistral")),
        Preset("custom", "مخصص (أي خدمة بصيغة OpenAI)", "openai", "", "", "", listOf())
    )

    /** (v274) خطأ HTTP من مزوّد: الكود (401 مفتاح · 402 رصيد · 404 رابط/موديل · 429 كوتة) + مدة الانتظار لو الخدمة قالتها */
    class LlmErr(val code: Int, msg: String, val retryAfterSec: Int = 0) : IOException(msg)

    fun byId(id: String): Preset? = presets.firstOrNull { it.id == id }
    fun enabled(p: Preset): Boolean = Cfg.str("ext_on_llm_" + p.id, "on") != "off"
    fun key(p: Preset): String = Cfg.str("llm_${p.id}_key").trim()
    fun model(p: Preset): String = Cfg.str("llm_${p.id}_model").trim().ifEmpty { p.model }
    fun url(p: Preset): String = Cfg.str("llm_${p.id}_url").trim().ifEmpty { p.url }.trimEnd('/')
    fun sttModel(p: Preset): String = Cfg.str("llm_${p.id}_stt").trim().ifEmpty { p.sttModel }
    /** (v364) OmniRoute المحلي بيشتغل من غير مفتاح (REQUIRE_API_KEY=false افتراضيًا) — بيتفعّل لوحده لما OmniAuto يلاقيه شغّال */
    fun keyless(p: Preset): Boolean = p.id == "omniroute"
    fun hasAccess(p: Preset): Boolean = key(p).isNotEmpty() || (keyless(p) && Cfg.str("omni_seen") == "1")
    fun bearer(p: Preset): Map<String, String> = key(p).let { if (it.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $it") }
    fun configured(p: Preset): Boolean = hasAccess(p) && url(p).isNotEmpty() && model(p).isNotEmpty()
    /** يدعم تفريغ الصوت (/audio/transcriptions)؟ */
    fun supportsStt(p: Preset): Boolean = p.kind == "openai" && (p.sttModel.isNotEmpty() || p.id == "custom")

    /** المزوّدين الجاهزين (مفتاحهم مضبوط) كمزوّدين في سلسلة الترجمة */
    fun provs(): List<ExtTranslate.Prov> = presets.filter { configured(it) }.map { p ->
        ExtTranslate.Prov("llm_" + p.id, p.label, p.keys, 0) { _, _ -> null }.also {
            it.keyHash = { key(p).hashCode() }
            it.batch = { texts, sl -> translate(p, texts, sl) }
        }
    }

    /** (v274) سبب إن المزوّد واقف دلوقتي (مفتاح مرفوض / كوتة / رصيد…) أو null لو شغّال */
    fun blockedWhy(p: Preset): String? = ExtTranslate.blockedWhy("llm_" + p.id, key(p).hashCode())

    private fun langName(sl: String): String = when (sl) {
        "en" -> "English"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh-CN", "zh" -> "Chinese"; "fr" -> "French"; "es" -> "Spanish"
        "tr" -> "Turkish"; "ru" -> "Russian"; "de" -> "German"; "it" -> "Italian"; "hi" -> "Hindi"; "fa" -> "Persian"; "ur" -> "Urdu"
        else -> "the source language (auto-detect)"
    }

    /** يترجم دفعة جمل (≤25) لعربي بسطور مرقّمة. بيرجّع نفس الطول والناقص null. أي خطأ HTTP ← استثناء برسالة واضحة */
    fun translate(p: Preset, texts: List<String>, sl: String): List<String?> = translateVia(texts, sl) { sys, user -> chat(p, sys, user, 4096) }

    /** (v366) نفس الترجمة بس الإرسال بيتم بدالة [ask] (system, user) → رد — عشان الراوتر الداخلي يستخدمها */
    fun translateVia(texts: List<String>, sl: String, ask: (String, String) -> String): List<String?> {
        val numbered = texts.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val sys = "You are a professional subtitle translator. Translate each numbered line from ${langName(sl)} to Arabic (clear, natural Modern Standard Arabic suitable for subtitles). " +
            "Keep the numbering. Output ONLY the translated lines in the exact format `N. translation`, one per line, with no extra text, notes or blank lines."
        val reply = ask(sys, numbered)
        val out = arrayOfNulls<String>(texts.size)
        val rx = Regex("^\\s*(\\d+)\\s*[.)\\-:]\\s*(.*)$")
        for (line in reply.lines()) {
            val m = rx.find(line) ?: continue
            val ix = (m.groupValues[1].toIntOrNull() ?: continue) - 1
            val t = m.groupValues[2].trim()
            if (ix in out.indices && t.isNotEmpty() && out[ix] == null) out[ix] = t
        }
        return out.toList()
    }

    /**
     * (v281) تحويل ترجمة فصحى للهجة [tgt] بمحرك LLM (ChatGPT / Claude / …) بدل جيميناي. بيرجّع نفس الطول والناقص null.
     * [orig] النص الأصلي للمرجع، [msa] الفصحى، [meta] جنس المتكلم/المخاطَب (ممكن فاضي)، [rules] قواعد اللهجة (PromptBuilder.dialectBlock)
     */
    fun convert(p: Preset, orig: List<String>, msa: List<String>, meta: List<String>, tgt: String, rules: String): List<String?> {
        fun clean(t: String) = t.replace(Regex("\\s*\n\\s*"), " ").trim()
        val sys = "You are a professional Arabic subtitle editor. Each numbered line has the ORIGINAL line and its Modern Standard Arabic (MSA) translation. " +
            "Rewrite ONLY the Arabic translation into authentic everyday spoken $tgt Arabic, the way native speakers really talk, keeping the exact meaning, names, numbers and the speaker/addressee gender. " +
            "Never merge, split, reorder or drop lines. Return EVERY line, even if it is already close to the dialect. If the MSA misses the meaning, fix it from the ORIGINAL. " +
            "Output ONLY lines in the exact format `N. text` (Arabic only), one per line, no notes, no blank lines.\n" + rules
        val user = orig.indices.joinToString("\n") { i -> "${i + 1}. ORIGINAL: " + clean(orig[i]) + " | MSA: " + clean(msa[i]) + (meta.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: "") }
        val reply = chat(p, sys, user, 4096)
        val out = arrayOfNulls<String>(orig.size)
        val rx = Regex("^\\s*(\\d+)\\s*[.)\\-:]\\s*(.*)$")
        for (line in reply.lines()) {
            val m = rx.find(line) ?: continue
            val ix = (m.groupValues[1].toIntOrNull() ?: continue) - 1
            val t = m.groupValues[2].trim()
            if (ix in out.indices && t.isNotEmpty() && out[ix] == null) out[ix] = t
        }
        return out.toList()
    }

    private fun http(method: String, url: String, headers: Map<String, String>, body: String?, readMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = readMs
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            OmniMeta.capture(c)   // (v364) هيدرات OmniRoute
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            NetUse.api((body?.length ?: 0).toLong() + 200L, txt)
            if (code !in 200..299) throw LlmErr(code, errMsg(code, txt), c.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 0)
            return txt
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    fun post(url: String, headers: Map<String, String>, body: String, readMs: Int = 60000): String = http("POST", url, headers, body, readMs)

    /** رسالة خطأ مفهومة: 401/403 = مفتاح، 429 = كوتة، + أول سطر من رد الخدمة */
    fun errMsg(code: Int, body: String): String {
        val hint = when (code) { 401, 403 -> "المفتاح مرفوض"; 402 -> "الرصيد خلص"; 404 -> "الرابط أو الموديل غلط"; 429 -> "الكوتة/الحد خلص"; in 500..599 -> "الخدمة واقعة"; else -> "" }
        val detail = try { JSONObject(body).let { j -> j.optJSONObject("error")?.optString("message") ?: (j.opt("error") as? String) ?: j.optString("message") } } catch (_: Throwable) { null }
        return "HTTP $code" + (if (hint.isNotEmpty()) " ($hint)" else "") + (if (!detail.isNullOrBlank()) " — " + detail.take(80) else "")
    }

    /**
     * طلب نصي واحد للمزوّد. بيرجّع نص الرد.
     * (v274) لو الموديل مش موجود عند الخدمة (404 / 400 «model»): بنجيب قايمة موديلاتها ونختار أقرب واحد لاسمك (نفس العيلة: mini / haiku / llama…)،
     * نحفظه ونعيد الطلب مرة واحدة، وبيطلع إشعار بالتغيير. لو ملقيناش قريب بيفضل الخطأ الأصلي.
     */
    fun chat(p: Preset, system: String, user: String, maxTokens: Int): String {
        if (p.id == "omniroute") return OmniModels.chat(p, system, user, maxTokens)   // (v369) لو موديل فشل بيتبعت لموديل تاني من أومني روت
        return chatHeal(p, system, user, maxTokens)
    }

    /** (v369) طلب واحد بموديل محدد (من غير إصلاح تلقائي لاسم الموديل) — لتبديل موديلات أومني روت */
    fun chatWith(p: Preset, modelName: String, system: String, user: String, maxTokens: Int): String = chatOnce(p, system, user, maxTokens, modelName)

    private fun chatHeal(p: Preset, system: String, user: String, maxTokens: Int): String {
        try { return chatOnce(p, system, user, maxTokens) }
        catch (e: LlmErr) {
            if (!(e.code == 404 || (e.code == 400 && (e.message ?: "").contains("model", true)))) throw e
            val old = model(p)
            val nm = healModel(p, old) ?: throw e
            ExtTranslate.lastNote = "🔄 موديل «$old» مش موجود عند ${p.label} ← اتحوّل لـ «$nm» واتحفظ (تقدر تغيّره من الإعدادات)"
            return chatOnce(p, system, user, maxTokens)
        }
    }

    /** (v274) قايمة موديلات الخدمة (GET {url}/models) — الأسماء بس */
    fun listModels(p: Preset, key: String, url: String): List<String> {
        val u = url.trim().trimEnd('/')
        if (u.isEmpty() || (key.isEmpty() && !keyless(p))) throw IOException("المفتاح أو الرابط ناقص")
        val hdr = if (p.kind == "anthropic") mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01") else (if (key.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $key"))
        val txt = http("GET", u + "/models" + (if (p.kind == "anthropic") "?limit=100" else ""), hdr, null, 30000)
        val arr = JSONObject(txt).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.optString("id", "")?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out.sorted()
    }

    val skipWords = listOf("embed", "tts", "whisper", "audio", "realtime", "image", "moderation", "transcribe", "dall", "guard", "rerank", "ocr", "search", "speech", "safeguard")
    private fun words(n: String): Set<String> = n.lowercase().split(Regex("[^a-z0-9]+")).filter { w -> w.isNotEmpty() && w.any { it.isLetter() } }.toSet()
    private fun ver(n: String): List<Int> = Regex("\\d+").findAll(n).map { it.value.toIntOrNull()?.takeIf { v -> v < 100000 } ?: 0 }.toList()

    /** أقرب موديل من [list] لاسم [old]: أكتر كلمات مشتركة (من غير أرقام) ← الأحدث رقمًا ← الأقصر. null لو مفيش قريب */
    fun pickClosest(old: String, list: List<String>): String? {
        val ow = words(old); if (ow.isEmpty()) return null
        var best: String? = null; var bs = 0
        val cand = list.filter { c -> skipWords.none { c.contains(it, true) } }
        for (c in cand) {
            val cw = words(c); val common = cw.intersect(ow).size
            if (common == 0) continue
            val sc = common * 10 - (cw.size - common)
            if (best == null || sc > bs) { best = c; bs = sc; continue }
            if (sc == bs) {
                val va = ver(c); val vb = ver(best)
                var cmp = 0
                for (i in 0 until maxOf(va.size, vb.size)) { val x = va.getOrElse(i) { 0 }; val y = vb.getOrElse(i) { 0 }; if (x != y) { cmp = x.compareTo(y); break } }
                if (cmp > 0 || (cmp == 0 && c.length < best.length)) best = c
            }
        }
        return best
    }

    /** بيدوّر على بديل لموديل مش موجود ويحفظه. null لو الموديل أصلًا موجود أو ملقاش قريب أو القايمة ما اتجابتش */
    private fun healModel(p: Preset, old: String): String? {
        val list = try { listModels(p, key(p), url(p)) } catch (_: Exception) { return null }
        if (list.isEmpty() || old in list) return null
        val nm = pickClosest(old.ifEmpty { p.model }, list) ?: return null
        Cfg.put("llm_${p.id}_model", nm)
        return nm
    }

    /** (v274) اختبار سريع من الإعدادات: جملة واحدة. بيرجّع سطر نتيجة (✅ / ❌ بالسبب) وبيفك الإيقاف لو نجح */
    fun test(p: Preset): String = try {
        val r = translate(p, listOf("Hello, how are you today?"), "en")
        ExtTranslate.clearBlock("llm_" + p.id)
        val note = ExtTranslate.lastNote.also { ExtTranslate.lastNote = "" }
        "✅ ${p.label} شغّال: «" + (r.firstOrNull() ?: "؟") + "»" + (if (note.isNotEmpty()) "\n$note" else "")
    } catch (e: Exception) { "❌ ${p.label}: " + (e.message ?: e.javaClass.simpleName).take(140) }


    /** (v275) اختبار لحظي بقياس الزمن: (نجح؟، ms، سطر نتيجة). مابيحفظش حاجة، وبيفك الإيقاف لو نجح */
    fun testTimed(p: Preset): Triple<Boolean, Long, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val r = translate(p, listOf("Hello, how are you today?"), "en").firstOrNull()
            val ms = System.currentTimeMillis() - t0
            ExtTranslate.lastNote = ""   // (v277) إشعار تغيير الموديل التلقائي ماينفعش يفضل لحد ما يظهر غلط في ترجمة شغّالة
            if (r.isNullOrBlank()) Triple(false, ms, "ما رجّعش ترجمة") else { ExtTranslate.clearBlock("llm_" + p.id); Triple(true, ms, r) }
        } catch (e: Exception) { Triple(false, System.currentTimeMillis() - t0, (e.message ?: e.javaClass.simpleName).take(90)) }
    }

    private fun chatOnce(p: Preset, system: String, user: String, maxTokens: Int, modelName: String = model(p)): String {
        val key = key(p)
        if (p.kind == "anthropic") {
            val body = JSONObject().put("model", modelName).put("max_tokens", maxTokens).put("system", system)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user))).toString()
            val txt = post(url(p) + "/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body)
            val arr = JSONObject(txt).optJSONArray("content") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until arr.length()) sb.append(arr.optJSONObject(i)?.optString("text", "") ?: "")
            return sb.toString()
        }
        val body = JSONObject().put("model", modelName).put("temperature", 0.2)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user))).toString()
        val txt = post(url(p) + "/chat/completions", bearer(p), body)
        return JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "") ?: ""
    }
}


/**
 * (v364) اكتشاف OmniRoute تلقائي: بيجرّب الرابط المحفوظ ثم 127.0.0.1:20128 و localhost:20128 (GET /v1/models).
 *  - 200 = شغّال من غير مفتاح ← بيتفعّل لوحده (بيتحفظ omni_seen) ومفيش حاجة تكتبها.
 *  - 401/403 = شغّال بس مضبوط على REQUIRE_API_KEY=true ← محتاج مفتاح من Dashboard → Endpoints.
 */
object OmniAuto {
    enum class St { UP, NEED_KEY, DOWN }
    @Volatile var last: St? = null
    @Volatile private var lastAt = 0L

    fun candidates(): List<String> {
        val p = ExtLlm.byId("omniroute")
        return listOfNotNull(p?.let { ExtLlm.url(it) }, "http://127.0.0.1:20128/v1", "http://localhost:20128/v1").map { it.trimEnd('/') }.distinct()
    }

    fun probe(): St {
        val p = ExtLlm.byId("omniroute")!!
        var needKey = false
        for (u in candidates()) {
            try {
                val c = URL("$u/models").openConnection() as java.net.HttpURLConnection
                try {
                    c.connectTimeout = 1500; c.readTimeout = 3000
                    ExtLlm.key(p).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                    val code = c.responseCode
                    if (code in 200..299) {
                        if (ExtLlm.key(p).isEmpty()) Cfg.put("omni_seen", "1")
                        if (u != ExtLlm.url(p)) Cfg.put("llm_omniroute_url", u)
                        return St.UP
                    }
                    if (code == 401 || code == 403) needKey = true
                } finally { try { c.disconnect() } catch (_: Throwable) {} }
            } catch (_: Throwable) {}
        }
        return if (needKey) St.NEED_KEY else St.DOWN
    }

    /** مرة كل دقيقة على الأكتر؛ [onDone] بيتنادى على thread الخلفية */
    fun probeAsync(force: Boolean = false, onDone: ((St) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastAt < 60000L) { last?.let { onDone?.invoke(it) }; return }
        lastAt = now
        Thread { val r = try { probe() } catch (_: Throwable) { St.DOWN }; last = r; onDone?.invoke(r) }.start()
    }
}


/**
 * (v369) وضع أومني روت (تفريغ + ترجمة بأومني روت بس — جيميناي والراوتر الداخلي وجوجل مقفولين):
 *  - on(): الخطة المحفوظة فيها محرك أومني روت (Cfg omni_mode=1، بتتكتب من TrWizard.apply).
 *  - models(): الموديل الأساسي (llm_omniroute_model، افتراضي auto) + اللي كتبته في omni_models (مفصولين بفاصلة/سطر)؛ لو ماكتبتش بدايل
 *    بنجيب قايمة موديلات أومني روت (GET /v1/models) ونضيف منها لحد 6 موديلات شات احتياطي (بتتحدّث كل 10 دقايق).
 *  - chat(): بيجرّب الموديلات بالترتيب؛ موديل فشل (كوتة/خطأ/رد فاضي/مهلة) بيتوقف دقايق ويتبعت نفس الطلب للي بعده، لحد 4 موديلات في الطلب الواحد.
 */
object OmniModels {
    @Volatile private var found: List<String> = emptyList()
    @Volatile private var foundAt = 0L
    private const val MAX_TRIES = 4

    fun extras(): List<String> = Cfg.str("omni_models").split(Regex("[,\n;،]")).map { it.trim() }.filter { it.isNotEmpty() }

    fun models(p: ExtLlm.Preset): List<String> {
        val base = (listOf(ExtLlm.model(p)) + extras()).distinct()
        if (base.size > 1) return base
        val now = System.currentTimeMillis()
        if (now - foundAt > 600_000L) {
            foundAt = now
            found = try {
                ExtLlm.listModels(p, ExtLlm.key(p), ExtLlm.url(p)).filter { m -> ExtLlm.skipWords.none { m.contains(it, true) } }.take(6)
            } catch (_: Throwable) { found }
        }
        return (base + found).distinct()
    }

    fun chat(p: ExtLlm.Preset, system: String, user: String, maxTokens: Int): String {
        var last: Throwable? = null
        var tried = 0
        val all = models(p)
        for ((ix, m) in all.withIndex()) {
            if (tried >= MAX_TRIES) break
            val id = "omni|" + m
            if (ExtTranslate.blockedWhy(id, 0) != null) continue
            tried++
            try {
                val r = ExtLlm.chatWith(p, m, system, user, maxTokens)
                if (r.isBlank()) throw ExtLlm.LlmErr(502, "رد فاضي")
                ExtTranslate.clearBlock(id)
                if (ix > 0) ExtTranslate.lastNote = "🔄 أومني روت: اتحوّل لموديل «$m»"
                return r
            } catch (e: ExtLlm.LlmErr) {
                last = e
                when (e.code) {
                    401, 403 -> ExtTranslate.coolFor(id, 60_000L, "المفتاح مرفوض")
                    429, 402, 404, 500, 501, 502, 503, 504 -> ExtTranslate.noteFail(id, 0, e)
                    else -> ExtTranslate.coolFor(id, 300_000L, "الموديل رفض الطلب (" + e.code + ")")
                }
            } catch (e: java.io.IOException) {
                last = e
                ExtTranslate.coolFor(id, 120_000L, "مهلة/اتصال")
            }
        }
        throw last ?: ExtLlm.LlmErr(503, "كل موديلات أومني روت واقفة دلوقتي — هيتجرّبوا تاني بعد دقايق")
    }
}
