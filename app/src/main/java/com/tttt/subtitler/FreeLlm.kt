package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * (v416) محركين ترجمة بالذكاء الصناعي بمفتاحك إنت (طبقة مجانية): Mistral و Cerebras.
 * الاتنين واجهتهم OpenAI-compatible (chat/completions). بيدخلوا سلسلة الترجمة (ExtTranslate) كمزوّدين دفعات:
 *  - بيترجموا دفعة (15–20 سطر) في طلب واحد مع آخر 3 أسطر من الدفعة اللي قبلها كسياق.
 *  - لو عندك أكتر من مفتاح بيلفّ عليهم، ولو مفتاح خد 429 بيوقفه دقيقة ويكمّل بالتاني.
 *  - الموديل بيتختار لوحده من قايمة الموديلات اللي مفتاحك شايفها (Cerebras بتشيل موديلات فجأة)، أو بتكتبه إنت.
 * مفيش أي موديل مدفوع بيتحط تلقائي، لكن الخطة (مجانية/مدفوعة) على حسابك إنت عند الشركة.
 */
object FreeLlm {
    class Eng(
        val id: String, val label: String, val icon: String,
        val chatUrl: String, val modelsUrl: String,
        val keyCfg: String, val modelCfg: String,
        val prefer: List<String>, val keyPage: String, val keyPageLabel: String,
        val chunk: Int, val gapMs: Long
    )

    val engines: List<Eng> = listOf(
        Eng("mistral", "Mistral", "🌬",
            "https://api.mistral.ai/v1/chat/completions", "https://api.mistral.ai/v1/models",
            "mistral_keys", "mistral_model",
            listOf("mistral-small-latest", "mistral-medium-latest", "mistral-large-latest", "open-mistral-nemo"),
            "https://console.mistral.ai/api-keys", "console.mistral.ai", 15, 1300L),
        Eng("cerebras", "Cerebras", "🧠",
            "https://api.cerebras.ai/v1/chat/completions", "https://api.cerebras.ai/v1/models",
            "cerebras_keys", "cerebras_model",
            listOf("gpt-oss-120b", "qwen-3-235b-a22b-instruct-2507", "llama-3.3-70b", "llama3.1-8b"),
            "https://cloud.cerebras.ai/", "cloud.cerebras.ai", 20, 600L)
    )

    fun eng(id: String): Eng? = engines.firstOrNull { it.id == id }
    fun isLlm(id: String): Boolean = engines.any { it.id == id }
    fun keys(e: Eng): List<String> = Cfg.keys(e.keyCfg)
    fun hasKey(e: Eng): Boolean = keys(e).isNotEmpty()

    /** آخر موديل اشتغل فعلًا لكل محرك (للعرض في زرار التجربة) */
    val usedModel = ConcurrentHashMap<String, String>()

    /** المحرك اللي يترجم الأول: llm_first = auto (أول واحد عنده مفتاح) | mistral | cerebras | google (جوجل الأول). فاضي = مفيش */
    fun firstId(): String {
        val v = Cfg.str("llm_first", "auto")
        if (v == "google") return ""
        val ready = engines.filter { hasKey(it) && Cfg.str("ext_on_" + it.id, "on") != "off" }
        if (v == "mistral" || v == "cerebras") return if (ready.any { it.id == v }) v else ""
        return ready.firstOrNull()?.id ?: ""
    }

    fun provs(): List<ExtTranslate.Prov> = engines.filter { hasKey(it) }.map { prov(it) }

    fun prov(e: Eng): ExtTranslate.Prov =
        ExtTranslate.Prov(e.id, e.label, listOf(e.id), 0) { _, _ -> null }.also { pv ->
            pv.keyHash = { Cfg.str(e.keyCfg).hashCode() }
            pv.chunk = e.chunk
            pv.gapMs = e.gapMs
            pv.batch = { texts, sl -> translate(e, texts, sl) }
        }

    // ===== مفاتيح: لفّ + إيقاف مؤقت =====
    private val keyCool = ConcurrentHashMap<String, Long>()
    private val keyBad = ConcurrentHashMap<String, Boolean>()
    private val rr = AtomicInteger(0)

    // ===== الموديل =====
    private class ModelPick(val want: String, val pick: String, val at: Long)
    private val modelCache = ConcurrentHashMap<String, ModelPick>()

