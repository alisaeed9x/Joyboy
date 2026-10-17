package com.tttt.subtitler

import org.json.JSONArray
import org.json.JSONObject

/**
 * (v379) 🆓 أوبن كود (OpenCode Zen) — مزوّد موديلات مجانية جوه التطبيق (من غير Termux ولا سيرفر).
 *  - بيشتغل في كل حتة: مشغّل الفيديو (الترجمة) · مشغّل الموسيقى (تحديد الأغاني) · 🤖 الذكاء الاصطناعي النصي · الراوتر الداخلي.
 *  - الموديلات المجانية بتتجاب لوحدها من GET /models (أي id فيه free أو big-pickle أو bunny) وبتتحدّث كل 10 دقايق،
 *    فلو أوبن كود بدّل القايمة التطبيق بيمشي معاها من غير تحديث. موديل وقع ← بيتجرّب اللي بعده (لحد 4 في الطلب).
 *  - المفتاح: [BUILTIN_KEY] لو حطيته هنا قبل البناء بيشتغل جاهز من غير ما تكتب حاجة في التطبيق؛ وإلا بيحاول من غير مفتاح.
 *  - لو أوبن كود رفض الطلب المجهول (FreeTierError) بتظهر رسالة واضحة بدل فشل غامض.
 */
object OpenCodeFree {
    /** حط مفتاح أوبن كود هنا (من opencode.ai) عشان يتبني جوه التطبيق. فاضي = محاولة من غير مفتاح */
    const val BUILTIN_KEY = ""
    const val ID = "opencode"
    const val BASE = "https://opencode.ai/zen/v1"
    private val FALLBACK = listOf("glm-5.3-flash-free", "big-pickle", "deepseek-v4.1-flash-free", "nemotron-3.5-super-vl-free", "mimo-v2.6-flash-free", "nemotron-3-ultra-free", "longcat-2.5-preview-free")
    private val PREFER = listOf("glm", "big-pickle", "deepseek", "minimax", "qwen", "mimo", "nemotron", "bunny")
    private const val MAX_TRIES = 4

    @Volatile private var found: List<String> = emptyList()
    @Volatile private var foundAt = 0L
    /** (v393) آخر قايمة موديلات جاية فعلًا من السيرفر/CLI؟ false = القايمة احتياطية جاهزة (أسماء مش مؤكدة) */
    @Volatile var lastListReal = true

    /** في مفتاح (محفوظ أو مبني) — بنستخدمه كاحتياطي نصي في الموسيقى بس لو فيه مفتاح فعلًا */
    fun hasKey(): Boolean = OpenCodeLocal.enabled() || ExtLlm.key(preset()).isNotEmpty() || BUILTIN_KEY.isNotEmpty()

    /** طلب نصي واحد بسيط (JSON/نص) — للمهام الصغيرة */
    fun ask(prompt: String, maxTokens: Int): String = chat("auto", "You are a precise assistant. Follow the output format exactly.", listOf(AiApi.Msg("user", prompt)), maxTokens)

    private fun preset(): ExtLlm.Preset = ExtLlm.byId(ID)!!

    fun headers(): Map<String, String> {
        val k = ExtLlm.key(preset()).ifEmpty { BUILTIN_KEY }.ifEmpty { "public" }
        return mapOf("Authorization" to "Bearer $k")
    }

    fun isFree(id: String): Boolean {
        val l = id.lowercase()
        return (l.contains("free") || l.contains("pickle") || l.contains("bunny")) && ExtLlm.skipWords.none { l.contains(it) }
    }

    /** (v393) الأنسب للترجمة الأول (بالسالب عشان sortedBy الصاعد) — بدل الترتيب الثابت القديم */
    private fun score(id: String): Int = -TranslatePick.score(id)

