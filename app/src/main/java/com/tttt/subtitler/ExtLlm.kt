package com.tttt.subtitler

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * (v395) كل المحركات الخارجية (ChatGPT · Claude · Groq · OpenRouter · DeepSeek · Mistral · OmniRoute · OpenCode · مخصص) اتشالت.
 * التطبيق بيعتمد على جيميناي + جوجل ترانزليت بس. الكائن ده فاضي (مفيش مزوّدين) وباقي له اسم LlmErr + post
 * عشان LibreTranslate/ExtTranslate لسه بيستخدموهم.
 */
object ExtLlm {
    class Preset(val id: String, val label: String, val kind: String, val url: String, val model: String, val sttModel: String, val keys: List<String>)
    class LlmErr(val code: Int, msg: String, val retryAfterSec: Int = 0) : IOException(msg)

    val presets: List<Preset> = emptyList()
    fun byId(id: String): Preset? = null
    fun enabled(p: Preset): Boolean = false
    fun key(p: Preset): String = ""
    fun model(p: Preset): String = ""
    fun url(p: Preset): String = ""
    fun sttModel(p: Preset): String = ""
    fun hasAccess(p: Preset): Boolean = false
    fun configured(p: Preset): Boolean = false
    fun blockedWhy(p: Preset): String? = null
    fun provs(): List<ExtTranslate.Prov> = emptyList()
    fun convert(p: Preset, orig: List<String>, msa: List<String>, meta: List<String>, tgt: String, rules: String): List<String?> = emptyList()
    fun chat(p: Preset, system: String, user: String, maxTokens: Int): String = ""
    fun testTimed(p: Preset): Triple<Boolean, Long, String> = Triple(false, 0L, "")

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
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            NetUse.api((body?.length ?: 0).toLong() + 200L, txt)
            if (code !in 200..299) throw LlmErr(code, errMsg(code, txt), c.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 0)
            return txt
        } finally { try { c.disconnect() } catch (_: Throwable) {} }
    }
    fun post(url: String, headers: Map<String, String>, body: String, readMs: Int = 60000, method: String = "POST"): String = http(method, url, headers, if (method == "GET") null else body, readMs)

    /** رسالة خطأ مفهومة: 401/403 = مفتاح، 429 = كوتة، + أول سطر من رد الخدمة */
    fun errMsg(code: Int, body: String): String {
        val hint = when (code) { 401, 403 -> "المفتاح مرفوض"; 402 -> "الرصيد خلص"; 404 -> "الرابط أو الموديل غلط"; 429 -> "الكوتة/الحد خلص"; in 500..599 -> "الخدمة واقعة"; else -> "" }
        val detail = try { JSONObject(body).let { j -> j.optJSONObject("error")?.optString("message") ?: (j.opt("error") as? String) ?: j.optString("message") } } catch (_: Throwable) { null }
        return "HTTP $code" + (if (hint.isNotEmpty()) " ($hint)" else "") + (if (!detail.isNullOrBlank()) " — " + detail.take(80) else "")
    }
}