    fun listModels(e: Eng, key: String): List<String> {
        val txt = ExtLlm.post(e.modelsUrl, mapOf("Authorization" to "Bearer $key"), "", 15000, "GET")
        val arr = JSONObject(txt).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isBlank()) continue
            val low = id.lowercase()
            if (low.contains("embed") || low.contains("moderation") || low.contains("ocr") || low.contains("whisper") || low.contains("voxtral")) continue
            val cap = o.optJSONObject("capabilities")
            if (cap != null && cap.has("completion_chat") && !cap.optBoolean("completion_chat", true)) continue
            if (id !in out) out.add(id)
        }
        return out
    }

    private fun resolveModel(e: Eng, key: String, force: Boolean = false): String {
        val want = Cfg.str(e.modelCfg).trim()
        val now = System.currentTimeMillis()
        val c = modelCache[e.id]
        if (!force && c != null && c.want == want && now - c.at < 6 * 3_600_000L) return c.pick
        var ids: List<String> = emptyList()
        try { ids = listModels(e, key) }
        catch (x: ExtLlm.LlmErr) { if (x.code == 401 || x.code == 403 || x.code == 429) throw x }
        catch (_: Exception) {}
        val pick = when {
            ids.isEmpty() -> if (want.isNotEmpty()) want else e.prefer.first()
            want.isNotEmpty() && want in ids -> want
            else -> e.prefer.firstOrNull { it in ids } ?: ids.first()
        }
        if (want.isNotEmpty() && ids.isNotEmpty() && pick != want) ExtTranslate.lastNote = "🔄 موديل ${e.label} «$want» مش متاح على مفتاحك — اتحوّل لـ «$pick»"
        modelCache[e.id] = ModelPick(want, pick, now)
        usedModel[e.id] = pick
        return pick
    }

    // ===== طلب واحد =====
    private fun contentOf(txt: String): String {
        val c = JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val s: String = when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text", "") ?: "" }
            else -> ""
        }
        return s.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    private fun chatOnce(e: Eng, key: String, system: String, user: String, maxTok: Int): String {
        var model = resolveModel(e, key)
        for (attempt in 0..3) {
            val body = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)))
            if (e.id == "mistral") {
                body.put("max_tokens", maxTok)
                body.put("response_format", JSONObject().put("type", "json_object"))
            } else {
                body.put("max_completion_tokens", maxTok)
                if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
            }
            try {
                val txt = ExtLlm.post(e.chatUrl, mapOf("Authorization" to "Bearer $key", "Accept" to "application/json"), body.toString(), 90000)
                usedModel[e.id] = model
                return contentOf(txt)
            } catch (x: ExtLlm.LlmErr) {
                if (x.code == 404 && attempt == 0) { modelCache.remove(e.id); model = resolveModel(e, key, true); continue }   // الموديل اتشال
                if (x.code == 429 && attempt < 2 && x.retryAfterSec <= 15) {   // حد في الثانية/الدقيقة: نستنى ثانيتين ونعيد مرة
                    Thread.sleep((if (x.retryAfterSec > 0) x.retryAfterSec else 2) * 1000L); continue
                }
                throw x
            }
        }
        throw ExtLlm.LlmErr(500, "${e.label}: فشل الطلب")
    }

    /** بيلفّ على المفاتيح: 401/403 = المفتاح بايظ (بيتتخطى)، 429 = دقيقة راحة للمفتاح ده، غير كده بيرمي الخطأ */
    internal fun chat(e: Eng, system: String, user: String, maxTok: Int): String {   // (v418) internal عشان VideoLingo.kt
        val all = keys(e)
        if (all.isEmpty()) throw ExtLlm.LlmErr(401, "مفيش مفتاح ${e.label}")
        val start = rr.getAndIncrement()
        var lastErr: ExtLlm.LlmErr? = null
        for (i in all.indices) {
            val k = all[Math.floorMod(start + i, all.size)]
            if (keyBad[k] == true) continue
            if ((keyCool[k] ?: 0L) > System.currentTimeMillis()) continue
            try { return chatOnce(e, k, system, user, maxTok) }
            catch (x: ExtLlm.LlmErr) {
                lastErr = x
                when (x.code) {
                    401, 403 -> keyBad[k] = true
                    429 -> keyCool[k] = System.currentTimeMillis() + (if (x.retryAfterSec > 0) x.retryAfterSec.coerceIn(5, 3600) else 60) * 1000L
                    else -> throw x
                }
            }
        }
        if (lastErr != null) throw lastErr
        if (all.all { keyBad[it] == true }) throw ExtLlm.LlmErr(401, "كل مفاتيح ${e.label} مرفوضة — غيّرها")
        throw ExtLlm.LlmErr(429, "كل مفاتيح ${e.label} واقفة مؤقتًا (حد الاستخدام)", 60)
    }

    // ===== الترجمة =====
    private class Ctx(val src: List<String>, val tr: List<String>, val at: Long)
    private val ctxMap = ConcurrentHashMap<String, Ctx>()

    private fun langName(sl: String): String = when (sl) {
        "en" -> "English"; "ja" -> "Japanese"; "ko" -> "Korean"; "zh", "zh-CN" -> "Chinese"; "ru" -> "Russian"
        "fr" -> "French"; "es" -> "Spanish"; "de" -> "German"; "it" -> "Italian"; "tr" -> "Turkish"
        "hi" -> "Hindi"; "pt" -> "Portuguese"; "auto", "" -> "the source language (auto-detect)"; else -> sl
    }

    private fun firstArray(j: JSONObject): JSONArray? {
        val ks = j.keys()
        while (ks.hasNext()) { val v = j.opt(ks.next()); if (v is JSONArray) return v }
        return null
    }

    private fun parseList(raw: String): List<String>? {
        val s = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try {
            val a = s.indexOf('{'); val b = s.lastIndexOf('}')
            if (a >= 0 && b > a) {
                val j = JSONObject(s.substring(a, b + 1))
                val arr = j.optJSONArray("t") ?: j.optJSONArray("translations") ?: firstArray(j)
                if (arr != null) return List(arr.length()) { arr.optString(it, "") }
            }
        } catch (_: Exception) {}
        try {
            val a = s.indexOf('['); val b = s.lastIndexOf(']')
            if (a >= 0 && b > a) { val arr = JSONArray(s.substring(a, b + 1)); return List(arr.length()) { arr.optString(it, "") } }
        } catch (_: Exception) {}
        return null
    }

    /** ترجمة دفعة سطور للعربي. بيرجّع نفس الطول (الفاضي = null). بيرمي ExtLlm.LlmErr لو المفاتيح/الحد/الخدمة وقفت */
    fun translate(e: Eng, texts: List<String>, sl: String): List<String?> {
        if (texts.isEmpty()) return emptyList()
        if (VideoLingo.on()) return VideoLingo.translate(e, texts, sl)   // (v418) خطوات فيديو لينجو (مصري)
        val egy = Cfg.str("llm_dialect", "msa") == "egy"
        val sys = "You are a professional subtitle translator. Translate every input line from ${langName(sl)} into " +
            (if (egy) "natural spoken Egyptian Arabic (اللهجة المصرية العامية), the way a native Egyptian would say it"
            else "clear, natural Modern Standard Arabic (فصحى بسيطة), idiomatic and never word-for-word") +
            ". Rules: keep the meaning, tone and speaker gender; keep names, numbers and brand names; never merge, split, skip or add lines; " +
            "keep each line short like a subtitle; do not censor; do not explain. " +
            "Use previous_lines only as context. Reply with ONLY a JSON object: {\"t\":[...]} holding exactly ${texts.size} Arabic strings in the same order."
        val now = System.currentTimeMillis()
        val c = ctxMap[e.id]
        val prev = if (c != null && now - c.at < 120_000L) c else null
        val prevArr = JSONArray()
        if (prev != null) for (i in prev.src.indices) prevArr.put(JSONObject().put("src", prev.src[i]).put("ar", prev.tr[i]))
        val user = JSONObject()
            .put("previous_lines", prevArr)
            .put("translate", JSONArray(texts.map { it.replace('\n', ' ').trim() }))
            .toString()
        val maxTok = (texts.sumOf { it.length } * 3 + 600).coerceIn(800, 6000)
        var out: List<String>? = null
        for (attempt in 0..1) {
            val l = parseList(chat(e, sys, user, maxTok))
            if (l != null && l.size == texts.size) { out = l; break }
        }
        val o = out ?: throw Exception("${e.label}: الرد مش بنفس عدد الأسطر")
        val res: List<String?> = o.map { s -> s.trim().takeIf { it.isNotEmpty() } }
        val okIdx = texts.indices.filter { res[it] != null }.takeLast(3)
        ctxMap[e.id] = Ctx(okIdx.map { texts[it] }, okIdx.map { res[it] ?: "" }, now)
        return res
    }

    /** تجربة محرك بجملة واحدة: (نجح؟، سطر نتيجة للعرض) */
    fun test(e: Eng): Pair<Boolean, String> {
        if (!hasKey(e)) return false to "حط مفتاح ${e.label} الأول"
        keyBad.clear(); keyCool.clear(); modelCache.remove(e.id)
        val t0 = System.currentTimeMillis()
        return try {
            val r = translate(e, listOf("Hello, how are you today?"), "en").firstOrNull()
            val ms = System.currentTimeMillis() - t0
            if (r.isNullOrBlank()) false to "${e.label}: ما رجّعش ترجمة"
            else { ExtTranslate.clearBlock(e.id); true to "✅ ${e.label} شغّال · " + (usedModel[e.id] ?: "") + " · ${ms}ms\n«" + r.take(60) + "»" }
        } catch (x: Exception) { false to "❌ ${e.label}: " + (x.message ?: x.javaClass.simpleName).take(140) }
    }
}