    /** قايمة الموديلات المجانية (بكاش 10 دقايق). لو الجلب فشل بنرجع لآخر قايمة أو للاحتياطي */
    fun list(force: Boolean = false): List<String> {
        // (v387) القايمة اللي التجهيز التلقائي جابها من opencode models (محلي) — بتتقدّم على جلب /models من السيرفر
        if (OpenCodeLocal.enabled()) {
            val loc = Cfg.str("oc_models").split(',').map { it.trim() }.filter { it.isNotEmpty() }.sortedBy { score(it) }
            if (loc.isNotEmpty()) { lastListReal = true; return loc }
        }
        val now = System.currentTimeMillis()
        if (!force && found.isNotEmpty() && now - foundAt < 600_000L) return found
        val ids = try {
            val txt = ExtLlm.post(BASE + "/models", headers(), "", 20000, "GET")
            val arr = JSONObject(txt).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id", "")?.takeIf { s -> s.isNotBlank() } }
        } catch (_: Throwable) { emptyList() }
        val free = ids.filter { isFree(it) }.sortedBy { score(it) }
        if (free.isNotEmpty()) { found = free; foundAt = now; lastListReal = true }
        else if (found.isEmpty()) lastListReal = false
        return found.ifEmpty { FALLBACK }
    }

    /** الموديلات اللي هتتجرّب بالترتيب: اللي اخترته (لو مش auto) ثم القايمة المجانية */
    private fun order(model: String): List<String> {
        val m = model.trim().removePrefix("$ID/")
        val pick = if (m.isEmpty() || m == "auto" || m.startsWith("auto/")) emptyList() else listOf(m)
        return (pick + list()).distinct()
    }

    private fun call(model: String, system: String, msgs: List<AiApi.Msg>, maxTokens: Int): String {
        if (OpenCodeLocal.enabled()) return OpenCodeLocal.ask(model, system, msgs)   // (v386) الفري بيشتغل بس من جوه OpenCode: بنعدّي على Termux
        val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in msgs) {
            val content: Any = if (m.imgB64 == null) m.text else JSONArray()
                .put(JSONObject().put("type", "text").put("text", m.text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + m.imgB64)))
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("temperature", 0.2).put("messages", arr).toString()
        val txt = try { ExtLlm.post(BASE + "/chat/completions", headers(), body, 120000) }
        catch (e: ExtLlm.LlmErr) {
            if ((e.message ?: "").contains("free tier", true))
                throw ExtLlm.LlmErr(401, "أوبن كود بيرفض الموديلات المجانية من أي تطبيق برا أداته (403 FreeTierError) — ده قيد منهم مش من مفتاحك. الحل: Termux + OpenCode CLI (🤖 ← 🩺 فحص شامل ← 🔧 إصلاح)، أو موديلات Zen المدفوعة. (opencode.ai)")
            throw e
        }
        val msg = JSONObject(txt).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        val c = msg.opt("content")
        return when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text", "") ?: "" }
            else -> ""
        }.trim()
    }

    /** مفتاح/موديل اتغيّر ← امسح كل فترات الإيقاف عشان يتجرّب من الأول */
    fun resetCool() {
        ExtTranslate.clearBlock("llm_$ID")
        for (m in (found + FALLBACK).distinct()) ExtTranslate.clearBlock("opencode|$m")
    }

    fun chat(model: String, system: String, msgs: List<AiApi.Msg>, maxTokens: Int): String {
        var last: Throwable? = null
        var tried = 0
        for ((ix, m) in order(model).withIndex()) {
            if (tried >= MAX_TRIES) break
            val id = "opencode|" + m
            if (ExtTranslate.blockedWhy(id, 0) != null) continue
            tried++
            try {
                val r = call(m, system, msgs, maxTokens)
                if (r.isBlank()) throw ExtLlm.LlmErr(502, "رد فاضي")
                ExtTranslate.clearBlock(id)
                if (ix > 0) ExtTranslate.lastNote = "🔄 أوبن كود: اتحوّل لموديل «$m»"
                return r
            } catch (e: ExtLlm.LlmErr) {
                last = e
                when (e.code) {
                    401, 403 -> { ExtTranslate.coolFor(id, 120_000L, e.message ?: "المفتاح مرفوض"); if ((e.message ?: "").let { it.contains("opencode.ai") || it.startsWith(OpenCodeLocal.FATAL) }) throw e }
                    429, 402, 404, 500, 501, 502, 503, 504 -> ExtTranslate.noteFail(id, 0, e)
                    else -> ExtTranslate.coolFor(id, 300_000L, "الموديل رفض الطلب (" + e.code + ")")
                }
            } catch (e: java.io.IOException) {
                last = e
                ExtTranslate.coolFor(id, 120_000L, "مهلة/اتصال")
            }
        }
        throw last ?: ExtLlm.LlmErr(503, "كل موديلات أوبن كود المجانية واقفة دلوقتي — هيتجرّبوا تاني بعد دقايق")
    }
}
